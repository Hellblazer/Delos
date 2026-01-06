# Delos Troubleshooting Guide

**Document Version:** 1.0
**Date:** 2026-01-06
**Status:** Production

## Overview

This guide helps diagnose and resolve common Delos issues. Follow the decision trees by symptom, then execute diagnostics and fixes.

---

## 1. Cluster Not Starting

### 1.1 Symptom: All nodes fail to start

**Decision Tree:**
```
Nodes fail to start?
├─ ERROR: Port 50051 already in use
│  └─ Action: Check for competing processes, change port
├─ ERROR: Identity keystore not found
│  └─ Action: Verify keystore path, restore from backup
├─ ERROR: TLS certificate validation failed
│  └─ Action: Check certificate dates, regenerate if expired
├─ OutOfMemory exception
│  └─ Action: Increase heap size (-Xmx flag)
└─ Other (check logs below)
```

**Diagnostics:**

```bash
# 1. Check if port is in use
lsof -i :50051

# 2. Check keystore exists and is readable
ls -l /opt/delos/keys/identity.jks
file /opt/delos/keys/identity.jks  # Should be "Zip archive"

# 3. Check keystore is not corrupted
keytool -list -v -keystore /opt/delos/keys/identity.jks \
  -storepass SecurePassword123!

# 4. Verify TLS certificates
openssl x509 -in /opt/delos/keys/node1-cert.pem -text -noout | grep -A 5 "Validity"

# 5. Check logs
journalctl -u delos -n 100 --no-pager
tail -f /opt/delos/logs/delos.log
```

**Common Fixes:**

```bash
# If port conflict
sudo lsof -i :50051 | grep -v PID | awk '{print $2}' | xargs kill -9

# If keystore missing
scp /secure/backup/node1-identity.jks /opt/delos/keys/

# If certificate expired
# Follow Section 6.4 in DEPLOYMENT_GUIDE.md (Rolling Updates)

# If out of memory
# Edit /etc/systemd/system/delos.service
# Change: JAVA_OPTS="-Xmx8g -Xms4g"
sudo systemctl daemon-reload && sudo systemctl restart delos
```

### 1.2 Symptom: Single node fails to start

**Check node-specific logs:**

```bash
ssh delos@node3 "journalctl -u delos --since '5 minutes ago' -n 50"
```

**Common causes:**
- Network interface down: `ip link show`
- Hostname resolution: `nslookup node3.delos.local`
- Disk full: `df -h /opt/delos`
- Permission issues: `ls -l /opt/delos/keys/ /opt/delos/data/`

---

## 2. High Latency / Slow Consensus

### 2.1 Symptom: Consensus latency p95 > 1 second

**Diagnostics:**

```bash
# 1. Check metrics
curl -s http://node1:8080/metrics | grep choam_consensus_latency

# 2. Check network latency between nodes
for node in node{2..7}; do
  echo "$node:"
  ping -c 5 $node | grep avg
done

# 3. Check GC pauses (major cause of latency spikes)
jstat -gc -h20 $(pgrep -f delos) 1000

# 4. Check CPU usage
top -b -n 1 | head -20

# 5. Check network throughput
iftop -p -n 5
```

**Possible causes and fixes:**

| Cause | Diagnosis | Fix |
|-------|-----------|-----|
| **High GC pause** | jstat shows large GC_US columns | Increase heap: -Xmx8g |
| **Network jitter** | ping shows high variance | Check network switch, reduce gossip rate |
| **Slow peer** | One node has high latency | Restart slow node, check its disk I/O |
| **CHOAM batch size too large** | Latency increases with load | Reduce batch_size in delos.yaml |

**Recommended tuning:**

```yaml
# In delos.yaml - reduce consensus time
choam:
  batch_size: 50          # Reduce from 100
  batch_timeout: 50ms     # Reduce from 100ms
  consensus_timeout: 5s   # Increase safety margin

# In delos startup
JAVA_OPTS="-Xmx6g -Xms4g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:ParallelGCThreads=4"
```

---

## 3. Nodes Suspecting Each Other (Instability)

