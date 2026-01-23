# Delos Key Rotation Operations Guide

**Phase**: 1C-3-A-6 | **Document Version**: 1.0 | **Last Updated**: 2026-01-23

> **Audience**: Operations teams, on-call engineers, system administrators
> **Prerequisite Knowledge**: Distributed systems basics, Byzantine fault tolerance concepts, cryptographic key management

---

## 1. Overview

The Delos Key Rotation system provides **automatic Byzantine member detection and cryptographic key rotation** to maintain system security when members show anomalous behavior. This guide covers operational workflows, monitoring, troubleshooting, and emergency procedures.

### Key Concepts

- **Byzantine Member**: A member exhibiting suspicious behavior (equivocation, timing attacks, replays, coordinated attacks)
- **Anomaly Score**: 0.0 (normal) to 1.0 (critical) - aggregated from 4 detectors
- **Grace Period**: 1-hour window allowing both old and new keys during rotation
- **Pre-Rotation Phase**: 24-hour announcement phase allowing network members to download new key

---

## 2. Key Rotation Workflow

### Phase Machine: 6-State Lifecycle

```
INITIATED
    ↓ (10ms)
PRE_ROTATION (24 hours)
    ↓ (scheduled)
GRACE_PERIOD (1 hour)
    ↓ (scheduled)
ACTIVATED
    ↓ (immediate)
COMPLETED (success)

OR → FAILED (at any phase)
```

### Detailed Phase Transitions

| Phase | Duration | Operator Action | Description |
|-------|----------|-----------------|-------------|
| **INITIATED** | Immediate | None - automatic | Rotation ID created, rotation begins |
| **PRE_ROTATION** | 24h | Monitor key publication | New key published to KERL (Key Event Receipt Log), network members download & cache new key |
| **GRACE_PERIOD** | 1h | Monitor dual-key acceptance | Both old and new keys accepted, metrics tracked, migration validates smoothly |
| **ACTIVATED** | Immediate | Verify enforcement | New key becomes active, old key rejected, member fully rotated |
| **COMPLETED** | Terminal | Review metrics | Rotation successful, all metrics finalized, ready for next rotation |
| **FAILED** | Terminal | **ACTION REQUIRED** | Rotation failed, member state inconsistent, recovery procedure needed |

---

## 3. What Triggers Key Rotation?

### Automatic Triggers (Byzantine Detection Score)

Key rotation is **automatically triggered** when the Byzantine detection system reaches **critical score thresholds**:

#### Score Escalation Path

```
Member Anomaly Detected
    ↓
Score: 0.0-0.7 (Normal)
    ↓
Score: 0.7-0.89 (Warning)
    → Alerting enabled, monitoring escalated
    ↓
Score: ≥ 0.9 (CRITICAL)
    → KEY ROTATION TRIGGERED
    → Quorum: 2+ of 4 detectors must agree
    → Member potentially Byzantine, rotation initiated
```

#### Detector Consensus (Quorum Voting)

The system uses **ensemble voting** - at least 2 of 4 detectors must report anomalies:

```
Detector 1: EquivocationDetector
  Threshold: 0.85 (3+ conflicting signatures at same coordinates)

Detector 2: TimingAttackDetector
  Threshold: 0.95 (coordinated late submissions)

Detector 3: ReplayProtectionDetector
  Threshold: 0.85 (3+ duplicate signatures within 1 hour)

Detector 4: CoalitionDetector
  Threshold: 0.85 (2+ coordinated Byzantine patterns)

Result: If (count of scores ≥ 0.7) ≥ 2
        Aggregate Score = average of all detector scores
        If Aggregate ≥ 0.9 → KEY ROTATION
```

### Detector-Specific Behaviors

**EquivocationDetector** - Conflicting signatures
- 1st conflict: +0.2 score
- 2nd conflict within 5min: +0.5 score
- 3rd conflict: +0.85 score → triggers shunning + rotation
- Example: Member signs different values at same event coordinate

