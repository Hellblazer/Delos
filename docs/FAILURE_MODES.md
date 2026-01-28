# Delos Failure Modes

**Comprehensive guide to failure modes, detection mechanisms, and recovery procedures in Byzantine distributed systems.**

**Audience**: System architects, operations teams, security engineers
**Version**: 1.0
**Last Updated**: 2026-01-27

---

## Overview

Delos is architected to detect, handle, and recover from multiple classes of failures:
- **Transient failures**: Temporary network issues, GC pauses, temporary unavailability
- **Permanent failures**: Node crashes, permanent network partition, disk failure
- **Byzantine failures**: Malicious/compromised nodes, arbitrary behavior, data corruption
- **Cascading failures**: Multiple failures in sequence or combination

This document describes how each failure mode is detected, its impact on system operation, and recovery procedures.

---

## Failure Classification

### By Scope

| Scope | Impact | Examples |
|-------|--------|----------|
| **Single Member** | Local recovery | Node crash, disk I/O error, key loss |
| **Multiple Members** | Quorum assessment | Network partition, mass GC pause |
| **Quorum Loss** | System stops | 2+ failures in 7-node cluster (f=2) |
| **Byzantine Subset** | Safety concerns | 3+ Byzantine members in 7-node cluster |
| **Systemic** | Complete failure | Clock skew cluster-wide, total network partition |

### By Origin

| Origin | Detection | Handler |
|--------|-----------|---------|
| **Network** | Timeout, gossip failure | Fireflies failure detection |
| **Compute** | Exception, timeout | Application error handler, fallback to safe state |
| **Storage** | I/O error, verification | Database layer, WAL recovery |
| **Byzantine** | Inconsistency, signature failure | Quorum protocol, isolation |
| **Time** | Timestamp out of range | Clock skew detection, message rejection |

---

## 1. Node/Member Failures

### 1.1 Single Node Crash

**Description**: A single node becomes permanently unavailable (hardware failure, OOM kill, forced restart).

**Detection**:
- Gossip communication fails repeatedly
- Fireflies phi-accrual failure detector triggers (5-30 seconds typical)
- Node stops publishing metrics/heartbeats
- Member marked as suspected → accused → shunned

**System Behavior**:
- Gossip partners select new members via reservoir sampling
- View change triggered if node was active member
- Remaining nodes continue consensus without failed node
- No data loss if not part of quorum subset

**Impact** (7-node cluster, f=2):
- After 1 failure: Still safe (4/7 honest). Continue normal operation.
- After 2 failures: Still safe (5/7 honest). Continue normal operation.
- After 3 failures: Quorum lost (4/7). System stops accepting new requests.

**Recovery**:
1. Identify root cause (disk full, OOM, power loss, upgrade)
2. Restart node or replace failed hardware
3. Node rejoins via Binding protocol (join with 2-phase BFT join)
4. State synchronized via gossip during join
5. Member automatically included in next view change

**Prevention**:
- Monitor `fireflies_node_resource_warnings` (memory, disk)
- Set up automated restarts via systemd Restart=always
- Configure JVM heap appropriately (see PERFORMANCE_TUNING.md)

---

### 1.2 Cascading Node Failures

**Description**: Multiple nodes fail in sequence (e.g., rolling restart gone wrong, resource exhaustion cascade).

**Detection**:
- Multiple nodes suspected in quick succession
- View change rate increases (multiple view changes per minute)
- Consensus commits slow or stall

**System Behavior**:
- Each failure reduces voting subset cardinality
- Quorum requirements remain: need (n - f) members with f failures
- If failures exceed f, system stops accepting new work
- Existing consensus continues for already-proposed blocks

**Impact** (7-node cluster, f=2):
- 1 failure: Normal operation (4/7 honest remain)
- 2 failures: Degraded (5/7 honest remain, slower consensus)
- 3 failures: QUORUM LOST - system halts

