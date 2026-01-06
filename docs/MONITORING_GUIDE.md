# Delos Monitoring Guide

**Document Version:** 1.0
**Date:** 2026-01-06
**Status:** Production

## Overview

This guide covers monitoring Delos clusters with Dropwizard Metrics, health checks, performance baselines, and alerting strategies.

---

## 1. Metrics Architecture

### 1.1 Metrics Endpoints

Each Delos node exposes metrics on port 8080:

| Endpoint | Format | Refresh | Use |
|----------|--------|---------|-----|
| `/metrics` | Prometheus text | Real-time | Prometheus scraping |
| `/metrics/json` | JSON | Real-time | Custom tools |
| `/health` | JSON | Real-time | Load balancer health checks |
| `/health/deep` | JSON | Deep validation | Startup/diagnostics |

### 1.2 Metric Types

**Counters** (cumulative, always increasing):
- `fireflies_gossip_messages_sent`
- `choam_blocks_committed`
- `ethereal_dag_events_processed`

**Gauges** (current value):
- `fireflies_view_size` (members in current view)
- `fireflies_suspected_count` (suspected nodes)
- `choam_pending_transactions`
- `sql_state_height` (blocks applied)

**Timers** (latency distribution):
- `fireflies_message_round_trip_time`
- `choam_consensus_latency`
- `sql_state_apply_latency`

**Histograms** (value distribution):
- `choam_batch_size` (transactions per block)
- `ethereal_dag_depth` (node count in DAG)

---

## 2. Performance Baselines

### 2.1 Healthy Cluster Metrics

| Metric | Healthy Range | Warning | Critical |
|--------|---|---|---|
| **Fireflies** | | | |
| View size | 7 | <5 | 0 |
| Suspected | 0-1 | 2+ | 3+ |
| Gossip latency p95 | <100ms | <500ms | >1s |
| View changes / hour | 0-1 | 2-5 | >5 |
| | | | |
| **CHOAM** | | | |
| Consensus latency p95 | <500ms | <2s | >5s |
| Blocks/sec | 10-20 | 5-10 | <5 |
| Pending txns | 0-50 | 100+ | >500 |
| Batch size | 80-100 | 50-80 | <50 |
| | | | |
| **SQL-State** | | | |
| Apply latency p95 | <100ms | <500ms | >1s |
| Height | Increasing | Stalled | Decreasing |
| Txns/sec | 100-500 | 50-100 | <50 |
| | | | |
| **System** | | | |
| Memory (heap) | <50% | 70-80% | >90% |
| GC pause time | <100ms | <500ms | >1s |
| File handles | <5000 | 10000-15000 | >20000 |
| Disk I/O wait | <5% | 10-20% | >30% |

### 2.2 Baseline Collection

**Establish baselines during stable operation (48 hours):**

```bash
#!/bin/bash
# /opt/delos/scripts/collect-baselines.sh

BASELINE_DIR="/opt/delos/baselines/$(date +%Y%m%d)"
mkdir -p $BASELINE_DIR

for hour in {1..48}; do
  echo "=== Baseline Collection Hour $hour ==="

  for node in node{1..7}; do
    curl -s http://$node:8080/metrics > $BASELINE_DIR/$node-$hour.txt
  done

  sleep 3600  # 1 hour
done

# Calculate percentiles
./tools/analyze-baselines.py $BASELINE_DIR > $BASELINE_DIR/summary.txt
```

---

## 3. Health Checks

### 3.1 HTTP Health Check Endpoint

**GET `/health`** (simple readiness probe):

```json
{
  "status": "UP",
  "components": {
    "fireflies": "UP",
    "choam": "UP",
    "sqlState": "UP",
    "diskSpace": "UP",
    "memory": "UP"
  }
}
```

**Kubernetes liveness probe:**
```yaml
livenessProbe:
  httpGet:
    path: /health
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10
  timeoutSeconds: 5
  failureThreshold: 3
```

### 3.2 Deep Health Check

**GET `/health/deep`** (startup/diagnostics):

```json
{
  "status": "UP",
  "fireflies": {
    "status": "UP",
    "viewSize": 7,
    "suspected": 0,
    "gossipLatencyMs": 45,
    "lastViewChangeMs": 125000
  },
  "choam": {
    "status": "UP",
    "consensusLatencyMs": 320,
    "blocksCommitted": 45230,
    "pendingTransactions": 12,
    "lastBlockMs": 1234567890000
  },
  "sqlState": {
    "status": "UP",
    "height": 45230,
    "lastApplyMs": 234,
    "txnsApplied": 125000,
    "blockHashMatch": true
  },
  "database": {
    "status": "UP",
    "connectionsActive": 15,
    "connectionsMax": 20,
    "kerl_size_mb": 234,
    "state_size_mb": 1230
  }
}
```

### 3.3 Health Check Automaton

**Health check script for monitoring:**

