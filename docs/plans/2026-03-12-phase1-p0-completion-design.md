# Phase 1 P0 Completion Design

**Beads**: Delos-izm.1.3 (Nonce Verification), Delos-izm.1.5 (RecursiveProofValidator)
**RDR**: RDR-001 (accepted)
**Date**: 2026-03-12

## 1.3 — Nonce Verification Implementation

### Problem

KerlDHT uses timestamp-based freshness validation (Phase 2). A Byzantine node can replay captured DHT requests within the operationTimeout window. The TODO at KerlDHT.java:327 calls for cryptographic nonce verification.

### Design

**Interface-based**, configurable via feature flag, starting with in-memory implementation.

#### NonceVerifier Interface

```java
public interface NonceVerifier {
    /** Generate a unique nonce for a request. */
    String generateNonce();

    /** Record a nonce as used. Returns false if already seen (replay). */
    boolean recordAndVerify(String nonce, Digest memberId);

    /** Shutdown and release resources. */
    void close();
}
```

#### InMemoryNonceVerifier (default)

- `ConcurrentHashMap<String, Long>` mapping nonce → expiry timestamp
- Capacity bounded (configurable, default 100_000)
- Scheduled eviction task removes expired entries at `operationTimeout` intervals
- TTL matches `operationTimeout` from KerlDHT configuration
- Thread-safe, no external dependencies

#### PersistentNonceVerifier (future, behind NONCE_PERSISTENCE flag)

- MVStore-backed (dependency already available via CHOAM)
- Survives restarts — closes cross-restart replay window
- Same interface, selected by feature flag
- Deferred until Delos-g4sh migration tooling is complete

#### Integration Points

1. `RequestContext` gains a `nonce` field (generated via `NonceVerifier.generateNonce()`)
2. Nonce included in DHT request metadata (piggybacks on existing request ID infrastructure)
3. `validateResponseFreshness` enhanced: checks timestamp freshness AND nonce uniqueness
4. Duplicate nonce from same member → reject + record Byzantine signal
5. KerlDHT constructor accepts `NonceVerifier`; factory selects implementation based on feature flag

### Files to Modify

- `KerlDHT.java` — add NonceVerifier field, update RequestContext, enhance validateResponseFreshness
- New: `NonceVerifier.java` interface
- New: `InMemoryNonceVerifier.java` implementation
- New: `NonceVerifierTest.java` — replay detection, TTL expiry, capacity bounds
- New: `KerlDHTNonceVerificationTest.java` — integration test for replay rejection

## 1.5 — RecursiveProofValidator Completion

### Problem

Six TODOs in RecursiveProofValidator leave critical verification paths as no-ops:
- `constructEpochMessage` returns `new byte[32]` (zero array)
- Leaf/intermediate node message construction uses `new byte[32]` placeholders
- `verifyIntermediateNodeBLS` returns `ValidationResult.valid()` unconditionally

### Design

#### constructEpochMessage (line 1006)

Canonical serialization: `DigestAlgorithm.DEFAULT.digest(epochNumber_8bytes_BE || previousRootHash.getBytes())`

```java
private byte[] constructEpochMessage(long epochNumber, Digest previousRootHash) {
    var buffer = ByteBuffer.allocate(8 + previousRootHash.getBytes().length);
    buffer.putLong(epochNumber);
    buffer.put(previousRootHash.getBytes());
    return DigestAlgorithm.DEFAULT.digest(buffer.array()).getBytes();
}
```

#### Leaf node message (line 753)

Replace `new byte[32]` with epoch message derived from the leaf's `committeeEpoch` and root hash lookup. The message that was signed by the committee is the epoch message for that committee's epoch.

#### Intermediate node message (lines 818-820)

Replace `new byte[32]` with hash of concatenated child signatures/hashes. For `TreeNode.IntermediateNode`: hash the ordered child aggregated signatures. For `ProofPathNode.IntermediateAggregation`: hash the `childHashes` list.

```java
// Intermediate message = Hash(child_hash_1 || child_hash_2 || ... || child_hash_n)
var childData = ByteBuffer.allocate(intermediate.children().size() * 32);
for (var child : intermediate.children()) {
    childData.put(extractNodeHash(child));
}
return DigestAlgorithm.DEFAULT.digest(childData.array()).getBytes();
```

#### verifyIntermediateNodeBLS (line 992)

Replace unconditional `ValidationResult.valid()` with actual BLS aggregate verification:
1. Get parent-level keys via `keyResolver.getCommitteeKeys()`
2. Construct message from child hashes
3. Build `BLSAggregate` from the intermediate node's signature
4. Call `BLSOperations.verifyAggregate(keys, message, aggregate)`
5. On failure, return appropriate `ValidationResult` subtype

#### committeeEpoch encoding (line 721)

Document current encoding (committeeEpoch as epoch, leaf.index() as committee index). This is functional — the TODO is about defining a formal spec, not a bug.

### Files to Modify

- `RecursiveProofValidator.java` — implement all 6 TODOs
- New: `RecursiveProofValidatorSecurityTest.java` — forged node detection, valid node acceptance
- Existing tests updated to reflect real verification behavior

## Risks

| Risk | Mitigation |
|------|------------|
| Nonce overhead on hot path | InMemoryNonceVerifier is O(1) lookup; eviction is async |
| Breaking existing RecursiveProofValidator tests | Tests that relied on placeholder behavior will need updating |
| BLS verification performance | Already benchmarked in witness-service; no new hot paths |

## Success Criteria

- [ ] Replayed DHT request with duplicate nonce → rejected
- [ ] Fresh DHT request with unique nonce → accepted
- [ ] Nonce TTL expiry works correctly
- [ ] Forged intermediate proof node → detected and rejected
- [ ] Valid proof tree → accepted
- [ ] constructEpochMessage is deterministic (same inputs → same output)
- [ ] All existing thoth and witness-service tests pass
