# Delos Capacity Planning Guide

**Metrics-driven capacity planning with specific triggers and scaling procedures.**

**Audience**: Operations teams, system architects, DevOps engineers
**Version**: 1.0
**Last Updated**: 2026-01-27

---

## Overview

This guide provides specific metrics, triggers, and procedures for capacity planning in Delos. It enables data-driven decisions on when to scale, how to scale, and what to monitor during scaling operations.

**Key Principles**:
1. **Measure first**: Track metrics before scaling
2. **Trigger-based**: Scale when metrics exceed thresholds
3. **Gradual scaling**: Add resources incrementally
4. **Monitor during**: Verify scaling improved metrics
5. **Archive history**: Track growth trends over time

---

## Deployment Sizes

### Small Deployment (1-10K ops/sec)

**Node Configuration**:
- Cluster size: 3-5 nodes
- Hardware: 8GB RAM, 4 CPU cores, 100GB SSD
- Network: 1Gbps link minimum

**Capacity Characteristics**:
- Throughput: 5-10K ops/sec
- Latency p95: 200-300ms
- Storage growth: ~1GB/day (with 7-day retention)

**When to Scale Up**:
- See "Scaling Triggers" section below

---

### Medium Deployment (10-50K ops/sec)

**Node Configuration**:
- Cluster size: 7-9 nodes
- Hardware: 16GB RAM, 8 CPU cores, 500GB SSD
- Network: 10Gbps link recommended

**Capacity Characteristics**:
- Throughput: 30-50K ops/sec
- Latency p95: 250-350ms
- Storage growth: ~5GB/day (with 7-day retention)

**When to Scale Up**:
- See "Scaling Triggers" section below

---

### Large Deployment (50K-500K ops/sec)

**Node Configuration**:
- Cluster size: 13-21 nodes (f=4-6)
- Hardware: 32GB RAM, 16 CPU cores, 2TB SSD
- Network: 40Gbps link recommended

**Capacity Characteristics**:
- Throughput: 100-500K ops/sec (with multiple committees)
- Latency p95: 300-500ms
- Storage growth: ~50GB/day (with 7-day retention)

**When to Scale Up**:
- See "Scaling Triggers" section below

---

## Capacity Planning Metrics

### Compute (CPU)

| Metric | Description | How to Measure | Healthy Range | Scale Trigger |
|--------|-------------|-----------------|---------------|----|
| `cpu_usage_percent` | Average CPU usage across node | `top` or metrics | 10-40% | > 70% sustained |
| `cpu_p99_percent` | Peak CPU usage | Metrics peak | < 80% | > 85% |
| `gc_pause_ms_p99` | GC pause latency | JVM metrics | < 100ms | > 200ms |
| `gc_time_percent` | % time spent in GC | JVM metrics | < 5% | > 10% |

**Scaling Strategy for CPU**:
1. At 60% usage: Monitor closely
2. At 70% usage: Plan scaling (allocate hardware)
3. At 85% usage: Execute scaling (add nodes or resize)
4. > 90%: Emergency (immediate action required)

---

### Memory (RAM)

| Metric | Description | How to Measure | Healthy Range | Scale Trigger |
|--------|-------------|-----------------|---------------|----|
| `jvm_memory_used_mb` | Heap memory used | `jps -l` + jstat | < 70% of max | > 85% |
| `jvm_memory_heap_committed` | Allocated heap | JVM metrics | 60-80% of max | > 90% |
| `dag_size_mb` | DAG memory usage | Ethereal metrics | < 30% of heap | > 50% |
| `state_machine_memory_mb` | SQL-State cache | Metrics | < 20% of heap | > 40% |

**Scaling Strategy for Memory**:
1. At 70% usage: Monitor DAG size (if > 50% of heap, increase heap or reduce DAG depth)
2. At 80% usage: Plan scaling (increase heap or reduce batch sizes)
3. At 85% usage: Execute scaling (add nodes or increase RAM)
4. > 95%: Emergency (OOM kill imminent)

---

### Network Bandwidth

