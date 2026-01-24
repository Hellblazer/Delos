# Delos-4001: RecursiveProofValidator BLS Integration

**Epic**: Delos-3992 (Recursive Epoch Aggregation)
**Phase**: 3.1 - BLS Cryptographic Verification
**Status**: Implementation Plan
**Dependencies**: Delos-3999 (COMPLETE), Delos-4000 (COMPLETE)
**Blocks**: Delos-4002, Delos-4004

---

## Executive Summary

This plan adds BLS cryptographic signature verification to the existing `RecursiveProofValidator`, layering it on top of the structural validation implemented in Phase 1C-2-B. The integration provides complete proof verification for cross-epoch consensus receipts while maintaining O(log n) complexity per epoch and the stateless/thread-safe design pattern.

---

## Architecture Overview

### Current State (Structural Validation Only)

```
RecursiveProofValidator
├── verifyReceipt(receipt, rootHashLookup)      [Structure only]
├── verifyPath(path)                             [Structure only]
├── verifyHierarchicalAggregate(aggregate)      [Structure only]
├── findInvalidNodes(node)                       [Byzantine isolation]
└── verifyTreeNode(node)                        [Internal]
```

### Target State (Structural + Cryptographic)

```
RecursiveProofValidator
├── [EXISTING] Structural Validation
│   ├── verifyReceipt(receipt, rootHashLookup)
│   ├── verifyPath(path)
│   └── findInvalidNodes(node)
│
└── [NEW] BLS Cryptographic Verification
    ├── verifyReceiptWithBLS(receipt, rootHashLookup, keyResolver, message)
    ├── verifyPathWithBLS(path, keyResolver, message)
    ├── verifyEpochSequenceWithBLS(epochs, keyResolver, message)
    └── [Internal helpers]
        ├── verifyHierarchicalAggregateBLS(aggregate, keyResolver, message)
        ├── verifyTreeNodeBLS(node, committeeKeys, message)
        └── verifyEpochLinkBLS(link, epochKeys, message)
```

### Layered Verification Pipeline

```
                          ┌─────────────────────────┐
                          │    Input: Receipt       │
                          └───────────┬─────────────┘
                                      │
                          ┌───────────▼─────────────┐
                          │   LAYER 1: Structural   │
                          │   (existing methods)    │
                          └───────────┬─────────────┘
                                      │
                              ┌───────▼───────┐
                              │   Passes?     │──No──▶ Return structural failure
                              └───────┬───────┘
                                      │ Yes
                          ┌───────────▼─────────────┐
                          │   LAYER 2: BLS Crypto   │
                          │   (new methods)         │
                          └───────────┬─────────────┘
                                      │
                              ┌───────▼───────┐
                              │   Passes?     │──No──▶ Return crypto failure
                              └───────┬───────┘
                                      │ Yes
                          ┌───────────▼─────────────┐
                          │   ValidationResult.     │
                          │   Valid()               │
                          └─────────────────────────┘
```

---

## Key Design Decisions

### 1. KeyResolver Interface Design

The `RecursiveKeyResolver` interface abstracts key material access for verification:

```java
/**
 * Resolver for BLS public keys used in recursive proof verification.
 *
 * Contract:
 * - Thread-safe: Implementations must be safe for concurrent access
 * - Non-blocking: Implementations should not perform blocking I/O
 * - Deterministic: Same epoch/committee should return same keys
 */
public interface RecursiveKeyResolver {

    /**
     * Get public keys for all members of a committee at an epoch.
     * Keys are ordered by committee member index (matching bitmap order).
     *
     * @param epoch The epoch number
     * @param committeeIndex Committee index within the epoch
     * @return List of BLS public keys, empty if committee not found
     */
    List<BLSPublicKey> getCommitteeKeys(long epoch, int committeeIndex);

    /**
     * Get all committee keys for an epoch (for full tree verification).
     * Index in outer list corresponds to committeeIndex.
     *
     * @param epoch The epoch number
     * @return List of committee key lists, empty if epoch not found
     */
    List<List<BLSPublicKey>> getAllCommitteeKeys(long epoch);

    /**
     * Get signing keys for epoch-level signatures (EpochLink.Changed).
     * These are the aggregate of all committee root keys.
     *
     * @param epoch The epoch number
     * @return List of epoch signing keys
     */
    List<BLSPublicKey> getEpochSigningKeys(long epoch);

    /**
     * Optional grace period support for key rotation.
     * When present, verification will try deprecated keys if active keys fail.
     *
     * @return Optional grace period lookup, empty if not supported
     */
    default Optional<GracePeriodKeyLookup> getGracePeriodLookup() {
        return Optional.empty();
    }
}
```

