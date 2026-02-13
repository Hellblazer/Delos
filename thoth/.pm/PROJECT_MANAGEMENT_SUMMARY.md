# Thoth Module Project Management Summary

## One-Minute Overview

Thoth is the Distributed Hash Table (DHT) for KERI key management in the Delos Byzantine fault-tolerant platform. This project management infrastructure enables systematic development, knowledge persistence, and seamless team handoffs.

**Phase**: Foundation & Initialization → Core Enhancement → Integration & Scalability Testing
**Model**: Test-First (TDD) with Byzantine Fault Tolerance verification
**Team**: Code architects, Java developers, code reviewers, testers
**Success Metric**: 85%+ test coverage, Byzantine FT guarantees maintained, integrated with Fireflies/CHOAM

## Key Features

### 1. Resumable Development
- **CONTINUATION_PROMPT.md**: Session resumption template with immediate next actions
- **execution_state.json**: Central state tracking (phase, metrics, blockers, dependencies)
- **Checkpoints**: Work session snapshots at `.pm/checkpoints/`
- **Session storage**: `/check` and `/load` commands save/restore context

### 2. Knowledge Architecture
- **ChromaDB**: Persistent architectural decisions and research (e.g., `decision::thoth::kerl-dht-topology`)
- **Memory Bank**: Ephemeral session state in `delos_active/thoth_*.md`
- **Learnings**: Validated discoveries in `.pm/learnings/L*.md`
- **Hypotheses**: Architecture decisions and test results in `.pm/hypotheses/H*.md`

### 3. Byzantine Fault Tolerance Focus
- **Core requirement**: All operations respect 3f+1 node model (tolerate f corrupted nodes)
- **Test coverage**: Dedicated Byzantine FT scenarios for each component
- **Validation**: Ani service validates KERI events cryptographically
- **Witnessing**: Maat service provides consensus-based certification
- **Rebalancing**: Gossip protocol ensures consistency during membership changes

### 4. Three-Phase Development
```
Phase 1: Foundation (Documentation, test coverage, baselines)
    ↓
Phase 2: Hardening (Error handling, witness rotation, optimization)
    ↓
Phase 3: Integration (Fireflies, CHOAM, scalability testing)
```

### 5. Integration Points
- **Fireflies**: Ring topology for DHT successor determination
- **Stereotomy**: KERI event operations and validation hooks
- **CHOAM**: Applications using Thoth for key management

## Quick Navigation

| Artifact | Purpose | Location | Update Frequency |
|----------|---------|----------|------------------|
| Continuation | Resume quickly | `CONTINUATION_PROMPT.md` | End of each session |
| State Tracking | Phase, metrics, blockers | `execution_state.json` | After work session |
| Checkpoints | Work session summaries | `checkpoints/CHECKPOINT-n-*.md` | End of session |
| Learnings | Validated discoveries | `learnings/L*.md` | As discovered |
| Hypotheses | Architecture decisions | `hypotheses/H*.md` | During exploration |
| Audits | Code review findings | `audits/AUDIT-*.md` | Post code-review |
| Context Protocol | Handoff standards | `CONTEXT_PROTOCOL.md` | As needed (reference) |

## Development Methodology

### Test-First (TDD)
1. Write test demonstrating requirement
2. Run test (fails)
3. Implement to pass
4. Refactor
5. Commit with bead reference

### Byzantine Fault Tolerance Verification
- All consensus operations tested with f Byzantine nodes in 3f+1 setup
- Membership changes trigger rebalancing tests
- Network partitions simulated in gossip protocol tests
- Witness majority requirements validated

### Code Review Integration
- Automatic trigger on >10 lines of changes
- Validates: BFT assumptions, KERL consistency, test coverage, gRPC contracts
- Address findings before commit
- Document decisions about deferred items

## Beads Integration

All task tracking uses `bd` (beads) command:

```bash
# Create task
bd create "Thoth: Feature description" -t feature

# Track status
bd update THOTH-xxx --status in_progress

# Link dependencies
bd dep add THOTH-xxx THOTH-parent-epic

# List active work
bd list --status=in_progress

# Close completed work
bd close THOTH-xxx
```

**Format**: Bead descriptions include `Context: .pm/CONTEXT_PROTOCOL.md`

## Success Criteria

### Functional
- All core DHT operations function correctly under BFT model
- KERL consistency maintained during membership changes
- Ani validates events correctly (valid accept, invalid reject)
- Maat witnessing provides Byzantine fault-tolerant certification
- Gossip rebalancing successfully migrates KERLs

### Quality
- Test coverage >= 85% (KerlDht, Ani, Maat, ReconciliationService)
- All Byzantine FT scenarios passing
- No critical/high-severity code quality issues
- Documentation current and accurate
- Performance benchmarks meet baseline targets

### Integration
- Successfully integrates with Fireflies membership
- Works with CHOAM replicated applications
- Compatible with Stereotomy KERI operations
- gRPC service contracts stable

## Key Components

