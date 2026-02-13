# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Copyright
Use the following as the copyright statment for all source files.  Replace <current year> with the current year.

"Copyright (c) <current year>, Hal Hildebrand.
  All rights reserved.
  GNU Affero General Public License
  For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
  This file is part of the Delos Distributed Systems Framework."

## Build Commands

**First-time setup (required once):**
```bash
./mvnw clean install -Ppre -DskipTests
```
This builds the deterministic SQL module (`h2-deterministic`) which must be installed before regular builds.

**Standard build:**
```bash
./mvnw clean install
```
The `install` goal is required as modules depend on each other via local Maven repository.

**Build single module with dependencies:**
```bash
./mvnw install -amd -pl <module-name>
```

**Run tests:**
```bash
./mvnw test                           # All tests
./mvnw test -pl <module>              # Single module tests
./mvnw test -Dtest=ClassName          # Single test class
./mvnw test -Dtest=ClassName#method   # Single test method
./mvnw clean install -Dlarge_tests=true  # Full test suite (resource-intensive)
```

**Build with GraalVM isolates:**
```bash
./mvnw clean install -Pisolates
```

**Generate sources (GRPC/Proto, JOOQ):**
```bash
./mvnw generate-sources
```

## Architecture Overview

Delos is a multi-tenant distributed system platform with Byzantine fault tolerance. Key architectural layers:

### Core Infrastructure
- **cryptography** - Self-describing digests, signatures, identifiers; Bloom filters
- **memberships** - Membership model, Context abstraction, GRPC routers, ring communication patterns
- **protocols** - GRPC MTLS service fundamentals, rate limiters
- **grpc** - All Protobuf/GRPC definitions and generated code (centralized)

### Identity & Security
- **stereotomy** - KERI (Key Event Receipt Infrastructure) implementation for decentralized identity
- **stereotomy-services** - GRPC services for KERI
- **thoth** - Distributed hash table for KERI key management
- **gorgoneion** / **gorgoneion-client** - Identity bootstrapping and attestation

### Consensus & State
- **fireflies** - Byzantine intrusion tolerant membership service and secure communications overlay
- **ethereal** - Aleph-BFT asynchronous atomic broadcast (consensus)
- **choam** - Committee-based replicated state machines on linear logs
- **sql-state** - JDBC-accessible SQL state machines on CHOAM logs

### Application Layer
- **model** - Process domains and multi-tenant sharding
- **delphinius** - Google Zanzibar-style Relation Based Access Control
- **tron** - Finite State Machine framework using Java Enums

### Platform Support
- **protocols** - gRPC MTLS service fundamentals with Unix domain socket support (via Netty NIO JEP 380)
- **leyden** - Additional platform features
- **isolates** - GraalVM isolate-based multi-tenant enclaves (requires `-Pisolates`)

## Architecture Decision Records

Critical architectural decisions are documented in `/docs/adr/`. Key model module ADRs:

- **[ADR-0008](docs/adr/0008-subdomain-isolation-strategy.md)**: Subdomain isolation strategy (GraalVM isolates vs in-process)
- **[ADR-0009](docs/adr/0009-portal-routing-semantics.md)**: Portal routing using context digests and Unix sockets
- **[ADR-0010](docs/adr/0010-delegation-gossip-protocol.md)**: KERI delegation propagation via anti-entropy gossip
- **[ADR-0011](docs/adr/0011-jdbc-connection-pooling-for-oracle.md)**: Thread-safe Oracle queries via DataSource pooling
- **[ADR-0012](docs/adr/0012-unix-domain-socket-architecture.md)**: Unix domain sockets for multi-tenant IPC

See also:
- **[ADR-0002](docs/adr/0002-keri-implementation-architecture.md)**: KERI identity architecture
- **[ADR-0003](docs/adr/0003-bft-membership-architecture.md)**: Fireflies Byzantine membership
- **[ADR-0004](docs/adr/0004-consensus-design-choam.md)**: CHOAM consensus design
- **[ADR-0005](docs/adr/0005-deterministic-sql-state.md)**: Deterministic SQL state machines

## Key Patterns

### Code Generation
- GRPC/Protobuf generation is centralized in the `grpc` module
- JOOQ generation occurs in modules that define schemas
- Generated sources go to `target/generated-sources/` (cleaned on `mvn clean`)

### Testing
- Tests use dynamic ports to avoid conflicts
- Standard tests: reduced client count; Full tests: `-Dlarge_tests=true`
- JUnit 5 with Mockito, AssertJ

