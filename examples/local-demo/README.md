# Delos Local Demo - Docker Compose Cluster

Multi-node Delos cluster deployment using Docker Compose with real Fireflies membership service.

## Overview

This module provides a containerized Delos cluster demonstrating the **three-tier bootstrap pattern** for Byzantine Fault Tolerant (BFT) consensus:

1. **Bootstrap Node** - Initializes the cluster, acts as rendezvous point
2. **Kernel Nodes** (3) - Form minimal BFT quorum (4 nodes = 3f+1 for f=1 tolerance)
3. **Member Nodes** (N) - Scalable nodes that join after genesis

### Current Status: Phase 1 Implementation

**Implemented:**
- ✅ Real Delos node containers with Fireflies membership
- ✅ Three-tier bootstrap sequence
- ✅ Docker Compose networking
- ✅ Prometheus metrics endpoint
- ✅ Health checks
- ✅ Scalable member nodes

**Phase 2 (planned):**
- ⬜ Full MTLS communication between nodes
- ⬜ CHOAM consensus integration
- ⬜ KERI identity bootstrap via gRPC
- ⬜ Witness service integration
- ⬜ 100+ node testing

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Docker Network: delos-net                     │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌───────────────┐     ┌───────────────┐     ┌───────────────┐      │
│  │   Bootstrap   │     │   Kernel 1    │     │   Kernel 2    │      │
│  │   (Genesis)   │◄───►│   (Quorum)    │◄───►│   (Quorum)    │      │
│  │               │     │               │     │               │      │
│  │  Port: 9999   │     │               │     │               │      │
│  │  Port: 9090   │     │               │     │               │      │
│  └───────┬───────┘     └───────┬───────┘     └───────┬───────┘      │
│          │                     │                     │               │
│          │         ┌───────────┴───────────┐         │               │
│          │         │                       │         │               │
│          │    ┌────▼─────┐           ┌─────▼────┐    │               │
│          │    │ Kernel 3 │           │ Member 1 │    │               │
│          │    │ (Quorum) │           │ (Scale)  │    │               │
│          │    └──────────┘           └──────────┘    │               │
│          │                                           │               │
│          │    ┌──────────┐           ┌──────────┐    │               │
│          └───►│ Member 2 │    ...    │ Member N │◄───┘               │
│               │ (Scale)  │           │ (Scale)  │                    │
│               └──────────┘           └──────────┘                    │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

## Quick Start

### 1. Build the Docker Image

```bash
# From project root
./mvnw package -Pdocker -pl examples/local-demo -am -DskipTests
```

### 2. Start the Cluster

```bash
cd examples/local-demo

# Start complete cluster (bootstrap + 3 kernel + 1 member)
docker compose up -d

# View logs
docker compose logs -f

# Check status
docker compose ps
```

### 3. Scale Member Nodes

```bash
# Add 10 member nodes
docker compose up -d --scale node=10

# Add 100 member nodes (for load testing)
docker compose up -d --scale node=100
```

### 4. Stop the Cluster

```bash
docker compose down -v
```

## Project Structure

```
local-demo/
├── compose.yaml           # Combined cluster definition
├── bootstrap/
│   └── compose.yaml       # Standalone bootstrap node
├── kernel/
│   └── compose.yaml       # Kernel quorum (needs bootstrap first)
├── nodes/
│   └── compose.yaml       # Scalable members (needs kernel first)
├── Dockerfile             # Node container image
├── pom.xml                # Maven build configuration
└── src/
    ├── main/
    │   ├── java/
    │   │   └── com/hellblazer/delos/demo/
    │   │       ├── DelosNode.java     # Main entry point
    │   │       └── NodeConfig.java    # Environment config
    │   └── resources/
    │       └── logback.xml            # Logging configuration
    └── test/
        └── java/
            └── com/hellblazer/delos/demo/
                └── SmokeTest.java     # Integration tests
```

## Configuration

Environment variables for node configuration:

| Variable | Default | Description |
|----------|---------|-------------|
| `DELOS_NODE_TYPE` | member | Node type: bootstrap, kernel, or member |
| `DELOS_NODE_ID` | auto | Unique node identifier |
| `DELOS_BOOTSTRAP_HOST` | bootstrap | Bootstrap node hostname |
| `DELOS_BOOTSTRAP_PORT` | 9999 | Bootstrap node gRPC port |
| `DELOS_GRPC_PORT` | 9999 | This node's gRPC port |
| `DELOS_METRICS_PORT` | 9090 | Prometheus metrics port |
| `DELOS_CARDINALITY` | 10 | Expected cluster size |
| `DELOS_BIAS` | 3 | Fireflies ring bias |
| `DELOS_PBYZ` | 0.1 | Byzantine fault probability |
| `DELOS_GOSSIP_DURATION_MS` | 100 | Gossip interval (ms) |
| `DELOS_SEEDING_TIMEOUT_S` | 30 | Join timeout (seconds) |
| `JAVA_OPTS` | "" | Additional JVM options |