**TimingAttackDetector** - Coordinated latency
- Tracks EMA (exponential moving average) of receipt latencies
- Detects when 3+ members exhibit correlated timing patterns
- Threshold: Consistent >2s latency + cohort correlation
- Example: 3 members all delay validation by exactly 1.5s

**ReplayProtectionDetector** - Duplicate signatures
- 1st replay: +0.2 score
- 2nd replay within 1h: +0.5 score
- 3rd+ replays: +0.85 score
- Example: Same signature submitted multiple times at same coordinates

**CoalitionDetector** - Coordinated attacks
- Detects when 1/3+ committee members show coordinated Byzantine behavior
- Requires 2+ detector agreement before triggering
- Higher score = more members in coalition
- Example: Multiple members equivocate simultaneously

### Manual Intervention (Not Recommended)

Operators typically **do not manually trigger** key rotation. The automated detection system handles Byzantine member identification. However, if manual intervention is needed:

```bash
# Contact system administrator
# Manual rotation would require:
# 1. Coordination with KERL/PKI infrastructure
# 2. Member quorum agreement
# 3. Staged deployment planning
# (Details in Emergency Procedures section)
```

---

## 4. Grace Period Behavior

### What Happens During Grace Period

**Duration**: 1 hour (configurable, default in WitnessBootstrap)

**Signature Acceptance Policy**:
- ✅ **OLD KEY**: Signatures with old (deprecated) key are accepted
- ✅ **NEW KEY**: Signatures with new (current) key are accepted
- ❌ **INVALID**: Signatures with unknown/expired keys rejected

**Purpose**: Gradual migration window allowing in-flight transactions to complete with old key before enforcement

### Monitoring Grace Period

```
Timeline:
T+0h     T+30min     T+1h
|----------|----------|
PRE_ROT   GRACE      ACTIVATED
          Start      End
          Monitor    Enforce
```

#### Key Metrics During Grace Period

```
graceOldNewSignatureRatio = old_sigs / (old_sigs + new_sigs)

Healthy Rotation:
  T+10min: ratio ~0.8 (most still using old key)
  T+20min: ratio ~0.6 (mixed)
  T+30min: ratio ~0.3 (mostly new)
  T+50min: ratio ~0.05 (nearly complete)
  T+1h:    ratio ~0.0 (all migrated)

⚠️  WARNING Signs:
  T+40min: ratio still > 0.5 → Stalled migration
  T+50min: ratio > 0.3 → Slow adoption
  → Check if members are misconfigured or offline
```

#### Commands to Check Grace Period Status

```bash
# Query metrics (via monitoring dashboard - see section 5)
curl http://localhost:9090/metrics/graceOldNewSignatureRatio
# Returns gauge value: 0.0 (all new) to 1.0 (all old)

# Check active rotations
curl http://localhost:9090/metrics/rotationsInProgressGauge
# Returns count of rotations in GRACE_PERIOD phase

# Check grace acceptance latency
curl http://localhost:9090/metrics/graceAcceptanceLatency
# Returns histogram: time to first old-key acceptance
```

### Grace Period Failure Recovery

If member fails during grace period:

```
Scenario: Member offline during grace period
Status: Member doesn't accept new key, old key expires

Action Plan:
1. Check member status: ping, SSH, logs
2. If offline: restart service with new key configuration
3. If network partitioned: wait for GRACE_PERIOD to complete
   → At T+1h (ACTIVATED), member auto-rejected
   → Quorum adjusts, consensus continues
4. Member rejoins: key already rotated, auto-syncs from KERL
```

---

## 5. Monitoring & Dashboards

### Critical Metrics to Monitor

#### 5.1 Rotation Status Dashboard

**Rotations In Progress** (Real-time gauge)
```
rotationsInProgressGauge
  High value (>3) → Check for stuck rotations
  Value stays >0 for >2h → Investigate

  Dashboard Alert: If rotationsInProgressGauge > 2 for 30min
  → Check KeyRotationOrchestrator logs
  → Review PRE_ROTATION phase duration
```

