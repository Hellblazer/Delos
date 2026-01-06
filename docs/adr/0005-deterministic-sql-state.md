# ADR-0005: Deterministic SQL State Machine

**Status**: ACCEPTED

**Date**: 2026-01-06

**Context**

Delos requires a high-fidelity SQL materialized view derived from a totally ordered transaction log (from CHOAM). All nodes must execute the same transactions and arrive at identical state—determinism is non-negotiable for replicated state machines. However, SQL features like TIME(), RANDOM(), and non-deterministic Java logic create significant obstacles to identical execution across distributed replicas.

This ADR documents the architecture of the sql-state module, which provides a deterministic SQL state machine capable of executing DDL, DML, and procedural SQL against an embedded H2 database, seeded by block hashes for reproducibility.

**Decision**

**We implement a deterministic SQL state machine based on:**

1. **Single-writer pattern**: Linear log (CHOAM) is the sole source of truth for state mutations
2. **H2 embedded database**: Materialized view of replicated state, modified for deterministic execution
3. **Deterministic seeding**: Block hashes seed RANDOM, TIME, and other non-deterministic functions
4. **Full SQL support**: DDL, DML, stored procedures, functions, triggers, views, schemas
5. **Checkpointing**: H2 SCRIPT command snapshots state for efficient bootstrapping

**Architecture Components:**

1. **SqlStateMachine: Core State Executor**
   - Entry point for transaction execution against materialized view
   - Accepts CHOAM transactions sequentially
   - Maintains H2 embedded database connection
   - Tracks block height, block hash, transaction state
   - Returns results and errors to transaction submitter
   - Location: `sql-state/src/main/java/com/hellblazer/delos/state/SqlStateMachine.java`

2. **Mutator: Transaction Mutation API**
   - High-level API for submitting transactions to state machine
   - Supports multiple transaction types (see below)
   - Collects results and exception handling
   - Enables batching and composability
   - Location: `sql-state/src/main/java/com/hellblazer/delos/state/Mutator.java`

3. **Transaction Types (Protocol Buffer Definitions)**
   - **Statement**: JDBC prepared statement with optional arguments
   - **Call**: SQL stored procedure calls with return values
   - **Batch**: Multiple SQL statements executed in order
   - **BatchUpdate**: Single prepared statement executed with multiple argument sets
   - **Script**: Java function accepting JDBC connection (anonymous stored procedure)
   - **BatchedTransaction**: Recursive batch of any transaction types (executed atomically)
   - **Migration**: Liquibase database migration with change log
   - Location: `sql-state/proto/state.proto`

4. **Deterministic H2 Database (h2-deterministic module)**
   - Modified H2 database with deterministic functions
   - RANDOM(seed): Uses block hash as seed for pseudo-random number generation
   - TIME-based functions: Use block timestamp, not system time
   - Deterministic string/UUID operations: Seeded by digest
   - Guarantees identical execution on all replicas
   - Location: `h2-deterministic/` (shaded Maven package, not importable)

5. **Result Handling**
   - **Call Results**: Return values from SQL functions (via CachedRowSet)
   - **Update Counts**: Number of affected rows from DML
   - **Result Sets**: Query results marshaled as Java objects
   - **Exceptions**: Captured and returned to transaction submitter
   - Asynchronous callbacks for result notification
   - Location: `sql-state/src/main/java/com/hellblazer/delos/state/SqlStateMachine.java`

6. **Checkpointing and Bootstrapping**
   - **Checkpoint Creation**: H2 SCRIPT command dumps database state
   - **Compression**: Checkpoint compressed for efficient storage
   - **Restoration**: New node loads checkpoint via bootstrap
   - **Deferred Blocks**: Blocks after checkpoint processed after restoration
   - **State Metadata**: Block height, hash tracked in delos_internal table
   - Location: `sql-state/src/main/java/com/hellblazer/delos/state/SqlStateMachine.java`

7. **Schema Evolution via DDL**
   - **Full DDL Support**: CREATE/ALTER/DROP for tables, indexes, views, schemas, functions, triggers
   - **Non-transactional DDL**: H2 commits each DDL statement immediately (cannot rollback)
   - **Risks**: Ill-advised DDL can wedge the database (no restrictions imposed)
   - **Mitigation**: Operators responsible for schema evolution strategies
   - **Liquibase Support**: Alternative migration path via Migration transactions

8. **Liquibase Integration**
   - **Pluggable Migrations**: Change logs applied deterministically
   - **Custom History Service**: ReplicatedChangeLogHistoryService tracks migrations
   - **No External Storage**: Change logs exist only in CHOAM log, not persisted separately
   - **Migration Executor**: LiquibaseConnection wraps H2 connection
   - **Contexts and Labels**: Supported via standard Liquibase API
   - Location: `sql-state/src/main/java/com/hellblazer/delos/state/liquibase/`

9. **Stored Procedures, Functions, and Triggers**
   - **Java Implementation**: User-defined via Java reflection
   - **JDBC Connection Access**: Functions receive SQL connection parameter
   - **Return Types**: Supported via H2 Value type system
   - **Execution Context**: `DelegatingJdbcConnector` provides connection wrapper
   - **Alternative Languages**: WASM support planned for future
   - Location: `sql-state/src/main/java/com/hellblazer/delos/state/JavaMethod.java`

**Rationale**

**Why Deterministic Seeding Instead of System Time?**

