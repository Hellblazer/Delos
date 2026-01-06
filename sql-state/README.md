# Delos Replicated SQL State Machine

The  _sql-state_  module provides a high-fidelity SQL Materialized View from a linear log in the form of a SQL database.
This SQL database has a single writer, which is the linear log fed into the SqlStateMachine.
The log is a sequence of transactions to execute against the current SQL state of the database. As these transactions
can contain SQL Data Definition Language statements,
this also means that the manipulation of the meta-data of the SQL database is also part of the log. This allows this
system to provide a full-featured
SQL database that represents the "current state" of the linear log. This is, in essence, a Materialized View. This model
includes stored procedures, triggers, functions, indexes as well as schemas, tables, types, and even views (a
view with a view).

## Model

The input to the SQL state machine is a linear log of SQL commands. This log is composed of Blocks of transactions,
which are submitted, in order, to the state machine for execution.
Each transaction is then executed against the local database that represents the materialized view of this state.
Results may be returned from these transactions,
such as the _Call_ results from a SQL function. Arguments may be supplied as well, which essentially means that each of
the executed transactions by this module
represents a kind of anonymous stored procedure executed at the "server" - in this case, the in embedded H2 Database for
this CHOAM log that represents its materialized view.

### Transaction Execution

Transaction exeution is performed via the single JDBC connection to the underlying H2 database. This implies that the
contents of the transactions are representable
with H2 SQL. Sadly, "generic" SQL is not really a thing, but a convention, and so this is not a  _generic_  SQL
execution. Further, the transaction execution model
is JDBC, which constrains the styles of interaction as well as the argument value and return types.

When a transaction is submitted, the client submitting the transaction can provide a function to execute when the
transaction is finalized. This function takes
the value returned — possibly null - and the error raised - if any. This means that calls, scripts, prepared statements,
etc, and return values in addition to
the normal SQL execution, providing a very powerful mechanism for transactions against an SQL store that we normally
take
for granted.

### Database Compatibility

