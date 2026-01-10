# Phase 3: ViewManager Extraction - Comprehensive Implementation Plan

**Version**: 1.0
**Date**: 2026-01-09
**Status**: PENDING AUDIT
**Epic**: CHOAM Architecture Refactoring
**Author**: strategic-planner (opus)

---

## Executive Summary

This plan extracts view state management from CHOAM.java (1766 lines) into a dedicated ViewManager component. The extraction addresses identified code complexity and reduces the risk of threading issues around view state transitions. Committee inner classes (Formation, Associate, Client, Synchronizer) REMAIN in CHOAM as they are tightly coupled with committee lifecycle logic.

### Key Decisions

1. **View State Extraction**: Extract 6 fields related to view management
2. **Committee Classes Stay**: All 4 inner classes remain in CHOAM
3. **Callback Pattern**: ViewManager uses callback for committee transitions
4. **Lock Ordering**: viewStateLock is a leaf lock, never nested
5. **PendingViews Extracted**: Moves with ViewManager as tightly coupled

### Timeline: 11 days total

| Phase | Duration | Description |
|-------|----------|-------------|
| Phase 0 | 2 days | Test coverage expansion |
| Phase 1 | 1 day | Interface and skeleton |
| Phase 2 | 2 days | View state field extraction |
| Phase 3 | 3 days | Complex operations extraction |
| Phase 4 | 2 days | CHOAM integration |
| Phase 5 | 1 day | Cleanup and documentation |

---

## 1. Current State Analysis

### 1.1 View State Fields in CHOAM.java

| Field | Type | Line | Extract | Rationale |
|-------|------|------|---------|-----------|
| `view` | `AtomicReference<HashedCertifiedBlock>` | 102 | YES | Core view state |
| `viewStateLock` | `ReentrantLock` | 106 | YES | View state protection |
| `nextViewId` | `AtomicReference<Digest>` | 92 | YES | Next view tracking |
| `next` | `AtomicReference<nextView>` | 91 | YES | Consensus keys |
| `pendingViews` | `PendingViews` | 103 | YES | View queue |
| `ongoingJoin` | `AtomicBoolean` | 105 | YES | Join state tracking |

### 1.2 Fields Retained in CHOAM.java

| Field | Type | Line | Rationale |
|-------|------|------|-----------|
| `current` | `AtomicReference<Committee>` | 86 | Committee lifecycle, inner class access |
| `head` | `AtomicReference<HashedCertifiedBlock>` | 90 | Block state, not view state |
| `headLock` | `ReadWriteLock` | 107 | Block processing protection |
| `genesis` | `AtomicReference<HashedCertifiedBlock>` | 89 | Initialization state |

### 1.3 Committee Inner Classes (REMAIN IN CHOAM)

| Class | Lines | Reason to Keep |
|-------|-------|----------------|
| `Formation` | 1625-1712 | Creates GenesisAssembly, accesses next.get(), pendingViews |
| `Associate` | 1572-1614 | Creates Producer, uses pendingViews() supplier |
| `Client` | 1617-1622 | Simple, extends Administration |
| `Synchronizer` | 1715-1758 | Simple, validation only |
| `Administration` | 1360-1570 | Base class for Associate/Client, join logic |

---

## 2. Lock Safety Analysis

### 2.1 Identified Deadlock Risks

**Risk 1: headLock + viewStateLock Interaction**
- **Location**: `consume()` (Line 560) acquires `headLock.writeLock()`, then reads `view.get()` (Line 575)
- **Conflict**: `reconfigure()` (Line 758) acquires `viewStateLock`, then reads `head.get()` (Line 771)
- **Severity**: LOW - Both use atomic reads after acquiring their respective locks
- **Mitigation**: Document that locks are independent, never nested

**Risk 2: Blocking in viewStateLock Critical Section**
- **Location**: `reconfigure()` Line 767 calls `c.complete()`
- **Conflict**: `Associate.complete()` calls `producer.stop()` which may block
- **Severity**: MEDIUM - Potential for extended lock hold time
- **Mitigation**: Add timeout to Producer.stop(), document non-blocking requirement

**Risk 3: Committee.validate() Needs Lock**
- **Location**: `consume()` Line 610 calls `current.get().validate(next)`
- **Conflict**: If validate() ever acquired viewStateLock -> DEADLOCK
- **Severity**: LOW - Current implementations do not acquire viewStateLock
- **Mitigation**: Document that validate() must never acquire viewStateLock

### 2.2 Lock Ordering Strategy

**RULE**: `viewStateLock` is a LEAF LOCK

```
viewStateLock
    - NEVER acquire other locks while held
    - NEVER call methods that might acquire headLock
    - NEVER call blocking operations without timeout

headLock
    - Independent of viewStateLock
    - May read atomic references (view.get(), current.get())
    - Must not call methods that acquire viewStateLock
```

