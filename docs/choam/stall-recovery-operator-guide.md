# CHOAM Stall Recovery - Operator Guide

## Overview

CHOAM consensus can stall when the block processor experiences consecutive empty polls, indicating blocks are not being produced or consumed. This guide explains how to detect, diagnose, and recover from stalls using the automated recovery infrastructure.

**Target Audience**: DevOps, SREs, System Administrators
**Version**: 1.0.0
**Last Updated**: 2026-02-07

---

## Table of Contents

1. [What is a Stall?](#what-is-a-stall)
2. [Stall Causes](#stall-causes)
3. [Detection](#detection)
4. [Recovery Strategies](#recovery-strategies)
5. [Monitoring](#monitoring)
6. [Troubleshooting](#troubleshooting)
7. [Configuration](#configuration)
8. [Emergency Procedures](#emergency-procedures)

---

## What is a Stall?

A **stall** occurs when the CHOAM block processor polls for new blocks but receives empty results consecutively, indicating consensus has stopped making progress.

**Stall Criteria**:
- **Empty Poll Count**: 10 consecutive empty polls (configurable via `BlockProcessor.MAX_EMPTY_POLLS`)
- **Duration**: Typically 10-30 seconds depending on poll interval
- **Impact**: No blocks processed, transactions not committed, consensus halted

**Detection Log Example**:
```
WARN  StallDiagnostics - Stall detected: 10 empty polls, last height: 1523, duration: PT25S on: [Member-A]
```

---

## Stall Causes

CHOAM stalls are classified into three root causes, each requiring different recovery strategies:

### 1. PARTITION (Network Partition)

**Diagnosis**: Gossip heartbeat failures exceed threshold (default: >3 consecutive failures in 30s window)

**Symptoms**:
- Fireflies reports unreachable members
- Network connectivity lost to majority of cluster
- Logs show: `Stall diagnosed as PARTITION: 4 members with heartbeat failures`

**Root Causes**:
- Network outage or partition
- Switch/router failure
- Firewall misconfiguration
- DNS resolution failure

**Recovery**: ReconnectRecovery
- Monitors for partition healing (heartbeat resumption)
- Triggers resync when majority partition available
- Catches up on missed blocks

**Manual Intervention**:
1. Verify network connectivity: `ping <peer-ip>`
2. Check firewall rules: `iptables -L` or `firewall-cmd --list-all`
3. Verify DNS: `nslookup <peer-hostname>`
4. Restart networking if needed: `systemctl restart network`

---

### 2. CONSENSUS_SLOW (Consensus Slowdown)

**Diagnosis**: Consensus participation rate below threshold (default: <50% in 1 minute window)

**Symptoms**:
- Block production velocity decreased significantly
- Ethereal participation rate low
- Logs show: `Stall diagnosed as CONSENSUS_SLOW: participationRate=35%`

**Root Causes**:
- High CPU/memory pressure on validators
- Database I/O bottleneck
- Slow network latency
- Insufficient validator resources
- Transaction backlog

**Recovery**: ResyncRecovery (with Circuit Breaker)
- Fetches missing blocks from healthy nodes
- Validates chain continuity
- Resumes consensus participation
- Circuit breaker protects against infinite loops (3 failures → OPEN for 5min)

**Manual Intervention**:
1. Check resource utilization: `top`, `free -h`, `iostat`
2. Verify database health: Check H2 lock contention, disk space
3. Monitor network latency: `ping -c 100 <peer>` (check avg/max)
4. Scale resources if needed: Increase CPU/memory allocation
5. Clear transaction backlog: Review pending transactions

---

### 3. BYZANTINE (Byzantine Behavior)

**Diagnosis**: Byzantine indicators exceed thresholds:
- Signature failure rate >5% (5 minute window), OR
- Timing anomaly rate >10% (5 minute window)

**Symptoms**:
- Invalid signatures detected
- Timing anomalies in state transitions
- Equivocation or fork detection
- Logs show: `Stall diagnosed as BYZANTINE: signatureFailureRate=8.5%, timingAnomalyRate=2.1%`

**Root Causes**:
- Malicious node in committee
- Compromised validator
- Software bug causing invalid behavior
- Clock skew >500ms (causes timing violations)

**Recovery**: ViewChangeRecovery
- Logs Byzantine incident with forensic evidence
- Proposes view change to exclude Byzantine member (TODO: requires Ethereal integration)
- Reconfigures committee without malicious node

**Manual Intervention**:
1. **Immediate**: Isolate suspected Byzantine node from network
2. Check clock synchronization: `ntpq -p` (ensure offset <100ms)
3. Review forensic logs: Search for "BYZANTINE INCIDENT DETECTED"
4. Verify signatures manually if possible
5. **Critical**: Do NOT restart Byzantine node - preserve state for forensics
6. Contact security team for incident analysis
7. Backup logs and state for post-mortem

---

## Detection

### Automatic Detection

Stall detection runs continuously in the block processor:

```java
// Automatic stall detection in BlockProcessor
if (emptyPollCount >= MAX_EMPTY_POLLS) {
    var event = new StallDetectedEvent(lastHeight, stallDuration, emptyPollCount, context);
    stallListener.accept(event);  // Triggers CHOAM.handleStallDetected()
}
```

**Detection Flow**:
```
BlockProcessor polls for blocks
  ↓ (empty poll)
emptyPollCount++
  ↓ (count >= 10)
StallDetectedEvent emitted
  ↓
StallDiagnostics.diagnose()
  ├─ Check Byzantine indicators (highest priority)
  ├─ Check partition indicators
  └─ Default to CONSENSUS_SLOW
  ↓
StallRecoveryStrategy selected
  ↓
Recovery invoked
```

### Manual Detection

Check for stalls manually:

```bash
# 1. Check last processed height (should be increasing)
tail -f choam.log | grep "Processed block.*height"

# 2. Check for empty polls
grep "empty poll" choam.log | tail -20

# 3. Check for stall warnings
grep "Stall detected" choam.log

# 4. Monitor metrics (if enabled)
curl http://localhost:9090/metrics | grep choam_stall
```

---

## Recovery Strategies

### Circuit Breaker Protection

**ResyncRecovery** uses a circuit breaker to prevent infinite recovery loops:

**States**:
- **CLOSED**: Normal operation, recovery attempts proceed
- **OPEN**: Circuit tripped (3 consecutive failures), fail-fast for 5 minutes
- **HALF_OPEN**: Testing recovery after timeout, single attempt allowed

**Configuration**:
```java
// Circuit breaker defaults (ResyncRecovery)
Failure threshold: 3 consecutive failures
Reset timeout: 5 minutes
Half-open test: Single recovery attempt
```

**Circuit Breaker Logs**:
```
INFO  CircuitBreaker - Circuit breaker transitioning OPEN → HALF_OPEN (reset timeout elapsed)
WARN  CircuitBreaker - Circuit breaker failure threshold reached (3/3), transitioning CLOSED → OPEN
INFO  CircuitBreaker - Circuit breaker test request succeeded, transitioning HALF_OPEN → CLOSED
```

**Manual Circuit Breaker Reset**:

Circuit breakers are per-recovery-instance and auto-reset after 5 minutes. If immediate reset is needed, restart the CHOAM node (circuit breaker state is not persisted).

---

## Monitoring

### Metrics

If Micrometer metrics enabled, monitor these counters:

```prometheus
# Stall diagnosis counts by cause
choam_stall_diagnosis_partition_total
choam_stall_diagnosis_consensus_slow_total
choam_stall_diagnosis_byzantine_total

# Recovery attempts (TODO: M5.4 - add recovery metrics)
choam_stall_recovery_success_total
choam_stall_recovery_failure_total
choam_stall_recovery_duration_seconds
```

**Grafana Dashboard Query Examples**:
```promql
# Stall rate (stalls per hour)
rate(choam_stall_diagnosis_partition_total[1h]) * 3600

# Partition stall percentage
choam_stall_diagnosis_partition_total /
  (choam_stall_diagnosis_partition_total +
   choam_stall_diagnosis_consensus_slow_total +
   choam_stall_diagnosis_byzantine_total) * 100

# Recovery success rate (when metrics added)
choam_stall_recovery_success_total /
  (choam_stall_recovery_success_total + choam_stall_recovery_failure_total) * 100
```

### Log Monitoring

**Key Log Patterns**:

```bash
# Stall detection
grep "Stall detected:" choam.log

# Diagnosis results
grep "Stall diagnosed as" choam.log

# Recovery initiation
grep "Initiating.*recovery" choam.log

# Recovery success/failure
grep "recovery.*successfully\|recovery.*failed" choam.log

# Circuit breaker events
grep "Circuit breaker" choam.log

# Byzantine incidents
grep "BYZANTINE INCIDENT DETECTED" choam.log
```

**Alerting Rules**:

```yaml
# Prometheus alerting rules
groups:
  - name: choam_stall_alerts
    rules:
      - alert: ChoamHighStallRate
        expr: rate(choam_stall_diagnosis_total[5m]) > 0.1
        for: 5m
        annotations:
          summary: "High CHOAM stall rate detected"
          description: "Node {{ $labels.instance }} experiencing {{ $value }} stalls/sec"

      - alert: ChoamByzantineStall
        expr: increase(choam_stall_diagnosis_byzantine_total[5m]) > 0
        for: 0m
        annotations:
          summary: "CRITICAL: Byzantine behavior detected"
          description: "Node {{ $labels.instance }} detected Byzantine stall - INVESTIGATE IMMEDIATELY"
```

---

## Troubleshooting

### Repeated Stalls

**Symptom**: Stalls recur every few minutes

**Diagnosis**:
1. Check stall cause pattern: `grep "Stall diagnosed as" choam.log | tail -50`
2. If all PARTITION: Network instability
3. If all CONSENSUS_SLOW: Resource exhaustion or configuration issue
4. If mixed: Multiple root causes (investigate each)

**Resolution**:
- **Partition**: Fix network stability (see Network Partition below)
- **Consensus Slow**: Increase resources or tune configuration
- **Mixed**: Address each root cause independently

### Network Partition

**Symptom**: Frequent PARTITION stalls

**Diagnosis**:
```bash
# Check network connectivity to all peers
for peer in peer1 peer2 peer3; do
  ping -c 10 $peer | tail -3
done

# Check packet loss
mtr --report <peer-ip>

# Verify firewall rules
iptables -L -n | grep <peer-ip>
```

**Resolution**:
1. Fix network routing/switching issues
2. Verify firewall allows CHOAM ports (check `params.communications()` config)
3. Check for asymmetric routing
4. Verify MTU settings consistent across cluster

### Resource Exhaustion

**Symptom**: Frequent CONSENSUS_SLOW stalls, high CPU/memory/IO

**Diagnosis**:
```bash
# CPU usage
top -b -n 1 | grep java

# Memory usage
free -h
jmap -heap <java-pid>

# Disk I/O
iostat -x 1 10

# JVM GC activity
jstat -gc <java-pid> 1000
```

**Resolution**:
1. Increase JVM heap: `-Xmx` parameter
2. Tune GC settings: Consider G1GC or ZGC
3. Add CPU cores or scale to larger instance
4. Optimize database: Add indexes, vacuum H2, check disk space
5. Reduce transaction rate if possible

### Circuit Breaker Stuck OPEN

**Symptom**: Recovery not attempted, logs show "Circuit breaker is OPEN"

**Diagnosis**:
```bash
# Check circuit breaker state
grep "Circuit breaker is OPEN" choam.log | tail -5

# Check when it opened
grep "transitioning CLOSED → OPEN" choam.log | tail -1
```

**Resolution**:
- **Wait 5 minutes**: Circuit auto-resets to HALF_OPEN
- **OR restart node**: Circuit breaker state is not persisted
- **Root cause**: Fix underlying recovery failure before circuit closes

---

## Configuration

### Stall Detection Thresholds

Configure via `Parameters` (requires code change, no runtime config yet):

```java
// StallDiagnostics.Thresholds (defaults)
var thresholds = new StallDiagnostics.Thresholds(
    3,      // PARTITION: heartbeat failures (default: 3)
    0.5,    // CONSENSUS_SLOW: participation threshold (default: 50%)
    0.05,   // BYZANTINE: signature failure rate (default: 5%)
    0.1,    // BYZANTINE: timing anomaly rate (default: 10%)
    Duration.ofSeconds(30),  // PARTITION window (default: 30s)
    Duration.ofMinutes(1),   // CONSENSUS_SLOW window (default: 1min)
    Duration.ofMinutes(5)    // BYZANTINE window (default: 5min)
);

// Pass to StallDiagnostics
var diagnostics = new StallDiagnostics(
    context, communications, byzantineMapper, thresholds, metrics);
```

### Circuit Breaker Tuning

Circuit breaker configuration is hardcoded in `ResyncRecovery` (future: make configurable):

```java
// Current: ResyncRecovery circuit breaker
Failure threshold: 3
Reset timeout: 5 minutes

// To change: Modify ResyncRecovery constructor (requires code change)
```

**Tuning Recommendations**:
- **Conservative** (high availability): Threshold=5, Timeout=10min
- **Aggressive** (fail-fast): Threshold=2, Timeout=2min
- **Default** (balanced): Threshold=3, Timeout=5min

---

## Emergency Procedures

### Byzantine Node Isolation

**Trigger**: BYZANTINE stall detected

**Immediate Actions**:

1. **Identify Byzantine node** from forensic logs:
```bash
grep "BYZANTINE INCIDENT DETECTED" choam.log -A 30
```

2. **Isolate node from network** (prevent further damage):
```bash
# Block node IP at firewall (IMMEDIATE)
iptables -A INPUT -s <byzantine-node-ip> -j DROP
iptables -A OUTPUT -d <byzantine-node-ip> -j DROP

# OR remove from DNS/load balancer
```

3. **DO NOT restart Byzantine node** - preserve state for forensics

4. **Capture forensic evidence**:
```bash
# Backup logs
cp choam.log choam-byzantine-$(date +%Y%m%d-%H%M%S).log

# Capture JVM state
jstack <java-pid> > byzantine-threads.txt
jmap -heap <java-pid> > byzantine-heap.txt

# Capture network state
netstat -an > byzantine-network.txt
```

5. **Notify security team** with:
   - Node ID (from logs)
   - Timestamp of detection
   - Forensic log file
   - Violation type (signature/timing/equivocation)

6. **Monitor remaining nodes** for consensus recovery

### Complete Cluster Stall

**Trigger**: All nodes stalled simultaneously

**Diagnosis**:
```bash
# Check if ALL nodes see stalls
for node in node1 node2 node3 node4; do
  ssh $node "tail -20 /var/log/choam.log | grep 'Stall detected'"
done
```

**Recovery**:

1. **Check cluster-wide issues**:
   - Network partition (split-brain)
   - NTP sync failure (clock skew)
   - Shared infrastructure failure (DB, storage)

2. **If majority partition available** (≥2f+1 nodes):
   - Nodes should auto-recover via ResyncRecovery
   - Wait 30s for automatic recovery
   - If no recovery, check logs for circuit breaker state

3. **If NO majority partition** (Byzantine fault tolerance lost):
   - **CRITICAL**: Manual intervention required
   - Restore network connectivity FIRST
   - Once majority available, nodes auto-recover
   - If auto-recovery fails, consider manual restart (last resort)

4. **Manual restart procedure** (LAST RESORT):
```bash
# Restart nodes in sequence (one at a time, wait for sync)
systemctl restart choam-node

# Verify sync before restarting next
tail -f /var/log/choam.log | grep "Synchronized, resuming"
```

---

## Performance SLAs

**Target SLAs** (under normal conditions):

| Metric | Target (p95) | Critical Threshold |
|--------|-------------|-------------------|
| Stall Detection Latency | <5s | >10s |
| Recovery Latency | <30s | >60s |
| Recovery Success Rate | >90% | <80% |
| False Positive Rate | <1% | >5% |

**Monitoring**:
- Track actual vs target in monitoring dashboard
- Alert if SLAs breached consistently
- Investigate root cause if critical thresholds exceeded

---

## Best Practices

1. **Proactive Monitoring**: Set up alerts for stall rate, recovery failures
2. **Regular Health Checks**: Monitor Fireflies heartbeats, Ethereal participation
3. **Resource Headroom**: Maintain 30% CPU/memory/disk headroom
4. **Clock Synchronization**: Use NTP, ensure <100ms offset across cluster
5. **Network Stability**: Monitor packet loss, latency spikes
6. **Forensic Logging**: Retain logs for ≥7 days for incident analysis
7. **Regular Testing**: Simulate partitions/failures in staging environment
8. **Byzantine Preparedness**: Have incident response plan ready
9. **Capacity Planning**: Scale before reaching 70% resource utilization
10. **Version Consistency**: Ensure all nodes run same CHOAM version

---

## Support

**For issues not covered in this guide**:

1. Check project documentation: `/docs/choam/`
2. Review source code: `choam/src/main/java/com/hellblazer/delos/choam/support/`
3. Search logs: `grep -r "ERROR\|WARN" /var/log/choam/`
4. File GitHub issue: https://github.com/Hellblazer/Delos/issues

**Include in bug reports**:
- CHOAM version
- JVM version and platform
- Cluster size and topology
- Stall logs (last 100 lines)
- Recovery attempt logs
- Metrics screenshots (if available)
- Steps to reproduce

---

## Appendix

### Log Levels

Recommended log levels for stall recovery:

```properties
# log4j.properties or logback.xml
com.hellblazer.delos.choam.support.StallDiagnostics=INFO
com.hellblazer.delos.choam.support.CircuitBreaker=INFO
com.hellblazer.delos.choam.support.StallRecoveryStrategy=INFO

# For troubleshooting, set to DEBUG
com.hellblazer.delos.choam.support.StallDiagnostics=DEBUG
```

### Related Documentation

- [CHOAM State Validation - Operator Guide](state-validation-operator-guide.md)
- [CHOAM State Validation - Developer Guide](state-validation-developer-guide.md)
- [CHOAM Architecture](../../README.md)
- [Fireflies Membership](../../fireflies/README.md)
- [Ethereal Consensus](../../ethereal/README.md)

---

**Document Version**: 1.0.0
**Last Updated**: 2026-02-07
**Maintained By**: CHOAM Team
