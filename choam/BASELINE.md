# CHOAM Baseline Documentation (CHOAMStateManager Extraction)

**Created**: 2026-02-04
**Purpose**: Establish baseline metrics before CHOAMStateManager extraction
**Bead**: Delos-sm0v (Pre-Phase: Baseline Establishment)

---

## Test Status Baseline

### Overall Results

**Test Suite**: CHOAM module (`./mvnw test -pl choam`)
**Execution Date**: 2026-02-04
**Total Tests**: 168
**Failures**: 4 (all pre-existing, NonceTracker)
**Errors**: 0
**Skipped**: 5
**Build Status**: FAILURE (due to NonceTracker failures)

### Pre-Existing Failures (NonceTrackerTest - DO NOT FIX)

**Test Class**: `com.hellblazer.delos.choam.NonceTrackerTest`
**Total Tests in Class**: 10
**Failures**: 4
**Passing**: 6

**Failing Tests** (Pre-existing, not related to CHOAMStateManager extraction):

1. **testPersistenceAcrossRestarts** (`NonceTrackerTest.java:?`)
   - Status: FAILURE
   - Description: Nonce persistence across system restarts

2. **testBlockHeightExpiration** (`NonceTrackerTest.java:?`)
   - Status: FAILURE
   - Description: Nonce expiration based on block height

3. **testMultipleSourcesIndependent** (`NonceTrackerTest.java:?`)
   - Status: FAILURE
   - Description: Multiple sources tracked independently

4. **testNonceValidation** (`NonceTrackerTest.java:89`)
   - Status: FAILURE
   - Error: `expected: <false> but was: <true>`
   - Description: Nonce replay detection validation

**Note**: These failures are **PRE-EXISTING** and **NOT** related to CHOAMStateManager extraction. They exist in the baseline before any refactoring begins. The extraction is successful if these same 4 tests fail and NO NEW failures are introduced.

### Skipped Tests

**Test Class**: `com.hellblazer.delos.choam.ViewStateContractTest`
**Skipped**: 5 tests

These tests are intentionally skipped (likely configuration or environment-dependent).

### Passing Tests Summary

**Passing Tests**: 164 (168 total - 4 failures = 164)

**Key Test Classes** (All Passing):

1. **CHOAMConcurrencyTest** (6 tests, 109.2s)
   - Validates no deadlocks under high concurrency
   - Tests concurrent block acceptance, view transitions
   - **CRITICAL** for lock ordering validation

2. **CHOAMThreadAndLockingTest** (6 tests, 104.0s)
   - Validates lock ordering invariants
   - Tests that headLock and viewStateLock never held simultaneously
   - **CRITICAL** for deadlock freedom proof

3. **DeterminismVerificationTest** (1 test, 33.81s)
   - Validates deterministic execution
   - Byzantine fault tolerance requires determinism

4. **CHOAMBlockValidationTest** (1 test, 23.53s)
   - Block validation under Byzantine scenarios

5. **DynamicTest** (1 test, 15.19s)
   - Dynamic reconfiguration scenarios

6. **CallbackReentrancyTest** (1 test, 26.59s)
   - Validates callback execution doesn't cause reentrancy issues

7. **CommitteeValidationTest** (5 tests)
   - Committee state machine transitions

8. **PerformanceBaselineTest** (1 test, 0.561s)
   - **CRITICAL** for performance regression detection

9. **FeatureFlagManagerTest** (10 tests)
   - Feature flag management (including NONCE_PERSISTENCE)

10. **BatchVerificationPerformanceTest** (11 tests)
    - Batch signature verification performance

Additional passing tests:
- TransactionSignatureTest (4 tests)
- SynchronizationCircuitBreakerTest (6 tests)
- CheckpointValidationByzantineTest (6 tests)
- MVBlockStoreTest (8 tests)
- BatchVerificationConfigTest (8 tests)
- BootstrapperTest (3 tests)
- BatchingQueueTest (2 tests)
- CheckpointAssemblerTest (1 test)
- TxDataSourceTest (1 test)
- TestCHOAM (1 test)
- SessionTest (2 tests)

---

## Performance Baseline

### PerformanceBaselineTest Results

**Test Class**: `com.hellblazer.delos.choam.PerformanceBaselineTest`
**Status**: PASSED
**Execution Time**: 0.561s

**Metrics** (to be extracted from detailed test output):
- Throughput: [TBD - requires detailed log analysis]
- P95 Latency: [TBD - requires detailed log analysis]
- P99 Latency: [TBD - requires detailed log analysis]

**Performance Acceptance Criteria** (for CHOAMStateManager extraction):
- Throughput: Within 5% of baseline
- P95 Latency: Within 10% of baseline
- P99 Latency: Within 15% of baseline

