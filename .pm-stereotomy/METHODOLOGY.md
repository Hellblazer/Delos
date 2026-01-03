# Stereotomy Security Remediation - Engineering Methodology

**Project**: Delos Stereotomy KERI Security Remediation
**Approach**: Test-Driven Design (TDD) for Security
**Quality Framework**: Hypothesis-Driven Validation

## Core Principle

**All security fixes follow Test-First discipline**: Write failing test → Implement fix → Verify test passes

This prevents regression, documents expected behavior, and proves fix effectiveness.

## Phase Structure

### Phase 0: TDD Harness Setup (3-4 days)

**Goal**: Establish failing test suite demonstrating all 6 vulnerabilities

**Deliverables**:
- 6 failing security tests (one per CRIT-X issue)
- Baseline metrics (test execution time, memory usage, coverage)
- Regression test harness

**Process**:
1. For each CRIT-X:
   - Write test that demonstrates attack/vulnerability
   - Test should fail consistently with clear error message
   - Test includes assertions for expected behavior
   - Run and verify failure
2. Establish baseline metrics
3. Document in Phase 0 checkpoint

**Success Criteria**:
- [ ] 6 tests fail consistently
- [ ] Baseline metrics captured
- [ ] Regression suite unaffected
- [ ] CI still passing on main

**Bead Commands**:
```bash
bd create "Phase 0: TDD & Security Tests Setup" -t task -p 1
bd update <phase0-id> --status in_progress
```

### Phases 1-5: Fix Implementation (2-3 days each)

**Goal**: Implement fix for each CRIT-X issue and verify test now passes

**Deliverables**:
- Implementation of fix
- Test changes from FAIL to PASS
- Code review approval
- Checkpoint documentation

**Process per CRIT-X**:
1. **Design** (2-4 hours):
   - Read analysis document (critique::stereotomy::...)
   - Understand root cause and attack vector
   - Document design in hypothesis file
   - Identify backward compatibility concerns

2. **Implement** (4-8 hours):
   - Minimal change to make test pass
   - No scope creep (fix the issue, nothing more)
   - Follow Java 24 style (var, records, sealed classes)
   - Add comments explaining security fix

3. **Verify** (2-4 hours):
   - Run: `mvn test -pl stereotomy`
   - Confirm target test passes
   - Confirm no regression failures
   - Run stress test if applicable

4. **Code Review** (2-4 hours):
   - Self-review for security
   - Request code-review-expert review
   - Address feedback
   - Approval and merge

5. **Document** (1-2 hours):
   - Write checkpoint file
   - Record lesson learned
   - Update metrics
   - Close bead

**Success Criteria per Fix**:
- [ ] Test passes
- [ ] Regression suite passes (100%)
- [ ] Code review approved
- [ ] Security impact documented
- [ ] Performance acceptable (±10% baseline)

**Phase 1**: CRIT-1 (XOR Commutativity) - KeyConfigurationDigester.java
**Phase 2**: CRIT-2, 4, 5 (Concurrency/Transactions) - MemKERL, UniKERL, KeyStateProcessor
**Phase 3**: CRIT-3 (Inception Signature) - KeyEventProcessor.java
**Phase 4**: CRIT-6 (Key Clearing) - JksKeyStore, MemKeyStore
**Phase 5**: Audit & Stress Testing

## Design Pattern: Security Fix Pattern

Each security fix follows this pattern:

### 1. Vulnerability Analysis

Read analysis from ChromaDB:
- `critique::stereotomy::deep-analysis-2026-01-02`

Understand:
- **Root Cause**: What code allows vulnerability?
- **Attack Vector**: How does attacker exploit it?
- **Impact**: What security property is violated?
- **Evidence**: What code lines are vulnerable?

**Record in**: `.pm-stereotomy/hypotheses/H-N-[issue].md`

### 2. Test Design

Create test that demonstrates vulnerability:

```java
@Test
void testCRIT1_XORCommutativityAttack() {
    // Arrange: Create two key rotations with same keys in different order
    var keys1 = List.of(key1, key2, key3);
    var keys2 = List.of(key3, key2, key1);

    var digest1 = KeyConfigurationDigester.digest(keys1);
    var digest2 = KeyConfigurationDigester.digest(keys2);

    // Act & Assert: Digests should be different (current code fails here)
    assertNotEquals(digest1, digest2,
        "XOR is commutative: different key orders produce same digest!");
}
```

**Test Characteristics**:
- [ ] Demonstrates vulnerability
- [ ] Fails consistently
- [ ] Clear failure message
- [ ] Measures fix effectiveness
- [ ] Documents expected behavior

**Record in**: `stereotomy/src/test/java/.../SecurityTests.java`

### 3. Root Cause Identification

Trace code to identify exact vulnerability:

**Example (CRIT-1)**:
```java
// KeyConfigurationDigester.java:29-39
// VULNERABLE: XOR is commutative
private static Digest digest(List<PublicKey> keys) {
    var accumulator = Digest.NONE;
    for (PublicKey key : keys) {
        accumulator = accumulator.xor(hash(key));  // WRONG: order doesn't matter
    }
    return accumulator;
}

// FIXED: Order-preserving hash
private static Digest digest(List<PublicKey> keys) {
    var hasher = DigestAlgorithm.DEFAULT.getHasher();
    for (PublicKey key : keys) {
        hasher.update(hash(key).toByteString().toByteArray());
    }
    return hasher.digest();  // Order matters: concatenation not XOR
}
```

### 4. Implementation Strategy

Document approach before implementing:

**Record in**: `.pm-stereotomy/hypotheses/H-N-[issue]-fix.md`

Example structure:
```markdown
# Hypothesis: Fix CRIT-1 via Order-Preserving Hash

## Problem
XOR accumulation is commutative, allowing key rotation order evasion.

## Solution
Replace XOR with sequential hash of concatenated keys.

## Implementation Plan
1. Modify KeyConfigurationDigester.digest() method
2. Use DigestAlgorithm.DEFAULT.getHasher()
3. Update hash() method to use hasher.update() instead of XOR
4. Verify test passes

## Backward Compatibility
- Old keys that passed validation will still pass (forward compatible)
- New keys require correct order (backward compatible)
- Migration: None required (new format on next rotation)

## Security Properties Verified
- [ ] Different key orders produce different digests
- [ ] Original key order preserved
- [ ] No cryptographic compromise
- [ ] Performance acceptable

## Risk Assessment
- Risk: Low (isolated change to digest calculation)
- Testing: Comprehensive (5 test cases)
- Rollback: Simple (revert to XOR if needed)
```

### 5. Implementation

**Code Standards**:
- Java 23+ style: use `var`, pattern matching, records
- No synchronized blocks (use concurrent collections)
- Explicit error handling (no silent failures)
- Comments explaining security fix
- No debug logging in security-critical paths

**Example Fix**:
```java
// Before (VULNERABLE)
private static Digest digest(List<PublicKey> keys) {
    var accumulator = Digest.NONE;
    for (PublicKey key : keys) {
        accumulator = accumulator.xor(hash(key));  // XOR is commutative
    }
    return accumulator;
}

// After (FIXED)
private static Digest digest(List<PublicKey> keys) {
    // Use order-preserving hash instead of commutative XOR
    var hasher = DigestAlgorithm.DEFAULT.getHasher();
    // Order matters: sequential concatenation, not XOR
    for (PublicKey key : keys) {
        hasher.update(hash(key).toByteString().toByteArray());
    }
    return hasher.digest();  // Order-dependent: [k1, k2, k3] != [k3, k2, k1]
}

// Add test case validating order-dependency
@Test
void testKeyOrderMatters() {
    var keys1 = List.of(key1, key2, key3);
    var keys2 = List.of(key3, key2, key1);

    assertNotEquals(
        digest(keys1),
        digest(keys2),
        "Key order must affect digest (prevents evasion attacks)"
    );
}
```

### 6. Testing & Verification

**Unit Test**:
```bash
mvn test -Dtest=SecurityTests -pl stereotomy
# Verify: CRIT-1 test PASSES
```

**Regression Tests**:
```bash
mvn test -pl stereotomy
# Verify: All existing tests PASS
```

**Performance Impact**:
```bash
# Baseline (Phase 0): Note execution time and memory
# After fix: Compare to baseline
# Acceptable: ±10% difference
```

### 7. Code Review

**Self-Review Checklist**:
- [ ] Root cause addressed
- [ ] No scope creep (only fixes CRIT-X)
- [ ] Backward compatible (or migration documented)
- [ ] Test passes
- [ ] Regression suite passes
- [ ] Code follows style guide
- [ ] Comments explain security fix
- [ ] No security bypass remains
- [ ] Performance acceptable

**External Review** (code-review-expert):
- [ ] Security fix validation
- [ ] Code quality assessment
- [ ] Performance impact review
- [ ] Approval/feedback

### 8. Documentation

**Checkpoint File** (.pm-stereotomy/checkpoints/phase-N-complete.md):
```markdown
# Phase N: Fix CRIT-X

**Completed**: 2026-01-DD
**Issue**: CRIT-X: [Title]
**Bead**: [ID] (CLOSED)

## Summary
Fixed [vulnerability] by [approach].

## Changes
- File: [source file]
- Method: [method name]
- Lines: [line range]

## Test Results
- CRIT-X test: PASS
- Regression suite: PASS (123/123)
- Performance: -5% (baseline: 1.2s, now: 1.14s)

## Security Validation
- Attack vector blocked: [describe]
- No new vulnerabilities introduced: [evidence]

## Lessons Learned
- Lesson 1: [insight]
- Lesson 2: [insight]

## Next Phase
Fix CRIT-Y in Phase N+1
```

