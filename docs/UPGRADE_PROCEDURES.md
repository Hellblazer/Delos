# Delos Upgrade Procedures

**Document Version**: 1.0
**Date**: 2026-01-27
**Status**: Production
**Audience**: Operations, DevOps, Release engineers

---

## Quick Reference: Rolling Update

```bash
# Upgrade from 0.2.2 to 0.2.3 (rolling update, zero downtime)

# 1. Prepare
cd /opt/delos
./mvnw clean install -Ppre -DskipTests
BACKUP_JAR="delos-0.2.2-backup-$(date +%s).jar"
cp delos-0.2.2.jar $BACKUP_JAR

# 2. Upgrade nodes one at a time
for node in node1 node2 node3 node4 node5 node6 node7; do
  echo "Upgrading $node..."

  # Copy new version
  scp delos-0.2.3.jar delos@$node:/opt/delos/

  # Graceful restart
  ssh delos@$node "sudo systemctl stop delos"
  sleep 2
  ssh delos@$node "sudo systemctl start delos"

  # Verify health
  sleep 15
  curl -s http://$node:8080/health | jq '.status'

  # Wait for catchup
  sleep 30
done

# 3. Verify cluster
curl -s http://node1:8080/metrics | grep fireflies_view_size
# Should show: 7 nodes
```

---

## Table of Contents