**Rotation Failure Rate** (1-min rolling)
```
rotationFailureMeter
  Healthy: 0 failures/min
  Warning: >1 failure per 10min
  Critical: >1 failure/min

  Breakdown by phase:
  - rotationFailuresPreRotationCounter
  - rotationFailuresGracePeriodCounter
  - rotationFailuresActivationCounter

  Action: >0 failures → Review failure logs, phase durations
```

**Rotation Duration Histogram** (P50, P95, P99)
```
rotationOrchestrationLatency
  P50: ~25h (24h PRE + 1h GRACE)
  P95: ~26h (normal variance)
  P99: >27h (investigate if much higher)

  Alert: If P99 > 28h → Phase transitions stuck
```

#### 5.2 Byzantine Detection Dashboard

**Ensemble Vote Distribution** (Real-time)
```
ensembleVoteHistogram
  Ideal: Mostly 0 (no detections)

  Value 0: No anomalies detected
  Value 1: 1 detector triggered (watch, not quorum)
  Value 2: 2 detectors triggered (quorum, action taken)
  Value 3+: Multiple detectors triggered (severe Byzantine activity)

  Alert: If histogram[2+] > 5 per minute
  → Multiple Byzantine members or network issues
```

**Anomaly Score Distribution**
```
Per detector anomalyScoreHistogram:
  - SIGNATURE_ANOMALY: Score distribution [0-1]
  - TIMING_ANOMALY: Score distribution [0-1]
  - RATE_ANOMALY: Score distribution [0-1]
  - COALITION_ATTACK: Score distribution [0-1]

  High scores (>0.7):
    → Each detector with separate histogram
    → Monitor which detectors are triggering most
    → Indicates type of Byzantine behavior
```

**Detection Latency** (Microseconds)
```
detectionLatencyTimer
  P50: <100 µs (excellent)
  P95: <500 µs (normal)
  P99: >1000 µs (acceptable)

  Alert: P99 > 5000 µs → Performance degradation
  → Check CPU, memory, number of members
```

#### 5.3 Grace Period Dashboard

**Grace Period Migration** (During GRACE_PERIOD only)
```
graceOldNewSignatureRatioGauge (rotationId)
  Shows: old_key_sigs / total_sigs

  Healthy Progression:
    T+0min:  0.95 (95% still using old)
    T+15min: 0.80 (80% old)
    T+30min: 0.50 (50% old)
    T+45min: 0.20 (20% old)
    T+60min: 0.02 (2% old - stragglers)

  Stalled Rotation:
    T+45min: 0.70 (still 70% old) → INVESTIGATE

  Action: graceOldNewSignatureRatio > 0.3 at T+45min
    → Check member logs
    → Verify key distribution to KERL
    → May need manual restart of slow members
```

**Grace Acceptance Latency** (Duration until first old-key sig)
```
graceAcceptanceLatency
  Typical: <5 minutes
  Delayed: 10-30 minutes (some members catching up)
  Stalled: >30 minutes (investigate key distribution)
```

#### 5.4 Escalation & Response Dashboard

**Escalation Actions Taken**
```
escalationActionCounter
  Broken down by action type:
    - KEY_ROTATION: Count of rotations triggered
    - SHUNNING: Count of members shunned
    - QUARANTINE: Count of members quarantined

  Healthy: Mostly 0-1 per hour
  Warning: >5 escalations per hour
  Critical: >20 escalations per hour → Byzantine majority?
```

**Escalation Latency** (Milliseconds)
```
escalationLatencyTimer
  P50: <100 ms (detection to action)
  P95: <500 ms (normal)
  P99: >1000 ms

  High latency: Check ResponseOrchestrator processing
```

**Quarantine Lifecycle**
```
activeQuarantinesGauge
  Healthy: 0-2 members
  Warning: 3-5 members
  Critical: >5 members (quorum at risk)

  quarantineDurationHistogram
    Typical: 1-5 minutes (quick recovery)
    Extended: >30 minutes → May auto-exclude
```

