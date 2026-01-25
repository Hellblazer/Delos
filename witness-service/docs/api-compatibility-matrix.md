# API Compatibility Matrix

**Task**: Delos-4045 - API Verification Sprint
**Date**: 2026-01-25
**Status**: Complete

## Executive Summary

This document captures the findings from the Phase 1A API Verification Sprint, which assessed the compatibility of external APIs required for witness service integration with CHOAM, KERL/Stereotomy, and Fireflies.

### Key Finding

**CHOAM APIs exist but are encapsulated as PRIVATE fields.** Public accessor methods must be added before witness integration can proceed.

---

## 1. CHOAM API Verification

### API Status Matrix

| Required API | Method/Field | Visibility | Status | Location |
|-------------|--------------|------------|--------|----------|
| `getCurrentBlockHeight()` | `CHOAM.currentHeight()` | PUBLIC | ✅ AVAILABLE | CHOAM.java:210 |
| `getLatestCheckpoint()` | `CheckpointManager.lastCheckpoint()` | PRIVATE field | ❌ BLOCKED | CHOAM.java:83 |
| `getBlocksSince(height)` | `BlockStore.blocksFrom()` | PRIVATE field | ❌ BLOCKED | CHOAM.java:99 |
| `getCurrentBlockHash()` | `BlockStore.hash()` | PRIVATE field | ❌ BLOCKED | CHOAM.java:99 |

### Upstream Change Request

The following public accessor methods must be added to `CHOAM.java`:

```java
/**
 * Returns the BlockStore for recovery operations.
 * Required for witness service CHOAM block binding.
 */
public BlockStore getBlockStore() {
    return store;
}

/**
 * Returns the BlockProcessor for block processing operations.
 * Required for witness service log replay.
 */
public BlockProcessor getBlockProcessor() {
    return blockProcessor;
}

/**
 * Returns the CheckpointManager for checkpoint operations.
 * Required for witness service checkpoint recovery.
 */
public CheckpointManager getCheckpointManager() {
    return checkpointManager;
}
```

### Integration Pattern (Once Accessors Added)

```java
// Block height binding in WitnessCHOAM
public WitnessReceipt createReceipt(EventCoordinates coords, BLSSignature sig) {
    var blockHeight = choam.currentHeight();
    var blockHash = choam.getBlockStore().hash(blockHeight);

    return WitnessReceipt.newBuilder()
        .setEventCoordinates(coords)
        .setBlsSig(sig)
        .setChoamBlockHeight(blockHeight)
        .setChoamBlockHash(blockHash)
        .setReceiptVersion(1)
        .build();
}
```

---

## 2. KERL/Stereotomy API Verification

### Integration Options Evaluated

| Option | Component | Fault Tolerance | Complexity | Recommendation |
|--------|-----------|-----------------|------------|----------------|
| A | Stereotomy Direct | Single point of failure | Low | ❌ Not recommended |
| B | Thoth DHT | Byzantine fault tolerant | Medium | ✅ **RECOMMENDED** |

### Recommended Integration: Thoth DHT

**Rationale**: The witness service requires Byzantine fault tolerance for KeyState queries. Thoth DHT provides:
- L1 Cache: `CachingKERL` for local hot-path
- L2 Database: `UniKERLDirectPooled` for persistence
- L3 Network: DHT gossip for distributed availability

### Integration Pattern

```java
// In WitnessContext constructor
public WitnessContext(Thoth thoth, Context<Member> firefliesContext, ...) {
    this.keriVerifiers = thoth.getKerlDHT().getVerifiers();
    this.firefliesContext = firefliesContext;
    // ...
}

// KeyState verification before accepting signature
public boolean verifySignerKeyState(Identifier signer, byte[] signature, byte[] message) {
    var verifier = keriVerifiers.verifierFor(signer);
    if (verifier.isEmpty()) {
        log.warn("No KeyState found for signer: {}", signer);
        return false;
    }
    return verifier.get().verify(signature, message);
}
```

### Key Files

| File | Purpose |
|------|---------|
| `thoth/src/main/java/.../KerlDHT.java` | DHT-backed KERL implementation |
| `stereotomy/src/main/java/.../Verifiers.java` | Verifier interface |
| `stereotomy/src/main/java/.../Verifier.java` | Single identity verifier |

---

## 3. Fireflies API Verification

### API Status Matrix

| Required API | Method | Status | Thread-Safe | Deterministic |
|-------------|--------|--------|-------------|---------------|
| BFT Subset Selection | `context.bftSubset(hash)` | ✅ AVAILABLE | ✅ Yes | ✅ Yes |
| View Change Callbacks | `view.register(key, listener)` | ✅ AVAILABLE | ✅ Yes | N/A |
| Shunning Stream | `view.shunned()` | ✅ AVAILABLE | ✅ Yes | N/A |
| Member Lookup | `context.getMember(id)` | ✅ AVAILABLE | ✅ Yes | N/A |

### Current Integration (Working)

