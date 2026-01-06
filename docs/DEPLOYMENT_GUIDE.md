# Delos Deployment Guide

**Document Version:** 1.0
**Date:** 2026-01-06
**Status:** Production

## Overview

This guide covers deployment of Delos Byzantine fault-tolerant distributed systems with KERI identity, committee-based consensus (CHOAM), and replicated state machines (SQL-State).

---

## 1. Pre-Deployment Checklist

### 1.1 Infrastructure Requirements

| Component | Minimum | Recommended | Notes |
|-----------|---------|-------------|-------|
| **Nodes** | 4 (f+1 for f=1) | 7-13 (f=2-4) | Byzantine tolerance: f < n/3 |
| **CPU** | 2 cores | 4+ cores | Per-node for concurrent operations |
| **Memory** | 2 GB | 8 GB | Fireflies gossip + CHOAM consensus |
| **Storage** | 10 GB | 100+ GB | KERL, CHOAM logs, SQL-State snapshots |
| **Network** | 100 Mbps | 1 Gbps | Low-latency for consensus |
| **Java** | 23+ | 25 LTS | GraalVM recommended for isolates |

### 1.2 Security Requirements

- [ ] SSH key pairs generated for node access (no passwords)
- [ ] TLS certificates created for MTLS GRPC (Subject Alternative Names)
- [ ] KERI identity keys generated (offline on secure machine)
- [ ] Database encryption keys configured
- [ ] File system permissions restricted to service owner
- [ ] Network firewalls configured for GRPC ports
- [ ] Backup strategy for identity keys established
- [ ] Disaster recovery plan documented

### 1.3 Network Configuration

- [ ] Identify subnet for nodes (e.g., 10.0.1.0/24)
- [ ] Reserve static IPs for all nodes
- [ ] Configure DNS records for node hostnames
- [ ] Open required ports:
  - 50051: GRPC (consensus, gossip)
  - 50052: GRPC (identity services)
  - 8080: HTTP (metrics, health checks)
  - Custom: Application ports (if any)
- [ ] Enable network monitoring on firewall

---

## 2. Node Setup

### 2.1 Pre-Installation

**On each node:**

```bash
# Update system packages
sudo apt-get update && sudo apt-get upgrade -y

# Create dedicated service user
sudo useradd -m -s /bin/bash delos
sudo mkdir -p /opt/delos/{data,logs,keys}
sudo chown -R delos:delos /opt/delos
sudo chmod 700 /opt/delos/keys

# Install Java 25 (or GraalVM)
# Download from oracle.com and install
java -version  # Verify 25.x.x

# Install Maven (if building locally)
wget https://archive.apache.org/dist/maven/maven-3/3.9.4/binaries/apache-maven-3.9.4-bin.tar.gz
sudo tar xzf apache-maven-3.9.4-bin.tar.gz -C /opt/
export PATH="/opt/apache-maven-3.9.4/bin:$PATH"
```

### 2.2 Build Delos

**Option A: From Source (Recommended)**

```bash
cd /opt/delos/src
git clone https://github.com/Hellblazer/Delos.git .
./mvnw clean install -DskipTests
# Output: Delos jars in target/

# Pre-install h2-deterministic (required)
./mvnw install -Ppre -DskipTests -pl h2-deterministic
```

**Option B: From Released JAR**
```bash
# When releases available
wget https://github.com/Hellblazer/Delos/releases/download/v0.0.1/delos-app.jar
sudo mv delos-app.jar /opt/delos/
```

### 2.3 KERI Identity Provisioning

**On secure offline machine:**

```bash
# Generate KERI identity keypair (Ed25519)
java -cp delos.jar \
  com.hellblazer.delos.tools.StereotomyKeygen \
  --output /secure/path/node1-identity.jks \
  --password SecurePassword123!

# Export public identifier (SAI - Self-Addressing Identifier)
java -cp delos.jar \
  com.hellblazer.delos.tools.StereotomyExport \
  --keystore /secure/path/node1-identity.jks \
  --password SecurePassword123!
# Output: EJLQ5RZoM4ypLLdIqNXkbsVWXMaqfC0I... (SAI)
```

⚠️ **SECURITY WARNING**: Never use the example password `SecurePassword123!` in production. Generate a strong, random password using:
```bash
openssl rand -base64 32  # Generate secure 32-byte password
```
Store passwords in a secrets manager (Vault, AWS Secrets Manager, etc.), never in configuration files.

