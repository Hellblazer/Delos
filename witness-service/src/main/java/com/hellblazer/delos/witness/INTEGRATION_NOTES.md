# BLSMetrics and ResponseOrchestrationMetrics Integration

**Created**: 2026-01-22
**Author**: java-developer agent
**Context**: Phase 1C-3-D-A2 (Delos-3966)
**Related Beads**: Delos-3970 (Byzantine detection metrics)

---

## Overview

This document describes how to integrate BLSMetrics with existing ResponseOrchestrationMetrics when implementing Byzantine detection metrics in Phase B (Delos-3970).

## Current State

### BLSMetricsImpl (Phase A - COMPLETED)

**Location**: `com.hellblazer.delos.witness.BLSMetricsImpl`

**Implemented Metrics** (12 core):
1. `bls.signature.receipt.latency` - Histogram
2. `bls.signature.verify.latency` - Timer
3. `bls.aggregation.create.latency` - Timer
4. `bls.threshold.time` - Timer
5. `bls.signatures.received` - Meter
6. `bls.signatures.accepted` - Meter
7. `bls.signatures.rejected.epoch` - Counter
8. `bls.signatures.rejected.viewRef` - Counter
9. `bls.signatures.rejected.late` - Counter
10. `bls.signatures.rejected.duplicate` - Counter
11. `bls.accumulator.active` - Gauge
12. `bls.accumulator.completed` - Meter

### ResponseOrchestrationMetrics (Existing)

**Location**: `com.hellblazer.delos.witness.detection.ResponseOrchestrationMetrics`

**Existing Byzantine Metrics**:
- `activeQuarantines` - AtomicLong (current quarantine count)
- `totalQuarantines` - LongAdder (cumulative quarantines)
- `totalRecoveries` - LongAdder (cumulative recoveries)
- `totalShuns` - LongAdder (cumulative shuns)
- `escalationCounts` - Map<ResponseAction, LongAdder> (escalations by action type)
- `stateTransitionCounts` - Map<ResponseState, Map<ResponseState, LongAdder>> (state transitions)

---

## Integration Strategy for Phase B

When implementing Byzantine detection metrics (Delos-3970), **DO NOT** duplicate existing metrics. Instead, use a bridge adapter pattern.

### Approach 1: Bridge Pattern (RECOMMENDED)

Create adapter methods in BLSMetricsImpl that bridge to ResponseOrchestrationMetrics:

```java
public class BLSMetricsImpl implements BLSMetrics {
    // ... existing fields ...

    private ResponseOrchestrationMetrics orchestrationMetrics;

    /**
     * Set the ResponseOrchestrationMetrics instance to bridge Byzantine metrics.
     * Call this during initialization to enable Byzantine detection metrics.
     */
    public void setOrchestrationMetrics(ResponseOrchestrationMetrics orchestrationMetrics) {
        this.orchestrationMetrics = orchestrationMetrics;
    }

    // Bridge methods for Phase B:

    /**
     * Get the gauge for active Byzantine quarantines.
     * Bridges to ResponseOrchestrationMetrics.activeQuarantines.
     */
    public Gauge<Long> byzantineQuarantineActive() {
        if (orchestrationMetrics == null) {
            return () -> 0L;
        }
        return orchestrationMetrics::getActiveQuarantines;
    }

    /**
     * Get the counter for Byzantine recoveries.
     * Bridges to ResponseOrchestrationMetrics.totalRecoveries.
     */
    public Counter byzantineRecoveryCount() {
        if (orchestrationMetrics == null) {
            return new Counter();
        }
        // Wrap LongAdder as Counter
        return new BridgeCounter(() -> orchestrationMetrics.getTotalRecoveries());
    }

    /**
     * Get the counter for escalations by action type.
     * Bridges to ResponseOrchestrationMetrics.escalationCounts.
     */
    public Counter escalationCounter(ResponseAction action) {
        if (orchestrationMetrics == null) {
            return new Counter();
        }
        return new BridgeCounter(() -> orchestrationMetrics.getEscalationCount(action));
    }
}
```

### Approach 2: Expose via Registry (ALTERNATIVE)

Register ResponseOrchestrationMetrics directly with Dropwizard registry:

```java
public class ResponseOrchestrationMetrics {
    /**
     * Register Byzantine detection metrics with Dropwizard registry.
     * Call this to expose existing metrics to monitoring systems.
     */
    public void register(MetricRegistry registry) {
        // Register gauges
        registry.register("bls.byzantine.quarantine.active",
                         (Gauge<Long>) this::getActiveQuarantines);

        registry.register("bls.byzantine.recovery.total",
                         (Gauge<Long>) this::getTotalRecoveries);

        registry.register("bls.byzantine.quarantine.total",
                         (Gauge<Long>) this::getTotalQuarantines);

        registry.register("bls.byzantine.shuns.total",
                         (Gauge<Long>) this::getTotalShuns);

        // Register escalation counters
        for (var action : ResponseAction.values()) {
            registry.register("bls.byzantine.escalation." + action.name().toLowerCase(),
                             new BridgeCounter(() -> getEscalationCount(action)));
        }
    }
}
```

---

## BridgeCounter Implementation

Create a helper class to adapt LongAdder to Dropwizard Counter:

