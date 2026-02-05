# CHOAM Lock Ordering Documentation

## Executive Summary

**CRITICAL INVARIANT**: `headLock` and `viewStateLock` have **DISJOINT CRITICAL SECTIONS**. They are **NEVER** acquired while holding the other, eliminating deadlock risk.

**Created**: 2026-02-04 (Baseline for CHOAMStateManager extraction)

**Purpose**: Document current lock ordering to ensure CHOAMStateManager extraction preserves Byzantine fault tolerance safety properties.

---

## Lock Declarations

From `CHOAM.java`:

```java
// Line 107: View reconfiguration lock
private final ReentrantLock viewStateLock = new ReentrantLock();

// Line 108: Blockchain head/view block lock
private final ReadWriteLock headLock = new ReentrantReadWriteLock();
```

---

## Lock Usage Patterns

### headLock (ReadWriteLock)

**Purpose**: Protects blockchain head, genesis, view blocks, and pending queue

**Type**: `ReadWriteLock` (supports multiple readers, single writer)

**Critical Section**: `consume()` method (lines 642-679)

```java
private void consume(HashedCertifiedBlock next) {
    headLock.writeLock().lock();  // Line 642
    try {
        // Block acceptance logic:
        // - Check block height vs head
        // - Validate view consistency
        // - Update head reference
        // - Enqueue to pending if future view
    } finally {
        headLock.writeLock().unlock();  // Line 679
    }
}
```

**Protected State**:
- `AtomicReference<HashedCertifiedBlock> genesis` (line 90)
- `AtomicReference<HashedCertifiedBlock> head` (line 91)
- `AtomicReference<HashedCertifiedBlock> view` (line 103)
- `BoundedPriorityBlockingQueue<HashedCertifiedBlock> pending` (line 95)

**Lock Acquisition Sites**: 1 site
- `CHOAM.java:642` - consume() method (write lock)

---

### viewStateLock (ReentrantLock)

**Purpose**: Protects view reconfiguration state (nextViewId, pendingViews)

**Type**: `ReentrantLock` (exclusive lock)

**Critical Section**: View reconfiguration method (lines 999-1015)

```java
// Phase 1 (locked): Collect callbacks for deterministic computation
List<Runnable> callbacks;
viewStateLock.lock();  // Line 999
try {
    log.info("Setting next view id: {} on: {}", hash, params.member().getId());
    nextViewId.set(hash);

    // Update pending views
    var advanced = pendingViews.get().advance();
    pendingViews.set(advanced);
    var pv = advanced.last();
    if (pv != null) {
        params.context().setContext(pv.context());
    }

    // Collect callbacks for execution outside lock
    callbacks = collectReconfigureCallbacks(hash, reconfigure);
} finally {
    viewStateLock.unlock();  // Line 1015
}

// Phase 2 (unlocked): Execute collected callbacks
// This allows callbacks to acquire other locks without reentrancy risks
```

**Protected State**:
- `AtomicReference<Digest> nextViewId` (line 93)
- `AtomicReference<nextView> next` (line 92)
- `AtomicReference<ImmutablePendingViews> pendingViews` (line 104)
- `ViewCoordinator coordinator` (line 110)

**Lock Acquisition Sites**: 1 site
- `CHOAM.java:999` - View reconfiguration (exclusive lock)

---

## Safety Proof: Deadlock Freedom

### Theorem

The lock structure is **deadlock-free** because `headLock` and `viewStateLock` have **disjoint critical sections**.

### Proof

Let:
- `H = headLock`
- `V = viewStateLock`

**Invariant**: `∀ thread T, ¬(holds(T, H) ∧ holds(T, V))`

Translation: No thread ever holds both locks simultaneously.

**Proof by Inspection**:

1. **headLock acquisition sites**: 1 site
   - `consume()` (line 642): Acquires `H.writeLock()` ONLY, never acquires `V`

2. **viewStateLock acquisition sites**: 1 site
   - View reconfiguration (line 999): Acquires `V` ONLY, never acquires `H`

3. **No code path acquires both locks**: Verified by exhaustive search (see Lock Usage Patterns above)

**Therefore**:
- No circular wait condition possible (no thread waits for lock held by another thread that waits for the first thread)
- No deadlock possible (by Coffman conditions: circular wait is necessary for deadlock)

