# Developer Guide: Adding Byzantine Detection Layers

This guide explains how to add new protocol layers to the cross-layer Byzantine detection system.

## Overview

The Byzantine Intelligence Coordinator aggregates anomaly signals from multiple layers via the `ByzantineStateProvider` interface. Each layer is responsible for detecting anomalies within its domain and exposing them through this interface.

## Implementing ByzantineStateProvider

### Interface Definition

```java
public interface ByzantineStateProvider {
    /**
     * Get the unique name for this layer.
     */
    String getLayerName();

    /**
     * Get anomaly states for all tracked members.
     * Called during each poll cycle.
     */
    Map<Identifier, LayerAnomalyState> getMemberAnomalyStates();

    /**
     * Get anomaly state for a specific member.
     * Used for targeted queries.
     */
    Optional<LayerAnomalyState> getMemberState(Identifier memberId);

    /**
     * Get count of members being tracked.
     */
    int getTrackedMemberCount();

    /**
     * Reset all tracking state.
     * Called on view changes.
     */
    void reset();
}
```

### LayerAnomalyState

Each member's anomaly state is represented by:

```java
public record LayerAnomalyState(
    String layerName,           // Layer that produced this state
    double anomalyScore,        // Score in range [0.0, 1.0]
    Instant detectedAt,         // When anomaly was detected
    List<String> activeSignals, // Signal types contributing to score
    String summary              // Human-readable description
) {
    public boolean hasActiveSignals() {
        return activeSignals != null && !activeSignals.isEmpty();
    }
}
```

### Example Implementation

Here's a complete example for a hypothetical "Consensus" layer:

```java
public class ConsensusStateProvider implements ByzantineStateProvider {

    private static final String LAYER_NAME = "CONSENSUS";

    private final ConcurrentHashMap<Identifier, MemberState> memberStates;
    private final Clock clock;

    public ConsensusStateProvider(Clock clock) {
        this.memberStates = new ConcurrentHashMap<>();
        this.clock = clock;
    }

    @Override
    public String getLayerName() {
        return LAYER_NAME;
    }

    @Override
    public Map<Identifier, LayerAnomalyState> getMemberAnomalyStates() {
        var now = clock.instant();
        var result = new HashMap<Identifier, LayerAnomalyState>();

        for (var entry : memberStates.entrySet()) {
            var state = entry.getValue();
            if (state.hasAnomalies()) {
                result.put(entry.getKey(), state.toLayerState(LAYER_NAME, now));
            }
        }

        return Collections.unmodifiableMap(result);
    }

    @Override
    public Optional<LayerAnomalyState> getMemberState(Identifier memberId) {
        var state = memberStates.get(memberId);
        if (state == null || !state.hasAnomalies()) {
            return Optional.empty();
        }
        return Optional.of(state.toLayerState(LAYER_NAME, clock.instant()));
    }

    @Override
    public int getTrackedMemberCount() {
        return (int) memberStates.values().stream()
            .filter(MemberState::hasAnomalies)
            .count();
    }

    @Override
    public void reset() {
        memberStates.clear();
    }

    // Layer-specific detection methods

    public void recordEquivocation(Identifier memberId, byte[] proof) {
        memberStates.compute(memberId, (id, state) -> {
            if (state == null) state = new MemberState();
            state.recordEquivocation(proof);
            return state;
        });
    }

    public void recordTimingViolation(Identifier memberId, Duration delay) {
        memberStates.compute(memberId, (id, state) -> {
            if (state == null) state = new MemberState();
            state.recordTimingViolation(delay);
            return state;
        });
    }

    // Internal state tracking
    private static class MemberState {
        private int equivocationCount = 0;
        private int timingViolations = 0;
        private Instant lastViolation;

        void recordEquivocation(byte[] proof) {
            equivocationCount++;
            lastViolation = Instant.now();
        }

        void recordTimingViolation(Duration delay) {
            timingViolations++;
            lastViolation = Instant.now();
        }

        boolean hasAnomalies() {
            return equivocationCount > 0 || timingViolations > 2;
        }

        LayerAnomalyState toLayerState(String layerName, Instant now) {
            var signals = new ArrayList<String>();
            double score = 0.0;

            if (equivocationCount > 0) {
                signals.add("EQUIVOCATION");
                score = Math.min(1.0, 0.9 + (equivocationCount - 1) * 0.05);
            }

            if (timingViolations > 2) {
                signals.add("TIMING_VIOLATION");
                score = Math.max(score, Math.min(1.0, timingViolations * 0.1));
            }

            return new LayerAnomalyState(
                layerName,
                score,
                lastViolation,
                signals,
                String.format("Equivocations: %d, Timing: %d",
                    equivocationCount, timingViolations)
            );
        }
    }
}
```

## Scoring Guidelines

### Score Ranges

| Score Range | Meaning | Example Signals |
|-------------|---------|-----------------|
| 0.0 - 0.2 | Minor anomaly | Occasional slow response |
| 0.2 - 0.5 | Moderate concern | Repeated delays, minor protocol violations |
| 0.5 - 0.7 | Significant anomaly | Quorum failures, rate anomalies |
| 0.7 - 0.9 | Strong Byzantine indicator | Equivocation detected |
| 0.9 - 1.0 | Definite Byzantine | Signature forgery, proof of misbehavior |

