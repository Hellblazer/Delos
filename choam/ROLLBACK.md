# CHOAM Security Features Rollback Procedure

This document provides step-by-step rollback procedures for CHOAM Phase 1 security features.

## Emergency Contact

If you need to rollback immediately due to a production incident:

```bash
# Connect to JMX (requires jconsole or jmxterm)
jconsole

# Navigate to: com.hellblazer.delos.choam:type=FeatureFlagManager
# Execute: rollbackAll()
```

Or via CLI with jmxterm:
```bash
echo "run -b com.hellblazer.delos.choam:type=FeatureFlagManager rollbackAll" | java -jar jmxterm.jar -l localhost:9999
```

This immediately disables all Phase 1 security features across all nodes.

---

## Rollback Triggers

Initiate rollback if **ANY** of these conditions occur:

1. **Performance SLA Violations**: 2+ thresholds exceeded after feature deployment
   - p95 latency > baseline + 5%
   - p99 latency > baseline + 10%
   - Throughput < baseline - 10%
   - Memory > baseline + 15%

2. **Byzantine False Positives**: Validator rejection rate > 1%

3. **Data Integrity Issues**: Transaction replay detected OR queue overflow

4. **System Instability**: Crashes, deadlocks, or unexpected Byzantine behavior

---

## Feature-Specific Rollback

### Feature 1: Verifier Validation (VERIFIER_VALIDATION)

**What it does**: Strict validator verification instead of silently using NO_VERIFIER

**Rollback impact**: Returns to permissive mode, Byzantine nodes can potentially forge blocks

**Steps**:

1. **Disable feature flag** (choose one method):

   ```bash
   # Method A: JMX (no restart)
   jconsole -> com.hellblazer.delos.choam:type=FeatureFlagManager
   setEnabled("VERIFIER_VALIDATION", false)

   # Method B: System property (requires restart)
   Add to startup: -Dfeature.verifier.validation=false

   # Method C: Programmatic
   FeatureFlags.VERIFIER_VALIDATION.setEnabled(false);
   ```

2. **Verify rollback**:
   ```bash
   # Check feature status
   jconsole -> getStatus()
   # Should show: VERIFIER_VALIDATION: DISABLED

   # Monitor validator rejection rate (should drop to 0%)
   # Check Micrometer metrics: validator.rejection.rate
   ```

3. **Root cause analysis**:
   - Check logs for "validator not found in context" errors
   - Verify grace period (30s) is sufficient for slow publishers
   - Check if rejection rate > 1% threshold is too sensitive

4. **Re-enable when fixed**:
   ```bash
   # Gradual rollout: 10% -> 50% -> 100%
   jconsole -> enableForPercentage(10)  # Week 1
   # Monitor for 1 week, then:
   jconsole -> enableForPercentage(50)  # Week 2
   # Monitor for 1 week, then:
   jconsole -> enableForPercentage(100) # Week 3
   ```

**Affected code**: `Committee.java:59 (validatorsOf method)`

---

### Feature 2: Nonce Persistence (NONCE_PERSISTENCE)

**What it does**: Persists nonces across restarts to prevent replay attacks

**Rollback impact**: Nonces reset on restart, replay attacks become possible

**Steps**:

1. **Disable feature flag**:
   ```bash
   jconsole -> setEnabled("NONCE_PERSISTENCE", false)
   ```

2. **Verify rollback**:
   ```bash
   # Check nonce behavior after restart
   # Nonces should reset to 0 (old behavior restored)
   ```

3. **Data migration rollback** (if needed):
   ```bash
   # Run nonce migration rollback script
   java -cp choam.jar com.hellblazer.delos.choam.tools.NonceRollback

   # This restores non-persistent nonce state
   ```

4. **Root cause analysis**:
   - Check if nonce window (10,000) is too small
   - Verify block height expiration (H to H+10,000) is correct
   - Check for nonce overflow or collision issues

