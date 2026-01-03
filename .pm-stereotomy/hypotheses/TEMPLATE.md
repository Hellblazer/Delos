# Hypothesis Template - Security Fix Design

**Copy this file for each fix design decision**

---

# Hypothesis: [Fix Description]

**Date Proposed**: YYYY-MM-DD
**Issue**: CRIT-X: [Title]
**Status**: [PROPOSED/VALIDATED/IMPLEMENTED/ABANDONED]
**Agent**: [Name] (or Manual)

## The Problem

[Clear statement of the vulnerability]

Example:
> KeyConfigurationDigester uses XOR accumulation to compute key digest. XOR is commutative, so different orderings of the same keys produce identical digests. This violates KERI's key ordering invariant and allows attackers to bypass multisig thresholds by rotating to keys in unintended order.

## Root Cause Analysis

[Trace of how vulnerability was introduced]

Example:
> 1. Developer wanted to compute digest of multiple keys
> 2. Used XOR because it's associative and appears order-independent
> 3. Didn't realize XOR is commutative (a XOR b = b XOR a)
> 4. Result: [k1, k2, k3] XOR = [k3, k2, k1] XOR (same digest)
> 5. This breaks KERI requirement: key order must matter for multisig policies

## Proposed Solution

[Clear description of fix approach]

Example:
> Replace XOR accumulation with order-preserving sequential hash:
> - Use DigestAlgorithm.DEFAULT.getHasher()
> - Sequentially call hasher.update() for each key's hash
> - Return hasher.digest()
> - This ensures [k1, k2, k3] != [k3, k2, k1] (order-dependent)

## Implementation Plan

### Step 1: Code Changes
[Specific code modifications required]

Example:
- **File**: KeyConfigurationDigester.java
- **Method**: `digest(List<PublicKey> keys)`
- **Change**: Replace lines 32-37
  - Remove: XOR accumulation loop
  - Add: Sequential hasher.update() calls
  - Return: hasher.digest() instead of accumulator

### Step 2: Test Changes
[New tests to validate fix]

Example:
- **Test Class**: SecurityTests.java
- **Test Method**: `testCRIT1_XORCommutativityAttack`
- **Validates**:
  - Different key orders produce different digests
  - Original attack scenario now fails
  - Backward compatibility with existing keys

### Step 3: Verification
[How to verify fix works]

Example:
- Run: `mvn test -Dtest=SecurityTests::testCRIT1* -pl stereotomy`
- Expected: Test PASS
- Verify: Attack scenario blocked

### Step 4: Integration
[Integration with rest of system]

Example:
- No API changes (method signature stays same)
- Return value changes (different digest), but old keys were never valid
- No cascading failures expected (digest is internal to stereotomy)

## Alternatives Considered

### Alternative 1: [Name]
[Why considered and rejected]

Example:
- **Name**: Use HMAC instead of hash
- **Rationale**: HMAC provides authentication in addition to digest
- **Why Rejected**: Overkill for this use case; added complexity and key management burden
- **Trade-off**: Simpler solution (plain hash) sufficient for key ordering guarantee

### Alternative 2: [Name]
[Why considered and rejected]

Example:
- **Name**: Merkle tree of keys
- **Rationale**: Merkle trees guarantee order and are well-tested
- **Why Rejected**: Performance overhead (~20% slowdown for typical 3-5 key rotations)
- **Trade-off**: Sequential hash simpler and faster for small key sets

### Alternative 3: [Name]
[Why considered and rejected]

Example:
- **Name**: Keep XOR, document order requirement
- **Rationale**: XOR is faster, just document that order must be maintained
- **Why Rejected**: Documentation easily ignored; better to make properties cryptographic
- **Trade-off**: Slightly worse performance (~5%) vs cryptographic guarantee

## Security Analysis

### Vulnerability Blocked
[Describe how fix blocks the attack]

