# Operational Procedures & Runbooks

Step-by-step procedures for running Delos clusters in production. All procedures include success criteria and rollback steps.

**Status**: Production Ready
**Last Updated**: 2026-01-09
**Audience**: Operations teams, SREs, DevOps engineers

---

## Table of Contents

1. [Quick Reference](#quick-reference)
2. [Day-to-Day Operations](#day-to-day-operations)
3. [Essential Runbooks](#essential-runbooks)
4. [Service Management](#service-management)
5. [Health Checks](#health-checks)
6. [Maintenance Tasks](#maintenance-tasks)
7. [Operational Checklists](#operational-checklists)

---

## Quick Reference

### Emergency Commands

| Issue | Command | Effect |
|-------|---------|--------|
| **Node won't start** | `journalctl -u delos -n 50 -e` | View recent error logs |
| **Node stuck** | `kill -SIGTERM <PID>` | Graceful shutdown attempt |
| **Cluster offline** | `curl http://node:8080/health` | Check node health status |
| **Memory leak suspect** | `jcmd <PID> GC.heap_dump /tmp/dump.hprof` | Dump heap for analysis |
| **Network problem** | `ping -c5 other-node` | Test connectivity |

### Common Ports

| Port | Service | Purpose |
|------|---------|---------|
| 8080 | HTTP Health | Status, metrics (/health, /metrics) |
| 8443 | HTTPS | Secured endpoints (with TLS) |
| 9090 | Gossip | Internal cluster communication |
| 9091 | Consensus | CHOAM consensus messages |
| 9092 | GRPC | Stereotomy KERI services |
| 5432 | JDBC | SQL-State database (if external) |

---

## Day-to-Day Operations

### Starting Your Cluster

**Initial Setup** (first time):
```bash
# 1. Ensure data directory exists
sudo mkdir -p /var/lib/delos /var/log/delos
sudo chown delos:delos /var/lib/delos /var/log/delos

# 2. Copy configuration
sudo cp delos.conf /etc/delos/

# 3. Enable systemd service
sudo systemctl enable delos
```

**Start Node**:
```bash
# Start single node
sudo systemctl start delos

# Verify startup
sleep 5 && journalctl -u delos -n 20
sudo systemctl status delos  # Should show "active (running)"
```

**Verify Cluster Formation**:
```bash
# Wait for quorum (up to 30 seconds)
for i in {1..6}; do
  echo "Checking cluster status (attempt $i/6)..."
  curl -s http://localhost:8080/health | grep -q "HEALTHY" && break
  sleep 5
done

# Verify all nodes joined
curl -s http://localhost:8080/members | jq '.members | length'
# Should show: 4 (for 4-node cluster)
```

**Success Criteria**:
- ✅ Systemd shows "active (running)"
- ✅ Health endpoint returns "HEALTHY"
- ✅ All expected members visible
- ✅ No errors in journalctl

---

## Essential Runbooks

### RB-01: Start Single Node

**When**: Initial node startup, restart after crash
**Duration**: 2-5 minutes
**Risk**: Low (standalone)

**Steps**:

1. **Verify prerequisites**
   ```bash
   # Check disk space (need > 100GB free)
   df -h /var/lib/delos | awk 'NR==2 {print $4}'

   # Check Java installation
   java -version

   # Check config exists
   test -f /etc/delos/delos.conf && echo "Config OK"
   ```

2. **Start service**
   ```bash
   sudo systemctl start delos
   ```

3. **Verify startup**
   ```bash
   # Wait up to 30 seconds for ready
   for i in {1..6}; do
     STATUS=$(curl -s http://localhost:8080/health 2>/dev/null | grep -o HEALTHY)
     if [ "$STATUS" = "HEALTHY" ]; then
       echo "Node ready after ${i}0 seconds"
       break
     fi
     sleep 5
   done
   ```

4. **Check logs**
   ```bash
   journalctl -u delos -n 20 | grep -E "ERROR|WARN|started"
   ```

**Success Criteria**:
- ✅ Systemd status shows "active (running)"
- ✅ Health endpoint returns 200 with "HEALTHY"
- ✅ No ERROR entries in recent logs
- ✅ Port 9090 listening (`lsof -i :9090`)

**Rollback**:
```bash
sudo systemctl stop delos
```

---

### RB-02: Add Node to Running Cluster

**When**: Scaling cluster, replacing node
**Duration**: 5-10 minutes
**Risk**: Low (cluster continues, new node joins quorum)

**Prerequisites**:
- Cluster must have quorum (> 2/3 nodes healthy)
- New node must have identical config (except node ID)

**Steps**:

1. **Prepare new node**
   ```bash
   # Assign unique node ID
   NODE_ID=$(hostname -s)

   # Create empty data directory
   sudo mkdir -p /var/lib/delos/${NODE_ID}
   sudo chown delos:delos /var/lib/delos/${NODE_ID}
   ```

2. **Update configuration**
   ```bash
   # Set node ID
   sed -i "s/^node.id=.*/node.id=${NODE_ID}/" /etc/delos/delos.conf

   # Add to member list (bootstrap)
   # Append to cluster.members:
   # node.id=${NODE_ID},host=<ip>,port=9090
   ```

3. **Start new node**
   ```bash
   sudo systemctl start delos
   ```

4. **Monitor join process**
   ```bash
   # Watch logs until "Member joined cluster"
   journalctl -u delos -f | grep -E "Member|Quorum|joined"

   # Or check member count increasing
   for i in {1..10}; do
     COUNT=$(curl -s http://localhost:8080/members | jq '.members | length')
     echo "Members: $COUNT"
     sleep 1
   done
   ```

5. **Verify state sync**
   ```bash
   # Check catch-up progress (P state sync)
   curl -s http://localhost:8081/metrics | grep replication_lag_ms
   # Should converge to < 100ms
   ```

**Success Criteria**:
- ✅ Member count increased by 1
- ✅ Catch-up lag < 100ms
- ✅ Node logs show "Member joined"
- ✅ Health endpoint shows HEALTHY

**Troubleshooting**:
- If "Cannot find any members": Check network, firewall, bootstrap config
- If "Consensus timeout": Cluster may have lost quorum, check other nodes
- If "State sync stalled": Check disk space, network bandwidth

**Rollback**:
```bash
# Remove from cluster gracefully
# (See RB-03 for removal procedure)
sudo systemctl stop delos
```

---

### RB-03: Remove Node from Cluster

**When**: Maintenance, scale down, replacing node
**Duration**: 5 minutes
**Risk**: Low if cluster has quorum

**Prerequisites**:
- Cluster must have quorum even after removal

**Steps**:

1. **Drain ongoing operations** (optional but recommended)
   ```bash
   # Stop accepting new transactions (application level)
   # Or proceed if can tolerate temporary errors
   ```

2. **Gracefully stop node**
   ```bash
   # Signal node to shutdown
   sudo systemctl stop delos
   ```

3. **Wait for quorum adjustment**
   ```bash
   # Remaining nodes detect removal (ballot timeout)
   # Takes up to BALLOT_TIMEOUT seconds (default 5 sec)
   sleep 10
   ```

4. **Verify removal**
   ```bash
   # Check member count decreased
   curl -s http://other-node:8080/members | jq '.members | length'

   # Check logs for "Member removed" or "Member failed"
   ssh other-node "journalctl -u delos -n 50 | grep -E 'removed|left'"
   ```

**Success Criteria**:
- ✅ Member count decreased
- ✅ Cluster remains healthy
- ✅ Other nodes show "Member removed"
- ✅ No failed transactions (unless testing failures)

**Troubleshooting**:
- If cluster becomes unhealthy: Restart node to restore quorum
- If state divergence: Run state sync procedure

**Rollback**:
```bash
# If need to restore node:
sudo systemctl start delos
# Monitor as in RB-02
```

---

### RB-04: Perform Graceful Shutdown

**When**: Maintenance window, server decommissioning
**Duration**: 2-5 minutes
**Risk**: Low (planned, all nodes can shutdown sequentially)

**Steps**:

1. **Notify applications** (if coordinating)
   ```bash
   # Application should stop sending new transactions
   # Or implement circuit breaker to wait for cluster
   ```

2. **Shutdown nodes sequentially** (one at a time)
   ```bash
   # For each node, starting with non-leaders:
   NODE_ID=<node>
   ssh node-$NODE_ID "sudo systemctl stop delos"
   sleep 10  # Wait for cluster to rebalance

   # Verify remaining cluster healthy
   curl -s http://remaining-node:8080/health
   ```

3. **Shutdown final node**
   ```bash
   # Last node stops naturally when quorum lost
   ssh last-node "sudo systemctl stop delos"
   ```

**Success Criteria**:
- ✅ All nodes stopped cleanly
- ✅ No error entries in shutdown logs
- ✅ Data persisted to disk (checkpoints created)

**Recovery**:
```bash
# Restart in reverse order
for NODE in node-4 node-3 node-2 node-1; do
  ssh $NODE "sudo systemctl start delos"
  sleep 5
done
```

---

### RB-05: Rolling Update (Zero-Downtime)

**When**: Software upgrade, configuration change
**Duration**: N × 5 minutes (where N = number of nodes)
**Risk**: Medium (cluster in reduced capacity during update)

**Prerequisites**:
- Backup latest checkpoint
- Change must be compatible (no data format changes)
- At least 1/3 of nodes must remain available during update

**Steps**:

1. **Update one node at a time** (start with non-leaders)
   ```bash
   for NODE_ID in node-2 node-3 node-4; do
     echo "Updating $NODE_ID..."

     # Stop node
     ssh $NODE_ID "sudo systemctl stop delos"

     # Upgrade binary/config
     ssh $NODE_ID "sudo cp new-delos.jar /opt/delos/"

     # Start updated node
     ssh $NODE_ID "sudo systemctl start delos"

     # Wait for catch-up
     sleep 15

     # Verify health
     curl -s http://$NODE_ID:8080/health | grep HEALTHY
   done
   ```

2. **Update leader last**
   ```bash
   # Identify leader (highest ballot number)
   LEADER=$(curl -s http://node-1:8080/members | jq -r '.leader_id')

   # Wait for stable state
   sleep 30

   # Stop leader (triggers new leader election)
   ssh $LEADER "sudo systemctl stop delos"

   # Update leader
   ssh $LEADER "sudo cp new-delos.jar /opt/delos/"

   # Restart leader
   ssh $LEADER "sudo systemctl start delos"

   # Wait for rejoin
   sleep 15
   ```

3. **Verify cluster**
   ```bash
   # Check all nodes healthy
   for NODE in node-{1..4}; do
     curl -s http://$NODE:8080/health | jq '.status'
   done
   ```

**Success Criteria**:
- ✅ All nodes running new version
- ✅ Cluster consensus operational
- ✅ No failed transactions during update
- ✅ Catch-up lag < 100ms

**Rollback**:
```bash
# Revert one node at a time to previous version
for NODE in node-{1..4}; do
  ssh $NODE "sudo systemctl stop delos"
  ssh $NODE "sudo cp delos.jar.backup /opt/delos/delos.jar"
  ssh $NODE "sudo systemctl start delos"
  sleep 10
done
```

---

## Service Management

### Systemd Service Configuration

**File**: `/etc/systemd/system/delos.service`

```ini
[Unit]
Description=Delos Distributed Consensus System
After=network.target
Wants=network-online.target

[Service]
Type=simple
User=delos
Group=delos
WorkingDirectory=/var/lib/delos
EnvironmentFile=/etc/delos/delos.env

ExecStart=/usr/bin/java \
  -Xmx4g -Xms2g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -Dfile.encoding=UTF-8 \
  -jar /opt/delos/delos.jar

ExecReload=/bin/kill -HUP $MAINPID
KillMode=mixed
KillSignal=SIGTERM

# Restart policy
Restart=on-failure
RestartSec=5
StartLimitInterval=60
StartLimitBurst=3

# Resource limits
MemoryLimit=6G
LimitNOFILE=65536
LimitNPROC=32768

# Logging
StandardOutput=journal
StandardError=journal
SyslogIdentifier=delos

[Install]
WantedBy=multi-user.target
```

**Enable and test**:
```bash
sudo systemctl daemon-reload
sudo systemctl enable delos
sudo systemctl start delos
sudo systemctl status delos
```

### Log Management

**Log Rotation** - `/etc/logrotate.d/delos`:

```
/var/log/delos/*.log {
    daily
    rotate 14
    missingok
    notifempty
    compress
    delaycompress
    copytruncate
    postrotate
        systemctl reload delos > /dev/null 2>&1 || true
    endscript
}
```

**Enable**:
```bash
sudo logrotate -f /etc/logrotate.d/delos  # Test
```

---

## Health Checks

### Automated Health Check Script

```bash
#!/bin/bash
# delos-health-check.sh - Run periodically via cron

NODE=localhost
PORT=8080
THRESHOLD_LATENCY_MS=500
THRESHOLD_MEMBERS=3

# Check HTTP health
HEALTH=$(curl -s -w "%{http_code}" -o /tmp/health.json \
  http://$NODE:$PORT/health)

if [ "$HEALTH" != "200" ]; then
  echo "CRITICAL: HTTP health failed ($HEALTH)"
  exit 2
fi

# Check consensus status
STATUS=$(jq -r '.status' /tmp/health.json)
if [ "$STATUS" != "HEALTHY" ]; then
  echo "CRITICAL: Node unhealthy ($STATUS)"
  exit 2
fi

# Check member count
MEMBERS=$(jq '.member_count' /tmp/health.json)
if [ $MEMBERS -lt $THRESHOLD_MEMBERS ]; then
  echo "CRITICAL: Insufficient members ($MEMBERS < $THRESHOLD_MEMBERS)"
  exit 2
fi

# Check latency
LATENCY=$(curl -s http://$NODE:$PORT/metrics | \
  grep 'consensus_latency_ms{quantile="0.99"}' | \
  cut -d' ' -f2)

if [ $(echo "$LATENCY > $THRESHOLD_LATENCY_MS" | bc) -eq 1 ]; then
  echo "WARNING: High latency ($LATENCY ms)"
  exit 1
fi

echo "OK: Node healthy, $MEMBERS members, latency ${LATENCY}ms"
exit 0
```

**Install cron job**:
```bash
# Check every 5 minutes
*/5 * * * * /usr/local/bin/delos-health-check.sh
```

---

## Maintenance Tasks

### Monthly Maintenance Checklist

**First of month**:
- [ ] Review logs for ERROR entries
- [ ] Check disk usage trends
- [ ] Verify backups completed
- [ ] Review and update documentation
- [ ] Check certificate expiration dates

```bash
# Check disk usage growth
du -sh /var/lib/delos
df -h /var/lib/delos

# Check certificate dates
openssl x509 -in /etc/delos/tls.crt -noout -dates

# Review error log frequency
journalctl -u delos --since "2 weeks ago" | grep ERROR | wc -l
```

### Certificate Renewal

**When**: Certificate expires in 30 days
**Duration**: 15 minutes per node

**Steps**:
```bash
# Generate new certificate
openssl req -new -key /etc/delos/tls.key \
  -out /etc/delos/tls.csr

# Have CA sign it
# ... send to CA, receive tls.crt

# Reload daemon
sudo systemctl reload delos
```

### Disk Cleanup

**When**: Free space < 20%
**Steps**:
```bash
# Find large old checkpoints
find /var/lib/delos/checkpoints -type f -mtime +7 -exec ls -lh {} \;

# Remove if safe (keep last 7 days)
find /var/lib/delos/checkpoints -type f -mtime +7 -delete
```

---

## Operational Checklists

### Pre-Deployment Checklist

Before deploying Delos cluster to production:

- [ ] **Hardware ready**
  - [ ] 4+ nodes assigned
  - [ ] Network connectivity verified (ping test)
  - [ ] Disk space allocated (> 500GB each)
  - [ ] Memory installed (8GB+ per node)

- [ ] **Software prepared**
  - [ ] Delos binary built and tested
  - [ ] Configuration files created
  - [ ] Systemd service files installed
  - [ ] Log rotation configured

- [ ] **Security verified**
  - [ ] TLS certificates generated
  - [ ] MTLS enabled and tested
  - [ ] KERI identities bootstrapped
  - [ ] Firewall rules applied
  - [ ] SSH key access verified

- [ ] **Monitoring ready**
  - [ ] Prometheus scrape configured
  - [ ] Alerting rules created
  - [ ] Dashboards created
  - [ ] Log aggregation enabled

- [ ] **Testing completed**
  - [ ] Single node startup tested
  - [ ] Multi-node cluster tested
  - [ ] Failover scenario tested
  - [ ] Recovery tested
  - [ ] Load tested

### Post-Deployment Verification

After all nodes started:

- [ ] All nodes report HEALTHY status
- [ ] Cluster has correct member count
- [ ] Leader elected and stable
- [ ] Catch-up lag < 100ms for all nodes
- [ ] Transactions executing normally
- [ ] Metrics being collected
- [ ] No ERROR entries in logs
- [ ] Backup completed successfully

### Incident Response Checklist

When incident occurs:

1. [ ] **Assess severity**
   - [ ] Can cluster still reach consensus?
   - [ ] Are transactions failing?
   - [ ] Data loss risk?

2. [ ] **Collect information**
   - [ ] Systemd logs (journalctl)
   - [ ] Application logs
   - [ ] Metrics (CPU, memory, network)
   - [ ] Consensus status

3. [ ] **Take immediate action**
   - [ ] Stop causing further damage
   - [ ] Prevent data loss
   - [ ] Maintain availability

4. [ ] **Execute recovery**
   - [ ] Use appropriate runbook
   - [ ] Monitor progress
   - [ ] Verify success

5. [ ] **Post-incident**
   - [ ] Document what happened
   - [ ] Identify root cause
   - [ ] Update procedures
   - [ ] Communicate with team

---

## Related Documentation

- **PERFORMANCE_TUNING.md** - Capacity planning and optimization
- **DEPLOYMENT_GUIDE.md** - Initial setup and bootstrap
- **TROUBLESHOOTING_GUIDE.md** - Problem diagnosis
- **MONITORING_ALERTING.md** - Observability and alerts
- **DISASTER_RECOVERY.md** - Backup and restore

---

**Last Updated**: 2026-01-09
**Phase**: 3.2 (Operations & Maintenance)
**Epic**: Delos-aj2 (Documentation Improvement)
