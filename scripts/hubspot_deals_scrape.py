#!/usr/bin/env python3
"""Scrape ALL HubSpot deals WITH their company attribution → ingest/hubspot/deals_attributed.ndjson.

The committed deals_lifecycle.ndjson carries pipeline + amount + dates but NO company link, so deals are
only attributable at the pipeline (segment) level, never to the actual installer/wholesaler that placed them.
This pulls each deal's primary company association inline (associations=companies) and resolves the company
name in batch, emitting one attributed record per deal. Reusable + idempotent (overwrites the ndjson).

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

OUT = os.path.join(os.path.dirname(__file__), "..", "ingest", "hubspot", "deals_attributed.ndjson")

# pipeline id -> (label, segment). Unknown pipelines fall through to (id, 'other').
PIPELINES = {
    "default": ("UK Installers", "installer"),
    "12284392": ("New Installer Sign Up", "installer"),
    "23872899": ("New Installer Sign Up Outbound", "installer"),
    "46423619": ("Staging - UK Installers", "installer"),
    "124101508": ("AUSTRALIA - Installer sign up", "installer"),
    "12283119": ("UK Distributors & Wholesalers", "wholesaler"),
    "3088658": ("Distributors", "wholesaler"),
    "12283103": ("UK Retail", "retail"),
    "27384693": ("UK Retail - Direct", "retail"),
    "1153815": ("Staging - UK Retail", "retail"),
    "44037918": ("Staging - UK Retail - Direct", "retail"),
    "701692972": ("UK Energy Retail", "retail"),
    "5520760": ("UK Automotive", "automotive"),
    "25767051": ("Commercial", "commercial"),
    "145494818": ("Enterprise", "enterprise"),
    "15638247": ("International", "international"),
    "120019894": ("Australia", "international"),
    "12214656": ("Investors", "other"),
    "696570664": ("BD - Partnerships", "other"),
    "88387735": ("CC", "other"),
}


def api(method, path, body=None):
    url = "https://api.hubapi.com" + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    req.add_header("Content-Type", "application/json")
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


def page_deals():
    props = "dealname,amount,pipeline,dealstage,createdate,closedate,hs_is_closed_won,hs_is_closed,deal_currency_code"
    after = None
    while True:
        q = f"/crm/v3/objects/deals?limit=100&associations=companies&properties={props}"
        if after:
            q += f"&after={after}"
        d = api("GET", q)
        for r in d.get("results", []):
            yield r
        after = d.get("paging", {}).get("next", {}).get("after")
        if not after:
            break


def resolve_company_names(ids):
    names = {}
    ids = list(ids)
    for i in range(0, len(ids), 100):
        chunk = ids[i : i + 100]
        d = api(
            "POST",
            "/crm/v3/objects/companies/batch/read",
            {"properties": ["name"], "inputs": [{"id": c} for c in chunk]},
        )
        for r in d.get("results", []):
            names[r["id"]] = r.get("properties", {}).get("name")
    return names


def main():
    deals = []
    company_ids = set()
    n = 0
    for r in page_deals():
        p = r.get("properties", {})
        assoc = (r.get("associations", {}).get("companies", {}) or {}).get("results", [])
        company_id = assoc[0]["id"] if assoc else None
        if company_id:
            company_ids.add(company_id)
        pid = p.get("pipeline") or ""
        label, segment = PIPELINES.get(pid, (pid, "other"))
        deals.append(
            {
                "deal_id": r["id"],
                "pipeline": label,
                "segment": segment,
                "created": (p.get("createdate") or "")[:10] or None,
                "closed": (p.get("closedate") or "")[:10] or None,
                "won": p.get("hs_is_closed_won") == "true",
                "is_closed": p.get("hs_is_closed") == "true",
                "amount": p.get("amount"),
                "ccy": p.get("deal_currency_code") or "GBP",
                "dealname": p.get("dealname"),
                "company_id": company_id,
            }
        )
        n += 1
        if n % 5000 == 0:
            sys.stderr.write(f"  …{n} deals\n")
    sys.stderr.write(f"deals={n} companies={len(company_ids)} — resolving names\n")
    names = resolve_company_names(company_ids)
    with open(OUT, "w") as f:
        for d in deals:
            d["company_name"] = names.get(d["company_id"]) if d["company_id"] else None
            f.write(json.dumps(d) + "\n")
    attributed = sum(1 for d in deals if d["company_id"])
    sys.stderr.write(f"wrote {n} deals ({attributed} attributed to a company) → {OUT}\n")


if __name__ == "__main__":
    main()
