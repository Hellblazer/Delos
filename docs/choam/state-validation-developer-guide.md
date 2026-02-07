# CHOAM State Machine Validation - Developer Guide

**Version**: 1.0
**Last Updated**: 2026-02-07
**Applies To**: Delos CHOAM (Phases 1-5)
**Target Audience**: Developers adding transitions, debugging validation

---

## Table of Contents

1. [Architecture](#architecture)
2. [Adding New Transitions](#adding-new-transitions)
3. [Defining State Invariants](#defining-state-invariants)
4. [Testing with Validation](#testing-with-validation)
5. [Debugging Validation Failures](#debugging-validation-failures)
6. [Performance Considerations](#performance-considerations)
7. [Byzantine Detection Integration](#byzantine-detection-integration)
8. [Code Examples](#code-examples)

---

## Architecture

### Component Overview

```
┌─────────────────────────────────────────────────────────────────┐
│ CHOAM.java                                                       │
│   • Constructs FSM with sync=true                                │
│   • Conditionally wraps transitions based on FeatureFlags        │
│   • Provides captureStateSnapshot() for atomic state capture     │
└────────────────┬────────────────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────────────────┐
│ ValidatingCombineTransitions.java (Decorator)                    │
│   • Implements Combine.Transitions interface                     │
│   • Wraps all 13 transition methods                              │
│   • Validates pre/postconditions via StateTransitionValidator    │
│   • Logs violations, optionally throws (based on mode)           │
└────────────────┬────────────────────────────────────────────────┘
                 │
                 ├──────────────────┬─────────────────┐
                 ▼                  ▼                 ▼
┌────────────────────────┐  ┌──────────────┐  ┌──────────────────┐
│ StateTransitionMatrix  │  │ CHOAMState   │  │ Byzantine        │
│ • Maps transitions     │  │ Snapshot     │  │ DetectionMapper  │
│ • Pre/postconditions   │  │ • Immutable  │  │ • Classifies     │
│ • Complete coverage    │  │ • FSM-synced │  │   violations     │
└────────────────────────┘  └──────────────┘  └──────────────────┘
```

### Data Flow

```
1. Transition invoked (e.g., transitions.start())
       ↓
2. ValidatingCombineTransitions.start() called
       ↓
3. Capture pre-snapshot (CHOAM.captureStateSnapshot())
       ↓
4. Validate precondition (StateTransitionValidator)
       ├─ Valid → Continue
       └─ Invalid → Log + (optionally throw)
       ↓
5. Delegate to real FSM (Tron proxy)
       ↓
6. Capture post-snapshot (CHOAM.captureStateSnapshot())
       ↓
7. Validate postcondition (StateTransitionValidator)
       ├─ Valid → Return
       └─ Invalid → Log + (optionally throw)
       ↓
8. Map violation to Byzantine category (ByzantineDetectionMapper)
       ↓
9. Record metrics, log violation summary
```

### Key Design Principles

1. **Non-Invasive**: No changes to Tron FSM or CombinerFSM
2. **Lock-Free Snapshots**: Uses Fsm.synchronizeOnState() for atomicity
3. **Fail-Safe**: Validation errors don't crash FSM (LOG_ONLY mode)
4. **Performance Budget**: <244 μs p95 overhead (10% of 2.44ms baseline)
5. **Configurable**: Enable/disable via FeatureFlags with zero overhead when disabled

---

## Adding New Transitions

When you add a new state transition to CHOAM, follow these steps to integrate with validation:

### Step 1: Define the Transition in Tron FSM

```java
// In Combine.java (enum defining FSM)
public enum Mercantile implements Combine {
    // ... existing states ...

    // Add new state (if needed)
    NEW_STATE {
        @Override
        public Transitions validTransitions() {
            return transitions -> {
                transitions.newTransition();  // Declare transition
            };
        }
    };
}

// In Combine.Transitions interface
public interface Transitions {
    // ... existing transitions ...

    /**
     * New transition: <describe what it does>
     *
     * Preconditions:
     * - <condition 1>
     * - <condition 2>
     *
     * Postconditions:
     * - <expected outcome 1>
     * - <expected outcome 2>
     */
    void newTransition();
}
```

### Step 2: Add State Invariant (if new state)

```java
// In CHOAMStateInvariant.java
public enum CHOAMStateInvariant {
    // ... existing invariants ...

    /**
     * NEW_STATE invariant: <describe requirements>
     */
    NEW_STATE_INVARIANT(snapshot -> {
        // Define invariant predicate
        boolean started = snapshot.started();
        boolean hasSpecificState = snapshot.someField();

        return started && hasSpecificState;
    }, "NEW_STATE requires: started AND someField");

    // Constructor and methods (unchanged)
    // ...
}
```

### Step 3: Add Transition Specification

```java
// In StateTransitionMatrix.java (static initializer)
static {
    // ... existing transitions ...

    // Add new transition specification
    matrix.put(
        key(Mercantile.SOURCE_STATE, "newTransition"),
        new TransitionSpec(
            Mercantile.SOURCE_STATE,
            Mercantile.TARGET_STATE,
            "newTransition",

            // Precondition predicate
            snapshot -> {
                // Example: requires started but not yet in new state
                return snapshot.started() && !snapshot.someField();
            },

            // Postcondition predicate (pre and post snapshots)
            (pre, post) -> {
                // Example: transition must set someField
                return post.someField();
            },

            // Descriptions (for violation messages)
            "Precondition: started AND !someField",
            "Postcondition: someField must be set"
        )
    );
}
```

### Step 4: Implement in CombinerFSM Context

```java
// In CombinerFSM.java (FSM context)
public void newTransition() {
    // Implementation logic
    // Example:
    choam.performNewTransitionLogic();
}
```

### Step 5: Wrap in Decorator (Automatic)

The `ValidatingCombineTransitions` decorator **automatically wraps all transitions** via the `Combine.Transitions` interface. No manual wrapping needed.

**Verification**:
```java
// In ValidatingCombineTransitions.java (add method)
@Override
public void newTransition() {
    validateAndDelegate(
        "newTransition",
        delegate::newTransition
    );
}
```

**Note**: For transitions with parameters, adjust the wrapper signature:

```java
@Override
public void newTransitionWithParam(String param) {
    validateAndDelegate(
        "newTransitionWithParam",
        () -> delegate.newTransitionWithParam(param)
    );
}
```

### Step 6: Add Tests

```java
// In StateTransitionValidatorTest.java
@Test
public void testNewTransitionPrecondition() {
    var snapshot = new CHOAMStateSnapshot(
        true,   // started
        false,  // joinOngoing
        false,  // someField - INVALID for precondition
        // ... other fields
        "SOURCE_STATE"
    );

    var result = validator.validatePrecondition(
        Mercantile.SOURCE_STATE,
        "newTransition",
        snapshot
    );

    assertFalse(result.valid());
    assertTrue(result.violations().contains("!someField"));
}

@Test
public void testNewTransitionPostcondition() {
    var preSnapshot = new CHOAMStateSnapshot(/* valid pre-state */);
    var postSnapshot = new CHOAMStateSnapshot(
        // ... fields, someField should be true after transition
        true,  // someField
        "TARGET_STATE"
    );

    var result = validator.validatePostcondition(
        Mercantile.SOURCE_STATE,
        "newTransition",
        preSnapshot,
        postSnapshot
    );

    assertTrue(result.valid());
}
```

---

## Defining State Invariants

### Invariant Structure

State invariants are boolean predicates that must hold for a state to be valid.

**Template**:
```java
STATE_NAME_INVARIANT(snapshot -> {
    // Extract relevant fields
    boolean field1 = snapshot.field1();
    boolean field2 = snapshot.field2();

    // Combine into invariant
    return field1 && field2;  // AND logic
    // OR: return field1 || field2;  // OR logic
    // OR: return field1 && !field2;  // negation
}, "Human-readable invariant description");
```

### Common Patterns

#### 1. Lifecycle Invariant

**Pattern**: State requires system to be started

```java
OPERATIONAL_INVARIANT(snapshot -> {
    return snapshot.started();
}, "OPERATIONAL requires: started");
```

#### 2. Resource Invariant

**Pattern**: State requires specific resources to exist

```java
OPERATIONAL_INVARIANT(snapshot -> {
    boolean hasGenesis = snapshot.hasGenesis();
    boolean hasCommittee = snapshot.hasCommittee();
    boolean hasView = snapshot.hasView();

    return snapshot.started() && hasGenesis && hasCommittee && hasView;
}, "OPERATIONAL requires: started AND hasGenesis AND hasCommittee AND hasView");
```

#### 3. Mutual Exclusion Invariant

**Pattern**: State forbids certain conditions

```java
INITIAL_INVARIANT(snapshot -> {
    boolean started = snapshot.started();
    boolean joinOngoing = snapshot.joinOngoing();

    return !started && !joinOngoing;
}, "INITIAL requires: NOT started AND NOT joinOngoing");
```

#### 4. Dependency Invariant

**Pattern**: One field implies another

```java
BOOTSTRAPPING_INVARIANT(snapshot -> {
    boolean hasCommittee = snapshot.hasCommittee();
    String committeeType = snapshot.committeeType();

    // If hasCommittee, then committeeType must be GenesisFormation
    return !hasCommittee || "GenesisFormation".equals(committeeType);
}, "BOOTSTRAPPING: hasCommittee → committeeType == 'GenesisFormation'");
```

#### 5. Count Invariant

**Pattern**: State requires specific counts/ranges

```java
CHECKPOINTING_INVARIANT(snapshot -> {
    long headHeight = snapshot.headHeight();
    boolean hasHead = snapshot.hasHead();

    return hasHead && headHeight > 0;
}, "CHECKPOINTING requires: hasHead AND headHeight > 0");
```

### Invariant Testing

Always test invariants with both **valid** and **invalid** snapshots:

```java
@Test
public void testOperationalInvariantValid() {
    var validSnapshot = new CHOAMStateSnapshot(
        true,   // started
        false,  // joinOngoing
        true,   // hasCommittee
        "Standard",  // committeeType
        true,   // hasGenesis
        true,   // hasHead
        100,    // headHeight
        true,   // hasView
        50,     // viewHeight
        0,      // pendingViewCount
        0,      // syncAttempts
        false,  // bootstrapActive
        false,  // syncScheduled
        "OPERATIONAL"
    );

    var result = validator.validateInvariant(validSnapshot);
    assertTrue(result.valid());
}

@Test
public void testOperationalInvariantInvalid_MissingView() {
    var invalidSnapshot = new CHOAMStateSnapshot(
        true,   // started
        false,  // joinOngoing
        true,   // hasCommittee
        "Standard",
        true,   // hasGenesis
        true,   // hasHead
        100,
        false,  // hasView - INVALID
        -1,
        0, 0, false, false,
        "OPERATIONAL"
    );

    var result = validator.validateInvariant(invalidSnapshot);
    assertFalse(result.valid());
    assertTrue(result.violations().contains("missing view"));
}
```

---

## Testing with Validation

### Unit Tests (Isolated)

Test validation logic independently of FSM:

```java
@Test
public void testPreconditionValidation() {
    var matrix = StateTransitionMatrix.getInstance();
    var metrics = new SimpleMeterRegistry();
    var validator = new StateTransitionValidator(matrix, metrics);

    var snapshot = new CHOAMStateSnapshot(/* ... */);

    var result = validator.validatePrecondition(
        Mercantile.INITIAL,
        "start",
        snapshot
    );

    // Assert on result
    assertTrue(result.valid());
    assertEquals(0, result.violations().size());
}
```

### Integration Tests (With FSM)

Test validation with real CHOAM instance:

```java
@Test
public void testStartTransitionWithValidation() {
    // Enable validation for test
    FeatureFlags.STATE_VALIDATION.setEnabled(true);

    try {
        var choam = createCHOAM(/* params */);

        // Transition should succeed
        choam.start();

        // Verify no violations logged (check metrics)
        var violations = getValidationViolations(choam);
        assertEquals(0, violations);
    } finally {
        // Clean up
        FeatureFlags.STATE_VALIDATION.setEnabled(false);
    }
}
```

### Property-Based Tests

Test random valid transition sequences:

```java
@Test
public void testRandomValidTransitionSequences() {
    var random = new SeededSecureRandom("test-seed");
    var choam = createCHOAM(/* ... */);

    FeatureFlags.STATE_VALIDATION.setEnabled(true);

    for (int i = 0; i < 1000; i++) {
        var validTransition = selectRandomValidTransition(choam.currentState(), random);
        executeTransition(choam, validTransition);

        // Should have zero violations
        assertEquals(0, getValidationViolations(choam));
    }
}
```

### Adversarial Tests

Test invalid transitions are detected:

```java
@Test
public void testInvalidTransitionDetected() {
    var choam = createCHOAM(/* ... */);

    FeatureFlags.STATE_VALIDATION.setEnabled(true);

    // Attempt invalid transition (e.g., start() when already started)
    choam.start();  // First start (valid)
    choam.start();  // Second start (invalid precondition)

    // Should log PRECONDITION_VIOLATION
    var violations = getValidationViolations(choam);
    assertTrue(violations > 0);

    // Verify violation type
    var violationType = getLastViolationType(choam);
    assertEquals(ByzantineViolationType.PRECONDITION_VIOLATION, violationType);
}
```

---

## Debugging Validation Failures

### Common Failure Modes

#### 1. Precondition Violation

**Symptom**: Transition rejected before execution

**Debug Steps**:

1. **Check snapshot at violation time**:
```java
// In ValidatingCombineTransitions.java (add debug logging)
log.warn("PRECONDITION_VIOLATION: {}", result.violations());
log.debug("Snapshot: {}", preSnapshot);
```

2. **Verify precondition logic**:
```java
// In StateTransitionMatrix.java
// Review precondition predicate for the transition
snapshot -> {
    // Is this condition too strict?
    return snapshot.started() && !snapshot.joinOngoing();
}
```

3. **Check for race conditions**:
   - Concurrent transitions modifying state
   - Snapshot captured mid-transition (should not happen with FSM lock)

**Common Causes**:
- Duplicate transition call
- Unexpected state from previous transition
- Missing state initialization

**Fix**:
- Add guard in transition implementation
- Initialize state properly in entry action
- Adjust precondition if overly strict

#### 2. Postcondition Violation

**Symptom**: Transition executed but expected outcome not achieved

**Debug Steps**:

1. **Compare pre and post snapshots**:
```java
log.warn("POST violation - Pre: {}, Post: {}", preSnapshot, postSnapshot);
```

2. **Check transition implementation**:
```java
// In CombinerFSM.java
public void start() {
    // Did this logic actually set started=true?
    choam.setStarted(true);  // Ensure this is called
}
```

3. **Verify snapshot timing**:
   - Is post-snapshot captured too early?
   - Is state update asynchronous?

**Common Causes**:
- Exception swallowed during transition
- Asynchronous state update (snapshot captured before completion)
- Missing state update in implementation

**Fix**:
- Add synchronous state update before transition returns
- Fix exception handling
- Adjust postcondition if unrealistic

#### 3. State Invariant Violation

**Symptom**: State becomes invalid (torn read or corruption)

**Debug Steps**:

1. **Check for TOCTOU race**:
```java
// Is snapshot captured atomically?
// Verify CHOAM.captureStateSnapshot() uses Fsm.synchronizeOnState()
```

2. **Review state modification patterns**:
   - Are StateHolders updated atomically?
   - Are updates properly synchronized?

3. **Check Byzantine indicators**:
   - Correlation with signature failures?
   - Pattern of violations (single node vs. cluster-wide)?

**Common Causes**:
- Race condition in state update
- Byzantine attack (state corruption)
- Snapshot inconsistency (if FSM lock not held)

**Fix**:
- Add synchronization to state updates
- Use atomic state holders (AtomicReference)
- If Byzantine: isolate and replace node

### Debugging Tools

#### 1. Heap Dump Analysis

Capture state at violation time:

```bash
# Trigger heap dump on PRECONDITION_VIOLATION
jmap -dump:live,format=b,file=/tmp/violation.hprof <pid>

# Analyze with Eclipse MAT
mat /tmp/violation.hprof
# Query: Find CHOAMStateSnapshot instances
# Compare expected vs. actual field values
```

#### 2. Thread Dump

Check for lock contention or deadlock:

```bash
jstack <pid> > /tmp/threads.txt
# Look for threads in BLOCKED state
# Check FSM lock holders
```

#### 3. Metrics Analysis

Review validation latency distribution:

```bash
# Connect to metrics endpoint
curl localhost:9090/metrics | grep validation

# Identify slow paths
validation.precondition.latency{quantile="0.95"} 500.0  # Slow!
```

---

## Performance Considerations

### Overhead Budget

**SLA**: p95 validation latency < 244 μs (10% of baseline 2.44 ms)

**Breakdown**:
- Snapshot capture: **0.2 μs** (lock-free reads)
- Precondition check: **5 μs** (predicate evaluation)
- Postcondition check: **5 μs** (predicate evaluation)
- Logging (if violation): **variable** (async preferred)
- **Total**: ~10-12 μs nominal, <244 μs p95 (includes outliers)

### Optimization Techniques

#### 1. Minimize Snapshot Capture Time

**Do**:
- Use AtomicReference.get() (lock-free)
- Capture under Fsm.synchronizeOnState() (atomicity without new locks)
- Avoid expensive computations during capture

**Don't**:
- Acquire new locks during snapshot
- Perform I/O (database, network)
- Clone large objects

**Example**:
```java
public CHOAMStateSnapshot captureStateSnapshot() {
    return fsm.synchronizeOnState(() -> {
        // Fast: direct field access
        return new CHOAMStateSnapshot(
            started.get(),           // AtomicBoolean
            hasCommittee.get(),      // AtomicBoolean
            committeeType.get(),     // AtomicReference<String>
            // ... other fields
            fsm.getCurrentState().name()
        );
    });
}
```

#### 2. Optimize Predicate Evaluation

**Do**:
- Short-circuit boolean expressions (most restrictive first)
- Cache expensive calculations
- Avoid string operations (use enums)

**Don't**:
- Perform regex matching
- Call external methods
- Allocate objects in hot path

**Example**:
```java
// Good: short-circuit AND (started is most restrictive)
snapshot -> snapshot.started() && snapshot.hasGenesis() && snapshot.hasView()

// Bad: expensive string operation
snapshot -> snapshot.committeeType().matches("Genesis.*")  // SLOW

// Better: enum or equals
snapshot -> "GenesisFormation".equals(snapshot.committeeType())
```

#### 3. Conditional Logging

**Do**:
- Use log level guards
- Log violations asynchronously
- Rate-limit high-frequency violations

**Don't**:
- Log every validation success
- Perform expensive formatting in log statements
- Block on log I/O

**Example**:
```java
if (!result.valid() && log.isWarnEnabled()) {
    // Only format message if WARN enabled
    log.warn("Violation: {}", result.summary());
}
```

### Benchmarking

**Phase 0 Baseline Test**:
```java
@Test
public void benchmarkSnapshotCapture() {
    var choam = createCHOAM(/* ... */);
    var iterations = 10_000;

    var startNanos = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
        choam.captureStateSnapshot();
    }
    var durationNanos = System.nanoTime() - startNanos;

    var avgNanos = durationNanos / iterations;
    System.out.printf("Avg snapshot time: %.2f μs%n", avgNanos / 1000.0);

    // SLA: < 0.2 μs average
    assertTrue(avgNanos < 200, "Snapshot too slow: " + avgNanos + " ns");
}
```

---

## Byzantine Detection Integration

### Classification Framework

Validation failures are automatically mapped to Byzantine categories by `ByzantineDetectionMapper`.

**Workflow**:
```
ValidationResult (from StateTransitionValidator)
       ↓
ByzantineDetectionMapper.mapViolation()
       ↓
ByzantineViolation (classified with severity and remediation)
```

### Violation Mapping

| ValidationResult.ValidationType | Byzantine Category |
|---------------------------------|--------------------|
| INVARIANT (OPERATIONAL state) | STATE_INVARIANT_VIOLATION (CRITICAL) |
| INVARIANT (other states) | STATE_INVARIANT_VIOLATION (HIGH) |
| PRECONDITION | PRECONDITION_VIOLATION (HIGH) |
| POSTCONDITION | POSTCONDITION_VIOLATION (HIGH) |
| Invariant + "consistency" keyword | STATE_INCONSISTENCY (MEDIUM) |
| Invariant + "equivocation" keyword | EQUIVOCATION (CRITICAL) |
| Precondition + "timing" keyword | TIMING_ANOMALY (MEDIUM) |

### Adding Custom Detection

**Example**: Detect repeated threshold bypass attempts

```java
// In ByzantineDetectionMapper.java
private ByzantineViolationType classifyViolation(ValidationResult result) {
    var violations = result.violations();
    var firstViolation = violations.get(0).toLowerCase();

    // Custom detection: threshold bypass
    if (firstViolation.contains("threshold") && firstViolation.contains("bypass")) {
        return ByzantineViolationType.THRESHOLD_BYPASS;  // Add new enum
    }

    // Existing logic...
}
```

### Severity Escalation

**Pattern**: Repeated violations increase severity

```java
// In ByzantineDetectionMapper.java
private Severity assessSeverity(ByzantineViolationType type, ValidationResult result) {
    return switch (type) {
        case STATE_INCONSISTENCY -> {
            long count = violationCounts.get(type).get();
            // Escalate after 10 occurrences
            yield count > 10 ? Severity.HIGH : Severity.MEDIUM;
        }
        // ... other cases
    };
}
```

### Testing Byzantine Detection

```java
@Test
public void testEquivocationDetection() {
    var result = new ValidationResult(
        false,
        List.of("Equivocation detected: conflicting post-states"),
        Mercantile.OPERATIONAL,
        "combine",
        Instant.now(),
        ValidationResult.ValidationType.POSTCONDITION
    );

    var mapper = new ByzantineDetectionMapper();
    var violation = mapper.mapViolation(result);

    assertEquals(ByzantineViolationType.EQUIVOCATION, violation.type());
    assertEquals(Severity.CRITICAL, violation.severity());
    assertTrue(violation.isCritical());
}
```

---

## Code Examples

### Example 1: Simple Transition with Validation

```java
// 1. Define transition in StateTransitionMatrix
matrix.put(
    key(Mercantile.INITIAL, "start"),
    new TransitionSpec(
        Mercantile.INITIAL,
        Mercantile.RECOVERING,
        "start",
        snapshot -> !snapshot.started(),  // Precondition: not started
        (pre, post) -> post.started(),    // Postcondition: now started
        "Precondition: !started",
        "Postcondition: started == true"
    )
);

// 2. Implement in CombinerFSM.java
public void start() {
    choam.setStarted(true);
    choam.scheduleAwaitSynchronization();
}

// 3. Test with validation
@Test
public void testStartTransition() {
    FeatureFlags.STATE_VALIDATION.setEnabled(true);

    var choam = createCHOAM();
    assertFalse(choam.isStarted());

    choam.start();
    assertTrue(choam.isStarted());

    // Verify no violations
    assertEquals(0, getValidationViolations(choam));
}
```

### Example 2: Complex Postcondition

```java
// Transition with multiple postcondition checks
matrix.put(
    key(Mercantile.RECOVERING, "bootstrap"),
    new TransitionSpec(
        Mercantile.RECOVERING,
        Mercantile.BOOTSTRAPPING,
        "bootstrap",
        snapshot -> snapshot.started(),  // Simple precondition
        (pre, post) -> {
            // Complex postcondition: must create GenesisFormation committee
            boolean committeeCreated = post.hasCommittee();
            boolean isGenesisFormation = "GenesisFormation".equals(post.committeeType());
            return committeeCreated && isGenesisFormation;
        },
        "Precondition: started",
        "Postcondition: hasCommittee AND committeeType == 'GenesisFormation'"
    )
);
```

### Example 3: Custom Validation Metrics

```java
// In CHOAM.java (if using custom metrics)
var validationMetrics = new SimpleMeterRegistry();

// Add custom tags for per-state metrics
validationMetrics.config().commonTags("instance", params.member().getId().toString());

var validator = new StateTransitionValidator(matrix, validationMetrics);

// Query metrics later
validationMetrics.find("validation.violations")
    .tag("instance", "Member[12345]")
    .counter()
    .count();
```

---

## Summary

Key takeaways for developers:

✅ **Add transitions** to StateTransitionMatrix with pre/postconditions
✅ **Define invariants** for new states in CHOAMStateInvariant
✅ **Test validation** with unit, integration, and adversarial tests
✅ **Debug failures** using snapshots, metrics, and heap dumps
✅ **Optimize** snapshot capture and predicate evaluation
✅ **Integrate Byzantine detection** via ByzantineDetectionMapper

For operator documentation, see: [state-validation-operator-guide.md](state-validation-operator-guide.md)

---

## Reference

### Key Files

| File | Purpose |
|------|---------|
| `CHOAMStateSnapshot.java` | Immutable state capture record |
| `CHOAMStateInvariant.java` | Per-state invariant predicates (enum) |
| `TransitionSpec.java` | Transition specification record |
| `StateTransitionMatrix.java` | Complete transition map (static) |
| `StateTransitionValidator.java` | Validation engine |
| `ValidationResult.java` | Validation outcome record |
| `ValidatingCombineTransitions.java` | Decorator wrapping FSM transitions |
| `ByzantineDetectionMapper.java` | Violation classification |
| `ByzantineViolation.java` | Byzantine violation record |
| `FeatureFlags.java` | STATE_VALIDATION feature flag |

### Validation SLAs

| Metric | SLA |
|--------|-----|
| Snapshot capture | p95 < 0.2 μs |
| Precondition check | p95 < 5 μs |
| Postcondition check | p95 < 5 μs |
| End-to-end validation | p95 < 244 μs |
| Latency overhead | < 10% of baseline |
| Throughput impact | < 10% of baseline |

### Contact

**Questions**: File issue in JIRA with `choam-validation` label
**Code Reviews**: Tag @choam-team for validation changes
**Bugs**: Include reproduction steps and snapshot logs

**Maintainers**: CHOAM Reliability Team
**Version**: 1.0 (2026-02-07)
