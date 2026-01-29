# Delos Glossary

This glossary defines key terms and concepts used throughout Delos documentation and architecture.

---

## Core Infrastructure

**Cluster**
A set of Delos nodes (physical or virtual machines) deployed together to provide distributed system services. Typically 3-7 nodes (odd numbers for Byzantine fault tolerance).
- *Context*: Deployment and infrastructure
- *Related*: Node, Member, View
- *Example*: "The production cluster has 7 nodes"

**Node**
A physical or virtual machine instance running the Delos software. Nodes are the infrastructure-level units of a Delos cluster.
- *Context*: Hardware/deployment
- *Related*: Cluster, Member, Process
- *Example*: "node1.delos.local is a physical server with 16 cores and 64GB RAM"

**Member**
A KERI identity participating in the Fireflies view. Members are the protocol-level participants that gossip and reach consensus. Each node runs one or more members.
- *Context*: Protocol and consensus
- *Related*: Node, View, Identity
- *Example*: "7 members are currently in the view"

**Peer**
Another member that a member communicates with via gossip protocol. A peer is a communication partner in the Fireflies ring structure.
- *Context*: Gossip and communication
- *Related*: Member, Gossip
- *Example*: "Send message to peer in next ring position"

**Process**
An application or service running on a Delos node that performs work (queries, commands, transactions). Multiple processes may run on a single node.
- *Context*: Application layer
- *Related*: Node, Member, Enclave
- *Example*: "The secrets manager process running on node1"

---

## Byzantine Fault Tolerance

**Byzantine Fault Tolerance (BFT)**
The ability of a distributed system to reach consensus and maintain correctness even when some members are faulty or malicious. A system tolerating f Byzantine members requires n ≥ 3f + 1 total members.
- *Context*: Consensus and security
- *Formula*: n ≥ 3f + 1 (e.g., 7 members tolerance f=2)
- *Example*: "7-node cluster tolerates 2 Byzantine failures"

**Byzantine Member**
A member that may be faulty, slow, or malicious (arbitrarily deviating from the protocol). Byzantine members can drop messages, send conflicting messages, or attack the protocol.
- *Context*: Failure modes
- *Related*: Byzantine Fault Tolerance
- *Example*: "System remains safe even with 2 Byzantine members"

**Quorum**
A minimum set of members whose agreement is required for a decision. In BFT: quorum = 2f + 1 (majority of 3f+1 members). For 7 nodes: quorum = 5.
- *Context*: Consensus decisions
- *Formula*: Quorum = 2f + 1
- *Example*: "5 out of 7 members must agree to commit a block"

**Byzantine Tolerance (tolerance factor f)**
The maximum number of Byzantine members a system can survive. For n members: f = floor((n-1)/3).
- *Context*: System design
- *Formula*: f < n/3
- *Example*: "With 7 nodes, f=2 (can tolerate 2 failures)"

---

## Membership and Gossip

**View**
An immutable snapshot of agreed-upon membership at a point in time. A view is identified by the hash of its membership (the "crown"). All members in a view can communicate and reach consensus.
- *Context*: Membership protocol
- *Related*: Member, Consensus, View Change
- *Example*: "View {members: [A,B,C,D,E,F,G], hash: abc123...}"

**View Change**
A transition from one membership view to another, triggered by joins or leaves. Only occurs with group consensus via the BFT voting protocol.
- *Context*: Membership dynamics
- *Related*: View, Join, Leave
- *Example*: "Member H joins → View change vote → New view with 8 members"

**Gossip**
The peer-to-peer communication protocol used by Fireflies to propagate state and membership information. Gossip is asynchronous, probabilistic, and Byzantine fault-tolerant.
- *Context*: Communication protocol
- *Related*: Peer, Fireflies, Ring
- *Example*: "Gossip latency p95 < 200ms"

**Gossip Latency**
The time required for information to propagate to all members via gossip. Lower latency = faster consensus.
- *Context*: Performance
- *Related*: Gossip, Network
- *Example*: "Gossip latency p95: 45ms (excellent)"

**Ring Structure**
The deterministic ordering of members in a Fireflies view, used for:
1. Gossip topology (ring topology, not all-to-all)
2. BFT subset selection for voting
3. Deterministic peer selection

