# Thoth Module - Agent Instructions

This file provides specific instructions for agents working on the Thoth module. All agents must follow the Context Protocol and this module-specific guidance.

## For All Agents

### Before Starting Work

1. **Read these files in order**:
   - `.pm/CONTEXT_PROTOCOL.md` (Context and standards)
   - `.pm/CONTINUATION_PROMPT.md` (Current state)
   - `CLAUDE.md` (Project-specific instructions)

2. **Verify prerequisites**:
   - [ ] Current phase identified
   - [ ] Active beads listed
   - [ ] Blockers documented
   - [ ] Required dependencies available

3. **Build baseline**:
   ```bash
   ./mvnw clean install -Ppre -DskipTests
   ./mvnw clean install -pl thoth
   ./mvnw test -pl thoth
   ```

### Bead Conventions

All beads must include these in description:
- Context: `.pm/CONTEXT_PROTOCOL.md`
- Type: bug/feature/task/epic/chore
- Priority: P0/P1/P2/P3
- Related phase (if applicable)

Example:
```
Thoth: Enhance KerlDHT rebalancing with Byzantine node tolerance

Context: .pm/CONTEXT_PROTOCOL.md
Type: Feature
Priority: P1
Phase: 2 - Core Enhancement
Related: THOTH-phase-2-epic
```

### Commit Message Format

**Required Format**:
```
[Type]: [Description]

[Detailed explanation if needed]

References: THOTH-xxx
```

**Example**:
```
Enhancement: Improve KerlDHT rebalancing for Byzantine nodes

- Add Byzantine node detection in successor selection
- Implement witness rotation during membership changes
- Add comprehensive BFT test coverage for rebalancing

References: THOTH-047
```

**Important**: Never include AI attribution (company policy violation)

## For java-developer

### Your Role

Implement features and fixes for Thoth using test-first methodology (TDD).

### Workflow

1. **Get bead context**: `bd show THOTH-xxx`
2. **Understand requirements**: Review CONTINUATION_PROMPT.md and linked hypotheses
3. **Write failing test first**:
   ```java
   @Test
   void shouldImplementFeature() {
       // Test must fail initially
   }
   ```
4. **Implement to pass**: Minimal code to make test pass
5. **Refactor**: Clean up code while keeping tests passing
6. **Run full suite**: `./mvnw test -pl thoth`
7. **Commit**: Reference bead ID
8. **Signal code-review-expert**: For >10 lines of changes

### Testing Requirements

**Unit Tests**:
- Test public API contracts
- Test error paths and exceptions
- Test Byzantine fault tolerance assumptions

**Integration Tests**:
- Test with Fireflies membership context
- Test with CHOAM state machines
- Test gossip protocol message delivery

**Byzantine FT Tests**:
- Test with f Byzantine nodes in 3f+1 setup
- Test membership change rebalancing
- Test network partition scenarios
- Test witness majority requirements

### Key Files to Know

- **KerlDht.java**: Core DHT with ring-based routing
  - Methods: lookup(), insert(), rebalance()
  - Key concern: Successor determination from digest

- **Ani.java**: KERI event validation
  - Methods: validate(), validateRoot()
  - Key concern: Cryptographic event verification

- **Maat.java**: Byzantine fault-tolerant witnessing
  - Methods: witness(), verify()
  - Key concern: Majority witness agreement

- **ReconciliationService.java**: Gossip rebalancing
  - Methods: reconcile(), migrate()
  - Key concern: KERL migration correctness

### Testing Commands

```bash
# All thoth tests
./mvnw test -pl thoth

# Single test class
./mvnw test -pl thoth -Dtest=KerlDhtTest

# Single test method
./mvnw test -pl thoth -Dtest=KerlDhtTest#shouldRebalanceAfterMembershipChange

# With longer timeout (for Byzantine tests)
./mvnw test -pl thoth -DargLine="-Dtimeout=600000"

# With verbose output
./mvnw test -pl thoth -X
```