Note that the H2 database has the ability to  _emulate_  several popular databases (it's one of
its [fine selling points](http://www.h2database.com/html/features.html#compatibility)). Currently, this is only
available on the JDBC connection creation to the H2 embedded instance and thus is not currently supported by Delos.
This will be accomidated in the future.

### Schema Evolution

The SQL state machine supports the full gamut of the SQL Data Definition Language (DDL). Note, however, that for H2 and
thus the SQL state machine, DDL is not transactional, and the current transaction will be committed on each DDL
statement. The DDL statements cannot be rolled back. Thus, one can wedge one's self quite easily with ill-advised schema
evolution strategies. In the interests of providing as many sharp knives as needed, there are currently no restrictions
on DDL.

Schema may also be maintained by executing [Liquibase](https://docs.liquibase.com/home.html) Migration transactions.
This transaction type includes a Liquibase command and change log that is applied to the H2 SQL store. Note that the
actual change log is not stored in the DB, rather it exists only in the CHOAM log.

### Transaction Types

Transactions are one of the following types, and are represented as Protobuffs defined in the sql-state.proto file.

* Statement
* Call
* Batch
* BatchUpdate
* Script
* BatchedTransaction
* Migration

#### Statement

The Statement is the equivalent of the JDBC SQL prepared statement with or without arguments.

#### Call

The Call is the transaction to execute SQL stored procedure Calls.

#### Batch

A Batch of SQL statements with no arguments, executed in order

#### BatchUpdate

A Single prepared statement that is executed in batch with a list of arguments, one argument set per batch entry

#### Script

A Java function that accepts an SQL connection and may return results — basically an anonymous function

#### BatchedTransaction

This is a batch of any of the types (even recursively), executed in order in a single transaction

#### Migration

A Liquibase Database migration

## Stored Procedures, Functions, and Triggers

The model provides the definition and execution of user-defined SQL stored procedures, functions, and triggers. These
are
currently limited to Java implementations, although more languages and WASM support is
straightforward to add. Java was chosen as the first implementation because - frankly - it's a shitload more mature
than all the others. Not simply from
a "been around longer" but from a "has standard interfaces for things like SQL." Seriously, it's hard to come up with
these things, and a "bring your own SQL connection library" does not
lend itself well to the issues of SQL state machine replication.

## Deterministic Implementation

Due to the model used for SQL state, it's essential that every node executes the transactions with identical results.
That's how we achieve replicated state across the system.
However, things like TIME and RANDOM make that impossible. So, the underlying H2 database used by this module has been
modified to provide for deterministic SQL execution. Even though
we provide the RANDOM function, we guarantee that the results of these RANDOM function invocations will be identical
across all nodes. Likewise, with TIME and some other functions. This
is accomplished by slight modifications of the underlying H2 database, and the use of block hashes for seeding these
functions. This results in deterministic SQL execution across the system.

Likewise, it's important in the Java stored procedures, functions and triggers to be deterministically executed. This is
not
enforced at the moment.

## Checkpointing and Bootstrapping

Checkpoints are implemented with H2's _SCRIPT_ command, which dumps the current database state in a form that will
recreate the state of the database. This is compressed and become the checkpoint state
used in CHOAM for bootstrapping nodes. Currently, no facilities are implemented for incremental backup, but it should be
straightforward to implement an incremental scheme. For the future ;)

## Public API Reference

### Core Classes

#### `SqlStateMachine` (State Executor)
**Location**: `sql-state/src/main/java/com/hellblazer/delos/state/SqlStateMachine.java`

Main entry point for executing transactions against replicated SQL materialized view.

**Key Methods**:
- `execute(transaction: Transaction): CompletableFuture<Result>` - Execute single transaction
- `checkpoint(): CompletedCheckpoint` - Create database snapshot
- `restore(checkpoint: CompletedCheckpoint): CompletableFuture<Void>` - Restore from snapshot
- `getHeight(): long` - Current transaction height in log
- `getBlockHash(): Digest` - Hash of current block (for RANDOM seeding)
- `close()` - Shutdown state machine and close H2 connection

**Properties**:
- **Single-writer**: JDBC connection strictly sequential (one transaction at a time)
- **H2 embedded**: Materialized view in process
- **Deterministic**: Block hash seeds RANDOM/TIME functions
- **Checkpointable**: Full state snapshot via H2 SCRIPT command

**Transaction Execution Flow**:
1. Receive transaction from CHOAM log
2. Execute via appropriate handler (Statement, Call, Batch, etc.)
3. Collect results or exceptions
4. Return to transaction submitter
5. Move to next transaction

#### `Mutator` (Transaction Builder API)
**Location**: `sql-state/src/main/java/com/hellblazer/delos/state/Mutator.java`

High-level API for constructing and submitting transactions to state machine.

**Key Methods**:
- `statement(sql: String, args: Object...): Statement` - JDBC prepared statement
- `call(sql: String, args: Object...): Call` - Stored procedure call
- `batch(statements: String...): Batch` - Multiple statements
- `batchUpdate(sql: String, argSets: List<Object[]>): BatchUpdate` - Bulk update
- `script(function: Function<Connection, Object>): Script` - Anonymous Java function
- `batch(transactions: Message...): BatchedTransaction` - Composite transaction
- `migration(changeLog: ChangeLog): Migration` - Liquibase migration

**Builder Pattern**:
```java
Mutator mutator = new Mutator(session, h2Session);
Statement stmt = mutator.statement("INSERT INTO users(id, name) VALUES(?, ?)", 1, "Alice");
```

**Transaction Composition**:
```java
// Batch multiple transaction types together
BatchedTransaction batch = mutator.batch(
    mutator.statement("CREATE TABLE accounts(id BIGINT, balance DECIMAL)"),
    mutator.statement("INSERT INTO accounts VALUES(?, ?)", 1, 1000),
    mutator.call("CALL audit_log(?, ?)", 1, "created")
);
```

#### `Transaction Types` (Protocol Buffer Messages)

All transaction types inherit from `Txn` message and support:
- Serialization via Protocol Buffers
- Deserialization on remote replicas
- Result callbacks for transaction submitter

**Statement**: JDBC prepared statement with arguments
```java
Statement stmt = Statement.newBuilder()
    .setSql("INSERT INTO events(id, data) VALUES(?, ?)")
    .addBindings(Value.newBuilder().setInt(1))
    .addBindings(Value.newBuilder().setString("event_data"))
    .build();
```

**Call**: SQL stored procedure/function call
```java
Call call = Call.newBuilder()
    .setSql("{ CALL get_balance(?) }")
    .addBindings(Value.newBuilder().setInt(account_id))
    .build();
```

**Batch**: Multiple SQL statements in order (non-transactional)
```java
Batch batch = Batch.newBuilder()
    .addStatements("INSERT INTO logs(message) VALUES('start')")
    .addStatements("UPDATE counters SET value = value + 1")
    .addStatements("INSERT INTO logs(message) VALUES('done')")
    .build();
```

**BatchUpdate**: Single prepared statement with multiple argument sets
```java
BatchUpdate batchUpdate = BatchUpdate.newBuilder()
    .setSql("INSERT INTO transactions(id, amount) VALUES(?, ?)")
    .addArgumentSets(ArgumentSet.newBuilder()
        .addBindings(Value.newBuilder().setInt(1))
        .addBindings(Value.newBuilder().setDecimal("100.50"))
        .build())
    .addArgumentSets(ArgumentSet.newBuilder()
        .addBindings(Value.newBuilder().setInt(2))
        .addBindings(Value.newBuilder().setDecimal("250.00"))
        .build())
    .build();
```

**Script**: Anonymous Java function with JDBC connection
```java
Script script = Script.newBuilder()
    .setClassName("com.example.MyScripts")
    .setMethodName("processTransactions")
    .build();

// Method signature:
public static Object processTransactions(Connection conn) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
        ResultSet rs = stmt.executeQuery("SELECT * FROM pending");
        // Process results...
        return rs.getRow();  // Return result
    }
}
```

**BatchedTransaction**: Composite transaction (atomic)
```java
BatchedTransaction composite = Mutator.batch(
    statement1,
    statement2,
    call1
);
// All execute in single transaction; rolled back if any fails
```

**Migration**: Liquibase database schema evolution
```java
ChangeLog changeLog = Mutator.changeLog(
    resourceZip,           // Zip of migration files
    "com/example/db"       // Root path in zip
);
Migration migration = Migration.newBuilder()
    .setChangeLog(changeLog.toByteString())
    .setContexts("production")  // Liquibase contexts
    .build();
```

### Result Handling

#### `CallResult` (Return Values)
**Location**: `sql-state/src/main/java/com/hellblazer/delos/state/SqlStateMachine.java`

Marshaled result from SQL function call or Script execution.

**Properties**:
- **Values**: CachedRowSet containing result rows
- **Update count**: Number of rows affected (for DML)
- **Exception**: If transaction failed, exception details
- **Height**: Log height of transaction

**Usage**:
```java
Future<CallResult> future = submitTransaction(call);
CallResult result = future.get();
if (result.getException() != null) {
    System.out.println("Error: " + result.getException());
} else {
    System.out.println("Rows affected: " + result.getUpdateCount());
    result.getValues().forEach(row -> System.out.println(row));
}
```

### Supporting Components

#### `H2 Deterministic Database` (Seeded Randomness)
**Location**: `h2-deterministic/` module (shaded, not importable)

Modified H2 database with deterministic functions for replicated state machine.

**Deterministic Functions**:
- `RANDOM(seed)`: Pseudo-random number generator seeded by block hash
- `CURRENT_TIMESTAMP()`: Uses block timestamp, not system time
- `UUID()`: Generated from block hash, reproducible
- `HASH()`: Seeded hash functions

**Seeding via Block Hash**:
```sql
-- In replicated state machine, these return identical results:
SELECT RANDOM(hash_bytes_to_long('block_hash'));  -- Same on all replicas
SELECT CURRENT_TIMESTAMP();                        -- Block timestamp
SELECT UUID();                                      -- Derived from hash
```

**Usage**:
All calls to RANDOM(), TIME(), UUID() automatically use block hash for seeding. No explicit seed required.

#### `Liquibase Integration` (Schema Migrations)
**Location**: `sql-state/src/main/java/com/hellblazer/delos/state/liquibase/`

Pluggable migration support for deterministic schema evolution.

**Key Classes**:
- `LiquibaseConnection`: Wraps H2 JDBC connection for Liquibase
- `ReplicatedChangeLogHistoryService`: Tracks migrations in CHOAM log (not external DB)
- `MigrationAccessor`: Loads migration change logs from zip
- `ThreadLocalScopeManager`: Thread-local context for migration scope

**Usage**:
```java
ChangeLog changeLog = ChangeLog.newBuilder()
    .setResources(zipInputStream.readAllBytes())  // Zip of .xml change logs
    .setRoot("db/migrations")                      // Root path in zip
    .build();

Migration migration = Migration.newBuilder()
    .setChangeLog(changeLog)
    .setContexts("production,primary")
    .setLabels("v1.0")
    .build();

sqlStateMachine.execute(migration);  // Replicated across all nodes
```

**Migration Files** (Standard Liquibase XML):
```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog>
    <changeSet id="1" author="dba">
        <createTable tableName="users">
            <column name="id" type="BIGINT">
                <constraints primaryKey="true"/>
            </column>
            <column name="name" type="VARCHAR(255)"/>
        </createTable>
    </changeSet>
</databaseChangeLog>
```

## Usage Examples

### 1. Initialize SQL State Machine

```java
// Create H2 database connection
JdbcConnection conn = new JdbcConnection(
    DriverManager.getConnection("jdbc:h2:mem:state")
);

// Create state machine
SqlStateMachine stateMachine = new SqlStateMachine(
    session,              // CHOAM session
    conn,
    executors,
    params
);

// Initialize internal schema
stateMachine.initialize();

// State machine ready for transactions
```

### 2. Execute Basic CRUD Operations

```java
Mutator mutator = new Mutator(session, h2Session);

// Create table
stateMachine.execute(
    mutator.statement("CREATE TABLE users(id BIGINT PRIMARY KEY, name VARCHAR(255))")
).get();

// Insert rows
stateMachine.execute(
    mutator.statement("INSERT INTO users(id, name) VALUES(?, ?)", 1, "Alice")
).get();

// Query data
ResultSet rs = h2Connection.createStatement()
    .executeQuery("SELECT * FROM users WHERE id = 1");
if (rs.next()) {
    System.out.println("User: " + rs.getString("name"));
}

// Update
stateMachine.execute(
    mutator.statement("UPDATE users SET name = ? WHERE id = ?", "Alice Smith", 1)
).get();

// Delete
stateMachine.execute(
    mutator.statement("DELETE FROM users WHERE id = ?", 1)
).get();
```

### 3. Execute Bulk Operations via Batch

```java
// Batch insert multiple rows
BatchUpdate batchInsert = BatchUpdate.newBuilder()
    .setSql("INSERT INTO events(id, type, timestamp) VALUES(?, ?, ?)")
    .addArgumentSets(ArgumentSet with (1, "login", now))
    .addArgumentSets(ArgumentSet with (2, "action", now))
    .addArgumentSets(ArgumentSet with (3, "logout", now))
    .build();

stateMachine.execute(batchInsert).get();
```

### 4. Call Stored Procedures

```java
// Create stored function
stateMachine.execute(
    mutator.statement(
        "CREATE FUNCTION get_user_count() RETURNS INT " +
        "AS 'SELECT COUNT(*) FROM users'"
    )
).get();

// Call function
Call callFunc = Call.newBuilder()
    .setSql("{ CALL get_user_count() }")
    .build();

CallResult result = stateMachine.execute(callFunc).get();
System.out.println("User count: " + result.getValues().first());
```

### 5. Execute Anonymous Java Functions

```java
// Submit Script transaction (Java function in CHOAM log)
Script script = Script.newBuilder()
    .setClassName("com.example.StateScripts")
    .setMethodName("computeStats")
    .build();

CallResult result = stateMachine.execute(script).get();

// Implementation (deterministic across replicas):
public static Object computeStats(Connection conn) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
        ResultSet rs = stmt.executeQuery(
            "SELECT COUNT(*) as total, AVG(amount) as avg_amount FROM transactions"
        );
        rs.next();
        return Map.of(
            "total", rs.getLong("total"),
            "average", rs.getDouble("avg_amount")
        );
    }
}
```

### 6. Atomic Multi-step Transactions

```java
// All-or-nothing: either all execute or all rollback
BatchedTransaction atomic = Mutator.batch(
    mutator.statement("INSERT INTO accounts(id, balance) VALUES(?, ?)", 1, 1000),
    mutator.statement("INSERT INTO accounts(id, balance) VALUES(?, ?)", 2, 500),
    mutator.call("CALL update_total_balance(?, ?)", 1500, 1),
    mutator.statement("INSERT INTO audit(event) VALUES(?)", "accounts_created")
);

try {
    sqlStateMachine.execute(atomic).get();
    System.out.println("All operations committed");
} catch (Exception e) {
    System.out.println("All operations rolled back: " + e);
}
```

### 7. Schema Evolution via Liquibase

```java
// Load migration change logs from resources
byte[] migrationZip = Files.readAllBytes(
    Paths.get("src/main/resources/db/migrations.zip")
);

ChangeLog changeLog = ChangeLog.newBuilder()
    .setResources(ByteString.copyFrom(migrationZip))
    .setRoot("db/migrations")
    .build();

Migration migration = Migration.newBuilder()
    .setChangeLog(changeLog)
    .setContexts("production")
    .build();

// Execute migration (replicated across all nodes)
sqlStateMachine.execute(migration).get();
```

### 8. Create and Restore Checkpoint

```java
// Checkpoint current state
CompletedCheckpoint checkpoint = sqlStateMachine.checkpoint();
System.out.println("Checkpoint at height: " + checkpoint.getHeight());
System.out.println("Checkpoint size: " + checkpoint.getCheckpoint().size());

// Store checkpoint (e.g., in CHOAM log)
storeCheckpoint(checkpoint);

// Later: New node restores from checkpoint
sqlStateMachine.restore(checkpoint).get();

// Process deferred transactions after checkpoint
sqlStateMachine.executeDeferred();

System.out.println("Restored to height: " + sqlStateMachine.getHeight());
```

### 9. Handle Errors and Exceptions

```java
// Transaction with error handling
try {
    CallResult result = sqlStateMachine.execute(transaction).get();

    if (result.getException() != null) {
        System.out.println("SQL error: " + result.getException().getMessage());
        // Handle error: log, alert, trigger recovery
    } else {
        System.out.println("Success: " + result.getUpdateCount() + " rows");
    }
} catch (InterruptedException | ExecutionException e) {
    System.out.println("Execution error: " + e);
}
```

## Determinism Guarantees

### Database-level Determinism

All nodes executing the same transaction log will compute identical state:

```sql
-- These return same values on all replicas:
SELECT RANDOM(0);           -- Block hash seeded
SELECT CURRENT_TIMESTAMP(); -- Block timestamp
SELECT UUID();              -- Block hash derived

-- Comparison: System-level randomness (different per node):
-- SELECT RANDOM();          -- ❌ Different on each node
-- SELECT CURRENT_TIMESTAMP(); -- ❌ Different per system clock
```

### Implications

- **State replication**: All nodes achieve identical materialized view
- **Auditability**: Results reproducible given same block hash
- **Recovery**: Checkpoint + transaction log enables exact state reconstruction
- **Consistency**: No probabilistic or time-dependent results

## Performance Characteristics

### Transaction Execution
- **Latency**: ~10-50ms per transaction (single JDBC connection)
- **Throughput**: Sequential, limited by single writer
- **Batching**: Batch operations amortize connection overhead

### Checkpointing
- **Creation time**: ~100-500ms for typical state (H2 SCRIPT command)
- **Checkpoint size**: Compressed SQL dump (varies by data)
- **Restoration time**: ~50-200ms (replay from SCRIPT dump)

### Scalability
- **State size**: Limited only by H2 database (typically 1-10 GB per node)
- **Transaction throughput**: Single-writer sequential (100-1000 TPS typical)
- **Network overhead**: Transactions transmitted as Protocol Buffers

## Metrics

SQL-State exposes operational metrics via Dropwizard Metrics for monitoring state machine health and performance.

### Transaction Execution Metrics

| Metric | Type | Description | Healthy Range |
|--------|------|-------------|-----------------|
| `sql_state_transactions_executed` | Counter | Total transactions processed | Increasing monotonically |
| `sql_state_transaction_latency` | Timer | Time per transaction execution | p95 < 100ms |
| `sql_state_batch_size` | Histogram | Rows affected per transaction | 1-1000 |
| `sql_state_pending_transactions` | Gauge | Transactions awaiting execution | < 100 |
| `sql_state_errors_total` | Counter | SQL errors encountered | 0 (ideally) |

### State Height and Checkpointing

| Metric | Type | Description | Healthy Range |
|--------|------|-------------|-----------------|
| `sql_state_height` | Gauge | Current transaction height in log | Increasing with CHOAM |
| `sql_state_block_hash` | Gauge | Hash of current block (seed for RANDOM) | Changes per block |
| `sql_state_checkpoint_height` | Gauge | Latest checkpoint transaction height | Should match or lag slightly behind height |
| `sql_state_checkpoint_size_bytes` | Gauge | Size of last checkpoint | Varies by data (typically MB-GB) |
| `sql_state_checkpoint_latency` | Timer | Time to create checkpoint | p95 < 1s |
| `sql_state_restore_latency` | Timer | Time to restore from checkpoint | p95 < 2s |

### Determinism Guarantees

| Metric | Type | Description | Healthy Range |
|--------|------|-------------|-----------------|
| `sql_state_determinism_violations` | Counter | Non-deterministic execution detected | 0 (alerts if > 0) |
| `sql_state_random_seeding_failures` | Counter | Block hash seeding failures | 0 (alerts if > 0) |
| `sql_state_replicas_in_sync` | Gauge | Number of replicas with identical state | = cluster member count |

### Database Operations

| Metric | Type | Description | Healthy Range |
|--------|------|-------------|-----------------|
| `sql_state_ddl_statements` | Counter | Schema modifications (DDL) | Occasional |
| `sql_state_dml_statements` | Counter | Data modifications (INSERT/UPDATE/DELETE) | Increasing with workload |
| `sql_state_query_statements` | Counter | Read-only queries | Increasing with workload |
| `sql_state_jdbc_connection_time` | Timer | JDBC connection acquisition | p95 < 50ms |
| `sql_state_database_size_bytes` | Gauge | H2 database file size | Grows with data |

### Liquibase Migrations

| Metric | Type | Description | Healthy Range |
|--------|------|-------------|-----------------|
| `sql_state_migrations_applied` | Counter | Schema migrations completed | Increases on schema changes |
| `sql_state_migration_latency` | Timer | Time per migration | p95 < 5s |
| `sql_state_migration_rollbacks` | Counter | Failed migrations rolled back | 0 (ideally) |

### Performance and Health

| Metric | Type | Description | Healthy Range |
|--------|------|-------------|-----------------|
| `sql_state_memory_usage_bytes` | Gauge | JVM heap used by sql-state | < 80% of max heap |
| `sql_state_gc_time_percent` | Gauge | % of time in garbage collection | < 5% |
| `sql_state_connection_pool_active` | Gauge | Active JDBC connections | Usually 1 (single-writer) |
| `sql_state_last_transaction_time` | Timer | Time since last transaction processed | Should be near-zero |

### Alert Thresholds

Set up alerts for these conditions:

- **Transaction latency p95 > 500ms** → Investigate H2 performance (GC, disk I/O, table scans)
- **Pending transactions > 1000** → Backlog building, check CHOAM block production rate
- **Error rate > 0.1%** → SQL execution failures, check transaction validity
- **Checkpoint latency > 10s** → Database too large or disk I/O bottleneck
- **Determinism violations > 0** → Critical: state inconsistency across replicas
- **Database size > threshold** → Plan checkpoint archival and cleanup
- **Connection pool errors > 0** → JDBC connection exhaustion

### Monitoring Examples

**Prometheus Query: Transaction Throughput**
```promql
rate(sql_state_transactions_executed[5m])
```
Typical: 10-1000 transactions/second (depends on workload)

**Prometheus Query: State Height Progression**
```promql
delta(sql_state_height[5m])
```
Should match `delta(choam_blocks_committed[5m])` - if lagging, investigate state machine performance

**Prometheus Query: Checkpoint Health**
```promql
sql_state_height - sql_state_checkpoint_height
```
Should be small (< 1000 blocks typically). Large gaps indicate checkpoint failures.

**Prometheus Query: Replication Lag**
```promql
max(sql_state_height) - min(sql_state_height)
```
Should be 0 (all nodes identical) or very small. Persistent lag indicates consensus stalling.

**Grafana Alert: Replica Divergence**
```promql
(max(sql_state_height) - min(sql_state_height)) > 100
```
Critical alert: Some nodes lagging in state execution

**Grafana Dashboard: SQL-State Health**
- Top panel: Transaction rate (TPS)
- Second panel: Transaction latency (p50, p95, p99)
- Third panel: State height progression (should match CHOAM)
- Fourth panel: Checkpoint interval (time between checkpoints)
- Fifth panel: Error rate (should be zero)
- Bottom panel: Database size and memory usage

### Metrics Access

**HTTP Endpoint:**
```bash
curl http://node1.delos.local:8080/metrics
```

**JMX Endpoint:** (if enabled)
```bash
jconsole jmx:service:jmx:rmi:///jndi/rmi://node1.delos.local:7199/jmxrmi
```

**Log Output:**
Set in delos.yaml to export metrics to logs periodically:
```yaml
metrics:
  reporterIntervalSeconds: 60
  outputFormat: json
```

---

## Security and Validation

**See**: [ADR-0005: Deterministic SQL State Machine](../docs/adr/0005-deterministic-sql-state.md) for comprehensive architecture and security guarantees.

**Quick Reference**:
- **State consistency**: Identical execution via block hash seeding
- **Auditability**: All state changes traced to CHOAM transaction ID
- **Isolation**: Single JDBC connection prevents concurrent access
- **Durability**: Checkpoints provide recovery points
- **Determinism**: RANDOM/TIME reproducible across replicas

## Testing and Validation

**Test Suite Location**: `sql-state/src/test/java/com/hellblazer/delos/state/`

**Test Coverage**:
- Transaction execution (all 7 types)
- Deterministic RANDOM/TIME seeding
- Result marshaling and callbacks
- Schema evolution via DDL and Liquibase
- Stored procedure/function execution
- Checkpoint creation and restoration
- Concurrent bootstrap scenarios
- Error handling and rollbacks

**Running Tests**:
```bash
# All sql-state tests
./mvnw test -pl sql-state

# Specific test class
./mvnw test -pl sql-state -Dtest=SqlStateMachineTest

# Determinism verification
./mvnw test -pl sql-state -Dtest=DeterministicExecutionTest
```

## Integration with CHOAM

The SQL state machine acts as a **TransactionExecutor** for CHOAM:

```java
// CHOAM provides block stream
choam.registerExecutor(new TransactionExecutor() {
    @Override
    public void execute(Block block) throws InvalidTransaction {
        block.getTransactions().forEach(txn -> {
            try {
                sqlStateMachine.execute(txn).get();
            } catch (Exception e) {
                throw new InvalidTransaction(e);
            }
        });
    }
});
```

## References

- **Core Classes**:
  - `sql-state/src/main/java/com/hellblazer/delos/state/SqlStateMachine.java`
  - `sql-state/src/main/java/com/hellblazer/delos/state/Mutator.java`
  - `sql-state/src/main/java/com/hellblazer/delos/state/JavaMethod.java`

- **H2 Deterministic**: `h2-deterministic/` module
- **Liquibase Integration**: `sql-state/src/main/java/com/hellblazer/delos/state/liquibase/`
- **Protobuf Definitions**: `sql-state/proto/state.proto`

- **Related ADRs**:
  - ADR-0005: Deterministic SQL State Machine
  - ADR-0004: Consensus Design (CHOAM integration)

- **Source Code**:
  - Main: `sql-state/src/main/java/com/hellblazer/delos/state/`
  - Tests: `sql-state/src/test/java/com/hellblazer/delos/state/`
  - Protocol Buffers: `grpc/src/main/proto/state.proto`

- **Integration Points**:
  - CHOAM: State machine replication via block log
  - H2 Deterministic: Database engine for deterministic execution
  - Liquibase: Schema migration framework
  - Cryptography: Block hash seeding for RANDOM/TIME

