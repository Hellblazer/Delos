# Phase 1C Operations Runbook

Byzantine Detection, Graceful Degradation, and Performance Monitoring

## Overview

Phase 1C implements Byzantine fault tolerance with comprehensive detection, graceful degradation under failures, and performance-optimized consensus. This runbook covers operational procedures, monitoring, and recovery for Byzantine detection infrastructure in the Delos witness service.

### Key Components

- **Byzantine Detection (D-1)**: Multi-detector anomaly detection system (signature validation, timing analysis, rate monitoring)
- **Response Orchestration (D-2)**: Escalation coordination (quarantine, key rotation, view change)
- **Graceful Degradation (D-3)**: Dynamic threshold adjustment, signature buffering during failures
- **View Change Integration (D-4)**: CHOAM-coordinated view changes with Byzantine member exclusion
- **Performance Monitoring (D-5)**: <1% overhead validation with continuous metrics

### Design Principles

- **Non-blocking async**: All detection and orchestration operations use non-blocking APIs
- **Threshold-based**: Detection uses anomaly scores (0.0-1.0) with configurable thresholds
- **Bounded memory**: Byzantine detection uses <50KB per committee member
- **Deterministic**: Detection and exclusion decisions based on configurable rules

## Pre-Deployment Checklist

### Infrastructure Requirements

```
□ Minimum 7 witness nodes (f=2 Byzantine tolerance: 3f+1 = 7)
□ Network latency: <50ms p99 between nodes (for consensus)
□ Clock synchronization: ±500ms max skew (NTP configured)
□ Memory: >1GB per witness node for Phase 1C metrics
□ CPU: Multi-core (≥4 cores) for parallel detection
□ Storage: 10GB+ for CHOAM consensus log
□ Monitoring: Prometheus instance for metrics scraping
□ Alerting: Alert manager configured for Byzantine events
```

### Configuration Validation

Before deploying Phase 1C, verify:

```bash
# 1. Check Byzantine detector configuration
grep -r "ByzantineDetectorConfig" config/

# 2. Verify metrics are enabled
grep -r "WitnessMetricsBootstrap" config/ witness-service/src/main/

# 3. Confirm metrics ports are available
netstat -an | grep 9090  # Prometheus HTTP endpoint
netstat -an | grep 9010  # JMX port range

# 4. Check disk space for metrics
df -h /var/lib/delos-witness/
```

### Required Metrics Endpoints

| Endpoint | Port | Purpose | Status |
|----------|------|---------|--------|
| JMX MBeans | 9010 | Local JVM monitoring | Auto (if reporters enabled) |
| Prometheus | 9090 | Time-series metrics | Requires configuration |
| Health check | 8080/health | Service health | Built-in |

## Deployment Procedure

### Phase 1: Initialize Metrics Baseline

**Goal**: Establish baseline metrics before Byzantine detection is active.

1. **Start witness service with metrics enabled**:
   ```bash
   java -Dcom.sun.management.jmxremote \
        -Dcom.sun.management.jmxremote.port=9010 \
        -Dcom.sun.management.jmxremote.authenticate=false \
        -Dcom.sun.management.jmxremote.ssl=false \
        -jar witness-service/target/witness-service.jar
   ```

2. **Verify metrics are exposed**:
   ```bash
   # Via JMX
   jconsole localhost:9010
   # Check: com.codahale.metrics → witness-service → metrics

   # Via JMX remote (if Prometheus gateway configured)
   curl http://localhost:9090/metrics | grep bls_signatures_received
   ```

3. **Record baseline metrics** (run for 5 minutes):
   ```bash
   # Normal signature receipt rate
   curl -s http://localhost:9090/metrics | grep "bls_signatures_received{" | head -1

   # Expected output: bls_signatures_received{...} 1500.0
   # This establishes your normal throughput baseline
   ```

4. **Verify no Byzantine detections** (should be zero):
   ```bash
   jconsole → com.codahale.metrics → byzantine.detection.anomaly.detection.*
   # All detector meters should show 0 rate
   ```

### Phase 2: Enable Byzantine Detection

**Goal**: Activate Byzantine detection with conservative thresholds.

1. **Update Byzantine detector configuration**:
   ```yaml
   # config/byzantine-detector.yaml
   detectors:
     signature:
       enabled: true
       threshold: 5           # 5 invalid signatures = flag
       mode: "warning"        # warning before critical

     timing:
       enabled: true
       threshold: 95th percentile  # 95% of latencies
       mode: "warning"

     rate:
       enabled: true
       threshold: 0.5         # 50% failure rate = flag
       mode: "warning"

   orchestration:
     escalation_enabled: true
     auto_quarantine: false   # Manual quarantine initially
     key_rotation_enabled: false  # Defer to Phase 2
   ```

