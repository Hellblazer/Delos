# Thoth Module Issue Tracker

## Overview

This document tracks all issues (critical and significant) discovered during the Byzantine fault tolerance remediation project. Issues are linked to beads, phases, and resolution strategies.

---

## Critical Issues (Must Fix in Phase 1)

### THOTH-BFT-001: Byzantine Detection Infrastructure Unused

**Status**: PENDING
**Severity**: CRITICAL
**Impact**: System cannot detect Byzantine behavior from colliding nodes
**Phase**: 1
**Bead**: (To be created by strategic-planner)

#### Problem Description

The `ThothByzantineStateProvider` class is implemented and available, but is never instantiated or integrated into DHT operations. This means:

- Byzantine nodes can send invalid data without detection
- Quorum operations accept responses without Byzantine validation
- System has no way to identify compromised nodes
- No metrics for Byzantine detection latency or frequency

#### Root Cause Analysis

```java
// Current code: ThothByzantineStateProvider never used
public class DhtService {
    public void store(Digest id, KeyEvent event) {
        // Query quorum without Byzantine detection
        List<KeyEvent> responses = queryQuorum(successors);
        // Accept responses as-is (DANGEROUS!)
        selectMajority(responses);
    }
}
```

The Byzantine detection framework exists (in `ThothByzantineStateProvider`) but lacks:
1. Instantiation point in service initialization
2. Integration into quorum validation loop
3. Metrics for observability
4. Error handling for Byzantine transitions

#### Detection Strategy

**Test Scenario**: 3f+1 nodes (f=1 tolerance) with 1 Byzantine node
```java
@Test
void testStore_WithByzantineSignatureForgery_DetectedAndRecovered() {
    // Setup: 4-node cluster, node 3 is Byzantine
    var cluster = TestCluster.create(4);
    cluster.setByzantine(3);  // Node 3 will forge signatures

    // Store event through cluster
    var identifier = cluster.generateIdentifier();
    var event = cluster.generateKeyEvent(identifier);
    cluster.store(identifier, event);

    // Verify: Byzantine node detected
    // Expected: "byzantineDetections" metric > 0
    assertThat(cluster.getMetrics("byzantineDetections")).isGreaterThan(0);

    // Expected: Detection latency < 100ms
    var latency = cluster.getMetrics("byzantineDetectionLatency_ms");
    assertThat(latency).isLessThan(100);

    // Expected: Event still stored correctly
    var retrieved = cluster.retrieve(identifier);
    assertThat(retrieved).isEqualTo(event);
}
```

#### Fix Strategy

1. **Instantiate Provider**:
   - Add `ThothByzantineStateProvider` field to `DhtService`
   - Initialize during service startup
   - Wire into quorum validation loop

2. **Integration Points**:
   - After quorum responses received, call provider validation
   - Provider checks signatures against root of trust
   - Provider identifies Byzantine pattern (inconsistent signatures)
   - Update metrics with detection results

3. **Metrics**:
   - `byzantineDetections` counter
   - `byzantineDetectionLatency_ms` timer
   - `byzantineNodeExclusions` counter

4. **Error Handling**:
   - Detected Byzantine nodes marked as unreliable
   - Exclude from future quorum selections
   - Log with WARN level including node ID and reason
   - Trigger rebalance to exclude Byzantine node

#### Code Changes

**File**: `src/main/java/com/hellblazer/delos/thoth/grpc/dht/DhtService.java`
```java
public class DhtService {
    private final ThothByzantineStateProvider byzantineDetector;

    public DhtService(Context context, KerlDhtMetrics metrics) {
        // ... existing code ...
        this.byzantineDetector = new ThothByzantineStateProvider(context);
    }

    public void store(Digest id, KeyEvent event) throws IOException {
        var startTime = System.currentTimeMillis();
        List<KeyEvent> responses = queryQuorum(successors);

        // NEW: Detect Byzantine behavior
        var byzantineNodes = byzantineDetector.validate(responses);
        if (!byzantineNodes.isEmpty()) {
            metrics.byzantineDetectionCount().increment();
            metrics.byzantineDetectionLatency().record(
                Duration.ofMillis(System.currentTimeMillis() - startTime));
            log.warn("Byzantine nodes detected: {} - excluding from quorum",
                byzantineNodes);
            // Exclude from future quorums
            markUnreliable(byzantineNodes);
        }

        // Consensus despite Byzantine detection
        selectMajority(responses.stream()
            .filter(r -> !byzantineNodes.contains(r.getSourceNode()))
            .collect(toList()));
    }
}
```

#### Validation Criteria

