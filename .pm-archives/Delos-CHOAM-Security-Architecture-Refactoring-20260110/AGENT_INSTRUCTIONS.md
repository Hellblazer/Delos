# Agent Coordination & Delegation Instructions

**Project**: CHOAM Security & Architecture Refactoring
**Discipline**: Parallel agent execution with strict coordination
**Version**: 1.0
**Effective Date**: 2026-01-09

---

## Quick Start for Agents

### Context Available

When spawning agents, they will automatically load:
- `.pm/` directory structure (all project metadata)
- `CONTINUATION.md` (current state and phase)
- `execution_state.json` (metrics and blockers)
- `METHODOLOGY.md` (engineering discipline)
- Relevant phase file (e.g., `PHASE_0/PLAN.md`)
- ChromaDB (prior research and decisions)
- Memory Bank `Delos_active/` (session state)

### Context Protocol

**RECEIVE**:
1. Check bead ID and description
2. Read `.pm/CONTINUATION.md` for current phase
3. Search ChromaDB for prior work on this component
4. Check Memory Bank for session state
5. Review relevant phase file

**PRODUCE**:
- Implementation or analysis delivered as specified
- Commit with bead reference: `[Delos-XXXX]`
- Update checkpoint file if implementing
- Store findings in ChromaDB if research-based

**HANDOFF**:
```
## Handoff: [Next Agent]

**Task**: [1-2 sentence summary]
**Bead**: [ID] (status: [status])

### Input Artifacts
- ChromaDB: [IDs if applicable]
- Memory Bank: [file path if applicable]
- Files: [files modified]

### Deliverable
[What the next agent should produce]

### Quality Criteria
- [ ] [Criterion 1]
- [ ] [Criterion 2]
```

---

## Agent-Specific Instructions

### java-developer (Implementation)

**Trigger**: Implementation task with plan already approved

**Scope**: Phase 0-2 implementation work

**Workflow**:
1. **Receive**: Get bead ID, read phase details, understand requirements
2. **Test-First**: Write test demonstrating issue
3. **Implement**: Make test pass with minimal changes
4. **Verify**: Run full test suite
5. **Commit**: Reference bead in message
6. **Handoff**: To code-review-expert for review

**Special Instructions for CHOAM**:
- Security-critical code - extra careful on crypto operations
- Byzantine assumptions - verify state consistency
- Ethereal integration - run consensus tests
- Fireflies integration - test with BFT scenarios
- Tests required: Unit + Integration + Byzantine

**Example Handoff**:
```
## Handoff: code-review-expert

**Task**: Review transaction signature validation fixes for Phase 0
**Bead**: Delos-ki6t (status: in_progress -> ready for review)

### Input Artifacts
- Files: CHOAM.java (lines 145-220), TransactionSignatureTest.java
- Tests: 15 new tests covering signature operations
- Commits: 2 commits with detailed messages

### Deliverable
Approved code ready to merge or feedback for fixes

### Quality Criteria
- [ ] All signature operations validated
- [ ] Tests cover all code paths
- [ ] No regressions in other modules
- [ ] Ethereal integration verified
```

### code-review-expert (Code Review)

**Trigger**: After implementation complete, before merging

**Scope**: All phases

**Review Focus**:
- Phase 0 (Security): Signature validation, input validation, error handling
- Phase 1 (Design): Architecture, component boundaries, dependencies
- Phase 2 (Quality): Code clarity, test coverage, documentation

**Workflow**:
1. **Receive**: Get bead and implementation details
2. **Examine**: Code, tests, commit messages
3. **Evaluate**: Against phase focus criteria
4. **Feedback**: Clear list of issues and approvals
5. **Verify**: Changes address feedback
6. **Approve**: Ready for merge
7. **Handoff**: To merge, or back to developer if issues

**Phase 0 Checklist**:
- [ ] Transaction signatures validated
- [ ] Queue bounds enforced
- [ ] Circuit breaker implemented correctly
- [ ] Race conditions fixed
- [ ] Error handling secure (fail closed)
- [ ] Tests cover security paths
- [ ] Ethereal integration correct
- [ ] Fireflies integration correct

**Phase 1 Checklist**:
- [ ] Component boundaries clean
- [ ] Interface design appropriate
- [ ] CHOAM.java properly decomposed
- [ ] No circular dependencies
- [ ] State machine correct
- [ ] Error recovery complete

### test-validator (Test Coverage & Quality)

**Trigger**: After code review approved, before merge

**Scope**: All phases

