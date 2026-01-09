# Agent Coordination & Delegation Instructions

**Project**: Delos Gorgoneion Security & Quality Remediation
**Discipline**: Parallel agent execution with strict coordination
**Version**: 1.0
**Effective Date**: 2026-01-08

---

## Quick Start for Agents

### Context Available

When spawning agents, they will automatically load:
- `.pm/` directory structure (all project metadata)
- `CONTINUATION.md` (current state and phase)
- `EXECUTION_STATE.md` (metrics and blockers)
- `METHODOLOGY.md` (engineering discipline)
- Relevant phase file (e.g., `phases/phase-0-critical-security.md`)
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

**Special Instructions for Gorgoneion**:
- Security-critical code - extra careful on crypto operations
- Byzantine assumptions - verify state consistency
- Stereotomy integration - run integration tests
- Fireflies integration - test with BFT scenarios
- Tests required: Unit + Integration + Byzantine

**Example Handoff**:
```
## Handoff: code-review-expert

**Task**: Review cryptographic validation fixes for Phase 0
**Bead**: Delos-XXXX (status: in_progress → ready for review)

### Input Artifacts
- Files: Gorgoneion.java (lines 45-120), GorgoneionTest.java
- Tests: 12 new tests covering crypto operations
- Commits: 3 commits with detailed messages

### Deliverable
Approved code ready to merge or feedback for fixes

### Quality Criteria
- [ ] All cryptographic operations validated
- [ ] Tests cover all code paths
- [ ] No regressions in other modules
- [ ] Stereotomy integration verified
```

### code-review-expert (Code Review)

**Trigger**: After implementation complete, before merging

**Scope**: All phases

**Review Focus**:
- Phase 0 (Security): Crypto operations, input validation, error handling
- Phase 1 (Design): Architecture, state consistency, dependencies
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
- [ ] Cryptographic operations correct
- [ ] Input validation comprehensive
- [ ] Error handling secure (fail closed)
- [ ] No unsafe type casts or operations
- [ ] Tests cover crypto paths
- [ ] Stereotomy integration correct
- [ ] Fireflies integration correct
- [ ] No regressions detected

**Phase 1 Checklist**:
- [ ] Follows Gorgoneion architecture
- [ ] State machine correct
- [ ] No circular dependencies
- [ ] Race conditions prevented
- [ ] Error recovery complete
- [ ] Byzantine assumptions valid

**Phase 2 Checklist**:
- [ ] Code readable and maintainable
- [ ] Names descriptive
- [ ] Complexity reasonable
- [ ] Tests comprehensive
- [ ] Documentation complete
- [ ] Performance acceptable

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
- Overall coverage: 85%+
- Critical path coverage: 95%+
- Public API coverage: 95%+
- Error path coverage: 95%+

**Test Quality Checks**:
- [ ] Unit tests in place
- [ ] Integration tests in place (Stereotomy, Fireflies)
- [ ] Byzantine tests in place (Phase 0-1)
- [ ] Edge cases covered
- [ ] Error paths tested
- [ ] Performance tests adequate

**Example Handoff**:
```
## Handoff: [Merge]

**Task**: Approval to merge after coverage validation
**Bead**: Delos-XXXX (status: ready for merge)

### Coverage Results
- Overall: 87% (target: 85%) ✓
- Critical: 96% (target: 95%) ✓
- Public API: 94% (target: 95%) ⚠️ [minor gap in error handling]
- Error paths: 92% (target: 95%) ⚠️ [2 error paths untested]

### Recommendation
APPROVED with minor coverage gaps documented.
Next iteration can address gaps if needed.

### Quality Criteria
- [x] Coverage targets met
- [x] Test quality adequate
- [x] No test anti-patterns
- [x] Performance tests adequate
```

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

**Special for Gorgoneion**:
- Byzantine failures hard to reproduce - use systematic testing
- Timing issues common - look for synchronization problems
- State consistency issues - check for race conditions
- Stereotomy interactions - verify key event processing

**Example Trigger**:
```
Test: CryptoValidationTest#shouldRejectInvalidSignatures
Status: FLAKY (passes 7/10 times)
Impact: Blocking Phase 0 completion

Issue: Race condition in signature validation under concurrent access
```

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

