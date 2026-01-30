# Delos Pre-Deployment & Day-1 Operations Guide

**Document Version**: 2.0 (Consolidated)
**Date**: 2026-01-27
**Status**: Production
**Audience**: Operations teams, DevOps engineers

---

## Quick Start (TL;DR)

Use this section when you need to move fast. See **Detailed Checklists** below for comprehensive version.

### Day-1 Checklist: New Cluster (Simplified)

```
WEEK BEFORE DEPLOYMENT
[ ] Reserve hardware/cloud instances (per Hardware Requirements guide)
[ ] Verify Java 25+ available on all nodes
[ ] Test network connectivity between all nodes
[ ] Sync NTP on all nodes (< 500ms skew required)
[ ] Prepare KERI identity keys (offline, secure generation)
[ ] Generate TLS certificates for MTLS GRPC

DEPLOYMENT DAY - PRE-DEPLOYMENT (1 hour before)
[ ] Final network connectivity test
[ ] Verify all nodes have correct configuration files
[ ] Verify all identity keys are installed
[ ] Verify TLS certificates are in place
[ ] Verify database directories are writable

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
```

---

## Detailed Checklists

### Checklist 1: Pre-Deployment (New Cluster)

**Timeline**: 1 week before go-live
**Owner**: DevOps lead
**Duration**: 2-3 days of preparation

#### Infrastructure (3 days before)

- [ ] **Hardware Provisioning**
  - [ ] Reserve 7 physical servers or cloud instances
  - [ ] Verify CPU: 4+ cores per node
  - [ ] Verify Memory: 8-16 GB per node
  - [ ] Verify Storage: 200+ GB SSD per node
  - [ ] Verify Network: 1+ Gbps per node

- [ ] **Network Setup**
  - [ ] Assign static IPs to all 7 nodes
  - [ ] Configure DNS records: node1.delos.local through node7.delos.local
  - [ ] Configure firewall rules (ports 50051, 50052, 8080)
  - [ ] Test network connectivity: `ping -c 5 node2` from node1 (all pairs)
  - [ ] Measure latency: `mtr -r -c 20 nodeX` (should be < 10ms)

- [ ] **System Configuration**
  - [ ] Install Java 25+ on all nodes
  - [ ] Verify Java: `java -version` shows 25.x
  - [ ] Configure NTP: `sudo apt-get install chrony`
  - [ ] Verify time sync: `timedatectl status` (< 500ms skew)
  - [ ] Configure filesystem: mount /opt/delos on SSD
  - [ ] Set permissions: `chmod 700 /opt/delos`

#### Security (5 days before)

- [ ] **Identity Keys**
  - [ ] Generate 7 KERI identity keys offline
  - [ ] Store in secure location (not on any node yet)
  - [ ] Create password (32+ random bytes): `openssl rand -base64 32`
  - [ ] Store password in secrets manager (Vault/AWS Secrets Manager)
  - [ ] Test keystore creation: `keytool -list -v -keystore test.jks`

- [ ] **TLS Certificates**
  - [ ] Generate CA certificate (valid 10 years)
  - [ ] Generate 7 server certificates (valid 1-3 years)
  - [ ] Add Subject Alternative Names (SANs) for each node
  - [ ] Create PKCS12 keystores from certificates
  - [ ] Test certificate validity: `openssl x509 -in cert.pem -text -noout`

- [ ] **Secret Management**
  - [ ] Set up Vault/AWS Secrets Manager
  - [ ] Store keystore passwords (for identity keys)
  - [ ] Store TLS keystore passwords
  - [ ] Store database passwords
  - [ ] Verify secrets can be retrieved: `vault read secret/delos/keystore-password`

#### Build & Configuration (3 days before)

- [ ] **Build Application**
  - [ ] Clone Delos repository
  - [ ] Run `./mvnw clean install -Ppre -DskipTests`
  - [ ] Verify build succeeds
  - [ ] Create delos.jar artifact: `ls target/delos-*.jar`

- [ ] **Configuration**
  - [ ] Create delos.yaml template
  - [ ] Fill in node names (node1, node2, ..., node7)
  - [ ] Fill in peer list (all 7 IP addresses and ports)
  - [ ] Set fireflies.member_count = 7
  - [ ] Set choam.batch_size = 100
  - [ ] Review all parameters (see CONFIGURATION_GUIDE.md)
  - [ ] Create 7 node-specific configs (one per node)

#### Testing (2 days before)

- [ ] **Staging Deployment**
  - [ ] Deploy 4-node staging cluster
  - [ ] Run integration tests: `./mvnw test -Dlarge_tests=true`
  - [ ] Run consensus tests (Byzantine detection)
  - [ ] Test key rotation procedures
  - [ ] Test upgrade procedures (if not first deployment)

- [ ] **Load Testing**
  - [ ] Run synthetic load: 10K transactions
  - [ ] Measure throughput: target 5K-10K tx/sec
  - [ ] Measure latency: P95 should be < 250ms
  - [ ] Check memory usage: should stabilize < 70%
  - [ ] Monitor for crashes or errors

#### Operations Preparation (1 day before)