## Staged Bootstrap (Alternative)

For fine-grained control, use the separate compose files:

```bash
# Stage 1: Start bootstrap
docker compose -f bootstrap/compose.yaml up -d
docker compose -f bootstrap/compose.yaml logs -f
# Wait for "Bootstrap node is operational"

# Stage 2: Start kernel quorum
docker compose -f kernel/compose.yaml up -d
docker compose -f kernel/compose.yaml logs -f
# Wait for all kernel nodes to be healthy

# Stage 3: Scale member nodes
docker compose -f nodes/compose.yaml up -d --scale node=5
```

## Running Tests

```bash
# Build the Docker image first
./mvnw package -Pdocker -pl examples/local-demo -am -DskipTests

# Run integration tests (requires Docker)
./mvnw test -Pe2e -pl examples/local-demo
```

## Monitoring

### Prometheus Metrics

Bootstrap node exposes metrics at `http://localhost:9090/metrics`

Sample metrics:
- `fireflies_active_count` - Active nodes in view
- `fireflies_gossip_rounds` - Gossip rounds completed
- `fireflies_ring_count` - Number of rings in context

### Container Logs

```bash
# All containers
docker compose logs -f

# Specific container
docker compose logs -f bootstrap
docker compose logs -f kernel1
```

### Health Checks

```bash
# Check container health
docker compose ps

# Detailed health
docker inspect delos-bootstrap --format='{{.State.Health.Status}}'
```

## Node Components

Each container runs `DelosNode` with:

```
┌─────────────────────────────────────────┐
│           Delos Node Container          │
├─────────────────────────────────────────┤
│  ┌─────────────────────────────────┐    │
│  │      Fireflies (Gossip)         │    │
│  │  - Membership view               │    │
│  │  - Ring-based gossip             │    │
│  │  - View change coordination      │    │
│  └─────────────────────────────────┘    │
│                  │                       │
│  ┌─────────────────────────────────┐    │
│  │      Stereotomy (KERI)          │    │
│  │  - Identity management           │    │
│  │  - Key event logs               │    │
│  └─────────────────────────────────┘    │
│                                          │
│  Ports: 9999 (gRPC), 9090 (metrics)     │
└─────────────────────────────────────────┘
```

## Troubleshooting

### Container Won't Start

1. Check if the image is built:
   ```bash
   docker images | grep delos-node
   ```

2. Rebuild if needed:
   ```bash
   ./mvnw package -Pdocker -pl examples/local-demo -am -DskipTests
   ```

### Nodes Not Joining

1. Check bootstrap is healthy:
   ```bash
   docker compose logs bootstrap | tail -20
   ```

2. Verify network connectivity:
   ```bash
   docker compose exec kernel1 ping bootstrap
   ```

3. Increase seeding timeout:
   ```yaml
   environment:
     DELOS_SEEDING_TIMEOUT_S: '120'
   ```

### Out of Memory

Reduce heap size for large clusters:
```yaml
environment:
  JAVA_OPTS: '-Xmx256m -Xms128m'
```

## Development

### Modifying Node Code

1. Make changes to `DelosNode.java` or `NodeConfig.java`
2. Rebuild: `./mvnw package -Pdocker -pl examples/local-demo -DskipTests`
3. Restart: `docker compose down && docker compose up -d`

### Debugging

Enable remote debugging:
```yaml
environment:
  JAVA_OPTS: '-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005'
ports:
  - "5005:5005"
```

## Future Enhancements

Phase 2 roadmap:

1. ⬜ Real MTLS communication between nodes
2. ⬜ CHOAM consensus integration
3. ⬜ Witness service integration
4. ⬜ KERI identity bootstrap via gRPC
5. ⬜ Checkpoint and recovery testing
6. ⬜ Byzantine fault injection
7. ⬜ Performance benchmarks (100+ nodes)
8. ⬜ Kubernetes deployment patterns

## License

GNU Affero General Public License v3.0 - see LICENSE file in repository root.