- [ ] ThothByzantineStateProvider instantiated and passed to DhtService
- [ ] Byzantine detection called after each quorum operation
- [ ] Test: Byzantine signature injection detected within 100ms
- [ ] Test: System continues operating with Byzantine node excluded
- [ ] Metrics: byzantineDetections counter incremented on detection
- [ ] Logging: WARN level message with node ID and reason
- [ ] Performance: <1% latency overhead from detection

#### Related Components

- `DhtService`: Integration point
- `DhtClient`: Quorum query interface
- `ThothByzantineStateProvider`: Detection logic
- `KerlDhtMetrics`: Observability

---

### THOTH-BFT-002: No Cryptographic Validation in Quorum Operations

**Status**: PENDING
**Severity**: CRITICAL
**Impact**: Corrupted KERLs can be accepted due to lack of cryptographic verification
**Phase**: 1
**Bead**: (To be created by strategic-planner)

#### Problem Description

Quorum-based store and retrieve operations do not validate signatures on responses. This allows:

- Byzantine nodes to return forged KERL data
- Majority acceptance of invalid events
- Breakdown of cryptographic security model
- No protection against Sybil attacks on quorum responses

#### Root Cause Analysis

```java
// Current code: No signature validation
public class DhtService {
    public KeyEvent retrieve(Digest id, long seq) throws IOException {
        List<KeyEvent> responses = queryQuorum(successors);
        // Select by frequency, NO cryptographic validation
        return selectMajority(responses);  // UNSAFE!
    }
}
```

Missing validation steps:
1. Response signature against responder's public key
2. KERL sequence continuity
3. Event timestamp plausibility
4. Witness threshold requirements

#### Detection Strategy

**Test Scenario**: Byzantine node returns forged signature
```java
@Test
void testRetrieve_WithByzantineForgedSignature_Rejected() {
    var cluster = TestCluster.create(4);
    cluster.setByzantine(3);  // Node 3 will forge signatures

    // Node 3 forges signature on KERL event
    var identifier = cluster.generateIdentifier();
    var event = cluster.generateKeyEvent(identifier);
    cluster.store(identifier, event);

    // Node 3 retrieves but returns with FORGED signature
    // Expected: Signature validation catches forgery
    var retrieved = cluster.retrieve(identifier);

    // Verify: Byzantine signature rejected
    // Check: "signatureValidationFailures" metric incremented
    assertThat(cluster.getMetrics("signatureValidationFailures"))
        .isGreaterThan(0);

    // Verify: Correct event returned (from honest majority)
    assertThat(retrieved).isEqualTo(event);
}
```

#### Fix Strategy

1. **Signature Validation**:
   - Extract signature from each quorum response
   - Verify against responder's public key (from Fireflies context)
   - Reject responses with invalid signatures
   - Count failures in metrics

2. **KERL Continuity Check**:
   - Verify sequence numbers are contiguous
   - Verify no gaps or jumps
   - Verify rotation events have proper key derivation

3. **Timestamp Validation**:
   - Verify timestamp is recent (within 5 minute clock skew)
   - Reject outliers (too old or in future)

4. **Witness Threshold**:
   - Verify witness signatures meet threshold
   - Verify witness IDs against configured witnesses

#### Code Changes

**File**: `src/main/java/com/hellblazer/delos/thoth/grpc/dht/DhtService.java`
```java
public class DhtService {
    public KeyEvent retrieve(Digest id, long seq) throws IOException {
        List<KeyEvent> responses = queryQuorum(successors);

        // NEW: Validate signatures on all responses
        List<KeyEvent> validatedResponses = new ArrayList<>();
        for (var response : responses) {
            try {
                validateSignature(response);  // Cryptographic check
                validatedResponses.add(response);
            } catch (SignatureException e) {
                metrics.signatureValidationFailures().increment();
                log.warn("Signature validation failed: {} from {} - {}",
                    id, response.getSourceNode(), e.getMessage());
            }
        }

        if (validatedResponses.isEmpty()) {
            throw new IOException("No valid responses from quorum");
        }

        // Select from validated responses only
        return selectMajority(validatedResponses);
    }

    private void validateSignature(KeyEvent event) throws SignatureException {
        var publicKey = context.getMember(event.getSourceNode())
            .getPublicKey();
        var isValid = cryptoService.verify(
            event.getSignature(),
            event.getPayload(),
            publicKey);
        if (!isValid) {
            throw new SignatureException("Invalid signature on KERL event");
        }
    }
}
```

#### Validation Criteria

