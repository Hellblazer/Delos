# CHOAM Performance Baseline

**Established**: 2026-02-06T00:27:52Z
**Purpose**: Baseline metrics for CHOAM module before Phase 2 extractions
**Milestone**: M2.4 - CHOAM.java < 1500 LOC via state extraction

## Performance Metrics

**Test Configuration**:
- Transaction count: 10,000
- Concurrent clients: 100
- Block size: 1KB (typical transaction)
- Test: `PerformanceBaselineTest.establishBaseline()`

**Results**:
| Metric | Baseline Value | SLA Threshold | Notes |
|--------|---------------|---------------|-------|
| **Throughput** | 14,084.51 tx/sec | ≥ 12,676.06 tx/sec | Min throughput (-10%) |
| **Latency p50** | 56.64 ms | - | Median latency |
| **Latency p95** | 106.20 ms | ≤ 111.51 ms | Max p95 (+5%) |
| **Latency p99** | 377.40 ms | ≤ 415.14 ms | Max p99 (+10%) |
| **Memory Used** | 12.0 MB | ≤ 13.8 MB | Max memory (+15%) |
| **Test Duration** | 710 ms | - | 10K transactions |

**Test Execution Time**: 1.853s (PerformanceBaselineTest only)

## SLA Thresholds

Post-extraction validation must meet these thresholds to avoid rollback:

1. **Latency p95** ≤ 111.51 ms (+5% from baseline)
2. **Latency p99** ≤ 415.14 ms (+10% from baseline)
3. **Throughput** ≥ 12,676.06 tx/sec (-10% from baseline)
4. **Memory** ≤ 13.8 MB (+15% from baseline)

**Rollback Policy**: If **2 or more thresholds** are exceeded after any code change, rollback and redesign the extraction.

## Test Suite Summary

**Full CHOAM Test Suite**: (execution in progress)
- Total tests: 168 (expected)
- Execution time: *To be measured*
- All tests must pass after each extraction

## Validation Workflow

After each extraction:

1. Run `./mvnw test -pl choam -Dtest=PerformanceBaselineTest`
2. Compare results against SLA thresholds above
3. Verify all 168 tests pass
4. If 2+ thresholds exceeded → **ROLLBACK**
5. If all pass → **PROCEED**

## Baseline Files

- **Metrics JSON**: `choam/target/baseline-results.json`
- **Test**: `PerformanceBaselineTest.java`
- **This document**: `choam/docs/PERFORMANCE_BASELINE.md`

## Performance Targets

**Current State**:
- CHOAM.java: 1987 LOC
- Target: < 1500 LOC
- Reduction needed: 487 lines

**Extraction Plan** (Phase 2):
- Formation: 102 lines (CONDITIONAL - 17 compilation errors)
- Combiner: 132 lines
- Trampoline: 29 lines
- Synchronizer: 45 lines
- **Total available**: 308 lines (Administration deferred - 162 compilation errors)

**Expected Final**: 1987 - 308 = 1679 LOC (179 lines over target, milestone ~12% over)

## Notes

- Baseline established before any Phase 2 extractions
- Performance test uses deterministic seed for reproducibility
- SLA thresholds allow for reasonable performance variance
- Full test suite timing will be updated after execution completes
- Formation extraction blocked pending 17 compilation error fixes (2-3 hours)
- Administration extraction deferred (162 errors - requires 1-2 weeks refactoring)
