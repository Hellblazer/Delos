# ProcessContainerDomain Capacity Planning Guide

## Quick Start

**Need to know how many subdomains you can run?**

```
Maximum Subdomains = MIN(
    (Available RAM - 2GB) / 150 MB,
    (ulimit -n - 100) / 2
)
```

**Example:** 16GB RAM, ulimit -n 10240
```
RAM limit:  (16GB - 2GB) / 150MB = 93 subdomains
FD limit:   (10240 - 100) / 2    = 5070 subdomains
→ Maximum:  93 subdomains (RAM-constrained)
```

---

## Deployment Sizing

### Small Deployment (10-20 subdomains)

**Use Case:** Development, testing, low-volume production

**Hardware Requirements:**
- **CPU:** 2 cores
- **RAM:** 4 GB
- **Disk:** 10 GB
- **File Descriptors:** 100 (ulimit -n 1024 sufficient)

**Expected Performance:**
- Spawn latency (p95): < 500ms
- Routing latency (p95): < 20ms
- Concurrent spawns: 10/sec
- Sequential spawns: 5/sec

**Configuration:**
```java
// Use default ResourceLimits (50MB heap, 100MB native, 20 threads)
var handle = container.spawn(params);
```

**JVM Tuning:**
```bash
java -Xmx2G -Xms2G \
     -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=200 \
     ...
```

---

### Medium Deployment (50 subdomains)

**Use Case:** Production, moderate traffic, multi-tenant SaaS

**Hardware Requirements:**
- **CPU:** 4 cores
- **RAM:** 12 GB
- **Disk:** 50 GB
- **File Descriptors:** 200 (ulimit -n 2048)

**Expected Performance:**
- Spawn latency (p95): < 750ms
- Routing latency (p95): < 30ms
- Concurrent spawns: 8/sec
- Sequential spawns: 4/sec

**Configuration:**
```java
// Slightly reduced limits for higher density
var limits = ResourceLimits.newBuilder()
    .setMaxHeapMemoryMB(40)    // 40 MB heap (vs 50 MB default)
    .setMaxNativeMemoryMB(80)  // 80 MB native (vs 100 MB default)
    .setMaxThreads(15)         // 15 threads (vs 20 default)
    .build();

var handle = container.spawn(params);
handle.setResourceLimits(limits);
```

**JVM Tuning:**
```bash
java -Xmx8G -Xms8G \
     -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=200 \
     -XX:ParallelGCThreads=4 \
     ...
```

**OS Tuning:**
```bash
# Increase file descriptor limit
ulimit -n 2048

# Increase max threads (Linux)
sudo sysctl -w kernel.threads-max=50000
```

---

### Large Deployment (100 subdomains)

**Use Case:** High-density production, maximum single-host utilization

**Hardware Requirements:**
- **CPU:** 8 cores
- **RAM:** 24 GB
- **Disk:** 100 GB
- **File Descriptors:** 300 (ulimit -n 4096)

**Expected Performance:**
- Spawn latency (p95): < 1000ms
- Routing latency (p95): < 50ms
- Concurrent spawns: 5/sec
- Sequential spawns: 3/sec

**Configuration:**
```java
// Aggressive limits for maximum density
var limits = ResourceLimits.newBuilder()
    .setMaxHeapMemoryMB(30)    // 30 MB heap
    .setMaxNativeMemoryMB(60)  // 60 MB native
    .setMaxThreads(10)         // 10 threads
    .build();

var handle = container.spawn(params);
handle.setResourceLimits(limits);
```

**JVM Tuning:**
```bash
java -Xmx16G -Xms16G \
     -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=200 \
     -XX:ParallelGCThreads=8 \
     -XX:ConcGCThreads=2 \
     -XX:+UnlockExperimentalVMOptions \
     -XX:G1NewSizePercent=20 \
     -XX:G1MaxNewSizePercent=30 \
     ...
```

**OS Tuning:**
```bash
# Increase file descriptor limit
ulimit -n 4096

# Increase max threads (Linux)
sudo sysctl -w kernel.threads-max=100000

# Increase socket buffer sizes
sudo sysctl -w net.core.rmem_max=134217728
sudo sysctl -w net.core.wmem_max=134217728
```

---

## Resource Calculation

### Memory Sizing Formula

**Total RAM Required:**
```
Total RAM = JVM Heap + (N × Subdomain Heap) + (N × Subdomain Native) + Buffer

Where:
  JVM Heap     = 2-4 GB (baseline for container JVM)
  Subdomain Heap   = ResourceLimits.maxHeapMemoryBytes (default 50 MB)
  Subdomain Native = ResourceLimits.maxNativeMemoryBytes (default 100 MB)
  N                = Number of subdomains
  Buffer           = 2 GB (OS, file cache, overhead)
```

