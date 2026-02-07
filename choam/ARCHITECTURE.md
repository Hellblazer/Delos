# CHOAM Architecture Documentation

## Overview

CHOAM (Committee-based replicated state machines) implements Byzantine fault-tolerant consensus for distributed systems. This document describes the architecture after state extraction refactoring (2026-02-06).

---

## Extraction History

**Baseline**: 1,991 LOC (before extraction)
**Current**: 1,794 LOC (after extraction)
**Reduction**: 197 lines (9.9%)
**Validation**: Delos-a8a9 (2026-02-06)

### Phase 1-5: StateHolder Pattern Extraction

State fields extracted into dedicated holder classes for improved maintainability and testability.

| Phase | Class | Responsibility | Lines | Bead |
|-------|-------|----------------|-------|------|
| 1 | ControlStateHolder | Lifecycle flags (started, ongoingJoin) | ~150 | Delos-zukm |
| 2 | AsyncOperationStateHolder | Async operations (bootstrap, sync attempts) | ~140 | Delos-5jo6 |
| 3 | CommitteeStateHolder | Committee state machine | ~130 | Delos-k4ml |
| 4 | BlockChainStateHolder | Blockchain state (genesis, head, view, pending) | ~200 | Delos-gmhs |
| 5 | ViewStateHolder | View reconfiguration state | ~180 | Delos-zfb1 |

**Total StateHolder LOC**: ~800 lines

### Post-Phase 5: Inner Class Extraction

Large inner classes extracted to top-level classes in `support/` package.

| Class | Original | Responsibility | Lines |
|-------|----------|----------------|-------|
| CombinerFSM | `Combiner` inner class | FSM for consensus combination and synchronization | 170 |
| GenesisFormation | `Formation` inner class | Genesis committee bootstrapping | 134 |
| ConciergeService | `Trampoline` inner class | GRPC service delegation | 60 |

**Total Extracted**: ~364 lines from inner classes

---

## Current Architecture

### Core Classes

```
choam/
├── CHOAM.java (1,794 LOC)
│   └── Main consensus coordinator
├── support/
│   ├── StateHolders (5 classes, ~800 LOC)
│   │   ├── AsyncOperationStateHolder.java
│   │   ├── BlockChainStateHolder.java
│   │   ├── CommitteeStateHolder.java
│   │   ├── ControlStateHolder.java
│   │   └── ViewStateHolder.java
│   ├── FSM & Services (3 classes, ~364 LOC)
│   │   ├── CombinerFSM.java
│   │   ├── GenesisFormation.java
│   │   └── ConciergeService.java
│   └── Supporting classes
│       ├── HashedCertifiedBlock.java
│       ├── ViewCoordinatorImpl.java
│       └── ImmutablePendingViews.java
```

### Lock Structure

**CRITICAL INVARIANT**: `headLock` and `viewStateLock` have **DISJOINT CRITICAL SECTIONS**.

| Lock | Owner | Line | Acquisition Sites | Purpose |
|------|-------|------|-------------------|---------|
| `headLock` | BlockChainStateHolder | 80 | CHOAM.java:708 | Blockchain head protection |
| `viewStateLock` | ViewStateHolder | 83 | CHOAM.java:1066 | View reconfiguration protection |

**Lock Exposure**: Both locks exposed as `public final` fields from StateHolder classes to CHOAM.java.

**Validation**: Lock ordering audit (2026-02-06) confirmed disjoint critical sections preserved.

---

## Invariants

### 10 Core Invariants (validated 2026-02-06)

**AsyncOperationStateHolder** (2):
1. syncAttempts >= 0 and bounded (< 100)
2. At most one bootstrap operation active at a time

**BlockChainStateHolder** (4):
3. Genesis immutability: `genesis != null ⇒ genesis.height() == 0`
4. Head monotonicity: `head != null ⇒ head.height() >= 0` (monotonic increase)
5. View lags head: `view != null ⇒ view.height() <= head.height()`
6. Pending bounded: `pending.size() <= maxPendingBlocks` (DoS prevention)

**CommitteeStateHolder** (2):
7. Committee state machine: `null → Formation → Administration ↔ Synchronizer`
8. Committee never null after genesis block acceptance

**ControlStateHolder** (1):
9. ongoingJoin ⇒ started (Byzantine safety critical)

**ViewStateHolder** (2):
10. Pending views bounded: `pendingViews.size() < 100`
11. View transition atomicity: snapshot() sees old or new state, never partial

**Test Coverage**: 44 StateHolder tests (100% pass rate), all invariants explicitly validated.

---

## Performance Impact

Baseline established commit `87cd804d` (2026-02-05). Post-extraction measurement (2026-02-06):