**Workflow**:
1. **Receive**: Get approved code and test files
2. **Measure**: Coverage metrics for changed code
3. **Analyze**: Test quality (unit, integration, Byzantine)
4. **Verify**: Coverage meets targets
5. **Report**: Coverage summary and any gaps
6. **Handoff**: Approval for merge or list of coverage gaps

**Metrics to Validate**:
- Overall coverage: 60%+ (target)
- Critical path coverage: 95%+
- Public API coverage: 90%+
- Error path coverage: 90%+

**Test Quality Checks**:
- [ ] Unit tests in place
- [ ] Integration tests in place (Ethereal, Fireflies)
- [ ] Byzantine tests in place (Phase 1-2)
- [ ] Edge cases covered
- [ ] Error paths tested
- [ ] Performance tests adequate

### java-debugger (Test Failures & Bugs)

**Trigger**: Test failure after 1-2 fix attempts, or complex bug investigation

**Scope**: Phase 0-2 issues

**Workflow**:
1. **Receive**: Failed test or bug description
2. **Investigate**: Hypothesis-driven debugging
3. **Analyze**: Root cause analysis
4. **Test**: Create minimal reproduction
5. **Fix**: Implement solution
6. **Verify**: Fix passes all tests
7. **Handoff**: Back to developer with solution

**Special for CHOAM**:
- Byzantine failures hard to reproduce - use systematic testing
- Timing issues common - look for synchronization problems
- State consistency issues - check for race conditions
- Ethereal interactions - verify consensus ordering

### java-architect-planner (Architecture & Design)

**Trigger**: Design decision needed, architecture review, phase planning

**Scope**: Phase 1 design issues, cross-phase architecture

**Workflow**:
1. **Receive**: Design question or architecture issue
2. **Analyze**: Current architecture, constraints, options
3. **Design**: Solution with tradeoffs
4. **Document**: In ChromaDB for persistence
5. **Recommend**: Implementation approach
6. **Handoff**: Design decision to java-developer for implementation

**CHOAM Architecture Areas**:
- BlockStore interface extraction
- ConsensusEngine interface design
- CHOAM god object decomposition
- Admission control integration
- Inner class extraction (Combiner, Trampoline)

**Example Handoff**:
```
## Handoff: java-developer

**Task**: Implement BlockStore interface extraction
**Bead**: Delos-XXXX (status: designed -> in_progress)

### Design Specification
- Document: decision::choam::block-store-interface (in ChromaDB)
- Interface methods:
  - store(Block block)
  - retrieve(Digest hash) -> Block
  - getLatest() -> Block
  - checkpoint(ULong height)
- Implementation: FileBlockStore (initial)
- Testing: Mock provider for unit tests

### Implementation Approach
1. Create BlockStore interface
2. Extract storage methods from CHOAM.java
3. Create FileBlockStore implementation
4. Wire into CHOAM via dependency injection
5. Update tests to use interface

### Quality Criteria
- [ ] Clean interface separation
- [ ] No behavior changes
- [ ] All existing tests pass
- [ ] New interface tests added
```

### strategic-planner (Project Planning)

**Trigger**: Phase planning, scope clarification, risk assessment

**Scope**: Overall project planning and phase breakdown

**Workflow**:
1. **Receive**: Planning request or phase scope
2. **Analyze**: Requirements, dependencies, risks
3. **Plan**: Phase breakdown with dependencies
4. **Create**: Phase files with detailed requirements
5. **Document**: In `.pm/PHASE_*/` directories
6. **Handoff**: Phase plan to team with bead structure

---

## Common Workflows

### Phase 0: Security Fix Pipeline

```
Issue Identified
     |
     v
java-developer: Write failing test
     |
     v
java-developer: Implement fix
     |
     v
code-review-expert: Security review
     |
     v (if issues)
java-developer: Address feedback
     |
     v
test-validator: Coverage validation
     |
     v
Merge & Close Bead
```

### Phase 1: Architecture Extraction Pipeline

```
Component Identified for Extraction
     |
     v
java-architect-planner: Design interface
     |
     v
java-developer: Write interface tests
     |
     v
java-developer: Extract implementation
     |
     v
code-review-expert: Architecture review
     |
     v
test-validator: Coverage + regression check
     |
     v
Merge & Close Bead
```

### Parallel Execution Model

```
Phase 0 (parallel where possible):

Week 1:
  P0-1: Dev1 (signature validation) -> code-review -> merge
  P0-2: Dev2 (queue bounds, parallel) -> code-review -> merge

Week 2:
  P0-3: Dev1 (circuit breaker, after P0-1) -> code-review -> merge
  P0-4: Dev2 (race condition, after P0-1) -> code-review -> merge
  Integration: All -> test-validator -> gate approval
```

