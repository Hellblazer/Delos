# CHOAM Operator Guide

Production deployment, configuration, monitoring, and troubleshooting guide for CHOAM operators.

**Version**: 0.0.6-SNAPSHOT
**Last Updated**: 2026-02-08
**Target Audience**: DevOps, SREs, Platform Engineers

---

## Table of Contents

- [Quick Start](#quick-start)
- [Deployment](#deployment)
- [Configuration](#configuration)
- [Monitoring](#monitoring)
- [Troubleshooting](#troubleshooting)
- [Performance Tuning](#performance-tuning)
- [Security Hardening](#security-hardening)
- [Disaster Recovery](#disaster-recovery)
- [Upgrade and Migration](#upgrade-and-migration)

---

## Quick Start

### Prerequisites

- **Java 24+** (required for production)
- **8 GB+ RAM** per node (minimum 2 GB for development)
- **Network**: All committee members must reach each other (Byzantine fault tolerance requires full mesh)
- **Clock skew**: < 500ms between nodes (use NTP)
- **Ports**: Configure firewall for GRPC communication (default: dynamic allocation)

### Minimal 4-Node Cluster (f=1 Byzantine tolerance)

```bash
# 1. Build from source
./mvnw clean install -DskipTests

# 2. Generate KERI identities for each node
java -cp choam/target/choam-0.0.6-SNAPSHOT.jar \
  com.hellblazer.delos.stereotomy.Stereotomy generate --id node1

# Repeat for node2, node3, node4

# 3. Create genesis configuration
cat > genesis.yaml <<EOF
members:
  - id: node1
    endpoint: node1.example.com:30000
  - id: node2
    endpoint: node2.example.com:30000
  - id: node3
    endpoint: node3.example.com:30000
  - id: node4
    endpoint: node4.example.com:30000

bft:
  tolerance: 1          # f=1: tolerate 1 Byzantine failure
  quorum: 3             # 2f+1

profile: PRODUCTION     # See Configuration Profiles section
EOF

# 4. Start each node
# Node 1:
java -jar choam.jar --config genesis.yaml --member node1

# Node 2-4:
java -jar choam.jar --config genesis.yaml --member node2
# ... (repeat for node3, node4)
```

**Validation**: All 4 nodes should form a committee and reach consensus within 30 seconds.

```bash
# Check node status
curl http://node1:8080/metrics | grep choam_view_epoch
# Should show: choam_view_epoch{view="..."} 0

# Submit test transaction
curl -X POST http://node1:8080/api/submit \
  -H "Content-Type: application/json" \
  -d '{"data": "test transaction"}'
# Should return: {"status": "committed", "receipt": "..."}
```

---

## Deployment

### Deployment Topologies

#### 1. Single Committee (Development/Testing)

```
┌──────────────────────────────────────────────┐
│  Committee (4 nodes, f=1 Byzantine tolerance) │
│  ┌──────┐  ┌──────┐  ┌──────┐  ┌──────┐     │
│  │ Node1│  │ Node2│  │ Node3│  │ Node4│     │
│  └──────┘  └──────┘  └──────┘  └──────┘     │
│                                              │
│  Throughput: ~10K-50K tx/sec                │
│  Latency: p95 < 1s                          │
└──────────────────────────────────────────────┘
```

**Use case**: Development, testing, small production workloads

**Configuration**:
```yaml
profile: PRODUCTION
bft:
  tolerance: 1
committee:
  size: 4
```

#### 2. Multi-Committee (Production Scaling)

```
┌────────────────────────────────────────────────────────┐
│  Committee 1          Committee 2          Committee 3  │
│  (keys 0x00-0x55)    (keys 0x55-0xAA)    (keys 0xAA-FF)│
│  ┌────┐ ┌────┐      ┌────┐ ┌────┐      ┌────┐ ┌────┐ │
│  │ N1 │ │ N2 │      │ N3 │ │ N4 │      │ N5 │ │ N6 │ │
│  │ N7 │ │ N8 │      │ N9 │ │N10 │      │N11 │ │N12 │ │
│  └────┘ └────┘      └────┘ └────┘      └────┘ └────┘ │
│                                                        │
│  Total Throughput: ~100K-500K tx/sec                  │
│  Horizontal Scaling: Linear with committee count      │
└────────────────────────────────────────────────────────┘
```

**Use case**: High-throughput production workloads

**Configuration**:
```yaml
profile: PRODUCTION
sharding:
  enabled: true
  committees: 3
  key_distribution: consistent_hash
```

#### 3. Multi-Region (Geographic Distribution)

```
┌─────────────────────┐   ┌─────────────────────┐   ┌─────────────────────┐
│   us-east-1         │   │   eu-west-1         │   │   ap-southeast-1    │
│   ┌────┐ ┌────┐     │   │   ┌────┐ ┌────┐     │   │   ┌────┐            │
│   │ N1 │ │ N2 │     │   │   │ N3 │ │ N4 │     │   │   │ N5 │            │
│   └────┘ └────┘     │   │   └────┘ └────┘     │   │   └────┘            │
└─────────────────────┘   └─────────────────────┘   └─────────────────────┘
           │                        │                          │
           └────────────────────────┴──────────────────────────┘
                       Cross-region GRPC (MTLS)

Latency: p95 < 500ms (cross-region consensus)
Fault tolerance: Survives single region failure (with 7+ nodes, f=2)
```

**Use case**: Global deployments, disaster recovery

**Configuration**:
```yaml
profile: PRODUCTION
regions:
  - us-east-1
  - eu-west-1
  - ap-southeast-1
bft:
  tolerance: 2  # f=2: requires 7 nodes minimum
```

### Container Deployment (Docker/Kubernetes)

#### Docker Compose

```yaml
# docker-compose.yml
version: '3.8'
services:
  choam-node1:
    image: delos/choam:0.0.6-SNAPSHOT
    environment:
      - CHOAM_PROFILE=PRODUCTION
      - MEMBER_ID=node1
      - JAVA_OPTS=-Xmx8G -Xms4G
    volumes:
      - ./data/node1:/data
      - ./config:/config
    ports:
      - "30000:30000"
    networks:
      - choam-network

  choam-node2:
    image: delos/choam:0.0.6-SNAPSHOT
    environment:
      - CHOAM_PROFILE=PRODUCTION
      - MEMBER_ID=node2
      - JAVA_OPTS=-Xmx8G -Xms4G
    volumes:
      - ./data/node2:/data
      - ./config:/config
    ports:
      - "30001:30000"
    networks:
      - choam-network

  # ... node3, node4 similar

networks:
  choam-network:
    driver: bridge
```

**Start cluster**:
```bash
docker-compose up -d
docker-compose logs -f  # Monitor startup
```

#### Kubernetes StatefulSet

```yaml
# choam-statefulset.yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: choam
spec:
  serviceName: choam
  replicas: 4
  selector:
    matchLabels:
      app: choam
  template:
    metadata:
      labels:
        app: choam
    spec:
      containers:
      - name: choam
        image: delos/choam:0.0.6-SNAPSHOT
        env:
        - name: CHOAM_PROFILE
          value: "PRODUCTION"
        - name: MEMBER_ID
          valueFrom:
            fieldRef:
              fieldPath: metadata.name
        - name: JAVA_OPTS
          value: "-Xmx8G -Xms4G -XX:+UseG1GC"
        resources:
          requests:
            memory: "8Gi"
            cpu: "4"
          limits:
            memory: "12Gi"
            cpu: "8"
        volumeMounts:
        - name: data
          mountPath: /data
        ports:
        - containerPort: 30000
          name: grpc
        livenessProbe:
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /ready
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 5
  volumeClaimTemplates:
  - metadata:
      name: data
    spec:
      accessModes: [ "ReadWriteOnce" ]
      resources:
        requests:
          storage: 100Gi
```

**Deploy**:
```bash
kubectl apply -f choam-statefulset.yaml
kubectl get pods -l app=choam -w  # Watch startup
```

---

## Configuration

### Configuration Profiles

CHOAM provides 4 preconfigured profiles for different environments:

| Profile | Timeouts | Cluster Size | Validation | Use Case |
|---------|----------|--------------|------------|----------|
| **DEVELOPMENT** | Relaxed (10s/60s/120s) | 3-5 nodes (f=1) | Enabled | Local development |
| **PRODUCTION** | Baseline (5s/30s/60s) | 7-21 nodes (f=2-6) | Disabled | Production deployment |
| **TEST** | Fast (2s/15s/30s) | 4-7 nodes (f=1-2) | Enabled | CI/CD testing |
| **BYZANTINE_TEST** | Fast (2s/15s/30s) | 4 nodes (f=1) | Aggressive | Byzantine fault injection |

**Select profile**:
```bash
# System property (highest precedence)
java -Dchoam.profile=PRODUCTION -jar choam.jar

# Environment variable
export CHOAM_PROFILE=PRODUCTION
java -jar choam.jar

# Configuration file
cat > config.yaml <<EOF
profile: PRODUCTION
EOF
java -jar choam.jar --config config.yaml
```

**Resolution order**: System property > Environment variable > Config file > Default (TEST)

### Configuration Parameters

#### Byzantine Fault Tolerance

```yaml
bft:
  tolerance: 2                      # f (Byzantine failures to tolerate)
  quorum: 5                         # 2f+1 (required for consensus)
  committee_size: 7                 # 3f+1 (total committee size)
  signature_aggregation: true       # Enable BLS Crown aggregation
  detection_aggressive: false       # Aggressive Byzantine detection (PRODUCTION: false)
  state_validation: false           # Runtime state validation (PRODUCTION: false for performance)
```

**Validation**: System enforces `committee_size ≥ 3*tolerance + 1`

#### Timeouts

```yaml
timeouts:
  stall: 5s                         # Producer stall detection
  view_change: 30s                  # View reconfiguration timeout
  session: 60s                      # Client session timeout
  bootstrap: 120s                   # New node bootstrap timeout
  checkpoint_interval: 3600s        # Checkpoint creation (1 hour)
```

**Hierarchy constraint**: `session > view_change > stall` (enforced by ProfileValidator)

#### Gossip

```yaml
gossip:
  duration: 1s                      # Gossip round interval
  redundancy: 3                     # Gossip fanout (send to 3 random members)
  buffer_size: 5000                 # Max pending blocks in gossip buffer
```

#### Producer

```yaml
producer:
  batch_size: 1000                  # Max transactions per block
  batch_delay: 100ms                # Max wait for batch accumulation
  max_pending: 5000                 # Max pending blocks (PRODUCTION)
  synchronization_cycles: 15        # Sync cycles before declaring stall (PRODUCTION)
  regeneration_cycles: 30           # Regen cycles before view change (PRODUCTION)
```

**Tuning**: Larger batches increase throughput but add latency

#### Memory Management

```yaml
memory:
  min_free_ratio: 0.10              # Trigger GC when <10% heap free (PRODUCTION)
  max_cached_checkpoints: 10        # Max checkpoints in memory (PRODUCTION)
  checkpoint_compression: true      # Compress checkpoints (saves ~60% space)
```

#### Logging

```yaml
logging:
  level: ERROR                      # PRODUCTION: ERROR (minimal overhead)
  audit: true                       # Enable security audit logging
  metrics: true                     # Enable Dropwizard metrics
  output: /var/log/choam/choam.log
```

### Advanced Configuration

#### Custom State Machine

```java
// Application-specific state machine
public class MyStateMachine implements StateMachine {
    @Override
    public void apply(Block block, StateHolder state) {
        // Custom state transition logic
        for (var tx : block.transactions()) {
            processTransaction(tx, state);
        }
    }

    @Override
    public boolean validate(Transaction tx, StateHolder snapshot) {
        // Custom validation logic
        return isValid(tx, snapshot);
    }
}

// Wire in configuration
Parameters params = Parameters.Builder.from(ConfigurationProfile.PRODUCTION)
    .setStateMachine(new MyStateMachine())
    .build();
```

#### Feature Flags

```yaml
features:
  view_reconfiguration: true        # Enable automatic view rotation
  checkpoint_creation: true         # Enable periodic checkpoints
  bootstrap_from_checkpoint: true   # Allow new nodes to bootstrap from checkpoint
  crown_aggregation: true           # Enable BLS signature aggregation
  byzantine_detection: true         # Enable Byzantine detection
```

---

## Monitoring

### Key Metrics

CHOAM exposes Dropwizard metrics for production monitoring:

#### System Health

```
# View epoch (monotonically increasing)
choam_view_epoch{view="abcd1234"} 42

# Committee size
choam_committee_size 7

# Transaction throughput (tx/sec)
choam_transaction_rate 15000

# Block production rate (blocks/sec)
choam_block_rate 150

# Consensus latency (p50, p95, p99)
choam_consensus_latency{quantile="0.5"} 150ms
choam_consensus_latency{quantile="0.95"} 450ms
choam_consensus_latency{quantile="0.99"} 800ms
```

#### Byzantine Detection

```
# Signature verification failures
choam_byzantine_signature_failures 0

# Timing anomalies detected
choam_byzantine_timing_anomalies 0

# Rate anomalies detected
choam_byzantine_rate_anomalies 0

# Equivocation detected
choam_byzantine_equivocations 0

# Total Byzantine incidents
choam_byzantine_incidents_total 0
```

**Alert on**: Any non-zero Byzantine incident count

#### Resource Utilization

```
# Heap memory usage (bytes)
choam_heap_used 4294967296
choam_heap_max 8589934592

# GC pause time (ms, p99)
choam_gc_pause{quantile="0.99"} 50ms

# Thread count
choam_threads_active 42

# Network I/O (bytes/sec)
choam_network_rx_rate 10485760
choam_network_tx_rate 8388608
```

#### Operational Status

```
# FSM state
choam_fsm_state{fsm="Mercantile"} OPERATIONAL
choam_fsm_state{fsm="Earner"} PRODUCING
choam_fsm_state{fsm="Genesis"} COMPLETE

# View change count
choam_view_changes_total 42

# Checkpoint count
choam_checkpoints_total 12

# Bootstrap count (new nodes joined)
choam_bootstraps_total 3
```

### Prometheus Integration

**Expose metrics endpoint**:

```yaml
metrics:
  prometheus:
    enabled: true
    port: 9090
    path: /metrics
```

**Scrape configuration** (`prometheus.yml`):

```yaml
scrape_configs:
  - job_name: 'choam'
    static_configs:
      - targets:
        - node1:9090
        - node2:9090
        - node3:9090
        - node4:9090
    scrape_interval: 15s
```

**Sample queries**:

```promql
# Transaction throughput
rate(choam_transaction_count[5m])

# Average consensus latency
rate(choam_consensus_latency_sum[5m]) / rate(choam_consensus_latency_count[5m])

# Byzantine incident rate
rate(choam_byzantine_incidents_total[1h])

# Heap memory usage percentage
100 * choam_heap_used / choam_heap_max
```

### Grafana Dashboards

**Import pre-built dashboard**: `choam/grafana/dashboard.json`

**Key panels**:
1. **Transaction Throughput**: Line graph of `rate(choam_transaction_count[5m])`
2. **Consensus Latency**: Heatmap of `choam_consensus_latency` (p50/p95/p99)
3. **Committee Health**: Gauge of `choam_committee_size` vs configured size
4. **Byzantine Incidents**: Counter of `choam_byzantine_incidents_total`
5. **Memory Usage**: Area graph of `choam_heap_used` / `choam_heap_max`
6. **View Changes**: Counter of `choam_view_changes_total`

### Alerting Rules

**Prometheus alerting** (`alerts.yml`):

```yaml
groups:
  - name: choam_alerts
    rules:
      # Critical: Byzantine incident detected
      - alert: ByzantineIncident
        expr: increase(choam_byzantine_incidents_total[5m]) > 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Byzantine behavior detected on {{ $labels.instance }}"
          description: "{{ $value }} Byzantine incidents in last 5 minutes"

      # Critical: Committee size below quorum
      - alert: CommitteeBelowQuorum
        expr: choam_committee_size < 3
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Committee size {{ $value }} below quorum on {{ $labels.instance }}"

      # Warning: High consensus latency
      - alert: HighConsensusLatency
        expr: choam_consensus_latency{quantile="0.95"} > 2000
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High consensus latency (p95: {{ $value }}ms) on {{ $labels.instance }}"

      # Warning: High memory usage
      - alert: HighMemoryUsage
        expr: 100 * choam_heap_used / choam_heap_max > 90
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High heap usage ({{ $value }}%) on {{ $labels.instance }}"

      # Warning: Frequent view changes
      - alert: FrequentViewChanges
        expr: rate(choam_view_changes_total[1h]) > 2
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "Frequent view changes ({{ $value }}/hour) on {{ $labels.instance }}"
```

### Health Checks

**HTTP health endpoints**:

```bash
# Liveness probe (node is running)
curl http://node1:8080/health
# Response: {"status": "UP"}

# Readiness probe (node is operational)
curl http://node1:8080/ready
# Response: {"status": "READY", "view": "abcd1234", "epoch": 42}

# Metrics endpoint
curl http://node1:9090/metrics
# Response: Prometheus metrics (see above)
```

**Kubernetes probes**:

```yaml
livenessProbe:
  httpGet:
    path: /health
    port: 8080
  initialDelaySeconds: 60    # Allow startup time
  periodSeconds: 10
  failureThreshold: 3        # Restart after 3 failures

readinessProbe:
  httpGet:
    path: /ready
    port: 8080
  initialDelaySeconds: 30    # Allow bootstrap time
  periodSeconds: 5
  failureThreshold: 2        # Remove from service after 2 failures
```

---

## Troubleshooting

### Common Issues

#### 1. Node Won't Join Committee

**Symptoms**:
- Node logs `AWAIT_GENESIS` indefinitely
- Metrics show `choam_fsm_state{fsm="Genesis"} AWAIT_GENESIS`

**Diagnosis**:
```bash
# Check network connectivity
ping node2.example.com
telnet node2.example.com 30000

# Check Fireflies membership
curl http://node1:8080/api/membership
# Should list all 4 nodes

# Check for anchor block
curl http://node1:8080/api/blocks/anchor
# Should return valid anchor block
```

**Resolution**:
1. Verify all nodes are reachable (full mesh required for BFT)
2. Check firewall rules (allow GRPC port 30000)
3. Verify KERI identities match genesis configuration
4. Check for clock skew (must be < 500ms): `ntpdate -q pool.ntp.org`

#### 2. Consensus Stalled

**Symptoms**:
- No new blocks produced
- Metrics show `choam_block_rate 0`
- Logs show `Producer stalled, initiating view change`

**Diagnosis**:
```bash
# Check Ethereal consensus
curl http://node1:8080/api/ethereal/status
# Should show: {"status": "running", "units": 42}

# Check producer state
curl http://node1:8080/api/producer/status
# Should show: {"state": "PRODUCING", "pending": 0}

# Check for Byzantine members
curl http://node1:8080/api/byzantine/incidents
# Should show: []
```

**Resolution**:
1. If Ethereal stalled: Check committee size (need ≥ 2f+1 online)
2. If Byzantine member detected: Rotate view to exclude member
3. If producer stalled: Restart affected node(s)
4. Manual view change: `curl -X POST http://node1:8080/api/view/rotate`

#### 3. High Consensus Latency

**Symptoms**:
- Metrics show `choam_consensus_latency{quantile="0.95"} > 2000ms`
- Client transaction timeouts

**Diagnosis**:
```bash
# Measure inter-node latency
ping -c 100 node2.example.com
# Check p95 latency

# Check network bandwidth
iperf3 -c node2.example.com
# Should show > 100 Mbps

# Check CPU usage
top -bn1 | grep java
# CHOAM should not exceed 80% CPU
```

**Resolution**:
1. **Network latency**: Optimize network topology (same region/datacenter)
2. **CPU bottleneck**: Increase CPU allocation or reduce batch size
3. **GC pauses**: Tune JVM GC (see Performance Tuning section)
4. **Large batches**: Reduce `producer.batch_size` to decrease serialization overhead

#### 4. Memory Exhaustion

**Symptoms**:
- `OutOfMemoryError: Java heap space`
- Metrics show `choam_heap_used ≈ choam_heap_max`
- Frequent full GC pauses

**Diagnosis**:
```bash
# Capture heap dump
jmap -dump:format=b,file=heap.bin <pid>

# Analyze with jhat or VisualVM
jhat heap.bin
# Look for memory leaks in block cache, checkpoint cache

# Check GC activity
jstat -gcutil <pid> 1000
# FGC column shows full GC count
```

**Resolution**:
1. **Increase heap**: `-Xmx12G -Xms6G` (PRODUCTION: 8 GB minimum)
2. **Reduce caches**:
   ```yaml
   memory:
     max_cached_checkpoints: 5      # Down from 10
     checkpoint_compression: true   # Enable compression
   producer:
     max_pending: 1000               # Down from 5000
   ```
3. **Enable checkpoint pruning**: Old checkpoints deleted after new checkpoint created
4. **Use G1GC**: `-XX:+UseG1GC -XX:MaxGCPauseMillis=200`

#### 5. Byzantine Member Detected

**Symptoms**:
- Metrics show `choam_byzantine_incidents_total > 0`
- Logs show `Byzantine behavior detected: member=node3, type=SIGNATURE_ANOMALY`

**Diagnosis**:
```bash
# List Byzantine incidents
curl http://node1:8080/api/byzantine/incidents
# Response: [
#   {"member": "node3", "type": "SIGNATURE_ANOMALY", "timestamp": "..."},
#   ...
# ]

# Check signature verification
curl http://node1:8080/api/byzantine/verify-signatures?member=node3
# Response: {"member": "node3", "valid": false, "reason": "Invalid BLS signature"}
```

**Resolution**:
1. **Confirm incident**: Check if multiple honest nodes report same anomaly
2. **Rotate view**: Exclude Byzantine member from next committee
   ```bash
   curl -X POST http://node1:8080/api/view/rotate \
     -H "Content-Type: application/json" \
     -d '{"exclude": ["node3"]}'
   ```
3. **Investigate root cause**:
   - Key compromise? → Rotate KERI identifier
   - Software bug? → Upgrade affected node
   - Malicious actor? → Remove from network permanently
4. **Audit logs**: Review audit logs for security incident investigation

---

## Performance Tuning

### JVM Tuning

**Production JVM flags**:

```bash
java \
  -Xmx8G -Xms4G \                          # Heap size (8 GB max, 4 GB initial)
  -XX:+UseG1GC \                            # G1 garbage collector
  -XX:MaxGCPauseMillis=200 \                # Target max GC pause 200ms
  -XX:+UseStringDeduplication \             # Save memory on duplicate strings
  -XX:+ParallelRefProcEnabled \             # Parallel reference processing
  -XX:G1HeapRegionSize=16M \                # G1 region size
  -XX:InitiatingHeapOccupancyPercent=45 \   # Trigger concurrent mark at 45% heap
  -XX:+UnlockDiagnosticVMOptions \          # Enable diagnostic options
  -XX:+G1SummarizeConcMark \                # Log concurrent mark summary
  -jar choam.jar
```

**Development JVM flags** (faster startup, less optimization):

```bash
java \
  -Xmx2G -Xms2G \                           # Smaller heap for development
  -XX:+UseSerialGC \                        # Simpler GC for single-node
  -XX:TieredStopAtLevel=1 \                 # Disable C2 JIT (faster startup)
  -jar choam.jar
```

### Network Tuning

**Linux kernel parameters** (`/etc/sysctl.conf`):

```bash
# Increase TCP buffer sizes
net.core.rmem_max = 134217728
net.core.wmem_max = 134217728
net.ipv4.tcp_rmem = 4096 87380 67108864
net.ipv4.tcp_wmem = 4096 65536 67108864

# Increase max connections
net.core.somaxconn = 4096
net.ipv4.tcp_max_syn_backlog = 4096

# Enable TCP fast open
net.ipv4.tcp_fastopen = 3

# Reduce TIME_WAIT recycling
net.ipv4.tcp_tw_reuse = 1
```

**Apply changes**:
```bash
sudo sysctl -p
```

### Batch Size Tuning

**Trade-off**: Larger batches = higher throughput, higher latency

**Experiment**:
```yaml
# Test configurations
producer:
  batch_size: 100       # Latency: p95 200ms, Throughput: 5K tx/sec
  batch_size: 500       # Latency: p95 500ms, Throughput: 15K tx/sec
  batch_size: 1000      # Latency: p95 1000ms, Throughput: 30K tx/sec
  batch_size: 5000      # Latency: p95 3000ms, Throughput: 50K tx/sec
```

**Recommendation**:
- **Low latency** (< 500ms): `batch_size: 500`
- **High throughput** (> 30K tx/sec): `batch_size: 2000`

### Gossip Redundancy Tuning

**Trade-off**: Higher redundancy = faster dissemination, more network bandwidth

```yaml
# Test configurations
gossip:
  redundancy: 1         # Minimal bandwidth, slowest dissemination
  redundancy: 3         # Balanced (PRODUCTION default)
  redundancy: 5         # Fastest dissemination, highest bandwidth
```

**Measurement**:
```promql
# Block dissemination time (time from producer to all nodes)
histogram_quantile(0.95, rate(choam_block_dissemination_seconds_bucket[5m]))
```

**Recommendation**:
- **Low bandwidth**: `redundancy: 2`
- **Fast dissemination**: `redundancy: 5`

---

## Security Hardening

### MTLS Configuration

**Require mutual TLS for all GRPC connections**:

```yaml
security:
  mtls:
    enabled: true
    ca_cert: /etc/choam/ca.crt
    server_cert: /etc/choam/server.crt
    server_key: /etc/choam/server.key
    client_cert: /etc/choam/client.crt
    client_key: /etc/choam/client.key
    verify_client: true               # Require client certificates
    cipher_suites:                    # Restrict cipher suites
      - TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
      - TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
```

**Generate certificates** (production: use CA-signed certificates):

```bash
# Generate CA
openssl req -x509 -newkey rsa:4096 -keyout ca.key -out ca.crt -days 3650 -nodes

# Generate server certificate
openssl req -newkey rsa:4096 -keyout server.key -out server.csr -nodes
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out server.crt -days 365

# Generate client certificate
openssl req -newkey rsa:4096 -keyout client.key -out client.csr -nodes
openssl x509 -req -in client.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out client.crt -days 365
```

### Firewall Rules

**iptables configuration** (example for 4-node cluster):

```bash
# Allow GRPC traffic between committee members
iptables -A INPUT -p tcp --dport 30000 -s node1.example.com -j ACCEPT
iptables -A INPUT -p tcp --dport 30000 -s node2.example.com -j ACCEPT
iptables -A INPUT -p tcp --dport 30000 -s node3.example.com -j ACCEPT
iptables -A INPUT -p tcp --dport 30000 -s node4.example.com -j ACCEPT

# Allow metrics endpoint from monitoring server
iptables -A INPUT -p tcp --dport 9090 -s monitoring.example.com -j ACCEPT

# Deny all other traffic
iptables -A INPUT -p tcp --dport 30000 -j DROP
iptables -A INPUT -p tcp --dport 9090 -j DROP
```

### Audit Logging

**Enable security audit logs**:

```yaml
logging:
  audit:
    enabled: true
    output: /var/log/choam/audit.log
    events:
      - BYZANTINE_INCIDENT
      - VIEW_CHANGE
      - MEMBER_JOIN
      - MEMBER_LEAVE
      - SIGNATURE_FAILURE
      - UNAUTHORIZED_ACCESS
```

**Sample audit log entry**:

```json
{
  "timestamp": "2026-02-08T10:30:45.123Z",
  "event": "BYZANTINE_INCIDENT",
  "severity": "CRITICAL",
  "member": "node3",
  "incident_type": "SIGNATURE_ANOMALY",
  "details": {
    "block_height": 12345,
    "block_hash": "abcd1234...",
    "expected_signature": "xyz...",
    "actual_signature": "invalid..."
  },
  "action": "VIEW_ROTATION_INITIATED"
}
```

### Key Rotation

**Rotate KERI identifiers** (recommended: annually or after suspected compromise):

```bash
# Generate new KERI identifier
java -cp choam.jar com.hellblazer.delos.stereotomy.Stereotomy rotate \
  --current-id node1 \
  --new-id node1-v2

# Update configuration
# (Manual: replace node1 with node1-v2 in genesis.yaml)

# Restart node with new identifier
java -jar choam.jar --config genesis.yaml --member node1-v2
```

**Rotate view keys** (automatic on every view change, manual trigger):

```bash
# Trigger view rotation (rotates view keys)
curl -X POST http://node1:8080/api/view/rotate
```

---

## Disaster Recovery

### Backup Strategy

**What to backup**:
1. **Checkpoints**: Full state snapshots (large, infrequent)
2. **Block log**: Complete transaction history (append-only)
3. **KERI identities**: Private keys (critical, small)
4. **Configuration**: genesis.yaml and runtime config (small)

**Backup schedule** (production):
```yaml
backups:
  checkpoints:
    frequency: daily
    retention: 30 days
    destination: s3://choam-backups/checkpoints/

  blocks:
    frequency: hourly
    retention: 7 days
    destination: s3://choam-backups/blocks/

  identities:
    frequency: once (after rotation)
    retention: permanent
    destination: vault://choam/identities/
```

**Backup script**:

```bash
#!/bin/bash
# backup-choam.sh

DATE=$(date +%Y%m%d-%H%M%S)
BACKUP_ROOT=/var/backups/choam

# Backup latest checkpoint
CHECKPOINT=$(curl -s http://localhost:8080/api/checkpoint/latest)
curl -s http://localhost:8080/api/checkpoint/download?id=$CHECKPOINT \
  > $BACKUP_ROOT/checkpoints/checkpoint-$DATE.bin

# Backup block log (incremental)
rsync -avz /var/lib/choam/blocks/ $BACKUP_ROOT/blocks/

# Backup KERI identities (encrypted)
tar -czf - /etc/choam/identities/ | \
  openssl enc -aes-256-cbc -salt -pass pass:$BACKUP_PASSWORD | \
  aws s3 cp - s3://choam-backups/identities/identities-$DATE.tar.gz.enc

# Prune old backups
find $BACKUP_ROOT/checkpoints/ -mtime +30 -delete
find $BACKUP_ROOT/blocks/ -mtime +7 -delete
```

### Restore from Backup

**Scenario 1: Single node failure**

```bash
# 1. Provision new node (same spec as failed node)

# 2. Restore KERI identity
aws s3 cp s3://choam-backups/identities/identities-latest.tar.gz.enc - | \
  openssl enc -d -aes-256-cbc -pass pass:$BACKUP_PASSWORD | \
  tar -xzf - -C /etc/choam/

# 3. Restore latest checkpoint
aws s3 cp s3://choam-backups/checkpoints/checkpoint-latest.bin \
  /var/lib/choam/checkpoint.bin

# 4. Start node (will bootstrap from checkpoint)
java -jar choam.jar --config genesis.yaml --member node1 \
  --bootstrap-from-checkpoint /var/lib/choam/checkpoint.bin

# 5. Verify node rejoins committee
curl http://node1:8080/ready
# Response: {"status": "READY", "view": "...", "epoch": 42}
```

**Scenario 2: Total cluster failure (disaster)**

```bash
# Requires 2f+1 nodes minimum to restore quorum

# 1. Provision 3 nodes (for f=1 tolerance)
# 2. Restore KERI identities on each node (from backup)
# 3. Restore latest checkpoint on all nodes
# 4. Start nodes with same genesis configuration
# 5. Nodes will form quorum and resume from checkpoint

# Verify quorum reached
curl http://node1:8080/api/committee/status
# Response: {"size": 3, "quorum": 2, "online": 3}
```

**Data loss**: Transactions after last checkpoint are lost (bounded by checkpoint interval, default 1 hour)

---

## Upgrade and Migration

### Rolling Upgrade (Zero Downtime)

**Prerequisites**:
- Cluster size ≥ 2f+1 (quorum maintained during upgrade)
- New version backward-compatible with current version
- Checkpoint created before upgrade

**Procedure** (7-node cluster, f=2):

```bash
# 1. Create checkpoint before upgrade
curl -X POST http://node1:8080/api/checkpoint/create

# 2. Upgrade node1 (oldest member)
systemctl stop choam-node1
cp choam-0.0.7.jar /opt/choam/choam.jar
systemctl start choam-node1

# Verify node1 rejoins committee
curl http://node1:8080/ready

# 3. Wait for node1 to stabilize (30 seconds)
sleep 30

# 4. Upgrade node2
systemctl stop choam-node2
cp choam-0.0.7.jar /opt/choam/choam.jar
systemctl start choam-node2

# Verify node2 rejoins
curl http://node2:8080/ready

# 5. Repeat for remaining nodes (node3-7)
# ...

# 6. Verify all nodes upgraded
for i in {1..7}; do
  curl -s http://node$i:8080/api/version
done
# All should report: {"version": "0.0.7"}
```

**Rollback**: Reverse procedure (downgrade one node at a time)

### Breaking Changes Migration

**Scenario**: Version N+1 changes state schema (incompatible with version N)

**Procedure**:

1. **Create migration checkpoint** (version N):
   ```bash
   curl -X POST http://node1:8080/api/checkpoint/create?type=migration
   ```

2. **Stop all nodes**:
   ```bash
   for i in {1..7}; do
     systemctl stop choam-node$i
   done
   ```

3. **Run migration script** (offline):
   ```bash
   java -jar choam-migration-0.0.6-to-0.0.7.jar \
     --checkpoint /var/lib/choam/checkpoints/migration.bin \
     --output /var/lib/choam/checkpoints/migrated.bin
   ```

4. **Deploy version N+1 on all nodes**:
   ```bash
   for i in {1..7}; do
     scp choam-0.0.7.jar node$i:/opt/choam/choam.jar
   done
   ```

5. **Start all nodes with migrated checkpoint**:
   ```bash
   for i in {1..7}; do
     ssh node$i "systemctl start choam-node$i \
       --bootstrap-from-checkpoint /var/lib/choam/checkpoints/migrated.bin"
   done
   ```

6. **Verify cluster formed**:
   ```bash
   curl http://node1:8080/api/committee/status
   # Response: {"size": 7, "quorum": 5, "online": 7}
   ```

**Downtime**: ~5-10 minutes (depends on checkpoint size and migration complexity)

---

## Appendix

### Configuration Reference

Full configuration reference: See `choam/src/main/java/com/hellblazer/delos/choam/Parameters.java`

### Error Codes

| Code | Description | Action |
|------|-------------|--------|
| E001 | Committee size below quorum | Add nodes or reduce f |
| E002 | Byzantine incident detected | Investigate and rotate view |
| E003 | Consensus stalled | Check network and Ethereal |
| E004 | Checkpoint validation failed | Restore from backup |
| E005 | View reconfiguration timeout | Check Fireflies membership |

### Support

- **Issues**: https://github.com/Hellblazer/Delos/issues
- **Documentation**: https://github.com/Hellblazer/Delos/wiki
- **Slack**: #choam-operators

---

**Last Updated**: 2026-02-08
**CHOAM Version**: 0.0.6-SNAPSHOT

For architecture details, see [ARCHITECTURE.md](ARCHITECTURE.md).
For terminology reference, see [GLOSSARY.md](GLOSSARY.md).
