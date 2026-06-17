#!/usr/bin/env python3
"""Download the full HubSpot companies object → ingest/hubspot/companies.ndjson.

Deals only reference ~3k companies, but contacts span ~25k. This pulls every company with its canonical name +
domain so the master-account correlation has a name for every company_id a contact points at (and thus can
attribute every B2B contact). Reusable + idempotent (overwrites the ndjson).

Token: env HUBSPOT_TOKEN.
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

OUT = os.path.join(os.path.dirname(__file__), "..", "ingest", "hubspot", "companies.ndjson")
PROPS = "name,domain,industry,country,hs_object_id"


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
            q = f"/crm/v3/objects/companies?limit=100&properties={PROPS}"
            if after:
                q += f"&after={after}"
            d = api(q)
            for r in d.get("results", []):
                p = r.get("properties", {})
                f.write(
                    json.dumps(
                        {
                            "company_id": r["id"],
                            "name": p.get("name"),
                            "domain": p.get("domain"),
                            "industry": p.get("industry"),
                            "country": p.get("country"),
                        }
                    )
                    + "\n"
                )
                n += 1
            after = d.get("paging", {}).get("next", {}).get("after")
            if n % 5000 == 0:
                sys.stderr.write(f"  …{n} companies\n")
            if not after:
                break
    sys.stderr.write(f"wrote {n} companies → {OUT}\n")


if __name__ == "__main__":
    main()