**Lock Ordering Documentation** (add to ViewManager javadoc):
```java
/**
 * LOCK ORDERING INVARIANT:
 *
 * viewStateLock is a leaf lock. When held:
 * 1. Do NOT acquire headLock or any other lock
 * 2. Do NOT call Committee.validate() or similar methods
 * 3. Do NOT call blocking operations without timeout
 * 4. Committee.complete() may be called but must not block indefinitely
 *
 * headLock and viewStateLock are independent:
 * - consume() holds headLock, reads atomics only
 * - reconfigure() holds viewStateLock, reads atomics only
 * - No code path should ever hold both locks
 */
```

---

## 3. Interface Design

### 3.1 ViewManager Interface

```java
/**
 * Manages view state for CHOAM consensus.
 *
 * Thread Safety: All public methods are thread-safe.
 * Lock Ordering: viewStateLock is a leaf lock (see class documentation).
 */
public interface ViewManager {

    // ========== View State Accessors (Lock-Free) ==========

    /**
     * Current view block (Genesis or Reconfigure).
     * @return current view block, never null after initialization
     */
    HashedCertifiedBlock currentView();

    /**
     * Current view ID extracted from view block.
     * @return view ID or null if not initialized
     */
    Digest viewId();

    /**
     * Next view ID being formed.
     * @return next view ID or null if not set
     */
    Digest nextViewId();

    /**
     * Current consensus key material.
     * @return nextView record with ViewMember and KeyPair
     */
    nextView consensusKeys();

    /**
     * Immutable snapshot of current view state.
     * Use for consistent reads without locking.
     */
    ViewSnapshot snapshot();

    // ========== PendingViews Operations ==========

    /**
     * Add a pending view context for future activation.
     * Thread-safe via internal lock.
     */
    void addPendingView(Digest diadem, Context<Member> context);

    /**
     * Get most recent pending view without removal.
     */
    PendingView lastPendingView();

    /**
     * Clear all pending views.
     * Called when receiving ViewChange with no active committee.
     */
    void clearPendingViews();

    /**
     * Get Views proto builder for communication.
     */
    Views.Builder getViews(Digest hash);

    // ========== View Mutations (Protected by viewStateLock) ==========

    /**
     * Reconfigure to a new view.
     *
     * CRITICAL SECTION: Acquires viewStateLock.
     *
     * Operations performed:
     * 1. Set nextViewId to hash
     * 2. Advance pendingViews
     * 3. Rotate view keys
     * 4. Set view to current head
     * 5. Clear ongoing join flag
     * 6. Invoke callback for committee transition
     *
     * @param hash new view ID
     * @param reconfigure reconfigure proto
     * @param currentHead current block head for view block
     * @param callback invoked under lock for committee creation
     */
    void reconfigure(Digest hash, Reconfigure reconfigure,
                     HashedCertifiedBlock currentHead,
                     ReconfigureCallback callback);

    /**
     * Rotate consensus key pair.
     * Generates new consensus key and updates next field.
     */
    void rotateViewKeys();

    // ========== Join State Management ==========

    /**
     * Attempt to start a join operation.
     * @return true if join started, false if already ongoing
     */
    boolean startJoin();

    /**
     * Mark join operation as complete.
     */
    void completeJoin();

    /**
     * Check if join is in progress.
     */
    boolean isJoining();

    // ========== Initialization/Restoration ==========

    /**
     * Restore view state during startup.
     * Called before consensus starts.
     */
    void restore(HashedCertifiedBlock viewBlock);

    /**
     * Set genesis view during formation.
     */
    void setGenesisView(HashedCertifiedBlock genesis);

    /**
     * Initialize with NullBlock for startup.
     */
    void initialize(DigestAlgorithm algorithm);
}
```

### 3.2 ReconfigureCallback Interface

```java
/**
 * Callback for committee creation during reconfigure.
 * Invoked while viewStateLock is held.
 *
 * CRITICAL: Implementation must not:
 * - Acquire additional locks
 * - Perform blocking operations
 * - Take longer than 100ms
 */
@FunctionalInterface
public interface ReconfigureCallback {

    /**
     * Create new committee based on reconfigure.
     *
     * @param reconfigure the reconfigure proto
     * @param validators extracted validator map
     * @param consensusKeys current consensus key material
     * @param viewBlock the new view block
     * @param pendingContext the activated pending view context (may be null)
     */
    void onReconfigure(Reconfigure reconfigure,
                       Map<Member, Verifier> validators,
                       nextView consensusKeys,
                       HashedCertifiedBlock viewBlock,
                       Context<Member> pendingContext);
}
```

### 3.3 ViewSnapshot Record

```java
/**
 * Immutable snapshot of view state for safe concurrent reads.
 */
public record ViewSnapshot(
    HashedCertifiedBlock view,
    Digest viewId,
    Digest nextViewId,
    boolean ongoingJoin,
    int pendingViewCount
) {
    public static ViewSnapshot empty(DigestAlgorithm algo) {
        return new ViewSnapshot(
            new HashedCertifiedBlock.NullBlock(algo),
            null, null, false, 0
        );
    }
}
```

---

## 4. Integration Points

### 4.1 CHOAM Call Sites - View State Access

