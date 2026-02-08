# CHOAM Glossary

Reference guide for CHOAM terminology, concepts, and acronyms.

---

## Table of Contents

- [Protocol Concepts](#protocol-concepts)
- [Components](#components)
- [State Machine States](#state-machine-states)
- [Byzantine Fault Tolerance](#byzantine-fault-tolerance)
- [Cryptographic Concepts](#cryptographic-concepts)
- [Acronyms](#acronyms)
- [Whimsical Terms](#whimsical-terms)

---

## Protocol Concepts

### View
A **View** represents a specific configuration of the CHOAM committee with a unique membership composition and epoch. Each view has:
- **View ID**: Unique digest identifying the view (XOR of previous assembly block hash and consensus diadem)
- **Committee Members**: Set of members selected for this view via consistent hashing
- **View Keys**: Ephemeral cryptographic keys generated per member for this view only
- **View Epoch**: Sequential view number in the chain of reconfigurations

Views change periodically through the **View Reconfiguration Protocol**, ensuring committee rotation and load balancing across the membership.

**Related**: Committee, View Reconfiguration, Diadem, Assembly Block

---

### Epoch
A monotonically increasing counter representing the current view number. Each view reconfiguration increments the epoch. Used for:
- Ordering view changes chronologically
- Resolving stale state from old views
- Tracking committee rotation history

**Related**: View, View Reconfiguration

---

### Committee
A **Committee** is a subset of the total membership selected to handle consensus for a specific view. Committees provide:
- **Byzantine Fault Tolerance**: Minimum n ≥ 3f+1 members to tolerate f Byzantine failures
- **Load Distribution**: Multiple committees can process different key ranges in parallel
- **Horizontal Scaling**: Total throughput increases with number of committees

Committee selection uses **consistent hashing** on the view ID to deterministically select members from the Fireflies ring.

**Related**: View, Quorum, Majority, BFT

---

### Genesis
The **Genesis** block is the first block in a CHOAM chain, bootstrapping the initial view and membership. Genesis blocks contain:
- Founding members and their identifiers
- Initial configuration parameters
- Genesis view ID (typically a well-known digest)
- Foundation seal (cryptographic proof of genesis)

All subsequent blocks trace their provenance back to genesis through the view reconfiguration chain.

**Related**: Bootstrap, Foundation Seal, Genesis View

---

### Checkpoint
A **Checkpoint** is a snapshot of the CHOAM state at a specific block height, enabling:
- **Fast Bootstrap**: New nodes restore from checkpoint instead of replaying from genesis
- **Storage Pruning**: Blocks before checkpoint can be archived or discarded
- **State Verification**: Merkle root proves state consistency

Checkpoints are created periodically (configurable interval) and contain:
- Block height at checkpoint
- State snapshot (or Merkle root)
- Provenance chain (view reconfiguration blocks back to genesis)

**Related**: Bootstrap, State Snapshot, Block Pruning

---

### Block
A **Block** is a signed unit of replicated state on the CHOAM log. Block types:

| Type | Purpose | Contents |
|------|---------|----------|
| **Transaction Block** | Normal state changes | Client transactions, state updates |
| **Assemble Block** | Initiate view rotation | Consensus view diadem, next committee signal |
| **Reconfiguration Block** | Complete view rotation | View keys from next committee members |
| **Checkpoint Block** | State snapshot | Merkle root, provenance chain |
| **Genesis Block** | Bootstrap chain | Initial configuration, founding members |

All blocks (except genesis) are signed by a quorum (2f+1) of committee members.

**Related**: Transaction, View Reconfiguration, Signature, Quorum

---

### Pre-Block
A **Pre-Block** is a unit of consensus output from Ethereal (Aleph-BFT) that may or may not contain transactions. Pre-blocks are produced continuously to ensure liveness:
- **With transactions**: Assembled into CHOAM block and broadcast
- **Without transactions** (quiescent): Discarded, but contributes to view rotation counters

This ensures joining members can bootstrap even during periods of no transactions.

**Related**: Ethereal, Consensus, Liveness

---

### Quorum
The minimum number of committee members required to make a Byzantine fault-tolerant decision:
- **2f+1**: Standard BFT quorum for most operations (view consensus, block validation)
- **3f+1**: Total committee size to tolerate f Byzantine failures

A quorum ensures that even with f Byzantine failures and f additional crashes, at least one honest member participates.

**Related**: Majority, Committee, Byzantine Fault Tolerance

---

### Majority
Alias for **Quorum** (2f+1). The number of members required to reach consensus. Accessed via `context.majority()`.

**Related**: Quorum, Context

---

### View Reconfiguration
The protocol for rotating committee membership and transitioning to a new view:

1. **Determine Consensus View**: Current committee agrees on Fireflies view for next committee
2. **Produce Assemble Block**: Signals next committee to begin join process
3. **Start Block Production**: Current view produces blocks for view N
4. **Await View Keys**: Wait for 2f+1 of next view members to propose ephemeral keys
5. **Emit Reconfiguration Block**: Finalize view change with next committee's keys

**Key Properties**:
- Asynchronous and leaderless (like all CHOAM operations)
- Ephemeral view keys are generated per view and deleted after view change
- View ID is pseudo-random (XOR of previous block hash and consensus diadem)

**Related**: View, Committee, Assembly Block, Reconfiguration Block, View Keys

---

### Bootstrap
The process of a new node joining an existing CHOAM committee and synchronizing state:

1. **Join Fireflies Membership**: Establish presence in gossip overlay
2. **Wait for Anchor Block**: Receive valid block from external consensus (prevents eclipse attacks)
3. **Assemble Checkpoint** (if available): Gossip-based distributed transfer of checkpoint state
4. **Replay from Genesis** (if no checkpoint): Process all blocks from genesis
5. **Process Deferred Blocks**: Apply blocks received during bootstrap
6. **Ready to Participate**: Join committee and process new blocks

**Security**: Anchor block prevents eclipse attacks by ensuring at least one honest view exists before bootstrap begins.

**Related**: Checkpoint, Anchor Block, Genesis, Gossip

---

### Anchor Block
The first block received by a bootstrapping node that has been validated by external consensus. Serves as a trust anchor preventing eclipse attacks during bootstrap.

**Related**: Bootstrap, Eclipse Attack, Security

---

## Components

### CHOAM
The main **CHOAM** class is the facade coordinating all state machine replication components:
- Lifecycle management (start/stop)
- Session creation for client transactions
- FSM coordination (Mercantile, Earner, Reconfigure, Genesis)
- Metric collection and monitoring

After Phase 3 refactoring: **907 LOC** (reduced from 1,992 LOC).

**Related**: Session, FSM, Producer, Committee

---

### Producer
The **Producer** component drives block production by coordinating between consensus (Ethereal) and CHOAM:
- Batches client transactions
- Proposes blocks to Ethereal consensus
- Receives consensus decisions (pre-blocks)
- Assembles CHOAM blocks from pre-blocks
- Broadcasts blocks via Bounded Epidemic Gossip

**Thread Model**: Operates on Ethereal callback thread (consensus-driven).

**Related**: Ethereal, Pre-Block, Block, Transaction Batching

---

### Committee
The **Committee** component manages the current view's membership and BFT properties:
- Tracks committee members for current view
- Calculates quorum (2f+1) and majority
- Validates member signatures
- Provides member lookup by ID

**Related**: View, Quorum, Membership

---

### Session
A **Session** provides the client API for submitting transactions to CHOAM:
- `submit(Transaction)`: Submit single transaction
- `submitBatch(List<Transaction>)`: Batch submission
- Automatic retry logic on transient failures
- View change handling (routes to current committee)

Sessions are obtained via `choam.session()`.

**Related**: Transaction, Client API

---

### StateHolder
Abstract base class for state management in CHOAM. Encapsulates state snapshots with:
- Immutable snapshots (snapshot at specific height)
- Mutable current state (updated by block processing)
- Concurrency control (read/write locks)
- Checkpoint support

**Implementations**: Various state holders for different CHOAM subsystems.

**Related**: State Snapshot, Checkpoint, Concurrency

---

### Context
A **Context** (from `delos-membership` module) represents a consistent view of membership arranged on multiple rings:
- Members distributed via consistent hashing
- Multiple rings for redundancy (configurable)
- BFT subset selection (`bftSubset()`)
- Successor/predecessor queries

CHOAM uses `DelegatedContext<Member>` to wrap the Fireflies membership context.

**Related**: Fireflies, Membership, Ring, Consistent Hashing

---

### Router
A **Router** (from `delos-archipelago` module) provides point-to-point messaging between members:
- GRPC-based communication
- MTLS security (mutual TLS)
- Service registration and routing
- Rate limiting and backpressure

Used by CHOAM for client transaction submission.

**Related**: GRPC, MTLS, Communication

---

## State Machine States

CHOAM uses the **Tron** finite state machine framework with enum-based state definitions.

### Mercantile States
The **Mercantile** state machine (in `Combine.java`) manages normal CHOAM operation:

| State | Description |
|-------|-------------|
| **INITIAL** | Starting state, awaiting initialization |
| **RECOVERING** | Node is synchronizing state (bootstrap or rejoin) |
| **OPERATIONAL** | Normal operation, processing transactions |
| **RECONFIGURING** | View change in progress |
| **CHECKPOINTING** | Creating checkpoint snapshot |
| **FAILED** | Unrecoverable failure, manual intervention required |

**Transitions triggered by**: Block processing, view changes, checkpoint intervals, failures.

**Related**: FSM, Combine, Tron

---

### Earner States
The **Earner** state machine (in `Driven.java`) manages block production:

| State | Description |
|-------|-------------|
| **AWAIT_VIEW** | Waiting for view consensus |
| **PRODUCING** | Actively producing blocks for current view |
| **DRAINING** | Finishing current view, awaiting reconfiguration |

**Related**: Producer, Block Production

---

### Reconfigure States
The **Reconfigure** state machine manages view rotation:

| State | Description |
|-------|-------------|
| **AWAIT_ASSEMBLY** | Waiting for assemble block |
| **AWAIT_KEYS** | Collecting view keys from next committee |
| **RECONFIGURE** | Emitting reconfiguration block |

**Related**: View Reconfiguration, Assembly Block

---

### Genesis States
The **Genesis** state machine (in `Genesis.java`) manages bootstrap:

| State | Description |
|-------|-------------|
| **AWAIT_GENESIS** | Waiting for genesis block or bootstrap anchor |
| **SYNCHRONIZING** | Synchronizing state from checkpoint or genesis replay |
| **COMPLETE** | Bootstrap complete, transitioning to operational |

**Related**: Bootstrap, Genesis

---

## Byzantine Fault Tolerance

### Byzantine Failure
A **Byzantine Failure** is arbitrary malicious or faulty behavior by a member, including:
- **Equivocation**: Sending conflicting messages to different members
- **Signature Forgery**: Invalid cryptographic signatures
- **State Corruption**: Reporting incorrect state
- **Timing Anomaly**: Abnormal message timing patterns
- **Rate Anomaly**: Excessive message rates (spam)

Byzantine fault tolerance requires n ≥ 3f+1 members to tolerate f Byzantine failures.

**Related**: BFT, Quorum, Byzantine Detection

---

### Byzantine Detection
The process of identifying Byzantine behavior through:
- **Signature Verification**: Validate all cryptographic signatures
- **State Invariant Checks**: Verify state consistency across transitions
- **Timing Analysis**: Detect abnormal message timing patterns
- **Rate Monitoring**: Detect excessive message rates
- **Equivocation Detection**: Identify conflicting messages

**Implementation**: `ByzantineDetectionMapper` and `BFTValidator` interface.

**Related**: BFT Validator, State Validation

---

### Equivocation
Sending conflicting messages to different members (a Byzantine behavior). Example:
- Node A tells Node B: "View ID = X"
- Node A tells Node C: "View ID = Y"

Equivocation is detected by comparing messages from the same source across different recipients.

**Related**: Byzantine Failure, Detection

---

### BFT Validator
Interface abstracting Byzantine fault validation logic:
- `validateBlock(Block, Committee)`: Validate block signatures and content
- `validateSignatures(BlockSignatures)`: Verify cryptographic signatures
- `checkByzantineIndicators(Member)`: Check for Byzantine behavior patterns

**Implementations**: `DefaultBFTValidator` (wraps `ByzantineDetectionMapper`).

**Related**: Byzantine Detection, Validation

---

### State Validation
Optional runtime verification of state machine invariants and preconditions:
- Enabled via `stateValidationEnabled` configuration profile flag
- Validates state transitions satisfy safety properties
- Throws on invariant violations (fail-fast in test/development)
- Logs violations in production (non-blocking)

**Trade-off**: Validation overhead vs early bug detection.

**Related**: State Machine, Configuration Profiles, Invariants

---

## Cryptographic Concepts

### Digest
A cryptographic hash (SHA-256 or BLAKE2b-256) used as a unique identifier for:
- View IDs
- Block hashes
- Member IDs
- Merkle roots

**Properties**: Collision-resistant, deterministic, fixed size (32 bytes default).

**Related**: DigestAlgorithm, Hash Function

---

### Signature
A cryptographic signature proving authenticity and integrity:
- **BLS-12-381**: Used for signature aggregation (efficient batch verification)
- **ED25519**: Used for member identifiers (KERI)

All blocks are signed by a quorum (2f+1) of committee members.

**Related**: BLS, ED25519, Signature Aggregation

---

### View Keys
**Ephemeral cryptographic keys** generated per member per view:
- Created during view join process
- Used to sign blocks in that view
- **Deleted after view change** (by all right-thinking members)
- Never reused across views

Key rotation per view limits the impact of key compromise.

**Related**: View Reconfiguration, Ephemeral Keys, Security

---

### Crown
In BLS signature aggregation, a **Crown** is an aggregate signature from multiple members. CHOAM uses crowns for efficient block validation:
- Multiple individual signatures → single crown signature
- One verification instead of n verifications
- Circuit breaker disables on high failure rate (> 1%)

**Related**: BLS, Signature Aggregation, Batch Verification

---

### Diadem
A **HexBloom** (Bloom filter) representing the consensus view of Fireflies membership. The diadem is:
- Determined by Ethereal consensus
- XORed with previous assembly block hash to create next view ID
- Pseudo-random function ensuring unpredictable committee selection

**Related**: View ID, HexBloom, Fireflies, Consensus

---

## Acronyms

### BFT
**Byzantine Fault Tolerance** - The ability to reach consensus despite arbitrary (Byzantine) failures. Requires n ≥ 3f+1 members to tolerate f failures.

---

### CHOAM
**Committee-based State Machine Replication on Linear Distributed Logs** - The name of this module. (Note: Also a reference to Dune's "Combine Honnete Ober Advancer Mercantiles").

---

### FSM
**Finite State Machine** - A model of computation with discrete states and defined transitions. CHOAM uses FSMs (via Tron framework) for:
- Mercantile (normal operation)
- Earner (block production)
- Reconfigure (view rotation)
- Genesis (bootstrap)

---

### KERI
**Key Event Receipt Infrastructure** - A decentralized identity system used by Delos (via Stereotomy module) for cryptographic identifiers and key rotation.

---

### MTLS
**Mutual TLS** - Transport Layer Security where both client and server authenticate with certificates. Used by CHOAM for all GRPC communication.

---

### GRPC
**Google Remote Procedure Call** - A high-performance RPC framework used for all inter-member communication in CHOAM.

---

### BEG
**Bounded Epidemic Gossip** - The gossip protocol used to disseminate blocks to all committee members. Provides:
- Probabilistic broadcast (epidemic dissemination)
- Bounded memory (fixed-size buffers, garbage collection)
- Byzantine tolerance (2f+1 confirmation threshold)

---

### TOCTOU
**Time-Of-Check Time-Of-Use** - A race condition where state changes between validation and use. CHOAM guards against TOCTOU with:
- Immutable snapshots for validation
- Lock ordering for state updates
- Atomic transitions

---

### SLA
**Service Level Agreement** - Performance guarantees for CHOAM operations:
- Transaction commit latency: p95 < 1s
- View change duration: < 30s
- Block production rate: > 10 blocks/sec
- Consensus availability: > 99.9%

---

## Whimsical Terms

CHOAM borrows terminology from Frank Herbert's *Dune* universe and uses whimsical names for components.

### Combine
**Full name**: "Combine Honnete Ober Advancer Mercantiles" (CHOAM from *Dune*)

In Delos: The `Combine` class implements the **Mercantile** state machine managing normal CHOAM operation. Named for the trading conglomerate in Dune that controlled interstellar commerce.

**Related**: Mercantile, FSM

---

### Mercantile
The primary **FSM** managing CHOAM's normal operation state machine. Named for commercial/trading activities, fitting CHOAM's transaction processing role.

**States**: INITIAL, RECOVERING, OPERATIONAL, RECONFIGURING, CHECKPOINTING, FAILED

**Related**: Combine, State Machine

---

### Earner
The **FSM** managing block production. "Earners" produce value (blocks) for the committee. Whimsical name for the producer state machine.

**States**: AWAIT_VIEW, PRODUCING, DRAINING

**Related**: Producer, Block Production

---

### Bricklayer
Alternative name for the **Genesis** state machine (used in early implementations). "Bricklayers" build foundations (genesis/bootstrap).

**Related**: Genesis, Bootstrap

---

### Driven
The implementation class for the **Earner** state machine. Blocks are "driven" through the consensus process.

**Related**: Earner, Producer

---

### Reconfiguration Coordinator
The component managing view reconfiguration protocol. Coordinates the assembly and reconfiguration of views.

**Related**: View Reconfiguration, Coordinator

---

### Right-Thinking Scotsman
A humorous term for an honest (non-Byzantine) member who follows the protocol correctly:
- Generates valid signatures
- Deletes view keys after view change
- Reports accurate state
- Follows timing and rate limits

Contrast with Byzantine members who may behave arbitrarily.

**Origin**: Playful reference to "No True Scotsman" logical fallacy.

**Related**: Byzantine Failure, Honest Member

---

## Usage Examples

### Linking to Glossary Terms

In documentation, link to glossary terms like this:

```markdown
The [View](#view) reconfiguration process requires a [Quorum](#quorum) of
committee members to agree on the next [Diadem](#diadem).
```

### Cross-References

Glossary terms include "Related" sections linking to related concepts. Follow these to build understanding:

```
View → Committee → Quorum → Byzantine Fault Tolerance
```

---

**Last Updated**: 2026-02-08
**CHOAM Version**: 0.0.6-SNAPSHOT