Members are arranged in a ring based on a hash of their identity.
- *Context*: Fireflies design
- *Related*: View, BFT Subset
- *Example*: "In ring order: A→B→C→D→E→F→G→A"

**BFT Subset**
A small set of members (typically 13-42) selected deterministically via ring structure to vote on view changes and reaching decisions. Only BFT subset votes are counted, reducing voting overhead.
- *Context*: Voting and consensus
- *Related*: Ring Structure, Quorum
- *Example*: "13 members in BFT subset for 7-node cluster"

---

## Join and Leave

**Join**
The process by which a new member becomes part of a cluster. Two-phase protocol:
1. New member contacts a seed (any member)
2. Seed redirects to appropriate members
3. New member acquires view membership via gossip
4. View change vote includes new member in next view

- *Context*: Membership dynamics
- *Related*: Seed, Bootstrap, View Change
- *Example*: "Member H joins the cluster via node1 seed"

**Leave**
Graceful departure of a member from the cluster. Triggers a view change vote to remove the departing member.
- *Context*: Membership dynamics
- *Related*: View Change
- *Example*: "Member G voluntarily leaves the cluster"

**Seed**
An existing cluster member that new members contact during the join protocol. A seed is the first contact point for bootstrap.
- *Context*: Join protocol
- *Related*: Join, Bootstrap
- *Example*: "Use node1:50051 as seed to join cluster"

**Bootstrap**
The process of starting a new cluster or adding a new member to an existing cluster. Requires:
1. Identity provisioning (KERI keystore)
2. Network connectivity to seeds
3. Complete membership acquisition via gossip

- *Context*: Startup and scaling
- *Related*: Join, Seed, Identity
- *Example*: "Bootstrap new node1 cluster with 7 members"

**Shun**
A temporary rejection of a member's gossip messages, typically due to Byzantine behavior. The member can rejoin after proving correct behavior.
- *Context*: Byzantine handling
- *Related*: Byzantine Member, View Change
- *Example*: "Shun member if signature verification fails"

---

## Consensus and State

**Consensus**
Agreement among a BFT quorum of members on a decision (block commitment, view change, checkpoint). Asynchronous Byzantine consensus via CHOAM layer.
- *Context*: Agreement
- *Related*: Quorum, CHOAM, Block
- *Example*: "Consensus latency p95: 320ms"

**CHOAM**
The consensus layer providing committee-based state machine replication. Combines ideas from two papers: BFT consensus transformation and blockchain fundamentals.
- *Context*: Consensus protocol
- *Related*: Committee, Block, Consensus
- *Example*: "CHOAM committed 1000 blocks in the last minute"

**Committee**
The set of members responsible for a view's consensus decisions. Committee = BFT subset of current view.
- *Context*: Consensus
- *Related*: BFT Subset, View
- *Example*: "13-member committee votes on block commitment"

**Block**
A unit of ordered transactions committed to the Delos ledger via consensus. Types:
- **Transaction Block**: Client-submitted DDL/DML
- **Genesis Block**: Initial ledger block
- **Assemble Block**: Signals view change
- **Reconfiguration Block**: Completes view change
- **Checkpoint Block**: Snapshots state for recovery

- *Context*: Ledger
- *Related*: Ledger, Consensus, Transaction
- *Example*: "Block 12345: 87 transactions, hash: def456..."

**Consensus Latency**
Time from transaction proposal to block commitment. Metric: choam_consensus_latency. Healthy: p95 < 500ms.
- *Context*: Performance
- *Related*: CHOAM, Block
- *Example*: "Consensus latency p95: 234ms"

**Ledger**
The append-only log of committed blocks. Each block contains transactions ordered by consensus.
- *Context*: State management
- *Related*: Block, Transaction, Consistency
- *Example*: "Ledger height: 45,230 blocks"

**State Machine**
The application logic that processes transactions from the ledger in order, maintaining deterministic state. See SQL-State.
- *Context*: Application
- *Related*: Ledger, SQL-State, Transaction
- *Example*: "SQL state machine materialized view"

**SQL-State**
Delos's state machine implementation using SQL databases (H2) with deterministic execution (block hash seeding for random functions).
- *Context*: State management
- *Related*: State Machine, Ledger, H2
- *Example*: "SQL-State height: 45,230 blocks"

