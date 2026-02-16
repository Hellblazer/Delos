# Liquibase Modified

Modified Liquibase implementation for deterministic database migrations in replicated state machines.

## Overview

`liquibase-modified` provides a forked version of Liquibase that ensures deterministic schema migration execution across all Byzantine fault-tolerant replicas. This is essential for CHOAM-based SQL state machines where all nodes must produce identical schemas from the same sequence of migrations.

## Why This Module Exists

Standard Liquibase uses `ThreadLocal` singleton patterns for internal factories (`SqlGeneratorFactory`, `ChangeLogHistoryServiceFactory`). While this design works for single-threaded applications, it poses determinism challenges for replicated state machines:

1. **ThreadLocal isolation**: Each thread gets its own factory instance, making cross-thread execution non-deterministic
2. **Generator registration order**: Concurrent registration from multiple threads could produce different SQL generation orders
3. **Service selection**: Without deterministic ordering, replicas could select different implementations

For Byzantine fault tolerance, **all replicas must execute migrations identically**. This requires:
- Single-threaded migration execution per replica (guaranteed by `SqlStateMachine`)
- Deterministic generator/service selection order (enforced by `TreeSet` with comparators)
- Explicit documentation of threading constraints (added in this fork)

## Key Modifications

Unlike `h2-deterministic` which uses package shading, `liquibase-modified` uses **forked source files** to avoid conflicts with the standard Liquibase version. This approach:

- Preserves standard Liquibase API compatibility
- Allows applications to use both standard and modified Liquibase in the same classpath
- Enables explicit documentation of determinism requirements in source code

### Modified Classes

The following Liquibase classes have been forked and modified:

1. **`liquibase/sqlgenerator/SqlGeneratorFactory.java`**
   - **Modification**: Added comprehensive documentation explaining ThreadLocal usage and determinism requirements
   - **Why**: Clarifies that migrations MUST execute on a single thread to ensure same factory instance
   - **Key insight**: Generator selection uses `TreeSet` with `SqlGeneratorComparator` for deterministic ordering

2. **`liquibase/changelog/ChangeLogHistoryServiceFactory.java`**
   - **Modification**: Added documentation explaining ThreadLocal pattern and BFT considerations
   - **Why**: Service selection must be deterministic across replicas
   - **Key insight**: Services sorted by priority comparator for consistent selection order

3. **`liquibase/util/SmartMap.java`**
   - **Modification**: Added determinism notes for collection ordering
   - **Why**: Ensures iteration order is deterministic when used in migration context

### What Was NOT Modified

Standard Liquibase functionality remains unchanged:
- XML/YAML/JSON changelog parsing
- Change type implementations (createTable, addColumn, etc.)
- Database abstraction layer
- Migration execution logic
- Rollback support

The modifications are **documentation and clarity only**—no algorithmic changes to Liquibase's core behavior.

## Architecture

### Integration with Deterministic SQL Stack

```
┌─────────────────────────────────────────┐
│  SqlStateMachine (sql-state module)     │  ← Single-threaded executor
│  - Executes migrations sequentially     │
│  - Guarantees same thread for Liquibase │
└──────────────┬──────────────────────────┘
               │
               ↓
┌─────────────────────────────────────────┐
│  liquibase-modified                     │  ← Modified factories
│  - ThreadLocal SqlGeneratorFactory      │
│  - ThreadLocal ChangeLogHistoryService  │
│  - Deterministic ordering guarantees    │
└──────────────┬──────────────────────────┘
               │
               ↓
┌─────────────────────────────────────────┐
│  h2-deterministic                       │  ← Deterministic database
│  - Seeded RANDOM() functions            │
│  - Block-timestamp TIME functions       │
└─────────────────────────────────────────┘
```

### How Determinism is Guaranteed

1. **Single-threaded execution**: `SqlStateMachine.acceptMigration()` uses a single-threaded executor, ensuring all Liquibase operations on a replica occur on the same thread
2. **Generator selection order**: `SqlGeneratorFactory` uses `TreeSet<SqlGenerator>` with `SqlGeneratorComparator`, providing deterministic priority-based ordering
3. **Service selection order**: `ChangeLogHistoryServiceFactory` sorts services by priority using a comparator
4. **Schema metadata consistency**: All replicas see identical database state before migration (guaranteed by CHOAM consensus)

## Important Constraints