| Metric | Description | How to Measure | Healthy Range | Scale Trigger |
|--------|-------------|-----------------|---------------|----|
| `network_throughput_mbps` | Outbound network usage | `iftop` or metrics | < 100Mbps | > 500Mbps |
| `rpc_latency_p95_ms` | RPC round-trip latency | Metrics | < 100ms | > 200ms |
| `gossip_bandwidth_mbps` | Gossip protocol overhead | Metrics | < 50Mbps | > 200Mbps |
| `packet_loss_percent` | Network packet loss | `mtr` or metrics | < 0.1% | > 1% |

**Scaling Strategy for Bandwidth**:
1. At 100Mbps usage: Monitor packet loss (if > 1%, upgrade network)
2. At 200Mbps usage: Plan network upgrade
3. At 500Mbps usage: Execute network upgrade (10Gbps link)
4. > 1Gbps: Add more nodes to distribute load

---

### Storage/Disk

| Metric | Description | How to Measure | Healthy Range | Scale Trigger |
|--------|-------------|-----------------|---------------|----|
| `disk_usage_percent` | Disk usage | `df -h` | < 50% | > 80% |
| `disk_growth_gb_per_day` | Daily disk growth | Track over 7 days | Varies | Runout in < 30 days |
| `checkpoint_frequency_hours` | Checkpoint interval | Metrics | 1-2 hours | > 4 hours |
| `wal_retention_days` | Write-ahead log retention | Metrics | 7 days | > 14 days |

**Scaling Strategy for Disk**:
1. At 50% usage: Baseline for planning
2. At 60% usage: Calculate runout date (`disk_free_gb / disk_growth_gb_per_day`)
3. At 70% usage: Plan capacity (add storage or reduce retention)
4. At 80% usage: Execute capacity addition
5. > 95%: Emergency (disk full is critical failure)

---

### Consensus/Throughput

| Metric | Description | How to Measure | Healthy Range | Scale Trigger |
|--------|-------------|-----------------|---------------|----|
| `blocks_committed_per_sec` | Block commit rate | `choam_blocks_committed` delta | Target rate | < 50% of target |
| `transactions_per_sec` | Transaction throughput | Sum across all nodes | Target rate | < 50% of target |
| `block_commit_latency_p95_ms` | Latency to commit | Metrics | < 1000ms | > 2000ms |
| `dag_size_units` | DAG unit count | Ethereal metrics | Stable | Growing unbounded |

**Scaling Strategy for Throughput**:
1. At 60% of target: Monitor (acceptable)
2. At 50% of target: Plan scaling (increase cluster size or optimize)
3. At 40% of target: Execute scaling (add committee nodes)
4. < 30% of target: Performance issue (investigate + scale)

---

### Quorum Health

| Metric | Description | How to Measure | Healthy Range | Scale Trigger |
|--------|-------------|-----------------|---------------|----|
| `members_count` | Active member count | `fireflies_members` | Stable | Decreasing |
| `members_suspected` | Suspected member count | `fireflies_suspected` | 0 | > 0 |
| `byzantine_members` | Detected Byzantine members | `byzantine_member_count` | 0 | > 0 |
| `quorum_size_required` | BFT quorum size needed | Calculated | n-f | < n-f |

**Scaling Strategy for Quorum**:
1. Monitor: Members should stay constant
2. If decreasing: Diagnose failures (see FAILURE_MODES.md)
3. If suspected count > 0: Investigate member health
4. If Byzantine count > 0: Isolate and replace member

---

## Scaling Decision Matrix

**Use this matrix to decide when and how to scale:**

```
Metric              | Current Level    | Decision
--------------------|------------------|------------------------------------------
CPU                 | 40-60%          | Monitor
                    | 60-70%          | Plan scaling
                    | 70-85%          | Execute scaling
                    | > 85%           | Emergency scale

Memory              | 50-70%          | Monitor
                    | 70-80%          | Plan scaling
                    | 80-90%          | Execute scaling
                    | > 90%           | Emergency scale

Disk                | 30-50%          | Monitor, calculate runway
                    | 50-70%          | Plan capacity addition
                    | 70-80%          | Execute capacity addition
                    | > 80%           | Emergency action

Throughput          | 80-100% target  | Optimal
                    | 60-80% target   | Monitor
                    | 40-60% target   | Plan scaling
                    | < 40% target    | Emergency scale

Quorum              | All members     | Healthy
                    | f members fail  | Degraded but safe
                    | f+1 members fail| Quorum lost - CRITICAL
```