**Gorgoneion Architecture Areas**:
- Service bootstrap and initialization
- Stereotype/Fireflies integration points
- State machine design
- Error recovery mechanisms
- Byzantine-tolerant consensus
- Key rotation and management

**Example Handoff**:
```
## Handoff: java-developer

**Task**: Implement dependency injection for Gorgoneion components
**Bead**: Delos-5555 (status: designed → in_progress)

### Design Specification
- Document: decision::gorgoneion::dependency-injection (in ChromaDB)
- Components to inject:
  - StereotomyProvider (KERI operations)
  - FirefliesProvider (Membership service)
  - MetricsCollector (Performance tracking)
- Injection approach: Constructor injection with factory
- Configuration: Via Parameters class
- Testing: Mock providers for unit tests, real providers for integration

### Implementation Approach
1. Create ComponentFactory class
2. Add injection constructors to Gorgoneion
3. Update Parameters for configuration
4. Create integration test with real components
5. Verify Stereotomy/Fireflies still work

### Quality Criteria
- [ ] All components injectable
- [ ] Testable with mocks
- [ ] Backward compatible
- [ ] Documentation updated
```

### strategic-planner (Project Planning)

**Trigger**: Phase planning, scope clarification, risk assessment

**Scope**: Overall project planning and phase breakdown

**Workflow**:
1. **Receive**: Planning request or phase scope
2. **Analyze**: Requirements, dependencies, risks
3. **Plan**: Phase breakdown with dependencies
4. **Create**: Phase files with detailed requirements
5. **Document**: In `.pm/phases/` directory
6. **Handoff**: Phase plan to team with bead structure

**Example Handoff**:
```
## Handoff: Team

**Task**: Begin Phase 0 - Critical Security Implementation
**Bead**: Delos-XXXX (Phase 0 Epic, status: ready)

### Phase Scope
Document: phases/phase-0-critical-security.md

### Dependencies & Order
1. Cryptographic Validation (CRIT-1) - foundation
2. Authentication Bypass (CRIT-2) - depends on CRIT-1
3. Key Management (CRIT-3) - parallel with CRIT-2
4. Protocol Enforcement (CRIT-4) - depends on CRIT-2 + CRIT-3

### Beads to Create
- Delos-1001: CRIT-1 Cryptographic Validation (P0)
- Delos-1002: CRIT-2 Authentication Bypass (P0) [dep: 1001]
- Delos-1003: CRIT-3 Key Management (P0)
- Delos-1004: CRIT-4 Protocol Enforcement (P0) [dep: 1002, 1003]
- Delos-1005: Phase 0 Epic (P0)

### Success Criteria
[See EXECUTION_STATE.md for gate criteria]
```

---

## Common Workflows

### Parallel Security Fixes (Phase 0)

```
Team Setup:
- Developer 1: CRIT-1 (Cryptographic Validation)
- Developer 2: CRIT-2 (Authentication Bypass) [blocked on CRIT-1]
- Developer 3: CRIT-3 (Key Management)

Parallelization:
- CRIT-1 is sequential (foundation)
- CRIT-2 blocked until CRIT-1 complete
- CRIT-3 can start immediately (independent)
- CRIT-4 starts after CRIT-2 + CRIT-3

Testing:
- Each developer: Unit + Integration + Byzantine tests
- Code review: Security-focused
- Merge: After code review + test validation
```

### Quality Improvements (Phase 2)

```
Team Setup:
- QA Lead: Code coverage gap identification
- Developers: Fix coverage gaps in parallel
- Docs Lead: API documentation
- Test Lead: Integration test enhancements

Parallelization:
- Coverage fixes are mostly independent
- Can be grouped by component or file
- Documentation can be written in parallel
- Integration tests enhanced in parallel

Testing:
- Each fix: Unit test demonstrating improvement
- Coverage validation: After each fix
- Documentation review: Before merge
- Integration validation: Weekly
```

### Integration Checkpoints

