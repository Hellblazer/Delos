# Delos Fireflies Remediation - Engineering Methodology

This document defines the engineering discipline, workflow patterns, and quality standards for this project.

## Core Principles

### 1. Test-First Development
- **Advance only on validated code** - No changes without passing tests
- **Write tests before fixes** - Understand failure modes first
- **Integration testing mandatory** - Critical path tests with `mvn clean install -Dlarge_tests=true`
- **Coverage target**: 95%+ for critical membership paths

### 2. Systematic Knowledge
- **ChromaDB for permanent findings** - Research, decisions, patterns
- **Memory Bank for session work** - In-progress state, agent handoffs, temp notes
- **Beads for task tracking** - Never markdown TODOs; use `bd` for everything
- **Documentation by default** - Record learnings, hypotheses, decisions in real-time

### 3. Delegated Parallel Work
- **Spawn agents at top level** - Don't do subtask work directly
- **Agent pipeline**: strategic-planner → plan-auditor → java-architect-planner → java-developer → code-review-expert
- **Standard handoffs**: Include task context, bead IDs, input artifacts, expected output, quality criteria
- **No sequential work on trivial tasks** - Use concurrent agents for non-dependent work

### 4. Git Discipline
- **NO AI attribution in commits** - Company policy. Use professional technical language.
- **Reference beads in commit messages** - Link changes to tracked issues: "Fix ring election [fireflies-123]"
- **Atomic commits** - One logical change per commit
- **Branch strategy**: Work on feature branches, PR to main with code review

## Workflow Patterns

### Daily Standup
1. Run `bd ready` - identify unblocked work
2. Check EXECUTION_STATE.md - any new blockers?
3. Review RISK_REGISTER.md - any risks escalated?
4. Update checkpoint for previous day (if applicable)
5. Plan today's focus - max 3 parallel work streams

### Starting an Issue
```
1. Bead created with clear description and file:line references
2. Search ChromaDB for prior art on similar issues
3. Create hypothesis in .pm/hypotheses/ if design decision needed
4. Write test case that demonstrates the bug/requirement
5. Implement fix
6. Verify all tests pass (unit + integration)
7. Document learning in .pm/learnings/
8. Close bead and link to checkpoint
```

### Code Review Checklist
- Tests pass (unit + integration)
- Code follows project patterns (Java 23+ with var, no synchronized, modern patterns)
- Critical path coverage >= 95%
- No regressions in existing functionality
- Documentation updated (code comments + architecture docs)
- Bead references correct in commit message

### Checkpoint Creation (Daily or Per-Task)
Every work session ends with a checkpoint file:
```
.pm/checkpoints/YYYYMMDD-HH{MM}-{issue-summary}.md
```

