# ADR-007: Cross-Layer Byzantine Detection Architecture

## Status

Accepted

## Context

Delos is a multi-tenant distributed system platform requiring Byzantine fault tolerance. Individual protocol layers (Fireflies, Ethereal, Thoth, Gorgoneion) each have mechanisms to detect anomalous behavior, but these operate independently without correlation.

Byzantine actors may exhibit subtle patterns that are difficult to detect from a single layer's perspective but become clear when signals are correlated across layers. We needed an architecture to aggregate and correlate these signals for improved detection accuracy.

### Key Requirements

1. **Cross-layer signal aggregation** - Combine signals from multiple protocol layers
2. **Configurable detection thresholds** - Support tuning for different deployment scenarios
3. **Low false positive rate** - Avoid incorrectly flagging honest members (≤10%)
4. **High detection rate** - Correctly identify Byzantine behavior (≥95%)
5. **Anti-feedback mechanisms** - Prevent detection-response amplification loops
6. **Async response handling** - Non-blocking response execution

### Architectural Options Considered

#### Option A: Push-Based Event Streaming

Each layer pushes anomaly events to a central coordinator in real-time.

**Pros:**
- Immediate detection of anomalies
- Natural event-driven architecture

**Cons:**
- Requires reliable event delivery infrastructure
- Complex backpressure handling
- Risk of event storms during attacks
- Tight coupling between layers and coordinator

#### Option B: Pull-Based Polling (Selected)

A central coordinator periodically polls each layer for current anomaly state.

**Pros:**
- Simpler implementation with existing APIs
- Natural rate limiting via poll interval
- Decoupled architecture - layers don't need coordinator knowledge
- Graceful degradation if coordinator is slow
- Easier testing with deterministic timing

**Cons:**
- Detection latency bounded by poll interval
- Slightly higher baseline resource usage

#### Option C: Hybrid Push-Pull

Layers push critical events immediately, coordinator polls for comprehensive state.

**Pros:**
- Low latency for critical events
- Complete state via polling

**Cons:**
- Complexity of two communication patterns
- Harder to test and reason about

## Decision

We chose **Option B: Pull-Based Polling** for the following reasons:

1. **Simplicity** - Single communication pattern is easier to implement, test, and debug
2. **Natural Rate Limiting** - Poll intervals inherently prevent event storms
3. **Decoupling** - Layers don't need to know about the coordinator
4. **Testability** - Deterministic timing enables reliable testing
5. **Anti-Feedback** - Polling interval provides natural dampening

The detection latency tradeoff is acceptable because:
- Byzantine behavior is typically persistent, not instantaneous
- Per-layer poll intervals can be tuned (ETHEREAL: 2s, FIREFLIES: 5s, THOTH: 10s)
- Critical consensus violations are still handled by layer-specific mechanisms

## Architecture

### Component Overview

```
┌─────────────────────────────────────────────────────────────┐
│                ByzantineIntelligenceCoordinator             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │ Layer       │  │ Score       │  │ Response            │  │
│  │ Pollers     │  │ Aggregator  │  │ Handler             │  │
│  └──────┬──────┘  └──────┬──────┘  └──────────┬──────────┘  │
└─────────┼────────────────┼────────────────────┼─────────────┘
          │                │                    │
          ▼                ▼                    ▼
┌─────────────────┐ ┌─────────────────┐ ┌─────────────────────┐
│ ByzantineState  │ │ MemberRisk      │ │ FirefliesResponse   │
│ Providers       │ │ Profiles        │ │ Handler (shunning)  │
└─────────────────┘ └─────────────────┘ └─────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────┐
│  Fireflies  │  Ethereal  │  Thoth  │  Gorgoneion           │
│  Provider   │  Provider  │ Provider│  Provider             │
└─────────────────────────────────────────────────────────────┘
```

### Key Interfaces

