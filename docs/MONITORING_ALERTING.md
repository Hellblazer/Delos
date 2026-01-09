# Delos Monitoring & Alerting Guide

**Comprehensive metrics collection, dashboard setup, and alert procedures for production Delos clusters.**

**Status**: Production Ready
**Last Updated**: 2026-01-09
**Audience**: Operations teams, SREs, on-call engineers

---

## Table of Contents

1. [Metrics Collection Setup](#metrics-collection-setup)
2. [Key Metrics Reference](#key-metrics-reference)
3. [Dashboard Setup](#dashboard-setup)
4. [Alerting Rules](#alerting-rules)
5. [On-Call Guide](#on-call-guide)
6. [SLA & Targets](#sla--targets)

---

## Metrics Collection Setup

### Prometheus Configuration

**Prerequisites:**
- Prometheus 2.40+
- Delos nodes with metrics endpoints exposed (port 8080/metrics)
- Network connectivity from Prometheus to all nodes

**Basic prometheus.yml:**
```yaml
global:
  scrape_interval: 15s      # Default interval (every 15 seconds)
  evaluation_interval: 15s
  retention: 30d            # Keep data for 30 days

scrape_configs:
  - job_name: 'delos'
    static_configs:
      - targets: ['node1:8080', 'node2:8080', 'node3:8080', 'node4:8080']
        labels:
          cluster: 'production'

    # Scrape every 10s for faster detection
    scrape_interval: 10s

    # 30s timeout for scrape operations
    scrape_timeout: 30s

    # Replace labels with node name
    metric_relabel_configs:
      - source_labels: [__address__]
        target_label: instance
```

**Service discovery (for dynamic clusters):**
```yaml
  - job_name: 'delos-dynamic'
    consul_sd_configs:
      - server: 'localhost:8500'
        services: ['delos']

    # Refresh every 30 seconds
    consul_sd_config:
      refresh_interval: 30s
```

**High-throughput setup (100K+ tx/sec):**
```yaml
global:
  scrape_interval: 5s       # More frequent collection
  evaluation_interval: 5s
  retention: 7d             # Less data (use external storage)

scrape_configs:
  - job_name: 'delos'
    scrape_interval: 5s
    scrape_timeout: 10s     # Shorter timeout
    static_configs:
      - targets: ['node1:8080', 'node2:8080', ..., 'node50:8080']

# Remote write for long-term storage
remote_write:
  - url: "https://prometheus-remote:9009/api/v1/write"
    queue_config:
      capacity: 50000
      max_shards: 200
      min_shards: 1
      max_samples_per_send: 10000
      batch_send_interval: 5s
```

### Alerting Backend Setup

**AlertManager configuration (alert routing):**
```yaml
global:
  resolve_timeout: 5m

route:
  receiver: 'default'
  group_by: ['alertname', 'cluster', 'job']
  group_wait: 30s         # Wait 30s before first notification
  group_interval: 5m      # Wait 5m before re-grouping
  repeat_interval: 4h     # Repeat every 4 hours

  # Route critical alerts to PagerDuty immediately
  routes:
    - match:
        severity: critical
      receiver: 'pagerduty'
      group_wait: 10s
      repeat_interval: 1h

    # Route warnings to Slack
    - match:
        severity: warning
      receiver: 'slack'
      group_wait: 1m

receivers:
  - name: 'default'
    slack_configs:
      - api_url: 'https://hooks.slack.com/services/YOUR/WEBHOOK/URL'
        channel: '#monitoring'
        title: 'Alert: {{ .GroupLabels.alertname }}'

  - name: 'pagerduty'
    pagerduty_configs:
      - service_key: 'YOUR_PAGERDUTY_SERVICE_KEY'

  - name: 'slack'
    slack_configs:
      - api_url: 'https://hooks.slack.com/services/YOUR/WEBHOOK/URL'
        channel: '#alerts'
```

---

## Key Metrics Reference

### Consensus Metrics (CHOAM/Ethereal)

| Metric | Type | Description | Healthy | Warning | Critical |
|--------|------|-------------|---------|---------|----------|
| **choam_blocks_committed** | Counter | Cumulative blocks applied to state | Increasing | Flat for >30s | Not changing |
| **choam_consensus_latency_p50** | Gauge | Median consensus time (ms) | < 200ms | 200-500ms | > 500ms |
| **choam_consensus_latency_p95** | Gauge | 95th percentile consensus | < 500ms | 500-2000ms | > 2000ms |
| **choam_consensus_latency_p99** | Gauge | 99th percentile consensus | < 1000ms | 1-3s | > 3s |
| **choam_view_changes** | Counter | Number of view changes | < 2/min | 2-5/min | > 5/min |
| **choam_pending_transactions** | Gauge | Transactions waiting | < 1000 | 1000-5000 | > 5000 |
| **choam_blocks_behind** | Gauge | How far behind committed | = 0 | < 10 | > 10 |

### Membership Metrics (Fireflies)

| Metric | Type | Description | Healthy | Warning | Critical |
|--------|------|-------------|---------|---------|----------|
| **fireflies_view_size** | Gauge | Active cluster members | = target | target-1 | < target-1 |
| **fireflies_suspected_count** | Gauge | Suspected members | 0-1 | 2-3 | > 3 or > 1/3 of cluster |
| **fireflies_suspect_duration** | Gauge | How long suspected (ms) | < 30s | 30-60s | > 60s |
| **fireflies_view_changes** | Counter | Membership changes | < 2/min | 2-5/min | > 5/min |
| **fireflies_gossip_latency_p95** | Gauge | Gossip delivery (ms) | < 500ms | 500-1000ms | > 1000ms |
| **fireflies_alive_members** | Gauge | Members reporting alive | = total | = total | < total |

### Replication Metrics (State Sync)

| Metric | Type | Description | Healthy | Warning | Critical |
|--------|------|-------------|---------|---------|----------|
| **sql_state_height** | Gauge | Applied state height | = leader | leader-10 | leader-100+ |
| **sql_state_height_behind** | Gauge | Blocks behind leader | = 0 | < 10 | > 100 |
| **sql_state_apply_latency_p95** | Gauge | Time to apply block (ms) | < 100ms | 100-500ms | > 500ms |
| **sql_state_replication_lag_ms** | Gauge | Lag behind consensus | < 100ms | 100-500ms | > 1000ms |
| **sql_state_snapshot_duration** | Gauge | Checkpoint time (ms) | < 5000ms | 5-10s | > 10s |

### System Metrics (JVM/OS)

| Metric | Type | Description | Baseline | High Load | Critical |
|--------|------|-------------|----------|-----------|----------|
| **jvm_memory_used_percent** | Gauge | Heap % used | < 40% | 70-85% | > 90% |
| **jvm_gc_time_ms** | Timer | GC pause time | < 50ms | 100-200ms | > 500ms |
| **jvm_gc_count** | Counter | GC cycles | < 1/min | 5-10/min | > 20/min |
| **process_cpu_usage_percent** | Gauge | CPU utilization | < 30% | 60-80% | > 90% |
| **process_open_files** | Gauge | Open file descriptors | < 500 | 1000-2000 | > limit-100 |
| **disk_usage_percent** | Gauge | Disk space used | < 70% | 70-85% | > 90% |
| **disk_io_read_bytes** | Counter | Disk read throughput | N/A | N/A | Growing unbounded |
| **disk_io_write_bytes** | Counter | Disk write throughput | N/A | N/A | Growing unbounded |

### Network Metrics

| Metric | Type | Description | Healthy | Warning | Critical |
|--------|------|-------------|---------|---------|----------|
| **grpc_message_latency_p95** | Timer | Message roundtrip (ms) | < 100ms | 100-500ms | > 1000ms |
| **network_packet_loss_percent** | Gauge | Packet loss % | 0-0.1% | 0.1-1% | > 1% |
| **network_bandwidth_mbps** | Gauge | Bandwidth utilization | < 50% | 50-80% | > 90% |

### Identity & Security Metrics

| Metric | Type | Description | Healthy | Warning | Critical |
|--------|------|-------------|---------|---------|----------|
| **kerl_events_stored** | Counter | Key events recorded | Increasing | Flat | Decreasing |
| **credential_validation_errors** | Counter | Auth failures | Low rate | Increasing | > 10/min |
| **byzantine_members_detected** | Counter | Byzantine nodes found | 0 | 1 | > 1 |
| **replay_cache_hits** | Counter | Replay attacks blocked | Should exist | N/A | Increasing rapidly |

---

## Dashboard Setup

### Grafana Import Configuration

**Grafana setup (local):**
```bash
# Docker Compose for local setup
docker run -d \
  -p 3000:3000 \
  -e "GF_SECURITY_ADMIN_PASSWORD=admin" \
  -e "GF_USERS_ALLOW_SIGN_UP=false" \
  grafana/grafana:9.5
```

**Add Prometheus data source:**
```json
{
  "name": "Prometheus",
  "type": "prometheus",
  "url": "http://prometheus:9090",
  "access": "proxy",
  "jsonData": {
    "timeInterval": "15s"
  }
}
```

### Dashboard 1: Cluster Health Overview

**Purpose**: Single-pane summary for on-call engineers (refresh: 30s)

**Panels:**

1. **Cluster Status (Big Number)**
   ```
   Query: (fireflies_view_size / 4) * 100
   Unit: percent
   Thresholds: 100% green, 75% yellow, 50% red
   ```

2. **Blocks Per Second (Graph)**
   ```
   Query: rate(choam_blocks_committed[1m])
   Range: Last 1 hour
   ```

3. **Consensus Latency p95 (Gauge)**
   ```
   Query: choam_consensus_latency_p95
   Thresholds: 500ms green, 2000ms yellow, 3000ms red
   ```

4. **Active Members (Table)**
   ```
   Query: fireflies_view_size
   Legend: Instance name
   ```

5. **Suspected Members (Alert Box)**
   ```
   Query: fireflies_suspected_count > 0
   Color: Red if any suspects
   ```

6. **Memory Usage (Gauge)**
   ```
   Query: jvm_memory_used_percent
   Thresholds: 50% green, 85% yellow, 90% red
   ```

### Dashboard 2: Performance & Throughput

**Purpose**: Performance monitoring for capacity planning (refresh: 1m)

**Panels:**

1. **Transaction Throughput (Graph)**
   ```
   Query: rate(choam_blocks_committed[5m]) * avg(choam_block_size)
   Legend: tx/sec
   ```

2. **Consensus Latency Distribution (Heatmap)**
   ```
   Query: choam_consensus_latency
   Buckets: 50ms, 100ms, 200ms, 500ms, 1000ms, 2000ms
   ```

3. **View Changes Over Time (Graph)**
   ```
   Query: rate(fireflies_view_changes[5m])
   Legend: Changes per minute
   ```

4. **Block Apply Latency (Graph)**
   ```
   Query: sql_state_apply_latency_p95
   Legend: Per node
   ```

5. **GC Time Trends (Graph)**
   ```
   Query: rate(jvm_gc_time_ms[5m])
   Legend: ms per second (if high, GC is heavy)
   ```

### Dashboard 3: System Health

**Purpose**: Infrastructure monitoring (refresh: 1m)

**Panels:**

1. **CPU Usage (Graph)**
   ```
   Query: process_cpu_usage_percent
   Legend: Per node
   Threshold line: 80%
   ```

2. **Memory Usage (Graph)**
   ```
   Query: jvm_memory_used_percent
   Legend: Per node
   Threshold lines: 70%, 85%, 90%
   ```

3. **Disk Space (Table)**
   ```
   Query: disk_usage_percent
   Columns: Node, Usage %, Available
   ```

4. **Open File Descriptors (Graph)**
   ```
   Query: process_open_files
   Threshold line: system ulimit
   ```

5. **Network Latency (Graph)**
   ```
   Query: grpc_message_latency_p95
   Legend: Between node pairs
   ```

### Dashboard 4: Replication & State

**Purpose**: State consistency monitoring (refresh: 30s)

**Panels:**

1. **State Height by Node (Graph)**
   ```
   Query: sql_state_height
   Legend: Per node
   ```

2. **Height Divergence (Big Number)**
   ```
   Query: max(sql_state_height) - min(sql_state_height)
   Thresholds: 0 green, 10 yellow, 100 red
   ```

3. **Replication Lag (Graph)**
   ```
   Query: sql_state_replication_lag_ms
   Legend: Per node
   Threshold: 100ms for sync, 500ms for warning
   ```

4. **Checkpoint Duration (Graph)**
   ```
   Query: sql_state_snapshot_duration
   Legend: Per node
   Threshold: 5s
   ```

---

## Alerting Rules

### Critical Alerts (Page On-Call)

**Alert 1: Cluster Lost Quorum**
```yaml
alert: ClusterLostQuorum
expr: fireflies_view_size <= count(choam_view_size) / 3
for: 30s
labels:
  severity: critical
  team: platform
annotations:
  summary: "Cluster {{ $labels.cluster }} lost quorum: {{ $value }}/{{ $labels.total_members }} members"
  action: "Immediately check failed nodes. See TROUBLESHOOTING_GUIDE.md Section 4 (Consensus Stalled)"
```

**Alert 2: Byzantine Member Detected**
```yaml
alert: ByzantineMemberDetected
expr: byzantine_members_detected > 0
for: 10s
labels:
  severity: critical
  team: security
annotations:
  summary: "Byzantine member detected on {{ $labels.instance }}"
  action: "Isolate member immediately. Contact security team. See TROUBLESHOOTING_GUIDE.md Section 5"
```

**Alert 3: Consensus Not Progressing**
```yaml
expr: increase(choam_blocks_committed[1m]) == 0
for: 1m
labels:
  severity: critical
annotations:
  summary: "No blocks committed in 1 minute on {{ $labels.instance }}"
  action: "Check network connectivity. Verify quorum. See TROUBLESHOOTING_GUIDE.md Section 4"
```

**Alert 4: Out of Memory**
```yaml
alert: OutOfMemory
expr: jvm_memory_used_percent > 95
for: 30s
labels:
  severity: critical
annotations:
  summary: "JVM approaching OOM on {{ $labels.instance }}: {{ $value }}%"
  action: "Increase heap or restart node. Investigate memory leak. See TROUBLESHOOTING_GUIDE.md Section 8"
```

**Alert 5: Disk Full**
```yaml
alert: DiskFull
expr: disk_usage_percent > 95
for: 5m
labels:
  severity: critical
annotations:
  summary: "Disk 95% full on {{ $labels.instance }}: {{ $value }}%"
  action: "Delete old logs or expand storage. See TROUBLESHOOTING_GUIDE.md Section 5"
```

### Warning Alerts (Investigate ASAP)

**Alert 6: High Consensus Latency**
```yaml
alert: HighConsensusLatency
expr: choam_consensus_latency_p95 > 2000
for: 2m
labels:
  severity: warning
annotations:
  summary: "High consensus latency on {{ $labels.cluster }}: {{ $value }}ms"
  action: "Check network, GC, and database performance. See TROUBLESHOOTING_GUIDE.md Section 2"
```

**Alert 7: High Suspected Count**
```yaml
alert: HighSuspectedCount
expr: fireflies_suspected_count > 1
for: 1m
labels:
  severity: warning
annotations:
  summary: "{{ $value }} members suspected on {{ $labels.cluster }}"
  action: "Check network stability and node health. See TROUBLESHOOTING_GUIDE.md Section 3"
```

**Alert 8: Replication Lagging**
```yaml
alert: ReplicationLagging
expr: sql_state_replication_lag_ms > 500
for: 2m
labels:
  severity: warning
annotations:
  summary: "Replication lag on {{ $labels.instance }}: {{ $value }}ms"
  action: "Check network and database performance. Node may need restart. See TROUBLESHOOTING_GUIDE.md Section 6"
```

**Alert 9: High GC Pause**
```yaml
alert: HighGCPause
expr: jvm_gc_time_ms > 500
for: 30s
labels:
  severity: warning
annotations:
  summary: "High GC pause on {{ $labels.instance }}: {{ $value }}ms"
  action: "Check heap pressure and tune GC. See TROUBLESHOOTING_GUIDE.md Section 8"
```

**Alert 10: Credential Validation Errors**
```yaml
alert: CredentialValidationErrors
expr: rate(credential_validation_errors[5m]) > 10
for: 1m
labels:
  severity: warning
annotations:
  summary: "High auth failure rate on {{ $labels.instance }}: {{ $value }}/sec"
  action: "Check identity service and certificates. See TROUBLESHOOTING_GUIDE.md Section 7"
```

### Info Alerts (Track Trends)

**Alert 11: View Changes Increasing**
```yaml
alert: ViewChangesIncreasing
expr: rate(fireflies_view_changes[5m]) > 5
for: 5m
labels:
  severity: info
annotations:
  summary: "Cluster instability: {{ $value }} view changes/min on {{ $labels.cluster }}"
  action: "Monitor network. Escalate if continues. See TROUBLESHOOTING_GUIDE.md Section 3"
```

**Alert 12: Memory Usage High**
```yaml
alert: MemoryUsageHigh
expr: jvm_memory_used_percent > 85
for: 5m
labels:
  severity: info
annotations:
  summary: "Memory usage {{ $value }}% on {{ $labels.instance }}"
  action: "Monitor for further increase. Plan capacity upgrade if continues."
```

---

## On-Call Guide

### Alert Response Procedures

**When PagerDuty fires an alert:**

1. **Acknowledge the alert** (within 5 minutes)
   - Prevents escalation to backup on-call
   - Signals you're investigating

2. **Assess severity**
   - **Critical**: Cluster is down or lost quorum → immediate action required
   - **Warning**: Degradation, but operating → investigate within 15 minutes
   - **Info**: Trends to monitor → may not need action tonight

3. **Access cluster status**
   ```bash
   # SSH to any node
   ssh delos@node1.prod

   # Check cluster health
   curl -s http://localhost:8080/health | jq '.'

   # View recent errors
   journalctl -u delos -n 100 --priority=err

   # Check metrics
   curl -s http://localhost:8080/metrics | head -50
   ```

### Playbook: Cluster Lost Quorum

**Symptom**: PagerDuty alert "Cluster Lost Quorum"

**Time to Resolution**: 5-30 minutes

**Steps:**

1. **Verify quorum is actually lost** (1 min)
   ```bash
   curl -s http://node1:8080/health | jq '.fireflies.view_size'
   # Should be < (total_members / 3)
   ```

2. **Identify which nodes are down** (1 min)
   ```bash
   for n in node{1..4}; do
     status=$(ssh -m 5 delos@$n "systemctl is-active delos" 2>/dev/null || echo "unknown")
     echo "$n: $status"
   done
   ```

3. **Restart failed nodes** (2-5 min per node)
   ```bash
   # If network issue, just restart
   ssh delos@node3 "sudo systemctl restart delos"
   sleep 10
   ```

4. **Monitor recovery** (2-3 min)
   ```bash
   watch -n 2 'curl -s http://node1:8080/health | jq ".fireflies.view_size, .choam.blocks_committed"'
   ```

5. **Escalate if quorum doesn't recover** (after 5 min)
   - See DISASTER_RECOVERY.md Section BP-03 for full recovery
   - Page platform team lead

### Playbook: High Consensus Latency

**Symptom**: PagerDuty alert "High Consensus Latency > 2 seconds"

**Time to Resolution**: 10-20 minutes

**Steps:**

1. **Check network latency between nodes** (2 min)
   ```bash
   for node in node{2..4}; do
     ping -c 3 $node | grep avg
   done
   ```

2. **Check GC pauses** (2 min)
   ```bash
   PID=$(pgrep -f delos)
   jstat -gc -h10 $PID 500 20  # 10 samples at 500ms interval
   ```

3. **Identify slow node** (2 min)
   ```bash
   curl -s http://node1:8080/metrics | grep "choam_consensus_latency_p95" | head -4
   # Compare across nodes
   ```

4. **If single node is slow:**
   - Check disk I/O: `iostat -x 1 5`
   - Check CPU: `top -n 1 | head -20`
   - Restart node if consistently high

5. **If all nodes are slow:**
   - Check network for packet loss: `mtr -r -c 100 node2`
   - Reduce batch size temporarily (PERFORMANCE_TUNING.md)
   - Page network team if issue confirmed

### Playbook: Out of Memory

**Symptom**: PagerDuty alert "Out of Memory approaching"

**Time to Resolution**: 5-15 minutes

**Steps:**

1. **Immediate action** (1 min)
   ```bash
   # Restart with larger heap
   systemctl stop delos

   # Edit service
   sudo systemctl edit delos
   # Change: JAVA_OPTS="-Xmx8g -Xms4g"

   systemctl restart delos
   ```

2. **Monitor new instance** (3 min)
   ```bash
   watch -n 5 'jstat -gc $(pgrep -f delos) | tail -1'
   ```

3. **If memory continues growing:**
   - Capture heap dump: `jmap -dump:live,format=b,file=/tmp/heap.bin $(pgrep -f delos)`
   - Report memory leak to platform team
   - Escalate for development investigation

### Escalation Matrix

| Alert | 1st Response | 2nd (15 min) | 3rd (30 min) |
|-------|--------------|--------------|--------------|
| **Quorum Lost** | On-call SRE | Platform Lead | VP Engineering |
| **Byzantine Detected** | On-call SRE | Security Lead | VP Engineering |
| **High Latency** | On-call SRE | Database Lead | Platform Lead |
| **OOM** | On-call SRE | Platform Lead | Infrastructure |
| **Disk Full** | On-call SRE | Infrastructure | CTO |

---

## SLA & Targets

### Availability Targets

| Metric | Small Prod (5K tx/sec) | Medium Prod (25K tx/sec) | Large Prod (100K+ tx/sec) |
|--------|------------------------|-------------------------|---------------------------|
| **Availability** | 99.9% | 99.95% | 99.99% |
| **Downtime Budget** | 43.2 min/month | 21.6 min/month | 4.3 min/month |

### Performance Targets

| Metric | P50 | P95 | P99 |
|--------|-----|-----|-----|
| **Transaction Latency** | 50ms | 250ms | 1000ms |
| **Consensus Latency** | 80ms | 500ms | 2000ms |
| **Block Apply Time** | 20ms | 100ms | 500ms |

### Alerting Response Targets

| Alert Severity | Detection | On-Call Ack | Resolution |
|----------------|-----------|------------|-----------|
| **Critical** | < 1 min | < 5 min | < 30 min |
| **Warning** | < 2 min | < 15 min | < 2 hours |
| **Info** | < 5 min | < 1 hour | Next business day |

---

## Related Documentation

**Phase 3 Guides:**
- **PERFORMANCE_TUNING.md** - Baseline thresholds used for alerts
- **OPERATIONAL_PROCEDURES.md** - Runbooks referenced in alerts
- **TROUBLESHOOTING_GUIDE.md** - Diagnostic procedures for each alert
- **DISASTER_RECOVERY.md** - Recovery procedures for critical scenarios

**Module Documentation:**
- **CHOAM** - `/choam/README.md` - Consensus protocol metrics
- **Fireflies** - `/fireflies/README.md` - Membership service metrics
- **SQL-State** - `/sql-state/README.md` - State machine metrics
- **Stereotomy** - `/stereotomy/README.md` - Identity metrics

**External References:**
- **Prometheus** - https://prometheus.io/docs/
- **Grafana** - https://grafana.com/docs/
- **AlertManager** - https://prometheus.io/docs/alerting/latest/alertmanager/

---

**Last Updated**: 2026-01-09
**Phase**: 3.5 (Operations & Maintenance)
**Epic**: Delos-aj2 (Documentation Improvement)
**Status**: Production Ready
