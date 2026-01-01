# Delos Comprehensive Remediation Phase 2

## Executive Summary

This document outlines the comprehensive Phase 2 remediation plan for the Delos distributed system platform. Phase 1 (Delos-868 epic, 23 beads) addressed critical bugs discovered in the initial code review. Phase 2 addresses additional critical issues discovered in a comprehensive architectural review.

**Epic Bead**: `Delos-u9e` - Delos Comprehensive Remediation Phase 2
**Previous Epic**: `Delos-868` - COMPLETED (all 23 beads closed)
**Plan Hardening**: `Delos-a4s` - Plan revisions per audit/critique feedback

### Phase 1 Completed Items (Already Implemented)

The following items from the architectural critique were addressed during Phase 1:

| Issue | Resolution | Bead | Evidence |
|-------|------------|------|----------|
| Content-Change Checks in Oracle | Implemented `checkContentChange()` | 868.13 | `DirectOracle.java:133` |
| Watch API for Oracle | Implemented change notification API | 868.11 | Bead closed |
| KERL Witness Threshold | Enforced in KERL interface | 868.12 | Bead closed |
| HexBloom Crown XOR Context Binding | Added context binding | 868.5 | Bead closed |
| BlockClock Thread Safety | Fixed non-atomic operations | 868.7 | Bead closed |
| Ed25519-X25519 Threat Model | Documented | 868.6 | Bead closed |

### Scope Summary

| Category | Count | Priority | Status |
|----------|-------|----------|--------|
| Critical Race Conditions (CHOAM) | 4 | P0 | NEW |
| Critical Security Stubs (Ethereal) | 2 | P0 | NEW |
| Critical Security (Stereotomy/KERI) | 2 | P0 | NEW |
| Critical Bugs (Memberships, Model) | 2 | P0 | NEW |
| Security Hardening | 8 | P1 | NEW (includes rate limiting) |
| Correctness Fixes | 7 | P1-P2 | NEW |
| Architecture Refactoring | 11 | P2-P3 | NEW |
| **Total New Tasks** | **36** | - | - |

### Timeline Adjustment

**Original Estimate**: 12 weeks
**Revised Estimate**: 16-18 weeks (includes 25% contingency buffer)

Per substantive-critic review, original Phase 0 timeline was unrealistic. Revised timeline:
- Phase 0: Weeks 1-4 (was 1-2)
- Phase 1: Weeks 5-7 (was 3-4)
- Phase 2: Weeks 8-10 (was 5-6)
- Phase 3: Weeks 11-18 (was 7-12)

---

## Phase Structure Overview

### Phase 0: Critical Issues (P0 - IMMEDIATE)
**Target**: Weeks 1-4 (REVISED from 1-2)
**Parallel Execution**: All tasks are independent, but Track A (CHOAM) should be serialized or single-developer

Focus: Fix consensus-breaking race conditions, security stub implementations, and critical bugs that affect system correctness.

**Note**: Phase 0 tasks in CHOAM (869.1-4) modify the same file. Parallel execution requires careful coordination or serialization within Track A.

### Phase 1: Security Hardening (P1)
**Target**: Weeks 5-7 (REVISED from 3-4)
**Dependencies**: Some depend on Phase 0

Focus: Address security vulnerabilities in authentication, validation, access control, and **transaction submission rate limiting** (Delos-qon).

### Phase 2: Correctness Fixes (P1-P2)
**Target**: Weeks 8-10 (REVISED from 5-6)
**Dependencies**: Some depend on Phase 0-1

Focus: Fix functional bugs and complete implementations.

### Phase 3: Architecture Refactoring (P2-P3)
**Target**: Weeks 11-18 (REVISED from 7-12)
**Dependencies**: Requires Phase 0-2 completion

Focus: Decompose CHOAM God Object, improve abstractions, add missing patterns.

**See**: `.pm/ROLLBACK_STRATEGY.md` for regression criteria and rollback procedures.

---

## Phase 0: Critical Issues

### CHOAM Race Conditions

| ID | Title | Location | Severity |
|----|-------|----------|----------|
| 869.1 | Block processing race condition | CHOAM.java:543-602 | CRITICAL |
| 869.2 | View change linearization issue | CHOAM.java:754-793 | CRITICAL |
| 869.3 | Silent consumer thread failure | CHOAM.java:604-627 | HIGH |
| 869.4 | Unbounded pending block queue (OOM) | CHOAM.java:91 | CRITICAL |