**Transfer to node (encrypted channel):**
```bash
scp -P 22 \
  /secure/path/node1-identity.jks \
  delos@node1:/opt/delos/keys/identity.jks

# Set permissions
ssh delos@node1 'chmod 600 /opt/delos/keys/identity.jks'
```

### 2.4 TLS Certificates for MTLS GRPC

**Generate certificates (on PKI machine):**

```bash
# Create CA
openssl genrsa -out ca-key.pem 4096
openssl req -new -x509 -days 3650 -key ca-key.pem -out ca-cert.pem \
  -subj "/CN=Delos-CA"

# Create server certificate for node1
openssl genrsa -out node1-key.pem 4096

# Create CSR with SANs
cat > node1.conf << EOF
[req]
distinguished_name = req_distinguished_name
req_extensions = v3_req

[req_distinguished_name]

[v3_req]
keyUsage = keyEncipherment, dataEncipherment
extendedKeyUsage = serverAuth, clientAuth
subjectAltName = DNS:node1.delos.local,DNS:10.0.1.101,DNS:localhost,IP:10.0.1.101
EOF

openssl req -new -key node1-key.pem -out node1.csr \
  -subj "/CN=node1.delos.local" -config node1.conf

# Sign with CA
openssl x509 -req -days 365 -in node1.csr \
  -CA ca-cert.pem -CAkey ca-key.pem -CAcreateserial \
  -out node1-cert.pem -extensions v3_req -extfile node1.conf

# Create PKCS12 keystore (Java format)
openssl pkcs12 -export \
  -in node1-cert.pem -inkey node1-key.pem \
  -out node1-keystore.p12 \
  -name node1 \
  -passout pass:KeystorePassword123!
```

⚠️ **SECURITY WARNING**: Never use the example password `KeystorePassword123!` in production. Use a strong random password and store securely in a secrets manager (not in configuration files or version control).

**Transfer to nodes:**
```bash
scp node1-keystore.p12 delos@node1:/opt/delos/keys/
scp ca-cert.pem delos@node1:/opt/delos/keys/truststore.pem
```

---

## 3. Node Configuration

### 3.1 Configuration File (delos.yaml)

⚠️ **SECURITY WARNING**: The configuration examples below contain hardcoded passwords for illustration only. **NEVER use hardcoded passwords in production configuration files.** Instead:
1. Use environment variables: `${DELOS_KEYSTORE_PASSWORD}`
2. Load from secrets manager at startup
3. Use external configuration management (Vault, consul, etc.)
4. Ensure delos.yaml has restrictive permissions (chmod 600)

Create `/opt/delos/config/delos.yaml`:

```yaml
# Node Identity
identity:
  keystore: /opt/delos/keys/identity.jks
  keystore_password: SecurePassword123!  # Use env var in production
  alias: node1

# GRPC Server
grpc:
  port: 50051
  enable_keep_alive: true
  keep_alive_time: 30s

  # MTLS Configuration
  mtls:
    enabled: true
    keystore: /opt/delos/keys/node1-keystore.p12
    keystore_password: KeystorePassword123!  # Use env var
    keystore_type: PKCS12
    truststore: /opt/delos/keys/truststore.pem
    truststore_type: PEM
    require_client_auth: true

# Fireflies (Membership Service)
fireflies:
  member_count: 7  # Must match cluster size
  sample_size: 7  # Sample all for consensus
  gossip_interval: 100ms
  failure_threshold: 5s
  suspect_threshold: 30s

# CHOAM (Consensus)
choam:
  batch_size: 100
  batch_timeout: 100ms
  checkpoint_interval: 10000  # blocks
  view_change_timeout: 10s

# SQL-State
sql_state:
  h2_url: jdbc:h2:/opt/delos/data/state;MODE=PostgreSQL
  h2_user: sa
  h2_password: ""  # H2 default (change for production)
  connection_pool_size: 20

# Database (KERL, StateLog)
database:
  type: h2
  path: /opt/delos/data
  encrypt: true
  encryption_key_path: /opt/delos/keys/db-encryption.key

# Metrics (Dropwizard)
metrics:
  enabled: true
  http_port: 8080
  reporters:
    - type: slf4j
      interval: 60s
    - type: prometheus
      path: /metrics

# Logging
logging:
  level: INFO
  file: /opt/delos/logs/delos.log
  max_size: 100MB
  backup_count: 10

# Network
network:
  peers:
    - name: node1
      host: 10.0.1.101
      port: 50051
    - name: node2
      host: 10.0.1.102
      port: 50051
    - name: node3
      host: 10.0.1.103
      port: 50051
    # ... other nodes
```

