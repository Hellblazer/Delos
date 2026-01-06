# ADR-0004: Consensus Design - Committee-based State Machine Replication

**Status**: ACCEPTED

**Date**: 2026-01-06

**Context**

Delos requires a distributed ledger that provides totally ordered transactions (equivalent to a Kafka event partition) while supporting Byzantine fault-tolerant consensus. The consensus layer must orchestrate multiple committees, handle view reconfiguration, support checkpointing for efficient bootstrapping, and operate asynchronously without centralized leaders.

This ADR documents the architecture of the CHOAM (Combine Honnete Ober Advancer Mercantiles) module, which implements committee-based state machine replication combining insights from two foundational papers.

**Decision**

**We adopt a committee-based asynchronous Byzantine fault-tolerant state machine replication architecture based on two key design papers:**

1. **[From Byzantine Consensus to BFT State Machine Replication: A Latency-Optimal Transformation](https://www.researchgate.net/profile/Alysson_Bessani/publication/254037731_From_Byzantine_Consensus_to_BFT_State_Machine_Replication_A_Latency-Optimal_Transformation/links/562f872108ae4742240af924/)**
   - Transformation of consensus algorithms into state machine replication
   - Asynchronous Byzantine consensus foundations
   - Optimal latency properties

2. **[From Byzantine Replication to Blockchain: Consensus is only the Beginning](https://arxiv.org/abs/2004.14527)**
   - Different block types and their semantics
   - View reconfiguration mechanisms
   - Checkpoint and recovery protocols

**Architecture Components:**

1. **CHOAM: Main Consensus Abstraction**
   - Entry point for nodes to participate in distributed ledger
   - Coordinates committee membership based on ring ordering
   - Uses Context.bftSubset(viewId) for pseudo-random committee selection
   - Location: `choam/src/main/java/com/hellblazer/delos/choam/CHOAM.java`

2. **Committee: View-based Voting Group**
   - Immutable committee membership for a view
   - Selected deterministically via context digest hash
   - Requires 2/3 + 1 threshold for all consensus decisions
   - Members are Fireflies Context members
   - Location: `choam/src/main/java/com/hellblazer/delos/choam/Committee.java`

3. **Block Types and Semantics**
   - **Transaction Blocks**: Client-submitted DDL/DML commands (state-changing)
   - **Genesis Block**: Initial ledger block (bootstrap)
   - **Assemble Block**: Signals view change, contains HexBloom diadem of new view
   - **Reconfiguration Block**: Completes view change, includes join keys from new committee
   - **Checkpoint Block**: Snapshots state, enables efficient recovery (deferred blocks discarded)
   - Location: `choam/proto/choam.proto`

4. **View and View Reconfiguration**
   - View identity: XOR of previous assembly block hash + consensus HexBloom diadem
   - Pseudo-random committee selection via Context.bftSubset(viewId)
   - Ephemeral keys per view per member (deleted after view change)
   - 2/3 + 1 threshold required for view consensus
   - Location: `choam/src/main/java/com/hellblazer/delos/choam/ViewAssembly.java`

5. **Asynchronous Join Protocol**
   - Step 1: Consensus on view within current committee
   - Step 2: Produce Assemble block (signals join to new committee)
   - Step 3: Start block production for current view
   - Step 4: Await majority of next view members to propose view keys
   - Step 5: Emit Reconfiguration block (end of current view, begin next)
   - Location: `choam/src/main/java/com/hellblazer/delos/choam/Session.java`

6. **Finite State Machine Model (Tron)**
   - Four state machines drive CHOAM operation:
     - **Mercantile** (normal operations): Client transaction handling
     - **Earner** (block production): Consensus-driven block generation
     - **Reconfiguration** (view rotation): Committee member rotation
     - **BrickLayer** (genesis bootstrap): Initial ledger creation
   - FSM context provides leaf actions
   - Location: `choam/src/main/java/com/hellblazer/delos/choam/fsm/`

7. **Block Production and Consensus**
   - Ethereal (Aleph-BFT) produces pre-blocks continuously
   - Pre-blocks without transactions are discarded in quiescence
   - Pre-blocks contribute to counters driving view reconfiguration
   - Consensus driven by Ethereal DAG: `Dag.validate()` checks BFT (f < n/3)
   - Location: `choam/src/main/java/com/hellblazer/delos/choam/CHOAM.java`

8. **Reliable Block Distribution**
   - ReliableBroadcaster using gossip + ring-based 2/3+1 broadcast
   - Only committee members produce blocks; others receive via broadcast
   - Bounded message buffer with garbage collection
   - Messages age out; periodic rebroadcast during quiescence
   - Location: `choam/src/main/java/com/hellblazer/delos/choam/comm/`

9. **Bootstrapping and Checkpointing**
   - **Checkpoints**: Special blocks that snapshot current state
   - Previous blocks before checkpoint can be discarded
   - View reconfiguration chain maintained to genesis (proves provenance)
   - **Bootstrap Process**: Join group → wait for anchor block → assemble checkpoint via gossip → restore state → process deferred blocks
   - Network load distributed across membership (no bottleneck)
   - Location: `choam/src/main/java/com/hellblazer/delos/choam/support/Bootstrapper.java`

**Rationale**

**Why Committee-based Instead of Quorum-of-All?**

- **Efficiency**: Only f < n/3 committee members produce blocks, not all members
- **Scalability**: Linear log with distributed replication avoids Kafka bottleneck
- **Leaderless**: View reconfiguration and block production both asynchronous
- **Rotation**: Pseudo-random committee selection ensures fair load distribution

**Why Asynchronous with Ethereal?**

- **Liveness**: Ethereal (Aleph-BFT) drives consensus asynchronously
- **Tightly Coupled**: CHOAM uses Ethereal for consensus, not loosely coupled (unlike BFT-SMART)
- **Leaderless**: Ethereal is inherently leaderless and asynchronous
- **Primitive Unit**: Uses smaller units (pre-blocks) than full blocks for efficiency

**Why Ephemeral Keys Per View?**

- **Security**: Keys deleted after view change (forward secrecy)
- **Never Reused**: Prevents key material exposure across views
- **Membership Proof**: Join keys recorded in Reconfiguration block for provenance

**Why Checkpointing?**

- **Bootstrap Efficiency**: Eliminates need to replay full ledger on join
- **Garbage Collection**: Enables discarding pre-checkpoint blocks
- **Distributed Assembly**: Gossip-based checkpoint assembly distributes load
- **Provenance**: View reconfiguration chain proves checkpoint validity

**Security Properties Guaranteed**

1. **Byzantine Tolerance**: Up to f < n/3 Byzantine committee members
2. **Total Order**: All honest members process blocks in same order
3. **Atomicity**: View changes synchronized across all members
4. **Checkpoint Validity**: Provable via view reconfiguration chain to genesis
5. **Join Safety**: New members only join after consensus within current committee
6. **State Consistency**: Deferred blocks processed after checkpoint restoration

**Design Integration Points**

1. **Fireflies**: Provides membership Context for committee selection via bftSubset
2. **Ethereal**: Provides asynchronous consensus (pre-blocks)
3. **Tron**: FSM framework driving state machines (Mercantile, Earner, Reconfiguration, BrickLayer)
4. **Cryptography**: Signatures, digests, HexBloom for view identity
5. **sql-state**: Processes CHOAM transaction blocks as DDL/DML commands
6. **Stereotomy**: Ephemeral key generation and management per view

**Consequences**

**Positive**:
- Asynchronous, leaderless consensus enabling continuous block production
- Efficient bootstrapping via checkpointing and distributed gossip
- Fair load distribution through pseudo-random committee rotation
- Totally ordered transactions suitable for state machine replication
- Compatible with decentralized identity (Stereotomy ephemeral keys)

**Negative**:
- More complex than centralized ledger (multiple block types, view changes)
- View reconfiguration adds latency during membership changes
- Requires Ethereal consensus backend (not standalone)
- Ephemeral key management per view adds operational complexity

**Alternative Approaches Considered**

1. **Kafka-style Leader Election**
   - Simpler partition leadership model
   - Leader bottleneck; not truly leaderless
   - Requires separate failure detection
   - Rejected: Inconsistent with Fireflies leaderless design

2. **Quorum-of-All Voting**
   - Every member votes on blocks
   - Terrible scalability for large membership
   - State machine replication overhead proportional to membership
   - Rejected: Defeats purpose of committee model

3. **Static Committee**
   - No view reconfiguration
   - Unfair load distribution
   - Members can't leave/join dynamically
   - Rejected: Inconsistent with dynamic membership model

**Implementation Status**

| Component | Status | Coverage | Notes |
|-----------|--------|----------|-------|
| CHOAM interface | ✅ COMPLETE | Core operations | Transaction submission, block acceptance |
| Committee selection | ✅ COMPLETE | Pseudo-random via hash | Context.bftSubset() integration |
| Block types | ✅ COMPLETE | All 5 types | Transaction, Genesis, Assemble, Reconfiguration, Checkpoint |
| FSM model | ✅ COMPLETE | 4 state machines | Tron integration complete |
| View reconfiguration | ✅ COMPLETE | Async join protocol | Ephemeral key management |
| Reliable broadcast | ✅ COMPLETE | 2/3+1 gossip | Ring-based distribution |
| Checkpointing | ✅ COMPLETE | Snapshot + restore | Bootstrap integration |
| Security hardening | ✅ COMPLETE | BFT validation | Dag.validate() enforcement |

**Test Coverage**

- Block production and ordering (deterministic)
- View reconfiguration with Byzantine failures
- Checkpoint creation and recovery
- Bootstrap process with new members
- Reliable broadcast delivery
- Ephemeral key management

**Metrics**

CHOAM exposes operational metrics for monitoring consensus health via Dropwizard Metrics.

| Metric | Type | Description | Healthy Range |
|--------|------|-------------|----------------|
| `choam_blocks_committed` | Counter | Total blocks successfully committed to ledger | Increasing monotonically |
| `choam_consensus_latency` | Timer | Time from proposal to block commitment | p95 < 500ms |
| `choam_pending_transactions` | Gauge | Transactions awaiting commitment | < 50 |
| `choam_batch_size` | Histogram | Transactions per committed block | 50-100 |
| `choam_view_changes` | Counter | Total view reconfigurations | 0 in stable cluster |
| `choam_checkpoint_blocks` | Counter | Snapshots created for recovery | Occasional |
| `choam_committee_size` | Gauge | Current BFT committee membership | 3f+1 for f Byzantine members |
| `choam_byzantine_tolerance` | Gauge | Tolerance for Byzantine members | f < n/3 |

**Alert Thresholds**

- Consensus Latency p95 > 1s: Investigate network, GC, or Byzantine behavior
- Pending Txns > 500: Throughput bottleneck, consider increasing batch_size
- Block Commit Rate < 5 blocks/sec: Consensus stalling, check for Byzantine activity
- View Changes > 1 per hour: Network instability or Byzantine activity

**Related Guides**

- [MONITORING_GUIDE.md](../../docs/MONITORING_GUIDE.md): Comprehensive metrics monitoring
- [TROUBLESHOOTING_GUIDE.md](../../docs/TROUBLESHOOTING_GUIDE.md): Consensus troubleshooting
- [ADR-0005: Deterministic SQL State](0005-deterministic-sql-state.md): State machine execution metrics

**References**

- Consensus papers: https://www.researchgate.net/profile/Alysson_Bessani/publication/254037731_From_Byzantine_Consensus_to_BFT_State_Machine_Replication_A_Latency-Optimal_Transformation and https://arxiv.org/abs/2004.14527
- Core Classes:
  - `choam/src/main/java/com/hellblazer/delos/choam/CHOAM.java`
  - `choam/src/main/java/com/hellblazer/delos/choam/Committee.java`
  - `choam/src/main/java/com/hellblazer/delos/choam/Session.java`
  - `choam/src/main/java/com/hellblazer/delos/choam/ViewAssembly.java`
  - `choam/src/main/java/com/hellblazer/delos/choam/fsm/Combine.java`
- README: `choam/README.md`
- Test Suite: `choam/src/test/java/com/hellblazer/delos/choam/`

---

**Decision Made By**: Quality Initiative Phase 1b Team
**Last Updated**: 2026-01-06
**Status**: ACCEPTED - Implementation complete, production-ready
