# Delos Operational Checklists

**Document Version**: 1.0
**Date**: 2026-01-27
**Status**: Production
**Audience**: Operations teams

---

These checklists are templates for common operational tasks. Copy and customize for your cluster.

---

## Checklist 1: Pre-Deployment (New Cluster)

**Timeline**: 1 week before go-live
**Owner**: DevOps lead
**Duration**: 2-3 days of preparation

### Infrastructure (3 days before)

- [ ] **Hardware Provisioning**
  - [ ] Reserve 7 physical servers or cloud instances
  - [ ] Verify CPU: 4+ cores per node
  - [ ] Verify Memory: 8-16 GB per node
  - [ ] Verify Storage: 200+ GB SSD per node
  - [ ] Verify Network: 1+ Gbps per node

- [ ] **Network Setup**
  - [ ] Assign static IPs to all 7 nodes
  - [ ] Configure DNS records: node1.delos.local through node7.delos.local
  - [ ] Configure firewall rules (ports 50051, 50052, 8080)
  - [ ] Test network connectivity: `ping -c 5 node2` from node1 (all pairs)
  - [ ] Measure latency: `mtr -r -c 20 nodeX` (should be < 10ms)

- [ ] **System Configuration**
  - [ ] Install Java 25+ on all nodes
  - [ ] Verify Java: `java -version` shows 25.x
  - [ ] Configure NTP: `sudo apt-get install chrony`
  - [ ] Verify time sync: `timedatectl status` (< 500ms skew)
  - [ ] Configure filesystem: mount /opt/delos on SSD
  - [ ] Set permissions: `chmod 700 /opt/delos`

### Security (5 days before)

- [ ] **Identity Keys**
  - [ ] Generate 7 KERI identity keys offline
  - [ ] Store in secure location (not on any node yet)
  - [ ] Create password (32+ random bytes): `openssl rand -base64 32`
  - [ ] Store password in secrets manager (Vault/AWS Secrets Manager)
  - [ ] Test keystore creation: `keytool -list -v -keystore test.jks`

- [ ] **TLS Certificates**
  - [ ] Generate CA certificate (valid 10 years)
  - [ ] Generate 7 server certificates (valid 1-3 years)
  - [ ] Add Subject Alternative Names (SANs) for each node
  - [ ] Create PKCS12 keystores from certificates
  - [ ] Test certificate validity: `openssl x509 -in cert.pem -text -noout`

- [ ] **Secret Management**
  - [ ] Set up Vault/AWS Secrets Manager
  - [ ] Store keystore passwords (for identity keys)
  - [ ] Store TLS keystore passwords
  - [ ] Store database passwords
  - [ ] Verify secrets can be retrieved: `vault read secret/delos/keystore-password`

### Build & Configuration (3 days before)

- [ ] **Build Application**
  - [ ] Clone Delos repository
  - [ ] Run `./mvnw clean install -Ppre -DskipTests`
  - [ ] Verify build succeeds
  - [ ] Create delos.jar artifact: `ls target/delos-*.jar`

- [ ] **Configuration**
  - [ ] Create delos.yaml template
  - [ ] Fill in node names (node1, node2, ..., node7)
  - [ ] Fill in peer list (all 7 IP addresses and ports)
  - [ ] Set fireflies.member_count = 7
  - [ ] Set choam.batch_size = 100
  - [ ] Review all parameters (see CONFIGURATION_GUIDE.md)
  - [ ] Create 7 node-specific configs (one per node)

### Testing (2 days before)

- [ ] **Staging Deployment**
  - [ ] Deploy 4-node staging cluster
  - [ ] Run integration tests: `./mvnw test -Dlarge_tests=true`
  - [ ] Run consensus tests (Byzantine detection)
  - [ ] Test key rotation procedures
  - [ ] Test upgrade procedures (if not first deployment)

- [ ] **Load Testing**
  - [ ] Run synthetic load: 10K transactions
  - [ ] Measure throughput: target 5K-10K tx/sec
  - [ ] Measure latency: P95 should be < 250ms
  - [ ] Check memory usage: should stabilize < 70%
  - [ ] Monitor for crashes or errors

### Operations Preparation (1 day before)

- [ ] **Monitoring Setup**
  - [ ] Install Prometheus
  - [ ] Configure Prometheus scrape targets (7 nodes)
  - [ ] Verify metrics collection: `curl http://node1:8080/metrics`
  - [ ] Install Grafana
  - [ ] Create dashboards (use templates from docs)
  - [ ] Configure alerts (see MONITORING_AND_ALERTING.md)

