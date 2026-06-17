#!/usr/bin/env python3
"""Download the ENTIRE HubSpot contact set → ingest/hubspot/contacts.ndjson.

One record per contact with the identity fields needed for master-account correlation: email, name, phone,
free-text company, job title, and the associated HubSpot company id (the deterministic contact→company link).
Paginates the full object set (no search filter). Reusable + idempotent (overwrites the ndjson).

Token: env HUBSPOT_TOKEN (e.g. `export HUBSPOT_TOKEN=$(aws ssm get-parameter --name /prod/athena/hubspot \
  --with-decryption --region eu-west-1 --query Parameter.Value --output text)`).
"""
import json
import os
import sys
import time
import urllib.request
import urllib.error

TOKEN = os.environ.get("HUBSPOT_TOKEN", "").strip()
if not TOKEN:
    sys.exit("set HUBSPOT_TOKEN")

OUT = os.path.join(os.path.dirname(__file__), "..", "ingest", "hubspot", "contacts.ndjson")
PROPS = "email,firstname,lastname,phone,company,jobtitle,associatedcompanyid,createdate,lifecyclestage"


def api(path):
    req = urllib.request.Request("https://api.hubapi.com" + path, method="GET")
    req.add_header("Authorization", "Bearer " + TOKEN)
    for attempt in range(6):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code == 429 or e.code >= 500:
                time.sleep(2 * (attempt + 1))
                continue
            sys.stderr.write(f"HTTP {e.code} on {path}: {e.read()[:200]}\n")
            raise
    raise RuntimeError("retries exhausted: " + path)


def main():
    after = None
    n = 0
    with open(OUT, "w") as f:
        while True:
            q = f"/crm/v3/objects/contacts?limit=100&properties={PROPS}"
            if after:
                q += f"&after={after}"
            d = api(q)
            for r in d.get("results", []):
                p = r.get("properties", {})
                f.write(
                    json.dumps(
                        {
                            "contact_id": r["id"],
                            "email": p.get("email"),
                            "first_name": p.get("firstname"),
                            "last_name": p.get("lastname"),
                            "phone": p.get("phone"),
                            "company": p.get("company"),
                            "job_title": p.get("jobtitle"),
                            "company_id": p.get("associatedcompanyid"),
                            "lifecycle": p.get("lifecyclestage"),
                            "created": (p.get("createdate") or "")[:10] or None,
                        }
                    )
                    + "\n"
                )
                n += 1
            after = d.get("paging", {}).get("next", {}).get("after")
            if n % 10000 == 0:
                sys.stderr.write(f"  …{n} contacts\n")
            if not after:
                break
    sys.stderr.write(f"wrote {n} contacts → {OUT}\n")


if __name__ == "__main__":
    main()