### 3.2 Environment Variables

Create `/opt/delos/config/.env`:

```bash
# Credentials (use Vault in production)
DELOS_KEYSTORE_PASSWORD=SecurePassword123!
DELOS_KEYSTORE_PASSWORD_ALIAS=Delos-node1-key
DELOS_DB_PASSWORD=DatabasePassword456!

# Java Options
JAVA_OPTS="-Xmx4g -Xms2g -XX:+UseG1GC"

# Delos Config
DELOS_CONFIG=/opt/delos/config/delos.yaml
DELOS_DATA_DIR=/opt/delos/data
DELOS_LOGS_DIR=/opt/delos/logs
```

---

## 4. Systemd Service Setup

Create `/etc/systemd/system/delos.service`:

```ini
[Unit]
Description=Delos Byzantine Fault-Tolerant System
After=network.target

[Service]
Type=simple
User=delos
WorkingDirectory=/opt/delos
EnvironmentFile=/opt/delos/config/.env

# Start command
ExecStart=/usr/bin/java $JAVA_OPTS \
  -cp /opt/delos/delos-app.jar \
  com.hellblazer.delos.DelosApp

# Restart policy
Restart=on-failure
RestartSec=10s
StandardOutput=journal
StandardError=journal

# Resource limits
LimitNOFILE=65536
LimitNPROC=65536

[Install]
WantedBy=multi-user.target
```

**Enable and start:**
```bash
sudo systemctl daemon-reload
sudo systemctl enable delos
sudo systemctl start delos
sudo systemctl status delos
```

---

## 5. Multi-Node Cluster Bootstrap

### 5.1 Witness Setup

**Designate 3 external witness nodes** (not part of consensus):

These nodes:
- Attest to KERI identities
- Provide non-repudiation
- Are independent of consensus cluster

```bash
# On witness node
java -cp delos.jar \
  com.hellblazer.delos.tools.WitnessNode \
  --config witness.yaml
```

### 5.2 Bootstrap Sequence

**Step 1: Start all nodes (wait 30s between each)**
```bash
for node in node1 node2 node3 node4 node5 node6 node7; do
  ssh delos@$node "sudo systemctl start delos"
  sleep 30
done

# Monitor bootstrap
for node in node1 node2 node3 node4 node5 node6 node7; do
  ssh delos@$node "journalctl -u delos -f" &
done
```

**Step 2: Verify initial view formation**
```bash
# Query from any node
curl -s http://localhost:8080/metrics | grep "fireflies_view_changes"
# Expected: Should stabilize after ~30s
```

**Step 3: Create initial CHOAM state**
```bash
java -cp delos.jar \
  com.hellblazer.delos.tools.InitializeChoam \
  --nodes node1,node2,node3,node4,node5,node6,node7 \
  --checkpoint /tmp/genesis-checkpoint.bin
```

**Step 4: Activate SQL-State**
```bash
java -cp delos.jar \
  com.hellblazer.delos.tools.InitializeState \
  --schema-sql schemas/initial-schema.sql \
  --checkpoint /tmp/genesis-checkpoint.bin
```

### 5.3 Validate Cluster Health

```bash
# Check all nodes are up
for node in node{1..7}; do
  nc -zv 10.0.1.10$((node#//1)) 50051 && echo "$node: UP" || echo "$node: DOWN"
done

# Verify consensus is working
./tools/check-consensus.sh node1

# Verify identity distribution
./tools/verify-identity.sh --expected-sai EJLQ5RZoM...
```

---

## 6. Operational Tasks

### 6.1 Health Checks

**Daily health check script:**
```bash
#!/bin/bash
# /opt/delos/scripts/health-check.sh

echo "=== Delos Cluster Health Check ==="
date

for node in node{1..7}; do
  echo -n "$node: "

  # Check service is running
  if systemctl is-active delos &>/dev/null; then
    echo "✓ Running"
  else
    echo "✗ STOPPED - investigating..."
    journalctl -u delos -n 20
  fi
done

# Check metrics endpoint
echo "Metrics endpoint:"
curl -s http://localhost:8080/metrics | head -5

# Check view stability
echo "View formation (fireflies_view_changes):"
curl -s http://localhost:8080/metrics | grep fireflies_view_changes
```

