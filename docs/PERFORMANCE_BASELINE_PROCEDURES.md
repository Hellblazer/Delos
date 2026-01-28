# Performance Baseline Procedures

**Establishing, maintaining, and detecting performance regression in production Delos deployments.**

**Audience**: Operations teams, DevOps engineers, performance engineers
**Version**: 1.0
**Last Updated**: 2026-01-27

---

## Overview

This guide provides step-by-step procedures for:
1. **Establishing baseline** - Measure performance in your specific environment before production
2. **Monitoring regression** - Detect performance degradation early
3. **Troubleshooting degradation** - Identify and fix causes
4. **SLA definition** - Set realistic performance targets

A baseline is your environment's "normal" performance under controlled conditions. It's the foundation for detecting problems.

---

## Pre-Baseline Checklist

Before measuring baseline, verify your environment:

- [ ] **Hardware**: Dedicated test cluster (no other workloads), representative of production
- [ ] **Network**: Stable connectivity, <2ms latency between nodes (measure with `ping`)
- [ ] **Clock**: All nodes synchronized within 100ms (run `ntpdate -q time.nist.gov` on each)
- [ ] **Kernel**: Load testing tools installed (`iperf3`, `sysbench`)
- [ ] **Monitoring**: Metrics collection enabled (Prometheus/Grafana or similar)
- [ ] **Database**: H2 database initialized, checkpoint settings configured
- [ ] **JVM**: Heap size configured per PERFORMANCE_TUNING.md (typically 4GB minimum)
- [ ] **Version**: All nodes running exact same Delos version (no mixed versions)

---

## Phase 1: Establish Baseline (Day 1-2)

### Step 1.1: Document Environment

Create `baseline-environment.txt`:

```bash
#!/bin/bash
# Record environment details

echo "=== BASELINE ENVIRONMENT $(date) ===" > baseline-environment.txt
echo "" >> baseline-environment.txt

echo "### Hardware" >> baseline-environment.txt
lscpu >> baseline-environment.txt
echo "" >> baseline-environment.txt

echo "### Memory" >> baseline-environment.txt
free -h >> baseline-environment.txt
echo "" >> baseline-environment.txt

echo "### Disk" >> baseline-environment.txt
df -h /var/delos >> baseline-environment.txt
echo "" >> baseline-environment.txt

echo "### Network (measure latency between nodes)" >> baseline-environment.txt
for node in node-2 node-3; do
  echo "Latency to $node:" >> baseline-environment.txt
  ping -c 5 $node | tail -1 >> baseline-environment.txt
done
echo "" >> baseline-environment.txt

echo "### Java Version" >> baseline-environment.txt
java -version 2>&1 >> baseline-environment.txt
echo "" >> baseline-environment.txt

echo "### Delos Version" >> baseline-environment.txt
curl -s http://localhost:8080/metrics | grep delos_version >> baseline-environment.txt
```

**Keep this file**: You'll compare against it when investigating performance changes.

---

### Step 1.2: Warm Up the System (1 hour)

Run traffic at 50% of target throughput for 1 hour to:
- Trigger JVM compilation (JIT warmup)
- Fill caches
- Establish steady-state GC patterns

```bash
# Example: 50% of 10K ops/sec = 5K ops/sec
# Use your load generator (e.g., gRPC client, HTTP load test)

load-generator \
  --target-throughput 5000 \
  --duration 1h \
  --transaction-size 1KB \
  --output baseline-warmup.log
```

**Metrics to observe during warmup**:
- CPU usage gradually increases then stabilizes
- Latency initially high, then decreases (cache warming)
- GC frequency stabilizes
- Checkpoint frequency steady

If not stabilized after 1 hour, investigate causes (check CPU, memory, GC logs).

---

### Step 1.3: Measure Baseline (2 hours)

Run at **target throughput** for 2 hours. This establishes your operational baseline.

```bash
load-generator \
  --target-throughput 10000 \
  --duration 2h \
  --transaction-size 1KB \
  --concurrent-clients 50 \
  --output baseline-measurements.log \
  --metrics-interval 10s
```

**Key metrics to capture** (every 10 seconds):

```bash
# In separate terminal, collect system metrics
while true; do
  echo "=== $(date)" >> baseline-system.log

  # CPU and memory
  top -bn1 | grep "java" >> baseline-system.log

  # Disk I/O
  iostat -x 1 1 | grep nvme0n1 >> baseline-system.log

  # Network
  iftop -n -s 1 -t >> baseline-network.log

  # JVM metrics
  curl -s http://localhost:8080/metrics | grep -E "jvm_|gc_" >> baseline-jvm.log

  sleep 10
done
```

