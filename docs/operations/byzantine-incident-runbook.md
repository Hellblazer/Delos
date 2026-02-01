# Byzantine Incident Response Runbook

This runbook provides step-by-step procedures for responding to Byzantine detection alerts and incidents.

## Alert Severity Levels

| Level | Criteria | Response Time |
|-------|----------|---------------|
| **P1 Critical** | Multiple critical detections, coordinated attack suspected | Immediate |
| **P2 High** | Single critical detection, high-confidence Byzantine | 15 minutes |
| **P3 Medium** | Warning detections, potential Byzantine behavior | 1 hour |
| **P4 Low** | Elevated false positive rate, tuning needed | Next business day |

## Incident Response Procedures

### Procedure 1: Critical Detection Alert

**Trigger**: `byzantine.detections.critical` counter increases

**Steps**:

1. **Assess Scope**
   ```bash
   # Check number of affected members
   curl -s localhost:8081/metrics | grep byzantine_detections_critical

   # Review recent detections
   grep "Critical detection" /var/log/delos/byzantine.log | tail -20
   ```

2. **Identify Affected Members**
   ```bash
   # List members with critical scores
   grep "score >= critical" /var/log/delos/byzantine.log | \
     awk '{print $NF}' | sort | uniq -c | sort -rn | head -10
   ```

3. **Verify Detection Accuracy**
   - Cross-reference with layer-specific logs
   - Check if member is genuinely Byzantine or false positive
   - Look for corroborating signals from multiple layers

4. **Confirm Shunning Action**
   ```bash
   # Verify response was triggered
   curl -s localhost:8081/metrics | grep byzantine_responses_triggered

   # Check Fireflies shun list
   curl -s localhost:8080/admin/shunned
   ```

5. **Escalate if Needed**
   - If multiple members detected: Possible coordinated attack → P1
   - If detection appears incorrect: Possible misconfiguration → P4

---

### Procedure 2: Coordinated Attack Response

**Trigger**: >5 critical detections within 5 minutes

**Steps**:

1. **Activate Incident Response**
   - Notify on-call team
   - Open incident channel
   - Begin timeline documentation

2. **Assess Attack Pattern**
   ```bash
   # Group detections by time
   grep "Critical detection" /var/log/delos/byzantine.log | \
     awk '{print substr($1,1,16)}' | uniq -c

   # Identify common characteristics
   grep "Critical detection" /var/log/delos/byzantine.log | \
     grep -oP 'signals=\[\K[^\]]+' | tr ',' '\n' | sort | uniq -c
   ```

3. **Adjust Detection Sensitivity**
   ```java
   // Temporarily lower thresholds for faster detection
   coordinator.reconfigure(IntelligenceConfig.builder()
       .warningThreshold(0.10)   // Lowered from 0.15
       .criticalThreshold(0.5)   // Lowered from 0.6
       .maxResponsesPerInterval(50)  // Increased from 10
       .build());
   ```

4. **Enable Enhanced Logging**
   ```bash
   # Increase log level
   curl -X POST localhost:8081/admin/loglevel \
     -d "logger=com.hellblazer.delos.membership.byzantine&level=DEBUG"
   ```

5. **Monitor Attack Progression**
   - Track number of shunned members
   - Monitor consensus health
   - Watch for attack pattern changes

6. **Coordinate with Network Operators**
   - Share list of shunned member IDs
   - Request IP blocklisting if applicable
   - Coordinate response across deployments

---

### Procedure 3: High False Positive Rate

**Trigger**: `byzantine.detections.warning` rate > 10% of tracked members

**Steps**:

1. **Verify False Positive Rate**
   ```bash
   # Calculate FP rate
   WARNINGS=$(curl -s localhost:8081/metrics | grep byzantine_detections_warning | awk '{print $2}')
   TRACKED=$(curl -s localhost:8081/metrics | grep byzantine_members_tracked | awk '{print $2}')
   echo "FP Rate: $(echo "scale=2; $WARNINGS / $TRACKED * 100" | bc)%"
   ```

2. **Identify Contributing Layers**
   ```bash
   # Check which layers are generating most signals
   grep "Layer contribution" /var/log/delos/byzantine.log | \
     grep -oP 'layer=\K\w+' | sort | uniq -c | sort -rn
   ```

3. **Adjust Thresholds**
   ```java
   // Raise thresholds to reduce false positives
   coordinator.reconfigure(IntelligenceConfig.builder()
       .warningThreshold(0.25)   // Raised from 0.15
       .build());
   ```

