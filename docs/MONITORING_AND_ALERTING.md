# Delos Monitoring & Alerting Guide

**Document Version**: 2.0
**Date**: 2026-01-27
**Status**: Production
**Audience**: Operations, SRE, DevOps teams

---

## Quick Reference: Health Check Commands

```bash
# Node health status
curl -s http://localhost:8080/health | jq '.status'

# Deep health check (validates all subsystems)
curl -s http://localhost:8080/health/deep | jq '.'

# Cluster view members
curl -s http://localhost:8080/metrics | grep fireflies_view_size

# Consensus latency (milliseconds)
curl -s http://localhost:8080/metrics | grep choam_consensus_latency_95th

# Alert on missing metrics (node may be unhealthy)
curl -s http://localhost:8080/metrics | grep -c 'choam' || echo "ALERT: Missing metrics"
```

---

## Table of Contents

1. [Metrics Architecture](#metrics-architecture)
2. [Health Check Endpoints](#health-check-endpoints)
3. [Core Metrics (50+)](#core-metrics-50)
4. [SLA Definitions](#sla-definitions)
5. [Alerting Rules](#alerting-rules)
6. [Prometheus Setup](#prometheus-setup)
7. [Grafana Dashboards](#grafana-dashboards)
8. [Log Analysis](#log-analysis)
9. [Troubleshooting with Metrics](#troubleshooting-with-metrics)

---

## Metrics Architecture

### Metrics Collection Stack

```
Delos Node
    ├─ Dropwizard Metrics (in-process collection)
    │  ├─ Counters (cumulative)
    │  ├─ Gauges (current values)
    │  ├─ Timers (latency distributions)
    │  └─ Histograms (value distributions)
    │
    └─ HTTP Metrics Endpoints
       ├─ /metrics (Prometheus text format)
       ├─ /metrics/json (JSON format)
       └─ /health (Health status)

Prometheus (scrape every 15s)
    └─ Stores metrics with timestamps

Grafana
    └─ Query Prometheus, create dashboards, set alerts
```

### Metric Naming Convention

All metrics follow pattern: `{component}_{operation}_{metric_type}`

Examples:
- `fireflies_gossip_messages_sent` (counter)
- `choam_consensus_latency_95th` (timer percentile)
- `fireflies_view_size` (gauge)
- `sql_state_height` (gauge)

---

## Health Check Endpoints

### Endpoint: /health (Quick Health)

**Response**: Simple JSON status

```bash
curl -s http://localhost:8080/health | jq '.'
```

**Sample response**:
```json
{
  "status": "UP",
  "timestamp": "2026-01-27T10:15:30Z",
  "checks": {
    "fireflies": "UP",
    "choam": "UP",
    "sql_state": "UP"
  }
}
```

**Interpretation**:
- Status `UP`: Node is healthy, participating in consensus
- Status `DOWN`: Critical failure, node may be isolated
- Status `DEGRADED`: Partial functionality, may be recovering

### Endpoint: /health/deep (Comprehensive Health)

**Response**: Detailed health with metrics

```bash
curl -s http://localhost:8080/health/deep | jq '.'
```

**Sample response**:
```json
{
  "status": "UP",
  "timestamp": "2026-01-27T10:15:30Z",
  "checks": {
    "fireflies": {
      "status": "UP",
      "view_size": 7,
      "view_changes_recent": 0,
      "suspected_count": 0,
      "latency_p95_ms": 45
    },
    "choam": {
      "status": "UP",
      "consensus_latency_p95_ms": 95,
      "blocks_committed": 15340,
      "pending_transactions": 23,
      "batch_size_avg": 98
    },
    "sql_state": {
      "status": "UP",
      "height": 15340,
      "transactions_applied": 1502300,
      "apply_latency_p95_ms": 78
    }
  }
}
```

---

## Core Metrics (50+)

### Category 1: Membership & Gossip (Fireflies)

**Purpose**: Monitor node discovery, cluster cohesion, failure detection

| Metric | Type | Unit | Healthy | Warning | Critical |
|--------|------|------|---------|---------|----------|
| `fireflies_view_size` | Gauge | nodes | = n | n-1 | < n-2 |
| `fireflies_suspected_count` | Gauge | nodes | 0-1 | 2-3 | > 3 |
| `fireflies_failed_count` | Gauge | nodes | 0 | 1 | > 1 |
| `fireflies_view_changes_per_hour` | Counter | events | 0-1 | 2-5 | > 5 |
| `fireflies_gossip_messages_sent` | Counter | messages | > 100/min | stalled | 0 |
| `fireflies_gossip_messages_received` | Counter | messages | > 100/min | stalled | 0 |
| `fireflies_gossip_round_time_avg` | Timer | ms | < 100 | < 500 | > 1000 |
| `fireflies_gossip_round_time_95th` | Timer | ms | < 150 | < 750 | > 1500 |
| `fireflies_member_lookup_latency_p99` | Timer | ms | < 50 | < 100 | > 200 |

**Alert Rules**:

```yaml
# ALERT: Cluster fragmented (< n-2 nodes)
- alert: FirefliesViewFragmented
  expr: fireflies_view_size < (fireflies_view_size offset 24h) - 2
  for: 2m
  severity: critical

# ALERT: Too many suspected nodes
- alert: FirefliesManyNodesSuspected
  expr: fireflies_suspected_count > 3
  for: 1m
  severity: warning

# ALERT: Excessive view changes
- alert: FirefliesHighViewChangeRate
  expr: increase(fireflies_view_changes_per_hour[1h]) > 5
  for: 5m
  severity: warning
```

---

### Category 2: Consensus & Ordering (CHOAM)

**Purpose**: Monitor block production, ordering latency, transaction throughput

| Metric | Type | Unit | Healthy | Warning | Critical |
|--------|------|------|---------|---------|----------|
| `choam_blocks_committed` | Counter | blocks | 10-20/min | 5-10/min | < 5/min |
| `choam_consensus_latency_p50` | Timer | ms | < 50 | < 150 | > 300 |
| `choam_consensus_latency_p95` | Timer | ms | < 100 | < 300 | > 1000 |
| `choam_consensus_latency_p99` | Timer | ms | < 200 | < 500 | > 2000 |
| `choam_pending_transactions` | Gauge | txns | 0-100 | 100-500 | > 1000 |
| `choam_batch_size_avg` | Gauge | txns | 80-100 | 50-80 | < 50 |
| `choam_batch_size_p95` | Gauge | txns | > 100 | 50-100 | < 50 |
| `choam_view_change_events` | Counter | events | 0-1/day | 1-5/day | > 5/day |
| `choam_view_change_latency_p95` | Timer | ms | < 1000 | < 5000 | > 10000 |
| `choam_checkpoint_latency_p95` | Timer | ms | < 500 | < 2000 | > 5000 |

**Alert Rules**:

```yaml
# ALERT: Consensus stalled (no blocks produced)
- alert: CHOAMBlocksStalled
  expr: increase(choam_blocks_committed[5m]) == 0
  for: 2m
  severity: critical

# ALERT: High consensus latency
- alert: CHOAMHighLatency
  expr: choam_consensus_latency_p95 > 1000
  for: 5m
  severity: warning

# ALERT: Transactions backing up
- alert: CHOAMPendingTransactions
  expr: choam_pending_transactions > 1000
  for: 5m
  severity: warning

# ALERT: Small batch sizes (inefficient)
- alert: CHOAMSmallBatches
  expr: choam_batch_size_avg < 50
  for: 10m
  severity: warning
```

---

### Category 3: Byzantine Detection

**Purpose**: Monitor Byzantine attack detection and response

| Metric | Type | Unit | Healthy | Warning | Critical |
|--------|------|------|---------|---------|----------|
| `byzantine_detection_signals_total` | Counter | signals | 0-5/day | 5-20/day | > 20/day |
| `byzantine_equivocation_detected` | Counter | events | 0 | 1-3 | > 3 |
| `byzantine_timing_anomalies` | Counter | events | 0-2/day | 2-10/day | > 10/day |
| `byzantine_rate_anomalies` | Counter | events | 0-1/day | 1-5/day | > 5/day |
| `byzantine_response_actions_total` | Counter | actions | 0 | varies | varies |
| `byzantine_member_suspect_count` | Gauge | members | 0 | 1-2 | > 2 |

**Alert Rules**:

```yaml
# ALERT: Byzantine attack detected
- alert: ByzantineEquivocationDetected
  expr: increase(byzantine_equivocation_detected[10m]) > 0
  for: 1m
  severity: critical

# ALERT: High rate of anomalies
- alert: ByzantineAnomalyRate
  expr: increase(byzantine_detection_signals_total[1h]) > 20
  for: 5m
  severity: warning
```

---

### Category 4: Replication & State (SQL-State)

**Purpose**: Monitor state machine replication, transaction processing

| Metric | Type | Unit | Healthy | Warning | Critical |
|--------|------|------|---------|---------|----------|
| `sql_state_height` | Gauge | blocks | increasing | stalled | decreasing |
| `sql_state_transactions_applied` | Counter | txns | > 1000/min | > 100/min | < 100/min |
| `sql_state_apply_latency_p95` | Timer | ms | < 100 | < 500 | > 1000 |
| `sql_state_apply_latency_p99` | Timer | ms | < 200 | < 1000 | > 2000 |
| `sql_state_snapshot_size` | Gauge | bytes | < 1GB | 1-10GB | > 10GB |
| `sql_state_log_size` | Gauge | bytes | varies | varies | varies |
| `sql_state_connection_pool_active` | Gauge | connections | 5-20 | 20-50 | > 50 |
| `sql_state_query_latency_p95` | Timer | ms | < 50 | < 200 | > 500 |

**Alert Rules**:

```yaml
# ALERT: State machine stuck
- alert: SQLStateHeightStalled
  expr: increase(sql_state_height[5m]) == 0
  for: 2m
  severity: critical

# ALERT: High apply latency
- alert: SQLStateHighApplyLatency
  expr: sql_state_apply_latency_p95 > 1000
  for: 5m
  severity: warning
```

---

### Category 5: System Resources

**Purpose**: Monitor JVM, OS resources

| Metric | Type | Unit | Healthy | Warning | Critical |
|--------|------|------|---------|---------|----------|
| `jvm_memory_heap_used` | Gauge | bytes | < 50% | 70-80% | > 90% |
| `jvm_memory_heap_max` | Gauge | bytes | configured | configured | configured |
| `jvm_gc_pause_time_p95` | Timer | ms | < 100 | < 500 | > 1000 |
| `jvm_gc_pause_time_max` | Timer | ms | < 1000 | < 5000 | > 10000 |
| `jvm_threads_live` | Gauge | threads | 50-100 | 100-200 | > 300 |
| `system_cpu_usage` | Gauge | % | < 50% | 50-80% | > 80% |
| `system_disk_used` | Gauge | % | < 70% | 70-85% | > 85% |
| `system_file_descriptors_open` | Gauge | files | < 5000 | 10000-15000 | > 20000 |
| `system_network_bytes_sent` | Counter | bytes | varies | varies | varies |
| `system_network_bytes_received` | Counter | bytes | varies | varies | varies |

**Alert Rules**:

```yaml
# ALERT: High heap usage
- alert: JVMHighHeapUsage
  expr: jvm_memory_heap_used / jvm_memory_heap_max > 0.85
  for: 5m
  severity: warning

# ALERT: Out of memory risk
- alert: JVMCriticalHeapUsage
  expr: jvm_memory_heap_used / jvm_memory_heap_max > 0.95
  for: 1m
  severity: critical

# ALERT: High GC pause times
- alert: JVMHighGCPausetime
  expr: jvm_gc_pause_time_p95 > 500
  for: 5m
  severity: warning

# ALERT: Disk filling up
- alert: DiskSpaceLow
  expr: (1 - system_disk_used / system_disk_total) < 0.15
  for: 10m
  severity: warning
```

---

## SLA Definitions

### Latency SLAs

**Transaction Latency** (client → response):
- P50: 150ms
- P95: 250ms
- P99: 400ms
- Max: 600ms

**Consensus Latency** (order → commitment):
- P50: 50ms
- P95: 100ms
- P99: 200ms

**Query Latency** (JDBC read):
- P50: 5ms
- P95: 20ms
- P99: 50ms

### Availability SLAs

**Uptime Target**: 99.95% (< 22 minutes downtime/month)

**Calculation**:
```
Downtime = (1 - uptime_target) × days_per_month × 24 × 60
         = (1 - 0.9995) × 30 × 24 × 60
         = 21.6 minutes per month
```

**For 7-node cluster**:
- 1 node down: No SLA impact (consensus continues with 6 nodes)
- 2 nodes down: May slow consensus, still operational
- 3+ nodes down: Cluster stops (Byzantine tolerance violated)

### Throughput SLAs

**Small production (7 nodes, f=2)**:
- Minimum: 5K tx/sec
- Target: 10K tx/sec
- Peak: 15K tx/sec

**Medium production (13 nodes, f=4)**:
- Minimum: 2K tx/sec
- Target: 5K tx/sec
- Peak: 8K tx/sec

---

## Alerting Rules

### Critical Alerts (Page on-call immediately)

```yaml
groups:
  - name: delos_critical
    rules:
      # Cluster fragmentation
      - alert: ClusterFragmentation
        expr: fireflies_view_size < 5 and on() fireflies_view_size offset 1h >= 7
        for: 1m
        annotations:
          summary: "Cluster fragmentation: {{ $value }} nodes remaining"
          action: "Check network connectivity, verify NTP sync"

      # Consensus stalled
      - alert: ConsensusStalled
        expr: increase(choam_blocks_committed[5m]) == 0
        for: 2m
        annotations:
          summary: "CHOAM consensus stalled, no blocks produced"
          action: "Check logs for Byzantine detection, verify network"

      # Byzantine attack
      - alert: ByzantineAttack
        expr: increase(byzantine_equivocation_detected[10m]) > 0
        for: 1m
        annotations:
          summary: "Byzantine equivocation detected"
          action: "Isolate suspected node, trigger incident response"

      # Out of memory imminent
      - alert: OutOfMemoryRisk
        expr: jvm_memory_heap_used / jvm_memory_heap_max > 0.95
        for: 1m
        annotations:
          summary: "JVM heap {{ $value | humanizePercentage }} full"
          action: "Restart node with larger heap immediately"
```

### Warning Alerts (Alert ops team)

```yaml
  - name: delos_warning
    rules:
      # High latency
      - alert: HighConsensusLatency
        expr: choam_consensus_latency_p95 > 300
        for: 5m
        annotations:
          summary: "Consensus latency high: {{ $value }}ms"
          action: "Check network latency, CPU usage"

      # Many suspected nodes
      - alert: ManyNodesSuspected
        expr: fireflies_suspected_count >= 2
        for: 2m
        annotations:
          summary: "{{ $value }} nodes suspected"
          action: "Check node health, network connectivity"

      # Pending transactions backing up
      - alert: TransactionBacklog
        expr: choam_pending_transactions > 1000
        for: 5m
        annotations:
          summary: "{{ $value }} pending transactions"
          action: "Increase batch_size or batch_timeout"

      # High heap usage
      - alert: HighHeapUsage
        expr: jvm_memory_heap_used / jvm_memory_heap_max > 0.85
        for: 5m
        annotations:
          summary: "Heap usage: {{ $value | humanizePercentage }}"
          action: "Monitor GC frequency, may need larger heap"
```

### Info Alerts (Logging only)

```yaml
  - name: delos_info
    rules:
      # Excessive view changes
      - alert: ViewChangeRate
        expr: increase(fireflies_view_changes_per_hour[1h]) > 5
        for: 5m
        annotations:
          summary: "{{ $value }} view changes in last hour"
          action: "Investigate cluster stability"

      # Small batch sizes
      - alert: SmallBatchSizes
        expr: choam_batch_size_avg < 50
        for: 10m
        annotations:
          summary: "Average batch size: {{ $value }} (optimal: 80-100)"
          action: "Consider increasing batch_size or batch_timeout"
```

---

## Prometheus Setup

### Configuration: prometheus.yml

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

alerting:
  alertmanagers:
    - static_configs:
        - targets:
            - localhost:9093

rule_files:
  - '/etc/prometheus/delos_rules.yml'

scrape_configs:
  - job_name: 'delos-cluster'
    metrics_path: '/metrics'
    static_configs:
      - targets:
          - 'node1:8080'
          - 'node2:8080'
          - 'node3:8080'
          - 'node4:8080'
          - 'node5:8080'
          - 'node6:8080'
          - 'node7:8080'
```

### Docker Compose Setup

```yaml
version: '3.8'

services:
  prometheus:
    image: prom/prometheus:latest
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
      - ./delos_rules.yml:/etc/prometheus/delos_rules.yml
      - prometheus_data:/prometheus
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'

  alertmanager:
    image: prom/alertmanager:latest
    ports:
      - "9093:9093"
    volumes:
      - ./alertmanager.yml:/etc/alertmanager/config.yml
      - alertmanager_data:/alertmanager

  grafana:
    image: grafana/grafana:latest
    ports:
      - "3000:3000"
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin
    volumes:
      - grafana_data:/var/lib/grafana
    depends_on:
      - prometheus

volumes:
  prometheus_data:
  alertmanager_data:
  grafana_data:
```

---

## Grafana Dashboards

### Dashboard 1: Cluster Health Overview

Key panels:
1. **View Size**: Gauge showing `fireflies_view_size` (target: n)
2. **Blocks/Min**: Gauge showing `rate(choam_blocks_committed[5m])`
3. **Consensus Latency**: Graph of `choam_consensus_latency_p95`
4. **Pending Txns**: Graph of `choam_pending_transactions`
5. **Byzantine Signals**: Graph of `rate(byzantine_detection_signals_total[1h])`

### Dashboard 2: Node Performance

Key panels:
1. **CPU Usage**: `system_cpu_usage` for each node
2. **Heap Memory**: `jvm_memory_heap_used / jvm_memory_heap_max` (%)
3. **GC Pause Time**: `jvm_gc_pause_time_p95`
4. **Disk Used**: `system_disk_used / system_disk_total` (%)
5. **Network I/O**: `rate(system_network_bytes_sent[5m])` + `rate(system_network_bytes_received[5m])`

### Dashboard 3: Transaction Pipeline

Key panels:
1. **Txn Rate**: `rate(sql_state_transactions_applied[5m])`
2. **Apply Latency**: `sql_state_apply_latency_p95`
3. **Batch Size**: `choam_batch_size_avg`
4. **SQL Queries**: `rate(sql_state_query_latency_p95[5m])`
5. **State Height**: `sql_state_height`

---

## Log Analysis

### Log Levels

**INFO** (Normal operation):
```
2026-01-27 10:15:30.123 [main] INFO  Fireflies - View change: 7 members (member1, member2, ...)
2026-01-27 10:15:35.456 [pool-2-thread-1] INFO  CHOAM - Block 1000 committed, 98 transactions
2026-01-27 10:15:40.789 [sql-pool-1] INFO  SQLState - Height: 1000, 10200 transactions applied
```

**WARNING** (Potential issues):
```
2026-01-27 10:16:30.123 [gossip-1] WARN  Fireflies - Member node3 suspected (no message in 5s)
2026-01-27 10:16:35.456 [consensus-1] WARN  CHOAM - Consensus latency high: 850ms (target: 100ms)
2026-01-27 10:16:40.789 [pool-1] WARN  JVM - Heap usage 83% (1.2GB / 1.4GB)
```

**ERROR** (Failures):
```
2026-01-27 10:17:30.123 [gossip-1] ERROR Fireflies - Failed to deliver message to node5 (connection timeout)
2026-01-27 10:17:35.456 [byzantine-1] ERROR Byzantine - Equivocation detected for member node2, triggering shunning
2026-01-27 10:17:40.789 [pool-1] ERROR SQLState - Failed to apply transaction (constraint violation)
```

### Log Patterns to Watch

```bash
# Check for Byzantine attacks
grep "Equivocation detected" /opt/delos/logs/delos.log

# Check for network issues
grep "connection timeout\|Failed to deliver\|unreachable" /opt/delos/logs/delos.log

# Check for consensus problems
grep "consensus\|latency high\|stalled" /opt/delos/logs/delos.log

# Check for state corruption
grep "Failed to apply\|consistency violation" /opt/delos/logs/delos.log
```

---

## Troubleshooting with Metrics

### Problem: Cluster fragmentation (view_size < n-1)

**Steps**:
1. Check metric: `fireflies_view_size`
2. Identify missing nodes (logs show member names)
3. Check network: `ping -c5 missing-node`
4. Check node logs: `ssh missing-node "journalctl -u delos -n 50"`
5. If network ok, check node status: `curl http://missing-node:8080/health`

### Problem: High consensus latency (> 300ms)

**Steps**:
1. Check network latency: `mtr -r -c 100 other-node`
2. Check CPU: `top` or `system_cpu_usage` metric
3. Check disk I/O: `iostat -x 1`
4. Check JVM GC: `jvm_gc_pause_time_p95` metric
5. Potential fix: Increase `batch_timeout` in configuration

### Problem: Transactions backing up (pending_transactions > 1000)

**Steps**:
1. Check throughput: `rate(sql_state_transactions_applied[5m])`
2. Check apply latency: `sql_state_apply_latency_p95`
3. If latency high, check database: `EXPLAIN PLAN` for slow queries
4. If latency low, increase `batch_size` in configuration

---

## Related Documentation

- [Deployment Guide](DEPLOYMENT_GUIDE.md) - Setup procedures
- [Operational Procedures](OPERATIONAL_PROCEDURES.md) - Day-to-day operations
- [Troubleshooting Guide](TROUBLESHOOTING_GUIDE.md) - Problem resolution
- [Performance Tuning](PERFORMANCE_TUNING.md) - Optimization
- [OPS Runbook](OPS_RUNBOOK_PHASE_1C.md) - Phase 1C specific procedures
