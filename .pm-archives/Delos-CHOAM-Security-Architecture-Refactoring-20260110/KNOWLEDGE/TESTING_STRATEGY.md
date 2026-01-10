# Testing Strategy

**Project**: CHOAM Security & Architecture Refactoring
**Last Updated**: 2026-01-09

---

## Overview

Testing is critical for this project due to:
1. Security-critical nature of CHOAM
2. Byzantine fault tolerance requirements
3. Major refactoring risk
4. Low starting coverage (21%)

---

## Test Categories

### 1. Unit Tests

**Purpose**: Test individual components in isolation

**Characteristics**:
- Fast execution (<1 second per test)
- No external dependencies
- Mock all collaborators
- One logical assertion per test

**Naming Convention**: `ClassNameTest.java`

**Pattern**:
```java
@Test
void shouldDescribeExpectedBehavior() {
    // Arrange
    var mock = mock(Dependency.class);
    var sut = new SystemUnderTest(mock);

    // Act
    var result = sut.doSomething();

    // Assert
    assertThat(result).isEqualTo(expected);
}
```

### 2. Integration Tests

**Purpose**: Test component interactions

**Characteristics**:
- Medium execution time (<30 seconds)
- Real component instances
- May use real Ethereal/Fireflies
- Tests workflows end-to-end

**Naming Convention**: `*IntegrationTest.java`

**Pattern**:
```java
@Test
void shouldCompleteWorkflow() {
    // Setup real components
    var choam = CHOAMFactory.create(params);

    // Execute workflow
    choam.submit(transaction);
    waitForCompletion();

    // Verify outcome
    assertThat(choam.getBlock(hash)).isPresent();
}
```

### 3. Byzantine Tests

**Purpose**: Validate fault tolerance properties

**Characteristics**:
- Longer execution time (minutes)
- Multiple nodes (typically 4+)
- Simulated failures
- Property verification

**Naming Convention**: `*ByzantineTest.java`

**Test Categories**:

| Category | Count | Description |
|----------|-------|-------------|
| Node Failures | 8-10 | Crash, Byzantine behavior |
| Network Partitions | 6-8 | Various partition scenarios |
| Message Corruption | 4-6 | Invalid messages |
| Timing Attacks | 4-5 | Delay, reorder |
| Recovery | 5-6 | Node rejoin, sync |

**Pattern**:
```java
@Test
void shouldMaintainSafetyUnderFByzantineFailures() {
    // Setup cluster
    var nodes = createNodes(4); // 3f+1 for f=1

    // Introduce Byzantine behavior
    nodes.get(0).setByzantine(true);

    // Execute transactions
    submitTransactions(nodes, 100);

    // Verify safety: all honest nodes agree
    var states = getHonestNodeStates(nodes);
    assertThat(states).allMatch(s -> s.equals(states.get(0)));
}
```

### 4. Determinism Tests

**Purpose**: Verify identical state across nodes

**Characteristics**:
- Critical for Byzantine systems
- Must run after each decomposition step
- Compares state hashes

**Naming Convention**: `DeterminismTest.java`

**Pattern**:
```java
@Test
void shouldProduceDeterministicStateWithIdenticalInput() {
    // Setup: Create identical nodes
    List<CHOAM> nodes = createIdenticalNodes(4);

    // Execute: Submit identical transactions
    var transactions = generateDeterministicTransactions(100);
    for (var tx : transactions) {
        for (var node : nodes) {
            node.submit(tx);
        }
    }

    // Wait for consensus
    waitForConsensus(nodes);

    // Verify: All nodes have identical state
    var expectedHash = nodes.get(0).getStateHash();
    for (var node : nodes) {
        assertThat(node.getStateHash())
            .describedAs("Node %s state mismatch", node.getId())
            .isEqualTo(expectedHash);
    }
}
```

### 5. Performance Tests

**Purpose**: Measure and validate performance

**Characteristics**:
- Benchmarking focused
- Measures throughput, latency
- Establishes baselines

**Naming Convention**: `*PerformanceTest.java`

**Metrics**:
- Transaction throughput (TPS)
- Transaction latency (p50, p95, p99)
- Block time
- Memory usage
- CPU usage

---

## Coverage Strategy

### Phase 0: Security Focus

| Area | Target | Priority |
|------|--------|----------|
| Signature validation | 100% | P0 |
| Queue bounds | 95%+ | P0 |
| Circuit breaker | 95%+ | P0 |
| Race condition fix | 95%+ | P0 |

### Phase 1: Refactoring Foundation

| Milestone | Target | Rationale |
|-----------|--------|-----------|
| Before decomposition | 40%+ | Safety net for refactoring |
| During decomposition | Maintain | No regression |
| After decomposition | 60%+ | Target coverage |

### Phase 2: Enhancement

| Area | Target |
|------|--------|
| Byzantine tests | 30+ scenarios |
| Critical paths | 95%+ |
| Overall | 60%+ |

---

## Test Infrastructure

### Test Utilities

```java
public class CHOAMTestUtils {
    public static List<CHOAM> createNodes(int count) { ... }
    public static List<Transaction> generateTransactions(int count) { ... }
    public static void waitForConsensus(List<CHOAM> nodes) { ... }
    public static void injectFailure(CHOAM node, FailureType type) { ... }
}
```

### Byzantine Test Framework

