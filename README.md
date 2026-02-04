# Delos

Delos is a **distributed multi-tenant database platform** providing Byzantine fault-tolerant consensus, decentralized identity management, and replicated SQL state machines. Build secure, wide-area distributed systems with verifiable credentials and role-based access control.

**Status**: [![Build Status](https://github.com/Hellblazer/delos/actions/workflows/maven.yml/badge.svg)](https://github.com/Hellblazer/Delos/actions)

**Current version**: `0.3.1-SNAPSHOT`

> Not A Coin Platform™ — Delos is a distributed database, not blockchain. While it can support cryptocurrencies, that's not the design goal.

## Features

* **Multi-tenancy**: GraalVM isolates for secure tenant enclaves
* **Cryptography**: Self-describing digests, signatures, identifiers; Bloom filters and windows
* **Identity**: KERI-based decentralized identity, key management, attestation, bootstrapping
* **Networking**: MTLS (KERI certificates), multi-instance GRPC routing, virtual synchrony overlay
* **Consensus**: Byzantine fault-tolerant atomic broadcast (Ethereal with chRBC), block dissemination via Bounded Epidemic Gossip (BEG)
* **State Machines**: CHOAM — replicated SQL state machines with materialized views, DDL/DML/stored procedures, BLS batch verification
* **Access Control**: Zanzibar-style relation-based access control (Delphinius)
* **Witness Service**: Byzantine detection (5 detectors), receipt aggregation, multi-backend storage with compression

## Status

Delos is an experimental distributed platform—well-tested subsystems, solid architecture, but not yet production-deployed:

**Well-Tested Subsystems**:
- **Fireflies** (membership service): Extensively tested — Critical ReservoirSampler bug eliminated, all canary tests passing at scale (100 nodes). FirefliesWitnessAdapter maps KERI thresholds to Fireflies contexts with production metrics.
- **Ethereal** (consensus): Well-tested and hardened, parallel unit consumer for improved throughput, fine-grained locking in Adder
- **CHOAM** (state machine replication): Comprehensive testing with Byzantine concurrency validation, BLS batch signature verification with feature flags and circuit breaker
- **Bounded Epidemic Gossip (BEG)**: Production-hardened message dissemination with circuit breaker, health checks, Byzantine defense, and comprehensive metrics
- **Stereotomy/KERI** (identity): Fully integrated
- **SQL-State**: Mature with comprehensive testing
- **Domain Sockets**: Pure Java NIO implementation (JEP 380) — No native dependencies, full GraalVM isolates compatibility
- **Simulation Framework**: Multi-node testing infrastructure validated at 100-node scale

**Experimental Components**:
- **Witness-Service** (receipt management): Well-tested foundation — Full storage integration with multi-backend persistence, compression, Byzantine detection (5 detectors), and E2E testing. Solid architecture; production hardening in progress

Platform is architecturally sound with excellent test coverage. Integration-level production deployment testing still needed. Simulation framework provides foundation for continuous validation at production scale.

## Examples & Simulation Testing

Delos includes production-scale simulation and testing infrastructure:

- [**Local Demo**](examples/local-demo/README.md) — Single-machine multi-node cluster with Docker Compose
- [**Simulation Framework**](examples/local-demo/README-SIMULATION.md) — 168-hour production stability testing with chaos engineering, heap dump analysis, state verification, and metric collection
- **CI/CD Pipelines**: Automated short (1h), medium (24h), and full-scale (168h) testing via GitHub Actions

Quick start: See [Local Demo Setup](examples/local-demo/README.md) for running a 10-node cluster locally.

## Documentation

Comprehensive implementation guides, architectural decisions, and testing patterns:

### Getting Started
- [**Developer Quick Start**](docs/DEVELOPER_QUICKSTART.md) — 30-min setup, first build, architecture overview, code exploration
- [**CLAUDE.md**](CLAUDE.md) — Developer reference, build commands, troubleshooting, IDE setup
- [**CONTRIBUTING.md**](CONTRIBUTING.md) — Contribution process, code standards, testing, commit format

### Architecture & Design
- [**Architecture Guide**](docs/ARCHITECTURE.md) — System layers, data flow, Byzantine fault tolerance model, failure handling
- [**Module READMEs**](choam/README.md) — Detailed architecture, usage patterns, threat models for each component
- [**ADRs**](docs/adr/) — Architectural decision records for design rationale
- [**Code Quality Standards**](docs/CODE_QUALITY_STANDARDS.md) — Design patterns, concurrency, error handling, code review checklist

### Operations & Deployment
- [**Deployment Guide**](docs/DEPLOYMENT_GUIDE.md) — Production setup, node configuration, cluster initialization
- [**Build Guide**](docs/BUILD.md) — Build profiles, Maven configuration, troubleshooting
- [**Configuration Guide**](docs/CONFIGURATION_GUIDE.md) — All parameters, identity, network, consensus, database, performance tuning
- [**Hardware Requirements**](docs/HARDWARE_REQUIREMENTS.md) — Cluster sizing, Byzantine tolerance, capacity planning
- [**Monitoring & Alerting**](docs/MONITORING_AND_ALERTING.md) — Health checks, metrics, SLA definitions, Prometheus setup
- [**Operational Checklists**](docs/OPERATIONAL_CHECKLISTS.md) — Pre-deployment, running, incident response procedures
- [**Upgrade Procedures**](docs/UPGRADE_PROCEDURES.md) — Rolling updates, blue-green deployment, rollback procedures
- [**Operational Quick Start**](docs/OPERATIONAL_QUICK_START.md) — Day-1 operations, incident response patterns
- [**OPS Runbook**](docs/OPS_RUNBOOK_PHASE_1C.md) — Phase 1C specific operational procedures

### Development & Testing
- [**Testing Guide**](docs/TESTING_GUIDE.md) — BFT testing patterns, deterministic execution, test infrastructure, examples
- [**IDE Setup**](docs/IDE_SETUP.md) — IntelliJ IDEA, Eclipse, VS Code configuration
- [**TLS Setup**](docs/TLS_SETUP.md) — Certificate generation, keystore setup, MTLS configuration

### Security & Cryptography
- [**Cryptography Algorithms**](docs/CRYPTOGRAPHY_ALGORITHMS.md) — Algorithm reference, BLS risk assessment, post-quantum planning
- [**Security Threat Model**](docs/SECURITY_THREAT_MODEL.md) — Attack scenarios, cryptographic agility, security guarantees
- [**KERI Integration**](docs/KERI_INTEGRATION.md) — Decentralized identity, key management, identity bootstrapping

### Reference & Support
- [**Complete Documentation Index**](docs/INDEX.md) — All guides organized by category
- [**Glossary**](docs/GLOSSARY.md) — Key terminology and acronyms
- [**Troubleshooting Guide**](docs/TROUBLESHOOTING_GUIDE.md) — Common issues and resolution
- [**API Reference**](docs/API_REFERENCE.md) — Gorgoneion API, Witness-Service API documentation
- [**Knowledge Base**](.pm/CONTINUATION.md) — Consolidated research, design patterns, implementation reference (ChromaDB-indexed for semantic search)

---

## Modules

Each module is a Maven module under the source root with its own README.md.

**Core Infrastructure**
* [Cryptography](cryptography/README.md) - Self-describing digests, signatures, identifiers; Bloom filters; utilities
* [Protocols](protocols/README.md) - GRPC MTLS fundamentals, rate limiters, service routing
* [Memberships](memberships/README.md) - Membership model, Context abstraction, GRPC routers, gossip patterns

**Identity & Security**
* [Stereotomy](stereotomy/README.md) - KERI implementation; KEL, KERL, key and identity management
* [Stereotomy Services](stereotomy-services/README.md) - GRPC services and Protobuf interfaces for KERI
* [Thoth](thoth/README.md) - Distributed hash table for KERI key management
* [Gorgoneion](gorgoneion/README.md) - Identity bootstrapping
* [Gorgoneion Client](gorgoneion-client/README.md) - Identity bootstrap client

**Consensus & State**
* [Fireflies](fireflies/README.md) - Byzantine membership service; virtually synchronous views; secure overlay
* [Ethereal](ethereal/README.md) - Aleph BFT; asynchronous atomic broadcast (consensus)
* [CHOAM](choam/README.md) - Committee-based replicated state machines; causal ordering on linear logs
* [Sql-State](sql-state/README.md) - JDBC-accessible SQL state machines on CHOAM

**Application Layer**
* [Delphinius](delphinius/README.md) - Zanzibar-style relation-based access control
* [Model](model/README.md) - Replicated domains; multi-tenant sharding enclaves
* [Tron](tron/README.md) - Finite state machine framework using Java Enums

**Specialized Services**
* [Witness-Service](witness-service/README.md) - Byzantine detection, receipt aggregation, proof validation

**Platform & Storage**
* [Schemas](schemas/README.md) - Liquibase SQL definitions
* [Deterministic H2](h2-deterministic/README.md) - Deterministic H2 SQL database
* [Deterministic Liquibase](liquibase-deterministic/README.md) - Deterministic Liquibase
* [Isolates](isolates/README.md) - GraalVM isolate-based multi-tenant enclaves
* [Isolate Functional Testing](isolate-ftesting/README.md) - Enclave functional testing

## Contributing

Contributions are welcome! Before submitting:
1. Build with `./mvnw clean install` and verify tests pass
2. Run tests for modified modules: `./mvnw test -pl <module>`
3. Format code using project conventions
4. Reference related issues/ADRs in commit messages
5. Ensure no secrets (keys, tokens) are committed

For major changes, please open an issue first to discuss the approach.

---

## Administration & Setup

### Quick Start

```bash
# First-time setup (one-time, ~5 min)
./mvnw clean install -Ppre -DskipTests

# Subsequent builds
./mvnw clean install

# Run tests (small test suite by default)
./mvnw test

# Full test suite (requires 8+ GB RAM)
./mvnw clean install -Dlarge_tests=true
```

**Requirements**: JDK 25+, Maven 3.9.3+ (mvnw included)

### Building

The `mvnw` Maven wrapper is included; no separate Maven installation required. The build uses two Maven profiles:

**Pre-profile (one-time setup)**
```bash
./mvnw clean install -Ppre -DskipTests
```
Builds the deterministic SQL module (`h2-deterministic`) which must be installed in your local repository once. After this, standard builds don't need this profile.

**Isolates profile (optional)**
```bash
./mvnw clean install -Pisolates
```
Includes GraalVM-based multi-tenant isolation enclaves (requires GraalVM 24.0.2+).

**Building a single module**
```bash
./mvnw install -amd -pl <module-name>
```
Use `--also-make-dependents` to build a module with its dependencies.

> **Note**: The `install` goal is required for all builds since modules depend on each other via the local Maven repository.

### Optional: GraalVM Support

Install [GraalVM 24.0.2+](https://www.graalvm.org/latest/docs/getting-started/) for multi-tenant isolation via the `isolates` profile. On macOS with Apple Silicon, use [Homebrew](https://github.com/graalvm/homebrew-tap).

### Using Delos Modules

Delos modules are published to **GitHub Packages** for consumption by external projects.

#### Consuming from GitHub Packages

Add to your project's `pom.xml`:

```xml
<repositories>
  <repository>
    <id>github</id>
    <name>GitHub Packages</name>
    <url>https://maven.pkg.github.com/Hellblazer/Delos</url>
  </repository>
</repositories>

<pluginRepositories>
  <pluginRepository>
    <id>github</id>
    <name>GitHub Packages</name>
    <url>https://maven.pkg.github.com/Hellblazer/Delos</url>
  </pluginRepository>
</pluginRepositories>

<dependencies>
  <dependency>
    <groupId>com.hellblazer.delos</groupId>
    <artifactId>fireflies</artifactId>
    <version>0.0.11-SNAPSHOT</version>
  </dependency>
  <dependency>
    <groupId>com.hellblazer.delos</groupId>
    <artifactId>stereotomy</artifactId>
    <version>0.0.11-SNAPSHOT</version>
  </dependency>
</dependencies>
```

> **Note**: The `<pluginRepositories>` section is required for resolving Liquibase plugin schemas. Maven plugins are not resolved from regular repositories.

#### Authenticating with GitHub Packages

Create a personal access token (PAT) with `read:packages` scope at [GitHub Settings → Developer Settings](https://github.com/settings/tokens). Then add to `~/.m2/settings.xml`:

```xml
<servers>
  <server>
    <id>github</id>
    <username>YOUR_GITHUB_USERNAME</username>
    <password>YOUR_PERSONAL_ACCESS_TOKEN</password>
  </server>
</servers>
```

Alternatively, use environment variables:
```bash
export GITHUB_ACTOR=your_username
export GITHUB_TOKEN=your_pat_token
./mvnw clean deploy
```

#### Publishing Releases

Delos uses GitHub Packages for both snapshot and release deployments:

**Deploy snapshot builds** (automatic on main branch via CI)
```bash
./mvnw clean deploy
```

**Create a release** (GitHub Actions recommended)

Navigate to **Actions → Release → Run workflow** and provide:
- **Release version**: e.g., `0.1.0`
- **Next development version**: e.g., `0.1.1-SNAPSHOT`

The workflow automatically:
- Updates `pom.xml` versions in all modules
- Updates README.md with the released version
- Builds, tests, and verifies the release
- Commits and creates git tag
- Publishes artifacts to GitHub Packages
- Creates GitHub Release with auto-generated release notes
- Resets to next development version

**Local release alternative** (manual process):
```bash
# Update version
./mvnw versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false

# Manually update README.md - replace version strings
# Current version: 0.0.1-SNAPSHOT → 0.1.0
# <version>0.0.4-SNAPSHOT</version> → <version>0.0.4-SNAPSHOT</version>

# Commit release
git add -A
git commit -m "Release version 0.1.0"
git tag -a v0.1.0 -m "Release 0.1.0"

# Deploy to GitHub Packages
./mvnw clean deploy -DskipTests

# Prepare next version
./mvnw versions:set -DnewVersion=0.1.1-SNAPSHOT -DgenerateBackupPoms=false

# Manually update README.md again with next version

# Commit development version
git add -A
git commit -m "Prepare next development iteration: 0.1.1-SNAPSHOT"

# Push all changes
git push origin main v0.1.0
```

**Available modules for consumption**:
- `fireflies` — Byzantine fault-tolerant membership service
- `stereotomy` — KERI decentralized identity and key management
- `choam` — Consensus and replicated state machine
- `ethereal` — Aleph-BFT consensus
- `sql-state` — SQL state machine over JDBC
- And 15+ more. See [Modules section](#modules) above.

### Code Generation & Architecture

**Protobuf/GRPC**: All serialization and inter-process communication use Protocol Buffers and gRPC. Code generation happens in the `grpc` module (output: `grpc/target/generated-sources/`). IDE Maven integration sometimes requires manual regeneration via **Maven → generate-sources**.

**JOOQ**: SQL DSL code generation occurs in modules that define schemas (output: `{module}/target/generated-sources/jooq/`). This integrates cleanly with IDEs.

**Generated sources** are cleaned during `mvn clean` and must be regenerated. The build-helper plugin handles including them in compilation paths automatically.

### IDE Integration

#### Important: h2-deterministic Module

The `h2-deterministic` module uses package shading and **must not be imported** into your IDE. This module:
- Must be built once via `./mvnw clean install -Ppre -DskipTests`
- Can then be excluded from IDE imports (it's installed in your local Maven repository)
- Will cause compilation errors if imported into Eclipse/IntelliJ

**Setup**:
```bash
# First-time: Build deterministic SQL module from command line
./mvnw clean install -Ppre -DskipTests

# Then: Import remaining Delos modules into IDE (exclude h2-deterministic)
# Build/test from command line first with -DskipTests to warm up cache
./mvnw clean install -DskipTests
```

#### Eclipse M2E + os-maven-plugin

If Eclipse M2E can't resolve `${os.detected.classifier}`, download the [os-maven-plugin JAR](https://repo1.maven.org/maven2/kr/motd/maven/os-maven-plugin/1.7.0/os-maven-plugin-1.7.0.jar) and place it in `<ECLIPSE_HOME>/dropins`.

#### Code Generation & IDE Sync

Because Delos uses GRPC/Proto and JOOQ code generation, IDEs occasionally need a manual sync:
- **Eclipse**: Select top-level project → **Run As → Maven generate-sources**
- **IntelliJ**: Right-click pom.xml → **Run Maven → generate-sources**
- **After updates**: Build from command line first (`./mvnw clean install -DskipTests`), then refresh IDE

> The `build-helper` plugin automatically configures generated source directories; no manual configuration needed.

### Testing

**Default behavior** (as of 0.2.0):
- **Local builds**: Run large-scale tests by default (100 nodes) for thorough validation
- **CI environment**: Uses smaller tests (12-25 nodes) for fast feedback

```bash
# Local development (default: large tests with 100 nodes)
./mvnw test

# Local with small tests (faster, 25 nodes)
./mvnw test -Dlarge_tests=false

# Full build with comprehensive testing
./mvnw clean install
```

Large tests require 8+ GB RAM and validate Byzantine fault tolerance, membership convergence, and consensus at scale. They're designed as **canaries, not flaky tests** — failures indicate real bugs.

**Metrics**: Dropwizard Metrics are integrated into Fireflies, Bounded Epidemic Gossip (BEG), Ethereal, and CHOAM modules.

## License

See [LICENSE](LICENSE) file in the repository.