#### 869.1: Block Processing Race Condition

**Problem**: Block processing in `consume()` has race conditions between validation and acceptance.

**Location**: `/choam/src/main/java/com/hellblazer/delos/choam/CHOAM.java` lines 543-602

**Impact**:
- Blocks may be processed out of order
- Potential consensus divergence
- State machine corruption

**Fix Strategy**:
1. Add proper synchronization for block state transitions
2. Use atomic state transitions for block acceptance
3. Add happens-before guarantees between validation and execution

**Acceptance Criteria**:
- [ ] Race condition eliminated (verified by stress tests)
- [ ] Block ordering guaranteed under concurrent access
- [ ] No performance regression > 5%

---

#### 869.2: View Change Linearization Issue

**Problem**: View changes may not be properly linearized, causing committee membership confusion.

**Location**: CHOAM.java lines 754-793

**Impact**:
- Concurrent view changes may corrupt state
- Committee membership inconsistencies
- Potential for Byzantine actors to exploit race window

**Fix Strategy**:
1. Implement proper linearization for view transitions
2. Add fencing tokens or epoch numbers
3. Ensure atomic view state updates

---

#### 869.3: Silent Consumer Thread Failure

**Problem**: Consumer thread failures are not properly propagated, causing silent system degradation.

**Location**: CHOAM.java lines 604-627

**Impact**:
- System appears healthy but stops processing blocks
- No alerts or recovery
- Silent data loss potential

**Fix Strategy**:
1. Add proper exception handling with escalation
2. Implement health check for consumer thread
3. Add automatic restart with backoff

---

#### 869.4: Unbounded Pending Block Queue

**Problem**:
```java
private final PriorityBlockingQueue<HashedCertifiedBlock> pending = new PriorityBlockingQueue<>();
```
No capacity limit enables memory exhaustion attacks.

**Location**: CHOAM.java line 91

**Impact**:
- Byzantine node can flood with future blocks
- Out of memory crash
- Denial of service

**Technical Specification**:

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| Queue Size | `max_view_size * 2` | Allow buffering for view transition plus current view |
| Drop Policy | Drop NEWEST | Preserve older blocks closer to consensus |
| Default Limit | 2000 blocks | Conservative default for typical deployments |

```java
// Implementation specification
public class BoundedPendingQueue {
    private final int capacity;
    private final PriorityBlockingQueue<HashedCertifiedBlock> queue;
    private final AtomicLong droppedCount = new AtomicLong(0);

    public BoundedPendingQueue(int maxViewSize) {
        this.capacity = maxViewSize * 2;  // Calculated from view size
        this.queue = new PriorityBlockingQueue<>(capacity);
    }

    public boolean offer(HashedCertifiedBlock block) {
        if (queue.size() >= capacity) {
            // Drop policy: reject newest (incoming block)
            droppedCount.incrementAndGet();
            log.warn("Pending queue full, dropping block {}", block.hash());
            return false;
        }
        return queue.offer(block);
    }

    public long getDroppedCount() {
        return droppedCount.get();
    }
}
```

**Fix Strategy**:
1. Replace with bounded ArrayBlockingQueue or custom bounded priority queue
2. Add drop policy for excess blocks (DROP NEWEST)
3. Add metrics for queue depth monitoring
4. Calculate limit from `max_view_size * 2`
5. Make configurable via Parameters

---

### Ethereal Security Stubs

| ID | Title | Location | Severity |
|----|-------|----------|----------|
| 869.5 | Implement validate(SignedCommit) | Adder.java:772-775 | CRITICAL |
| 869.6 | Implement validate(SignedPreVote) | Adder.java:777-780 | CRITICAL |

#### 869.5 & 869.6: Validation Methods Return True Unconditionally

**Problem**:
```java
private boolean validate(SignedCommit c) {
    // TODO Auto-generated method stub
    return true;
}

private boolean validate(SignedPreVote pv) {
    // TODO Auto-generated method stub
    return true;
}
```

**Location**: `/ethereal/src/main/java/com/hellblazer/delos/ethereal/Adder.java` lines 772-780

**Impact**:
- **CRITICAL SECURITY VULNERABILITY**
- Malicious actors can submit invalid commits/prevotes
- Consensus can be manipulated
- Byzantine fault tolerance COMPROMISED