2. **Restart witness service with new config**:
   ```bash
   # Existing service stops gracefully
   kill -SIGTERM $(pgrep -f witness-service)

   # Wait for graceful shutdown
   sleep 10

   # Restart with new config
   ./start-witness.sh
   ```

3. **Monitor detection startup** (5-10 minutes):
   ```bash
   # Watch for initial configuration load
   tail -f /var/log/delos-witness/witness.log | grep -i "byzantine\|detection\|metrics"

   # Expected logs:
   # - "Byzantine detection metrics registered"
   # - "Byzantine detector coordinator initialized"
   # - "Detector registered: SIGNATURE_ANOMALY"
   # - "Detector registered: TIMING_ANOMALY"
   # - "Detector registered: RATE_ANOMALY"
   ```

4. **Verify detectors are active**:
   ```bash
   jconsole → com.codahale.metrics → byzantine.detection.*

   # Should see these gauge values:
   # - anomaly.detection.signature (Counter starting at 0)
   # - anomaly.detection.timing (Counter starting at 0)
   # - anomaly.detection.rate (Counter starting at 0)
   ```

### Phase 3: Monitor Detection Accuracy

**Goal**: Verify Byzantine detection operates correctly and has low false positive rate.

1. **Baseline metrics collection** (24-48 hours):
   ```bash
   # Collect metrics every 5 minutes
   for i in {1..288}; do  # 24 hours
     echo "$(date): $(curl -s http://localhost:9090/metrics | grep byzantine_detection)"
     sleep 300
   done > byzantine-baseline.txt
   ```

2. **Analyze false positive rate**:
   ```bash
   # Check false positive counters
   jconsole → byzantine.detection.false.positive.*

   # If rate > 1% of all detections:
   # - Increase threshold (0.7 → 0.8 for anomaly score)
   # - Review network latency variance
   # - Check for legitimate slow nodes
   ```

3. **Simulate Byzantine member** (controlled test):
   ```bash
   # Inject invalid signatures on test node
   # (requires test infrastructure - Phase 1C tests)
   ./mvnw test -Dtest=ByzantineDetectionAccuracyTest -pl witness-service

   # Verify detection occurs within 5 seconds
   # Verify escalation follows configured policy
   ```

4. **Review metrics dashboards**:
   - Prometheus dashboard (if configured):
     - Graph: `rate(byzantine_detection_anomaly_detection_signature[5m])`
     - Alert thresholds: High > 10/min, Medium > 5/min
   - JMX dashboards:
     - Active quarantines: Should be 0-1 normally
     - Ensemble votes: Should be distributed across detectors

## Metrics Monitoring

### Key Metrics to Monitor

#### Byzantine Detection Metrics

**Anomaly Detection**:
```
byzantine.detection.anomaly.detection.signature     (Meter)  - Invalid signatures/sec
byzantine.detection.anomaly.detection.timing        (Meter)  - Slow receipts/sec
byzantine.detection.anomaly.detection.rate          (Meter)  - High failure rate events/sec
```

**Detection Quality**:
```
byzantine.detection.detection.latency.signature     (Timer)  - Signature validation latency
byzantine.detection.detection.latency.timing        (Timer)  - Timing analysis latency
byzantine.detection.anomaly.score.*                 (Histo)  - Anomaly scores (0-1)
```

**Escalation & Quarantine**:
```
byzantine.detection.ensemble.vote                   (Histo)  - Multi-detector consensus (0-3)
byzantine.detection.quorum.reached                  (Counter) - Quorum events
byzantine.detection.quarantine.event                (Counter) - Members quarantined
byzantine.detection.members.excluded                (Gauge)   - Active exclusions
byzantine.detection.escalation.action               (Counter) - Escalation actions by type
```

#### BLS Metrics

**Signature Operations**:
```
bls.signatures.received                             (Counter) - Total signatures received
bls.signatures.accepted                             (Counter) - Accepted signatures
bls.signature.receipt.latency                       (Timer)   - Receipt processing latency
bls.signature.verify.latency                        (Timer)   - Signature verification latency
```