- [ ] All quorum responses validated before acceptance
- [ ] Invalid signatures rejected with appropriate logging
- [ ] Test: Forged signature detected and Byzantine node excluded
- [ ] Test: Correct majority selected from valid responses
- [ ] Metrics: signatureValidationFailures counter tracked
- [ ] Metrics: validatedResponseCount tracked
- [ ] Performance: <2% latency overhead from validation
- [ ] Compatibility: No regression with honest quorums

#### Related Components

- `DhtService`: Validation integration point
- `CryptoService`: Signature verification
- `Fireflies Context`: Public key lookup
- `KerlDhtMetrics`: Observability

---

### THOTH-BFT-003: Validation Components Not Integrated

**Status**: PENDING
**Severity**: CRITICAL
**Impact**: DHT operations bypass all validation, defeating KERI security model
**Phase**: 1
**Bead**: (To be created by strategic-planner)

#### Problem Description

The `Ani` (KERI validation) and `Maat` (witnessing) components exist but are not called in DHT store/retrieve flows. This means:

- KERL events stored without validation
- No verification that witness thresholds are met
- No checks for rotation sequence integrity
- No detection of fork attacks or equivocation

#### Root Cause Analysis

`Ani.java` and `Maat.java` define validation interfaces but:
```java
// Ani is never called during store/retrieve
public class DhtService {
    public void store(Digest id, KeyEvent event) {
        // MISSING: ani.validate(event)
        // MISSING: maat.verify(event)
        queryQuorum(successors);
        selectMajority(responses);
    }
}
```

#### Detection Strategy

**Test Scenario**: Invalid event (missing witness signature) stored and retrieved
```java
@Test
void testStore_WithInvalidEvent_RejectedByAni() {
    var cluster = TestCluster.create(3);
    var ani = cluster.getAni();

    // Create invalid event (missing witness signatures)
    var identifier = cluster.generateIdentifier();
    var invalidEvent = cluster.generateKeyEvent(identifier);
    // Remove witness signatures (INVALID!)

    // Attempt store: Should be rejected by Ani validation
    assertThatThrownBy(() -> cluster.store(identifier, invalidEvent))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("insufficient witness signatures");

    // Verify: Event not stored
    assertThatThrownBy(() -> cluster.retrieve(identifier))
        .isInstanceOf(IOException.class);
}
```

#### Fix Strategy

1. **Ani Integration**:
   - Call `ani.validate(event)` before store
   - Verify KERL sequence integrity
   - Verify witness thresholds
   - Reject invalid events with clear error

2. **Maat Integration**:
   - Call `maat.verify(event)` after store
   - Obtain witness receipts
   - Verify witness signatures meet threshold
   - Update validation status

3. **Validation Error Handling**:
   - Log validation failures at WARN level
   - Include event digest and failure reason
   - Count failures in metrics
   - Track validation success rate

#### Code Changes

**File**: `src/main/java/com/hellblazer/delos/thoth/grpc/dht/DhtService.java`
```java
public class DhtService {
    private final Ani ani;
    private final Maat maat;

    public void store(Digest id, KeyEvent event) throws IOException {
        // NEW: Validate with Ani before storing
        try {
            ani.validate(event);
        } catch (ValidationException e) {
            metrics.aniValidationFailures().increment();
            log.warn("Ani validation failed: {} - {}",
                event.digest(), e.getMessage());
            throw e;
        }
        metrics.aniValidationSuccess().increment();

        // Original store logic
        queryQuorum(successors);
        var majority = selectMajority(responses);

        // NEW: Verify with Maat after storing
        try {
            maat.verify(event, majority);
        } catch (VerificationException e) {
            metrics.maatVerificationFailures().increment();
            log.warn("Maat verification failed: {} - {}",
                event.digest(), e.getMessage());
            // Log but don't fail (witness receipts may arrive later)
        }
        metrics.maatWitnessCount().record(
            maat.getWitnessCount(event));
    }

    public KeyEvent retrieve(Digest id, long seq) throws IOException {
        List<KeyEvent> responses = queryQuorum(successors);

        // NEW: Validate retrieved events
        List<KeyEvent> validated = new ArrayList<>();
        for (var event : responses) {
            try {
                ani.validate(event);
                validated.add(event);
            } catch (ValidationException e) {
                metrics.aniValidationFailures().increment();
                log.warn("Retrieved event failed validation: {} - {}",
                    event.digest(), e.getMessage());
            }
        }

        if (validated.isEmpty()) {
            throw new IOException("No valid responses from quorum");
        }

        return selectMajority(validated);
    }
}
```

#### Validation Criteria