**Fix Strategy**:
1. Reference Aleph-BFT paper for validation requirements
2. Implement signature verification for SignedCommit
3. Implement signature verification for SignedPreVote
4. Validate message structure and content
5. Validate sender is authorized committee member

**Paper Reference**: Search mixedbread `"Aleph BFT commit prevote validation signature"`

---

### Stereotomy/KERI Vulnerabilities

| ID | Title | Location | Severity |
|----|-------|----------|----------|
| 869.7 | Event authentication not validated | KeyEventProcessor.java:51-71 | CRITICAL |
| 869.8 | Key rotation race condition | StereotomyImpl.java:256-292 | HIGH |

#### 869.7: Event Authentication Not Validated

**Problem**: Key events processed without proper authentication verification.

**Impact**:
- Unauthorized key events could be accepted
- Identity spoofing possible
- KERI security guarantees violated

**Fix Strategy**:
1. Implement proper signature verification for all key events
2. Verify event chain integrity (prior event hash)
3. Validate against KERI specification

---

#### 869.8: Key Rotation Race Condition

**Problem**: Concurrent key rotations may corrupt identifier state.

**Impact**:
- Key state corruption
- Lost key rotation events
- Identity recovery failure

**Fix Strategy**:
1. Add proper locking for rotation operations
2. Use optimistic locking with retry
3. Ensure atomicity of rotation + notification

---

### Memberships Critical Bug

| ID | Title | Location | Severity |
|----|-------|----------|----------|
| 869.9 | predecessors() can return null | DynamicContextImpl.java | HIGH |

#### 869.9: predecessors() Returns Null

**Problem**: Method returns null instead of empty list in edge cases.

**Impact**:
- NullPointerException in callers
- Ring traversal failures
- Membership protocol errors

**Fix Strategy**:
1. Return empty list instead of null
2. Add @NonNull annotation
3. Add defensive null checks in callers

---

### Model Module Bugs

| ID | Title | Location | Severity |
|----|-------|----------|----------|
| 869.10 | JniBridge.stop() calls start() | JniBridge.java:132-134 | CRITICAL |

#### 869.10: JniBridge.stop() Calls start()

**Problem**:
```java
@Override
public void stop() {
    start(isolateId);  // BUG: Should be stop(isolateId)
}
```

**Location**: `/model/src/main/java/com/hellblazer/delos/model/demesnes/JniBridge.java` line 133

**Impact**:
- GraalVM isolates NEVER stop
- Resource leak (memory, threads)
- System degradation over time
- Cannot gracefully shutdown

**Fix Strategy**:
1. Change `start(isolateId)` to `stop(isolateId)`
2. Add integration test for stop behavior
3. Verify native library has correct stop() implementation

---

## Phase 1: Security Hardening

| ID | Title | Depends On | Severity | Bead |
|----|-------|------------|----------|------|
| 869.11 | Gorgoneion replay attack protection | 869.7 | HIGH | Delos-bfb |
| 869.12 | Fix weak default verifier | - | HIGH | Delos-fph |
| 869.13 | Fix empty certificate validator leak | - | HIGH | Delos-nrp |
| 869.14 | Fix streaming RPC rate limiting bypass | - | MEDIUM | Delos-amc |
| 869.15 | Secure SQL-State Script compilation | - | HIGH | Delos-0b4 |
| 869.16 | Implement transaction replay prevention | 869.1-4 | HIGH | Delos-mqv |
| 869.17 | Implement certificate revocation | - | MEDIUM | Delos-9um |
| **869.18** | **Transaction submission rate limiting** | - | **HIGH** | **Delos-qon** |

### 869.18: Transaction Submission Rate Limiting (NEW)

**Bead**: `Delos-qon`

**Problem**: CHOAM.submit() method has no rate limiting, no quotas, no admission control. Byzantine or misbehaving clients can flood the consensus with transactions.

**Location**: `/choam/src/main/java/com/hellblazer/delos/choam/CHOAM.java` line 914+

**Impact**:
- DoS via transaction flooding
- Queue exhaustion
- Consensus stall from overload
- Unfair resource allocation

**Fix Strategy**:
1. Add per-client transaction rate limiter
2. Add global admission control threshold
3. Implement token bucket or sliding window algorithm
4. Add backpressure signaling to clients
5. Add metrics for rate limiting events

