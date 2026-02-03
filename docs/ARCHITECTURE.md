# Delos Architecture

This document provides visual architecture diagrams and explanations of the Delos distributed systems platform.

---

## Table of Contents

- [High-Level Overview](#high-level-overview)
- [Layer Architecture](#layer-architecture)
- [Module Dependencies](#module-dependencies)
- [Consensus Data Flow](#consensus-data-flow)
- [Network Topology](#network-topology)
- [Key Abstractions](#key-abstractions)

---

## High-Level Overview

Delos is a **multi-tenant distributed database platform** providing Byzantine fault-tolerant consensus, decentralized identity management, and replicated SQL state machines. It's designed for wide-area distributed systems requiring high security and verifiable operations.

**Core Capabilities**:
- Byzantine fault tolerance (BFT) with 3f+1 quorum
- KERI-based decentralized identity (no central CA)
- Replicated SQL databases with JDBC interface
- Relation-based access control (Zanzibar-style)
- Multi-tenant isolation with GraalVM

---

## Byzantine Fault Tolerance Model

Delos operates on a Byzantine Fault Tolerance (BFT) security model where:

- **Quorum Threshold**: `3f+1` nodes required (where `f` = max number of simultaneous Byzantine failures)
- **Fault Tolerance**: Can tolerate up to `f` malicious (Byzantine) nodes for cluster of `n >= 3f+1` nodes
- **Safety Guarantee**: All honest nodes converge to identical state despite up to `f` Byzantine failures
- **Liveness Assumption**: Network is partially asynchronous (eventual message delivery)

**Common Configurations**:
| Nodes (n) | Max Failures (f) | Quorum (2f+1) | BFT Subset | Example |
|-----------|-----------------|--------------|-----------|---------|
| 4 | 1 | 3 | 1-4 | Development cluster |
| 7 | 2 | 5 | 2-4 | Test cluster |
| 13 | 4 | 9 | 4-5 | Small production |
| 100+ | 33+ | 67+ | 42 | Large production |

---

## Layer Architecture

Delos is organized into four logical layers, each building on the layer below:

```mermaid
graph TD
    subgraph APP["APPLICATION LAYER"]
        delphinius["delphinius<br/>(RBAC)"]
        model["model<br/>(domains)"]
        tron["tron<br/>(FSM)"]
        leyden["leyden<br/>(platform)"]
    end

    subgraph CONSENSUS["CONSENSUS & STATE LAYER"]
        sqlstate["sql-state<br/>(JDBC)"]
        choam["choam<br/>(SMR)"]
        ethereal["ethereal<br/>(Aleph-BFT)"]
    end

    subgraph MEMBERSHIP["MEMBERSHIP & IDENTITY LAYER"]
        fireflies["fireflies<br/>(overlay)"]
        stereotomy["stereotomy<br/>(KERI)"]
        thoth["thoth<br/>(DHT)"]
        gorgoneion["gorgoneion<br/>(attest)"]
    end

    subgraph INFRA["CORE INFRASTRUCTURE LAYER"]
        crypto["crypto<br/>(digest)"]
        memberships["membership<br/>(context)"]
        protocols["protocols<br/>(MTLS)"]
        grpc["grpc<br/>(protos)"]
    end

    APP --> CONSENSUS
    CONSENSUS --> MEMBERSHIP
    MEMBERSHIP --> INFRA
```

### Layer 1: Core Infrastructure

**Purpose**: Fundamental building blocks used by all other layers.

- **cryptography**: Self-describing cryptographic primitives
  - `Digest`: Content-addressed identifiers
  - `Signature`: Self-describing digital signatures
  - `Identifier`: Self-describing identifiers for entities
  - Bloom filters for membership sets

- **memberships**: Context abstraction and ring communication
  - `Context`: Logical grouping of members (like cluster ID)
  - `Member`: Identity and location of a node
  - Ring-based communication patterns (successor/predecessor)
  - gRPC routing by context

- **protocols**: gRPC MTLS fundamentals
  - MTLS with KERI certificate validation
  - Rate limiters and flow control
  - Service fundamentals

- **grpc**: Centralized Protocol Buffer definitions
  - All `.proto` files for inter-service communication
  - Generated gRPC stubs and message classes

### Layer 2: Membership & Identity

**Purpose**: Establish and maintain secure membership with decentralized identity.

- **fireflies**: Byzantine fault-tolerant membership service
  - Stable, virtually synchronous views (Rapid-inspired)
  - BFT join protocol (requires majority agreement)
  - Ring-based gossip for state dissemination
  - Failure detection and view consensus

- **stereotomy**: KERI implementation for decentralized identity
  - Key Event Receipt Infrastructure (no central CA)
  - Cryptographic rotation and recovery
  - Self-certifying identifiers
  - MTLS certificate validation

- **thoth**: Distributed hash table for key management
  - Stores KERI key event logs (KELs)
  - Chord-based routing
  - Replicated storage with BFT

- **gorgoneion**: Identity bootstrapping and attestation
  - Secure node provisioning
  - Attestation of node identity
  - Secrets management

### Layer 3: Consensus & State

**Purpose**: Achieve consensus on transaction order and maintain replicated state.

- **ethereal**: Aleph-BFT asynchronous consensus
  - DAG-based consensus (no leader bottleneck)
  - Asynchronous (no timing assumptions)
  - BFT with 3f+1 quorum
  - Outputs totally ordered blocks

- **choam**: Committee-based state machine replication
  - Linear log of ordered transactions
  - Committee rotation for load distribution
  - Reconfiguration support (membership changes)
  - Checkpoint and recovery

- **sql-state**: JDBC-accessible SQL state machines
  - H2 database with deterministic execution
  - Liquibase for schema management
  - JOOQ for type-safe queries
  - Replicated across all nodes

### Layer 4: Application

**Purpose**: Application-level services built on consensus and state.

- **delphinius**: Relation-based access control (Zanzibar-style)
  - Namespace, object, relation, subject tuples
  - Transitive permission checks
  - Backed by SQL-State

- **tron**: Finite state machine framework
  - Type-safe FSM using Java enums
  - Transition validation
  - State persistence

- **model**: Process domains and multi-tenant sharding
  - Domain abstraction for multi-tenancy
  - Shard management
  - Process isolation

- **leyden**: Platform support features
  - Additional platform-specific capabilities

**Isolation Support**:
- **isolates**: GraalVM isolate-based enclaves
  - True multi-tenant isolation at OS level
  - Separate heap spaces per tenant
  - Requires GraalVM 24.0.2+

---

## Protocol Comparison: Ethereal vs CHOAM vs Fireflies

Three core protocols solve different problems in the consensus stack:

| Aspect | **Ethereal** (Layer 3) | **CHOAM** (Layer 3) | **Fireflies** (Layer 2) |
|--------|--------|-------|---------|
| **Purpose** | Asynchronous consensus | State machine replication | Membership & gossip |
| **Problem Solved** | Total ordering of transactions | Replicated state with view changes | Secure membership discovery |
| **Consensus Model** | DAG-based (no leader) | Committee-based SMR | BFT gossip + voting |
| **Unit of Order** | Blocks via DAG validation | Linear transaction log | View changes + membership |
| **Timing Model** | Asynchronous (no clocks needed) | Partially synchronous | Asynchronous with timeouts |
| **Quorum Required** | 2f+1 honest nodes in committee | 2f+1 in committee | BFT subset voting (2f+1) |
| **Key Innovation** | Parallel unit production (no bottleneck) | Committee rotation per view | BFT subset voting reduces voting from 3f+1→13-42 nodes |
| **Fault Tolerance** | Tolerates f Byzantine nodes | Tolerates f Byzantine nodes | Tolerates f Byzantine nodes |
| **Output** | Totally ordered blocks | Totally ordered transactions | Stable membership + gossip state |
| **View Changes** | Implicit (DAG continues) | Explicit (Assemble block) | Explicit (observation voting) |

**How They Work Together**:
1. **Fireflies** maintains stable membership view (which nodes are in cluster)
2. **CHOAM** routes transactions to committee, receives ordered blocks from Ethereal
3. **Ethereal** takes transaction batches, produces totally ordered blocks via DAG consensus
4. **SQLState** executes blocks deterministically → all nodes identical state

---

## Module Dependencies

This diagram shows the primary dependencies between major modules:

```mermaid
graph TD
    delphinius[delphinius] --> sqlstate[sql-state]
    tron[tron] --> choam[choam]
    model[model] --> choam

    sqlstate --> choam
    choam --> ethereal[ethereal]

    ethereal --> fireflies[fireflies]
    ethereal --> stereotomy[stereotomy]

    fireflies --> cryptography[cryptography]
    fireflies --> protocols[protocols]
    fireflies --> grpc[grpc]
    fireflies --> memberships[memberships]

    stereotomy --> cryptography
    stereotomy --> protocols
    stereotomy --> grpc
    stereotomy --> memberships

    protocols --> memberships
    grpc --> memberships
```

**Key Dependencies**:
- Everything depends on `cryptography` (Digest, Signature)
- Consensus (`ethereal`, `choam`) depends on membership (`fireflies`)
- State (`sql-state`) depends on consensus (`choam`)
- Applications depend on state and/or consensus
- All modules use `grpc` for inter-node communication

---

## Consensus Data Flow

This shows how a client transaction flows through the system:

```mermaid
graph TD
    Client[Client<br/>Submits transaction] --> CHOAM[CHOAM<br/>Routes to current committee<br/>based on Context]
    CHOAM --> Ethereal[Ethereal<br/>Aleph-BFT consensus<br/>Orders transactions into blocks]
    Ethereal --> Block[Ordered Block<br/>Block with sequence number<br/>and hash of previous block]
    Block --> SQLState[SQL-State<br/>Execute SQL against H2 database<br/>Deterministic execution]
    SQLState --> StateUpdate[State Update<br/>All nodes have identical state<br/>Byzantine agreement + deterministic execution]
```

**Transaction Lifecycle**:

1. **Submission**: Client submits transaction to any CHOAM node
2. **Routing**: Transaction routed to current committee members
3. **Consensus**: Ethereal orders transaction via Aleph-BFT
4. **Execution**: SQL-State executes transaction deterministically
5. **Replication**: All nodes execute same transaction sequence → identical state

**Key Properties**:
- **Total order**: All nodes see same transaction sequence
- **Determinism**: Same inputs always produce same outputs
- **Byzantine fault tolerance**: Works even if f<n/3 nodes are malicious (requires n >= 3f+1)
- **Asynchronous**: No timing assumptions required

---

## Deterministic Execution Requirements

For all nodes to achieve identical state despite failures, execution must be **deterministic**:

**Determinism Guarantees in SQL-State**:
1. **Block hash seeding**: RANDOM(), TIME(), and other non-deterministic functions seeded by block hash
2. **Single-writer model**: Only CHOAM's totally ordered log can modify state
3. **Transaction ordering**: All nodes execute same transactions in same sequence
4. **Identical execution**: Same SQL input → same database output on all nodes

**What's Forbidden** (causes non-determinism):
- `System.currentTimeMillis()` in stored procedures (use block timestamp)
- `Math.random()` in SQL functions (use RANDOM(seed) instead)
- `UUID.randomUUID()` in transactions (deterministic seed required)
- File I/O, network calls, or external state during transaction execution
- Concurrent threads with non-deterministic ordering

**How It Works**:
```
Block #123 hash: SHA256(abcd...)
  ↓
RANDOM(abcd...) in SQL → always same value on all nodes
TIME() in SQL → uses block timestamp, not system clock
  ↓
All nodes execute identical transactions → identical state
```

---

## Byzantine Fault Tolerance Guarantees Per Layer

**Layer 2 (Fireflies Membership)**:
- Stable, agreed-upon membership even with f Byzantine members
- BFT join protocol ensures quorum agreement on new members
- Gossip with Byzantine ring isolation (malicious nodes confined)
- View changes via 2f+1 majority voting

**Layer 3 (Ethereal Consensus)**:
- Total ordering via DAG despite f Byzantine nodes proposing invalid units
- Invalid units detected via signature verification and cryptographic validation
- Consensus advances with 2f+1 honest nodes regardless of Byzantine delays
- No leader bottleneck → Byzantine nodes can't block consensus by stalling

**Layer 3 (CHOAM SMR)**:
- Committee-based replication ensures linear log despite membership changes
- View reconfiguration via 2f+1 votes + asynchronous join protocol
- Blocks delivered via Bounded Epidemic Gossip (BEG) with 2/3+1 confirmation
- State machine execution deterministic → all nodes identical state

**Layer 4 (SQL-State & Delphinius)**:
- Identical state across all nodes (no divergence despite Byzantine failures)
- Relation-based access control enforced on replicated state
- Corruption detected via state hash comparison
- Rollback via checkpoint + replay

---

## Network Topology

### Ring-Based Gossip (Fireflies)

Each member has a position on a consistent hash ring based on its identifier:

```mermaid
graph LR
    A[Member A] --> B[Member B]
    B --> C[Member C]
    C --> D[Member D]
    D --> A

    style A fill:#e1f5ff
    style B fill:#e1f5ff
    style C fill:#e1f5ff
    style D fill:#e1f5ff
```

Ring positions: A → B → C → D → A (circular)

**Gossip pattern**:
- Each member gossips with successors and predecessors
- Redundant paths ensure Byzantine fault tolerance
- Bloom filters track gossip state efficiently
- Ring structure provides O(log n) paths between any two members

### Committee-Based Consensus (CHOAM)

Committees are subsets of members responsible for consensus:

```mermaid
graph TD
    Membership[Full Membership<br/>16 members] --> CommitteeA[Committee A<br/>members 1, 3, 5, 7<br/>Handles keys: 0-0.5]
    Membership --> CommitteeB[Committee B<br/>members 2, 4, 6, 8<br/>Handles keys: 0.5-1.0]

    style Membership fill:#f9f9f9
    style CommitteeA fill:#d4edda
    style CommitteeB fill:#d4edda
```

**Committee selection**:
- Committees chosen via consistent hashing
- BFT quorum within each committee (3f+1)
- Load distribution across multiple committees
- Dynamic reconfiguration as membership changes

---

## Key Abstractions

### Context

A **Context** is a logical grouping of members, similar to a cluster ID. All members in a context:
- Share a common ring structure
- Participate in the same gossip protocol
- Achieve consensus on membership views

**Example**: A production deployment might have separate contexts for different tenants or geographic regions.

### Member

A **Member** represents a node in the system with:
- **Identity**: KERI-based self-certifying identifier
- **Location**: Network address (host, port)
- **Note**: Signed metadata (certificate equivalent)

Members communicate via gRPC with MTLS using KERI for certificate validation.

### View

A **View** represents a consistent snapshot of membership:
- **Members**: Set of all members in the context
- **Epoch**: Monotonically increasing version number
- **Crown**: Cryptographic hash of membership set
- **Bloom filter**: Compact membership representation

Views change only by consensus (BFT voting).

### Digest

A **Digest** is a self-describing content-addressed identifier:
```
<algorithm><hash_bytes>
```

Used for:
- Content addressing (blocks, transactions, states)
- Cryptographic binding (crown, view IDs)
- Cache keys and deduplication

**Example**: `SHA256:<32_bytes>` uniquely identifies content.

### CHOAM Session

A **CHOAM Session** manages transaction submission:
- Batching for efficiency
- Retry logic for failures
- Result tracking
- View change handling

Provides linearizable operations (sequential consistency).

---

## Putting It All Together

### Bootstrapping a Cluster

1. **Identity generation** (stereotomy): Each node generates KERI identifier
2. **Join protocol** (fireflies): New node contacts seed, acquires membership view
3. **Gossip discovery**: Node fills out full membership via ring gossip
4. **Consensus participation** (ethereal, choam): Node joins committees and votes
5. **State synchronization** (sql-state): Node catches up on replicated state

### Processing a Transaction

1. **Client** submits SQL transaction via JDBC
2. **sql-state** packages transaction for CHOAM
3. **CHOAM** routes to committee based on key hash
4. **Ethereal** achieves consensus on transaction order within committee
5. **CHOAM** assembles ordered block from consensus output
6. **sql-state** executes transaction deterministically on all nodes
7. **Client** receives result once majority commits

### Handling Failures

**Node Crash (Fail-Stop)**:
1. **Detection**: Fireflies detects via gossip timeout (typically 5-30s)
2. **Accusation**: Live members accuse failed node (part of gossip protocol)
3. **Rebuttal Period**: Failed node has window to prove liveness
4. **View Change**: If no rebuttal, view change vote scheduled
5. **Voting**: BFT subset votes on new view (excluding failed node)
6. **Installation**: All nodes install new view, committees rebalanced
7. **Recovery**: Failed node can rejoin by contacting seed, re-acquiring membership

**Byzantine Behavior** (Malicious Node):
1. **Detection**: Multiple detection mechanisms
   - Signature verification fails (KERI validation)
   - Cryptographic hash mismatch
   - Equivocation detected (two different signatures for same content)
2. **Response**:
   - Invalid messages rejected locally
   - Gossip excludes Byzantine node (Bloom filter + shunning)
   - Consensus proceeds with 2f+1 honest nodes
   - If repeated behavior: explicit blacklisting + view change
3. **Safety Guarantee**: Consensus requires 3f+1 total nodes
   - Even if f nodes are Byzantine, 2f+1 honest remain
   - Honest majority ensures convergence

**Network Partition** (Minority Partition):
- Minority partition can read state but not produce new blocks
- Majority partition continues consensus (owns the ledger)
- When partition heals: minority replays partition minority blocks
- No split-brain: only majority partition's blocks are canonical

**Recovery Process**:
1. **Checkpoint-Based Sync**: New node loads recent checkpoint (state snapshot)
2. **Deferred Block Replay**: Blocks after checkpoint replayed (catches up to leader)
3. **Gossip State Transfer**: Missing blocks acquired via gossip from committee
4. **View Synchronization**: Joins at next view change via Fireflies join protocol
5. **State Verification**: Hash comparison confirms identical state as other nodes

---

## Related Documentation

**Module deep-dives**:
- [fireflies/README.md](../fireflies/README.md) - Detailed membership protocol
- [ethereal/README.md](../ethereal/README.md) - Aleph-BFT consensus details
- [choam/README.md](../choam/README.md) - State machine replication
- [sql-state/README.md](../sql-state/README.md) - Replicated SQL databases
- [stereotomy/README.md](../stereotomy/README.md) - KERI identity management

**Current documentation**:
- [DEVELOPER_QUICKSTART.md](DEVELOPER_QUICKSTART.md) - Get started building Delos
- [DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md) - Production deployment *(coming soon)*
- [GLOSSARY.md](GLOSSARY.md) - Term definitions *(coming soon)*

---

**Last updated**: 2026-01-27 (Updated with protocol comparison, BFT model, deterministic execution, and recovery details)
**Version**: 0.0.7
**Status**: Consolidated with ChromaDB knowledge base (264+ indexed documents)