```java
package com.hellblazer.delos.witness.metrics;

import com.codahale.metrics.Counter;
import java.util.function.LongSupplier;

/**
 * Adapter that bridges an external long value supplier to Dropwizard Counter.
 * Used to expose existing metrics (e.g., ResponseOrchestrationMetrics)
 * via Dropwizard registry without duplication.
 */
public class BridgeCounter extends Counter {
    private final LongSupplier valueSupplier;

    public BridgeCounter(LongSupplier valueSupplier) {
        this.valueSupplier = valueSupplier;
    }

    @Override
    public long getCount() {
        return valueSupplier.getAsLong();
    }

    @Override
    public void inc() {
        throw new UnsupportedOperationException(
            "BridgeCounter is read-only; increment via source metric");
    }

    @Override
    public void inc(long n) {
        throw new UnsupportedOperationException(
            "BridgeCounter is read-only; increment via source metric");
    }

    @Override
    public void dec() {
        throw new UnsupportedOperationException(
            "BridgeCounter is read-only; decrement via source metric");
    }

    @Override
    public void dec(long n) {
        throw new UnsupportedOperationException(
            "BridgeCounter is read-only; decrement via source metric");
    }
}
```

---

## Metrics Naming Convention

### BLS Signature Metrics (Phase A - COMPLETED)
- `bls.signature.*` - Receipt and verification
- `bls.aggregation.*` - Aggregation operations
- `bls.threshold.*` - Threshold timing
- `bls.accumulator.*` - Accumulator health

### Byzantine Detection Metrics (Phase B - BRIDGED FROM ResponseOrchestrationMetrics)
- `bls.byzantine.quarantine.active` - Current quarantines
- `bls.byzantine.quarantine.total` - Cumulative quarantines
- `bls.byzantine.recovery.total` - Cumulative recoveries
- `bls.byzantine.recovery.count` - Same as above (alias for Counter interface)
- `bls.byzantine.escalation.<action>` - Escalations by ResponseAction
- `bls.byzantine.shuns.total` - Cumulative shuns

### New Metrics to Add in Phase B
- `bls.byzantine.escalation.latency` - Timer (NEW)
- `bls.byzantine.anomaly.score.max` - Gauge (NEW)
- `bls.byzantine.state.transitions` - Counters per state transition (NEW)

---

## Testing Guidance for Phase B

### Tests to Write

1. **BridgeCounter Tests**:
   - Verify read-only behavior (throws on inc/dec)
   - Verify correct value bridging from LongAdder
   - Thread safety with concurrent reads

2. **Integration Tests**:
   - Verify all ResponseOrchestrationMetrics exposed via registry
   - Verify metric names match convention
   - Verify no duplicate metrics exist

3. **Instrumentation Tests**:
   - ByzantineDetectorCoordinator calls correct metrics
   - ResponseEscalationEngine updates escalation counters
   - Quarantine lifecycle updates active/total/recovery counters

### Example Test Structure

```java
@Test
void testByzantineMetricsBridge() {
    var orchestrationMetrics = new ResponseOrchestrationMetrics();
    var blsMetrics = new BLSMetricsImpl();
    blsMetrics.register(registry);
    blsMetrics.setOrchestrationMetrics(orchestrationMetrics);

    // Update via orchestration metrics
    orchestrationMetrics.recordEscalation(ResponseAction.QUARANTINE);

    // Verify visible via BLS metrics bridge
    assertEquals(1, blsMetrics.escalationCounter(ResponseAction.QUARANTINE).getCount());
    assertEquals(1, blsMetrics.byzantineQuarantineActive().getValue());
}
```

---

## Audit Trail

**Audit Document**: `.pm/plans/PHASE_1C_3D_BLS_METRICS_AUDIT.md`

**Key Findings Addressed**:
- ✅ **Critical Finding #2**: Existing ResponseOrchestrationMetrics acknowledged
- ✅ Integration strategy documented (bridge pattern)
- ✅ No duplicate metrics created in Phase A
- ✅ Clear guidance for Phase B implementation

**Phase A Deliverables** (Delos-3966):
- ✅ BLSMetricsImpl with 12 core metrics
- ✅ 39 unit tests (100% pass rate)
- ✅ Thread-safe, lock-free implementation
- ✅ Dropwizard MetricRegistry integration
- ✅ Graceful degradation (null registry support)
- ✅ Integration notes for Phase B

**Next Steps**:
1. Phase B (Delos-3970): Implement bridge adapters
2. Phase B: Add new Byzantine metrics (escalation latency, anomaly score)
3. Phase B: Instrument ByzantineDetectorCoordinator and ResponseEscalationEngine
4. Phase C (Delos-3971): View change and degradation metrics
5. Phase D (Delos-3972): Wire into WitnessParameters and document

---

## References

- **BLSMetrics Interface**: `com.hellblazer.delos.witness.BLSMetrics`
- **BLSMetricsImpl**: `com.hellblazer.delos.witness.BLSMetricsImpl`
- **ResponseOrchestrationMetrics**: `com.hellblazer.delos.witness.detection.ResponseOrchestrationMetrics`
- **ResponseAction Enum**: `com.hellblazer.delos.witness.detection.ResponseAction`
- **ResponseState Enum**: `com.hellblazer.delos.witness.detection.ResponseState`
- **Audit Report**: `.pm/plans/PHASE_1C_3D_BLS_METRICS_AUDIT.md`
- **Parent Bead**: Delos-3939 (Phase 1C-3-D Epic)
