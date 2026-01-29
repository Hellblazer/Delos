# H2 Deterministic

Deterministic H2 SQL database implementation for replicated state machines.

## Overview

`h2-deterministic` provides a modified H2 database that executes SQL deterministically across all replicas. This is essential for Byzantine fault-tolerant state machines where all nodes must produce identical results from the same sequence of operations.

## Key Features

* **Deterministic execution**: Guaranteed identical results on all replicas for the same SQL sequence
* **Package shading**: Self-contained dependencies to avoid conflicts with application H2 versions
* **Byzantine-safe**: No randomization, consistent ordering, reproducible behavior
* **Full H2 compatibility**: Standard SQL, views, DDL/DML, stored procedures

## Important Notes

⚠️ **Do not import into IDEs**: This module uses package shading and should only be built via Maven. IDEs will show false errors. It must be installed in your local Maven repository via:

```bash
./mvnw clean install -Ppre -DskipTests
```

This module is a required dependency for the `sql-state` module and other replicated state machine implementations.

## Technical Details

See [RISKS.md](RISKS.md) for determinism guarantees, known limitations, and Byzantine safety considerations.

## Related Modules

* **liquibase-deterministic** - Deterministic schema migrations paired with H2
* **sql-state** - Replicated SQL state machines using this H2 implementation
* **schemas** - Database schema definitions

## Build Commands

Build this module with dependencies:
```bash
./mvnw install -amd -pl h2-deterministic
```

Build with the pre-release profile (required first-time):
```bash
./mvnw clean install -Ppre -DskipTests
```

## Testing

This module includes tests for determinism verification:
```bash
./mvnw test -pl h2-deterministic
```

---

Copyright (c) 2026, Hal Hildebrand.
All rights reserved.
GNU Affero General Public License
For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