**View Changes** (Phase 1C):
```
bls.view.changes.initiated                          (Counter) - View change count
bls.view.change.duration                            (Timer)   - View change latency
bls.view.active                                     (Gauge)   - Current view number
```

**Graceful Degradation** (Phase 1C):
```
bls.degradation.buffers.created                     (Counter) - Signature buffers created
bls.degradation.buffered.signatures.drained         (Counter) - Buffered sigs processed
bls.degradation.threshold.delta                     (Gauge)   - Current threshold adjustment
bls.degradation.byzantine.exclusions                (Counter) - Members excluded
```

### Prometheus Query Examples

```promql
# Byzantine detection rate (signatures per minute)
rate(byzantine_detection_anomaly_detection_signature[1m])

# Detection latency (p99)
histogram_quantile(0.99, rate(byzantine_detection_detection_latency_signature_bucket[5m]))

# Members actively excluded
byzantine_detection_members_excluded

# View changes per hour
increase(bls_view_changes_initiated[1h])

# Signature receipt latency (p95)
histogram_quantile(0.95, rate(bls_signature_receipt_latency_bucket[5m]))

# Buffered signatures (graceful degradation)
bls_degradation_buffered_signatures_drained

# Ensemble voting distribution (multi-detector consensus)
histogram_quantile(0.99, byzantine_detection_ensemble_vote)
```

### Alert Thresholds (Recommended)

| Alert | Condition | Severity | Action |
|-------|-----------|----------|--------|
| High Byzantine Detection | rate > 10/min | warning | Check network stability |
| Quorum Detected | increase > 2 in 10m | critical | Investigate Byzantine activity |
| High Quarantine Rate | active > 3 | critical | Manual investigation required |
| View Change Storm | increase > 5 in 30m | warning | Check for cascading failures |
| Low Throughput | rate < 50% baseline | warning | Check detector overhead |

### YAML Alert Configuration

```yaml
groups:
  - name: delos-witness-phase1c
    rules:
      - alert: HighByzantineDetectionRate
        expr: rate(byzantine_detection_anomaly_detection_signature[5m]) > 10
        for: 2m
        severity: warning
        annotations:
          summary: "High Byzantine detection rate: {{ $value }} detections/sec"
          runbook: "See Troubleshooting: False Positive Detections"

      - alert: MultipleDetectorQuorum
        expr: increase(byzantine_detection_quorum_reached[10m]) > 2
        for: 1m
        severity: critical
        annotations:
          summary: "{{ $value }} multi-detector quorums in 10 minutes"
          runbook: "See Troubleshooting: Missed Byzantine Detections"

      - alert: FrequentViewChanges
        expr: increase(bls_view_changes_initiated[30m]) > 3
        for: 5m
        severity: warning
        annotations:
          summary: "{{ $value }} view changes in 30 minutes"
          runbook: "Check consensus stability"

      - alert: HighQuarantineActive
        expr: byzantine_detection_quarantine_active > 2
        for: 1m
        severity: critical
        annotations:
          summary: "{{ $value }} members in active quarantine"
          runbook: "Manual investigation of Byzantine members"
```

## Troubleshooting

### Issue: False Positive Detections

**Symptoms**:
- High `byzantine.detection.false.positive.*` counts
- Members quarantined but no actual Byzantine behavior observed
- Normal network latency variations triggering alerts

**Diagnosis**:
```bash
# 1. Check false positive rates
jconsole localhost:9010
# → com.codahale.metrics → byzantine.detection → false.positive.*
# Look for rates > 1% of total detections

# 2. Review detector configuration
cat config/byzantine-detector.yaml | grep -A5 "threshold:"

# 3. Analyze network latency distribution
curl -s http://localhost:9090/metrics | grep "detection_latency"
# Check histogram percentiles: p50, p95, p99

# 4. Review recent detections in logs
grep "Byzantine anomaly detected" /var/log/delos-witness/witness.log | tail -20
```

**Resolution**:
1. **Increase anomaly thresholds** (make detection less sensitive):
   ```yaml
   # Increase anomaly score threshold
   anomaly_score_threshold: 0.7 → 0.8

   # Increase minimum sample size before scoring
   min_sample_size: 50 → 100

   # Increase failure count before exclusion
   failure_threshold: 5 → 10
   ```

2. **Review network characteristics**:
   ```bash
   # Check latency percentiles
   ping -c 100 node-2.witness-cluster | tail -1
   # Expected: rtt min/avg/max/stddev = X/Y/Z/W ms

   # If p99 > 100ms:
   # - Increase timing anomaly threshold percentile (0.95 → 0.98)
   # - Investigate network configuration
   ```