- [ ] **Runbook Preparation**
  - [ ] Print or open incident runbooks (tabs in browser)
  - [ ] Brief ops team on alert thresholds
  - [ ] Identify on-call escalation path
  - [ ] Document contact info for architecture team

- [ ] **Final Verification**
  - [ ] All 7 nodes passing health checks
  - [ ] Network connectivity verified (< 10ms latency)
  - [ ] NTP sync verified on all nodes
  - [ ] Backups configured and tested
  - [ ] Monitoring stack ready and validated

---

## Checklist 2: Weekly Maintenance

**Timeline**: Every Friday EOD
**Owner**: Operations lead
**Duration**: 30-60 minutes

### Health Check

- [ ] **Cluster Status**
  - [ ] Verify all 7 nodes responding: `for i in {1..7}; do curl http://node$i:8080/health; done`
  - [ ] No nodes suspected: `curl http://node1:8080/metrics | grep fireflies_suspected`
  - [ ] Blocks being produced: `curl http://node1:8080/metrics | grep choam_blocks_committed`
  - [ ] No critical alerts firing in Prometheus

- [ ] **Performance**
  - [ ] Consensus latency p95 < 300ms: `curl http://node1:8080/metrics | grep choam_consensus_latency_95th`
  - [ ] Throughput > 5K tx/sec (or your target)
  - [ ] Pending transactions < 100: `curl http://node1:8080/metrics | grep choam_pending`
  - [ ] No Byzantine detections: `grep -i byzantine /opt/delos/logs/delos.log`

- [ ] **Resources**
  - [ ] Heap usage < 70%: `curl http://node1:8080/metrics | grep jvm_memory_heap_used`
  - [ ] Disk usage < 70%: `df -h /opt/delos/data/` on all nodes
  - [ ] No GC warnings in logs: `grep "GC overhead" /opt/delos/logs/delos.log`
  - [ ] File descriptor usage normal: `lsof -p $(pidof java) | wc -l` < 5000

### Backup Verification

- [ ] **Backup Status**
  - [ ] Latest backup file exists: `ls -la /backup/delos-backup-*.tar.gz | tail -1`
  - [ ] Backup is recent (< 24 hours old): `find /backup -name "delos-backup-*.tar.gz" -mtime 0`
  - [ ] Backup size is reasonable: check size matches data directory
  - [ ] S3 backup uploaded: `aws s3 ls s3://company-backups/delos/ | tail -1`

- [ ] **Restore Test (Monthly)**
  - [ ] Mount test node
  - [ ] Extract backup: `tar xzf delos-backup-*.tar.gz -C /test/`
  - [ ] Verify data integrity: spot-check some records
  - [ ] Document procedure if not monthly

### Log Review

- [ ] **Error Analysis**
  - [ ] Check for errors: `grep ERROR /opt/delos/logs/delos.log | wc -l`
  - [ ] If > 0 errors, investigate and document
  - [ ] Check for warnings: `grep WARN /opt/delos/logs/delos.log | tail -20`
  - [ ] Document any patterns (timeouts, memory warnings, etc.)

- [ ] **Trend Analysis**
  - [ ] Check view changes: `grep "View change" /opt/delos/logs/delos.log | wc -l`
  - [ ] Should be < 5 per week
  - [ ] If > 5, investigate network or node stability
  - [ ] Check Byzantine detections: should be 0

### Documentation Update

- [ ] **Runbook Updates**
  - [ ] Review any incidents from past week
  - [ ] Update runbooks with lessons learned
  - [ ] Note any configuration changes needed
  - [ ] Brief team on any procedure changes

---

## Checklist 3: Incident Response

**Timeline**: During incident
**Owner**: On-call engineer
**Duration**: Varies

### Node Isolation/Failure (View Size < 7)

**Severity**: HIGH
**Response time**: < 15 minutes

- [ ] **Diagnosis** (5 min)
  - [ ] Which nodes are missing? `curl http://node1:8080/metrics | grep fireflies_view_size`
  - [ ] Can we reach the node? `ping -c 5 missing-node`
  - [ ] Is the node running? `ssh missing-node "sudo systemctl status delos"`
  - [ ] Check node logs: `ssh missing-node "journalctl -u delos -n 50"`