```java
public enum FailureType {
    CRASH,
    SLOW_RESPONSE,
    CONFLICTING_MESSAGES,
    MESSAGE_DROP,
    MESSAGE_DELAY,
    PARTITION
}

public class ByzantineTestHarness {
    public void injectFailure(int nodeIndex, FailureType type) { ... }
    public void healFailure(int nodeIndex) { ... }
    public void partition(Set<Integer> groupA, Set<Integer> groupB) { ... }
    public void healPartition() { ... }
}
```

### Determinism Verification

```java
public class DeterminismVerifier {
    public static boolean verifyIdenticalState(List<CHOAM> nodes) {
        if (nodes.isEmpty()) return true;

        Digest expected = nodes.get(0).getStateHash();
        for (var node : nodes) {
            if (!node.getStateHash().equals(expected)) {
                log.error("State mismatch: node {} has {} vs expected {}",
                    node.getId(), node.getStateHash(), expected);
                return false;
            }
        }
        return true;
    }
}
```

---

## Test Execution

### Local Development

```bash
# Run all tests in module
./mvnw test -pl choam

# Run specific test class
./mvnw test -pl choam -Dtest=BlockProcessorTest

# Run specific test method
./mvnw test -pl choam -Dtest=BlockProcessorTest#shouldProcessValidBlock

# Run with debugging
./mvnw test -pl choam -Dtest=BlockProcessorTest \
  -DargLine="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005"
```

### Integration Testing

```bash
# With Ethereal
./mvnw test -pl choam,ethereal

# With Fireflies
./mvnw test -pl choam,fireflies

# Full integration
./mvnw clean install
```

### Large Tests (Resource Intensive)

```bash
# Full test suite
./mvnw clean install -Dlarge_tests=true

# With increased heap
./mvnw clean install -Dlarge_tests=true \
  -DargLine="-Xmx12G -Xms6G"
```

### Coverage Report

```bash
# Generate coverage report
./mvnw jacoco:report -pl choam

# View report
open choam/target/site/jacoco/index.html
```

---

## Test Quality Standards

### Test Naming

```java
// Good: Describes behavior
void shouldRejectTransactionWithInvalidSignature()
void shouldMaintainSafetyUnderFFailures()
void shouldRecoverAfterPartitionHeals()

// Bad: Describes implementation
void testValidateSignature()
void testByzantine()
void testPartition()
```

### Test Structure

```java
@Test
void shouldDescribeBehavior() {
    // Arrange - Setup preconditions
    var input = createInput();
    var sut = createSystemUnderTest();

    // Act - Execute behavior
    var result = sut.execute(input);

    // Assert - Verify outcome
    assertThat(result).satisfies(r -> {
        assertThat(r.getValue()).isEqualTo(expected);
        assertThat(r.getStatus()).isEqualTo(SUCCESS);
    });
}
```

### Assertions

```java
// Good: Descriptive assertions
assertThat(result)
    .describedAs("Transaction should be accepted")
    .isEqualTo(AdmissionResult.ADMITTED);

// Bad: Raw assertions
assertEquals(expected, result);
assertTrue(result.isValid());
```

---

## Byzantine Test Scenarios

### Node Failure Scenarios

| ID | Scenario | Expected Outcome |
|----|----------|------------------|
| BF-1 | Single node crash | System continues |
| BF-2 | f nodes crash | System continues |
| BF-3 | f+1 nodes crash | System halts (expected) |
| BF-4 | Leader crash | New leader elected |
| BF-5 | Crash during consensus | Transaction eventually completes |
| BF-6 | Crash during checkpoint | Checkpoint recovered |
| BF-7 | Byzantine conflicting messages | Honest nodes agree |
| BF-8 | Byzantine delayed responses | Timeout and proceed |

### Network Partition Scenarios

| ID | Scenario | Expected Outcome |
|----|----------|------------------|
| NP-1 | Two-way split (equal) | Majority side continues |
| NP-2 | Minority partition | Minority halts, majority continues |
| NP-3 | Leader in minority | New leader in majority |
| NP-4 | Partition during view change | View change completes after heal |
| NP-5 | Asymmetric partition | Correct behavior per group |
| NP-6 | Partition heal | State sync and recovery |

### Recovery Scenarios

| ID | Scenario | Expected Outcome |
|----|----------|------------------|
| REC-1 | Node rejoin after crash | State syncs correctly |
| REC-2 | Multiple nodes rejoin | All sync correctly |
| REC-3 | Rejoin after many blocks | Efficient sync |
| REC-4 | Rejoin during checkpoint | Checkpoint used for sync |
| REC-5 | Partition heal with divergent state | Converge to correct state |

---

## Continuous Integration

### CI Pipeline

```yaml
# Example CI configuration
test:
  script:
    - ./mvnw test -pl choam
    - ./mvnw jacoco:report -pl choam
  coverage:
    threshold: 60%

integration-test:
  script:
    - ./mvnw clean install
    - ./mvnw test -pl choam,ethereal,fireflies

large-test:
  script:
    - ./mvnw clean install -Dlarge_tests=true
  timeout: 30m
```

### Test Gates

| Gate | Requirement |
|------|-------------|
| PR Merge | All unit tests pass |
| Phase Gate | All integration tests pass |
| Release | All large tests pass |

---

**Testing Strategy Established**: 2026-01-09
**Update**: After each phase completion
