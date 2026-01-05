# Delos Fireflies Remediation - Agent Instructions

Instructions for agents spawned to work on Delos Fireflies remediation tasks.

## Context Protocol

### RECEIVE (Before Starting Work)

When handed a task, verify you have received:

1. **Bead Context**
   - `bd show <id>` for task context
   - Check design field for rationale
   - Verify dependencies are met

2. **Project Infrastructure**
   - Read `.pm/CONTINUATION.md` for current status
   - Read `.pm/EXECUTION_STATE.md` for metrics and blockers
   - Read `.pm/METHODOLOGY.md` for discipline

3. **Knowledge Base**
   - Search ChromaDB: `analysis::codebase-deep-analyzer::fireflies-module-2025-01-01`
   - Search ChromaDB: `critique::fireflies::deep-analysis-2026-01-01`
   - Check Memory Bank: `Delos_active/phase*-work.md` for active hypotheses

4. **Input Artifacts**
   - Verify you received bead ID(s) with status
   - Check ChromaDB document references (prior work)
   - Confirm Memory Bank location (session state)
   - Identify key files to examine

### PRODUCE (What to Deliver)

**All deliverables must include**:

1. **Code Changes**
   - Implementation of the fix/feature
   - Test cases demonstrating the fix
   - All tests passing (unit + integration)

2. **Documentation**
   - Code comments for complex logic
   - Architecture decision document in ChromaDB (if design decision)
   - Updated RISK_REGISTER.md if risks discovered

3. **Knowledge Artifacts**
   - ChromaDB document: `decision::fireflies::issue-summary`
   - Memory Bank session notes: `Delos_active/phase*-work.md`
   - Checkpoint file: `.pm/checkpoints/YYYYMMDD-HHMM-issue-summary.md`

4. **Task Tracking**
   - Update bead status to `in_progress` when starting
   - Update bead with findings/decisions before closing
   - Close bead when complete: `bd close <id>`
   - Reference bead in commit message

### HANDOFF (To Next Agent)

When delegating or handing off, use this format:

```markdown
## Handoff: [Target Agent Name]

**Task**: [1-2 sentence summary of what needs doing]
**Bead**: [ID] (status: [status])

### Input Artifacts
- ChromaDB: [document IDs, e.g., "analysis::fireflies-module-2025-01-01" or "none"]
- Memory Bank: [file path, e.g., "Delos_active/phase0.md" or "none"]
- Files: [key files to examine]

### Deliverable
[Specific expected output, e.g., "ViewContext.java refactored with 100% test coverage"]

### Quality Criteria
- [ ] All tests pass (unit + integration)
- [ ] Code review completed
- [ ] ChromaDB decision document created (if design decision)
- [ ] No regressions in existing functionality
- [ ] Next bead created and ready for successor
```

## Agent-Specific Roles

### java-architect-planner (Opus)

**Responsibilities**:
- Design architecture for complex issues
- Evaluate trade-offs between approaches
- Validate safety guarantees
- Review for Byzantine fault tolerance
- Design test strategy

**When to request**:
- Architecture decision needed
- Safety/correctness concern
- Complex multi-module refactoring

**Handoff provides**:
- Architecture decision document
- Design rationale and trade-offs
- Test strategy outline
- Bead for implementation work

**Expects in return**:
- Implementation status updates
- Test results
- Code review feedback
- Risk assessment

---

### java-developer (Sonnet)

**Responsibilities**:
- Implement fixes and features
- Write test cases
- Follow Java patterns (Java 23+, var, no synchronized)
- Ensure test coverage >= targets
- Commit with proper bead references

**When to request**:
- Implementation work
- Test case development
- Bug fixes
- Code refactoring

**Handoff provides**:
- Detailed task description with file:line references
- Architecture decision (if applicable)
- Test strategy
- List of files to modify

**Expects in return**:
- Working code with tests
- Test results summary
- Checkpoint file
- Code ready for review

---

### code-review-expert (Sonnet)

**Responsibilities**:
- Review code for quality and correctness
- Verify test coverage >= targets
- Check for regressions
- Validate Byzantine tolerance implications
- Approve merge to main

**When to request**:
- Code review before merge
- Coverage analysis
- Test quality assessment
- Regression risk evaluation

**Handoff provides**:
- Code changes (branch ready for review)
- Test coverage report
- Change summary
- Risk assessment

**Expects in return**:
- Approved/rejected status
- Feedback for fixes (if needed)
- Sign-off for merge

---

### test-validator (Sonnet)

**Responsibilities**:
- Validate test coverage
- Design test scenarios
- Verify critical path tests
- Assess test quality
- Recommend additional tests

**When to request**:
- Test strategy validation
- Coverage analysis
- Test case design
- Test quality concerns

**Handoff provides**:
- Current test scope
- Coverage report
- Quality questions
- Failing test cases (if any)

**Expects in return**:
- Test coverage report
- Recommendations
- Validation sign-off

---

### java-debugger (Opus)

**Responsibilities**:
- Debug complex failures
- Root cause analysis
- Integration test investigation
- Performance profiling
- Failure mode analysis

**When to request**:
- Integration test failures
- Complex debugging needed
- Root cause investigation
- Performance issues

**Handoff provides**:
- Failing test case
- Expected vs actual behavior
- Reproduction steps
- Test logs

**Expects in return**:
- Root cause identified
- Fix recommendation
- Test case validating fix

---

### deep-analyst (Opus)

**Responsibilities**:
- Analyze codebase for issues
- Find root causes
- Evaluate design alternatives
- Risk assessment
- Pattern identification

**When to request**:
- Deep analysis needed
- Pattern investigation
- Design alternatives
- Risk evaluation