**Recovery**:
1. **Immediately**: Stop additional restarts/changes
2. **Within 5 minutes**: Restart failed nodes (don't cascade more failures)
3. **Within 30 minutes**: Verify all nodes back online
4. **Monitor**: Consensus catch-up (blocks recommitting)
5. **Verify**: No state divergence (block hashes match)

**Prevention**:
- Stagger restarts: 60-90 seconds between node starts
- Monitor resource utilization before maintenance
- Test upgrade procedures in staging first

---

### 1.3 Permanent Member (Key) Loss

**Description**: A member permanently loses its identity key (e.g., HSM destroyed, key store corrupted).

**Detection**:
- Member cannot sign operations (cryptographic verification fails)
- Member cannot participate in consensus (votes rejected)
- Signature verification failures on metrics/heartbeats
- Member marked as Byzantine (if attempting to use wrong key)

**System Behavior**:
- Other nodes reject operations from compromised member
- Member automatically suspected and shunned
- Remaining honest members exclude this member from voting
- If member tries to participate with invalid key, marked Byzantine

**Impact** (7-node cluster, f=2):
- After key loss: Treated as 1 Byzantine member (system can tolerate 2 Byzantine)
- Member must be replaced/recovered

**Recovery**:
1. **Immediate**: Remove node from cluster (don't let it send invalid operations)
2. **Options**:
   - Option A: Restore key from backup (if available)
   - Option B: Remove node permanently, replace with new member
   - Option C: Re-issue identity via Gorgoneion (if trusted witness available)
3. **Verify**: All nodes reject old member identity
4. **Add**: New member via Binding protocol

**Prevention**:
- Back up identity keys (see DISASTER_RECOVERY.md)
- Use HSM with redundant key storage
- Test key recovery procedures quarterly

---

## 2. Network Failures

### 2.1 Network Partition

**Description**: Network split where subset of nodes can't reach other subset (e.g., switch failure, routing misconfiguration).

**Scenario A: Symmetric Partition** (two isolated groups)
```
Group A: Nodes 1, 2, 3  [Can communicate internally]
Group B: Nodes 4, 5, 6, 7  [Can communicate internally]
Network: No communication between groups
```

**Detection**:
- Nodes in Group A can't reach nodes in Group B (TCP timeouts)
- Fireflies failure detection on both sides
- View ID mismatch (different views on each side)
- Consensus doesn't progress (can't reach quorum if split fails f<n/3)

**System Behavior** - **Safety always maintained**:
- Group with quorum (4+/7 nodes): Continues consensus (conservative - may not have quorum)
- Group without quorum (3 or fewer): Stops accepting new requests
- Nodes detect partition via view ID mismatch, log warning

**Impact** (7-node cluster, f=2):
- 4-node partition: Majority quorum available, consensus continues
- 3-node partition: No quorum, system halts
- Either partition: No cross-partition communication (safety maintained)

**Recovery**:
1. **Identify cause**: Network infrastructure logs, switch port status
2. **Restore connectivity**: Fix switch, restore routing, troubleshoot firewall
3. **Reconciliation** (automatic):
   - Fireflies detects restored connectivity (gossip succeeds)
   - Views reconcile (higher view number wins)
   - Minority partition accepts view from majority partition
   - Consensus resumes with unified view

**Prevention**:
- Network redundancy: Multiple ISP/network providers
- Monitoring: Alert on packet loss >1%, latency >500ms
- Circuit breakers: Nodes with high latency to others are suspected

---

### 2.2 Network Congestion / High Latency

**Description**: Network links congested (high latency, increased jitter, packet loss).

**Detection**:
- RPC latencies increase (p95 > 200ms, p99 > 500ms)
- Gossip rounds take longer (gossip_duration increases)
- Consensus commits slow (block_commit_latency increases)
- Timeout-based accusations increase (phi-accrual sensitivity)

**System Behavior**:
- Consensus continues but slower (safety maintained)
- May trigger false suspicions if latency > timeout
- Nodes may be falsely accused and shunned if latency too high
- Message ordering maintained via total-order consensus

**Impact** (on 7-node cluster):
- p95 latency < 100ms: No impact
- p95 latency 100-200ms: Slow consensus (2-3s instead of 1s)
- p95 latency > 500ms: Risk of false accusations, instability
- Packet loss > 5%: Effective latency increases (retransmits)

**Recovery**:
1. **Immediate**: Increase timeout thresholds (conservative approach)
2. **Short-term**:
   - Reduce gossip fanout (fewer partners per round)
   - Increase batch sizes (amortize latency over more data)
   - Reduce poll frequencies (metrics, health checks)
3. **Long-term**: Upgrade network infrastructure

**Prevention**:
- Network QoS: Prioritize Delos traffic
- Capacity planning: Design for 2x expected traffic
- Monitoring: Alert on latency > 200ms median

---

### 2.3 Asymmetric Communication Failure

**Description**: A→B works but B→A fails (e.g., firewall rule, router misconfiguration).

**Detection**:
- Member A receives gossip from B (proves B→A works)
- Member A can't send gossip to B (B unreachable on outbound)
- Fireflies detects one-way communication, suspects member
- Both A and B log asymmetric communication warning

**System Behavior**:
- Member A suspects B (can receive but not send)
- Member B doesn't suspect A (not receiving A's gossip)
- System tolerates briefly (Fireflies has rebuttal window)
- If persistent, both members eventually suspected