### Deterministic SQL
The `h2-deterministic` and `liquibase-modified` modules provide deterministic SQL execution for replicated state machines. The `h2-deterministic` module uses package shading, while `liquibase-modified` uses forked source files. Both must NOT be imported into IDEs.

## Technology Stack
- Java 25+ (configured in pom.xml) - **Required for production**
- Maven 3.9.3+ with Maven Wrapper
- gRPC 1.68.0 / Protobuf 4.28.2
- H2 Database, JOOQ, Liquibase
- Netty 4.1.x for networking
- Bouncy Castle for cryptography
- Dropwizard Metrics
- SLF4J/Logback logging

## Knowledge Consolidation & ChromaDB (2026-01-26)

The Delos project maintains a consolidated semantic knowledge base in ChromaDB with 264+ indexed documents across 5 specialized collections.

### Collections

| Collection | Focus | Examples |
|-----------|-------|----------|
| `delos_consensus-architecture` | Byzantine, Ethereal, CHOAM, test infrastructure | CHOAM replication patterns, Ethereal reliability |
| `delos_cryptography-signatures` | BLS, aggregation, key rotation, performance | BLS-12-381 optimization, Crown aggregation |
| `delos_system-architecture` | Witness network, KERI, monitoring, protocols | FIREFLIES-KERI mapping, witness design |
| `delos_operational-decisions` | Audits, design reviews, risk assessment | Phase audits, design decisions, risk analysis |
| `delos_operational-roadmap` | Future work, phases, priorities, strategy | Future work backlog (50 items), phase plans |

### Search Tips

```bash
# Find consensus patterns
mgrep search "Byzantine detection framework" --store delos -a

# Find cryptographic design decisions
mgrep search "BLS signature aggregation" --store delos -a

# Find system integration patterns
mgrep search "KERI integration witness network" --store delos -a

# Find operational strategy
mgrep search "implementation roadmap phases" --store delos -a

# Cross-domain search
mgrep search "performance SLA Byzantine tolerance" --store delos -a
```

### Archive Reference

Historical documentation archived in `.pm-archives/Consolidation-20260126/` with manifest guide.

## Module Dependencies
Modules depend on each other through the local Maven repository. Always run `install` (not just `compile`) when building. The parent POM enforces dependency convergence.

## Module Entry Points

| Module | Main Entry Point | Purpose |
|--------|-----------------|---------|
| fireflies | `View.java` | Membership and gossip overlay |
| ethereal | `Ethereal.java` | Consensus protocol (Aleph-BFT) |
| choam | `CHOAM.java` | State machine replication |
| sql-state | `SqlStateMachine.java` | JDBC-accessible replicated state |
| stereotomy | `Stereotomy.java` | KERI identity management |
| delphinius | `Oracle.java` | Relation-based access control |
| tron | `Fsm.java` | Finite state machine execution |
| thoth | `Thoth.java` | DHT for key management |
| witness-service | `WitnessContext.java` | KERI witness network with BLS aggregation |

### FirefliesWitnessAdapter (KERI-Fireflies Integration)

The `FirefliesWitnessAdapter` maps KERI witness thresholds to Fireflies context configuration.

**Key formula:**
```
majority = rings - (rings - 1) / bias
bias = (witnessCount - 1) / (witnessCount - threshold)
```

**Usage patterns:**

```java
// Create context with KERI threshold semantics
var adapter = new FirefliesWitnessAdapter(DigestAlgorithm.DEFAULT);
DynamicContext<Member> context = adapter.createContext(
    contextId,
    witnessCount,  // KERI N (total witnesses)
    threshold,     // KERI T (required signatures)
    pByz           // Byzantine probability (typically 0.1)
);

// Select witnesses deterministically for an event
SequencedSet<Member> witnesses = adapter.selectWitnesses(context, eventCoordinates);

// Or use WitnessContext factory method
WitnessContext witnessCtx = WitnessContext.createWithAdapter(
    contextId, parameters, pByz, DigestAlgorithm.DEFAULT);
```

**Common threshold configurations:**
| Witnesses (N) | Threshold (T) | Bias | Description |
|---------------|---------------|------|-------------|
| 5 | 3 | 2 | Standard 2f+1 (f=1) |
| 5 | 4 | 4 | Higher security 3f+1 |
| 7 | 5 | 3 | Larger committee |

**Production monitoring:**
```java
// With metrics (recommended for production)
var metrics = new WitnessAdapterMetricsImpl(metricRegistry);
var adapter = new FirefliesWitnessAdapter(DigestAlgorithm.DEFAULT, metrics);

// Health check
if (!adapter.isHealthy()) {
    // Check metrics snapshot for SLA violations
    var snapshot = adapter.getMetricsSnapshot();
}
```

