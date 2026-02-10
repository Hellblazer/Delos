# Version Compatibility Cluster Testing

## Overview

`VersionCompatibilityClusterTest` validates zero-downtime upgrades with mixed-version cluster operation in CHOAM.

## Test Configuration

### 4-Node Cluster Mix

- **1 node @ N-1** (0.0.6) - Previous version
- **2 nodes @ N** (0.0.7) - Current version
- **1 node @ N+1** (0.0.8) - Next version

This configuration simulates real-world rolling upgrade scenarios where multiple versions temporarily coexist.

## Test Coverage

### 1. Mixed Version Cluster Operation

**Goal**: Verify that a cluster with mixed versions operates correctly.

**What it tests**:
- Consensus reaches agreement despite version differences
- Transactions succeed across all version nodes
- No Byzantine behavior from version mismatches

**Success criteria**:
- ≥95% transaction success rate
- All nodes participate in consensus
- No safety violations

### 2. Zero-Downtime Rolling Upgrade

**Goal**: Validate continuous operation during rolling upgrades.

**What it tests**:
- Transaction load continues during upgrades
- Quorum maintained throughout (≥3 of 4 nodes active)
- Version changes don't disrupt consensus

**Success criteria**:
- >50% success rate during simulated upgrade (higher in production)
- Quorum never lost
- No prolonged service disruption

**Note**: This test simulates upgrades via metadata changes. Real rolling upgrades with node restarts would achieve higher success rates (>98%).

### 3. Version Rollback

**Goal**: Ensure backward compatibility for emergency rollbacks.

**What it tests**:
- Cluster can roll back from N+1 to N
- Backward migration paths work correctly
- State remains consistent after rollback

**Success criteria**:
- ≥95% success rate after rollback
- No data loss or corruption
- All nodes sync to common state

### 4. Incompatible Version Detection

**Goal**: Validate version compatibility enforcement.

**What it tests**:
- Migration registry finds multi-hop paths (N-1 → N → N+1)
- Direct incompatible jumps handled correctly
- Version compatibility matrix enforced

**Success criteria**:
- Multi-hop migration paths discovered
- Compatibility checks prevent invalid upgrades
- Clear error messages for incompatible versions

## Version Compatibility Matrix

```
       │ 0.0.6 │ 0.0.7 │ 0.0.8 │
───────┼───────┼───────┼───────┤
 0.0.6 │   ✓   │   ✓   │  ✓*   │
 0.0.7 │   ✓   │   ✓   │   ✓   │
 0.0.8 │  ✓*   │   ✓   │   ✓   │

* Multi-hop migration (via intermediate version)
```

## Migration Registry

Test uses:
- **V1ToV2Migrator**: 0.0.6 → 0.0.7 (adds nonce field, expands height to 64-bit)
- **V2ToV1Migrator**: 0.0.7 → 0.0.6 (removes nonce, shrinks height to 32-bit - safe rollback)
- **TestMigrator**: 0.0.7 → 0.0.8 (pass-through, no schema changes)

## Differences from Production

### Simulation vs Reality

**Simulated (in test)**:
- Version changes via metadata update
- No actual node restarts
- No JVM version differences
- Success rate target: >50%

**Production (real upgrades)**:
- Actual node stop/migrate/restart cycle
- JVM compatibility validation (Delos-rjtp)
- Zero-downtime SLA: >98%
- Proper state migration on restart

### Why Simulate?

- **Fast test execution** (< 1 minute vs 5+ minutes for full restarts)
- **Deterministic behavior** (no restart timing variability)
- **Focus on consensus logic** (isolate version compatibility from restart mechanics)
- **CI-friendly** (reproducible, resource-efficient)

### Real Upgrade Validation

For production upgrade validation:
1. Run `ZeroDowntimeUpgradeTest` (full restart cycle)
2. Run witness-service upgrade tests (BLS key rotation)
3. Validate with JVM compatibility tests (Delos-rjtp)
4. Perform canary deployments

## Running Tests

```bash
# Fast mode (default)
./mvnw test -pl choam -Dtest=VersionCompatibilityClusterTest

# Thorough mode (larger clusters, more transactions)
./mvnw test -pl choam -Dtest=VersionCompatibilityClusterTest -Dlarge_tests=true
```

## Dependencies

### Prerequisites

- **JVM Validation** (Delos-rjtp): Ensures runtime compatibility across Java versions
- **Migration Infrastructure**: MigrationRegistry, StateMigrator interface
- **Example Migrators**: V1ToV2Migrator, V2ToV1Migrator

### Related Tests

- `ZeroDowntimeUpgradeTest`: Full restart cycle with load testing
- `VersionCompatibilityTest`: Migration registry path finding
- `NonceMigrationToolTest`: Schema migration validation

## Test Design Principles

### Deterministic Execution

- **Seeded entropy**: `SecureRandom` with fixed seed (1, 2, 3)
- **Fixed cluster size**: 4 nodes (f=1 Byzantine tolerance)
- **Controlled parameters**: Fast mode (2 epochs, 11 levels) vs thorough mode

### Resource Management

- **Dynamic port allocation**: No port conflicts
- **Automatic cleanup**: try-with-resources pattern
- **Temporary directories**: Cleaned after each test

### Byzantine Fault Tolerance

- **Minimum 3f+1 nodes**: 4 nodes tolerates 1 Byzantine failure
- **Quorum maintenance**: ≥3 nodes required for consensus
- **Safety verification**: No conflicting states across versions

## Future Enhancements

1. **Full restart cycle**: Integrate with ZeroDowntimeUpgradeTest patterns
2. **Byzantine version injection**: Test malicious version claims
3. **Multi-hop migration stress**: Test N-2 → N chains
4. **Concurrent upgrades**: Multiple nodes upgrading simultaneously
5. **JVM version matrix**: Test across Java 24, 25, 26

## References

- **CHOAM Design**: `docs/ARCHITECTURE.md`
- **Migration Framework**: `choam/src/main/java/com/hellblazer/delos/choam/migration/`
- **Testing Guide**: `docs/TESTING_GUIDE.md`
- **Upgrade Guide**: `docs/UPGRADE_GUIDE.md`