Contains:
- Context (what issue being worked)
- Completed work (what was done)
- Decisions made (trade-offs, alternatives considered)
- Blockers encountered (what blocked progress)
- Next actions (what's next)
- Metrics update (if applicable)
- Files modified

### Agent Handoff Format
All delegations follow this structure:

```markdown
## Handoff: [Agent Name]

**Task**: [1-2 sentence summary of what needs doing]
**Bead**: [ID] (status: [status])

### Input Artifacts
- ChromaDB: [document IDs, e.g., "analysis::fireflies-module-2025-01-01" or "none"]
- Memory Bank: [file path, e.g., "Delos_active/phase0.md" or "none"]
- Files: [key files to examine, e.g., "fireflies/src/main/java/com/hellblazer/delos/fireflies/ViewContext.java"]

### Deliverable
[Specific output expected, e.g., "Fixed Ethereal signature validation with 100% test coverage"]

### Quality Criteria
- [ ] All tests pass (unit + integration)
- [ ] Code review completed
- [ ] ChromaDB decision document created
- [ ] Next phase bead created
```

## Knowledge Management

### ChromaDB Usage

**Store permanent findings** using this pattern:

```bash
mcp__chromadb__search_similar(
  query: "ethereal signature validation fireflies",
  num_results: 10
)

mcp__chromadb__create_document(
  document_id: "decision::fireflies::signature-validation-v1",
  content: "Decision rationale, trade-offs, implementation approach...",
  metadata: {
    "component": "fireflies",
    "type": "decision",
    "phase": 0,
    "status": "implemented",
    "date_created": "2026-01-01"
  }
)
```

**Metadata naming conventions**:
- `research::{topic}` - Research findings
- `decision::{component}` - Architectural decisions
- `pattern::{name}` - Reusable patterns
- `debug::{issue}` - Debugging insights
- `critique::{component}` - Code review findings

### Memory Bank Usage

**Store session work** using this pattern:

```bash
mcp__allPepper-memory-bank__memory_bank_write(
  projectName: "Delos_active",
  fileName: "phase0-work.md",
  content: "Session notes, active hypotheses, blockers, agent handoffs..."
)
```

**File naming**: `{phase}-work.md`, `blockers.md`, `handoffs.md`

## Testing Strategy

### Test Coverage Goals
- **Critical membership paths**: >= 95%
- **Byzantine tolerance scenarios**: 100%
- **Recovery protocols**: >= 90%
- **Failure modes**: >= 85%

### Test Organization
```
fireflies/src/test/java/
├── com/hellblazer/delos/fireflies/
│   ├── MembershipStateTest.java         ← Membership logic
│   ├── RingCommunicationTest.java       ← Ring topology
│   ├── ElectionTest.java                ← Ring election
│   ├── FailureRecoveryTest.java         ← Recovery logic
│   ├── EtherealConsensusTest.java       ← Consensus validation
│   └── BootstrapValidationTest.java     ← Bootstrap logic
```

### Test Execution
```bash
# Single module
./mvnw test -pl fireflies

# Single test class
./mvnw test -Dtest=ViewContextTest

# Single test method
./mvnw test -Dtest=ViewContextTest#testMembershipUpdate

# Full integration tests (resource-intensive)
./mvnw clean install -Dlarge_tests=true
```

## Code Quality Standards

### Java Style (Java 23+)
```java
// GOOD: Modern patterns with var
var members = viewContext.getMembers();
var updated = members.stream()
  .filter(m -> m.isActive())
  .map(Member::getId)
  .toList();

// BAD: Old style with explicit types
List<Member> members = viewContext.getMembers();

// GOOD: No synchronized - use concurrent collections
var members = new ConcurrentHashMap<String, Member>();

// BAD: Old synchronized patterns
synchronized(memberMap) { ... }

// GOOD: Floating point only for neural networks with f suffix
float[] weights = new float[100];
float value = 0.5f;

// BAD: Double for non-NN code
double[] weights = new double[100];
```

### Commit Message Format
```
<type>: <short description> [<bead-id>]

<longer explanation of change>

Type: bug|feature|refactor|test|docs|chore
Bead: fireflies-123
Related: fireflies-456, fireflies-789
```

Example:
```
fix: Ethereal signature validation logic [fireflies-p0-001]

Complete missing validation check in consensus message verification.
Add test coverage for invalid signature scenarios.

Bead: fireflies-p0-001
Related: fireflies-p0-002
```

## Rollback Protocol

**Added per substantive-critic audit recommendation**

### Feature Flags for Phase 0 Fixes
Each Phase 0 fix should be behind a feature flag until full phase validates:

```java
// Example: Signature validation fix
if (FeatureFlags.SIGNATURE_VALIDATION_V2.isEnabled()) {
    validateSignatureV2(message);  // New implementation
} else {
    validateSignature(message);    // Old implementation
}
```

### Rollback Decision Criteria
If a Phase 0 fix fails success criteria:
1. **Disable feature flag immediately** - revert to old behavior
2. **Root cause analysis** - document failure in .pm/learnings/
3. **Re-plan** - create new bead for corrected approach
4. **Do NOT block other Phase 0 work** - continue parallel tracks

### Rollback Test
Before merging any Phase 0 fix:
1. Enable feature flag → run full test suite → verify pass
2. Disable feature flag → run full test suite → verify pass (old behavior)
3. Toggle flag under load → verify no inconsistent state

## Risk Management

See RISK_REGISTER.md for:
- Known risks and mitigation strategies
- Blockers and escalation paths
- Assumptions and validation criteria

## Escalation Paths

### Design Questions
- **Escalate to**: java-architect-planner (opus)
- **When**: Architecture decision needed, trade-offs between approaches
- **Provide**: Current hypothesis, alternatives evaluated, recommendation

### Code Review Concerns
- **Escalate to**: code-review-expert (sonnet)
- **When**: Code review fails, test coverage insufficient, regression risk
- **Provide**: Failing tests, coverage report, regression evidence

### Integration Issues
- **Escalate to**: java-debugger (opus)
- **When**: Integration test failures, complex debugging needed
- **Provide**: Test failure logs, hypothesis on root cause

### Testing Validation
- **Escalate to**: test-validator (sonnet)
- **When**: Test strategy unclear, coverage gaps, test quality concerns
- **Provide**: Current test scope, coverage report, quality questions

## Session Management

### Before Compacting
1. Save state: `/check` - saves CONTINUATION.md, hypothesis state, active blockers
2. Update checkpoint in `.pm/checkpoints/`
3. Push changes: `git add .pm/ && git commit -m "pm: session checkpoint [date]"`
4. Document blockers in EXECUTION_STATE.md if any emerged

### When Resuming
1. Load state: `/load` - restores previous session context
2. Read CONTINUATION.md - refresh on current status
3. Run `bd ready` - see unblocked work
4. Check EXECUTION_STATE.md - any new blockers?
5. Resume from last checkpoint

## Quality Gates

### Before Merging to Main
- [ ] All tests pass (unit + integration)
- [ ] Code review approved
- [ ] Coverage >= targets (95% critical paths)
- [ ] No regressions in existing functionality
- [ ] ChromaDB decision document created (if architecture decision)
- [ ] CONTINUATION.md updated
- [ ] Checkpoint created and linked
- [ ] Bead marked closed with reference to merge commit

### Before Phase Completion
- [ ] All phase issues closed with test evidence
- [ ] Phase retrospective completed
- [ ] Metrics updated and validated
- [ ] Next phase beads created and ready
- [ ] Lead architect sign-off on quality

## Tools & Commands Reference

```bash
# Beads task tracking
bd ready                        # Unblocked work
bd create "Title" -t feature -p 1
bd update <id> --status in_progress
bd close <id>
bd dep add <id> <blocker-id>
bd list --status=in_progress
bd show <id>

# Session management
/check                          # Save state
/load                           # Resume state
/sessions                       # List saved sessions

# Build & test
./mvnw clean install            # Full build
./mvnw test -pl fireflies       # Single module
mvn test -Dtest=Class           # Single test class
mvn test -Dtest=Class#method    # Single test method
./mvnw clean install -Dlarge_tests=true  # Full integration tests

# Git workflow
git status
git add <files>
git commit -m "message"
git push origin <branch>
git log --oneline | head -10

# ChromaDB
mcp__chromadb__search_similar(query, num_results=10)
mcp__chromadb__create_document(document_id, content, metadata)
mcp__chromadb__update_document(document_id, content)

# Memory Bank
mcp__allPepper-memory-bank__memory_bank_read(projectName, fileName)
mcp__allPepper-memory-bank__memory_bank_write(projectName, fileName, content)
mcp__allPepper-memory-bank__memory_bank_update(projectName, fileName, content)
```

---

**Last Updated**: 2026-01-01
**Version**: 1.0
**Applicable To**: All phases
