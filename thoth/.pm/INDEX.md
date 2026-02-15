# Thoth Module Project Management - Index

This file provides navigation and quick reference for all project management artifacts.

## Core Navigation

| Artifact | Purpose | When to Read | Update Frequency |
|----------|---------|--------------|-----------------|
| **README.md** | Quick start and workflows | Starting sessions | Reference |
| **CONTEXT_PROTOCOL.md** | Standards and protocols | Before working | Reference |
| **CONTINUATION_PROMPT.md** | Session resumption | Starting sessions | Each session |
| **execution_state.json** | Central state tracking | Checking status | After work |
| **PROJECT_MANAGEMENT_SUMMARY.md** | High-level overview | Project orientation | Reference |
| **AGENT_INSTRUCTIONS.md** | Role-specific guidance | Agent-specific | Reference |

## Phases & Epics

### Phase 1: Foundation & Initialization
**Status**: Pending
**Epic Bead**: [To be created]
**Deliverables**:
- Complete documentation of KerlDHT topology and rebalancing
- Baseline test coverage analysis for core services
- Byzantine fault tolerance test suite
- Performance baseline metrics
- Architecture decision records

**Key Files**:
- Checkpoint: `.pm/checkpoints/CHECKPOINT-1-*.md`
- Learnings: `.pm/learnings/L1-*.md`, `.pm/learnings/L2-*.md`
- Hypotheses: `.pm/hypotheses/H1-*.md`, `.pm/hypotheses/H2-*.md`

### Phase 2: Core Enhancement & Hardening
**Status**: Pending
**Epic Bead**: [To be created]
**Deliverables**:
- Enhanced KerlDHT implementation
- Comprehensive error handling framework
- Distributed consensus verification
- Witness rotation and Byzantine handling
- Performance optimizations

**Key Files**:
- Checkpoint: `.pm/checkpoints/CHECKPOINT-2-*.md`
- Learnings: `.pm/learnings/L3-*.md`, `.pm/learnings/L4-*.md`
- Hypotheses: `.pm/hypotheses/H3-*.md`

### Phase 3: Integration & Scalability Testing
**Status**: Pending
**Epic Bead**: [To be created]
**Deliverables**:
- Full integration tests with Fireflies and CHOAM
- Scalability tests (10-100+ nodes)
- Chaos engineering test suite
- Performance benchmarks and optimizations
- Deployment and operational guides

**Key Files**:
- Checkpoint: `.pm/checkpoints/CHECKPOINT-3-*.md`
- Learnings: `.pm/learnings/L5-*.md`
- Hypotheses: `.pm/hypotheses/H4-*.md`, `.pm/hypotheses/H5-*.md`

## Knowledge Base

### Learnings (Validated Discoveries)
**Location**: `.pm/learnings/`

Learnings follow the pattern: `L{n}-{title}.md`

**Current Learnings**:
- [To be populated as discoveries are made]

### Hypotheses (Architecture Decisions & Explorations)
**Location**: `.pm/hypotheses/`

Hypotheses follow the pattern: `H{n}-{title}.md`

**Current Hypotheses**:
- [To be populated as decisions are explored]

### Analysis & Thinking
**Location**: `.pm/thinking/`

Analysis documents follow the pattern: `ANALYSIS-{YYYY-MM-DD}-{topic}.md`

**Current Analysis**:
- [To be populated as deep dives are conducted]

### Audits & Code Review Findings
**Location**: `.pm/audits/`

Audit documents follow the pattern: `AUDIT-{YYYY-MM-DD}-{focus}.md`

**Current Audits**:
- [To be populated after code review cycles]

## Metrics & Tracking

### Test Coverage
**File**: `.pm/metrics/TEST-COVERAGE.md`
**Update**: Each test session
**Key Metrics**:
- Overall coverage (target: 85%)
- Per-component coverage (KerlDht, Ani, Maat, ReconciliationService)
- Byzantine FT test count
- Performance baselines

