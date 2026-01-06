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

## See Also

- **DEPLOYMENT_GUIDE.md**: Deployment terminology and procedures
- **MONITORING_GUIDE.md**: Metrics and monitoring terminology
- **TROUBLESHOOTING_GUIDE.md**: Error scenarios and resolution
- **Architecture Decision Records**: Technical deep-dives
  - ADR-0003: BFT Membership Architecture
  - ADR-0004: Consensus Design (CHOAM)
  - ADR-0005: Deterministic SQL State

---

**Last Updated**: 2026-01-06
**Status**: Production
**Audience**: Users, Operators, Developers