| Location | Line | Current Code | After Extraction |
|----------|------|--------------|------------------|
| `getViewId()` | 305 | `view.get()...` | `viewManager.viewId()` |
| `checkpoint()` | 433 | `view.get()` | `viewManager.currentView()` |
| `BlockProducer.genesis()` | 482 | `view.get()` | `viewManager.currentView()` |
| `BlockProducer.produce(Assemble)` | 510 | `view.get()` | `viewManager.currentView()` |
| `BlockProducer.produce(Executions)` | 521 | `view.get()` | `viewManager.currentView()` |
| `BlockProducer.reconfigure()` | 550 | `view.get()` | `viewManager.currentView()` |
| `consume()` | 575 | `this.view.get().height()` | `viewManager.currentView().height()` |
| `restore()` | 854 | `view.get().hash` | `viewManager.currentView().hash` |
| `Formation.accept()` | 1660 | `view.set(c)` | `viewManager.setGenesisView(c)` |

### 4.2 CHOAM Call Sites - nextViewId Access

| Location | Line | Current Code | After Extraction |
|----------|------|--------------|------------------|
| `Administration.nextView()` | 1407 | `nextViewId.get()` | `viewManager.nextViewId()` |
| `Administration.join()` | 1465, 1469, 1481, 1484 | `nextViewId.get()` | `viewManager.nextViewId()` |
| `Administration.join()` | 1501 | `nextViewId.get().toDigeste()` | `viewManager.nextViewId().toDigeste()` |
| `Associate constructor` | 1588 | `nextViewId.get()` | `viewManager.nextViewId()` |
| `Formation constructor` | 1647 | `nextViewId.set(...)` | `viewManager.setNextViewId(...)` |

### 4.3 CHOAM Call Sites - pendingViews Access

| Location | Line | Current Code | After Extraction |
|----------|------|--------------|------------------|
| Constructor | 117 | `pendingViews.add(...)` | `viewManager.addPendingView(...)` |
| `rotateViewKeys(ViewChange)` | 348 | `pendingViews.clear()` | `viewManager.clearPendingViews()` |
| `rotateViewKeys(ViewChange)` | 349 | `pendingViews.add(...)` | `viewManager.addPendingView(...)` |
| `Administration.nextView()` | 1405 | `pendingViews.add(...)` | `viewManager.addPendingView(...)` |
| `Formation.nextView()` | 1686 | `pendingViews.add(...)` | `viewManager.addPendingView(...)` |
| `Synchronizer.nextView()` | 1746 | `pendingViews.add(...)` | `viewManager.addPendingView(...)` |
| `Combiner.anchor()` | 1202 | `pendingViews.last().context` | `viewManager.lastPendingView().context()` |
| `Associate constructor` | 1587 | `pendingViews()` (supplier) | `() -> viewManager` |

### 4.4 Constructor Injection Strategy

```java
public CHOAM(Parameters params) {
    // ... existing initialization ...

    // Create ViewManager first (no dependencies on other CHOAM state)
    this.viewManager = new ViewManagerImpl(
        params.digestAlgorithm(),
        params.viewSigAlgorithm(),
        params.member(),
        params.context()
    );

    // Initialize view state
    viewManager.initialize(params.digestAlgorithm());
    viewManager.addPendingView(params.context().getId(), params.context().delegate());
    viewManager.rotateViewKeys();

    // ... rest of constructor ...
}
```

---

## 5. Detailed Phase Plan

### Phase 0: Test Coverage Expansion (2 days)

**Objective**: Establish baseline test coverage for view state operations before extraction.

#### Tasks

**P0-1: ViewTransitionRaceTest.java**
```java
/**
 * Tests concurrent view transitions and reconfigure operations.
 * Verifies no race conditions between:
 * - consume() reading view state
 * - reconfigure() writing view state
 */
class ViewTransitionRaceTest {
    @Test void concurrentConsumeAndReconfigure();
    @Test void rapidViewChanges();
    @Test void reconfigureDuringBlockValidation();
}
```

**P0-2: LockOrderingVerificationTest.java**
```java
/**
 * Verifies lock ordering invariants are maintained.
 * Uses ThreadMXBean to detect potential deadlocks.
 */
class LockOrderingVerificationTest {
    @Test void noDeadlockBetweenHeadLockAndViewStateLock();
    @Test void reconfigureDoesNotAcquireHeadLock();
    @Test void consumeDoesNotAcquireViewStateLock();
}
```

**P0-3: PendingViewsThreadSafetyTest.java**
```java
class PendingViewsThreadSafetyTest {
    @Test void concurrentAddOperations();
    @Test void advanceWhileAdding();
    @Test void clearWhileReading();
    @Test void lastDuringAdvance();
}
```

**P0-4: ViewStateConsistencyTest.java**
```java
class ViewStateConsistencyTest {
    @Test void viewAndCommitteeConsistentAfterReconfigure();
    @Test void nextViewIdUpdatedBeforeViewBlock();
    @Test void pendingViewActivatedOnReconfigure();
}
```

**P0-5: OngoingJoinStateTest.java**
```java
class OngoingJoinStateTest {
    @Test void joinFlagTogglesCorrectly();
    @Test void concurrentJoinAttempts();
    @Test void joinCancelledOnReconfigure();
}
```