- [ ] Ani.validate() called before all store operations
- [ ] Maat.verify() called after store for witness verification
- [ ] Ani.validate() called on retrieved events
- [ ] Invalid events rejected with ValidationException
- [ ] Test: Invalid witness threshold rejected
- [ ] Test: Invalid rotation sequence rejected
- [ ] Metrics: aniValidationSuccess/Failures tracked
- [ ] Metrics: maatWitnessCount tracked
- [ ] Performance: <5% latency overhead from validation
- [ ] Backward compatibility: No regression with valid events

#### Related Components

- `DhtService`: Integration point for validation
- `Ani`: KERI event validation
- `Maat`: Witness verification
- `KerlDhtMetrics`: Observability

---

### THOTH-BFT-004: Metrics Interface Completely Empty

**Status**: PENDING
**Severity**: CRITICAL
**Impact**: Cannot monitor DHT health, Byzantine behavior, or performance in production
**Phase**: 1
**Bead**: (To be created by strategic-planner)

#### Problem Description

`KerlDhtMetrics` and `GorgoneionMetrics` interfaces are defined with empty stub methods. This means:

- No operational observability for DHT operations
- No metrics for Byzantine detection
- No latency tracking
- No error rate monitoring
- Cannot diagnose production issues

#### Root Cause Analysis

```java
// Current code: Empty stub methods
public interface KerlDhtMetrics {
    // All methods are empty stubs!
    default Timer storeLatency() { return null; }
    default Timer retrieveLatency() { return null; }
    // ... etc
}
```

#### Fix Strategy

1. **Implement KerlDhtMetrics**:
   - Latency tracking for store/retrieve/validate
   - Throughput counters
   - Error rates by category
   - Byzantine detection metrics

2. **Create NoOpMetrics** for testing:
   - Dummy implementation for unit tests
   - Zero overhead (compile-time optimization)

3. **Wire into DhtService**:
   - Inject metrics via constructor
   - Record all critical operations
   - <1% performance overhead

#### Code Changes

**File**: `src/main/java/com/hellblazer/delos/thoth/metrics/KerlDhtMetricsImpl.java` (NEW)
```java
public class KerlDhtMetricsImpl implements KerlDhtMetrics {
    private final Timer storeLatency;
    private final Timer retrieveLatency;
    private final Timer validateLatency;
    private final Counter storeErrors;
    private final Counter retrieveErrors;
    private final Counter byzantineDetections;
    private final Timer byzantineDetectionLatency;
    // ... etc

    public KerlDhtMetricsImpl(MeterRegistry registry) {
        this.storeLatency = Timer.builder("thoth.dht.store.latency_ms")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);
        // ... wire all metrics
    }

    @Override
    public Timer storeLatency() {
        return storeLatency;
    }
    // ... etc
}
```

**File**: `src/main/java/com/hellblazer/delos/thoth/grpc/dht/DhtService.java`
```java
public class DhtService {
    private final KerlDhtMetrics metrics;

    public void store(Digest id, KeyEvent event) throws IOException {
        var timer = metrics.storeLatency().start();
        try {
            // ... store logic
            timer.stop();
        } catch (Exception e) {
            metrics.storeErrorCount().increment();
            throw e;
        }
    }
}
```

#### Validation Criteria

- [ ] KerlDhtMetrics fully implemented (not stubs)
- [ ] GorgoneionMetrics fully implemented
- [ ] Metrics integrated into all critical paths
- [ ] <1% overhead verified
- [ ] Test: Metrics recorded on store/retrieve
- [ ] Test: Error metrics incremented on failures
- [ ] Test: Byzantine detection metrics updated
- [ ] Metrics exportable to Prometheus/Grafana

#### Related Components

- `KerlDhtMetrics`: Interface definition
- `KerlDhtMetricsImpl`: Implementation (NEW)
- `DhtService`: Integration point
- `MeterRegistry`: Dropwizard Metrics

---

## Risk Assessment

This section tracks architectural and operational risks identified during Byzantine fault tolerance remediation.

### HIGH: Metrics Overhead in Hot Path

**Impact**: Recording 19 metrics on every quorum response could degrade throughput by 2-5% in high-load scenarios
**Likelihood**: High (metrics are in critical path)
**Mitigation**:
- Benchmark before/after metrics implementation
- Use sampling if overhead exceeds 1% threshold
- Consider selective metric recording for non-critical paths
**Validation**:
- Performance test with metrics enabled vs disabled
- Verify throughput degradation <1% at p95
- Implement circuit breaker if overhead exceeds threshold
**Status**: Open
**Tracked**: Delos-gpx9 (Metrics implementation), Delos-c7g8 (Risk tracking)

---

### HIGH: Byzantine Provider False Positives

