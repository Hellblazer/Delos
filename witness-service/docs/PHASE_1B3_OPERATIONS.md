# Phase 1B-3 Operations Runbook

Genesis Migration: Ed25519 → BLS Signature Transition for KERI Witness Network

## Overview

Phase 1B-3 implements a staged genesis migration for the KERI witness network, transitioning signature verification from Ed25519 to BLS (Boneh-Lynn-Shacham) aggregation with Byzantine fault tolerance. This runbook covers operational procedures, deployment, monitoring, and recovery.

## Architecture

**Key Components**:
- **BLS Cryptography Layer** (C-1): Ed25519→BLS signature verification with Byzantine detection
- **Fireflies Integration** (C-2): Async gossip callbacks, member shunning on Byzantine detection
- **CHOAM Transition Logging** (C-3): Persistent transition state recording
- **Metrics & Observability** (C-4): Dropwizard metrics for non-blocking monitoring
- **Health Aggregation** (C-5): Real-time health status from all components

**Non-blocking Design**: CompletableFuture patterns, virtual threads, zero synchronized blocks

## Pre-Deployment Checklist

### Infrastructure Requirements

```
□ Minimum 4 witness nodes (f=1 Byzantine tolerance)
□ Network connectivity: <100ms latency between nodes
□ System clocks: Synchronized within ±500ms (NTP)
□ Disk space: ≥500MB per node for transition logs
□ Memory: ≥2GB heap per witness process
□ CPU: ≥2 cores per node (async throughput)
```

### Software Requirements

```
□ Java 25+ configured in GRAALVM_HOME or system PATH
□ H2 Database 2.x+ with deterministic SQL module
□ gRPC 1.68.0+ for proto services
□ All witness-service dependencies resolved via Maven
```

### Database Preparation

```bash
# Verify CHOAM state machine initialized
./mvnw test -pl sql-state -Dtest="*CHOAM*" -q

# Verify liquibase migration applied
./mvnw liquibase:update -pl sql-state

# Verify schema: 10 CHOAM tables present
SELECT COUNT(*) FROM information_schema.tables
WHERE table_schema = 'PUBLIC' AND table_name LIKE 'CHOAM_%';
```

### Network Verification

```bash
# Test connectivity between witness nodes
for node in node1 node2 node3 node4; do
  ping -c 1 $node
  nc -zv $node 9090  # Default gRPC port
done

# Verify time sync (NTP)
ntpstat
chronyc tracking
```

## Deployment Procedure

### Phase 1: Pre-Genesis Initialization

**1. Shutdown all witness processes**

```bash
systemctl stop delos-witness || kill $(pgrep -f WitnessBootstrap)
```

**2. Initialize BLS key store on each node**

```bash
# Generate BLS keys for genesis
java -cp witness-service/target/classes:... \
  com.hellblazer.delos.witness.committee.CommitteeBLSKeyStore \
  --mode=init \
  --member-id=$MEMBER_ID \
  --keystore=/var/lib/delos/bls.keystore
```

**3. Validate key registration**

```bash
# Verify all nodes have registered ≥3 BLS keys
curl -s http://localhost:8080/health | jq '.bls_keys_registered'
# Expected: ≥3 (from M-of-N threshold config)
```

### Phase 2: Genesis Transition

**1. Start witness processes with Phase 1B-3 configuration**

```bash
export DELOS_PHASE=GENESIS
export DELOS_BLS_THRESHOLD=7  # 2f+1 for f=3 Byzantine nodes
export DELOS_MIGRATION_MODE=BLS_ONLY

java -Xmx2G -cp witness-service/target/... \
  com.hellblazer.delos.witness.WitnessBootstrap \
  --config=/etc/delos/witness.yaml
```

**2. Monitor transition readiness**

```bash
# Poll readiness status every 10 seconds
watch -n 10 'curl -s http://localhost:8080/transition/readiness | jq .'

# Expected output:
# {
#   "phase": "GENESIS",
#   "status": "WAITING_FOR_READINESS",
#   "registered_keys": 7,
#   "threshold": 7,
#   "ready": true
# }
```

**3. Initiate genesis transition**

