# Delos 168-Hour Simulation Framework

Comprehensive stability testing framework for Byzantine fault-tolerant Delos clusters at scale.

## Overview

The 168-hour simulation framework provides automated, long-running stability tests for Delos clusters with:

- **Production-scale testing:** Up to 100 nodes (configurable)
- **Extended duration:** 168 hours (7 days) continuous operation
- **Comprehensive monitoring:** Metrics, logs, heap dumps, and health checks
- **Degradation detection:** Baseline comparison and anomaly alerts
- **CI/CD integration:** GitHub Actions workflows for automated testing
- **Chaos engineering:** Optional fault injection (network partitions, node churn, resource exhaustion)

## Quick Start

### Local Execution (Manual)

1. **Build the project:**
   ```bash
   cd examples/local-demo
   ./mvnw clean package -Pdocker
   ```

2. **Start cluster (10 nodes for testing):**
   ```bash
   docker compose -f compose-simulation.yaml up -d --scale node=6
   ```

3. **Run short simulation (1 hour):**
   ```bash
   ./mvnw exec:java -Psimulation \
     -Dsimulation.duration.hours=1 \
     -Dsimulation.nodeCount=10 \
     -Dsimulation.enableHeapDumps=true
   ```

4. **View results:**
   ```bash
   open simulation-results/reports/simulation-report.html
   ```

5. **Cleanup:**
   ```bash
   docker compose -f compose-simulation.yaml down -v
   ```

### CI/CD Execution (GitHub Actions)

#### Short Validation (1 hour)
Runs automatically on every commit to `examples/local-demo/`:

```bash
# Triggered automatically by git push
# Or manually:
gh workflow run simulation-short.yml
```

#### 24-Hour Soak Test
Manual trigger for extended testing:

```bash
gh workflow run simulation-24h.yml \
  --field node_count=50 \
  --field enable_chaos=true
```

#### Full 168-Hour Simulation
**Requires self-hosted runner** (see setup below):

```bash
gh workflow run simulation-168h.yml \
  --field node_count=100 \
  --field enable_chaos=true \
  --field enable_heap_dumps=true \
  --field notify_slack=true
```

## Configuration

### System Properties

The simulation orchestrator accepts the following system properties:

| Property | Default | Description |
|----------|---------|-------------|
| `simulation.duration.hours` | 168 | Simulation duration in hours |
| `simulation.nodeCount` | 100 | Total number of nodes (min 4 for BFT) |
| `simulation.enableHeapDumps` | `true` | Enable periodic heap dumps |
| `simulation.prometheusUrl` | `http://localhost:9090` | Prometheus endpoint for metrics |
| `simulation.composeFile` | `compose-simulation.yaml` | Docker Compose file to use |

### Example: Custom 48-hour run with 50 nodes

```bash
./mvnw exec:java -Psimulation \
  -Dsimulation.duration.hours=48 \
  -Dsimulation.nodeCount=50 \
  -Dsimulation.enableHeapDumps=false \
  -Dsimulation.prometheusUrl=http://localhost:9091
```

## Docker Compose Files

### `compose.yaml`
- Standard cluster for manual testing and development
- Memory: 256MB per node
- No volume mounts for simulation data
- Use for quick tests and development

### `compose-simulation.yaml`
- Extended configuration for long-running simulations
- Memory: 512MB per node (increased for stability)
- Volume mounts for heap dumps, checkpoints, and metrics
- JMX monitoring enabled on port 9010
- Heap dumps on OOM with auto-exit
- 30-day Prometheus retention (vs 7 days in standard)

## Monitoring and Metrics

### Prometheus

- **URL:** http://localhost:9091 (exposed from container)
- **Retention:** 30 days for simulation runs
- **Metrics collected:**
  - Transaction latency (P50, P95, P99)
  - Transaction rate and success rate
  - Heap usage and GC metrics
  - Byzantine accusation rate
  - Cluster membership changes

### Prometheus Recording Rules

Located in `monitoring/prometheus/rules/simulation-rules.yml`:

- **Baseline metrics:** Recorded from first 2 hours
- **Current metrics:** Calculated every 30s
- **Alerts:** Degradation detection, stalls, anomalies

### Grafana

- **URL:** http://localhost:3000
- **Credentials:** admin / delos
- **Dashboards:**
  - Delos Cluster Overview
  - CHOAM Consensus
  - Fireflies Membership
  - Byzantine Behavior

## Results and Artifacts

### Directory Structure

