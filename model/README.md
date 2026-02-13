# Delos Model

Process and subdomain tenant models for multi-tenant distributed systems.

## Overview

The model module provides:
- **ProcessContainerDomain**: Multi-tenant container for isolated subdomains
- **SubDomainHandle**: Lifecycle management API for spawned subdomains
- **Portal Routing**: Unix domain socket-based inter-subdomain communication
- **Delegation Gossip**: KERI delegation state synchronization

## Architecture

### Multi-Tenancy Design

Subdomains are isolated execution environments with:
- **KERI Identities**: Each subdomain has a delegated KERI identifier
- **Resource Limits**: Configurable heap, native memory, threads, and file descriptors
- **Portal Communication**: gRPC over Unix domain sockets for IPC
- **Delegation Gossip**: Anti-entropy gossip for delegation state propagation

### Isolation Mechanisms

**Current (In-Process):**
- Separate Demesne instances with resource monitoring
- Limits are advisory, enforced via tracking

**Future (GraalVM Isolates):**
- True heap isolation via GraalVM isolate API
- Native memory limits enforced by OS
- See [ADR-0008](../docs/adr/0008-subdomain-isolation-strategy.md)

## Resource Requirements

### Per-Subdomain Resources

| Resource | Default Limit | Notes |
|----------|---------------|-------|
| Heap Memory | 50 MB | Configurable via ResourceLimits |
| Native Memory | 100 MB | Configurable via ResourceLimits |
| Threads | 20 | Configurable via ResourceLimits |
| File Descriptors | 2 | Portal + context sockets |

### ProcessContainerDomain Resources

| Resource | Usage | Notes |
|----------|-------|-------|
| Base Memory | ~500 bytes | Event loops, maps |
| Per-Subdomain Overhead | ~1 KB | Handle + route + Demesne ref |
| Event Loop Threads | 3 pools | Portal, server, client |

## Scalability Limits

### Maximum Concurrent Subdomains

| Constraint | Limit | Rationale |
|------------|-------|-----------|
| **Recommended** | 100 per container | Balances performance and resource usage |
| **Maximum** | 500 per host | File descriptor limits (2 FDs × 500 = 1000) |
| **File Descriptors** | OS-dependent | macOS: ~10,240; Linux: configurable via ulimit |
| **Memory** | Hardware-dependent | 150MB × 100 = 15GB for 100 subdomains |

### Performance Characteristics

**Spawn Latency (p95):**
- 10 subdomains: ≤ 500ms
- 50 subdomains: ≤ 750ms
- 100 subdomains: ≤ 1000ms

**Routing Latency (p95):**
- 10 subdomains: ≤ 20ms
- 50 subdomains: ≤ 30ms
- 100 subdomains: ≤ 50ms

See [docs/PERFORMANCE_BASELINES.md](docs/PERFORMANCE_BASELINES.md) for complete SLA definitions.

## Capacity Planning

### Quick Reference

**For N subdomains:**
- Heap: `N × 50 MB` (default limits)
- Native: `N × 100 MB` (default limits)
- File Descriptors: `N × 2`
- Threads: `N × 20` (default limits)

**Example: 50 subdomains**
- Total Heap: 2.5 GB
- Total Native: 5 GB
- File Descriptors: 100
- Threads: ~1000

### Hardware Sizing

| Subdomains | Heap (GB) | Total Memory (GB) | CPU Cores | File Descriptors |
|------------|-----------|-------------------|-----------|------------------|
| 10 | 0.5 | 1.5 | 2 | 20 |
| 50 | 2.5 | 7.5 | 4 | 100 |
| 100 | 5 | 15 | 8 | 200 |

See [docs/CAPACITY_PLANNING.md](docs/CAPACITY_PLANNING.md) for detailed sizing guidance.

## Configuration

### Resource Limits

```java
var limits = ResourceLimits.newBuilder()
    .setMaxHeapMemoryMB(100)        // 100 MB heap
    .setMaxNativeMemoryMB(200)      // 200 MB native
    .setMaxThreads(50)              // 50 threads
    .setMaxFileDescriptors(10)      // 10 file descriptors
    .build();

var handle = container.spawn(params);
handle.setResourceLimits(limits);
```

### OS-Level Tuning

**File Descriptor Limits (Linux):**
```bash
# Per-process limit
ulimit -n 10240

# System-wide limits
sudo sysctl -w fs.file-max=100000
```

**File Descriptor Limits (macOS):**
```bash
# Per-process limit
ulimit -n 10240

# System-wide limits (requires reboot)
sudo sysctl -w kern.maxfiles=100000
sudo sysctl -w kern.maxfilesperproc=10240
```

### Unix Socket Cleanup

ProcessContainerDomain automatically cleans up Unix domain socket files to prevent stale files from accumulating after crashes.

**Automatic Cleanup:**
- **Startup:** Removes socket files older than 5 minutes from previous crashed instances
- **Normal Shutdown:** Deletes all socket files created by this container
- **Abnormal Termination:** Shutdown hook cleans up on SIGTERM/SIGINT (not SIGKILL)

**Manual Cleanup:**
If socket files accumulate after repeated crashes or SIGKILL:
```bash
# List socket files
ls -lh /path/to/communications/directory/

# Remove all socket files (when container is stopped)
rm -f /path/to/communications/directory/*

# Remove only stale files (older than 5 minutes)
find /path/to/communications/directory/ -type f -mmin +5 -delete
```

**Systemd Integration:**
For guaranteed cleanup on abnormal termination, add to systemd unit:
```ini
[Unit]
Description=Delos ProcessContainerDomain

[Service]
ExecStart=/usr/bin/java -jar delos-app.jar
ExecStopPost=/bin/rm -f /path/to/communications/directory/*

[Install]
WantedBy=multi-user.target
```

## Monitoring

### Key Metrics

1. **Active Subdomain Count** - Track current vs maximum
2. **Spawn Latency** - p50, p95, p99 percentiles
3. **Routing Latency** - p50, p95, p99 percentiles
4. **File Descriptor Usage** - Current vs OS limit
5. **Memory Footprint** - Per-subdomain and total

### Alerting Thresholds

| Metric | Warning | Critical |
|--------|---------|----------|
| Spawn p95 Latency | > 1s | > 2s |
| Routing p95 Latency | > 50ms | > 100ms |
| Active Subdomains | > 80 | > 90 |
| File Descriptor Usage | > 80% | > 90% |

## Testing

### Performance Benchmarks

```bash
# Run performance tests (when enabled)
./mvnw test -pl model -Dgroups=performance

# Large-scale tests
./mvnw test -pl model -Dgroups=performance -Dlarge_tests=true
```

**Note:** Most benchmarks are currently @Disabled pending GraalVM isolates support.

### Integration Tests

Multi-tenancy integration tests are in the `isolates` module (require `-Pisolates` profile):

```bash
./mvnw test -pl isolates -Pisolates
```

## References

- **[docs/PERFORMANCE_BASELINES.md](docs/PERFORMANCE_BASELINES.md)**: SLA definitions and performance targets
- **[docs/SCALABILITY.md](docs/SCALABILITY.md)**: Detailed scalability analysis and limits
- **[docs/CAPACITY_PLANNING.md](docs/CAPACITY_PLANNING.md)**: Hardware sizing and resource planning
- **[ADR-0008](../docs/adr/0008-subdomain-isolation-strategy.md)**: Subdomain isolation strategy
- **[ADR-0009](../docs/adr/0009-portal-routing-semantics.md)**: Portal routing design
- **[ADR-0010](../docs/adr/0010-delegation-gossip-protocol.md)**: Delegation gossip protocol