**Example: 50 subdomains with default limits**
```
Total RAM = 3 GB + (50 × 50 MB) + (50 × 100 MB) + 2 GB
          = 3 GB + 2.5 GB + 5 GB + 2 GB
          = 12.5 GB
→ Provision 16 GB host
```

### CPU Sizing Formula

**Recommended Cores:**
```
Cores = MAX(
    N / 25,           # 25 subdomains per core (spawn contention)
    Threads / 100     # 100 threads per core (thread scheduler)
)

Where:
  N       = Number of subdomains
  Threads = N × ResourceLimits.maxThreads
```

**Example: 50 subdomains with default limits (20 threads each)**
```
By subdomain count: 50 / 25  = 2 cores
By thread count:    1000 / 100 = 10 cores
→ Provision 4 cores (middle ground, accounts for gossip/consensus)
```

### File Descriptor Calculation

**Required File Descriptors:**
```
FDs = Base + (N × 2)

Where:
  Base = 100 (stdin/stdout/stderr, CHOAM, database, temporary files)
  N    = Number of subdomains
  2    = Portal socket + context socket per subdomain
```

**Example: 50 subdomains**
```
FDs = 100 + (50 × 2) = 200
→ Set ulimit -n 2048 (10× buffer for safety)
```

**Checking Current Limit:**
```bash
ulimit -n               # Per-process limit
cat /proc/sys/fs/file-max  # System-wide limit (Linux)
```

### Disk Sizing

**Recommended Disk Space:**
```
Disk = Logs + Data + Binaries + Buffer

Where:
  Logs     = 1-5 GB per subdomain (depends on logging level)
  Data     = Varies by application (CHOAM logs, database, etc.)
  Binaries = 500 MB (application + JVM)
  Buffer   = 20% of (Logs + Data)
```

**Example: 50 subdomains, moderate logging, 100MB data each**
```
Disk = (50 × 2 GB) + (50 × 100 MB) + 500 MB + 20% buffer
     = 100 GB + 5 GB + 500 MB + 21 GB
     = 126.5 GB
→ Provision 200 GB disk
```

---

## Scaling Decision Matrix

| Subdomains | Strategy | Hosts | Hardware per Host | Total Cost (est.) |
|------------|----------|-------|-------------------|-------------------|
| 10 | Vertical | 1 | 2 core, 4 GB | Low |
| 50 | Vertical | 1 | 4 core, 12 GB | Medium |
| 100 | Vertical | 1 | 8 core, 24 GB | High |
| 200 | Horizontal | 2 | 8 core, 24 GB | 2× High |
| 500 | Horizontal | 5 | 8 core, 24 GB | 5× High |
| 1000 | Horizontal | 10 | 8 core, 24 GB | 10× High |

**Recommendation:** Vertical scaling up to 100 subdomains, then horizontal.

**Rationale:**
- Vertical is simpler (single host, no network overhead)
- Beyond 100, hit diminishing returns (spawn latency > 1s)
- Horizontal provides better fault isolation and HA

---

## Performance Trade-offs

### Resource Limits vs Density

**Tight Limits (Higher Density):**
- **Pros:** More subdomains per host, lower cost
- **Cons:** Risk of OOM, reduced headroom for spikes
- **Use Case:** Homogeneous workloads, predictable usage

**Loose Limits (Lower Density):**
- **Pros:** Better isolation, more headroom, fewer OOM errors
- **Cons:** Fewer subdomains per host, higher cost
- **Use Case:** Heterogeneous workloads, unpredictable usage

**Example:**

| Limit Profile | Heap (MB) | Native (MB) | Threads | Subdomains/Host (24GB RAM) |
|---------------|-----------|-------------|---------|----------------------------|
| **Tight** | 30 | 60 | 10 | 133 |
| **Default** | 50 | 100 | 20 | 93 |
| **Loose** | 100 | 200 | 50 | 46 |

### Spawn Latency vs Throughput

**Low Latency Configuration:**
- Fewer subdomains per host (< 50)
- More CPU cores (reduce contention)
- Aggressive JVM tuning (minimize GC pauses)

**High Throughput Configuration:**
- More subdomains per host (50-100)
- Moderate CPU cores (balance cost)
- Conservative JVM tuning (favor throughput over latency)

---

## Monitoring and Capacity Alerts

### Key Metrics to Track

1. **Active Subdomain Count**
   - Current vs configured maximum
   - Alert if > 80% of capacity

2. **Resource Utilization**
   - Heap usage per subdomain (detect memory leaks)
   - File descriptor usage (prevent exhaustion)
   - Thread count (detect thread leaks)

3. **Performance Metrics**
   - Spawn latency (p50, p95, p99)
   - Routing latency (p50, p95, p99)
   - Gossip convergence time

4. **Failure Rates**
   - Spawn failure rate (should be < 1%)
   - Routing failure rate (should be < 0.1%)

### Capacity Planning Alerts