3. **Test threshold adjustments**:
   ```bash
   # Restart with new config and re-run detection tests
   ./mvnw test -Dtest=ByzantineDetectionAccuracyTest -pl witness-service

   # Should see: False positives < 1%, True positives > 99%
   ```

### Issue: Missed Byzantine Detections

**Symptoms**:
- Known malicious behavior but no detection events
- `byzantine.detection.anomaly.detection.*` not incrementing
- Quorum never reached despite Byzantine activity

**Diagnosis**:
```bash
# 1. Verify detectors are active
jconsole localhost:9010 → com.codahale.metrics
# Check if detector meters are being updated

# 2. Check detector configuration is loaded
grep -i "detector" /var/log/delos-witness/witness.log | head -20

# 3. Verify metrics are being recorded
# Trigger a test Byzantine event and check metrics
./mvnw test -Dtest=ByzantineDetectionBasicTest -pl witness-service
tail -f /var/log/delos-witness/witness.log | grep "detected"

# 4. Check coordinator anomaly scores
jconsole → byzantine.detection.anomaly.score.*
# Scores should increase when Byzantine activity occurs
```

**Resolution**:
1. **Lower anomaly thresholds** (make detection more sensitive):
   ```yaml
   anomaly_score_threshold: 0.7 → 0.5
   failure_threshold: 5 → 3
   ```

2. **Verify detector initialization**:
   ```bash
   # Check logs for "Detector registered" messages
   grep "Detector registered" /var/log/delos-witness/witness.log

   # Should see all three:
   # - Detector registered: SIGNATURE_ANOMALY
   # - Detector registered: TIMING_ANOMALY
   # - Detector registered: RATE_ANOMALY
   ```

3. **Manually trigger Byzantine event in test**:
   ```bash
   ./mvnw test -Dtest=ByzantineDetectionIntegrationTest#testDetectsInvalidSignature -pl witness-service

   # Should log detection within 100ms
   # Check logs for: "Byzantine anomaly detected"
   ```

### Issue: Graceful Degradation Not Activating

**Symptoms**:
- Member failures observed but no threshold adjustment
- `bls.degradation.buffers.created` = 0 during known failures
- View change not triggering signature buffering

**Diagnosis**:
```bash
# 1. Verify view change listener has metrics wired
grep "BLSMetrics" /var/log/delos-witness/witness.log

# 2. Check if view changes are occurring
jconsole → bls.view.changes.initiated
# Should increment when Fireflies view changes

# 3. Check signature buffer creation
jconsole → bls.degradation.buffers.created
# Should > 0 during view change period

# 4. Verify threshold recalculation
jconsole → bls.degradation.threshold.delta
# Should show non-zero values during Byzantine exclusion
```

**Resolution**:
1. **Verify view change listener is initialized with metrics**:
   ```bash
   grep "WitnessCHOAMViewChangeListener" src/main/java/com/hellblazer/delos/witness/WitnessBootstrap.java
   # Should show: listener = new WitnessCHOAMViewChangeListener(..., blsMetrics)
   ```

2. **Check CHOAM integration**:
   ```bash
   # Restart witness service
   pkill -SIGTERM witness-service
   sleep 5
   ./start-witness.sh

   # Verify bootstrap initializes metrics:
   grep "Witness metrics initialized" /var/log/delos-witness/witness.log
   ```

### Issue: Metrics Not Appearing in Prometheus

**Symptoms**:
- Prometheus scrape succeeds but no metrics data
- JMX shows metrics but HTTP endpoint empty
- "No metrics found" when querying

**Diagnosis**:
```bash
# 1. Verify Prometheus scrape is working
curl -v http://localhost:9090/metrics 2>&1 | head -20

# 2. Check if metrics are being created
jconsole localhost:9010 → com.codahale.metrics
# Should show 50+ metrics

# 3. Verify HTTP endpoint is configured
grep "prometheus" config/witness-service.yaml

# 4. Check service logs for errors
grep -i "prometheus\|http" /var/log/delos-witness/witness.log | tail -20
```

**Resolution**:
1. **Enable JMX to Prometheus gateway** (if using):
   ```bash
   # Start JMX exporter
   java -javaagent:jmx_exporter.jar=9090:/etc/prometheus/jmx-config.yaml \
        -jar witness-service/target/witness-service.jar
   ```

