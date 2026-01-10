# Engineering Discipline & Methodology

**Project**: CHOAM Security & Architecture Refactoring
**Discipline Level**: STRICT (Security-critical path)
**Version**: 1.0
**Effective Date**: 2026-01-09

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

4. **Code Review** (MANDATORY - DO NOT SKIP)
   - **HARD GATE**: Code review MUST complete before pushing
   - Use `/code-review` skill immediately after implementation
   - Security focus for Phase 0 (all changes reviewed)
   - Architecture focus for Phase 1 (all changes reviewed)
   - Quality focus for Phase 2 (all changes reviewed)
   - Minimum: Review of all modified + created files
   - Critical issues must be fixed before push
   - Non-critical issues: Create follow-up beads, document in review

5. **Integration Test**
   - Full build with Ethereal dependency
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
- **Progress** - Consensus always progresses with honest majority

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
   .pm/checkpoints/YYYYMMDD-HHMM-Delos-XXXX.md
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
   ./mvnw test -pl choam -Dtest=ClassName#methodName
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
   ./mvnw test -pl choam -Dtest=ClassName#methodName
   ```

3. **Run Full Module Tests**
   ```bash
   ./mvnw test -pl choam
   ```
   - Verify no regressions in choam
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
   - Verifies Ethereal compatibility
   - Verifies Fireflies compatibility

2. **Run Ethereal Integration Tests**
   ```bash
   ./mvnw test -pl choam,ethereal -DskipTests=false
   ```
   - Verify consensus operations still work
   - Check block production

3. **Run Fireflies Integration Tests**
   ```bash
   ./mvnw test -pl choam,fireflies -DskipTests=false
   ```
   - Verify BFT membership still works
   - Check Byzantine scenarios

### Code Review Phase (15-30 minutes)

1. **Create Commit**
   ```bash
   git add -A
   git commit -m "fix: description of change

   - Point 1: What was wrong
   - Point 2: How it's fixed
   - Point 3: How it's tested

   References: Delos-XXXX"
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

## Definition of Done (MANDATORY GATES)

**NO CODE IS COMPLETE UNTIL ALL ITEMS CHECKED:**

- [ ] **Tests Written**: All new code has comprehensive tests (unit + edge cases)
- [ ] **Tests Passing**: `./mvnw clean test -pl <module>` passes 100%
- [ ] **No Regressions**: Full test suite `./mvnw clean install` passes with zero new failures
- [ ] **Code Review Completed**: `/code-review` skill executed on all modified/created files
  - Security focus (Phase 0): All changes reviewed for vulnerabilities
  - Architecture focus (Phase 1): All changes reviewed for design consistency
  - Quality focus (Phase 2): All changes reviewed for best practices
- [ ] **Critical Issues Fixed**: Any "critical" or "must fix" findings from review addressed
- [ ] **Non-Critical Issues Tracked**: Medium/low priority findings documented in follow-up beads
- [ ] **Javadocs Updated**: Public methods documented; comments explain "why" not "what"
- [ ] **Commit Message Clear**: Explains the change, rationale, and references bead ID
- [ ] **Checkpoint Updated**: Work session documented with learnings and status
- [ ] **Bead Status**: Issue marked as `in_progress` during work, ready for `closed`

**GIT PUSH GATE:**
- [ ] **All above items COMPLETE**
- [ ] **`git status` shows no unstaged changes** (everything committed)
- [ ] **Ready to execute**: `git push origin main`

**ENFORCEMENT**: If any item is unchecked, DO NOT PUSH. Create blocker bead if needed.

---

## Checkpoint Template

Create file: `.pm/checkpoints/YYYYMMDD-HHMM-Delos-XXXX.md`

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
- Multiple bullet points OK

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
fix: validate transaction signatures before processing

- Add SignatureValidator to verify transaction signatures
- Check signer authority against context membership
- Reject transactions with invalid or missing signatures
- Add 15 tests covering edge cases

Prevents unauthorized transaction processing.