### 6.2 Log Rotation

Already configured in systemd/journald. Force rotation:
```bash
sudo journalctl --vacuum-time=30d
```

### 6.3 Backup Procedures

**Daily backup of critical data:**
```bash
#!/bin/bash
# /opt/delos/scripts/backup.sh

BACKUP_DIR="/mnt/backups/delos-$(date +%Y%m%d)"
mkdir -p $BACKUP_DIR

# Backup KERL (identity events) - DO NOT LOSE
cp /opt/delos/data/kerl.db $BACKUP_DIR/

# Backup CHOAM consensus log
cp /opt/delos/data/choam.db $BACKUP_DIR/

# Backup SQL-State materialized view
cp /opt/delos/data/state.h2.db $BACKUP_DIR/

# Backup keys (encrypted)
tar czf $BACKUP_DIR/keys.tar.gz \
  --exclude='*.pem' \  # Don't backup certs (regenerate)
  /opt/delos/keys/

# Encrypt backup
gpg --encrypt --recipient backup@delos.local $BACKUP_DIR/keys.tar.gz

# Verify backup
tar tzf $BACKUP_DIR/keys.tar.gz > /dev/null && echo "Backup OK" || echo "Backup FAILED"

# Upload to remote
rsync -av $BACKUP_DIR/ backup-server:/backups/delos/
```

### 6.4 Certificate Renewal

**90 days before expiration:**
```bash
# Regenerate TLS certificates (see Section 2.4)
# Perform rolling update (one node at a time):

for node in node{1..7}; do
  # 1. Generate new cert for this node
  # 2. SCP to node
  # 3. Restart service
  # 4. Wait for view stability (30s)
  # 5. Verify metrics
done
```

---

## 7. Rolling Updates

### 7.1 Update Procedure

```bash
#!/bin/bash
# /opt/delos/scripts/rolling-update.sh

VERSION=$1
NODES=(node1 node2 node3 node4 node5 node6 node7)

for NODE in "${NODES[@]}"; do
  echo "=== Updating $NODE to $VERSION ==="

  # 1. Download new version on node
  ssh delos@$NODE "curl -o /tmp/delos-$VERSION.jar \
    https://releases.delos.local/delos-$VERSION.jar"

  # 2. Verify checksum
  EXPECTED_CHECKSUM=$(cat releases/$VERSION.sha256)
  ACTUAL_CHECKSUM=$(ssh delos@$NODE "sha256sum /tmp/delos-$VERSION.jar | cut -d' ' -f1")
  [ "$EXPECTED_CHECKSUM" = "$ACTUAL_CHECKSUM" ] || exit 1

  # 3. Backup current version
  ssh delos@$NODE "cp /opt/delos/delos-app.jar /opt/delos/delos-app-backup.jar"

  # 4. Install new version
  ssh delos@$NODE "mv /tmp/delos-$VERSION.jar /opt/delos/delos-app.jar"

  # 5. Restart service
  ssh delos@$NODE "sudo systemctl restart delos"

  # 6. Wait for stability
  echo "Waiting for $NODE to stabilize (30s)..."
  sleep 30

  # 7. Verify health
  ssh delos@$NODE "curl -s http://localhost:8080/metrics | grep -q fireflies_view" \
    || { echo "Health check failed!"; exit 1; }

  echo "$NODE updated successfully"
  sleep 5  # Stagger updates
done

echo "All nodes updated to $VERSION"
```

---

## 8. Disaster Recovery

### 8.1 Single Node Failure

**Node goes down (within 1 minute):**
- System continues with consensus (f < n/3)
- Gossip propagates state to replacement

**Recovery:**
```bash
# 1. Fix underlying issue
# 2. Restore KERL from backup (CRITICAL - do not reinitialize)
cp /mnt/backups/delos-latest/kerl.db /opt/delos/data/
# 3. Restart service
sudo systemctl restart delos
# 4. Node catches up via gossip
# 5. Verify in cluster
./tools/verify-identity.sh
```

### 8.2 Total Cluster Failure

**All nodes down (rare):**