2. **Configure Prometheus scrape config**:
   ```yaml
   # prometheus.yml
   scrape_configs:
     - job_name: 'delos-witness'
       static_configs:
         - targets: ['witness1:9090', 'witness2:9090', ...]
       scrape_interval: 15s
       scrape_timeout: 10s
   ```

3. **Verify metrics HTTP endpoint**:
   ```bash
   curl http://localhost:9090/metrics | grep byzantine_detection | head -5
   # Should return metric lines, not empty
   ```

## Performance Validation

### Expected Performance (from PHASE_1C_PERFORMANCE_BASELINES.md)

| Metric | Target | Unit |
|--------|--------|------|
| Byzantine detection overhead | <1% | % throughput loss |
| Invalid signature detection | <1 | ms (p99) |
| Rate anomaly detection | 50-500 | ms (p50-p99) |
| Byzantine exclusion | <10 | ms (p99) |
| Memory per committee | <50 | KB |
| View change latency | <100 | ms (p99) |

### Performance Monitoring Commands

```bash
# 1. Run performance validation tests
./mvnw test -pl witness-service -Dtest=ByzantineDetectionPerformanceTest
# Expected output:
# - Overhead: <1%
# - Latencies: all within targets

# 2. Monitor current throughput
jconsole → bls.signatures.received
# Compare against baseline established in Phase 1

# 3. Measure detection latency
# From Prometheus:
histogram_quantile(0.99,
  rate(byzantine_detection_detection_latency_signature_bucket[1m]))
# Expected: <1ms for signature validation

# 4. Check memory usage
jconsole → Memory tab
# Heap usage should remain stable (<2GB for 100-member committee)

# 5. Analyze Byzantine exclusion latency
histogram_quantile(0.99,
  rate(byzantine_detection_escalation_latency_bucket[1m]))
# Expected: <10ms
```

### Optimization Opportunities

If performance is below targets:

1. **Detection latency high (>1ms)**:
   - Check network latency (may indicate slow I/O)
   - Review detector configuration (min_sample_size too high?)
   - Profile Byzantine detector implementation

2. **Memory usage high (>100KB per member)**:
   - Reduce history window size (historyWindowSize parameter)
   - Decrease reservoir size for anomaly score histograms
   - Review detector memory allocations

3. **Throughput degradation (>1%)**:
   - Disable non-critical detectors temporarily
   - Increase detector threshold (reduce false positives)
   - Review lock contention in coordinator

## Recovery Procedures

### Recovering from Mass Byzantine Event

**Scenario**: Multiple witness nodes detected as Byzantine within minutes.

**Immediate Response** (0-1 minute):
```bash
# 1. Verify legitimate nodes still form quorum
# Need 5 valid nodes for f=2 tolerance (3f+1 = 7 minimum)
jconsole → byzantine.detection.members.excluded
# If > 2: Risk of lost quorum

# 2. Assess impact on consensus
curl -s http://localhost:9090/metrics | grep "byzantine_detection_consensus_impact"
# If consensus stalled: Manual intervention needed
```

**Mitigation** (1-5 minutes):
```bash
# 1. Check if view change is triggered automatically
jconsole → bls.view.changes.initiated
# Should increase if enough Byzantine members detected

# 2. Monitor quarantine duration
jconsole → byzantine.detection.quarantine.duration
# Members should be excluded for duration before recovery attempts

# 3. If consensus stalled, manually trigger view change
# (requires gRPC interface or manual operator action)
./delos-cli view-change --committee witness-cluster --force
```

**Validation** (5-10 minutes):
```bash
# 1. Verify Byzantine members are excluded
jconsole → byzantine.detection.members.excluded
# Count should stabilize at 2-3

# 2. Confirm new view formed
jconsole → bls.view.active
# Should increment

# 3. Check threshold recalculated
jconsole → bls.degradation.threshold.delta
# Should show non-zero adjustment

# 4. Verify signatures resuming
curl -s http://localhost:9090/metrics | grep "bls_signatures_received" | tail -1
# Rate should return to normal
```

### Recovering from False Positive Storm

**Scenario**: High detection rate (>50/minute) with all members being quarantined.

**Emergency Stop** (immediate):
```bash
# 1. Disable detector causing false positives
# Edit config and restart, OR use feature flag:
grep "detector.enabled" config/byzantine-detector.yaml
# Set to false for problematic detector (e.g., TIMING_ANOMALY)

# 2. Restart service with updated config
pkill -SIGTERM witness-service
sleep 5
./start-witness.sh

# 3. Monitor for quarantine clearing
jconsole → byzantine.detection.members.excluded
# Should drop back to 0-1 over 5 minutes
```