**Technical Specification**:
```java
public class TransactionAdmissionController {
    // Per-client rate limits
    private final int clientTxPerSecond = 100;  // Configurable
    private final int clientBurstSize = 50;     // Token bucket burst

    // Global limits
    private final int globalTxPerSecond = 10000; // Total system throughput
    private final int queueHighWaterMark = 5000; // Backpressure trigger

    // Rate limiter implementation
    private final Map<Digest, RateLimiter> clientLimiters = new ConcurrentHashMap<>();
    private final RateLimiter globalLimiter = RateLimiter.create(globalTxPerSecond);

    public AdmissionResult admit(Transaction tx, Digest client) {
        // Check global limit first
        if (!globalLimiter.tryAcquire()) {
            return AdmissionResult.GLOBAL_LIMIT_EXCEEDED;
        }
        // Check per-client limit
        var clientLimiter = clientLimiters.computeIfAbsent(
            client, k -> RateLimiter.create(clientTxPerSecond));
        if (!clientLimiter.tryAcquire()) {
            return AdmissionResult.CLIENT_LIMIT_EXCEEDED;
        }
        return AdmissionResult.ADMITTED;
    }
}
```

**Acceptance Criteria**:
- [ ] Per-client rate limiting implemented
- [ ] Global admission control working
- [ ] Backpressure signaling added
- [ ] Metrics track rate limit events
- [ ] DoS test demonstrates protection
- [ ] Configuration exposed for tuning

### 869.11: Gorgoneion Replay Attack Protection

**Problem**: No protection against replay of attestation messages.

**Impact**: Attacker can replay old attestations to gain access.

**Fix**: Add nonce and timestamp validation.

---

### 869.12: Weak Default Verifier

**Problem**: Default verifier accepts ALL attestations without validation.

**Impact**: Bypasses attestation security entirely.

**Fix**: Require explicit verifier configuration, remove permissive default.

---

### 869.13: Empty Certificate Validator Leak

**Problem**: Empty/permissive certificate validators can leak to production.

**Impact**: MTLS security bypassed.

**Fix**: Add production mode checks, fail-closed defaults.

---

### 869.14: Streaming RPC Rate Limiting Bypass

**Problem**: Streaming RPCs bypass rate limiting.

**Impact**: DoS via streaming abuse.

**Fix**: Apply rate limits to streaming messages.

---

### 869.15: SQL-State Script Compilation Security

**Problem**: Arbitrary code execution possible via Script compilation.

**Impact**: Remote code execution vulnerability.

**Fix**: Sandbox script execution, restrict capabilities.

---

### 869.16: Transaction Replay Prevention

**Problem**: No nonce checking, timestamp validation, or transaction deduplication.

**Impact**: Double-spend, DoS via replay.

**Technical Specification**:

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| Nonce Location | Transaction protobuf `nonce` field | Client-generated, monotonic per client |
| Cache TTL | 5 minutes | `2 * max_clock_skew + max_tx_latency` |
| Timestamp Window | +/- 2 minutes | Allow for clock skew without vulnerability |
| Cache Implementation | ConcurrentHashMap with scheduled cleanup | Memory-bounded, high throughput |

```java
// Implementation specification
public class TransactionDeduplicator {
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration TIMESTAMP_WINDOW = Duration.ofMinutes(2);

    // Cache: txHash -> expirationTime
    private final ConcurrentHashMap<Digest, Instant> seenTransactions = new ConcurrentHashMap<>();

    public ValidationResult validate(Transaction tx) {
        // 1. Validate timestamp is within acceptable window
        var now = Instant.now();
        var txTime = Instant.ofEpochMilli(tx.getTimestamp());
        if (txTime.isBefore(now.minus(TIMESTAMP_WINDOW))) {
            return ValidationResult.rejected("Transaction timestamp too old");
        }
        if (txTime.isAfter(now.plus(TIMESTAMP_WINDOW))) {
            return ValidationResult.rejected("Transaction timestamp in future");
        }

        // 2. Check for replay (nonce + client combination)
        var dedupeKey = computeDedupeKey(tx);
        var expiration = now.plus(CACHE_TTL);
        var previous = seenTransactions.putIfAbsent(dedupeKey, expiration);
        if (previous != null) {
            return ValidationResult.rejected("Duplicate transaction");
        }

        return ValidationResult.accepted();
    }

    private Digest computeDedupeKey(Transaction tx) {
        // Hash of (client_id, nonce) for deduplication
        return DigestAlgorithm.DEFAULT.digest(
            tx.getSource().toByteArray(),
            ByteBuffer.allocate(8).putLong(tx.getNonce()).array()
        );
    }

    // Scheduled cleanup task
    public void cleanupExpired() {
        var now = Instant.now();
        seenTransactions.entrySet().removeIf(e -> e.getValue().isBefore(now));
    }
}
```

