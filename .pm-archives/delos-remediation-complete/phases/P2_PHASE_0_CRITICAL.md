# Phase 2 - Phase 0: Critical Issues

## Overview

**Status**: PENDING
**Priority**: P0 - IMMEDIATE
**Target Duration**: Week 1-2
**Task Count**: 10
**Dependencies**: None (all tasks independent)

---

## Objectives

1. Fix CHOAM race conditions that could cause consensus divergence
2. Implement Ethereal validation methods (currently return true unconditionally)
3. Fix Stereotomy/KERI authentication and race conditions
4. Fix critical bugs in Memberships and Model modules

---

## Task 1: CHOAM Block Processing Race Condition

### Bead: 869.1

**Location**: `/choam/src/main/java/com/hellblazer/delos/choam/CHOAM.java` lines 543-602

**Problem**: Block processing in `consume()` has race conditions between validation and acceptance steps.

**Code Context**:
```java
// Lines 543-602 contain the consume() method
// Race window exists between:
// 1. Block validation
// 2. Block acceptance
// 3. State transition
```

**Impact**:
- Blocks may be processed out of order under concurrent access
- Potential consensus divergence between nodes
- State machine corruption possible

**Root Cause Analysis**:
- Multiple threads can enter consume() simultaneously
- Validation and acceptance are not atomic
- No proper memory barriers between operations

**Fix Strategy**:
1. Analyze thread access patterns to consume()
2. Identify minimum critical section
3. Add proper synchronization (prefer locks over synchronized)
4. Use atomic state transitions
5. Add happens-before guarantees

**Test Strategy**:
1. Write concurrent access test with multiple producer threads
2. Verify block ordering under load
3. Add stress test with random delays

**ChromaDB Reference**: `crossref::choam::implementation`
**Paper Reference**: Search `"BFT state machine replication block ordering"`

**Acceptance Criteria**:
- [ ] Race condition identified and documented
- [ ] Fix implemented with minimal locking
- [ ] Concurrent stress test passes
- [ ] No deadlock risk introduced
- [ ] Performance regression < 5%

---

## Task 2: CHOAM View Change Linearization

### Bead: 869.2

**Location**: `/choam/src/main/java/com/hellblazer/delos/choam/CHOAM.java` lines 754-793

**Problem**: View changes may not be properly linearized, causing committee membership confusion.

**Impact**:
- Concurrent view changes can corrupt view state
- Committee membership inconsistencies
- Byzantine actors could exploit race window

**Fix Strategy**:
1. Implement proper linearization point
2. Add fencing tokens or epoch numbers
3. Ensure atomic view state updates
4. Add happens-before for view transitions

**Acceptance Criteria**:
- [ ] View changes properly serialized
- [ ] No concurrent modification of view state
- [ ] Epoch/fencing mechanism in place
- [ ] Tests verify linearization under concurrency

---

## Task 3: CHOAM Silent Consumer Thread Failure

### Bead: 869.3

**Location**: `/choam/src/main/java/com/hellblazer/delos/choam/CHOAM.java` lines 604-627

**Problem**: Consumer thread failures are silently swallowed, causing the system to appear healthy while not processing blocks.

**Impact**:
- System appears healthy but stops processing
- No alerts or recovery mechanism
- Silent data loss potential
- Hard to diagnose in production

**Fix Strategy**:
1. Add proper exception handling with logging
2. Escalate fatal exceptions
3. Implement health check for consumer thread
4. Add automatic restart with exponential backoff
5. Add metrics for consumer health

**Acceptance Criteria**:
- [ ] Consumer failures logged with full stack trace
- [ ] Health check mechanism in place
- [ ] Automatic restart implemented
- [ ] Metrics track consumer state
- [ ] Test verifies recovery from failure

---

## Task 4: CHOAM Unbounded Pending Block Queue

### Bead: 869.4

**Location**: `/choam/src/main/java/com/hellblazer/delos/choam/CHOAM.java` line 91

**Current Code**:
```java
private final PriorityBlockingQueue<HashedCertifiedBlock> pending = new PriorityBlockingQueue<>();
```

**Problem**: No capacity limit on pending blocks queue enables memory exhaustion attacks.

**Attack Vector**:
1. Byzantine node creates many future-dated blocks
2. Honest nodes queue them for later processing
3. Memory exhaustion causes crash
4. DoS achieved

**Impact**:
- Out of memory crash
- Denial of service
- System unavailability

