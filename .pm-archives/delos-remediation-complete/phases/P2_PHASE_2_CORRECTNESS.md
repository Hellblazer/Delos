# Phase 2 - Phase 2: Correctness Fixes

## Overview

**Status**: PENDING
**Priority**: P1-P2
**Target Duration**: Week 5-6
**Task Count**: 7
**Dependencies**: Some depend on Phase 0-1

---

## Objectives

1. Fix Thoth DHT routing logic
2. Add soft-delete filters to Delphinius
3. Complete temporal query primitives
4. Add witness receipt bounds checking
5. Fix thread safety issues in Tron FSM
6. Address Fireflies View lifecycle races
7. Re-enable Ethereal fork detection tests

---

## Task 18: Fix Thoth KeyInterval Predicate Logic

### Bead: 869.18

**Problem**: Incorrect predicate logic in KeyInterval causes wrong DHT routing decisions.

**Impact**:
- KERI key lookups may fail
- Keys routed to wrong nodes
- Inconsistent DHT state

**Fix Strategy**:
1. Review DHT routing algorithm
2. Identify predicate logic errors
3. Add comprehensive interval tests
4. Fix boundary conditions

**Acceptance Criteria**:
- [ ] Predicate logic corrected
- [ ] Boundary cases tested
- [ ] DHT routing verified
- [ ] Key lookup tests pass

---

## Task 19: Add Soft-Delete Filters to Delphinius

### Bead: 869.19

**Problem**: Read methods don't filter soft-deleted records.

**Impact**:
- Deleted permissions may still grant access
- Authorization leaks after deletion
- Inconsistent check vs expand results

**Fix Strategy**:
1. Add deleted_at column if missing
2. Filter deleted records in all read queries
3. Add index on deleted_at for performance
4. Update expand/check to respect deletion

**Implementation**:
```sql
-- Add to all read queries
WHERE deleted_at IS NULL

-- For temporal queries
WHERE deleted_at IS NULL
   OR deleted_at > :timestamp
```

**Acceptance Criteria**:
- [ ] All reads filter deleted records
- [ ] Soft-delete cascade works
- [ ] Performance acceptable
- [ ] Test verifies deleted not returned

---

## Task 20: Complete Temporal Query Primitives

### Bead: 869.20

**Problem**: Temporal query support incomplete for Zanzibar-style consistency.

**Impact**:
- Cannot query historical authorization state
- New Enemy Problem mitigation incomplete
- Audit queries limited

**Fix Strategy**:
1. Implement `checkAt(assertion, timestamp)`
2. Implement `expandAt(object, timestamp)`
3. Add `changesSince(timestamp)` for Watch
4. Add versioned Edge records

**Acceptance Criteria**:
- [ ] checkAt returns state at timestamp
- [ ] expandAt returns historical expansion
- [ ] changesSince returns diff
- [ ] Performance acceptable

---

## Task 21: Add Witness Receipt Bounds Checking

### Bead: 869.21

**Depends On**: 869.7 (KeyEventProcessor)

**Problem**: Missing bounds checking for witness receipts allows invalid data.

**Impact**:
- Buffer overflow potential
- Invalid witness count accepted
- KERI witness threshold circumvented

**Fix Strategy**:
1. Validate receipt count against threshold
2. Check receipt signature bounds
3. Validate witness identifier bounds
4. Add defensive size checks

**Acceptance Criteria**:
- [ ] Receipt count validated
- [ ] Signature bounds checked
- [ ] Invalid receipts rejected
- [ ] Test with malformed data

---

## Task 22: Fix Tron getCurrentState() Thread Safety

### Bead: 869.22

**Problem**: `getCurrentState()` has thread safety violation - concurrent reads/writes race.

**Impact**:
- FSM state corruption under concurrency
- Inconsistent state reads
- Undefined behavior

**Fix Strategy**:
1. Use volatile for state field
2. Or use AtomicReference<State>
3. Ensure all state transitions atomic
4. Add concurrent access tests

**Implementation**:
```java
// Option 1: volatile
private volatile State currentState;

// Option 2: AtomicReference
private final AtomicReference<State> currentState = new AtomicReference<>();

public State getCurrentState() {
    return currentState.get();
}
```

**Acceptance Criteria**:
- [ ] State field properly synchronized
- [ ] Concurrent read/write safe
- [ ] No torn reads possible
- [ ] Stress test passes

---

## Task 23: Fix Fireflies View Lifecycle Race

### Bead: 869.23

**Problem**: Race condition in View lifecycle management (semaphore corruption).

**Note**: May have been partially addressed in Delos-8b7 (Fireflies timeToLive safety). Verify before implementing.

**Impact**:
- View state corruption
- Semaphore count incorrect
- Membership protocol errors

**Fix Strategy**:
1. Review View lifecycle transitions
2. Identify semaphore usage patterns
3. Add proper acquire/release pairing
4. Use try-finally for semaphore release

**Acceptance Criteria**:
- [ ] Issue verified still exists
- [ ] Semaphore usage corrected
- [ ] No leaked permits
- [ ] Concurrent test passes

---

## Task 24: Re-enable Ethereal Fork Detection Tests

### Bead: 869.24

**Depends On**: 869.5-6 (Ethereal validation)

**Problem**: Fork detection tests disabled - cannot verify correct behavior.

**Impact**:
- Fork attacks may go undetected
- Regression risk for fork detection
- Security property unverified

**Fix Strategy**:
1. Review why tests were disabled
2. Fix underlying issues
3. Re-enable tests
4. Add additional fork scenarios

**Acceptance Criteria**:
- [ ] Tests re-enabled
- [ ] All fork scenarios pass
- [ ] No test flakiness
- [ ] CI includes these tests

---

## Definition of Done

### Per Task

- [ ] Issue verified/reproduced
- [ ] Root cause identified
- [ ] Fix implemented
- [ ] Tests added/fixed
- [ ] Code reviewed
- [ ] Bead closed

### Phase Complete

- [ ] All 7 tasks complete
- [ ] Full test suite passes
- [ ] No regressions
- [ ] EXECUTION_STATE.md updated

---

## Estimated Effort

| Task | Research | Test | Implementation | Review | Total |
|------|----------|------|----------------|--------|-------|
| 869.18 KeyInterval | 2h | 2h | 3h | 1h | 8h |
| 869.19 Soft-delete | 1h | 2h | 3h | 1h | 7h |
| 869.20 Temporal queries | 3h | 3h | 6h | 2h | 14h |
| 869.21 Witness bounds | 1h | 2h | 2h | 1h | 6h |
| 869.22 Tron thread safety | 1h | 2h | 2h | 1h | 6h |
| 869.23 View lifecycle | 2h | 2h | 3h | 1h | 8h |
| 869.24 Fork tests | 2h | 4h | 2h | 1h | 9h |
| **Total** | **12h** | **17h** | **21h** | **8h** | **58h** |

---

## Dependencies

```
Phase 0
└── 869.7 (KERI auth) ──→ 869.21 (Witness bounds)
└── 869.5-6 (Ethereal) ──→ 869.24 (Fork tests)

Independent
├── 869.18 (KeyInterval)
├── 869.19 (Soft-delete)
├── 869.20 (Temporal queries)
├── 869.22 (Tron thread safety)
└── 869.23 (View lifecycle)
```

---

*Last Updated: 2025-12-31*