**SLA targets:** p95 latency ≤ 100ms, failure rate < 1%, circuit breaker closed

## Testing Structure

### Quick Reference

For comprehensive testing guidance, see **[docs/TESTING_GUIDE.md](docs/TESTING_GUIDE.md)** which covers:
- All test categories (unit, integration, cluster, Byzantine, stress, performance)
- Deterministic testing patterns (seeded randomness, frozen clocks, controlled parameters)
- Byzantine FT testing framework (fault injection, detection validation, recovery)
- Resource management (lifecycle, ports, memory, timeouts)
- Enforcement checklist and common pitfalls
- Concrete examples and performance baselines

### Test Categories

- **Unit tests**: `./mvnw test` - Isolated component tests, fast feedback
- **Integration tests**: Multi-component tests with real I/O (database, gRPC)
- **Cluster tests**: Multi-node distributed system tests with gossip/consensus
- **Byzantine tests**: Byzantine fault tolerance with injected failures (3f+1 nodes)
- **Stress tests**: High load tests (requires `-Dlarge_tests=true`, 8+ GB RAM)
- **Performance tests**: Throughput and latency measurement with SLA validation

### Test Execution

```bash
# Standard mode (fast, ~10-15 min)
./mvnw test

# Thorough mode (full suite, ~45-60 min, requires 8+ GB RAM)
./mvnw clean install -Dlarge_tests=true

# Single module
./mvnw test -pl <module>

# Single test class
./mvnw test -pl <module> -Dtest=ClassName

# Single test method
./mvnw test -pl <module> -Dtest=ClassName#methodName

# Tests matching pattern
./mvnw test -Dtest="*Integration*"
```

### Memory Requirements

- **Standard tests**: JVM defaults sufficient (~2-4 GB heap)
- **Large tests**: Requires increased heap: `-DargLine="-Xmx12G -Xms6G"` (8+ GB total)

### Core Patterns

- **Dynamic port allocation**: Tests use port 0, OS assigns available port (no conflicts)
- **Deterministic execution**: SeededSecureRandom, Clock.fixed() for reproducibility
- **JUnit 5**: AssertJ fluent assertions, Mockito mocking
- **Independent tests**: Each test is isolation, no shared state, can run in any order
- **Cluster formation**: Real multi-node coordination with gossip protocols
- **Byzantine injection**: GorgoneionBftTestHelpers for fault injection and detection

### Deterministic Testing

Tests must be deterministic to enable debugging, CI reproducibility, and debugging:

```java
// Use SeededSecureRandom instead of Random
var random = new SeededSecureRandom("test-seed");

// Use Clock.fixed() for time-dependent operations
var clock = Clock.fixed(Instant.parse("2026-01-27T10:00:00Z"), ZoneId.of("UTC"));

// Use largeTests flag for controlled parameters
static final boolean largeTests = Boolean.parseBoolean(System.getProperty("large_tests", "false"));
var clusterSize = largeTests ? 100 : 10;  // Adapts to fast/thorough mode
```

### Byzantine Testing Infrastructure

Delos includes comprehensive Byzantine fault testing support:

- **GorgoneionBftTestHelpers**: Fault injection (crashes, delays, equivocation)
- **ByzantineDetector**: Multi-signal detection (signatures, timing, rate, state anomalies)
- **TestContext**: Cluster management with member lifecycle and failure handling
- **Fault categories**: Equivocation, signature forgery, timing anomaly, fork detection, threshold bypass

Example: Test with 3f+1 nodes (3 honest + 1 Byzantine, f=1 tolerance):
```java
var cluster = TestCluster.create(4);
cluster.setByzantine(3);  // Node 3 is Byzantine
cluster.execute(transaction);  // Consensus continues despite Byzantine member
```

### Running Specific Tests

```bash
# Run all tests in fireflies module
./mvnw test -pl fireflies

# Run all cluster tests
./mvnw test -Dtest="*ClusterTest"

# Run all Byzantine tests
./mvnw test -Dtest="*ByzantineTest"

# Run with debugging
./mvnw test -pl <module> -Dtest=ClassName#method \
  -DargLine="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005"
```

## Common Development Tasks

### After Protocol Buffer Changes

When you modify `.proto` files in `src/main/proto/` or `src/test/proto/`:
```bash
./mvnw clean compile  # Regenerates gRPC and protobuf classes
```