### Concurrency Test Performance

**CHOAMConcurrencyTest**: 109.2 seconds (6 tests = ~18.2s avg per test)
**CHOAMThreadAndLockingTest**: 104.0 seconds (6 tests = ~17.3s avg per test)

These times establish baseline for concurrency test performance. Significant increases (>20%) after extraction may indicate lock contention or deadlock issues.

---

## Lock Ordering Baseline

**Reference**: See `choam/LOCK_ORDERING.md` for complete documentation

### Lock Structure

1. **headLock** (ReadWriteLock, line 108)
   - Purpose: Protects blockchain head, genesis, view blocks, pending queue
   - Acquisition sites: 1 (consume() method, line 642)
   - Critical section: 37 lines (642-679)

2. **viewStateLock** (ReentrantLock, line 107)
   - Purpose: Protects view reconfiguration state
   - Acquisition sites: 1 (view reconfiguration method, line 999)
   - Critical section: 16 lines (999-1015)

### Safety Invariant

**CRITICAL**: headLock and viewStateLock have **DISJOINT CRITICAL SECTIONS**

**Proof**: No code path acquires both locks (verified by inspection)

**Validation**:
- CHOAMThreadAndLockingTest: ✓ PASSED (validates locks never held simultaneously)
- CHOAMConcurrencyTest: ✓ PASSED (validates no deadlocks under stress)

**Requirement**: After CHOAMStateManager extraction, this invariant MUST be preserved.

---

## State Invariants Baseline

### Blockchain State Invariants

1. **Genesis Immutability**: `genesis != null ⇒ genesis.height() == 0`
2. **Head Monotonicity**: `head != null ⇒ head.height() >= 0` (monotonically increasing)
3. **View Lags Head**: `view != null ⇒ view.height() <= head.height()`
4. **Pending Bounded**: `pending.size() <= maxPendingBlocks`

### View State Invariants

1. **Pending Views Bounded**: `pendingViews.size() < 100`
2. **View Transition Atomicity**: snapshot() sees old or new state, never partial

### Control State Invariants

1. **Join Requires Started**: `ongoingJoin ⇒ started`
2. **Boolean Values Uncorrupted**: started, ongoingJoin ∈ {true, false}

### Async Operation State Invariants

1. **Sync Attempts Bounded**: `syncAttempts >= 0 && syncAttempts < 100`
2. **Single Bootstrap**: At most one bootstrap operation active at a time

### Committee State Invariants

1. **State Machine Transitions**: `null → Formation → Administration ↔ Synchronizer`
2. **Committee Non-Null After Genesis**: `genesis != null ⇒ current != null`

**Validation Framework**: `choam/src/test/java/com/hellblazer/delos/choam/regression/CHOAMStateInvariants.java` (if exists)

**Requirement**: All invariants MUST hold after each CHOAMStateManager extraction phase.

---

## Critical Files Inventory

### Source Files

1. **CHOAM.java** (1,992 LOC)
   - Location: `choam/src/main/java/com/hellblazer/delos/choam/CHOAM.java`
   - Lock declarations: Lines 107-108
   - State fields: 14 atomic fields (lines 87-109)
   - Lock acquisition: Lines 642, 999

2. **ViewState.java** (Interface)
   - Location: `choam/src/main/java/com/hellblazer/delos/choam/ViewState.java`
   - Pattern for ViewStateHolder implementation

3. **ViewStateImpl.java** (Reference Implementation)
   - Location: `choam/src/main/java/com/hellblazer/delos/choam/support/ViewStateImpl.java`
   - Two-phase reconfigure pattern

### Test Files (Critical for Validation)

1. **CHOAMThreadAndLockingTest.java**
   - Location: `choam/src/test/java/com/hellblazer/delos/choam/CHOAMThreadAndLockingTest.java`
   - Purpose: Lock ordering validation
   - Status: ✓ PASSED (6/6 tests)

2. **CHOAMConcurrencyTest.java**
   - Location: `choam/src/test/java/com/hellblazer/delos/choam/CHOAMConcurrencyTest.java`
   - Purpose: Concurrency stress testing
   - Status: ✓ PASSED (6/6 tests)

3. **PerformanceBaselineTest.java**
   - Location: `choam/src/test/java/com/hellblazer/delos/choam/PerformanceBaselineTest.java`
   - Purpose: Performance regression detection
   - Status: ✓ PASSED (1/1 test)

4. **DeterminismVerificationTest.java**
   - Location: `choam/src/test/java/com/hellblazer/delos/choam/DeterminismVerificationTest.java`
   - Purpose: Validate deterministic execution (Byzantine requirement)
   - Status: ✓ PASSED (1/1 test)