---

## Scaling Procedures

### Horizontal Scaling (Add Nodes)

**When to use**: Add capacity while maintaining fault tolerance.

**Procedure**:
1. **Preparation** (on new node):
   ```bash
   # Install Delos, configure service
   # Generate new identity via Gorgoneion
   # Copy configuration from existing node
   ```

2. **Bootstrap** (new node joins cluster):
   ```bash
   # Start Delos on new node
   systemctl start delos

   # Verify joining
   curl http://new-node:8080/metrics | grep fireflies_members

   # Wait for full state sync (~1-5 minutes depending on data size)
   ```

3. **Verification** (all nodes):
   ```bash
   # Verify new member in view
   curl http://node:8080/metrics | grep fireflies_members_count
   # Should show increased count

   # Verify consensus continues
   curl http://node:8080/metrics | grep choam_blocks_committed
   # Should show blocks still committing
   ```

4. **Monitor** (first hour):
   - Watch CPU usage on new node (should level off)
   - Monitor commit latency (should remain stable)
   - Watch for Byzantine detections (none expected)

**Success criteria**:
- New node appears in member list
- Consensus continues without interruption
- Latency returns to baseline within 5 minutes
- No Byzantine member detections

---

### Vertical Scaling (Increase Node Resources)

**When to use**: Increase resources on existing nodes (CPU, RAM, disk).

**Procedure for CPU/RAM**:
1. **Plan upgrade** (pick low-usage window)
2. **Rolling restart** (one node at a time):
   ```bash
   # On each node, in sequence:
   systemctl stop delos
   # Upgrade instance type / resize VM
   systemctl start delos
   # Wait for consensus to resume (2-5 minutes)
   # Wait for next node (60-90 second stagger)
   ```

3. **Verification** (after all nodes):
   - CPU usage should be lower
   - Latency should improve
   - No Byzantine detections

**Procedure for Disk**:
1. **Plan upgrade** (can do anytime if > 50% free)
2. **Add storage**:
   ```bash
   # Attach new volume or expand partition
   # Resize filesystem
   sudo resize2fs /dev/nvme0n1p1
   ```

3. **Verify**:
   ```bash
   df -h /var/delos
   # Should show increased capacity
   ```

No restart needed for disk scaling.

---

### Cluster Size Scaling

**Small (3-5) → Medium (7-9)**:
1. Add 2-4 new nodes one at a time
2. Wait 5-10 minutes between additions
3. Quorum improves from f=1 to f=3

**Medium (7-9) → Large (13-21) with Committees**:
1. Add nodes gradually (every 1-2 weeks)
2. Reconfigure committees as cluster grows
3. May use `CHOAM.reconfigureCommittees()` API

---

## Capacity Planning Workflow

### Month 1: Baseline

1. Deploy 3-5 node cluster
2. Track metrics:
   - Daily active users
   - Transactions per second
   - CPU/memory/disk usage
   - Latency p95/p99

3. Store baseline metrics:
   ```bash
   date > metrics-baseline.txt
   curl http://localhost:8080/metrics | grep -E "cpu_|memory_|disk_" >> metrics-baseline.txt
   ```

### Months 2-6: Growth Monitoring

1. **Weekly**: Review metrics
   - Is growth trending up?
   - Current: X%, Target: Y%, Runway: Z days

2. **Monthly**: Generate report
   ```bash
   # Collect monthly metrics
   # Calculate growth rate
   # Project when scaling trigger will hit
   ```

3. **Alert triggering**:
   - Set alert at 60% usage for review
   - Set alert at 70% usage for planning
   - Set alert at 80% usage for execution

### Month 6+: Continuous Scaling