### ⚠️ DO NOT Import Into IDEs

This module uses forked source files that overlap with standard Liquibase classes. Importing it into IDEs will cause:
- Classpath conflicts
- Duplicate class errors
- False compilation errors

**Build via Maven only**:
```bash
./mvnw clean install -Ppre -DskipTests
```

After this one-time setup, the module is installed in your local Maven repository and does not need to be imported into IDEs.

### Required for First-Time Setup

This module is part of the `-Ppre` profile, which must be run once per repository clone:

```bash
# First-time setup (builds both h2-deterministic and liquibase-modified)
./mvnw clean install -Ppre -DskipTests
```

This installs the modified Liquibase to your local Maven repository at:
```
~/.m2/repository/com/hellblazer/delos/liquibase-modified/
```

After installation, standard builds (`./mvnw clean install`) automatically use the locally installed version.

### Migration Determinism Requirements

**CRITICAL**: Liquibase changesets must follow strict determinism rules. Non-deterministic operations will cause replica divergence and consensus failure.

See **[DETERMINISM_REQUIREMENTS.md](DETERMINISM_REQUIREMENTS.md)** for comprehensive guidelines on:
- Forbidden operations (RAND(), NOW(), UUID generation, external files)
- Required patterns (sequential changeset IDs, explicit naming, deterministic data)
- Validation strategy (static analysis, runtime testing)
- Failure modes and best practices

Example forbidden changeset:
```xml
❌ FORBIDDEN (non-deterministic):
<changeSet id="timestamp-1704067200000" author="dev">
  <insert tableName="events">
    <column name="created_at" valueComputed="NOW()"/>
    <column name="uuid" valueComputed="RANDOM_UUID()"/>
  </insert>
</changeSet>
```

Example allowed changeset:
```xml
✅ ALLOWED (deterministic):
<changeSet id="1" author="dev">
  <insert tableName="events">
    <column name="id" valueNumeric="1"/>
    <column name="created_at" valueNumeric="1704067200000"/>
    <column name="uuid" value="550e8400-e29b-41d4-a716-446655440000"/>
  </insert>
</changeSet>
```

## Build Instructions

### First-Time Setup

Run once per repository clone:
```bash
./mvnw clean install -Ppre -DskipTests
```

**What this does**:
1. Builds `h2-deterministic` and `liquibase-modified` modules
2. Installs them to local Maven repository (`~/.m2/repository/`)
3. Makes them available for subsequent builds without rebuilding

**Build time**: ~2-5 minutes (faster than h2-deterministic due to fewer dependencies)

### When to Rebuild

You typically **do not** need to rebuild this module unless:
- You update to a new Delos version that includes Liquibase changes
- You modify Liquibase source files in `src/main/java/liquibase/`
- You clean your local Maven repository (`rm -rf ~/.m2/repository/com/hellblazer/delos/liquibase-modified/`)

After rebuilding, always run the full pre-install profile:
```bash
./mvnw clean install -Ppre -DskipTests
```

### Build Single Module

If you need to rebuild just this module:
```bash
./mvnw install -pl liquibase-modified
```

## Testing

### Determinism Tests

The module includes comprehensive multi-replica determinism tests:

```bash
./mvnw test -pl liquibase-modified
```

**Test coverage**:
- **`testBasicTableCreationDeterminism`**: Table, column, constraint creation across 4 replicas
- **`testIndexCreationDeterminism`**: Index creation and ordering consistency
- **`testForeignKeyDeterminism`**: Foreign key constraint determinism
- **`testDeterministicDataInsertion`**: Data insertion with explicit values

Each test:
1. Creates 4 independent H2 databases (simulating replicas)
2. Runs identical Liquibase changelogs on each
3. Computes schema/data hashes using SHA-256
4. Asserts all replicas produce byte-for-byte identical results

**Test failure = Non-deterministic migration** → Must fix changeset before production use.

### Test Changelogs

Test changelogs are in `src/test/resources/changelogs/`:
- `basic-tables.xml`: Table creation patterns
- `indexes.xml`: Index creation with explicit naming
- `foreign-keys.xml`: Foreign key constraints
- `deterministic-data.xml`: Data insertion with explicit values

These serve as examples of **correct deterministic migration patterns**.

## Maintenance

### Updating Liquibase Version

If Liquibase releases a new version with critical fixes:

