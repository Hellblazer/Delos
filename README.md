# Delos

Delos is a **distributed multi-tenant database platform** providing Byzantine fault-tolerant consensus, decentralized identity management, and replicated SQL state machines. Build secure, wide-area distributed systems with verifiable credentials and role-based access control.

**Status**: [![Build Status](https://github.com/Hellblazer/delos/actions/workflows/maven.yml/badge.svg)](https://github.com/Hellblazer/Delos/actions) | Production-ready consensus & membership (Fireflies remediated Jan 2026)

**Current version**: `0.1.1`

> Not A Coin Platform™ — Delos is a distributed database, not blockchain. While it can support cryptocurrencies, that's not the design goal.

## Quick Start

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

## Features

* Multi tenant isolation enclaves using GraalVM Isolates
* Self-contained cryptography module — Self describing Digests, Signatures and Identifiers, solid Bloom Filters,
  windows, etc
* Decentralized Identifier-based foundation and key management infrastructure, based on
  the [Key Event Receipt Infrastructure](https://github.com/decentralized-identity/keri) (KERI)
* Secure and trusted attestation, identity bootstrapping and secrets provisioning
* MTLS network communication — KERI for MTLS certificate authentication. Local communication simulation for simplified
  multi-node simulation for single process (IDE) testing
* Multi instance GRPC service routing - Context keyed services and routing framework
* Byzantine intrusion tolerant secure membership and communications overlay providing virtually synchronous, stable
  membership views.
* Efficient and easy to reuse communication patterns for Fireflies ring style gossiping on membership contexts
* Reliable Broadcast — garbage collected, context routed reliable broadcast
* Efficient atomic broadcast in asynchronous networks with byzantine nodes - Consensus
* Dynamic, committee-based, causal ordering service producing linear logs - Replicated State Machines
* JDBC accessible, SQL store backed, materialized views maintained by an SQL state machine. Supports DDL, DML, stored
  procedures, functions and triggers.
* Google Zanzibar like functionality providing Relation Based Access Control hosted on SQL state machines.

## Building

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

## Using Delos Modules

Delos modules are published to **GitHub Packages** for consumption by external projects.

### Consuming from GitHub Packages

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

### Authenticating with GitHub Packages

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

### Publishing Releases

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
- And 15+ more. See [Modules section](#modules) below.

## Modules

Delos is modularized largely for subsystem isolation and reuse. Each module is a Maven module
under the source root and contains a README.md documenting the module.

* [CHOAM](choam/README.md) - Committee maintenance of replicated state machines
* [Delphinius](delphinius/README.md) - Bare bones Google Zanzibar clone
* [Domain-EPoll](domain-epoll) - linux support for Netty domain sockets
* [Domain-KQueue](domain-epoll) - mac osx support for Netty domain sockets
* [Domain-Sockets](domain-sockets) - unifying abstraction for the different OS domain sockets
* [Ethereal](ethereal/README.md) - Aleph asynchronous BFT atomic broadcast (consensus block production)
* [Fireflies](fireflies/README.md) - Byzantine intrusion tolerant, virtually synchronous membership service and secure
  communications overlay
* [Deterministic H2](h2-deterministic) - Deterministic H2 SQL Database
* [Deterministic Liquibase](liquibase-deterministic) - Deterministic Liquibase
* [Gorgoneion](gorgoneion/README.md) - Identity bootstrapping
* [Gorgoneion Client](gorgoneion-client/README.md) - Identity bootstrap client
* [Isolates](isolates/README.md) - GraalVM shared library construction of Delos subdomain enclaves.
* [Isolate Functional Testing](isolate-ftesting/README.md) - Functional testing of Delos domain enclaves.
* [Memberships](memberships/README.md) - Fundamental membership and Context model. Local and MTLS GRPC _Routers_. Ring
  communication and gossip patterns.
* [Model](model/README.md) - Replicated domains. Process and multi-tenant sharding domains and enclaves.
* [Protocols](protocols/README.md) - GRPC MTLS service fundamentals, Netflix GRPC and other rate limiters.
* [Schemas](schemas/README.md) - Liquibase SQL definitions for other modules
* [Sql-State](sql-state/README.md) - Replicated SQL state machines running on CHOAM linear logs. JDBC interface.
* [Stereotomy](stereotomy/README.md) - Key Event Receipt Infrastructure. KEL, KERL and other fundamental identity, key
  and trust management
* [Stereotomy Services](stereotomy-services) - GRPC services and protobuf interfaces for KERI services
* [Thoth](thoth/README.md) - Decentralized Stereotomy. Distributed hash table storage, protocols and API for managing
  KERI decentralized identity
* [Tron](tron/README.md) - Compact, sophisticated Finite State Machine model using Java Enums.
* [Cryptography](cryptography/README.md) - Base cryptography primitives. Bloom filters (of several varieties). Some
  general utility stuff.

## Code Generation & Architecture

**Protobuf/GRPC**: All serialization and inter-process communication use Protocol Buffers and gRPC. Code generation happens in the `grpc` module (output: `grpc/target/generated-sources/`). IDE Maven integration sometimes requires manual regeneration via **Maven → generate-sources**.

**JOOQ**: SQL DSL code generation occurs in modules that define schemas (output: `{module}/target/generated-sources/jooq/`). This integrates cleanly with IDEs.

**Generated sources** are cleaned during `mvn clean` and must be regenerated. The build-helper plugin handles including them in compilation paths automatically.

## Status

Delos is a maturing distributed platform:
- **Fireflies** (membership service): Production-ready as of Jan 2026 (109 issues remediated)
- **Ethereal** (consensus): Well-tested and hardened
- **CHOAM** (state machine replication): Production-ready
- **Stereotomy/KERI** (identity): Fully integrated
- **SQL-State**: Mature with comprehensive testing
- Other modules: Continuing development

Current version: `0.0.11-SNAPSHOT` — No official release yet, but core layers are production-hardened.

## IDE Integration

### Important: h2-deterministic Module

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

### Eclipse M2E + os-maven-plugin

If Eclipse M2E can't resolve `${os.detected.classifier}`, download the [os-maven-plugin JAR](https://repo1.maven.org/maven2/kr/motd/maven/os-maven-plugin/1.7.0/os-maven-plugin-1.7.0.jar) and place it in `<ECLIPSE_HOME>/dropins`.

### Code Generation & IDE Sync

Because Delos uses GRPC/Proto and JOOQ code generation, IDEs occasionally need a manual sync:
- **Eclipse**: Select top-level project → **Run As → Maven generate-sources**
- **IntelliJ**: Right-click pom.xml → **Run Maven → generate-sources**
- **After updates**: Build from command line first (`./mvnw clean install -DskipTests`), then refresh IDE

> The `build-helper` plugin automatically configures generated source directories; no manual configuration needed.

## Testing

By default, tests use a reduced number of simulated clients for speed. For comprehensive testing:

```bash
./mvnw clean install -Dlarge_tests=true
```

This tests with 100x more clients and longer transaction chains. Requires 8+ GB RAM and takes 10-15 minutes.

**Metrics**: Dropwizard Metrics are integrated into Fireflies, Reliable Broadcast, Ethereal, and CHOAM modules.

## Documentation

- [**docs/** ](docs/) — Deployment guides, troubleshooting, KERI integration, threat models
- [**Each module's README**](choam/README.md) — Architecture, usage patterns, threat models, tests
- [**ADRs** ](docs/adr/) — Architectural decision records for design rationale

## Contributing

Contributions are welcome! Before submitting:
1. Build with `./mvnw clean install` and verify tests pass
2. Run tests for modified modules: `./mvnw test -pl <module>`
3. Format code using project conventions
4. Reference related issues/ADRs in commit messages
5. Ensure no secrets (keys, tokens) are committed

For major changes, please open an issue first to discuss the approach.

## License

See [LICENSE](LICENSE) file in the repository.