```bash
# Admin API: Trigger phase transition
curl -X POST http://localhost:8080/admin/transition/execute \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"target_phase":"BLS_ONLY"}'

# Monitor transition logs
tail -f /var/log/delos/witness.log | grep "GenesisTransitionCoordinator"
```

**4. Verify transition completion**

```bash
# Wait for health check to report BLS_ONLY phase
curl -s http://localhost:8080/health | jq '.migration_phase'
# Expected: "BLS_ONLY"

# Verify no errors in Byzantine detector
curl -s http://localhost:8080/metrics | jq '.byzantine_failures'
# Expected: 0
```

## Operational Procedures

### Health Monitoring

**Real-time health aggregation (non-blocking)**:

```bash
# Query health from all components
curl -s http://localhost:8080/health/phase1b3 | jq .

# Expected response:
{
  "current_phase": "BLS_ONLY",
  "transition_status": "COMPLETED",
  "registered_keys": 7,
  "total_members": 10,
  "shunned_count": 0,
  "byzantine_failures": 0,
  "transition_in_progress": false,
  "health_check_latency_ms": 2.5
}
```

**Performance metrics**:

```bash
# BLS validation throughput
curl -s http://localhost:8080/metrics | jq '.bls_validation_timer'

# Receipt aggregation latency
curl -s http://localhost:8080/metrics | jq '.receipt_aggregation_timer'

# Shunning operation latency
curl -s http://localhost:8080/metrics | jq '.shunning_latency_histogram'
# Baseline: p99 <100ms (includes GC variance)
```

### Byzantine Member Detection

**Automatic detection and shunning**:

```bash
# Monitor Byzantine detector status
curl -s http://localhost:8080/metrics | jq '.byzantine_detector_state'

# Typical causes detected:
# - BLS signature forgery: Invalid signature on valid digest
# - Equivocation: Conflicting signatures in same view
# - Failure threshold: >3 consecutive validation failures

# When detected:
# 1. Member is shunned (fireflies gossip notification)
# 2. Event logged with member ID and failure type
# 3. Health aggregator updated immediately
```

**Log example**:

```
[ERROR] com.hellblazer.delos.witness.validation.ByzantineWitnessDetector
        -- BLS failure threshold reached for member=BD1a2b3c, triggering shunning
[INFO]  com.hellblazer.delos.witness.committee.GenesisTransitionCoordinator
        -- Member BD1a2b3c shunned from consensus group
```

### Metrics Collection

**Dropwizard metrics (non-blocking)**:

```bash
# Export metrics in Prometheus format
curl -s http://localhost:8080/metrics/prometheus

# Key metrics for Phase 1B-3:
# - delos_bls_keys_registered: Current BLS key count
# - delos_bls_keys_coverage: Percentage of members registered
# - delos_byzantine_failures_total: Cumulative Byzantine detections
# - delos_receipt_aggregation_time: Aggregation latency (histogram)
# - delos_shunning_latency: Async callback completion time
# - delos_transition_status: Current phase enumeration
```

## Troubleshooting

### Problem: Transition Stuck in WAITING_FOR_READINESS

**Symptom**: Readiness status remains WAITING_FOR_READINESS after >5 minutes

```bash
# Debug: Check key registration
curl -s http://localhost:8080/transition/readiness | jq '.registered_keys'

# If < threshold:
# 1. Verify all 4+ nodes have started
# 2. Check network connectivity: nc -zv each node
# 3. Review key generation logs: grep "CommitteeBLSKeyStore" /var/log/delos/witness.log
```

**Recovery**:

```bash
# Restart witness process with clean BLS store
systemctl stop delos-witness
rm /var/lib/delos/bls.keystore
systemctl start delos-witness
# Allow 30 seconds for key generation and registration
```

### Problem: High Byzantine Failure Rate

**Symptom**: Byzantine detector triggering frequently, members being shunned

```bash
# Monitor failure rate
watch -n 5 'curl -s http://localhost:8080/metrics | jq .byzantine_failures_total'

# Check for:
# 1. Clock skew: timedatectl, chronyc tracking
# 2. Network latency: ping all nodes, check for >100ms delay
# 3. GC pauses: Enable GC logging
#    -XX:+PrintGCDetails -Xloggc:/var/log/delos/gc.log
```