**Rationale**:
- Functional interface per committee enables lazy resolution
- Separation of committee keys vs epoch keys reflects the hierarchical structure
- Optional grace period follows WitnessSignatureValidator pattern

### 2. Extended ValidationResult Types

Add BLS-specific failure types to the existing sealed interface:

```java
public sealed interface ValidationResult
    permits ValidationResult.Valid,
            ValidationResult.Invalid,
            ValidationResult.SignatureVerificationFailed,
            ValidationResult.KeyResolutionFailed,
            ValidationResult.GracePeriodExhausted {

    // Existing types unchanged...

    /**
     * BLS signature verification failed.
     */
    record SignatureVerificationFailed(
        String reason,
        long epoch,
        OptionalInt committeeIndex  // Empty for epoch-level signature
    ) implements ValidationResult {
        // Constructor validates non-null reason
    }

    /**
     * Key resolver could not provide required keys.
     */
    record KeyResolutionFailed(
        String reason,
        long epoch,
        OptionalInt committeeIndex
    ) implements ValidationResult {}

    /**
     * All valid keys (active + deprecated) failed verification.
     */
    record GracePeriodExhausted(
        long epoch,
        OptionalInt committeeIndex,
        int keysAttempted
    ) implements ValidationResult {}
}
```

**Rationale**:
- Sealed interface enables exhaustive pattern matching
- Distinct types allow callers to handle crypto vs structural failures differently
- Epoch and committee context aids debugging

### 3. Thread-Safety and Statelessness

The validator remains stateless:
- No instance fields added
- BLSOperations is stateless (static methods)
- KeyResolver implementations required to be thread-safe (documented contract)
- All methods are pure functions of their inputs

---

## Detailed Method Specifications

### Method 1: `verifyReceiptWithBLS`

```java
/**
 * Verify a complete RecursiveAggregateReceipt with BLS signatures.
 *
 * Pipeline:
 * 1. Structural validation (via existing verifyReceipt)
 * 2. Base HierarchicalAggregate BLS verification
 * 3. Epoch chain BLS verification (EpochLink.Changed entries)
 *
 * Complexity: O(M * log n) where M = epoch count, n = committee count
 *
 * @param receipt The receipt to verify
 * @param rootHashLookup Function to get computed root hash for epochs
 * @param keyResolver Resolver for committee public keys
 * @param message The signed message (event digest)
 * @return ValidationResult with detailed success/failure information
 */
public ValidationResult verifyReceiptWithBLS(
    RecursiveAggregateReceipt receipt,
    Function<Long, Digest> rootHashLookup,
    RecursiveKeyResolver keyResolver,
    byte[] message
);
```

### Method 2: `verifyPathWithBLS`

```java
/**
 * Verify a historical proof path with BLS signatures.
 *
 * Pipeline:
 * 1. Structural validation (via existing verifyPath)
 * 2. Path node signature verification (ProofPathNode.CommitteeNode)
 * 3. Epoch boundary validation
 *
 * Complexity: O(log n) for path length
 *
 * @param path The proof path to verify
 * @param keyResolver Resolver for committee public keys
 * @param message The signed message
 * @return ValidationResult
 */
public ValidationResult verifyPathWithBLS(
    HistoricalProofPath path,
    RecursiveKeyResolver keyResolver,
    byte[] message
);
```

### Method 3: `verifyEpochSequenceWithBLS`

```java
/**
 * Verify a sequence of epoch links with BLS signatures.
 *
 * Validates EpochLink.Changed entries have valid BLS aggregate signatures.
 * Unchanged epochs are validated structurally only (no signature).
 *
 * Complexity: O(M) where M = number of Changed epochs
 *
 * @param epochs List of epoch links in order
 * @param keyResolver Resolver for epoch signing keys
 * @param message The signed message
 * @return ValidationResult
 */
public ValidationResult verifyEpochSequenceWithBLS(
    List<EpochLink> epochs,
    RecursiveKeyResolver keyResolver,
    byte[] message
);
```

---

## Integration Points

### 1. BLSOperations Integration

Use existing `BLSOperations.verifyAggregate()` for cryptographic verification:

```java
// Example integration in verifyTreeNodeBLS
var verified = BLSOperations.verifyAggregate(
    committeeKeys,          // List<BLSPublicKey> from KeyResolver
    message,                // Signed data
    aggregate               // BLSAggregate from TreeNode
);
```

### 2. WitnessSignatureValidator Pattern (Grace Period)

Follow the dual-key verification pattern for key rotation support:

