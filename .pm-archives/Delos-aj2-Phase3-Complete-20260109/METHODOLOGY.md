# Engineering Discipline & Methodology

**Project**: Delos Gorgoneion Security & Quality Remediation
**Discipline Level**: STRICT (Security-critical path)
**Version**: 1.0
**Effective Date**: 2026-01-08

---

## Core Principles

### 1. Test-First Development (TFD)

Every change follows this strict sequence:

1. **Write Test First**
   - Demonstrate the bug OR desired behavior
   - Test must fail initially (for bugs) or demonstrate feature
   - Test should be minimal and focused

2. **Implement Fix**
   - Make the test pass
   - Use simplest solution (no premature optimization)
   - Add comments for non-obvious code

3. **Run Full Suite**
   ```bash
   ./mvnw clean install -Dlarge_tests=true
   ```
   - All tests must pass
   - No regressions allowed
   - Memory tests may need increased heap

4. **Code Review**
   - Peer review before merge
   - Security focus for Phase 0
   - Architecture focus for Phase 1
   - Quality focus for Phase 2

5. **Integration Test**
   - Full build with Stereotomy dependency
   - Full build with Fireflies dependency
   - Run cross-module tests

### 2. Security-First Mindset (Phase 0)

For critical security issues:

- **Assume hostile input** - Validate all external input
- **Cryptographic correctness** - Get external review
- **Fail securely** - Errors default to denying access
- **Principle of least privilege** - Minimize access scope
- **Defense in depth** - Multiple validation layers

### 3. Byzantine Fault Tolerance

When fixing issues affecting BFT properties:

- **State consistency** - No race conditions under concurrent access
- **Liveness** - System continues despite f Byzantine failures
- **Safety** - No invalid state reached under any failure scenario
- **Progress** - Byzantine cut consensus always progresses

### 4. No Shortcuts

**Prohibited**:
- Skipping tests to "save time"
- Merging without code review
- Assuming dependencies work correctly
- Leaving TODOs without context
- Untested error paths
- Performance assumptions without measurement

**Required**:
- Every test must demonstrate coverage
- Every code review must address security/quality
- Every merge must pass full suite
- Every issue must reference a bead
- Every commit must explain the why

---

## Detailed Workflow

### Issue Startup (5-10 minutes)

1. **Get Issue Details**
   ```bash
   bd show Delos-XXXX
   ```

2. **Check Dependencies**
   - Does this depend on other beads?
   - Are those beads closed?
   - If not, wait or escalate blocker

3. **Mark In Progress**
   ```bash
   bd update Delos-XXXX --status in_progress
   ```

4. **Create Checkpoint**
   ```
   .pm/checkpoints/YYYYMMDD-HHMM-[bead-id].md
   ```
   - Record what issue you're fixing
   - Document approach before starting

### Exploratory Phase (10-30 minutes)

1. **Understand the Code**
   - Locate relevant files
   - Read existing tests
   - Understand current behavior

2. **Identify Test Point**
   - What test demonstrates the issue?
   - What test validates the fix?
   - Create test skeleton

3. **Create Test First**
   ```java
   @Test
   void shouldDemonstrateIssue() {
       // Arrange

       // Act

       // Assert
   }
   ```

4. **Verify Test Fails**
   ```bash
   ./mvnw test -pl gorgoneion -Dtest=ClassName#methodName
   ```
   - Test must fail initially (for bug fixes)
   - Should demonstrate the problem clearly

### Implementation Phase (30-120 minutes)

1. **Implement Minimal Fix**
   - Only change what's necessary
   - Use clear variable names
   - Add comments for non-obvious code

2. **Make Test Pass**
   ```bash
   ./mvnw test -pl gorgoneion -Dtest=ClassName#methodName
   ```

3. **Run Full Module Tests**
   ```bash
   ./mvnw test -pl gorgoneion
   ```
   - Verify no regressions in gorgoneion
   - All tests must pass

4. **Check for Similar Issues**
   - Is this pattern elsewhere?
   - Apply same fix in other locations
   - Update tests accordingly

### Integration Phase (20-40 minutes)