```bash
#!/bin/bash
# /opt/delos/scripts/check-health.sh

THRESHOLD_LATENCY=1000  # ms
THRESHOLD_MEMORY=85     # percent
THRESHOLD_SUSPECTED=2   # nodes

check_node() {
  local node=$1
  local response=$(curl -s -m 5 http://$node:8080/health/deep)

  if [ $? -ne 0 ]; then
    echo "CRITICAL: $node unreachable"
    return 2
  fi

  # Extract metrics
  local consensus_latency=$(echo "$response" | jq '.choam.consensusLatencyMs')
  local memory_usage=$(echo "$response" | jq '.database.memoryPercent')
  local suspected=$(echo "$response" | jq '.fireflies.suspected')

  # Evaluate health
  if [ $consensus_latency -gt $THRESHOLD_LATENCY ]; then
    echo "WARNING: $node consensus latency high: ${consensus_latency}ms"
    return 1
  fi

  if [ $memory_usage -gt $THRESHOLD_MEMORY ]; then
    echo "WARNING: $node memory high: ${memory_usage}%"
    return 1
  fi

  if [ $suspected -gt $THRESHOLD_SUSPECTED ]; then
    echo "CRITICAL: $node suspects too many peers: $suspected"
    return 2
  fi

  echo "OK: $node healthy"
  return 0
}

# Check all nodes
STATUS=0
for node in node{1..7}; do
  check_node $node || STATUS=$?
done

exit $STATUS
```

---

## 4. Prometheus Integration

### 4.1 Prometheus Scrape Configuration

**Add to `prometheus.yml`:**

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'delos'
    static_configs:
      - targets:
        - 'node1:8080'
        - 'node2:8080'
        - 'node3:8080'
        - 'node4:8080'
        - 'node5:8080'
        - 'node6:8080'
        - 'node7:8080'
        labels:
          cluster: 'delos-prod'
```

### 4.2 Alert Rules

**Create `delos-alerts.yml`:**

```yaml
groups:
  - name: delos
    rules:
      # Fireflies (Membership)
      - alert: DelosViewInstability
        expr: |
          rate(fireflies_view_changes_total[5m]) > 0.2
        for: 5m
        annotations:
          summary: "{{ $labels.instance }} view changing frequently"

      - alert: DelosMembersDown
        expr: |
          fireflies_view_size < 5
        for: 1m
        annotations:
          summary: "{{ $labels.instance }} lost cluster members"

      - alert: DelosHighSuspicion
        expr: |
          fireflies_suspected_count > 2
        for: 2m
        annotations:
          summary: "{{ $labels.instance }} suspecting multiple peers"

      # CHOAM (Consensus)
      - alert: DelosHighConsensusLatency
        expr: |
          histogram_quantile(0.95, choam_consensus_latency) > 1000
        for: 5m
        annotations:
          summary: "{{ $labels.instance }} consensus latency p95 > 1s"

      - alert: DelosBlockStall
        expr: |
          rate(choam_blocks_committed[5m]) < 5
        for: 5m
        annotations:
          summary: "{{ $labels.instance }} block commit rate low"

      - alert: DelosHighPendingTxns
        expr: |
          choam_pending_transactions > 500
        for: 10m
        annotations:
          summary: "{{ $labels.instance }} high pending transactions backlog"

      # SQL-State
      - alert: DelosStateHeightStall
        expr: |
          delta(sql_state_height[5m]) == 0
        for: 2m
        annotations:
          summary: "{{ $labels.instance }} state not advancing"

      - alert: DelosHighApplyLatency
        expr: |
          histogram_quantile(0.95, sql_state_apply_latency) > 500
        for: 5m
        annotations:
          summary: "{{ $labels.instance }} state apply latency p95 > 500ms"

      # System
      - alert: DelosHighMemory
        expr: |
          process_resident_memory_bytes / node_memory_MemTotal * 100 > 85
        for: 5m
        annotations:
          summary: "{{ $labels.instance }} memory usage > 85%"

      - alert: DelosHighGCTime
        expr: |
          rate(jvm_gc_pause_seconds_sum[5m]) > 0.1
        for: 5m
        annotations:
          summary: "{{ $labels.instance }} excessive GC pause time"

      - alert: DelosHighDiskUsage
        expr: |
          disk_used_percent > 90
        for: 10m
        annotations:
          summary: "{{ $labels.instance }} disk > 90% full"
```

### 4.3 Grafana Dashboard

**Create dashboard JSON** with panels:

```json
{
  "dashboard": {
    "title": "Delos Cluster Overview",
    "panels": [
      {
        "title": "Cluster Health",
        "targets": [
          {
            "expr": "fireflies_view_size",
            "legendFormat": "{{ instance }}"
          }
        ]
      },
      {
        "title": "Consensus Latency (p95)",
        "targets": [
          {
            "expr": "histogram_quantile(0.95, choam_consensus_latency)",
            "legendFormat": "{{ instance }}"
          }
        ]
      },
      {
        "title": "Block Commit Rate",
        "targets": [
          {
            "expr": "rate(choam_blocks_committed[5m])",
            "legendFormat": "{{ instance }}"
          }
        ]
      },
      {
        "title": "SQL-State Height",
        "targets": [
          {
            "expr": "sql_state_height",
            "legendFormat": "{{ instance }}"
          }
        ]
      },
      {
        "title": "Memory Usage",
        "targets": [
          {
            "expr": "process_resident_memory_bytes / 1024 / 1024",
            "legendFormat": "{{ instance }}"
          }
        ]
      }
    ]
  }
}
```

---

## 5. Log Monitoring

### 5.1 Log Levels

**Default:** `INFO` (operational events)
**Debug:** `DEBUG` (detailed flow, enable when investigating issues)

**Change log level at runtime:**
```bash
# Increase logging for troubleshooting
curl -X POST http://node1:8080/loggers/com.hellblazer.delos \
  -H "Content-Type: application/json" \
  -d '{"level": "DEBUG"}'