```java
// Try active keys first
var result = verifyWithKeys(activeKeys, message, aggregate);
if (result.isValid()) {
    return result;
}

// If grace period lookup available, try deprecated keys
var graceLookup = keyResolver.getGracePeriodLookup();
if (graceLookup.isPresent()) {
    var deprecatedKeys = graceLookup.get().getDeprecatedKeys(epoch, committee);
    result = verifyWithKeys(deprecatedKeys, message, aggregate);
    if (result.isValid()) {
        return result;  // Verified with deprecated key during grace period
    }
    return ValidationResult.gracePeriodExhausted(epoch, committee, keysAttempted);
}

return result;  // Return original failure
```

### 3. AggregateValidator Reuse

For batch verification of multiple receipts, delegate to existing `AggregateValidator`:

```java
// For batch scenarios (future optimization)
var aggregateValidator = new AggregateValidator(BLSProvider.getDefault());
var batchResults = aggregateValidator.validateBatch(committeeKeys, aggregates, messages);
```

---

## Complexity Analysis

| Method | Structural | BLS Verification | Total |
|--------|------------|------------------|-------|
| verifyReceiptWithBLS | O(M * log n) | O(M + log n) pairings | O(M * log n) |
| verifyPathWithBLS | O(log n) | O(log n) pairings | O(log n) |
| verifyEpochSequenceWithBLS | O(M) | O(C) pairings* | O(M) |

*C = number of Changed epochs (typically C << M)

**Performance Characteristics**:
- BLS pairing: ~1-2ms per verification
- Happy path (all valid): 1 root pairing per level
- Byzantine isolation: O(log n) additional pairings to locate bad committee

---

## Error Handling Strategy

### Structural Errors (Layer 1)
- Return early with original `ValidationResult.Invalid`
- No BLS verification attempted (saves expensive crypto operations)

### Cryptographic Errors (Layer 2)
- `SignatureVerificationFailed`: BLS math failed, signature invalid
- `KeyResolutionFailed`: KeyResolver returned empty/null keys
- `GracePeriodExhausted`: All valid keys tried, none succeeded

### Error Propagation
```java
// Example error flow
public ValidationResult verifyReceiptWithBLS(...) {
    // Layer 1: Structural
    var structuralResult = verifyReceipt(receipt, rootHashLookup);
    if (!structuralResult.isValid()) {
        return structuralResult;  // Propagate structural error
    }

    // Layer 2: BLS
    var blsResult = verifyHierarchicalAggregateBLS(receipt.baseAggregate(), keyResolver, message);
    if (!blsResult.isValid()) {
        return blsResult;  // Propagate crypto error
    }

    // Continue with epoch chain verification...
    return ValidationResult.valid();
}
```

---

## File Locations

### New Files

| File | Purpose |
|------|---------|
| `witness-service/src/main/java/.../recursive/RecursiveKeyResolver.java` | Key resolution interface |
| `witness-service/src/test/java/.../recursive/RecursiveProofValidatorBLSTest.java` | BLS verification tests |
| `witness-service/src/test/java/.../recursive/MockRecursiveKeyResolver.java` | Test fixture |

### Modified Files

| File | Changes |
|------|---------|
| `witness-service/src/main/java/.../recursive/RecursiveProofValidator.java` | Add BLS verification methods |
| `witness-service/src/main/java/.../recursive/ValidationResult.java` | Add BLS-specific result types |

---

## Implementation Tasks

### Task 1: Create RecursiveKeyResolver Interface
**Effort**: 0.5 day

1. Create `RecursiveKeyResolver.java` with documented contract
2. Create `GracePeriodKeyLookup` functional interface
3. Add Javadoc with thread-safety and performance expectations

### Task 2: Extend ValidationResult
**Effort**: 0.25 day

1. Add `SignatureVerificationFailed` record
2. Add `KeyResolutionFailed` record
3. Add `GracePeriodExhausted` record
4. Ensure sealed interface updated with permits clause

### Task 3: Implement BLS Verification Methods
**Effort**: 1.5 days

1. `verifyReceiptWithBLS` - full receipt verification
2. `verifyPathWithBLS` - selective path verification
3. `verifyEpochSequenceWithBLS` - epoch chain verification
4. `verifyHierarchicalAggregateBLS` - tree verification (internal)
5. `verifyTreeNodeBLS` - node verification (internal)
6. `verifyEpochLinkBLS` - epoch link verification (internal)

### Task 4: Implement Test Suite
**Effort**: 1.5 days

Test scenarios:
1. Happy path - valid receipt with valid signatures
2. Structural failure - invalid chain (no BLS attempted)
3. Signature failure - valid structure, bad signature
4. Key resolution failure - resolver returns empty
5. Grace period success - deprecated key works
6. Grace period exhaustion - all keys fail
7. Byzantine isolation - identify corrupt committee
8. Edge cases - empty chain, single epoch, null inputs
9. Performance - O(log n) scaling verification

### Task 5: Integration Verification
**Effort**: 0.25 day

1. Verify compilation with all tests passing
2. Run existing RecursiveProofValidatorTest (no regressions)
3. Run new BLS tests
4. Update bead status

