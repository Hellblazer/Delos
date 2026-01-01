# Certificate Revocation in Stereotomy

## Overview

Stereotomy now supports explicit certificate/key revocation checking beyond the normal KERI key rotation mechanism. While key rotation supersedes old keys for new operations, revocation marks specific key states as compromised and prevents their use for verification or signing.

## Components

### KeyRevocationRegistry
Interface for tracking revoked key states:
- `boolean isRevoked(EventCoordinates)` - Check if a key state is revoked
- `void revoke(EventCoordinates)` - Mark a key state as revoked
- `void unrevoke(EventCoordinates)` - Remove a revocation (for testing/recovery)

### MemKeyRevocationRegistry
Thread-safe in-memory implementation using `ConcurrentHashMap.newKeySet()`.

### KeyRevokedException
Runtime exception thrown when attempting to use a revoked key.

### RevocationAwareVerifier
Verifier wrapper that checks revocation status before delegating to the underlying verifier.
Requires a `KeyStateProvider` to look up key states by sequence number.

## Usage

### Basic Revocation

```java
var revocationRegistry = new MemKeyRevocationRegistry();
var identifier = stereotomy.newIdentifier();

// Mark current key state as revoked
identifier.revoke(revocationRegistry);

// Check if revoked
if (revocationRegistry.isRevoked(identifier.getLastEstablishmentEvent())) {
    throw new KeyRevokedException(identifier.getLastEstablishmentEvent());
}
```

### Revocation-Aware Verification

```java
var baseVerifier = identifier.getVerifier().orElseThrow();
var revAwareVerifier = new RevocationAwareVerifier(
    baseVerifier,
    revocationRegistry,
    seqNum -> kerl.getKeyState(identifier.getIdentifier(), seqNum)
);

// Signatures from revoked keys will fail verification
boolean valid = revAwareVerifier.verify(signature, message);
```

### Application-Level Revocation Checking

Applications should check revocation before signing:

```java
if (revocationRegistry.isRevoked(identifier.getLastEstablishmentEvent())) {
    throw new KeyRevokedException(identifier.getLastEstablishmentEvent(),
                                   "Cannot sign with revoked key");
}
var signature = identifier.getSigner().sign(message);
```

## Integration Points

### BoundIdentifier
Added `getVerifier(KeyRevocationRegistry)` method that returns empty Optional if the key is revoked.

### ControlledIdentifier
Added `revoke(KeyRevocationRegistry)` default method to mark the current key state as revoked.

### KeyStateVerifier
Added `verifierFor(ULong sequenceNumber, KeyRevocationRegistry)` protected method for revocation-aware verification.

## Design Decisions

1. **Explicit vs Implicit**: Revocation checking is explicit, not automatic. Applications must opt-in by using RevocationAwareVerifier or checking the registry before operations.

2. **Separation from Rotation**: Revocation is distinct from key rotation. Rotation supersedes keys; revocation marks them as compromised.

3. **Thread Safety**: MemKeyRevocationRegistry uses concurrent collections, avoiding synchronized blocks per project standards.

4. **Verification Semantics**: By default, signatures from rotated keys remain valid (they were valid at that sequence number). Revoked keys fail verification even for historical signatures.

5. **Storage**: This implementation provides an in-memory registry. Production systems should implement KeyRevocationRegistry backed by persistent storage (database, distributed cache, etc.).

## Testing

See `KeyRevocationTest` for comprehensive test coverage:
- Revoked keys return empty verifier
- Revocation checking during signing
- Rotation vs revocation semantics
- Normal operations unaffected

## Future Enhancements

- Persistent KeyRevocationRegistry implementations
- Revocation events in KERL
- CRL/OCSP-style revocation checking
- Time-based revocation (revoke from timestamp forward)
- Batch revocation operations