```java
// Layer providers implement this to expose anomaly state
public interface ByzantineStateProvider {
    String getLayerName();
    Map<Identifier, LayerAnomalyState> getMemberAnomalyStates();
    Optional<LayerAnomalyState> getMemberState(Identifier memberId);
}

// Response handlers implement this for async actions
public interface ResponseHandler {
    CompletableFuture<Boolean> handleCritical(Identifier memberId, MemberRiskProfile profile);
    CompletableFuture<Void> handleWarning(Identifier memberId, MemberRiskProfile profile);
}
```

### Weighted Score Aggregation

Each layer contributes to a member's composite score based on configured weights:

| Layer | Weight | Rationale |
|-------|--------|-----------|
| FIREFLIES | 0.4 | Core membership, high signal quality |
| ETHEREAL | 0.3 | Consensus layer, strong equivocation detection |
| THOTH | 0.2 | DHT layer, quorum failure detection |
| GORGONEION | 0.1 | Identity layer, attestation failures |

Composite score = Σ(layer_score × layer_weight) / Σ(layer_weight)

## Thoth DHT Integration

### ThothByzantineStateProvider Implementation

The Thoth DHT layer implements `ByzantineStateProvider` via `ThothByzantineStateProvider`, which tracks five categories of Byzantine signals:

| Signal Type | Weight | Detection Scenario | Recording Point |
|-------------|--------|-------------------|-----------------|
| Validation Failures | 3 | Invalid KERI signatures, out-of-sequence events, equivocation | Post-quorum validation pipeline |
| Signature Failures | 3 | Response signature verification failures during DHT reads | Response handler after quorum |
| Quorum Failures | 2 | Member fails to participate in DHT quorum operations | Quorum response tracking |
| Timeouts | 1 | Operations consistently timeout for a member | Operation timeout handler |
| Connection Failures | 1 | Connection failures before member identification | Connection establishment |

**Per-member anomaly score calculation:**
```
weighted_score = (validation_failures × 3) + (signature_failures × 3)
               + (quorum_failures × 2) + (timeouts × 1) + (connection_failures × 1)
anomaly_score = min(1.0, weighted_score / 10)
```

### Integration with KerlDHT Lifecycle

**Registration (startup):**
```java
// KerlDHT.start() - line 1257
if (byzantineCoordinator != null) {
    byzantineCoordinator.registerProvider(byzantineProvider);
}
```

**Deregistration (shutdown):**
```java
// KerlDHT.stop() - line 1279-1283
if (byzantineCoordinator != null) {
    // Coordinator uses CopyOnWriteArrayList for providers
    // No explicit deregister - provider lifecycle tied to DHT
    byzantineProvider.reset();  // Clear accumulated state
}
```

### Signal Recording Integration Points

**1. Validation Pipeline (DhtValidationPipeline)**

Post-quorum validation failures are recorded during the ordered validation pipeline:

```
Quorum Response → Ani (KERI validation) → Maat (Signature verification) → DHT write
                       ↓ failures                ↓ failures
                 byzantineProvider.recordValidationFailure()
                                          byzantineProvider.recordSignatureFailure()
```

**2. Quorum Failure Detection**

Members failing to participate in quorum operations are tracked:
```java
// KerlDHT quorum handler - line 1380
if (failedMajority) {
    providers.forEach(p -> byzantineProvider.recordQuorumFailure(p.getId()));
}
```

**3. Timeout Detection**

Operations timing out trigger timeout recording:
```java
// KerlDHT operation timeout - line 1490
byzantineProvider.recordTimeout(destination.getMember().getId());
```

**4. Connection Failures**

Connection failures before remote member identification:
```java
// KerlDHT connection handler - line 1497
byzantineProvider.recordConnectionFailure(member.getId(), context);
```

### Failure Expiry and State Management