1. **Update parent POM**: Change `liquibase-core` version in `delos.app/pom.xml`
2. **Review modified files**: Check if forked files need updates:
   - `src/main/java/liquibase/sqlgenerator/SqlGeneratorFactory.java`
   - `src/main/java/liquibase/changelog/ChangeLogHistoryServiceFactory.java`
   - `src/main/java/liquibase/util/SmartMap.java`
3. **Re-apply modifications**: Merge Delos-specific documentation into updated source
4. **Run determinism tests**: Verify `./mvnw test -pl liquibase-modified` still passes
5. **Rebuild**: `./mvnw clean install -Ppre -DskipTests`

**Important**: Always test migration determinism after Liquibase version updates. New Liquibase features may introduce non-deterministic behavior.

### Adding New Modified Classes

If you need to fork additional Liquibase classes:

1. Copy source file to `src/main/java/liquibase/[package]/`
2. Add Delos copyright header
3. Document determinism considerations in class Javadoc
4. Add tests in `MigrationDeterminismTest` if needed
5. Update this README with modification rationale

## Related Modules

| Module | Relationship |
|--------|-------------|
| **h2-deterministic** | Companion module providing deterministic H2 database (uses package shading) |
| **sql-state** | Consumer module that uses both liquibase-modified and h2-deterministic for SQL state machines |
| **schemas** | Defines database schemas used by sql-state applications |

## Related Documentation

- **[DETERMINISM_REQUIREMENTS.md](DETERMINISM_REQUIREMENTS.md)**: Comprehensive migration determinism rules and examples
- **[docs/adr/0005-deterministic-sql-state.md](../docs/adr/0005-deterministic-sql-state.md)**: Architecture decision record for deterministic SQL
- **[h2-deterministic/README.md](../h2-deterministic/README.md)**: Deterministic H2 database implementation
- **[sql-state/README.md](../sql-state/README.md)**: SQL state machine architecture

## Troubleshooting

### Build Failures

**"Cannot find liquibase-modified dependency"**

Run first-time setup:
```bash
./mvnw clean install -Ppre -DskipTests
```

**"Duplicate class definitions in IDE"**

Do NOT import this module into IDEs. It should only be built via Maven and used as a dependency from the local Maven repository.

**"liquibase-modified tests failing"**

Schema hash mismatch indicates non-deterministic migration:
1. Review test output for which replica diverged
2. Check changeset for forbidden operations (RAND(), NOW(), UUID generation)
3. See DETERMINISM_REQUIREMENTS.md for allowed patterns
4. Fix changeset and re-run tests

### Runtime Issues

**"Migrations produce different schemas on different replicas"**

This indicates a violation of determinism requirements:
1. Enable debug logging: `<logger name="com.hellblazer.delos.liquibase" level="DEBUG"/>`
2. Compare Liquibase SQL output on both replicas
3. Look for differences in generated SQL (constraint names, index order, etc.)
4. Ensure all database object names are explicit (no auto-generation)

**"ChangeLogHistoryService not found"**

This is a Liquibase classpath issue:
1. Verify liquibase-modified is in dependencies (should be transitive via sql-state)
2. Check for standard Liquibase version conflicts in dependency tree:
   ```bash
   ./mvnw dependency:tree -Dverbose | grep liquibase
   ```
3. Ensure no exclusions are removing liquibase-modified

## Production Considerations

### Byzantine Fault Tolerance

This module is **production-ready** for Byzantine fault-tolerant SQL state machines, provided:

1. ✅ All migrations follow determinism requirements (see DETERMINISM_REQUIREMENTS.md)
2. ✅ `SqlStateMachine` enforces single-threaded execution (guaranteed by design)
3. ✅ All replicas use identical Liquibase and H2 versions (enforced by CHOAM log)
4. ✅ Migration tests pass (`MigrationDeterminismTest` suite)

### Schema Evolution Strategy

For production schema evolution:

1. **Test migrations** in dev/staging with multi-replica cluster
2. **Run determinism tests** to verify byte-for-byte schema consistency
3. **Deploy via CHOAM** as Migration transactions (not direct SQL)
4. **Monitor consensus** for any replica divergence after migration
5. **Roll forward only** (Liquibase rollback not supported in BFT mode)

---

Copyright (c) 2026, Hal Hildebrand.
All rights reserved.
GNU Affero General Public License
For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
This file is part of the Delos Distributed Systems Framework.