**Nonce Protocol**:
1. Client generates monotonically increasing nonce per session
2. Server tracks highest seen nonce per client
3. Rejects nonces <= highest seen (prevents replay)
4. Gap handling: Allow small gaps (configurable, default 100) for out-of-order delivery

**Fix**: Implement nonce/timestamp validation, dedupe cache with TTL-based eviction.

---

### 869.17: Certificate Revocation

**Problem**: No CRL/OCSP checking for member certificates.

**Impact**: Compromised members cannot be revoked.

**Fix**: Add revocation checking infrastructure.

---

## Phase 2: Correctness Fixes

| ID | Title | Depends On | Severity |
|----|-------|------------|----------|
| 869.18 | Fix Thoth KeyInterval predicate logic | - | MEDIUM |
| 869.19 | Add soft-delete filters to Delphinius | - | MEDIUM |
| 869.20 | Complete temporal query primitives | - | MEDIUM |
| 869.21 | Add witness receipt bounds checking | 869.7 | MEDIUM |
| 869.22 | Fix Tron getCurrentState() thread safety | - | MEDIUM |
| 869.23 | Fix Fireflies View lifecycle race | - | MEDIUM |
| 869.24 | Re-enable Ethereal fork detection tests | 869.5-6 | LOW |

### 869.18: Thoth KeyInterval Predicate Logic

**Problem**: Incorrect predicate logic in KeyInterval causes wrong DHT routing.

**Impact**: KERI key lookups may fail or return wrong results.

---

### 869.19: Delphinius Soft-Delete Filters

**Problem**: Read methods don't filter soft-deleted records.

**Impact**: Deleted permissions may still grant access.

---

### 869.20: Temporal Query Primitives

**Problem**: Temporal query support incomplete.

**Impact**: Cannot properly query historical authorization state.

---

### 869.21: Witness Receipt Bounds Checking

**Problem**: Missing bounds checking for witness receipts.

**Impact**: Potential buffer overflow or invalid data acceptance.

---

### 869.22: Tron Thread Safety

**Problem**: `getCurrentState()` has thread safety violation.

**Impact**: Race conditions in FSM state reads.

---

### 869.23: Fireflies View Lifecycle Race

**Problem**: Race condition in View lifecycle management (semaphore corruption).

**Impact**: View state corruption under concurrent operations.

---

### 869.24: Re-enable Fork Detection Tests

**Problem**: Fork detection tests disabled.

**Impact**: Cannot verify fork detection works correctly.

---

## Phase 3: Architecture Refactoring

### CHOAM Decomposition

| ID | Title | Depends On |
|----|-------|------------|
| 869.25 | Extract BlockProducer from CHOAM | 869.1-4 |
| 869.26 | Extract ConsensusCoordinator | 869.25 |
| 869.27 | Extract ViewManager | 869.26 |
| 869.28 | Extract TransactionRouter | 869.26 |
| 869.29 | Extract SynchronizationProtocol | 869.27 |
| 869.30 | Add circuit breaker for sync | 869.29 |

See `.pm/designs/CHOAM_DECOMPOSITION.md` for detailed design.

### Interface Abstractions

| ID | Title | Depends On |
|----|-------|------------|
| 869.31 | Replace SQLException with OracleException | - |
| 869.32 | Add StateExecutor abstraction | 869.25 |
| 869.33 | Add Domain Builder pattern | - |
| 869.34 | Abstract X509 from Member interface | - |
| 869.35 | Fix reflection method ordering | - |

---

## Dependency Graph

