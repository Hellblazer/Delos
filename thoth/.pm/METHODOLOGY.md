# Thoth Byzantine Fault Tolerance Remediation - Engineering Methodology

## Overview

This document defines the engineering discipline and practices for the Thoth module Byzantine fault tolerance remediation project. The goal is to systematically fix critical security gaps while maintaining code quality, test coverage, and operational readiness.

## Core Principles

### 1. Test-First Byzantine Fault Tolerance

All fixes are validated against Byzantine fault tolerance scenarios BEFORE implementation:

- **Fault Models**: f=1, f=2, f=3 Byzantine nodes in 3f+1 consensus
- **Injection Patterns**: Byzantine node signature forgery, equivocation, timing attacks
- **Validation Points**: Quorum confirmation, fork detection, recovery procedures

**Test Structure**:
```java
// 1. Create Byzantine detection test suite
@Test
void testByzantineNodeDetected_SignatureForgery() {
    // Setup: 3f+1 nodes with f Byzantine
    // Inject: Byzantine node sends forged signatures
    // Verify: Detection within 100ms, system recovers
}

// 2. Validate fix before implementation
// 3. Measure Byzantine detection latency
// 4. Confirm SLA compliance
```

### 2. Metrics-Driven Development

No code changes without metrics infrastructure:

**Phase 1 Pre-Req**: Implement `KerlDhtMetrics` with:
- Operation latency (p50, p95, p99)
- Throughput (ops/sec)
- Error rates and categories
- Byzantine detection triggers and latency
- Validation success/failure counts

**Implementation Quality Gate**: <1% metrics overhead

### 3. Validation Infrastructure First

Cryptographic validation is foundational, not optional:

**Mandatory for all quorum operations**:
```java
// BEFORE: No validation
void store(Digest id, KeyEvent event) {
    queryQuorum(successors);  // Byzantine nodes can return garbage
}

// AFTER: Signature validation required
void store(Digest id, KeyEvent event) {
    List<KeyEvent> responses = queryQuorum(successors);
    validateSignatures(responses);  // MUST include this
    validateWithAni(event);  // Validate via KERI
    validateWithMaat(event);  // Witness validation
}
```

### 4. Operational Observability

Every fix must include production observability:

- **Metrics**: Latency, throughput, error rates
- **Logging**: Warning/Error level for Byzantine behavior
- **Alerts**: Thresholds for Byzantine detection, fork attacks
- **Diagnostics**: State inspection commands for operational investigation

## Phase Structure

### Phase 1: Infrastructure & Detection Foundation (Weeks 1-2)

**Goal**: Fix all 4 critical issues, establish metrics and detection

**Mandatory Deliverables**:
1. `ThothByzantineStateProvider` integration test suite
2. `KerlDhtMetrics` complete implementation
3. Signature validation in all quorum paths
4. `Ani`/`Maat` validator integration points
5. Byzantine detection latency measurement
6. 20+ Byzantine fault tolerance test scenarios

**Enforcement**:
- No code change without corresponding test
- All tests passing before integration
- Metrics overhead verified <1%
- ADR written for each critical fix

### Phase 2: Core Enhancement & Hardening (Weeks 3-4.5)

**Goal**: Fix significant issues, standardize patterns, improve resilience

**Mandatory Deliverables**:
1. Multisig support with consistent quorum semantics
2. Unified error handling framework
3. Resource management (thread pools, cleanup)
4. Fork attack detection and recovery
5. Performance optimizations with benchmarks

**Enforcement**:
- Error handling patterns documented and enforced
- Resource management verified via lifecycle tests
- Benchmarks show 10%+ improvement
- Chaos engineering tests passing

### Phase 3: Integration & Scalability (Weeks 5-7)

**Goal**: Validate all fixes in realistic scenarios, production readiness

**Mandatory Deliverables**:
1. Integration tests with Fireflies + CHOAM
2. Chaos engineering: network partitions, Byzantine cascades
3. Scalability: 10-100+ nodes, 1M+ identifiers
4. Performance SLA validation
5. Deployment runbooks

**Enforcement**:
- 100% integration test pass rate
- SLAs verified: p95 latency ≤100ms, throughput ≥10K ops/sec
- Chaos tests 10+ scenarios, all passing
- Runbooks tested on fresh deployment

## Issue Resolution Process

### 1. Issue Triage

For each issue (critical or significant):

```markdown
# Issue: THOTH-BFT-001 - Byzantine Detection Infrastructure Unused

## Analysis
- Root cause: ThothByzantineStateProvider never instantiated
- Security impact: System cannot detect Byzantine behavior
- Code locations: KerlDHT, DhtService, DhtServer

## Detection Strategy
- Test: Inject Byzantine node with forged signatures
- Expected: Detection within 100ms
- Verify: Metrics increment "byzantineDetections" counter

## Fix Strategy
- Instantiate provider in DhtService constructor
- Hook detection into quorum validation loop
- Add metrics for detection latency
- Document detection semantics in ADR

## Validation
- Test Byzantine signature injection scenario
- Measure detection latency against SLA
- Verify no false positives in normal operation
- Benchmark metrics overhead
```

