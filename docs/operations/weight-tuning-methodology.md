# Byzantine Detection Weight Tuning Methodology

This document describes the methodology for tuning the Byzantine detection system's layer weights and thresholds to achieve target detection rates.

## Overview

The Byzantine Intelligence Coordinator aggregates anomaly signals from multiple layers (FIREFLIES, ETHEREAL, THOTH, GORGONEION) using weighted averaging. Proper tuning ensures:

- **High detection rate**: Correctly identifies Byzantine behavior (≥95%)
- **Low false positive rate**: Avoids flagging honest members (≤10%)
- **Multi-layer correlation**: Reduces noise through cross-layer validation (≥30% FP reduction)

## Baseline Configuration

Start with these baseline weights based on signal quality and reliability:

| Layer | Weight | Rationale |
|-------|--------|-----------|
| FIREFLIES | 0.4 | Core membership layer; high signal quality from gossip and message verification |
| ETHEREAL | 0.3 | Consensus layer; strong equivocation and timing anomaly detection |
| THOTH | 0.2 | DHT layer; detects quorum failures and key resolution anomalies |
| GORGONEION | 0.1 | Identity layer; attestation failures, lower frequency signals |

### Threshold Guidelines

| Threshold | Default | Purpose |
|-----------|---------|---------|
| Warning | 0.15 | Initial investigation trigger; catches subtle anomalies |
| Critical | 0.6 | Immediate response trigger; high-confidence Byzantine detection |

## Tuning Process

### Step 1: Establish Baseline Performance

Run the validation test suite with baseline configuration:

```bash
./mvnw test -pl memberships -Dtest=WeightTuningValidationTest
```

Collect metrics:
- False positive rate (FPR)
- True positive rate (TPR / detection rate)
- Per-fault-type detection rates

### Step 2: Adjust for Detection Rate

If detection rate < 95%:

1. **Lower warning threshold**: Reduces the score needed to trigger detection
   - Start by lowering in 0.05 increments
   - Monitor false positive rate as you lower

2. **Increase layer weights for high-signal layers**:
   ```java
   .layerWeights(Map.of(
       LAYER_FIREFLIES, 0.5,  // Increased from 0.4
       LAYER_ETHEREAL, 0.35,  // Increased from 0.3
       ...
   ))
   ```

3. **Check fault type scores**: Some fault types produce lower scores
   - DELAY: 0.2 (subtle anomaly)
   - CRASH: 0.3 (definite but not critical)
   - EQUIVOCATION: 0.9 (strong Byzantine indicator)

### Step 3: Adjust for False Positive Rate

If false positive rate > 10%:

1. **Raise warning threshold**: Requires higher score to trigger
   - Increase in 0.05 increments
   - Monitor detection rate as you raise

2. **Increase decay rate**: Reduces influence of stale signals
   ```java
   .scoreDecayRate(0.98)  // Faster decay from default 0.95
   ```

3. **Reduce weights on noisy layers**: If a specific layer generates false signals
   ```java
   .layerWeights(Map.of(
       LAYER_THOTH, 0.15,  // Reduced if generating noise
       ...
   ))
   ```

### Step 4: Validate on Multiple Scenarios

Test the tuned configuration on these scenarios:

| Scenario | Fault Type | Expected Detection |
|----------|------------|-------------------|
| Crash failure | CRASH | ≥95% |
| Slow node | DELAY | ≥95% |
| Byzantine equivocation | EQUIVOCATION | ≥99% |
| Mixed faults | Multiple | ≥95% |
| Normal operation | None | ≤10% FP |

### Step 5: Document Final Configuration

Record the final configuration with justification:

```java
IntelligenceConfig.builder()
    .defaultPollInterval(Duration.ofSeconds(5))
    .warningThreshold(0.15)      // Rationale: catches DELAY (0.2) faults
    .criticalThreshold(0.6)      // Rationale: EQUIVOCATION (0.9) triggers critical
    .layerWeights(Map.of(
        LAYER_FIREFLIES, 0.4,    // Primary membership signals
        LAYER_ETHEREAL, 0.3,     // Consensus verification
        LAYER_THOTH, 0.2,        // DHT coordination
        LAYER_GORGONEION, 0.1    // Identity attestation
    ))
    .responseCooldown(Duration.ofSeconds(15))
    .scoreDecayRate(0.95)
    .maxResponsesPerInterval(10)
    .signalDeduplicationWindow(Duration.ofSeconds(30))
    .build()
```

## Quantitative Targets

The tuned configuration must meet these targets:

| Metric | Target | Test Method |
|--------|--------|-------------|
| False Positive Rate | ≤10% | Normal load simulation (100+ members) |
| Byzantine Detection Rate | ≥95% | Single-Byzantine fault injection |
| Multi-layer Improvement | ≥30% FP reduction | Compare single vs multi-layer |
| CRASH detection | ≥95% | Crash fault injection |
| DELAY detection | ≥95% | Delay fault injection |
| EQUIVOCATION detection | ≥99% | Equivocation fault injection |

## Validation Test Suite

The `WeightTuningValidationTest` class provides automated validation:

```java
// Validates FPR ≤ 10%
@Test void falsePositiveRateBelowThreshold()

// Validates detection rate ≥ 95%
@Test void byzantineDetectionRateAboveThreshold()

// Validates multi-layer improvement ≥ 30%
@Test void multiLayerCorrelationReducesFalsePositives()

// Per-fault-type validation
@Test void tuningValidatedOnCrashFaults()
@Test void tuningValidatedOnDelayFaults()
@Test void tuningValidatedOnEquivocationFaults()
```

Run validation after any configuration changes:

```bash
./mvnw test -pl memberships -Dtest=WeightTuningValidationTest
```

## Troubleshooting

### Low Detection Rate

1. Check if warning threshold is too high for fault type scores
2. Verify layer injectors are producing expected anomaly scores
3. Review weighted averaging calculation

### High False Positive Rate

1. Check for noisy layers generating spurious signals
2. Verify normal members have score 0.0 (no active faults)
3. Review decay rate - scores should decay between evaluations

### Inconsistent Results

1. Use deterministic testing (fixed clock, seeded entropy)
2. Verify test harness configuration matches production config
3. Check for race conditions in multi-threaded scenarios

## Related Documentation

- [Byzantine Intelligence Coordinator](../../memberships/src/main/java/com/hellblazer/delos/membership/byzantine/ByzantineIntelligenceCoordinator.java)
- [Intelligence Config](../../memberships/src/main/java/com/hellblazer/delos/membership/byzantine/IntelligenceConfig.java)
- [Validation Tests](../../memberships/src/test/java/com/hellblazer/delos/membership/byzantine/testing/WeightTuningValidationTest.java)