### Handoff to Code Review

When your work is ready for review:

```
## Handoff: code-review-expert

**Task**: Review KerlDht rebalancing implementation for Byzantine FT correctness
**Bead**: THOTH-xxx (status: in_progress)

### Input Artifacts
- Files: src/main/java/com/hellblazer/delos/thoth/KerlDht.java (lines 150-250)
- Tests: src/test/java/com/hellblazer/delos/thoth/KerlDhtByzantineTest.java
- ChromaDB: decision::thoth::kerl-dht-topology

### Deliverable
Validation that:
- Byzantine fault tolerance assumptions are correct (f corrupted of 3f+1)
- KERL consistency guarantees maintained
- Test coverage adequate for new code paths
- gRPC service contract unchanged

### Quality Criteria
- [ ] No critical or high-severity findings
- [ ] All BFT test scenarios passing
- [ ] Code style follows CLAUDE.md conventions
- [ ] Documentation updated
```

## For code-review-expert

### Your Role

Validate code quality, Byzantine fault tolerance correctness, and integration compliance.

### Review Focus Areas

1. **Byzantine Fault Tolerance**:
   - [ ] Assumes 3f+1 nodes with f corrupted nodes
   - [ ] Consensus operations require majority agreement
   - [ ] Rebalancing handles Byzantine nodes
   - [ ] Witness majority enforced

2. **KERL Consistency**:
   - [ ] KERL operations are append-only
   - [ ] Immutability maintained
   - [ ] Consistency during membership changes
   - [ ] Gossip rebalancing correctness

3. **Test Coverage**:
   - [ ] New code paths covered by tests
   - [ ] Byzantine FT scenarios included
   - [ ] Integration tests where applicable
   - [ ] Performance tests for critical paths

4. **Code Quality**:
   - [ ] Modern Java patterns (var, records, etc.)
   - [ ] Proper error handling with exceptions
   - [ ] Clear variable/method naming
   - [ ] Comments for complex Byzantine logic

5. **gRPC Contract Compliance**:
   - [ ] Service interface unchanged (if breaking, document)
   - [ ] Message format compatibility
   - [ ] Error handling aligned with spec

### Key Questions to Ask

- [ ] Is Byzantine fault tolerance correctly modeled?
- [ ] Could this fail with f Byzantine nodes?
- [ ] Is KERL consistency guaranteed during rebalancing?
- [ ] Are all failure modes tested?
- [ ] Does this integrate safely with Fireflies/CHOAM?
- [ ] Is test coverage adequate (85% target)?

### Findings Format

When flagging issues:

```
**Severity**: [Critical/High/Medium/Low]
**Category**: [BFT/KERL Consistency/Testing/Code Quality/Integration]
**Finding**: [Clear description]
**Location**: [File and line numbers]
**Recommendation**: [How to fix]
**Discussion**: [Why this matters for Byzantine FT]
```

### Approval Criteria

Approve when ALL of these are true:
- [ ] No critical or high-severity findings
- [ ] Byzantine FT assumptions verified
- [ ] KERL consistency guarantees maintained
- [ ] Test coverage >= 85% for changed code
- [ ] Integration concerns addressed
- [ ] Code style follows standards

## For strategic-planner

### Your Role

Create execution plans for Thoth features and phases.

### Planning Requirements

When creating a plan for Thoth:

1. **Identify Phase**:
   - Which of 3 phases does this work affect?
   - What are the phase success criteria?
   - What milestone is this work toward?

2. **Account for BFT Complexity**:
   - Byzantine fault tolerance testing adds 30-40% overhead
   - Network partition scenarios must be tested
   - Membership change scenarios required
   - Witness rotation scenarios required

3. **Estimate Accurately**:
   - TDD adds time (write test, then code)
   - Byzantine FT testing is complex (multiply base estimate by 1.5-2x)
   - Code review cycle for critical paths
   - Integration testing with Fireflies/CHOAM