```
After Each Phase:
1. Full build: ./mvnw clean install
2. Stereotomy tests: ./mvnw test -pl gorgoneion,stereotomy
3. Fireflies tests: ./mvnw test -pl gorgoneion,fireflies
4. Large test suite: ./mvnw clean install -Dlarge_tests=true
5. Code coverage report
6. Performance metrics
7. Gate approval: All criteria met?
```

---

## Knowledge Management Handoff

### Storing Architectural Decisions

When an architecture decision is made, persist it:

```bash
mcp__chromadb__create_document(
  document_id: "decision::gorgoneion::issue-name",
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
    "component": "gorgoneion",
    "type": "decision",
    "phase": "0 or 1",
    "date": "2026-01-XX",
    "author": "agent-name"
  }
)
```

### Storing Research Findings

For research-based work:

```bash
mcp__chromadb__create_document(
  document_id: "research::gorgoneion::topic",
  content: """
# Research: [Topic]

## Question
[What was researched?]

## Findings
- Finding 1
- Finding 2

## Sources
[Where did info come from?]

## Application to Gorgoneion
[How does this apply?]

## Next Steps
[What to do next?]
""",
  metadata: {
    "component": "gorgoneion",
    "type": "research",
    "topic": "topic-name",
    "date": "2026-01-XX"
  }
)
```

### Session State Handoff

When handing off mid-phase work:

```bash
mcp__allPepper-memory-bank__memory_bank_write(
  projectName: "Delos_active",
  fileName: "gorgoneion-phase0-status.md",
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

## Parallel Execution Model

### Phase 0 Parallelization

```
Week 1:
  CRIT-1: Dev1 (CRIT-1 implementation) → code-review → test-validator → merge
    ↓ (needed for CRIT-2)
  CRIT-2: Dev2 (CRIT-2 implementation) → code-review → test-validator → merge
  CRIT-3: Dev3 (CRIT-3 implementation in parallel) → code-review → merge

Week 2:
  CRIT-4: Dev2/3 (CRIT-4 implementation) → code-review → merge
  Integration Testing: All → test-validator → gate approval
```

### Agent Parallelization

```
While Developer works on implementation:
  - code-review-expert: Reviews previous commit
  - test-validator: Validates coverage on previous
  - java-architect: Plans next phase
  - Documentation writer: Documents completed work
```

### Review & Test Parallelization

```
Implementation → Code Review (parallel with next impl)
              ↓
           Tests
              ↓
          Merge ← (while next impl continues)
```

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
  Bead: Delos-1001
  Blocker: Cannot understand Stereotomy API change
  Status: Blocked waiting for clarification
  Escalate to: stereotomy-team lead
  Workaround: Use previous API version until clarified
```

### When Scope Changes

```
1. Note in Memory Bank
2. Update bead description
3. Notify project lead
4. Adjust timeline/resources as needed
5. Document decision in ChromaDB
```

### When Risk Emerges

```
1. Document in Memory Bank blockers file
2. Assess impact on phase/timeline
3. Add to RISK_REGISTER.md
4. Create mitigation plan
5. Execute mitigation with java-architect if design-related
```

---

## Status Communication

### Daily Status

**Format**: One line per bead status
```
Delos-1001: CRIT-1 Crypto validation - IN PROGRESS (Dev1, 60% done)
Delos-1002: CRIT-2 Auth bypass - PENDING (blocked on 1001)
Delos-1003: CRIT-3 Key management - IN PROGRESS (Dev3, 40% done)
Delos-1004: CRIT-4 Protocol - PENDING (blocked on 1002, 1003)
```

### Weekly Summary

**Include**:
- Beads closed this week
- Code review status
- Test coverage achieved
- Blockers and resolutions
- Learnings discovered
- Next week's focus

### Phase Gate Status

**Before moving to next phase**:
- All beads closed for current phase
- Code review approved
- Test coverage targets met
- Integration tests passing
- Risk register updated
- Stakeholder sign-off obtained

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
- Cryptographic operations correct
- Input validation comprehensive
- Error handling secure
- No unsafe operations
- Stereotomy/Fireflies integration verified

---

**Agent Instructions Established**: 2026-01-08
**Status**: READY FOR USE
**Review Cadence**: Weekly team sync