**P0-6: DeadlockDetectionTest.java**
```java
class DeadlockDetectionTest {
    @Test void detectPotentialDeadlocks();
    @Test void stressTestConcurrentOperations();
}
```

**Success Criteria**:
- All 6 new test classes created and passing
- DeterminismVerificationTest passes
- No existing test regressions

**Rollback Trigger**: Any existing test failure

---

### Phase 1: ViewManager Interface and Skeleton (1 day)

**Objective**: Create interface and pass-through implementation with no behavior change.

#### Tasks

**P1-1: Create ViewManager.java interface**
- Location: `choam/src/main/java/com/hellblazer/delos/choam/ViewManager.java`
- Content: Full interface as specified in Section 3.1

**P1-2: Create ReconfigureCallback.java**
- Location: `choam/src/main/java/com/hellblazer/delos/choam/ReconfigureCallback.java`
- Content: Functional interface as specified in Section 3.2

**P1-3: Create ViewSnapshot.java**
- Location: `choam/src/main/java/com/hellblazer/delos/choam/ViewSnapshot.java`
- Content: Record as specified in Section 3.3

**P1-4: Create ViewManagerImpl.java skeleton**
- Location: `choam/src/main/java/com/hellblazer/delos/choam/ViewManagerImpl.java`
- Initial implementation: All methods throw UnsupportedOperationException
- Javadoc: Include lock ordering documentation

**P1-5: Create ViewManagerTest.java**
```java
class ViewManagerTest {
    @Test void interfaceContractTest();
    @Test void snapshotImmutability();
}
```

**Success Criteria**:
- All interfaces compile
- Skeleton implementation compiles
- No changes to CHOAM.java behavior
- All existing tests pass

**Rollback Trigger**: Compilation failure

---

### Phase 2: Extract View State Fields (2 days)

**Objective**: Move view state fields from CHOAM to ViewManagerImpl.

#### Tasks

**P2-1: Move fields to ViewManagerImpl**

From CHOAM.java, cut:
```java
// Line 102
private final AtomicReference<HashedCertifiedBlock> view = new AtomicReference<>();

// Line 106
private final ReentrantLock viewStateLock = new ReentrantLock();

// Line 92
private final AtomicReference<Digest> nextViewId = new AtomicReference<>();

// Line 91
private final AtomicReference<nextView> next = new AtomicReference<>();

// Line 103
private final PendingViews pendingViews = new PendingViews();

// Line 105
private final AtomicBoolean ongoingJoin = new AtomicBoolean();
```

Paste to ViewManagerImpl.java with updated access modifiers.

**P2-2: Implement ViewManager getters**

```java
@Override
public HashedCertifiedBlock currentView() {
    return view.get();
}

@Override
public Digest viewId() {
    var viewBlock = view.get();
    if (viewBlock == null) return null;
    return new Digest(viewBlock.block.hasGenesis()
        ? viewBlock.block.getGenesis().getInitialView().getId()
        : viewBlock.block.getReconfigure().getId());
}

@Override
public Digest nextViewId() {
    return nextViewId.get();
}

@Override
public nextView consensusKeys() {
    return next.get();
}

@Override
public ViewSnapshot snapshot() {
    return new ViewSnapshot(
        view.get(),
        viewId(),
        nextViewId.get(),
        ongoingJoin.get(),
        pendingViews.views.size()
    );
}
```

**P2-3: Implement PendingViews delegations**

```java
@Override
public void addPendingView(Digest diadem, Context<Member> context) {
    pendingViews.add(diadem, context);
}

@Override
public PendingView lastPendingView() {
    return pendingViews.last();
}

@Override
public void clearPendingViews() {
    pendingViews.clear();
}

@Override
public Views.Builder getViews(Digest hash) {
    return pendingViews.getViews(hash);
}
```

**P2-4: Implement join state methods**

```java
@Override
public boolean startJoin() {
    return ongoingJoin.compareAndSet(false, true);
}

@Override
public void completeJoin() {
    ongoingJoin.set(false);
}

@Override
public boolean isJoining() {
    return ongoingJoin.get();
}
```

**P2-5: Add ViewManager field to CHOAM**

```java
private final ViewManager viewManager;
```

**P2-6: Update CHOAM constructor**

```java
public CHOAM(Parameters params) {
    // Create ViewManager early
    this.viewManager = new ViewManagerImpl(
        params.digestAlgorithm(),
        params.viewSigAlgorithm(),
        params.member()
    );
    viewManager.initialize(params.digestAlgorithm());
    viewManager.addPendingView(params.context().getId(), params.context().delegate());
    viewManager.rotateViewKeys();

    // ... rest unchanged ...
}
```

**P2-7: Update CHOAM getViewId()**

```java
@Override
public Digest getViewId() {
    return viewManager.viewId();
}
```

**P2-8: Update view.get() call sites in CHOAM**

Replace each `view.get()` with `viewManager.currentView()` per Section 4.1.