- **Expiry Duration**: 15 minutes (configurable via constructor)
- **State Tracking**: ConcurrentHashMap per member, thread-safe for concurrent DHT operations
- **Signal History**: Last 5 signals per member retained for debugging
- **Cleanup**: Expired entries removed during `getMemberAnomalyStates()` polling

### Validation Pipeline Architecture

The Thoth validation pipeline implements ordered validation with Byzantine signal recording:

```
┌──────────────────────────────────────────────────────────────────┐
│                     DHT Read/Write Operation                      │
└───────────────────────────────┬──────────────────────────────────┘
                                │
                                ▼
                        ┌───────────────┐
                        │ Quorum Phase  │
                        │ (3 of 5 ring) │
                        └───────┬───────┘
                                │
                                ▼
                    ┌───────────────────────┐
                    │ Validation Pipeline   │
                    │ (if quorum achieved)  │
                    └───────────┬───────────┘
                                │
        ┌───────────────────────┼───────────────────────┐
        │                       │                       │
        ▼                       ▼                       ▼
   ┌────────┐            ┌──────────┐          ┌──────────────┐
   │  Ani   │            │   Maat   │          │  DHT Write   │
   │ (KERI) │────────────│ (BLS Sig)│──────────│ (if valid)   │
   └────┬───┘  success   └─────┬────┘ success  └──────────────┘
        │                      │
        │ failure              │ failure
        ▼                      ▼
   recordValidation       recordSignature
      Failure()               Failure()
        │                      │
        └──────────┬───────────┘
                   │
                   ▼
         ThothByzantineStateProvider
                   │
                   ▼
      ByzantineIntelligenceCoordinator
           (poll-based aggregation)
```

### Testing and Validation

Thoth Byzantine integration is validated through:
- **Unit tests**: `ThothByzantineStateProviderTest.java` (15 tests)
- **Integration tests**: `ByzantineDetectionIntegrationTest.java` (5 E2E scenarios)
- **Functional tests**: `ByzantineFaultToleranceTest.java` (11 fault injection tests)

### Anti-Feedback Mechanisms

To prevent detection-response amplification:

1. **Rate Limiting** - Maximum responses per evaluation interval
2. **Per-Member Cooldown** - Minimum time between responses for same member
3. **Signal Deduplication** - Track seen signal fingerprints within time window
4. **Score Decay** - Reduce influence of stale signals over time

## Consequences

### Positive

- Clean separation of concerns between layers and coordinator
- Easily testable with deterministic timing and seeded entropy
- Tunable per-layer poll intervals and weights
- Natural rate limiting prevents runaway escalation
- Async response handling doesn't block detection

### Negative

- Detection latency bounded by poll interval (mitigated by per-layer tuning)
- Requires periodic polling even when no anomalies exist (low overhead)

### Neutral

- Layers must implement ByzantineStateProvider interface
- Configuration requires tuning for specific deployments

## Performance Characteristics

| Metric | Target | Achieved |
|--------|--------|----------|
| Poll cycle latency | <100ms for 100 members | ✓ |
| False positive rate | ≤10% | ✓ |
| Detection rate | ≥95% | ✓ |
| Memory per member | <500 bytes | ✓ |

## References

- [IntelligenceConfig.java](../../memberships/src/main/java/com/hellblazer/delos/membership/byzantine/IntelligenceConfig.java)
- [ByzantineIntelligenceCoordinator.java](../../memberships/src/main/java/com/hellblazer/delos/membership/byzantine/ByzantineIntelligenceCoordinator.java)
- [ThothByzantineStateProvider.java](../../thoth/src/main/java/com/hellblazer/delos/thoth/ThothByzantineStateProvider.java)
- [DhtValidationPipeline.java](../../thoth/src/main/java/com/hellblazer/delos/thoth/DhtValidationPipeline.java)
- [ADR-0013: Thoth Byzantine Fault Tolerance](0013-thoth-byzantine-fault-tolerance.md)
- [Weight Tuning Methodology](../operations/weight-tuning-methodology.md)
