# Delos Hardware Requirements & Capacity Planning

**Document Version**: 1.0
**Date**: 2026-01-27
**Status**: Production
**Audience**: DevOps engineers, capacity planners, system architects

---

## Quick Reference: Cluster Sizing

| Cluster Size | Use Case | Min CPU | Min Memory | Min Storage | Network | Cost/Month |
|---|---|---|---|---|---|---|
| **4-node** | Dev/test, high fault tolerance (f=1) | 8 cores | 16 GB | 160 GB | 1 Gbps | $600-1200 |
| **7-node** | Small production (f=2) | 20 cores | 56 GB | 560 GB | 1 Gbps | $1400-2800 |
| **13-node** | Medium production (f=4) | 40 cores | 112 GB | 1.04 TB | 1 Gbps | $2500-5000 |
| **25-node** | Large production (f=8) | 80 cores | 224 GB | 2.0 TB | 10 Gbps | $5000-10000 |

---

## Table of Contents

1. [Byzantine Fault Tolerance & Sizing](#byzantine-fault-tolerance--sizing)
2. [Minimum Requirements](#minimum-requirements)
3. [Recommended Configurations](#recommended-configurations)
4. [Hardware Specifications](#hardware-specifications)
5. [Network Requirements](#network-requirements)
6. [Storage Planning](#storage-planning)
7. [Performance Scaling](#performance-scaling)
8. [Cloud Provider Sizing](#cloud-provider-sizing)

---

## Byzantine Fault Tolerance & Sizing

### Understanding f and n

**Byzantine Fault Tolerance Formula**: n ≥ 3f + 1

Where:
- **n** = total number of nodes
- **f** = number of Byzantine (faulty) nodes the system can tolerate

### Cluster Size Selection

**4-node cluster (f=1)**
- Can tolerate 1 Byzantine node
- Consensus requires 3/4 nodes (75%)
- Use case: Development, testing, non-critical services
- Cost: Lowest
- Fault tolerance: Low

⚠️ **CRITICAL WARNING**: 4-node clusters (f=1) are suitable for **crash failures only**. They provide NO Byzantine fault tolerance against malicious or compromised nodes. For production deployments with Byzantine fault tolerance requirements, use 7+ nodes (f=2 minimum). See [Byzantine Fault Tolerance & Sizing](#byzantine-fault-tolerance--sizing) and [ARCHITECTURE.md](ARCHITECTURE.md#byzantine-fault-tolerance-model) for details.

**7-node cluster (f=2)**
- Can tolerate 2 Byzantine nodes
- Consensus requires 5/7 nodes (71%)
- Use case: Small production deployments
- Cost: Moderate
- Fault tolerance: Medium
- **RECOMMENDED MINIMUM for production**

**13-node cluster (f=4)**
- Can tolerate 4 Byzantine nodes
- Consensus requires 9/13 nodes (69%)
- Use case: Medium production deployments, regional service
- Cost: High
- Fault tolerance: High

**25-node cluster (f=8)**
- Can tolerate 8 Byzantine nodes
- Consensus requires 17/25 nodes (68%)
- Use case: Large production, multi-region service
- Cost: Very high
- Fault tolerance: Very high

### Selecting Cluster Size

**Ask these questions**:

1. **What's the cost of 1 Byzantine node?** (Data loss, service interruption, incident response)
   - Low cost → f=1 (4 nodes) is acceptable
   - Medium cost → f=2 (7 nodes) recommended
   - High cost → f=4 (13 nodes) required

2. **What's the probability of hardware failure?**
   - Good hardware (99.99% uptime) → f=1
   - Standard hardware (99.95% uptime) → f=2
   - Unreliable hardware or WAN → f=4

3. **What's the blast radius of a single node failure?**
   - Single node failure = isolated service impact → f=1
   - Single node failure = cascading failures → f=2
   - Multiple simultaneous failures possible → f=4

---

## Minimum Requirements

### Development/Test Environment

**Cluster Configuration**: 4 nodes (f=1)

**Per-Node Hardware**:
- **CPU**: 2 cores (can be shared VM)
- **Memory**: 4 GB
- **Storage**: 40 GB SSD
- **Network**: 100 Mbps (can be virtualized)
- **Java**: 21+ (JDK or GraalVM)

**Total Cluster**:
- CPU: 8 cores
- Memory: 16 GB
- Storage: 160 GB
- Estimated monthly cost: $300-600 (on-demand cloud)

**Example**: AWS t3.medium × 4 nodes

### Small Production

**Cluster Configuration**: 7 nodes (f=2)

**Per-Node Hardware**:
- **CPU**: 2-4 cores
- **Memory**: 8 GB
- **Storage**: 80 GB SSD
- **Network**: 1 Gbps
- **Java**: 25 LTS (required for production)

**Total Cluster**:
- CPU: 14-28 cores
- Memory: 56 GB
- Storage: 560 GB
- Estimated monthly cost: $800-1600 (on-demand cloud)

**Example**: AWS t3.large × 7 nodes

---

## Recommended Configurations

### Development Configuration

**Optimal for**: Local development, testing new features, CI/CD builds

**Per-node specs**:
- **CPU**: 4 cores
- **Memory**: 8 GB
- **Storage**: 100 GB SSD
- **Network**: 1 Gbps
- **Java**: 25
- **OS**: Linux (Ubuntu 22.04 LTS), macOS, Windows

**Cluster**: 4-7 nodes (depends on test scope)

**Total cost**: $400-800/month (cloud) or $3000-5000 (one-time hardware)

---

### Small Production Configuration (f=2)

**Optimal for**: Startup, single-region service, <50K transactions/sec

**Per-node specs**:
- **CPU**: 4 cores (not shared/throttled)
- **Memory**: 8-16 GB
- **Storage**: 200 GB SSD (NVME preferred)
- **Network**: 1 Gbps dedicated
- **Java**: 25 LTS
- **OS**: Linux (Ubuntu 22.04 LTS, CentOS 8+)
- **Hypervisor**: Bare metal or VM with dedicated resources

**Cluster**: 7 nodes

**Total resources**:
- 28 cores
- 56-112 GB memory
- 1.4 TB storage
- Estimated monthly cost: $1400-2800 (reserved instances)

**Example deployment**:
- AWS: m6i.xlarge × 7 (reserved instances)
- Azure: Standard_D4s_v3 × 7
- On-prem: Dell PowerEdge R6515 × 7

---

### Medium Production Configuration (f=4)

**Optimal for**: Enterprise, multi-region, 50K-500K transactions/sec

**Per-node specs**:
- **CPU**: 8 cores
- **Memory**: 32 GB
- **Storage**: 500 GB SSD NVME
- **Network**: 10 Gbps or dual 1 Gbps bonded
- **Java**: 25 LTS
- **OS**: Linux (RHEL 8+, Ubuntu 22.04 LTS)

**Cluster**: 13 nodes

**Total resources**:
- 104 cores
- 416 GB memory
- 6.5 TB storage
- Estimated monthly cost: $3500-7000 (reserved instances)

**Example deployment**:
- AWS: c6i.2xlarge × 13 (reserved instances)
- Azure: Standard_D8s_v3 × 13
- On-prem: Dell PowerEdge R7515 × 13

---

## Hardware Specifications

### CPU Requirements

**Processor Selection**:

| Processor | Latency | Throughput | Cost | Notes |
|---|---|---|---|---|
| **Intel Xeon Gold** | Excellent | Excellent | High | Production recommended |
| **AMD EPYC** | Excellent | Excellent | Medium | Good value, excellent performance |
| **Intel Core i7/i9** | Good | Good | Medium | Development acceptable |
| **ARM (Graviton)** | Good | Good | Low | Cost-effective for cloud |

**CPU Tuning**:

```bash
# Disable CPU frequency scaling for consistent performance
echo 'scaling_governor=performance' | sudo tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor

# Check current CPU frequency
cat /proc/cpuinfo | grep MHz
```

### Memory Requirements

**Memory Calculation**: `n × 4GB + cache_size`

- **n** = number of nodes
- **4GB** = base per-node allocation
- **cache_size** = depends on transaction throughput

**Examples**:

7-node cluster:
- Base: 7 × 4GB = 28 GB
- Cache (10K tx/sec): 14 GB = 42 GB total
- Cache (25K tx/sec): 28 GB = 56 GB total
- **Recommended**: 64 GB total cluster memory

13-node cluster:
- Base: 13 × 4GB = 52 GB
- Cache (100K tx/sec): 52 GB = 104 GB total
- **Recommended**: 128 GB total cluster memory

**Memory Tuning**:

```bash
# Per-node JVM heap size
export JAVA_OPTS="-Xmx8g -Xms4g -XX:+UseG1GC"

# For 32GB total node memory:
# -Xmx8g = 8GB heap (25% available)
# -Xms4g = 4GB initial (12.5% available)
# Remaining 20GB = OS + off-heap + caches
```

### Storage Requirements

**Storage Calculation**: `n × 80GB + data_retention × throughput`

- **n** = number of nodes
- **80GB** = base per-node (logs, snapshots)
- **data_retention** = how long to keep transaction logs (days)
- **throughput** = average transactions/sec

**Examples**:

7-node cluster, 10K tx/sec, 30-day retention:
- Base: 7 × 80GB = 560 GB
- Logs (30 days): 10,000 × 30 × 86,400 × 200 bytes = ~500 GB
- Total: 1.06 TB
- **Recommend**: 1.5 TB with overhead

13-node cluster, 100K tx/sec, 7-day retention:
- Base: 13 × 80GB = 1.04 TB
- Logs (7 days): 100,000 × 7 × 86,400 × 200 bytes = ~121 TB
- **Not practical for local storage**
- Use: 2TB SSD + external storage for archive

**Storage Type**:

| Type | Latency | Cost | Use Case |
|------|---------|------|----------|
| **SSD NVMe** | <1ms | $$$ | Primary (strongly recommended) |
| **SSD SATA** | 1-5ms | $$ | Acceptable |
| **HDD** | 5-20ms | $ | NOT RECOMMENDED (consensus latency > 5s) |
| **Network Storage** | 5-50ms | Varies | Backup only, not primary |

---

## Network Requirements

### Network Bandwidth Calculation

**Required bandwidth**: `n × throughput × message_size / 8`

- **n** = number of nodes (each sends/receives)
- **throughput** = transactions/sec
- **message_size** = average transaction size (bytes)

**Examples**:

7-node cluster, 10K tx/sec, 200 bytes:
- Bandwidth: 7 × 10,000 × 200 / 8 / 1,000,000 = 1.75 Gbps
- **Recommended**: 10 Gbps uplink with burst capacity

13-node cluster, 100K tx/sec, 200 bytes:
- Bandwidth: 13 × 100,000 × 200 / 8 / 1,000,000 = 32.5 Gbps
- **Requires**: 40 Gbps or multi-path

### Network Configuration

**For 7-node cluster**:
- Latency within cluster: <10 ms (same datacenter)
- Bandwidth between nodes: 1 Gbps dedicated per node
- Network redundancy: Bonded dual 1 Gbps NICs
- Switch uplink: 10+ Gbps

**For 13-node cluster**:
- Latency: <5 ms (same datacenter, low-latency switches)
- Bandwidth: 10 Gbps per node
- Network redundancy: Dual 10 Gbps NICs with load balancing
- Switch uplink: 100+ Gbps

**Clock Synchronization**:

```bash
# NTP configuration (required for Byzantine consensus)
sudo apt-get install chrony
sudo systemctl enable chronyd
sudo systemctl start chronyd

# Verify clock sync
timedatectl status
chronyc tracking

# Max clock skew: <500ms between nodes (critical)
```

---

## Storage Planning

### Transaction Log Size Estimation

**Formula**: `throughput × duration × message_size × replication_factor`

**Parameters**:
- **throughput**: Average transactions/sec (typically 10K-100K)
- **duration**: Log retention period (days)
- **message_size**: Average transaction size (100-1KB)
- **replication_factor**: Copies per transaction (typically 1-2)

**Examples**:

Small production (10K tx/sec, 30 days, 200 bytes):
- Size = 10,000 × (30×86,400) × 200 × 1 = 518 GB

Medium production (50K tx/sec, 14 days, 500 bytes):
- Size = 50,000 × (14×86,400) × 500 × 1 = 302 GB

Large production (100K tx/sec, 7 days, 1KB):
- Size = 100,000 × (7×86,400) × 1024 × 1 = 604 GB

### Backup Storage

**Minimum**: 3× primary storage (for 3 full backups rotation)

**Recommended**: 10× primary storage (for versioning and disaster recovery)

---

## Performance Scaling

### Throughput vs. Cluster Size

| Cluster Size | Max Throughput | Consensus Latency | Notes |
|---|---|---|---|
| 4 nodes | 8K tx/sec | 80ms | Development/test |
| 7 nodes | 5K tx/sec | 120ms | Small production |
| 13 nodes | 2K tx/sec | 300ms | Medium production, high fault tolerance |
| 25 nodes | 1K tx/sec | 500ms | Large distributed clusters |

**Key insight**: Larger clusters have higher fault tolerance but lower throughput (consensus coordination overhead).

### Latency vs. Network

| Network Latency | Consensus Impact | Recommendation |
|---|---|---|
| <10ms | +0% | Single datacenter (preferred) |
| 10-50ms | +5-10% | Same region, different datacenters |
| 50-100ms | +20-30% | Different regions (acceptable for some use cases) |
| >100ms | +50%+ | NOT RECOMMENDED (exceeds SLA for most) |

---

## Cloud Provider Sizing

### AWS

**Small Production (7 nodes, f=2)**:
```
Instance: m6i.xlarge (4 vCPU, 16GB RAM)
Storage: 200GB gp3 SSD per instance
Network: Enhanced networking (10 Gbps)
Cost: ~$200/node/month (reserved instances)
Total: ~$1,400/month
```

**Medium Production (13 nodes, f=4)**:
```
Instance: c6i.2xlarge (8 vCPU, 32GB RAM)
Storage: 500GB io2 SSD per instance (optimized for IOPS)
Network: 10 Gbps enhanced networking
Cost: ~$350/node/month (reserved instances)
Total: ~$4,550/month
```

### Azure

**Small Production (7 nodes)**:
```
VM: Standard_D4s_v3 (4 vCPU, 16GB RAM)
Storage: 200GB Premium SSD per node
Cost: ~$180/node/month
Total: ~$1,260/month
```

**Medium Production (13 nodes)**:
```
VM: Standard_D8s_v3 (8 vCPU, 32GB RAM)
Storage: 500GB Premium SSD per node
Cost: ~$320/node/month
Total: ~$4,160/month
```

### Google Cloud

**Small Production (7 nodes)**:
```
Machine: n2-standard-4 (4 vCPU, 16GB RAM)
Storage: 200GB SSD persistent disk
Cost: ~$170/node/month
Total: ~$1,190/month
```

---

## Pre-Deployment Checklist

- [ ] Cluster size determined (n ≥ 3f+1)
- [ ] CPU cores allocated per node
- [ ] Memory per node meets minimum (8GB for production)
- [ ] Storage per node sized for log retention
- [ ] Network latency verified (<10ms within cluster)
- [ ] Network bandwidth available (1 Gbps minimum for 7-node)
- [ ] NTP synchronized across all nodes
- [ ] Java 25+ installed and verified
- [ ] Storage IOPS tested (>10K IOPS recommended)
- [ ] Redundancy configured (bonded NICs, multi-path storage)

---

## Related Documentation

- [Deployment Guide](DEPLOYMENT_GUIDE.md) - Node setup procedures
- [Performance Tuning](PERFORMANCE_TUNING.md) - Optimization guidelines
- [Monitoring Guide](MONITORING_GUIDE.md) - Health checks and baselines
- [Capacity Planning](OPERATIONAL_PROCEDURES.md) - Runtime capacity management
