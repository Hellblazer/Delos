# KERL Integration Decision

**Task**: Delos-4045 - API Verification Sprint
**Date**: 2026-01-25
**Status**: Decision Recorded

## Decision

**Use Thoth DHT (`KerlDHT`) for KERI KeyState verification in the witness service.**

## Context

The witness service must verify KERI KeyState for signers before accepting their signatures into witness receipts. Two integration options were evaluated:

| Option | Component | Description |
|--------|-----------|-------------|
| A | Stereotomy Direct | Direct KERL access via local Stereotomy instance |
| B | Thoth DHT | Distributed KeyState via gossip-based DHT |

## Evaluation

### Option A: Stereotomy Direct

**Architecture**:
```
WitnessService → Stereotomy → Local KERL Database
```

**Pros**:
- Simple integration
- Low latency for local queries
- Minimal dependencies

**Cons**:
- Single point of failure
- No Byzantine fault tolerance
- Stale data if local KERL not synchronized
- Not suitable for multi-node witness deployments

**Verdict**: ❌ Rejected - insufficient fault tolerance for production

### Option B: Thoth DHT (Recommended)

**Architecture**:
```
WitnessService → Thoth → KerlDHT → Fireflies Gossip
                            ↓
                    L1: CachingKERL (hot path)
                    L2: UniKERLDirectPooled (persistence)
                    L3: DHT Network (distributed)
```

**Pros**:
- Byzantine fault tolerant (survives f Byzantine nodes in 3f+1 network)
- Gossip-based synchronization
- Multi-layer caching for performance
- Production-grade implementation
- Already integrated with Fireflies overlay

**Cons**:
- Higher complexity
- Network latency for cache misses
- Requires Thoth bootstrap

**Verdict**: ✅ Selected - meets BFT requirements

## Integration Design

### Component Dependencies

```java
// WitnessCHOAM.java constructor
public WitnessCHOAM(
    CHOAM choam,
    Thoth thoth,                    // NEW: KERL integration
    Context<Member> context,
    WitnessParameters parameters
) {
    this.choam = choam;
    this.keriVerifiers = thoth.getKerlDHT().getVerifiers();
    this.context = context;
    this.parameters = parameters;
}
```

### Verification Flow

```java
public ValidationResult verifySignerKeyState(Identifier signer, JohnHancock signature) {
    // 1. Get verifier for signer identity
    var verifierOpt = keriVerifiers.verifierFor(signer);
    if (verifierOpt.isEmpty()) {
        log.warn("KeyState not found for signer: {}", signer);
        metrics.counter("witness.kerl.keystate_not_found").increment();
        return ValidationResult.KEYSTATE_UNAVAILABLE;
    }

    // 2. Verify signature against current KeyState
    var verifier = verifierOpt.get();
    try {
        if (!verifier.verify(signature)) {
            log.warn("Signature verification failed for: {}", signer);
            metrics.counter("witness.kerl.signature_invalid").increment();
            return ValidationResult.SIGNATURE_INVALID;
        }
    } catch (Exception e) {
        log.error("Verification error for {}: {}", signer, e.getMessage());
        return ValidationResult.VERIFICATION_ERROR;
    }

    return ValidationResult.VALID;
}
```

### Caching Strategy

```java
// L1 Cache: In-memory for hot signers
private final LoadingCache<Identifier, Optional<Verifier>> verifierCache =
    Caffeine.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(Duration.ofMinutes(5))
        .build(this::loadVerifier);

private Optional<Verifier> loadVerifier(Identifier id) {
    return keriVerifiers.verifierFor(id);
}
```

## Error Handling

### KeyState Not Found

```java
// Retry with exponential backoff for transient unavailability
public ValidationResult verifyWithRetry(Identifier signer, JohnHancock sig) {
    return Retry.of("kerl-verify", RetryConfig.custom()
            .maxAttempts(3)
            .waitDuration(Duration.ofMillis(100))
            .retryExceptions(KeyStateUnavailableException.class)
            .build())
        .executeSupplier(() -> verifySignerKeyState(signer, sig));
}
```

### Key Rotation Handling

```java
// Handle key rotation during verification window
public ValidationResult verifyWithRotation(Identifier signer, JohnHancock sig) {
    var result = verifySignerKeyState(signer, sig);

    if (result == ValidationResult.SIGNATURE_INVALID) {
        // Clear cache and retry - may be stale after rotation
        verifierCache.invalidate(signer);
        result = verifySignerKeyState(signer, sig);
    }

    return result;
}
```

## Performance Considerations

### Expected Latencies

| Operation | L1 Hit | L2 Hit | L3 (Network) |
|-----------|--------|--------|--------------|
| KeyState lookup | <1ms | 2-5ms | 10-50ms |
| Signature verify | 1-2ms | 1-2ms | 1-2ms |

### Optimization Strategies

1. **Pre-warm cache**: Load committee KeyStates on view change
2. **Batch verification**: Group signatures by signer for single lookup
3. **Async refresh**: Background thread refreshes expiring entries

```java
// Pre-warm on view change
view.register("kerl-cache", viewChange -> {
    var committee = context.bftSubset(currentEventHash);
    committee.forEach(member ->
        verifierCache.get(toIdentifier(member.getId())));
});
```

## Migration Path

### Phase 1: Add Thoth Dependency
```java
// Update WitnessCHOAM constructor signature
- public WitnessCHOAM(CHOAM choam, Context<Member> context, ...)
+ public WitnessCHOAM(CHOAM choam, Thoth thoth, Context<Member> context, ...)
```

### Phase 2: Implement Verification
```java
// Add verification before signature acceptance
if (!verifySignerKeyState(signer, signature).isValid()) {
    return WitnessResult.rejected("KeyState verification failed");
}
```

### Phase 3: Add Metrics
```java
// Prometheus metrics for monitoring
Counter keystateHits = Counter.build()
    .name("witness_kerl_keystate_hits")
    .help("KeyState cache hits")
    .register();
```

## Testing Strategy

### Unit Tests
- Mock Thoth/KerlDHT for isolated testing
- Test cache behavior (hits, misses, expiration)
- Test error handling paths

### Integration Tests
- Real Thoth instance with test KERL
- Key rotation scenarios
- Network partition behavior

### Chaos Tests
- DHT node failures during verification
- High-latency L3 lookups
- Cache stampede scenarios

## Files to Modify

| File | Change |
|------|--------|
| `WitnessCHOAM.java` | Add Thoth parameter, implement verification |
| `WitnessContext.java` | Add verifier cache |
| `WitnessParameters.java` | Add KERL verification config |
| `WitnessCHOAMTest.java` | Add verification tests |

## References

- **KerlDHT Implementation**: `thoth/src/main/java/.../KerlDHT.java`
- **Verifiers Interface**: `stereotomy/src/main/java/.../Verifiers.java`
- **KERI Specification**: https://keri.one/
- **Delos-4040**: KERI Identity Verification (implementation bead)
