# CHOAM Developer Guide

Development guide for CHOAM contributors covering architecture, testing, debugging, and code conventions.

**Version**: 0.0.6-SNAPSHOT
**Last Updated**: 2026-02-08
**Target Audience**: Contributors, Maintainers, Developers

---

## Table of Contents

- [Getting Started](#getting-started)
- [Architecture Deep Dive](#architecture-deep-dive)
- [Development Workflow](#development-workflow)
- [Adding Features](#adding-features)
- [Testing](#testing)
- [Debugging](#debugging)
- [Performance Optimization](#performance-optimization)
- [Code Conventions](#code-conventions)
- [Build and CI](#build-and-ci)

---

## Getting Started

### Development Environment Setup

**Prerequisites**:
- Java 24+ JDK
- Maven 3.9.3+ (Maven Wrapper included)
- Git 2.x
- IDE: IntelliJ IDEA (recommended) or Eclipse

**Clone and build**:

```bash
# Clone repository
git clone https://github.com/Hellblazer/Delos.git
cd Delos

# First-time build (includes h2-deterministic dependency)
./mvnw clean install -Ppre -DskipTests

# Standard build
./mvnw clean install

# Build single module (choam)
./mvnw install -amd -pl choam
```

**IDE configuration**:

**IntelliJ IDEA**:
1. Import as Maven project
2. Enable annotation processing: Settings → Build → Compiler → Annotation Processors
3. Set JDK to 24+: File → Project Structure → Project SDK
4. Import code style: `.idea/codeStyles/Project.xml` (already in repo)

**Eclipse**:
1. Import as Existing Maven Project
2. Configure JDK: Window → Preferences → Java → Installed JREs
3. Enable annotation processing: Project Properties → Java Compiler → Annotation Processing

### Running Tests

```bash
# All tests
./mvnw test

# CHOAM module tests only
./mvnw test -pl choam

# Single test class
./mvnw test -pl choam -Dtest=CombineTest

# Single test method
./mvnw test -pl choam -Dtest=CombineTest#testRecovery

# Large test suite (requires 8+ GB RAM)
./mvnw clean install -Dlarge_tests=true
```

### Running CHOAM Locally

**4-node local cluster for development**:

```bash
# Terminal 1 (node 1)
java -cp choam/target/choam-0.0.6-SNAPSHOT-tests.jar:choam/target/choam-0.0.6-SNAPSHOT.jar \
  com.hellblazer.delos.choam.TestCHOAM --node 1

# Terminal 2 (node 2)
java -cp choam/target/choam-0.0.6-SNAPSHOT-tests.jar:choam/target/choam-0.0.6-SNAPSHOT.jar \
  com.hellblazer.delos.choam.TestCHOAM --node 2

# Terminal 3 (node 3)
java -cp choam/target/choam-0.0.6-SNAPSHOT-tests.jar:choam/target/choam-0.0.6-SNAPSHOT.jar \
  com.hellblazer.delos.choam.TestCHOAM --node 3

# Terminal 4 (node 4)
java -cp choam/target/choam-0.0.6-SNAPSHOT-tests.jar:choam/target/choam-0.0.6-SNAPSHOT.jar \
  com.hellblazer.delos.choam.TestCHOAM --node 4
```

**Verify cluster**:

```bash
# Check node 1 status
curl http://localhost:8080/ready
# Response: {"status": "READY", "view": "...", "epoch": 0}

# Submit test transaction
curl -X POST http://localhost:8080/api/submit \
  -H "Content-Type: application/json" \
  -d '{"data": "test"}'
```

---

## Architecture Deep Dive

### Module Structure

```
choam/
├── src/
│   ├── main/
│   │   ├── java/com/hellblazer/delos/choam/
│   │   │   ├── CHOAM.java                    # Main facade
│   │   │   ├── support/
│   │   │   │   ├── Combine.java              # Mercantile FSM
│   │   │   │   ├── Driven.java               # Earner FSM
│   │   │   │   ├── Genesis.java              # Bootstrap FSM
│   │   │   │   ├── Reconfigure.java          # View rotation FSM
│   │   │   │   ├── Producer.java             # Block production
│   │   │   │   ├── Committee.java            # View membership
│   │   │   │   ├── Session.java              # Client API
│   │   │   │   └── StateHolder.java          # State snapshots
│   │   │   ├── ConfigurationProfile.java     # Environment profiles
│   │   │   ├── ProfileValidator.java         # BFT validation
│   │   │   └── Parameters.java               # Configuration builder
│   │   └── proto/                             # Protobuf definitions
│   └── test/
│       ├── java/                              # Unit tests
│       └── resources/                         # Test configs
└── docs/                                      # Documentation
```

### FSM Lifecycle

**Tron FSM framework** usage pattern:

```java
// Define states (enum)
public enum Mercantile implements State {
    INITIAL,
    RECOVERING,
    OPERATIONAL,
    RECONFIGURING,
    CHECKPOINTING,
    FAILED
}

// Define transitions (enum)
public enum Transitions implements Transition<Mercantile> {
    RECOVER {
        @Override
        public Mercantile execute(Combine fsm) {
            fsm.startRecovery();
            return Mercantile.RECOVERING;
        }
    },
    OPERATIONAL {
        @Override
        public Mercantile execute(Combine fsm) {
            fsm.becomeOperational();
            return Mercantile.OPERATIONAL;
        }
    },
    RECONFIGURE {
        @Override
        public Mercantile execute(Combine fsm) {
            fsm.beginViewChange();
            return Mercantile.RECONFIGURING;
        }
    }
    // ... more transitions
}

// Create FSM
Fsm<Combine, Mercantile> fsm = Fsm.create(
    new Combine(),                  // FSM context
    Mercantile.INITIAL,             // Initial state
    Transitions.class,              // Transition class
    executor                        // Single-threaded executor
);

// Execute transition (thread-safe)
fsm.transition(Transitions.RECOVER);
```

**Invariants**:
- Transitions execute sequentially on FSM executor (no concurrency)
- State changes are atomic (all-or-nothing)
- Transitions are idempotent (safe to retry)

### Block Production Pipeline

**End-to-end flow**:

```java
// 1. Client submits transaction
Session session = choam.session();
CompletableFuture<Receipt> future = session.submit(tx);

// 2. Producer batches transactions
Producer.propose() {
    var batch = pendingTransactions.poll(batchSize, batchDelay);
    ethereal.propose(batch);  // → Ethereal consensus
}

// 3. Ethereal consensus reaches decision
Ethereal.onConsensus(PreBlock preBlock) {
    producer.onConsensus(preBlock);  // Callback to Producer
}

// 4. Producer assembles CHOAM block
Producer.onConsensus(PreBlock pb) {
    var block = new Block(pb.transactions(), currentHeight, previousHash);
    var sig = viewKey.sign(block.hash());
    gossip.publish(block, sig);  // → Bounded Epidemic Gossip
}

// 5. Gossip delivers block to all committee members
Gossip.onDeliver(Block block) {
    combine.deliverBlock(block);  // → Combine FSM
}

// 6. Combine validates and applies block
Combine.deliverBlock(Block block) {
    if (validateBlock(block)) {
        stateHolder.apply(block);
        session.notify(receipts);  // → Future completes
    }
}
```

**Thread handoffs**:
- Client thread → Session (async, returns Future)
- FSM thread → Producer → Ethereal thread
- Ethereal thread → Producer callback → FSM thread (enqueue)
- Gossip thread → Combine FSM (enqueue)

### State Snapshot Architecture

**Immutable snapshot pattern** for TOCTOU prevention:

```java
public class StateHolder<S> {
    private S currentState;           // Mutable, updated on block application
    private S snapshotState;          // Immutable, used for validation
    private long snapshotHeight;      // Block height of snapshot

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // Validation: lock-free read of snapshot
    public boolean validate(Transaction tx) {
        var snapshot = snapshotState;  // Volatile read (no lock)
        return validator.validate(tx, snapshot);
    }

    // Application: serialized write to current state
    public void apply(Block block) {
        lock.writeLock().lock();
        try {
            for (var tx : block.transactions()) {
                applyTransaction(tx, currentState);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Checkpoint: atomic snapshot creation
    public void checkpoint(long height) {
        lock.writeLock().lock();
        try {
            snapshotState = deepCopy(currentState);
            snapshotHeight = height;
        } finally {
            lock.writeLock().unlock();
        }
    }
}
```

**Proof of TOCTOU safety**:
```
Validation at time t1:
  validate(tx, snapshot_h)  // Reads snapshot (immutable)

Application at time t2 > t1:
  apply(block, current_h+1)  // Writes current state (locked)

No race:
  snapshot_h is immutable (created at checkpoint)
  current_h+1 is isolated by write lock
  Validators never see partial updates
```

---

## Development Workflow

### Git Workflow

**Branch strategy**:
- `main`: Stable releases only
- `develop`: Integration branch for features
- `feature/*`: Feature branches
- `bugfix/*`: Bug fixes
- `hotfix/*`: Production hotfixes

**Creating a feature branch**:

```bash
# Start from develop
git checkout develop
git pull origin develop

# Create feature branch
git checkout -b feature/view-rotation-optimization

# Make changes, commit
git add .
git commit -m "Optimize view rotation key collection"

# Push and create PR
git push origin feature/view-rotation-optimization
# Create PR: feature/view-rotation-optimization → develop
```

**Commit message format**:

```
Add view rotation optimization for key collection

- Parallelize key proposal collection across 2f+1 members
- Reduce view change latency from 30s to 15s (p95)
- Add unit tests for parallel key collection

References: CHOAM-123 (View Rotation Performance)
```

**Rules**:
- **NEVER** include AI attribution (policy violation)
- Include bead reference if applicable
- Focus on *what* changed, not *how* it was written
- Keep title < 72 characters
- Separate paragraphs with blank lines

### Code Review Process

**Before creating PR**:
1. Run all tests: `./mvnw clean install`
2. Run static analysis: `./mvnw spotbugs:check`
3. Format code: `./mvnw formatter:format`
4. Update documentation if needed

**PR checklist**:
- [ ] All tests pass (including large tests if touching consensus)
- [ ] Code coverage ≥ 80% for new code
- [ ] Documentation updated (if public API changes)
- [ ] CHANGELOG.md updated (if user-facing change)
- [ ] No new compiler warnings
- [ ] Performance impact assessed (if touching hot path)

**Review criteria**:
- Correctness: Does it work as intended?
- Safety: No race conditions, TOCTOU vulnerabilities?
- Performance: Hot path optimized, no N² algorithms?
- Testing: Edge cases covered, deterministic tests?
- Style: Follows code conventions?

---

## Adding Features

### Example: Adding Byzantine Detection Metric

**Requirement**: Track signature verification failures for Byzantine detection

**1. Define metric in `ByzantineDetector.java`**:

```java
public class ByzantineDetector {
    private final Meter signatureFailures;  // Dropwizard metric

    public ByzantineDetector(MetricRegistry metrics) {
        this.signatureFailures = metrics.meter("choam.byzantine.signature_failures");
    }

    public boolean detectSignatureAnomaly(Block block, Member member) {
        var sig = block.signature(member);
        if (!member.verify(sig, block.hash())) {
            signatureFailures.mark();  // Increment metric
            recordIncident(member, AnomalyType.SIGNATURE);
            return true;
        }
        return false;
    }
}
```

**2. Add unit test in `ByzantineDetectorTest.java`**:

```java
@Test
public void testSignatureAnomalyMetric() {
    // Arrange
    var metrics = new MetricRegistry();
    var detector = new ByzantineDetector(metrics);
    var invalidBlock = createBlockWithInvalidSignature();

    // Act
    detector.detectSignatureAnomaly(invalidBlock, member);

    // Assert
    var failures = metrics.meter("choam.byzantine.signature_failures");
    assertThat(failures.getCount()).isEqualTo(1);
}
```

**3. Add integration test in `ByzantineDetectionIntegrationTest.java`**:

```java
@Test
public void testByzantineMemberExcluded() {
    // Arrange: 4-node cluster with node 3 Byzantine
    var cluster = TestCluster.create(4);
    cluster.setByzantine(3);  // Node 3 sends invalid signatures

    // Act: Submit transaction
    var receipt = cluster.submit(transaction);

    // Assert: Transaction committed despite Byzantine member
    assertThat(receipt.status()).isEqualTo(Status.COMMITTED);

    // Assert: Byzantine member detected
    var incidents = cluster.node(1).getByzantineIncidents();
    assertThat(incidents).hasSize(1);
    assertThat(incidents.get(0).member()).isEqualTo(cluster.node(3).id());

    // Assert: Metric incremented
    var failures = cluster.node(1).getMetrics().meter("choam.byzantine.signature_failures");
    assertThat(failures.getCount()).isGreaterThan(0);
}
```

**4. Document metric in `OPERATOR_GUIDE.md`**:

```markdown
### Byzantine Detection

choam_byzantine_signature_failures 0

Total signature verification failures. Non-zero indicates Byzantine behavior or key corruption.
```

**5. Add to CHANGELOG.md**:

```markdown
## [Unreleased]
### Added
- Byzantine detection metric: `choam_byzantine_signature_failures`
```

### Example: Adding Custom State Machine

**Requirement**: Implement counter state machine (increment/decrement operations)

**1. Define state machine in `CounterStateMachine.java`**:

```java
public class CounterStateMachine implements StateMachine {
    // State: simple counter
    private static class CounterState {
        private long value = 0;

        public void increment(long delta) {
            value += delta;
        }

        public void decrement(long delta) {
            value -= delta;
        }

        public long getValue() {
            return value;
        }

        public CounterState copy() {
            var copy = new CounterState();
            copy.value = this.value;
            return copy;
        }
    }

    // Transaction types
    public enum Operation {
        INCREMENT, DECREMENT
    }

    private final StateHolder<CounterState> stateHolder;

    public CounterStateMachine() {
        this.stateHolder = new StateHolder<>(new CounterState());
    }

    @Override
    public void apply(Block block) {
        stateHolder.apply(state -> {
            for (var tx : block.transactions()) {
                var op = deserializeOperation(tx);
                switch (op.type) {
                    case INCREMENT -> state.increment(op.delta);
                    case DECREMENT -> state.decrement(op.delta);
                }
            }
        });
    }

    @Override
    public boolean validate(Transaction tx) {
        var snapshot = stateHolder.snapshot();
        var op = deserializeOperation(tx);
        // Validation: prevent negative values
        if (op.type == Operation.DECREMENT &&
            snapshot.getValue() - op.delta < 0) {
            return false;
        }
        return true;
    }

    @Override
    public void checkpoint(long height) {
        stateHolder.checkpoint(height);
    }

    public long getValue() {
        return stateHolder.snapshot().getValue();
    }
}
```

**2. Add test in `CounterStateMachineTest.java`**:

```java
@Test
public void testIncrementDecrement() {
    var sm = new CounterStateMachine();

    // Increment by 10
    var tx1 = createTransaction(Operation.INCREMENT, 10);
    sm.apply(createBlock(tx1));
    assertThat(sm.getValue()).isEqualTo(10);

    // Decrement by 5
    var tx2 = createTransaction(Operation.DECREMENT, 5);
    sm.apply(createBlock(tx2));
    assertThat(sm.getValue()).isEqualTo(5);
}

@Test
public void testValidationPreventsNegative() {
    var sm = new CounterStateMachine();

    // Decrement by 10 (invalid, counter at 0)
    var tx = createTransaction(Operation.DECREMENT, 10);
    assertThat(sm.validate(tx)).isFalse();
}
```

**3. Wire into CHOAM in `CHOAM.java`**:

```java
Parameters params = Parameters.Builder.from(ConfigurationProfile.PRODUCTION)
    .setStateMachine(new CounterStateMachine())
    .build();

CHOAM choam = new CHOAM(params);
choam.start();
```

---

## Testing

### Testing Philosophy

**Test pyramid**:
```
        ┌─────────────────┐
        │  System Tests   │  ← Byzantine, multi-node clusters
        │    (slow)        │
        ├─────────────────┤
        │ Integration Tests│  ← Multi-component, real I/O
        │   (moderate)     │
        ├─────────────────┤
        │   Unit Tests     │  ← Isolated components, mocked deps
        │    (fast)        │
        └─────────────────┘

Unit:Integration:System ≈ 70:20:10 (test count ratio)
```

**Test categories**:
- **Unit**: Isolated component tests (fast, deterministic)
- **Integration**: Multi-component tests (moderate speed, real I/O)
- **Cluster**: Multi-node consensus tests (slow, requires network)
- **Byzantine**: Fault injection tests (slowest, 3f+1 nodes)
- **Performance**: Throughput and latency benchmarks

### Writing Deterministic Tests

**Pattern**: Use seeded randomness and frozen clocks

```java
@Test
public void testViewRotation() {
    // Use SeededSecureRandom for deterministic random selection
    var random = new SeededSecureRandom("view-rotation-test");

    // Use Clock.fixed() for deterministic time-based logic
    var clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC"));

    // Create committee with deterministic parameters
    var committee = Committee.create(
        members,
        viewId,
        random,
        clock
    );

    // Test view rotation
    committee.rotateView(newViewId);

    // Assertions are deterministic (same result every run)
    assertThat(committee.epoch()).isEqualTo(1);
}
```

**Benefits**:
- Reproducible failures (same seed → same execution)
- Debuggable (can step through exact sequence)
- CI-friendly (no flaky tests from randomness)

### Testing Byzantine Scenarios

**GorgoneionBftTestHelpers**: Fault injection framework

```java
@Test
public void testByzantineSignatureForge() {
    // Arrange: 4-node cluster (f=1 tolerance)
    var cluster = TestCluster.create(4);

    // Inject Byzantine behavior: node 3 forges signatures
    GorgoneionBftTestHelpers.injectSignatureForgery(cluster.node(3));

    // Act: Submit transaction
    var receipt = cluster.submit(transaction);

    // Assert: Transaction committed despite Byzantine member
    assertThat(receipt.status()).isEqualTo(Status.COMMITTED);

    // Assert: Byzantine member detected and excluded
    var incidents = cluster.node(1).getByzantineIncidents();
    assertThat(incidents).hasSize(1);
    assertThat(incidents.get(0).type()).isEqualTo(AnomalyType.SIGNATURE_FORGERY);

    // Assert: Next view excludes Byzantine member
    cluster.waitForViewChange();
    assertThat(cluster.currentView().members()).doesNotContain(cluster.node(3).id());
}
```

**Fault injection types**:
- **Signature forgery**: Invalid BLS signatures
- **Equivocation**: Send different blocks to different members
- **Timing anomaly**: Slow responses, stalling
- **Rate anomaly**: Message spam
- **State corruption**: Invalid state transitions

### Test Fixtures and Utilities

**TestCHOAM**: Builder for test clusters

```java
// Create 7-node cluster with f=2 tolerance
var cluster = TestCHOAM.builder()
    .setCommitteeSize(7)
    .setTolerance(2)
    .setProfile(ConfigurationProfile.TEST)
    .setByzantineDetection(true)
    .build();

// Start cluster
cluster.start();

// Submit test transaction
var receipt = cluster.submit(createTransaction("test"));

// Wait for consensus
cluster.awaitConsensus(Duration.ofSeconds(5));

// Cleanup
cluster.stop();
```

**Assertions** (AssertJ fluent API):

```java
// Assertions on blocks
assertThat(block.height()).isEqualTo(42);
assertThat(block.transactions()).hasSize(100);
assertThat(block.signatures()).hasSize(5);  // 2f+1 for f=2

// Assertions on committee
assertThat(committee.majority()).isEqualTo(5);  // 2*2+1
assertThat(committee.toleranceLevel()).isEqualTo(2);  // (7-1)/3

// Assertions on metrics
assertThat(metrics.meter("choam.transaction_rate").getOneMinuteRate())
    .isGreaterThan(10_000);  // > 10K tx/sec
```

---

## Debugging

### Debugging FSM Transitions

**Enable FSM trace logging**:

```xml
<!-- logback-test.xml -->
<configuration>
  <logger name="com.hellblazer.delos.tron" level="DEBUG"/>
  <logger name="com.hellblazer.delos.choam.support.Combine" level="TRACE"/>
</configuration>
```

**Trace output**:

```
DEBUG [Tron-FSM] Transition: OPERATIONAL → RECONFIGURING (trigger: VIEW_CHANGE)
TRACE [Combine] Entering reconfiguration (current view: abcd1234, next view: efgh5678)
TRACE [Combine] Collected 3/5 view keys
TRACE [Combine] Collected 5/5 view keys (quorum reached)
DEBUG [Tron-FSM] Transition: RECONFIGURING → OPERATIONAL (trigger: KEYS_COLLECTED)
```

**Debugging stuck FSM**:

```bash
# Attach remote debugger
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005 \
  -jar choam.jar

# In IDE, set breakpoint in Combine.java:
// Line: fsm.transition(Transitions.RECONFIGURE)

# Inspect FSM state
fsm.currentState()  // OPERATIONAL
fsm.pendingTransitions()  // []

# Step through transition
Transitions.RECONFIGURE.execute(combine)
```

### Debugging Consensus Stalls

**Symptoms**:
- No new blocks produced
- Logs show `Producer stalled, awaiting consensus`

**Diagnosis**:

```bash
# 1. Check Ethereal consensus status
curl http://localhost:8080/api/ethereal/status
# Expected: {"status": "running", "units": 42}
# Actual: {"status": "stalled", "units": 42}

# 2. Check committee size
curl http://localhost:8080/api/committee
# Expected: {"size": 7, "online": 7, "quorum": 5}
# Actual: {"size": 7, "online": 4, "quorum": 5}  ← Below quorum!

# 3. Check for Byzantine members
curl http://localhost:8080/api/byzantine/incidents
# Response: [{"member": "node3", "type": "EQUIVOCATION", ...}]
```

**Resolution**:
- **Below quorum**: Add nodes or reduce `f`
- **Byzantine detected**: Rotate view to exclude member
- **Network partition**: Check connectivity between nodes

### Debugging Memory Leaks

**Capture heap dump**:

```bash
# Find CHOAM process
jps | grep CHOAM
# Output: 12345 CHOAM.jar

# Capture heap dump
jmap -dump:live,format=b,file=heap.bin 12345

# Analyze with jhat
jhat heap.bin
# Navigate to http://localhost:7000
```

**Common leak sources**:
- **Block cache**: Unbounded cache (check `max_pending_blocks`)
- **Checkpoint cache**: Too many checkpoints (check `max_cached_checkpoints`)
- **Gossip buffers**: Slow consumers (check `gossip.buffer_size`)

**Preventive measures**:
```yaml
memory:
  max_cached_checkpoints: 10       # Limit checkpoint count
  checkpoint_compression: true     # Compress checkpoints
producer:
  max_pending: 5000                 # Limit pending block queue
```

### Debugging Byzantine Detection

**Enable Byzantine detection logging**:

```xml
<logger name="com.hellblazer.delos.choam.ByzantineDetector" level="DEBUG"/>
```

**Trace output**:

```
DEBUG [Byzantine] Signature verification failed: member=node3, block=12345
DEBUG [Byzantine] Anomaly score: member=node3, score=0.85 (threshold: 0.75)
DEBUG [Byzantine] Byzantine incident recorded: member=node3, type=SIGNATURE_ANOMALY
INFO  [Byzantine] Initiating view rotation to exclude node3
```

**Metrics to check**:

```bash
curl http://localhost:9090/metrics | grep byzantine
# choam_byzantine_signature_failures 5
# choam_byzantine_timing_anomalies 0
# choam_byzantine_rate_anomalies 0
# choam_byzantine_equivocations 1
# choam_byzantine_incidents_total 6
```

---

## Performance Optimization

### Profiling

**YourKit profiler**:

```bash
# Start with profiler agent
java -agentpath:/opt/yourkit/bin/linux-x86-64/libyjpagent.so \
  -jar choam.jar

# Connect YourKit UI to localhost:10001
# Profile CPU and memory allocation
```

**JFR (Java Flight Recorder)**:

```bash
# Start with JFR
java -XX:StartFlightRecording=filename=choam.jfr,duration=60s \
  -jar choam.jar

# Analyze with JMC
jmc choam.jfr
```

**Async-profiler** (low overhead):

```bash
# Profile CPU for 60 seconds
./profiler.sh -d 60 -f flamegraph.html <pid>

# Profile allocations
./profiler.sh -d 60 -e alloc -f alloc.html <pid>
```

### Hot Path Optimization

**Identified hot paths** (from profiling):

1. **Block validation** (30% CPU):
   - Signature verification (BLS)
   - Merkle tree hashing
   - State transition validation

2. **Gossip dissemination** (20% CPU):
   - Protobuf serialization
   - Network I/O
   - Buffer management

3. **State application** (15% CPU):
   - Lock acquisition
   - State mutation
   - Checkpoint creation

**Optimization strategies**:

**1. Signature aggregation (Crown)**:

```java
// Before: Verify 5 signatures individually (5× BLS verify)
for (var sig : block.signatures()) {
    member.verify(sig, block.hash());  // ~2ms each
}
// Total: 10ms

// After: Aggregate and verify once (1× BLS verify)
var crown = Crown.aggregate(block.signatures());
crown.verify(block.hash());  // ~2ms
// Total: 2ms (5× faster)
```

**2. Protobuf optimization**:

```java
// Before: Parse protobuf on every access
public String getData() {
    return proto.getData();  // Parses protobuf each call
}

// After: Cache parsed data
private volatile String cachedData;
public String getData() {
    if (cachedData == null) {
        cachedData = proto.getData();
    }
    return cachedData;
}
```

**3. Lock-free reads**:

```java
// Before: Read lock for validation
public boolean validate(Transaction tx) {
    lock.readLock().lock();
    try {
        return validator.validate(tx, currentState);
    } finally {
        lock.readLock().unlock();
    }
}

// After: Lock-free snapshot read
public boolean validate(Transaction tx) {
    var snapshot = snapshotState;  // Volatile read, no lock
    return validator.validate(tx, snapshot);
}
```

### Benchmarking

**JMH benchmarks**:

```java
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
public class BlockValidationBenchmark {

    private Block block;
    private Committee committee;

    @Setup
    public void setup() {
        block = createTestBlock();
        committee = createTestCommittee();
    }

    @Benchmark
    public boolean validateSignaturesIndividually() {
        for (var sig : block.signatures()) {
            if (!committee.verify(sig, block.hash())) {
                return false;
            }
        }
        return true;
    }

    @Benchmark
    public boolean validateSignaturesAggregated() {
        var crown = Crown.aggregate(block.signatures());
        return crown.verify(block.hash());
    }
}
```

**Run benchmarks**:

```bash
./mvnw jmh:run BlockValidationBenchmark

# Results:
# Benchmark                                        Mode  Cnt   Score   Error  Units
# validateSignaturesIndividually                  thrpt   25   2000.0 ±  50.0  ops/s
# validateSignaturesAggregated                    thrpt   25  10000.0 ± 200.0  ops/s
# → 5× throughput improvement with aggregation
```

---

## Code Conventions

### Java Style

**Formatting**:
- Indentation: 4 spaces (no tabs)
- Line length: 120 characters
- Braces: K&R style (`{` on same line)

**Automated formatting**:

```bash
# Format all code
./mvnw formatter:format

# Check formatting
./mvnw formatter:validate
```

**Naming conventions**:
- Classes: `PascalCase`
- Methods: `camelCase`
- Constants: `UPPER_SNAKE_CASE`
- Packages: `lowercase`

### Documentation

**Javadoc requirements**:
- All public classes and methods MUST have Javadoc
- Package-private classes SHOULD have Javadoc
- Private methods MAY have Javadoc (if complex)

**Javadoc style**:

```java
/**
 * Validates a block against Byzantine fault tolerance constraints.
 * <p>
 * Validation includes:
 * - Signature verification (2f+1 valid signatures required)
 * - Height validation (monotonic increase)
 * - Hash chain validation (previous block hash)
 * - State transition validation (application-specific)
 * <p>
 * This method is thread-safe and uses immutable snapshots for validation.
 *
 * @param block the block to validate
 * @param committee the current committee (provides signature verification)
 * @return true if block is valid, false otherwise
 * @throws NullPointerException if block or committee is null
 */
public boolean validateBlock(Block block, Committee committee) {
    // Implementation
}
```

### Error Handling

**Prefer unchecked exceptions for programming errors**:

```java
// Good: Illegal argument (programming error)
if (clusterSize < 3) {
    throw new IllegalArgumentException("Cluster size must be ≥ 3");
}

// Good: Illegal state (invariant violation)
if (currentState != Mercantile.OPERATIONAL) {
    throw new IllegalStateException("Cannot submit transaction in state: " + currentState);
}
```

**Use checked exceptions for recoverable errors**:

```java
// Good: I/O error (recoverable)
public void loadCheckpoint(Path path) throws IOException {
    Files.readAllBytes(path);
}
```

**Never catch and ignore exceptions**:

```java
// Bad
try {
    process();
} catch (Exception e) {
    // Ignored
}

// Good
try {
    process();
} catch (Exception e) {
    log.error("Processing failed", e);
    throw new ProcessingException("Failed to process", e);
}
```

### Logging

**SLF4J logging levels**:
- **TRACE**: Very detailed (e.g., every state transition)
- **DEBUG**: Detailed (e.g., view changes, block production)
- **INFO**: Informational (e.g., startup, shutdown)
- **WARN**: Warning (e.g., retry after transient failure)
- **ERROR**: Error (e.g., Byzantine detected, consensus stalled)

**Logging guidelines**:

```java
// Good: Parameterized logging (avoids string concatenation)
log.debug("View rotation: current={}, next={}", currentView, nextView);

// Bad: String concatenation
log.debug("View rotation: current=" + currentView + ", next=" + nextView);

// Good: Guard expensive operations
if (log.isDebugEnabled()) {
    log.debug("Block details: {}", block.toDetailedString());  // Expensive
}

// Good: Log exceptions with context
log.error("Block validation failed: height={}, hash={}", block.height(), block.hash(), exception);
```

---

## Build and CI

### Maven Build Lifecycle

**Phases**:
```
clean → validate → compile → test → package → verify → install → deploy
```

**Common goals**:

```bash
# Clean build
./mvnw clean install

# Skip tests (for quick iteration)
./mvnw install -DskipTests

# Run spotbugs static analysis
./mvnw spotbugs:check

# Generate site documentation
./mvnw site

# Deploy to Maven repository (requires credentials)
./mvnw deploy
```

### Continuous Integration

**GitHub Actions workflow** (`.github/workflows/ci.yml`):

```yaml
name: CI

on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main, develop ]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2

      - name: Set up JDK 24
        uses: actions/setup-java@v2
        with:
          java-version: '24'

      - name: Build with Maven
        run: ./mvnw clean install

      - name: Run large tests
        run: ./mvnw clean install -Dlarge_tests=true

      - name: SpotBugs analysis
        run: ./mvnw spotbugs:check

      - name: Upload test reports
        uses: actions/upload-artifact@v2
        with:
          name: test-reports
          path: target/surefire-reports/
```

### Release Process

**Version bump**:

```bash
# Update version in pom.xml
mvn versions:set -DnewVersion=0.0.7

# Commit version change
git add pom.xml
git commit -m "Bump version to 0.0.7"

# Tag release
git tag -a v0.0.7 -m "Release 0.0.7"
git push origin v0.0.7
```

**Changelog update** (`CHANGELOG.md`):

```markdown
## [0.0.7] - 2026-02-08

### Added
- Byzantine detection metric: `choam_byzantine_signature_failures`
- View rotation optimization (15s p95 latency, down from 30s)

### Fixed
- Memory leak in checkpoint cache (max 10 checkpoints enforced)
- Race condition in gossip buffer management

### Changed
- Default profile changed from TEST to PRODUCTION (breaking change)
```

---

**Last Updated**: 2026-02-08
**CHOAM Version**: 0.0.6-SNAPSHOT

For architecture details, see [ARCHITECTURE.md](ARCHITECTURE.md).
For operator guide, see [OPERATOR_GUIDE.md](OPERATOR_GUIDE.md).
For terminology, see [GLOSSARY.md](GLOSSARY.md).