### 3.1 Symptom: `fireflies_suspected_count > 2` or frequent view changes

**Diagnostics:**

```bash
# 1. Check current suspicion
curl -s http://node1:8080/metrics | grep fireflies_suspected

# 2. Check view stability
curl -s http://node1:8080/metrics | grep fireflies_view_changes

# 3. Analyze gossip latencies
curl -s http://node1:8080/metrics | grep fireflies_gossip_latency

# 4. Check for network packet loss
# From router or with mtr (My Trace Route)
mtr -r -c 100 node2

# 5. Check logs for specific suspects
journalctl -u delos -n 200 | grep -i "suspected\|suspect\|accuse"
```

**Common causes:**

| Symptom | Cause | Fix |
|---------|-------|-----|
| One node consistently suspected | Node is slow / overloaded | Restart node, check resources |
| Multiple nodes suspected | Network instability | Check switch/cable, reduce gossip |
| Frequent view changes | High latency variance | Increase `suspect_threshold` in yaml |

**Temporary fix (increase suspicion threshold):**

```yaml
# delos.yaml
fireflies:
  suspect_threshold: 60s  # Increase from 30s
  failure_threshold: 10s  # Increase from 5s
```

**Check if it's the network:**

```bash
# Ping all pairs, look for outliers
for a in node{1..7}; do
  for b in node{1..7}; do
    [ "$a" = "$b" ] && continue
    latency=$(ping -c 1 -W 1 $b 2>/dev/null | grep -oP '\d+\.\d+(?= ms)' | head -1)
    [ ! -z "$latency" ] && echo "$a->$b: $latency ms"
  done
done
```

---

## 4. Blocks Not Committing / Consensus Stalled

### 4.1 Symptom: `choam_blocks_committed` not increasing

**Diagnostics:**

```bash
# 1. Check if consensus is stuck
curl -s http://node1:8080/metrics | grep "choam_blocks\|choam_consensus_latency"

# 2. Check pending transactions
curl -s http://node1:8080/metrics | grep choam_pending

# 3. Look for errors in logs
journalctl -u delos -n 100 --grep "ERROR\|CRITICAL\|consensus"

# 4. Check if leader is alive (consensus requires leader)
curl -s http://node1:8080/health/deep | jq '.choam'

# 5. Verify quorum (need 3f+1 members)
curl -s http://node1:8080/metrics | grep fireflies_view_size
# For 7 nodes: quorum=5 (if f=2) ✓
# For 7 nodes with 2 down: view_size=5 ✓ Still have quorum
# For 7 nodes with 3 down: view_size=4 ✗ Lost quorum (need 5)
```

**Decision Tree:**

```
Blocks not committing?
├─ fireflies_view_size < 5 (lost quorum)
│  ├─ Check failed nodes
│  ├─ Restart failed nodes (DEPLOYMENT_GUIDE.md Section 8.2)
│  └─ Wait ~30 seconds for recovery
├─ High consensus_latency (>5s)
│  └─ Check latency diagnostics above
├─ Pending transactions increasing unbounded
│  ├─ Consensus may be deadlocked
│  └─ Try: Restart CHOAM module only (see below)
└─ No errors in logs (mysterious)
   └─ Try: Full cluster restart (coordinate with ops)
```

**Restart CHOAM (keeps identity/state intact):**

```bash
# Only restart consensus module, not entire node
for node in node{1..7}; do
  curl -X POST http://$node:8080/admin/restart-choam
  sleep 5
done

# Monitor recovery
watch -n 1 'curl -s http://node1:8080/metrics | \
  grep -E "blocks_committed|consensus_latency|pending"'
```

### 4.2 Consensus Deadlock Recovery

**If consensus is truly deadlocked:**

```bash
# 1. Verify no quorum has recovered
for node in node{1..7}; do
  status=$(curl -s -m 2 http://$node:8080/health | jq .choam.status)
  echo "$node: $status"
done

# 2. If consensus is locked, need full recovery
./tools/recover-consensus.sh
# This replays KERL from clean state

# 3. Verify state consistency after recovery
./tools/validate-state.sh --repair
```

