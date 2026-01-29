# Delos Operational Quick Start

**Document Version**: 1.0
**Date**: 2026-01-27
**Status**: Production
**Audience**: Operations teams, Day-1 responsibilities

---

## Day-1 Checklist: New Cluster

Use this checklist when bringing up a new Delos cluster for the first time.

```
WEEK BEFORE DEPLOYMENT
[ ] Reserve hardware/cloud instances (per Hardware Requirements guide)
[ ] Verify Java 25+ available on all nodes
[ ] Test network connectivity between all nodes
[ ] Sync NTP on all nodes (< 500ms skew required)
[ ] Prepare KERI identity keys (offline, secure generation)
[ ] Generate TLS certificates for MTLS GRPC

DEPLOYMENT DAY

PRE-DEPLOYMENT (1 hour before start)
[ ] Final network connectivity test
[ ] Verify all nodes have correct configuration files
[ ] Verify all identity keys are installed
[ ] Verify TLS certificates are in place
[ ] Verify database directories are writable
[ ] Start systemd service on node 1

FIRST STARTUP (Nodes 1-3, wait 5m between)
[ ] ssh delos@node1 "sudo systemctl start delos"
[ ] Wait 30 seconds, verify: curl http://node1:8080/health
[ ] Check logs: ssh delos@node1 "journalctl -u delos -n 30"
[ ] ssh delos@node2 "sudo systemctl start delos"
[ ] Wait 5 minutes
[ ] Verify: curl http://node2:8080/health
[ ] ssh delos@node3 "sudo systemctl start delos"
[ ] Wait 5 minutes

HEALTH CHECK (After all nodes started)
[ ] Verify quorum: curl http://node1:8080/health
[ ] Count members: curl http://node1:8080/metrics | grep fireflies_view_size
[ ] Should show: fireflies_view_size{} = 7 (for 7-node cluster)
[ ] Check consensus: curl http://node1:8080/metrics | grep choam_blocks_committed
[ ] Should be increasing: blocks_committed 10, 11, 12, 13, ...
[ ] Check all nodes responding: for i in {1..7}; do curl http://node$i:8080/health; done

METRICS VERIFICATION
[ ] Access Prometheus: http://monitoring-node:9090
[ ] Check targets: all 7 nodes showing "UP"
[ ] Check alerts: no CRITICAL or WARNING alerts firing
[ ] Access Grafana: http://monitoring-node:3000
[ ] Verify dashboards loading: Cluster Health, Node Performance, Txn Pipeline

OPERATIONAL HANDOFF
[ ] Brief ops team on:
  - Alert thresholds and response procedures
  - Where to find logs: /opt/delos/logs/delos.log
  - How to check health: /health, /health/deep endpoints
  - Who to call for Byzantine detection
[ ] Schedule first metrics review (24 hours)
[ ] Archive pre-deployment documentation
```

---

## Hourly Checks (First 24 Hours)

### Hour 0 (Right after startup):

```bash
# Terminal 1: Monitor cluster health
watch -n 5 'curl -s http://node1:8080/health | jq ".checks"'

# Terminal 2: Monitor consensus
watch -n 5 'curl -s http://node1:8080/metrics | grep "choam_blocks_committed\|choam_consensus_latency_95th\|fireflies_view_size"'

# Terminal 3: Monitor logs (any errors)
ssh delos@node1 'tail -f /opt/delos/logs/delos.log | grep -E "ERROR|WARN|Byzantine"'
```

**Success criteria**:
- View size = 7 (all nodes present)
- Blocks increasing (1 per ~100ms)
- No errors in logs
- No Byzantine detections

### Hour 1-4:

- [ ] Blocks committed: 600+ (6 blocks/sec × 60s × 10min = 3600 blocks in first 10min)
- [ ] No view changes in logs
- [ ] No suspected nodes
- [ ] Consensus latency p95 < 200ms
- [ ] No memory warnings in logs

### Hour 4-8:

- [ ] Run first synthetic transaction test
  ```bash
  # Simple test: insert 1000 rows, measure throughput
  time for i in {1..1000}; do
    curl -X POST http://node1:8080/api/transactions \
      -d "INSERT INTO test VALUES ($i, 'data')"
  done
  ```
- [ ] Verify all 1000 transactions applied to all nodes
- [ ] Check throughput: transactions_applied should increase evenly

### Hour 8-24:

- [ ] Verify data replication: query each node, confirm same result sets
- [ ] Test network failure: unplug one node's network cable, verify cluster continues
- [ ] Test node restart: kill delos process on one node, verify rejoin
- [ ] Check backup procedures: verify snapshots are being created

---

## Daily Operations Checklist

### Morning (Start of shift)