**Impact**:
- Temporary (< 5 seconds): Likely rebutted, no view change
- Sustained (> 5 seconds): Both members accumulate accusations, view change triggered
- One of both members shunned

**Recovery**:
1. **Immediate**: Check firewall rules, network ACLs
2. **Verify bidirectional communication**:
   ```bash
   # From Node A
   nc -zv <node-b-ip> 9090  # Should succeed

   # From Node B
   nc -zv <node-a-ip> 9090  # Should succeed
   ```
3. **Fix**: Adjust firewall, verify routing on both sides
4. **Automatic reconciliation**: Members rejoin when bidirectional communication restored

**Prevention**:
- Network testing: Verify bidirectional connectivity before deployment
- Monitoring: Alert on asymmetric latencies (A→B latency differs from B→A)

---

## 3. Consensus Failures

### 3.1 Consensus Deadlock

**Description**: All nodes become stuck, no new blocks commit, existing blocks don't advance.

**Detection**:
- `choam_blocks_committed` metric stops increasing (0/min for > 60 seconds)
- `ethereal_dag_units` increases but `blocks_committed` doesn't
- All nodes report same block height for > 60 seconds

**Root Causes** (check in order):
1. **Quorum loss** (3+ member failures) → See section 1
2. **All nodes Byzantine** (impossible) → Not a real failure
3. **Consensus protocol bug** (rare) → See recovery
4. **Message delivery failure** → See network section
5. **Clock skew cluster-wide** → See section 4

**System Behavior**:
- All honest nodes follow consensus protocol correctly
- System halts if quorum lost or Byzantine conditions
- No safety violation (no contradictory state)
- No liveness guarantee (system may not progress)

**Recovery**:
1. **Assess member status**:
   ```bash
   # Check node health (all nodes)
   curl http://localhost:8080/health
   curl http://localhost:8080/metrics | grep choam_blocks
   ```

2. **Count active members**:
   ```bash
   # Should be 4+ in 7-node cluster (3f+1 where f=2)
   curl http://localhost:8080/metrics | grep fireflies_members
   ```

3. **If quorum lost** (< 4 members): Restart failed nodes (section 1.2)

4. **If quorum present** (4+ members):
   - Check network connectivity (section 2)
   - Check clock skew (section 4)
   - Review logs for consensus protocol messages
   - Force view change: `POST /consensus/force-view-change` (expert tool only)

**Prevention**:
- Monitor consensus frequently (every 10 seconds)
- Alert on commit rate = 0 for > 30 seconds
- Set up automated recovery: restart if no progress for 5 minutes

---

### 3.2 View Change Storms

**Description**: Excessive view changes occur (> 1 per second), preventing consensus progress.

**Detection**:
- `fireflies_view_changes` metric increasing rapidly (> 1/sec)
- `choam_blocks_committed` rate drops to near zero
- Logs show "View change initiated" repeatedly