**Recovery**:

```bash
# Increase Byzantine failure threshold if nodes genuinely Byzantine
curl -X POST http://localhost:8080/admin/byzantine/adjust-threshold \
  -d '{"new_threshold": 4}'  # f=1 → f=2

# OR resolve underlying cause (clock sync, network) and restart nodes
```

### Problem: Memory Leaks or High Heap Usage

**Symptom**: Heap usage grows >1GB over 1 hour

```bash
# Collect heap dump
jmap -dump:live,format=b,file=/tmp/heap.bin $PID

# Analyze with Eclipse MAT or similar:
# Expected pattern: Phase transition state cleaned up
# Look for: Transition records accumulating in CHOAMTransitionRecorder
```

**Recovery**:

```bash
# Force full GC and check for recovery
jcmd $PID GC.run

# If memory remains high, check logs for:
# - Unclosed gRPC streams
# - CompletableFuture leak in phase transition handler
# - CHOAM log accumulation

# Last resort: Graceful restart
systemctl restart delos-witness
```

## Rollback Procedures

### Rollback: BLS_ONLY → GENESIS

**If Byzantine detection rate > 20% or consensus fails**:

```bash
# 1. Admin API: Initiate rollback
curl -X POST http://localhost:8080/admin/transition/rollback \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"target_phase":"GENESIS"}'

# 2. Monitor rollback progress
curl -s http://localhost:8080/transition/readiness | jq '.phase'
# Should transition back to GENESIS within 30 seconds

# 3. Verify consensus recovery
curl -s http://localhost:8080/health | jq '.consensus_active'
# Expected: true
```

**Post-rollback actions**:

```bash
# Investigate root cause in logs
grep "GenesisTransitionCoordinator" /var/log/delos/witness.log | tail -50

# Wait 5 minutes before retrying transition
sleep 300

# Only retry if root cause addressed (clock sync, network tuning, etc.)
```

## Performance Baselines

**Healthy Phase 1B-3 Operations**:

| Metric | Baseline | Notes |
|--------|----------|-------|
| BLS validation latency | <1ms per sig | Production measurement |
| Receipt aggregation | <100ms M-of-N | Includes GC variance |
| Shunning async notify | <100ms P99 | Fireflies gossip + memory updates |
| Health check latency | <10ms P99 | Non-blocking aggregation |
| Memory per member | <1KB state | ByzantineWitnessDetector tracking |
| Transition readiness | <5min | Sequential key registration |

**Test Coverage**:

```
D-1 Byzantine scenarios: 25 tests
D-2 Performance stress: 16 tests (adjusted baselines)
D-3 Edge cases & rollback: 16 tests
Total: 57 new Phase 1B-3 tests
Regression: 673 tests passing (2 skipped)
```

## Support and Escalation

**Warnings to watch for**:

```
WARN: BLS key registration lagging → Increase key generation parallelism
WARN: Fireflies gossip backlog → Check network bandwidth/latency
WARN: CHOAM log growth > 100MB → Truncate old transition records
WARN: Byzantine detection threshold exceeded → Investigate member clocks/network
```

**Escalation path**:

1. Check `/var/log/delos/witness.log` for ERROR or WARN logs
2. Query `/health/phase1b3` for component status
3. Collect metrics snapshot: `curl -s http://localhost:8080/metrics > /tmp/metrics.json`
4. Review operational runbook troubleshooting section
5. If unresolved: Graceful restart with clean BLS store

## References

- **Phase 1B-3 Specification**: `witness-service/docs/PHASE_1B3_SPECIFICATION.md`
- **BLS Cryptography**: `cryptography/docs/BLS_IMPLEMENTATION.md`
- **CHOAM Consensus**: `choam/docs/CHOAM_REPLICATION.md`
- **Byzantine Detection**: `witness-service/docs/BYZANTINE_DETECTION.md`
- **Fireflies Membership**: `fireflies/docs/FIREFLIES_OVERLAY.md`

---

**Last Updated**: 2026-01-19
**Version**: 1.0
**Status**: Production Ready