**Clear Quarantine State** (2-5 minutes):
```bash
# Option A: Wait for automatic quarantine timeout (configured duration)
# Check current duration:
jconsole → byzantine.detection.quarantine.duration
# Typical: 5-10 minutes

# Option B: Restart witness processes to reset state
pkill -SIGTERM witness-service
sleep 10
./start-witness.sh
```

**Tune Thresholds** (5-10 minutes):
```bash
# Edit config to reduce false positives:
# 1. Increase anomaly score threshold: 0.7 → 0.8
# 2. Increase minimum sample size: 50 → 100
# 3. Increase failure count threshold: 5 → 10

# Restart with updated config:
./start-witness.sh

# Re-run detection accuracy tests:
./mvnw test -Dtest=ByzantineDetectionAccuracyTest -pl witness-service
```

## Metrics Export Configuration

### JMX (Local Monitoring)

Metrics automatically exposed via JMX when WitnessMetricsBootstrap is initialized.

**Access via jconsole**:
```bash
jconsole localhost:9010
# Navigate to: com.codahale.metrics → witness-service → <metric-name>
```

**Programmatic Access**:
```java
// In your monitoring code:
JMXConnector connector = JMXConnectorFactory.connect(url);
MBeanServerConnection conn = connector.getMBeanServerConnection();

// Query metrics
ObjectName metricsName = new ObjectName("com.codahale.metrics:type=*");
Set<ObjectName> names = conn.queryNames(metricsName, null);
```

### Prometheus (Production Monitoring)

**Configuration** (add to witness-service startup):
```bash
java -javaagent:jmx_exporter.jar=9090:/etc/prometheus/jmx-config.yaml \
     -Dcom.sun.management.jmxremote.port=9010 \
     -Dcom.sun.management.jmxremote.authenticate=false \
     -Dcom.sun.management.jmxremote.ssl=false \
     -jar witness-service/target/witness-service.jar
```

**Scrape Configuration**:
```yaml
# /etc/prometheus/prometheus.yml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'delos-witness-phase1c'
    static_configs:
      - targets: ['witness1:9090', 'witness2:9090', 'witness3:9090']
    scrape_interval: 15s
    scrape_timeout: 10s
    relabel_configs:
      - source_labels: [__address__]
        target_label: instance
```

**Grafana Dashboard** (example queries):
```json
{
  "panels": [
    {
      "title": "Byzantine Detection Rate",
      "targets": [
        {
          "expr": "rate(byzantine_detection_anomaly_detection_signature[5m])"
        }
      ]
    },
    {
      "title": "Active Quarantines",
      "targets": [
        {
          "expr": "byzantine_detection_quarantine_active"
        }
      ]
    },
    {
      "title": "Detection Latency (p99)",
      "targets": [
        {
          "expr": "histogram_quantile(0.99, rate(byzantine_detection_detection_latency_signature_bucket[5m]))"
        }
      ]
    },
    {
      "title": "View Changes",
      "targets": [
        {
          "expr": "increase(bls_view_changes_initiated[1h])"
        }
      ]
    }
  ]
}
```

## References

- **Phase 1C Architecture**: See design documents in `.pm/designs/phase1c/`
- **Phase 1C Performance Baselines**: See `PHASE_1C_PERFORMANCE_BASELINES.md`
- **Byzantine Detection Tests**: `witness-service/src/test/java/com/hellblazer/delos/witness/detection/`
- **Metrics Implementation**: `witness-service/src/main/java/com/hellblazer/delos/witness/*Metrics*.java`
- **Configuration Examples**: `witness-service/config/byzantine-detector-example.yaml`
- **Related Epics**: Delos-3909 (Phase 1C Epic), Delos-3925 (BLS Performance & Hardening)

## Support & Escalation

| Issue | Owner | Channel |
|-------|-------|---------|
| Byzantine detection accuracy | Platform team | #delos-platform-bugs |
| Performance degradation | Performance team | #delos-perf |
| Metrics not appearing | DevOps | #delos-devops |
| Graceful degradation failure | Consensus team | #delos-consensus |
| False positive storms | Tuning task | Reference Troubleshooting |

---

**Last Updated**: Phase 1C (January 2026)
**Document Version**: 1.0
**Related Beads**: Delos-3972, Delos-3969, Delos-3970, Delos-3971