- [ ] **Mitigation** (5 min)
  - If network issue: Notify network team, node will rejoin automatically when network recovers
  - If node crashed: `ssh missing-node "sudo systemctl restart delos"`
  - If stuck: `ssh missing-node "sudo systemctl stop delos && sleep 5 && sudo systemctl start delos"`

- [ ] **Recovery** (5 min)
  - [ ] Monitor view size: watch for missing-node to rejoin
  - [ ] Verify logs on rejoined node (should be clean)
  - [ ] Run health check on that node
  - [ ] Check consensus is continuing (blocks increasing)

- [ ] **Follow-up** (next day)
  - [ ] Analyze logs to understand root cause
  - [ ] File incident ticket with logs attached
  - [ ] Plan remediation if systemic issue

### Consensus Stalled (No Blocks for 5 Minutes)

**Severity**: CRITICAL
**Response time**: < 5 minutes

- [ ] **Diagnosis** (2 min)
  - [ ] Verify actually stalled: `curl http://node1:8080/metrics | grep choam_blocks_committed` (check twice, 30s apart)
  - [ ] Check cluster formation: `curl http://node1:8080/metrics | grep fireflies_view_size` (should be 7)
  - [ ] Check for Byzantine: `grep -i byzantine /opt/delos/logs/delos.log | tail -5`