```bash
# 1. Restore from latest backup ON ALL NODES
for node in node{1..7}; do
  scp /mnt/backups/delos-latest/*.db delos@$node:/opt/delos/data/
done

# 2. Start nodes in same order (important for view stability)
for node in node{1..7}; do
  ssh delos@$node "sudo systemctl start delos"
  sleep 30
done

# 3. Verify identity continuity
./tools/verify-identity.sh --expect-same-sai $(cat previous-sai.txt)

# 4. Verify all data is present
./tools/validate-state.sh
```

### 8.3 Data Corruption

**If KERL or state is corrupted:**
```bash
# KERL recovery (identity is authoritative):
cp /mnt/backups/delos-latest/kerl.db /opt/delos/data/
# This is safe - KERL is append-only

# SQL-State recovery (rebuild from CHOAM log):
java -cp delos.jar \
  com.hellblazer.delos.tools.RebuildState \
  --choam-log /opt/delos/data/choam.db \
  --output /opt/delos/data/state.h2.db

# Verify consistency
./tools/verify-consistency.sh
```

---

## 9. Scaling Considerations

### 9.1 Adding Nodes (f → f+1)

Not supported for Byzantine quorum (f < n/3). Instead:
1. Plan to n+3 nodes (allows f+1 faults)
2. Set up new nodes (Section 2-3)
3. Perform controlled view change:
   ```bash
   ./tools/reconfigure-membership.sh --add node8,node9,node10
   ```
4. Witness new committee member view
5. Monitor metrics for stability

### 9.2 Removing Nodes (n → n-1)

```bash
./tools/reconfigure-membership.sh --remove node7
# Requires (n-1) >= 3f+1 (e.g., 7 nodes → remove node 7 → 6 nodes (f=1))
```

---

## 10. Pre-Production Security Deployment Checklist

**Important:** This checklist is stratified into two categories:
1. **Current Release:** Features fully implemented and supported
2. **Enhanced Security Roadmap:** Planned improvements for future releases

Before deploying to production, complete all **Current Release** items. **Roadmap** items enhance security posture but are not blockers for initial production deployment.

### Current Release (Required for Production)

Before deploying to production, verify all current release security requirements are met:

### Identity & Key Management

- [ ] **KERI Keys Generated Securely**
  - [ ] Private keys generated on air-gapped machine
  - [ ] Keys never transmitted over network
  - [ ] Key backups in multiple geographic locations
  - [ ] Backup encryption keys documented
  - [ ] Backup storage locations: Off-site, access-controlled

- [ ] **Key Rotation Schedule Documented**
  - [ ] Quarterly key rotation procedure documented
  - [ ] Key rotation tested in staging environment
  - [ ] Key rotation runbook prepared for ops team
  - [ ] Calendar reminders set for rotation dates

- [ ] **KERL Database Backup**
  - [ ] KERL backups automated (daily minimum)
  - [ ] Backup retention policy: 30 days minimum
  - [ ] Backup encryption enabled
  - [ ] Backup integrity verification automated
  - [ ] Restore procedure tested quarterly

### TLS/MTLS Configuration

- [ ] **Certificates Generated Properly**
  - [ ] CA-signed certificates (not self-signed in production)
  - [ ] Subject Alternative Names (SANs) include all node hostnames
  - [ ] Certificate validity verified (`openssl x509 -in cert.pem -text -noout`)
  - [ ] Certificate chains verified (`openssl verify -CAfile ca.pem cert.pem`)

- [ ] **Secure Password Management**
  - [ ] No hardcoded passwords in configuration files
  - [ ] All passwords >= 20 characters (use: `openssl rand -base64 20`)
  - [ ] Environment variables used for password injection
  - [ ] Password rotation procedure documented
  - [ ] Password access logging enabled

- [ ] **Certificate Rotation Plan**
  - [ ] Certificate expiration dates tracked (30-day alert threshold)
  - [ ] Certificate rotation procedure tested
  - [ ] Rolling update strategy documented
  - [ ] Zero-downtime rotation verified
  - [ ] Rollback procedure prepared

### Network & Firewall

- [ ] **Network Isolation**
  - [ ] Cluster network isolated from untrusted networks
  - [ ] Firewall rules restrict GRPC ports to authorized nodes only
  - [ ] Firewall rules restrict metrics port (8080) to ops/monitoring networks
  - [ ] SSH access restricted to bastion or VPN
  - [ ] Outbound egress filtered (no unexpected outbound connections)

