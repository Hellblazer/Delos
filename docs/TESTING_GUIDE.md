# Delos Testing Guide

**Status**: Production Reference | Last Updated: 2026-01-27 | Version: 2.0

Complete guide for testing in the Delos distributed systems framework, covering unit tests, integration tests, Byzantine fault tolerance tests, deterministic testing patterns, and best practices.

## Table of Contents

1. [Quick Reference](#quick-reference)
2. [Test Infrastructure](#test-infrastructure)
3. [Test Categories](#test-categories)
4. [Deterministic Testing Patterns](#deterministic-testing-patterns)
5. [Byzantine FT Testing](#byzantine-ft-testing)
6. [Resource Management](#resource-management)
7. [Concrete Examples](#concrete-examples)
8. [Enforcement Checklist](#enforcement-checklist)
9. [Common Pitfalls & Solutions](#common-pitfalls--solutions)
10. [Performance Baselines](#performance-baselines)

---

## Quick Reference

### Running Tests

```bash
# Run all tests (standard mode)
./mvnw test

# Run module tests
./mvnw test -pl <module>

# Run single test class
./mvnw test -pl <module> -Dtest=ClassName

# Run single test method
./mvnw test -pl <module> -Dtest=ClassName#methodName

# Run tests matching pattern
./mvnw test -Dtest="*Integration*"

# Run with full suite (resource-intensive, 8+ GB RAM required)
./mvnw clean install -Dlarge_tests=true

# Run specific test with debugging
./mvnw test -pl <module> -Dtest=ClassName#method \
  -DargLine="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005"

# Increase heap for memory-intensive tests
./mvnw test -DargLine="-Xmx12G -Xms6G"

# Rerun flaky tests automatically
./mvnw test -Dsurefire.rerunFailingTestsCount=2
```

### Test Mode Selection

| Mode | Command | Duration | Target | Best For |
|------|---------|----------|--------|----------|
| **Fast (Default)** | `./mvnw test` | ~10-15 min | Development | Local development, quick feedback |
| **Thorough (Large)** | `./mvnw test -Dlarge_tests=true` | ~45-60 min | CI/pre-release | Comprehensive validation, pre-merge |
| **Single Module** | `./mvnw test -pl <module>` | Variable | Specific area | Testing changes in one module |

### Test Results Interpretation

```
[INFO] Tests run: 673, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- **Failures**: Test assertions failed (logic error)
- **Errors**: Unexpected exceptions (code error)
- **Skipped**: Test not run (usually due to @Disabled or missing dependency)

If failures occur:
1. Check logs: `<module>/target/surefire-reports/`
2. Port conflicts: `lsof -i :9090` (tests use dynamic ports)
3. Retry: May be timing-sensitive distributed system tests
4. Check README or TESTING_GUIDE for known issues

---

## Test Infrastructure

### Core Components

#### 1. Test Helpers & Utilities

**TestContext** - Base context for cluster tests:
- Manages member creation and lifecycle
- Provides ring-based communication setup
- Handles cleanup and resource management

**GorgoneionCluster** - Bootstrap and enrollment testing:
- Simulates identity registration workflow
- Tests attestation and credential flow
- Manages test certificate authorities

**GorgoneionBftTestHelpers** - Byzantine fault injection:
- Injects node failures at specific points
- Simulates Byzantine behavior (equivocation, forged signatures)
- Tracks detection and response

#### 2. Test Fixtures & Data

**SeededSecureRandom** - Deterministic randomness:
```java
var random = new SeededSecureRandom("test-seed-12345");
// Same seed produces identical sequence every run
var value1 = random.nextInt();
var value2 = random.nextInt();
// Reproducible across test runs
```

**Clock.fixed()** - Time control:
```java
var clock = Clock.fixed(Instant.parse("2026-01-27T10:00:00Z"), ZoneId.of("UTC"));
// Frozen time for testing scheduled operations
```

**TestTimeout** - Consistent timeout management:
```java
static final long ACTIVATION_WAIT = largeTests ? 30_000 : 15_000;  // ms
static final long TRANSACTION_COUNTDOWN = largeTests ? 150_000 : 45_000;  // ms
```

#### 3. Test Configuration

**`-Dlarge_tests` flag**:
- `false` (default): Fast tests for local development
- `true`: Thorough tests for CI and pre-release
- Controls: Cluster size, epoch count, transaction volume, timeouts

**Module-Specific Settings**:
- `choam`: Epoch parameters, transaction volume, checkpoint delta
- `fireflies`: Gossip duration, bootstrap size, view formation timing
- `witness-service`: Byzantine member count, detection thresholds

### Test Execution Framework

**JUnit 5** with:
- **AssertJ**: Fluent assertions (`.isEqualTo()`, `.contains()`)
- **Mockito**: Mocking and verification
- **Parameterized Tests**: `@ParameterizedTest` for multiple scenarios

**Coverage Tracking**:
- Target: 85% per module
- Use `jacoco:report` for coverage analysis
- Exclusions: Generated code, boilerplate

---

## Test Categories

### 1. Unit Tests

**Purpose**: Test isolated components in isolation (no cluster, no consensus)

**Location**: `*Test.java` in `src/test/java`

**Characteristics**:
- Single class under test
- Mocked dependencies
- No network/consensus operations
- Duration: < 1 second per test

**Example**:
```java
@Test
void shouldValidateBLSSignature() {
    // Arrange
    var keyPair = BlsKeyPair.generate(randomProvider);
    var message = "test message".getBytes();

    // Act
    var signature = keyPair.getPublicKey().sign(message);

    // Assert
    assertThat(signature.verify(keyPair.getPublicKey(), message))
        .isTrue();
}
```

### 2. Integration Tests

**Purpose**: Test components working together (single node, no Byzantine)

**Location**: `*IntegrationTest.java` in `src/test/java`

**Characteristics**:
- Multiple components
- Real I/O (database, gRPC)
- No network (mocked or localhost)
- No Byzantine members
- Duration: 1-10 seconds per test

**Example**: CHOAM consensus on single committee, SQL-State checkpoint creation

### 3. Cluster Tests

**Purpose**: Test distributed system behavior (multi-node cluster, normal conditions)

**Location**: `*ClusterTest.java` in `src/test/java`

**Characteristics**:
- Multiple independent processes (simulated or real)
- Gossip/consensus protocols
- Network communication (localhost)
- All nodes honest
- Duration: 10-60 seconds per test

**Example**:
```java
@Test
void shouldReplicateStateConsistently() {
    var committee = createCommittee(3);  // 3 honest nodes

    var result1 = committee.get(0).execute(transaction);
    var result2 = committee.get(1).execute(transaction);
    var result3 = committee.get(2).execute(transaction);

    // All nodes produce identical state
    assertThat(result1.checkpoint()).isEqualTo(result2.checkpoint());
    assertThat(result2.checkpoint()).isEqualTo(result3.checkpoint());
}
```

### 4. Byzantine Tests

**Purpose**: Test Byzantine fault tolerance (multi-node with failures)

**Location**: `*ByzantineTest.java` in `src/test/java`

**Characteristics**:
- 3f+1 nodes (f dishonest, 2f+1 honest)
- Injected Byzantine failures
- Detection and response validation
- Consensus correctness under attacks
- Duration: 20-120 seconds per test

**Example**: See [Byzantine FT Testing](#byzantine-ft-testing) section

### 5. Stress Tests

**Purpose**: Test system under load (many transactions, high concurrency)

**Location**: `*StressTest.java` in `src/test/java`

**Characteristics**:
- High transaction volume (1000+)
- High concurrency (100+ threads)
- Long duration (minutes)
- Resource monitoring
- Only run with `-Dlarge_tests=true`

**Example**: CHOAMConcurrencyTest with 5 transactioneers, 15 transactions each

### 6. Performance Tests

**Purpose**: Measure performance and validate SLAs

**Location**: `*BenchmarkTest.java` or `*PerformanceTest.java`

**Characteristics**:
- Throughput measurement (ops/sec)
- Latency percentiles (p50, p95, p99)
- Baseline comparison
- Resource profiling
- Duration: 30-300 seconds

**Example**:
```java
@Test
void shouldAchieveThroughputTarget() {
    var timer = Timer.start();

    for (int i = 0; i < 10000; i++) {
        submitTransaction(...);
    }

    var duration = timer.elapsed();
    var throughput = 10000 / duration.getSeconds();

    // Assert: >= 1000 tx/sec
    assertThat(throughput).isGreaterThanOrEqualTo(1000);
}
```

---

## Deterministic Testing Patterns

### 1. Seeded Randomness

**Problem**: Tests using `java.util.Random` or `SecureRandom` without seeds are non-deterministic.

**Solution**: Use `SeededSecureRandom` or seed `Random`:

```java
// GOOD - Deterministic
private static final String TEST_SEED = "delos-test-42";
var random = new SeededSecureRandom(TEST_SEED);

// OR
var random = new Random(42L);  // Seed for determinism
var value = random.nextInt();  // Same value every run

// BAD - Non-deterministic
var random = new Random();  // No seed
var value = random.nextInt();  // Different every run
```

**Why it matters**:
- Enables test reproduction when failures occur
- CI builds are reproducible
- Debugging is easier with predictable behavior

### 2. Frozen Clock

**Problem**: Tests using `System.currentTimeMillis()` depend on wall clock, making them flaky.

**Solution**: Use `Clock.fixed()` for time-dependent operations:

```java
// GOOD - Deterministic time
@Test
void shouldExpireAfterDelay() {
    var clock = Clock.fixed(Instant.parse("2026-01-27T10:00:00Z"), ZoneId.of("UTC"));
    var expirationTime = clock.instant().plus(Duration.ofMinutes(5));

    // Advance time by 6 minutes
    var futureTime = expirationTime.plus(Duration.ofMinutes(1));

    // Assertion uses fixed time, not system clock
    assertThat(futureTime.isAfter(expirationTime)).isTrue();
}

// BAD - Non-deterministic
@Test
void shouldExpireAfterDelay() {
    var expirationTime = System.currentTimeMillis() + 300_000;  // 5 minutes
    Thread.sleep(360_000);  // Hope this takes exactly 6 minutes (unreliable)

    assertThat(System.currentTimeMillis() > expirationTime).isTrue();
}
```

### 3. Controlled Consensus Parameters

**Problem**: Default consensus parameters are optimized for production, making tests slow.

**Solution**: Use `largeTests` flag to control parameters:

```java
// In test setup
static final boolean largeTests =
    Boolean.parseBoolean(System.getProperty("large_tests", "false"));

@Test
void shouldFormCluster() {
    var clusterSize = largeTests ? 100 : 10;
    var epochLength = largeTests ? 33 : 11;
    var gossipDuration = Duration.ofMillis(largeTests ? 150 : 5);

    // Test runs fast locally, thoroughly on CI
}
```

**Parameter Reduction Strategy**:

| Parameter | Large Tests | Fast Tests | Reduction | Rationale |
|-----------|-------------|------------|-----------|-----------|
| **Cluster Size** | 100 | 10 | 90% | Still validates consensus |
| **Epoch Length** | 33 | 11 | 67% | Minimum for correctness |
| **Epochs** | 3-4 | 2 | 33-50% | Covers: Genesis → View1 → View2 |
| **Gossip Duration** | 150ms | 5ms | 96% | Doesn't affect safety |
| **Batch Interval** | 50ms | 50ms | 0% | Conservative, preserved |
| **Checkpoint Delta** | 5 | 3 | 40% | Sufficient for coverage |
| **Timeout** | 30s | 15s | 50% | 2x typical completion |

### 4. Determinism Verification

**Pattern**: Verify identical operations produce identical results.

```java
@Test
void shouldBeDeterministic() {
    // Scenario 1: Execute operations with seed A
    var random1 = new SeededSecureRandom("seed-1");
    var clock1 = Clock.fixed(EPOCH, UTC);
    var state1 = executeWorkload(clock1, random1);
    var checkpoint1 = state1.hash();

    // Scenario 2: Execute identical operations with same seed
    var random2 = new SeededSecureRandom("seed-1");
    var clock2 = Clock.fixed(EPOCH, UTC);
    var state2 = executeWorkload(clock2, random2);
    var checkpoint2 = state2.hash();

    // Assertion: Identical execution produces identical state
    assertThat(checkpoint1).isEqualTo(checkpoint2);
}
```

**When to use**:
- Replicated state machines (CHOAM)
- Byzantine consensus (Ethereal)
- Deterministic SQL execution

---

## Byzantine FT Testing

### 1. Byzantine Failure Categories

| Category | Example | Detection | Response |
|----------|---------|-----------|----------|
| **Equivocation** | Sign conflicting messages | Member sent A and B | Mark member suspicious |
| **Signature Forgery** | Invalid BLS signature | Verify fails | Mark member Byzantine |
| **Timing Anomaly** | Message arrives too early | Timestamp check | Alert, monitor |
| **Fork Detection** | Conflicting checkpoints | Compare roots | Quarantine leader |
| **Threshold Bypass** | Insufficient signatures | Count < 2f+1 | Request revalidation |

### 2. Fault Injection Patterns

#### Pattern A: Equivocation Detection

```java
@Test
void shouldDetectEquivocation() {
    var committee = createCommittee(4);  // 3 honest + 1 Byzantine (f=1)
    var byzantine = committee.get(3);    // Member 3 is Byzantine

    // Member 3 sends conflicting messages
    var message1 = createMessage("consensus-unit-A");
    var message2 = createMessage("consensus-unit-B");  // Different content

    byzantine.sendTo(committee.get(0), message1);
    byzantine.sendTo(committee.get(1), message2);  // Same sender, different msg

    // Detector should flag equivocation
    var detector = committee.get(0).getByzantineDetector();
    assertThat(detector.getEquivocationDetections())
        .contains(byzantine.identifier());
}
```

#### Pattern B: Crash/Failure Injection

```java
@Test
void shouldTolerateSingleNodeCrash() {
    var committee = createCommittee(4);  // 3 honest + 1 can fail (f=1)

    // Crash member 3
    committee.get(3).shutdown();

    // System should continue with 3 honest nodes
    var transaction = createTransaction("test");
    var result = committee.get(0).execute(transaction);

    assertThat(result.isSuccess()).isTrue();  // Consensus without crashed node
}
```

#### Pattern C: Byzantine Delay Injection

```java
@Test
void shouldDetectSuspiciousDelay() {
    var committee = createCommittee(4);
    var byzantine = committee.get(3);

    // Inject delay in message delivery
    byzantine.setOutboundDelay(Duration.ofSeconds(10));

    // Submit transaction through honest members
    var transaction = createTransaction("test");
    committee.get(0).execute(transaction);

    // Byzantine member shouldn't be selected as validator/proposer
    var detector = committee.get(0).getRateAnomalyDetector();
    assertThat(detector.isUnderSuspicion(byzantine.identifier())).isTrue();
}
```

### 3. Byzantine Test Structure

```java
@DisplayName("Byzantine Fault Tolerance Tests")
class ByzantineConsensusTest {

    static final int COMMITTEE_SIZE = 4;        // 3f+1 (f=1, tolerance 1 byzantine)
    static final int BYZANTINE_COUNT = 1;
    TestContext context;

    @BeforeEach
    void setup() {
        context = new TestContext(COMMITTEE_SIZE);
        // Mark last BYZANTINE_COUNT members as Byzantine
        for (int i = COMMITTEE_SIZE - BYZANTINE_COUNT; i < COMMITTEE_SIZE; i++) {
            context.setByzantine(i);
        }
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void shouldReachConsensusWithByzantineMembers() {
        // Even with 1 Byzantine member, 3 honest nodes reach consensus
        assertThat(context.commitBlocks(100))
            .isEqualTo(100);  // All 100 blocks committed
    }

    @Test
    void shouldDetectByzantineProposal() {
        // Byzantine member proposes invalid block
        var invalidBlock = context.createInvalidBlock();
        context.getByzantineMember(0).proposeBlock(invalidBlock);

        // Honest members reject it
        assertThat(context.getHonestMembers()
            .stream()
            .allMatch(m -> m.blockIsRejected(invalidBlock)))
            .isTrue();
    }

    @Test
    void shouldMaintainSafetyDuringViewChange() {
        // Trigger view change while Byzantine member is leader
        context.triggerViewChange();

        // New honest leader elected
        var newLeader = context.getLeader();
        assertThat(context.isHonest(newLeader)).isTrue();

        // Consensus continues
        assertThat(context.commitBlocks(50)).isEqualTo(50);
    }
}
```

### 4. Byzantine Detector Framework

**Core Interface**:
```java
public interface ByzantineDetector<T> {
    DetectionResult analyze(T context);
    void recordSuspicion(Identifier member, SuspicionType type);
    Set<Identifier> getSuspiciousMembers();
    void clear();
}
```

**Detection Types**:
```java
enum SuspicionType {
    EQUIVOCATION,           // Sent conflicting messages
    SIGNATURE_FORGERY,      // Invalid cryptographic proof
    TIMING_ANOMALY,         // Suspicious timing patterns
    RATE_ANOMALY,          // Unusual failure rates
    STATE_DIVERGENCE        // Inconsistent state
}
```

**Implementation Pattern**:
```java
@Test
void shouldDetectMultipleByzantineSignals() {
    var detector = ByzantineDetectorImpl.create()
        .withEquivocationDetector(new EquivocationDetector())
        .withTimingAnomalyDetector(new TimingAnomalyDetector(500))  // 500ms threshold
        .withRateAnomalyDetector(new RateAnomalyDetector(0.1));    // 10% threshold

    var member = Identifier.random();

    // Signal 1: Equivocation detected
    detector.recordSuspicion(member, EQUIVOCATION);
    assertThat(detector.getSuspiciousMembers()).contains(member);

    // Signal 2: Timing anomaly detected
    detector.recordSuspicion(member, TIMING_ANOMALY);

    // Signal 3: Analysis determines Byzantine behavior
    var result = detector.analyze(context);
    assertThat(result.getVerdict()).isEqualTo(BYZANTINE);
}
```

---

## Resource Management

### 1. Lifecycle Management

**Pattern**: Use `AutoCloseable` and try-with-resources for proper cleanup:

```java
// GOOD - Automatic cleanup
@Test
void shouldCleanupResources() {
    try (var cluster = new TestCluster(3)) {
        cluster.executeTransaction(tx);
        assertThat(cluster.isHealthy()).isTrue();
    }
    // Cluster automatically shut down and resources released
}

// BAD - Manual cleanup (easy to forget)
@Test
void shouldCleanupResources() {
    var cluster = new TestCluster(3);
    try {
        cluster.executeTransaction(tx);
        assertThat(cluster.isHealthy()).isTrue();
    } finally {
        cluster.close();  // Must remember
    }
}
```

### 2. Port Management

**Problem**: Tests using fixed ports conflict when running in parallel or on shared systems.

**Solution**: Use dynamic port allocation (port 0):

```java
// GOOD - Dynamic ports
var server = new Server(0);  // OS assigns available port
server.start();
var actualPort = server.getPort();  // Query assigned port

// Test with queryable port
var client = new Client("localhost", actualPort);

// BAD - Fixed ports (conflicts)
var server = new Server(9090);  // May already be in use
```

### 3. Memory Management

**Pattern**: Monitor and control memory usage in tests:

```java
@Test
void shouldNotLeakMemory() {
    var initialMemory = Runtime.getRuntime().totalMemory();

    // Run test workload
    for (int i = 0; i < 10000; i++) {
        submitTransaction();
    }

    var finalMemory = Runtime.getRuntime().totalMemory();
    var memoryGrowth = finalMemory - initialMemory;

    // Memory growth should be minimal
    assertThat(memoryGrowth).isLessThan(100_000_000);  // 100 MB
}
```

### 4. Timeout Management

**Pattern**: Use configured timeouts, not arbitrary sleep:

```java
// GOOD - Configured timeouts
private static final long ACTIVATION_TIMEOUT =
    largeTests ? 30_000 : 15_000;  // ms

@Test
void shouldCompleteWithinTimeout() throws Exception {
    var result = cluster.activate();
    assertThat(result.get(ACTIVATION_TIMEOUT, TimeUnit.MILLISECONDS))
        .isNotNull();
}

// BAD - Arbitrary sleep
@Test
void shouldActivate() throws Exception {
    cluster.activate();
    Thread.sleep(5000);  // Hope it completes in 5 seconds
    assertThat(cluster.isActive()).isTrue();
}
```

---

## Concrete Examples

### Example 1: Simple BFT Cluster Test with Gorgoneion

**Scenario**: 7-node cluster (Gorgoneion - attestation service) verifies Byzantine safety.

```java
@DisplayName("Gorgoneion BFT Cluster Safety")
class GorgoneionBftClusterTest {

    private TestContext context;
    private GorgoneionCluster cluster;

    @BeforeEach
    void setup() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 1, 2, 3 });  // Deterministic seed

        // Create 7-node test context (f=2, tolerates 2 Byzantine nodes)
        context = new TestContext(7, entropy);

        // Create Gorgoneion cluster from context
        var parameters = new Parameters();  // Use default parameters
        cluster = new GorgoneionCluster(context, parameters);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (cluster != null) cluster.close();
        if (context != null) context.close();
    }

    @Test
    void shouldMaintainSafetyWithNodeFailure() throws Exception {
        // Verify initial cluster health
        assertThat(cluster.size()).isEqualTo(7);
        assertThat(cluster.majority()).isEqualTo(5);
        assertThat(cluster.faultTolerance()).isEqualTo(2);

        // Stop one node (still within Byzantine tolerance, f=2)
        cluster.stopNode(5);

        // Issue attestations from remaining nodes
        var nonce1 = cluster.getMember(0).nonce(UUID.randomUUID());
        var nonce2 = cluster.getMember(1).nonce(UUID.randomUUID());

        // Record nonces for safety verification
        cluster.recordNonce(nonce1);
        cluster.recordNonce(nonce2);

        // Verify Byzantine safety properties held
        var verification = cluster.verifySafety();
        assertThat(verification.noConflictingNonces()).isTrue();
    }
}
```

### Example 2: Node Failure and Recovery Test

**Scenario**: Stop a node, verify others continue, then restart and verify synchronization.

```java
@Test
void shouldRecoverFromNodeFailure() throws Exception {
    // All nodes active initially
    for (int i = 0; i < 7; i++) {
        assertThat(cluster.isNodeActive(i)).isTrue();
    }

    // Stop node 3 (within Byzantine tolerance f=2, we need 5/7 active)
    cluster.stopNode(3);
    assertThat(cluster.isNodeActive(3)).isFalse();

    // System continues with 6 active nodes
    // Verify remaining 6 nodes still function (> majority of 5)
    for (int i = 0; i < 7; i++) {
        if (i != 3) {
            assertThat(cluster.getMember(i)).isNotNull();
        }
    }

    // Restart the failed node
    cluster.restartNode(3);
    assertThat(cluster.isNodeActive(3)).isTrue();

    // Verify node synchronizes with cluster
    assertThat(cluster.getMember(3)).isNotNull();
}
```

### Example 3: Blacklist and Safety Test

**Scenario**: Blacklist a node, verify cluster continues safely without it.

```java
@Test
void shouldMaintainSafetyWhenBlacklistingNode() throws Exception {
    var node0 = cluster.getMember(0);
    var node0Digest = node0.getIdentifier();

    // Verify initial state
    assertThat(cluster.isBlacklisted(node0Digest)).isFalse();

    // Blacklist node 0 (simulates detection of Byzantine behavior)
    cluster.blacklist(node0Digest);
    assertThat(cluster.isBlacklisted(node0Digest)).isTrue();

    // Create nonces from other nodes
    var nonce1 = cluster.getMember(1).nonce(UUID.randomUUID());
    var nonce2 = cluster.getMember(2).nonce(UUID.randomUUID());
    var nonce3 = cluster.getMember(3).nonce(UUID.randomUUID());

    // Record nonces for verification (skip blacklisted node 0)
    cluster.recordNonce(nonce1);
    cluster.recordNonce(nonce2);
    cluster.recordNonce(nonce3);

    // System should maintain Byzantine safety despite blacklisted node
    var verification = cluster.verifySafety();
    assertThat(verification.noConflictingNonces()).isTrue();

    // Clear blacklist for next test
    cluster.clearBlacklist();
}
```

### Example 4: Signer Injection Test (Equivocation Detection)

**Scenario**: Inject custom signer to create conflicting attestations, detect equivocation.

```java
@Test
void shouldDetectEquivocation() throws Exception {
    var node5 = cluster.getMember(5);
    var clientId = UUID.randomUUID();

    // Create two conflicting nonces from node 5 (simulates equivocation)
    // This requires careful handling - in real Byzantine tests, would use
    // custom signer that produces different signatures for same input

    var nonce1 = node5.nonce(clientId);  // First attestation
    var nonce2 = node5.nonce(clientId);  // Should be identical (deterministic)

    cluster.recordNonce(nonce1);
    cluster.recordNonce(nonce2);

    // Verify no false detection of equivocation from deterministic signing
    var verification = cluster.verifySafety();
    assertThat(verification.noConflictingNonces())
        .as("Deterministic signing should not create conflicts")
        .isTrue();

    // For true Byzantine equivocation, would inject custom Signer:
    // cluster.injectSigner(5, customSignerWithEquivocation);
    // Then verification would detect conflicts
}
```

### Example 5: Multi-Cluster Setup for Cross-Cluster Testing

**Scenario**: Create multiple independent clusters for coordination testing.

```java
@Test
void shouldCoordinateAcrossClusters() throws Exception {
    var entropy1 = SecureRandom.getInstance("SHA1PRNG");
    entropy1.setSeed(new byte[] { 1, 2, 3 });

    var entropy2 = SecureRandom.getInstance("SHA1PRNG");
    entropy2.setSeed(new byte[] { 4, 5, 6 });

    // Create two independent clusters (e.g., for cross-cluster attestation)
    try (var context1 = new TestContext(4, entropy1);
         var context2 = new TestContext(4, entropy2)) {

        var params = new Parameters();
        try (var cluster1 = new GorgoneionCluster(context1, params);
             var cluster2 = new GorgoneionCluster(context2, params)) {

            // Get router prefixes for cluster-to-cluster communication
            var prefix1 = cluster1.getPrefix();
            var prefix2 = cluster2.getPrefix();

            // Cross-cluster coordination would use these prefixes
            // for routers to communicate between clusters

            assertThat(cluster1.size()).isEqualTo(4);
            assertThat(cluster2.size()).isEqualTo(4);

            // Each cluster maintains independent Byzantine safety
            var verification1 = cluster1.verifySafety();
            var verification2 = cluster2.verifySafety();

            assertThat(verification1.noConflictingNonces()).isTrue();
            assertThat(verification2.noConflictingNonces()).isTrue();
        }
    }
}
```

---

## Enforcement Checklist

### For All New Tests

- [ ] **Test name** describes what is being tested (e.g., `shouldRejectInvalidSignature`)
- [ ] **@DisplayName** added for complex tests (helps in IDE and reports)
- [ ] **Deterministic**: No `System.currentTimeMillis()`, `Random()`, or `new Date()`
- [ ] **Seeded**: Use `SeededSecureRandom` or `Clock.fixed()`
- [ ] **Timeouts**: Use constants, not arbitrary sleep times
- [ ] **Port allocation**: Use dynamic ports (port 0), not fixed
- [ ] **Resource cleanup**: Implement `AutoCloseable` or use try-with-resources
- [ ] **Error messages**: Include context in assertions

### For Integration Tests

- [ ] **Independent**: No dependencies on test execution order
- [ ] **Idempotent**: Can run multiple times with same result
- [ ] **Isolated**: No shared state between tests
- [ ] **Fast mode support**: Respects `-Dlarge_tests` flag
- [ ] **Parameter reduction**: Uses smaller cluster/epoch counts for speed
- [ ] **Coverage**: Tests both happy path and error cases

### For Byzantine/Cluster Tests

- [ ] **Quorum size correct**: Minimum 3f+1 nodes (f = Byzantine tolerance)
- [ ] **Byzantine node count**: <= f (max Byzantine fault tolerance)
- [ ] **Fault injection clear**: Explicitly marks which nodes are Byzantine
- [ ] **Detection validation**: Verifies Byzantine behavior was detected
- [ ] **Response validation**: Verifies system responded appropriately
- [ ] **Safety verification**: Verifies correct behavior despite Byzantine nodes

### For Performance Tests

- [ ] **Baseline documented**: Expected throughput/latency clearly stated
- [ ] **Measurement repeated**: Multiple runs to reduce variance
- [ ] **Percentiles tracked**: p50, p95, p99 latencies measured
- [ ] **Resource limits noted**: Heap size, thread count, etc.
- [ ] **Pass criteria explicit**: Tests fail if SLA not met

### Test Structure

```java
@DisplayName("Descriptive test category name")
class ComponentTest {

    // Configuration
    private static final boolean largeTests =
        Boolean.parseBoolean(System.getProperty("large_tests", "false"));
    private static final long TIMEOUT = largeTests ? 30_000 : 15_000;

    // Fixtures
    private TestContext context;

    @BeforeEach
    void setup() throws Exception {
        context = new TestContext(largeTests ? 100 : 10);
    }

    @AfterEach
    void cleanup() {
        context.close();
    }

    @Test
    @DisplayName("Should handle normal case")
    void shouldHandleNormalCase() {
        // Arrange
        var input = prepareTestData();

        // Act
        var result = executeUnderTest(input);

        // Assert
        assertThat(result).isEqualTo(expected);
    }
}
```

---

## Common Pitfalls & Solutions

### Pitfall 1: Non-Deterministic Tests

**Problem**: Tests pass on developer machine, fail in CI.

**Cause**: Uses wall-clock time or unseeded randomness.

**Solution**:
```java
// WRONG
@Test
void testScheduledTask() {
    task.schedule(Duration.ofMillis(100));
    Thread.sleep(150);
    assertThat(task.isComplete()).isTrue();  // Flaky!
}

// RIGHT
@Test
void testScheduledTask() throws Exception {
    var latch = new CountDownLatch(1);
    task.onCompletion(() -> latch.countDown());
    task.schedule(Duration.ofMillis(100));

    assertTrue(latch.await(5, TimeUnit.SECONDS), "Task didn't complete");
    assertThat(task.isComplete()).isTrue();
}
```

### Pitfall 2: Port Conflicts

**Problem**: Tests fail with "Address already in use" error.

**Cause**: Using fixed port numbers, conflicts when parallel testing.

**Solution**:
```java
// WRONG
var server = new Server(8080);  // Fixed port

// RIGHT
var server = new Server(0);  // Dynamic port (OS assigns)
server.start();
var port = server.getPort();  // Query assigned port

// Use port in tests
var client = new Client("localhost", port);
```

### Pitfall 3: Timing-Sensitive Assertions

**Problem**: Tests fail randomly with timing-dependent operations.

**Cause**: Assertions based on elapsed time or scheduled operations.

**Solution**:
```java
// WRONG
@Test
void shouldCompleteWithinTime() {
    var startTime = System.nanoTime();
    operation.execute();
    var elapsed = System.nanoTime() - startTime;

    assertThat(elapsed).isLessThan(1_000_000);  // Flaky on slow systems
}

// RIGHT
@Test
void shouldCompleteWithinTime() throws Exception {
    var future = new CompletableFuture<>();
    operation.onComplete(future::complete);

    var result = future.get(1, TimeUnit.SECONDS);  // Waits for actual completion
    assertThat(result).isNotNull();
}
```

### Pitfall 4: Insufficient Byzantine Tolerance

**Problem**: "Byzantine quorum loss" or tests that can't tolerate injected faults.

**Cause**: Cluster size too small relative to Byzantine count.

**Solution**:
```java
// WRONG
var cluster = createCluster(3);  // Only 3 nodes
cluster.setByzantine(1);  // Now quorum is impossible (1 honest < 2 needed)

// RIGHT
var cluster = createCluster(4);  // 3f+1 for f=1
cluster.setByzantine(1);  // 3 honest > 2 needed for quorum

// OR for f=2 tolerance
var cluster = createCluster(7);  // 3f+1 for f=2
cluster.setByzantine(2);  // 5 honest > 3 needed
```

### Pitfall 5: Forgotten Resource Cleanup

**Problem**: File descriptors leak, ports remain open, memory grows.

**Cause**: Resources not properly closed.

**Solution**:
```java
// WRONG
@Test
void testCluster() {
    var cluster = new TestCluster(3);
    cluster.start();
    // ... test ...
    // Forgot to close!
}

// RIGHT
@Test
void testCluster() {
    try (var cluster = new TestCluster(3)) {
        cluster.start();
        // ... test ...
    }  // Automatically closed
}
```

### Pitfall 6: Unclear Byzantine Behavior

**Problem**: Test description doesn't explain what Byzantine behavior is injected.

**Cause**: Byzantine injection not explicitly marked.

**Solution**:
```java
// WRONG
@Test
void testConsensus() {
    var cluster = createCluster(4);
    // Somewhere node 3 is made Byzantine, but where?
}

// RIGHT
@Test
void testConsensusWithEquivocation() {
    var cluster = createCluster(4);

    // Inject Byzantine behavior: Node 3 will equivocate
    cluster.setByzantine(3, ByzantineType.EQUIVOCATION);

    // Test verifies detection and recovery
}
```

---

## Performance Baselines

### Transaction Throughput

| Test | Module | Baseline | Target | Notes |
|------|--------|----------|--------|-------|
| **Submit throughput** | CHOAM | 100-1000 tx/sec | >= 100 | Committee-dependent |
| **Execution throughput** | SQL-State | 50-500 tx/sec | >= 50 | Database + consensus |
| **Block production** | CHOAM | 10-50 blocks/sec | >= 10 | Batching dependent |
| **Gossip rate** | Fireflies | 1000-10000 msgs/sec | >= 100 | Network-dependent |

### Latency Percentiles

| Operation | p50 | p95 | p99 | Test |
|-----------|-----|-----|-----|------|
| **Transaction submit** | <10ms | <100ms | <500ms | ChoamPerformanceTest |
| **Block execution** | <50ms | <100ms | <200ms | SQLStatePerformanceTest |
| **Consensus round** | <100ms | <500ms | <1000ms | EtherealPerformanceTest |
| **Message latency** | <5ms | <20ms | <50ms | GossipLatencyTest |

### Resource Baselines

| Resource | Per Node | Total (4 nodes) | Test |
|----------|----------|-----------------|------|
| **Heap memory** | 500 MB | 2 GB | MemoryProfileTest |
| **Open file descriptors** | 50 | 200 | ResourceLeakTest |
| **Network connections** | 10 | 30 | ConnectionCountTest |

### Known Timing-Sensitive Tests

| Test | Module | Issue | Mitigation |
|------|--------|-------|------------|
| `KeyRotationOrchestratorTest` | witness-service | ScheduledExecutorService timing | Use CountDownLatch, not sleep |
| `TimingAnomalyTest` | witness-service | Clock-dependent | Use Clock.fixed() |
| `GossipLatencyTest` | fireflies | Network timing | Use CountDownLatch for completion |

---

## References

### Test Configuration
- **CLAUDE.md**: Build commands and troubleshooting
- **DEVELOPER_QUICKSTART.md**: Getting started with testing
- **choam/TEST_OPTIMIZATION.md**: CHOAM-specific test optimization

### Byzantine Consensus
- **docs/ARCHITECTURE.md**: System architecture overview
- **fireflies/README.md**: Membership service design
- **ethereal/README.md**: Consensus protocol (Aleph-BFT)

### Test Patterns
- **docs/INTEGRATION_PATTERNS.md**: Common integration patterns
- **docs/SECURITY_THREAT_MODEL.md**: Threat model and Byzantine assumptions

### Monitoring & Observability
- **docs/MONITORING_GUIDE.md**: Test metrics and SLAs
- **docs/OPERATIONAL_PROCEDURES.md**: Test infrastructure operations

---

## Quick Links

- **Find tests**: `find . -path ./target -prune -o -name "*Test.java" -print`
- **Run all tests**: `./mvnw test`
- **Run module tests**: `./mvnw test -pl <module>`
- **View coverage**: `./mvnw clean test jacoco:report` then open `target/site/jacoco/index.html`
- **Debug test**: See [Debugging a Test](#common-development-tasks) in DEVELOPER_QUICKSTART.md

---

## Test Harness Patterns

### Overview

Delos test infrastructure provides comprehensive patterns for all test categories. This section documents the common patterns, infrastructure classes, and best practices demonstrated in the Archipelago subsystem remediation (Phases 1-4).

### Test Naming Conventions

**Test Class Naming**:
- `*Test.java` - Unit tests (isolated components, mocked dependencies)
- `*IntegrationTest.java` - Integration tests (real I/O, single node)
- `*ClusterTest.java` - Multi-node distributed tests
- `*ByzantineTest.java` - Byzantine fault tolerance tests
- `*StressTest.java` - High load tests (requires `-Dlarge_tests=true`)
- `*ErrorPathTest.java` - Comprehensive error path and edge case tests
- `*AsyncTest.java` - Async I/O and concurrency tests

**Test Method Naming**:
```java
// GOOD - Describes what is tested
@Test
void shouldRejectInvalidSignature() { ... }

@Test
void shouldMaintainSafetyWithNodeFailure() { ... }

@Test
void shouldDetectEquivocation() { ... }

// BAD - Generic names
@Test
void testMethod1() { ... }

@Test
void test() { ... }
```

**Test Category Organization**:
```java
public class ArchipelagoErrorPathTest {

    // ========================================
    // Category 1: Server Shutdown During Active Calls
    // ========================================

    @Test
    void testServerShutdownDuringActiveCall() { ... }

    @Test
    void testServerShutdownDuringMultipleActiveCalls() { ... }

    // ========================================
    // Category 2: Concurrent close() + borrow() Races
    // ========================================

    @Test
    void testConcurrentCloseAndBorrow() { ... }
}
```

### Test Infrastructure Patterns

#### 1. Dynamic Port Allocation

**Problem**: Fixed ports conflict in parallel testing.

**Solution**: Use port 0 (OS assigns available port):

```java
// GOOD - Dynamic port
var server = InProcessServerBuilder.generateName();
var channel = InProcessChannelBuilder.forName(server).build();

// In-process channels don't use network ports, but for real gRPC servers:
var server = ServerBuilder.forPort(0).build().start(); // OS assigns port
var actualPort = server.getPort(); // Query assigned port

// BAD - Fixed port
var server = ServerBuilder.forPort(9090).build().start(); // May conflict
```

**Example from ArchipelagoErrorPathTest**:
```java
var serverName = InProcessServerBuilder.generateName(); // Unique per test
var server = InProcessServerBuilder.forName(serverName)
                                   .directExecutor()
                                   .addService(testService)
                                   .build()
                                   .start();
```

#### 2. Deterministic Testing with Clock.fixed()

**Pattern**: Use fixed clock for reproducible time-dependent tests.

**Example from ServerConnectionCacheTest**:
```java
@BeforeEach
public void setUp() {
    // Deterministic clock for reproducible tests
    fixedClock = Clock.fixed(Instant.parse("2026-02-15T10:00:00Z"), ZoneId.of("UTC"));
}

@Test
void testMetrics_channelOpenDuration() {
    var mutableClock = new MutableClock(Instant.parse("2026-02-15T10:00:00Z"), ZoneId.of("UTC"));
    cache = ServerConnectionCache.newBuilder()
                                  .setClock(mutableClock)
                                  .build();

    var channel = cache.borrow(contextDigest, testMembers.get(0));

    // Advance clock by 5 seconds
    mutableClock.advance(Duration.ofSeconds(5));

    cache.close();

    // Verify duration recorded (5 seconds)
    verify(mockMetrics).recordChannelOpenDuration(Duration.ofSeconds(5).toNanos());
}

// Helper: Mutable clock for time progression tests
private static class MutableClock extends Clock {
    private Instant instant;
    private final ZoneId zone;

    public void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
```

#### 3. Resource Lifecycle Management

**Pattern**: Use try-with-resources or @AfterEach cleanup.

**Example from ArchipelagoErrorPathTest**:
```java
private List<Server> serversToCleanup;
private List<ManagedChannel> channelsToCleanup;

@BeforeEach
public void setUp() {
    serversToCleanup = new ArrayList<>();
    channelsToCleanup = new ArrayList<>();
}

@AfterEach
public void tearDown() {
    // Cleanup servers
    for (Server server : serversToCleanup) {
        try {
            server.shutdown();
            server.awaitTermination(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }
    serversToCleanup.clear();

    // Cleanup channels
    for (ManagedChannel channel : channelsToCleanup) {
        try {
            channel.shutdown();
            channel.awaitTermination(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }
    channelsToCleanup.clear();
}

@Test
void testServerShutdownDuringActiveCall() {
    var server = InProcessServerBuilder.forName(serverName).build().start();
    serversToCleanup.add(server); // Register for cleanup

    var channel = InProcessChannelBuilder.forName(serverName).build();
    channelsToCleanup.add(channel); // Register for cleanup

    // Test logic...
}
```

#### 4. Concurrent Testing Patterns

**Pattern A: CountDownLatch for synchronization**:

```java
@Test
void testConcurrentBorrow_sameMember() throws Exception {
    var member = testMembers.get(0);
    int threadCount = 10;
    var latch = new CountDownLatch(threadCount);
    var channels = Collections.synchronizedList(new ArrayList<ManagedServerChannel>());

    // Multiple threads borrow same member simultaneously
    for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
            try {
                var channel = cache.borrow(contextDigest, member);
                channels.add(channel);
            } finally {
                latch.countDown();
            }
        });
    }

    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

    // Assertions...
    assertThat(channels).hasSize(threadCount).doesNotContainNull();
}
```

**Pattern B: CyclicBarrier for synchronized start**:

```java
@Test
void testConcurrentBorrowSameMember_singleConnectionAttempt() throws Exception {
    int threadCount = 50;
    var barrier = new CyclicBarrier(threadCount);
    var results = new ConcurrentLinkedQueue<ManagedServerChannel>();

    var executor = Executors.newVirtualThreadPerTaskExecutor();
    var futures = new ArrayList<Future<?>>();

    for (int i = 0; i < threadCount; i++) {
        futures.add(executor.submit(() -> {
            try {
                barrier.await(); // Synchronize start
                var channel = cache.borrow(contextDigest, targetMember);
                results.add(channel);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }));
    }

    // Wait for all threads
    for (var future : futures) {
        future.get(10, TimeUnit.SECONDS);
    }
    executor.shutdown();

    assertThat(results).hasSize(threadCount);
}
```

**Pattern C: AtomicInteger for concurrent counting**:

```java
@Test
void testConcurrentMultipleClose() throws Exception {
    int closeThreads = 5;
    var latch = new CountDownLatch(closeThreads);
    var closeCount = new AtomicInteger(0);

    // Multiple threads try to close simultaneously
    for (int i = 0; i < closeThreads; i++) {
        executor.submit(() -> {
            try {
                cache.close();
                closeCount.incrementAndGet();
            } finally {
                latch.countDown();
            }
        });
    }

    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

    // All threads attempted close
    assertThat(closeCount.get()).isEqualTo(closeThreads);
}
```

#### 5. Async Testing Patterns

**Pattern**: Test async operations with CompletableFuture and timeouts.

**Example from ServerConnectionCacheAsyncTest**:
```java
@Test
void testLockNotHeldDuringConnect() throws Exception {
    var slowFactory = new SlowFactory(Duration.ofMillis(200));

    var threadAStarted = new CountDownLatch(1);
    var threadBCompleted = new AtomicInteger(0);

    // Thread A: borrow uncached member (triggers slow connection)
    var threadA = Thread.ofVirtual().start(() -> {
        threadAStarted.countDown();
        var channel = cache.borrow(contextDigest, memberA);
        assertThat(channel).isNotNull();
    });

    // Wait for Thread A to start
    threadAStarted.await();
    Thread.sleep(50); // Ensure Thread A is waiting

    // Thread B: borrow cached member (should complete immediately)
    long startB = System.nanoTime();
    var channelB2 = cache.borrow(contextDigest, memberB);
    long durationB = System.nanoTime() - startB;

    assertThat(channelB2).isNotNull();
    threadBCompleted.set((int) TimeUnit.NANOSECONDS.toMillis(durationB));

    // Thread B completed in < 10ms (not blocked by Thread A's 200ms I/O)
    assertThat(threadBCompleted.get()).isLessThan(10);

    threadA.join();
}
```

**Helper: Slow Factory for I/O Testing**:
```java
static class SlowFactory implements ServerConnectionFactory {
    private final Duration delay;
    private final AtomicInteger callCount = new AtomicInteger();
    private final AtomicInteger peakConcurrency = new AtomicInteger();
    private final AtomicInteger currentConcurrency = new AtomicInteger();

    @Override
    public ManagedChannel connectTo(Member to) {
        int concurrent = currentConcurrency.incrementAndGet();
        peakConcurrency.accumulateAndGet(concurrent, Math::max);
        try {
            Thread.sleep(delay.toMillis());
            callCount.incrementAndGet();
            return InProcessChannelBuilder.forName("test-" + to.getId()).build();
        } finally {
            currentConcurrency.decrementAndGet();
        }
    }

    int getPeakConcurrency() { return peakConcurrency.get(); }
}
```

### Category-Specific Patterns

#### Unit Test Patterns

**Characteristics**:
- Single class under test
- Mocked dependencies
- No network/consensus
- Duration: < 1 second

**Example from ServerConnectionCacheTest**:
```java
@Test
public void testEvictionRespects_minIdle() {
    var mutableClock = new MutableClock(Instant.parse("2026-02-15T10:00:00Z"), ZoneId.of("UTC"));
    cache = ServerConnectionCache.newBuilder()
                                  .setTarget(5)
                                  .setMinIdle(Duration.ofSeconds(10))
                                  .setClock(mutableClock)
                                  .setMetrics(mockMetrics)
                                  .build();

    // Borrow and release 10 connections
    for (int i = 0; i < 10; i++) {
        var ch = cache.borrow(contextDigest, testMembers.get(i));
        ch.release();
    }

    // Should NOT evict because minIdle hasn't passed
    verify(mockMetrics, never()).recordCloseConnection();

    // Advance clock by 11 seconds
    mutableClock.advance(Duration.ofSeconds(11));

    // Trigger eviction check
    var newChannel = cache.borrow(contextDigest, testMembers.get(10));
    newChannel.release();

    // Now eviction should occur
    verify(mockMetrics, atLeastOnce()).recordCloseConnection();
}
```

#### Integration Test Patterns

**Characteristics**:
- Multiple components
- Real I/O (database, gRPC)
- No Byzantine members
- Duration: 1-10 seconds

**Pattern**: Test component integration without cluster complexity.

#### Error Path Test Patterns

**Characteristics**:
- Comprehensive edge cases
- Missing context/headers
- Validator rejection
- Shutdown races
- Malformed input

**Example from ArchipelagoErrorPathTest**:
```java
// Category: Missing gRPC Context Headers
@Test
public void testMissingContextHeader() {
    var routableService = new RoutableService<TestServiceImpl>();
    var testService = new TestServiceImpl();
    var responseObserver = mock(StreamObserver.class);

    routableService.bind(contextDigest, testService, null);

    // Evaluate WITHOUT setting SERVER_CONTEXT_KEY (missing header)
    Consumer<TestServiceImpl> consumer = s -> s.doSomething();
    routableService.evaluate(responseObserver, consumer);

    // Verify NOT_FOUND (not NPE)
    verify(responseObserver).onError(argThat(t -> {
        if (!(t instanceof StatusRuntimeException)) {
            return false;
        }
        var sre = (StatusRuntimeException) t;
        return sre.getStatus().getCode() == Status.Code.NOT_FOUND;
    }));

    // Verify service was NOT invoked
    assertThat(testService.invoked.get()).isFalse();
}

// Category: Validator Rejection
@Test
public void testValidatorRejectsToken() {
    var routableService = new RoutableService<TestServiceImpl>();
    var testService = new TestServiceImpl();
    var responseObserver = mock(StreamObserver.class);

    // Validator that always rejects
    routableService.bind(contextDigest, testService, token -> false);

    // Create a real HashedToken
    var tokenHash = DigestAlgorithm.DEFAULT.digest("test-token".getBytes());
    var token = Token.generate(Key.generateKey(), "test-payload");
    var hashedToken = new HashedToken(tokenHash, token);

    Context.current()
           .withValue(SERVER_CONTEXT_KEY, contextDigest)
           .withValue(AccessTokenContextKey, hashedToken)
           .run(() -> {
               routableService.evaluate(responseObserver, s -> s.doSomething());
           });

    verify(responseObserver).onError(argThat(t -> {
        return ((StatusRuntimeException) t).getStatus().getCode() == Status.Code.UNAUTHENTICATED;
    }));

    assertThat(testService.invoked.get()).isFalse();
}
```

#### Byzantine Test Patterns

**Characteristics**:
- 3f+1 nodes minimum
- Fault injection
- Detection validation
- Duration: 20-120 seconds

**Pattern from GorgoneionBftTestHelpers**:
```java
@Test
void shouldDetectByzantineNodeFailure() throws Exception {
    // Setup deterministic context
    var entropy = deterministicEntropy();

    try (var ctx = setupMultiMemberContext(7, entropy)) {
        var params = testParameters(fixedClock()).build();

        try (var cluster = createGorgoneionCluster(ctx, params)) {
            // Verify initial cluster health
            assertThat(cluster.size()).isEqualTo(7);
            assertThat(cluster.majority()).isEqualTo(5);
            assertThat(cluster.faultTolerance()).isEqualTo(2);

            // Inject Byzantine failure
            var fault = simulateByzantineNodeFailure(cluster, 5);

            try {
                // System should continue with remaining nodes
                var nonce = applyWithRetry(client, cluster, Duration.ofSeconds(30), 3);
                assertThat(nonce).isNotNull();

                // Verify safety properties maintained
                verifyBftSafetyProperty(cluster, safety -> {
                    assertThat(safety.noConflictingNonces()).isTrue();
                });
            } finally {
                fault.restore();
            }
        }
    }
}

// Helper: Deterministic entropy
public static SecureRandom deterministicEntropy() {
    var entropy = SecureRandom.getInstance("SHA1PRNG");
    entropy.setSeed(new byte[] { 6, 6, 6 });
    return entropy;
}

// Helper: Fixed clock
public static Clock fixedClock() {
    return Clock.fixed(Instant.parse("2026-01-09T12:00:00Z"), ZoneId.of("UTC"));
}
```

#### Byzantine Attack Simulations

**Pattern from ArchipelagoErrorPathTest**:
```java
// Category: Byzantine Attack Simulations

// Test 1: Header Spoofing
@Test
public void testByzantineHeaderSpoofing_contextId() {
    var routableService = new RoutableService<TestServiceImpl>();
    var testService = new TestServiceImpl();
    var responseObserver = mock(StreamObserver.class);

    // Legitimate context
    routableService.bind(contextDigest, testService, null);

    // Attacker tries to spoof a different context
    var spoofedContext = DigestAlgorithm.DEFAULT.digest("spoofed-context".getBytes());

    Context.current().withValue(SERVER_CONTEXT_KEY, spoofedContext).run(() -> {
        routableService.evaluate(responseObserver, s -> s.doSomething());
    });

    // Verify NOT_FOUND (spoofed context not bound)
    verify(responseObserver).onError(argThat(t ->
        ((StatusRuntimeException) t).getStatus().getCode() == Status.Code.NOT_FOUND
    ));

    assertThat(testService.invoked.get()).isFalse();
}

// Test 2: Connection Flooding
@Test
public void testByzantineConnectionFlooding() throws Exception {
    var cache = ServerConnectionCache.newBuilder()
                                     .setTarget(5) // Small target
                                     .setConnectionTimeout(Duration.ofSeconds(2))
                                     .build();

    int floodCount = 50; // Attempt to open 50 connections rapidly
    var latch = new CountDownLatch(floodCount);
    var successfulBorrows = new AtomicInteger(0);
    var failedBorrows = new AtomicInteger(0);

    for (int i = 0; i < floodCount; i++) {
        int memberIndex = i % testMembers.size();
        executor.submit(() -> {
            try {
                var channel = cache.borrow(contextDigest, testMembers.get(memberIndex));
                if (channel != null) {
                    successfulBorrows.incrementAndGet();
                    channel.release();
                } else {
                    failedBorrows.incrementAndGet();
                }
            } finally {
                latch.countDown();
            }
        });
    }

    assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();

    // Verify cache handled flood without crashing
    assertThat(successfulBorrows.get() + failedBorrows.get()).isEqualTo(floodCount);
}

// Test 3: Replay Attack
@Test
public void testByzantineReplayAttack() {
    var seenTokens = new ConcurrentHashMap<Digest, Boolean>();
    routableService.bind(contextDigest, testService, token -> {
        // Check if token already seen (simple replay detection)
        return seenTokens.putIfAbsent(token.hash(), true) == null;
    });

    var hashedToken = new HashedToken(tokenHash, token);

    // First request (legitimate)
    Context.current()
           .withValue(SERVER_CONTEXT_KEY, contextDigest)
           .withValue(AccessTokenContextKey, hashedToken)
           .run(() -> routableService.evaluate(responseObserver, s -> s.doSomething()));

    verify(responseObserver, never()).onError(any());
    assertThat(testService.invoked.get()).isTrue();

    // Second request with SAME token (replay attack)
    testService.invoked.set(false);
    var replayObserver = mock(StreamObserver.class);

    Context.current()
           .withValue(SERVER_CONTEXT_KEY, contextDigest)
           .withValue(AccessTokenContextKey, hashedToken)
           .run(() -> routableService.evaluate(replayObserver, s -> s.doSomething()));

    // Verify replay was rejected
    verify(replayObserver).onError(argThat(t ->
        ((StatusRuntimeException) t).getStatus().getCode() == Status.Code.UNAUTHENTICATED
    ));

    assertThat(testService.invoked.get()).isFalse();
}
```

### Test Helper Classes

#### SlowFactory (Async I/O Testing)

```java
/**
 * Factory with configurable delay to simulate slow connections.
 * Tracks call count and peak concurrency.
 */
static class SlowFactory implements ServerConnectionFactory {
    private final Duration delay;
    private final AtomicInteger callCount = new AtomicInteger();
    private final AtomicInteger peakConcurrency = new AtomicInteger();
    private final AtomicInteger currentConcurrency = new AtomicInteger();

    SlowFactory(Duration delay) {
        this.delay = delay;
    }

    @Override
    public ManagedChannel connectTo(Member to) {
        int concurrent = currentConcurrency.incrementAndGet();
        peakConcurrency.accumulateAndGet(concurrent, Math::max);
        try {
            Thread.sleep(delay.toMillis());
            callCount.incrementAndGet();
            return InProcessChannelBuilder.forName("test-" + to.getId()).build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            currentConcurrency.decrementAndGet();
        }
    }

    int getCallCount() { return callCount.get(); }
    int getPeakConcurrency() { return peakConcurrency.get(); }
}
```

#### MutableClock (Time Progression Testing)

```java
/**
 * Mutable clock for testing time-dependent behavior.
 */
private static class MutableClock extends Clock {
    private Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
```

#### TestServiceImpl (Simple Service for Testing)

```java
/**
 * Simple test service implementation.
 */
static class TestServiceImpl {
    final AtomicBoolean invoked = new AtomicBoolean(false);

    void doSomething() {
        invoked.set(true);
    }
}
```

### Common Test Pitfalls

#### Pitfall 1: Race Conditions in Shutdown Tests

**Problem**: Test completes before cleanup, resource leaks.

**Solution**: Use CountDownLatch or Future.get() to wait:

```java
// WRONG
@Test
void testServerShutdown() {
    executor.submit(() -> cache.close());
    // Test completes immediately, cleanup may not finish
}

// RIGHT
@Test
void testServerShutdown() throws Exception {
    var latch = new CountDownLatch(1);
    executor.submit(() -> {
        try {
            cache.close();
        } finally {
            latch.countDown();
        }
    });
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
}
```

#### Pitfall 2: Forgetting to Register Resources for Cleanup

**Problem**: Resources leak if test fails before cleanup.

**Solution**: Register in @BeforeEach, cleanup in @AfterEach:

```java
// WRONG
@Test
void testSomething() {
    var server = InProcessServerBuilder.forName("test").build().start();
    // If assertion fails, server leaks
    assertThat(result).isTrue();
    server.shutdown(); // Never reached if assertion fails
}

// RIGHT
private List<Server> serversToCleanup;

@BeforeEach
void setUp() {
    serversToCleanup = new ArrayList<>();
}

@AfterEach
void tearDown() {
    for (Server server : serversToCleanup) {
        try {
            server.shutdown();
            server.awaitTermination(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }
}

@Test
void testSomething() {
    var server = InProcessServerBuilder.forName("test").build().start();
    serversToCleanup.add(server); // Always cleaned up
    assertThat(result).isTrue();
}
```

#### Pitfall 3: Not Using AtomicInteger for Concurrent Counting

**Problem**: int++ is not thread-safe.

**Solution**: Use AtomicInteger:

```java
// WRONG
private int callCount = 0;

@Test
void testConcurrent() {
    for (int i = 0; i < 100; i++) {
        executor.submit(() -> callCount++); // Race condition!
    }
}

// RIGHT
private AtomicInteger callCount = new AtomicInteger();

@Test
void testConcurrent() {
    for (int i = 0; i < 100; i++) {
        executor.submit(() -> callCount.incrementAndGet()); // Thread-safe
    }
}
```

### Real-World Examples

#### Example 1: Comprehensive Error Path Test (ArchipelagoErrorPathTest)

**File**: `memberships/src/test/java/com/hellblazer/delos/archipelago/ArchipelagoErrorPathTest.java`

**Coverage**: 21 tests, 1158 lines

**Categories**:
1. Server shutdown during active calls
2. Concurrent close() + borrow() races
3. Missing gRPC context headers
4. RoutableService validator rejection
5. GrpcProxy edge cases (streaming, cancellation)
6. Byzantine attack simulations

**Key Patterns**:
- Dynamic port allocation
- CountDownLatch for synchronization
- AtomicInteger for concurrent counting
- Comprehensive cleanup in @AfterEach

#### Example 2: Deterministic Unit Test (ServerConnectionCacheTest)

**File**: `memberships/src/test/java/com/hellblazer/delos/archipelago/ServerConnectionCacheTest.java`

**Coverage**: Eviction, concurrency, lifecycle, metrics

**Key Patterns**:
- MutableClock for time progression
- Mock verification with Mockito
- Helper methods for cache creation

#### Example 3: Async I/O Test (ServerConnectionCacheAsyncTest)

**File**: `memberships/src/test/java/com/hellblazer/delos/archipelago/ServerConnectionCacheAsyncTest.java`

**Coverage**: 10 tests validating async connection establishment

**Key Patterns**:
- SlowFactory for I/O simulation
- Virtual threads for async testing
- CyclicBarrier for synchronized start
- Peak concurrency tracking

#### Example 4: Byzantine Fault Injection (GorgoneionBftTestHelpers)

**File**: `gorgoneion/src/test/java/com/hellblazer/delos/gorgoneion/GorgoneionBftTestHelpers.java`

**Coverage**: BFT test infrastructure

**Key Patterns**:
- Deterministic entropy (SeededSecureRandom)
- Fixed clock for reproducibility
- Fault injection with restoration
- Safety property verification

---

## Changelog

| Version | Date | Changes |
|---------|------|---------|
| **2.1** | 2026-02-16 | Added comprehensive test harness patterns from Archipelago remediation (error paths, async I/O, Byzantine attacks, naming conventions) |
| **2.0** | 2026-01-27 | Comprehensive testing guide with BFT, deterministic patterns, concrete examples, enforcement checklist |
| **1.0** | 2026-01-01 | Initial testing documentation (basic commands and categories) |
