# CHOAM Public Accessor Change Request

**Task**: Delos-4045 - API Verification Sprint
**Date**: 2026-01-25
**Priority**: P0 - Blocking Witness Integration

## Problem Statement

The witness service requires access to CHOAM's BlockStore, BlockProcessor, and CheckpointManager for:
1. Block height binding (receipt → block association)
2. Recovery operations (replay from checkpoint)
3. Block hash verification (integrity checks)

These components exist but are encapsulated as **private fields** in `CHOAM.java` with no public accessors.

## Affected Code

**File**: `choam/src/main/java/com/hellblazer/delos/choam/CHOAM.java`

```java
// Lines 83-99: Private fields requiring public access
private final BlockProcessor blockProcessor;     // Line 84
private final CheckpointManager checkpointManager; // Line 83
private final BlockStore store;                  // Line 99
```

## Proposed Changes

Add three public accessor methods to `CHOAM.java`:

```java
/**
 * Returns the BlockStore for block lookup and hash operations.
 * <p>
 * Required for witness service block binding and recovery.
 *
 * @return the block store instance
 */
public BlockStore getBlockStore() {
    return store;
}

/**
 * Returns the BlockProcessor for block processing operations.
 * <p>
 * Required for witness service log replay during recovery.
 *
 * @return the block processor instance
 */
public BlockProcessor getBlockProcessor() {
    return blockProcessor;
}

/**
 * Returns the CheckpointManager for checkpoint operations.
 * <p>
 * Required for witness service checkpoint-based recovery.
 *
 * @return the checkpoint manager instance
 */
public CheckpointManager getCheckpointManager() {
    return checkpointManager;
}
```

## Use Cases

### 1. Block Height Binding

```java
// WitnessCHOAM.java - Receipt creation
public WitnessReceipt createReceipt(EventCoordinates coords, BLSSignature sig) {
    var height = choam.currentHeight();           // Already public
    var hash = choam.getBlockStore().hash(height); // NEW accessor needed

    return WitnessReceipt.newBuilder()
        .setChoamBlockHeight(height)
        .setChoamBlockHash(hash)
        .build();
}
```

### 2. Recovery Operations

```java
// WitnessCHOAM.java - Recovery from checkpoint
public void recoverFromCheckpoint(long checkpointHeight) {
    var checkpoint = choam.getCheckpointManager().lastCheckpoint(); // NEW accessor
    var blocks = choam.getBlockStore().blocksFrom(checkpointHeight); // NEW accessor

    blocks.forEach(this::replayWitnessOperations);
}
```

### 3. Block Hash Verification

```java
// WitnessCHOAM.java - Receipt validation
public boolean validateReceiptBlock(WitnessReceipt receipt) {
    var expectedHash = choam.getBlockStore().hash(receipt.getChoamBlockHeight());
    return Arrays.equals(expectedHash, receipt.getChoamBlockHash().toByteArray());
}
```

## API Safety Analysis

| Component | Thread Safety | Mutability | Risk |
|-----------|---------------|------------|------|
| BlockStore | Thread-safe (concurrent collections) | Read-only operations exposed | Low |
| BlockProcessor | Thread-safe | Process operations are idempotent | Low |
| CheckpointManager | Thread-safe | Read-only for checkpoint queries | Low |

## Alternatives Considered

### Alternative 1: Add Witness-Specific Methods

Add targeted methods like `getBlockHash(long height)` instead of exposing full components.

**Pros**: Minimal API surface
**Cons**: Anticipates all use cases upfront; inflexible for recovery scenarios

**Decision**: Rejected - recovery operations require full BlockStore access

### Alternative 2: Subclass CHOAM for Witness

Create `WitnessCHOAM extends CHOAM` with protected access.

**Pros**: No changes to base CHOAM
**Cons**: Tightly couples witness to CHOAM internals; maintenance burden

**Decision**: Rejected - accessors are cleaner

### Alternative 3: Composition via Builder

Pass components to witness service during CHOAM construction.

**Pros**: Explicit dependency injection
**Cons**: Changes CHOAM.Builder API; complicates bootstrap

**Decision**: Rejected - accessor pattern is simpler

## Testing Impact

No changes to existing CHOAM tests. New tests for accessor methods:

```java
@Test
void testBlockStoreAccessor() {
    var choam = createTestCHOAM();
    assertNotNull(choam.getBlockStore());
    assertEquals(0L, choam.getBlockStore().firstBlock());
}

@Test
void testCheckpointManagerAccessor() {
    var choam = createTestCHOAM();
    assertNotNull(choam.getCheckpointManager());
}

@Test
void testBlockProcessorAccessor() {
    var choam = createTestCHOAM();
    assertNotNull(choam.getBlockProcessor());
}
```

## Implementation Checklist

- [ ] Add `getBlockStore()` to CHOAM.java
- [ ] Add `getBlockProcessor()` to CHOAM.java
- [ ] Add `getCheckpointManager()` to CHOAM.java
- [ ] Add Javadoc for each accessor
- [ ] Add unit tests for accessors
- [ ] Update module documentation

## Dependencies

**Blocks**: Delos-4043 (CHOAM Recovery Testing)

Without these accessors, the witness service cannot:
- Bind receipts to specific blocks
- Recover from checkpoints
- Validate receipt block references
