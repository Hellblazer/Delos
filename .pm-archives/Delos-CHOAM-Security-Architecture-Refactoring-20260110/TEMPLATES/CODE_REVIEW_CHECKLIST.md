# Code Review Checklist

**Project**: CHOAM Security & Architecture Refactoring

---

## Quick Reference

Copy this checklist for each code review:

```markdown
## Code Review: [Bead ID]

**Reviewer**:
**Date**:
**Phase**: [0 | 1 | 2]

### General
- [ ] Code compiles without warnings
- [ ] All tests pass
- [ ] No regressions
- [ ] Commit message clear with bead reference

### Phase-Specific
[Use appropriate section below]

### Decision
- [ ] APPROVED
- [ ] APPROVED with minor comments
- [ ] NEEDS CHANGES

### Comments
[Feedback here]
```

---

## Phase 0: Security Review Checklist

### Cryptographic Operations

- [ ] Signature algorithms correct
- [ ] Key sizes appropriate
- [ ] No hardcoded keys or secrets
- [ ] Key material properly cleared after use
- [ ] Secure random number generation

### Input Validation

- [ ] All external input validated
- [ ] Size limits enforced
- [ ] Type validation performed
- [ ] No injection vulnerabilities
- [ ] Deserialization safe

### Error Handling

- [ ] Errors fail closed (deny by default)
- [ ] No sensitive data in error messages
- [ ] No sensitive data in logs
- [ ] Exceptions handled securely
- [ ] No information leakage

### Transaction Validation (P0-1)

- [ ] Signature validated before processing
- [ ] Signer authority verified
- [ ] Replay attack prevented
- [ ] Invalid transactions rejected

### Queue Bounds (P0-2)

- [ ] Queue size limits enforced
- [ ] Overflow handled correctly
- [ ] Memory bounds verified
- [ ] DoS resistant

### Circuit Breaker (P0-3)

- [ ] Failure threshold appropriate
- [ ] Backoff timing correct
- [ ] Recovery logic correct
- [ ] No cascading failures

### Race Condition (P0-4)

- [ ] Race condition eliminated
- [ ] Atomicity verified
- [ ] No partial states possible
- [ ] Concurrent access safe

---

## Phase 1: Architecture Review Checklist

### Interface Design

- [ ] Interface is focused (single responsibility)
- [ ] Methods are well-named
- [ ] Parameters are clear
- [ ] Return types appropriate
- [ ] Exceptions documented

### Component Extraction

- [ ] Component has clear boundaries
- [ ] Dependencies are injected
- [ ] No circular dependencies
- [ ] State properly managed
- [ ] Lifecycle clear

### CHOAM Decomposition

- [ ] CHOAM.java reduced appropriately
- [ ] Coordinator role only
- [ ] No business logic in CHOAM
- [ ] Components properly wired
- [ ] Factory pattern used

### Testing

- [ ] Interface tests comprehensive
- [ ] Unit tests for component
- [ ] Integration tests updated
- [ ] Determinism tests passing
- [ ] Coverage improved

### Backwards Compatibility

- [ ] External API unchanged (or documented)
- [ ] No behavior changes
- [ ] Ethereal integration maintained
- [ ] Fireflies integration maintained

---

## Phase 2: Quality Review Checklist

### Code Quality

- [ ] Cyclomatic complexity acceptable (<15)
- [ ] Method length acceptable (<50 lines)
- [ ] Clear naming conventions
- [ ] Appropriate comments
- [ ] No code duplication

### Byzantine Tests

- [ ] Scenario clearly documented
- [ ] Setup is reproducible
- [ ] Assertions are clear
- [ ] Cleanup is complete
- [ ] Test name describes behavior

### Performance

- [ ] No obvious performance issues
- [ ] Appropriate data structures
- [ ] No unnecessary allocations
- [ ] Efficient algorithms

### Documentation

- [ ] Javadoc for public APIs
- [ ] Complex logic commented
- [ ] README updated if needed
- [ ] Architecture docs updated

---

## All Phases: General Checklist

### Code Style

- [ ] Follows project conventions
- [ ] Consistent formatting
- [ ] Appropriate use of var
- [ ] Modern Java patterns (Java 24+)

### Testing

- [ ] Tests written first (TDD)
- [ ] Tests are independent
- [ ] Tests have clear assertions
- [ ] Edge cases covered
- [ ] Error paths tested

### Documentation

- [ ] Commit message clear
- [ ] Bead reference included
- [ ] Complex changes explained
- [ ] Breaking changes documented

### Process

- [ ] Build passes locally
- [ ] CI passes
- [ ] Coverage not decreased
- [ ] No new warnings

---

## Review Decision Guide

### APPROVED

Use when:
- All checklist items pass
- No blocking issues
- Code is production-ready

### APPROVED with minor comments

Use when:
- Minor issues that can be addressed
- Issues don't block functionality
- Author can merge after addressing

### NEEDS CHANGES

Use when:
- Blocking issues found
- Security concerns
- Tests failing
- Coverage inadequate
- Architecture issues

---

## Common Review Feedback

### Security (Phase 0)

```
CONCERN: Input not validated before use
SUGGESTION: Add validation at entry point

CONCERN: Error message exposes internal details
SUGGESTION: Return generic error, log details internally

CONCERN: Signature validation can be bypassed
SUGGESTION: Validate at all entry points, not just happy path
```

### Architecture (Phase 1)

```
CONCERN: Component has too many responsibilities
SUGGESTION: Consider extracting [X] into separate component

CONCERN: Circular dependency detected
SUGGESTION: Use interface or event pattern to break cycle

CONCERN: Direct coupling to external system
SUGGESTION: Use interface for testability
```

### Quality (Phase 2)

```
CONCERN: Method too complex (cyclomatic complexity X)
SUGGESTION: Extract helper methods for [X, Y, Z]

CONCERN: Test doesn't assert meaningful behavior
SUGGESTION: Add assertion for [expected outcome]

CONCERN: Missing edge case coverage
SUGGESTION: Add test for [scenario]
```

---

## Review Response Template

```markdown
## Review Response

### Changes Made
- [Change 1]
- [Change 2]

### Discussion Points
- [Response to comment 1]
- [Response to comment 2]

### Not Addressed (with reason)
- [Item]: [Reason, e.g., "Will address in separate PR"]

### Ready for Re-Review
[Yes/No]
```

---

**Checklist Established**: 2026-01-09
