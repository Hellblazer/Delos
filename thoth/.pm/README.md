# Thoth Module Project Management

This directory contains comprehensive project management infrastructure for the Thoth distributed hash table module.

## Quick Start

**Resuming after a break?**
1. Read this file (2 minutes)
2. Read `CONTINUATION_PROMPT.md` (3 minutes)
3. Run: `bd list --status=in_progress` to see active work
4. Read last checkpoint from `checkpoints/`
5. Start work!

## Directory Structure

```
.pm/
├── CONTEXT_PROTOCOL.md              Context and handoff protocol for all agents
├── CONTINUATION_PROMPT.md           Session resumption template
├── execution_state.json             Central state tracking (JSON)
├── PROJECT_MANAGEMENT_SUMMARY.md    High-level overview
├── README.md                        This file
├── checkpoints/                     Work session snapshots (CHECKPOINT-n-title.md)
├── learnings/                       Validated discoveries (L0, L1, L2...)
├── hypotheses/                      Architecture decisions & tests (H0, H1, H2...)
├── audits/                          Code review findings & quality gates
├── thinking/                        Deep analysis documents
└── metrics/                         Test coverage, performance baselines, progress tracking
```

## Core Concepts

### Byzantine Fault Tolerance (BFT)
The Thoth module implements Byzantine fault-tolerant distributed hash table and key management. Critical concepts:

- **3f+1 nodes minimum**: Can tolerate f corrupted/Byzantine nodes
- **Consensus requirement**: Operations must be valid with majority agreement
- **KERL integrity**: Key Event Receipt Logs are append-only and immutable
- **Gossip rebalancing**: When membership changes, keys move via gossip protocol

### Key Components
- **KerlDht**: DHT core with ring-based routing (via Fireflies)
- **Ani**: KERI event validation (cryptographic)
- **Maat**: Byzantine fault-tolerant witnessing service
- **Reconciliation**: Gossip-based rebalancing during membership changes

## Workflows

### Starting New Work (Feature/Bug)

1. **Create bead**:
   ```bash
   bd create "Thoth: [Feature/Fix description]" -t feature
   # Outputs: THOTH-xxx
   ```

2. **Link to phase**:
   ```bash
   bd dep add THOTH-xxx THOTH-phase-epic  # Link to current phase epic
   ```

3. **Write test first** (TDD):
   ```java
   @Test
   void shouldHandleNewFeature() {
       // Arrange: Set up Byzantine nodes if needed
       // Act: Call feature
       // Assert: Verify Byzantine FT guarantees
   }
   ```

4. **Implement code** to make test pass

5. **Run full test suite**:
   ```bash
   ./mvnw test -pl thoth
   ```

6. **Commit** with bead reference:
   ```bash
   git add .
   git commit -m "Implement feature: [description]

   References: THOTH-xxx"
   ```

### Code Review Process

After significant changes (>10 lines), code-review-expert automatically validates:
- Byzantine fault tolerance assumptions
- KERL consistency guarantees
- Test coverage for new code paths
- gRPC service contract compliance

Address findings before committing. Document decisions about deferred items.

### Creating a Checkpoint

At end of work session, create checkpoint:

1. Copy template:
   ```bash
   cp .pm/checkpoints/TEMPLATE-checkpoint.md .pm/checkpoints/CHECKPOINT-N-title.md
   ```

2. Fill in:
   - What was completed
   - What's in progress
   - Any blockers
   - Files modified
   - Tests passing/failing
   - Metrics updates

3. Update `execution_state.json`:
   - current_phase.progress_percentage
   - session_tracking.last_checkpoint
   - session_tracking.last_work_session_date
   - metrics values

4. Commit:
   ```bash
   git add .pm/
   git commit -m "Session checkpoint: Phase 1 - [summary]

   Completed: [items]
   In Progress: [items]
   References: THOTH-xxx"
   ```

### Recording a Learning

When you discover something important:

1. Create learning file:
   ```bash
   cp .pm/learnings/TEMPLATE-learning.md .pm/learnings/L0-title.md
   ```

2. Document:
   - Date discovered
   - The learning (what did you discover?)
   - Why it matters
   - Evidence supporting it
   - Action items

3. Store in ChromaDB if it's architectural or reusable:
   ```bash
   mcp__chromadb__create_document(
     "decision::thoth::topic-name",
     "# Learning Title\n\n[Content from learning file]",
     {"type": "learning", "verified": "2026-01-12", "module": "thoth"}
   )
   ```

4. Reference in bead description

### Recording a Hypothesis

For architecture decisions or uncertain approaches:

1. Create hypothesis file:
   ```bash
   cp .pm/hypotheses/TEMPLATE-hypothesis.md .pm/hypotheses/H0-title.md
   ```

