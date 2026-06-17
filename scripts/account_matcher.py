#!/usr/bin/env python3
"""Model + order-history matcher (Anthropic Message Batches API).

The fuzzy candidates collapse to ~1,857 HubSpot companies, each with candidate MRPeasy trading accounts. One batch
request per company asks the model (name + domain + each candidate's order history) which candidate, if any, is the
SAME real-world trading entity — order count is the "real account" signal; junk (AAA/test) is rejected. Uses the
Batch API (50% cheaper, async) with a capable model. Verdicts → ingest/hubspot/account_match_verdicts.ndjson
(committed); ignition applies confidence>=0.9 with full lineage. Reproducible: boot never calls the API.

Env: ANTHROPIC_API_KEY (SSM /staging/kinetic/anthropic). PGdocker exec for psql.
"""
import json
import os
import subprocess
import sys
import time
import urllib.request
import urllib.error

KEY = os.environ.get("ANTHROPIC_API_KEY", "").strip()
if not KEY:
    sys.exit("set ANTHROPIC_API_KEY")
MODEL = os.environ.get("MATCH_MODEL", "claude-sonnet-4-6")
OUT = os.path.join(os.path.dirname(__file__), "..", "ingest", "hubspot", "account_match_verdicts.ndjson")
MAX_CANDS = 12
PSQL = ["docker", "exec", "conduit-postgres", "psql", "-U", "conduit", "-d", "conduit", "-tAc"]

CANDIDATE_SQL = r"""
WITH ranked AS (
  SELECT c.source_id, c.party_id, c.source_name, c.score,
         row_number() OVER (PARTITION BY c.source_id ORDER BY c.score DESC, c.party_id) AS rn
  FROM account_link_candidate c WHERE c.status = 'pending')
SELECT jsonb_build_object(
  'hs_company_id', r.source_id, 'hs_name', max(r.source_name), 'hs_domain', max(hcr.domain),
  'candidates', jsonb_agg(jsonb_build_object('party_id', r.party_id::text,
     'name', regexp_replace(p.display_name,'^MRP:\s*',''),
     'orders', (SELECT count(*) FROM "order" o WHERE o.sold_to_party_id = r.party_id)) ORDER BY r.score DESC))
FROM ranked r JOIN party p ON p.id = r.party_id
LEFT JOIN hubspot_company_raw hcr ON hcr.company_id = r.source_id
WHERE r.rn <= %d GROUP BY r.source_id
""" % MAX_CANDS

PROMPT = """Entity resolution for a customer master. Below is a HubSpot company and candidate MRPeasy trading \
accounts (accounts that placed real orders). Decide if the company is the SAME real-world trading entity as ONE \
candidate.
Rules: the `orders` count signals a real account; ignore Ltd/Limited, punctuation, duplicated words and \
branch/location suffixes; be CONSERVATIVE (confidence >= 0.9 ONLY if clearly the same business); reject junk/test \
data (AAA, test, single letters) with null.
Return ONLY a JSON object, no prose: {"merge_into_party_id":"<party_id or null>","confidence":<0..1>,"reason":"<short>"}

%s"""


def api(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request("https://api.anthropic.com" + path, data=data, method=method)
    req.add_header("x-api-key", KEY)
    req.add_header("anthropic-version", "2023-06-01")
    req.add_header("content-type", "application/json")
    for attempt in range(6):
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code in (429, 529) or e.code >= 500:
                time.sleep(3 * (attempt + 1))
                continue
            sys.stderr.write(f"HTTP {e.code} {path}: {e.read()[:300]}\n")
            raise
    raise RuntimeError("retries exhausted")


def load_companies():
    out = subprocess.run(PSQL + [CANDIDATE_SQL], capture_output=True, text=True, check=True).stdout
    return [json.loads(l) for l in out.splitlines() if l.strip()]


def main():
    companies = load_companies()
    sys.stderr.write(f"{len(companies)} companies → building batch\n")
    requests = []
    for c in companies:
        block = f"HubSpot company: {c.get('hs_name')} (domain {c.get('hs_domain') or '?'})\nCandidates:\n" + "\n".join(
            f"- party_id={x['party_id']} name=\"{x['name']}\" orders={x['orders']}" for x in (c.get("candidates") or [])
        )
        requests.append({
            "custom_id": c["hs_company_id"],
            "params": {"model": MODEL, "max_tokens": 300,
                       "messages": [{"role": "user", "content": PROMPT % block}]},
        })

    created = json.loads(api("POST", "/v1/messages/batches", {"requests": requests}))
    bid = created["id"]
    sys.stderr.write(f"batch {bid} submitted ({len(requests)} requests)\n")

    while True:
        st = json.loads(api("GET", f"/v1/messages/batches/{bid}"))
        counts = st.get("request_counts", {})
        sys.stderr.write(f"  status={st['processing_status']} {counts}\n")
        if st["processing_status"] == "ended":
            results_url = st["results_url"]
            break
        time.sleep(20)

    # results_url is a full URL; fetch with auth
    req = urllib.request.Request(results_url, method="GET")
    req.add_header("x-api-key", KEY)
    req.add_header("anthropic-version", "2023-06-01")
    with urllib.request.urlopen(req, timeout=180) as r:
        lines = r.read().decode().splitlines()

    n = 0
    with open(OUT, "w") as f:
        for line in lines:
            if not line.strip():
                continue
            rec = json.loads(line)
            cid = rec["custom_id"]
            res = rec.get("result", {})
            if res.get("type") != "succeeded":
                continue
            text = res["message"]["content"][0]["text"].strip()
            if text.startswith("```"):
                text = text.split("```")[1].lstrip("json").strip()
            try:
                v = json.loads(text)
            except Exception:
                continue
            f.write(json.dumps({"hs_company_id": cid, "merge_into_party_id": v.get("merge_into_party_id"),
                                "confidence": v.get("confidence"), "reason": v.get("reason"), "model": MODEL}) + "\n")
            n += 1
    merges = 0
    for line in open(OUT):
        v = json.loads(line)
        if v.get("merge_into_party_id") and float(v.get("confidence") or 0) >= 0.9:
            merges += 1
    sys.stderr.write(f"wrote {n} verdicts ({merges} auto-merge >=0.9) → {OUT}\n")


if __name__ == "__main__":
    main()
