# Delos Configuration Guide

**Document Version**: 2.0
**Date**: 2026-01-27
**Status**: Production
**Audience**: DevOps engineers, operations, system administrators

---

## Quick Reference: Essential Parameters

```yaml
# Minimal production configuration
identity:
  keystore: /opt/delos/keys/identity.jks
  keystore_password: ${DELOS_KEYSTORE_PASSWORD}  # From env var

grpc:
  port: 50051
  mtls:
    enabled: true
    keystore: /opt/delos/keys/node1-keystore.p12

fireflies:
  member_count: 7           # Must match cluster size
  gossip_interval: 100ms

choam:
  batch_size: 100
  batch_timeout: 100ms

sql_state:
  h2_url: jdbc:h2:/opt/delos/data/state;MODE=PostgreSQL
  connection_pool_size: 20
```

---

## Table of Contents

1. [Configuration Overview](#configuration-overview)
2. [Configuration Methods](#configuration-methods)
3. [Identity Configuration](#identity-configuration)
4. [Network Configuration](#network-configuration)
5. [Consensus Configuration](#consensus-configuration)
6. [Database Configuration](#database-configuration)
7. [Performance Tuning Parameters](#performance-tuning-parameters)
8. [Monitoring & Logging](#monitoring--logging)
9. [Phase 1C: BLS Configuration](#phase-1c-bls-configuration)
10. [Common Configuration Scenarios](#common-configuration-scenarios)

---

## Configuration Overview

### Configuration Priority (Highest → Lowest)

1. **Command-line arguments**: `--config`, `--property`
2. **Environment variables**: `${DELOS_*}`
3. **Configuration file**: `delos.yaml` or `delos.properties`
4. **Defaults**: Built-in defaults

### Configuration File Formats

**YAML (Recommended)**:
```yaml
# delos.yaml
identity:
  keystore: /path/to/keystore.jks
  keystore_password: ${DELOS_KEYSTORE_PASSWORD}

grpc:
  port: 50051
```

**Properties**:
```properties
# delos.properties
identity.keystore=/path/to/keystore.jks
identity.keystore_password=${DELOS_KEYSTORE_PASSWORD}
grpc.port=50051
```

**Environment Variables**:
```bash
export DELOS_IDENTITY_KEYSTORE=/path/to/keystore.jks
export DELOS_KEYSTORE_PASSWORD=secret
export DELOS_GRPC_PORT=50051
```

---

## Configuration Methods

### Method 1: Configuration File (YAML)

**Create `/opt/delos/config/delos.yaml`**:

```bash
sudo mkdir -p /opt/delos/config
sudo nano /opt/delos/config/delos.yaml
sudo chmod 600 /opt/delos/config/delos.yaml
```

**Start with config file**:

```bash
java -cp delos.jar \
  -Dconfig.file=/opt/delos/config/delos.yaml \
  com.hellblazer.delos.node.DelosNode
```

### Method 2: Environment Variables

**Create `/opt/delos/config/.env`**:

```bash
export DELOS_IDENTITY_KEYSTORE=/opt/delos/keys/identity.jks
export DELOS_KEYSTORE_PASSWORD=`cat /opt/delos/secrets/keystore.pwd`
export DELOS_GRPC_PORT=50051
export DELOS_FIREFLIES_MEMBER_COUNT=7
export JAVA_OPTS="-Xmx8g -Xms4g"
```

**Load before starting**:

```bash
source /opt/delos/config/.env
java -cp delos.jar com.hellblazer.delos.node.DelosNode
```

### Method 3: Systemd Service

**Create `/etc/systemd/system/delos.service`**:

```ini
[Unit]
Description=Delos Byzantine Consensus Node
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=delos
Group=delos
WorkingDirectory=/opt/delos

# Load environment variables
EnvironmentFile=/opt/delos/config/.env

# Set JVM options
Environment="JAVA_OPTS=-Xmx8g -Xms4g -XX:+UseG1GC"

# Start command
ExecStart=/usr/bin/java \
  ${JAVA_OPTS} \
  -cp /opt/delos/delos.jar \
  -Dconfig.file=/opt/delos/config/delos.yaml \
  com.hellblazer.delos.node.DelosNode

# Restart on failure
Restart=on-failure
RestartSec=5s

# Logging
StandardOutput=journal
StandardError=journal
SyslogIdentifier=delos

# Process management
KillMode=mixed
TimeoutStopSec=30s

[Install]
WantedBy=multi-user.target
```

**Enable and start**:

```bash
sudo systemctl daemon-reload
sudo systemctl enable delos
sudo systemctl start delos
sudo systemctl status delos
```

---

## Identity Configuration

### KERI Identity Setup

**Parameter**: `identity` section

```yaml
identity:
  # Required: Path to KERI identity keystore
  keystore: /opt/delos/keys/identity.jks

  # Required: Keystore password (use env var)
  keystore_password: ${DELOS_KEYSTORE_PASSWORD}

  # Optional: Keystore type (default: JKS)
  keystore_type: JKS

  # Optional: Key alias in keystore (default: first key)
  key_alias: delos-node1
```

### Generating Identity Keys

**On secure offline machine**:

```bash
# Generate Ed25519 keypair
keytool -genkey -alias delos-node1 \
  -keyalg EC -keysize 256 \
  -keystore /secure/node1-identity.jks \
  -storepass "${KEYSTORE_PASSWORD}" \
  -dname "CN=delos-node1,O=MyOrg,L=Secure,ST=State,C=US"

# Export public key for peer exchange
keytool -exportcert -alias delos-node1 \
  -keystore /secure/node1-identity.jks \
  -storepass "${KEYSTORE_PASSWORD}" \
  -rfc > /secure/node1-public.pem
```

---

## Network Configuration

### GRPC Server Configuration

**Parameters**: `grpc` section

```yaml
grpc:
  # Port for GRPC server (default: 50051)
  port: 50051

  # Host binding (default: 0.0.0.0 = all interfaces)
  host: 0.0.0.0

  # Keep-alive settings
  enable_keep_alive: true
  keep_alive_time: 30s         # Send ping every 30s
  keep_alive_timeout: 10s      # Expect pong within 10s
  keep_alive_without_calls: false  # Allow ping without active calls

  # Maximum message size (default: 4MB)
  max_inbound_message_size: 4194304
  max_outbound_message_size: 4194304

  # MTLS Configuration
  mtls:
    enabled: true
    # PKCS12 format (recommended)
    keystore: /opt/delos/keys/node1-keystore.p12
    keystore_password: ${DELOS_KEYSTORE_PASSWORD}
    keystore_type: PKCS12

    # CA certificate for client verification
    truststore: /opt/delos/keys/ca-cert.pem
    truststore_type: PEM

    # Require client authentication
    require_client_auth: true

    # Certificate validation
    verify_hostname: true
```

### Peer Configuration

**Parameters**: `network.peers` section

```yaml
network:
  # Peer definitions (used for discovery)
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
```

---

## Consensus Configuration

### Fireflies (Membership Service)

**Parameters**: `fireflies` section

```yaml
fireflies:
  # Total number of nodes in cluster (critical)
  member_count: 7

  # Number of nodes sampled in gossip rounds
  sample_size: 7  # For BFT: sample_size = member_count

  # Gossip round interval
  gossip_interval: 100ms

  # Time before member marked as suspected (no message)
  failure_threshold: 5s

  # Time before suspected member considered failed
  suspect_threshold: 30s

  # Time before failed member removed from view
  remove_threshold: 60s

  # Reservoir sampler configuration
  sample_size_target: 7
  confidence_level: 99  # Confidence for gossip convergence
```

### CHOAM (Consensus & State Machine Replication)

**Parameters**: `choam` section

```yaml
choam:
  # Batch size (transactions per block)
  batch_size: 100

  # Maximum wait time for batch before committing
  batch_timeout: 100ms

  # Blocks per checkpoint
  checkpoint_interval: 10000

  # Timeout for view changes
  view_change_timeout: 10s

  # Maximum pending transactions
  max_pending_transactions: 10000
```

### Ethereal (Byzantine Atomic Broadcast)

**Parameters**: `ethereal` section

```yaml
ethereal:
  # DAG vertex timeout
  vertex_timeout: 5s

  # Maximum pending messages
  max_pending_messages: 100000

  # Message compression
  enable_compression: true
  compression_threshold: 1024  # Compress messages >1KB
```

---

## Database Configuration

### H2 Embedded Database

**Parameters**: `sql_state` section

```yaml
sql_state:
  # JDBC connection string
  # MODE=PostgreSQL for compatibility with PostgreSQL SQL
  h2_url: jdbc:h2:/opt/delos/data/state;MODE=PostgreSQL;TRACE_LEVEL_SYSTEM_OUT=3

  # H2 user (default: sa)
  h2_user: sa

  # H2 password (default: empty, change in production)
  h2_password: ${DELOS_DB_PASSWORD}

  # Connection pool size
  connection_pool_size: 20

  # Maximum connection idle time
  max_idle_time: 300s

  # Enable auto-shutdown
  auto_shutdown: true
```

### External PostgreSQL Database

**Parameters**: `sql_state` section (alternative)

```yaml
sql_state:
  # PostgreSQL JDBC URL
  db_url: jdbc:postgresql://postgres.internal:5432/delos
  db_user: delos
  db_password: ${DELOS_DB_PASSWORD}
  db_driver: org.postgresql.Driver

  # Connection pool
  connection_pool_size: 30
  max_idle_time: 300s
```

---

## Performance Tuning Parameters

### JVM Tuning

**Parameters**: `java` section or environment variable

```yaml
java:
  # Heap size (set to 50-70% of available memory)
  heap_min: 4g
  heap_max: 8g

  # GC configuration (G1GC recommended)
  gc_type: G1GC

  # GC logging
  gc_log: /opt/delos/logs/gc.log
  gc_log_filesize: 100m
  gc_log_files: 5
```

**Via environment**:

```bash
export JAVA_OPTS="-Xmx8g -Xms4g -XX:+UseG1GC"
```

### Cache Tuning

**Parameters**: `cache` section

```yaml
cache:
  # Transaction cache size (transactions)
  transaction_cache_size: 10000

  # Block cache size (blocks)
  block_cache_size: 1000

  # Member cache size (view members)
  member_cache_size: 100
```

### Threading

**Parameters**: `threading` section

```yaml
threading:
  # Consensus thread count
  consensus_threads: 4

  # Gossip thread count
  gossip_threads: 2

  # I/O thread count
  io_threads: 8
```

---

## Monitoring & Logging

### Metrics Collection

**Parameters**: `metrics` section

```yaml
metrics:
  # Enable metrics collection
  enabled: true

  # HTTP metrics port
  http_port: 8080

  # Metrics path
  metrics_path: /metrics

  # Health check path
  health_path: /health

  # Deep health check path
  health_deep_path: /health/deep
```

### Prometheus Metrics

```yaml
metrics:
  reporters:
    - type: prometheus
      enabled: true
      path: /metrics
      port: 8080
      # Update interval
      interval: 60s
```

### Logging Configuration

**Parameters**: `logging` section

```yaml
logging:
  # Log level (TRACE, DEBUG, INFO, WARN, ERROR)
  level: INFO

  # Log file location
  file: /opt/delos/logs/delos.log

  # Maximum file size before rotation
  max_file_size: 100MB

  # Number of backup files to keep
  backup_count: 10

  # Log format
  pattern: "%d{ISO8601} [%t] %-5p %c{1} - %m%n"
```

**Per-module logging**:

```yaml
logging:
  modules:
    fireflies: DEBUG      # Verbose gossip logging
    ethereal: INFO        # Standard consensus logging
    choam: INFO           # State machine replication
    stereotomy: INFO      # KERI identity
```

---

## Phase 1C: BLS Configuration

### BLS Key Store

**Parameters**: `bls` section

```yaml
bls:
  # Path to BLS key store
  keystore: /opt/delos/keys/bls-keystore.jks

  # Keystore password
  keystore_password: ${DELOS_BLS_PASSWORD}

  # Key alias
  key_alias: bls-node1
```

### Byzantine Detection

**Parameters**: `byzantine_detection` section

```yaml
byzantine_detection:
  # Enable detection
  enabled: true

  # Detection window (sliding window for anomaly scoring)
  detection_window: 5m

  # Equivocation detection
  equivocation:
    enabled: true
    threshold: 2  # Two different signatures for same message

  # Timing anomaly detection
  timing_anomaly:
    enabled: true
    threshold: 3s  # Deviation threshold

  # Rate anomaly detection
  rate_anomaly:
    enabled: true
    threshold: 50  # % failure rate spike

  # Response action
  response_action: shun  # shun, alert, quarantine, key_rotation
```

---

## Common Configuration Scenarios

### Scenario 1: Small Production (7-node)

**Use case**: Startup, <10K tx/sec

```yaml
identity:
  keystore: /opt/delos/keys/identity.jks
  keystore_password: ${DELOS_KEYSTORE_PASSWORD}

grpc:
  port: 50051
  mtls:
    enabled: true
    keystore: /opt/delos/keys/node1-keystore.p12
    keystore_password: ${DELOS_KEYSTORE_PASSWORD}

fireflies:
  member_count: 7
  sample_size: 7
  gossip_interval: 100ms

choam:
  batch_size: 100
  batch_timeout: 100ms
  checkpoint_interval: 10000

sql_state:
  h2_url: jdbc:h2:/opt/delos/data/state;MODE=PostgreSQL
  connection_pool_size: 20

metrics:
  enabled: true
  http_port: 8080

logging:
  level: INFO
  file: /opt/delos/logs/delos.log
```

### Scenario 2: High-Throughput (13-node)

**Use case**: Enterprise, 100K+ tx/sec

```yaml
# Same as Scenario 1, with additions:

fireflies:
  member_count: 13
  sample_size: 13
  gossip_interval: 50ms   # Faster gossip
  failure_threshold: 3s    # Faster failure detection

choam:
  batch_size: 500         # Larger batches
  batch_timeout: 50ms     # Shorter timeout
  checkpoint_interval: 5000

threading:
  consensus_threads: 8
  gossip_threads: 4
  io_threads: 16

cache:
  transaction_cache_size: 50000
```

### Scenario 3: Development (4-node)

```yaml
# Minimal configuration
fireflies:
  member_count: 4
  sample_size: 4

choam:
  batch_size: 10      # Small batches for testing
  batch_timeout: 200ms

sql_state:
  h2_url: jdbc:h2:mem:state;MODE=PostgreSQL  # In-memory for testing

logging:
  level: DEBUG        # Verbose for debugging
```

---

## Validation Checklist

Before starting production cluster:

- [ ] All required parameters set (no defaults used for production)
- [ ] KERI identity keys generated and backed up
- [ ] TLS certificates valid and installed
- [ ] Fireflies member_count = cluster size
- [ ] CHOAM batch_size appropriate for throughput
- [ ] Database storage sized for retention period
- [ ] Metrics collection enabled
- [ ] Logging configured and rotated
- [ ] All environment variables set correctly
- [ ] Configuration file permissions restricted (chmod 600)
- [ ] Secrets not in version control
- [ ] NTP synchronized across cluster
- [ ] Network connectivity verified

---

## Related Documentation

- [Deployment Guide](DEPLOYMENT_GUIDE.md) - Setup procedures
- [Hardware Requirements](HARDWARE_REQUIREMENTS.md) - Sizing and capacity
- [Monitoring Guide](MONITORING_GUIDE.md) - Health checks and metrics
- [Performance Tuning](PERFORMANCE_TUNING.md) - Optimization guidelines
- [OPS Runbook](OPS_RUNBOOK_PHASE_1C.md) - Operational procedures