4. **Define Success Criteria**:
   - Functional requirements
   - Test coverage targets (85%+)
   - Byzantine FT guarantees
   - Performance benchmarks
   - Code review approval

5. **Identify Dependencies**:
   - Fireflies membership stable?
   - Stereotomy KERI operations available?
   - CHOAM state machine layer ready?
   - Test infrastructure in place?

### Plan Template

Include in every plan:

```
## Thoth Module Work Plan

### Phase Context
- Current Phase: [1/2/3]
- Related Epic: [THOTH-xxx]

### Success Criteria
- Functional: [List requirements]
- Quality: [Test coverage, BFT guarantees]
- Integration: [Fireflies/CHOAM compatibility]

### Byzantine FT Considerations
- Setup: [f Byzantine nodes in 3f+1]
- Test Scenarios: [List scenarios]
- Risk Assessment: [BFT assumptions correct?]

### Work Breakdown
[Detailed tasks with TDD and BFT testing estimates]

### Risk & Mitigation
- Risk: [Byzantine consensus complexity]
  Mitigation: [Extra testing, code review, hypothesis testing]

### Success Metrics
- Test Coverage: 85%+
- Byzantine FT Tests: [X scenarios passing]
- Code Review: Approved
```

## For plan-auditor

### Your Role

Validate Thoth implementation plans before execution.

### Audit Focus

When auditing a Thoth plan:

1. **BFT Modeling**:
   - [ ] Correctly assumes 3f+1 nodes?
   - [ ] Byzantine node model clear?
   - [ ] Consensus requirements stated?
   - [ ] Membership change scenarios identified?

2. **Testing Strategy**:
   - [ ] Unit test plan clear?
   - [ ] Integration test plan realistic?
   - [ ] Byzantine FT scenarios comprehensive?
   - [ ] Coverage target (85%) achievable?

3. **Complexity Assessment**:
   - [ ] Realistic time estimates?
   - [ ] 1.5-2x multiplier for BFT testing applied?
   - [ ] Dependency risks identified?
   - [ ] Blockers documented?

4. **Resource Alignment**:
   - [ ] Correct developers assigned?
   - [ ] Code review planned?
   - [ ] Knowledge sharing documented?

5. **Success Criteria**:
   - [ ] Measurable and achievable?
   - [ ] BFT guarantees well-defined?
   - [ ] Integration points clear?

### Audit Checklist

- [ ] Plan addresses current phase deliverables
- [ ] Byzantine FT modeling is sound
- [ ] Testing strategy is comprehensive
- [ ] Estimates account for BFT complexity
- [ ] Integration with Fireflies/CHOAM planned
- [ ] Success criteria are measurable
- [ ] Risks identified and mitigated
- [ ] Resources allocated appropriately
- [ ] No blocking dependencies
- [ ] Plan is ready for execution

## For test-validator

### Your Role

Verify test coverage, quality, and Byzantine FT completeness for Thoth.

### Test Validation Criteria

1. **Coverage Target: 85%**
   - KerlDht: ≥85%
   - Ani: ≥85%
   - Maat: ≥85%
   - ReconciliationService: ≥85%

2. **Byzantine FT Tests**:
   - [ ] f Byzantine nodes scenarios
   - [ ] Membership change rebalancing
   - [ ] Network partition handling
   - [ ] Witness majority enforcement
   - [ ] KERL consistency verification

3. **Test Quality**:
   - [ ] Tests are independent
   - [ ] No flaky/timing-dependent tests
   - [ ] Clear test names
   - [ ] Good assertions with messages
   - [ ] Proper test isolation

4. **Integration Tests**:
   - [ ] Fireflies membership integration
   - [ ] CHOAM state machine integration
   - [ ] Stereotomy KERI operations
   - [ ] gRPC service contracts