2. Include:
   - Hypothesis (what you're testing)
   - Rationale
   - Validation criteria
   - Testing approach

3. Update as you gather evidence:
   - Add verification results
   - Record test outcomes
   - Document conclusion

## Phases

### Phase 1: Foundation & Initialization
**Status**: Pending

Establish baseline infrastructure, documentation, and test coverage.

Deliverables:
- Complete KerlDHT topology documentation
- Baseline test coverage for core services
- Byzantine FT test suite
- Performance baselines
- Architecture decision records

### Phase 2: Core Enhancement & Hardening
**Status**: Pending

Enhance core implementations with improved error handling and BFT verification.

Deliverables:
- Enhanced rebalancing algorithm
- Comprehensive error handling
- Witness rotation and Byzantine node handling
- Performance optimizations

### Phase 3: Integration & Scalability Testing
**Status**: Pending

Full integration and large-scale testing scenarios.

Deliverables:
- Full integration tests with Fireflies and CHOAM
- Scalability tests (10-100+ nodes)
- Chaos engineering tests
- Deployment guides

## Key Metrics

### Test Coverage
- **Target**: 85% for core modules
- **Current**: [Update from execution_state.json]
- **Components**: KerlDht, Ani, Maat, ReconciliationService

### Byzantine FT Tests
- **Total scenarios**: [Count of BFT test cases]
- **Passing**: [Number passing]
- **Coverage**: Membership changes, Byzantine nodes, network partitions

### Performance Baselines
- DHT lookup latency (ms)
- DHT insert latency (ms)
- Rebalancing time (seconds)
- Event validation throughput (events/sec)

## Technology Stack

- Java 25+ (required for production)
- Maven 3.9.3+
- gRPC 1.68.0 / Protobuf 4.28.2
- H2 Database + JOOQ + Liquibase
- Netty 4.1.127
- Bouncy Castle (cryptography)
- Dropwizard Metrics
- SLF4J/Logback
- JUnit 5 + Mockito + AssertJ

## Dependencies

### Internal
- **stereotomy-services**: KERI event operations
- **fireflies**: Membership service and ring topology
- **choam**: Replicated state machine layer
- **cryptography**: Digest and signature operations

### External
- gRPC 1.68.0
- Netty 4.1.127
- H2 Database
- Liquibase

## Integration Points

### Fireflies Membership
- Thoth queries ring topology to determine DHT successors
- Membership changes trigger gossip rebalancing
- Status: Integrated

### Stereotomy Services
- Thoth validates KERI events (Ani service)
- Stereotomy provides KERI operation hooks
- Status: Integrated

### CHOAM State Machines
- Applications use Thoth for key management
- Replicated applications access distributed keys
- Status: Integrated

## Troubleshooting

### Tests Failing Locally
```bash
# Run with more verbosity
./mvnw test -pl thoth -X

# Run single test class
./mvnw test -pl thoth -Dtest=KerlDhtTest

# Run with longer timeout
./mvnw test -pl thoth -DargLine="-Dtimeout=300000"
```

### Byzantine FT Tests Timing Out
Some Byzantine consensus tests are timing-sensitive:
```bash
# Increase timeout
./mvnw test -pl thoth -DargLine="-Dtimeout=600000"

# Retry flaky tests
./mvnw test -Dsurefire.rerunFailingTestsCount=2 -pl thoth
```

### Memory Issues in Tests
```bash
# Increase heap for large tests
./mvnw test -pl thoth -DargLine="-Xmx10G -Xms4G"
```

### Cannot Find Generated Sources
```bash
# Regenerate protobuf and JOOQ sources
./mvnw generate-sources -pl thoth
```

## Session Management

### Save Session State
Before closing, use `/check` command to save:
- Current bead status
- ChromaDB discoveries
- Memory Bank session notes

### Resume Session
Use `/load` command to restore:
- Bead context
- Previous discoveries
- Session state

### List Sessions
View all saved sessions:
```bash
/sessions
```

## Best Practices

1. **Always write tests first** (TDD)
2. **Test Byzantine fault tolerance** for consensus operations
3. **Reference beads in commits** (References: THOTH-xxx)
4. **Update metrics regularly** (test coverage, performance)
5. **Document decisions** in hypotheses/
6. **Create checkpoints** at end of sessions
7. **Use ChromaDB** for persistent architectural knowledge
8. **Link beads** to parent epics for traceability

## Getting Help

- **Architecture questions**: See `hypotheses/` and ChromaDB
- **How-to questions**: See `learnings/` for discoveries
- **Code review findings**: Check `audits/`
- **What to work on next**: Check `CONTINUATION_PROMPT.md`
- **Stuck on something**: Document in `thinking/` and ask code-review-expert

## External References

- **KERI Specification**: https://github.com/trustoverip/keri
- **Byzantine Fault Tolerance**: Leslie Lamport's "The Byzantine Generals Problem"
- **Consistent Hashing**: Karger et al., "Consistent Hashing and Random Trees"
- **Gossip Protocols**: Demers et al., "Epidemic Algorithms for Replicated Database Maintenance"