**Handoff provides**:
- Issue description
- Suspected root cause
- Initial analysis
- Questions to investigate

**Expects in return**:
- Detailed analysis
- Root cause identified
- Recommendations
- ChromaDB analysis document

---

## Project Context (Quick Reference)

**Module**: Fireflies - Byzantine fault-tolerant membership service
**Location**: `/Users/hal.hildebrand/git/Delos/fireflies`
**Key Classes**:
- `ViewContext.java` - Core membership state machine
- `Fireflies.java` - Service implementation
- Ring communication patterns, election protocols

**Build**:
```bash
./mvnw clean install                      # Full build
./mvnw test -pl fireflies                 # Test module
./mvnw clean install -Dlarge_tests=true   # Full integration tests
```

**Test Location**: `fireflies/src/test/java`
**Coverage Target**: 95%+ for critical paths

**Key Issues** (Phase 0 - Critical):
1. Ethereal consensus signature validation
2. Membership update atomicity
3. Ring communication state consistency
4. Failure recovery protocol
5. Ring election consensus bug
6. Service bootstrap validation
7. GRPC connection state management
8. Rate limiting edge cases

**Known Risks**:
- Byzantine tolerance guarantee impacts
- State consistency under failures
- Consensus validation correctness
- See RISK_REGISTER.md for details

## Code Quality Standards

### Java 23+ Patterns
```java
// USE: var with modern patterns
var members = viewContext.getMembers();
var count = members.stream().filter(m -> m.isActive()).count();

// DON'T: Old explicit types
List<Member> members = viewContext.getMembers();

// USE: Concurrent collections (no synchronized)
var map = new ConcurrentHashMap<String, Member>();

// DON'T: Synchronized blocks
synchronized(map) { ... }

// USE: float only for neural networks
float[] weights = new float[100];
float val = 0.5f;

// DON'T: Double for non-neural code
double[] weights = new double[100];
```

### Testing Requirements
```java
// REQUIRED: Demonstrating the bug
@Test
void testMembershipUpdateAtomicity() {
  // Test setup
  var context = new ViewContext(...);

  // Demonstrate the bug or requirement
  context.updateMembership(members);

  // Verify the fix
  assertThat(context.getMembers()).isEqualTo(expected);
}

// REQUIRED: Byzantine tolerance scenarios
@Test
void testRecoveryUnderByzantineFailure() {
  // Inject failure
  // Verify recovery
}
```

### Commit Message Format
```
<type>: <short description> [<bead-id>]

<longer explanation>

Type: bug|feature|refactor|test|docs
Bead: <id>
Related: <other-ids>
```

Example:
```
fix: Ethereal signature validation logic [fireflies-p0-001]

Complete missing validation check for invalid signatures.
Add coverage for edge cases in validation.

Bead: fireflies-p0-001
Related: fireflies-p0-002
```

## Workflow Checklist

### Starting Work on Bead
- [ ] `bd show <id>` - understand task
- [ ] Read CONTINUATION.md - project status
- [ ] Search ChromaDB for prior art
- [ ] `bd update <id> --status in_progress`
- [ ] Read related architecture docs

### During Implementation
- [ ] Write test first (test-driven development)
- [ ] Implement fix
- [ ] All tests pass (unit + integration)
- [ ] Check coverage: `mvn coverage` or equivalent
- [ ] Code review quality check
- [ ] Update code comments/docs
- [ ] Run full integration tests: `mvn clean install -Dlarge_tests=true`

### Before Completing
- [ ] Create checkpoint file: `.pm/checkpoints/YYYYMMDD-HHMM-<summary>.md`
- [ ] Create ChromaDB document (if design decision)
- [ ] Update Memory Bank: `Delos_active/phase*-work.md`
- [ ] Verify all tests pass
- [ ] No regressions in existing functionality
- [ ] Code ready for review

### After Completion
- [ ] Commit with bead reference: `git commit -m "fix: description [<bead-id>]"`
- [ ] Push branch: `git push origin <branch>`
- [ ] Create PR or mark for merge review
- [ ] `bd close <id>` - close the bead
- [ ] Reference PR/commit in bead notes
- [ ] Update EXECUTION_STATE.md metrics (if applicable)

## Knowledge Management

### ChromaDB (Permanent Storage)
```bash
# Search for prior art
mcp__chromadb__search_similar(
  query: "issue name",
  num_results: 10
)

# Store findings
mcp__chromadb__create_document(
  document_id: "decision::fireflies::issue-summary",
  content: "Decision rationale, trade-offs, implementation...",
  metadata: {
    "component": "fireflies",
    "type": "decision",
    "phase": 0,
    "status": "implemented",
    "date": "2026-01-01"
  }
)
```

### Memory Bank (Session Work)
```bash
# Store session notes, active work
mcp__allPepper-memory-bank__memory_bank_write(
  projectName: "Delos_active",
  fileName: "phase0-work.md",
  content: "Session notes, hypotheses, findings..."
)
```

## Support & Escalation

### Questions?
1. Check CONTINUATION.md - quick status
2. Check RISK_REGISTER.md - known risks
3. Search ChromaDB for prior decisions
4. Check Memory Bank for active session notes
5. Check METHODOLOGY.md for discipline guidelines

### Blockers?
1. Document in EXECUTION_STATE.md
2. Update bead with blocker
3. Escalate to java-architect-planner if design decision
4. Escalate to java-debugger if complex failure

### Code Review Issues?
1. Escalate to code-review-expert
2. Address feedback
3. Resubmit for approval
4. Close bead only after approval

---

**Last Updated**: 2026-01-01
**Version**: 1.0
**Questions**: See METHODOLOGY.md or CONTINUATION.md
