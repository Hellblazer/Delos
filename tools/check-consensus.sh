#!/bin/bash
#
# Delos Consensus Check Utility
#
# Verifies that consensus is operating correctly on a node.
#
# USAGE:
#   ./tools/check-consensus.sh <node>
#   ./tools/check-consensus.sh node1
#
# ENVIRONMENT:
#   DELOS_METRICS_PORT (default: 8080)
#   DELOS_METRICS_HOST (default: localhost)
#

set -e

NODE="${1:-localhost}"
METRICS_PORT="${DELOS_METRICS_PORT:-8080}"
TIMEOUT=5

if [ -z "$NODE" ]; then
  echo "Usage: $0 <node>"
  echo "Example: $0 node1"
  exit 1
fi

echo "Checking consensus on $NODE..."

# Check if node is reachable
if ! timeout $TIMEOUT bash -c "echo >/dev/tcp/$NODE/$METRICS_PORT" 2>/dev/null; then
  echo "❌ ERROR: Cannot reach $NODE:$METRICS_PORT"
  exit 1
fi

# Get consensus metrics
RESPONSE=$(curl -s -m $TIMEOUT "http://$NODE:$METRICS_PORT/metrics" 2>/dev/null || echo "")

if [ -z "$RESPONSE" ]; then
  echo "❌ ERROR: Cannot fetch metrics from $NODE"
  exit 1
fi

# Extract key metrics
CONSENSUS_LATENCY=$(echo "$RESPONSE" | grep -E "choam_consensus_latency" | tail -1 || echo "UNKNOWN")
BLOCKS_COMMITTED=$(echo "$RESPONSE" | grep -E "choam_blocks_committed" | tail -1 || echo "UNKNOWN")
PENDING_TXNS=$(echo "$RESPONSE" | grep -E "choam_pending_transactions" | tail -1 || echo "UNKNOWN")

echo ""
echo "Consensus Status:"
echo "  Latency: $CONSENSUS_LATENCY"
echo "  Blocks:  $BLOCKS_COMMITTED"
echo "  Pending: $PENDING_TXNS"
echo ""

# Simple health check
if echo "$CONSENSUS_LATENCY" | grep -q "choam_consensus_latency"; then
  echo "✅ Consensus metrics available"
  exit 0
else
  echo "⚠️  Warning: Could not verify consensus metrics"
  echo ""
  echo "SEE ALSO:"
  echo "  - Troubleshooting Guide: docs/TROUBLESHOOTING_GUIDE.md"
  echo "  - Monitoring Guide: docs/MONITORING_GUIDE.md"
  exit 1
fi