```
simulation-results/
├── reports/
│   ├── simulation-report.html    # Interactive HTML report
│   ├── simulation-report.json    # Machine-readable JSON
│   └── simulation-report.md      # Markdown summary
├── metrics/
│   ├── baseline.json             # Baseline metrics (first 2h)
│   └── snapshots/                # Periodic metric snapshots
├── logs/
│   ├── orchestrator.log          # Orchestrator logs
│   └── docker-compose.log        # All container logs
└── EXECUTIVE_SUMMARY.md          # High-level summary

simulation-data/
├── heapdumps/                    # Heap dumps (if enabled)
├── checkpoints/                  # State checkpoints
└── metrics/                      # Raw Prometheus data
```

### Report Contents

**HTML Report** (`simulation-report.html`):
- Executive summary with SLA compliance
- Phase timeline visualization
- Performance metrics graphs
- Degradation alerts and anomalies
- Verification results (consensus, state, membership)
- Chaos event log (if enabled)

**JSON Report** (`simulation-report.json`):
- Structured data for programmatic analysis
- All metrics in machine-readable format
- Suitable for CI/CD pass/fail decisions

**Markdown Report** (`simulation-report.md`):
- Text summary for commit messages and issues
- Key findings and recommendations
- Suitable for GitHub issue bodies

## Performance SLAs

The simulation validates against these SLAs:

| Metric | Threshold | Severity |
|--------|-----------|----------|
| Transaction Latency P99 | < 100ms | Warning |
| Transaction Success Rate | > 99% | Critical |
| Heap Usage | < 80% | Warning |
| GC Pause Ratio | < 10% | Warning |
| Byzantine Accusations | < 5/min | Warning |
| Node Availability | > 95% | Critical |

**Degradation Detection:**
- Latency increase > 50% from baseline → Warning
- Throughput decrease > 20% from baseline → Warning
- Memory growth > 100% over 1h → Critical (memory leak)

## Self-Hosted Runner Setup

The 168-hour simulation requires a self-hosted runner due to GitHub-hosted runner timeout limits (360 minutes max).

### Hardware Requirements

- **CPU:** 16+ cores recommended
- **Memory:** 64GB+ for 100-node cluster
- **Disk:** 100GB+ free space (logs, metrics, heap dumps)
- **Network:** Stable connection (avoid WiFi)

### Installation

1. **Install GitHub Actions Runner:**

   ```bash
   # Create runner directory
   mkdir -p ~/actions-runner && cd ~/actions-runner

   # Download latest runner
   curl -o actions-runner-linux-x64-2.311.0.tar.gz \
     -L https://github.com/actions/runner/releases/download/v2.311.0/actions-runner-linux-x64-2.311.0.tar.gz

   # Extract
   tar xzf ./actions-runner-linux-x64-2.311.0.tar.gz

   # Configure (follow prompts)
   ./config.sh --url https://github.com/YOUR_ORG/Delos --token YOUR_TOKEN

   # Install as service
   sudo ./svc.sh install
   sudo ./svc.sh start
   ```

2. **Verify Docker installed:**

   ```bash
   docker --version
   docker compose version
   ```

3. **Configure runner labels:**

   Add label `self-hosted` and `simulation` to the runner in GitHub settings.

4. **Test runner:**

   ```bash
   # Check runner status
   ./svc.sh status

   # View logs
   journalctl -u actions.runner.* -f
   ```

### Maintenance

- **Monitor disk usage:** Cleanup old simulation results periodically
- **Check Docker health:** `docker system df` and prune unused images
- **Update runner:** Follow GitHub's update procedure
- **Rotate logs:** Configure log rotation for runner logs

## Troubleshooting

### Simulation Fails to Start

**Symptom:** Docker Compose fails to start cluster

**Solutions:**
1. Check Docker is running: `docker info`
2. Verify compose file: `docker compose -f compose-simulation.yaml config`
3. Check available resources: `docker system df`
4. Clean up old containers: `docker compose down -v && docker system prune -f`

### High Heap Usage Alerts

**Symptom:** Heap usage > 80% alert triggered

**Solutions:**
1. Check heap dumps in `simulation-data/heapdumps/`
2. Analyze with MAT or VisualVM
3. Increase heap size in `compose-simulation.yaml` (`-Xmx512m` → `-Xmx1g`)
4. Reduce node count or transaction load

### Transaction Rate Stalls

**Symptom:** Transaction rate drops to near zero

**Solutions:**
1. Check Prometheus metrics for Byzantine accusations
2. Review container logs: `docker compose logs bootstrap`
3. Verify network connectivity between containers
4. Check for clock skew: `docker compose exec bootstrap date`

### Cluster Health Check Fails

**Symptom:** Bootstrap node fails health check

**Solutions:**
1. Check bootstrap logs: `docker compose logs bootstrap`
2. Verify port 8080 is accessible: `curl http://localhost:8080/health`
3. Increase `start_period` in health check (currently 60s)
4. Check for port conflicts: `lsof -i :8080`

### Workflow Timeout (GitHub Actions)