**Fix Strategy**:
```java
// Option 1: Bounded ArrayBlockingQueue (loses priority)
private final BlockingQueue<HashedCertifiedBlock> pending =
    new ArrayBlockingQueue<>(PENDING_BLOCK_LIMIT);

// Option 2: Custom bounded priority queue
private final BoundedPriorityBlockingQueue<HashedCertifiedBlock> pending =
    new BoundedPriorityBlockingQueue<>(PENDING_BLOCK_LIMIT);
```

1. Replace with bounded queue
2. Define appropriate limit (e.g., 1000)
3. Add drop policy for excess blocks
4. Log when blocks are dropped
5. Add metrics for queue depth

**Acceptance Criteria**:
- [ ] Queue has bounded capacity
- [ ] Drop policy defined and tested
- [ ] Dropped blocks logged
- [ ] Metrics track queue depth
- [ ] Memory exhaustion attack blocked

---

## Task 5: Ethereal validate(SignedCommit) Implementation

### Bead: 869.5

**Location**: `/ethereal/src/main/java/com/hellblazer/delos/ethereal/Adder.java` lines 772-775

**Current Code**:
```java
private boolean validate(SignedCommit c) {
    // TODO Auto-generated method stub
    return true;
}
```

**Problem**: **CRITICAL SECURITY VULNERABILITY** - All commits accepted without validation.

**Impact**:
- Malicious actors can submit invalid commits
- Consensus can be manipulated
- Byzantine fault tolerance COMPROMISED
- System security fundamentally broken

**Fix Strategy**:
1. Reference Aleph-BFT paper for commit validation requirements
2. Verify signature on commit message
3. Verify sender is authorized committee member
4. Validate commit content structure
5. Check commit is for valid round/epoch

**Validation Requirements**:
```java
private boolean validate(SignedCommit c) {
    // 1. Verify signature
    if (!verifySignature(c.getSignature(), c.getCommit(), getSigner(c.getCreator()))) {
        return false;
    }
    // 2. Verify sender is committee member
    if (!isCommitteeMember(c.getCreator())) {
        return false;
    }
    // 3. Verify commit structure
    if (!isValidCommitStructure(c.getCommit())) {
        return false;
    }
    // 4. Verify commit is for current/valid round
    if (!isValidRound(c.getCommit().getRound())) {
        return false;
    }
    return true;
}
```

**Paper Reference**: Search `"Aleph BFT commit validation signature round"`

**Acceptance Criteria**:
- [ ] Signature verification implemented
- [ ] Committee membership check added
- [ ] Structure validation added
- [ ] Round/epoch validation added
- [ ] Test with invalid commits rejected
- [ ] Test with valid commits accepted
- [ ] Security review completed

---

## Task 6: Ethereal validate(SignedPreVote) Implementation

### Bead: 869.6

**Location**: `/ethereal/src/main/java/com/hellblazer/delos/ethereal/Adder.java` lines 777-780

**Current Code**:
```java
private boolean validate(SignedPreVote pv) {
    // TODO Auto-generated method stub
    return true;
}
```

**Problem**: **CRITICAL SECURITY VULNERABILITY** - All prevotes accepted without validation.

**Impact**: Same as 869.5 - consensus security compromised.

**Fix Strategy**: Same pattern as 869.5 for SignedPreVote validation.

**Acceptance Criteria**:
- [ ] Same criteria as 869.5
- [ ] PreVote-specific validation logic

---

## Task 7: KeyEventProcessor Event Authentication

### Bead: 869.7

**Location**: `/stereotomy/src/main/java/com/hellblazer/delos/stereotomy/processing/KeyEventProcessor.java` lines 51-71

**Problem**: Key events processed without proper authentication verification.

**Impact**:
- Unauthorized key events could be accepted
- Identity spoofing possible
- KERI security guarantees violated

**Fix Strategy**:
1. Verify event signature against current key state
2. Verify event chain integrity (prior event hash)
3. Validate event sequence number
4. Check witness threshold for applicable events
5. Validate against KERI specification

**KERI Reference**: Search `"KERI key event authentication signature verification"`

**Acceptance Criteria**:
- [ ] Signature verification implemented
- [ ] Event chain integrity checked
- [ ] Sequence validation added
- [ ] Witness threshold enforced
- [ ] Test with forged events rejected

---

## Task 8: StereotomyImpl Key Rotation Race Condition

