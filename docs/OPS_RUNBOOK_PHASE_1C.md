# Phase 1C Operations Runbook

**Version**: 1.0
**Date**: 2026-01-22
**Last Updated**: 2026-01-22
**Status**: Production Ready
**Audience**: Operations, DevOps, SRE Teams

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture & Components](#architecture--components)
3. [Deployment Guide](#deployment-guide)
4. [Key Rotation Procedures](#key-rotation-procedures)
5. [Byzantine Incident Response](#byzantine-incident-response)
6. [Graceful Degradation Monitoring](#graceful-degradation-monitoring)
7. [Performance Monitoring](#performance-monitoring)
8. [Troubleshooting Guide](#troubleshooting-guide)
9. [Emergency Procedures](#emergency-procedures)
10. [Appendix: Metrics Reference](#appendix-metrics-reference)

---

## Overview

Phase 1C implements **Byzantine fault-tolerant BLS aggregate signatures** with operational hardening for production deployment. This runbook covers:

- **7-node witness committees** with f=2 Byzantine fault tolerance
- **BLS aggregate signatures** for threshold receipt collection
- **Key rotation** without consensus disruption
- **Byzantine detection** with automated response escalation
- **Graceful degradation** during view changes and partial outages

### Critical Parameters

| Parameter | Value | Notes |
|-----------|-------|-------|
| Committee Size (n) | 7 | 3f+1, f=2 |
| Threshold (t) | 5 | 2f+1 majority |
| Max Byzantine (f) | 2 | Can tolerate 2 compromised nodes |
| Drain Period | 500ms | View change buffer duration |
| Grace Period (Key Rotation) | 1 hour | Old signatures still accepted |
| Pre-Rotation Period | 24 hours | Announcement period before activation |
| Byzantine Detection Window | 5 minutes | Sliding window for anomaly scoring |

---

## Architecture & Components

### Core Components

```
┌─────────────────────────────────────────────────┐
│        Phase 1C BLS Witness Service             │
├─────────────────────────────────────────────────┤
│                                                 │
│  ┌──────────────────────────────────────────┐  │
│  │  Receipt Collection & Aggregation         │  │
│  │  - SignatureAccumulator                   │  │
│  │  - BLSReceiptAggregator                   │  │
│  │  - MultiCommitteeAggregate (Phase 2)      │  │
│  └──────────────────────────────────────────┘  │
│                                                 │
│  ┌──────────────────────────────────────────┐  │
│  │  Byzantine Detection & Response            │  │
│  │  - EquivocationDetector                    │  │
│  │  - TimingAnomalyDetector                   │  │
│  │  - RateAnomalyDetector                     │  │
│  │  - ResponseEscalationEngine                │  │
│  │  - Fireflies Integration (Shunning)        │  │
│  └──────────────────────────────────────────┘  │
│                                                 │
│  ┌──────────────────────────────────────────┐  │
│  │  Key Management & Rotation                 │  │
│  │  - BLSKeyRotationManager                   │  │
│  │  - Stereotomy KERI Integration             │  │
│  │  - Proof-of-Possession Validation          │  │
│  └──────────────────────────────────────────┘  │
│                                                 │
│  ┌──────────────────────────────────────────┐  │
│  │  Graceful Degradation                      │  │
│  │  - SignatureBuffer                         │  │
│  │  - DegradedThresholdCalculator             │  │
│  │  - WitnessFirefliesIntegration             │  │
│  └──────────────────────────────────────────┘  │
│                                                 │
│  ┌──────────────────────────────────────────┐  │
│  │  Observability & Metrics                   │  │
│  │  - BLSMetrics                              │  │
│  │  - ByzantineDetectionMetrics               │  │
│  │  - ResponseOrchestrationMetrics            │  │
│  └──────────────────────────────────────────┘  │
│                                                 │
└─────────────────────────────────────────────────┘
```

### Integration Points

1. **Fireflies** (Membership & Gossip)
   - Committee selection and view changes
   - Member shunning integration
   - Drain period coordination

2. **Ethereal** (Consensus)
   - Signature collection coordination
   - Block commitment with BLS aggregates

3. **CHOAM** (State Replication)
   - Receipt accumulation state
   - View change callbacks

4. **Stereotomy** (Identity & KERI)
   - Key event receipt infrastructure
   - Key rotation events (rot)

---

## Deployment Guide

### Prerequisites

- Java 25+ (required for production)
- Maven 3.9.3+
- 7-node cluster (minimum for BFT tolerance)
- Network latency <500ms between nodes
- System clock synchronization <500ms across cluster

### Pre-Deployment Checklist

```
[ ] Hardware provisioning (7 nodes)
[ ] Network connectivity verified (<500ms latency)
[ ] NTP configured on all nodes
[ ] Java 25+ installed and configured
[ ] TLS certificates generated and distributed
[ ] Database prepared (H2 or PostgreSQL)
[ ] Monitoring stack ready (Prometheus, Grafana)
[ ] Backup procedures configured
[ ] Emergency response team trained
[ ] Run simulation test suite (Phase1CProductionSimulationTest)
[ ] Dry-run key rotation procedure
```

### Deployment Steps

#### 1. Build and Package

```bash
# First-time setup (required once)
./mvnw clean install -Ppre -DskipTests

# Build Phase 1C release
./mvnw clean install -pl witness-service
```

#### 2. Deploy to Witness Nodes

```bash
# Copy witness-service JAR to each node
scp target/witness-service-0.2.3-SNAPSHOT.jar ops@witness-1:/opt/delos/

# Generate node identities using Stereotomy
java -cp /opt/delos/witness-service-0.2.3-SNAPSHOT.jar \
  com.hellblazer.delos.stereotomy.StereotomyBootstrap \
  --node-id witness-1 \
  --out /opt/delos/keys/witness-1.keri
```

#### 3. Configure Witness Service

Create `/opt/delos/witness-service.properties` on each node:

```properties
# Witness Configuration
witness.committee.size=7
witness.threshold=5
witness.drain.period.ms=500
witness.k=7

# Byzantine Detection
byzantine.detection.enabled=true
byzantine.detection.window.minutes=5
byzantine.shunning.threshold=5
byzantine.shunning.enabled=true

# Key Rotation
key.rotation.enabled=true
key.rotation.grace.period.hours=1
key.rotation.pre.rotation.hours=24

# Performance
metrics.enabled=true
metrics.jmx.enabled=false
performance.target.ops.per.sec=1200
performance.target.p99.us=1100

# Networking
gossip.duration.ms=5
view.change.drain.ms=500
```

#### 4. Start Witness Service

```bash
# On each witness node (staggered by 2 seconds to avoid thundering herd)
java -server -Xmx4G -Xms2G \
  -Dcom.sun.management.jmxremote \
  -Dcom.sun.management.jmxremote.port=9010 \
  -Dcom.sun.management.jmxremote.authenticate=false \
  -Dcom.sun.management.jmxremote.ssl=false \
  -cp /opt/delos/witness-service-0.2.3-SNAPSHOT.jar \
  com.hellblazer.delos.witness.WitnessService
```

#### 5. Verify Cluster Formation

```bash
# Check cluster health (on any node)
curl -s http://localhost:8080/health/cluster | jq .

# Expected output:
{
  "status": "healthy",
  "active_members": 7,
  "committee_size": 7,
  "byzantine_count": 0,
  "threshold": 5,
  "view_height": 1
}
```

#### 6. Run Integration Tests

```bash
# Run 5-minute integration test
./mvnw test -pl witness-service \
  -Dtest=Phase1CProductionSimulationTest \
  -Dsimulation.duration.minutes=5

# Expected: All tests PASS, zero P0 issues
```

---

## Key Rotation Procedures

### Overview

Key rotation in Phase 1C follows a **3-phase process** to maintain consensus safety:

1. **Pre-Rotation (24 hours)** - Announce new key, old key remains active
2. **Rotation (1 hour grace period)** - Accept both old and new signatures
3. **Post-Rotation** - Only new key active, old signatures rejected

### Step-by-Step Procedure

#### Phase 1: Announcement (24 hours before rotation)

**Timeline**: T-24h

```bash
# On any committee member with admin credentials
curl -X POST http://localhost:8080/admin/key-rotation/announce \
  -H "Content-Type: application/json" \
  -d '{
    "node_id": "witness-3",
    "new_key_epoch": 42,
    "rotation_time_utc": "2026-01-23T14:00:00Z"
  }'

# Response:
{
  "status": "announced",
  "current_key_version": 41,
  "next_key_version": 42,
  "rotation_time": "2026-01-23T14:00:00Z",
  "grace_period_hours": 1
}
```

**Verification**:
- All 7 nodes receive announcement via Fireflies gossip
- New key propagated through KERI events
- Monitoring dashboard shows "Rotation Announced" status

**Monitoring**:
```bash
# Monitor gossip propagation
tail -f /var/log/delos/witness-service.log | grep "KEY_ROTATION"

# Expected: 7 log entries (one per node) within 30 seconds
```

#### Phase 2: Rotation Activation (At T-0)

**Timeline**: T ± 5 minutes

```bash
# Automatic activation (no action needed if time-synchronized)
# Manual activation if needed:
curl -X POST http://localhost:8080/admin/key-rotation/activate \
  -H "Content-Type: application/json" \
  -d '{
    "node_id": "witness-3",
    "new_key_epoch": 42
  }'

# Response:
{
  "status": "active",
  "current_key_version": 42,
  "grace_period_until": "2026-01-23T15:00:00Z",
  "signatures_accepted": ["42", "41"]
}
```

**Verification**:
- New key active on all 7 nodes
- Both old and new signatures accepted
- No consensus disruption observed
- Signature latency <100µs (no increase)

**Health Check**:
```bash
# Check key rotation status
curl -s http://localhost:8080/metrics/key-rotation | jq .

# Expected output:
{
  "current_key_epoch": 42,
  "signatures_with_new_key": 1250,
  "signatures_with_old_key": 312,
  "grace_period_active": true,
  "grace_period_remaining_seconds": 1800
}
```

#### Phase 3: Grace Period Completion (T + 1 hour)

**Timeline**: T+1h

```bash
# Automatic grace period expiration
# Manual deactivation if needed:
curl -X POST http://localhost:8080/admin/key-rotation/complete \
  -H "Content-Type: application/json" \
  -d '{
    "node_id": "witness-3",
    "old_key_epoch": 41
  }'

# Response:
{
  "status": "completed",
  "current_key_version": 42,
  "old_key_deactivated": 41,
  "signatures_accepted": ["42"]
}
```

**Verification**:
- Old key no longer accepted
- All new signatures use new key
- No consensus impact observed
- Monitoring shows "Rotation Complete"

### Emergency Key Rotation

**Use Case**: Suspected key compromise

**Procedure**:
```bash
# Emergency rotation (immediate, no grace period)
curl -X POST http://localhost:8080/admin/key-rotation/emergency \
  -H "Content-Type: application/json" \
  -d '{
    "node_id": "witness-3",
    "reason": "suspected_compromise",
    "new_key_epoch": 43
  }'

# Response includes:
{
  "status": "emergency_rotation_active",
  "old_key_deactivated": 42,
  "new_key_active": 43,
  "warning": "All old signatures rejected - consensus may be disrupted"
}
```

**Risks**:
- May cause temporary consensus disruption
- In-flight signatures with old key rejected
- Use only in compromise scenarios

**Post-Emergency Actions**:
1. Investigate key compromise
2. Replace node if necessary
3. Run integration tests to validate cluster stability
4. Document incident

### Monitoring During Rotation

**Critical Metrics** (should remain stable):
- Receipt collection latency: <1100µs P99
- Threshold achievement rate: >99%
- Byzantine detection accuracy: unchanged
- Consensus progression: no blocks delayed

**Dashboard Query** (Prometheus):
```promql
# Key rotation overhead measurement
rate(bls_signature_latency_us[5m]) - baseline_latency_us

# Should be: <10µs increase
```

---

## Byzantine Incident Response

### Incident Types & Response Levels

| Incident | Detection | Response | Escalation |
|----------|-----------|----------|------------|
| Invalid Signature | Immediate | ALERT (monitoring only) | None |
| Equivocation | 5-min window | QUARANTINE (exclude from collection) | Shunning after 5 violations |
| Timing Anomaly | 5-min window | ALERT | Investigation |
| Coordinated Attack | Multiple detectors | REQUEST_KEY_ROTATION | View change if severe |
| Signature Forgery | Immediate | SHUN (permanent) | Auto-escalate |

### Playbook: Single Node Invalid Signatures

**Detection**:
```
[2026-01-22 14:23:15] ByzantineDetector: witness-2 submitted 3 invalid signatures
[2026-01-22 14:23:15] AnomalyScore: witness-2 score increased to 0.65
[2026-01-22 14:23:15] ResponseEscalation: ALERT - Invalid signature rate elevated
```

**Response**:
```bash
# 1. Investigate node health
ssh ops@witness-2 "tail -100 /var/log/delos/witness-service.log | grep ERROR"

# 2. Check BLS key validity
curl -s http://witness-2:8080/metrics/key-validation | jq .

# 3. Check network connectivity
ping -c 5 witness-2
mtr --report witness-2

# 4. If network issue, no action needed (signature collection excludes node)
# If key corrupted, escalate to emergency key rotation
```

**Monitoring**:
```bash
# Watch anomaly score decay
while true; do
  curl -s http://witness-2:8080/metrics/anomaly-score | jq .
  sleep 10
done

# Score should decay over 5 minutes if issue resolved
```

**Escalation to QUARANTINE**:
```bash
# If 5+ violations in 5-minute window:
curl -X POST http://localhost:8080/admin/byzantine/quarantine \
  -H "Content-Type: application/json" \
  -d '{
    "node_id": "witness-2",
    "reason": "repeated_invalid_signatures"
  }'

# Witness-2 now excluded from signature collection
# Threshold drops from 5 to 4 (degraded but operational)
```

### Playbook: Equivocation Attack

**Detection**:
```
[2026-01-22 15:10:22] EquivocationDetector: witness-4 signed conflicting receipts
[2026-01-22 15:10:22] Receipt-A: event-123 with digest ABC...
[2026-01-22 15:10:22] Receipt-B: event-123 with digest XYZ...
[2026-01-22 15:10:22] AnomalyScore: witness-4 score set to CRITICAL (1.0)
```

**Response**:
```bash
# 1. Immediate action: Quarantine node
curl -X POST http://localhost:8080/admin/byzantine/quarantine \
  -H "Content-Type: application/json" \
  -d '{
    "node_id": "witness-4",
    "reason": "equivocation_detected"
  }'

# 2. Trigger Fireflies shunning (permanent exclusion)
curl -X POST http://localhost:8080/admin/byzantine/shun \
  -H "Content-Type: application/json" \
  -d '{
    "node_id": "witness-4"
  }'

# 3. Investigate compromised node
ssh ops@witness-4 "sudo systemctl stop delos-witness-service"
ssh ops@witness-4 "sudo tar czf /opt/backups/witness-4-incident-$(date +%s).tar.gz /opt/delos"

# 4. Forensic analysis (preserve logs)
ssh ops@witness-4 "tail -1000 /var/log/delos/witness-service.log > /tmp/witness-4-forensics.log"
scp ops@witness-4:/tmp/witness-4-forensics.log /opt/incident-reports/

# 5. Restart node (if not replacing hardware)
# First: redeploy with fresh keys
./deploy-node.sh witness-4 --fresh-keys
```

**Cluster State After Equivocation**:
- 6 active nodes (witness-4 shunned)
- n=6, f=2, threshold=5 (degraded but operational)
- Consensus continues with 5+ signatures

**Post-Incident Actions**:
1. Document forensic findings
2. Update security policies if needed
3. Schedule root cause analysis meeting
4. Plan replacement strategy for shunned node

### Playbook: Coordinated Byzantine Attack

**Detection** (3+ detectors triggering):
```
[2026-01-22 16:05:30] SignatureAnomalyDetector: witness-1 invalid signature (1/5)
[2026-01-22 16:05:31] TimingAnomalyDetector: witness-1 timing anomaly (1/5)
[2026-01-22 16:05:32] RateAnomalyDetector: witness-1 failure rate elevated (1/5)
[2026-01-22 16:05:33] ResponseEscalation: COORDINATED ATTACK - Request view change
```

**Response**:
```bash
# 1. Immediate escalation
# System automatically requests view change via Ethereal
# No manual action needed (automatic escalation)

# 2. Monitor view change progression
curl -s http://localhost:8080/metrics/view-change | jq .

# Expected response:
{
  "current_view": 5,
  "previous_view": 4,
  "view_change_reason": "byzantine_escalation",
  "transition_time_ms": 1250,
  "new_committee": ["witness-2", "witness-3", "witness-5", "witness-6", "witness-7"],
  "excluded_members": ["witness-1"],
  "new_threshold": 4
}

# 3. Verify consensus resumed
curl -s http://localhost:8080/health/consensus | jq .
# Should show: "status": "ok", "blocks_since_view_change": 15

# 4. Incident investigation
./scripts/analyze-byzantine-incident.sh --incident-id witness-1 --time-range 16:00-16:10
```

**Recovery**:
1. Perform full node forensics
2. If node compromised: complete replacement
3. If false positive: investigate detector tuning
4. Restore node to cluster (if replacement) or remove permanently

---

## Graceful Degradation Monitoring

### View Change Procedure

Phase 1C maintains consensus **during view changes** through signature buffering:

```
STABLE
  ↓ (membership change)
DRAINING (500ms buffer period)
  ↓ (buffer full or timeout)
TRANSITIONING
  ↓ (new committee formed)
STABLE
```

### Monitoring View Changes

**Metrics During Drain Period**:
```bash
# Watch buffer fill during 500ms drain
curl -s http://localhost:8080/metrics/signature-buffer | jq .

# Expected output:
{
  "drain_state": "DRAINING",
  "buffered_signatures": 45,
  "buffer_capacity": 1000,
  "drain_period_remaining_ms": 312,
  "new_committee": {
    "size": 7,
    "join_count": 2,
    "leave_count": 1
  }
}

# After drain completes:
{
  "drain_state": "STABLE",
  "signatures_replayed": 45,
  "signatures_lost": 0,
  "transition_time_ms": 523
}
```

### Threshold Adaptation During Degradation

When Byzantine members are excluded, threshold automatically recalculates:

```
Initial: n=7, f=2, threshold=5
After 1 Byzantine: n=6, threshold=5 (ceil(6 × 2/3) = 5)
After 2 Byzantine: n=5, threshold=4 (ceil(5 × 2/3) = 4)
```

**Monitoring Threshold Changes**:
```bash
# Check current degraded threshold
curl -s http://localhost:8080/metrics/threshold | jq .

# Response:
{
  "original_threshold": 5,
  "current_threshold": 4,
  "active_members": 5,
  "byzantine_members": 2,
  "bft_safety_maintained": true,
  "honest_majority": "5 >= 4 (SAFE)"
}
```

**Alert Conditions**:
```
# CRITICAL: Honest majority at risk
IF active_members < (3 * byzantine_members + 1) THEN ALERT

# Example:
# If 2+ Byzantine and 4 active → 4 < (3*2+1)=7 → UNSAFE
# Must trigger immediate view change or key rotation
```

---

## Performance Monitoring

### Key Performance Indicators (KPIs)

| Metric | Target | Alert Threshold | Critical |
|--------|--------|-----------------|----------|
| Receipt Latency P99 | <1100µs | >1500µs | >2000µs |
| Threshold Achievement Rate | >99% | <98% | <95% |
| Byzantine Detection Overhead | <1% | >2% | >5% |
| View Change Duration | <2s | >3s | >5s |
| Signature Verification Rate | >10k/s | <8k/s | <5k/s |
| Key Rotation Time | <2s | >3s | >5s |
| Committee Stability | >24h | <12h | <1h |

### Grafana Dashboard Queries

**Receipt Collection Latency**:
```promql
# P99 latency over 5-minute window
histogram_quantile(0.99, rate(bls_receipt_latency_us_bucket[5m]))

# Should be: <1100µs
```

**Threshold Achievement Rate**:
```promql
# Percentage of events reaching threshold
rate(bls_threshold_achieved_total[5m]) / rate(bls_collection_initiated_total[5m]) * 100

# Should be: >99%
```

**Byzantine Detection Overhead**:
```promql
# Percentage of time spent in detection logic
rate(byzantine_detection_time_us[5m]) / rate(signature_verification_time_us[5m]) * 100

# Should be: <1%
```

**Cluster Health**:
```promql
# Number of active committee members
witness_active_members

# Should be: 7 (or degraded value)
```

### Prometheus Alert Rules

```yaml
# rules/phase1c-alerts.yaml

groups:
  - name: phase1c_performance
    rules:
      - alert: ReceiptLatencyHigh
        expr: histogram_quantile(0.99, rate(bls_receipt_latency_us_bucket[5m])) > 1500
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "Phase 1C receipt latency elevated: {{ $value }}µs"
          runbook: "docs/OPS_RUNBOOK_PHASE_1C.md#troubleshooting-receipt-latency"

      - alert: ThresholdAchievementLow
        expr: (rate(bls_threshold_achieved_total[5m]) / rate(bls_collection_initiated_total[5m])) < 0.95
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Phase 1C threshold achievement <95%"
          runbook: "docs/OPS_RUNBOOK_PHASE_1C.md#troubleshooting-threshold-failure"

      - alert: ByzantineMembersHigh
        expr: witness_byzantine_count > 2
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Phase 1C Byzantine members exceeds tolerance: {{ $value }}/2"
          runbook: "docs/OPS_RUNBOOK_PHASE_1C.md#playbook-coordinated-byzantine-attack"

      - alert: ClusterNotHealthy
        expr: witness_active_members < 5
        for: 30s
        labels:
          severity: critical
        annotations:
          summary: "Phase 1C cluster unhealthy: {{ $value }}/7 active"
          runbook: "docs/OPS_RUNBOOK_PHASE_1C.md#troubleshooting-cluster-health"
```

---

## Troubleshooting Guide

### Troubleshooting: Receipt Latency High

**Symptoms**:
- Receipt collection P99 > 1500µs
- Alerts triggered: "ReceiptLatencyHigh"

**Root Cause Analysis**:
```bash
# 1. Check system resources
ssh witness-1 "top -b -n1 | head -20"
# Look for: CPU >80%, Memory >85%

# 2. Check Byzantine detection overhead
curl -s http://localhost:8080/metrics/detector-overhead | jq .

# 3. Check network latency
for i in 1 2 3 4 5 6 7; do
  echo -n "witness-$i: "
  ping -c 1 -q witness-$i | grep rtt | awk '{print $4}' | cut -d/ -f2
done
# All should be <50ms (typical LAN)
```

**Solutions**:
| Root Cause | Solution |
|-----------|----------|
| High CPU | Reduce Byzantine detection sensitivity or scale up |
| High Memory | Increase JVM heap or restart service |
| High Network Latency | Check network path, QoS, or migrate nodes |
| Disk I/O | Check disk space and I/O contention |
| Byzantine Detection | See "Byzantine Detection False Positives" |

**Quick Fix**:
```bash
# Restart witness service (if memory leak suspected)
ssh ops@witness-1 "sudo systemctl restart delos-witness-service"

# Monitor after restart
while true; do
  curl -s http://localhost:8080/metrics/latency | jq '.p99'
  sleep 5
done
```

### Troubleshooting: Threshold Achievement Rate Low

**Symptoms**:
- <95% of collections reach threshold
- "ThresholdAchievementLow" alert triggered

**Root Cause Analysis**:
```bash
# 1. Check member participation
curl -s http://localhost:8080/metrics/member-participation | jq .

# Expected: all 7 members >95% participation

# 2. Identify failing signatures
curl -s http://localhost:8080/metrics/signature-failures | jq .

# Response might show:
{
  "witness-1": 10,    # 10 failed signatures from witness-1
  "witness-2": 2,
  "witness-3": 0,
  ...
}

# 3. Check if Byzantine detection is too aggressive
curl -s http://localhost:8080/metrics/false-positives | jq .
```

**Solutions**:
| Root Cause | Solution |
|-----------|----------|
| Single node failing | Check node health, maybe quarantine |
| Multiple nodes failing | Check network path or detector calibration |
| Byzantine false positives | Increase detection threshold (5-min window) |
| Threshold miscalculation | Verify n, f, threshold values |

**Tuning Byzantine Detection**:
```bash
# Reduce sensitivity (false positive rate)
curl -X POST http://localhost:8080/admin/detection/tune \
  -H "Content-Type: application/json" \
  -d '{
    "anomaly_score_threshold": 0.8,  # was 0.7 (more conservative)
    "failure_count_threshold": 6      # was 5 (tolerate more failures)
  }'

# Monitor false positives after tuning
curl -s http://localhost:8080/metrics/detection-accuracy | jq .
```

### Troubleshooting: Byzantine Members Exceeding Tolerance

**Symptoms**:
- Byzantine count > 2
- "ByzantineMembersHigh" alert

**Response**:
```bash
# 1. Immediate: Request view change
curl -X POST http://localhost:8080/admin/view-change/trigger \
  -H "Content-Type: application/json" \
  -d '{
    "reason": "byzantine_exceeded_tolerance"
  }'

# Monitor view change
while true; do
  curl -s http://localhost:8080/health/consensus | jq '.view_height'
  sleep 2
done

# 2. After view change: Cluster should be healthy
curl -s http://localhost:8080/health/cluster | jq .
```

### Troubleshooting: Cluster Unhealthy

**Symptoms**:
- Active members < 5 (below minimum)
- "ClusterNotHealthy" alert

**Emergency Response**:
```bash
# 1. Check cluster status
curl -s http://localhost:8080/health/cluster | jq .

# 2. Identify down nodes
for i in 1 2 3 4 5 6 7; do
  echo -n "witness-$i: "
  curl -s http://witness-$i:8080/health || echo "DOWN"
done

# 3. Attempt restart on down nodes
ssh ops@witness-1 "sudo systemctl restart delos-witness-service"

# 4. If restart fails, check logs
ssh ops@witness-1 "tail -50 /var/log/delos/witness-service.log | grep ERROR"

# 5. If multiple nodes down: Declare incident, escalate
./scripts/incident-declare.sh --severity critical --reason "cluster_unhealthy"
```

**Recovery**:
```bash
# After nodes restored
# Run integration test to validate
./mvnw test -pl witness-service -Dtest=Phase1CProductionSimulationTest -Dsimulation.duration.minutes=5

# Expected: All tests PASS
```

---

## Emergency Procedures

### Procedure: Emergency Key Rotation

**Use When**: Suspected key compromise

```bash
#!/bin/bash
# emergency-key-rotation.sh

set -e

TARGET_NODE="${1:-witness-1}"
INCIDENT_ID="INC-$(date +%s)"

echo "[$(date)] Starting emergency key rotation for $TARGET_NODE"
echo "[$(date)] Incident ID: $INCIDENT_ID"

# 1. Trigger emergency rotation (immediate, no grace period)
curl -X POST http://localhost:8080/admin/key-rotation/emergency \
  -H "Content-Type: application/json" \
  -d "{
    \"node_id\": \"$TARGET_NODE\",
    \"reason\": \"suspected_compromise\",
    \"incident_id\": \"$INCIDENT_ID\"
  }"

# 2. Monitor cluster stability (may be disrupted temporarily)
for i in {1..10}; do
  echo "[$(date)] Checking cluster health..."
  curl -s http://localhost:8080/health/cluster | jq '.status'
  sleep 5
done

# 3. After stabilization, run tests
echo "[$(date)] Running integration tests..."
./mvnw test -pl witness-service -Dtest=Phase1CProductionSimulationTest \
  -Dsimulation.duration.minutes=5

echo "[$(date)] Emergency key rotation complete (Incident: $INCIDENT_ID)"
```

### Procedure: Emergency Node Replacement

**Use When**: Hardware failure or unrecoverable corruption

```bash
#!/bin/bash
# emergency-node-replacement.sh

FAILED_NODE="${1:-witness-3}"
REPLACEMENT_HOST="${2:-witness-replacement-1}"

echo "[$(date)] Starting emergency node replacement"
echo "[$(date)] Failed node: $FAILED_NODE"
echo "[$(date)] Replacement: $REPLACEMENT_HOST"

# 1. Quarantine failed node (prevent further damage)
curl -X POST http://localhost:8080/admin/byzantine/quarantine \
  -H "Content-Type: application/json" \
  -d "{\"node_id\": \"$FAILED_NODE\", \"reason\": \"hardware_failure\"}"

# 2. Wait for Fireflies to exclude
sleep 10

# 3. Deploy replacement node
scp witness-service-0.2.3-SNAPSHOT.jar ops@$REPLACEMENT_HOST:/opt/delos/

# 4. Start replacement with fresh keys
ssh ops@$REPLACEMENT_HOST "
  java -server -Xmx4G -Xms2G \
    -cp /opt/delos/witness-service-0.2.3-SNAPSHOT.jar \
    com.hellblazer.delos.witness.WitnessService \
    --node-id=$REPLACEMENT_HOST \
    --fresh-keys
"

# 5. Verify cluster stability
for i in {1..30}; do
  ACTIVE=$(curl -s http://localhost:8080/health/cluster | jq '.active_members')
  echo "[$(date)] Active members: $ACTIVE/7"
  if [ "$ACTIVE" -eq "7" ]; then
    echo "[$(date)] Cluster recovered to 7 members"
    break
  fi
  sleep 2
done
```

### Procedure: Escalation Decision Tree

```
Incident Detected
  │
  ├─ Single Invalid Signature
  │   └─ RESPONSE: Monitor (no action, self-healing)
  │
  ├─ Equivocation (1 node)
  │   └─ RESPONSE: Quarantine → Monitor recovery
  │       If 5+ violations in 5 min → SHUN
  │
  ├─ Multiple Byzantine (2+ nodes)
  │   ├─ IF Coordinated Attack
  │   │   └─ RESPONSE: Automatic view change + escalation
  │   └─ IF Distributed failures
  │       └─ RESPONSE: Quarantine + Monitor
  │
  ├─ Key Compromise (Suspected)
  │   └─ RESPONSE: Emergency key rotation + forensics
  │
  ├─ Hardware Failure (1 node)
  │   └─ RESPONSE: Quarantine + Plan replacement
  │       If replacement delayed → Consensus degrades
  │
  ├─ Multiple Node Failures (3+)
  │   └─ RESPONSE: CRITICAL - Consensus lost
  │       1. Declare incident
  │       2. Failover to backup cluster
  │       3. Root cause analysis
  │       4. Restore from last known good state
  │
  └─ Network Partition
      └─ RESPONSE: Depends on partition
          If isolated node: Mark Byzantine, continue
          If split cluster: Choose majority, shun minority
```

---

## Appendix: Metrics Reference

### BLS Aggregation Metrics

```
# Receipt collection latency (per event)
bls_receipt_latency_us{quantile="0.50"}      # P50
bls_receipt_latency_us{quantile="0.95"}      # P95
bls_receipt_latency_us{quantile="0.99"}      # P99

# Signature collection progress
bls_signatures_collected_total               # Total signatures processed
bls_threshold_achieved_total                 # Collections reaching threshold
bls_collection_initiated_total               # Collections started

# Aggregate verification
bls_aggregate_verify_time_us                 # Verification latency
bls_aggregate_verify_failed_total            # Failed verifications
```

### Byzantine Detection Metrics

```
# Detector activity
byzantine_invalid_signatures_total           # Invalid signatures detected
byzantine_equivocations_total                # Equivocation events
byzantine_timing_anomalies_total             # Timing anomalies
byzantine_rate_anomalies_total               # Rate anomalies

# Member status
byzantine_member_anomaly_score{member="..."}  # Per-member score (0.0-1.0)
byzantine_quarantine_count                   # Currently quarantined
byzantine_shunned_count                      # Permanently shunned

# Detection overhead
byzantine_detection_time_us                  # Time spent detecting
byzantine_false_positive_rate                # FP rate (should be <1%)
```

### Graceful Degradation Metrics

```
# View change coordination
view_change_total                            # Total view changes
view_change_drain_time_ms                    # Drain period duration
signature_buffer_size                        # Buffered signatures
signature_buffer_drained_total               # Signatures replayed

# Threshold adaptation
degraded_threshold_current                   # Current adapted threshold
active_members_count                         # Non-quarantined members
byzantine_members_count                      # Quarantined/shunned
bft_safety_maintained                        # Boolean: safety OK?
```

### Key Rotation Metrics

```
# Rotation events
key_rotation_initiated_total                 # Rotations started
key_rotation_completed_total                 # Rotations completed
key_rotation_emergency_total                 # Emergency rotations

# Grace period tracking
key_rotation_grace_period_active             # Boolean: grace active?
key_rotation_old_key_signatures              # Signatures with old key
key_rotation_new_key_signatures              # Signatures with new key
key_rotation_grace_period_remaining_seconds  # Time left
```

---

## Support & Escalation

### Support Contacts

| Role | Contact | On-Call |
|------|---------|---------|
| Operations Lead | ops-lead@example.com | 24/7 PagerDuty |
| SRE Team | sre-team@example.com | Rotating on-call |
| Security Team | security@example.com | For incidents |
| Incident Commander | ic@example.com | On-call rotation |

### Incident Severity Levels

| Level | Criteria | Response Time | Escalation |
|-------|----------|---------------|------------|
| P1 (Critical) | Consensus lost, >2 Byzantine | 5 minutes | VP Engineering |
| P2 (Major) | Degraded (1 Byzantine), <1200 ops/sec | 15 minutes | Engineering Lead |
| P3 (Minor) | Performance <SLA, false positives | 1 hour | Team Lead |
| P4 (Info) | Cosmetic, documentation | 24 hours | Self-serve |

### Post-Incident Review

All P1 and P2 incidents require:
1. Incident report filed within 4 hours
2. Root cause analysis completed within 24 hours
3. Action items assigned with due dates
4. Blameless post-mortem (culture first)

---

**Document Version**: 1.0
**Last Updated**: 2026-01-22
**Next Review**: 2026-04-22 (quarterly)

For questions or feedback, contact: ops-team@example.com