References: Delos-ki6t
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
# Use: bd ready (shows only unblocked issues)
```

### Dependency Types

1. **Blocking Dependencies** - Cannot start until resolved
   - Use for critical prerequisites
   - Example: P0-1 blocks P0-3

2. **Informational Dependencies** - Should read but not blocking
   - Document in bead description
   - Example: Quality issue references Phase 0 security fix

### Phase 0 Dependency Graph

```
P0-1: Transaction Signature (Foundation)
  +-- P0-3: Sync Circuit Breaker (depends on P0-1)
  +-- P0-4: Checkpoint Race (depends on P0-1)

P0-2: Pending Queue (Independent)
```

---

## Testing Standards

### Unit Tests

**Required for**:
- Every public method
- Every private method with complex logic
- All error paths
- Edge cases and boundaries

**Standards**:
- Test class per Java class (ClassName -> ClassNameTest)
- Test method naming: `shouldDescribeExpectedBehavior`
- Arrange-Act-Assert pattern
- One logical assertion per test
- Mock external dependencies
- Clear failure messages

### Integration Tests

**Required for**:
- Ethereal interaction
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

| Category | Current | Target |
|----------|---------|--------|
| Overall | 21% | 60%+ |
| Critical paths | - | 95%+ |
| Public API | - | 90%+ |
| Error handling | - | 90%+ |

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
document_id: "decision::choam::transaction-validation"
content: """
# Transaction Validation Design

## Problem
How to validate transactions before consensus processing?

## Solution
Validate signature and signer authority at submission time.
Reject invalid transactions before they enter pending queue.

## Rationale
- Prevents DoS from invalid transactions
- Reduces consensus overhead
- Fails fast at boundary

## Tradeoffs
- Additional latency at submission
- Memory for authority lookups
"""
metadata: {
  "component": "choam",
  "type": "decision",
  "phase": "0",
  "date": "2026-01-09"
}
```

### Session Work (Memory Bank)

**When to store**:
- Active hypotheses being investigated
- Blocker analysis and workarounds
- Phase-specific findings
- Cross-agent handoff information

---

## Quality Gates

### Phase 0 Gate (Before Phase 1)

```
[] All 4 security issues closed
[] Code review: security team approved
[] Tests: comprehensive coverage of security paths
[] Integration: Ethereal + Fireflies tests passing
[] Regression: No failures in existing tests
[] Architecture: Security design reviewed
```

### Phase 1 Gate (Before Phase 2)

```
[] All 5 architecture issues closed
[] CHOAM.java reduced to ~500 lines
[] Code review: architecture team approved
[] Tests: 60%+ overall coverage
[] Integration: Full cross-module tests passing
[] State machine: No violations detected
```

### Phase 2 Gate (Completion)

```
[] All 5 enhancement items closed
[] Byzantine tests comprehensive
[] Security audit ready
[] Performance baselines documented
[] Production readiness complete
[] Team sign-off obtained
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
   - Use java-architect-planner agent
   - Schedule design discussion

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

## CHOAM-Specific Guidelines

### God Object Decomposition (Phase 1)

When extracting from CHOAM.java:

1. **Identify cohesive functionality** - Group related methods
2. **Create interface first** - Define contract before implementation
3. **Extract gradually** - One component at a time
4. **Maintain tests** - Tests should pass after each extraction
5. **Update dependencies** - Wire new components properly

### Inner Class Handling

CHOAM.java contains inner classes (Combiner, Trampoline, etc.):
- Evaluate if truly inner-class appropriate
- Extract to top-level if independently testable
- Keep as inner if tightly coupled to parent state

### Transaction Processing

```java
// Pattern for secure transaction handling
public void submit(Transaction tx) {
    // 1. Validate signature (P0-1)
    validateSignature(tx);

    // 2. Check authority
    checkSignerAuthority(tx.getSigner());

    // 3. Bounds check (P0-2)
    checkPendingQueueBounds();

    // 4. Add to pending
    pendingQueue.add(tx);
}
```

---

**Methodology Established**: 2026-01-09
**Status**: READY FOR USE
**Review Cadence**: Weekly team review, phase retrospective at phase completion