---

## Test Strategy

### Unit Tests (RecursiveProofValidatorBLSTest.java)

```java
@DisplayName("RecursiveProofValidator BLS Integration")
class RecursiveProofValidatorBLSTest {

    private RecursiveProofValidator validator;
    private MockRecursiveKeyResolver keyResolver;

    @BeforeEach
    void setup() {
        validator = new RecursiveProofValidator();
        keyResolver = new MockRecursiveKeyResolver();
        // Setup test key pairs using BLSOperations.generateKeyPair()
    }

    @Test
    @DisplayName("should verify valid receipt with BLS signatures")
    void testVerifyReceiptWithBLS_ValidSignatures_Success() { ... }

    @Test
    @DisplayName("should fail on structural error without BLS check")
    void testVerifyReceiptWithBLS_StructuralFailure_NoBLSAttempted() { ... }

    @Test
    @DisplayName("should fail on invalid BLS signature")
    void testVerifyReceiptWithBLS_InvalidSignature_SignatureVerificationFailed() { ... }

    @Test
    @DisplayName("should succeed with deprecated key during grace period")
    void testVerifyReceiptWithBLS_GracePeriod_DeprecatedKeySucceeds() { ... }

    @Test
    @DisplayName("should isolate Byzantine committee in O(log n)")
    void testFindInvalidNodesBLS_ByzantineCommittee_LogNComplexity() { ... }
}
```

### MockRecursiveKeyResolver

```java
/**
 * Test fixture for RecursiveKeyResolver.
 * Allows configuration of keys per epoch/committee.
 */
class MockRecursiveKeyResolver implements RecursiveKeyResolver {

    private final Map<Long, List<List<BLSPublicKey>>> epochCommitteeKeys = new ConcurrentHashMap<>();
    private final Map<Long, List<BLSPublicKey>> epochSigningKeys = new ConcurrentHashMap<>();
    private GracePeriodKeyLookup gracePeriodLookup;

    public void setCommitteeKeys(long epoch, int committee, List<BLSPublicKey> keys) { ... }
    public void setEpochSigningKeys(long epoch, List<BLSPublicKey> keys) { ... }
    public void setGracePeriodLookup(GracePeriodKeyLookup lookup) { ... }

    // Interface implementations...
}
```

---

## Dependency Graph

```
Delos-3999 (ChainAggregator) ─────────────────┐
                                              │
Delos-4000 (EpochTransitionValidator) ────────┼──▶ Delos-4001 (This Task)
                                              │
                                              │         │
                                              │         ▼
                                              │    Delos-4002 (TemporalByzantineIsolator)
                                              │         │
                                              │         ▼
                                              └──▶ Delos-4004 (WitnessReceiptManager Integration)
```

---

## Risk Assessment

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| BLS pairing performance | Low | Medium | Use happy-path optimization (verify root only) |
| KeyResolver contract violations | Medium | High | Comprehensive Javadoc, defensive null checks |
| Grace period edge cases | Medium | Medium | Thorough test coverage, follow existing patterns |
| Existing test regressions | Low | High | Run full test suite before closing task |

---

## Acceptance Criteria

- [ ] `RecursiveKeyResolver` interface created with documented contract
- [ ] `ValidationResult` extended with BLS-specific types
- [ ] `verifyReceiptWithBLS` implemented and tested
- [ ] `verifyPathWithBLS` implemented and tested
- [ ] `verifyEpochSequenceWithBLS` implemented and tested
- [ ] Grace period support integrated
- [ ] All existing tests pass (no regressions)
- [ ] New test coverage for BLS scenarios
- [ ] O(log n) complexity verified
- [ ] Thread-safety maintained (stateless design)
- [ ] Code compiles including all tests

---

## Estimated Effort

| Task | Days |
|------|------|
| RecursiveKeyResolver interface | 0.5 |
| ValidationResult extensions | 0.25 |
| BLS verification methods | 1.5 |
| Test suite | 1.5 |
| Integration verification | 0.25 |
| **Total** | **4 days** |

---

## References

- Existing `RecursiveProofValidator`: `/witness-service/src/main/java/.../recursive/RecursiveProofValidator.java`
- BLS operations: `/cryptography/src/main/java/.../bls/BLSOperations.java`
- Aggregate validation pattern: `/witness-service/src/main/java/.../validation/AggregateValidator.java`
- Grace period pattern: `/witness-service/src/main/java/.../validation/WitnessSignatureValidator.java`
- Existing tests: `/witness-service/src/test/java/.../recursive/RecursiveProofValidatorTest.java`

---

*Plan created: 2026-01-24*
*Bead: Delos-4001*
*Context: .pm/CONTEXT_PROTOCOL.md*
