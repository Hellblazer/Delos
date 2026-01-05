# FPF Bounded Context: Delos

**Initialized**: 2025-12-31
**Domain**: Distributed Systems / Byzantine Fault Tolerant Platform

## Project Summary

Delos is an experimental multi-tenant distributed system platform providing:
- Byzantine fault tolerant consensus (Ethereal - Aleph-BFT)
- Secure communications overlay (Fireflies)
- Replicated state machines with JDBC-accessible SQL (CHOAM + sql-state)
- Decentralized identity via KERI (Stereotomy)
- Relation-based access control (Delphinius - Google Zanzibar-style)

## Vocabulary (U.BoundedContext Terms)

### Core Infrastructure
- **Digest**: Self-describing cryptographic hash with algorithm identifier
- **Signature**: Self-describing cryptographic signature with key identifier
- **Identifier**: Self-describing cryptographic public key identifier
- **Context**: Membership abstraction for routing and ring communication patterns
- **Bloom Filter**: Probabilistic data structure for set membership testing

### Identity & Security
- **KERI**: Key Event Receipt Infrastructure - decentralized identity standard
- **KEL**: Key Event Log - append-only log of key events for an identifier
- **KERL**: Key Event Receipt Log - KEL with receipts from witnesses
- **Stereotomy**: Delos KERI implementation module
- **Thoth**: DHT-based distributed KERI key management
- **Gorgoneion**: Identity bootstrapping and attestation service

### Consensus & State
- **Fireflies**: Byzantine intrusion tolerant membership service
- **Ethereal**: Aleph-BFT asynchronous atomic broadcast (consensus)
- **CHOAM**: Committee-based replicated state machines on linear logs
- **Unit**: Atomic consensus unit in Ethereal (contains transactions)
- **Round**: Consensus epoch in Ethereal
- **View**: Membership view in CHOAM (committee configuration)
- **Block**: Ordered batch of transactions from consensus

### Application Layer
- **Delphinius**: Relation-based access control (ReBAC) service
- **Relation**: Subject-Object-Predicate tuple in ReBAC
- **Assertion**: A claimed relation in the ReBAC graph
- **Oracle**: Service that evaluates access control queries
- **Tron**: Finite state machine framework using Java Enums

### Multi-tenancy
- **Domain**: Isolated tenant namespace
- **Enclave**: GraalVM isolate-based tenant isolation
- **Process Domain**: Sharded multi-tenant execution environment

## Invariants (System-Wide Constraints)

### Build System
- INV-B1: Maven 3.9.3+ required; use `./mvnw` wrapper
- INV-B2: Java 23+ required (`version.java=23` in pom.xml)
- INV-B3: `install` goal required (not just `compile`) due to inter-module dependencies
- INV-B4: First-time build requires `-Ppre -DskipTests` for h2-deterministic
- INV-B5: h2-deterministic module must NOT be imported into IDE

### Code Generation
- INV-G1: All GRPC/Protobuf generation centralized in `grpc` module
- INV-G2: Generated sources in `target/generated-sources/` (cleaned on `mvn clean`)
- INV-G3: JOOQ generation occurs in defining modules

### Coding Standards
- INV-C1: Java 23+ idioms; use `var` everywhere
- INV-C2: No `synchronized` blocks; use concurrent collections
- INV-C3: Dynamic ports in tests to avoid conflicts
- INV-C4: SLF4J logging with `{}` placeholders (not Python-style)
- INV-C5: JUnit 5 + Mockito + AssertJ for testing

### Architecture
- INV-A1: MTLS for all inter-process communication
- INV-A2: Protobuf for all serialization
- INV-A3: gRPC for all RPC
- INV-A4: Deterministic SQL execution for replicated state machines

### Security
- INV-S1: Byzantine fault tolerance assumed (up to f < n/3 malicious nodes)
- INV-S2: All cryptographic operations use Bouncy Castle
- INV-S3: KERI-based identity validation required for membership

## Technology Stack

| Layer | Technology | Version |
|-------|------------|---------|
| Language | Java | 23+ |
| Build | Maven | 3.9.3+ |
| RPC | gRPC | 1.68.0 |
| Serialization | Protobuf | 4.28.2 |
| Database | H2 (deterministic) | 2.2.224 |
| SQL DSL | JOOQ | 3.18.15 |
| Migrations | Liquibase | 4.8.0 |
| Networking | Netty | 4.1.x |
| Crypto | Bouncy Castle | 1.78.1 |
| Metrics | Dropwizard | 4.0.8 |
| Logging | SLF4J/Logback | 2.0.3/1.5.8 |

## Module Dependency Graph (Key Paths)

```
cryptography (foundation)
    ↓
memberships (Context, routing)
    ↓
protocols (MTLS, rate limiting)
    ↓
stereotomy (KERI identity)
    ↓
fireflies (membership overlay)
    ↓
ethereal (consensus)
    ↓
choam (state machines)
    ↓
sql-state (JDBC interface)
    ↓
delphinius (ReBAC)
    ↓
model (domains, multi-tenancy)
```

## Current Branch Context

**Branch**: `fix/ethereal-signature-validation`
**Focus**: Ethereal consensus signature validation
**Recent Commits**:
- Implement Ethereal consensus signature validation [Delos-jdc, Delos-m1e, Delos-9qr]
