# Model Module Performance Baselines

## Overview

This document defines Service Level Agreements (SLAs) and performance expectations for the model module's multi-tenancy and delegation gossip features.

**Status:** SLAs defined; benchmarks pending isolates/JniBridge availability
**References:** Delos-ixf9 (Performance baselines task)

---

## Subdomain Spawn Performance

### spawn() Latency

**Operation:** `ProcessContainerDomain.spawn(DemesneParameters)`

| Metric | Target | Rationale |
|--------|--------|-----------|
| p50 latency | ≤ 100ms | Cold spawn with KERI ceremony |
| p95 latency | ≤ 500ms | Includes outliers (GC, contention) |
| p99 latency | ≤ 1000ms | Maximum acceptable user-facing delay |

**Measurement:**
- Cold spawn: First subdomain spawn (includes JniBridge initialization)
- Warm spawn: Subsequent spawns (JniBridge already loaded)
- Measure from spawn() call to SubDomainHandle.markRunning()

**Constraints:**
- File descriptor limits (macOS: ~10,240; Linux: configurable)
- Unix domain socket path length (macOS: 104 chars; Linux: 108 chars)

### Concurrent Spawn Throughput

| Metric | Target | Rationale |
|--------|--------|-----------|
| Concurrent spawns | ≥ 10/sec | Rapid scale-up scenarios |
| Sequential spawns | ≥ 5/sec | Typical workload |

**Test Scenarios:**
1. Sequential: Spawn 50 subdomains sequentially, measure total time
2. Concurrent: Spawn 50 subdomains using ExecutorService, measure completion time
3. Burst: Spawn 10 subdomains simultaneously, verify all succeed

---

## Portal Routing Performance

### Routing Latency

**Operation:** `SubDomainHandle.send(request)` via Portal

| Metric | Target | Rationale |
|--------|--------|-----------|
| p50 latency | ≤ 5ms | Local Unix socket overhead |
| p95 latency | ≤ 20ms | Includes serialization/deserialization |
| p99 latency | ≤ 50ms | Maximum acceptable for interactive operations |

**Components:**
- Route lookup: `routes.get(qb64(digest))` - O(1) ConcurrentHashMap
- Socket connect: Unix domain socket handshake
- gRPC call: Marshalling + unmarshalling

### Routing Throughput

| Metric | Target | Rationale |
|--------|--------|-----------|
| Requests/sec | ≥ 1,000 | Per ProcessContainerDomain instance |
| Concurrent requests | ≥ 100 | Simultaneous in-flight operations |

**Test Scenarios:**
1. Single-threaded: Sequential requests to same subdomain
2. Multi-threaded: Concurrent requests from 10 threads
3. Fan-out: Single request broadcast to 10 subdomains

---

## Delegation Gossip Performance

### Convergence Time

**Operation:** Anti-entropy gossip for delegation state synchronization

| Cluster Size | Target Convergence | Rationale |
|--------------|-------------------|-----------|
| 2 replicas | ≤ 2 seconds | Minimal latency for 2-node sync |
| 5 replicas | ≤ 5 seconds | Standard committee size |
| 10 replicas | ≤ 10 seconds | Larger deployments |
| 50 replicas | ≤ 30 seconds | Maximum expected cluster |

**Convergence Definition:** 90% of nodes have consistent delegation state

**Gossip Parameters:**
- Round interval: 1 second (configurable)
- Fan-out: 3 peers per round
- Bloom filter false positive rate: 0.01

### Bandwidth Consumption

| Metric | Target | Rationale |
|--------|--------|-----------|
| Per-node bandwidth | ≤ 100 KB/sec | Sustainable for 50-node cluster |
| Bloom filter size | ≤ 1 KB | Per gossip message |

---

## Oracle Performance

### Operation Latency

**Module:** delphinius (Oracle.check, Oracle.add, Oracle.map, Oracle.delete)

| Operation | p50 Target | p95 Target | Rationale |
|-----------|------------|------------|-----------|
| check() | ≤ 5ms | ≤ 20ms | Read-only, should be fast |
| add() | ≤ 10ms | ≤ 50ms | Write with CHOAM commit |
| delete() | ≤ 10ms | ≤ 50ms | Write with CHOAM commit |
| map() | ≤ 10ms | ≤ 50ms | Write with CHOAM commit |

**Note:** These targets assume Oracle is backed by CHOAM for replicated state.

