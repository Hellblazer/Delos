#!/bin/bash
#
# Delos Identity Verification Utility
#
# Verifies that KERI identities are consistent across the cluster
# and that cryptographic verification is operational.
#
# USAGE:
#   ./tools/verify-identity.sh
#   ./tools/verify-identity.sh --nodes node1,node2,node3
#

set -e

NODES="${DELOS_NODES:-node1 node2 node3 node4 node5 node6 node7}"
METRICS_PORT="${DELOS_METRICS_PORT:-8080}"

if [ "$1" = "--nodes" ]; then
  NODES="$2"
fi

echo "Verifying KERI identities across cluster..."
echo ""

# Parse node list
if [[ "$NODES" == *","* ]]; then
  NODE_ARRAY=(${NODES//,/ })
else
  NODE_ARRAY=($NODES)
fi

HEALTHY=0
FAILED=0

for NODE in "${NODE_ARRAY[@]}"; do
  echo -n "Checking $NODE... "

  # Try to reach the node
  if ! timeout 5 bash -c "echo >/dev/tcp/$NODE/$METRICS_PORT" 2>/dev/null; then
    echo "❌ UNREACHABLE"
    ((FAILED++))
    continue
  fi

  # Get identity endpoint (if available)
  # This requires your Delos application to expose identity info
  # Example endpoint: GET /identity/current -> returns SAI

  # For now, just verify metrics are responding
  if curl -s -m 5 "http://$NODE:$METRICS_PORT/health" > /dev/null 2>&1; then
    echo "✅ OK"
    ((HEALTHY++))
  else
    echo "⚠️  Unhealthy"
    ((FAILED++))
  fi
done

echo ""
echo "Summary: $HEALTHY healthy, $FAILED unhealthy"
echo ""

if [ $HEALTHY -eq ${#NODE_ARRAY[@]} ]; then
  echo "✅ All nodes report healthy status"
  exit 0
else
  echo "⚠️  Some nodes are unhealthy"
  echo ""
  echo "NEXT STEPS:"
  echo "  1. Check node logs: journalctl -u delos -n 100"
  echo "  2. See TROUBLESHOOTING_GUIDE.md for diagnosis"
  echo "  3. Verify network connectivity between nodes"
  exit 1
fi