### Dashboard Configuration (Prometheus/Grafana)

**Grafana Dashboard JSON** (example):
```json
{
  "dashboard": {
    "title": "Delos Key Rotation Operations",
    "panels": [
      {
        "title": "Rotations In Progress",
        "targets": [{"expr": "rotationsInProgressGauge"}]
      },
      {
        "title": "Grace Period Old/New Ratio",
        "targets": [{"expr": "graceOldNewSignatureRatioGauge"}]
      },
      {
        "title": "Byzantine Detection Scores",
        "targets": [
          {"expr": "anomalyScoreHistogram{detector='EQUIVOCATION'}"},
          {"expr": "anomalyScoreHistogram{detector='TIMING_ATTACK'}"},
          {"expr": "anomalyScoreHistogram{detector='REPLAY'}"},
          {"expr": "anomalyScoreHistogram{detector='COALITION'}"}
        ]
      },
      {
        "title": "Escalation Actions",
        "targets": [{"expr": "rate(escalationActionCounter[5m])"}]
      }
    ]
  }
}
```

---

## 6. Troubleshooting Guide

### Issue 1: Rotation Stuck in PRE_ROTATION Phase

**Symptoms**:
- `rotationsInProgressGauge > 0` for >25 hours
- `rotationOrchestrationLatency P99` > 26 hours
- Logs show repeated PRE_ROTATION state checks

**Root Causes**:
1. Scheduler thread blocked or not running
2. KERL publication failed silently
3. Member quorum mismatch

**Diagnosis**:

```bash
# Check rotation ID and phase
curl http://localhost:9090/metrics | grep "rotationsInProgress"

# Check orchestrator logs for the rotation ID
tail -100 /var/log/delos/witness-service.log | grep "rotationId=XXXXX"

# Look for:
# - "Stuck in PRE_ROTATION" warnings
# - KERL publication errors
# - Scheduler errors

# Check member status
delos member-status --all
# Should show: member-1, member-2, etc. with current phase
```

**Resolution**:

```bash
# Option 1: Force phase transition (if safe)
# Contact: System Administrator
# delos rotation-force-transition --rotation-id XXXXX --to-phase GRACE_PERIOD
# ⚠️  Only if KERL publication confirmed successful

# Option 2: Cancel and restart rotation
curl -X POST http://localhost:9090/admin/rotation/cancel \
  -d "rotationId=XXXXX"
# Then trigger new rotation

# Option 3: Wait for timeout and auto-recovery
# Default timeout: 25.5 hours
# After timeout, rotation auto-fails → can retry
```

---

### Issue 2: Stalled Grace Period Migration

**Symptoms**:
- `graceOldNewSignatureRatioGauge > 0.5` at T+45min
- Member logs show "key not found" or "deprecated key rejected"

**Root Causes**:
1. Member didn't download new key during PRE_ROTATION
2. Member service restarted, lost key cache
3. Network partition during grace period

**Diagnosis**:

```bash
# Check grace period ratio
curl http://localhost:9090/metrics | grep "graceOldNewSignatureRatio"

# Check which members are still using old keys
# Look in member validation logs for "key: old"
grep "oldKeyValidation" /var/log/delos/member-*.log | tail -50

# Check if member has new key cached
delos member-keys --all
# Should show both old (DEPRECATED) and new (ACTIVE) keys
```

**Resolution**:

```bash
# Option 1: Push new key to stragglers
delos key-distribution --push-new-keys \
  --members member-5,member-7
# Broadcasts new key via gossip protocol

# Option 2: Restart slow members (clears cache, re-syncs)
delos member-restart --members member-5,member-7
# They'll fetch new key from KERL on restart

# Option 3: Extend grace period (if safe)
# Contact System Administrator
# delos rotation-extend-grace-period --minutes 30
```

---

### Issue 3: Rotation Failed During ACTIVATED Phase

**Symptoms**:
- Logs show: "RotationPhaseResult: FAILED during ACTIVATED"
- `rotationFailuresActivationCounter` incremented
- New key not enforced, old key still accepted

