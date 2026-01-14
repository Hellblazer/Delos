# Liquibase Determinism Requirements for Byzantine Fault Tolerance

## Overview

This document defines the requirements for deterministic Liquibase migrations in Byzantine fault-tolerant replicated state machines. All replicas must execute migrations identically to maintain consensus.

## Core Principle

**CRITICAL**: Every migration changeset must produce byte-for-byte identical schemas and data across all replicas. Any non-deterministic operation will cause state divergence and consensus failure.

## Forbidden Operations

### 1. Random Value Generation

❌ **FORBIDDEN**:
```xml
<!-- Using RAND() function -->
<insert tableName="users">
    <column name="id" valueComputed="RAND() * 1000000"/>
</insert>

<!-- Using UUID generation -->
<addColumn tableName="users">
    <column name="uuid" type="UUID" defaultValueComputed="RANDOM_UUID()"/>
</addColumn>
```

✅ **ALLOWED**:
```xml
<!-- Explicit deterministic values -->
<insert tableName="users">
    <column name="id" valueNumeric="123456"/>
    <column name="uuid" value="550e8400-e29b-41d4-a716-446655440000"/>
</insert>
```

**Why**: Random functions produce different values on each replica.

### 2. Wall-Clock Time Functions

❌ **FORBIDDEN**:
```xml
<!-- Using NOW() or CURRENT_TIMESTAMP -->
<insert tableName="events">
    <column name="created_at" valueComputed="NOW()"/>
</insert>

<addColumn tableName="users">
    <column name="last_login" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP"/>
</addColumn>
```

✅ **ALLOWED**:
```xml
<!-- Explicit epoch milliseconds from BlockClock -->
<insert tableName="events">
    <column name="created_at" valueNumeric="1704067200000"/>
</insert>
```

**Why**: Wall-clock time differs across replicas due to clock skew.

### 3. Timestamp-Based Changeset IDs

❌ **FORBIDDEN**:
```xml
<!-- Using timestamp as changeset ID -->
<changeSet id="1704067200000" author="dev">
    ...
</changeSet>
```

✅ **ALLOWED**:
```xml
<!-- Sequential changeset IDs -->
<changeSet id="1" author="dev">
    ...
</changeSet>
<changeSet id="2" author="dev">
    ...
</changeSet>
```

**Why**: Timestamp-based IDs may vary if changelogs are generated at different times on different replicas.

### 4. External File References

❌ **FORBIDDEN**:
```xml
<!-- Loading data from external CSV -->
<loadData file="data/users.csv" tableName="users"/>

<!-- Including SQL from external file -->
<sqlFile path="scripts/migration.sql"/>
```

✅ **ALLOWED**:
```xml
<!-- Inline SQL in changelog -->
<sql>
    INSERT INTO users (id, name) VALUES (1, 'Alice');
    INSERT INTO users (id, name) VALUES (2, 'Bob');
</sql>
```

**Why**: External files may differ across replicas (different file systems, sync timing).

### 5. Auto-Generated Names

❌ **FORBIDDEN**:
```xml
<!-- Auto-generated index name -->
<createIndex tableName="users">
    <column name="email"/>
</createIndex>

<!-- Auto-generated foreign key name -->
<addForeignKeyConstraint baseTableName="posts" baseColumnNames="user_id"
                         referencedTableName="users" referencedColumnNames="id"/>
```

✅ **ALLOWED**:
```xml
<!-- Explicit index name -->
<createIndex indexName="idx_users_email" tableName="users">
    <column name="email"/>
</createIndex>

<!-- Explicit foreign key name -->
<addForeignKeyConstraint constraintName="fk_posts_user"
                         baseTableName="posts" baseColumnNames="user_id"
                         referencedTableName="users" referencedColumnNames="id"/>
```

**Why**: Auto-generated names may vary based on database state or Liquibase version.

### 6. System Environment Variables

❌ **FORBIDDEN**:
```xml
<!-- Using environment variables -->
<property name="schema.name" value="${DB_SCHEMA}"/>
<createTable tableName="${schema.name}.users">
    ...
</createTable>
```

✅ **ALLOWED**:
```xml
<!-- Hardcoded schema name -->
<property name="schema.name" value="public"/>
<createTable tableName="public.users">
    ...
</createTable>
```

**Why**: Environment variables differ across replicas.

### 7. Database-Specific Functions

❌ **FORBIDDEN**:
```xml
<!-- Using database-specific sequences -->
<addColumn tableName="users">
    <column name="id" type="BIGINT" defaultValueSequenceNext="user_id_seq"/>
</addColumn>
```

✅ **ALLOWED**:
```xml
<!-- Application-managed IDs -->
<addColumn tableName="users">
    <column name="id" type="BIGINT">
        <constraints nullable="false"/>
    </column>
</addColumn>
```

**Why**: Sequence state may diverge across replicas.

### 8. Non-Deterministic Ordering

❌ **FORBIDDEN**:
```xml
<!-- Using HashSet iteration (non-deterministic order) -->
<!-- This applies to internal Liquibase code, not changesets -->
```

✅ **ALLOWED**:
```xml
<!-- Explicit column order in table definitions -->
<createTable tableName="users">
    <column name="id" type="BIGINT"/>
    <column name="name" type="VARCHAR(255)"/>
    <column name="email" type="VARCHAR(255)"/>
</createTable>
```

**Why**: Column order, index order, and constraint order must be deterministic.

## Required Patterns

### 1. Sequential Changeset IDs