**Application-level metrics to collect** (from HTTP /metrics endpoint):

```
- choam_blocks_committed_per_sec  (throughput)
- choam_consensus_latency_p95_ms  (consensus latency)
- choam_replication_latency_p95_ms (replication latency)
- fireflies_members_count          (cluster health)
- jvm_memory_used_mb               (heap usage)
- jvm_gc_time_percent              (GC pressure)
```

---

### Step 1.4: Analyze Results

After 2 hours of measurement, calculate baseline statistics:

```bash
#!/bin/bash
# Analyze baseline measurements

echo "=== BASELINE ANALYSIS $(date)" > baseline-results.txt
echo "" >> baseline-results.txt

# Latency percentiles (P50, P95, P99)
echo "### Latency Percentiles" >> baseline-results.txt
awk '{print $LATENCY_FIELD}' baseline-measurements.log | \
  sort -n | \
  awk 'BEGIN{count=0} {latencies[count++]=$1} END{
    p50=int(count*0.50); p95=int(count*0.95); p99=int(count*0.99);
    print "P50:", latencies[p50], "ms"
    print "P95:", latencies[p95], "ms"
    print "P99:", latencies[p99], "ms"
  }' >> baseline-results.txt
echo "" >> baseline-results.txt

# Throughput (avg and min/max)
echo "### Throughput (ops/sec)" >> baseline-results.txt
awk '{sum+=$1; count++} END{print "Average:", sum/count}' baseline-measurements.log >> baseline-results.txt
echo "" >> baseline-results.txt

# Resource usage (peaks and averages)
echo "### Memory (MB)" >> baseline-results.txt
echo "Peak:" $(grep "java" baseline-system.log | awk '{print $6}' | sort -n | tail -1) >> baseline-results.txt
echo "" >> baseline-results.txt

# GC impact
echo "### GC Impact" >> baseline-results.txt
grep "gc_time_percent" baseline-jvm.log | tail -10 >> baseline-results.txt
```

**Expected baseline results** (from PERFORMANCE_TUNING.md):
- Latency P95: 200-300ms (network + consensus dominated)
- Throughput: 5-10K ops/sec for 3-node cluster
- Memory: 1-2GB heap under sustained load
- GC time: <5% under normal load

**If results differ significantly from expected**, investigate:
- Network latency: `ping` between nodes (should be <2ms)
- Clock skew: Compare times on all nodes (`date` on each)
- GC tuning: Check `-Xmx` and `-Xms` settings
- Contention: Monitor lock hold times in logs

---

### Step 1.5: Document Baseline

Create `BASELINE.md` in your deployment directory:

```markdown
# Performance Baseline - [Environment Name]

**Established**: 2026-01-27
**Cluster**: 5 nodes
**Region**: us-east-1
**Hardware**: AWS c5.2xlarge (8 CPU, 16GB RAM)

## Results

### Latency (Target throughput: 10K ops/sec)
- P50: 150ms
- P95: 250ms
- P99: 400ms
- Max: 600ms

### Throughput
- Sustained: 9.8K ops/sec
- Peak burst: 12K ops/sec

### Resource Usage
- Memory peak: 2.1GB
- Memory avg: 1.8GB
- GC time: 2.3%
- Disk I/O avg: 15MB/sec

### Environment
- Network latency: 1.2ms avg between nodes
- Clock skew: <50ms
- Java version: 25.0
- Delos version: 1.0.0

## Maintenance
- **Review date**: 2026-04-27 (90 days)
- **Next baseline**: When cluster size changes, hardware upgrades, or Delos version updates
```

---

## Phase 2: Continuous Monitoring (Ongoing)

### Step 2.1: Daily Baseline Check

Once per day, run a quick 15-minute baseline check:

```bash
#!/bin/bash
# Daily baseline check (15 minutes)

echo "=== DAILY BASELINE CHECK $(date) ===" >> daily-baselines.log

load-generator \
  --target-throughput 10000 \
  --duration 15m \
  --output /dev/null \
  --json-output daily-check-$(date +%s).json

# Compare against baseline
python3 compare-baseline.py \
  --current daily-check-*.json \
  --baseline BASELINE.md \
  --threshold 10% \
  >> daily-baselines.log 2>&1
```