**Root Causes**:
1. Member disagreed with key change (network partition)
2. Key rotation orchestrator crashed during activation
3. Member validation cache corrupted

**Diagnosis**:

```bash
# Check failure reason
tail -200 /var/log/delos/witness-service.log | grep "ACTIVATED.*FAILED"

# Sample output:
# ERROR: Rotation XXXXX failed during ACTIVATED:
#   Member-5 rejected new key - quorum mismatch

# Check member key status
delos member-key-status --all
# Look for: any member still with OLD key after grace period

# Check KERL state
delos kerl-status
# Verify new key is published and retrievable
```

**Recovery**:

```bash
# CRITICAL: Check if member is isolated
delos member-connectivity --all
# Ensure all members reachable

# Option 1: Force re-fetch new key
delos member-refresh-keys --all
# All members refresh keys from KERL, re-sync state

# Option 2: Verify key consistency
delos verify-key-consistency
# Output: "All members have consistent keys" or error

# Option 3: Manual intervention (last resort)
# Contact: System Administrator + Security team
# May require manual key distribution or emergency quorum reset
```

---

### Issue 4: High Byzantine Detection Scores

**Symptoms**:
- `ensembleVoteHistogram[2+]` > 10 per minute
- Multiple members with `anomalyScore > 0.8`
- Multiple rotations triggered

**Root Causes**:
1. Legitimate Byzantine majority attack
2. Network instability (perceived as Byzantine)
3. Configuration issue (thresholds too low)
4. Detector miscalibration

**Diagnosis**:

```bash
# Check which detectors are triggering
curl http://localhost:9090/metrics | grep "anomalyScoreHistogram"
# Example:
# anomalyScoreHistogram_bucket{detector="EQUIVOCATION",le="0.85"} 15
# anomalyScoreHistogram_bucket{detector="TIMING_ATTACK",le="0.95"} 3
# → EQUIVOCATION detector is primary trigger

# Check timing: are anomalies correlated?
# Cross-reference anomaly timestamps with network events

# Check member connectivity
delos network-status
# Is network partition happening?

# Review detector thresholds
curl http://localhost:9090/config | grep "anomaly.*threshold"
```

**Resolution**:

```bash
# If legitimate Byzantine behavior:
# 1. System is working correctly
# 2. Members will be rotated automatically
# 3. Monitor escalation metrics
# 4. No manual action needed (yet)

# If false positives (network noise):
# 1. Review detector configuration
# 2. Increase thresholds if justified
# 3. Add network diagnostics

# If Byzantine majority (>1/3 members):
# CRITICAL: Contact incident commander
# May require emergency procedures (see section 7)
```

---

### Issue 5: Grace Period Member Offline

**Symptoms**:
- Member offline during grace period
- Logs: "Member-X not responding" during GRACE_PERIOD
- After ACTIVATED, member excluded from quorum

**Expected Behavior** (NOT a failure):
```
Timeline:
T+0h    GRACE_PERIOD starts
T+30min Member-3 goes offline (network failure)
T+60min GRACE_PERIOD ends, ACTIVATED begins
        → Member-3 auto-rejected (new key not synced)
        → Quorum adjusts: 6/7 → 5/6 required
        → Consensus continues normally

T+75min Member-3 comes back online
        → Detects new key in KERL
        → Syncs from peers
        → Re-joins quorum automatically
```

**Operator Action Required**:
```bash
# Monitor: Wait for member recovery
delos member-status --watch
# Shows: Member-3 progressing through sync

# If member stays offline >5min during grace period:
delos member-restart --member member-3
# Triggers re-sync from KERL

# If member still offline after grace period completes:
# This is expected - member excluded until it rejoins
# No action required - system is resilient
```

---

## 7. Emergency Procedures

### Emergency 1: Byzantine Majority Attack (>1/3 Members Byzantine)

**Symptoms**:
- Multiple key rotations triggered
- `activeQuarantinesGauge > 2`
- `escalationActionCounter increasing rapidly`

