# Thoth Module Context Protocol

## Overview
This document defines how Claude Code agents interact with the Thoth module project management infrastructure. All agents working on Thoth must follow this protocol to maintain consistency and enable seamless handoffs.

## RECEIVE (Before Starting Work)

When resuming work on Thoth, check these sources in order:

1. **execution_state.json** - Current phase, active tasks, blockers, metrics
2. **CONTINUATION_PROMPT.md** - High-level context for quick resumption
3. **Active Beads** - `bd list --status=in_progress` for current work
4. **ChromaDB** - Search for prior decisions and research on thoth topics
5. **Memory Bank** - `delos_active/thoth_*.md` for session state

### Verification Checklist
Before starting work, verify:
- [ ] Current phase identified from execution_state.json
- [ ] Active bead(s) listed with status and dependencies
- [ ] Blockers documented
- [ ] Last checkpoint reviewed
- [ ] Recent learnings understood

## PRODUCE

When working on Thoth, produce artifacts following these conventions:

### Beads
- Epic: `Thoth: Module Name` (e.g., `Thoth: KerlDHT Rebalancing`)
- Features/Tasks: Reference parent epic, include related beads as dependencies
- Format: Include `Context: .pm/CONTEXT_PROTOCOL.md` in bead description

### Files Created/Modified
- Always reference bead ID in commit messages: `References: THOTH-xxx`
- Record file changes in checkpoint before commit
- Update execution_state.json metrics after each work session

### Knowledge Artifacts
- ChromaDB: Store validated architectural decisions with ID format `decision::thoth::{topic}`
- Memory Bank: Session state in `delos_active/thoth_{date}.md`
- Learnings: Record in `.pm/learnings/L{n}-{title}.md`

## HANDOFF (Standard Format)

All handoffs between agents use this structure:

```
## Handoff: [Target Agent Name]

**Task**: [1-2 sentence summary of what needs to be done]
**Bead**: THOTH-xxx (status: [status])

### Input Artifacts
- ChromaDB: [document IDs or "none"]
  - Example: decision::thoth::kerl-dht-topology
- Memory Bank: [file path or "none"]
  - Example: delos_active/thoth_phase2.md
- Files: [key files touched/created]
  - Example: src/main/java/com/hellblazer/delos/thoth/Thoth.java

### Deliverable
[Clear description of what the receiving agent should produce]

### Quality Criteria
- [ ] [Criterion 1]
- [ ] [Criterion 2]
- [ ] [Criterion 3]
- [ ] Commit messages reference bead ID
- [ ] Tests pass locally
- [ ] Code review findings addressed

### Context Notes
[Special context, Byzantine fault tolerance requirements, blockers, or warnings]
```

## RECOVER (If Context Missing)

If expected context not received:

1. Search ChromaDB: `mcp__chromadb__search_similar("thoth architecture decisions")`
2. Check Memory Bank: `mcp__allPepper-memory-bank__memory_bank_read("delos_active", "thoth_*.md")`
3. Query active beads: `bd list --status=in_progress --tags thoth`
4. Document assumption in bead notes
5. Flag incomplete context in downstream handoff

## Key Vocabulary

### Core Concepts
- **KerlDHT**: Distributed Hash Table mapping KERI identifier digests to Fireflies ring successors for KERL storage
- **KERL**: Key Event Receipt Log - immutable log of key events for an identifier
- **Ani**: KERI event validation service for root validation and KERL set validation
- **Maat**: Witnessing service providing Byzantine fault tolerance for event validation
- **Fireflies Ring**: Membership context providing Byzantine fault-tolerant DHT routing
- **Gossip Protocol**: Rebalancing mechanism for moving KERLs when membership changes
- **Byzantine Fault Tolerance (BFT)**: System remains correct with f corrupted nodes in 3f+1 nodes

