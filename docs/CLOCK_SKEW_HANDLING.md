# Clock Skew Handling Guide

**Configuring, monitoring, and troubleshooting clock synchronization in Byzantine distributed systems.**

**Audience**: Operations teams, DevOps engineers, system administrators
**Version**: 1.0
**Last Updated**: 2026-01-27

---

## Overview

Clock skew is the difference between a node's clock and the cluster's reference time. In Byzantine distributed systems, excessive clock skew can:
- Cause credentials to be rejected (timestamp validation fails)
- Prevent Byzantine consensus participation
- Break identity rotation (KERI)
- Cascade to cluster-wide failure if widespread

This guide covers:
1. **Configuration** - Setting appropriate clock skew tolerance
2. **Monitoring** - Detecting clock drift early
3. **Prevention** - Best practices for NTP setup
4. **Troubleshooting** - Diagnosing and fixing clock issues
5. **Recovery** - Bringing nodes back into sync

---

## Part 1: Configuration

### Default Clock Skew Tolerance

Delos allows configurable clock skew tolerance. Default is **500ms** (±0.5 seconds).

```properties
# In delos.properties or configuration file
credentials.clockSkewTolerance=500ms    # ±500ms tolerance (default)

# For stricter environments, reduce to:
credentials.clockSkewTolerance=100ms    # ±100ms (stricter, more failures possible)

# For lenient environments (not recommended for production):
credentials.clockSkewTolerance=1000ms   # ±1000ms (loose, security risk)
```

### Recommended Settings by Environment

| Environment | Tolerance | Rationale | Risk |
|-------------|-----------|-----------|------|
| Production (LAN) | 500ms | Standard for controlled networks | Low: NTP keeps drift <100ms |
| Production (WAN) | 1000ms | Internet latency may cause sync delays | Medium: Larger window for drift |
| Development/Testing | 1000ms | Easier testing without strict time setup | N/A: Not production |
| High-security | 100ms | Minimize attack window | High: More clock-sync failures |

### Validation Configuration

Clock skew validation happens at two points:

**1. Credential validation** (on every message):
```java
// In CredentialValidator.java
boolean isTimestampValid(Timestamp ts) {
    Instant now = clock.instant();
    Duration diff = Duration.between(ts.toInstant(), now).abs();
    return diff.compareTo(clockSkewTolerance) <= 0;  // Within tolerance?
}
```

**2. Identity rotation** (KERI):
```java
// In Gorgoneion (identity service)
Credential rotate(RotationEvent event) {
    // Check timestamp is within tolerance
    if (event.getTimestamp().outside(clockSkewTolerance)) {
        throw new InvalidEventException("Timestamp outside tolerance");
    }
    // ... continue with rotation
}
```

### TTL Calculation

Credentials have TTL (time to live) that includes clock skew tolerance:

```
TTL = maxDuration + clockSkewTolerance

Example:
- maxDuration = 30 seconds (credential max age)
- clockSkewTolerance = 500ms
- Total TTL = 30.5 seconds
```

If a credential was issued with max age 30s but a peer's clock is 500ms ahead, they'll accept it at 30.5 seconds.

---

## Part 2: Monitoring

### 2.1 Daily Clock Sync Check

Run this check daily (typically in a cron job):

```bash
#!/bin/bash
# daily-clock-check.sh - Verify cluster clock synchronization

NODES=("node-1" "node-2" "node-3" "node-4" "node-5")
TOLERANCE_MS=500

echo "=== CLOCK SYNCHRONIZATION CHECK $(date) ===" | tee -a clock-sync.log

# Get reference time from NTP
REFERENCE=$(ntpdate -q time.nist.gov 2>/dev/null | tail -1 | awk '{print $1}')
REFERENCE_EPOCH=$(date -d "$REFERENCE" +%s%N | cut -b1-13)

MAX_SKEW=0

for node in "${NODES[@]}"; do
    # Get node's current time
    NODE_TIME=$(ssh $node date +%s%N | cut -b1-13)

    # Calculate skew
    SKEW=$((NODE_TIME - REFERENCE_EPOCH))
    SKEW_ABS=${SKEW#-}  # Absolute value

    # Convert to milliseconds
    SKEW_MS=$((SKEW_ABS / 1000000))

    # Status: OK or WARN
    if [ $SKEW_MS -le $TOLERANCE_MS ]; then
        STATUS="✓ OK"
    else
        STATUS="✗ WARN"
    fi

    echo "$node: ${SKEW_MS}ms offset $STATUS" | tee -a clock-sync.log

    if [ $SKEW_MS -gt $MAX_SKEW ]; then
        MAX_SKEW=$SKEW_MS
    fi
done

echo ""
echo "Max cluster skew: ${MAX_SKEW}ms (tolerance: ${TOLERANCE_MS}ms)"

# Alert if exceeded
if [ $MAX_SKEW -gt $((TOLERANCE_MS + 100)) ]; then
    echo "⚠️ WARNING: Clock skew exceeds tolerance!" | tee -a clock-sync.log
    # Send alert (example: Slack, PagerDuty)
    # curl -X POST https://hooks.slack.com/... -d "..."
fi
```