```
Phase 0 (Parallel - No Dependencies)
├── 869.1 (CHOAM block race) ──┐
├── 869.2 (CHOAM view linear) ─┼─→ 869.16 (Replay prevention)
├── 869.3 (CHOAM consumer)    ─┤    └─→ 869.25 (BlockProducer)
├── 869.4 (CHOAM queue)       ─┘         └─→ 869.26 → 869.27 → 869.29 → 869.30
├── 869.5 (Ethereal validate) ──┬─→ 869.24 (Fork tests)
├── 869.6 (Ethereal validate) ──┘
├── 869.7 (KERI auth) ──────────┬─→ 869.11 (Gorgoneion replay)
│                               └─→ 869.21 (Witness bounds)
├── 869.8 (KERI rotation race)
├── 869.9 (predecessors null)
└── 869.10 (JniBridge stop)

Phase 1 (Security)
├── 869.11 (Gorgoneion replay) [blocked by 869.7]
├── 869.12 (Weak verifier)
├── 869.13 (Cert validator leak)
├── 869.14 (RPC rate limit)
├── 869.15 (Script sandbox)
├── 869.16 (Tx replay) [blocked by 869.1-4]
└── 869.17 (Cert revocation)

Phase 2 (Correctness)
├── 869.18 (Thoth KeyInterval)
├── 869.19 (Soft-delete)
├── 869.20 (Temporal queries)
├── 869.21 (Witness bounds) [blocked by 869.7]
├── 869.22 (Tron thread safety)
├── 869.23 (View lifecycle race)
└── 869.24 (Fork tests) [blocked by 869.5-6]

Phase 3 (Architecture)
├── 869.25 (BlockProducer) [blocked by 869.1-4]
├── 869.26 (ConsensusCoordinator) [blocked by 869.25]
├── 869.27 (ViewManager) [blocked by 869.26]
├── 869.28 (TransactionRouter) [blocked by 869.26]
├── 869.29 (SyncProtocol) [blocked by 869.27]
├── 869.30 (Circuit breaker) [blocked by 869.29]
├── 869.31 (OracleException)
├── 869.32 (StateExecutor) [blocked by 869.25]
├── 869.33 (Domain Builder)
├── 869.34 (Member abstraction)
└── 869.35 (Reflection ordering)
```

### Critical Path

The longest dependency chain determining minimum remediation time:

```
869.1-4 (CHOAM fixes) → 869.25 (BlockProducer) → 869.26 (Coordinator) →
869.27 (ViewManager) → 869.29 (SyncProtocol) → 869.30 (Circuit breaker)
```

**Critical Path Estimate**: ~8-10 weeks for CHOAM refactor chain

---

## Risk Assessment

### Critical Risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Ethereal validation stubs exploited | HIGH | CRITICAL | Immediate Phase 0 priority |
| CHOAM race causes consensus fork | MEDIUM | CRITICAL | Comprehensive testing |
| JniBridge resource leak in production | HIGH | HIGH | Simple fix, immediate deploy |

### High Risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| CHOAM refactor introduces bugs | MEDIUM | HIGH | Incremental extraction, extensive testing |
| Script execution RCE exploited | LOW | CRITICAL | Sandbox before production use |
| Transaction replay attacks | MEDIUM | HIGH | Phase 1 priority |

### Medium Risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Cascading test failures | MEDIUM | MEDIUM | TDD approach |
| Performance regression | LOW | MEDIUM | Benchmark before/after |

---

## Parallel Execution Opportunities

### Maximum Parallelism Points

1. **Phase 0**: All 10 critical fixes can run in parallel (different modules)
2. **Phase 1**: 5 of 7 security tasks can run in parallel
3. **Phase 2**: All 7 correctness tasks can run in parallel
4. **Phase 3**: CHOAM tasks are sequential; other tasks parallel

### Suggested Resource Allocation

| Track | Focus | Tasks |
|-------|-------|-------|
| Track A | CHOAM | 869.1-4, 869.16, 869.25-30 |
| Track B | Ethereal | 869.5-6, 869.24 |
| Track C | Stereotomy/KERI | 869.7-8, 869.21 |
| Track D | Security | 869.11-15, 869.17 |
| Track E | Misc | 869.9-10, 869.18-20, 869.22-23, 869.31-35 |

---

## Verification Methodology

### Per-Fix Requirements

1. **TDD Discipline**: Write tests FIRST before implementation
2. **Compilation Gate**: Code must compile with all tests
3. **Review Gate**: Use code-review-expert agent
4. **Documentation**: Update docs alongside code