### Score Calculation Best Practices

1. **Use consistent ranges** - Map your signals to the ranges above
2. **Accumulate evidence** - Multiple violations should increase score
3. **Cap at 1.0** - Never exceed the maximum score
4. **Consider severity** - Equivocation (0.9) is worse than delay (0.2)

```java
// Good: Accumulating evidence
double score = Math.min(1.0, baseScore + (violationCount - 1) * 0.1);

// Good: Severity-based
double score = switch (violationType) {
    case EQUIVOCATION -> 0.9;
    case SIGNATURE_FAILURE -> 1.0;
    case SLOW_RESPONSE -> 0.2;
    case RATE_ANOMALY -> 0.4;
};
```

## Registering with the Coordinator

### At Startup

```java
// Create your provider
var consensusProvider = new ConsensusStateProvider(Clock.systemUTC());

// Create coordinator with all providers
var coordinator = new ByzantineIntelligenceCoordinator(
    config,
    List.of(
        firefliesProvider,
        thothProvider,
        gorgoneionProvider,
        consensusProvider  // Your new provider
    ),
    responseHandler,
    metrics,
    scheduler
);
```

### Configure Layer Weight

Add your layer to the configuration:

```java
IntelligenceConfig.builder()
    .layerWeights(Map.of(
        "FIREFLIES", 0.35,
        "ETHEREAL", 0.25,
        "THOTH", 0.15,
        "GORGONEION", 0.10,
        "CONSENSUS", 0.15  // Your new layer
    ))
    .layerPollIntervals(Map.of(
        // ... existing intervals
        "CONSENSUS", Duration.ofSeconds(3)  // Poll interval for your layer
    ))
    .build()
```

## Testing Your Provider

### Unit Testing

```java
@Test
void shouldDetectEquivocation() {
    var provider = new ConsensusStateProvider(Clock.systemUTC());
    var memberId = createTestMember("test-1");

    // Record anomaly
    provider.recordEquivocation(memberId, new byte[]{1, 2, 3});

    // Verify state
    var state = provider.getMemberState(memberId);
    assertThat(state).isPresent();
    assertThat(state.get().anomalyScore()).isGreaterThanOrEqualTo(0.9);
    assertThat(state.get().activeSignals()).contains("EQUIVOCATION");
}

@Test
void shouldResetState() {
    var provider = new ConsensusStateProvider(Clock.systemUTC());
    var memberId = createTestMember("test-1");

    provider.recordEquivocation(memberId, new byte[]{1, 2, 3});
    assertThat(provider.getTrackedMemberCount()).isEqualTo(1);

    provider.reset();
    assertThat(provider.getTrackedMemberCount()).isZero();
}
```

### Integration Testing

Use the test harness to verify your provider integrates correctly:

```java
@Test
void shouldIntegrateWithCoordinator() {
    var harness = ByzantineTestHarness.builder()
        .withLayers("FIREFLIES", "CONSENSUS")  // Include your layer
        .build();

    var member = harness.createMember("test-member");

    // Inject fault on your layer
    harness.injectFault("CONSENSUS", member, FaultType.EQUIVOCATION);

    // Evaluate
    var result = harness.evaluate(member);

    assertThat(result.isAnomalous()).isTrue();
    assertThat(result.layerScores()).containsKey("CONSENSUS");
}
```

## Thread Safety Requirements

Your provider will be called from the coordinator's polling thread. Ensure:

1. **Thread-safe collections** - Use `ConcurrentHashMap` for member state
2. **Atomic updates** - Use `compute()` or `merge()` for state updates
3. **Immutable returns** - Return unmodifiable collections from getMemberAnomalyStates()

```java
// Good: Thread-safe update
memberStates.compute(memberId, (id, state) -> {
    if (state == null) state = new MemberState();
    state.update();
    return state;
});

// Good: Immutable return
return Collections.unmodifiableMap(result);
```

## Performance Considerations

1. **Minimize getMemberAnomalyStates() cost** - This is called every poll cycle
2. **Filter inactive members** - Only return members with actual anomalies
3. **Avoid blocking operations** - Don't do I/O in provider methods
4. **Consider caching** - Cache expensive calculations if signal data is stable

```java
// Good: Only return anomalous members
for (var entry : memberStates.entrySet()) {
    if (entry.getValue().hasAnomalies()) {  // Filter first
        result.put(entry.getKey(), entry.getValue().toLayerState());
    }
}
```

## Checklist

Before deploying your new layer:

- [ ] Implements all `ByzantineStateProvider` methods
- [ ] Returns scores in valid range [0.0, 1.0]
- [ ] Thread-safe implementation
- [ ] Unit tests for all detection scenarios
- [ ] Integration test with coordinator
- [ ] Configured layer weight in `IntelligenceConfig`
- [ ] Configured poll interval if different from default
- [ ] Documentation updated

## Related Documentation

- [ADR-007: Cross-Layer Byzantine Detection](../adr/007-cross-layer-byzantine-detection.md)
- [Operations Guide](../operations/byzantine-detection-operations.md)
- [Weight Tuning Methodology](../operations/weight-tuning-methodology.md)
