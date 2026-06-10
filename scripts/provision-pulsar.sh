#!/usr/bin/env bash
# Idempotent Pulsar provisioning for Conduit (run at deploy — topics are NOT in Terraform, per the estate
# convention). Creates the conduit.* partitioned topics and registers the placement-stream subscription.
set -euo pipefail

ADMIN="${PULSAR_ADMIN:-pulsar-admin}"
TENANT_NS="${PULSAR_NAMESPACE:-public/default}"

topics=(
  conduit.orders conduit.inventory conduit.activations conduit.pricing conduit.crm
  conduit.commission conduit.ledger conduit.forecast conduit.purchasing
)

for t in "${topics[@]}"; do
  if ! $ADMIN topics list "$TENANT_NS" | grep -q "/$t\$"; then
    $ADMIN topics create "persistent://$TENANT_NS/$t"
    echo "created $t"
  else
    echo "exists  $t"
  fi
done

# The UFE placement stream: Conduit consumes with its OWN subscription (doc 03). Creating it ahead of the
# consumer pins the start position so no events are missed between deploy and first connect.
$ADMIN topics create-subscription \
  --subscription conduit-placement-versioned-subscription-1 \
  "persistent://$TENANT_NS/athena-placement-versioned" 2>/dev/null \
  && echo "subscription created" || echo "subscription exists"