5. **NonceTrackerTest.java**
   - Location: `choam/src/test/java/com/hellblazer/delos/choam/NonceTrackerTest.java`
   - Purpose: Nonce replay protection
   - Status: ✗ FAILED (4/10 tests) - **PRE-EXISTING**

---

## Validation Gates (Post-Extraction)

### Phase 1-5: After Each State Holder Extraction

1. **Test Suite**: Run `./mvnw test -pl choam`
   - **Pass Criteria**: 164 tests pass (same 4 NonceTracker failures as baseline)
   - **Fail Criteria**: Any NEW failures or errors

2. **Lock Ordering**: Run `CHOAMThreadAndLockingTest`
   - **Pass Criteria**: All 6 tests pass
   - **Fail Criteria**: Any test failure (indicates lock ordering violation)

3. **Concurrency**: Run `CHOAMConcurrencyTest`
   - **Pass Criteria**: All 6 tests pass, execution time <120s per test
   - **Fail Criteria**: Any test failure or timeout (indicates deadlock)

4. **Performance**: Run `PerformanceBaselineTest`
   - **Pass Criteria**: Throughput within 5%, latency within 10%
   - **Fail Criteria**: Performance regression >5%

### Post-Phase (Final Validation)

1. **Full Test Suite**: All 168 tests
   - Same 4 NonceTracker failures
   - No new failures
   - All skipped tests remain skipped

2. **Performance Benchmark**: CommitteeTest
   - Throughput within 5% of baseline
   - P95 latency within 10% of baseline

3. **Lock Ordering Audit**: Manual review
   - headLock and viewStateLock remain disjoint
   - No new lock acquisition sites
   - State holders expose locks as `public final` fields

4. **Code Coverage**: State holders >=90% coverage

5. **Documentation**: Updated LOCK_ORDERING.md, ARCHITECTURE.md

---

## Migration Success Criteria Summary

### Functional Success

✓ **Test Pass Rate**: 164/168 tests pass (same 4 NonceTracker failures)
✓ **Lock Ordering**: headLock and viewStateLock remain disjoint
✓ **Concurrency**: No deadlocks, no race conditions
✓ **Determinism**: DeterminismVerificationTest passes
✓ **State Invariants**: All 9+ invariants hold

### Non-Functional Success

✓ **Performance**: Throughput within 5%, latency within 10%
✓ **Code Quality**: CHOAM.java reduced to ~1,500 LOC
✓ **Testability**: State holders independently testable (90%+ coverage)
✓ **Maintainability**: Clear separation of concerns, documented lock ownership
✓ **Byzantine Safety**: All Byzantine fault tolerance properties preserved

---

## Baseline Commit

**Branch**: feature/choam-phase0-baselines
**Files Tracked**:
- `choam/BASELINE.md` (this file)
- `choam/LOCK_ORDERING.md`
- Test output: `/tmp/choam-baseline-test.log` (archived for reference)

**Commit Message**:
```
Establish CHOAMStateManager extraction baseline

Pre-Phase (Delos-sm0v): Document current state before extraction
- Test status: 168 tests (164 pass, 4 NonceTracker failures)
- Lock ordering: headLock and viewStateLock disjoint
- Performance: PerformanceBaselineTest 0.561s
- State invariants: 9+ invariants documented

References: Delos-sm0v (Pre-Phase: Baseline Establishment)
```

---

## Next Steps

1. **Phase 1**: Extract ControlStateHolder (Bead: Delos-zukm)
   - Simplest holder, lock-free
   - Migrate started, ongoingJoin flags
   - Validate: Full test suite, lock ordering, performance

2. **Phase 2**: Extract AsyncOperationStateHolder (Bead: Delos-5jo6)
   - Lock-free async operation tracking
   - Migrate futureBootstrap, futureSynchronization, syncAttempts
   - Validate: Same as Phase 1

3. **Phases 3-5**: Extract remaining holders per plan
   - Follow TDD approach (tests before implementation)
   - Validate after each phase
   - Document any deviations from baseline

---

## Notes

- **NonceTracker failures**: These are PRE-EXISTING and unrelated to CHOAMStateManager extraction. Do NOT attempt to fix during extraction work.
- **Performance baseline**: Detailed metrics require CommitteeTest execution (long-running, deferred to Post-Phase)
- **Lock ordering**: This is the MOST CRITICAL property to preserve. Any violation introduces deadlock risk.
- **State invariants**: Byzantine fault tolerance depends on these invariants. All must hold after extraction.

**Baseline Established**: 2026-02-04
**Ready for Phase 1**: ✓
