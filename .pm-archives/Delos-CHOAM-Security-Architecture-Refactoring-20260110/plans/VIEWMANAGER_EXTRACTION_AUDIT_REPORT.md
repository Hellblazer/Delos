# Plan Audit Report: ViewManager Extraction (Phase 3)

**Audit Date**: 2026-01-09
**Auditor**: plan-auditor (opus)
**Plan**: `/Users/hal.hildebrand/git/Delos/.pm/plans/VIEWMANAGER_EXTRACTION_PLAN_PHASE3.md`
**Overall Score**: 92/100
**Recommendation**: **GO** with minor corrections

---

## Executive Summary

The ViewManager Extraction Plan is **well-designed and comprehensive**. The plan demonstrates deep understanding of CHOAM's threading model, lock ordering requirements, and the complexity of view state management. The phased approach with explicit rollback triggers and DeterminismVerificationTest validation gates is sound.

**Key Strengths**:
- Thorough lock ordering analysis with documented invariants
- Committee inner classes correctly kept in CHOAM
- ReconfigureCallback pattern isolates committee lifecycle from ViewManager
- Extensive test coverage plan (Phase 0)
- DeterminismVerificationTest validation at each phase

**Issues Found**: 2 Medium, 3 Low

---

## Verification Results

### 1. Field Line Number Verification

| Field | Plan Line | Actual Line | Status |
|-------|-----------|-------------|--------|
| `next` | 91 | 91 | VERIFIED |
| `nextViewId` | 92 | 92 | VERIFIED |
| `view` | 102 | 102 | VERIFIED |
| `pendingViews` | 103 | 103 | VERIFIED |
| `ongoingJoin` | 105 | 105 | VERIFIED |
| `viewStateLock` | 106 | 106 | VERIFIED |
| `headLock` | 107 | 107 | VERIFIED |

**Result**: All line numbers are accurate.

### 2. CHOAM.java Line Count Verification

- **Plan states**: 1766 lines
- **Actual**: 1766 lines
- **Result**: VERIFIED

### 3. Existing Test Coverage Verification

Tests already exist for lock ordering and threading:
- `CHOAMThreadAndLockingTest.java` (21,763 bytes) - **Already validates lock ordering invariants**
- `CHOAMConcurrencyTest.java` (19,885 bytes) - Tests concurrent operations
- `DeterminismVerificationTest.java` (12,639 bytes) - Determinism validation

**Result**: Phase 0 test coverage already partially exists. Some tests may be redundant.

### 4. DeterminismVerificationTest Execution

**Result**: Test PASSES (verified via test execution)

---

## Issues Found

### M1: ReconfigureCallback Blocking Risk (MEDIUM)

**Location**: Section 3.2 ReconfigureCallback, Phase 3 reconfigure() implementation

**Problem**: The callback documentation states "must not block indefinitely" but the current CHOAM reconfigure() calls `c.complete()` which blocks for Associate.producer.stop(). Moving this to a callback invoked UNDER viewStateLock creates the same blocking risk the plan identifies in Risk 2.

**Current Code** (Line 767):
```java
final Committee c = current.get();
c.complete();  // This blocks waiting for producer.stop()
```

**Plan Proposal** (P3-3):
```java
viewManager.reconfigure(hash, reconfigure, head.get(),
    (reconf, validators, consensusKeys, viewBlock, pendingContext) -> {
        // ...
        final Committee c = current.get();
        c.complete();  // STILL BLOCKS under viewStateLock!
```

**Impact**: Lock hold time unchanged; blocking risk not mitigated.

**Recommendation**: Either:
1. Move `c.complete()` BEFORE acquiring viewStateLock (outside callback)
2. Add explicit timeout to the callback contract (e.g., 500ms max)
3. Make Producer.stop() async with CompletableFuture

**Fix Required**: Add to P3-2/P3-3 a task to address blocking.

---

### M2: Missing setNextViewId() Method in Interface (MEDIUM)

**Location**: Section 3.1 ViewManager Interface, Section 4.2 Integration Points

**Problem**: The plan identifies `Formation constructor` (Line 1647) as needing `viewManager.setNextViewId(...)` but the ViewManager interface does not include this method.

**Integration Table** (Section 4.2):
```
| `Formation constructor` | 1647 | `nextViewId.set(...)` | `viewManager.setNextViewId(...)` |
```

**Interface** (Section 3.1): No `setNextViewId()` method defined.

**Impact**: Compilation failure in Phase 4.

**Recommendation**: Add to ViewManager interface:
```java
/**
 * Set next view ID during genesis formation.
 * Only called by Formation committee.
 */
void setNextViewId(Digest viewId);
```

---

### L1: Phase 0 Test Redundancy (LOW)

**Problem**: CHOAMThreadAndLockingTest already covers several planned Phase 0 tests:
- Lock ordering verification (noDeadlockBetweenHeadLockAndViewStateLock)
- Lock nesting validation

**Recommendation**: Review CHOAMThreadAndLockingTest before Phase 0 to avoid redundant tests. Consolidate or extend existing tests rather than creating duplicates.