### Validation Strategy

1. **Static Analysis**: `NestedLockDetectionTest` (reflection-based)
   - Validates no nested lock acquisitions via reflection
   - Checks all methods for lock ordering violations

2. **Dynamic Analysis**: `CHOAMThreadAndLockingTest` (concurrency-based)
   - High concurrency load testing
   - Validates no deadlocks under stress

3. **Concurrency Testing**: `CHOAMConcurrencyTest` (timeout-based)
   - Timeout-based deadlock detection (tests fail if locks held >30s)
   - Validates no race conditions or lock contention issues

---

## Migration Constraints (CRITICAL)

### Non-Negotiable Requirements

1. **Preserve Disjoint Critical Sections**:
   - After CHOAMStateManager extraction, `headLock` and `viewStateLock` MUST remain disjoint
   - State holders may own locks, but MUST NOT introduce new lock acquisition sites

2. **No Nested Locking**:
   - State holders MUST NOT acquire locks while holding other locks
   - Callers MUST NOT acquire viewStateLock while holding headLock or vice versa

3. **Lock Exposure**:
   - State holders MAY expose locks as `public final` fields
   - Callers MUST acquire locks externally (state holders don't hide lock ownership)

4. **Validation Gates**:
   - After each migration phase, run `NestedLockDetectionTest`
   - After each migration phase, run `CHOAMThreadAndLockingTest`
   - Zero tolerance for lock ordering violations

### Risk Mitigation

**RISK**: Migration introduces nested locking (e.g., `headLock` acquired while holding `viewStateLock`)

**IMPACT**: CRITICAL - Deadlocks in production, system hang, Byzantine safety compromised

**MITIGATION**:
1. **Prevention**: Add assertions in state holders requiring correct lock held
2. **Detection**: Extend `NestedLockDetectionTest` BEFORE migration
3. **Validation**: Run concurrency tests after each extraction phase
4. **Documentation**: Document lock ownership in javadoc for each holder

---

## Lock-Free State (No Locks Required)

The following state does NOT require locks (AtomicReference provides lock-free synchronization):

1. **ControlFlags** (2 fields):
   - `AtomicBoolean started` (line 98)
   - `AtomicBoolean ongoingJoin` (line 106)

2. **AsyncOperationState** (3 fields):
   - `AtomicReference<CompletableFuture<SynchronizedState>> futureBootstrap` (line 88)
   - `AtomicReference<ScheduledFuture<?>> futureSynchronization` (line 89)
   - `AtomicInteger syncAttempts` (line 109)

3. **CommitteeState** (1 field):
   - `AtomicReference<Committee> current` (line 87)

These state holders will use lock-free AtomicReference operations (CAS, get, set) without explicit locking.

---

## Historical Context

**File**: `choam/src/main/java/com/hellblazer/delos/choam/CHOAM.java`
- **Current Size**: 1,992 LOC
- **Complexity**: ~136 methods, 112 atomic operations
- **Lock Count**: 2 locks (viewStateLock, headLock)
- **Lock Acquisition Sites**: 2 sites (1 per lock)

**Design Rationale**: Disjoint critical sections eliminate deadlock risk while allowing parallel block acceptance and view reconfiguration. This is foundational to Byzantine fault tolerance in CHOAM.

---

## References

- **Lock Detection**: `choam/src/test/java/com/hellblazer/delos/choam/CHOAMThreadAndLockingTest.java`
- **Concurrency Tests**: `choam/src/test/java/com/hellblazer/delos/choam/CHOAMConcurrencyTest.java`
- **Nested Lock Detection**: Based on `NestedLockDetectionTest` pattern (reflection-based)
- **ViewState Pattern**: `choam/src/main/java/com/hellblazer/delos/choam/ViewState.java` (two-phase reconfigure with lock-free snapshots)

---

## Appendix: Complete Lock Acquisition Map

| Method | Line | Lock | Type | Critical Section |
|--------|------|------|------|------------------|
| `consume()` | 642 | `headLock` | WriteLock | Block acceptance (37 lines) |
| View reconfiguration | 999 | `viewStateLock` | Exclusive | View transition (16 lines) |

**Total Lock Sites**: 2
**Disjoint**: YES (no overlap)
**Deadlock Risk**: NONE (no circular wait possible)
