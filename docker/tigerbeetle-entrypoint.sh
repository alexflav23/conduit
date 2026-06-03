#!/bin/sh
# Single-replica, single-node Tigerbeetle for local dev.
# Formats the data file on first boot, then starts the replica.
# See ../terraform/athena-tigerbeetle/files/tigerbeetle-script.nix for the
# shape this mirrors (prod uses a multi-replica cluster on NixOS EC2).

set -eu

# Cluster id "1" is a dev convention; anything non-zero avoids the
# "cluster id 0 is reserved for testing and benchmarking" warning.
DATA_FILE=/data/1_0.tigerbeetle
CLUSTER_ID=1
REPLICA=0
REPLICA_COUNT=1
PORT=3000

if [ ! -f "$DATA_FILE" ]; then
  echo "[tigerbeetle] formatting $DATA_FILE (cluster=$CLUSTER_ID, replica=$REPLICA)"
  /tigerbeetle format \
    --cluster="$CLUSTER_ID" \
    --replica="$REPLICA" \
    --replica-count="$REPLICA_COUNT" \
    "$DATA_FILE"
fi

echo "[tigerbeetle] starting on :$PORT"
exec /tigerbeetle start \
  --addresses="0.0.0.0:$PORT" \
  "$DATA_FILE"