### Security Fixes

1. **Threat Model**: Document threat before fix
2. **Attack Test**: Write test demonstrating vulnerability
3. **Defense Test**: Verify fix blocks attack
4. **Security Review**: Extra scrutiny for security code

### Integration Testing

```bash
./mvnw clean install                      # Full build
./mvnw clean install -Dlarge_tests=true   # Full test suite
./mvnw test -pl <module>                  # Module tests
```

---

## Related Documents

### ChromaDB
- `critique::master::delos-codebase-review-2025-12-30` - Initial review
- `critique::architecture::delos-comprehensive-2025-12-31` - Architecture critique
- `crossref::*::implementation` - Module mappings

### Mixedbread Store "delos"
- 18 foundational papers
- Search for algorithm specifications

### PM Infrastructure
- `.pm/phases/P2_PHASE_*.md` - Phase details
- `.pm/designs/CHOAM_DECOMPOSITION.md` - CHOAM design
- `.pm/RISK_REGISTER.md` - Risk tracking

---

## Approval

**Created**: 2025-12-31
**Author**: Strategic Planner Agent
**Source**: Comprehensive architectural review
**Status**: AWAITING plan-auditor review

---

## Appendix: Bead Summary

| ID | Title | Type | Priority | Dependencies | Status |
|----|-------|------|----------|--------------|--------|
| Delos-869 | Phase 2 Remediation Epic | epic | P0 | - | pending |
| 869.1 | CHOAM block processing race | bug | P0 | - | ready |
| 869.2 | CHOAM view change linearization | bug | P0 | - | ready |
| 869.3 | CHOAM consumer thread failure | bug | P0 | - | ready |
| 869.4 | CHOAM unbounded pending queue | bug | P0 | - | ready |
| 869.5 | Ethereal validate(SignedCommit) | bug | P0 | - | ready |
| 869.6 | Ethereal validate(SignedPreVote) | bug | P0 | - | ready |
| 869.7 | KeyEventProcessor authentication | bug | P0 | - | ready |
| 869.8 | StereotomyImpl rotation race | bug | P0 | - | ready |
| 869.9 | DynamicContext predecessors null | bug | P0 | - | ready |
| 869.10 | JniBridge stop() bug | bug | P0 | - | ready |
| 869.11 | Gorgoneion replay protection | task | P1 | 869.7 | blocked |
| 869.12 | Weak default verifier | task | P1 | - | ready |
| 869.13 | Empty cert validator leak | task | P1 | - | ready |
| 869.14 | Streaming RPC rate limit | task | P1 | - | ready |
| 869.15 | Script compilation security | task | P1 | - | ready |
| 869.16 | Transaction replay prevention | task | P1 | 869.1-4 | blocked |
| 869.17 | Certificate revocation | task | P1 | - | ready |
| 869.18 | Thoth KeyInterval predicate | bug | P2 | - | ready |
| 869.19 | Delphinius soft-delete filters | task | P2 | - | ready |
| 869.20 | Temporal query primitives | task | P2 | - | ready |
| 869.21 | Witness receipt bounds | task | P2 | 869.7 | blocked |
| 869.22 | Tron thread safety | bug | P2 | - | ready |
| 869.23 | Fireflies View lifecycle | bug | P2 | - | ready |
| 869.24 | Re-enable fork tests | chore | P2 | 869.5-6 | blocked |
| 869.25 | Extract BlockProducer | feature | P3 | 869.1-4 | blocked |
| 869.26 | Extract ConsensusCoordinator | feature | P3 | 869.25 | blocked |
| 869.27 | Extract ViewManager | feature | P3 | 869.26 | blocked |
| 869.28 | Extract TransactionRouter | feature | P3 | 869.26 | blocked |
| 869.29 | Extract SynchronizationProtocol | feature | P3 | 869.27 | blocked |
| 869.30 | Add circuit breaker | feature | P3 | 869.29 | blocked |
| 869.31 | OracleException abstraction | task | P3 | - | ready |
| 869.32 | StateExecutor abstraction | feature | P3 | 869.25 | blocked |
| 869.33 | Domain Builder pattern | feature | P3 | - | ready |
| 869.34 | Member X509 abstraction | feature | P3 | - | ready |
| 869.35 | Reflection method ordering | bug | P3 | - | ready |