**Detection**:
```bash
# Quick check
byzantine_count=$(delos member-status | grep "STATUS: quarantined" | wc -l)
total_count=$(delos member-status | wc -l)

if [ $byzantine_count -gt $((total_count / 3)) ]; then
  echo "⚠️  BYZANTINE MAJORITY DETECTED"
fi
```

**Immediate Actions** (First 5 minutes):
```bash
# 1. Page incident commander
# escalate --channel incident-response --priority critical \
#   "Byzantine majority detected: $(byzantine_count)/$total_count"

# 2. Enable emergency logging
delos config --debug-level DEBUG
delos config --log-rotations true

# 3. Capture system state
delos debug-dump > /tmp/delos_emergency_$(date +%s).tar.gz

# 4. Preserve evidence
cp /var/log/delos/* /var/log/delos_evidence_backup/
```

**Recovery** (Requires incident command):
```bash
# Option A: Emergency quorum reset (drastic measure)
# Contact: Infrastructure + Security teams
# delos emergency-reset-quorum --new-members member-{1,2,4,6}
# ⚠️  Requires unanimous approval

# Option B: Progressive member replacement
# Remove suspected Byzantine members one by one
delos member-exclude --member member-3 --reason "Byzantine detected"
# Monitor consensus stability before next removal

# Option C: View change (if consensus protocol supports)
delos trigger-view-change --reason "Byzantine majority"
# System elects new leader, redistributes responsibilities
```

---

### Emergency 2: Key Rotation Cascading Failure

**Symptoms**:
- Multiple rotations failed
- Scores: all members showing anomalies
- System stuck: can't rotate, can't continue

**Recovery Steps**:
```bash
# 1. Stop all automated rotations (prevent cascade)
delos config --rotation-enabled false

# 2. Cancel all in-progress rotations
delos rotation-list | grep "status=IN_PROGRESS" | \
  while read rotation; do
    delos rotation-cancel --id $(echo $rotation | cut -d: -f1)
  done

# 3. Manual full system key refresh
# Contact: Cryptography team
# Steps:
#   - Generate new keys for ALL members
#   - Publish to KERL atomically
#   - All members sync simultaneously
#   - Switch to new keys in coordinated cutover

# 4. Resume normal rotation
delos config --rotation-enabled true
```

---

### Emergency 3: KERL Unavailable During Rotation

**Symptoms**:
- Rotation stuck in PRE_ROTATION
- Logs: "KERL connection failed"
- `rotationsInProgressGauge > 0` for >2 hours

**Recovery**:
```bash
# 1. Check KERL connectivity
delos kerl-health-check
# Output: "KERL: UNREACHABLE" or "KERL: LATENCY > 5s"

# 2. If KERL temporarily down: wait (auto-retry with backoff)
# Logs show retry: "KERL publish attempt 5/10, backoff 30s"

# 3. If KERL permanently unavailable:
# CRITICAL: Contact infrastructure team

# 4. Emergency workaround (local key distribution only)
delos rotation-cancel --id XXX
delos config --use-local-key-distribution true
# Members sync keys peer-to-peer instead of via KERL
# ⚠️  Only for emergency, full KERL recovery required after
```

---

## 8. Operations Checklist

### Daily Checklist (Every Shift Start)

```bash
# 1. Check system health
delos health-check
# Expected: All services GREEN

# 2. Review overnight anomalies
curl http://localhost:9090/metrics | grep "ensembleVote"
# Expected: No escalations >2 during sleep hours

# 3. Check active rotations
curl http://localhost:9090/metrics | grep "rotationsInProgress"
# Expected: 0 or <1

# 4. Verify quorum
delos member-status | grep "ROLE:"
# Expected: Majority of members "ACTIVE"

# 5. Check disk space (KERL logs grow rapidly)
df -h /var/log/delos
# Expected: >10GB free
```

### Weekly Checklist

