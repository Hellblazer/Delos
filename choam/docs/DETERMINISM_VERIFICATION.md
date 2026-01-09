# CHOAM Determinism Verification

## Overview

Determinism verification ensures that CHOAM, as a replicated state machine, produces identical results across all consensus replicas. This is critical for maintaining Byzantine fault tolerance properties and preventing silent corruption during refactoring.

**Primary Test**: `DeterminismVerificationTest.java`

## Why Determinism Matters

CHOAM is a state machine replicated across multiple members using Ethereal (Aleph-BFT) consensus:

1. **Ethereal guarantees**: All honest members receive transactions in the same order
2. **CHOAM obligation**: Given same transaction order, all replicas must produce identical block hashes and state

If CHOAM becomes non-deterministic (e.g., due to refactoring bugs), the system can:
- Silently diverge in state (different databases at different replicas)
- Produce different block hashes (breaking merkle proofs)
- Violate Byzantine fault tolerance guarantees (diverged state can't be recovered)

## Determinism Threats

These issues can introduce non-determinism:

| Threat | Symptom | Example |
|--------|---------|---------|
| **Random state generation** | Non-deterministic hashing | Using `Math.random()` for IDs |
| **Timing-dependent logic** | Different execution order per thread | `HashMap` iteration (use `TreeMap`) |
| **Floating point math** | Precision differences | FP32 vs FP64 conversions |
| **Clock-dependent state** | Different clock readings | Using `System.currentTimeMillis()` for state |
| **Unbounded iteration** | Different traversal order | Non-sorted collections |

## Test Design

### What It Verifies

```
DeterminismVerificationTest
├── verifyDeterministicBlockProduction()
│   ├── Start 4-member consensus cluster
│   ├── Submit fixed transaction sequence
│   ├── All members process same transactions in same order (Ethereal)
│   └── Verify all reach consistent heights (proof of determinism)
│
└── verifyHeightConsistency()
    └── Check max_height - min_height <= 2 (network timing allowance)
```

### Key Assertions

1. **Height Consistency**: All members reach same block height (within 2-block network tolerance)
   - If members diverge in height, blocks must have been produced differently
   - Same transaction order + same block production = identical heights

2. **Block Production Order**: All members log identical block hashes in same order
   - Logs show "Publishing: {hash} height: {n}"
   - Same hashes at same heights = deterministic execution

### Test Configuration

```java
CARDINALITY = 4                    // 3f+1 Byzantine tolerance
TRANSACTION_BATCH_SIZE = 10        // Sufficient transactions to reach multiple heights
Fixed entropy seed (42, 42, 42)    // Reproducible random number generation
```

## Running the Test

**Single test**:
```bash
./mvnw test -pl choam -Dtest=DeterminismVerificationTest
```

**All CHOAM tests**:
```bash
./mvnw test -pl choam
```

**Expected output**:
```
All members produce identical block hashes at each height
Height divergence <= 2 blocks (network timing)
Test PASSES
```

## Extending the Test

### Phase 1 Enhancement: Block Hash Recording

Currently verifies height consistency. To add detailed hash verification:

```java
// In CHOAM.java, add block production hook:
blockProducer.onBlockProduced(height, hash, block);

// In DeterminismRecorder, capture hashes:
private Map<Integer, Digest> blockHashes = new ConcurrentHashMap<>();

void recordBlockHash(int height, Digest hash) {
    blockHashes.put(height, hash);
    maxHeight.getAndSet(Math.max(maxHeight.get(), height));
}
```

Then add hash verification to test:

```java
private void verifyBlockHashConsistency() {
    // Compare block hashes at each height across all members
    for (int height = 1; height <= maxHeight; height++) {
        Set<Digest> hashes = recorders.stream()
            .map(r -> r.getBlockHashAt(height))
            .collect(Collectors.toSet());

        assertTrue(hashes.size() == 1,
                   "Hash mismatch at height " + height);
    }
}
```

### Phase 2 Enhancement: Replay Testing

After ensuring determinism is preserved:

1. Record transaction sequence from successful run
2. Run again with same sequence
3. Verify identical block hashes produced
4. This proves determinism is preserved

## Integration with Phase 1

**Critical Prerequisite**: Run DeterminismVerificationTest before Phase 1 refactoring:
- Establishes baseline determinism
- Documents current behavior
- Provides regression test for refactoring

**During Phase 1**: Run test after each major change:
- P1-1: After BlockStore extraction
- P1-2: After ConsensusEngine extraction
- P1-3: After CHOAM decomposition
- P1-4: After admission control addition

**Success Criteria**: Test passes with zero height divergence throughout Phase 1

## Debugging Non-Determinism

If test fails with height divergence:

1. **Check logs** for "Publishing" lines at each member
   - Different hashes at same height = non-determinism
   - Same hashes = network timing variance (expected)

2. **Compare block sequences**:
   ```
   Member A: height=39, hash=9736...
   Member B: height=38, hash=9736... (missing one block - lag)
   ```

3. **Look for these patterns**:
   - Random state generation (use fixed seeds)
   - Collection ordering (use sorted collections)
   - Clock dependencies (use logical time)
   - Floating point (use BigDecimal or exact arithmetic)

## Related Files

- `CHOAM.java`: Core state machine (1,796 lines)
- `Producer.java`: Block production
- `Store.java`: Persistent state with deterministic replay
- `RECOVERY_PROTOCOL_MAPPING.md`: Deterministic recovery procedures

## References

- Ethereum Consensus Algorithm: [https://ethereum.org/developers/articles/consensus-mechanisms-explained/](https://ethereum.org/developers/articles/consensus-mechanisms-explained/)
- Byzantine Fault Tolerance: [Practical Byzantine Fault Tolerance](http://pmg.csail.mit.edu/papers/osdi99.pdf)
- Aleph-BFT (Ethereal basis): [A Secure Totally Asynchronous Message Ordering](https://arxiv.org/abs/1805.06358)
