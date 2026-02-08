# CHOAM Upgrade Guide

Production upgrade procedures for zero-downtime CHOAM version migrations.

**Version**: 0.0.6-SNAPSHOT
**Last Updated**: 2026-02-08
**Target Audience**: Operators, DevOps, SREs

---

## Table of Contents

- [Overview](#overview)
- [Compatibility Matrix](#compatibility-matrix)
- [Pre-Upgrade Checklist](#pre-upgrade-checklist)
- [Rolling Upgrade Procedure](#rolling-upgrade-procedure)
- [Rollback Procedure](#rollback-procedure)
- [State Migration](#state-migration)
- [Troubleshooting](#troubleshooting)

---

## Overview

### Version Compatibility

CHOAM supports **1-version compatibility window**:
- Version N-1 ↔ Version N (backward/forward compatible)
- Version N ↔ Version N+1 (backward/forward compatible)
- Version N-1 ✗ Version N+1 (incompatible, requires N as bridge)

**Example**:
```
0.0.6 ↔ 0.0.7 ✓ Compatible
0.0.7 ↔ 0.0.8 ✓ Compatible
0.0.6 ✗ 0.0.8 ✗ Incompatible (requires 0.0.6 → 0.0.7 → 0.0.8)
```

### Zero-Downtime Guarantee

**SLA during upgrade**:
- Transaction success rate: > 99.9%
- Latency increase: < 10% (p95)
- View change duration: < 30s (normal SLA)
- No client-visible errors

**Requirements**:
- Cluster size ≥ 2f+1 (quorum maintained during upgrade)
- Rolling upgrade (one node at a time)
- Compatible versions (within 1-version window)

---

## Compatibility Matrix

### Version 0.0.7 Changes

**Breaking changes**:
- State format: Added nonce field (int64)
- API: Block height changed from int32 to int64

**Migration**: Automatic via V1ToV2Migrator
- Adds nonce field (default: 0)
- Expands height to 64-bit

**Rollback safety**:
- ✓ Safe if height < 2^31 and nonce = 0
- ✗ Unsafe if height ≥ 2^31 or nonce ≠ 0

### Version 0.0.6 → 0.0.7 Compatibility

| Feature | 0.0.6 | 0.0.7 | Compatible? |
|---------|-------|-------|-------------|
| Checkpoint format | V1 | V2 | ✓ (via migration) |
| Block height | int32 | int64 | ✓ (auto-expand) |
| Nonce tracking | None | int64 | ✓ (default: 0) |
| GRPC protocol | v1 | v1 | ✓ (unchanged) |
| View reconfiguration | v1 | v1 | ✓ (unchanged) |

---

## Pre-Upgrade Checklist

### Planning Phase (1 week before)

- [ ] **Review release notes**: Identify breaking changes
- [ ] **Verify compatibility**: Current version within 1-version window
- [ ] **Backup cluster**: Create checkpoint of all nodes
- [ ] **Test in staging**: Complete upgrade on staging cluster
- [ ] **Plan rollback window**: Define rollback criteria and timeline
- [ ] **Notify stakeholders**: Schedule maintenance window (if needed)

### Pre-Flight Checks (1 hour before)

- [ ] **Cluster health**: All nodes operational, no Byzantine incidents
  ```bash
  curl http://node1:8080/ready
  # Response: {"status": "READY", "view": "...", "epoch": 42}
  ```

- [ ] **Create checkpoint**: Manual checkpoint before upgrade
  ```bash
  curl -X POST http://node1:8080/api/checkpoint/create
  ```

- [ ] **Backup checkpoints**: Copy checkpoints to backup storage
  ```bash
  cp /var/lib/choam/checkpoints/* /backup/choam/checkpoints-0.0.6/
  ```

- [ ] **Monitor metrics**: Baseline metrics for comparison
  ```bash
  # Transaction rate
  curl http://node1:9090/metrics | grep choam_transaction_rate

  # Consensus latency
  curl http://node1:9090/metrics | grep choam_consensus_latency
  ```

- [ ] **Verify quorum**: Cluster can tolerate f failures during upgrade
  ```bash
  # For 7-node cluster (f=2): Can upgrade 2 nodes before losing quorum
  # For 4-node cluster (f=1): Can upgrade 1 node before losing quorum
  ```

---

## Rolling Upgrade Procedure

### Step 1: Upgrade First Node

**1.1. Drain node** (optional, for graceful shutdown):
```bash
# Mark node as draining (stops accepting new transactions)
curl -X POST http://node1:8080/api/drain
# Wait for pending transactions to complete (max 60s)
sleep 60
```

**1.2. Stop node**:
```bash
systemctl stop choam-node1
```

**1.3. Deploy new version**:
```bash
# Backup old version
cp /opt/choam/choam.jar /opt/choam/choam-0.0.6.jar

# Deploy new version
cp choam-0.0.7.jar /opt/choam/choam.jar
```

**1.4. Migrate checkpoint** (if state format changed):
```bash
java -cp /opt/choam/choam.jar \
  com.hellblazer.delos.choam.migration.MigrationCLI \
  --source /var/lib/choam/checkpoints/latest.bin \
  --source-version 0.0.6 \
  --target-version 0.0.7 \
  --output /var/lib/choam/checkpoints/migrated.bin
```

**1.5. Start node**:
```bash
systemctl start choam-node1
```

**1.6. Verify node rejoined**:
```bash
# Check node status
curl http://node1:8080/ready
# Expected: {"status": "READY", "view": "...", "epoch": 42}

# Check cluster recognizes node
curl http://node2:8080/api/committee
# Expected: {"size": 7, "online": 7, ...}  # All nodes online
```

**1.7. Monitor for 5 minutes**:
```bash
# Watch metrics
watch -n 10 'curl -s http://node1:9090/metrics | grep -E "(transaction_rate|consensus_latency|byzantine_incidents)"'

# Check for errors
journalctl -u choam-node1 -f | grep ERROR
```

### Step 2: Upgrade Remaining Nodes

Repeat Step 1 for each remaining node with **5-minute intervals**:

```bash
# Node 2
systemctl stop choam-node2
# ... (same steps as node1)
sleep 300  # Wait 5 minutes

# Node 3
systemctl stop choam-node3
# ... (same steps)
sleep 300

# Continue until all nodes upgraded
```

**Why 5-minute intervals?**
- Allows cluster to stabilize after each node rejoins
- Provides time to detect issues before affecting more nodes
- Ensures quorum maintained throughout upgrade

### Step 3: Verify Upgrade Complete

**3.1. Check all nodes upgraded**:
```bash
for i in {1..7}; do
  echo "Node $i:"
  curl -s http://node$i:8080/api/version
done

# Expected: All nodes report version 0.0.7
```

**3.2. Verify cluster health**:
```bash
# Committee status
curl http://node1:8080/api/committee
# Expected: {"size": 7, "online": 7, "quorum": 5}

# Byzantine incidents
curl http://node1:8080/api/byzantine/incidents
# Expected: []  # No incidents
```

**3.3. Run smoke tests**:
```bash
# Submit test transaction
curl -X POST http://node1:8080/api/submit \
  -H "Content-Type: application/json" \
  -d '{"data": "upgrade-verification-test"}'

# Verify committed
curl http://node1:8080/api/blocks/latest
# Expected: Block contains verification transaction
```

---

## Rollback Procedure

### When to Rollback

**Immediate rollback triggers**:
- Transaction success rate < 99%
- Byzantine incidents detected
- Consensus stalled > 5 minutes
- Critical bugs discovered in new version

**Acceptable issues** (no rollback):
- Latency spike < 20% (within tolerance)
- Single node failure (quorum maintained)
- Non-critical warnings in logs

### Rollback Steps

**1. Stop upgrade** (if in progress):
```bash
# Do not upgrade any more nodes
# Mixed-version operation is safe within compatibility window
```

**2. Rollback upgraded nodes** (reverse order):
```bash
# Rollback node 3 (last upgraded)
systemctl stop choam-node3

# Restore old version
cp /opt/choam/choam-0.0.6.jar /opt/choam/choam.jar

# Migrate checkpoint back to V1
java -cp /opt/choam/choam.jar \
  com.hellblazer.delos.choam.migration.MigrationCLI \
  --source /var/lib/choam/checkpoints/latest.bin \
  --source-version 0.0.7 \
  --target-version 0.0.6 \
  --output /var/lib/choam/checkpoints/migrated.bin

systemctl start choam-node3

# Wait 5 minutes, verify, then rollback node 2, etc.
```

**3. Verify rollback**:
```bash
# All nodes back to 0.0.6
for i in {1..7}; do
  curl -s http://node$i:8080/api/version
done

# Cluster healthy
curl http://node1:8080/ready
```

**4. Root cause analysis**:
- Review logs for error patterns
- Check metrics for anomalies
- Report issue to development team
- Plan fix or alternative upgrade strategy

---

## State Migration

### Migration Process

**Automatic migration** (during node startup):
```
1. Node detects checkpoint version (V1)
2. Loads registered migrators from MigrationRegistry
3. Finds migration path: V1 → V2
4. Applies migration, creates V2 checkpoint
5. Deletes old V1 checkpoint (after successful start)
```

**Manual migration** (pre-upgrade validation):
```bash
# Validate migration before upgrade
java -cp choam-0.0.7.jar \
  com.hellblazer.delos.choam.migration.MigrationCLI \
  --validate \
  --source /var/lib/choam/checkpoints/latest.bin \
  --source-version 0.0.6 \
  --target-version 0.0.7

# Output: "Migration validation successful"
```

### Creating Custom Migrators

**Example**: Version 0.0.8 adds transaction nonce

```java
public class V2ToV3Migrator implements StateMigrator {
    @Override
    public String getSourceVersion() { return "0.0.7"; }

    @Override
    public String getTargetVersion() { return "0.0.8"; }

    @Override
    public void migrate(InputStream source, OutputStream target)
            throws MigrationException {
        try (var input = new DataInputStream(source);
             var output = new DataOutputStream(target)) {

            // Read V2 state
            var height = input.readLong();
            var nonce = input.readLong();
            var hash = new byte[32];
            input.readFully(hash);
            var data = input.readAllBytes();

            // Write V3 state (add transaction nonce)
            output.writeLong(height);              // height (unchanged)
            output.writeLong(nonce);               // nonce (unchanged)
            output.writeLong(0L);                  // tx_nonce (new, default: 0)
            output.write(hash);                    // hash (unchanged)
            output.write(data);                    // data (unchanged)
        } catch (IOException e) {
            throw new MigrationException("V2→V3 migration failed", e);
        }
    }
}
```

**Register migrator**:
```java
var registry = new MigrationRegistry();
registry.register(new V2ToV3Migrator());
registry.register(new V3ToV2Migrator());  // Rollback
```

---

## Troubleshooting

### Issue: Node Won't Start After Upgrade

**Symptoms**:
- Node crashes on startup
- Logs show "Migration failed"

**Diagnosis**:
```bash
# Check migration logs
journalctl -u choam-node1 | grep Migration

# Validate checkpoint manually
java -cp choam-0.0.7.jar \
  com.hellblazer.delos.choam.migration.MigrationCLI \
  --validate \
  --source /var/lib/choam/checkpoints/latest.bin \
  --source-version 0.0.6 \
  --target-version 0.0.7
```

**Resolution**:
1. Restore from backup checkpoint
2. Retry migration with fresh checkpoint
3. If persistent, rollback to previous version

### Issue: Transaction Failures During Upgrade

**Symptoms**:
- Success rate < 99.9%
- Client errors: "Committee unavailable"

**Diagnosis**:
```bash
# Check quorum status
curl http://node1:8080/api/committee
# If online < quorum: Upgrade paused cluster temporarily

# Check for Byzantine incidents
curl http://node1:8080/api/byzantine/incidents
```

**Resolution**:
1. Pause upgrade (don't upgrade more nodes)
2. Wait for cluster to stabilize (5 minutes)
3. If quorum lost: Restart failed nodes
4. If Byzantine detected: Investigate root cause

### Issue: High Latency After Upgrade

**Symptoms**:
- p95 latency > 20% increase
- Slow transaction commits

**Diagnosis**:
```bash
# Check GC activity
jstat -gcutil <pid> 1000

# Check consensus latency
curl http://node1:9090/metrics | grep choam_consensus_latency
```

**Resolution**:
1. Tune JVM GC parameters (see OPERATOR_GUIDE.md)
2. Check for resource contention (CPU, network)
3. Verify batch size not increased unexpectedly

### Issue: Rollback Fails with Data Loss Warning

**Symptoms**:
- Rollback migration throws error: "Nonce non-zero, data loss risk"

**Diagnosis**:
```bash
# Check nonce values in checkpoint
java -cp choam-0.0.7.jar \
  com.hellblazer.delos.choam.migration.DebugCLI \
  --inspect /var/lib/choam/checkpoints/latest.bin

# Output: nonce=12345 (non-zero)
```

**Resolution**:
1. **Cannot rollback safely** (would lose nonce data)
2. Options:
   - Fix issue in 0.0.7 (patch release 0.0.7.1)
   - Force rollback with data loss (last resort):
     ```bash
     --force-rollback --accept-data-loss
     ```
3. Document decision and data loss in incident report

---

## Appendix

### Upgrade Testing Checklist

Before production upgrade, verify in staging:

- [ ] Rolling upgrade completes without errors
- [ ] Transaction success rate > 99.9% during upgrade
- [ ] Latency increase < 10% (p95)
- [ ] View changes complete successfully
- [ ] Mixed-version operation (V1 and V2 nodes)
- [ ] Rollback procedure tested
- [ ] State migration validated

### Compatibility Testing Matrix

Test all version pairs within compatibility window:

| Source | Target | Test Status |
|--------|--------|-------------|
| 0.0.6  | 0.0.7  | ✓ Tested    |
| 0.0.7  | 0.0.6  | ✓ Tested (rollback) |
| 0.0.7  | 0.0.8  | ⏳ Pending  |
| 0.0.8  | 0.0.7  | ⏳ Pending  |

### Migration CLI Reference

```bash
# Validate migration
java -cp choam.jar com.hellblazer.delos.choam.migration.MigrationCLI \
  --validate \
  --source checkpoint.bin \
  --source-version 0.0.6 \
  --target-version 0.0.7

# Execute migration
java -cp choam.jar com.hellblazer.delos.choam.migration.MigrationCLI \
  --source checkpoint.bin \
  --source-version 0.0.6 \
  --target-version 0.0.7 \
  --output migrated.bin

# Find migration path
java -cp choam.jar com.hellblazer.delos.choam.migration.MigrationCLI \
  --find-path \
  --source-version 0.0.6 \
  --target-version 0.0.8

# List available migrators
java -cp choam.jar com.hellblazer.delos.choam.migration.MigrationCLI \
  --list-migrators
```

---

**Last Updated**: 2026-02-08
**CHOAM Version**: 0.0.6-SNAPSHOT

For deployment guide, see [OPERATOR_GUIDE.md](OPERATOR_GUIDE.md).
For architecture details, see [ARCHITECTURE.md](ARCHITECTURE.md).