---

## 5. Database/Storage Issues

### 5.1 Symptom: Disk full, I/O errors, or corrupted databases

**Diagnostics:**

```bash
# 1. Check disk usage
df -h /opt/delos

# 2. Check individual database sizes
du -sh /opt/delos/data/*

# 3. Check for database corruption
# KERL
h2 -url jdbc:h2:/opt/delos/data/kerl "RECOVER"

# State
h2 -url jdbc:h2:/opt/delos/data/state "RECOVER"

# 4. Check for open file handles (can fill disk indirectly)
lsof | wc -l
ulimit -n
```

**Common issues and fixes:**

| Issue | Fix |
|-------|-----|
| **Disk full** | 1. Backup old data<br/>2. Delete old KERL entries (>90 days)<br/>3. Rotate logs<br/>4. Expand storage |
| **Database corruption** | 1. Stop service<br/>2. Run H2 RECOVER<br/>3. Verify consistency<br/>4. Restart |
| **Slow I/O** | 1. Move data to faster disk<br/>2. Reduce CHOAM batch size<br/>3. Enable write cache |

**Clear old logs:**

```bash
# Keep only last 30 days
sudo journalctl --vacuum-time=30d

# Compress old Delos logs
gzip /opt/delos/logs/delos.log.*

# Archive to secondary storage
tar czf /mnt/archive/delos-logs-jan.tar.gz /opt/delos/logs/
```

---

## 6. State Inconsistency Issues

### 6.1 Symptom: Block hash mismatch, state height inconsistency

**Diagnostics:**

```bash
# 1. Check state consistency
curl -s http://node1:8080/health/deep | jq '.sqlState'

# 2. Check if all nodes agree on height
for node in node{1..7}; do
  height=$(curl -s http://$node:8080/metrics | \
    grep 'sql_state_height' | awk '{print $NF}')
  echo "$node: $height"
done

# 3. Check block hashes
./tools/verify-block-hashes.sh

# 4. If mismatch, rebuild state from CHOAM log
java -cp delos.jar \
  com.hellblazer.delos.tools.RebuildState \
  --choam-log /opt/delos/data/choam.db \
  --output /opt/delos/data/state.h2.db.new \
  --validate

# 5. Verify rebuilt state
./tools/verify-consistency.sh /opt/delos/data/state.h2.db.new
```

**If state is unrecoverable:**

```bash
# 1. Restore from backup (only option)
cp /mnt/backups/delos-latest/state.h2.db /opt/delos/data/state.h2.db

# 2. Verify KERL and CHOAM log match
./tools/verify-log-consistency.sh

# 3. Restart service
sudo systemctl restart delos
```

---

## 7. Identity Issues

### 7.1 Symptom: Identity mismatch or signature verification failures

**Diagnostics:**

```bash
# 1. Check current identity
curl -s http://node1:8080/identity/current | jq '.'

# 2. Verify identity is consistent across cluster
for node in node{1..7}; do
  sai=$(curl -s http://$node:8080/identity/sai)
  echo "$node: $sai"
done

# 3. Check KERL integrity
java -cp delos.jar \
  com.hellblazer.delos.tools.VerifyKerl \
  --kerl /opt/delos/data/kerl.db

# 4. Validate witness attestations
./tools/verify-witnesses.sh
```

**Common issues:**

| Issue | Cause | Fix |
|-------|-------|-----|
| **SAI mismatch** | Key rotation incomplete | Wait for gossip to propagate (~30s) |
| **Signature failures** | Public key mismatch | Check witness network, restart gossip |
| **KERL corruption** | Disk error or partial write | Restore from backup (KERL is authoritative) |

---

## 8. Memory and GC Issues

### 8.1 Symptom: Memory usage creeping up, GC pauses increasing

**Diagnostics:**