### 2. Fix Implementation Flow

For each issue:

```
1. Write Byzantine FT test → FAILING (test-first)
2. Write metrics implementation → PARTIAL (framework only)
3. Implement validation logic → GREEN (tests pass)
4. Measure metrics overhead → <1% (performance verified)
5. Add observability (logging, metrics) → COMPLETE
6. Write ADR documenting design → DOCUMENTED
7. Run chaos engineering scenarios → ALL PASSING
8. Update operational runbooks → COMPLETE
```

### 3. Code Review Checklist

Every fix requires:

- [ ] Byzantine FT tests written and passing
- [ ] Signature validation present in all quorum paths
- [ ] Metrics implemented with <1% overhead
- [ ] Validation framework (Ani/Maat) integrated
- [ ] Error handling consistent with framework
- [ ] Logging at appropriate levels (WARN for Byzantine)
- [ ] ADR written explaining design decision
- [ ] Performance benchmarks show no regression
- [ ] Operational runbook updated

## Testing Strategy

### Byzantine Fault Tolerance Testing

**Fault Injection Patterns**:

1. **Signature Forgery**: Byzantine node sends invalid signature
   - Detection: Cryptographic validation catches it
   - Recovery: Node marked as Byzantine, excluded from quorum
   - SLA: Detection within 100ms

2. **Equivocation**: Byzantine node sends conflicting responses
   - Detection: Fork detection identifies inconsistent KERLs
   - Recovery: Majority KERL selected, minority marked as Byzantine
   - SLA: Resolution within 500ms

3. **Timing Attack**: Byzantine node sends responses after consensus
   - Detection: Timeout mechanism or explicit Byzantine signal
   - Recovery: Timeout triggers consensus without Byzantine node
   - SLA: Consensus still valid without timeout node

4. **Cascading Failure**: Multiple Byzantine nodes in sequence
   - Detection: Each node independently detected
   - Recovery: System remains functional with f Byzantine nodes
   - SLA: No data loss, consistency maintained

**Test Harness**:
```java
class ByzantineFaultToleranceTest {
    @Test
    void testStore_WithByzantineSignatureForgery_DetectedAndRecovered() {
        // 1. Setup 3f+1 nodes (f=1 → 4 nodes total)
        cluster = TestCluster.create(4);

        // 2. Inject Byzantine node
        cluster.setByzantine(3);  // Node 3 forges signatures

        // 3. Execute: Store KERL event
        var identifier = cluster.generateIdentifier();
        var event = cluster.generateKeyEvent(identifier);
        cluster.store(identifier, event);

        // 4. Verify: Byzantine node detected, event stored
        assertThat(cluster.getMetrics("byzantineDetections")).isGreaterThan(0);
        assertThat(cluster.retrieve(identifier)).isEqualTo(event);

        // 5. Verify: Detection latency meets SLA
        var detectionLatency = cluster.getMetrics("byzantineDetectionLatency");
        assertThat(detectionLatency).isLessThan(Duration.ofMillis(100));
    }
}
```

### Performance Baselines

Establish and verify against:

| Operation | Current | Target | SLA |
|-----------|---------|--------|-----|
| Store Event | ??? | <100ms p95 | Critical |
| Retrieve Event | ??? | <50ms p95 | Critical |
| Validate Event | ??? | <200ms p95 | High |
| Byzantine Detection | ??? | <100ms | Critical |
| Rebalance (10K ids) | ??? | <10s | Medium |

**Measurement**:
```bash
./mvnw test -pl thoth -Dtest=*PerformanceTest
# Reports baseline comparison to SLA
```

## Metrics & Observability

### Critical Metrics

```java
public interface KerlDhtMetrics {
    // Operations
    Timer storeLatency();
    Timer retrieveLatency();
    Timer validateLatency();
    Counter storeErrorCount();
    Counter retrieveErrorCount();

    // Byzantine Detection
    Counter byzantineDetectionCount();
    Timer byzantineDetectionLatency();
    Counter byzantineNodeCount();
    Gauge byzantineRecoveryTime();

    // Validation
    Counter aniValidationSuccess();
    Counter aniValidationFailure();
    Counter maatWitnessCount();

    // Rebalancing
    Timer rebalanceLatency();
    Counter rebalanceErrorCount();
    Gauge underReplicatedIdentifiers();
}
```

### Observability Levels

