# Delos Remediation - Engineering Methodology

## Core Principles

### 1. Test-Driven Development (Mandatory)

Every fix MUST follow this sequence:

```
1. Write failing test that demonstrates the bug
2. Verify test fails for the right reason
3. Implement the fix
4. Verify test passes
5. Run full module test suite
6. Submit for code review
```

**Rationale**: The bugs identified in the critique are subtle. TDD ensures:
- We understand the bug before fixing it
- The fix actually addresses the issue
- Future regressions are prevented
- Documentation of expected behavior

### 2. Hypothesis-Driven Analysis

For complex bugs or unclear behavior, use sequential thinking:

```
Thought 1: State hypothesis about root cause
Thought 2: Identify evidence needed to validate
Thought 3: Gather evidence (code, tests, logs)
Thought 4: Evaluate - does evidence support hypothesis?
Thought 5: Derive next steps or branch to new hypothesis
```

**Use Cases**:
- Understanding switch fall-through impact
- Analyzing staleness check semantics
- Tracing SQL query requirements

### 3. Paper-First Design

When implementing missing features:

1. **Reference the academic paper** via mixedbread "delos" store
2. **Document the algorithm** in ChromaDB cross-reference
3. **Implement to paper specification**
4. **Note any intentional deviations** with rationale

---

## Definition of Done

### Bug Fixes

A bug fix is complete when:

- [ ] Failing test exists that reproduces the bug
- [ ] Fix implemented with minimal code changes
- [ ] All existing tests still pass
- [ ] New tests verify fix works
- [ ] Code reviewed by code-review-expert
- [ ] Bead closed with summary
- [ ] EXECUTION_STATE.md updated

### Security Fixes

A security fix is complete when:

- [ ] Threat model documented
- [ ] Attack vector demonstrated (if safe)
- [ ] Fix implemented
- [ ] Security review completed
- [ ] No new attack vectors introduced
- [ ] Documentation updated
- [ ] Bead closed with security notes

### API Implementations

An API implementation is complete when:

- [ ] Paper specification referenced
- [ ] Design documented in hypothesis
- [ ] Unit tests written first
- [ ] Integration tests written
- [ ] Implementation complete
- [ ] JavaDoc complete
- [ ] Code reviewed
- [ ] Bead closed

### Documentation Tasks

A documentation task is complete when:

- [ ] Research completed (papers, code, existing docs)
- [ ] Draft written
- [ ] Technical accuracy verified
- [ ] Examples included where appropriate
- [ ] Reviewed for clarity
- [ ] Bead closed

---

## Code Review Requirements

### Mandatory Review Points

All changes MUST be reviewed for:

1. **Correctness**: Does the fix actually work?
2. **Completeness**: Are edge cases handled?
3. **Thread Safety**: Is concurrent access safe?
4. **Performance**: Any performance regressions?
5. **Testing**: Are tests adequate?

### Review Checklist by Type

#### Critical Bug Fixes
- [ ] Root cause identified correctly
- [ ] Fix addresses root cause (not symptoms)
- [ ] No unintended side effects
- [ ] Regression test exists
- [ ] Documentation updated if behavior changes

#### Security Fixes
- [ ] Threat model reviewed
- [ ] Fix doesn't introduce new vulnerabilities
- [ ] No information leakage
- [ ] Error handling secure
- [ ] Audit logging adequate

#### New Implementations
- [ ] Follows paper specification
- [ ] Deviations documented
- [ ] Interface consistent with module style
- [ ] Error handling complete
- [ ] Performance acceptable

---

## Commit Message Standards

### Format

```
<type>(<scope>): <subject>

<body>

<footer>
```

### Types
- `fix`: Bug fix (use for Phase 0, 1)
- `feat`: New feature (use for Phase 2)
- `docs`: Documentation (use for Phase 3)
- `refactor`: Code restructuring (use for Phase 4)
- `test`: Adding tests
- `security`: Security-related changes

### Scope
Use module name: `ethereal`, `delphinius`, `cryptography`, `choam`, etc.

### Examples

```
fix(ethereal): Add break statements in UnanimousVoter switch

The switch statement at line 283 was missing break statements,
causing POPULAR votes to set both votesOne and votesZero to true,
corrupting consensus voting.

Refs: critique::master::delos-codebase-review-2025-12-30
Bead: BD-XXX
```