**Root Causes**:
1. **High latency/packet loss** → Members timeout waiting for gossip (section 2.2)
2. **Byzantine member** → Causing repeated accusations/rebuttals
3. **Clock skew** → Members rejecting each other's timestamps (section 4)
4. **Threshold misconfiguration** → Phi-accrual threshold too low

**System Behavior**:
- View change protocol executes repeatedly
- Each view lasts only seconds before next change triggered
- No blocks committed (consensus can't advance)
- Members cycling through views

**Recovery**:
1. **Immediately**:
   - Increase phi-accrual threshold (conservatively)
   - Increase timeouts (give more time before suspicion)

2. **Assess root cause**:
   - Check `gossip_duration` (if > 100ms, latency issue)
   - Check clock skew (section 4)
   - Check for Byzantine member (weird vote patterns in logs)

3. **Fix**:
   - If latency: Upgrade network or increase timeouts
   - If Byzantine: Identify and remove member
   - If clock skew: Resync clocks

**Prevention**:
- Alert on view change rate > 1 per minute
- Monitor latency/clock skew continuously
- Test timeout thresholds before deployment

---

## 4. Storage/Database Failures

### 4.1 Disk Full

**Description**: Disk where database, WAL, or checkpoints stored becomes full.

**Detection**:
- I/O errors in logs: "No space left on device"
- `disk_usage_percent` metric reaches 95%+
- Database stops accepting writes

**System Behavior**:
- Existing reads continue (blocks already in memory or cache)
- New block writes fail (transactions can't commit)
- Consensus stops (can't persist new blocks)
- Node becomes unresponsive (I/O wait high)

**Impact**:
- Immediate: This node stops participating in consensus
- Cascade: If multiple nodes full, quorum may be lost

**Recovery** (Priority: DO IMMEDIATELY):
1. **Free space immediately**:
   ```bash
   # Check disk usage
   df -h /var/delos

   # Identify large files
   du -sh /var/delos/* | sort -h

   # Remove old checkpoints (if > 2 copies)
   rm /var/delos/checkpoints/checkpoint-*.db
   ```

2. **Restart node**:
   ```bash
   systemctl restart delos
   ```

3. **Verify recovery**:
   ```bash
   # Check consensus resumption
   curl http://localhost:8080/metrics | grep choam_blocks_committed
   ```

4. **Long-term**: Add disk capacity or reduce retention

**Prevention**:
- Alert at 80% disk usage
- Monitor disk growth rate (set capacity planning threshold)
- Automate checkpoint cleanup (keep only 2 most recent)

---

### 4.2 Database Corruption

**Description**: Database files corrupted (disk error, power loss during write, bug).

**Detection**:
- Database fails to open on startup
- Block hash verification fails (computed hash ≠ stored hash)
- SQL execution error: "database disk image is malformed"
- State divergence detected (this node's state differs from peers)

**System Behavior**:
- If detected on startup: Node fails to start
- If detected after start: State queries fail, consensus can't verify blocks
- Node can't participate reliably (other nodes suspect its responses)

**Recovery**:
1. **Assess damage**:
   - Check if node can start: `systemctl start delos`
   - If starts but errors on queries: Corruption detected

2. **Restore from backup** (recommended for critical corruption):
   - Stop the node: `systemctl stop delos`
   - Restore from last good backup: See DISASTER_RECOVERY.md
   - Verify: `choam_blocks_committed` matches peers

3. **Or: Rejoin cluster** (if corruption not critical):
   - Delete corrupted database: `rm /var/delos/state.db`
   - Restart node: `systemctl start delos`
   - Node rejoins, re-downloads state from peers
   - Monitor for divergence

**Prevention**:
- Regular backups (hourly incremental, daily full)
- Verify backups (restore to test machine monthly)
- UPS protection (prevent sudden power loss)
- Database checksums (H2 has them enabled)

---

### 4.3 Checkpoint Failure

**Description**: Checkpoint creation fails or checkpoint becomes corrupted.

**Detection**:
- Checkpoint process exits with error
- `choam_checkpoint_height` metric doesn't increase
- Old logs accumulate (not garbage collected due to failed checkpoint)
- Disk usage grows rapidly

**System Behavior**:
- Consensus continues (checkpoints are optimization, not required)
- Old logs preserved (for replay/recovery)
- Disk usage eventually fills up (cascades to section 4.1)
- Startup time increases (more logs to replay)

**Recovery**:
1. **Immediate**: Trigger manual checkpoint
   ```bash
   POST /state/checkpoint
   ```

2. **If checkpoint succeeds**: Monitor disk usage (should decrease as logs GC'd)

3. **If checkpoint fails**:
   - Check disk space (section 4.1)
   - Check database status (section 4.2)
   - Check permissions on checkpoint directory

4. **If persistent**: Restore to known-good state from backup

**Prevention**:
- Alert on checkpoint failures (any checkpoint attempt that fails)
- Monitor checkpoint frequency (should run every hour)
- Test checkpoint restore quarterly

---

## 5. Byzantine/Malicious Failures

### 5.1 Malicious Member (Arbitrary Behavior)

**Description**: A member node is compromised and behaves arbitrarily (corrupt data, send false messages, equivocate).

**Detection**:
- Member sends conflicting values for same key (equivocation)
- Member sends unsigned messages (signature verification fails)
- Member's state doesn't match rest of cluster
- Cryptographic verification fails on member's operations
- Member proposes invalid state transitions

**System Behavior** - **Safety always maintained**:
- Fireflies: Invalid signatures rejected, member shunned
- CHOAM: Invalid state transitions rejected via quorum protocol
- Ethereal: DAG must include honest member messages (f < n/3 guarantee)
- SQL-State: Deterministic verification fails, block rejected

**For 7-node cluster with f=2 Byzantine tolerance**:
- 1 Byzantine member: Tolerated (4 honest/correct remain)
- 2 Byzantine members: Tolerated (5 honest/correct remain)
- 3+ Byzantine members: Safety may not hold

**Recovery**:
1. **Identify compromised member**:
   - Look for signature failures in logs
   - Look for state divergence alerts
   - Check member's operations for inconsistency

2. **Isolate member**:
   - Block member at firewall (if needed)
   - Do NOT let it continue participating

3. **Remove member**:
   - Request cluster to remove via admin interface
   - Or: Shunning protocol (accuse, member fails to rebut)

4. **Audit damage**:
   - Check if state was corrupted
   - Verify all blocks via hashes with honest members
   - Restore from backup if state corrupted

**Prevention**:
- Secure deployment: Harden OS, enable security modules
- Key protection: HSM or PKCS11 for identity keys
- Monitoring: Alert on signature failures, state divergence
- Incident response: Plan for member replacement

---

### 5.2 Byzantine Consensus Violation Attempt

**Description**: Byzantine member attempts to violate consensus (e.g., create conflicting blocks at same height).

**Detection**:
- Ethereal detects conflicting DAG branches
- Two different blocks proposed for same height
- Quorum protocol counts votes, one block wins
- Minority block ignored by consensus

**System Behavior** - **Safety maintained by Byzantine quorum protocol**:
- Maximum f Byzantine members can propose invalid values
- Quorum requirement ensures ≥ 1 honest member in every quorum
- Honest majority always agrees on safe value
- Invalid block ignored

**Recovery**:
- Automatic: Byzantine quorum protocol handles this
- No recovery needed (consensus continues safely)
- Monitor for frequent Byzantine attempts (possible compromise)

**Prevention**:
- Monitoring: Alert on conflicting blocks detected
- If frequent: Audit member node for compromise

---

### 5.3 Replay Attack (Identity Reuse)

**Description**: Attacker reuses old valid identity to claim membership.

**Detection**:
- Member tries to rejoin with old KERL (rotated in past)
- Timestamp verification fails (old KERL has stale timestamp)
- Gorgoneion attestation fails (old identity not in current witness set)
- Signature verification shows old key (not current key)

**System Behavior**:
- Fireflies Join protocol requires BFT quorum agreement on identity
- Gorgoneion witness verifies freshness of KERL
- Old identity rejected (not in current view)
- Attack fails (safety maintained)

**Recovery**:
- Automatic: Replay attack detected and rejected
- Monitor logs for replay attempts (possible security probe)

---

## 6. Time-Based Failures

### 6.1 Clock Skew (Single Member)

**Description**: Single member's clock drifts significantly from cluster (> clockSkewTolerance, typically 500ms).

**Detection**:
- Member's timestamp outside acceptable range
- Messages from member rejected as "timestamp out of range"
- Member's credentials fail validation (wrong timestamp)
- Member can't participate in Gorgoneion attestation

**System Behavior**:
- Member's messages rejected (safety maintained)
- Member can't vote or participate effectively
- Member becomes suspect (no responses accepted)
- Eventually shunned

**Recovery**:
1. **Immediate**: Resync member's clock
   ```bash
   # Linux
   sudo systemctl restart chrony
   # or
   sudo ntpdate -s time.nist.gov
   ```

2. **Verify**:
   ```bash
   # Check clock vs. peers
   date && ssh node-2 date && ssh node-3 date
   # All should show time within 100ms of each other
   ```

3. **Restart node** (if still failing):
   ```bash
   systemctl restart delos
   ```

**Prevention**:
- NTP/Chrony: Configure on all nodes
- Alert on clock skew > 100ms
- Test clock synchronization in pre-deployment checklist

---

### 6.2 Cluster-Wide Clock Skew

**Description**: Multiple/all members' clocks drift in same direction (e.g., NTP server failure, deliberate attack).

**Detection**:
- All members' timestamps outside acceptable range
- All members' Gorgoneion credentials fail validation
- Identity rotation rejects all members (wrong timestamps)
- Entire cluster can't participate in consensus

**System Behavior**:
- Entire cluster stops (all timestamps rejected)
- Safety maintained (no contradictory state possible)
- Liveness lost (no progress possible)

**Recovery**:
1. **Fix NTP cluster-wide**:
   ```bash
   # On all nodes
   sudo systemctl restart chrony
   # Verify NTP servers reachable
   chronyc sources
   ```

2. **Manual resync if needed**:
   ```bash
   # On all nodes (in sequence, wait 1-2 seconds between)
   sudo ntpdate -s time.google.com
   ```

3. **Restart all nodes** (in sequence):
   ```bash
   # Node 1
   ssh node-1 'systemctl restart delos'
   sleep 30
   # Node 2
   ssh node-2 'systemctl restart delos'
   sleep 30
   # ... repeat for all nodes
   ```

4. **Verify**: Cluster recovers consensus after all nodes restarted

**Prevention**:
- Redundant NTP: Multiple NTP servers, prefer major public servers
- Local NTP server: Consider running chrony as server for nodes
- Monitoring: Alert if any node's clock skew > 50ms
- Pre-deployment: Verify clock sync across all nodes before go-live

---

## 7. Cascading and Complex Failure Scenarios

### 7.1 Multiple Failures + Network Partition

**Scenario**:
```
1 node crash + network partition splits cluster 2 ways
Remaining: 6 nodes
Partition A: 3 nodes (1 honest, 1 Byzantine, 1 recovering from crash)
Partition B: 3 nodes (2 honest)
```

**Analysis**:
- **Partition A** (3 nodes): Quorum = 5 needed. This partition has NO quorum. STOPS.
- **Partition B** (3 nodes): Quorum = 5 needed. This partition has NO quorum. STOPS.
- **Entire system**: Halts. Safety maintained (no progress).

**Recovery**:
1. Restart crashed node → 7 nodes available
2. Repair network partition → Single cluster again
3. Automatic recovery → Consensus resumes

---

### 7.2 Progressive Byzantine Member Growth

**Scenario**:
```
t=0: 0 Byzantine members (7 safe)
t=1: 1 Byzantine detected, starts behaving badly
t=2: 1 additional member compromised (2 Byzantine)
t=3: A 3rd member shows Byzantine behavior
```

**Impact**:
- t=0-2: System safe (f=2, tolerates up to 2 Byzantine)
- **t=3: SAFETY VIOLATION POSSIBLE** (3 Byzantine > f=2)

**Detection**:
- If detected at t=3: System has Byzantine members >= quorum threshold
- Alerts on Byzantine member count

**Recovery**:
1. **Immediately**: Isolate suspected Byzantine members
2. **Audit**: Determine if safety already violated
3. **If safety violated**: Restore from backup (only safe approach)
4. **Identify compromise**: Security audit of all member nodes
5. **Recovery**: Replace all compromised nodes, redeploy from scratch

**Prevention**:
- Alert if Byzantine member count ≥ f
- Security monitoring: Detect compromise early
- Regular security audits: Validate member nodes

---

## Response Procedures Quick Reference

| Failure | Time to Detect | Time to Fix | Action |
|---------|----------------|-----------|--------|
| Single node crash | 5-30 sec | 1-5 min | Restart node |
| Network partition | 1-10 sec | 5-30 min | Fix network |
| Disk full | 1 min | 1-5 min | Free space |
| Clock skew | 10 sec | 1-2 min | Resync NTP |
| Consensus deadlock | 30-60 sec | 5-30 min | Diagnose quorum/network |
| Byzantine member | 1-5 min | 5-10 min | Isolate + remove |
| Disk corruption | 10-30 sec | 10-30 min | Restore backup |

---

## Safety Invariants (Always Maintained)

1. **Quorum Intersection**: Any two quorums must have at least 1 honest member in common
   - Guarantees: No two conflicting values can both be accepted by separate quorums
   - Maintained by: f < n/3 Byzantine tolerance (need 2f+1 honest in any quorum)

2. **Total Ordering**: Blocks committed at height H are identical on all honest nodes
   - Guarantees: No state divergence
   - Maintained by: Ethereal consensus protocol + Deterministic SM execution

3. **Signature Verification**: All operations signed by verified identity
   - Guarantees: No spoofing, no unsigned operations
   - Maintained by: ED25519 signatures + KERI identity verification

4. **Deterministic Execution**: Same input sequence → same output on all nodes
   - Guarantees: States stay synchronized
   - Maintained by: Deterministic SQL execution + frozen clock

---

## Monitoring for Early Detection

**Key metrics to monitor continuously**:

1. **Member health**:
   - `fireflies_members_count` (should be stable)
   - `fireflies_suspected_count` (should be 0 or very low)
   - `fireflies_failed_count` (should be 0)

2. **Consensus progress**:
   - `choam_blocks_committed` (should increase 1-10/sec)
   - `choam_blocks_committed_rate` (should be > 0)
   - `ethereal_dag_units` (should increase with blocks)

3. **Network health**:
   - `rpc_latency_p95` (should be < 100ms)
   - `rpc_latency_p99` (should be < 200ms)
   - `gossip_duration_p95` (should be < 100ms)

4. **Resource usage**:
   - `jvm_memory_usage_percent` (alert if > 85%)
   - `disk_usage_percent` (alert if > 80%)
   - `cpu_usage_percent` (alert if sustained > 70%)

5. **Time health**:
   - `clock_skew_ms` (should be < 50ms)
   - `peer_clock_skew_ms` (should be < 100ms for all peers)

6. **Byzantine detection**:
   - `byzantine_member_count` (alert if > 0)
   - `signature_verification_failures` (alert if > 0)
   - `state_divergence_detected` (alert immediately)

---

## References

- [SECURITY_THREAT_MODEL.md](SECURITY_THREAT_MODEL.md) - Byzantine threat model
- [TROUBLESHOOTING_GUIDE.md](TROUBLESHOOTING_GUIDE.md) - Symptom-based troubleshooting
- [DISASTER_RECOVERY.md](DISASTER_RECOVERY.md) - Backup and restore procedures
- [MONITORING_AND_ALERTING.md](MONITORING_AND_ALERTING.md) - Metrics and dashboards
- [Ethereal README](../ethereal/README.md) - Consensus protocol
- [Fireflies README](../fireflies/README.md) - Membership service

---

**Document Version**: 1.0
**Last Updated**: 2026-01-27
**Author**: Delos Documentation Team