**5 minutes**:
```bash
# Quick cluster health
curl -s http://node1:8080/health/deep | jq '.status'

# View size (should be = cluster_size)
curl -s http://node1:8080/metrics | grep fireflies_view_size

# Blocks produced in last hour (should be > 3600)
curl -s http://node1:8080/metrics | grep choam_blocks_committed

# Any critical alerts?
curl -s http://prometheus:9090/api/v1/alerts | jq '.data.alerts[] | select(.severity=="critical")'
```

**15 minutes** (if any issues from quick check):
```bash
# Deep health check
curl -s http://node1:8080/health/deep | jq '.'

# Check logs for errors (last hour)
ssh delos@node1 'journalctl -u delos --since "1 hour ago" | grep ERROR'

# Check Byzantine detections
ssh delos@node1 'grep Byzantine /opt/delos/logs/delos.log | tail -10'

# Check view changes
ssh delos@node1 'grep "View change" /opt/delos/logs/delos.log | tail -5'
```

### Hourly Checks

```bash
# Run every hour via cron:
# 0 * * * * /opt/delos/scripts/hourly-health-check.sh

#!/bin/bash

echo "=== Delos Hourly Health Check $(date) ==="

# 1. Cluster health
STATUS=$(curl -s http://node1:8080/health | jq -r '.status')
if [ "$STATUS" != "UP" ]; then
  echo "ALERT: Cluster status is $STATUS"
fi

# 2. View size
VIEW_SIZE=$(curl -s http://node1:8080/metrics | grep "^fireflies_view_size" | awk '{print $2}')
if [ "$VIEW_SIZE" != "7" ]; then
  echo "ALERT: View size is $VIEW_SIZE (expected 7)"
fi

# 3. Blocks committed (should increase by 360+ per hour = 6 blocks/sec)
BLOCKS_NOW=$(curl -s http://node1:8080/metrics | grep "^choam_blocks_committed" | awk '{print $2}')
# Compare with previous hour (store in file)
if [ -f /tmp/last_blocks ]; then
  BLOCKS_THEN=$(cat /tmp/last_blocks)
  INCREASE=$((BLOCKS_NOW - BLOCKS_THEN))
  if [ "$INCREASE" -lt 300 ]; then
    echo "WARNING: Only $INCREASE blocks in last hour (expected 360+)"
  fi
fi
echo $BLOCKS_NOW > /tmp/last_blocks

# 4. Consensus latency
LATENCY=$(curl -s http://node1:8080/metrics | grep "choam_consensus_latency_95th" | awk '{print $2}')
if (( $(echo "$LATENCY > 300" | bc -l) )); then
  echo "WARNING: Consensus latency $LATENCY ms (target: < 100ms)"
fi

echo "=== Check complete ==="
```

### Daily Summary (End of shift)

**5 minutes**:
```bash
# Daily metrics report
echo "=== Daily Metrics Summary $(date +%Y-%m-%d) ==="
echo "Blocks committed today: $(curl -s http://node1:8080/metrics | grep choam_blocks_committed)"
echo "Current view size: $(curl -s http://node1:8080/metrics | grep fireflies_view_size)"
echo "Pending transactions: $(curl -s http://node1:8080/metrics | grep choam_pending_transactions)"
echo "Heap usage: $(curl -s http://node1:8080/metrics | grep jvm_memory_heap_used | head -1)"
echo "Critical alerts (if any):"
curl -s http://prometheus:9090/api/v1/alerts | jq '.data.alerts[] | select(.severity=="critical") | .labels.alertname'
```

---

## Incident Response

### Symptom: Node became isolated (view_size drops)

**Severity**: HIGH
**Response time**: < 5 minutes

**Steps**:

1. **Verify it's actually isolated**:
   ```bash
   # Check which nodes can reach isolated node
   for node in node{1..7}; do
     echo -n "$node can reach isolated-node: "
     timeout 2 curl -s http://isolated-node:8080/health > /dev/null && echo "YES" || echo "NO"
   done
   ```

2. **Check network connectivity**:
   ```bash
   ping -c 5 isolated-node
   mtr -r -c 20 isolated-node  # Shows path and latency
   ```

3. **If network issue**:
   - Notify network team
   - Isolated node will rejoin automatically when network recovers
   - Monitor: `watch fireflies_view_size` (should go 6 → 7)

4. **If node is running but can't reach cluster**:
   - SSH to isolated node
   - Check network: `ip link show` (should see UP interface)
   - Check firewall: `sudo iptables -L` (should allow port 50051)
   - Restart network: `sudo systemctl restart networking`

5. **If node crashed**:
   ```bash
   ssh isolated-node "sudo systemctl status delos"
   ssh isolated-node "sudo systemctl start delos"
   ```