Example:
- **Attack**: Rotate keys [k1, k2, k3] to different order [k3, k2, k1]
- **Old Code**: Both produce same digest (XOR is commutative)
- **New Code**: Different digests (order-dependent hash)
- **Result**: Attack blocked - attempted rotation to [k3, k2, k1] produces wrong digest

### No New Vulnerabilities Introduced
[Analyze for new attack vectors]

Example:
- **Preimage Attack**: SHA-256 preimage resistance holds (no vulnerability)
- **Collision Attack**: SHA-256 collision resistance holds (no vulnerability)
- **Order Bypass**: Sequential hashing enforces order (secure)
- **Key Exposure**: No new paths to key exposure (digest only, not raw keys)

### Cryptographic Properties Preserved
[Verify sound cryptography]

Example:
- Replacement function is order-preserving: [k1, k2, k3] != [k3, k2, k1]
- Based on SHA-256: well-tested, cryptographically sound
- Non-reversible: Can't derive keys from digest
- Deterministic: Same keys always produce same digest

## Backward Compatibility

### Compatibility Assessment
[Will existing deployments still work?]

Example:
- **Old Keystores**: Keys stored with old digests will be invalid
- **Migration**: Not required (old format was insecure anyway)
- **New Rotations**: Work with new digest format
- **Rollback Risk**: LOW (old format never deployed to production)

### Migration Path (if needed)
[How to migrate from old format]

Example:
1. Identify all keys created with old XOR digest
2. Mark as "legacy" (for forensic purposes)
3. Force rotation to new format (administrators must approve)
4. Validate new format works
5. Archive legacy format (no longer accepted)

### Deployment Strategy
[How to deploy without downtime]

Example:
1. Deploy new code with both old and new digest support
2. New rotations use new format
3. Validation accepts either format temporarily
4. Monitor for any issues with old format acceptance
5. Remove old format support in future release (after all nodes upgraded)

## Design Decisions

### Why Sequential Hash Over XOR?
[Fundamental design choice]

Example:
- **Decision**: Use sequential hash instead of XOR
- **Rationale**: Order matters for KERI multisig; sequential hash preserves order
- **Implications**: Slightly slower (~5%), cryptographically guaranteed
- **Assumption**: SHA-256 provides sufficient order-dependency

### Why DigestAlgorithm.DEFAULT?
[Component choice]

Example:
- **Decision**: Use DigestAlgorithm.DEFAULT.getHasher()
- **Rationale**: Consistent with rest of codebase; allows algorithm migration if needed
- **Implications**: Can change hash algorithm in future without code changes
- **Assumption**: DEFAULT algorithm is suitable for key digests

## Testing Strategy

### Unit Tests
[Tests to validate fix]

Example:
```java
@Test
void testCRIT1_XORCommutativityAttack() {
    // Arrange: Two key sets with same keys in different order
    var keys1 = List.of(key1, key2, key3);
    var keys2 = List.of(key3, key2, key1);

    // Act: Compute digests
    var digest1 = KeyConfigurationDigester.digest(keys1);
    var digest2 = KeyConfigurationDigester.digest(keys2);

    // Assert: Different order must produce different digest
    assertNotEquals(digest1, digest2,
        "Order-preserving hash required: [k1,k2,k3] != [k3,k2,k1]");
}

@Test
void testConsistency() {
    // Same keys in same order always produce same digest
    var keys = List.of(key1, key2, key3);
    var digest1 = KeyConfigurationDigester.digest(keys);
    var digest2 = KeyConfigurationDigester.digest(keys);
    assertEquals(digest1, digest2, "Digest must be deterministic");
}
```

### Integration Tests
[Tests to verify system-wide correctness]

Example:
- Test rotation with new digest format
- Test multisig validation with key ordering
- Test cross-module interaction (KeyEventProcessor, KeyStateProcessor)

### Regression Tests
[Ensure no breaking changes]

Example:
- All existing KeyConfigurationDigester tests pass
- All existing KERL tests pass
- Performance acceptable (±10% baseline)

### Stress Tests
[Load and safety testing]

Example:
- 1000 key rotations with various orders
- Concurrent rotations from multiple threads
- Verify no digest collisions under load