- [ ] **Port Configuration**
  - [ ] Port 50051 (GRPC consensus): Node-to-node only
  - [ ] Port 50052 (GRPC identity): Witness network only
  - [ ] Port 8080 (metrics/health): Monitoring network only
  - [ ] Application ports: Restricted to authorized clients
  - [ ] No ports exposed to public internet

- [ ] **Network Monitoring**
  - [ ] Network traffic monitoring enabled
  - [ ] Anomalous traffic alerting configured (via firewall/monitoring tools)
  - [ ] Connection logging enabled for audit trail
  - [ ] Network baseline established for anomaly detection

### File System & Permissions

- [ ] **Directory Permissions**
  - [ ] `/opt/delos` owned by `delos` user (600 or 750)
  - [ ] `/opt/delos/keys` owned by `delos` user (700 - no group/other access)
  - [ ] `/var/lib/delos` owned by `delos` user (700)
  - [ ] `/etc/delos` readable by `delos` user only (600)
  - [ ] Verified: `ls -la /opt/delos /var/lib/delos /etc/delos`

- [ ] **File Encryption**
  - [ ] Keystore files stored with secure permissions (600)
  - [ ] Configuration files with secrets readable by delos user only
  - [ ] Disk-level encryption enabled on volume (if available)

- [ ] **Audit Logging**
  - [ ] Application logging configured (SLF4J/Logback)
  - [ ] Key operation events logged (identity operations, rotations)
  - [ ] System logging enabled (systemd journal)
  - [ ] Log retention configured: 30 days minimum
  - [ ] Logs protected from unauthorized access (readable by delos/root only)

### Database & State

- [ ] **H2 Database Hardening**
  - [ ] H2 database password configured (strong, >= 20 chars)
  - [ ] Database stored with 600 permissions (user-only)
  - [ ] Database backups encrypted
  - [ ] Remote H2 access disabled (localhost only)

- [ ] **CHOAM Log Security**
  - [ ] Consensus log integrity verified (checksums)
  - [ ] Log backups encrypted and archived
  - [ ] Log access restricted to delos user
  - [ ] Audit trail captures all commits

- [ ] **Checkpoint Management**
  - [ ] Checkpoints encrypted at rest
  - [ ] Checkpoint backups retained (recovery point objective)
  - [ ] Checkpoint restore tested quarterly
  - [ ] Checkpoint metadata integrity verified

### Operational Security

- [ ] **Service Account**
  - [ ] `delos` service account created (non-shell user)
  - [ ] Service account UID/GID documented
  - [ ] Service account has no sudo privileges
  - [ ] Service account login disabled

- [ ] **Systemd Service**
  - [ ] Service runs as `delos` user (not root)
  - [ ] Service file permissions 644
  - [ ] Service restart policy: `on-failure` with backoff
  - [ ] Service logs captured via journalctl
  - [ ] Service socket activation disabled

- [ ] **Logging & Monitoring**
  - [ ] SLF4J/Logback configured for structured logging
  - [ ] Log files rotate daily (logrotate configured)
  - [ ] Log retention: 30 days minimum
  - [ ] Logs exclude sensitive data (passwords, keys)
  - [ ] Metrics endpoint secured (requires authentication)

### Backup & Disaster Recovery

- [ ] **Backup Strategy**
  - [ ] Daily automated backups scheduled
  - [ ] Backup destinations: Off-site encrypted storage (3+ locations)
  - [ ] Backup encryption: AES-256 minimum
  - [ ] Backup integrity: SHA-256 checksums verified
  - [ ] Backup retention: 30 days minimum

- [ ] **Restore Testing**
  - [ ] Restore procedure tested quarterly
  - [ ] Restore time objective (RTO) documented and verified
  - [ ] Recovery point objective (RPO) documented and verified
  - [ ] Restore runbook prepared for ops team
  - [ ] Restore test results documented

### Vulnerability Management

- [ ] **Dependencies Updated**
  - [ ] All Maven dependencies at current patch level
  - [ ] Dependency security scan completed (`mvn dependency-check`)
  - [ ] Known vulnerabilities risk assessment completed
  - [ ] Vulnerability remediation plan for critical/high issues
  - [ ] Dependency update schedule: Monthly minimum

- [ ] **Code Security**
  - [ ] OWASP security analysis completed
  - [ ] Code review for cryptographic operations
  - [ ] Input validation verified (SQL injection, XSS protection)
  - [ ] Secrets management verified (no hardcoded credentials)
  - [ ] Security testing results documented