- **Reproducibility**: All nodes must execute identically
- **Block Hash Seeding**: Ties randomness to consensus log position
- **Audit Trail**: RANDOM results are reproducible given same block hash
- **Forward Compatibility**: Allows adding new nodes without replaying full history

**Why Single-Writer Pattern?**

- **Simplicity**: Linear log guarantees total order without complex locking
- **Safety**: Eliminates race conditions from concurrent writes
- **Auditability**: All mutations traceable to CHOAM transaction ID
- **Replication**: Any node can replay log to reconstruct state

**Why H2 Embedded Instead of External Database?**

- **Embedded**: No external service dependency; fully contained in process
- **Performance**: Single-machine SQL execution with optimized query planning
- **Modularity**: Each node maintains independent copy of replicated state
- **Determinism**: Modified H2 provides seeded randomness

**Why No External Checkpoint Storage?**

- **Ledger Consistency**: Checkpoint only as reliable as CHOAM log
- **Simplicity**: Gossip-based checkpoint assembly during bootstrap
- **Decentralization**: No dedicated checkpoint server
- **Cost**: Avoids external storage infrastructure

**Security Properties Guaranteed**

1. **State Consistency**: All honest nodes compute identical state given same transaction log
2. **Determinism**: RANDOM/TIME functions produce identical results via block hash seeding
3. **Auditability**: All state changes traced to CHOAM transaction blocks
4. **Isolation**: No concurrent access to H2 database (single JDBC connection)
5. **Durability**: Checkpoint provides recovery point for failed/rejoining nodes
6. **Schema Safety**: DDL handled via Liquibase for controlled evolution

**Design Integration Points**

1. **CHOAM**: Provides linear transaction log (TransactionExecutor interface)
2. **Cryptography**: Block hash seeding for deterministic functions
3. **H2 Deterministic**: Modified H2 database with seeded randomness
4. **Liquibase**: Schema migration framework
5. **Java Reflection**: User-defined procedures/functions/triggers
6. **Domain Models**: State mutations represent domain-specific entities

**Consequences**

**Positive**:
- Full SQL expressiveness for replicated state machines
- Deterministic execution guaranteed via block hash seeding
- Efficient bootstrapping via checkpointing and gossip
- Schema evolution support via Liquibase
- Rich stored procedure/trigger capabilities via Java

**Negative**:
- Single JDBC connection limits concurrency (by design)
- H2 DDL non-transactional (cannot rollback schema changes)
- No built-in incremental backup (checkpoint always full)
- Deterministic seeding ties randomness to specific block
- Java procedure language forces JVM runtime requirement

**Alternative Approaches Considered**

1. **Stateful Processors (Event Sourcing)**
   - Maintain state as objects in memory
   - Replay events to reconstruct state
   - Advantages: Flexible domain model
   - Disadvantages: No SQL query capability, memory limitations, harder to checkpoint

2. **Blockchain Smart Contracts**
   - Execute state transitions via smart contract VM
   - Deterministic by design (WASM, EVM)
   - Advantages: Proven determinism
   - Disadvantages: Limited SQL expressiveness, external tool dependency

3. **External Consistent Database (No Replication)**
   - Single external database, replicate via transactions
   - Advantages: Standard SQL support
   - Disadvantages: Violates decentralization goal, single point of failure

**Implementation Status**

| Component | Status | Coverage | Notes |
|-----------|--------|----------|-------|
| SqlStateMachine | ✅ COMPLETE | All transaction types | Single JDBC connection model |
| Mutator API | ✅ COMPLETE | 7 transaction types | Composable batching |
| Transaction Types | ✅ COMPLETE | Statement, Call, Batch, BatchUpdate, Script, Batched, Migration | Protobuf definitions |
| Deterministic H2 | ✅ COMPLETE | RANDOM, TIME seeding | Block hash seeding |
| Result Handling | ✅ COMPLETE | CachedRowSet marshaling | Async callbacks |
| Checkpointing | ✅ COMPLETE | H2 SCRIPT dumps | Compression included |
| Schema Evolution | ✅ COMPLETE | Full DDL support | Liquibase integration |
| Stored Procedures | ✅ COMPLETE | Java implementation | Reflection-based |
| Bootstrap | ✅ COMPLETE | Checkpoint assembly | Gossip-based restoration |

**Test Coverage**

- Transaction execution (all 7 types)
- Deterministic RANDOM/TIME execution
- Result marshaling and callbacks
- Schema evolution via DDL and Liquibase
- Stored procedure/function/trigger execution
- Checkpoint creation and restoration
- Concurrent bootstrap scenarios

**References**

- Core Classes:
  - `sql-state/src/main/java/com/hellblazer/delos/state/SqlStateMachine.java`
  - `sql-state/src/main/java/com/hellblazer/delos/state/Mutator.java`
  - `sql-state/src/main/java/com/hellblazer/delos/state/JavaMethod.java`
- H2 Deterministic: `h2-deterministic/` module (shaded, not importable)
- Liquibase Integration: `sql-state/src/main/java/com/hellblazer/delos/state/liquibase/`
- README: `sql-state/README.md`
- Test Suite: `sql-state/src/test/java/com/hellblazer/delos/state/`

---

**Decision Made By**: Quality Initiative Phase 1b Team
**Last Updated**: 2026-01-06
**Status**: ACCEPTED - Implementation complete, production-ready
