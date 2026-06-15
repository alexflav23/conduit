#!/usr/bin/env bash
# Enable + configure the Google identity provider in the local `conduit` realm. The realm import ships the IdP
# DISABLED with empty creds (so no secret is committed); this script fills it in at runtime from the gitignored
# .env, idempotently. Run it after `docker compose up` whenever Keycloak has been (re)created — the dev container
# is ephemeral, so a fresh boot re-imports the realm and this restores federation.
#
#   ./keycloak/configure-google-idp.sh
#
# Requires GOOGLE_OAUTH_CLIENT_ID + GOOGLE_OAUTH_CLIENT_SECRET in .env (or the environment). The redirect URI
#   http://localhost:8083/realms/conduit/broker/google/endpoint
# must be registered on the Google OAuth client (Authorized redirect URIs).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[ -f "$ROOT/.env" ] && set -a && . "$ROOT/.env" && set +a

: "${GOOGLE_OAUTH_CLIENT_ID:?set GOOGLE_OAUTH_CLIENT_ID in .env}"
: "${GOOGLE_OAUTH_CLIENT_SECRET:?set GOOGLE_OAUTH_CLIENT_SECRET in .env}"

KC=conduit-keycloak
KCADM=/opt/keycloak/bin/kcadm.sh

# kcadm runs INSIDE the container so it talks to Keycloak over loopback (HTTP allowed; the master realm rejects
# plain HTTP over the bridge).
docker exec "$KC" "$KCADM" config credentials --server http://localhost:8083 --realm master --user admin --password "${KEYCLOAK_ADMIN_PASSWORD:-admin}"
docker exec "$KC" "$KCADM" update identity-provider/instances/google -r conduit \
  -s enabled=true -s trustEmail=true \
  -s "config.clientId=$GOOGLE_OAUTH_CLIENT_ID" \
  -s "config.clientSecret=$GOOGLE_OAUTH_CLIENT_SECRET" \
  -s "config.hostedDomain=hypervolt.co.uk" \
  -s "config.useJwksUrl=true" \
  -s "config.syncMode=FORCE" \
  -s "config.defaultScope=openid email profile"

# Seamless SSO: skip the one-time "Review profile" page when Keycloak first creates a federated user (we trust
# the Workspace email). Idempotent.
REVIEW_ID="$(docker exec "$KC" "$KCADM" get 'authentication/flows/first%20broker%20login/executions' -r conduit \
  | python3 -c "import json,sys;print(next((e['id'] for e in json.load(sys.stdin) if e.get('displayName')=='Review Profile'),''))")"
if [ -n "$REVIEW_ID" ]; then
  docker exec "$KC" "$KCADM" update 'authentication/flows/first%20broker%20login/executions' -r conduit \
    -b "{\"id\":\"$REVIEW_ID\",\"requirement\":\"DISABLED\"}"
fi

echo "✓ Google IdP enabled on the conduit realm (straight-to-Google, no profile review)."