4. **Reduce Noisy Layer Weight**
   ```java
   // If specific layer is noisy
   coordinator.reconfigure(IntelligenceConfig.builder()
       .layerWeights(Map.of(
           "FIREFLIES", 0.4,
           "THOTH", 0.1,  // Reduced from 0.2 if THOTH is noisy
           ...
       ))
       .build());
   ```

5. **Document and Follow Up**
   - Record configuration changes
   - Schedule review after 24 hours
   - Consider permanent tuning adjustments

---

### Procedure 4: Response Handler Failures

**Trigger**: `byzantine.responses.failed` > 10% of triggered

**Steps**:

1. **Check Handler Health**
   ```bash
   # Check for handler errors in logs
   grep "Response failed" /var/log/delos/byzantine.log | tail -20

   # Verify Fireflies view is healthy
   curl -s localhost:8080/admin/health | jq '.fireflies'
   ```

2. **Identify Failure Pattern**
   ```bash
   # Group failures by error type
   grep "Response failed" /var/log/delos/byzantine.log | \
     grep -oP 'error=\K[^,]+' | sort | uniq -c
   ```

3. **Common Fixes**

   **Timeout Failures**:
   ```java
   // Increase handler timeout
   responseHandler.setTimeoutMillis(5000);  // Up from 1000
   ```

   **Connection Failures**:
   ```bash
   # Check network connectivity to view nodes
   for node in $(cat /etc/delos/nodes.txt); do
     echo -n "$node: "; nc -zv $node 8080 2>&1 | grep -o "succeeded\|failed"
   done
   ```

   **View Unavailable**:
   ```bash
   # Check Fireflies view status
   curl -s localhost:8080/admin/view/status

   # Force view refresh if stale
   curl -X POST localhost:8080/admin/view/refresh
   ```

4. **Enable Retry Logic** (if not already)
   ```java
   responseHandler.setRetryCount(3);
   responseHandler.setRetryDelayMillis(500);
   ```

---

### Procedure 5: Rate Limit Exhaustion

**Trigger**: `byzantine.ratelimit.skips` increasing during attack

**Steps**:

1. **Assess Skip Rate**
   ```bash
   curl -s localhost:8081/metrics | grep byzantine_ratelimit_skips
   ```

2. **Temporarily Increase Limit**
   ```java
   coordinator.reconfigure(IntelligenceConfig.builder()
       .maxResponsesPerInterval(100)  // Up from 10
       .build());
   ```

3. **Monitor for Feedback Loops**
   - Watch `byzantine.responses.triggered` rate
   - If rate explodes, detection is causing more detections
   - May need to increase cooldown instead

4. **Alternative: Batch Responses**
   - If many responses needed, consider batch shunning
   - Coordinate with Fireflies to shun multiple members at once

---

## Post-Incident Procedures

### After Any Incident

1. **Document Timeline**
   - When alert triggered
   - Actions taken
   - Resolution time
   - Members affected

2. **Collect Artifacts**
   ```bash
   # Export relevant logs
   journalctl -u delos --since "1 hour ago" > /tmp/incident-logs.txt

   # Export metrics snapshot
   curl -s localhost:8081/metrics > /tmp/incident-metrics.txt
   ```

3. **Review Detection Accuracy**
   - Were detections true positives?
   - Any false positives or negatives?
   - Update tuning if needed

4. **Update Runbook**
   - Document any new patterns observed
   - Add new procedures if needed
   - Share learnings with team

### Configuration Recovery

After temporary configuration changes during incident:

```java
// Restore to production defaults
coordinator.reconfigure(IntelligenceConfig.defaults());

// Or restore from saved production config
coordinator.reconfigure(productionConfig);
```

## Quick Reference

### Key Metrics to Monitor

| Metric | Normal | Warning | Critical |
|--------|--------|---------|----------|
| detections.critical/min | 0-2 | 3-10 | >10 |
| detections.warning/min | <5% of tracked | 5-10% | >10% |
| responses.failed % | <1% | 1-5% | >5% |
| ratelimit.skips/min | 0 | 1-10 | >10 |

### Emergency Contacts

| Role | Contact |
|------|---------|
| On-Call Engineer | [pager] |
| Security Team | [channel] |
| Network Operations | [channel] |

### Key Commands

```bash
# Check overall health
curl -s localhost:8081/metrics | grep byzantine

# View recent critical detections
grep "Critical" /var/log/delos/byzantine.log | tail -10

# Check shunned members
curl -s localhost:8080/admin/shunned

# Force coordinator reset
curl -X POST localhost:8081/admin/byzantine/reset
```

## Related Documentation

- [Operations Guide](byzantine-detection-operations.md)
- [Weight Tuning Methodology](weight-tuning-methodology.md)
- [ADR-007: Architecture](../adr/007-cross-layer-byzantine-detection.md)
