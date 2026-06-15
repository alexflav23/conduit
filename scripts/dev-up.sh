#!/usr/bin/env bash
# One-command local bring-up. Brings the stack up, waits for Keycloak to import the realm, and (if the Google
# secret is available) enables federation. Everything else is automatic: Keycloak imports the `conduit` realm,
# the API runs Flyway migrations + the historical-data ingest (ingest/*.ndjson) on boot.
#
#   ./scripts/dev-up.sh
#
# Secrets come from direnv/SSM (preferred) or a local .env — never committed. Without the Google secret the
# stack still runs; you just sign in with the local Keycloak test user instead of Google.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

echo "→ docker compose up"
docker compose up -d

echo "→ waiting for the Keycloak realm…"
until curl -sf http://localhost:8083/realms/conduit/.well-known/openid-configuration >/dev/null 2>&1; do sleep 2; done

[ -f .env ] && set -a && . ./.env && set +a
if [ -n "${GOOGLE_OAUTH_CLIENT_SECRET:-}" ]; then
  echo "→ configuring Google federation"
  ./keycloak/configure-google-idp.sh
else
  echo "→ no GOOGLE_OAUTH_CLIENT_SECRET — skipping Google federation (sign in with the Keycloak test user)"
fi

echo "→ waiting for the API…"
until curl -sf http://localhost:9990/health >/dev/null 2>&1; do sleep 2; done

echo "✓ stack up. Desk: (cd conduit-desk && yarn && yarn start) → http://localhost:3060"