---

## Knowledge Management Handoff

### Storing Architectural Decisions

When an architecture decision is made, persist it:

```
mcp__chromadb__create_document(
  document_id: "decision::choam::[issue-name]",
  content: """
# [Issue Name] - Architectural Decision

## Problem
[What was the problem?]

## Solution
[How was it solved?]

## Rationale
[Why this solution?]

## Tradeoffs
[What were alternatives?]

## Implementation Notes
[How to implement this]

## Risks
[What could go wrong?]
""",
  metadata: {
    "component": "choam",
    "type": "decision",
    "phase": "0 or 1 or 2",
    "date": "2026-01-XX",
    "bead": "Delos-XXXX"
  }
)
```

### Storing Research Findings

For research-based work:

```
mcp__chromadb__create_document(
  document_id: "research::choam::[topic]",
  content: """
# Research: [Topic]

## Question
[What was researched?]

## Findings
- Finding 1
- Finding 2

## Sources
[Where did info come from?]

## Application to CHOAM
[How does this apply?]

## Next Steps
[What to do next?]
""",
  metadata: {
    "component": "choam",
    "type": "research",
    "topic": "topic-name",
    "date": "2026-01-XX"
  }
)
```

### Session State Handoff

When handing off mid-phase work:

```
mcp__allPepper-memory-bank__memory_bank_write(
  projectName: "Delos_active",
  fileName: "choam-phase0-status.md",
  content: """
# Phase 0 Status - Mid-Phase Handoff

## Completed
- [Issues closed]
- [Tests passing]
- [Code review status]

## In Progress
- [Current issue]
- [Approach being taken]
- [Status/blockers]

## Next
- [Issues ready to start]
- [Blockers to resolve]
- [Next agent if needed]

## Learnings
- [Key findings]
- [Architectural insights]

## Blockers
- [Current blockers]
- [Workarounds]
- [Escalations needed]
"""
)
```

---

## Phase 0 Specific Instructions

### P0-1: Transaction Signature Validation (Delos-ki6t)

**Focus**: Ensure all transactions are cryptographically validated

**Key Files**:
- `CHOAM.java` - submit() method
- `Session.java` - transaction processing

**Test Approach**:
- Valid signature accepted
- Invalid signature rejected
- Missing signature rejected
- Wrong signer rejected
- Replay attack prevention

### P0-2: Pending Queue Bounds (Delos-k0bz)

**Focus**: Verify pending queue cannot be exploited for DoS

**Key Files**:
- `CHOAM.java` - pending queue management

**Test Approach**:
- Queue respects max size
- Overflow handling correct
- Priority handling if applicable
- DoS resistance

### P0-3: Sync Circuit Breaker (Delos-tm53)

**Focus**: Implement circuit breaker for sync operations

**Key Files**:
- `CHOAM.java` - sync operations
- `ViewAssembly.java` - view coordination

**Test Approach**:
- Circuit opens on failures
- Backoff and retry
- Recovery after cooldown
- No cascading failures

### P0-4: Checkpoint Validation Race (Delos-vud5)

**Focus**: Fix race condition in checkpoint validation

**Key Files**:
- `CHOAM.java` - checkpoint handling

**Test Approach**:
- Concurrent checkpoint operations
- Atomic state updates
- No partial checkpoints
- Recovery from interrupted checkpoints

---

## Quality Standards for Agents

### Code Quality
- All tests passing
- No regressions
- Code review approved
- Coverage targets met
- No technical debt added

### Architecture Quality
- No new circular dependencies
- Clear component boundaries
- Byzantine assumptions valid
- Error handling complete
- State machine correct

### Security Quality (Phase 0)
- Signature operations correct
- Input validation comprehensive
- Error handling secure
- No unsafe operations
- Ethereal/Fireflies integration verified

---

## Escalation Procedures

### When Agent Needs Help

```
1. Document issue clearly
2. Update bead with "blocked" status
3. Create blocker entry in Memory Bank
4. Escalate to project lead
5. Project lead determines next action

Example:
  Bead: Delos-ki6t
  Blocker: Cannot understand Ethereal consensus API
  Status: Blocked waiting for clarification
  Escalate to: ethereal-team lead
  Workaround: Mock consensus for now
```

### When Scope Changes

```
1. Note in Memory Bank
2. Update bead description
3. Notify project lead
4. Adjust timeline/resources as needed
5. Document decision in ChromaDB
```

---

**Agent Instructions Established**: 2026-01-09
**Status**: READY FOR USE
**Review Cadence**: Weekly team sync