### Test Report Template

```
## Test Validation Report - Thoth

### Coverage Analysis
- Overall: [X%] (Target: 85%)
- KerlDht: [X%]
- Ani: [X%]
- Maat: [X%]
- ReconciliationService: [X%]

### Byzantine FT Testing
- Scenarios: [Count]
- Passing: [Count]
- Failing: [Count]
- Risk Assessment: [Are guarantees maintained?]

### Quality Assessment
- Flaky Tests: [Count and which ones]
- Test Isolation: [Good/Needs Work]
- Documentation: [Complete/Incomplete]

### Recommendations
- [ ] Coverage gaps to address
- [ ] BFT scenarios to add
- [ ] Flaky tests to fix
- [ ] Performance tests needed?

### Approval
- [ ] Meets 85% coverage target
- [ ] Byzantine FT comprehensive
- [ ] Test quality acceptable
- [ ] Ready for code review
```

## For All: Integration with Fireflies/CHOAM

### Fireflies Integration Points

Thoth queries Fireflies for:
- Ring topology for DHT successor determination
- Membership change notifications
- Node status information

Key assumptions:
- Fireflies provides consistent ring view
- Membership changes are ordered
- Gossip delivers all messages eventually

### CHOAM Integration Points

CHOAM applications use Thoth for:
- Key storage and retrieval
- Distributed identity management
- Witness coordination

Key assumptions:
- CHOAM provides replicated state machine interface
- Thoth keys are eventually consistent
- Byzantine nodes are tolerated

### Integration Test Requirements

Every integration point needs:
1. Unit test with mock Fireflies/CHOAM
2. Integration test with real services
3. Byzantine FT test with corrupted Fireflies/CHOAM

## For All: Session Management

### When Starting a Session
```bash
# 1. Load context
/load  # Restore previous session

# 2. Check state
bd list --status=in_progress
cat .pm/CONTINUATION_PROMPT.md

# 3. Verify baseline
./mvnw test -pl thoth

# 4. Get oriented
cat .pm/checkpoints/CHECKPOINT-latest.md
```

### When Ending a Session
```bash
# 1. Update checkpoint
cp .pm/checkpoints/TEMPLATE-checkpoint.md .pm/checkpoints/CHECKPOINT-N-title.md
# [Fill in checkpoint content]

# 2. Update continuation prompt
# [Update CONTINUATION_PROMPT.md with next actions]

# 3. Update execution state
# [Update execution_state.json metrics]

# 4. Save session
/check

# 5. Commit
git add .pm/
git commit -m "Session checkpoint: Phase N - [summary]

References: THOTH-xxx"
```

## Knowledge Base Access

### ChromaDB Queries

Search for prior decisions:
```bash
# Architecture decisions
mcp__chromadb__search_similar("thoth kerl dht topology", num_results=5)

# BFT implementation patterns
mcp__chromadb__search_similar("byzantine fault tolerance consensus", num_results=5)

# Witness coordination patterns
mcp__chromadb__search_similar("witness majority requirements", num_results=5)
```

### Memory Bank for Session State

Store session findings:
```bash
mcp__allPepper-memory-bank__memory_bank_write(
  "delos_active",
  "thoth_phase_2.md",
  "# Phase 2 Findings\n\n[Your session notes]"
)
```

## Escalation Procedures

### For Blocked Work
1. Document in execution_state.json `blockers` array
2. Create analysis in `.pm/thinking/`
3. Escalate to strategic-planner if blocking multiple beads
4. Update bead status to "blocked"

### For Byzantine FT Concerns
1. Create hypothesis in `.pm/hypotheses/`
2. Design test scenario to verify
3. Run test and document results
4. Escalate to code-review-expert if uncertain

### For Integration Issues
1. Document in `.pm/learnings/`
2. Create test case demonstrating issue
3. Coordinate with Fireflies/CHOAM maintainers
4. Update integration assumptions