**Deterministic Execution**
State machine execution that produces identical results on all replicas given identical input transactions. Achieved via block hash seeding of SQL RANDOM() and TIME() functions.
- *Context*: Replication
- *Related*: SQL-State, Block Hash
- *Example*: "All nodes execute same transaction sequence identically"

**Checkpoint**
A snapshot of state at a specific block height, used for efficient recovery and bootstrap. Contains:
- Block number
- State hash
- Serialized state machine snapshot

- *Context*: Recovery
- *Related*: Recovery, Bootstrap, Block
- *Example*: "Checkpoint at block 40,000, size: 234 MB"

---

## Identity and Security

**Identity**
A KERI-based decentralized identifier (SAI - Self-Addressing Identifier) for each member. Provides:
- Cryptographic proof of identity
- Key rotation history (KERL)
- Witness attestations

- *Context*: Security
- *Related*: KERI, SAI, KERL
- *Example*: "Member identity: EJxOV...mL1k"

**KERI**
Key Event Receipt Infrastructure - a decentralized identity and key management system. Delos uses KERI via the Stereotomy module.
- *Context*: Identity protocol
- *Related*: Identity, Stereotomy, SAI
- *Example*: "KERI keypair: Ed25519"

**SAI**
Self-Addressing Identifier - a KERI identifier derived from the cryptographic hash of the inception event. Used to address members.
- *Context*: Identity
- *Related*: KERI, Identity
- *Example*: "SAI: EJxOV...mL1k"

**KERL**
Key Event Receipt Log - a Delos database storing the complete history of key events and rotations. Authoritative for identity verification.
- *Context*: Identity persistence
- *Related*: KERI, Identity, Key Rotation
- *Example*: "KERL size: 50 MB"

**Key Rotation**
The process of replacing a member's signing key while maintaining identity continuity via KERI. Old key signs the new key in the KERL.
- *Context*: Identity and security
- *Related*: KERI, Key Management
- *Example*: "Rotate keys quarterly for security"

**Witness**
A KERI role - a trusted external service that attests to key events, providing additional security guarantees for identity.
- *Context*: KERI security
- *Related*: KERI, Attestation
- *Example*: "Gorgoneion acts as witness for identity bootstrap"

**MTLS**
Mutual Transport Layer Security - TLS with client certificate authentication. Delos uses MTLS for gRPC communication, with certificates derived from KERI identities.
- *Context*: Network security
- *Related*: gRPC, Identity
- *Example*: "gRPC server listening on port 50051 with MTLS"

---

## Data Structures

**HexBloom**
An authenticated Bloom filter used in Fireflies to efficiently encode membership. Combines:
- Bloom filter for space efficiency
- HMAC authentication for integrity

Used in join protocol and view representation.
- *Context*: Membership encoding
- *Related*: Bloom Filter, View
- *Example*: "HexBloom size: 256 bytes for 7-member view"

**Crown**
The HexBloom representation of a view's membership. The hash of the crown is the view identifier.
- *Context*: View identity
- *Related*: HexBloom, View, View Identity
- *Example*: "Crown hash: abc123def456..."

**View Identity**
The cryptographic hash of the crown (HexBloom). Uniquely identifies a view and its membership.
- *Context*: View identification
- *Related*: Crown, View
- *Example*: "View identity: hash(crown)"

---

## Network and Performance

**Network Partition**
A network failure that splits the cluster into disconnected groups that can't communicate. The system remains safe (no inconsistency) but may lose liveness (progress).
- *Context*: Failure modes
- *Related*: Byzantine Fault Tolerance, Liveness
- *Example*: "Network partition: {A,B,C} vs {D,E,F,G}"

**Gossip Cycle**
One complete round of gossip where each member exchanges state with selected peers. Gossip cycles are asynchronous and probabilistic.
- *Context*: Communication
- *Related*: Gossip, Latency
- *Example*: "Gossip cycle completes every 100ms"

**Message Round Trip Time (RTT)**
Latency for a single peer message exchange. Metric: fireflies_message_round_trip_time.
- *Context*: Network performance
- *Related*: Gossip, Latency
- *Example*: "RTT p95: 45ms"