| Metric | Warning Threshold | Critical Threshold | Action |
|--------|-------------------|-------------------|---------|
| **Active Subdomains** | > 80% of max | > 90% of max | Add host or scale vertically |
| **Heap Usage** | > 75% of limit | > 90% of limit | Reduce limits or add RAM |
| **FD Usage** | > 80% of ulimit | > 90% of ulimit | Increase ulimit or add host |
| **Spawn Latency p95** | > 1s | > 2s | Reduce load or add CPU |
| **Spawn Failure Rate** | > 1% | > 5% | Immediate investigation |

### Dashboards

**Recommended Metrics Dashboard:**
```
+------------------------+  +------------------------+
| Active Subdomains      |  | Spawn Latency (p95)    |
| [80/100] ▓▓▓▓▓▓▓▓░░░   |  | [750ms] ▓▓▓▓▓▓▓░░░     |
+------------------------+  +------------------------+
| FD Usage               |  | Routing Latency (p95)  |
| [180/200] ▓▓▓▓▓▓▓▓▓░   |  | [25ms] ▓▓▓░░░░░░░      |
+------------------------+  +------------------------+
| Heap Usage             |  | Spawn Failure Rate     |
| [7.5/12GB] ▓▓▓▓▓▓░░░   |  | [0.2%] ▓░░░░░░░░░      |
+------------------------+  +------------------------+
```

---

## Migration and Growth Planning

### Adding Capacity

**Vertical Scaling (Same Host):**
1. Stop ProcessContainerDomain gracefully
2. Increase RAM, CPU, or FD limits
3. Update JVM heap settings
4. Restart and verify performance

**Horizontal Scaling (Add Hosts):**
1. Provision new host with same configuration
2. Deploy ProcessContainerDomain instance
3. Configure load balancer to distribute spawns
4. Monitor for even distribution

### Load Rebalancing

**Scenario:** One host overloaded, others underutilized

**Options:**
1. **Stop & Restart:** Gracefully stop subdomains on hot host, respawn on cold hosts
2. **Migration:** Implement subdomain migration (future work, requires Portal routing changes)
3. **Load Balancer Tuning:** Adjust routing algorithm to favor cold hosts

### Testing Capacity Changes

**Before Production:**
1. **Baseline:** Measure current spawn/routing latency
2. **Incremental:** Add 10 subdomains at a time
3. **Monitor:** Check for degradation at each step
4. **Document:** Record performance at each capacity level

**Validation Checklist:**
- [ ] Spawn latency p95 within SLA (< 1s)
- [ ] Routing latency p95 within SLA (< 50ms)
- [ ] No spawn failures (0% failure rate)
- [ ] Resource utilization < 80% (headroom)
- [ ] No errors in logs

---

## Cost Optimization

### Cloud Instance Sizing

**AWS EC2 (example):**

| Deployment | Instance Type | vCPU | RAM | Cost (est/mo) | Max Subdomains |
|------------|---------------|------|-----|---------------|----------------|
| Small | t3.medium | 2 | 4 GB | $30 | 20 |
| Medium | c5.xlarge | 4 | 8 GB | $120 | 50 |
| Large | c5.2xlarge | 8 | 16 GB | $240 | 100 |

**Google Cloud (example):**

| Deployment | Machine Type | vCPU | RAM | Cost (est/mo) | Max Subdomains |
|------------|--------------|------|-----|---------------|----------------|
| Small | n2-standard-2 | 2 | 8 GB | $50 | 20 |
| Medium | n2-standard-4 | 4 | 16 GB | $100 | 50 |
| Large | n2-standard-8 | 8 | 32 GB | $200 | 100 |

### Right-Sizing Strategy

**Over-Provisioned (Wasteful):**
- 50 subdomains on c5.4xlarge (16 core, 32 GB)
- Utilization: ~25% CPU, ~40% RAM
- **Cost:** 2× higher than necessary

**Right-Sized (Optimal):**
- 50 subdomains on c5.xlarge (4 core, 8 GB)
- Utilization: ~80% CPU, ~75% RAM
- **Cost:** Baseline

**Under-Provisioned (Risky):**
- 50 subdomains on t3.medium (2 core, 4 GB)
- Utilization: 100% CPU, 95% RAM
- **Cost:** 50% cheaper, but spawn failures and OOM errors

**Recommendation:** Target 70-80% utilization for production workloads.

---

## References

- **[PERFORMANCE_BASELINES.md](PERFORMANCE_BASELINES.md)**: SLA definitions
- **[SCALABILITY.md](SCALABILITY.md)**: Detailed scalability analysis
- **[../README.md](../README.md)**: Model module overview
- **[PerformanceSLA.java](../src/main/java/com/hellblazer/delos/model/PerformanceSLA.java)**: SLA constants
- **Delos-fwyq**: Scalability documentation task (this document)