**Success Criteria**:
- All tests pass including DeterminismVerificationTest
- CHOAM no longer has view, viewStateLock, nextViewId, next, pendingViews, ongoingJoin fields
- All integration points updated

**Rollback Trigger**: Any test failure

**Rollback Procedure**:
1. `git checkout -- choam/src/main/java/com/hellblazer/delos/choam/CHOAM.java`
2. Delete ViewManagerImpl.java
3. Verify all tests pass

---

### Phase 3: Extract Complex Operations (3 days)

**Objective**: Move reconfigure() and rotateViewKeys() logic to ViewManager.

#### Tasks

**P3-1: Implement rotateViewKeys() in ViewManager**

```java
@Override
public void rotateViewKeys() {
    KeyPair keyPair = viewSigAlgorithm.generateKeyPair();
    PubKey pubKey = bs(keyPair.getPublic());
    JohnHancock signed = member.sign(pubKey.toByteString());
    if (signed == null) {
        log.error("Unable to generate and sign consensus key on: {}", member.getId());
        return;
    }
    log.trace("Generated next view consensus key: {} sig: {} on: {}",
              digestAlgorithm.digest(pubKey.getEncoded()),
              digestAlgorithm.digest(signed.toSig().toByteString()),
              member.getId());
    next.set(new nextView(ViewMember.newBuilder()
                                    .setId(member.getId().toDigeste())
                                    .setConsensusKey(pubKey)
                                    .setSignature(signed.toSig())
                                    .build(), keyPair));
}
```

**P3-2: Implement reconfigure() in ViewManager**

```java
@Override
public void reconfigure(Digest hash, Reconfigure reconfigure,
                        HashedCertifiedBlock currentHead,
                        ReconfigureCallback callback) {
    viewStateLock.lock();
    try {
        log.info("Setting next view id: {} on: {}", hash, member.getId());
        nextViewId.set(hash);

        var pv = pendingViews.advance();
        Context<Member> pendingContext = pv != null ? pv.context() : null;

        // Rotate view keys before updating view
        rotateViewKeys();

        // Update view to current head
        view.set(currentHead);

        // Calculate validators
        var validators = Committee.validatorsOf(reconfigure, context, member.getId(), log);

        // Get current consensus keys
        final var currentView = next.get();

        // Clear ongoing join
        if (ongoingJoin.compareAndSet(true, false)) {
            log.trace("Halting ongoing join on: {}", member.getId());
        }

        // Callback for committee creation (CHOAM handles this)
        callback.onReconfigure(reconfigure, validators, currentView, currentHead, pendingContext);

        log.info("Reconfigured to view: {} validators: {} on: {}",
                 new Digest(reconfigure.getId()),
                 validators.keySet().stream().map(m -> m.getId()).toList(),
                 member.getId());
    } finally {
        viewStateLock.unlock();
    }
}
```

**P3-3: Update CHOAM reconfigure() to use ViewManager**

```java
private void reconfigure(Digest hash, Reconfigure reconfigure) {
    viewManager.reconfigure(hash, reconfigure, head.get(),
        (reconf, validators, consensusKeys, viewBlock, pendingContext) -> {
            // Handle context update
            if (pendingContext != null) {
                params.context().setContext(pendingContext);
            }

            // Complete old committee
            final Committee c = current.get();
            c.complete();

            // Update session view
            session.setView(viewBlock);

            // Create new committee
            if (validators.containsKey(params.member())) {
                if (Dag.validate(validators.size())) {
                    current.set(new Associate(viewBlock, validators, consensusKeys));
                } else {
                    log.warn("Reconfiguration to associate failed: {} committee: {} in view: {} on:{}",
                             validators.size(), new Digest(reconf.getId()),
                             current.get().getClass().getSimpleName(), params.member().getId());
                    transitions.fail();
                }
            } else {
                current.set(new Client(validators, viewManager.viewId()));
            }
        });

    // FSM transition outside lock
    transitions.rotateViewKeys();
}
```

**P3-4: Update CHOAM rotateViewKeys(ViewChange) to use ViewManager**

```java
@Override
public void rotateViewKeys(ViewChange viewChange) {
    var context = viewChange.context();
    var diadem = viewChange.diadem();
    log.trace("Setting RBC Context to: {} on: {}", context, params.member().getId());
    ((DelegatedContext<Member>) combine.getContext()).setContext(context);
    var c = current.get();
    if (c != null) {
        c.nextView(viewChange.diadem(), context);
    } else {
        log.info("Acquiring new view of: {}, diadem: {} size: {} on: {}",
                 context.getId(), diadem, context.size(), params.member().getId());
        params.context().setContext(context);
        viewManager.clearPendingViews();
        viewManager.addPendingView(diadem, context);
    }
}
```

**P3-5: Add lock ordering verification test**

```java
class ViewManagerLockOrderingTest {
    @Test
    void reconfigureIsLeafLock() {
        // Verify no other locks acquired during reconfigure
        var viewManager = new ViewManagerImpl(...);
        var lockMonitor = new LockMonitor();

        viewManager.reconfigure(..., (reconf, validators, keys, view, ctx) -> {
            // Verify only viewStateLock is held
            assertFalse(lockMonitor.isAnyOtherLockHeld());
        });
    }
}
```