# Reset after investigation
curl -X POST http://node1:8080/loggers/com.hellblazer.delos \
  -H "Content-Type: application/json" \
  -d '{"level": "INFO"}'
```

### 5.2 Key Log Events

**Monitor for these in journalctl/logs:**

```
# Normal startup
"Delos starting"
"Fireflies gossip initialized"
"CHOAM consensus started"
"SQL-State height 0"

# View changes (occasional normal)
"View change detected: member X joined"
"View change detected: member X suspected"

# Concerning (investigate)
"CRITICAL: Consensus timeout"
"ERROR: Failed to apply block"
"Excessive GC: <X>ms pause"

# Critical (immediate action)
"PANIC: State inconsistency detected"
"Database corruption detected"
"All nodes suspected (network partition)"
```

### 5.3 Structured Logging

All logs include:

```
2026-01-06T10:23:45.123Z [worker-42] INFO com.hellblazer.delos.choam.Choam
  "Block committed"
  height=12345
  blockHash=abc123...
  txnCount=87
  consensusLatency=234ms
```

---

## 6. Custom Metrics

### 6.1 Application-Level Metrics

Register custom metrics in your application:

```java
MeterRegistry registry = // get registry
Timer txnTimer = Timer.builder("app_transaction_duration")
    .description("Custom transaction latency")
    .publishPercentiles(0.5, 0.95, 0.99)
    .register(registry);

txnTimer.record(() -> {
    // your code
});
```

### 6.2 Business Metrics

Example for tracking domain events:

```java
Counter.builder("app_orders_processed")
    .description("Total orders processed")
    .tags("status", "completed")
    .register(registry)
    .increment();
```

---

## 7. On-Call Runbook

### 7.1 Alert Response Matrix

| Alert | First Check | Action | Escalate If |
|-------|------------|--------|-------------|
| **ViewInstability** | `journalctl -u delos -n 50` | Check network connectivity | Continues >15 min |
| **MembersDown** | `systemctl status delos` on suspected node | Restart if failed | >2 members stay down |
| **HighSuspicion** | Check latency: `curl /metrics` | Check network jitter | Persists >10 min |
| **HighLatency** | Check GC logs (`jstat -gc`) | Increase heap if needed | Persists >30 min |
| **BlockStall** | Check logs for errors | Restart consensus leader | Stall >5 min |
| **HighMemory** | Monitor GC (`jstat -gc -h20 $PID 1000`) | Restart if >90% | Doesn't improve |
| **HighDiskUsage** | Check size: `du -sh /opt/delos/*` | Prune old KERL/logs | Approaches 100% |

### 7.2 Emergency Recovery

**If consensus is completely stalled:**
```bash
# 1. Check all nodes are up
for n in node{1..7}; do nc -zv $n 50051; done

# 2. Check identity continuity
./tools/verify-identity.sh

# 3. If identity OK, restart consensus module only
for n in node{1..7}; do
  ssh delos@$n 'curl -X POST http://localhost:8080/admin/restart-choam'
done

# 4. Monitor metrics - should recover in <1 min
watch -n 1 'curl -s http://node1:8080/metrics | grep -E "consensus_latency|blocks_committed"'
```

---

## 8. Capacity Planning

### 8.1 Scaling Metrics

As cluster load increases, monitor:

| Metric | Impact | Action |
|--------|--------|--------|
| Consensus latency | Network bandwidth | Add network capacity |
| Pending txns | Throughput bottleneck | Increase batch size (CHOAM) |
| Memory usage | GC pause time | Add RAM or increase heap |
| Disk I/O | State apply latency | Upgrade storage (SSD) |
| Gossip messages | Network bandwidth | Reduce gossip interval (fireflies) |

### 8.2 Growth Projections

Example metrics for 1M transactions/day:

```
Throughput: ~12 txn/sec
Consensus latency p95: ~300ms
Block commit rate: 15 blocks/sec
Heap usage: 4GB
Database size: ~100GB/month
```

---

## References

- [Prometheus Documentation](https://prometheus.io/docs/)
- [Dropwizard Metrics](https://metrics.dropwizard.io/)
- [Grafana Dashboard Guide](https://grafana.com/docs/grafana/latest/)
- [Fireflies: Membership Service](../fireflies/README.md)
- [CHOAM: Consensus Design](docs/adr/0004-consensus-design-choam.md)
- [Deployment Guide](DEPLOYMENT_GUIDE.md)
- [Troubleshooting Guide](TROUBLESHOOTING_GUIDE.md)