**Throughput**
The rate at which transactions are committed to the ledger. Measured in blocks/sec or transactions/sec.
- *Context*: Performance
- *Related*: Block, Consensus Latency, Batch Size
- *Example*: "Throughput: 12 txns/sec"

**Batch Size**
The number of transactions committed in a single block. Larger batches → higher throughput, longer latency.
- *Context*: Performance tuning
- *Related*: Block, Throughput, Latency
- *Example*: "Batch size: 100 transactions/block"

---

## Operational

**View Stability**
The length of time a view persists without changes. Healthy clusters have view stability: hours or days between view changes.
- *Context*: Operational health
- *Related*: View Change, Gossip Latency
- *Example*: "View stability: 3 days without view changes"

**Suspected Member**
A member that is not responding to gossip or whose messages fail validation. Members are temporarily suspected and eventually shunned if behavior doesn't improve.
- *Context*: Failure detection
- *Related*: Shun, Gossip, Byzantine Member
- *Example*: "2 members suspected due to network latency"

**Health Check**
A verification that a node is operational and participating correctly:
- Can reach other nodes
- Gossip working
- Consensus progressing
- Database consistent

- *Context*: Monitoring
- *Related*: Monitoring, Metrics
- *Example*: "Health check: 7/7 nodes healthy"

**Recovery**
The process of restoring a failed node or recovering from data corruption using:
- Backup data
- KERL/ledger replay
- Checkpoint restoration

- *Context*: Disaster recovery
- *Related*: Checkpoint, Backup, Consistency
- *Example*: "Recovery completed: node1 rejoined cluster"

**Enclave**
A GraalVM isolate-based process isolation boundary. Multiple enclaves can run on a single node, each with separate memory and identity.
- *Context*: Multi-tenancy
- *Related*: Process, Node, Multi-tenancy
- *Example*: "3 enclaves running on node1"

---

## Failure Modes and Recovery

**Crash Fault**
A node that permanently stops executing (hardware failure, power loss, etc.). Distinguished from Byzantine (continues but misbehaves).
- *Context*: Failure classification
- *Related*: Byzantine Fault, Failure Detection
- *Example*: "Node crashed due to OOM, requires restart"

**Quorum Loss**
Situation where fewer than (2f+1) honest members remain operational, making consensus impossible. System halts (safety maintained, liveness lost).
- *Context*: Failure modes
- *Formula*: For 7-node cluster (f=2), quorum loss when ≤4 nodes online
- *Example*: "Quorum lost: only 3 of 7 nodes responding"

**Byzantine Attack / Equivocation**
Attack where Byzantine member sends conflicting messages (e.g., votes for both Block A and Block B at same height). Detected and excluded via consensus.
- *Context*: Byzantine failure
- *Related*: Byzantine Member, Signature Verification
- *Example*: "Member attempted equivocation, shunned from view"

**Cascading Failure**
Multiple failures in sequence causing progressive system degradation. Example: 1 failure (degraded), 2 failures (more degraded), 3 failures (quorum lost).
- *Context*: Failure analysis
- *Related*: Quorum Loss, Failure Modes
- *Example*: "3 nodes failed in 5 minutes, cluster halted"

**Clock Skew**
Discrepancy between member's clock and cluster's synchronized time. If > clockSkewTolerance (typically 500ms), member's messages rejected.
- *Context*: Time-based failures
- *Related*: NTP, Timestamp Validation
- *Example*: "Clock skew 2 seconds, member isolated"

---

## Capacity Planning and Scaling

**Horizontal Scaling**
Adding more nodes to cluster to increase capacity. Increases fault tolerance (f increases) and quorum size.
- *Context*: Capacity planning
- *Related*: Vertical Scaling, Cluster Size
- *Example*: "Scale from 5 to 7 nodes to increase f from 1 to 2"

**Vertical Scaling**
Increasing resources (CPU, RAM, disk) on existing nodes. Doesn't change fault tolerance but improves throughput.
- *Context*: Capacity planning
- *Related*: Horizontal Scaling
- *Example*: "Increase RAM from 16GB to 32GB per node"

**Capacity Planning**
Proactive process of monitoring metrics and scaling before hitting hard limits. Uses triggers (e.g., scale at 70% CPU, not 95%).
- *Context*: Operations
- *Related*: Metrics, Monitoring, Scaling Triggers
- *Example*: "Monthly capacity review shows 60% growth rate"

