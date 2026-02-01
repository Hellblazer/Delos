# Byzantine Detection Operations Guide

This guide covers configuration, monitoring, and operational procedures for the cross-layer Byzantine detection system.

## Overview

The Byzantine Intelligence Coordinator aggregates anomaly signals from multiple protocol layers to detect Byzantine behavior. It uses a pull-based polling architecture with configurable thresholds and weights.

## Configuration

### IntelligenceConfig Parameters

```java
IntelligenceConfig.builder()
    // Polling
    .defaultPollInterval(Duration.ofSeconds(5))
    .layerPollIntervals(Map.of(
        "ETHEREAL", Duration.ofSeconds(2),    // Consensus - time critical
        "FIREFLIES", Duration.ofSeconds(5),   // Membership - moderate
        "THOTH", Duration.ofSeconds(10),      // DHT - slower
        "GORGONEION", Duration.ofSeconds(10)  // Identity - infrequent
    ))

    // Thresholds
    .warningThreshold(0.15)   // Triggers investigation
    .criticalThreshold(0.6)   // Triggers immediate response (shunning)

    // Layer weights
    .layerWeights(Map.of(
        "FIREFLIES", 0.4,
        "ETHEREAL", 0.3,
        "THOTH", 0.2,
        "GORGONEION", 0.1
    ))

    // Anti-feedback
    .responseCooldown(Duration.ofSeconds(15))
    .maxResponsesPerInterval(10)
    .signalDeduplicationWindow(Duration.ofSeconds(30))

    // Score management
    .scoreDecayRate(0.95)  // 5% decay per evaluation cycle
    .build()
```

### Configuration Guidelines

| Parameter | Conservative | Balanced | Aggressive |
|-----------|--------------|----------|------------|
| warningThreshold | 0.3 | 0.15 | 0.10 |
| criticalThreshold | 0.8 | 0.6 | 0.5 |
| responseCooldown | 30s | 15s | 5s |
| maxResponsesPerInterval | 5 | 10 | 20 |
| scoreDecayRate | 0.98 | 0.95 | 0.90 |

**Conservative**: Higher thresholds, slower responses. Use for stable networks.
**Balanced**: Default settings. Good for most deployments.
**Aggressive**: Lower thresholds, faster responses. Use when Byzantine attacks are likely.

## Monitoring

### Key Metrics

The system exposes Dropwizard metrics via `ByzantineIntelligenceMetrics`:

| Metric | Type | Description |
|--------|------|-------------|
| `byzantine.layer.poll.duration` | Timer | Time to poll each layer |
| `byzantine.evaluation.cycle.duration` | Timer | Full evaluation cycle time |
| `byzantine.score.distribution` | Histogram | Distribution of aggregated scores |
| `byzantine.detections.warning` | Counter | Warning-level detections |
| `byzantine.detections.critical` | Counter | Critical-level detections |
| `byzantine.responses.triggered` | Meter | Response actions triggered |
| `byzantine.responses.pending` | Counter | In-flight async responses |
| `byzantine.responses.failed` | Counter | Failed response actions |
| `byzantine.cooldown.skips` | Counter | Responses skipped due to cooldown |
| `byzantine.ratelimit.skips` | Counter | Responses skipped due to rate limit |
| `byzantine.signals.deduplicated` | Counter | Deduplicated signals |
| `byzantine.provider.errors` | Counter | Provider polling errors |

### Alerting Thresholds

| Condition | Severity | Action |
|-----------|----------|--------|
| `critical_detections/min > 5` | Warning | Investigate potential attack |
| `critical_detections/min > 20` | Critical | Possible coordinated attack |
| `failed_responses/total > 10%` | Warning | Check response handler health |
| `ratelimit_skips/min > 50` | Warning | Review rate limit configuration |
| `provider_errors/min > 10` | Critical | Check layer provider health |

### Dashboard Queries

**Detection Rate (Prometheus)**:
```promql
rate(byzantine_detections_critical_total[5m])
```

**False Positive Estimate**:
```promql
rate(byzantine_detections_warning_total[5m]) / rate(byzantine_members_tracked[5m])
```