1. [Upgrade Types](#upgrade-types)
2. [Pre-Upgrade Procedures](#pre-upgrade-procedures)
3. [Rolling Update (Zero Downtime)](#rolling-update-zero-downtime)
4. [Blue-Green Deployment](#blue-green-deployment)
5. [Rollback Procedures](#rollback-procedures)
6. [Compatibility Matrix](#compatibility-matrix)
7. [Data Migration](#data-migration)
8. [Testing Upgrades](#testing-upgrades)

---

## Upgrade Types

### Type 1: Patch Update (0.2.2 → 0.2.3)

**Risk**: LOW
**Downtime**: Zero (rolling update)
**Testing required**: Integration tests only
**Procedure**: Rolling update
**Duration**: 30-45 minutes

**When to use**:
- Bug fixes
- Performance improvements
- Security patches
- No consensus protocol changes

**Example**:
```
0.2.2 (BLS implementation, Nov 2025)
  ↓ (patch)
0.2.3 (BLS bug fix, Jan 2026)
```

### Type 2: Minor Update (0.2.x → 0.3.x)

**Risk**: MEDIUM
**Downtime**: Planned (1-2 hours for 7 nodes)
**Testing required**: Full regression test suite
**Procedure**: Rolling update with validation gates
**Duration**: 2-4 hours

**When to use**:
- New features (backward compatible)
- Configuration changes
- Minor protocol enhancements

**Example**:
```
0.2.3 (BLS + aggregate signatures)
  ↓ (minor)
0.3.0 (Add key rotation feature)
```

### Type 3: Major Update (0.x → 1.x)

**Risk**: HIGH
**Downtime**: Required (full cluster restart)
**Testing required**: Full test suite + production simulation
**Procedure**: Blue-green deployment
**Duration**: 4-8 hours

**When to use**:
- Consensus protocol changes
- Data format changes
- Breaking API changes

**Example**:
```
0.2.3 (7-node committees)
  ↓ (major)
1.0.0 (Multi-committee consensus)
```

---

## Pre-Upgrade Procedures

### Pre-Upgrade Checklist (1 week before)

```
WEEK BEFORE UPGRADE
[ ] Review release notes: breaking changes? data migration needed?
[ ] Test upgrade path in staging cluster
[ ] Create rollback plan (identify rollback version)
[ ] Schedule maintenance window (inform users)
[ ] Backup all nodes:
    - Full snapshot of /opt/delos/data/
    - Export KERL state: kerl-export.yaml
    - Export configuration: delos.yaml
[ ] Verify backup integrity (test restore on test node)
[ ] Prepare runbooks for issues found in staging
[ ] Brief ops team on changes, rollback procedure
[ ] Alert users: expected downtime (if any)

DAY BEFORE UPGRADE
[ ] Final network connectivity test
[ ] Verify all nodes at same version
[ ] Verify cluster healthy: all 7 nodes present
[ ] Run baseline metrics (blocks/min, latency, throughput)
[ ] Do final review of upgrade procedure

UPGRADE DAY - 1 HOUR BEFORE
[ ] Alert ops teams (email, Slack)
[ ] Pause automated testing/CI jobs (don't interfere)
[ ] Have rollback version ready
[ ] Have runbooks open on 2 screens
[ ] Schedule incident channel (if public service)
[ ] Do final health check: all nodes UP
```

### Backup Procedure

**Critical**: Never upgrade without recent backup

```bash
# Full cluster backup
BACKUP_DIR="/backup/delos-$(date +%Y%m%d-%H%M%S)"
mkdir -p $BACKUP_DIR

# Backup each node
for node in node{1..7}; do
  ssh delos@$node "tar czf - /opt/delos/data/ /opt/delos/config/" \
    > $BACKUP_DIR/$node-backup.tar.gz
done

# Verify backups (spot check)
cd $BACKUP_DIR
for backup in *-backup.tar.gz; do
  echo -n "Checking $backup: "
  tar tzf $backup > /dev/null && echo "OK" || echo "FAILED"
done

# Archive to cold storage
tar czf delos-backup-$(date +%Y%m%d).tar.gz $BACKUP_DIR
aws s3 cp delos-backup-*.tar.gz s3://company-backups/delos/
```

---

## Rolling Update (Zero Downtime)

**Use for**: Patch and minor updates
**Prerequisite**: Backward compatible changes
**Nodes**: Update one at a time

### Step-by-Step Procedure

**Step 1: Update node 1 (non-critical)**

```bash
# 1.1 Connect to node 1
ssh delos@node1

# 1.2 Verify cluster health before
curl -s http://localhost:8080/metrics | grep fireflies_view_size
# Output: fireflies_view_size{} 7.0 (all nodes present)

# 1.3 Graceful shutdown
sudo systemctl stop delos

# 1.4 Backup old JAR (if not already done)
cp delos-0.2.2.jar delos-0.2.2-backup.jar

# 1.5 Copy new version
scp /local/path/delos-0.2.3.jar /opt/delos/

# 1.6 Start new version
sudo systemctl start delos

# 1.7 Verify startup (wait 30 seconds)
sleep 30
curl -s http://localhost:8080/health | jq '.status'
# Should show: "UP"

# 1.8 Check logs for errors
journalctl -u delos --since "1 minute ago" | grep ERROR
# Should be empty
```

**Step 2: Verify node 1 rejoined cluster**

```bash
# On node 1
curl -s http://localhost:8080/metrics | grep fireflies_view_size
# Output: fireflies_view_size{} 7.0 (still 7, means rejoined)

# Verify blocks are applying
curl -s http://localhost:8080/metrics | grep choam_blocks_committed
# Wait 30 seconds and check again - should increase
```

**Step 3: Check cluster is still operational**

```bash
# From any node
curl -s http://node2:8080/metrics | grep fireflies_view_size
# Should show: 7.0 (cluster doesn't see node 1 as isolated)
```

**Step 4: Repeat for nodes 2-7**

```bash
# For each remaining node:
for node in node{2..7}; do
  echo "Upgrading $node..."

  # Same procedure as Step 1
  ssh delos@$node "sudo systemctl stop delos && sleep 2"
  scp /local/path/delos-0.2.3.jar delos@$node:/opt/delos/
  ssh delos@$node "sudo systemctl start delos"

  # Wait for it to catch up
  sleep 30

  # Verify health
  curl -s http://$node:8080/health | jq '.status'
  echo "Node $node upgraded"

  # Don't hammer the cluster - wait 30s between nodes
  sleep 30
done
```

**Step 5: Verify all nodes upgraded**

```bash
# Check version on all nodes
for node in node{1..7}; do
  echo -n "$node: "
  ssh delos@$node "ls -la /opt/delos/delos*.jar | awk '{print \$NF}'"
done
# Should all show: delos-0.2.3.jar

# Final health check
curl -s http://node1:8080/health/deep | jq '.status'
# Should be: "UP"

# Verify cluster operation (blocks should be increasing)
for i in {1..5}; do
  echo "$(date +%H:%M:%S) - Blocks: $(curl -s http://node1:8080/metrics | grep choam_blocks_committed | awk '{print $2}')"
  sleep 5
done
```

### Rollback During Rolling Update

If an issue is found during rolling update (e.g., incompatibility):

```bash
# STOP the rolling update immediately
# Don't update remaining nodes

# Rollback the updated node
ssh delos@$failed_node "sudo systemctl stop delos"
cp delos-0.2.3-backup.jar delos-0.2.3.jar  # Preserve new version
cp /opt/delos/delos-0.2.2-backup.jar /opt/delos/delos-0.2.2.jar

# Restart with old version
ssh delos@$failed_node "sudo systemctl start delos"

# Verify it rejoined
sleep 30
curl -s http://$failed_node:8080/health | jq '.status'
# Should be "UP"

# Investigate issue (analyze logs, metrics, compare configurations)
# File bug report with full logs and procedure
```

---

## Blue-Green Deployment

**Use for**: Major updates with breaking changes
**Prerequisite**: Cluster duplication, complex failover
**Nodes**: Run 2 complete clusters, switch traffic

### Setup Phase

**Infrastructure**:
- Blue cluster: 7 nodes running 0.2.3
- Green cluster: 7 nodes running 0.3.0 (new version)
- Load balancer: Routes traffic, can switch instantly
- DNS: Points to load balancer

### Procedure

```bash
# 1. Build and test new version (0.3.0) in staging
./mvnw clean install
./mvnw test -Dlarge_tests=true  # Full test suite

# 2. Deploy green cluster (7 nodes)
for i in {1..7}; do
  provision-instance green-node$i
  deploy-delos green-node$i 0.3.0
done

# 3. Wait for green cluster to form
wait-for-cluster green-cluster 7 nodes

# 4. Run validation tests
run-integration-tests green-cluster
# Tests: Consensus, replication, Byzantine detection, performance

# 5. If tests pass, switch traffic
load-balancer set-primary green-cluster

# 6. Monitor for issues (metrics, errors, user reports)
monitor-cluster green-cluster 30m

# 7. If no issues, decommission blue cluster
for i in {1..7}; do
  terminate-instance blue-node$i
  archive-data blue-node$i
done
```

### Rollback (Green to Blue)

If critical issue found in green:

```bash
# 1. Instant traffic switch
load-balancer set-primary blue-cluster
# Users are instantly back to 0.2.3

# 2. Investigate issue in green
analyze-green-cluster issues

# 3. File bug report with full logs

# 4. Keep blue running (green is decommissioned)

# 5. Schedule retry of 0.3.0 when issue is fixed
```

---

## Rollback Procedures

### Scenario 1: Single Node Upgrade Failed

**Symptom**: Node won't start after upgrade

**Recovery**:

```bash
# Restore old version
ssh delos@problem-node
cp /opt/delos/delos-0.2.2-backup.jar /opt/delos/delos.jar

# Restart with old version
sudo systemctl start delos

# Verify rejoin
sleep 30
curl -s http://localhost:8080/health
# Should be "UP"

# Investigate logs
tail -100 /opt/delos/logs/delos.log | grep ERROR
```

### Scenario 2: Incompatibility Discovered Mid-Upgrade

**Symptom**: Blocks stop being produced after partial upgrade

**Recovery**:

```bash
# 1. STOP the rolling update (don't update remaining nodes)

# 2. Downgrade all updated nodes
for node in node{1..3}; do  # Assuming nodes 1-3 were upgraded
  ssh delos@$node "sudo systemctl stop delos"
  ssh delos@$node "cp delos-0.2.2-backup.jar delos.jar"
  ssh delos@$node "sudo systemctl start delos"
  sleep 15
done

# 3. Verify cluster recovery
sleep 30
curl -s http://node1:8080/metrics | grep fireflies_view_size
# Should show: 7.0

# 4. Wait for consensus to resume
for i in {1..10}; do
  BLOCKS=$(curl -s http://node1:8080/metrics | grep choam_blocks_committed | awk '{print $2}')
  echo "$(date +%H:%M:%S) - Blocks: $BLOCKS"
  sleep 5
done
```

### Scenario 3: Data Corruption After Upgrade

**Symptom**: State inconsistency detected between nodes

**Recovery** (requires full cluster restart):

```bash
# 1. Identify affected nodes
curl -s http://node1:8080/metrics | grep sql_state_height
curl -s http://node2:8080/metrics | grep sql_state_height
# If heights differ, corruption detected

# 2. Stop all nodes
for node in node{1..7}; do
  ssh delos@$node "sudo systemctl stop delos"
done

# 3. Restore from backup
BACKUP_DATE="20260127"  # Use date with last known good state
for node in node{1..7}; do
  ssh delos@$node "
    rm -rf /opt/delos/data
    cd /backup && tar xzf delos-backup-$BACKUP_DATE-$node.tar.gz
    cp -r backup/opt/delos/data /opt/delos/
    chown -R delos:delos /opt/delos/data
  "
done

# 4. Restart nodes (old version that was backed up)
for node in node{1..7}; do
  ssh delos@$node "cp /opt/delos/delos-0.2.2-backup.jar /opt/delos/delos.jar"
  ssh delos@$node "sudo systemctl start delos"
  sleep 15
done

# 5. Verify cluster recovers
sleep 30
curl -s http://node1:8080/health | jq '.status'
# Should be: "UP"
```

---

## Compatibility Matrix

### Version Compatibility

| From → To | Backward Compat | Downtime | Procedure | Data Migration |
|---|---|---|---|---|
| 0.2.2 → 0.2.3 | YES | Zero | Rolling update | None |
| 0.2.3 → 0.3.0 | NO | Required | Blue-green | None |
| 0.3.0 → 0.3.1 | YES | Zero | Rolling update | None |
| 0.3.1 → 1.0.0 | NO | Required | Blue-green | Required |

### Node Version Mixing

**During rolling update, nodes can run different versions**:

```
State: Acceptable (upgrade in progress)
┌─────────────┐
│ node1: 0.2.3 │  ← Just updated
│ node2: 0.2.2 │  ← Will update next
│ node3: 0.2.2 │
│ node4: 0.2.2 │
│ node5: 0.2.2 │
│ node6: 0.2.2 │
│ node7: 0.2.2 │
└─────────────┘

BUT: Never mix non-backward-compatible versions!
State: NOT acceptable
┌─────────────┐
│ node1: 0.3.0 │  ← Incompatible format
│ node2: 0.2.3 │  ← Can't read 0.3.0 blocks
│ ...          │
└─────────────┘
```

---

## Data Migration

### Scenario: New Database Format in 0.3.0

**Procedure**:

```bash
# 1. Develop migration tool
./mvnw compile -pl delos-tools
java -cp delos-tools.jar \
  com.hellblazer.delos.tools.MigrateDB \
  --from-version 0.2.3 \
  --to-version 0.3.0 \
  --source /opt/delos/data/state.h2 \
  --output /opt/delos/data/state-new.h2

# 2. Verify migration (on test node)
# Run 1000 test queries on migrated DB
# Verify same results as original

# 3. Test in staging cluster
# Deploy 0.3.0 to staging
# Migrate 3-node staging cluster
# Run integration tests

# 4. Production migration (during blue-green deployment)
# Green cluster includes migrated data
# Blue cluster keeps old format
# If green works, blue is decommissioned
# If green fails, keep blue as is
```

---

## Testing Upgrades

### Local Testing (Developer)

```bash
# 1. Build both versions
git checkout v0.2.2 && ./mvnw clean install
git checkout v0.2.3 && ./mvnw clean install

# 2. Run upgrade test
./mvnw test -Dtest=UpgradeTest

# Test covers:
# - Start cluster on v0.2.2
# - Upgrade node 1 to v0.2.3
# - Verify consensus continues
# - Upgrade remaining nodes
# - Verify cluster healthy on v0.2.3
```

### Staging Testing (Before Production)

```bash
# 1. Deploy 7-node staging cluster on 0.2.2
./scripts/deploy-staging.sh delos-0.2.2 7-node-staging

# 2. Run workload
./scripts/run-workload.sh 7-node-staging &  # Background

# 3. Execute rolling update
./scripts/rolling-update.sh 7-node-staging 0.2.2 0.2.3

# 4. Monitor during update
watch ./scripts/cluster-health.sh 7-node-staging

# 5. Run validation after
./scripts/validate-cluster.sh 7-node-staging

# 6. Keep running for 24 hours to verify stability
```

---

## Related Documentation

- [Deployment Guide](DEPLOYMENT_GUIDE.md) - Initial setup
- [Operational Procedures](OPERATIONAL_PROCEDURES.md) - Day-to-day ops
- [Disaster Recovery](DISASTER_RECOVERY.md) - Backup/restore
- [Monitoring Guide](MONITORING_AND_ALERTING.md) - Health checks during upgrade
