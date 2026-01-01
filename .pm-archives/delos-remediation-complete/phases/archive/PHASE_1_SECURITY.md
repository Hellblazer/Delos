# Phase 1: Security Concerns

## Phase Overview

**Status**: NOT STARTED
**Priority**: P1 - HIGH
**Target Duration**: Week 2
**Dependencies**: Phase 0 complete

---

## Objectives

1. Address non-cryptographic hash in security context
2. Fix HexBloom crown XOR vulnerability
3. Document Ed25519-X25519 key conversion security model

---

## Task 1: Cryptographic Hash for BloomFilter

### Description

MurmurHash3 used in BloomFilter is not cryptographically secure. An adversary could craft inputs that cause collisions, potentially affecting HexBloom View ID integrity.

### Location

**File**: `cryptography/src/main/java/com/hellblazer/delos/bloomFilters/Hash.java`

### Current Implementation

Uses MurmurHash3 which is:
- Fast but not collision-resistant
- Vulnerable to chosen-input attacks
- Inappropriate for security-critical membership validation

### Required Implementation

Add option for cryptographic hash (HMAC-BLAKE2b) for security-critical paths:

```java
public interface HashFunction {
    long hash(byte[] data);
}

public class CryptographicBloomHash implements HashFunction {
    private final Mac hmac;

    public CryptographicBloomHash(byte[] key) {
        // Initialize HMAC-BLAKE2b
    }

    @Override
    public long hash(byte[] data) {
        // Return cryptographic hash
    }
}
```

### Impact

- Membership validation can be spoofed without fix
- Potential for Byzantine actors to manipulate view membership
- Security guarantees depend on hash collision resistance

### Test Strategy

1. **Document threat model**:
   - Attack scenarios requiring collision resistance
   - Performance impact of cryptographic hash

2. **Write tests for both hash types**:
   ```java
   @Test
   void shouldSupportCryptographicHash() {
       var filter = new BloomFilter(HashType.CRYPTOGRAPHIC, key);
       // Test functionality
   }
   ```

3. **Benchmark performance**:
   - Compare MurmurHash3 vs HMAC-BLAKE2b
   - Document acceptable use cases for each

### Paper Reference

Search mixedbread: `"HexBloom bloom filter security"`

### ChromaDB Reference

- `crossref::cryptography::implementation`

### Bead

- ID: TBD
- Type: security
- Priority: 2 (high)

---

## Task 2: HexBloom Context Binding

### Description

Direct XOR of crowns without domain separator allows potential cross-context replay attacks. View IDs from one context could be replayed in another.

### Location

**File**: `cryptography/src/main/java/com/hellblazer/delos/cryptography/HexBloom.java`

### Current Implementation

```java
// Direct XOR of crowns
result = crown1 XOR crown2 XOR ... XOR crownN
```

### Required Implementation

Add context binding to prevent cross-context replay:

```java
// Context-bound XOR
contextHash = H(context_id || ring_index)
result = (crown1 XOR crown2 XOR ... XOR crownN) XOR contextHash
```

### Impact

- Without context binding, replay attacks possible
- Membership confusion across contexts
- Byzantine tolerance assumptions weakened

### Test Strategy

1. **Write test demonstrating vulnerability**:
   ```java
   @Test
   void shouldNotAcceptCrossContextReplay() {
       var bloomA = createBloom(contextA);
       var bloomB = createBloom(contextB);

       // Views should have different IDs even with same members
       assertNotEquals(bloomA.getViewId(), bloomB.getViewId());
   }
   ```

2. **Implement context binding**

3. **Verify backward compatibility** (migration strategy)

### Paper Reference

Search mixedbread: `"HexBloom view identifier"`

### ChromaDB Reference

- `crossref::cryptography::implementation`

### Bead

- ID: TBD
- Type: security
- Priority: 2 (high)

---

## Task 3: Ed25519-X25519 Key Conversion Documentation

### Description

The codebase converts Ed25519 signing keys to X25519 encryption keys. This is cryptographically valid but requires documented threat model.

### Location

**File**: `cryptography/src/main/java/com/hellblazer/delos/cryptography/SignatureAlgorithm.java`

### Current State

- Conversion exists and works correctly
- No documentation of security assumptions
- No threat model documented

### Required Documentation

Document:
1. Why key conversion is used (efficiency, key management simplicity)
2. Security assumptions:
   - Same randomness not reused for signing and encryption
   - Key compromise affects both capabilities
3. Alternative approaches considered:
   - Separate keys via HKDF
   - Completely independent key pairs

### Template

```markdown
## Ed25519-X25519 Key Conversion Security Model

### Purpose
[Why we convert keys]

### Security Assumptions
1. [Assumption 1]
2. [Assumption 2]

### Threat Model
- Protected against: [threats]
- Not protected against: [threats]

### Alternatives Considered
- [Alternative 1]: [why not chosen]
- [Alternative 2]: [why not chosen]

### References
- [Paper or RFC]
```

### Paper Reference

Search mixedbread: `"Ed25519 X25519 key conversion"`

### Bead

- ID: TBD
- Type: documentation
- Priority: 3 (medium)

---

## Definition of Done

### Per Task

- [ ] Threat model documented
- [ ] Implementation complete (if applicable)
- [ ] Security review completed
- [ ] No new vulnerabilities introduced
- [ ] Tests verify security properties
- [ ] Bead closed

### Phase Complete

- [ ] All 3 tasks complete
- [ ] Full test suite passes
- [ ] Security documentation updated
- [ ] EXECUTION_STATE.md updated
- [ ] Ready for Phase 2

---

## Estimated Effort

| Task | Research | Design | Implementation | Review | Total |
|------|----------|--------|----------------|--------|-------|
| Crypto hash | 2h | 2h | 4h | 2h | 10h |
| Context binding | 1h | 2h | 3h | 2h | 8h |
| Ed25519 docs | 2h | 1h | 2h | 1h | 6h |
| **Total** | **5h** | **5h** | **9h** | **5h** | **24h** |

---

## Dependencies

### Blocking

This phase blocked until Phase 0 complete.

### Blocks

- Phase 2 (API Completion) - can proceed in parallel
- Phase 3 (Documentation) - security docs needed

### Required Resources

- java-architect-planner for security design
- java-developer for implementation
- code-review-expert for security review
- Cryptography domain expertise

---

## Risk Summary

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Breaking change to HexBloom | MEDIUM | HIGH | Migration strategy |
| Performance regression | MEDIUM | MEDIUM | Benchmark and document |
| Incomplete threat model | LOW | MEDIUM | Peer review |

---

*Last Updated: 2025-12-30*
