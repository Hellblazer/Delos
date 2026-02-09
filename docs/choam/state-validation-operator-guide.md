# CHOAM State Machine Validation - Operator Guide

**Version**: 1.0
**Last Updated**: 2026-02-07
**Applies To**: Delos CHOAM (Phases 1-5)
**Feature Flag**: `feature.state.validation`

---

## Table of Contents

1. [Overview](#overview)
2. [When to Enable](#when-to-enable)
3. [How to Enable](#how-to-enable)
4. [Validation Modes](#validation-modes)
5. [Understanding Violations](#understanding-violations)
6. [Byzantine Indicators](#byzantine-indicators)
7. [Troubleshooting](#troubleshooting)
8. [Metrics Monitoring](#metrics-monitoring)
9. [Performance Impact](#performance-impact)
10. [Emergency Procedures](#emergency-procedures)

---

## Overview

### What is State Machine Validation?

CHOAM's state machine validation provides **defensive infrastructure** for debugging and Byzantine fault detection. It validates:

1. **State Invariants** - Conditions that must hold within each FSM state
2. **Preconditions** - Requirements before a state transition fires
3. **Postconditions** - Expected outcomes after a state transition completes
4. **State Consistency** - Whether distributed state matches FSM state

The validation layer wraps CHOAM's Tron FSM without modifying its internals, providing runtime checks that complement compile-time safety.

### Architecture

```
CHOAM.transitions
    ↓
ValidatingCombineTransitions (validation decorator)
  - Captures state snapshot
  - Validates preconditions
  - Delegates to FSM (unchanged)
  - Captures post-snapshot
  - Validates postconditions
  - Logs/throws based on mode
    ↓
Tron FSM (unchanged)
```

### Key Benefits

- **Early Detection**: Catch state corruption before it propagates
- **Byzantine Detection**: Identify malicious or faulty node behavior
- **Debugging Aid**: Understand state transition failures in production
- **Compliance**: Document adherence to protocol invariants

---

## When to Enable

### Recommended Use Cases

**Enable state validation when:**

1. **Debugging Production Issues**
   - Investigating stalls, hangs, or unexpected state transitions
   - Diagnosing consensus failures or view changes
   - Analyzing Byzantine behavior reports

2. **Post-Deployment Validation**
   - After major refactoring (especially CHOAM internals)
   - After upgrading Tron FSM framework
   - During canary deployments of protocol changes

3. **Byzantine Fault Investigation**
   - Suspecting equivocation or state corruption
   - Analyzing timing anomalies or fork attempts
   - Validating committee threshold enforcement

4. **Development and Testing**
   - Integration testing with validation enabled
   - Stress testing to detect race conditions
   - Byzantine fault injection tests

### When NOT to Enable

**Avoid enabling in:**

- **Production (default)**: Disabled by default for zero overhead
- **High-throughput environments**: May impact latency (see Performance Impact)
- **Stable deployments**: No debugging needed, metrics show healthy operation

**Default**: STATE_VALIDATION is **disabled** (`false`) to avoid performance overhead in production.

---

## How to Enable

### Method 1: System Property (Startup)

Enable at JVM startup:

```bash
java -Dfeature.state.validation=true -jar choam.jar
```

**Pros**: Simple, deterministic
**Cons**: Requires restart to toggle

### Method 2: JMX (Runtime)

Enable/disable at runtime via JMX:

```bash
# Connect to JMX (default port 9090)
jconsole localhost:9090

# Navigate to:
# MBeans → com.hellblazer.delos.choam → FeatureFlagManager → Operations

# Call: setEnabled(FLAG_NAME, boolean)
setEnabled("STATE_VALIDATION", true)
```

**Pros**: No restart required, dynamic control
**Cons**: Requires JMX access, changes not persisted

### Method 3: Programmatic (Code)

Enable in code (testing/development):

```java
import com.hellblazer.delos.choam.FeatureFlags;

// Enable validation
FeatureFlags.STATE_VALIDATION.setEnabled(true);

// Check if enabled
boolean enabled = FeatureFlags.STATE_VALIDATION.isEnabled();
```

**Pros**: Useful for tests, initialization hooks
**Cons**: Code changes required

### Verification

Confirm validation is enabled by checking logs:

```
INFO  [CHOAM] State machine validation ENABLED on: Member[12345...]
```

---

## Validation Modes

State validation supports three modes controlled by configuration:

### 1. LOG_ONLY (Default)

**Behavior**: Log violations but continue execution

**Use Case**: Production debugging, canary deployments

**Configuration**:
```java
// Default mode when STATE_VALIDATION=true
// No additional configuration needed
```

**Log Example**:
```
WARN  [ValidatingCombineTransitions] PRECONDITION_VIOLATION in INITIAL→start:
      Violation: already started
      State: INITIAL, Snapshot: started=true, hasCommittee=false
```

**Pros**:
- Non-invasive (system continues)
- Captures violations for analysis
- Safe for production use

**Cons**:
- Does not prevent invalid transitions
- May accumulate violations before detection

### 2. ENFORCE

**Behavior**: Throw `IllegalStateException` on violations

**Use Case**: Integration tests, strict validation environments

**Configuration**:
```java
// Enforcement requires code changes or test configuration
// Not exposed via feature flags (intentional safety measure)
```

**Exception Example**:
```
IllegalStateException: PRECONDITION_VIOLATION: already started
  at ValidatingCombineTransitions.start(...)
  at CHOAM.start(...)
```

**Pros**:
- Fail-fast detection
- Prevents state corruption propagation
- Clear test failure signals

**Cons**:
- **Not safe for production** (can halt service)
- May cause cascading failures

### 3. METRICS_ONLY

**Behavior**: Record metrics, no logging

**Use Case**: Performance testing, production monitoring with minimal overhead

**Configuration**:
```java
// Metrics-only mode requires code changes
// Future: may be exposed via feature flag
```

**Pros**:
- Lowest overhead (no log I/O)
- Suitable for continuous monitoring
- Metrics-driven alerting

**Cons**:
- No log context for violations
- Harder to debug specific incidents

---

## Understanding Violations

### Violation Types

#### 1. State Invariant Violation

**What**: State does not satisfy its invariant requirements

**Example**:
```
OPERATIONAL state invariant violated: missing view
  Expected: started AND hasGenesis AND hasCommittee AND hasView
  Actual:   started=true, hasGenesis=true, hasCommittee=true, hasView=false
```

**Severity**: **CRITICAL** (for OPERATIONAL), **HIGH** (other states)

**Remediation**:
- OPERATIONAL violations → FAIL node immediately (state corrupted)
- Other states → Log and monitor (may self-recover)

**Causes**:
- Race condition in state initialization
- Torn read (snapshot inconsistency)
- Byzantine behavior (state corruption attack)

#### 2. Precondition Violation

**What**: Transition attempted when preconditions not met

**Example**:
```
INITIAL→start precondition violated: already started
  Expected: !started
  Actual:   started=true
```

**Severity**: **HIGH**

**Remediation**: Reject transition - potential Byzantine behavior

**Causes**:
- Duplicate start() call
- Race condition (concurrent transitions)
- Byzantine node attempting illegal transition

#### 3. Postcondition Violation

**What**: Transition completed but expected outcome not achieved

**Example**:
```
RECOVERING→bootstrap postcondition violated: committee not created
  Expected: committeeType == "GenesisFormation"
  Actual:   committeeType=null
```

**Severity**: **HIGH**

**Remediation**: Rollback transaction - state update failed

**Causes**:
- Transition logic incomplete
- Exception swallowed during transition
- Resource allocation failure

#### 4. State Inconsistency

**What**: Distributed state fields are internally inconsistent

**Example**:
```
State consistency violation: hasGenesis=true but hasHead=false
  Expected: hasGenesis → hasHead
  Actual:   hasGenesis=true, hasHead=false
```

**Severity**: **MEDIUM** (first occurrence), escalates to **HIGH** (after 10)

**Remediation**: Capture snapshot - investigate race condition

**Causes**:
- TOCTOU race (time-of-check to time-of-use)
- Partial state update
- Network partition healing

#### 5. Equivocation

**What**: Conflicting state reports for the same transition

**Example**:
```
Equivocation detected: conflicting post-states for same transition
  Transition: RECOVERING→bootstrap
  State A: committeeType="GenesisFormation"
  State B: committeeType=null
```

**Severity**: **CRITICAL**

**Remediation**: FAIL node - Byzantine behavior confirmed

**Causes**:
- Byzantine node (malicious equivocation)
- Severe memory corruption
- Clock skew causing duplicate events

#### 6. Timing Anomaly

**What**: State transition velocity exceeds protocol limits

**Example**:
```
Timing anomaly: transition velocity exceeds protocol limits
  Transition: OPERATIONAL→nextView
  Rate: 50 transitions/sec
  Limit: 10 transitions/sec
```

**Severity**: **MEDIUM**

**Remediation**: Rate limit transitions - possible attack

**Causes**:
- Byzantine DoS attack (rapid view changes)
- Clock skew causing timestamp anomalies
- Legitimate high load (tune limits)

---

## Byzantine Indicators

### Multi-Signal Detection

State validation integrates with CHOAM's Byzantine detection framework. Multiple violation signals increase confidence in Byzantine diagnosis.

### High-Confidence Byzantine Signals

| Violation Type | Single Occurrence | Repeated Pattern | Confidence |
|----------------|-------------------|------------------|------------|
| **Equivocation** | CRITICAL | N/A | 🔴 **Definite** |
| **State Invariant (OPERATIONAL)** | CRITICAL | N/A | 🟠 **High** |
| **Timing Anomaly** | MEDIUM | >5 in 1 min | 🟠 **High** |
| **Precondition (non-existent transition)** | HIGH | N/A | 🟠 **High** |
| **State Inconsistency** | MEDIUM | >10 total | 🟡 **Moderate** |
| **Postcondition** | HIGH | >3 same transition | 🟡 **Moderate** |

### Corroborating Evidence

**Increase Byzantine confidence when violations correlate with:**

1. **Signature Failures** - Invalid BLS signatures from same node
2. **Threshold Bypass** - Attempts to form committees with <3f+1 nodes
3. **Fork Attempts** - Multiple conflicting blocks at same height
4. **Rate Anomalies** - Excessive view changes or gossip messages

### Recommended Actions

**Definite Byzantine** (Equivocation):
1. Isolate node immediately
2. Capture forensic snapshot (heap dump, logs, state)
3. Report to cluster operator
4. Initiate committee reconfiguration (remove Byzantine node)

**High Confidence** (OPERATIONAL invariant + signature failure):
1. Reduce node voting weight to 0 (temporary isolation)
2. Monitor for additional violations
3. Schedule maintenance window for investigation
4. Consider node replacement if pattern persists

**Moderate Confidence** (Repeated inconsistencies):
1. Enable detailed logging for affected node
2. Capture metrics snapshot
3. Correlate with network events (partitions, latency spikes)
4. Investigate after cluster stabilizes

---

## Troubleshooting

### Common Issues

#### Issue 1: Validation Overhead Too High

**Symptoms**:
- Latency p95 increase >10%
- Throughput decrease >10%
- Validation metrics show >244 μs p95

**Diagnosis**:
```bash
# Check validation latency
jconsole localhost:9090
# Navigate to: MBeans → metrics → histograms → validation.latency
# Check p95, p99 values
```

**Solutions**:

1. **Disable validation temporarily**:
```bash
jconsole → FeatureFlagManager → setEnabled("STATE_VALIDATION", false)
```

2. **Switch to METRICS_ONLY mode** (if available):
   - Reduces logging overhead
   - Maintains violation counters

3. **Investigate slow snapshots**:
   - Check `snapshot.latency` histogram
   - Ensure FSM lock contention is low (<1%)

4. **Tune snapshot capture** (developer task):
   - Review captureStateSnapshot() implementation
   - Verify lock-free AtomicReference reads

#### Issue 2: False Positive Violations

**Symptoms**:
- Violations logged during normal operation
- No Byzantine behavior observed
- Violations correlate with specific benign operations

**Diagnosis**:

1. **Check violation frequency**:
```bash
# View violation counters
jconsole → metrics → counters → validation.violations
# Filter by type (PRECONDITION_VIOLATION, etc.)
```

2. **Analyze violation patterns**:
```bash
# Grep logs for violations
grep "VIOLATION" choam.log | awk '{print $4, $5}' | sort | uniq -c
# Example output:
#   15 PRECONDITION_VIOLATION INITIAL→start
#   3 STATE_INCONSISTENCY RECOVERING
```

**Solutions**:

1. **Benign race condition**:
   - If violations occur <1% of transitions → likely benign TOCTOU
   - Monitor for escalation (>10 inconsistencies → HIGH severity)

2. **Overly strict invariant**:
   - Review invariant definition in CHOAMStateInvariant.java
   - File bug with violation logs and snapshot details

3. **Snapshot timing issue**:
   - Check if snapshot captured mid-transition (unlikely with FSM lock)
   - Verify snapshotSupplier uses Fsm.synchronizeOnState()

#### Issue 3: Violations Not Logged

**Symptoms**:
- STATE_VALIDATION enabled but no violations in logs
- Expecting violations based on observed behavior

**Diagnosis**:

1. **Verify feature flag enabled**:
```bash
jconsole → FeatureFlagManager → isEnabled("STATE_VALIDATION")
# Should return: true
```

2. **Check log level**:
```bash
# Ensure WARN level enabled for ValidatingCombineTransitions
grep "ValidatingCombineTransitions" logback.xml
# Should have <level value="WARN"/>
```

3. **Confirm decorator wrapping**:
```bash
# Check CHOAM startup logs
grep "State machine validation ENABLED" choam.log
# Should appear once per CHOAM instance
```

**Solutions**:

1. **Feature flag not enabled**:
   - Restart with `-Dfeature.state.validation=true`
   - Or use JMX to enable at runtime

2. **Log level too high**:
   - Set ValidatingCombineTransitions logger to WARN or lower
   - Restart logger configuration (may require restart)

3. **Decorator not active**:
   - Verify CHOAM constructor wrapping logic
   - Check for exceptions during ValidatingCombineTransitions construction

---

## Metrics Monitoring

### Available Metrics

State validation exposes the following Micrometer metrics:

#### Histograms (Latency)

| Metric Name | Description | SLA |
|-------------|-------------|-----|
| `validation.latency` | End-to-end validation time (pre+post) | p95 < 244 μs |
| `validation.precondition.latency` | Precondition check time | p95 < 5 μs |
| `validation.postcondition.latency` | Postcondition check time | p95 < 5 μs |
| `snapshot.latency` | State snapshot capture time | p95 < 0.2 μs |

#### Counters (Violations)

| Metric Name | Description | Alert Threshold |
|-------------|-------------|-----------------|
| `validation.violations.total` | Total violations (all types) | >100 / hour |
| `validation.violations.invariant` | State invariant violations | >10 / hour |
| `validation.violations.precondition` | Precondition violations | >10 / hour |
| `validation.violations.postcondition` | Postcondition violations | >10 / hour |
| `validation.violations.inconsistency` | State inconsistency | >50 / hour |
| `validation.violations.equivocation` | Equivocation (Byzantine) | >0 (immediate alert) |
| `validation.violations.timing` | Timing anomaly | >5 / minute |

#### Gauges (State)

| Metric Name | Description |
|-------------|-------------|
| `validation.enabled` | 1 if enabled, 0 if disabled |
| `validation.mode` | 0=LOG_ONLY, 1=ENFORCE, 2=METRICS_ONLY |

### Prometheus Example

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'choam'
    static_configs:
      - targets: ['localhost:9090']
    metric_relabel_configs:
      - source_labels: [__name__]
        regex: 'validation.*'
        action: keep
```

### Grafana Dashboard

**Recommended Panels**:

1. **Validation Latency**:
```promql
histogram_quantile(0.95, rate(validation_latency_bucket[5m]))
```

2. **Violation Rate** (by type):
```promql
rate(validation_violations_total{type="EQUIVOCATION"}[5m])
```

3. **Byzantine Alert** (equivocation):
```promql
increase(validation_violations_total{type="EQUIVOCATION"}[1m]) > 0
```

### Alerting Rules

```yaml
# alerts.yml
groups:
  - name: choam_validation
    rules:
      - alert: ValidationLatencyHigh
        expr: histogram_quantile(0.95, rate(validation_latency_bucket[5m])) > 0.000244
        for: 5m
        annotations:
          summary: "Validation latency exceeds SLA (>244 μs)"

      - alert: ByzantineEquivocation
        expr: increase(validation_violations_total{type="EQUIVOCATION"}[1m]) > 0
        for: 0m
        annotations:
          summary: "CRITICAL: Byzantine equivocation detected"
          severity: "critical"

      - alert: StateInvariantViolation
        expr: rate(validation_violations_total{type="STATE_INVARIANT_VIOLATION"}[1h]) > 0.01
        for: 5m
        annotations:
          summary: "Frequent state invariant violations"
```

---

## Performance Impact

### Baseline (STATE_VALIDATION=false)

**CHOAM Transition Latency** (Phase 0 baseline):
- p50: 1.2 ms
- p95: 2.44 ms
- p99: 5.1 ms
- Throughput: 4,100 txn/sec

### With Validation Enabled (STATE_VALIDATION=true)

**Target Overhead**:
- Latency increase: **<10%** (p95 < 2.68 ms)
- Throughput decrease: **<10%** (>3,690 txn/sec)
- Validation latency: **<244 μs** (p95)

**Measured Overhead** (Phase 4 testing):
- Snapshot capture: **0.2 μs** (p95)
- Precondition check: **5 μs** (p95)
- Postcondition check: **5 μs** (p95)
- End-to-end validation: **10-12 μs** (p95)

**Actual Impact**:
- Latency increase: **~0.5%** (well under 10% SLA)
- Throughput decrease: **~2%** (well under 10% SLA)

### Optimization Tips

1. **Use METRICS_ONLY mode** if logging overhead is high
2. **Disable in production** once debugging complete
3. **Batch enable** for canary deployment (10% → 50% → 100%)
4. **Monitor metrics** to detect overhead spikes

---

## Emergency Procedures

### Scenario 1: Validation Causing Outage

**Symptoms**:
- Service degraded after enabling STATE_VALIDATION
- Latency spike >10x baseline
- Throughput drop >50%

**Immediate Action** (< 1 minute):

```bash
# Disable validation via JMX
jconsole localhost:9090
→ FeatureFlagManager → setEnabled("STATE_VALIDATION", false)

# OR restart with flag disabled
kill -TERM <pid>
java -Dfeature.state.validation=false -jar choam.jar
```

**Verify Recovery**:
```bash
# Check latency returned to baseline
curl localhost:9090/metrics | grep validation.enabled
# Should show: validation_enabled 0
```

**Post-Incident**:
1. File bug with heap dump and logs
2. Analyze validation latency histogram (identify slow paths)
3. Test fix in staging before re-enabling

### Scenario 2: Byzantine Equivocation Detected

**Symptoms**:
- `validation.violations.equivocation` counter > 0
- Logs show "EQUIVOCATION" violations
- Multiple nodes report conflicting states

**Immediate Action** (< 5 minutes):

1. **Identify Byzantine node**:
```bash
# Grep logs for equivocation
grep "EQUIVOCATION" choam.log | grep "Member"
# Extract node ID from violation context
```

2. **Isolate node**:
```bash
# Set voting weight to 0 (requires cluster API)
curl -X POST localhost:9090/admin/node/<id>/weight -d '0'

# OR remove from committee (drastic)
curl -X DELETE localhost:9090/admin/committee/<id>
```

3. **Capture forensics**:
```bash
# Heap dump
jmap -dump:format=b,file=/tmp/byzantine.hprof <pid>

# Thread dump
jstack <pid> > /tmp/byzantine.threads

# Copy logs
cp choam.log /tmp/byzantine-choam.log
```

**Post-Incident**:
1. Analyze heap dump for memory corruption
2. Check network logs for Byzantine message patterns
3. Replace node with fresh instance
4. Report incident with forensic data

### Scenario 3: False Positive Storm

**Symptoms**:
- Violations logged at >100/sec
- No actual Byzantine behavior observed
- Violations correlate with benign operations (e.g., view changes)

**Immediate Action**:

1. **Assess impact**:
```bash
# Check violation types
grep "VIOLATION" choam.log | awk '{print $NF}' | sort | uniq -c
# If dominated by STATE_INCONSISTENCY or TIMING_ANOMALY → likely false positives
```

2. **Reduce noise** (if not investigating Byzantine):
```bash
# Switch to METRICS_ONLY mode (requires code change)
# OR temporarily disable validation
jconsole → FeatureFlagManager → setEnabled("STATE_VALIDATION", false)
```

**Post-Incident**:
1. File bug with violation logs and operation context
2. Review invariant/condition definitions (may be overly strict)
3. Tune timing thresholds for legitimate high-load scenarios

---

## Summary

State machine validation is a powerful debugging and Byzantine detection tool. Key takeaways:

✅ **Enable for debugging**, not routine production use
✅ **Default mode: LOG_ONLY** (safe, non-invasive)
✅ **Monitor metrics** to detect overhead or Byzantine behavior
✅ **Understand violation types** to triage effectively
✅ **Use Byzantine indicators** for high-confidence diagnosis
✅ **Know emergency procedures** for rapid incident response

For developer documentation, see: [state-validation-developer-guide.md](state-validation-developer-guide.md)

---

## Contact and Support

**Questions**: File issue in JIRA with `choam-validation` label
**Bugs**: Include logs, metrics, and heap dump if available
**Feature Requests**: Propose enhancements via RFC process

**Maintainers**: CHOAM Reliability Team
**Version**: 1.0 (2026-02-07)