```
security(delphinius): Implement snapshot reads in check()

The check() method was reading current state instead of state
at the specified timestamp, enabling the New Enemy Problem
where stale ACLs could grant access to new content.

Implements true snapshot isolation using SQL temporal queries.

Refs: crossref::delphinius::implementation
Bead: BD-XXX
```

---

## Branch Strategy

### Naming Convention

```
remediation/<phase>/<bead-id>-<short-description>
```

### Examples
- `remediation/phase0/bd-001-ethereal-switch-fix`
- `remediation/phase0/bd-002-delphinius-staleness`
- `remediation/phase1/bd-005-crypto-bloomfilter`

### Workflow

1. Create branch from `main`
2. Implement fix with tests
3. Push branch
4. Request review
5. Address feedback
6. Merge to `main`
7. Delete branch

---

## Testing Requirements

### Test Organization

```
src/test/java/
  com/hellblazer/delos/<module>/
    <Feature>Test.java       # Unit tests
    <Feature>IntegrationTest.java  # Integration tests
```

### Test Naming

```java
@Test
void shouldRejectFutureTimestamps() { }

@Test
void shouldNotFallThroughOnPopularVote() { }

@Test
void shouldReturnSubjectPermissions() { }
```

### Coverage Requirements

- New bug fixes: 100% coverage of fix
- New implementations: >80% line coverage
- Security fixes: 100% coverage of security paths

---

## Quality Gates

### Pre-Commit
- [ ] Tests pass locally
- [ ] Code compiles without warnings
- [ ] No TODO comments without bead reference

### Pre-Merge
- [ ] All CI tests pass
- [ ] Code review approved
- [ ] Documentation updated
- [ ] Bead updated

### Phase Completion
- [ ] All phase beads closed
- [ ] Full test suite passes
- [ ] EXECUTION_STATE.md updated
- [ ] CONTINUATION.md updated
- [ ] No regressions in other modules

---

## Error Handling Standards

### Exceptions

```java
// DO: Specific exceptions with context
throw new ConsensusViolationException("Vote corruption detected: " + details);

// DON'T: Generic exceptions
throw new RuntimeException("Error");
```

### Logging

```java
// DO: Use SLF4J with placeholders
log.error("Vote corruption in round {}: {}", round, details);

// DON'T: String concatenation or Python-style
log.error("Vote corruption in round " + round);  // Bad
log.error("Vote corruption: {:d}", round);       // Wrong syntax
```

### Validation

```java
// DO: Fail-fast with clear messages
Objects.requireNonNull(assertion, "assertion must not be null");
if (valid.compareTo(clock.get()) > 0) {
    throw new IllegalArgumentException("Cannot query future timestamp: " + valid);
}
```

---

## Performance Guidelines

### Avoid
- Unnecessary object allocation in hot paths
- Synchronized blocks (use concurrent collections)
- Blocking I/O in event loops

### Prefer
- Primitive types where possible
- Immutable objects
- Pre-sized collections

### Benchmarking

Before/after measurements required for:
- Changes to consensus path
- Changes to cryptographic operations
- Changes to SQL queries

---

## Security Guidelines

### Cryptographic Operations

- Use `DigestAlgorithm` with appropriate strength
- Never use `DigestAlgorithm.NONE` in production paths
- Document threat model for any security-relevant code

### Input Validation

- Validate all external inputs
- Use parameterized SQL queries
- Sanitize log messages

### Access Control

- Verify authorization before action
- Log access decisions
- Fail closed (deny by default)

---

## Tool Usage

### Build
```bash
./mvnw clean install                    # Standard
./mvnw test -pl <module>                # Single module
./mvnw test -Dtest=ClassName#method     # Single test
```

### Beads
```bash
bd create "Title" -t bug -p 1           # Create critical bug
bd update <id> --status in_progress     # Start work
bd close <id>                           # Complete work
```

### Knowledge
```bash
# ChromaDB search
mcp__chromadb__search_similar(query, num_results=10)

# Mixedbread paper search
mcp__mixedbread__store_search(query, store_identifiers=["delos"])
```

---

*Last Updated: 2025-12-30*