**P3-6: Document lock ordering in ViewManagerImpl**

Add class-level javadoc per Section 2.2.

**Success Criteria**:
- All tests pass including DeterminismVerificationTest
- reconfigure() and rotateViewKeys() fully delegated to ViewManager
- Lock ordering documented
- LockOrderingVerificationTest passes

**Rollback Trigger**: Any test failure or lock ordering violation detected

---

### Phase 4: Integrate ViewManager into CHOAM (2 days)

**Objective**: Complete integration of ViewManager throughout CHOAM.

#### Tasks

**P4-1: Update Combiner FSM to use ViewManager**

```java
@Override
public void rotateViewKeys() {
    viewManager.rotateViewKeys();
}
```

**P4-2: Update BlockProducer interface**

Add ViewManager parameter to BlockProducer methods that need view state:

```java
private BlockProducer constructBlock() {
    return new BlockProducer() {
        @Override
        public Block genesis(Map<Digest, Join> joining, Digest nextViewId, HashedBlock previous) {
            final HashedCertifiedBlock cp = checkpointManager.currentCheckpoint();
            final HashedCertifiedBlock v = viewManager.currentView();
            // ... rest unchanged ...
        }
        // ... other methods similarly updated ...
    };
}
```

**P4-3: Update Administration inner class**

```java
@Override
public void nextView(Digest diadem, Context<Member> pendingView) {
    viewManager.addPendingView(diadem, pendingView);
    log.info("Pending context for view: {} size: {} on: {}",
             viewManager.nextViewId() == null ? "<null>" : viewManager.nextViewId(),
             pendingView.size(), params.member().getId());
}
```

**P4-4: Update Formation inner class**

```java
private Formation() {
    formation = Committee.viewFor(params.genesisViewId(), params.context());
    if (formation.isMember(params.member()) && params.generateGenesis()) {
        final var c = viewManager.consensusKeys();
        // ... rest uses viewManager.nextViewId(), etc.
    }
}

@Override
public void accept(HashedCertifiedBlock hb) {
    assert hb.height().equals(ULong.valueOf(0));
    final var c = head.get();
    genesis.set(c);
    ((CheckpointManagerImpl) checkpointManager).updateCheckpoint(c);
    viewManager.setGenesisView(c);
    process();
}
```

**P4-5: Update Associate inner class**

```java
Associate(HashedCertifiedBlock viewChange, Map<Member, Verifier> validators, nextView nextView) {
    super(validators, new Digest(
        viewChange.block.hasGenesis() ? viewChange.block.getGenesis().getInitialView().getId()
                                      : viewChange.block.getReconfigure().getId()));
    // ...
    producer = new Producer(viewManager.nextViewId(),
                            new ViewContext(context, params, () -> viewManager, signer, validators, constructBlock()),
                            head.get(), checkpointManager.currentCheckpoint(), getLabel(), scheduler);
}

private void join(View view) {
    if (!viewManager.startJoin()) {
        throw new IllegalStateException("Ongoing join should have been cancelled");
    }
    // ...
}
```

**P4-6: Update Synchronizer inner class**

```java
@Override
public void nextView(Digest diadem, Context<Member> pendingView) {
    log.info("Acquiring new view, size: {} on: {}", pendingView.size(), params.member().getId());
    params.context().setContext(pendingView);
    viewManager.addPendingView(diadem, pendingView);
}
```

**P4-7: Update restore() method**

```java
@Override
public void restore() throws IllegalStateException {
    // ... existing logic ...

    if (lastCheckpoint != null) {
        // ...
        viewManager.restore(lastView);
        var validators = validatorsOf(reconfigure, params.context(), params.member().getId(), log);
        current.set(new Synchronizer(validators));
    }
}
```

**P4-8: Run full integration test suite**

```bash
./mvnw test -pl choam -Dtest="*Test,*Tests"
./mvnw test -pl choam -Dtest="DeterminismVerificationTest"
```

**Success Criteria**:
- All integration points updated
- All tests pass including DeterminismVerificationTest
- No compilation warnings related to view state

**Rollback Trigger**: Any test failure

---

### Phase 5: Cleanup (1 day)

**Objective**: Remove extracted code and finalize documentation.

#### Tasks

**P5-1: Remove dead code from CHOAM**

Verify and remove:
- Any remaining view state field references
- Unused imports
- Dead code paths

**P5-2: Move PendingViews and PendingView to ViewManager file**

```java
// In ViewManagerImpl.java or separate file
public static class PendingViews { ... }
public record PendingView(Digest diadem, Context<Member> context) { ... }
```

**P5-3: Move nextView record**

```java
// To ViewManager.java or ViewManagerImpl.java
public record nextView(ViewMember member, KeyPair consensusKeyPair) { }
```

**P5-4: Update documentation**

- Update CHOAM class javadoc
- Add ViewManager architecture documentation
- Document threading model

**P5-5: Verify line count reduction**

Target: CHOAM.java reduced by ~200 lines (from 1766 to ~1566)

