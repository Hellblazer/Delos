# Delos Developer Quick Start

**Time to complete:** 30 minutes
**Prerequisites:** JDK 25+, Maven 3.9.3+, Git

This guide walks you through building Delos from source, running tests, and understanding the architecture. By the end, you'll have a working development environment and basic understanding of the system.

---

## Part 1: Environment Setup (5 minutes)

### Check Prerequisites

Verify you have the required tools installed:

```bash
java -version  # Must show version 25 or higher
mvn -version   # Must show 3.9.3 or higher
git --version  # Any recent version
```

**Expected output**:
```
java version "25..." or higher
Apache Maven 3.9.3 or higher
```

If you need to install these:
- **JDK 25+**: [Download from Oracle](https://www.oracle.com/java/technologies/downloads/) or use [SDKMAN](https://sdkman.io/)
- **Maven**: Included as `mvnw` wrapper in the repository (no separate install needed)

### Clone and Initial Build

Clone the Delos repository:

```bash
git clone https://github.com/Hellblazer/Delos.git
cd Delos
```

**First-time setup** (one-time, ~5-10 minutes):

```bash
./mvnw clean install -Ppre -DskipTests
```

This builds the deterministic SQL module (`h2-deterministic`) which must be installed in your local Maven repository before regular builds.

**Standard build**:

```bash
./mvnw clean install
```

This will:
1. Compile all modules
2. Generate sources (gRPC, JOOQ)
3. Run the standard test suite
4. Install artifacts to `~/.m2/repository`

**✓ Checkpoint**: Build completes successfully with `BUILD SUCCESS` message (~10-15 minutes for first build).

---

## Part 2: Run the Test Suite (5 minutes)

### Quick Validation

Run tests for the core membership module:

```bash
./mvnw test -pl fireflies
```

**Expected**: Tests pass with green output (may take 2-3 minutes).

### Understanding Test Output

Look for:
```
[INFO] Tests run: X, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

If tests fail:
- Check logs in `fireflies/target/surefire-reports/`
- Ensure no other services are using ports (tests use dynamic port allocation)
- Try running again (distributed systems tests can occasionally be timing-sensitive)

### Run a Single Test Class

```bash
./mvnw test -pl fireflies -Dtest=SwarmTest
```

This runs just the `SwarmTest` class, which tests large-scale membership formation (50-100 nodes).

**✓ Checkpoint**: Tests pass successfully.

---

## Part 3: Understanding the Architecture (5 minutes)

Delos is organized into four main layers:

### Layer 1: Core Infrastructure
- **cryptography** - Self-describing cryptographic primitives (Digest, Signature, Identifier)
- **memberships** - Context abstraction and ring-based communication patterns
- **protocols** - gRPC MTLS fundamentals
- **grpc** - Centralized Protocol Buffer definitions

### Layer 2: Identity & Security
- **stereotomy** - KERI (Key Event Receipt Infrastructure) for decentralized identity
- **thoth** - Distributed hash table for key management
- **gorgoneion** - Identity bootstrapping and attestation

### Layer 3: Consensus & State
- **fireflies** - Byzantine fault-tolerant membership service (stable views)
- **ethereal** - Aleph-BFT asynchronous consensus
- **choam** - Committee-based state machine replication on linear logs
- **sql-state** - JDBC-accessible SQL state machines

### Layer 4: Application
- **delphinius** - Relation-based access control (Zanzibar-style)
- **tron** - Finite state machine framework using Java enums
- **model** - Process domains and multi-tenant sharding

### Data Flow: Transaction to State

```
Client submits transaction
    ↓
CHOAM (routes to current committee)
    ↓
Ethereal (Aleph-BFT consensus on order)
    ↓
Ordered block of transactions
    ↓
SQL-State (executes against replicated H2 database)
    ↓
All nodes have identical state
```

For detailed architecture, see [ARCHITECTURE.md](ARCHITECTURE.md).

**✓ Checkpoint**: You understand the four layers and basic data flow.

---

## Part 4: Your First Delos Code Exploration (15 minutes)

### Explore a Core Module: Fireflies

The `fireflies` module provides Byzantine fault-tolerant membership. Let's explore its structure:

```bash
cd fireflies
ls src/main/java/com/hellblazer/delos/fireflies/
```

**Key classes**:
- `View.java` - Main entry point for membership management
- `FirefliesMetrics.java` - Observability and monitoring
- `Parameters.java` - Configuration

### Read a Test to Understand Usage

Open `fireflies/src/test/java/com/hellblazer/delos/fireflies/SwarmTest.java` (abbreviated example):

```java
@Test
public void swarm() throws Exception {
    // Create 50-100 member swarm (based on large_tests flag)
    final var seeds = members.values()
                             .stream()
                             .map(m -> new Seed(m.getIdentifier().getIdentifier(), "0"))
                             .limit(largeTests ? 100 : 10)
                             .toList();

    final var bootstrapSeed = seeds.subList(0, 1);
    final var gossipDuration = Duration.ofMillis(largeTests ? 150 : 5);

    // Bootstrap the first member
    var countdown = new AtomicReference<>(new CountDownLatch(1));
    views.get(0).start(() -> countdown.get().countDown(), gossipDuration, Collections.emptyList());

    assertTrue(countdown.get().await(60, TimeUnit.SECONDS), "Kernel did not bootstrap");

    // Start remaining seed members
    var bootstrappers = views.subList(0, seeds.size());
    countdown.set(new CountDownLatch(seeds.size() - 1));
    bootstrappers.subList(1, bootstrappers.size())
                 .forEach(v -> v.start(() -> countdown.get().countDown(), gossipDuration, bootstrapSeed));

    // Verify all members formed stable view
    var success = countdown.get().await(largeTests ? 2400 : 60, TimeUnit.SECONDS);
    assertTrue(success);
}
```

**Key concepts demonstrated**:
1. **Seeds**: Bootstrap nodes that new members contact to join
2. **View formation**: Members discover each other via ring-based gossip
3. **Countdown latches**: Synchronization mechanism for test coordination
4. **Gossip duration**: Timing parameter controlling message frequency
5. **Dynamic scaling**: Test adapts based on `large_tests` flag (50 vs 100 nodes)

### Run This Test

```bash
cd ..  # Return to root
./mvnw test -pl fireflies -Dtest=SwarmTest#swarm
```

**Expected**: Test passes after ~60 seconds, showing successful 50-node cluster formation. For larger scale:

```bash
./mvnw test -pl fireflies -Dtest=SwarmTest#swarm -Dlarge_tests=true
```

This runs the 100-node version (requires more memory and time).

### Explore sql-state: JDBC over Consensus

The `sql-state` module provides replicated SQL databases. Check out an example:

```bash
ls sql-state/src/test/java/com/hellblazer/delos/state/
```

Look at `SmokeTest.java` to see how deterministic SQL execution ensures replicated state consistency. The test verifies that two independent H2 databases produce identical checkpoints when executing the same operations in the same order - the foundation of replicated state machines.

**✓ Checkpoint**: You've explored actual Delos code and run specific tests.

---

## Part 5: Common Development Tasks (Summary)

Now that you have a working environment, here are common tasks you'll perform:

### After Modifying Protocol Buffers

```bash
./mvnw clean compile  # Regenerates gRPC classes
```

### After Modifying Database Schemas

```bash
./mvnw clean compile  # Regenerates JOOQ classes
```

### Debugging a Test

```bash
./mvnw test -pl <module> -Dtest=ClassName#method \
  -DargLine="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005"
```

Then attach your IDE debugger to port 5005.

### Building a Single Module with Dependencies

```bash
./mvnw install -amd -pl <module>
```

The `-amd` flag (also-make-dependents) ensures dependencies are built.

### Running Large Tests (Resource-Intensive)

```bash
./mvnw test -Dlarge_tests=true
```

Requires 8+ GB RAM. Runs full test suites with higher node counts.

---

## Part 6: Next Steps

Congratulations! You now have:
- ✅ A working Delos development environment
- ✅ Successfully built and tested the codebase
- ✅ Basic understanding of the architecture
- ✅ Explored actual code and tests

### Learn More

**Explore specific modules**:
- [fireflies/README.md](../fireflies/README.md) - Deep dive into membership
- [choam/README.md](../choam/README.md) - State machine replication
- [ethereal/README.md](../ethereal/README.md) - Aleph-BFT consensus
- [sql-state/README.md](../sql-state/README.md) - JDBC over consensus
- [stereotomy/README.md](../stereotomy/README.md) - KERI identity

**Read documentation**:
- [ARCHITECTURE.md](ARCHITECTURE.md) - Visual architecture diagrams
- [DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md) - Production deployment *(coming soon)*
- [MONITORING_GUIDE.md](MONITORING_GUIDE.md) - Metrics and observability *(coming soon)*
- [GLOSSARY.md](GLOSSARY.md) - Key terminology

**Understand the codebase**:
- [CLAUDE.md](../CLAUDE.md) - Complete developer reference with troubleshooting
- [CONTRIBUTING.md](../CONTRIBUTING.md) - Contribution guidelines

**Try examples**:
- [examples/simple-kv-store/](../examples/simple-kv-store/) - Minimal SQL-State application
- [examples/local-demo/](../examples/local-demo/) - Docker Compose cluster

### Get Help

- **Documentation**: Check the `docs/` directory for guides
- **Issues**: Found a bug? [File an issue](https://github.com/Hellblazer/Delos/issues)
- **Questions**: See [GitHub Discussions](https://github.com/Hellblazer/Delos/discussions)

### Start Contributing

Ready to contribute? See [CONTRIBUTING.md](../CONTRIBUTING.md) for:
- Code style guidelines
- Testing requirements
- Pull request process
- Commit message format

---

## Troubleshooting Quick Reference

**Build fails with "h2-deterministic not found"**:
```bash
./mvnw clean install -Ppre -DskipTests
```

**Tests timeout or fail randomly**:
- Check for port conflicts: `lsof -i :9090`
- Retry: `./mvnw test -Dsurefire.rerunFailingTestsCount=2`
- Increase timeout in test if needed

**IDE shows errors in generated code**:
```bash
./mvnw generate-sources  # Regenerate, then refresh IDE
```

**"Cannot resolve symbol" in h2-deterministic**:
- Don't import h2-deterministic into your IDE
- It uses package shading and should only be built via Maven

**OutOfMemoryError in tests**:
```bash
./mvnw test -DargLine="-Xmx12G -Xms6G"
```

For complete troubleshooting, see [CLAUDE.md](../CLAUDE.md#troubleshooting).

---

**Quick Start Complete!** You're now ready to explore Delos and start contributing.

**Time invested**: ~30 minutes
**Skills gained**: Build system, testing, architecture basics, code exploration
