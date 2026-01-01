# Fork Rejection in Ethereal DAG

## Overview

In Ethereal's DAG (Directed Acyclic Graph) implementation, a **fork** occurs when the same creator produces two different units at the same height within the same epoch. This is a Byzantine fault—either malicious behavior or a software bug.

## Current Behavior

Forks are **partially rejected** from the DAG structure:

### What Happens When a Fork is Detected

1. **Duplicate Detection**: When a unit with the same hash is inserted, `decodeParents()` returns `Correctness.DUPLICATE_UNIT`

2. **Fork Handling** (same creator/height/epoch, different hash):
   - The **first** unit at a given (creator, height, epoch) is indexed in `heightUnits`
   - The **first** unit at a given (creator, level, epoch) is indexed in `levelUnits`
   - The **first** unit becomes the maximal unit for that creator
   - Subsequent forks are stored in the `units` map (by hash) but NOT indexed by position

3. **Practical Effect**:
   - ✅ Forks do NOT participate in the DAG structure
   - ✅ `dag.get(PreUnit.id(height, creator, epoch))` returns the original unit
   - ✅ Fork units are not in maximal units
   - ✅ Fork units are not in level-based operations
   - ⚠️ Fork units ARE stored in the units map (retrievable by hash)

## Implementation Details

### Key Code Locations

- `Dag.java`: Line 267-286 - `insert()` method
- `Dag.java`: Line 548-554 - `fiberMap.updateHeight()` - first-wins logic
- `Dag.java`: Line 204-227 - `decodeParents()` - duplicate detection

### First-Wins Logic

```java
// In fiberMap.updateHeight()
if (fiber[u.creator()] == null) {
    fiber[u.creator()] = u;  // Only first unit is indexed
}
```

The `if` statement ensures only the first unit at a given (creator, height) is stored in the height index.

## Test Coverage

### ForkRejectionTest.java

Three tests verify fork rejection behavior:

1. **testForkAttemptWithDifferentDataIsRejected**: Verifies that when a fork is attempted, the original unit remains at the (creator, height) position

2. **testOriginalUnitPreservedAfterForkAttempt**: Verifies the original unit remains in maximal units and is unchanged

3. **testSameHashIsDetectedAsDuplicate**: Verifies duplicate hash detection returns `DUPLICATE_UNIT`

### Disabled Tests

Two tests in `DagTest.java` are disabled because they expected the old behavior (forks being stored):

- `aboveWorkingFoTwoForksFromOneUnit` - Expected to retrieve multiple units at same height
- `correctForkedDealingUnits` - Expected forked dealing units to be stored

## Security Implications

From a Byzantine fault tolerance perspective, the current behavior is acceptable:

- ✅ Forks do not corrupt the consensus DAG structure
- ✅ The original (first) unit is preserved and used
- ✅ Fork attempts are effectively neutralized

Potential improvements:

- ❌ No explicit fork logging or alerting
- ❌ Fork units waste memory in the units map
- ❌ No `Correctness.FORK` classification (could be added)

## Future Enhancements

If explicit fork detection is needed:

1. Add `FORK` to `Correctness` enum
2. Enhance `decodeParents()` to check for existing unit at (creator, height)
3. Add logging when fork is detected
4. Optionally: prevent fork storage in units map to save memory

However, the current implicit rejection is sufficient for correctness.