---

### L2: ViewSnapshot.pendingViewCount Access (LOW)

**Location**: Section 3.3 ViewSnapshot, P2-2 Implementation

**Problem**: ViewSnapshot accesses `pendingViews.views.size()` directly, but `views` is a private field in PendingViews.

**Code**:
```java
public ViewSnapshot snapshot() {
    return new ViewSnapshot(
        ...
        pendingViews.views.size()  // Cannot access private field!
    );
}
```

**Recommendation**: Add `size()` method to PendingViews class:
```java
public int size() {
    final var l = lock.readLock();
    try {
        l.lock();
        return views.size();
    } finally {
        l.unlock();
    }
}
```

---

### L3: PendingView Record Location (LOW)

**Location**: Section 5, Phase 5 P5-2/P5-3

**Problem**: Plan proposes moving PendingViews and nextView to ViewManagerImpl, but these are currently public classes used by other components (e.g., Producer uses PendingView via ViewContext).

**Recommendation**: Keep PendingViews and PendingView as public top-level or nested public classes. Document their public API contract.

---

## Dependency Verification

### Required Dependencies

| Dependency | Exists | Location |
|------------|--------|----------|
| Committee interface | YES | CHOAM.java, Committee.java |
| PendingViews class | YES | CHOAM.java:1116-1176 |
| nextView record | YES | CHOAM.java:1194 |
| HashedCertifiedBlock | YES | support/HashedCertifiedBlock.java |
| DeterminismVerificationTest | YES | test/DeterminismVerificationTest.java |

**Result**: All dependencies exist.

### Build Command Verification

```bash
./mvnw test -pl choam  # Standard test
./mvnw test -pl choam -Dtest="DeterminismVerificationTest"  # Specific test
```

**Result**: Commands are valid.

---

## Risk Assessment

| Risk | Plan Assessment | Audit Assessment | Notes |
|------|-----------------|------------------|-------|
| Deadlock: headLock + viewStateLock | LOW | LOW | Correctly analyzed; locks independent |
| Blocking in viewStateLock | MEDIUM | MEDIUM-HIGH | See M1; not fully mitigated |
| Committee.validate() lock acquisition | LOW | LOW | Current implementations safe |
| Race conditions | LOW | LOW | Atomic operations + ViewSnapshot |
| DeterminismVerificationTest failure | LOW | LOW | Test passes currently |

---

## Checklist

### Assumptions Verified
- [x] Line numbers accurate
- [x] CHOAM.java size accurate (1766 lines)
- [x] Fields to extract correctly identified
- [x] Committee classes correctly kept in CHOAM
- [x] Lock ordering analysis correct
- [x] Dependencies exist
- [x] Build commands valid
- [x] DeterminismVerificationTest passes

### Issues Requiring Action
- [ ] M1: Address ReconfigureCallback blocking risk
- [ ] M2: Add setNextViewId() to ViewManager interface
- [ ] L1: Review existing tests for redundancy
- [ ] L2: Add PendingViews.size() method
- [ ] L3: Clarify public API for PendingViews/PendingView

---

## Recommendations

### Before Starting Phase 0

1. **Address M2**: Add `setNextViewId(Digest viewId)` to ViewManager interface in Section 3.1
2. **Review L1**: Compare Phase 0 planned tests with CHOAMThreadAndLockingTest to avoid duplication

### During Phase 3

3. **Address M1**: Modify reconfigure() to either:
   - Call `c.complete()` OUTSIDE the viewStateLock critical section
   - Or add explicit contract: "callback must complete within 500ms"

### During Phase 5

4. **Address L2**: Add `size()` method to PendingViews
5. **Address L3**: Document public API for PendingViews, PendingView, nextView

---

## Conclusion

**Recommendation: GO**

The ViewManager Extraction Plan is well-designed with thorough analysis of the threading model and lock ordering. The phased approach with explicit rollback triggers provides safety. The two medium issues (M1, M2) are straightforward to fix before/during implementation.

**Confidence Level**: 90%

The plan demonstrates excellent understanding of CHOAM's complexity. With the recommended fixes, this refactoring has high probability of success.

---

## Appendix: Audit Evidence

### Test Execution Log

```
DeterminismVerificationTest: PASSED
- 4 replicas achieved consensus
- Block hashes consistent across all members
- No determinism violations detected
```

### Lock Ordering Evidence (from CHOAMThreadAndLockingTest)

```java
/**
 * Key Invariants:
 * 1. consume() uses headLock.writeLock() ONLY (line 564)
 * 2. reconfigure() uses viewStateLock ONLY (line 812)
 * 3. headLock and viewStateLock are NEVER acquired while holding the other
 */
```

### ChromaDB Prior Art

- `analysis::choam::view-state-management` - Comprehensive view state analysis
- `review::architect::choam-design-review-2026` - CHOAM architecture review
- `plan::choam::security-architecture-refactoring-2026-01-09` - Related refactoring plan

---

**Auditor Signature**: plan-auditor (opus)
**Date**: 2026-01-09
