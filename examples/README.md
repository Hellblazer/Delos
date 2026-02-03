# Delos Examples

Runnable examples demonstrating Delos capabilities.

## Available Examples

| Example | Description |
|---------|-------------|
| [local-demo](local-demo/README.md) | Multi-node cluster with Docker Compose, simulation testing |
| [simple-kv-store](simple-kv-store/README.md) | Basic key-value store on replicated SQL |
| [fsm-workflow](fsm-workflow/README.md) | Finite state machine workflow using Tron |
| [multi-tenant-demo](multi-tenant-demo/README.md) | GraalVM isolate-based multi-tenancy |

## Quick Start

```bash
# Build all examples
./mvnw install -pl examples -amd

# Run local demo (requires Docker)
cd local-demo && docker-compose up
```

See individual example READMEs for detailed instructions.