**Symptom:** GitHub Actions workflow times out before completion

**Solutions:**
1. **168h simulation:** Must use self-hosted runner (see setup above)
2. **24h simulation:** Increase `timeout-minutes` in workflow file
3. **1h simulation:** Check cluster startup time; may need more time for scaling

### Missing Artifacts

**Symptom:** Reports or metrics not generated

**Solutions:**
1. Check orchestrator logs: `cat simulation-results/logs/orchestrator.log`
2. Verify Prometheus is running: `curl http://localhost:9091/-/healthy`
3. Ensure simulation completed without crash
4. Check file permissions on `simulation-results/` directory

## Advanced Usage

### Custom Chaos Scenarios

Create custom chaos scenarios by extending `ChaosScenario`:

```java
public class CustomChaosScenario implements ChaosScenario {
    @Override
    public void inject(ChaosContext context) {
        // Your chaos logic here
    }
}
```

Register in `SimulationOrchestrator`:

```java
chaosInjector.register(new CustomChaosScenario());
```

### Custom Verification Checks

Add custom verification logic:

```java
public class CustomVerifier implements VerificationCheck {
    @Override
    public VerificationResult verify(SimulationContext context) {
        // Your verification logic
        return VerificationResult.pass("Custom check passed");
    }
}
```

### Programmatic API

Run simulation programmatically:

```java
var config = SimulationConfig.builder()
    .duration(Duration.ofHours(24))
    .nodeCount(50)
    .enableHeapDumps(true)
    .build();

var orchestrator = new SimulationOrchestrator(config);
orchestrator.start();
orchestrator.awaitCompletion();
```

## CI/CD Integration Details

### Artifact Retention

| Workflow | Reports | Metrics | Heap Dumps | Logs |
|----------|---------|---------|------------|------|
| 1h (short) | 30 days | 30 days | N/A | 7 days |
| 24h (soak) | 90 days | 90 days | 30 days | 30 days |
| 168h (full) | 365 days | 365 days | 90 days | 90 days |

### Workflow Triggers

- **Short (1h):** Automatic on push to `examples/local-demo/`
- **Soak (24h):** Manual trigger via `workflow_dispatch`
- **Full (168h):** Manual trigger via `workflow_dispatch` (requires self-hosted runner)

### Notifications

Configure Slack notifications for 168h runs:

1. Create Slack webhook URL
2. Add to GitHub secrets: `SLACK_WEBHOOK_URL`
3. Enable in workflow: `notify_slack=true`

### GitHub Pages Publishing

Results from 168h runs are automatically published to GitHub Pages at:

```
https://YOUR_ORG.github.io/Delos/simulation-168h/<run-number>/
```

## Development and Testing

### Running Unit Tests

```bash
# Run all simulation tests
./mvnw test -pl examples/local-demo

# Run specific test
./mvnw test -Dtest=SimulationOrchestratorTest -pl examples/local-demo
```

### Debugging

```bash
# Run with debug logging
./mvnw exec:java -Psimulation -X \
  -Dsimulation.duration.hours=1

# Attach debugger (port 5005)
./mvnw exec:java -Psimulation \
  -Dexec.args="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005"
```

### Local Docker Build

```bash
# Build Docker image locally
cd examples/local-demo
./mvnw package -Pdocker

# Verify image
docker images | grep delos-node

# Test single node
docker run --rm -p 8080:8080 delos-node:latest
```

## FAQ

**Q: Can I run 168h simulation on GitHub-hosted runners?**

A: No. GitHub-hosted runners have a 6-hour timeout limit. You must use a self-hosted runner.

**Q: How much disk space does a 168h run use?**

A: Approximately 50-100GB depending on:
- Heap dump frequency
- Prometheus metric resolution
- Log verbosity

**Q: Can I pause and resume a simulation?**

A: Not currently supported. Simulations must run continuously.

**Q: What happens if the simulation crashes?**

A: The orchestrator captures all logs and artifacts up to the crash point. These are uploaded as workflow artifacts for analysis.

**Q: Can I run multiple simulations in parallel?**

A: No. Each simulation requires exclusive access to Docker Compose resources and ports.

**Q: How do I interpret the executive summary?**

A: The executive summary shows:
- **Pass/Fail status** based on SLA compliance
- **Key metrics** vs baseline
- **Anomalies detected** during run
- **Recommendations** for action

## Support

For issues and questions:
- **GitHub Issues:** https://github.com/YOUR_ORG/Delos/issues
- **Tag:** `simulation`, `168h-simulation`
- **Documentation:** This file and inline JavaDoc

## License

Copyright (c) 2026, Hal Hildebrand.
All rights reserved.
GNU Affero General Public License
For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
This file is part of the Delos Distributed Systems Framework.
