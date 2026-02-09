# Nonce Migration Testing Guide

## Overview

This document provides comprehensive testing procedures for the nonce migration from in-memory to persistent storage. These tests must be executed on a staging environment before production deployment.

**CRITICAL**: This migration is a PREREQUISITE for deploying nonce persistence (Delos-0gps). Do not deploy nonce persistence without first successfully completing staging tests.

## Prerequisites

1. Staging environment identical to production
2. CHOAM cluster running with current (in-memory) nonce storage
3. Backup storage with sufficient space (minimum 1GB free)
4. JMX access configured for monitoring
5. Performance baselines established (see `choam/target/baseline-results.json`)

## Test Suite

### Test 1: Basic Migration

**Objective**: Verify migration completes successfully under normal conditions

**Procedure**:
```bash
# 1. Start CHOAM cluster
./start-choam-staging.sh

# 2. Generate baseline nonce state
./generate-nonce-baseline.sh --transactions=10000

# 3. Execute migration
./choam/src/main/scripts/migrate-nonces.sh

# 4. Verify success
grep "Migration successful" /var/log/choam/nonce-migration.log
```

**Acceptance Criteria**:
- ✅ Migration completes without errors
- ✅ All nonces present in new persistent store
- ✅ No nonce values regressed (new >= old)
- ✅ CHOAM cluster remains operational
- ✅ Backup created in `/var/lib/choam/backups/`

### Test 2: Migration with In-Flight Transactions

**Objective**: Verify grace period handles active transactions

**Procedure**:
```bash
# 1. Start background transaction load
./generate-continuous-load.sh &
LOAD_PID=$!

# 2. Execute migration with 30s grace period
GRACE_PERIOD_SECONDS=30 ./choam/src/main/scripts/migrate-nonces.sh

# 3. Stop load generator
kill ${LOAD_PID}

# 4. Verify no transaction failures during migration
grep "Transaction failed" /var/log/choam/transactions.log && exit 1 || exit 0
```

**Acceptance Criteria**:
- ✅ Zero transaction failures during migration
- ✅ All in-flight transactions complete successfully
- ✅ Grace period delta captured and synced
- ✅ Final nonce values >= pre-migration values

### Test 3: Rollback After Failed Migration

**Objective**: Verify rollback restores system to pre-migration state

**Procedure**:
```bash
# 1. Capture pre-migration state
./capture-state.sh --output=/tmp/pre-migration.json

# 2. Simulate migration failure (corrupt new store)
./simulate-migration-failure.sh

# 3. Execute rollback
./choam/src/main/scripts/rollback-nonces.sh --force

# 4. Verify state restored
./compare-states.sh /tmp/pre-migration.json /tmp/post-rollback.json
```

**Acceptance Criteria**:
- ✅ Rollback completes without errors
- ✅ In-memory nonces restored from backup
- ✅ Persistent store cleared
- ✅ CHOAM cluster operational
- ✅ Transaction processing resumes

### Test 4: Performance Regression Check

**Objective**: Verify migration doesn't degrade performance

**Procedure**:
```bash
# 1. Run performance baseline BEFORE migration
./mvnw test -pl choam -Dtest=PerformanceBaselineTest
cp choam/target/baseline-results.json /tmp/pre-migration-perf.json

# 2. Execute migration
./choam/src/main/scripts/migrate-nonces.sh

# 3. Run performance test AFTER migration
./mvnw test -pl choam -Dtest=PerformanceBaselineTest
cp choam/target/baseline-results.json /tmp/post-migration-perf.json

# 4. Compare results
./compare-performance.sh /tmp/pre-migration-perf.json /tmp/post-migration-perf.json
```

**Acceptance Criteria**:
- ✅ p95 latency < baseline + 5%
- ✅ p99 latency < baseline + 10%
- ✅ Throughput > baseline - 10%
- ✅ Memory usage < baseline + 15%

### Test 5: Large Nonce Set Migration

**Objective**: Verify migration scales to production data volumes

**Procedure**:
```bash
# 1. Generate large nonce set (production-scale)
./generate-large-nonce-set.sh --members=1000 --nonces-per-member=1000

# 2. Execute migration with extended grace period
GRACE_PERIOD_SECONDS=60 ./choam/src/main/scripts/migrate-nonces.sh

# 3. Verify all nonces migrated
./verify-nonce-count.sh --expected=1000000
```

**Acceptance Criteria**:
- ✅ All 1,000,000 nonces migrated successfully
- ✅ Migration completes within 5 minutes
- ✅ Memory usage remains stable
- ✅ No timeouts or errors

### Test 6: Rollback Under Load

**Objective**: Verify rollback works while cluster is processing transactions