**Run in cron** (4 AM daily):
```bash
0 4 * * * /opt/delos/daily-clock-check.sh
```

### 2.2 Continuous Monitoring

Set up continuous monitoring with Prometheus metrics:

```bash
# Every 10 seconds, scrape these metrics:
# From http://localhost:8080/metrics

clock_skew_ms              # Current offset from reference (absolute)
peer_clock_skew_max_ms     # Maximum skew to any peer
peer_clock_skew_count      # Number of peers with skew > 50ms
credentials_rejected_count # Rejected credentials (may indicate clock skew)
identity_rotation_failures # Failed identity rotations
```

**Grafana dashboard** for clock monitoring:

```
Row 1:
- Clock skew: Current offset (red if >100ms)
- Max peer skew: Maximum across cluster (red if >200ms)

Row 2:
- Skew trend over 24h (should be flat line near 0)
- Failed credentials due to timestamp

Row 3:
- NTP status per node (green/red: synced/not synced)
- Chrony/ntpd daemon status
```

### 2.3 Metrics Interpretation

| Metric | Normal | Warning | Critical |
|--------|--------|---------|----------|
| clock_skew_ms | <50ms | 50-100ms | >100ms |
| peer_clock_skew_max_ms | <100ms | 100-250ms | >250ms |
| credentials_rejected_count | 0-1/min | 2-5/min | >10/min |
| identity_rotation_failures | 0 per day | 1-2 per day | >3 per day |

**If Critical thresholds exceeded**: Page on-call immediately (cluster at risk).

---

## Part 3: Prevention - NTP Setup Best Practices

### 3.1 Linux: Chrony Setup (Recommended)

Chrony is the modern NTP implementation (works better with virtualization):

```bash
# 1. Install
sudo apt-get install chrony

# 2. Configure /etc/chrony/chrony.conf
# Replace pool lines with reliable NTP servers:

pool time.nist.gov iburst maxsources 4
pool time.google.com iburst maxsources 4
pool time.cloudflare.com iburst maxsources 4

# Key settings:
# Leap second handling: always step (correct immediately)
leapsecmode slew

# Initial sync aggressiveness: first sync slew clock (don't jump)
initstepslew 100 time.nist.gov time.google.com

# Frequency correction: track drift file for faster re-sync
driftfile /var/lib/chrony/chrony.drift

# Kernel synchronization: step clock if drift > 100ms
makestep 1.0 3

# Enable NTP port for external queries (optional)
allow 0.0.0.0/0
```

### 3.2 Linux: NTP Alternative Setup

If using ntpd instead of Chrony:

```bash
# 1. Install
sudo apt-get install ntp

# 2. Configure /etc/ntp.conf
# Use multiple NTP servers for redundancy:

server time.nist.gov iburst prefer
server time.google.com iburst
server time.cloudflare.com iburst
server 127.127.1.0 (if local GPS available)

# Key settings:
pool iburst minpoll 4 maxpoll 6    # Fast polling
ntpsigndsocket /var/lib/ntp/signd  # DNSSEC validation

# Frequency correction:
driftfile /var/lib/ntp/ntp.drift
```

### 3.3 Cloud Environments

Different cloud providers have clock synchronization best practices:

**AWS EC2**:
```bash
# 1. Install NTP
sudo apt-get install chrony

# 2. Configure to use AWS NTP server
# In /etc/chrony/chrony.conf:
server 169.254.169.123 iburst prefer

# 3. AWS hosts already synced, but add external fallback:
server time.nist.gov iburst
server time.google.com iburst

# 4. Restart
sudo systemctl restart chrony
```

**Google Cloud**:
```bash
# Already configured by default
# Verify with:
timedatectl status

# Should show: Synchronized to time.google.com
```

**Azure**:
```bash
# Configure to use Azure NTP
# In /etc/chrony/chrony.conf:
server metadata.google.internal iburst
server ntp.ubuntu.com iburst
```

