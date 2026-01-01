# Rollback Strategy for Delos Phase 2 Remediation

## Overview

This document defines rollback criteria, procedures, and safeguards for the Phase 2 remediation work, particularly the CHOAM decomposition which spans 8-10 weeks.

**Related Documents**:
- `.pm/REMEDIATION_PLAN_PHASE2.md` - Master remediation plan
- `.pm/designs/CHOAM_DECOMPOSITION.md` - CHOAM refactoring design

---

## 1. Performance Regression Criteria

### Threshold Definitions

| Metric | Investigate | Rollback | Measurement Method |
|--------|-------------|----------|-------------------|
| Block production latency | >5% increase | >10% increase | p99 in benchmark suite |
| Transaction throughput | >5% decrease | >10% decrease | Sustained TPS under load |
| View rotation time | >10% increase | >20% increase | Time from signal to active |
| Memory footprint | >10% increase | >25% increase | Heap usage under standard load |
| CPU utilization | >10% increase | >20% increase | Average under standard load |

### Performance Baseline Capture

Before starting Phase 3 (CHOAM decomposition):

```bash
# Capture baseline metrics
./mvnw clean install -Dlarge_tests=true -Dbaseline_capture=true

# Metrics captured:
# - choam_block_latency_p99_ms
# - choam_tx_throughput_tps
# - choam_view_rotation_ms
# - choam_heap_usage_mb
# - choam_cpu_percent
```

Baseline stored in: `.pm/metrics/baseline-YYYY-MM-DD.json`

### Regression Detection

After each extraction step:

```bash
# Run performance comparison
./mvnw test -pl choam -Dperf_compare=true -Dbaseline=.pm/metrics/baseline-YYYY-MM-DD.json
```

---

## 2. Rollback Procedures

### 2.1 Phase 0-2: Bug Fixes (Low Risk)

**Strategy**: Standard git revert

Each fix is a single PR. Rollback procedure:
1. Identify failing commit via `git bisect`
2. `git revert <commit-hash>`
3. Create hotfix PR
4. Re-run affected test suite

**Recovery Time**: < 1 hour

---

### 2.2 Phase 3: CHOAM Decomposition (High Risk)

**Strategy**: Incremental extraction with revert capability

Each extraction step is a single PR containing:
- New component class
- Interface definition
- Delegation from CHOAM to new component
- Unit tests for new component
- Integration test verification

#### Rollback per Extraction Step

| Step | Component | Rollback Procedure |
|------|-----------|-------------------|
| 1 | BlockProducer | `git revert <PR-hash>` - removes delegation, restores inline |
| 2 | TransactionRouter | `git revert <PR-hash>` - same pattern |
| 3 | ConsensusCoordinator | `git revert <PR-hash>` - restores Ethereal coupling |
| 4 | ViewManager | `git revert <PR-hash>` - restores view logic in CHOAM |
| 5 | SynchronizationProtocol | `git revert <PR-hash>` - restores sync logic |
| 6 | Circuit Breaker | `git revert <PR-hash>` - removes circuit breaker |

**Critical Constraint**: Each PR must be independently revertible. No cross-PR dependencies within a step.

#### Partial Rollback Scenario

If Step 4 (ViewManager) causes issues but Steps 1-3 are stable:

1. Revert Step 4 PR only
2. Keep Steps 1-3 in place
3. Debug ViewManager extraction separately
4. Re-attempt Step 4 with fixes

This is possible because each component has clean interface boundaries.

---

### 2.3 Consensus Correctness Rollback

**Trigger**: Byzantine test scenarios fail, or consensus divergence detected in testing.

**Procedure**:
1. **IMMEDIATE**: Stop all related development
2. Capture reproduction steps
3. Create minimal test case
4. Revert to last known-good state
5. Root cause analysis with sequential thinking
6. Document in `.pm/incident-reports/`

**Rollback Command**:
```bash
# Revert to last green build
git checkout tags/phase3-checkpoint-N
```

Checkpoints created after each successful extraction step:
- `phase3-checkpoint-1` - After BlockProducer
- `phase3-checkpoint-2` - After TransactionRouter
- etc.

---

## 3. Feature Flag Consideration

For major behavioral changes, consider feature flags:

```java
public class CHOAMConfig {
    // Feature flags for gradual rollout
    private boolean useExtractedBlockProducer = false;
    private boolean useExtractedConsensusCoordinator = false;
    private boolean useCircuitBreaker = false;

    // Runtime toggle (via JMX or config reload)
    public void enableFeature(String feature) {
        switch (feature) {
            case "block-producer" -> useExtractedBlockProducer = true;
            case "consensus-coordinator" -> useExtractedConsensusCoordinator = true;
            case "circuit-breaker" -> useCircuitBreaker = true;
        }
    }
}
```

**Benefits**:
- Runtime rollback without restart
- A/B comparison between old and new paths
- Gradual traffic migration

**Drawbacks**:
- More code complexity during transition
- Both paths must be maintained
- Potential for divergence

**Recommendation**: Use feature flags for ConsensusCoordinator and CircuitBreaker extractions only. BlockProducer and TransactionRouter are lower risk.

---

## 4. Rollback Decision Tree

```
Performance Regression Detected
            |
            v
    Is regression > 10%?
      /           \
    YES            NO
     |              |
     v              v
  ROLLBACK     Investigate
     |          (1-2 days)
     v              |
  Revert PR    Is root cause found?
     |          /        \
     v        YES         NO
  Verify      |           |
  metrics     v           v
     |     Fix +       ROLLBACK
     v     Re-test     (preserve
  Document            findings)
  in incident
  report
```

---

## 5. Consensus Correctness Validation

### Byzantine Test Scenarios

Before merging any CHOAM extraction PR, run:

```bash
# Byzantine fault tolerance tests
./mvnw test -pl choam -Dtest=ByzantineTest -Dlarge_tests=true

# Specific scenarios:
# - 1/3 Byzantine actors sending invalid blocks
# - Network partition simulation
# - Clock skew attacks
# - Replay attacks (requires 869.16 complete)
```

### Consensus Divergence Detection

Add post-extraction verification:

```java
@Test
void verifyNoConsensusDivergence() {
    // Run 5-node cluster
    var nodes = createCluster(5);
    // Submit 1000 transactions
    submitTransactions(nodes, 1000);
    // Wait for consensus
    awaitConsensus(nodes, Duration.ofMinutes(5));
    // Verify all nodes have identical state
    assertAllNodesHaveIdenticalState(nodes);
}
```

---

## 6. Incident Response

### Severity Levels

| Level | Description | Response Time | Rollback Authority |
|-------|-------------|---------------|-------------------|
| SEV-1 | Consensus divergence in production | Immediate | Any engineer |
| SEV-2 | Performance regression >20% | 4 hours | Tech lead |
| SEV-3 | Test failures, no production impact | 24 hours | PR author |

### Incident Report Template

Location: `.pm/incident-reports/YYYY-MM-DD-title.md`

```markdown
# Incident: [Title]

**Date**: YYYY-MM-DD
**Severity**: SEV-X
**Status**: RESOLVED | INVESTIGATING

## Summary
[1-2 sentences]

## Timeline
- HH:MM - Issue detected
- HH:MM - Rollback initiated
- HH:MM - Rollback complete
- HH:MM - Root cause identified

## Root Cause
[Analysis]

## Resolution
[What was done]

## Prevention
[What changes prevent recurrence]

## Beads Affected
- Delos-XXX
```

---

## 7. Checkpoint Schedule

| Milestone | Checkpoint Tag | Verification |
|-----------|----------------|--------------|
| Phase 0 complete | `phase0-complete` | All critical bugs fixed |
| Phase 1 complete | `phase1-complete` | Security hardening done |
| Phase 2 complete | `phase2-complete` | Correctness fixes done |
| BlockProducer extracted | `phase3-checkpoint-1` | Unit + integration pass |
| TransactionRouter extracted | `phase3-checkpoint-2` | Unit + integration pass |
| ConsensusCoordinator extracted | `phase3-checkpoint-3` | Byzantine tests pass |
| ViewManager extracted | `phase3-checkpoint-4` | View rotation tests pass |
| SynchronizationProtocol extracted | `phase3-checkpoint-5` | Sync tests pass |
| Circuit breaker added | `phase3-checkpoint-6` | Failure scenario tests pass |
| Phase 3 complete | `phase3-complete` | Full test suite + perf baseline |

---

## 8. Communication Protocol

### Pre-Rollback

1. Notify in #delos-dev channel
2. Create incident tracking issue
3. Document current state

### During Rollback

1. Update tracking issue with progress
2. Broadcast completion

### Post-Rollback

1. Schedule root cause analysis
2. Update incident report
3. Propose preventive measures
4. Update this document if process needs improvement

---

*Created: 2025-12-31*
*Bead: Delos-a4s*
*Status: Active*