```bash
# 1. Get JVM PID
PID=$(pgrep -f delos)

# 2. Monitor GC in real-time (20 samples/sec for 30 sec)
jstat -gc -h20 $PID 100 300

# 3. Heap usage breakdown
jmap -heap $PID | head -40

# 4. Find memory leaks (expensive, requires stop)
# Take heap dump
jmap -dump:live,format=b,file=/tmp/heap.bin $PID

# 5. Analyze with (requires tools)
# jhsdb jmap --binaryheap /tmp/heap.bin | less
```

**GC tuning:**

```bash
# Current setting (likely in systemd service)
JAVA_OPTS="-Xmx4g -Xms2g -XX:+UseG1GC"

# Improved for Delos (consensus workload)
JAVA_OPTS="-Xmx6g \
  -Xms4g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:InitiatingHeapOccupancyPercent=35 \
  -XX:+PrintGCDetails \
  -XX:+PrintGCDateStamps \
  -Xloggc:/opt/delos/logs/gc.log"
```

**If memory leak is found:**
```bash
# 1. Restart node cleanly
sudo systemctl restart delos

# 2. Monitor memory after restart
watch -n 5 'jstat -gc $(pgrep -f delos) | tail -1'

# 3. If leak recurs, check for:
#    - Long-lived collections (check code)
#    - Subscriber/listener not being cleaned up
#    - Thread local storage leaks
```

---

## 9. Network and Connectivity Issues

### 9.1 Symptom: Nodes can't reach each other, timeout errors

**Diagnostics:**

```bash
# 1. Check network connectivity (L2/L3)
for node in node{2..7}; do
  echo -n "$node: "
  ping -c 1 -W 1 $node >/dev/null 2>&1 && echo "REACHABLE" || echo "UNREACHABLE"
done

# 2. Check GRPC port specifically
for node in node{2..7}; do
  nc -zv $node 50051
done

# 3. Check firewall rules (if iptables)
sudo iptables -L -n | grep 50051

# 4. Check if service is listening on all interfaces
netstat -tlnp | grep 50051

# 5. DNS resolution
for node in node{1..7}; do
  nslookup $node.delos.local
done
```

**Common fixes:**

```bash
# If firewall is blocking
sudo ufw allow 50051/tcp
sudo ufw allow 50052/tcp

# If service not listening on correct interface
# Edit delos.yaml and set:
grpc:
  bind: 0.0.0.0  # All interfaces
  port: 50051

# If DNS not resolving, add to /etc/hosts
10.0.1.101  node1 node1.delos.local
10.0.1.102  node2 node2.delos.local
# ... etc
```

---

## 10. Quick Reference: Emergency Commands

```bash
# Get status of all nodes
for n in node{1..7}; do
  echo "$n: $(systemctl is-active delos --host=$n 2>/dev/null || echo 'unknown')"
done

# Restart entire cluster (coordinated)
for n in node{1..7}; do
  sudo systemctl restart delos --host=$n
  sleep 30
done

# Get latest 50 errors
journalctl -u delos -p err -n 50

# Watch metrics in real-time
watch -n 1 'curl -s http://localhost:8080/metrics | \
  grep -E "view_size|consensus_latency|blocks_committed|height"'

# Enable debug logging (temporary)
for n in node{1..7}; do
  curl -X POST http://$n:8080/loggers \
    -H "Content-Type: application/json" \
    -d '{"level": "DEBUG"}'
done

# Show cluster state
./tools/cluster-status.sh

# Validate everything
./tools/validate-all.sh --verbose
```

---

## 11. Backup Recovery Testing

**Importance:** Test backup restore procedures quarterly to ensure disaster recovery capability.

### Backup Strategy

Before testing recovery, ensure backups cover:
- KERI identity keystores (`/opt/delos/keys/`)
- KERL database (`/var/lib/delos/kerl.h2`)
- CHOAM consensus log (`/var/lib/delos/choam.log`)
- SQL-State checkpoint (`/var/lib/delos/checkpoint.sql`)
- Configuration files (`/etc/delos/delos.yaml`)

**Backup frequency:** Daily with retention of 30 days

### Test Restore Procedure

#### Step 1: Preparation (on test node, not production)