**Response Success Rate**:
```promql
1 - (byzantine_responses_failed_total / byzantine_responses_triggered_total)
```

## Operational Procedures

### Starting the Coordinator

```java
var config = IntelligenceConfig.defaults();
var metrics = new ByzantineIntelligenceMetricsImpl(registry, "node-1");
var responseHandler = new FirefliesResponseHandler(view);

var coordinator = new ByzantineIntelligenceCoordinator(
    config,
    List.of(firefliesProvider, thothProvider, gorgoneionProvider),
    responseHandler,
    metrics,
    scheduler
);

coordinator.start();
```

### Graceful Shutdown

```java
// Stop accepting new evaluations
coordinator.stop();

// Wait for pending responses (with timeout)
coordinator.awaitPendingResponses(Duration.ofSeconds(30));
```

### View Change Handling

On view change, reset detection state to avoid stale data:

```java
coordinator.reset();  // Clears all member profiles and scores
```

### Runtime Reconfiguration

To update thresholds without restart:

```java
var newConfig = IntelligenceConfig.builder()
    .warningThreshold(0.20)  // Raised from 0.15
    .criticalThreshold(0.7)  // Raised from 0.6
    // ... other settings
    .build();

coordinator.reconfigure(newConfig);
```

## Troubleshooting

### High False Positive Rate

**Symptoms**: Many warning detections but no actual Byzantine behavior

**Causes**:
1. Warning threshold too low
2. Noisy layer contributing spurious signals
3. Network instability causing temporary anomalies

**Resolution**:
1. Raise `warningThreshold` (e.g., 0.15 → 0.25)
2. Reduce weight of noisy layer
3. Increase `scoreDecayRate` for faster signal aging

### Low Detection Rate

**Symptoms**: Known Byzantine nodes not being detected

**Causes**:
1. Thresholds too high
2. Layer weights misconfigured
3. Provider not returning anomaly state

**Resolution**:
1. Lower `warningThreshold` and/or `criticalThreshold`
2. Verify layer weights match signal quality
3. Check provider implementation

### Response Handler Failures

**Symptoms**: `failed_responses` counter increasing

**Causes**:
1. Fireflies view unavailable
2. Network partition
3. Handler timeout

**Resolution**:
1. Check Fireflies view health
2. Verify network connectivity
3. Increase handler timeout or implement retry logic

### Rate Limit Exhaustion

**Symptoms**: `ratelimit_skips` counter high during attacks

**Causes**:
1. `maxResponsesPerInterval` too low for attack intensity
2. Many simultaneous Byzantine members

**Resolution**:
1. Increase `maxResponsesPerInterval` temporarily
2. Consider batch response handling
3. Review if this is expected during attack

### Memory Growth

**Symptoms**: Increasing memory usage over time

**Causes**:
1. Member profiles not being cleaned up
2. Signal deduplication window accumulating entries

**Resolution**:
1. Ensure `reset()` called on view changes
2. Reduce `signalDeduplicationWindow` duration
3. Verify departed members are being cleaned up

## Performance Tuning

### For Large Networks (1000+ members)

```java
IntelligenceConfig.builder()
    .defaultPollInterval(Duration.ofSeconds(10))  // Slower polling
    .maxResponsesPerInterval(50)  // Higher batch size
    .scoreDecayRate(0.98)  // Slower decay
    .build()
```

### For Low-Latency Detection

```java
IntelligenceConfig.builder()
    .defaultPollInterval(Duration.ofSeconds(1))  // Fast polling
    .layerPollIntervals(Map.of(
        "ETHEREAL", Duration.ofMillis(500)  // Sub-second for consensus
    ))
    .responseCooldown(Duration.ofSeconds(5))  // Faster re-response
    .build()
```

## Related Documentation

- [Weight Tuning Methodology](weight-tuning-methodology.md)
- [ADR-007: Cross-Layer Byzantine Detection](../adr/007-cross-layer-byzantine-detection.md)
- [Adding Byzantine Layers](../development/adding-byzantine-layers.md)