**P5-6: Final code review checklist**

- [ ] All view state in ViewManager
- [ ] Committee classes remain in CHOAM
- [ ] Lock ordering documented
- [ ] No unused imports
- [ ] DeterminismVerificationTest passes
- [ ] All Phase 0 tests pass

**Success Criteria**:
- CHOAM.java line count reduced by ~200 lines
- All tests pass
- Code review approved

**Rollback Trigger**: Any regression

---

## 6. Test Strategy Details

### 6.1 Required Tests by Phase

| Phase | New Tests | Existing Tests | Must Pass |
|-------|-----------|----------------|-----------|
| Phase 0 | 6 classes (30+ tests) | All existing | All |
| Phase 1 | ViewManagerTest (5 tests) | All existing | All |
| Phase 2 | ViewManagerIntegrationTest (10 tests) | All existing + Phase 0 | All + DeterminismVerificationTest |
| Phase 3 | LockOrderingTest (5 tests) | All existing + Phase 0-2 | All + DeterminismVerificationTest |
| Phase 4 | ViewManagerFSMTest (10 tests) | All existing + Phase 0-3 | All + DeterminismVerificationTest |
| Phase 5 | None new | All | All + Full suite |

### 6.2 Deadlock Prevention Tests

```java
class DeadlockPreventionTest {

    @Test
    @Timeout(30)
    void noDeadlockUnderConcurrentReconfigureAndConsume() {
        var executor = Executors.newFixedThreadPool(10, Thread.ofVirtual().factory());
        var latch = new CountDownLatch(1);
        var errors = new CopyOnWriteArrayList<Throwable>();

        // Thread group 1: Reconfigure operations
        for (int i = 0; i < 5; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    for (int j = 0; j < 100; j++) {
                        choam.reconfigure(...);
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }

        // Thread group 2: Consume operations
        for (int i = 0; i < 5; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    for (int j = 0; j < 100; j++) {
                        choam.consume(...);
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }

        latch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(25, TimeUnit.SECONDS));
        assertTrue(errors.isEmpty(), "Errors: " + errors);
    }

    @Test
    void verifyNoNestedLockAcquisition() {
        var lockTracker = new ThreadLocal<Set<String>>();
        // Instrument viewStateLock and headLock
        // Verify neither is held when the other is acquired
    }
}
```

### 6.3 Race Condition Detection Tests

```java
class RaceConditionDetectionTest {

    @Test
    void viewAndCommitteeRemainConsistent() {
        var inconsistencies = new AtomicInteger(0);
        var executor = Executors.newFixedThreadPool(2, Thread.ofVirtual().factory());

        // Thread 1: Continuously read view and committee
        executor.submit(() -> {
            for (int i = 0; i < 1000; i++) {
                var view = viewManager.currentView();
                var committee = choam.current.get();
                if (!isConsistent(view, committee)) {
                    inconsistencies.incrementAndGet();
                }
            }
        });

        // Thread 2: Trigger reconfigurations
        executor.submit(() -> {
            for (int i = 0; i < 100; i++) {
                triggerReconfigure();
                Thread.sleep(1);
            }
        });

        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);
        assertEquals(0, inconsistencies.get());
    }
}
```

### 6.4 View Transition Under Load Tests

```java
class ViewTransitionLoadTest {

    @RepeatedTest(10)
    @Timeout(60)
    void rapidViewChangesUnderLoad() {
        var transactionCount = new AtomicInteger(0);
        var viewChangeCount = new AtomicInteger(0);

        // Submit transactions continuously
        var txnThread = Thread.ofVirtual().start(() -> {
            while (!Thread.interrupted()) {
                choam.submit(createTransaction());
                transactionCount.incrementAndGet();
            }
        });

        // Trigger rapid view changes
        for (int i = 0; i < 50; i++) {
            triggerViewChange();
            viewChangeCount.incrementAndGet();
            Thread.sleep(10);
        }

        txnThread.interrupt();
        txnThread.join();

        // Verify no lost transactions
        verifyAllTransactionsProcessed(transactionCount.get());
    }
}
```

---

## 7. Risk Mitigation

### 7.1 Risk Matrix

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Deadlock introduced | Low | Critical | Lock ordering rules, deadlock detection tests |
| Race condition in view state | Medium | High | Atomic operations, ViewSnapshot pattern |
| Committee transition failure | Low | High | ReconfigureCallback isolation, rollback on exception |
| Blocking in viewStateLock | Medium | Medium | Timeout on c.complete(), async completion |
| DeterminismVerificationTest failure | Low | Critical | Run after every phase, immediate rollback |

### 7.2 Rollback Procedures

**Quick Rollback (any phase)**:
```bash
git checkout -- choam/src/main/java/com/hellblazer/delos/choam/CHOAM.java
git checkout -- choam/src/main/java/com/hellblazer/delos/choam/ViewManager.java
git checkout -- choam/src/main/java/com/hellblazer/delos/choam/ViewManagerImpl.java
./mvnw test -pl choam
```