```java
// WitnessContext.java:144-162
public Set<Identifier> selectCommittee(EventCoordinates eventCoordinates) {
    var eventHash = hashEventCoordinates(eventCoordinates);
    var bftSubset = firefliesContext.bftSubset(eventHash);
    return bftSubset.stream()
        .map(Member::getId)
        .map(this::toIdentifier)
        .limit(parameters.k())
        .collect(Collectors.toSet());
}
```

### View Change Integration Pattern

```java
// Register for view change notifications
view.register("witness-service", viewChange -> {
    log.info("View changed: {} members, diadem: {}",
        viewChange.context().activeCount(),
        viewChange.diadem());

    // Re-evaluate pending receipts with new committee
    pendingReceipts.forEach(this::revalidateCommittee);
});
```

---

## 4. Proto Field Assignments

### Current Field Usage (witness.proto)

```protobuf
message WitnessReceipt {
  stereotomy.EventCoords eventCoordinates = 1;  // Used
  bytes identifier = 2;                          // Used
  bytes digest = 3;                              // Used
  bytes signature = 4;                           // Used
  repeated bytes witnesses = 5;                  // Used
  google.protobuf.Timestamp timestamp = 6;       // Used
  uint64 sequenceNumber = 7;                     // Used
  bytes previousEventDigest = 8;                 // Used
  repeated bytes signerIdentifiers = 9;          // Used
  repeated bytes signatures = 10;                // Used
  BLSAggregateSignature blsSig = 11;            // Used
  bytes signerBitmap = 12;                       // Used
  MultiCommitteeReceipt multiCommitteeReceipt = 13; // Used

  // NEW FIELDS (Phase 1C)
  uint64 choam_block_height = 14;               // ASSIGNED
  bytes choam_block_hash = 15;                  // ASSIGNED
  uint32 receipt_version = 16;                  // ASSIGNED
}
```

### Field Semantics

| Field | Type | Purpose |
|-------|------|---------|
| `choam_block_height` | uint64 | Block height at receipt creation for recovery ordering |
| `choam_block_hash` | bytes | Block hash (32 bytes) for integrity verification |
| `receipt_version` | uint32 | Wire format version for forward compatibility |

---

## 5. Error Handling Patterns

### CHOAM Errors

```java
// Block height mismatch during recovery
if (receipt.getChoamBlockHeight() > choam.currentHeight()) {
    throw new RecoveryException(
        "Receipt references future block: %d > %d".formatted(
            receipt.getChoamBlockHeight(), choam.currentHeight()));
}
```

### KERL Errors

```java
// KeyState not found
var verifier = keriVerifiers.verifierFor(signer);
if (verifier.isEmpty()) {
    metrics.incrementCounter("witness.kerl.keystate_not_found");
    return ValidationResult.KEYSTATE_UNAVAILABLE;
}

// Signature verification failure
if (!verifier.get().verify(signature, message)) {
    metrics.incrementCounter("witness.kerl.signature_invalid");
    return ValidationResult.SIGNATURE_INVALID;
}
```

### Fireflies Errors

```java
// Insufficient committee members
var committee = context.bftSubset(hash);
if (committee.size() < parameters.minimumQuorum()) {
    throw new InsufficientQuorumException(
        "Committee size %d < minimum %d".formatted(
            committee.size(), parameters.minimumQuorum()));
}
```

---

## 6. Dependency Impact

### Updated Dependency Chain

```
Delos-4045 (API Verification) ✅ COMPLETE
    ↓
Delos-4043 (CHOAM Recovery) - BLOCKED until public accessors added
    ↓
Delos-4040 (KERI Identity)
    ↓
Delos-4041 (Byzantine Quorum) + Delos-4042 (Timestamps)
    ↓
Delos-4044 (Production Hardening)
```

### Blocking Issues

1. **CHOAM Public Accessors**: Must be added before Delos-4043 can proceed
2. **Thoth Integration**: WitnessCHOAM constructor currently passes `null` for CHOAM instance

---

## 7. Next Steps

1. **Immediate**: Create PR to add public accessors to CHOAM.java
2. **Delos-4043**: Integrate CHOAM block binding once accessors available
3. **Delos-4040**: Integrate Thoth DHT for KERL verification
4. **Delos-4041/4042**: Runtime quorum enforcement and timestamp attestation

---

## Appendix: File References

| Component | Key Files |
|-----------|-----------|
| CHOAM | `choam/src/main/java/.../CHOAM.java` |
| BlockStore | `choam/src/main/java/.../BlockStore.java` |
| CheckpointManager | `choam/src/main/java/.../CheckpointManager.java` |
| Thoth DHT | `thoth/src/main/java/.../KerlDHT.java` |
| Verifiers | `stereotomy/src/main/java/.../Verifiers.java` |
| Fireflies Context | `fireflies/src/main/java/.../Context.java` |
| Witness Context | `witness-service/src/main/java/.../WitnessContext.java` |
| Proto Definition | `grpc/src/main/proto/witness.proto` |