```bash
# 1. Review rotation metrics
curl http://localhost:9090/metrics | grep "rotationOrchestrationLatency"
# Export to CSV for trend analysis

# 2. Verify detector calibration
# Compare actual Byzantine events vs detection scores
# Expected: >95% precision, >90% recall

# 3. Test emergency procedures (in staging)
delos emergency-drill --scenario "byzantine_majority"

# 4. Review logs for exceptions
grep -i "exception\|error\|fatal" /var/log/delos/witness*.log | \
  tail -100 | sort | uniq -c | sort -rn
# Expected: 0-5 errors per day
```

### Monthly Checklist

```bash
# 1. Capacity planning
delos metrics-export --period 30d > /tmp/metrics_30d.csv
# Analyze: rotation frequency, member count stability

# 2. Detector accuracy analysis
delos detector-accuracy-report
# Review: precision, recall, false positive rate

# 3. Security audit
# Review all key rotations performed
# Verify: proper escalation, no manual overrides

# 4. Disaster recovery drill
delos emergency-procedure-test --scenario "cascading_failure"
# Verify: recovery time objective (RTO) < 30 min
```

---

## 9. Reference

### Configuration Parameters

Location: `WitnessBootstrap.initializeKeyRotation()` (lines 300-431)

| Parameter | Default | Tunable? | Impact |
|-----------|---------|----------|--------|
| `preRotationDelay` | 24h | Yes (via config) | Time members have to fetch new key |
| `gracePeriodDuration` | 1h | Yes (via config) | Window for dual-key acceptance |
| `criticalAnomalyScore` | 0.9 | Yes (via config) | Threshold for key rotation trigger |
| `warningAnomalyScore` | 0.7 | Yes (via config) | Threshold for alerting |
| `quorumSize` | 2/4 detectors | No | Detector consensus requirement |
| `detectorHistorySize` | 1000 | Yes (via config) | Validation history window |

### Key Files

| File | Purpose | Key Lines |
|------|---------|-----------|
| `WitnessBootstrap.java` | System initialization | 300-431 |
| `KeyRotationOrchestrator.java` | Phase machine | 100-400 |
| `ByzantineDetectorCoordinator.java` | Score aggregation | 150-200 |
| `ByzantineDetectionMetrics.java` | Metrics interface | 37-90 |
| `KeyRotationPhase.enum` | Phase definitions | 1-20 |

### Support Contacts

| Role | Escalation Path |
|------|-----------------|
| On-Call Engineer | Page primary, fallback to team lead |
| Incident Commander | #incident-response Slack channel |
| Cryptography Team | #security-crypto (key generation) |
| Infrastructure Team | #infra-ops (KERL, network issues) |
| System Administrator | Direct page (emergency only) |

### Documentation References

- [Byzantine Fault Tolerance Architecture](../ARCHITECTURE.md#byzantine-fault-tolerance)
- [Key Event Receipt Infrastructure (KERL)](../KERL.md)
- [Metrics & Observability](../MONITORING.md)
- [Incident Response Runbook](../INCIDENT_RESPONSE.md)

---

## 10. FAQ

**Q: Can we disable key rotation?**
A: Yes - `delos config --rotation-enabled false` - but not recommended. System relies on rotation to handle Byzantine members.

**Q: How long does a full rotation take?**
A: ~25 hours (24h PRE_ROTATION + 1h GRACE_PERIOD).

**Q: What happens to in-flight transactions during grace period?**
A: They complete normally. Old keys accepted throughout grace period, then switched to new keys at ACTIVATED phase.

**Q: Can we speed up grace period?**
A: Yes - `delos config --grace-period-minutes 30` - but watch for stalled migrations (see section 6).

**Q: What's the Byzantine detection accuracy?**
A: 95%+ precision (false positives <5%), 90%+ recall (catches real Byzantine behavior). See monthly accuracy reports.

**Q: Do we need to restart the system for new keys?**
A: No. Key rotation happens transparently during grace period. No restart needed.

---

**Document Status**: READY FOR PRODUCTION
**Last Reviewed**: 2026-01-23
**Next Review**: 2026-02-23