### Personnel & Access Control

- [ ] **Access Control**
  - [ ] SSH keys for all operators (no passwords)
  - [ ] SSH key access log enabled
  - [ ] Multi-person approval for sensitive operations
  - [ ] Operator roles defined (viewer, operator, admin)
  - [ ] Access revoked when operators leave

- [ ] **Secrets Management**
  - [ ] Secrets stored in environment variables or encrypted configuration files
  - [ ] Password rotations documented and scheduled
  - [ ] Access to secret storage controlled (file permissions/access control)
  - [ ] Secrets never logged or exposed in error messages
  - [ ] Configuration file with secrets readable by delos user only (600 permissions)

### Documentation & Procedures

- [ ] **Runbooks Prepared**
  - [ ] Deployment runbook reviewed and tested
  - [ ] Incident response playbook prepared
  - [ ] Key rotation procedure documented
  - [ ] Backup & restore procedure documented
  - [ ] Failover/failback procedure documented

- [ ] **Security Documentation**
  - [ ] Security policies documented
  - [ ] Threat model reviewed (ADR-0002)
  - [ ] Security training completed for operators
  - [ ] Incident communication plan prepared
  - [ ] Post-incident review process established

### Pre-Deployment Testing

- [ ] **Security Testing**
  - [ ] TLS handshake verified (openssl s_client)
  - [ ] Certificate expiration dates verified
  - [ ] Key rotation tested end-to-end
  - [ ] Backup restore tested on staging
  - [ ] Network isolation verified (firewall rules)

- [ ] **Performance Testing**
  - [ ] Load testing completed (target TPS achieved)
  - [ ] Consensus latency verified (< 500ms p95)
  - [ ] Member join time verified (< 5 minutes)
  - [ ] Failover time verified (< 10 seconds)
  - [ ] Resource limits verified (CPU, memory, disk)

---

### Enhanced Security Roadmap (Future Releases)

The following items enhance security posture but are **not required** for current production deployment. Plan to implement these in future releases:

**Identity & Key Management**
- [ ] Hardware Security Module (HSM) integration for key storage
- [ ] Key escrow and recovery procedures with multi-party control
- [ ] Hardware-based attestation for identity bootstrap

**Secrets Management**
- [ ] Vault (HashiCorp) or Cloud KMS integration for centralized secret management
- [ ] Dynamic secrets with automatic rotation
- [ ] Audit logging for all secret access operations
- [ ] Secret rotation automation

**Network Security**
- [ ] Intrusion Detection System (IDS) integration for anomaly detection
- [ ] DDoS protection (WAF, rate limiting)
- [ ] Network segmentation with zero-trust architecture
- [ ] VPN/TLS encryption for all internal communication

**File System & Encryption**
- [ ] Transparent Data Encryption (TDE) at rest for all databases
- [ ] File-level encryption for configuration and key material
- [ ] File integrity monitoring (FIM) for critical files
- [ ] Immutable backup storage

**Operational Security**
- [ ] Hardware-based Multi-factor Authentication (MFA) for operator access
- [ ] Behavioral analytics for anomalous activity detection
- [ ] Centralized log aggregation with SIEM integration
- [ ] Automated incident response playbooks

**Compliance & Auditing**
- [ ] FIPS 140-2 Level 2+ mode support
- [ ] Compliance reporting (SOC 2, ISO 27001)
- [ ] Automated policy enforcement
- [ ] Real-time audit trail with tamper-proof logging

---

### Sign-Off

| Role | Name | Date | Approved |
|------|------|------|----------|
| Security Officer | _________________ | ________ | ☐ |
| Operations Lead | _________________ | ________ | ☐ |
| System Admin | _________________ | ________ | ☐ |
| Project Manager | _________________ | ________ | ☐ |

**All items checked and approved before production deployment.**

---

## References

- [KERI Specification](https://github.com/decentralized-identity/keri)
- [KERI Implementation Architecture](adr/0002-keri-implementation-architecture.md)
- [Fireflies: Gossip-Based Byzantine Fault Tolerance](../fireflies/README.md)
- [CHOAM: Consensus Design](adr/0004-consensus-design-choam.md)
- [Troubleshooting Guide](TROUBLESHOOTING_GUIDE.md)
- [Monitoring Guide](MONITORING_GUIDE.md)