**Full Phase Rollback**:
```bash
git revert HEAD~N  # N = commits in current phase
./mvnw clean install -pl choam
./mvnw test -pl choam -Dtest="DeterminismVerificationTest"
```

### 7.3 Monitoring Points

- **Lock contention**: Monitor viewStateLock wait time
- **Reconfigure duration**: Time from reconfigure() entry to callback completion
- **View state consistency**: Periodic assertions in tests
- **Thread dumps**: Capture on any timeout

---

## 8. Success Criteria

### Phase-Level Criteria

| Phase | Criteria |
|-------|----------|
| Phase 0 | 6 new test classes pass, no existing test regressions |
| Phase 1 | Interfaces compile, no behavior change |
| Phase 2 | All view state in ViewManager, DeterminismVerificationTest passes |
| Phase 3 | Lock ordering verified, no deadlocks detected |
| Phase 4 | Full integration complete, all tests pass |
| Phase 5 | CHOAM.java reduced by ~200 lines, code review approved |

### Overall Criteria

1. **Functional**: All existing functionality preserved
2. **Determinism**: DeterminismVerificationTest passes consistently
3. **Thread Safety**: No deadlocks, no race conditions
4. **Maintainability**: CHOAM.java reduced by ~200 lines
5. **Documentation**: Lock ordering and threading model documented

---

## Appendix A: File Inventory

### New Files Created

| File | Phase | Purpose |
|------|-------|---------|
| `ViewManager.java` | P1 | Interface |
| `ReconfigureCallback.java` | P1 | Callback interface |
| `ViewSnapshot.java` | P1 | Immutable snapshot record |
| `ViewManagerImpl.java` | P1-P3 | Implementation |
| `ViewTransitionRaceTest.java` | P0 | Race condition tests |
| `LockOrderingVerificationTest.java` | P0 | Lock ordering tests |
| `PendingViewsThreadSafetyTest.java` | P0 | PendingViews thread safety |
| `ViewStateConsistencyTest.java` | P0 | State consistency tests |
| `OngoingJoinStateTest.java` | P0 | Join state tests |
| `DeadlockDetectionTest.java` | P0 | Deadlock detection |
| `ViewManagerTest.java` | P1 | Interface contract tests |
| `ViewManagerIntegrationTest.java` | P2 | Integration tests |
| `ViewManagerLockOrderingTest.java` | P3 | Lock ordering verification |
| `ViewManagerFSMTest.java` | P4 | FSM integration tests |

### Modified Files

| File | Phases | Changes |
|------|--------|---------|
| `CHOAM.java` | P2-P5 | Remove view state, add ViewManager delegation |
| `Parameters.java` | P2 | Add ViewManager builder (if needed) |

---

## Appendix B: Line Number Reference

### CHOAM.java Fields to Extract

| Field | Line | Extract Phase |
|-------|------|---------------|
| `view` | 102 | P2-1 |
| `viewStateLock` | 106 | P2-1 |
| `nextViewId` | 92 | P2-1 |
| `next` | 91 | P2-1 |
| `pendingViews` | 103 | P2-1 |
| `ongoingJoin` | 105 | P2-1 |

### CHOAM.java Methods to Update

| Method | Lines | Update Phase |
|--------|-------|--------------|
| `getViewId()` | 304-311 | P2-7 |
| `rotateViewKeys(ViewChange)` | 336-351 | P3-4 |
| `checkpoint()` | 416-448 | P2-8 |
| `consume()` | 559-599 | P2-8 |
| `reconfigure(Digest, Reconfigure)` | 757-801 | P3-3 |
| `restore()` | 826-856 | P4-7 |
| `rotateViewKeys()` | 863-884 | P3-1 |
| `Combiner.rotateViewKeys()` | 1301-1303 | P4-1 |
| `Administration.nextView()` | 1404-1409 | P4-3 |
| `Formation` constructor | 1629-1652 | P4-4 |
| `Formation.accept()` | 1655-1662 | P4-4 |
| `Associate` constructor | 1576-1592 | P4-5 |
| `Synchronizer.nextView()` | 1743-1747 | P4-6 |

---

## Appendix C: Beads Structure (To Create)

```bash
# Epic
bd create "Phase 3: ViewManager Extraction" -t epic -p 1

# Phase 0 tasks
bd create "P0-1: ViewTransitionRaceTest" -t task
bd create "P0-2: LockOrderingVerificationTest" -t task
bd create "P0-3: PendingViewsThreadSafetyTest" -t task
bd create "P0-4: ViewStateConsistencyTest" -t task
bd create "P0-5: OngoingJoinStateTest" -t task
bd create "P0-6: DeadlockDetectionTest" -t task

# Phase 1 tasks
bd create "P1-1: ViewManager interface" -t task
bd create "P1-2: ReconfigureCallback interface" -t task
bd create "P1-3: ViewSnapshot record" -t task
bd create "P1-4: ViewManagerImpl skeleton" -t task
bd create "P1-5: ViewManagerTest" -t task

# ... continue for all phases
```

---

**Document Status**: READY FOR AUDIT
**Next Step**: Submit to plan-auditor for validation