| Level | When | Example |
|-------|------|---------|
| TRACE | Development only | Quorum response from each node |
| DEBUG | Detailed investigation | Validation decision logic |
| INFO | Normal operation | Rebalance started/completed |
| WARN | Anomalies | Byzantine detection, validation failure |
| ERROR | Failures | Store operation failed, recovery triggered |

**Logging Pattern**:
```java
// Byzantine detection: WARN level
log.warn("Byzantine node detected: {} - {}",
    nodeId, detectionReason);

// Validation failure: WARN level
log.warn("Event validation failed: {} - {}",
    event.digest(), validationError);

// Recovery: INFO level
log.info("Recovering from Byzantine node: {} - rebalancing",
    nodeId);
```

## Documentation Requirements

### Architecture Decision Records (ADRs)

For each critical issue, create ADR:

```markdown
# ADR-THOTH-001: Byzantine Detection Integration

## Status
DECIDED

## Context
ThothByzantineStateProvider exists but is never instantiated.
System cannot detect Byzantine behavior from colliding nodes.

## Decision
Integrate Byzantine detection into DhtService quorum validation loop.

## Implementation
1. Instantiate ThothByzantineStateProvider in DhtService
2. Check detection results after each quorum response
3. Mark detected Byzantine nodes as unreliable
4. Exclude from future quorum selections
5. Log with metrics for observability

## Consequences
- (+) System detects Byzantine nodes within 100ms
- (+) Automatic Byzantine node exclusion
- (-) Small latency overhead (~1-2%)
- (-) Requires Byzantine detection SLA compliance
```

### Operational Runbooks

For each fix, include runbook:

```markdown
# Runbook: Byzantine Node Detection and Recovery

## Symptoms
- Alerts: HIGH byzantineDetections counter
- Logs: "Byzantine node detected" warnings
- Metrics: High p95 latency for store operations

## Investigation
1. Check metrics dashboard for Byzantine detection timeline
2. Identify which node triggered detection
3. Verify node signatures against root of trust
4. Check network connectivity to Byzantine node

## Recovery
1. Automatically: Node excluded from future quorums
2. Manual: Admin can trigger rebalance with `./delos-cli thoth rebalance`
3. Monitoring: Watch byzantineRecoveryTime metric

## Prevention
1. Regular key rotation (witness rotation)
2. Monitor Byzantine detection rate <1 per hour
3. Alert on cascade failures (multiple nodes detected)
```

## Code Quality Gates

### Test Coverage

- Minimum 85% across critical modules
- 100% coverage for all quorum paths (store/retrieve/rebalance)
- All Byzantine fault scenarios in tests

**Verification**:
```bash
./mvnw clean test -pl thoth
# Reports coverage by class
```

### Code Review Standards

- [ ] Byzantine FT test exists and passes
- [ ] Metrics instrumented with <1% overhead
- [ ] Validation integrated (Ani/Maat)
- [ ] Error handling consistent
- [ ] Logging at appropriate levels
- [ ] ADR explaining design
- [ ] Performance verified
- [ ] Operational runbook updated

### Linting & Static Analysis

```bash
./mvnw clean verify -pl thoth
# Runs: checkstyle, spotbugs, pmd, enforcer
# Must pass before merge
```

## Session Checkpoints

At end of each work session, create checkpoint:

**File**: `.pm/checkpoints/CHECKPOINT-{n}-{title}.md`

```markdown
# Checkpoint {n}: {Phase} - {Title}

## Session Summary
- **Date**: 2026-02-13
- **Duration**: 4 hours
- **Bead**: THOTH-BFT-001
- **Status**: In Progress → Complete

## Work Completed
1. Created Byzantine detection test harness
2. Implemented KerlDhtMetrics skeleton
3. Identified signature validation integration points
4. Measured baseline metrics overhead

## Code Changes
- Modified: KerlDhtTest.java (+150 lines)
- Added: KerlDhtMetricsImpl.java (+200 lines)
- Modified: DhtService.java (+30 lines)

## Test Results
- Byzantine tests: 12/12 passing
- Coverage: Core modules 45%
- Performance: Metrics overhead 0.8%

## Blockers
None

## Next Steps
1. Integrate Ani validator into store() path
2. Add Maat witness validation hook
3. Complete KerlDhtMetrics implementation
4. Run chaos engineering tests

## Metrics Update
```

## Reference Documents

- **execution_state.json**: Current project state, issues, metrics
- **CONTINUATION_PROMPT.md**: Quick resume template
- **INDEX.md**: Navigation guide
- **CONTEXT_PROTOCOL.md**: Handoff protocol to other agents

---

## Enforcement

**Project Manager Review**: Weekly checkpoints, progress against milestones
**Code Review Expert**: Checklist verification before merge
**Test Validator**: Coverage and Byzantine FT test pass rate
**Deep Analyst**: Performance baseline compliance

---

**Last Updated**: 2026-02-13
**Version**: 2.0 - Byzantine Remediation Focus
