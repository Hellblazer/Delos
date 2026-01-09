# Delos Documentation Index

**Master Navigation Guide for Delos Distributed Systems Platform**

**Last Updated**: 2026-01-09
**Version**: 1.0
**Status**: Complete Reference - All Major Documentation Sections

---

## Quick Start

### For Different Roles

**I want to...**

- **Deploy Delos to production**: Start with [Deployment Guide](#deployment-guide)
- **Understand the security model**: Read [Security Threat Model](#security-threat-model)
- **Develop a new module**: See [Developer Guide](#developer-guide--getting-started)
- **Understand a specific component**: Browse [Component Modules](#modules--component-documentation)
- **Troubleshoot an issue**: Go to [Troubleshooting](#troubleshooting--common-issues)
- **Monitor Delos in production**: See [Operations Guide](#operations--monitoring)
- **Learn about Byzantine fault tolerance**: Read [Consensus Architecture](#consensus--agreement)

---

## Foundation Documentation

These documents establish the core concepts, security model, and deployment procedures for Delos.

### Security & Threat Model

**[SECURITY_THREAT_MODEL.md](SECURITY_THREAT_MODEL.md)**
- **Purpose**: Comprehensive threat analysis, adversary model, defense mechanisms
- **Audience**: Security teams, system architects, operations
- **Key Sections**:
  - Executive summary of security guarantees
  - Adversary model (passive network, active network, Byzantine, catastrophic)
  - Assets and their protection mechanisms
  - Attack vectors by architectural layer (7 layers analyzed)
  - Defense mechanisms (network, message auth, BFT, consensus, replication, identity, credentials)
  - Byzantine fault tolerance analysis
  - Disaster recovery and resilience
  - Security checklist for production deployment
  - Incident response procedures
- **Length**: 1000+ lines
- **Status**: ✅ Complete and production-ready

### Deployment Guide

**[DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md)**
- **Purpose**: Step-by-step production deployment procedures
- **Audience**: Operations teams, DevOps engineers, system administrators
- **Key Sections**:
  - Pre-deployment checklist with infrastructure requirements
  - Security requirements and integration with threat model
  - Node setup and configuration
  - KERI identity provisioning procedures
  - TLS/MTLS certificate configuration
  - Systemd service setup
  - Multi-node cluster bootstrap with 5-phase procedures
  - Health checks and operational tasks
  - Backup and disaster recovery procedures
  - Rolling updates and scaling
  - Pre-production security checklist (50+ verification items)
  - Sign-off procedures for production deployment
- **Length**: 1150+ lines
- **Status**: ✅ Complete with enhanced bootstrap procedures

### Module Documentation Template

**[MODULE_DOCUMENTATION_TEMPLATE.md](MODULE_DOCUMENTATION_TEMPLATE.md)**
- **Purpose**: Standardized template for module documentation
- **Audience**: Module developers, technical writers, documentation contributors
- **Key Sections**:
  - 12 standard sections (Overview, Architecture, Design, API, Examples, Performance, Metrics, Testing, Troubleshooting, Status, References)
  - Guidance on Mermaid diagrams
  - Code example best practices
  - Metric documentation patterns
  - Usage notes and anti-patterns
- **Length**: 457 lines
- **Status**: ✅ Complete - serves as standardization guide

### Module Standardization Status

**[MODULE_STANDARDIZATION_STATUS.md](MODULE_STANDARDIZATION_STATUS.md)**
- **Purpose**: Track documentation quality and standardization progress
- **Audience**: Project managers, quality leads, documentation coordinators
- **Key Sections**:
  - Module quality matrix (all 33 modules assessed)
  - Excellent documentation examples (5 modules: fireflies, ethereal, choam, sql-state, tron)
  - Standardization progress (1/15 modules complete)
  - High-priority modules (8 modules identified for standardization)
  - Best practices discovered and anti-patterns to avoid
  - 4-week execution plan for continued standardization
  - Success criteria for Phase 1.3
- **Length**: 322 lines
- **Status**: ✅ Complete - roadmap for ongoing standardization

---

## Architecture & Design

### System Architecture

**README.md** (Root level)
- Overview of Delos architecture
- Module dependency diagram
- Key concepts introduction
- Link to Architecture Decision Records (ADRs)

### Key Architectural Decisions

**adr/** directory (in progress)
- ADR-0001: Core identity model (KERI)
- ADR-0002: KERI implementation architecture
- ADR-0003: Byzantine consensus design
- ADR-0004: CHOAM committee-based consensus
- [More ADRs available in adr/ directory](../adr/)

### Contributing Guide

**[CONTRIBUTING.md](../CONTRIBUTING.md)**
- How to contribute to Delos
- Development setup
- Code style guidelines
- PR process
- Testing requirements

---

## Modules & Component Documentation

### Foundation Layer (Cryptography & Protocol)

#### Cryptography

**[cryptography/README.md](../cryptography/README.md)** ✅ STANDARDIZED
- Self-describing digests and qualified signatures
- Digest/Signature algorithms (Ed25519, RSA, ECDSA)
- JohnHancock multi-signature support
- HexBloom probabilistic set membership
- API Reference: Digest, DigestAlgorithm, SignatureAlgorithm, Signer, Verifier, JohnHancock
- Performance: Ed25519 10K sig/sec, SHA-256 300 MB/sec
- **Status**: Production-ready, comprehensively documented (458 lines)

#### Protocols

**[protocols/README.md](../protocols/README.md)** ⏱️ PENDING STANDARDIZATION
- GRPC communications abstraction
- MTLS server and client configuration
- Protocol multiplexing
- **Status**: Needs standardization (16 lines → target 300+ lines)

#### Memberships

**[memberships/README.md](../memberships/README.md)**
- Membership model and Context abstraction
- GRPC routers and ring communication patterns
- Context interface for system knowledge
- **Status**: Partial documentation (127 lines → target 200+ lines)

### Identity & Security Layer

#### Stereotomy (KERI Implementation)

**[stereotomy/README.md](../stereotomy/README.md)**
- KERI (Key Event Receipt Infrastructure) for decentralized identity
- Key events and KERL (Key Event Receipt Log)
- Identity bootstrap and verification
- **Status**: Minimal documentation (44 lines → target 350+ lines)

#### Stereotomy Services

**[stereotomy-services/README.md](../stereotomy-services/README.md)**
- GRPC services for KERI operations
- Witness configuration
- **Status**: Minimal (1 line → target 250+ lines)

#### Thoth (DHT for Key Management)

**[thoth/README.md](../thoth/README.md)**
- Distributed Hash Table for KERI key events
- Key lookup and routing
- **Status**: Minimal documentation (33 lines → target 250+ lines)

#### Gorgoneion (Identity Bootstrapping & Attestation)

**[gorgoneion/README.md](../gorgoneion/README.md)**
- Identity bootstrapping using KERI
- Credential validation with Byzantine consensus
- Replay attack prevention
- Clock skew tolerance
- **Status**: Partial documentation (147 lines → target 250+ lines)

#### Gorgoneion Client

**[gorgoneion-client/README.md](../gorgoneion-client/README.md)**
- Client library for identity operations
- **Status**: Minimal (2 lines)

### Consensus & State Management Layer

#### Ethereal (Aleph-BFT Consensus)

**[ethereal/README.md](../ethereal/README.md)** ✅ COMPREHENSIVE
- Asynchronous atomic broadcast (Aleph-BFT)
- Byzantine consensus algorithm
- DAG-based ordering
- Liveness and safety guarantees
- **Status**: Excellently documented (594 lines)

#### Fireflies (Byzantine Membership Service)

**[fireflies/README.md](../fireflies/README.md)** ✅ COMPREHENSIVE
- Gossip-based Byzantine membership
- View formation and failure detection
- Probabilistic infection-style gossip
- **Status**: Excellently documented (626 lines)

#### CHOAM (Committee-Based Consensus)

**[choam/README.md](../choam/README.md)** ✅ COMPREHENSIVE
- Committee-based replicated state machines
- Linear logs and checkpointing
- Consensus on total ordering
- **Status**: Excellently documented (612 lines)

#### SQL-State (Replicated State Machines)

**[sql-state/README.md](../sql-state/README.md)** ✅ COMPREHENSIVE
- JDBC-accessible replicated state machines
- Deterministic SQL execution
- Materialized views and snapshots
- **Status**: Excellently documented (766 lines, most detailed)

#### Model (Process Domains & Multi-Tenancy)

**[model/README.md](../model/README.md)**
- Process domains for multi-tenant sharding
- State machine configuration
- **Status**: Minimal (3 lines → target 200+ lines)

### Application Layer

#### Delphinius (Relation-Based Access Control)

**[delphinius/README.md](../delphinius/README.md)**
- Google Zanzibar-style relation-based access control (ReBAC)
- Permission model
- Query evaluation
- **Status**: Minimal documentation (87 lines → target 250+ lines)

#### Tron (FSM Framework)

**[tron/README.md](../tron/README.md)** ✅ COMPREHENSIVE
- Finite State Machine framework using Java Enums
- Entry/exit actions and guards
- State hierarchies
- Usage examples with full working code
- **Status**: Excellently documented (736 lines)

### Platform Support Layer

#### Domain Sockets

**[domain-sockets/README.md](../domain-sockets/README.md)**
- Unix domain socket support
- **Status**: Minimal

#### Domain EPoll (Linux-specific)

**[domain-epoll/README.md](../domain-epoll/README.md)**
- Linux EPoll for efficient I/O
- **Status**: Minimal

#### Domain Kqueue (BSD-specific)

**[domain-kqueue/README.md](../domain-kqueue/README.md)**
- BSD kqueue for efficient I/O
- **Status**: Minimal

#### Leyden (Platform Features)

**[leyden/README.md](../leyden/README.md)**
- Additional platform features
- **Status**: Minimal (3 lines → target 200+ lines)

#### Java Noise (Noise Protocol Implementation)

**[java-noise/README.md](../java-noise/README.md)**
- Noise protocol for cryptographic handshake
- **Status**: Partial (233 lines)

### Code Generation & Utilities

#### GRPC Module

**[grpc/README.md](../grpc/README.md)**
- Protocol Buffer definitions
- GRPC service generation
- **Status**: Minimal (3 lines → target 250+ lines)

#### Schemas

**[schemas/README.md](../schemas/README.md)**
- Database schema definitions
- Liquibase changesets
- **Status**: Minimal (3 lines → target 200+ lines)

#### H2-Deterministic

**src/main/java/com/hellblazer/delos/h2_deterministic/README.md**
- H2 database with deterministic execution
- Package shading configuration
- **Note**: Do not import to IDEs
- **Status**: Minimal

#### Liquibase-Deterministic

**[liquibase-deterministic/README.md](../liquibase-deterministic/README.md)**
- Deterministic schema versioning
- **Status**: Minimal (3 lines)

#### Isolates (GraalVM Isolates)

**[isolates/README.md](../isolates/README.md)**
- GraalVM isolate-based multi-tenant enclaves
- Requires `-Pisolates` profile
- **Status**: Minimal (3 lines → target 250+ lines)

### Examples & Tools

#### Local Demo

**[examples/local-demo/README.md](../examples/local-demo/README.md)**
- Local cluster demonstration
- Running example with multiple nodes
- **Status**: Partial documentation

#### Simple KV Store Example

**[examples/simple-kv-store/README.md](../examples/simple-kv-store/README.md)**
- Simple key-value store built on Delos
- **Status**: Partial documentation

---

## Developer Enablement (Phase 2)

### Transaction Flow Understanding

**[TRANSACTION_FLOW_GUIDE.md](TRANSACTION_FLOW_GUIDE.md)** ✅ NEW
- Complete 7-stage transaction lifecycle (client → consensus → execution → response)
- Sequence diagrams for happy path and Byzantine scenarios
- Detailed latency breakdown (typical 250ms)
- Logging patterns at each stage for debugging
- Key metrics to monitor
- Common issues and diagnosis
- Testing examples
- **Length**: 791 lines
- **Status**: ✅ Complete - enables developers to understand transaction flow

### IDE Setup & Development Workflow

**[IDE_SETUP.md](IDE_SETUP.md)** ✅ NEW
- IntelliJ IDEA setup (recommended, with pre-configs)
- VS Code setup (lightweight alternative)
- Eclipse setup (not recommended)
- Command-line development
- IDE shortcuts reference
- Development workflow (TDD, testing, debugging)
- Productivity tips
- Environment setup
- **Length**: 614 lines
- **Status**: ✅ Complete - get productive in 15-30 minutes

### Coming Soon (Phase 2 Continuation)

**API_REFERENCE.md** (In Progress)
- Public API catalog for all core modules
- Method signatures, parameters, examples
- Cross-references to module READMEs
- Target: 600+ lines

**INTEGRATION_PATTERNS.md** (Planned)
- Multi-tenant example
- FSM workflow example
- 5+ integration patterns
- Target: 300-400 lines

---

## Operations & Maintenance

### Operational Procedures (Planned for Phase 3)

**MONITORING_GUIDE.md** (Coming Soon)
- Metrics and observability
- Health checks
- Alerting configuration
- Dashboard setup

**TROUBLESHOOTING_GUIDE.md** (Coming Soon)
- Common issues and solutions
- Diagnosis procedures
- Recovery procedures
- Performance troubleshooting

**DISASTER_RECOVERY_GUIDE.md** (Coming Soon)
- Backup procedures
- Restore procedures
- RTO/RPO planning
- Incident response

**DEBUGGING_GUIDE.md** (Planned for Phase 2.4)
- Remote debugging setup
- Multi-node cluster debugging
- Log analysis for consensus issues
- Common debugging scenarios

---

## Developer Guide & Getting Started

### Build System

**Build Commands**:
```bash
# First-time setup (required)
./mvnw clean install -Ppre -DskipTests

# Standard build
./mvnw clean install

# Build single module with dependencies
./mvnw install -amd -pl <module-name>

# Run tests
./mvnw test                               # All tests
./mvnw test -pl <module>                  # Single module
./mvnw test -Dtest=ClassName              # Single test class
./mvnw clean install -Dlarge_tests=true   # Full test suite
```

See [CLAUDE.md](../CLAUDE.md) in repository root for complete build instructions.

### IDE Setup

**IntelliJ IDEA**:
- Import as Maven project
- Use .idea configuration in repository
- Run configurations in .run/ directory

**VS Code**:
- Install Language Support for Java (Red Hat)
- Install Maven for Java (Microsoft)
- Install Protocol Buffers extension

**Eclipse**:
- Use M2Eclipse plugin
- Import as "Existing Maven Project"

### Testing

**Unit Tests**:
```bash
./mvnw test -pl <module> -Dtest=<TestClass>
```

**Integration Tests**:
- Tests use dynamic ports (port 0 allocation)
- Multi-node cluster formation in tests
- Standard tests: reduced load
- Large tests: `-Dlarge_tests=true` for full load

**Test Patterns**:
- JUnit 5 with AssertJ assertions
- Mockito for mocking
- Dynamic port allocation for test isolation

---

## Technology Stack

### Core Technologies

- **Java**: 25+ (configured in pom.xml)
- **Maven**: 3.9.3+ with Maven Wrapper
- **GRPC**: 1.68.0 / Protobuf 4.28.2
- **Database**: H2, JOOQ, Liquibase
- **Networking**: Netty 4.1.x
- **Cryptography**: Bouncy Castle
- **Metrics**: Dropwizard Metrics
- **Logging**: SLF4J/Logback

### Dependency Management

- **Parent POM**: Enforces dependency convergence
- **Module dependencies**: Flow through local Maven repository
- **First-time setup**: Must run `-Ppre` profile for h2-deterministic

---

## Documentation Status by Phase

### Phase 1: Foundation ✅ (COMPLETE)

- ✅ **Phase 1.1**: Security Threat Model Document (1000+ lines)
- ✅ **Phase 1.2**: Enhanced Deployment Guide (1150+ lines)
- ✅ **Phase 1.3**: Module Documentation Template & Standardization (1/15 modules, roadmap complete)
- ✅ **Phase 1.4**: Documentation Index (this document, 685 lines)

### Phase 2: Developer Enablement 🔄 (IN PROGRESS - 50%)

- ✅ **Phase 2.1**: End-to-End Transaction Flow Guide (791 lines) - COMPLETE
- ⏱️ **Phase 2.2**: Integration Examples & Sample Code - PENDING (depends on 2.1, 2.3)
- ⏱️ **Phase 2.3**: API Reference Documentation - IN PROGRESS
- ✅ **Phase 2.4**: Development Workflow & IDE Setup (614 lines) - COMPLETE

### Phase 3: Operations & Maintenance ⏱️ (PLANNED)

- **Phase 3.1**: Performance Tuning & Capacity Planning
- **Phase 3.2**: Operational Procedures (runbooks)
- **Phase 3.3**: Disaster Recovery & Backup
- **Phase 3.4**: Troubleshooting Decision Tree
- **Phase 3.5**: Monitoring & Alerting Playbook

### Phase 4: Enhancement & Polish ⏱️ (PLANNED)

- **Phase 4.1**: Architectural Deep-Dives (5-6 modules)
- **Phase 4.2**: ADRs for Recent Work (3-4 new ADRs)
- **Phase 4.3**: Visual Documentation Improvements (8-10 diagrams)
- **Phase 4.4**: Glossary Completion

### Phase 5: Community & Maintenance ⏱️ (PLANNED)

- **Phase 5.1**: Examples Documentation
- **Phase 5.2**: FAQ & Common Patterns
- **Phase 5.3**: Documentation Maintenance Procedures

---

## Cross-Module Navigation

### By Layer

**Foundation Layer**:
- [Cryptography](../cryptography/README.md) - Self-describing digests and signatures
- [Protocols](../protocols/README.md) - GRPC and MTLS
- [Memberships](../memberships/README.md) - Membership model

**Identity & Security Layer**:
- [Stereotomy](../stereotomy/README.md) - KERI implementation
- [Thoth](../thoth/README.md) - DHT for keys
- [Gorgoneion](../gorgoneion/README.md) - Identity bootstrapping
- Security Threat Model (this directory)

**Consensus & State Layer**:
- [Fireflies](../fireflies/README.md) - Byzantine membership
- [Ethereal](../ethereal/README.md) - Aleph-BFT consensus
- [CHOAM](../choam/README.md) - Committee consensus
- [SQL-State](../sql-state/README.md) - Replicated state

**Application Layer**:
- [Delphinius](../delphinius/README.md) - ReBAC authorization
- [Tron](../tron/README.md) - FSM framework
- [Model](../model/README.md) - Domain models

### By Complexity

**Start Here (Beginner)**:
1. [README.md](../README.md) - System overview
2. [CLAUDE.md](../CLAUDE.md) - Build and setup
3. [Deployment Guide](DEPLOYMENT_GUIDE.md) - Production procedures

**Intermediate (Developer)**:
1. [Cryptography](../cryptography/README.md) - Core abstractions
2. [Tron](../tron/README.md) - FSM framework
3. [Memberships](../memberships/README.md) - System concepts
4. [SECURITY_THREAT_MODEL.md](SECURITY_THREAT_MODEL.md) - Security model

**Advanced (Architect)**:
1. [Fireflies](../fireflies/README.md) - Byzantine membership
2. [Ethereal](../ethereal/README.md) - BFT consensus
3. [CHOAM](../choam/README.md) - Committee consensus
4. [SQL-State](../sql-state/README.md) - Replicated state
5. [Stereotomy](../stereotomy/README.md) - KERI identity
6. [adr/](../adr/) - Architecture decisions

### By Use Case

**I want to deploy to production**:
1. [SECURITY_THREAT_MODEL.md](SECURITY_THREAT_MODEL.md) - Understand security
2. [DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md) - Follow deployment steps
3. [Troubleshooting Guide](TROUBLESHOOTING_GUIDE.md) - Prepare for issues

**I want to develop a new application**:
1. [Model](../model/README.md) - Define domains
2. [Tron](../tron/README.md) - State machine logic
3. [Delphinius](../delphinius/README.md) - Authorization
4. [SQL-State](../sql-state/README.md) - Persistent state

**I want to understand consensus**:
1. [Fireflies](../fireflies/README.md) - Membership
2. [Ethereal](../ethereal/README.md) - BFT algorithm
3. [CHOAM](../choam/README.md) - Committee consensus
4. [adr/0004-consensus-design-choam.md](../adr/0004-consensus-design-choam.md) - Design rationale

**I want to extend the platform**:
1. [MODULE_DOCUMENTATION_TEMPLATE.md](MODULE_DOCUMENTATION_TEMPLATE.md) - Documentation guide
2. [CONTRIBUTING.md](../CONTRIBUTING.md) - Contribution process
3. Review related module documentation
4. Follow existing patterns

---

## Key Concepts Glossary

**Byzantine Fault Tolerance (BFT)**
- System tolerates up to f Byzantine (arbitrarily behaving) nodes out of n total nodes
- Requires n ≥ 3f + 1 for consensus with BFT guarantees
- See: [SECURITY_THREAT_MODEL.md](SECURITY_THREAT_MODEL.md), [Ethereal](../ethereal/README.md)

**Consensus**
- Agreement protocol ensuring all correct nodes reach same state
- See: [Ethereal](../ethereal/README.md) for BFT consensus, [CHOAM](../choam/README.md) for committee consensus

**KERL (Key Event Receipt Log)**
- Append-only log of cryptographically signed key events
- Provides non-repudiation for identity operations
- See: [Stereotomy](../stereotomy/README.md)

**Deterministic Replication**
- State machines produce same output for same input sequence
- Enables consensus without state comparison
- See: [SQL-State](../sql-state/README.md), [CHOAM](../choam/README.md)

**Self-Describing Digests**
- Cryptographic hash that includes algorithm information
- Enables algorithm-agnostic system design
- See: [Cryptography](../cryptography/README.md)

**Multi-Tenant Sharding**
- Process domains for isolating and scaling multi-tenant applications
- See: [Model](../model/README.md)

**Relation-Based Access Control (ReBAC)**
- Authorization model based on relationships between entities
- Alternative to role-based access control (RBAC)
- See: [Delphinius](../delphinius/README.md)

---

## Quick Reference

### Useful Commands

```bash
# Build
./mvnw clean install

# Test single module
./mvnw test -pl fireflies

# Run specific test
./mvnw test -Dtest=ViewTest -pl fireflies

# Check dependencies
./mvnw dependency:tree -pl choam

# Generate sources (GRPC, JOOQ)
./mvnw generate-sources

# Full test suite (resource-intensive)
./mvnw clean install -Dlarge_tests=true
```

### Key Files

| File | Purpose |
|------|---------|
| `pom.xml` | Parent POM with dependency management |
| `CLAUDE.md` | Build and IDE setup instructions |
| `CONTRIBUTING.md` | Contribution guidelines |
| `docs/` | All documentation |
| `adr/` | Architecture Decision Records |
| `.github/workflows/` | CI/CD pipelines |

---

## Support & Help

### Getting Help

1. **Check Documentation First**: Start with [relevant module README](../README.md)
2. **Search Issues**: Look for similar issues on GitHub
3. **Review ADRs**: Architecture decisions in [adr/](../adr/)
4. **Ask Questions**: Open an issue or discussion on GitHub

### Reporting Issues

When reporting issues, include:
- Delos version
- Java version
- Reproduction steps
- Error logs (with sensitive data removed)
- Environment details (OS, network, cluster size)

---

## Contributing to Documentation

### Documentation Guidelines

1. **Use MODULE_DOCUMENTATION_TEMPLATE.md** for module docs
2. **Include Working Code Examples** - test them first
3. **Add Mermaid Diagrams** for architecture/flows
4. **Document Metrics** - essential for operators
5. **Write Troubleshooting** - helps deployments
6. **Cross-Reference** - link related modules

See [CONTRIBUTING.md](../CONTRIBUTING.md) for detailed guidelines.

---

## License

All Delos documentation is licensed under the BSD 3-Clause License, consistent with the codebase.

---

**Master Documentation Index v1.0**
Last Updated: 2026-01-09
Maintained By: Delos Documentation Team
Status: Complete for Phase 1 - Foundation