### Key Components
- **KerlDht.java**: Main DHT implementation with rebalancing
- **KerlDhtServer.java**: gRPC server for DHT operations
- **KerlDhtService.java**: gRPC service interface
- **Ani.java**: KERI event validation
- **Maat.java**: Witnessing and certification service
- **ReconciliationService.java**: Data consistency during rebalancing

## Invariants & Constraints

1. **Byzantine Fault Tolerance**: Must tolerate f failures with 3f+1 nodes minimum
2. **KERL Integrity**: KERLs are append-only and immutable
3. **DHT Consistency**: During membership changes, gossip protocol ensures all KERLs reach correct successors
4. **Event Validation**: Only cryptographically valid events accepted
5. **Witness Majority**: Witnessed events require majority of witnesses to be valid
6. **Ring Topology**: Successor nodes determined by consistent hashing of KERL identifier digest

## Development Methodology

### Test-First (TDD)
1. Write test demonstrating requirement
2. Run test (should fail)
3. Implement code to make test pass
4. Refactor
5. Commit with bead reference

### Code Review Process
1. Code changes trigger code-review-expert automatically (>10 lines)
2. Address all findings before commit
3. Document decisions about deferred items in bead notes
4. Reference code review in commit message when addressing significant issues

### Byzantine Fault Tolerance Verification
1. All consensus operations tested with Byzantine nodes (f corrupted of 3f+1)
2. All rebalancing scenarios tested with membership changes
3. Gossip protocol delivery verified with network partitions
4. Event validation tested with invalid cryptographic material

## File Organization

```
thoth/
├── .pm/
│   ├── CONTEXT_PROTOCOL.md          (this file)
│   ├── CONTINUATION_PROMPT.md       (session resumption template)
│   ├── PROJECT_MANAGEMENT_SUMMARY.md (high-level overview)
│   ├── README.md                     (usage guide)
│   ├── execution_state.json          (central state tracking)
│   ├── checkpoints/                  (CHECKPOINT-{n}-{title}.md)
│   ├── learnings/                    (L{n}-{title}.md)
│   ├── hypotheses/                   (H{n}-{title}.md)
│   ├── audits/                       (AUDIT-{date}-{focus}.md)
│   ├── thinking/                     (ANALYSIS-{date}-{topic}.md)
│   └── metrics/                      (various tracking files)
├── src/
│   ├── main/java/com/hellblazer/delos/thoth/
│   │   ├── Thoth.java                (main entry point)
│   │   ├── KerlDht.java              (DHT core)
│   │   ├── Ani.java                  (KERI validation)
│   │   ├── Maat.java                 (witnessing service)
│   │   ├── grpc/dht/                 (DHT gRPC services)
│   │   ├── grpc/reconciliation/      (rebalancing services)
│   │   └── metrics/                  (Dropwizard metrics)
│   └── test/java/com/hellblazer/delos/thoth/
│       ├── AbstractDhtTest.java      (base test utilities)
│       ├── KerlDhtTest.java          (DHT tests)
│       ├── ThothTest.java            (integration tests)
│       └── ...
└── pom.xml
```

## Next Session Entry Point

When resuming:
1. Read execution_state.json to understand current phase
2. Read last CHECKPOINT file to see what was done
3. Read CONTINUATION_PROMPT.md for quick context
4. Execute: `bd list --status=in_progress` to see active work
5. Check .pm/metrics/ for current progress metrics

## Agent Coordination

### Code Review Integration
- After significant changes, code-review-expert validates:
  - Byzantine fault tolerance assumptions
  - KERL consistency guarantees
  - gRPC service contract compliance
  - Test coverage for new code paths
- Address findings before committing

### Knowledge Persistence
- Validated architectural decisions stored in ChromaDB
- Research on KERI/BFT patterns saved with metadata
- Cross-session knowledge accessible to all agents

### Memory Bank for Session State
- Current bead IDs and status
- Pending decisions or blocked work
- Hypotheses being tested
- Discovery findings waiting for implementation

