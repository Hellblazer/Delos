# Disaster Recovery & Backup Procedures

Comprehensive backup, restore, and disaster recovery procedures for Delos clusters. Includes RTO/RPO planning and real-world recovery scenarios.

**Status**: Production Ready
**Last Updated**: 2026-01-09
**Audience**: Operations teams, disaster recovery specialists

---

## Table of Contents

1. [Backup Strategy](#backup-strategy)
2. [Backup Procedures](#backup-procedures)
3. [Restore Procedures](#restore-procedures)
4. [RTO/RPO Planning](#rto-rpo-planning)
5. [Disaster Scenarios](#disaster-scenarios)
6. [Recovery Procedures](#recovery-procedures)

---

## Backup Strategy

### Backup Types

**Full Backup** (Complete state snapshot)
- **Size**: Entire cluster state
- **Time**: 1-30 minutes per node (depends on data size)
- **Use**: Before major changes, weekly baseline, archival
- **Retention**: 4 weeks minimum

**Incremental Backup** (Changes since last backup)
- **Size**: Only new/modified checkpoints
- **Time**: 1-5 minutes per node
- **Use**: Daily backup, post-incident snapshot
- **Retention**: 7 days

**Transaction Log Backup** (Raw transaction stream)
- **Size**: Small (text log)
- **Time**: Immediate
- **Use**: Point-in-time recovery, audit trail
- **Retention**: 30 days

### Backup Frequency Recommendations

| Deployment | Full Backup | Incremental | Log | RPO |
|-----------|-----------|-------------|-----|-----|
| **Dev/Test** | Weekly | Daily | Not needed | 1 hour |
| **Small Prod** (5K tx/sec) | Weekly | Daily | Every 6 hours | 30 min |
| **Medium Prod** (25K tx/sec) | Twice weekly | Daily | Every hour | 15 min |
| **Large Prod** (100K+ tx/sec) | Daily | Twice daily | Every 10 min | 5 min |

### Backup Destination

**Primary**: Local SSD on different drive than data
- **Location**: `/backup/delos/` (separate filesystem)
- **Advantage**: Fast, local, reliable
- **Limitation**: Vulnerable to single-machine failure

**Secondary**: Remote storage (S3, GCS, Azure)
- **Location**: `s3://company-backups/delos/`
- **Advantage**: Disaster-proof (different region/account)
- **Limitation**: Slower, requires credentials, network bandwidth

**Recommendation**: Dual destination - local for RTO, remote for disaster recovery

---

## Backup Procedures

### BP-01: Create Full Cluster Backup

**When**: Before major upgrades, weekly rotation
**Duration**: 5-30 minutes (depends on data size)
**Data Size**: Full checkpoint for all nodes

**Steps**:

1. **Notify operations** (optional but recommended)
   ```bash
   # If cluster is actively processing
   echo "Starting full cluster backup..." | mail ops@company.com
   ```

2. **Create backup directory**
   ```bash
   BACKUP_DIR="/backup/delos/full-$(date +%Y%m%d-%H%M%S)"
   mkdir -p $BACKUP_DIR
   ```

3. **Force checkpoint on all nodes**
   ```bash
   # This ensures fresh snapshot
   for NODE in node-{1..4}; do
     curl -s -X POST http://$NODE:8080/checkpoint
   done

   # Wait for checkpoint to complete
   sleep 10
   ```

4. **Backup from each node**
   ```bash
   for NODE in node-{1..4}; do
     echo "Backing up $NODE..."
     ssh $NODE "tar -czf - /var/lib/delos/checkpoints/ | \
       pv -N $NODE" > \
       $BACKUP_DIR/$NODE-checkpoint.tar.gz
   done
   ```

5. **Create manifest file**
   ```bash
   cat > $BACKUP_DIR/MANIFEST.txt <<EOF
   Backup Date: $(date -u)
   Cluster Size: 4 nodes
   Data Size: $(du -sh $BACKUP_DIR | cut -f1)
   Nodes Backed Up: node-{1..4}
   Backup Type: Full

   Restore Command:
   see BP-03 in DISASTER_RECOVERY.md
   EOF
   ```

6. **Verify backup integrity**
   ```bash
   # Check all files present
   ls -lh $BACKUP_DIR/*.tar.gz | wc -l
   # Should show: 4 (one per node)

   # Check manifest
   cat $BACKUP_DIR/MANIFEST.txt
   ```

7. **Upload to remote** (if dual destination)
   ```bash
   aws s3 sync $BACKUP_DIR s3://company-backups/delos/ \
     --region us-east-1 \
     --storage-class STANDARD_IA
   ```

**Success Criteria**:
- ✅ 4 backup files present
- ✅ Each backup > 100MB (non-empty)
- ✅ MANIFEST.txt created
- ✅ Remote copy verified (if applicable)

**Troubleshooting**:
- If checkpoint fails: Check disk space, network
- If tar hangs: May indicate slow disk, try again with timeout
- If upload fails: Check AWS credentials, S3 permissions

---

### BP-02: Verify Backup Integrity

**When**: After creating backup, weekly verification
**Duration**: 5-10 minutes per backup

**Steps**:

1. **Check backup files**
   ```bash
   BACKUP_DIR="/backup/delos/full-20260109-100000"

   # Verify files exist and have reasonable size
   for FILE in $BACKUP_DIR/*.tar.gz; do
     SIZE=$(stat -f%z "$FILE" 2>/dev/null || stat -c%s "$FILE")
     if [ $SIZE -lt 10485760 ]; then  # Less than 10MB
       echo "WARNING: Small backup file: $FILE ($SIZE bytes)"
     fi
   done
   ```

2. **Test extraction** (on separate machine)
   ```bash
   # Extract to temporary location
   TEST_DIR="/tmp/backup-test-$(date +%s)"
   mkdir $TEST_DIR
   cd $TEST_DIR

   tar -xzf $BACKUP_DIR/node-1-checkpoint.tar.gz
   ls -la var/lib/delos/checkpoints/
   ```

3. **Validate checksum** (if available)
   ```bash
   # Store checksums with backup
   cd $BACKUP_DIR
   sha256sum *.tar.gz > CHECKSUMS.txt

   # Verify later
   sha256sum -c CHECKSUMS.txt
   ```

4. **Document verification**
   ```bash
   echo "Backup verified: $BACKUP_DIR" >> /var/log/backup-verification.log
   ```

**Success Criteria**:
- ✅ All files extract without error
- ✅ File contents non-empty
- ✅ Checksums match
- ✅ Log entry created

---

## Restore Procedures

### BP-03: Restore from Full Backup

**When**: After data corruption, node failure recovery, rollback
**Duration**: 15-45 minutes (depends on data size)
**Risk**: High (data changes since backup will be lost)

**Prerequisites**:
- Backup file available and verified
- All nodes stopped
- Original node IDs match backup node IDs

**Steps**:

1. **Stop all nodes**
   ```bash
   for NODE in node-{1..4}; do
     ssh $NODE "sudo systemctl stop delos"
   done
   sleep 5
   ```

2. **Backup current data** (just in case)
   ```bash
   for NODE in node-{1..4}; do
     ssh $NODE "tar -czf /tmp/data-before-restore.tar.gz \
       /var/lib/delos/" &
   done
   wait
   ```

3. **Clear existing checkpoints**
   ```bash
   for NODE in node-{1..4}; do
     ssh $NODE "sudo rm -rf /var/lib/delos/checkpoints/*"
   done
   ```

4. **Restore from backup**
   ```bash
   BACKUP_DIR="/backup/delos/full-20260109-100000"

   for NODE in node-{1..4}; do
     echo "Restoring $NODE..."
     cat $BACKUP_DIR/$NODE-checkpoint.tar.gz | \
       ssh $NODE "sudo tar -xzf - -C /"
   done
   ```

5. **Verify restored files**
   ```bash
   for NODE in node-{1..4}; do
     CHECKPOINT_COUNT=$(ssh $NODE \
       "ls /var/lib/delos/checkpoints/ | wc -l")
     echo "$NODE: $CHECKPOINT_COUNT checkpoints"
   done
   ```

6. **Start nodes**
   ```bash
   for NODE in node-{1..4}; do
     ssh $NODE "sudo systemctl start delos"
   done
   ```

7. **Wait for cluster convergence**
   ```bash
   sleep 15

   # Monitor catch-up
   for i in {1..10}; do
     LAG=$(curl -s http://node-1:8080/metrics | \
       grep replication_lag_ms | tail -1 | awk '{print $2}')
     echo "Replication lag: ${LAG}ms"
     sleep 2
   done
   ```

**Success Criteria**:
- ✅ All nodes started successfully
- ✅ Cluster reached quorum
- ✅ Replication lag converged (< 100ms)
- ✅ Health endpoints report HEALTHY
- ✅ Transaction processing resumed

**Rollback** (restore data before restore):
```bash
# If restore caused problems, restore from pre-restore backup
# This undoes the restore and recovers to before-restore state
for NODE in node-{1..4}; do
  ssh $NODE "rm -rf /var/lib/delos/checkpoints/*"
  ssh $NODE "tar -xzf /tmp/data-before-restore.tar.gz -C /"
  ssh $NODE "sudo systemctl start delos"
done
```

---

## RTO/RPO Planning

### Calculate Your RTO/RPO

**RPO** (Recovery Point Objective) = data loss tolerance
```
RPO = time between backups

Example:
  Backup every 6 hours → RPO = 6 hours (up to 6 hours of data loss)
  Backup every 1 hour → RPO = 1 hour
  Continuous transaction log → RPO = minutes
```

**RTO** (Recovery Time Objective) = acceptable downtime
```
RTO = time to restore to operational state

Example:
  Full backup restore: 15-45 minutes
  Incremental + transaction log: 5-15 minutes
  Byzantine failure recovery: 2-5 minutes
```

### RTO/RPO by Scenario

| Scenario | Data Loss | Downtime | Strategy |
|----------|-----------|----------|----------|
| **Single node failure** | None | 0 min | Automatic (gossip detects, new leader elected) |
| **2 node failure** (in 4-node) | < 1 min | 1-2 min | Automatic recovery, state resync |
| **Full cluster failure** | Up to backup interval | 15-45 min | Restore all nodes from backup |
| **Data corruption** | RTO depends | 15-45 min | Restore from clean backup |
| **Byzantine attack** | < consensus latency | 1-5 min | Remove attacker node, consensus heals |

### Backup Interval Calculation

```
Required Data Loss Tolerance: 1 hour
→ Backup at least every hour

Required Downtime Tolerance: 30 minutes
→ Restore procedure must complete in 30 minutes
→ For 1TB data: need fast SSD, dual destination

Transaction Rate: 25,000 tx/sec
1 hour of data: 25,000 tx/sec × 3600 sec = 90 million transactions
Data size: 90M × 1KB/tx = 90GB (per hour)
```

---

## Disaster Scenarios

### S-01: Single Node Failure

**Symptoms**:
- Node offline (no response to health check)
- Member count decreased by 1
- Cluster continues functioning with N-1 nodes

**Automatic Recovery** (no action needed):
```
Gossip detects node offline → Other nodes mark as down
→ New leader elected if old leader failed
→ Remaining nodes continue consensus
→ No data loss (N-1 > 2/3 for 4-node cluster)
```

**Optional Actions**:
1. Check node logs: `journalctl -u delos -n 50`
2. Restart node: `sudo systemctl start delos`
3. Monitor catch-up: Watch replication_lag_ms decrease to < 100ms

**Timeline**:
- Detection: 5-10 seconds (ballot timeout)
- Recovery: < 30 seconds
- Full catch-up: 1-5 minutes

---

### S-02: Multiple Node Failures (< 1/3)

**Symptoms**:
- 2+ nodes offline (e.g., 2 of 4)
- Cluster still has quorum (> 2/3)
- Transactions still processing normally

**Recovery**:
```
Same as single node failure, but for multiple nodes
Each node rejoins automatically when restarted
State sync happens in background
```

**Critical**: Do NOT let > 1/3 fail (would lose quorum)

---

### S-03: Cluster Quorum Loss (> 1/3 failure)

**Symptoms**:
- 2+ of 4 nodes down (loses quorum)
- Remaining nodes cannot reach consensus
- All transactions fail with "No quorum"

**Recovery** (2-phase):

**Phase 1: Assess situation**
```bash
# How many nodes can we restore?
for NODE in node-{1..4}; do
  ssh $NODE "systemctl is-active delos" 2>/dev/null || \
    echo "$NODE: OFFLINE"
done
```

**Phase 2A: If can restart failed nodes** (preferred)
```bash
# Restart failed nodes
ssh offline-node "sudo systemctl start delos"
sleep 10
# Cluster should re-form automatically
```

**Phase 2B: If cannot restart** (need backup)
```bash
# Restore from latest backup to new hardware
# See BP-03 procedure
```

**Timeline**:
- Detection: Immediate (consensus timeout)
- Recovery: 5-30 minutes (depends on phase 2)

---

### S-04: Data Corruption

**Symptoms**:
- State divergence (nodes disagree on data)
- Validation errors in consensus
- Consensus stalled or very slow

**Recovery**:
1. **Identify corruption source** - Check logs for validation errors
2. **Isolate affected node** - Remove from cluster temporarily
3. **Restore from backup** - Use clean checkpoint
4. **Rejoin cluster** - Node catches up from peers

```bash
# 1. Stop suspect node
ssh suspect-node "sudo systemctl stop delos"

# 2. Restore from backup
# (See BP-03, but only for one node)

# 3. Start node
ssh suspect-node "sudo systemctl start delos"

# 4. Monitor catch-up
watch "curl -s http://suspect-node:8080/metrics | \
  grep replication_lag_ms"
```

---

### S-05: Byzantine Attack / Malicious Node

**Symptoms**:
- Invalid signatures detected
- Consensus validation failures
- Suspicious transaction patterns
- One node sending bad messages

**Recovery**:
1. **Identify attacker** - Check logs for signature failures
2. **Remove attacker** - Exclude from quorum
3. **Consensus heals** - Other nodes continue safely

```bash
# Remove via configuration or operational procedure
# Attacker cannot disrupt consensus (BFT property)
# Proceed with RB-03 (remove node from cluster)
```

**Timeline**:
- Detection: < 1 second (BFT validation)
- Healing: < 5 seconds (next consensus round)
- Recovery: Complete immediately

---

### S-06: Entire Cluster Failure

**Symptoms**:
- All nodes offline
- No cluster connectivity
- Complete data center outage

**Recovery** (lengthy, requires full restore):

**Phase 1: Access hardware** (30 min - 2 hours)
- Contact datacenter
- Physical inspection
- Power cycle if needed

**Phase 2: Restore** (15-45 minutes)
- Boot nodes from backup
- Restore data using BP-03
- Wait for cluster to stabilize

**Phase 3: Verify** (10 minutes)
- Check all nodes healthy
- Verify data integrity
- Resume operations

**Total RTO**: 1-4 hours
**Data Loss**: Up to backup interval (RPO)

---

## Recovery Procedures

### Quick Reference

| Failure | Automatic | Manual | RTO |
|---------|-----------|--------|-----|
| 1 node down | Yes, < 30s | Restart node | 5-30s |
| 2+ nodes (quorum OK) | Yes, < 1min | Restart nodes | < 2min |
| Quorum lost | No | Restore backup | 15-45min |
| Data corruption | No | Restore 1 node | 5-10min |
| Byzantine node | Yes, < 5s | Remove node | < 5s |
| Full failure | No | Restore all | 1-4 hours |

### Automated Recovery Checklist

In operations scripts, implement:

```bash
#!/bin/bash
# Automated cluster health recovery

HEALTHY=$(curl -s http://localhost:8080/health | jq -r '.status')

if [ "$HEALTHY" != "HEALTHY" ]; then
  # Try gentle recovery
  echo "Cluster unhealthy, attempting recovery..."

  # 1. Restart systemd service (clears stale state)
  systemctl restart delos

  # 2. Wait for catch-up
  sleep 10

  # 3. Re-check
  HEALTHY=$(curl -s http://localhost:8080/health | jq -r '.status')
  if [ "$HEALTHY" == "HEALTHY" ]; then
    echo "Recovery successful"
    exit 0
  fi

  # 4. Alert humans
  echo "Cluster still unhealthy, escalating..."
  exit 1
fi

echo "Cluster healthy"
exit 0
```

---

## Related Documentation

- **PERFORMANCE_TUNING.md** - RTO/RPO calculations from performance baselines
- **OPERATIONAL_PROCEDURES.md** - Runbooks, scheduling backups
- **TROUBLESHOOTING_GUIDE.md** - Diagnosis before recovery
- **MONITORING_ALERTING.md** - Detecting failures

---

**Last Updated**: 2026-01-09
**Phase**: 3.3 (Operations & Maintenance)
**Epic**: Delos-aj2 (Documentation Improvement)