1. **Run Full Build**
   ```bash
   ./mvnw clean install
   ```
   - Builds all modules
   - Verifies Stereotomy compatibility
   - Verifies Fireflies compatibility

2. **Run Stereotomy Integration Tests**
   ```bash
   ./mvnw test -pl gorgoneion,stereotomy -DskipTests=false
   ```
   - Verify KERI operations still work
   - Check key validation still works

3. **Run Fireflies Integration Tests**
   ```bash
   ./mvnw test -pl gorgoneion,fireflies -DskipTests=false
   ```
   - Verify BFT membership still works
   - Check Byzantine scenarios

### Code Review Phase (15-30 minutes)

1. **Create Commit**
   ```bash
   git add -A
   git commit -m "fix: description of change [Delos-XXXX]

   - Point 1: What was wrong
   - Point 2: How it's fixed
   - Point 3: How it's tested
   "
   ```

2. **Request Review**
   - For Phase 0 (security): Security-focused reviewer
   - For Phase 1 (design): Architecture-focused reviewer
   - For Phase 2 (quality): General code review

3. **Address Feedback**
   - Respond to all comments
   - Make requested changes
   - Re-run tests after changes
   - Commit additional changes as needed

4. **Verify Sign-Off**
   - Review approved
   - All feedback addressed
   - Ready to merge

### Finalization (5 minutes)

1. **Merge**
   ```bash
   git merge feature-branch
   ```

2. **Run Final Tests**
   ```bash
   ./mvnw clean install -Dlarge_tests=true
   ```

3. **Close Bead**
   ```bash
   bd close Delos-XXXX
   ```

4. **Update Checkpoint**
   - Record what was completed
   - Note any learnings
   - Document any remaining issues

---

## Checkpoint Template

Create file: `.pm/checkpoints/YYYYMMDD-HHMM-[bead-id].md`

```markdown
# Work Session: [Issue Name]

**Date**: YYYY-MM-DD HH:MM UTC
**Bead**: Delos-XXXX
**Duration**: [X hours]
**Status**: [IN PROGRESS / COMPLETE]

## What Was Done

- [List completed tasks]
- [List tests written]
- [List fixes implemented]

## What Was Learned

- [Key insights]
- [Design decisions made]
- [Unexpected findings]

## Blockers Encountered

- [Issues and resolutions]
- [Decisions needed from others]
- [Escalations made]

## Tests Created

- [Test class and methods]
- [Coverage area]
- [Pass/fail status]

## Next Actions

- [If not complete: what's left]
- [Dependencies for next issue]
- [Questions or clarifications needed]

## Files Modified

- src/main/java/...
- src/test/java/...

## Metrics

- Tests written: X
- Code coverage added: X%
- Time spent: X hours
```

---

## Commit Message Format

**Required format**:
```
<type>: <subject>

<body>

<footer>
```

### Type
- `fix` - Bug fix
- `feat` - New feature
- `refactor` - Code reorganization
- `test` - Test additions
- `docs` - Documentation
- `perf` - Performance improvement

### Subject
- Imperative mood ("add" not "added")
- No capitalization at start
- No period at end
- <50 characters

### Body
- Explain what changed and why
- Wrap at 72 characters
- Multiple bullet points OK:
  ```
  - Point 1: What was wrong
  - Point 2: How it's fixed
  - Point 3: Why this approach
  ```

### Footer
- Required: Bead reference
  ```
  References: Delos-XXXX
  ```
- Optional: Breaking changes
  ```
  BREAKING CHANGE: description
  ```

### Example
```
fix: validate attestation signatures before credential acceptance

- Add CryptoValidator to verify BFT-cut signatures
- Check all 3f+1 validations present before proceeding
- Reject credentials with insufficient signatures
- Add 12 tests covering edge cases

Prevents authorization of invalid credentials.

References: Delos-XXXX
```

---

## Code Review Checklist

**Security Focus (Phase 0)**:
- [ ] All cryptographic operations validated
- [ ] No unsafe type casts
- [ ] All external input sanitized
- [ ] Error handling secure (fail closed)
- [ ] No hardcoded secrets
- [ ] Proper key material handling