### 3.4 Docker/Kubernetes

In containerized deployments:

```yaml
# Kubernetes: Mount host time
apiVersion: v1
kind: Pod
metadata:
  name: delos-node
spec:
  containers:
  - name: delos
    image: delos:latest
    volumeMounts:
    - name: localtime
      mountPath: /etc/localtime
      readOnly: true
  volumes:
  - name: localtime
    hostPath:
      path: /etc/localtime

# Docker: Inherit host time
docker run --name delos \
  -v /etc/timezone:/etc/timezone:ro \
  -v /etc/localtime:/etc/localtime:ro \
  delos:latest
```

### 3.5 Pre-Deployment Clock Checklist

- [ ] NTP daemon running: `sudo systemctl status chrony`
- [ ] NTP servers accessible: `ntpdate -q time.nist.gov` (should succeed)
- [ ] Initial sync: `chronyc tracking` (Leap status: Normal, System time: good)
- [ ] Clock within tolerance: `date` on all nodes (within 100ms)
- [ ] Drift file exists: `/var/lib/chrony/chrony.drift` (for faster re-sync)
- [ ] Frequency tracking: `chronyc sources` (offset < 50ms)
- [ ] Kernel NTP mode: `timedatectl` shows "Synchronized: yes"

---

## Part 4: Troubleshooting

### 4.1 Detecting Clock Skew Problems

**Symptom 1: Credentials rejected with "timestamp out of range"**

```bash
# Check logs
grep "timestamp out of range\|timestamp validation failed" /var/log/delos/*.log

# Measure actual skew
ssh node-1 date +%s%N > /tmp/t1
ssh node-2 date +%s%N > /tmp/t2
echo "Skew: $(( $(cat /tmp/t2) - $(cat /tmp/t1) )) nanoseconds"

# Should be < 500ms (500,000,000 ns) in absolute value
```

**Symptom 2: Identity rotation failures**

```bash
# Check for rotation errors
grep "rotation failed\|RotationEvent rejected" /var/log/delos/*.log

# Count failures
grep -c "rotation failed" /var/log/delos/delos.log

# If >2 per hour, clock sync is suspect
```

**Symptom 3: Member becoming suspect/shunned**

```bash
# Check metrics
curl http://localhost:8080/metrics | grep -E "fireflies_suspected|fireflies_shunned"

# Check logs for clock-related suspicion
grep "suspected\|shunned" /var/log/delos/*.log | grep -i "clock\|timestamp"

# If member keeps getting suspected, likely clock drift
```

### 4.2 Diagnosis Procedure

**Step 1: Verify NTP status on all nodes**

```bash
#!/bin/bash
for node in node-1 node-2 node-3 node-4 node-5; do
    echo "=== $node ==="
    ssh $node "timedatectl; echo ''; chronyc tracking; echo ''; chronyc sources"
done
```

Expected output for `timedatectl`:
```
      Local time: Mon 2026-01-27 10:30:45 UTC
  Universal time: Mon 2026-01-27 10:30:45 UTC
        RTC time: Mon 2026-01-27 10:30:45
       Time zone: UTC (UTC, +0000)
     NTP enabled: yes
NTP synchronized: yes  ← Should be YES
 RTC in local TZ: no
      DST active: n/a
```

Expected output for `chronyc tracking`:
```
Reference ID    : 91540EF3 (time.nist.gov)
Stratum         : 2
Ref time (UTC)  : Mon Jan 27 10:30:45 2026
System time     : 0.000000003 seconds slow of NTP time  ← Should be <100ms
Last offset     : +0.000023456 seconds
RMS offset      : 0.000031200 seconds
Frequency       : -5.436 ppm  ← Drift (normal range: ±100 ppm)
Residual freq   : -0.001 ppm
Skew            : 0.003 ppm
Root delay      : 0.083458 seconds
Root dispersion : 0.100012 seconds
Update interval : 64.0 seconds
Leap status     : Normal
```

**Step 2: Check NTP daemon health**

```bash
# On each node
sudo systemctl status chrony
sudo journalctl -u chrony -n 50  # Last 50 log lines

# Should show:
# - Successful NTP connections
# - Clock adjustments (if any)
# - No errors
```

**Step 3: Measure actual clock skew**