### Bead: 869.8

**Location**: `/stereotomy/src/main/java/com/hellblazer/delos/stereotomy/StereotomyImpl.java` lines 256-292

**Problem**: Concurrent key rotations may corrupt identifier state.

**Impact**:
- Key state corruption
- Lost key rotation events
- Identity recovery failure
- Potential key compromise

**Fix Strategy**:
1. Add identifier-level locking for rotation operations
2. Use optimistic locking with CAS and retry
3. Ensure atomicity of rotation + notification
4. Add rotation completion verification

**Acceptance Criteria**:
- [ ] Concurrent rotation test passes
- [ ] No state corruption under concurrency
- [ ] Proper locking mechanism in place
- [ ] Atomic rotation completion

---

## Task 9: DynamicContextImpl predecessors() Null Return

### Bead: 869.9

**Problem**: `predecessors()` method returns null instead of empty list in edge cases.

**Impact**:
- NullPointerException in callers
- Ring traversal failures
- Membership protocol errors

**Fix Strategy**:
1. Identify null return paths
2. Return `Collections.emptyList()` instead of null
3. Add `@NonNull` annotation
4. Add defensive null checks in callers

**Acceptance Criteria**:
- [ ] Method never returns null
- [ ] Returns empty list for edge cases
- [ ] All callers handle empty list correctly
- [ ] NPE test demonstrates fix

---

## Task 10: JniBridge.stop() Bug

### Bead: 869.10

**Location**: `/model/src/main/java/com/hellblazer/delos/model/demesnes/JniBridge.java` line 133

**Current Code**:
```java
@Override
public void stop() {
    start(isolateId);  // BUG: Should be stop(isolateId)
}
```

**Problem**: Copy-paste error - stop() calls start() instead of stop().

**Impact**:
- GraalVM isolates NEVER stop
- Resource leak (memory, threads)
- System degradation over time
- Cannot gracefully shutdown

**Fix**:
```java
@Override
public void stop() {
    stop(isolateId);  // FIXED
}
```

**Acceptance Criteria**:
- [ ] stop() calls native stop()
- [ ] Integration test verifies isolate stops
- [ ] Resource cleanup confirmed

---

## Definition of Done

### Per Task

- [ ] Failing test demonstrates issue
- [ ] Fix implemented
- [ ] Test passes
- [ ] All existing tests pass
- [ ] Code reviewed
- [ ] Bead closed

### Phase Complete

- [ ] All 10 tasks complete
- [ ] Full test suite passes (`./mvnw clean install`)
- [ ] No regressions introduced
- [ ] EXECUTION_STATE.md updated
- [ ] Ready for Phase 1

---

## Estimated Effort

| Task | Research | Test | Implementation | Review | Total |
|------|----------|------|----------------|--------|-------|
| 869.1 CHOAM block race | 2h | 2h | 4h | 2h | 10h |
| 869.2 CHOAM view linear | 2h | 2h | 4h | 2h | 10h |
| 869.3 CHOAM consumer | 1h | 2h | 3h | 1h | 7h |
| 869.4 CHOAM queue | 1h | 1h | 2h | 1h | 5h |
| 869.5 Ethereal validate | 4h | 2h | 4h | 2h | 12h |
| 869.6 Ethereal prevote | 2h | 2h | 3h | 1h | 8h |
| 869.7 KERI auth | 4h | 3h | 4h | 2h | 13h |
| 869.8 KERI rotation | 2h | 2h | 3h | 1h | 8h |
| 869.9 predecessors null | 0.5h | 1h | 0.5h | 0.5h | 2.5h |
| 869.10 JniBridge stop | 0h | 1h | 0.25h | 0.25h | 1.5h |
| **Total** | **18.5h** | **18h** | **27.75h** | **12.75h** | **77h** |

**Elapsed Time**: ~2 weeks with single developer (assuming 40h/week)

---

## Parallel Execution

All 10 tasks can execute in parallel as they affect different modules:

| Track | Tasks | Modules |
|-------|-------|---------|
| Track A | 869.1-4 | CHOAM |
| Track B | 869.5-6 | Ethereal |
| Track C | 869.7-8 | Stereotomy |
| Track D | 869.9 | Memberships |
| Track E | 869.10 | Model |

With 5 parallel tracks: ~16h elapsed time (1-2 days with full team)

---

*Last Updated: 2025-12-31*