**Learning File** (.pm-stereotomy/learnings/L-N-[issue].md):
```markdown
# Learning: CRIT-X - [Title]

**Date**: 2026-01-DD
**Phase**: N
**Issue**: CRIT-X

## The Learning
[What was learned]

## Why It Matters
[Security/design impact]

## Evidence
- Code location: [file:lines]
- Test: [test class and method]
- Attack scenario: [describe]

## Action Items
- [ ] Item 1
- [ ] Item 2

## Related Items
- CRIT-Y (depends on this fix)
- [Other references]
```

## Quality Gates

### Phase 0 Gate (TDD Setup)
Before moving to Phase 1, verify:
- [ ] CRIT-1 failing test written and fails
- [ ] CRIT-2 failing test written and fails
- [ ] CRIT-3 failing test written and fails
- [ ] CRIT-4 failing test written and fails
- [ ] CRIT-5 failing test written and fails
- [ ] CRIT-6 failing test written and fails
- [ ] Baseline metrics captured
- [ ] No regressions in existing codebase
- [ ] All 6 tests fail consistently

### Per-Fix Gate (Phases 1-5)
Before closing CRIT-X bead, verify:
- [ ] Test passes (mvn test -Dtest=*Security*)
- [ ] Regression suite passes (mvn test -pl stereotomy)
- [ ] Code review approved
- [ ] Performance within baseline ±10%
- [ ] Checkpoint document created
- [ ] Learning document created
- [ ] Bead closed

### Phase 6 Gate (Audit & Release)
Before merging to main, verify:
- [ ] All 6 CRIT-X issues fixed
- [ ] All 6 tests pass
- [ ] Stress test passes (1000+ events)
- [ ] Memory leak analysis clean
- [ ] Security audit complete
- [ ] No new vulnerabilities found

## Failure Handling

### Test Fails to Pass After Fix

**Procedure**:
1. Review fix implementation
2. Check test expectations
3. If test is wrong: Update test, re-test
4. If implementation wrong: Spawn java-debugger agent
5. Document issue and resolution

**Escalation**:
- Bead status: blocked
- Create debug checkpoint
- Spawn java-debugger (see AGENT_INSTRUCTIONS.md)

### Regression Test Fails

**Procedure**:
1. Identify which test failed
2. Compare pre/post-fix test behavior
3. If regression is real: Fix introduced side effect
4. If regression is false alarm: Investigate test flakiness

**Prevention**:
- Run full regression suite after each fix
- Compare baseline metrics
- Review code changes for side effects

## Metrics Tracking

### Baseline Metrics (Phase 0)
Record in `.pm-stereotomy/metrics/baseline.md`:
- Test execution time
- Memory usage
- Code coverage
- Build time

### Per-Phase Metrics
Record in `.pm-stereotomy/metrics/phase-N-metrics.md`:
- Test execution time (delta from baseline)
- Memory usage (delta from baseline)
- Code coverage
- Performance impact
- Issues found/fixed

### Success Metrics
- All 6 tests pass
- Regression suite 100% pass rate
- Performance within ±10% baseline
- Zero security audit findings
- Stress test: 1000+ events processed correctly

## Tooling & Commands

### Build & Test
```bash
# Build module
mvn clean install -amd -pl stereotomy -DskipTests

# Run security tests
mvn test -Dtest=SecurityTests -pl stereotomy

# Run all stereotomy tests
mvn test -pl stereotomy

# Run full test suite
mvn clean install -pl stereotomy
```

### Beads Management
```bash
# Create epic
bd create "Stereotomy KERI Security Remediation" -t epic -p 0

# Create phase task
bd create "Phase 0: TDD Setup" -t task -p 1

# Create critical issue
bd create "CRIT-1: XOR Commutativity Attack" -t bug -p 1

# Update status
bd update <id> --status in_progress
bd update <id> --status completed

# Add dependency
bd dep add <id> <depends-on-id>

# Show details
bd show <id>
```

### ChromaDB Searches
```bash
# Find analysis documents
# (Use WebSearch tool or search pattern from AGENT_INSTRUCTIONS.md)

# Add findings
mcp__chromadb__create_document(document_id="phase-N-findings", content=...)
```

### Git Operations
```bash
# View changes
git diff

# Stage changes
git add [files]

# Commit (NO AI ATTRIBUTION per company policy)
git commit -m "Fix CRIT-1: XOR commutativity attack [Bead-ID]"

# Push to branch
git push origin feat/stereotomy-review
```

## Documentation Standards

All documentation must be:
- **Clear**: Written for developers new to the module
- **Evidence-Based**: Include code references and test evidence
- **Actionable**: Provide next steps and clear decisions
- **Consistent**: Use templates from `.pm-stereotomy/` directory

## Reference Materials

- **CLAUDE.md**: Global project directives
- **EXECUTION_STATE.md**: Current phase and metrics
- **CONTINUATION.md**: Quick resume guide
- **AGENT_INSTRUCTIONS.md**: Instructions for spawned agents
- **ChromaDB**: `critique::stereotomy::deep-analysis-2026-01-02`
- **Fireflies Project**: `.pm/` directory (reference implementation)

---

**Last Updated**: 2026-01-02
**Version**: 1.0
**Discipline**: Test-Driven Design for Security