- [ ] **Mitigation** (3 min)
  - If cluster fragmented: Follow "Node Isolation" checklist above
  - If Byzantine detected: See "Byzantine Attack" below
  - If no obvious cause: Escalate to architecture team (incident #<number>)

### Byzantine Attack (Equivocation Detected)

**Severity**: CRITICAL
**Response time**: < 2 minutes

- [ ] **Immediate Actions** (< 1 min)
  - [ ] Identify suspicious node: `grep "Byzantine\|Equivocation\|member" /opt/delos/logs/delos.log | grep -i byzantine`
  - [ ] Isolate node immediately: `ssh suspicious-node "sudo iptables -A INPUT -j DROP"` (network isolation)
  - [ ] OR restart it: `ssh suspicious-node "sudo systemctl restart delos"`
  - [ ] Document evidence: `cp /opt/delos/logs/delos.log /secure/incident-$(date +%s).log`

- [ ] **Escalation** (< 2 min)
  - [ ] Alert security team immediately
  - [ ] Provide: node name, timestamp, logs excerpt
  - [ ] Open incident ticket: "Byzantine attack on <node>"
  - [ ] Notify architecture team

- [ ] **Monitoring** (ongoing)
  - [ ] Watch for view size to drop (isolated node removed)
  - [ ] Verify blocks resume (consensus recovered)
  - [ ] Monitor for similar attacks on other nodes

### High Latency (P95 > 300ms)

**Severity**: MEDIUM
**Response time**: < 30 minutes

- [ ] **Diagnosis** (10 min)
  - [ ] Confirm latency: `curl http://node1:8080/metrics | grep choam_consensus_latency_95th`
  - [ ] Check network latency: `for node in node{2..7}; do echo "$node: $(ping -c1 $node | grep time=)"; done`
  - [ ] Check CPU usage: `top -bn1 | head -20` on each node
  - [ ] Check disk I/O: `iostat -x 1` on each node (look for wait time)
  - [ ] Check memory: `jvm_memory_heap_used` metric

- [ ] **Potential Causes & Fixes** (20 min)
  - If network latency high: Contact network team, may be temporary
  - If CPU high: Reduce batch_size or batch_timeout in config, restart
  - If disk slow: Check for full disk, runaway log files
  - If memory high: Restart with larger heap: `-Xmx10g -Xms8g`
  - If GC pausing: Check `jvm_gc_pause_time_p99` metric

- [ ] **Recovery** (follow fix above)
  - [ ] Monitor latency to return to normal
  - [ ] After return to normal, file follow-up ticket for root cause analysis

### Out of Memory Risk (Heap > 90%)

**Severity**: CRITICAL
**Response time**: < 10 minutes

- [ ] **Immediate Action** (< 5 min)
  - [ ] Identify affected node(s): `curl http://nodeX:8080/metrics | grep jvm_memory_heap_used`
  - [ ] Restart with larger heap: Edit systemd unit, increase `-Xmx` flag
  - [ ] Restart service: `sudo systemctl daemon-reload && sudo systemctl restart delos`
  - [ ] Verify rejoin: `curl http://nodeX:8080/health`

- [ ] **Follow-up** (next 24 hours)
  - [ ] Monitor heap usage on restarted node
  - [ ] If stabilizes, investigate memory leak
  - [ ] Check for abnormal log sizes: `du -sh /opt/delos/logs/`
  - [ ] Archive old logs if large
  - [ ] File ticket if pattern repeats

---

## Checklist 4: Regular Backups (Daily)

**Timeline**: Every day at scheduled time
**Owner**: Automated job (cron) or manual
**Duration**: 10-30 minutes (depends on data size)

- [ ] **Backup Execution**
  - [ ] Start backup job: `sudo /opt/delos/scripts/backup.sh`
  - [ ] Verify backup started: `tail -f /opt/delos/logs/backup.log`
  - [ ] Wait for completion (check file size stabilizes)

- [ ] **Backup Verification** (next day)
  - [ ] Check backup exists: `ls -la /backup/delos-backup-*.tar.gz | tail -1`
  - [ ] Check backup size: `du -h /backup/delos-backup-latest.tar.gz`
  - [ ] Spot check contents: `tar tzf /backup/delos-backup-latest.tar.gz | head -20`
  - [ ] Verify restore ability (monthly): Extract to test location

- [ ] **Archival**
  - [ ] Daily: Keep last 7 backups locally
  - [ ] Weekly: Copy to S3: `aws s3 cp /backup/delos-backup-*.tar.gz s3://company-backups/delos/`
  - [ ] Monthly: Keep 1 full backup in cold storage (Glacier)

---

## Checklist 5: Quarterly Security Review

**Timeline**: Every Q (every 3 months)
**Owner**: Security lead
**Duration**: 4-8 hours

- [ ] **Certificate Review**
  - [ ] List all TLS certificates: `openssl x509 -in /opt/delos/keys/node*.pem -text -noout | grep "Not After"`
  - [ ] Which expire in next 90 days? Generate replacements
  - [ ] Renew certificates before expiry

- [ ] **Key Rotation Review**
  - [ ] When was last KERI key rotation? (Check KERL events)
  - [ ] If > 1 year, plan key rotation
  - [ ] Test key rotation procedure in staging cluster

- [ ] **Access Control**
  - [ ] Review who has SSH access to nodes: `cat /home/delos/.ssh/authorized_keys`
  - [ ] Revoke any old keys
  - [ ] Add new team members
  - [ ] Document access list

- [ ] **Secret Rotation**
  - [ ] Review all secrets in Vault: `vault list secret/delos/`
  - [ ] Rotate any passwords older than 90 days
  - [ ] Update documentation with new passwords (encrypted)

- [ ] **Audit Logging**
  - [ ] Verify auth logs are being collected: `/var/log/auth.log`
  - [ ] Check for suspicious login attempts
  - [ ] Verify systemd logs are retained: `journalctl --disk-usage`
  - [ ] Archive old logs to cold storage

---

## Checklist 6: Annual Disaster Recovery Test

**Timeline**: Once per year (schedule in advance)
**Owner**: DevOps + Security + Architecture
**Duration**: 1-2 days

- [ ] **Test Objective**
  - [ ] Verify we can recover from complete data loss
  - [ ] Practice restore procedures
  - [ ] Identify gaps in backup/restore process

- [ ] **Test Procedure** (Day 1: Restore)
  - [ ] Provision 7 fresh nodes (temporary)
  - [ ] Restore latest backup to fresh nodes
  - [ ] Verify cluster forms on restored data
  - [ ] Run integration tests on restored cluster
  - [ ] Document any issues encountered

- [ ] **Test Verification** (Day 2: Validate)
  - [ ] Compare restored data to production:
    - Same number of transactions?
    - Same state hashes?
    - Same operational metrics?
  - [ ] Run full test suite: `./mvnw test -Dlarge_tests=true`
  - [ ] Document any data loss or discrepancies

- [ ] **Follow-up**
  - [ ] File improvement tickets for any issues
  - [ ] Update restore procedures based on experience
  - [ ] Brief team on lessons learned
  - [ ] Schedule next year's DR test

---

## Related Documentation

- [Operational Procedures](OPERATIONAL_PROCEDURES.md) - Detailed procedures
- [Monitoring Guide](MONITORING_AND_ALERTING.md) - Alert thresholds
- [Troubleshooting Guide](TROUBLESHOOTING_GUIDE.md) - Problem resolution
- [Disaster Recovery](DISASTER_RECOVERY.md) - Backup/restore details