```bash
#!/bin/bash
# Select non-production node for testing
TEST_NODE="test-node-1"  # Must NOT be in active cluster

# Stop Delos service
ssh ${TEST_NODE} "sudo systemctl stop delos"

# Verify stopped
ssh ${TEST_NODE} "sudo systemctl status delos | grep inactive"
if [ $? -ne 0 ]; then
  echo "ERROR: Delos not stopped on ${TEST_NODE}"
  exit 1
fi

# Backup current data (save for rollback)
ssh ${TEST_NODE} "sudo tar czf /tmp/current-data-backup.tar.gz /var/lib/delos /opt/delos/keys"

echo "✓ Test node prepared for restore"
```

#### Step 2: Restore from Backup

```bash
#!/bin/bash

TEST_NODE="test-node-1"
BACKUP_DATE="2026-01-05"  # Date of backup to restore
BACKUP_LOCATION="/mnt/backups/${BACKUP_DATE}"

# Verify backup exists and is readable
ssh ${TEST_NODE} "test -f ${BACKUP_LOCATION}/kerl.h2.gz && \
  test -f ${BACKUP_LOCATION}/keys.tar.gz && \
  test -f ${BACKUP_LOCATION}/choam.log.gz && \
  test -f ${BACKUP_LOCATION}/delos.yaml"
if [ $? -ne 0 ]; then
  echo "ERROR: Backup files not found on ${TEST_NODE}"
  exit 1
fi

# Clear current data
ssh ${TEST_NODE} "sudo rm -rf /var/lib/delos/*"
ssh ${TEST_NODE} "sudo rm -rf /opt/delos/keys/*"

# Restore from backup
ssh ${TEST_NODE} "cd /var/lib/delos && sudo tar xzf ${BACKUP_LOCATION}/kerl.h2.gz"
ssh ${TEST_NODE} "cd /var/lib/delos && sudo tar xzf ${BACKUP_LOCATION}/choam.log.gz"
ssh ${TEST_NODE} "cd /opt/delos && sudo tar xzf ${BACKUP_LOCATION}/keys.tar.gz"
ssh ${TEST_NODE} "sudo cp ${BACKUP_LOCATION}/delos.yaml /etc/delos/delos.yaml"

# Verify files restored
ssh ${TEST_NODE} "test -f /var/lib/delos/kerl.h2 && \
  test -f /var/lib/delos/choam.log && \
  test -f /opt/delos/keys/member-id-keystore.jks && \
  test -f /etc/delos/delos.yaml"
if [ $? -ne 0 ]; then
  echo "ERROR: Restore failed - files missing"
  exit 1
fi

echo "✓ Backup restored successfully"
```

#### Step 3: Database Validation

```bash
#!/bin/bash

TEST_NODE="test-node-1"

# Validate KERL database integrity
ssh ${TEST_NODE} "sudo java -cp /opt/delos/lib/* \
  com.hellblazer.delos.stereotomy.tools.ValidateKERL \
  /var/lib/delos/kerl.h2"
if [ $? -ne 0 ]; then
  echo "ERROR: KERL database corrupted"
  exit 1
fi

# Validate CHOAM log integrity
ssh ${TEST_NODE} "sudo java -cp /opt/delos/lib/* \
  com.hellblazer.delos.choam.tools.ValidateLog \
  /var/lib/delos/choam.log"
if [ $? -ne 0 ]; then
  echo "ERROR: CHOAM log corrupted"
  exit 1
fi

# Check keystore validity
ssh ${TEST_NODE} "keytool -list -v -keystore /opt/delos/keys/member-id-keystore.jks \
  -storepass \${KEYSTORE_PASSWORD} | grep -q 'Owner: CN='"
if [ $? -ne 0 ]; then
  echo "ERROR: Keystore invalid"
  exit 1
fi

echo "✓ All databases validated"
```

#### Step 4: Service Startup

```bash
#!/bin/bash

TEST_NODE="test-node-1"

# Start Delos service
ssh ${TEST_NODE} "sudo systemctl start delos"

# Wait for startup
sleep 10

# Verify service is running
ssh ${TEST_NODE} "sudo systemctl is-active delos | grep -q active"
if [ $? -ne 0 ]; then
  echo "ERROR: Delos failed to start after restore"

  # Show startup errors
  ssh ${TEST_NODE} "journalctl -u delos -n 30"
  exit 1
fi

echo "✓ Delos started successfully"
```