### getCurrentBlock() Overhead

| Metric | Target | Rationale |
|--------|--------|-----------|
| Call latency | ≤ 1ms | Should be cached/trivial lookup |
| Cache hit rate | ≥ 99% | Avoids redundant computation |

**Optimization:** Cache block number if no state changes detected (see Delos-vnrc)

---

## Scalability Limits

### Maximum Concurrent Subdomains

| Metric | Target | Constraint |
|--------|--------|------------|
| Per ProcessContainerDomain | ≥ 100 | File descriptors, memory |
| Per host | ≥ 500 | OS limits |

**Resource Consumption Per Subdomain:**
- Heap memory: ≤ 50 MB (default ResourceLimits)
- Native memory: ≤ 100 MB (default ResourceLimits)
- File descriptors: 2 (portal socket + context socket)
- Threads: ≤ 20 (default ResourceLimits)

### Performance Degradation Curve

| Subdomains | Expected Spawn Latency (p95) | Expected Routing Latency (p95) |
|------------|------------------------------|--------------------------------|
| 10 | ≤ 500ms | ≤ 20ms |
| 50 | ≤ 750ms | ≤ 30ms |
| 100 | ≤ 1000ms | ≤ 50ms |

**Degradation Factors:**
- CPU contention (KERI ceremonies)
- File descriptor exhaustion
- Memory pressure (GC overhead)
- Socket buffer exhaustion

---

## Memory Footprint

### SubDomainHandleImpl

| Component | Expected Size | Notes |
|-----------|---------------|-------|
| Base object | ~200 bytes | References, atomics |
| Latency list | ~8 bytes/request | Grows unbounded (potential issue) |
| Status/counters | ~50 bytes | AtomicReference, AtomicLong |

**TODO:** Add latency list size limit or sliding window (see TECH_DEBT.md)

### ProcessContainerDomain

| Component | Expected Size | Notes |
|-----------|---------------|-------|
| Base object | ~500 bytes | Event loops, maps |
| Per-subdomain overhead | ~1 KB | Handle + route + Demesne ref |
| Route map | ~50 bytes/route | ConcurrentHashMap entry |

---

## Test Infrastructure

### Benchmark Location

- **Module:** `model/src/test/java/com/hellblazer/delos/model/benchmark/`
- **Tag:** `@Tag("performance")`
- **Pattern:** Follow `FirefliesWitnessAdapterBenchmarkTest.java` structure

### Required for Testing

**All benchmarks currently @Disabled pending:**
- GraalVM isolates support (JniBridge)
- Portal.link() implementation (SubDomainHandle.send())

**Enabled Tests:**
- Oracle benchmarks (delphinius module, no isolates required)

### Running Benchmarks

```bash
# Run all performance tests (when enabled)
./mvnw test -Dgroups=performance

# Run specific benchmark
./mvnw test -Dtest=SubDomainSpawnBenchmarkTest

# Large-scale tests (requires large_tests=true)
./mvnw test -Dgroups=performance -Dlarge_tests=true
```

---

## Monitoring and Alerting

### Production Metrics

**Key Metrics to Track:**
1. Spawn latency (p50, p95, p99)
2. Routing latency (p50, p95, p99)
3. Active subdomain count
4. Gossip convergence time
5. Memory footprint per subdomain
6. File descriptor usage

**Alerting Thresholds:**
- Spawn p95 > 1s (warning)
- Routing p95 > 50ms (warning)
- Active subdomains > 80% of limit (critical)
- File descriptor usage > 90% (critical)

---

## Future Work

1. **Dynamic resource limits** - Adjust per-subdomain limits based on load
2. **Latency list bounds** - Add sliding window or size limit to prevent unbounded growth
3. **getCurrentBlock() caching** - Implement in Oracle (Delos-vnrc)
4. **Horizontal scaling** - Multiple ProcessContainerDomain instances with load balancing
5. **Profiling infrastructure** - JFR, async-profiler integration for production analysis

---

## References

- **Delos-ixf9:** Add performance baselines and SLA definitions for model module
- **Delos-mj8z:** Add subdomain lifecycle management API to ProcessContainerDomain
- **Delos-vnrc:** Cache getCurrentBlock() for Oracle to reduce latency
- **docs/TESTING_GUIDE.md:** Test categories and execution patterns
- **witness-service/../FirefliesWitnessAdapterBenchmarkTest.java:** Benchmark pattern reference
