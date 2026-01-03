# Learning Template

**Copy this file for each significant insight gained**

---

# Learning: [Title]

**Date**: YYYY-MM-DD
**Phase**: N
**Issue**: CRIT-X (if applicable)
**Agent**: [Name] (or Manual)
**Status**: RECORDED

## The Learning

[2-3 sentences stating what was learned]

Example:
> XOR accumulation for key digest calculation is commutative, meaning different orderings of keys produce identical results. This violates KERI's key ordering invariant and allows attackers to bypass multisig threshold enforcement by rotating to keys in unintended order.

## Why It Matters

[1-2 paragraphs explaining security/design impact]

Example:
> This vulnerability is critical because it breaks a fundamental KERI safety property: that key rotations are ordered and enforced through pre-commitment. The attack allows a malicious node to evade key ordering constraints by simply XORing keys in different order, potentially bypassing multisig thresholds that require specific key order (e.g., "require keys 1 AND 2, but NOT 3" can be bypassed by rotating to keys 3, 2, 1).
>
> The fix teaches us that cryptographic operations must be chosen carefully for their properties. XOR's commutativity is useful for symmetric operations but dangerous for order-dependent calculations. This lesson applies to other digest calculations throughout the codebase.

## Evidence

### Code Location
- **File**: [path/ClassName.java]
- **Method**: [methodName]
- **Lines**: [start:end]
- **Link**: [Provide code snippet or line reference]

Example:
- **File**: stereotomy/src/main/java/.../KeyConfigurationDigester.java
- **Method**: digest(List<PublicKey>)
- **Lines**: 29-39
- **Snippet**:
  ```java
  // VULNERABLE CODE
  private static Digest digest(List<PublicKey> keys) {
      var accumulator = Digest.NONE;
      for (PublicKey key : keys) {
          accumulator = accumulator.xor(hash(key));  // XOR is commutative!
      }
      return accumulator;
  }
  ```

### Test Evidence
- **Test File**: [path/TestClassName.java]
- **Test Method**: [testMethodName]
- **Status**: [PASS/FAIL]
- **Demonstrates**: [What the test proves]

Example:
- **Test File**: stereotomy/src/test/java/.../SecurityTests.java
- **Test Method**: testCRIT1_XORCommutativityAttack
- **Status**: PASS (after fix)
- **Demonstrates**: Different key orders produce different digests (fix prevents evasion)

### Attack Scenario
[Describe concrete attack exploiting this vulnerability]

Example:
1. Honest node creates key rotation with keys [k1, k2, k3]
2. Multisig policy requires: "Any 2 of [k1, k2, k3]" for transaction approval
3. Byzantine attacker intercepts rotation, modifies to [k3, k2, k1] (same keys, different order)
4. Old system computes: k1 XOR k2 XOR k3 (order irrelevant, same digest)
5. Attack succeeds: Byzantine node now controls keys [k3, k2, k1] with same threshold
6. Issue: Multisig policy expected keys in order [k1, k2, k3] (e.g., different key authorities)

## Related Learnings

[List other learnings from same issue or related work]

Examples:
- L-2-cryptographic-properties: How to evaluate cryptographic operations for correctness
- L-3-test-first-benefits: Why writing tests first catches security issues
- L-4-backward-compatibility: Ensuring fixes don't break existing deployments

## Action Items

### Immediate Actions
- [ ] [Action if any immediate follow-up needed]

Example:
- [ ] Audit other XOR-based digests in codebase (similar vulnerability risk)

### Future Improvements
- [ ] [Longer-term improvement]

Example:
- [ ] Add cryptographic property validation: require explicit documentation of hash function commutativity/associativity assumptions
- [ ] Create reusable order-preserving digest utility class
- [ ] Add performance benchmark: XOR vs sequential hash performance comparison

### Knowledge Sharing
- [ ] [Communication or documentation]

Example:
- [ ] Document in design guidelines: "Use order-preserving hash for order-dependent operations, not XOR"
- [ ] Share learning with team via code review comments
- [ ] Add FAQ entry: "Why not use XOR for key digests?"

## Related Issues

[Cross-references to related critical issues or learnings]

Example:
- **Related to CRIT-2**: Race conditions in MemKERL may interact with key rotation state
- **Related to CRIT-5**: Non-atomic state transitions could allow inconsistent key state
- **Relates to Learning L-2**: Cryptographic property misuse pattern appears in multiple issues

## Key Insights by Category

### Cryptographic Insights
[What was learned about cryptography/security]

Example:
- XOR's commutativity makes it unsuitable for order-dependent operations
- Always verify cryptographic operation properties match use case
- Order preservation requires sequential hashing, not accumulation

### Design Pattern Insights
[What was learned about design/architecture]

Example:
- God object (View class) hides security issues through complexity
- Extracting components forces explicit security model
- Small, focused classes make security properties obvious

### Process Insights
[What was learned about TDD/methodology]

Example:
- Writing test first forces explicit understanding of security properties
- TDD catches security bugs earlier in development
- Test names document expected behavior (tests as specification)

### System Insights
[What was learned about the system as a whole]

Example:
- KERI implementation has multiple similar vulnerabilities (suggests systematic issues)
- Mixing academic papers (Fireflies, Rapid) without formal validation is risky
- Transaction semantics need explicit validation end-to-end

## Comparative Analysis

[How does this learning compare to other systems/implementations?]

Example:
> Other KERI implementations (like Cardano's implementation) use SHA-256 hashing for all digests, avoiding XOR entirely. This is safer because SHA-256 is order-dependent and cryptographically sound. Delos's optimization to XOR was premature and violated security properties.

## Recommendations

### For This Issue
[Specific to CRIT-X fix]

Example:
- Use order-preserving hash (SHA-256) for all key digests
- Add test covering different key orderings
- Document in code comment why XOR was replaced

### For Similar Issues
[Applicable to CRIT-Y, CRIT-Z, etc.]

Example:
- Audit all XOR-based calculations for order-dependency
- Validate cryptographic operation properties for use case
- Add security property comments to all digest calculations

### For Codebase
[Broader improvements]

Example:
- Create reusable digest utilities with documented properties
- Add design review checklist for security-critical code
- Establish pattern: "Cryptographic operations must be proven for their properties"

## Learning History

[Track evolution of understanding for this learning]

Example:
- **Initial Hypothesis** (Phase 0): "XOR is fast digest method"
- **Discovery** (Phase 0 testing): "XOR is commutative, allows key order evasion"
- **Deep Analysis**: XOR breaks KERI's key ordering invariant
- **Fix Validated** (Phase 1): Order-preserving hash prevents evasion
- **Broader Context**: Multiple vulnerabilities suggest systematic cryptographic misuse

---

**Learning Status**: RECORDED
**Last Updated**: YYYY-MM-DD
**Related Checkpoint**: checkpoints/phase-N-complete.md