**Threshold**: Alert if any metric deviates >10% from baseline.

**If daily check fails**:
1. Run full re-baseline (Phase 1 above)
2. Investigate root cause (see Phase 3 below)
3. Update BASELINE.md if degradation is persistent and justified

---

### Step 2.2: Weekly Trend Analysis

Collect daily baseline results and plot trends:

```bash
#!/bin/bash
# Weekly trend analysis (Friday)

echo "=== WEEKLY TREND ANALYSIS $(date)" >> weekly-analysis.log

# Extract metrics from daily checks
for file in daily-check-*.json; do
  jq '.latency.p95, .throughput, .memory_peak' $file >> weekly-trends.csv
done

# Plot trends
gnuplot << EOF
set datafile separator ","
plot "weekly-trends.csv" using 1 with lines title "Latency P95"
EOF
```

**Expected behavior**: Stable (flat) trend line. If trending up (degradation):
1. Check for software changes (new deployment?)
2. Check for workload changes (more traffic?)
3. Check for hardware changes (disk filling up?)

---

### Step 2.3: Metrics to Track

Set up monitoring dashboard with these metrics:

| Metric | Baseline | Warning | Critical |
|--------|----------|---------|----------|
| Consensus latency P95 | 250ms | >300ms | >400ms |
| Throughput | 9.8K ops/sec | <8K ops/sec | <5K ops/sec |
| Memory usage (avg) | 1.8GB | >2.2GB | >2.5GB |
| GC time percent | 2.3% | >5% | >10% |
| Checkpoint latency P95 | 50ms | >75ms | >100ms |
| Heap memory used | 1.5GB | >2.0GB | >2.5GB |

Set up alerts:
- **Warning**: Alert operations team, schedule investigation
- **Critical**: Page on-call engineer immediately

---

## Phase 3: Regression Investigation

If daily baseline check fails or metrics exceed thresholds:

### Step 3.1: Identify the Change

```bash
# 1. Check recent deployments
git log --oneline -20

# 2. Check for system changes
systemctl list-units --state=failed

# 3. Check cluster changes (node count, configuration)
curl http://localhost:8080/metrics | grep fireflies_members_count

# 4. Check resource pressure
df -h /var/delos                    # Disk space
free -h                             # Memory
top -bn1 | grep -E "Cpu|Mem"       # CPU and memory usage

# 5. Check NTP status (clock skew)
ntpstat
chronyc sources
```

### Step 3.2: Categorize the Problem

Based on the change, categorize:

**Software change** (deployment, version upgrade):
- Revert to previous version: `systemctl stop delos && git checkout <previous-version> && systemctl start delos`
- Re-baseline to confirm regression
- File performance regression issue with previous version diff

**Hardware/resource change** (disk full, memory pressure):
- Free disk: Archive old checkpoints, delete WAL files beyond retention
- Clear memory: Restart node during maintenance window
- Re-baseline after recovery

**Workload change** (more traffic, different transaction types):
- Adjust baseline expectations if workload has legitimately increased
- If traffic spike unexpected, investigate source (abuse? Customers changing usage pattern?)
- Set alerts for expected changes (pre-announce capacity changes)

**Network change** (latency, packet loss):
- Measure latency: `mtr <remote-node>` (run for 60+ seconds)
- Check packet loss: Should be <0.1%
- If >2% packet loss, investigate network (switch misconfiguration, cable issues)
- Re-baseline after network stabilization

### Step 3.3: Root Cause Analysis

For each category:

**Software regression**:
```bash
# Compare performance between versions
git checkout <baseline-version>
./mvnw clean install
# Run baseline tests

git checkout <new-version>
./mvnw clean install
# Run same baseline tests

# Diff performance metrics
# If >5% degradation, investigate:
# - New synchronized sections (lock contention)
# - New memory allocations (GC pressure)
# - New network round-trips (latency)
```

**Memory regression**:
```bash
# Capture heap dump
jcmd $(pgrep -f "java.*delos") GC.heap_dump /tmp/heap-$(date +%s).hprof

# Analyze with Eclipse MAT
# Look for: Growing object counts, large collections
```

**GC regression**:
```bash
# Collect GC logs
java -Xmx4g -Xms2g -XX:+PrintGCDetails -XX:+PrintGCDateStamps \
  -Xloggc:gc.log ...

# Analyze with GC Easy or IBM GC Analyzer
# Look for: Increasing pause times, more frequent full GCs
```