Generated sources appear in `target/generated-sources/protobuf/`.

### Debugging a Single Test

Run a test with remote debugging enabled:
```bash
./mvnw test -pl <module> -Dtest=ClassName#methodName \
  -DargLine="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005"
```

Then attach your IDE debugger to port 5005.

### Dependency Analysis

View the full dependency tree for a module:
```bash
./mvnw dependency:tree -pl <module> -DoutputFile=deps.txt
```

Check for dependency updates:
```bash
./mvnw versions:display-dependency-updates
```

Analyze dependency usage:
```bash
./mvnw dependency:analyze -pl <module>
```

### After Database Schema Changes

When you modify Liquibase changesets:
```bash
./mvnw clean compile  # Regenerates JOOQ classes from schema
```

### Check Enforcer Rules

The build enforces dependency convergence and other rules:
```bash
./mvnw enforcer:enforce
```

## Troubleshooting

### Build Failures

**"h2-deterministic not found"**

Run the first-time setup profile:
```bash
./mvnw clean install -Ppre -DskipTests
```

This builds the deterministic SQL module which must be installed in your local Maven repository once.

**"Cannot resolve dependencies"**

Always use `install` not `compile`:
```bash
./mvnw install -amd -pl <module>
```

The `-amd` (also-make-dependents) flag ensures dependencies are built.

**"dependencyConvergence" error**

The enforcer plugin requires all transitive dependencies converge to a single version. Check the dependency tree:
```bash
./mvnw dependency:tree -Dverbose -DoutputFile=tree.txt
```

Look for conflicts and add explicit `<dependencyManagement>` entries in the parent POM.

### Test Failures

**Test Timeouts**

Increase timeout or check for port conflicts. Tests use dynamic ports but may conflict with other processes:
```bash
# Check what's using common ports
lsof -i :9090
```

**OutOfMemoryError in Tests**

Increase heap size for large tests:
```bash
./mvnw test -DargLine="-Xmx12G -Xms6G"
```

**Flaky Tests in CI**

Some distributed consensus tests may be timing-sensitive. Retry flaky tests:
```bash
./mvnw test -Dsurefire.rerunFailingTestsCount=2
```

### IDE Issues

**"Cannot resolve symbol" in IDE**

Run Maven generate-sources to create generated code:
```bash
./mvnw generate-sources
```

Then refresh your IDE's Maven project.

**IDE Shows Red in h2-deterministic**

This module uses package shading and should NOT be imported into IDEs. It's built via Maven only. Exclude it from your IDE's module import.

**IntelliJ IDEA Slow Indexing**

Exclude `target/` directories from indexing:
- Go to Settings → Project Structure → Modules
- Mark `target` directories as "Excluded"

### GraalVM Isolates Failing

**Prerequisites missing**

Ensure GraalVM is installed and `GRAALVM_HOME` is set:
```bash
export GRAALVM_HOME=/path/to/graalvm
./mvnw clean install -Pisolates
```

**Native image agent failures**

The native-image-agent may need manual configuration for reflection and resources:
```bash
./mvnw -Pnative-agent test -pl <module>
```

Check generated configuration in `src/main/resources/META-INF/native-image/`.

### Runtime Issues

**"Port already in use"**

Tests use dynamic port allocation (port 0), but manual testing may conflict. Check for existing processes:
```bash
# macOS/Linux
lsof -ti:PORT | xargs kill -9

# Find process using port range
netstat -ano | grep LISTEN
```

**Certificate validation failures**

KERI-based MTLS requires valid certificates. Check stereotomy configuration and ensure keystores are properly initialized.

**Consensus not converging**

Byzantine fault tolerance requires `3f+1` nodes to tolerate `f` failures. Ensure:
- Minimum 4 nodes for 1 failure tolerance
- Network connectivity between all nodes
- No clock skew > 500ms between nodes

## IDE Configuration

### IntelliJ IDEA

The repository includes `.idea` configuration with:
- Code style settings
- Run configurations in `.run/`
- Shared inspections

Import the project as a Maven project and IDEA will use these settings automatically.

### VS Code

Install extensions:
- **Language Support for Java** (Red Hat)
- **Maven for Java** (Microsoft)
- **Protocol Buffers** (pbkit)

Configure `settings.json`:
```json
{
  "java.configuration.updateBuildConfiguration": "automatic",
  "java.compile.nullAnalysis.mode": "automatic"
}
```

### Eclipse

Use **M2Eclipse** plugin for Maven integration. Import as "Existing Maven Project".