```bash
#!/bin/bash
# Measure skew between each pair of nodes

NODES=("node-1" "node-2" "node-3" "node-4" "node-5")

for i in "${!NODES[@]}"; do
  for j in "${!NODES[@]}"; do
    if [ $i -lt $j ]; then
      NODE1=${NODES[$i]}
      NODE2=${NODES[$j]}

      T1=$(ssh $NODE1 date +%s%N)
      T2=$(ssh $NODE2 date +%s%N)

      SKEW=$(( (T2 - T1) / 1000000 ))  # Convert to ms

      if [ $SKEW -lt 0 ]; then
        SKEW=$(( -SKEW ))
      fi

      echo "$NODE1 <-> $NODE2: ${SKEW}ms"
    fi
  done
done
```

### 4.3 Common Issues and Fixes

**Issue 1: NTP not synchronizing**

```bash
# Symptom: "NTP synchronized: no"

# Fix: Restart NTP daemon
sudo systemctl restart chrony

# If still not syncing, check NTP server reachability:
ntpdate -d time.nist.gov

# If unreachable (firewall issue):
# 1. Check firewall rules (NTP uses UDP port 123)
sudo ufw allow 123/udp

# 2. Verify DNS resolution:
nslookup time.nist.gov

# 3. If still failing, use different NTP server:
sudo sed -i 's/time.nist.gov/time.google.com/' /etc/chrony/chrony.conf
sudo systemctl restart chrony
```

**Issue 2: Large clock drift (> 1 second)**

```bash
# Symptom: Cluster-wide failure with all timestamps rejected

# Immediate fix: Force step clock
sudo chronyc makestep  # or: sudo ntpdate -s time.nist.gov

# Verify fix:
timedatectl

# Root cause investigation:
# 1. Check if node was down (VM suspended/paused)
# 2. Check if NTP was stopped
# 3. Check for hardware clock battery issue (VM)

# Long-term fix: Ensure NTP daemon auto-starts
sudo systemctl enable chrony
```

**Issue 3: Periodic clock jumps (spikes every hour)**

```bash
# Symptom: Clock skew OK, then suddenly jumps to > 500ms

# Root cause: NTP server misconfiguration or frequent leap seconds

# Fix: Use multiple NTP servers and increase polling interval
# In /etc/chrony/chrony.conf:

pool time.nist.gov iburst maxsources 4
pool time.google.com iburst maxsources 4
pool time.cloudflare.com iburst maxsources 4

# Increase polling interval to smooth out jumps:
minpoll 6  # Start at 64 seconds
maxpoll 10 # Up to 1024 seconds
```

**Issue 4: Clock running fast (system time ahead)**

```bash
# Symptom: "System time X.XXXXXX seconds fast of NTP time"

# This is usually harmless, but if > 1 second:

# Fix: Increase correction rate
sudo chronyc makestep  # One-time correction

# If repeated: Check for:
# 1. NTP source quality (switch servers)
# 2. Kernel timer tuning (rare in modern systems)
# 3. VM clock drift (if virtualized, check host NTP)
```

---

## Part 5: Recovery Procedures

### 5.1 Single Node Out of Sync

**If one node's clock is >500ms off**:

```bash
# 1. Verify the problem
ssh problematic-node "timedatectl"

# 2. Force NTP sync
ssh problematic-node "sudo chronyc makestep"

# 3. Verify correction
ssh problematic-node "timedatectl"

# 4. Monitor Delos logs for recovery
ssh problematic-node "tail -f /var/log/delos/delos.log | grep -E 'timestamp|credential|identity'"

# Expected: After 30-60 seconds, node re-joins cluster

# 5. If still not recovering, restart Delos on the node
ssh problematic-node "sudo systemctl restart delos"

# 6. Verify recovery
curl http://problematic-node:8080/metrics | grep fireflies_members_count
# Should match peer node counts
```

### 5.2 Multiple Nodes Out of Sync

**If 2+ nodes have clock skew issues**:

```bash
# 1. Check NTP infrastructure
# On each node:
chronyc sources | grep "^#"  # NTP server status

# If NTP servers show offline, investigate network connectivity

# 2. Fix NTP on all nodes (in sequence, staggered 1-2 seconds apart)
for node in node-1 node-2 node-3; do
    ssh $node "sudo systemctl restart chrony"
    sleep 2
done

# 3. Verify all nodes synced
for node in node-1 node-2 node-3; do
    ssh $node "timedatectl | grep Synchronized"
done

# 4. Monitor cluster convergence (30-90 seconds)
watch 'curl -s http://localhost:8080/metrics | grep fireflies_'
```

### 5.3 Cluster-Wide Clock Skew (Cascading Failure)

**If entire cluster loses NTP sync (all nodes out of sync)**:

```bash
# 1. Verify problem
for node in node-1 node-2 node-3; do
    ssh $node "timedatectl | grep Synchronized"
done
# All should show "Synchronized: no"

# 2. Diagnose NTP infrastructure
# Check if NTP servers are reachable
ntpdate -q time.nist.gov
# OR
chronyc dns-name time.nist.gov

# 3. If NTP servers unreachable:
# Option A: Restore network connectivity to NTP servers
#   - Check firewall rules (UDP 123 open?)
#   - Check DNS resolution
#   - Check gateway/routing

# Option B: Temporary workaround (manual sync):
#   - On one node, set correct time:
#     sudo date -s "2026-01-27 10:30:45 UTC"
#   - Other nodes sync from this node:
#     ssh other-node "sudo ntpdate -s node-1"

# 4. Once one node is synced, others can sync from it
for node in node-2 node-3; do
    ssh $node "sudo ntpdate -s node-1"
done

# 5. Verify all nodes synced
for node in node-1 node-2 node-3; do
    ssh $node "timedatectl"
done

# 6. Restart Delos cluster (one node at a time)
for node in node-1 node-2 node-3; do
    ssh $node "sudo systemctl restart delos"
    sleep 30  # Wait for consensus to stabilize
done

# 7. Verify cluster recovered
curl http://localhost:8080/metrics | grep fireflies_members_count
# Should show all members active
```

---

## Part 6: Advanced Scenarios

### 6.1 Handling Leap Seconds

Leap seconds are occasional 1-second corrections to UTC (last one: June 2015).

**Configuration**:
```bash
# In /etc/chrony/chrony.conf
# Option 1: Slew leap second (smoothly add 1 second over time)
leapsecmode slew

# Option 2: Step leap second (jump 1 second instantaneously)
leapsecmode step

# Recommended: slew (less disruptive)
# Delos handles both, but slew is safer
```

### 6.2 VM Clock Drift

Virtual machines often have clock drift (host hardware clock affects guest).

**For VMware/Hyper-V/KVM**:
```bash
# Disable host time propagation (let NTP handle it)
# In VM settings: Disable "Sync guest time with host"

# Instead, use NTP exclusively:
sudo systemctl enable chrony
sudo systemctl start chrony

# Use multiple NTP sources (not host's clock)
# NTP sources should be external to the VM environment
```

**For AWS/GCP (where you don't control VM time)**:
```bash
# AWS and GCP handle host clock, just configure NTP inside VM
# Trust the cloud provider's time infrastructure

# Configure to use cloud provider's NTP:
# AWS: 169.254.169.123 (internal)
# GCP: metadata.google.internal (internal)
```

### 6.3 Monitoring During Clock Transitions

During DST (Daylight Saving Time) transitions:

```bash
# If your region observes DST:

# 1. Hour forward (spring): Lose 1 hour
# - NTP handles this gracefully
# - No action needed

# 2. Hour backward (fall): Gain 1 hour
# - NTP may need adjustment
# - Monitoring: Graphs may show step backward
# - No impact to Delos (NTP-managed)

# Recommended: Use UTC (no DST)
# On all nodes:
timedatectl set-timezone UTC
```

---

## References

- [FAILURE_MODES.md](FAILURE_MODES.md) - Section 6: Time-Based Failures
- [HARDWARE_REQUIREMENTS.md](HARDWARE_REQUIREMENTS.md) - Clock requirements
- [MONITORING_AND_ALERTING.md](MONITORING_AND_ALERTING.md) - Metrics setup
- [GLOSSARY.md](GLOSSARY.md) - Clock skew definition

---

## Quick Reference

### Commands

```bash
# Check time synchronization
timedatectl

# Check NTP daemon (Chrony)
systemctl status chrony
chronyc tracking
chronyc sources

# Force NTP sync
sudo chronyc makestep

# Set timezone to UTC
timedatectl set-timezone UTC

# Measure skew between nodes
ssh node-1 date +%s%N > /tmp/t1
ssh node-2 date +%s%N > /tmp/t2
echo "Skew (ns): $(( $(cat /tmp/t2) - $(cat /tmp/t1) ))"
```

### Key Thresholds

| Metric | Healthy | Warning | Critical |
|--------|---------|---------|----------|
| Clock skew | <50ms | 50-100ms | >100ms |
| NTP sync time | <10s | 10-30s | >30s |
| Credentials rejected | 0-1/min | 2-5/min | >10/min |

---

**Document Version**: 1.0
**Last Updated**: 2026-01-27
**Maintained By**: Delos Operations Team