**Design Focus (Phase 1)**:
- [ ] Follows module architecture
- [ ] Clear component boundaries
- [ ] No circular dependencies
- [ ] State machine correct
- [ ] Race conditions prevented
- [ ] Error recovery complete

**Quality Focus (Phase 2)**:
- [ ] Code is readable
- [ ] Names are descriptive
- [ ] Complexity reasonable
- [ ] Performance acceptable
- [ ] Tests comprehensive
- [ ] Documentation complete

**All Phases**:
- [ ] All tests pass
- [ ] No regressions
- [ ] Code coverage improved
- [ ] Follows methodology
- [ ] Commit message clear
- [ ] Bead reference present

---

## Dependency Management

### Explicit Dependency Tracking

Use bead system to model dependencies:

```bash
# Create dependency: Delos-A depends on Delos-B
bd dep add Delos-A Delos-B

# Delos-B must be closed before Delos-A starts
# Use: bd ready  (shows only unblocked issues)
```

### Dependency Types

1. **Blocking Dependencies** - Cannot start until resolved
   - Use for critical prerequisites
   - Example: Cryptographic Validation blocks Authentication

2. **Informational Dependencies** - Should read but not blocking
   - Document in bead description
   - Example: Quality issue references Phase 0 security fix

### Dependency Graph (Phase 0)

```
Cryptographic Validation (CRIT-1)
  ├── Authentication Bypass (CRIT-2)
  ├── Key Management (CRIT-3)
  └── Protocol Enforcement (CRIT-4)
```

**Recommended Order**:
1. Start with Cryptographic Validation (foundation)
2. Then Authentication Bypass (depends on crypto)
3. Parallel: Key Management and Protocol Enforcement
4. Gate: All four complete before Phase 1

---

## Testing Standards

### Unit Tests

**Required for**:
- Every public method
- Every private method with complex logic
- All error paths
- Edge cases and boundaries

**Standards**:
- Test class per Java class (ClassName → ClassNameTest)
- Test method naming: `shouldDescribeExpectedBehavior`
- Arrange-Act-Assert pattern
- One logical assertion per test
- Mock external dependencies
- Clear failure messages

### Integration Tests

**Required for**:
- Stereotomy interaction
- Fireflies interaction
- Multi-component workflows
- Protocol sequences

**Standards**:
- Test class name ends with "IntegrationTest"
- Use real instances (not mocks) of integrated components
- Dynamic port allocation
- Clean up resources in tearDown
- Independent tests (can run in any order)

### Byzantine Tests

**Required for**:
- Byzantine failure scenarios
- State consistency under failures
- Consensus properties
- Recovery mechanisms

**Standards**:
- Test class name ends with "ByzantineTest"
- Simulate f Byzantine failures
- Verify liveness and safety
- Property-based testing where appropriate

### Test Coverage Requirements

| Category | Target |
|----------|--------|
| Critical paths | 100% |
| Public API | 95%+ |
| Error handling | 95%+ |
| Overall | 85%+ |

---

## Knowledge Management

### Permanent Knowledge (ChromaDB)

**When to store**:
- Architecture decisions with rationale
- Security findings and mitigations
- Design patterns and tradeoffs
- Research findings
- Complex algorithm explanations

**Example**:
```
document_id: "decision::gorgoneion::attestation-validation"
content: """
# Attestation Validation Design

## Problem
How to validate credential attestations while maintaining Byzantine tolerance?

## Solution
Require Byzantine cut (3f+1) validations before accepting credential.
Each member independently validates using attestation service.
Majority validation indicates correctness under f Byzantine failures.

## Rationale
- Ensures liveness: Can always reach f+1 honest members
- Ensures safety: f Byzantine failures cannot force invalid state
- Reduces trust: No single validation service required

## Tradeoffs
- Higher latency: Wait for all f+1 validations
- Higher bandwidth: Each validation must be transmitted
- Complexity: State machine for tracking validations
"""
metadata: {
  "component": "gorgoneion",
  "type": "decision",
  "date": "2026-01-08"
}
```

### Session Work (Memory Bank)

**When to store**:
- Active hypotheses being investigated
- Blocker analysis and workarounds
- Phase-specific findings
- Cross-agent handoff information