**Scaling Trigger**
Specific metric threshold that indicates need to scale. Examples: CPU > 70%, Memory > 85%, Disk growth runway < 30 days.
- *Context*: Capacity planning
- *Related*: Metrics, Monitoring
- *Example*: "CPU scaling trigger hit: 72% usage for 10 minutes"

**Runway Calculation**
Estimation of time until resource exhaustion. Formula: Free_Resource / Daily_Growth_Rate.
- *Context*: Disk capacity planning
- *Example*: "100GB disk, 1GB/day growth = 100 day runway"

**Committee Scaling**
In large clusters, dividing members into multiple committees, each handling different key ranges. Reduces consensus complexity.
- *Context*: Large-scale deployments
- *Related*: CHOAM, Committee
- *Example*: "16-node cluster split into 2 committees of 8 members each"

---

## Advanced Concepts

**Deterministic State Machine Replication**
Replicated state machines that produce identical state from identical transaction sequences, regardless of timing or Byzantine members. Achieved via:
- Seeded deterministic random generators
- Frozen clock for state execution
- Canonical transaction ordering via consensus
- Deterministic SQL execution

- *Context*: Core architecture
- *Related*: SQL-State, CHOAM, Consensus
- *Example*: "All 7 nodes execute same 1000-transaction block identically"

**Total Ordering**
Guarantee that all honest nodes see transactions in same order. Produced by Ethereal consensus.
- *Context*: Safety guarantee
- *Related*: Ethereal, Consensus, Ledger
- *Example*: "Transaction A always before Transaction B on all nodes"

**Quorum Intersection Property**
Mathematical guarantee that any two quorums have at least one honest member in common. Ensures safety even with Byzantine members.
- *Context*: Byzantine protocol design
- *Related*: Quorum, Byzantine Fault Tolerance
- *Example*: "Any two 5-member quorums in 7-node cluster overlap in ≥3 members"

**Virtually Synchronous Abstraction**
Abstraction where membership appears fully synchronized to applications - all nodes have identical view of membership at each logical time.
- *Context*: Application semantics
- *Related*: Fireflies, View, Abstraction
- *Example*: "Application code doesn't need to handle partial membership"

**DAG-Based Consensus**
Consensus protocol where nodes propose transactions into a Directed Acyclic Graph (DAG) showing causal relationships. Achieves high throughput without leader.
- *Context*: Consensus design
- *Related*: Ethereal, Aleph-BFT
- *Example*: "DAG units 5000-8000/second, converts to 1-10 blocks/second"

---

## Appendix: Terms by Domain

### For Operators
- Cluster, Node, Member, Health Check, Recovery
- Throughput, Consensus Latency, Gossip Latency
- Byzantine Fault Tolerance, Quorum, View
- Scaling (Horizontal/Vertical), Capacity Planning

### For Security Teams
- Byzantine Member, Byzantine Fault Tolerance, Equivocation
- Identity, KERI, KERL, Key Rotation
- MTLS, Witness, Signature
- Threat Model, Attack Surface

### For Developers
- Member, Process, Transaction
- Ledger, Block, State Machine
- Deterministic Execution, Checkpoint
- SQL-State, CHOAM, Ethereal

### For Architects
- 3f+1 formula, Quorum, Quorum Intersection
- Ring Structure, BFT Subset
- Totally Ordered, Virtually Synchronous
- Cascading Failure, Network Partition

---

## Cross-References

See related documentation:
- **FAILURE_MODES.md** - Detailed failure mode descriptions, detection, recovery
- **CAPACITY_PLANNING.md** - Metrics-driven scaling procedures
- **ARCHITECTURE.md** - System design and layers
- **SECURITY_THREAT_MODEL.md** - Threat analysis and mitigations
- **MONITORING_AND_ALERTING.md** - Metrics and dashboards
- **DEPLOYMENT_GUIDE.md** - Deployment terminology and procedures
- **TROUBLESHOOTING_GUIDE.md** - Error scenarios and resolution

---

**Last Updated**: 2026-01-27
**Status**: Production
**Audience**: Users, Operators, Developers, Security Teams
**Version**: 1.1 (Enhanced with failure modes and capacity planning terms)
