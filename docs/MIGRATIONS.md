# Delos Version Migration Guide

**Document Version**: 1.0
**Date**: 2026-02-08
**Status**: Production
**Audience**: Operators, DevOps, Release Engineers, Developers

---

## Quick Reference

**Current Version**: `0.3.1-SNAPSHOT`
**Migration Support**: N-1 ↔ N ↔ N+1 compatibility window
**JVM Requirement**: Java 25+
**Rollback Window**: Within 1 version (e.g., 0.3.0 can rollback to 0.2.2)

**Key Documents**:
- [UPGRADE_PROCEDURES.md](UPGRADE_PROCEDURES.md) — Step-by-step upgrade procedures
- [CHOAM UPGRADE_GUIDE.md](../choam/docs/UPGRADE_GUIDE.md) — CHOAM-specific zero-downtime upgrades
- [DISASTER_RECOVERY.md](DISASTER_RECOVERY.md) — Backup and restore procedures

---

## Table of Contents

1. [Migration Path Matrix](#migration-path-matrix)
2. [Version Compatibility Model](#version-compatibility-model)
3. [Breaking Changes by Version](#breaking-changes-by-version)
4. [Schema Changes](#schema-changes)
5. [JVM Compatibility Matrix](#jvm-compatibility-matrix)
6. [Dependency Changes](#dependency-changes)
7. [Migration Code Examples](#migration-code-examples)
8. [Rollback Safety](#rollback-safety)
9. [Testing Migration Paths](#testing-migration-paths)

---

## Migration Path Matrix

### Supported Version Transitions

Delos supports **1-version compatibility window**: Version N can interoperate with N-1 and N+1.

```
Version Graph (edges indicate direct migration support):

v0.0.1 → v0.0.3 → v0.0.4 → v0.0.5 → v0.0.6 → v0.0.7 → v0.0.8
                                                           ↓
                                                       v0.0.9 → v0.0.10
                                                                   ↓
                                              v0.1.0 → v0.1.1 → v0.1.3
                                                                   ↓
                                                       v0.2.1 → v0.2.2
                                                                   ↓
                                                               v0.3.0 → v0.3.1 (current)
```

### Migration Compatibility Table

| From → To | Direct | Multi-Hop | Downtime | State Migration | Procedure |
|-----------|--------|-----------|----------|----------------|-----------|
| 0.2.2 → 0.3.0 | ✓ | — | Zero | None | Rolling update |
| 0.3.0 → 0.3.1 | ✓ | — | Zero | None | Rolling update |
| 0.2.1 → 0.3.0 | ✗ | 0.2.1 → 0.2.2 → 0.3.0 | Zero | None | Multi-hop rolling |
| 0.1.3 → 0.3.0 | ✗ | 0.1.3 → 0.2.2 → 0.3.0 | Zero | None | Multi-hop rolling |
| 0.0.10 → 0.3.0 | ✗ | 0.0.10 → 0.1.3 → 0.2.2 → 0.3.0 | Zero | None | Multi-hop rolling |

**Direct migration**: Versions within 1-version window (N-1 ↔ N ↔ N+1)
**Multi-hop migration**: Versions > 1 version apart (requires intermediate versions)

### Multi-Hop Migration Example

**Scenario**: Upgrade from 0.1.3 to 0.3.0

```bash
# Step 1: Upgrade 0.1.3 → 0.2.2
./rolling-upgrade.sh 0.1.3 0.2.2
# Wait for cluster stability (5-10 minutes)

# Step 2: Upgrade 0.2.2 → 0.3.0
./rolling-upgrade.sh 0.2.2 0.3.0
# Verify final state
```

**Duration**: ~60-90 minutes for 7-node cluster
**Risk**: LOW (each hop is a tested, stable migration path)
**Rollback**: Can rollback to any intermediate version

---

## Version Compatibility Model

### N-1 ↔ N ↔ N+1 Window

Delos maintains a **rolling 3-version compatibility window**:

```
Timeline:
┌────────────┬────────────┬────────────┐
│ Version N-1│ Version N  │ Version N+1│
│  (0.2.2)   │  (0.3.0)   │  (0.3.1)   │
└────────────┴────────────┴────────────┘
      ↑            ↑            ↑
      │←─── Compatible ───→│
                   │←─── Compatible ───→│
      │←────────── NOT Compatible ─────→│
```

**Compatibility Rules**:
1. **Forward compatibility**: Version N can read Version N-1 data
2. **Backward compatibility**: Version N can be read by Version N+1
3. **No skip compatibility**: Version N-1 cannot directly read Version N+1 data

### Mixed-Version Operation

During rolling upgrades, cluster operates with mixed versions:

```
┌─────────────────────────────────────────┐
│ Acceptable (upgrade in progress)       │
├─────────────────────────────────────────┤
│ node1: 0.3.0  ← Just upgraded          │
│ node2: 0.3.0  ← Just upgraded          │
│ node3: 0.2.2  ← Will upgrade next      │
│ node4: 0.2.2                            │
│ node5: 0.2.2                            │
│ node6: 0.2.2                            │
│ node7: 0.2.2                            │
└─────────────────────────────────────────┘
Mixed-version operation: SAFE
Consensus: OPERATIONAL
SLA: Within normal bounds
```

**SLA during mixed-version operation**:
- Transaction success rate: > 99.9%
- Latency increase: < 10% (p95)
- View change duration: < 30s
- No Byzantine incidents

**NOT acceptable** (incompatible versions):
```
┌─────────────────────────────────────────┐
│ NOT Acceptable (version gap > 1)       │
├─────────────────────────────────────────┤
│ node1: 0.3.0  ← Incompatible format    │
│ node2: 0.1.3  ← Cannot read 0.3.0      │
│ node3: 0.1.3                            │
│ ...                                     │
└─────────────────────────────────────────┘
Mixed-version operation: UNSAFE
Consensus: WILL FAIL
```

---

## Breaking Changes by Version

### Version 0.3.0 (2025-02-06)

**Release Focus**: Bug fixes and consensus hardening

**Breaking Changes**: None (backward compatible with 0.2.2)

**Major Changes**:
- Fix: CHOAM committee transition race condition
- Fix: TOCTOU race condition in Ethereal timing round selection
- Fix: Incorrect state transition in Adder.output()
- Feature: Parallel unit consumer in Ethereal (performance improvement)
- Feature: Fine-grained locking in Adder (reduced contention)
- Feature: Bounded Epidemic Gossip (BEG) enhancements (Byzantine defense, circuit breaker, health checks)

**Impact on Operations**:
- No configuration changes required
- No schema migration required
- Rolling upgrade supported
- Rollback safe to 0.2.2

**Migration Path**: Direct upgrade from 0.2.2

---

### Version 0.2.2 (2025-11)

**Release Focus**: BLS signature implementation

**Breaking Changes**: None (backward compatible with 0.2.1)

**Major Changes**:
- Feature: BLS batch signature verification in CHOAM
- Feature: FirefliesWitnessAdapter for KERI threshold mapping
- Feature: Witness service receipt aggregation
- Performance: Improved consensus throughput with parallel processing

**Impact on Operations**:
- BLS verification is feature-flagged (can be disabled for rollback)
- No schema changes
- Rolling upgrade supported
- Rollback safe to 0.2.1

**Migration Path**: Direct upgrade from 0.2.1

---

### Version 0.2.1 (2025-10)

**Release Focus**: Release workflow fixes

**Breaking Changes**: None

**Major Changes**:
- Fix: Release workflow module dependencies
- Docs: Update README for 0.2.0 release

**Impact on Operations**: Minimal (hotfix release)

**Migration Path**: Direct upgrade from 0.1.3

---

### Version 0.1.3 (2025-09)

**Release Focus**: Stability and performance

**Breaking Changes**: None (backward compatible with 0.1.1)

**Major Changes**:
- Perf: Parallel test execution infrastructure
- Fix: Churn and swarm test stability
- Fix: Executor shutdown race conditions

**Impact on Operations**:
- Improved cluster stability under churn
- No configuration changes required

**Migration Path**: Direct upgrade from 0.1.1

---

### Version 0.1.1 (2025-08)

**Release Focus**: CI/CD improvements

**Breaking Changes**: None

**Major Changes**:
- Perf: Parallel test execution (60% time reduction)
- Fix: CI timeouts for resource-constrained environments

**Impact on Operations**: None (internal CI changes only)

**Migration Path**: Direct upgrade from 0.1.0

---

### Version 0.1.0 (2025-08)

**Release Focus**: First minor release milestone

**Breaking Changes**: None (backward compatible with 0.0.10)

**Major Changes**:
- Fix: Streaming join continuation path reseed depth
- Fix: Gateway race conditions in ChurnTest
- Docs: Version consistency across documentation

**Impact on Operations**:
- Improved streaming join reliability
- No configuration changes required

**Migration Path**: Direct upgrade from 0.0.10

---

### Version 0.0.10 (2025-07)

**Release Focus**: Early development release

**Breaking Changes**: Potential (pre-1.0 release, API not stable)

**Major Changes**:
- Development milestones
- Early consensus implementation

**Impact on Operations**:
- Pre-production software
- No upgrade guarantee from earlier versions

**Migration Path**: Fresh install recommended for versions < 0.0.10

---

## Schema Changes

### CHOAM State Format Evolution

Delos uses versioned state formats with automatic migration support.

#### Version 1 (v0.0.6 and earlier)

**Format**:
```
[height: int32] [hash: bytes32] [data: bytes]
```

**Fields**:
- `height` (int32): Block height (max: 2^31-1 = 2,147,483,647)
- `hash` (bytes32): SHA-256 block hash
- `data` (bytes): Arbitrary state data

**Limitations**:
- Block height limited to 2 billion blocks
- No nonce tracking for replay prevention

---

#### Version 2 (v0.0.7 and later)

**Format**:
```
[height: int64] [nonce: int64] [hash: bytes32] [data: bytes]
```

**Fields**:
- `height` (int64): Block height (max: 2^63-1 = 9.2 quintillion)
- `nonce` (int64): Replay prevention nonce
- `hash` (bytes32): SHA-256 block hash
- `data` (bytes): Arbitrary state data

**Enhancements**:
- Unlimited block height (practical infinity)
- Nonce-based replay prevention
- Forward compatible with future versions

---

#### Migration: V1 → V2

**Automatic migration** (via `V1ToV2Migrator`):

```java
// V1 state
int32  height = 42;
bytes32 hash = [...];
bytes  data = [...];

// V2 state (after migration)
int64  height = 42;              // Expanded to 64-bit
int64  nonce = 0;                // Default: 0 (safe)
bytes32 hash = [...];            // Unchanged
bytes  data = [...];             // Unchanged
```

**Migration guarantees**:
- ✓ Idempotent (can run multiple times)
- ✓ Deterministic (all nodes produce same result)
- ✓ Non-destructive (original state preserved)
- ✓ Validated (throws exception on corruption)

**Rollback safety**:
- ✓ Safe if `height < 2^31` and `nonce = 0`
- ✗ Unsafe if `height ≥ 2^31` or `nonce ≠ 0`

---

### Database Schema Changes

**Note**: No database schema changes in versions 0.0.1 through 0.3.0.

Future versions may introduce SQL schema changes via Liquibase migrations. When this occurs:
- Automatic migration during node startup
- Rollback via Liquibase rollback tags
- Pre-flight validation with `liquibase validate`

---

## JVM Compatibility Matrix

Delos requires **Java 25+** for all production versions.

### Version-JVM Compatibility

| Delos Version | Min JVM | Recommended JVM | Max Tested JVM | GraalVM |
|---------------|---------|-----------------|----------------|---------|
| 0.3.0 - 0.3.1 | Java 25 | Java 25 | Java 25 | 25.0.1 |
| 0.2.1 - 0.2.2 | Java 25 | Java 25 | Java 25 | 25.0.1 |
| 0.1.0 - 0.1.3 | Java 25 | Java 25 | Java 25 | 25.0.1 |
| 0.0.1 - 0.0.10| Java 25 | Java 25 | Java 25 | 25.0.1 |

**Why Java 25?**
- Modern concurrency primitives (virtual threads, structured concurrency)
- Performance improvements in GC and JIT
- Security updates
- Pattern matching and record patterns (code clarity)

**GraalVM Support**:
- GraalVM 25.0.1+ required for isolates profile (`-Pisolates`)
- Native image not currently supported (CHOAM requires reflection)
- Standard JVM mode fully supported

### JVM Version Verification

```bash
# Check installed Java version
java -version
# Expected: openjdk version "25" or later

# Verify Maven uses correct JVM
./mvnw --version
# Expected: Java version: 25

# Check GraalVM version (if using isolates)
java -version
# Expected: GraalVM 25.0.1 or later
```

### Upgrading JVM

**Scenario**: Upgrading cluster from Java 24 to Java 25

```bash
# Not required: Delos has always required Java 25
# This section is for future-proofing when JVM upgrades occur

# General procedure (if JVM upgrade becomes necessary):
# 1. Test in staging with new JVM
# 2. Rolling upgrade: Update JVM on each node
# 3. No Delos version change needed if within compatibility window
```

---

## Dependency Changes

### Major Dependency Versions

Consistent across versions 0.2.2, 0.3.0, 0.3.1:

| Dependency | Version | Purpose |
|------------|---------|---------|
| gRPC | 1.77.0 | Service communication |
| Protobuf | 4.28.2 | Message serialization |
| Netty | 4.1.124.Final | Network transport |
| H2 Database | 2.x | Deterministic SQL state |
| JOOQ | 3.x | Type-safe SQL generation |
| JUnit | 5.9.1 | Testing framework |
| SLF4J | 2.0.3 | Logging facade |
| GraalVM | 25.0.1 | Isolates support |

**Stability**: No major dependency changes between 0.2.2 and 0.3.0.

**Security**: Dependencies are regularly updated for CVE patches.

---

## Migration Code Examples

### Example 1: V1 to V2 State Migration

**Scenario**: Upgrading CHOAM checkpoint from v0.0.6 to v0.0.7

```java
package com.hellblazer.delos.choam.migration.example;

import com.hellblazer.delos.choam.migration.MigrationException;
import com.hellblazer.delos.choam.migration.StateMigrator;

import java.io.*;

/**
 * Migrates CHOAM state from V1 (0.0.6) to V2 (0.0.7).
 *
 * Changes:
 * - Expands block height from int32 to int64
 * - Adds nonce field for replay prevention
 */
public class V1ToV2Migrator implements StateMigrator {

    @Override
    public String getSourceVersion() {
        return "0.0.6";
    }

    @Override
    public String getTargetVersion() {
        return "0.0.7";
    }

    @Override
    public void migrate(InputStream source, OutputStream target) throws MigrationException {
        try (var input = new DataInputStream(source);
             var output = new DataOutputStream(target)) {

            // Read V1 state
            var heightV1 = input.readInt();           // int32 (4 bytes)
            var hash = new byte[32];
            input.readFully(hash);                     // bytes32 (32 bytes)
            var data = input.readAllBytes();           // remaining bytes

            // Write V2 state
            output.writeLong(heightV1);                // int32 → int64 (8 bytes)
            output.writeLong(0L);                      // nonce (default: 0)
            output.write(hash);                        // hash (unchanged)
            output.write(data);                        // data (unchanged)

        } catch (IOException e) {
            throw new MigrationException("V1→V2 migration failed", e);
        }
    }

    @Override
    public String getDescription() {
        return "Add nonce field and expand height to 64-bit";
    }
}
```

**Usage**:
```java
// Automatic during node startup
var registry = new MigrationRegistry();
registry.register(new V1ToV2Migrator());
registry.register(new V2ToV1Migrator());  // Rollback support

// Manual migration (pre-flight validation)
java -cp choam.jar com.hellblazer.delos.choam.migration.MigrationCLI \
  --source /var/lib/choam/checkpoints/latest.bin \
  --source-version 0.0.6 \
  --target-version 0.0.7 \
  --output /var/lib/choam/checkpoints/migrated.bin
```

---

### Example 2: Multi-Hop Migration

**Scenario**: Upgrading from 0.0.6 to 0.0.8 (requires 0.0.6 → 0.0.7 → 0.0.8)

```java
var registry = new MigrationRegistry();
registry.register(new V1ToV2Migrator());  // 0.0.6 → 0.0.7
registry.register(new V2ToV3Migrator());  // 0.0.7 → 0.0.8

// Automatic path finding
try (var input = new FileInputStream("checkpoint-v1.bin");
     var output = new FileOutputStream("checkpoint-v3.bin")) {

    registry.migrate(input, "0.0.6", "0.0.8", output);
    // Applies: V1→V2 migration, then V2→V3 migration
}
```

**Output**:
```
INFO: Migrating 0.0.6 → 0.0.8 via 2 hops
DEBUG: Applying migration 1/2: Add nonce field and expand height to 64-bit
DEBUG: Applying migration 2/2: Add transaction nonce field
INFO: Migration completed: 0.0.6 → 0.0.8
```

---

### Example 3: Rollback Migration

**Scenario**: Downgrading from 0.0.7 to 0.0.6

```java
/**
 * Rollback migrator: V2 (0.0.7) → V1 (0.0.6).
 *
 * Safety checks:
 * - Fails if height ≥ 2^31 (cannot fit in int32)
 * - Warns if nonce ≠ 0 (data loss)
 */
public class V2ToV1Migrator implements StateMigrator {

    @Override
    public String getSourceVersion() {
        return "0.0.7";
    }

    @Override
    public String getTargetVersion() {
        return "0.0.6";
    }

    @Override
    public void migrate(InputStream source, OutputStream target) throws MigrationException {
        try (var input = new DataInputStream(source);
             var output = new DataOutputStream(target)) {

            // Read V2 state
            var heightV2 = input.readLong();           // int64
            var nonce = input.readLong();              // int64
            var hash = new byte[32];
            input.readFully(hash);
            var data = input.readAllBytes();

            // Safety checks
            if (heightV2 > Integer.MAX_VALUE) {
                throw new MigrationException(
                    String.format("Height %d exceeds int32 max (%d), cannot rollback",
                                  heightV2, Integer.MAX_VALUE)
                );
            }

            if (nonce != 0) {
                throw new MigrationException(
                    String.format("Nonce %d is non-zero, rollback would lose data", nonce)
                );
            }

            // Write V1 state
            output.writeInt((int) heightV2);           // int64 → int32 (checked)
            output.write(hash);                        // hash (unchanged)
            output.write(data);                        // data (unchanged)

        } catch (IOException e) {
            throw new MigrationException("V2→V1 rollback failed", e);
        }
    }

    @Override
    public String getDescription() {
        return "Rollback: Remove nonce field and shrink height to 32-bit";
    }
}
```

**Rollback Safety**:
```bash
# Validate rollback before attempting
java -cp choam.jar com.hellblazer.delos.choam.migration.MigrationCLI \
  --validate \
  --source /var/lib/choam/checkpoints/latest.bin \
  --source-version 0.0.7 \
  --target-version 0.0.6

# Output: "Validation successful" or error with reason
```

---

## Rollback Safety

### Rollback Windows

Rollback is **safe within 1-version window** if state constraints are satisfied.

| From → To | Safe? | Conditions |
|-----------|-------|------------|
| 0.3.0 → 0.2.2 | ✓ | Always (no state format changes) |
| 0.2.2 → 0.2.1 | ✓ | Always (no state format changes) |
| 0.2.1 → 0.1.3 | ✓ | Always (no state format changes) |
| 0.1.3 → 0.1.1 | ✓ | Always (no state format changes) |
| 0.0.7 → 0.0.6 | ⚠️ | Only if height < 2^31 and nonce = 0 |

**Version 0.0.7 Rollback Constraints**:
```bash
# Check if rollback is safe
java -cp choam.jar com.hellblazer.delos.choam.migration.DebugCLI \
  --inspect /var/lib/choam/checkpoints/latest.bin

# Output:
# height: 1234567       ← Safe (< 2^31)
# nonce: 0              ← Safe (= 0)
# Rollback: SAFE

# Unsafe example:
# height: 3000000000    ← Unsafe (> 2^31)
# nonce: 42             ← Unsafe (≠ 0)
# Rollback: UNSAFE (would lose data)
```

### Rollback Procedure

**Step 1: Verify Rollback Safety**
```bash
# Check current state
curl http://node1:8080/api/state/info
# Output: {"version": "0.3.0", "height": 123456, "nonce": 0}

# Verify rollback compatible
if [[ height < 2147483647 && nonce == 0 ]]; then
  echo "Rollback safe"
else
  echo "Rollback unsafe - data loss risk"
  exit 1
fi
```

**Step 2: Rolling Rollback**
```bash
# Rollback nodes in reverse order (last upgraded → first upgraded)
for node in node7 node6 node5 node4 node3 node2 node1; do
  echo "Rolling back $node..."

  ssh delos@$node "sudo systemctl stop delos"

  # Restore old version
  ssh delos@$node "cp /opt/delos/delos-0.2.2-backup.jar /opt/delos/delos.jar"

  # Migrate state (if needed)
  ssh delos@$node "
    java -cp /opt/delos/delos.jar \
      com.hellblazer.delos.choam.migration.MigrationCLI \
      --source /var/lib/choam/checkpoints/latest.bin \
      --source-version 0.3.0 \
      --target-version 0.2.2 \
      --output /var/lib/choam/checkpoints/migrated.bin
  "

  ssh delos@$node "sudo systemctl start delos"

  # Verify rejoined
  sleep 30
  curl -s http://$node:8080/health | jq '.status'

  sleep 30  # Wait between nodes
done
```

**Step 3: Verify Cluster**
```bash
# All nodes back to 0.2.2
for node in node{1..7}; do
  curl -s http://$node:8080/api/version
done

# Cluster healthy
curl http://node1:8080/ready
```

---

## Testing Migration Paths

### Pre-Production Testing

**Mandatory before production upgrade**:

```bash
# 1. Build both versions
git checkout v0.2.2 && ./mvnw clean install -DskipTests
git checkout v0.3.0 && ./mvnw clean install -DskipTests

# 2. Run automated upgrade test
./mvnw test -pl choam -Dtest=ZeroDowntimeUpgradeTest
# Test covers:
# - Start cluster on v0.2.2
# - Submit transactions continuously
# - Upgrade nodes one by one to v0.3.0
# - Verify zero transaction failures
# - Verify final state consistency
```

### Staging Cluster Testing

```bash
# 1. Deploy 7-node staging cluster on 0.2.2
./scripts/deploy-staging.sh delos-0.2.2 7-node-staging

# 2. Run workload generator (background)
./scripts/run-workload.sh 7-node-staging &
# Generates 1000 TPS for duration of upgrade

# 3. Execute rolling upgrade
./scripts/rolling-upgrade.sh 7-node-staging 0.2.2 0.3.0

# 4. Monitor during upgrade
watch ./scripts/cluster-health.sh 7-node-staging
# Watch for:
# - Transaction success rate > 99.9%
# - Latency increase < 10%
# - No Byzantine incidents

# 5. Run validation after upgrade
./scripts/validate-cluster.sh 7-node-staging
# Verifies:
# - All nodes on 0.3.0
# - State consistent across nodes
# - No data loss

# 6. Soak test (24 hours)
./scripts/soak-test.sh 7-node-staging 24h
# Monitors stability over extended period
```

### Migration Validation Test Suite

```bash
# Run full migration test suite
./mvnw test -pl choam -Dtest="*Migration*,*Upgrade*"

# Tests include:
# - VersionCompatibilityTest: Version detection and compatibility checks
# - VersionCompatibilityMetadataTest: Metadata preservation during migration
# - ZeroDowntimeUpgradeTest: Full rolling upgrade simulation
# - StateMigrator unit tests: V1↔V2 migration correctness
```

---

## Related Documentation

- [UPGRADE_PROCEDURES.md](UPGRADE_PROCEDURES.md) — Step-by-step upgrade procedures (rolling update, blue-green)
- [CHOAM UPGRADE_GUIDE.md](../choam/docs/UPGRADE_GUIDE.md) — CHOAM-specific zero-downtime upgrades with state migration
- [DISASTER_RECOVERY.md](DISASTER_RECOVERY.md) — Backup and restore procedures
- [OPERATIONAL_PROCEDURES.md](OPERATIONAL_PROCEDURES.md) — Day-to-day cluster operations
- [TESTING_GUIDE.md](TESTING_GUIDE.md) — Testing infrastructure and patterns

---

## Appendix: Version Timeline

```
2025-02-06  v0.3.0     Consensus hardening, race condition fixes
2025-11     v0.2.2     BLS signature implementation
2025-10     v0.2.1     Release workflow fixes
2025-09     v0.1.3     Stability and performance
2025-08     v0.1.1     CI/CD improvements
2025-08     v0.1.0     First minor release milestone
2025-07     v0.0.10    Early development release
...         v0.0.1     Initial release
```

**Current development**: v0.3.1-SNAPSHOT (next release)

---

**Last Updated**: 2026-02-08
**Document Version**: 1.0
**Delos Version**: 0.3.1-SNAPSHOT

For operational procedures, see [UPGRADE_PROCEDURES.md](UPGRADE_PROCEDURES.md).
For CHOAM-specific migration details, see [CHOAM UPGRADE_GUIDE.md](../choam/docs/UPGRADE_GUIDE.md).