### Progress Tracking
**File**: `execution_state.json` (metrics section)
**Update**: End of work session
**Tracks**:
- Phase progress percentage
- Test coverage by component
- Byzantine FT test results
- Performance metrics

### Session History
**File**: `.pm/checkpoints/CHECKPOINT-{n}-{title}.md`
**Update**: End of each work session
**Contains**:
- Work completed
- Tests added/modified
- Metrics updates
- Blockers and issues
- Next actions

## Component Reference

### KerlDht (Core DHT)
**File**: `src/main/java/com/hellblazer/delos/thoth/KerlDht.java`
**Responsibility**: Distributed storage and routing of KERLs
**Key Methods**: `lookup()`, `insert()`, `rebalance()`
**Test Classes**: `KerlDhtTest.java`, `KerlDhtByzantineTest.java`
**Issues/Hypotheses**: [Link to related H*.md files]

### Ani (Event Validation)
**File**: `src/main/java/com/hellblazer/delos/thoth/Ani.java`
**Responsibility**: KERI event cryptographic validation
**Key Methods**: `validate()`, `validateRoot()`
**Test Classes**: `AniTest.java`, `KerlTest.java`
**Issues/Hypotheses**: [Link to related H*.md files]

### Maat (Witnessing Service)
**File**: `src/main/java/com/hellblazer/delos/thoth/Maat.java`
**Responsibility**: Byzantine fault-tolerant witness coordination
**Key Methods**: `witness()`, `verify()`
**Test Classes**: `MaatTest.java`, `KerlSpaceTest.java`
**Issues/Hypotheses**: [Link to related H*.md files]

### ReconciliationService (Rebalancing)
**File**: `src/main/java/com/hellblazer/delos/thoth/grpc/reconciliation/ReconciliationService.java`
**Responsibility**: Gossip-based KERL migration during membership changes
**Key Methods**: `reconcile()`, `migrate()`
**Test Classes**: `PublisherTest.java`, `DhtRebalanceTest.java`
**Issues/Hypotheses**: [Link to related H*.md files]

## Workflows & Checklists

### Starting Work on a Feature
- [ ] Read CONTINUATION_PROMPT.md
- [ ] Review related beads: `bd list --status=in_progress`
- [ ] Check for blockers in execution_state.json
- [ ] Read relevant hypotheses from `.pm/hypotheses/`
- [ ] Run tests to verify baseline: `./mvnw test -pl thoth`
- [ ] Create/update bead: `bd update THOTH-xxx --status in_progress`
- [ ] Write failing test first (TDD)
- [ ] Implement to pass test
- [ ] Run full suite: `./mvnw test -pl thoth`

### Code Review Process
- [ ] Changes >10 lines trigger code-review-expert
- [ ] Review findings documented in `.pm/audits/`
- [ ] Address all findings before commit
- [ ] Document deferred items in bead notes
- [ ] Get approval before final commit

### Ending Work Session
- [ ] Create checkpoint: `cp .pm/checkpoints/TEMPLATE-checkpoint.md .pm/checkpoints/CHECKPOINT-{n}-{title}.md`
- [ ] Fill in checkpoint details
- [ ] Update CONTINUATION_PROMPT.md with next actions
- [ ] Update execution_state.json metrics
- [ ] Run `/check` to save session state
- [ ] Commit with message referencing bead ID

### Recording Learnings
- [ ] Create file: `cp .pm/learnings/TEMPLATE-learning.md .pm/learnings/L{n}-{title}.md`
- [ ] Document discovery, evidence, and implications
- [ ] Assess BFT implications
- [ ] Store in ChromaDB: `decision::thoth::{topic}`
- [ ] Link in relevant bead description

### Creating Architecture Decisions
- [ ] Create file: `cp .pm/hypotheses/TEMPLATE-hypothesis.md .pm/hypotheses/H{n}-{title}.md`
- [ ] State hypothesis clearly
- [ ] Define validation criteria
- [ ] Plan testing approach (especially BFT scenarios)
- [ ] Execute tests
- [ ] Document results and decision
- [ ] Update execution_state.json