**Impact**: Transient network issues (partition, packet loss) could be misclassified as Byzantine behavior, leading to unnecessary node exclusion
**Likelihood**: Medium (network conditions vary in production)
**Mitigation**:
- Time-based decay of failure scores (implemented in `ThothByzantineStateProvider.java:240`)
- Distinguish timeout failures (weight 1) from validation failures (weight 3)
- Require sustained pattern (multiple failures) before Byzantine classification
- Configurable detection thresholds per environment
**Validation**:
- Chaos engineering test with network partition injection
- Verify transient partition doesn't trigger Byzantine classification
- Verify sustained Byzantine pattern is detected within SLA (100ms)
**Status**: Open
**Tracked**: Delos-c7g8 (Risk tracking)

**Code Reference**:
```java
// ThothByzantineStateProvider.java:240
// Time-based decay prevents false positives from transient issues
private void decayFailureScores(Duration timeSinceLastUpdate) {
    memberFailures.replaceAll((member, score) ->
        score * Math.exp(-timeSinceLastUpdate.toMillis() / DECAY_HALF_LIFE_MS));
}
```

---

### MEDIUM: Coordinator Integration Failure

**Impact**: `ByzantineIntelligenceCoordinator` may reject provider signals if schema mismatch or version incompatibility occurs during integration
**Likelihood**: Medium (coordinator integration is Phase 2 dependency)
**Mitigation**:
- Integration test planned in Phase 1 (Delos-doec dependency)
- Version compatibility check during provider registration
- Provider signals use versioned schema with backward compatibility
- Graceful degradation if coordinator unavailable
**Validation**:
- Test with coordinator version matrix (v1.0, v1.1, v2.0)
- Verify provider registration rejects incompatible versions
- Verify Byzantine detection continues if coordinator offline
**Status**: Open (blocked on Delos-doec)
**Tracked**: Delos-doec (Coordinator integration), Delos-c7g8 (Risk tracking)

---

### MEDIUM: Circular Dependency Risk

**Impact**: Initialization deadlock between `ThothByzantineStateProvider` (depends on `KerlDHT`) and `KerlDHT` (depends on provider for recording)
**Likelihood**: Low (careful initialization order mitigates)
**Mitigation**:
- Careful constructor ordering: provider instantiated before KERL
- Lazy initialization of provider in KERL if needed
- Dependency injection framework handles initialization graph
- Unit test verifies initialization order
**Validation**:
- Unit test with multiple initialization sequences
- Integration test with cold start scenarios
- Verify no deadlock under concurrent initialization
**Status**: Open
**Tracked**: Delos-c7g8 (Risk tracking)

**Code Pattern**:
```java
// Safe initialization order
public class ThothService {
    public ThothService(Context context, KerlDhtMetrics metrics) {
        // 1. Create provider first (no KERL dependency)
        this.byzantineProvider = new ThothByzantineStateProvider(context);

        // 2. Create KERL DHT (can safely use provider)
        this.kerlDht = new KerlDHT(context, metrics, byzantineProvider);
    }
}
```

---

## Significant Issues (Fix in Phase 2)

### THOTH-ENH-001: Multisig Support Incomplete

**Status**: PENDING
**Severity**: HIGH
**Impact**: Inconsistent Byzantine fault tolerance guarantees
**Phase**: 2

See `.pm/significant-issues/THOTH-ENH-001.md` for full details.

### THOTH-ENH-002: Error Handling Inconsistencies

**Status**: PENDING
**Severity**: HIGH
**Impact**: Difficult to debug failures, cascading timeouts
**Phase**: 2

### THOTH-ENH-003: Resource Management Minimal

**Status**: PENDING
**Severity**: HIGH
**Impact**: Memory leaks, thread exhaustion in production
**Phase**: 2

### THOTH-ENH-004: No Fork Attack Detection

**Status**: PENDING
**Severity**: HIGH
**Impact**: Split-brain KERL inconsistencies
**Phase**: 2

---

## Issue Resolution Statistics

| Category | Total | Pending | In Progress | Fixed |
|----------|-------|---------|-------------|-------|
| Critical | 4 | 4 | 0 | 0 |
| Risks | 4 | 4 | 0 | 0 |
| Significant | 4 | 4 | 0 | 0 |
| **Total** | **12** | **12** | **0** | **0** |

---

## Next Steps

1. **Strategic Planning**: strategic-planner creates beads for Phase 1 critical issues
2. **Phase 1 Execution**: 2-week push to fix all 4 critical issues
3. **Validation**: Byzantine FT tests, metrics overhead verification
4. **Phase 2**: Address significant issues and scalability
5. **Phase 3**: Integration and production readiness

---

**Last Updated**: 2026-02-13
**Status**: Infrastructure initialization, awaiting strategic planning
