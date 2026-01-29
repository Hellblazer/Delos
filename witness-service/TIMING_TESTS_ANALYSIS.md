# KeyRotationOrchestratorTest Timing Tests - Analysis & Findings

**See Also**:
- [docs/TESTING_GUIDE.md](../docs/TESTING_GUIDE.md) - Comprehensive testing guide with solutions for timing-sensitive tests
- [Common Pitfalls & Solutions](../docs/TESTING_GUIDE.md#common-pitfalls--solutions) - Timing-Sensitive Assertions pattern

## Executive Summary

**3 tests in KeyRotationOrchestratorTest remain flaky** due to fundamental architectural issues with timing-based assertions on scheduled tasks. These tests cannot be reliably fixed through timing adjustments alone.

## Problem Statement

The following tests fail unpredictably:
1. `shouldTransitionThroughAllPhases` (line 83)
2. `shouldSchedulePreRotationPhase` (line 123)
3. `shouldHandlePhaseCallbacks` (line 393)

**Failure Pattern**: Tests expect exact phase transitions at specific times, but ScheduledExecutorService provides no hard timing guarantees.

## Root Cause Analysis

### Architecture Issue
```java
// Current approach (unreliable)
orchestrator.startRotation(rotationId, memberId);
Thread.sleep(700);  // Hope scheduler executes within 700ms
assertThat(orchestrator.getCurrentPhase(rotationId))
    .isEqualTo(KeyRotationPhase.GRACE_PERIOD);  // May not have transitioned yet!
```

**Why it fails**:
- `ScheduledExecutorService.schedule()` is best-effort, not guaranteed
- Thread scheduling is OS-dependent
- System load affects timing
- Tests pass/fail non-deterministically

### Investigation Attempts

| Approach | Result | Duration |
|----------|--------|----------|
| Original (100ms/50ms phases) | ✗ Fails | N/A |
| 50% increase (150ms/75ms) | ✗ Fails | ~5min |
| 100% increase (200ms/100ms) | ✗ Fails | ~5min |
| 300% increase (300ms/150ms) | ✗ Fails | ~7min |
| 500% increase (500ms/250ms) | ✗ Fails | ~6min |

**Conclusion**: Adding more sleep time doesn't solve the fundamental issue.

## Recommended Solutions

### Option 1: Use CompletableFuture (Best)
```java
var resultFuture = orchestrator.startRotation(rotationId, testMemberId);

// Wait for specific result instead of guessing timing
var result = resultFuture.get(2000, TimeUnit.MILLISECONDS);
assertThat(result.phase()).isEqualTo(KeyRotationPhase.ACTIVATED);
```

**Advantages**:
- Reliable synchronization
- No timing assumptions
- Works on slow/fast systems

### Option 2: Use CountDownLatch
```java
var latch = new CountDownLatch(1);
orchestrator.registerPhaseCallback((oldPhase, newPhase) -> {
    if (newPhase == KeyRotationPhase.GRACE_PERIOD) {
        latch.countDown();
    }
});

orchestrator.startRotation(rotationId, testMemberId);
assertTrue(latch.await(2000, TimeUnit.MILLISECONDS), "GRACE_PERIOD not reached");
assertThat(orchestrator.getCurrentPhase(rotationId))
    .isEqualTo(KeyRotationPhase.GRACE_PERIOD);
```

**Advantages**:
- Synchronizes on actual events
- No arbitrary sleep times
- Clear intent

### Option 3: Mark as Flaky (Temporary)
```java
@Flaky(issueUrl = "https://github.com/Hellblazer/Delos/issues/XXXX")
@Test
void shouldTransitionThroughAllPhases() { ... }
```

**Use when**: Option 1/2 refactoring is deferred

## Architecture Improvement Needed

The `KeyRotationOrchestrator` should:
1. Return `CompletableFuture<RotationResult>` from `startRotation()`
2. Fire phase transition events/callbacks
3. Provide test-friendly synchronization primitives

**Current API**:
```java
public CompletableFuture<KeyRotationResult> startRotation(String rotationId, Identifier memberId)
```

**This is good!** - The orchestrator returns a future. Tests should use it instead of guessing timing.

## Proposed Fix (Priority: Medium)

Refactor the 3 flaky tests to use `resultFuture.get()` pattern instead of `Thread.sleep()`.

**Estimated effort**: 1-2 hours
**Risk**: Low (only changes test code)
**Reliability gain**: High (eliminates timing assumptions)

## Current Status (as of 2026-01-23)

### Test Results
- **Total tests in witness-service**: 1814
- **Total failures**: 6
- **Flaky timing tests**: 3
- **Reliability**: 99.84% (1808/1814 passing)

### Fixed Issues (Session)
- ✅ AggregatorInstrumentationTest: 12 failures fixed
- ✅ GracefulDegradationMetricsTest: 4 failures fixed
- ✅ BLSMetricsImplTest: 1 failure fixed
- ✅ CoordinatorMetricsIntegrationTest: 1 failure fixed
- ⚠️ KeyRotationOrchestratorTest: 3 timing tests (unfixable via timing adjustments)
- ⚠️ ViewChangeMetricsTest: 1 failure (unrelated)

## Tracking

**GitHub Issue**: [To be created]
**Epic**: Phase 1C-3 Test Infrastructure
**Severity**: Low (flaky, not broken)
**Type**: Technical Debt

## References

- `KeyRotationOrchestrator.java` - Scheduler implementation
- `KeyRotationOrchestratorTest.java` - Flaky test suite
- `ScheduledExecutorService` - Java docs on timing guarantees