- [ ] **Monitoring Setup**
  - [ ] Install Prometheus
  - [ ] Configure Prometheus scrape targets (7 nodes)
  - [ ] Verify metrics collection: `curl http://node1:8080/metrics`
  - [ ] Install Grafana
  - [ ] Create dashboards (use templates from docs)
  - [ ] Configure alerts (see MONITORING_AND_ALERTING.md)

- [ ] **Runbook Preparation**
  - [ ] Print or open incident runbooks (tabs in browser)
  - [ ] Brief ops team on alert thresholds
  - [ ] Identify on-call escalation path
  - [ ] Document contact info for architecture team

- [ ] **Final Verification**
  - [ ] All 7 nodes passing health checks
  - [ ] Network connectivity verified (< 10ms latency)
  - [ ] NTP sync verified on all nodes
  - [ ] Backups configured and tested
  - [ ] Monitoring stack ready and validated

---

### Checklist 2: Day-1 Operations (First 24 Hours)

#### Hourly Checks (First 24 Hours)

##### Hour 0 (Right after startup):

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

##### Hour 1-4:

- [ ] Blocks committed: 600+ (6 blocks/sec × 60s × 10min = 3600 blocks in first 10min)
- [ ] No view changes in logs
- [ ] No suspected nodes
- [ ] Consensus latency p95 < 200ms
- [ ] No memory warnings in logs

##### Hour 4-8:

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

##### Hour 8-24:

- [ ] Verify data replication: query each node, confirm same result sets
- [ ] Test network failure: unplug one node's network cable, verify cluster continues
- [ ] Test node restart: kill delos process on one node, verify rejoin
- [ ] Check backup procedures: verify snapshots are being created

---

### Checklist 3: Daily Operations

#### Morning (Start of shift) - 5 minutes

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

If any issues, run deep check (15 minutes):

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

#### Hourly Checks (via cron)

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

#### Daily Summary (End of shift) - 5 minutes

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

#### Weekly Operations

**Monday Morning Meeting** (30 minutes):
1. Availability metrics: uptime, downtime (if any)
2. Performance: throughput, latency trends
3. Anomalies: any unusual alerts or behavior
4. Capacity: disk usage trend, growing toward limit?
5. Upcoming maintenance: planned restarts, upgrades

**Friday Afternoon Review** (1 hour):
1. All nodes healthy heading into weekend
2. No alerts firing
3. Backups completed successfully
4. Logs archived properly
5. Documentation updated

**Monthly Deep Dive** (2 hours):
1. Performance trends over month
2. Byzantine detection rate (should be low/zero)
3. Node failures and recovery times
4. Capacity planning (growing?, future needs?)
5. Configuration tuning opportunities

---

## Incident Response Quick Reference

### Symptom: Node became isolated (view_size drops)

**Severity**: HIGH | **Response**: < 5 min

```bash
# Check which nodes can reach isolated node
for node in node{1..7}; do
  echo -n "$node can reach isolated-node: "
  timeout 2 curl -s http://isolated-node:8080/health > /dev/null && echo "YES" || echo "NO"
done

# Test network
ping -c 5 isolated-node
mtr -r -c 20 isolated-node

# If network issue: notify network team, node will rejoin automatically
# If node crashed: ssh isolated-node "sudo systemctl restart delos"
```

### Symptom: Consensus stalled (no blocks for 5 minutes)

**Severity**: CRITICAL | **Response**: < 2 min

```bash
# Verify stalled
for i in {1..5}; do
  echo "$(date +%H:%M:%S) - Blocks: $(curl -s http://node1:8080/metrics | grep choam_blocks_committed | awk '{print $2}')"
  sleep 10
done

# Check cluster formation
curl -s http://node1:8080/metrics | grep fireflies_view_size

# Check for Byzantine
tail -50 /opt/delos/logs/delos.log | grep Byzantine

# Check resources
curl -s http://node1:8080/metrics | grep jvm_memory_heap_used
df -h /opt/delos/data
```

### Symptom: Byzantine attack detected

**Severity**: CRITICAL | **Response**: < 1 min

```bash
# Confirm
grep -i "equivocation\|byzantine" /opt/delos/logs/delos.log | tail -10

# Isolate immediately
ssh suspected-node "sudo iptables -A INPUT -j DROP"

# Document
cp /opt/delos/logs/delos.log /secure/incident-$(date +%s).log

# Alert security team with evidence
```

---

## Related Documentation

- [Monitoring & Alerting](MONITORING_AND_ALERTING.md) - Metrics and alert setup
- [Operational Procedures](OPERATIONAL_PROCEDURES.md) - General day-to-day operations
- [Troubleshooting Guide](TROUBLESHOOTING_GUIDE.md) - Problem resolution
- [Deployment Guide](DEPLOYMENT_GUIDE.md) - Full deployment procedures
- [Configuration Guide](CONFIGURATION_GUIDE.md) - Configuration reference
- [Hardware Requirements](HARDWARE_REQUIREMENTS.md) - Sizing and capacity
- [Disaster Recovery](DISASTER_RECOVERY.md) - Backup/restore procedures

---

**Consolidated from**:
- OPERATIONAL_CHECKLISTS.md (v1.0, 2026-01-27)
- OPERATIONAL_QUICK_START.md (v1.0, 2026-01-27)

**Version**: 2.0
**Last Updated**: 2026-01-30