Always use sequential integer IDs:
```xml
<changeSet id="1" author="dev">...</changeSet>
<changeSet id="2" author="dev">...</changeSet>
<changeSet id="3" author="dev">...</changeSet>
```

### 2. Explicit Naming

Always name indexes, constraints, and foreign keys explicitly:
```xml
<createIndex indexName="idx_users_email" tableName="users">
    <column name="email"/>
</createIndex>

<addForeignKeyConstraint constraintName="fk_posts_user"
                         baseTableName="posts" baseColumnNames="user_id"
                         referencedTableName="users" referencedColumnNames="id"/>
```

### 3. Explicit Column Order

Declare columns in consistent order:
```xml
<createTable tableName="users">
    <column name="id" type="BIGINT">
        <constraints primaryKey="true" nullable="false"/>
    </column>
    <column name="username" type="VARCHAR(255)">
        <constraints nullable="false" unique="true"/>
    </column>
    <column name="email" type="VARCHAR(255)">
        <constraints nullable="false"/>
    </column>
</createTable>
```

### 4. Explicit Timestamps

Use epoch milliseconds from BlockClock:
```xml
<insert tableName="events">
    <column name="id" valueNumeric="1"/>
    <column name="created_at" valueNumeric="1704067200000"/>  <!-- Explicit epoch time -->
</insert>
```

### 5. Explicit ON DELETE/ON UPDATE Rules

Always specify referential integrity rules:
```xml
<addForeignKeyConstraint
    constraintName="fk_orders_customer"
    baseTableName="orders"
    baseColumnNames="customer_id"
    referencedTableName="customers"
    referencedColumnNames="id"
    onDelete="CASCADE"
    onUpdate="RESTRICT"/>
```

## Validation Strategy

### Static Analysis

Before applying a changelog, validate:

1. **No timestamp-based changeset IDs**: All IDs must be sequential integers
2. **No external file references**: All SQL must be inline
3. **No auto-generated names**: All indexes, constraints must have explicit names
4. **No forbidden functions**: Check for NOW(), RAND(), UUID(), etc.

### Runtime Testing

Use `MigrationDeterminismTest` to validate:

1. Run same changelog on 4 independent H2 databases
2. Compare schema hashes (all must match)
3. Compare data hashes (all must match)

If any hash differs, the migration is non-deterministic and MUST be fixed.

## Test Suite

The `liquibase-deterministic` module includes comprehensive determinism tests:

- **testBasicTableCreationDeterminism**: Validates table, column, and constraint creation
- **testIndexCreationDeterminism**: Validates index creation and ordering
- **testForeignKeyDeterminism**: Validates foreign key constraint creation
- **testDeterministicDataInsertion**: Validates data insertion, update, and delete operations

Run tests:
```bash
./mvnw test -pl liquibase-deterministic
```

All tests must pass before deploying migrations to production.

## Failure Modes

If a migration is non-deterministic:

1. **Replica divergence**: Different replicas compute different schemas/data
2. **Consensus failure**: Replicas cannot agree on next block
3. **Byzantine fault tolerance violated**: Minority can cause split-brain
4. **Data corruption**: Inconsistent state across cluster

## Best Practices

1. **Review all changesets**: Manually inspect for forbidden operations
2. **Run determinism tests**: Always run `MigrationDeterminismTest` before deployment
3. **Use sequential IDs**: Never use timestamps, UUIDs, or random IDs
4. **Explicit naming**: Name all database objects explicitly
5. **Inline SQL**: Never reference external files
6. **Deterministic data**: Use explicit values, never functions
7. **Document assumptions**: Comment why specific values are used

## Related Documentation

- **h2-deterministic/DETERMINISM.md**: H2 Database determinism requirements
- **sql-state/README.md**: SQL state machine architecture
- **SqlStateMachine.java**: Sequential transaction execution guarantees
- **TimeZoneProvider.java**: UTC-only timezone enforcement
- **SessionServices.java**: Deterministic service call requirements

## Example: Complete Deterministic Migration

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">

    <!-- ✅ Sequential changeset ID -->
    <changeSet id="1" author="dev">
        <comment>Create users table with explicit schema</comment>
        <createTable tableName="users">
            <!-- ✅ Explicit column order -->
            <column name="id" type="BIGINT">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="username" type="VARCHAR(255)">
                <constraints nullable="false" unique="true"/>
            </column>
            <column name="email" type="VARCHAR(255)">
                <constraints nullable="false"/>
            </column>
            <!-- ✅ Explicit timestamp (epoch milliseconds) -->
            <column name="created_at" type="BIGINT">
                <constraints nullable="false"/>
            </column>
        </createTable>
    </changeSet>

    <changeSet id="2" author="dev">
        <comment>Create explicit index on email</comment>
        <!-- ✅ Explicit index name -->
        <createIndex indexName="idx_users_email" tableName="users" unique="true">
            <column name="email"/>
        </createIndex>
    </changeSet>

    <changeSet id="3" author="dev">
        <comment>Insert deterministic seed data</comment>
        <!-- ✅ Explicit values only, no functions -->
        <insert tableName="users">
            <column name="id" valueNumeric="1"/>
            <column name="username" value="admin"/>
            <column name="email" value="admin@example.com"/>
            <column name="created_at" valueNumeric="1704067200000"/>
        </insert>
    </changeSet>

</databaseChangeLog>
```

## Summary

**Golden Rule**: If a migration uses any wall-clock time, random values, external files, or auto-generated names, it is **NON-DETERMINISTIC** and will cause consensus failure.

Always ask: "Will this produce identical results on all replicas?" If unsure, consult this document and run `MigrationDeterminismTest`.