**Procedure**:
```bash
# 1. Start continuous transaction load
./generate-continuous-load.sh --rate=100 &
LOAD_PID=$!

# 2. Execute migration
./choam/src/main/scripts/migrate-nonces.sh

# 3. Execute rollback (while load continues)
./choam/src/main/scripts/rollback-nonces.sh --force

# 4. Stop load
kill ${LOAD_PID}

# 5. Verify transaction success rate
./verify-success-rate.sh --min-success=99.9
```

**Acceptance Criteria**:
- ✅ Rollback completes with load running
- ✅ Transaction success rate >= 99.9%
- ✅ No data corruption
- ✅ Cluster remains stable

### Test 7: Dry Run Validation

**Objective**: Verify dry-run mode shows correct actions without making changes

**Procedure**:
```bash
# 1. Capture pre-migration state
./capture-state.sh --output=/tmp/pre-dry-run.json

# 2. Execute dry run
DRY_RUN=true ./choam/src/main/scripts/migrate-nonces.sh

# 3. Verify no changes made
./compare-states.sh /tmp/pre-dry-run.json /tmp/post-dry-run.json
```

**Acceptance Criteria**:
- ✅ Dry run completes without errors
- ✅ Log shows intended actions
- ✅ No state changes occurred
- ✅ Backup directory unchanged

## Staging Environment Requirements

### Cluster Configuration
- **Nodes**: 5 (matches production Byzantine threshold)
- **Data Volume**: 10% of production (minimum 100K nonces)
- **Network Latency**: Simulated production latency (50ms p50, 100ms p95)
- **Load**: 50% of production transaction rate

### Monitoring Setup
- JMX enabled on all nodes
- Metrics collection interval: 10s
- Log aggregation configured
- Alerting thresholds match production

### Rollback Readiness
- Automated rollback script tested
- Backup retention: 7 days
- Backup verification automated
- Rollback time < 5 minutes

## Success Criteria

All 7 tests must PASS before production deployment:

| Test | Status | Notes |
|------|--------|-------|
| 1. Basic Migration | ⬜ | - |
| 2. In-Flight Transactions | ⬜ | - |
| 3. Rollback After Failure | ⬜ | - |
| 4. Performance Regression | ⬜ | - |
| 5. Large Nonce Set | ⬜ | - |
| 6. Rollback Under Load | ⬜ | - |
| 7. Dry Run Validation | ⬜ | - |

**Overall Status**: ⬜ NOT TESTED

## Production Deployment Checklist

Before deploying to production:

- [ ] All 7 staging tests passed
- [ ] Rollback script verified on staging
- [ ] Performance within SLA thresholds
- [ ] Backup storage capacity verified (7 days retention)
- [ ] Monitoring dashboards created
- [ ] Runbook updated with migration steps
- [ ] Incident response plan documented
- [ ] Change control approval obtained
- [ ] Deployment window scheduled (low traffic period)
- [ ] Rollback decision criteria defined

## Known Issues and Mitigations

### Issue 1: Grace Period Too Short
**Symptom**: Nonce regressions detected after migration
**Mitigation**: Increase `GRACE_PERIOD_SECONDS` to 60 or 90
**Fix**: Adjust based on p99 transaction latency

### Issue 2: Backup Disk Space Exhausted
**Symptom**: Migration fails during backup creation
**Mitigation**: Ensure backup directory has >= 1GB free
**Fix**: Clean old backups or increase disk space

### Issue 3: JMX Connection Timeout
**Symptom**: Migration tool cannot connect to CHOAM JMX
**Mitigation**: Verify JMX port accessible and credentials correct
**Fix**: Check firewall rules and JMX configuration

## Post-Migration Monitoring

After production migration, monitor these metrics for 24 hours:

- **Nonce Persistence**: Verify nonces survive node restarts
- **Transaction Replay**: Confirm replay protection working (should reject duplicates)
- **Performance**: Compare to baseline (p95, p99, throughput)
- **Error Rate**: Should remain < 0.1%
- **Memory Usage**: Should stabilize within 15 minutes

## Rollback Triggers

Initiate immediate rollback if any of these occur:

1. Transaction success rate drops below 99%
2. p95 latency exceeds baseline + 10%
3. Memory leak detected (unbounded growth)
4. Nonce corruption detected (regression or duplicates)
5. Cluster instability (>1 node crash)

## References

- Migration Tool: `choam/src/main/java/com/hellblazer/delos/choam/migration/NonceMigrationTool.java`
- Migration Script: `choam/src/main/scripts/migrate-nonces.sh`
- Rollback Script: `choam/src/main/scripts/rollback-nonces.sh`
- Performance Baselines: `choam/target/baseline-results.json`
- Rollback Procedures: `choam/ROLLBACK.md`

## Contact

For migration issues or questions, contact:
- Primary: Hellblazer (hal.hildebrand@...)
- Escalation: CHOAM Development Team

---

**Last Updated**: 2026-02-04
**Next Review**: Before Delos-0gps deployment