6. **Verify recovery**:
   - Watch for view size to recover: `watch fireflies_view_size`
   - Should show 6 → 7 within 30 seconds
   - Check blocks are catching up: `choam_blocks_committed`

---

### Symptom: Consensus stalled (no blocks for 5 minutes)

**Severity**: CRITICAL
**Response time**: < 2 minutes

**Steps**:

1. **Verify it's really stalled**:
   ```bash
   # Check blocks over time
   for i in {1..5}; do
     echo "$(date +%H:%M:%S) - Blocks: $(curl -s http://node1:8080/metrics | grep choam_blocks_committed | awk '{print $2}')"
     sleep 10
   done
   ```

2. **Check cluster formation**:
   ```bash
   curl -s http://node1:8080/metrics | grep fireflies_view_size
   # If < 7, cluster is fragmented (see Node Isolation above)
   ```

3. **Check for Byzantine detection**:
   ```bash
   tail -50 /opt/delos/logs/delos.log | grep Byzantine
   # If found, go to Byzantine Incident below
   ```

4. **Check resource constraints**:
   ```bash
   # Memory?
   curl -s http://node1:8080/metrics | grep jvm_memory_heap_used

   # CPU?
   top -bn1 | grep "^%Cpu"

   # Disk?
   df -h /opt/delos/data
   ```

5. **Check network latency** (consensus may be timing out):
   ```bash
   for node in node{2..7}; do
     echo -n "$node: "
     ping -c1 $node | grep time=
   done
   ```

6. **If issue found**:
   - High memory: Restart node with larger heap
   - High latency: Check network, may need to increase timeouts
   - Low disk: Archive old logs/checkpoints immediately

7. **If no issue found, escalate**:
   - Collect logs from all 7 nodes
   - Create incident ticket with: logs, metrics, commands run
   - Escalate to architecture team

---

### Symptom: Byzantine attack detected

**Severity**: CRITICAL
**Response time**: < 1 minute

**Description**: System detected Byzantine (malicious) node behavior

**Steps**:

1. **Confirm detection**:
   ```bash
   grep -i "equivocation\|byzantine" /opt/delos/logs/delos.log | tail -10

   # Which node was it?
   grep "member\|node" /opt/delos/logs/delos.log | grep -i byzantine
   ```

2. **Isolate the node immediately**:
   ```bash
   # Network isolation (fastest)
   ssh suspected-node "sudo iptables -A INPUT -j DROP"

   # Or restart it (forces rejoin)
   ssh suspected-node "sudo systemctl restart delos"
   ```

3. **Document evidence**:
   ```bash
   # Save logs
   cp /opt/delos/logs/delos.log /secure/incident-$(date +%s).log

   # Dump metrics
   curl -s http://node1:8080/metrics > /secure/metrics-incident.txt
   ```

4. **Alert security team**:
   - "Byzantine behavior detected on <node>"
   - Provide logs, metrics, timestamp
   - Request investigation and node rebuild

5. **Monitor cluster recovery**:
   ```bash
   watch -n 2 'curl -s http://node1:8080/metrics | grep "fireflies_view_size\|choam_blocks_committed"'
   ```
   - Should show view_size drop 7 → 6
   - Then blocks should resume

6. **When node is rebuild**:
   - Provision new node or rebuild existing
   - Regenerate KERI identity keys (old ones are compromised)
   - Regenerate BLS keys (if Phase 1C deployed)
   - Rejoin cluster: follow normal startup procedures

---

## Weekly Operations

### Monday Morning Meeting (30 minutes)

Discuss:
1. Availability metrics: uptime, downtime (if any)
2. Performance: throughput, latency trends
3. Anomalies: any unusual alerts or behavior
4. Capacity: disk usage trend, growing toward limit?
5. Upcoming maintenance: planned restarts, upgrades

### Friday Afternoon Review (1 hour)

Validate:
1. All nodes healthy heading into weekend
2. No alerts firing
3. Backups completed successfully
4. Logs archived properly
5. Documentation updated

### Monthly Deep Dive (2 hours)

Analyze:
1. Performance trends over month
2. Byzantine detection rate (should be low/zero)
3. Node failures and recovery times
4. Capacity planning (growing?, future needs?)
5. Configuration tuning opportunities

---

## Related Documentation

- [Monitoring Guide](MONITORING_AND_ALERTING.md) - Detailed metrics reference
- [Deployment Guide](DEPLOYMENT_GUIDE.md) - Initial setup procedures
- [Troubleshooting Guide](TROUBLESHOOTING_GUIDE.md) - Problem resolution
- [Operational Procedures](OPERATIONAL_PROCEDURES.md) - Detailed procedures
- [Hardware Requirements](HARDWARE_REQUIREMENTS.md) - Sizing and capacity
