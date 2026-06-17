#!/usr/bin/env python3
"""Resolve serial → owner (email) from the Athena placement registry + prod Keycloak.

Chain: serial(hex) = deviceID(int) → PlacementDevice → Placement.keycloakUserId → Keycloak retail-customers user
(email + name). Produces ingest/placements/serial_owner.ndjson — the serial↔owner bridge that completes the
genealogy and lets owner accounts materialise by email (reconciled with HubSpot consumer contacts).

Auth: AWS CLI creds (DynamoDB scans) + KEYCLOAK_CLIENT_SECRET (athena-api-sa, from
SSM /prod/keycloak-configuration/athena-api/service-account). Reusable + idempotent (overwrites the ndjson).
"""
import json
import os
import subprocess
import sys
import time
import urllib.request
import urllib.parse

REGION = "eu-west-1"
KC = "https://kc.prod.hypervolt.co.uk"
REALM = "retail-customers"
CLIENT = "athena-api-sa"
SECRET = os.environ.get("KEYCLOAK_CLIENT_SECRET", "").strip()
if not SECRET:
    sys.exit("set KEYCLOAK_CLIENT_SECRET")
OUT = os.path.join(os.path.dirname(__file__), "..", "ingest", "placements", "serial_owner.ndjson")


def ddb_scan(table, proj, names=None):
    """Manual-paginated scan; returns list of raw DynamoDB item dicts."""
    items, token = [], None
    while True:
        cmd = ["aws", "dynamodb", "scan", "--region", REGION, "--table-name", table,
               "--projection-expression", proj, "--max-items", "8000", "--output", "json"]
        if names:
            cmd += ["--expression-attribute-names", json.dumps(names)]
        if token:
            cmd += ["--starting-token", token]
        out = subprocess.run(cmd, capture_output=True, text=True)
        if out.returncode != 0:
            sys.stderr.write(f"scan {table} failed: {out.stderr[:200]}\n"); raise SystemExit(1)
        d = json.loads(out.stdout)
        items.extend(d.get("Items", []))
        token = d.get("NextToken")
        sys.stderr.write(f"  {table}: {len(items)}\n")
        if not token:
            return items


def kc_token():
    body = urllib.parse.urlencode(
        {"grant_type": "client_credentials", "client_id": CLIENT, "client_secret": SECRET, "scope": "email"}
    ).encode()
    req = urllib.request.Request(f"{KC}/realms/{REALM}/protocol/openid-connect/token", data=body, method="POST")
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)["access_token"]


def kc_users():
    """Bulk-export id→(email,name) for the whole realm, paginated, refreshing the token."""
    users, first, tok = {}, 0, kc_token()
    while True:
        q = urllib.parse.urlencode({"first": first, "max": 100, "briefRepresentation": "true"})
        req = urllib.request.Request(f"{KC}/admin/realms/{REALM}/users?{q}")
        req.add_header("Authorization", "Bearer " + tok)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                page = json.load(r)
        except urllib.error.HTTPError as e:
            if e.code == 401:
                tok = kc_token(); continue
            raise
        if not page:
            break
        for u in page:
            nm = " ".join(x for x in [u.get("firstName"), u.get("lastName")] if x).strip()
            users[u["id"]] = (u.get("email"), nm or None)
        first += len(page)
        if first % 10000 == 0:
            sys.stderr.write(f"  users: {first}\n"); tok = kc_token()
        if len(page) < 100:
            break
    sys.stderr.write(f"users exported: {len(users)}\n")
    return users


def main():
    sys.stderr.write("scanning PlacementDevice…\n")
    devs = ddb_scan("PlacementDevice", "deviceID, placementId")
    dev2plac = {it["deviceID"]["N"]: it["placementId"]["S"] for it in devs if "deviceID" in it and "placementId" in it}
    sys.stderr.write(f"devices: {len(dev2plac)}\nscanning Placement…\n")
    placs = ddb_scan("Placement", "id, keycloakUserId, #n, #c", {"#n": "name", "#c": "country"})
    plac2owner = {it["id"]["S"]: (it.get("keycloakUserId", {}).get("S"),
                                  it.get("name", {}).get("S"), it.get("country", {}).get("S"))
                  for it in placs if "id" in it}
    sys.stderr.write(f"placements: {len(plac2owner)}\nexporting Keycloak users…\n")
    users = kc_users()

    n, withemail = 0, 0
    with open(OUT, "w") as f:
        for did, pid in dev2plac.items():
            owner = plac2owner.get(pid)
            if not owner or not owner[0]:
                continue
            kc_id, plac_name, country = owner
            email, name = users.get(kc_id, (None, None))
            serial = format(int(did), "016x")
            f.write(json.dumps({
                "serial": serial, "device_id": did, "placement_id": pid, "keycloak_user_id": kc_id,
                "owner_email": email, "owner_name": name, "placement_name": plac_name, "country": country,
            }) + "\n")
            n += 1
            if email:
                withemail += 1
    sys.stderr.write(f"wrote {n} serial→owner rows ({withemail} with email) → {OUT}\n")


if __name__ == "__main__":
    main()