#### Step 5: Health Verification

```bash
#!/bin/bash

TEST_NODE="test-node-1"

# Check identity is valid
delos_cmd="docker exec delos-${TEST_NODE} delos"

${delos_cmd} identity validate
if [ $? -ne 0 ]; then
  echo "ERROR: Identity validation failed"
  exit 1
fi

# Verify KERL state matches backup
${delos_cmd} kerl status | head -10
echo "KERL state shown above"

# Check consensus is not trying to rejoin cluster
# (Node should be isolated - not attempting to connect to other nodes)
${delos_cmd} consensus status | grep -q "DORMANT\|RECOVERING"
if [ $? -ne 0 ]; then
  echo "WARNING: Node may be trying to rejoin active cluster"
  echo "Ensure test node is firewalled from production cluster"
fi

echo "✓ Health checks complete"
```

#### Step 6: Reporting and Cleanup

```bash
#!/bin/bash

TEST_NODE="test-node-1"

# Generate restore report
cat > /tmp/restore-test-report.md << EOF
# Backup Restore Test Report

**Date:** $(date -Iseconds)
**Test Node:** ${TEST_NODE}
**Backup Date:** 2026-01-05

## Results

- ✓ Backup files located
- ✓ Data restored from backup
- ✓ KERL database valid
- ✓ CHOAM log valid
- ✓ Keystore valid
- ✓ Service started
- ✓ Identity validated
- ✓ KERL state verified

## Conclusion

Backup restore test **PASSED**. Disaster recovery capability confirmed.

**Next scheduled test:** $(date -d '+3 months' -Iseconds)

EOF

echo "Restore test report generated:"
cat /tmp/restore-test-report.md

# Rollback to previous state (restore current data)
ssh ${TEST_NODE} "sudo systemctl stop delos"
ssh ${TEST_NODE} "sudo rm -rf /var/lib/delos/* /opt/delos/keys/*"
ssh ${TEST_NODE} "sudo tar xzf /tmp/current-data-backup.tar.gz --strip-components=1 -C /"
ssh ${TEST_NODE} "sudo systemctl start delos"

echo "✓ Test node rolled back to current state"
```

### Restore Test Checklist

- [ ] Test node selected (non-production)
- [ ] Backup files verified (complete and readable)
- [ ] Current data backed up
- [ ] Data restored from backup
- [ ] KERL database validated
- [ ] CHOAM log validated
- [ ] Keystore validated
- [ ] Service started successfully
- [ ] Identity validation passed
- [ ] KERL state verified
- [ ] Test node rolled back
- [ ] Restore report documented
- [ ] Report filed with ops team
- [ ] Next test date scheduled (quarterly)

### Troubleshooting Restore Failures

| Error | Diagnosis | Resolution |
|-------|-----------|-----------|
| **Backup files not found** | Backup job failed or retention expired | Check backup system logs; recreate backup manually |
| **KERL database corrupted** | Backup interrupted during write | Use previous day's backup; check backup I/O |
| **CHOAM log corrupted** | Consensus log truncated | Validate backup integrity before restore; use checkpoint |
| **Service won't start** | Configuration or permissions issue | Check logs; verify file ownership; restore delos.yaml |
| **Identity validation fails** | Key material corrupted | Check keystore password; restore from encrypted backup |
| **Cannot decrypt data** | Encryption key lost | Restore backup and encryption key together; maintain key escrow |

---

## References

- [Deployment Guide](DEPLOYMENT_GUIDE.md)
- [Monitoring Guide](MONITORING_GUIDE.md)
- [CHOAM Consensus](adr/0004-consensus-design-choam.md)
- [Fireflies: Membership Service](../fireflies/README.md)
- [H2 Database Documentation](http://www.h2database.com/)
- [KERI Identity Infrastructure](../stereotomy/docs/THREAT_MODEL.md)