---

## Phase 4: SLA Definition

After baseline established, define SLA for your deployment:

### Step 4.1: Write SLA

```markdown
# Performance SLA - Production Delos Cluster

**Cluster**: us-east-1 production
**Established**: 2026-01-27
**Review date**: 2026-04-27

## Commitments

### Throughput
- Minimum sustained: 8K ops/sec (90th percentile day)
- Expected average: 9.8K ops/sec
- Emergency threshold: <5K ops/sec (page on-call)

### Latency
- P95: <300ms (95% of requests)
- P99: <400ms (99% of requests)
- Max acceptable: <1s

### Availability
- Target: 99.9% uptime (43 minutes downtime per month)
- Planned maintenance: 2-hour window monthly (excluded from uptime)

### Resource Usage
- Heap memory: <2.5GB (emergency if >3GB)
- CPU: <80% sustained (emergency if >90%)
- Disk: >20% free space required (scale if <30% free)

## Monitoring

- Dashboard: [Grafana link]
- Alerts: Performance Slack channel
- On-call escalation: PagerDuty

## Response

If SLA violated:
1. Alert operations team (auto-page if critical)
2. Diagnose root cause (30 minutes)
3. Execute mitigation (restart node, scale, rollback - 15 minutes)
4. Post-incident review within 24 hours
```

### Step 4.2: Adjust Baseline for Workload

Different workloads have different characteristics. Adjust baseline:

**Small transactions (100B)**:
- Throughput: Higher (more transactions in same time)
- Latency: Same (network + consensus time)
- Memory: Lower (less state)

**Large transactions (10KB)**:
- Throughput: Lower (fewer transactions in same time)
- Latency: Same (consensus dominates)
- Memory: Higher (more buffering)

**Mix (1KB average)**:
- Use baseline metrics (derived from 1KB)

---

## Quarterly Review Procedure

Every 90 days, review and update baseline:

### Review Checklist

- [ ] Run full 2-hour baseline test
- [ ] Compare P95 latency (acceptable: ±5%)
- [ ] Compare throughput (acceptable: ±5%)
- [ ] Compare memory peak (acceptable: ±10%)
- [ ] Compare GC time (acceptable: ±2%)

**If metrics drifted >5%**:
- [ ] Identify what changed (hardware? software? workload?)
- [ ] Decide if drift is acceptable (expected growth?)
- [ ] Update BASELINE.md or investigate regression

**If cluster size changed**:
- [ ] Re-baseline with new size
- [ ] Create new BASELINE.md
- [ ] Update SLA accordingly

**If Delos version upgraded**:
- [ ] Re-baseline to compare performance
- [ ] Document any regressions/improvements
- [ ] Update documentation

---

## Common Causes of Degradation

| Symptom | Possible Cause | Solution |
|---------|---|---|
| Latency creeping up | GC pressure increasing | Increase heap size, reduce batch sizes |
| Throughput down 10%+ | Network latency increased | Check NTP sync, network switch, cable |
| Memory peak rising | Cache size increasing | Check for memory leaks, restart node |
| GC time >10% | Heap too small or too many short-lived objects | Increase heap or profile allocations |
| Checkpoint latency spiking | Disk I/O bottleneck | Check disk performance, archiving |
| Members becoming suspect | Clock skew | Verify NTP, restart chrony/ntpd |

---

## Tools

**Recommended monitoring tools**:
- **Prometheus**: Metrics collection (pulls from /metrics endpoint)
- **Grafana**: Visualization and dashboards
- **JConsole**: JVM heap and GC monitoring
- **Eclipse MAT**: Heap dump analysis
- **iperf3**: Network performance testing
- **sysbench**: CPU/memory stress testing

---

## References

- [PERFORMANCE_TUNING.md](PERFORMANCE_TUNING.md) - Baseline metrics and tuning parameters
- [CAPACITY_PLANNING.md](CAPACITY_PLANNING.md) - When to scale, what triggers to monitor
- [MONITORING_AND_ALERTING.md](MONITORING_AND_ALERTING.md) - Setting up metrics dashboards
- [FAILURE_MODES.md](FAILURE_MODES.md) - Understanding failure scenarios and recovery

---

**Document Version**: 1.0
**Last Updated**: 2026-01-27
**Maintained By**: Delos Operations Team
