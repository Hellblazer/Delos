# Delos Local Demo - Docker Compose Cluster Pattern

## Overview

This example demonstrates the **three-tier bootstrap pattern** for deploying Delos clusters using Docker Compose. It shows the orchestration sequence required to form a Byzantine Fault Tolerant (BFT) cluster.

### Current Status: Pattern Demonstration

This is a **pattern demonstration** using simple Alpine containers to illustrate the cluster bootstrap sequence and Docker Compose structure. It establishes the infrastructure foundation for deploying actual Delos nodes.

**What this demonstrates:**
- Three-tier bootstrap sequence (bootstrap → kernel → nodes)
- Docker Compose networking configuration
- Health checks and readiness patterns
- TestContainers-based orchestration testing
- Proper startup ordering and dependencies

**Future enhancement** (not yet implemented):
- Full Delos node implementation with Fireflies membership
- MTLS communication between nodes
- CHOAM consensus operations
- KERI-based identity management
- Actual cluster state verification

## Architecture

### Three-Tier Bootstrap Pattern

```
┌─────────────────────────────────────────────────────┐
│  Phase 1: Bootstrap                                 │
│  ┌───────────────┐                                  │
│  │   Bootstrap   │  Initializes cluster             │
│  │    Node 0     │  Well-known rendezvous point     │
│  └───────────────┘                                  │
└─────────────────────────────────────────────────────┘
                      ▼
┌─────────────────────────────────────────────────────┐
│  Phase 2: Kernel Quorum (Minimal BFT)              │
│  ┌───────────┐ ┌───────────┐ ┌───────────┐        │
│  │  Kernel   │ │  Kernel   │ │  Kernel   │        │
│  │  Node 1   │ │  Node 2   │ │  Node 3   │        │
│  └───────────┘ └───────────┘ └───────────┘        │
│                                                     │
│  4 nodes total = Minimal quorum for BFT            │
│  Genesis block generation occurs here              │
└─────────────────────────────────────────────────────┘
                      ▼
┌─────────────────────────────────────────────────────┐
│  Phase 3: Additional Members (Scalable)            │
│  ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ...              │
│  │Node │ │Node │ │Node │ │Node │                   │
│  │  4  │ │  5  │ │  6  │ │  N  │                   │
│  └─────┘ └─────┘ └─────┘ └─────┘                   │
│                                                     │
│  Join after Genesis block is established           │
│  Scale horizontally as needed                      │
└─────────────────────────────────────────────────────┘
```

## Project Structure

```
local-demo/
├── bootstrap/
│   └── compose.yaml          # Bootstrap node definition
├── kernel/
│   └── compose.yaml          # Kernel quorum nodes (3 nodes)
├── nodes/
│   └── compose.yaml          # Scalable additional nodes
├── src/
│   └── test/
│       └── java/.../SmokeTest.java  # TestContainers orchestration test
├── pom.xml                   # Maven configuration with TestContainers
└── README.md                 # This file
```

## Requirements

- Docker and Docker Compose installed
- Maven 3.9.3+ (for running tests)
- Java 25+ (configured in parent POM)

## Manual Deployment

### Step-by-Step Cluster Bootstrap

**⚠️ IMPORTANT:** Follow this exact sequence. Timing matters!

#### 1. Start Bootstrap Node

```bash
cd examples/local-demo/bootstrap
docker compose up
```

Wait for log message: `Bootstrap node ready - cluster initialized`

**Do NOT proceed** until bootstrap is stable (~5 seconds).

#### 2. Start Kernel Nodes

In a new terminal:

```bash
cd examples/local-demo/kernel
docker compose up
```

Wait for all 3 kernel nodes to log: `Participating in Genesis block generation...`

**Critical:** Allow 10-30 seconds for the kernel to generate the Genesis block before starting additional nodes. In a real Delos cluster, this involves:
- Establishing secure MTLS connections
- Forming Fireflies membership rings
- Initializing CHOAM consensus
- Creating the initial Genesis block

For this demo, this is simulated with a wait period.

#### 3. Add Additional Nodes (Optional)

After kernel is stable:

```bash
cd examples/local-demo/nodes
docker compose up --scale node=3  # Start 3 additional nodes
```

#### 4. Verify Cluster Status

```bash
# List all running containers
docker ps --filter "name=delos-"

# View bootstrap logs
docker logs delos-bootstrap

# View kernel logs
docker logs delos-kernel1
docker logs delos-kernel2
docker logs delos-kernel3

# View additional node logs
docker logs local-demo-node-1
```

#### 5. Cleanup

```bash
# Stop all services (run from each directory)
cd examples/local-demo/nodes && docker compose down
cd ../kernel && docker compose down
cd ../bootstrap && docker compose down
```

## Automated Testing

### Run with Maven (E2E Profile)

```bash
# From Delos root directory
./mvnw test -P e2e -pl examples/local-demo

# Or from examples/local-demo directory
../../mvnw test -P e2e
```

The SmokeTest demonstrates:
1. Bootstrap node initialization
2. Kernel quorum formation (4 nodes)
3. Additional node joining
4. Network configuration verification
5. Health check validation