1. Monitor continuously
2. Scale before hitting hard limits (70-80%, not 90%+)
3. Add resources incrementally (don't over-provision)
4. Track efficiency (are resources being used?)

---

## Scaling Anti-Patterns to Avoid

| Anti-Pattern | Why it's wrong | Correct approach |
|---|---|---|
| **Panic scaling** | React after hitting 95% | Proactive scaling at 70-80% |
| **Over-provisioning** | Add 4x resources when 1.5x needed | Scale incrementally, measure |
| **Ignoring metrics** | "It feels slow" | Track specific metrics with triggers |
| **Single failure point** | Only 3 nodes (f=0) | Maintain f >= 1 (4+ nodes) |
| **Network bottleneck** | Scale nodes but not network | Scale network proportional to nodes |
| **No baseline** | "Was it always like this?" | Capture baseline before growing |

---

## Capacity Planning Examples

### Example 1: Growing from 5K to 50K ops/sec

**Initial State** (Month 1):
- 3 nodes
- 5K ops/sec
- CPU: 30%, Memory: 40%, Disk: 20%

**Target** (Month 6):
- 50K ops/sec (10x growth)

**Scaling Plan**:
```
Month 1: Baseline (3 nodes, 5K ops/sec)
Month 2: Add 1 node (4 nodes, 7K ops/sec, f=1) - CPU up to 40%
Month 3: Add 2 nodes (6 nodes, 12K ops/sec, f=1) - CPU at 35%
Month 4: Add 1 node (7 nodes, 25K ops/sec, f=2) - CPU at 40%
Month 5: Add 2 nodes (9 nodes, 50K ops/sec, f=3) - CPU at 45%
Month 6: Verify (9 nodes, 50K ops/sec, all metrics healthy)
```

**Key milestones**:
- Month 2: First scaling trigger (CPU 40%, memory 50%)
- Month 3: Quorum improvement (f increases)
- Month 4: Network monitoring (increasing gossip)
- Month 5: Stabilization

---

### Example 2: Storage Growth Planning

**Initial State**:
- 100GB disk
- 1GB/day growth (with 7-day retention)
- Current usage: 7GB

**Runway Calculation**:
```
Free space: 93GB
Daily growth: 1GB/day
Runway: 93 days (3 months)

Scaling timeline:
- Day 45 (50% usage): Alert to plan
- Day 70 (70% usage): Add storage
- Day 75: Increase retention to 14 days (if beneficial)
```

**Action at triggers**:
- Day 50: Plan 500GB expansion or implement 30-day rotation
- Day 70: Execute expansion or implement compression
- Day 90: Re-evaluate (may need ongoing growth management)

---

## Monitoring During Scaling

**During each scaling operation, monitor**:

1. **Consensus health**:
   ```bash
   # Should remain stable (no 0 blocks)
   curl http://localhost:8080/metrics | grep choam_blocks_committed
   ```

2. **Member stability**:
   ```bash
   # New member should join within 2 minutes
   curl http://localhost:8080/metrics | grep fireflies_members_count
   ```

3. **Latency**:
   ```bash
   # May increase briefly, should return to baseline
   curl http://localhost:8080/metrics | grep rpc_latency_p95
   ```

4. **Byzantine detections** (should be 0):
   ```bash
   curl http://localhost:8080/metrics | grep byzantine_member_count
   ```

**Success criteria**:
- All metrics stable within 5-10 minutes
- No new Byzantine detections
- Blocks committed continuously

---

## Capacity Planning Checklist

**Monthly Capacity Review**:
- [ ] CPU usage trending? (up/down/stable)
- [ ] Memory usage stable? (no unbounded growth)
- [ ] Disk usage: runway calculation
- [ ] Throughput: on track to target?
- [ ] Latency: within acceptable range?
- [ ] Quorum: all members healthy?
- [ ] Growth rate: when will next trigger hit?
- [ ] Resources: should we pre-order hardware?

**Quarterly Capacity Plan**:
- [ ] Update 12-month projection
- [ ] Identify scaling windows
- [ ] Schedule hardware procurement
- [ ] Plan cluster reorganization (if needed)
- [ ] Update runbooks with new node counts
- [ ] Review and adjust thresholds (if needed)

---

## References

- [PERFORMANCE_TUNING.md](PERFORMANCE_TUNING.md) - Baseline metrics and tuning
- [MONITORING_AND_ALERTING.md](MONITORING_AND_ALERTING.md) - Metrics and dashboards
- [OPERATIONAL_PROCEDURES.md](OPERATIONAL_PROCEDURES.md) - Adding nodes to cluster
- [FAILURE_MODES.md](FAILURE_MODES.md) - Understanding failure cascades

---

**Document Version**: 1.0
**Last Updated**: 2026-01-27
**Maintained By**: Delos Operations Team