## Risk Assessment

### Implementation Risk
[Difficulty of implementation]

Example:
- **Risk Level**: LOW
- **Reason**: Simple change to digest calculation
- **Mitigation**: Comprehensive test coverage validates correctness
- **Rollback**: Straightforward (revert to XOR if needed)

### Security Risk
[Potential security downsides]

Example:
- **Risk Level**: LOW
- **Reason**: Fix addresses root vulnerability, adds no new attack vectors
- **Mitigation**: Cryptographic property analysis confirms soundness
- **Monitoring**: Monitor for any unusual key rotation patterns

### Performance Risk
[Potential performance impact]

Example:
- **Risk Level**: LOW (~5% slower)
- **Reason**: Sequential hashing slightly slower than XOR
- **Mitigation**: Benchmarking shows acceptable performance
- **Threshold**: Will alert if performance degrades >10%

### Compatibility Risk
[Potential deployment issues]

Example:
- **Risk Level**: LOW (new format only for new rotations)
- **Reason**: Old insecure format was never deployed
- **Mitigation**: Clear upgrade path documented
- **Contingency**: Can accept both formats temporarily if needed

## Success Criteria

### Fix Validation
[How to prove fix works]

Example:
- [ ] CRIT-1 test passes (was failing, now passes)
- [ ] Attack scenario blocked (demonstrated in test)
- [ ] Different key orders produce different digests
- [ ] Same keys in same order produce same digest (deterministic)

### System Validation
[Proof fix doesn't break system]

Example:
- [ ] Regression suite passes (all existing tests)
- [ ] Performance acceptable (±10% baseline)
- [ ] No new memory leaks
- [ ] Stress test passes (1000+ rotations)

### Code Quality
[Quality requirements]

Example:
- [ ] Code review approved
- [ ] Follows Java 24 style guide
- [ ] Comments explain security fix
- [ ] No deprecated APIs used

## Metrics & Measurements

### Before Fix (Baseline)
Record these metrics before implementation:

Example:
- Test execution time: 1.2 seconds
- Memory usage: 156 MB
- Regression suite: 100% pass (123 tests)
- Performance: baseline

### After Fix
Compare these metrics after implementation:

Example:
- Test execution time: 1.14 seconds (-5%)
- Memory usage: 154 MB (-1%)
- Regression suite: 100% pass (123 tests)
- Performance: -5% acceptable

## Open Questions

[Issues to resolve during implementation]

Example:
- [ ] Should old digest format still be accepted for backward compatibility?
- [ ] How long to support both old and new formats?
- [ ] Should we add metrics to track old vs new format usage?
- [ ] Is there opportunity to optimize sequential hashing?

## Dependencies & Blockers

### Must Happen First
[Prerequisites]

Example:
- Phase 0: Security tests written and failing (CRIT-1 test)

### Blocks
[What this enables]

Example:
- Phase 2: CRIT-2 fix (depends on CRIT-1 being complete)

### Blocked By
[What prevents this]

Example:
- None (independent issue)

## Decision

### Final Status
[APPROVED/REJECTED/DEFERRED]

**Status**: APPROVED

**Approval By**: [Agent or Team]

**Date**: YYYY-MM-DD

### Rationale for Decision
[Why this approach was chosen]

Example:
> Order-preserving sequential hash is the correct fix for XOR commutativity vulnerability. Simple to implement, cryptographically sound, minimal performance impact. No viable alternatives that are both secure and practical.

### Next Steps
[What happens after approval]

Example:
1. Implement fix in KeyConfigurationDigester.java
2. Run test - verify CRIT-1 test passes
3. Run regression suite - verify no new failures
4. Code review - request approval
5. Complete Phase 1 checkpoint

---

**Status**: [PROPOSED/VALIDATED/IMPLEMENTED]
**Created**: YYYY-MM-DD
**Last Updated**: YYYY-MM-DD
**Related Bead**: [Bead ID]
**Related Learning**: learnings/L-N-[title].md
