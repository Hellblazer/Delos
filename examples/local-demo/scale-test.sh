#!/bin/bash
# Gradual scaling script for 100-node cluster testing
# Usage: ./scale-test.sh [target_members] [batch_size] [wait_seconds]

TARGET=${1:-96}        # Total member nodes (default: 96 for 100 total)
BATCH=${2:-10}         # Nodes to add per batch
WAIT=${3:-30}          # Seconds to wait between batches

echo "=== Delos 100-Node Scale Test ==="
echo "Target: $TARGET member nodes (+ 4 genesis = $((TARGET + 4)) total)"
echo "Batch size: $BATCH nodes"
echo "Wait between batches: ${WAIT}s"
echo ""

# Check if cluster is running
if ! docker compose ps --quiet bootstrap 2>/dev/null | grep -q .; then
    echo "Starting base cluster (bootstrap + 3 kernel)..."
    docker compose up -d
    echo "Waiting 60s for genesis nodes to stabilize..."
    sleep 60
fi

# Get current scale
CURRENT=$(docker compose ps --quiet node 2>/dev/null | wc -l | tr -d ' ')
echo "Current member nodes: $CURRENT"

# Scale in batches
while [ "$CURRENT" -lt "$TARGET" ]; do
    NEXT=$((CURRENT + BATCH))
    if [ "$NEXT" -gt "$TARGET" ]; then
        NEXT=$TARGET
    fi

    echo ""
    echo "Scaling to $NEXT member nodes..."
    docker compose up -d --scale node=$NEXT

    echo "Waiting ${WAIT}s for nodes to join..."
    sleep $WAIT

    # Check status
    ACTIVE=$(docker compose logs --tail=1 bootstrap 2>&1 | grep -oP 'active=\K[0-9]+' || echo "?")
    echo "Bootstrap reports: active=$ACTIVE"

    CURRENT=$NEXT
done

echo ""
echo "=== Scale test complete ==="
echo "Final configuration: $((TARGET + 4)) total nodes"

# Show final status
sleep 30
echo ""
echo "Final cluster status:"
docker compose logs --tail=1 bootstrap 2>&1 | grep "Status:"