## Bead Tracking

### Create New Bead
```bash
bd create "Thoth: [Description]" -t [feature/bug/task/chore]
# Outputs: THOTH-xxx
```

### Link to Epic
```bash
bd dep add THOTH-xxx THOTH-phase-epic
```

### View Bead Details
```bash
bd show THOTH-xxx
```

### List Active Work
```bash
bd list --status=in_progress --tags thoth
```

### Close Bead
```bash
bd close THOTH-xxx
```

## Key Technologies & Dependencies

### Internal Dependencies
- stereotomy-services (KERI operations)
- fireflies (membership and ring topology)
- choam (replicated state machines)
- cryptography (digests and signatures)

### External Dependencies
- Java 25+
- gRPC 1.68.0
- Netty 4.1.127
- H2 Database
- Liquibase
- Bouncy Castle
- Dropwizard Metrics
- JUnit 5, Mockito, AssertJ

## Build & Test Commands

### Setup
```bash
# First-time only
./mvnw clean install -Ppre -DskipTests

# Standard build
./mvnw clean install -pl thoth
```

### Testing
```bash
# All thoth tests
./mvnw test -pl thoth

# Single test class
./mvnw test -pl thoth -Dtest=KerlDhtTest

# With longer timeout
./mvnw test -pl thoth -DargLine="-Dtimeout=600000"

# Generate sources if needed
./mvnw generate-sources -pl thoth
```

## Quick Links

### Documentation
- [README.md](README.md) - Quick start guide
- [CONTEXT_PROTOCOL.md](CONTEXT_PROTOCOL.md) - Standards and protocols
- [AGENT_INSTRUCTIONS.md](AGENT_INSTRUCTIONS.md) - Role-specific guidance
- [PROJECT_MANAGEMENT_SUMMARY.md](PROJECT_MANAGEMENT_SUMMARY.md) - High-level overview

### State & Metrics
- [CONTINUATION_PROMPT.md](CONTINUATION_PROMPT.md) - Session resumption
- [execution_state.json](execution_state.json) - Central state tracking
- [TEST-COVERAGE.md](metrics/TEST-COVERAGE.md) - Coverage tracking

### Templates
- [CHECKPOINT Template](checkpoints/TEMPLATE-checkpoint.md)
- [LEARNING Template](learnings/TEMPLATE-learning.md)
- [HYPOTHESIS Template](hypotheses/TEMPLATE-hypothesis.md)
- [ANALYSIS Template](thinking/TEMPLATE-analysis.md)

### Knowledge Base
- [Learnings](learnings/) - Validated discoveries
- [Hypotheses](hypotheses/) - Architecture decisions
- [Analysis](thinking/) - Deep investigations
- [Audits](audits/) - Code review findings

## Contacts & Escalation

### For Architecture Questions
- Review hypotheses in `.pm/hypotheses/`
- Search ChromaDB for prior decisions
- Create new hypothesis if exploring unknown territory

### For Code Quality Issues
- Escalate to code-review-expert via handoff
- Document findings in `.pm/audits/`
- Address before commit

### For Blocked Work
- Document in execution_state.json blockers array
- Create analysis in `.pm/thinking/`
- Escalate to strategic-planner if blocking multiple tasks

### For Byzantine FT Concerns
- Create hypothesis in `.pm/hypotheses/`
- Design BFT test scenario
- Escalate to code-review-expert if uncertain

## Success Metrics

### Phase 1 Success
- [ ] Test coverage >= 75%
- [ ] Byzantine FT test suite established
- [ ] Performance baselines measured
- [ ] Architecture documented in hypotheses
- [ ] Zero critical code quality issues

### Overall Module Success
- [ ] Test coverage >= 85%
- [ ] All Byzantine FT scenarios passing
- [ ] Integrated with Fireflies and CHOAM
- [ ] Performance targets met
- [ ] Documentation current and complete

---

**Last Updated**: [YYYY-MM-DD]
**Total Sessions**: [N]
**Current Phase**: [Phase X]
**Progress**: [X%]