| Metric | Baseline | Post-Extraction | Change | SLA Threshold | Status |
|--------|----------|-----------------|--------|---------------|--------|
| Throughput | 14,084.51 tx/sec | 13,227.51 tx/sec | **-6.1%** | >12,676.06 tx/sec | ✅ PASS |
| p95 Latency | 106.20 ms | 56.79 ms | **-46.5%** (improved!) | <111.51 ms | ✅ PASS |
| p99 Latency | 377.40 ms | 253.77 ms | **-32.7%** (improved!) | <415.14 ms | ✅ PASS |
| Memory | 12.0 MB | 13.0 MB | **+8.3%** | <13.8 MB | ✅ PASS |

**Analysis**: Latency improvements likely due to reduced method call overhead from extracted classes. All SLA thresholds met.

---

## Testing

### Test Suite Coverage

| Test Type | Count | Purpose |
|-----------|-------|---------|
| CHOAM tests | 247 | Full consensus integration |
| StateHolder tests | 44 | Invariant validation |
| Concurrency tests | 6 | Lock ordering verification |
| NonceTracker tests | 13 | Replay protection |

**Total**: 310+ tests, 100% pass rate (5 skipped as expected)

### Validation Gates

All validation deliverables completed for Delos-a8a9 (2026-02-06):

1. ✅ **Test Suite**: 247 tests passed (exceeded expected 168)
2. ✅ **Performance**: All SLA thresholds met, latency improved
3. ✅ **Lock Ordering**: headLock and viewStateLock remain disjoint (audit passed)
4. ✅ **Invariants**: All 10 core invariants validated via 44 StateHolder tests
5. ⚠️ **Code Coverage**: JaCoCo unavailable (Java 25 not supported, deferred per ADR-0001)
6. ✅ **Documentation**: LOCK_ORDERING.md updated, ARCHITECTURE.md created

---

## Design Rationale

### State Extraction Benefits

1. **Maintainability**: State concerns isolated into focused classes
2. **Testability**: Each StateHolder has dedicated test suite
3. **Readability**: CHOAM.java reduced from 1,991 to 1,794 LOC (9.9% reduction)
4. **Thread Safety**: Lock ownership explicit, no nested locking
5. **Byzantine Safety**: All invariants preserved and validated

### Lock-Free Patterns

StateHolders use lock-free `AtomicReference` and `AtomicBoolean` for most state:
- **ControlStateHolder**: `AtomicBoolean started`, `AtomicBoolean ongoingJoin`
- **AsyncOperationStateHolder**: `AtomicReference<CompletableFuture>`, `AtomicInteger syncAttempts`
- **CommitteeStateHolder**: `AtomicReference<Committee>`

Only `headLock` and `viewStateLock` require explicit locking (for multi-field consistency).

### Inner Class Extraction

Large inner classes (CombinerFSM, GenesisFormation, ConciergeService) extracted to:
- Reduce CHOAM.java cognitive load
- Enable independent testing
- Improve compilation times (reduced inner class nesting)

---

## Migration Constraints (Preserved)

From LOCK_ORDERING.md - all constraints upheld:

1. ✅ **Preserve Disjoint Critical Sections**: headLock and viewStateLock remain disjoint
2. ✅ **No Nested Locking**: StateHolders do not acquire locks while holding other locks
3. ✅ **Lock Exposure**: StateHolders expose locks as `public final` fields
4. ✅ **Validation Gates**: All tests passed after each extraction phase

**Risk**: NONE - All constraints preserved, no Byzantine safety regressions.

---

## Future Work

### Potential Optimizations

1. **Further Extraction**: Consider extracting `View` management into ViewManager
2. **Metrics Integration**: Add Micrometer metrics to StateHolders for observability
3. **Code Coverage**: Establish JaCoCo baseline when Java 25 support available (ADR-0001)

### Monitoring

- **Performance**: Continue running PerformanceBaselineTest on each major change
- **Lock Ordering**: Run CHOAMThreadAndLockingTest concurrency suite
- **Invariants**: StateHolder test suite (44 tests) validates all invariants

---

## References

- **Lock Ordering**: [LOCK_ORDERING.md](LOCK_ORDERING.md)
- **Testing Guide**: [../docs/TESTING_GUIDE.md](../docs/TESTING_GUIDE.md)
- **Validation Bead**: Delos-a8a9 (Post-Phase Final validation)
- **Performance Baseline**: Commit `87cd804d` (2026-02-05)
- **JaCoCo Deferral**: [../docs/adr/0001-defer-jacoco-baseline-for-java25-support.md](../docs/adr/0001-defer-jacoco-baseline-for-java25-support.md)

---

**Last Updated**: 2026-02-06
**Validation Status**: ✅ COMPLETE (Delos-a8a9)
**Performance Status**: ✅ WITHIN SLA (all thresholds met, latency improved)
**Thread Safety**: ✅ PRESERVED (lock ordering disjoint, all invariants hold)