5. **Re-enable when fixed**:
   ```bash
   # Ensure migration tool completes first
   java -cp choam.jar com.hellblazer.delos.choam.tools.NonceMigration

   # Then enable gradually
   jconsole -> enableForPercentage(10)
   ```

**Affected code**: `Session.java:49 (nonce field)`
**Prerequisite**: Nonce migration tool (Delos-g4sh)

---

### Feature 3: Queue Eviction (QUEUE_EVICTION)

**What it does**: LRU eviction for pending queues to prevent DoS

**Rollback impact**: Queue flooding DoS becomes possible again

**Steps**:

1. **Disable feature flag**:
   ```bash
   jconsole -> setEnabled("QUEUE_EVICTION", false)
   ```

2. **Verify rollback**:
   ```bash
   # Pending queues revert to unbounded growth
   # Monitor queue sizes: pending.blocks.size, pending.validations.size
   ```

3. **Temporary mitigation** (if DoS attack in progress):
   ```bash
   # Increase queue limits as temporary workaround
   -Dmax.pending.blocks=50000
   -Dmax.pending.validations=500000

   # Or blacklist Byzantine nodes manually
   ```

4. **Root cause analysis**:
   - Check if LRU eviction is too aggressive (evicting legitimate blocks)
   - Verify height-based priority boost is working correctly
   - Analyze eviction rate vs throughput

5. **Re-enable when fixed**:
   ```bash
   jconsole -> enableForPercentage(10)
   # Monitor for queue overflow and eviction rates
   ```

**Affected code**: `Producer.java:46-47 (pending queues)`

---

## Gradual Rollout Strategy

### Week 1: Canary Deployment (10%)
```bash
jconsole -> enableForPercentage(10)
```

**Monitor**:
- Performance SLA thresholds (p95, p99, throughput, memory)
- Validator rejection rate (< 1%)
- Error logs and Byzantine detection alerts
- Queue sizes and eviction rates

**Go/No-Go Decision**:
- ✅ GO: All metrics within SLA, no errors -> Proceed to 50%
- ❌ NO-GO: Any metric violated -> Rollback and investigate

### Week 2: Half Deployment (50%)
```bash
jconsole -> enableForPercentage(50)
```

**Monitor**: Same as Week 1 plus:
- Cross-node consistency (nonce sync, queue states)
- Byzantine attack attempts and mitigation success

**Go/No-Go Decision**:
- ✅ GO: All metrics stable for 1 week -> Proceed to 100%
- ❌ NO-GO: Any issues -> Rollback to 10% or 0%

### Week 3: Full Deployment (100%)
```bash
jconsole -> enableForPercentage(100)
```

**Monitor**: Continue monitoring all metrics for 2 weeks post-deployment

---

## Rollback Verification Checklist

After rollback, verify:

- [ ] Feature flag shows DISABLED in `getStatus()`
- [ ] Performance metrics return to baseline
- [ ] No new errors in logs
- [ ] Byzantine detection still functioning
- [ ] All nodes acknowledge rollback (check cluster-wide)
- [ ] Incident report filed with root cause

---

## Production Deployment Checklist

Before enabling features:

- [ ] Performance baseline established (Delos-51vt complete)
- [ ] Rollback infrastructure tested (this task, Delos-7nho)
- [ ] Nonce migration tool ready (Delos-g4sh complete)
- [ ] JMX access configured for all nodes
- [ ] Monitoring dashboards configured (Grafana/Prometheus)
- [ ] On-call engineer briefed on rollback procedure
- [ ] Dry-run in staging environment successful

---

## Contact Information

- **Primary**: On-call SRE (PagerDuty: #choam-security)
- **Secondary**: Hal Hildebrand (CHOAM module owner)
- **Emergency**: Disable all features via `rollbackAll()`

---

## References

- Performance Baseline: Delos-51vt
- Rollback Infrastructure: Delos-7nho
- Phase 1 Security Plan: Delos-rhrf
- Individual fix beads: Delos-i642, Delos-0gps, Delos-z24d
