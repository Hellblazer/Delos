# Simple Key-Value Store Example

A minimal example demonstrating SQL-State integration in the Delos platform.

## Overview

This example shows how to:
- Create a replicated SQL state machine using Delos SQL-State
- Define a Liquibase schema for state management
- Bootstrap a Byzantine fault-tolerant (BFT) cluster
- Integrate with CHOAM consensus for write replication
- Query replicated state via standard JDBC

## Architecture

```
SimpleKVStore
    ↓ uses
SqlStateMachine (replicated H2 database)
    ↓ integrated with
CHOAM (consensus/replication)
    ↓ runs on
Fireflies (membership/gossip)
```

## Key Components

### Schema: `kv-store-changelog.xml`

Liquibase migration defining a simple key-value table:
```sql
CREATE TABLE kvstore.store (
    key VARCHAR(255) PRIMARY KEY,
    value VARCHAR(1024)
);
```

### Main Class: `SimpleKVStore.java`

Wraps `SqlStateMachine` to provide:
- Genesis data initialization (Liquibase schema)
- Checkpointer for state snapshots
- Transaction executor for consensus integration

### Integration Test: `SimpleKVStoreTest.java`

Demonstrates:
- 4-node BFT cluster setup (minimum for Byzantine fault tolerance: f=1, n=3f+1=4)
- Cluster activation and consensus
- SQL state replication across nodes

## Build

**Prerequisites**: First-time Delos builds require installing `h2-deterministic`:
```bash
cd ../..  # Navigate to Delos root
./mvnw clean install -Ppre -DskipTests
```

**Build this module:**
```bash
./mvnw clean install -pl examples/simple-kv-store
```

## Run Tests

```bash
./mvnw test -pl examples/simple-kv-store
```

**Note**: Tests require time for cluster formation (30+ seconds). The cluster must reach consensus before operations succeed.

## Key Concepts Demonstrated

### 1. SQL-State Machine
Replicated state backed by deterministic H2 database. All nodes execute the same SQL operations in the same order.

### 2. Genesis Data
Initial transactions to bootstrap state (Liquibase schema migration):
```java
public List<Transaction> getGenesisData() {
    var migration = Migration.newBuilder()
        .setUpdate(Mutator.changeLog(SCHEMA_PATH, SCHEMA_ROOT))
        .build();
    // Returns migration wrapped as Transaction
}
```

### 3. Consensus Integration
CHOAM consensus ensures all nodes agree on transaction order:
```java
new CHOAM(params.build(RuntimeParameters.newBuilder()
    .setGenesisData(view -> store.getGenesisData())
    .setCheckpointer(store.getCheckpointer())
    .setProcessor(store.getExecutor())
    .build()));
```

### 4. BFT Cluster
Minimum 4 nodes for f=1 Byzantine fault tolerance:
- Can tolerate 1 faulty node
- Requires 2f+1=3 nodes for quorum
- Uses n=3f+1=4 for optimal resilience

## Files

```
simple-kv-store/
├── pom.xml                                    # Maven configuration
├── README.md                                  # This file
├── src/
│   ├── main/
│   │   ├── java/.../SimpleKVStore.java       # Main implementation
│   │   └── resources/
│   │       └── liquibase/
│   │           └── kv-store-changelog.xml     # Database schema
│   └── test/
│       └── java/.../SimpleKVStoreTest.java   # Integration test
```

## Learning Path

1. **Read the schema** (`kv-store-changelog.xml`) - See how Liquibase defines state structure
2. **Review `SimpleKVStore`** - Understand SQL-State integration points
3. **Study the test** (`SimpleKVStoreTest`) - Learn BFT cluster setup patterns
4. **Run the test** - Observe consensus and replication in action

## Next Steps

- Explore `sql-state` module for advanced features (checkpointing, migrations, stored procedures)
- Review `choam` module for consensus details
- Study `fireflies` module for membership and gossip protocols
- See other examples in `examples/` directory

## Related Delos Modules

- `sql-state` - JDBC-accessible replicated state machines
- `choam` - Committee-based consensus on linear logs
- `fireflies` - Byzantine fault-tolerant membership
- `liquibase-modified` - Modified Liquibase with deterministic behavior for schema migrations
- `h2-deterministic` - Deterministic H2 database engine

## References

- [Delos Documentation](../../README.md)
- [SQL-State Module](../../sql-state/README.md)
- [CHOAM Consensus](../../choam/README.md)