| Component | Purpose | File | Responsibility |
|-----------|---------|------|-----------------|
| KerlDht | DHT core | `KerlDht.java` | Distributed storage and routing |
| Ani | Event validation | `Ani.java` | Cryptographic validation |
| Maat | Witnessing | `Maat.java` | Consensus-based certification |
| KerlDhtServer | gRPC server | `KerlDhtServer.java` | Network DHT interface |
| ReconciliationService | Rebalancing | `ReconciliationService.java` | Gossip-based migration |

## Metrics Tracking

### Test Coverage
- **Target**: 85%
- **Components**: KerlDht, Ani, Maat, ReconciliationService
- **Current**: [Update from execution_state.json]

### Byzantine FT Tests
- **Scenarios**: Membership changes, Byzantine nodes, partitions
- **Status**: [Update from execution_state.json]

### Performance
- DHT lookup latency (ms)
- DHT insert latency (ms)
- Rebalancing time (seconds)
- Event validation throughput (events/sec)

## How Agents Interact

### Strategic Planning
**Input**: Feature requirements
**Output**: Implementation plan with beads
**Handoff**: Plan → plan-auditor → java-architect-planner → java-developer

### Code Development
**Workflow**: java-developer works on bead, code-review-expert validates
**Test-First**: Write failing test, implement, pass test, commit
**Quality Gate**: Code review checks BFT assumptions and test coverage

### Knowledge Persistence
**ChromaDB**: Store validated architectural decisions
**Memory Bank**: Ephemeral session state
**Learnings**: Record discoveries in .pm/learnings/

### Session Management
**Resumption**: CONTINUATION_PROMPT.md provides quick context
**State Tracking**: execution_state.json maintains phase and metrics
**Checkpoints**: Work session summaries in checkpoints/

## Technology Stack

- **Language**: Java 25+
- **Build**: Maven 3.9.3+ with Wrapper
- **gRPC**: 1.68.0 with Protobuf 4.28.2
- **Database**: H2 + JOOQ + Liquibase
- **Networking**: Netty 4.1.127
- **Crypto**: Bouncy Castle
- **Metrics**: Dropwizard Metrics
- **Logging**: SLF4J/Logback
- **Testing**: JUnit 5 + Mockito + AssertJ

## Build Commands

```bash
# First-time setup (required)
./mvnw clean install -Ppre -DskipTests

# Standard build
./mvnw clean install

# Run thoth tests
./mvnw test -pl thoth

# Run single test
./mvnw test -pl thoth -Dtest=KerlDhtTest

# Build with GraalVM isolates
./mvnw clean install -Pisolates
```

## Session Workflow

### Starting Work
1. Read CONTINUATION_PROMPT.md
2. Check active beads: `bd list --status=in_progress`
3. Review last checkpoint
4. Run: `./mvnw test -pl thoth` to verify baseline
5. Start work on next action

### During Work
1. Create/update beads for tasks
2. Write tests first (TDD)
3. Implement to pass tests
4. Run full suite: `./mvnw test -pl thoth`
5. Commit with bead reference

### Ending Session
1. Update CONTINUATION_PROMPT.md
2. Create checkpoint in checkpoints/
3. Update execution_state.json metrics
4. Run: `/check` to save session state
5. Commit: `git commit -m "Session checkpoint: ..."`

## Common Agent Handoffs

### To Code Review Expert
```
## Handoff: code-review-expert

**Task**: Review KerlDht rebalancing implementation for Byzantine FT correctness
**Bead**: THOTH-042 (status: in_progress)

### Quality Criteria
- [ ] Byzantine fault tolerance assumptions verified
- [ ] KERL consistency guarantees documented
- [ ] Test coverage adequate for new code paths
- [ ] gRPC service contract changes documented
```

### To Java Developer
```
## Handoff: java-developer

**Task**: Implement enhanced KerlDht rebalancing with improved error handling
**Bead**: THOTH-043 (status: pending)

### Deliverable
- KerlDht.rebalance() handles Byzantine nodes correctly
- All BFT test scenarios passing
- Test coverage >= 85%
- Code review approved
```

## Troubleshooting Quick Reference

| Issue | Solution |
|-------|----------|
| Tests timeout | Increase timeout: `-DargLine="-Dtimeout=600000"` |
| Memory error | Increase heap: `-DargLine="-Xmx10G"` |
| BFT tests flaky | Retry with: `-Dsurefire.rerunFailingTestsCount=2` |
| Cannot find generated code | Run: `./mvnw generate-sources -pl thoth` |
| Port conflicts | Tests use port 0 (dynamic), check for other processes |

## Contact & Resources

- **Architecture Decisions**: See `hypotheses/` directory
- **Prior Research**: See `learnings/` directory and ChromaDB
- **Code Standards**: See CLAUDE.md (project-specific instructions)
- **Blocked Work**: Document in execution_state.json `blockers` array

## Next Steps

1. **Create Phase 1 Epic**: `bd create "Thoth: Phase 1 - Foundation" -t epic`
2. **Identify Baseline Tests**: Analyze test coverage for core modules
3. **Document Topology**: Create architecture decision for KerlDHT topology
4. **Set Metrics**: Establish performance baseline measurements
5. **Begin TDD**: Start with highest-priority features