**Example**:
```bash
mcp__allPepper-memory-bank__memory_bank_write(
  projectName: "Delos_active",
  fileName: "gorgoneion-phase0-blockers.md",
  content: """
# Phase 0 Blockers & Workarounds

## Blocker 1: Stereotomy API Change
**Status**: RESOLVED
**Impact**: Could not validate KEV signatures
**Workaround**: Updated to use new KeyEventProcessor API
**Resolution**: Stereotomy 0.0.6 includes new API
**PR Reference**: https://...

## Blocker 2: Fireflies Byzantine Cut Calculation
**Status**: INVESTIGATING
**Impact**: Cannot determine 3f+1 set
**Current Work**: Looking at membership coordinator
**Next Steps**: Ask jdoe for clarification on API
"""
)
```

### Checkpoints (Session Records)

**When to create**:
- End of each work session
- Completion of logical work unit
- When switching contexts
- Before taking extended break

**File location**: `.pm/checkpoints/YYYYMMDD-HHMM-[bead-id].md`

---

## Quality Gates

### Phase 0 Gate (Before Phase 1)

```
✓ All 4 security issues closed
✓ Code review: security team approved
✓ Tests: 100% of critical paths
✓ Integration: Stereotomy + Fireflies tests passing
✓ Regression: No failures in existing tests
✓ Architecture: Security design reviewed
```

### Phase 1 Gate (Before Phase 2)

```
✓ All 4 design issues closed
✓ Code review: architecture team approved
✓ Tests: 95%+ overall coverage
✓ Integration: Full cross-module tests passing
✓ State machine: No violations detected
✓ Performance: Baseline metrics established
```

### Phase 2 Gate (Before Phase 3)

```
✓ 29 quality items closed
✓ Code review: all feedback addressed
✓ Tests: 95%+ critical, 85%+ overall
✓ Documentation: All public APIs documented
✓ Static analysis: Clean code metrics
✓ Performance: 15%+ improvement verified
```

### Phase 3 Gate (Completion)

```
✓ Full integration tests passing
✓ Protocol compliance verified
✓ Production readiness checklist complete
✓ Team training complete
✓ Stakeholder sign-off obtained
✓ Deployment plan documented
```

---

## Escalation Procedures

### When Blocked

1. **Document blocker**
   - What is blocking you?
   - When did you discover it?
   - How does it impact the issue?

2. **Try workarounds**
   - Can you work around it?
   - Does workaround have side effects?
   - Document in Memory Bank

3. **Escalate**
   - Update bead with blocker flag
   - Post in team channel
   - Schedule discussion with owner

4. **Alternative approach**
   - If blocker is fundamental
   - Might dependency order be different?
   - Can issue be split?

### When Unclear

1. **Check documentation**
   - Module README
   - Code comments
   - CLAUDE.md

2. **Check existing tests**
   - How is the API used?
   - What patterns are established?

3. **Ask architect**
   - Schedule design discussion
   - Use architecture office hours
   - Post in design channel

---

## Daily Discipline

### Morning (5 minutes)

```bash
bd ready              # See unblocked work
bd show [bead-id]    # Pick next issue
bd update [bead-id] --status in_progress
```

### During Day (Continuous)

- Write test first
- Implement minimal fix
- Run tests frequently
- Create checkpoints at logical stops
- Document learnings

### Before Merge (15 minutes)

```bash
./mvnw clean install -Dlarge_tests=true  # Full tests
# Code review
git commit -m "..."
bd close [bead-id]
```

### End of Day (5 minutes)

- Save session state: `/check`
- Update checkpoint with progress
- Note next actions in bead
- Update blockers

---

## Continuous Improvement

### Weekly Review

1. What went well?
2. What was hard?
3. What can we improve?
4. Update methodology if needed

### Phase Retrospective

1. What did we learn?
2. How does this inform next phase?
3. Methodology adjustments needed?
4. Update METHODOLOGY.md

---

**Methodology Established**: 2026-01-08
**Status**: READY FOR USE
**Review Cadence**: Weekly team review, phase retrospective at phase completion