### Test Execution Flow

The test uses TestContainers to:
- Start bootstrap compose environment
- Wait for bootstrap readiness
- Start kernel compose environment
- Verify quorum formation
- Start additional nodes
- Validate cluster state
- Clean up all resources

## Networking

### Bridge Network Configuration

All services connect to a Docker bridge network (`delos-net`) created by the bootstrap compose file.

- **Bootstrap:** Creates the network
- **Kernel:** References external network `bootstrap_delos-net`
- **Nodes:** References external network `bootstrap_delos-net`

This allows:
- Service discovery by container name
- Isolation from other Docker networks
- Predictable addressing for node communication

## Scaling

### Adding More Nodes

Scale the generic node service:

```bash
cd examples/local-demo/nodes
docker compose up --scale node=10  # Scale to 10 nodes
```

Each scaled instance:
- Gets a unique hostname (node_1, node_2, etc.)
- Joins via the bootstrap node
- Participates in cluster membership

### Cluster Limits

For a real Delos cluster:
- **Minimum:** 4 nodes (1 bootstrap + 3 kernel) for BFT
- **Recommended:** 7+ nodes for production (tolerates 2 Byzantine failures)
- **Maximum:** Depends on network capacity and consensus algorithm tuning

## Environment Variables

### Bootstrap Node

| Variable | Value | Description |
|----------|-------|-------------|
| `GENESIS` | `true` | Indicates bootstrap/kernel node |
| `NODE_TYPE` | `bootstrap` | Node role identifier |
| `NODE_ID` | `bootstrap-0` | Unique node identifier |

### Kernel Nodes

| Variable | Value | Description |
|----------|-------|-------------|
| `GENESIS` | `true` | Participates in Genesis block |
| `NODE_TYPE` | `kernel` | Kernel quorum member |
| `NODE_ID` | `kernel-{1,2,3}` | Unique node identifier |
| `BOOTSTRAP_HOST` | `delos-bootstrap` | Bootstrap node hostname |

### Additional Nodes

| Variable | Value | Description |
|----------|-------|-------------|
| `GENESIS` | `false` | Joins after Genesis |
| `NODE_TYPE` | `member` | Regular cluster member |
| `BOOTSTRAP_HOST` | `delos-bootstrap` | Bootstrap node hostname |

## Troubleshooting

### Kernel nodes fail to start

**Symptom:** Kernel compose fails with network error

**Solution:**
1. Ensure bootstrap is running first
2. Verify network exists: `docker network ls | grep delos-net`
3. If missing, restart bootstrap: `cd bootstrap && docker compose up`

### Nodes can't join cluster

**Symptom:** Additional nodes fail to start

**Solution:**
1. Verify kernel is stable (wait 30 seconds after kernel starts)
2. Check bootstrap is reachable: `docker exec local-demo-node-1 ping delos-bootstrap`
3. Review logs for connection errors

### Port conflicts

**Symptom:** "Address already in use" error

**Solution:**
- This demo uses no exposed ports, conflicts are unlikely
- For real Delos nodes, ensure ports 8123-8125 are available

### Cleanup issues

**Symptom:** Network persists after `docker compose down`

**Solution:**
```bash
# Force remove network
docker network rm bootstrap_delos-net

# Remove all stopped containers
docker container prune
```

## Development Notes

### Extending with Real Delos Nodes

To replace the Alpine containers with actual Delos nodes:

1. **Create MinimalNode application**:
   - Implement Fireflies View initialization
   - Add MTLS communication setup
   - Configure KERI identity management
   - Implement health check endpoint

2. **Build Docker image**:
   ```bash
   ./mvnw package jib:dockerBuild -pl examples/local-demo
   ```

3. **Update compose files**:
   - Replace `image: alpine:latest` with `image: com.hellblazer.delos/local-demo:VERSION`
   - Add environment variables for Delos configuration
   - Configure proper health checks

4. **Implement network discovery**:
   - Use DNS service discovery or well-known addresses
   - Configure seed endpoints for Fireflies
   - Set up approach endpoints for node joining

### Maven Configuration

The `pom.xml` includes:
- **Jib plugin** for Docker image building (configured but not used in demo)
- **Shade plugin** for creating executable JARs
- **E2E profile** to run SmokeTest separately from unit tests

## References

- **Fireflies:** Delos membership service (Byzantine intrusion tolerant)
- **CHOAM:** Committee-based replicated state machines
- **KERI:** Key Event Receipt Infrastructure (decentralized identity)
- **TestContainers:** Container-based integration testing framework

## License

BSD-3-Clause (see LICENSE file in repository root)

## Future Enhancements

Planned improvements for this example:

1. ✅ Docker Compose structure and bootstrap pattern
2. ✅ TestContainers orchestration test
3. ⬜ Full Delos node implementation
4. ⬜ MTLS certificate generation and distribution
5. ⬜ KERI identity bootstrapping
6. ⬜ Actual consensus operations demonstration
7. ⬜ Monitoring and metrics (Prometheus/Grafana)
8. ⬜ Production deployment patterns (Kubernetes/Swarm)

**Status Legend:** ✅ Complete | ⬜ Planned
