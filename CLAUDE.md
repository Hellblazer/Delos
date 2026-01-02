# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

**First-time setup (required once):**
```bash
./mvnw clean install -Ppre -DskipTests
```
This builds the deterministic SQL module (`h2-deterministic`) which must be installed before regular builds.

**Standard build:**
```bash
./mvnw clean install
```
The `install` goal is required as modules depend on each other via local Maven repository.

**Build single module with dependencies:**
```bash
./mvnw install -amd -pl <module-name>
```

**Run tests:**
```bash
./mvnw test                           # All tests
./mvnw test -pl <module>              # Single module tests
./mvnw test -Dtest=ClassName          # Single test class
./mvnw test -Dtest=ClassName#method   # Single test method
./mvnw clean install -Dlarge_tests=true  # Full test suite (resource-intensive)
```

**Build with GraalVM isolates:**
```bash
./mvnw clean install -Pisolates
```

**Generate sources (GRPC/Proto, JOOQ):**
```bash
./mvnw generate-sources
```

## Architecture Overview

Delos is a multi-tenant distributed system platform with Byzantine fault tolerance. Key architectural layers:

### Core Infrastructure
- **cryptography** - Self-describing digests, signatures, identifiers; Bloom filters
- **memberships** - Membership model, Context abstraction, GRPC routers, ring communication patterns
- **protocols** - GRPC MTLS service fundamentals, rate limiters
- **grpc** - All Protobuf/GRPC definitions and generated code (centralized)

### Identity & Security
- **stereotomy** - KERI (Key Event Receipt Infrastructure) implementation for decentralized identity
- **stereotomy-services** - GRPC services for KERI
- **thoth** - Distributed hash table for KERI key management
- **gorgoneion** / **gorgoneion-client** - Identity bootstrapping and attestation

### Consensus & State
- **fireflies** - Byzantine intrusion tolerant membership service and secure communications overlay
- **ethereal** - Aleph-BFT asynchronous atomic broadcast (consensus)
- **choam** - Committee-based replicated state machines on linear logs
- **sql-state** - JDBC-accessible SQL state machines on CHOAM logs

### Application Layer
- **model** - Process domains and multi-tenant sharding
- **delphinius** - Google Zanzibar-style Relation Based Access Control
- **tron** - Finite State Machine framework using Java Enums

### Platform Support
- **domain-sockets** / **domain-epoll** / **domain-kqueue** - Unix domain socket support
- **leyden** - Additional platform features
- **isolates** - GraalVM isolate-based multi-tenant enclaves (requires `-Pisolates`)

## Key Patterns

### Code Generation
- GRPC/Protobuf generation is centralized in the `grpc` module
- JOOQ generation occurs in modules that define schemas
- Generated sources go to `target/generated-sources/` (cleaned on `mvn clean`)

### Testing
- Tests use dynamic ports to avoid conflicts
- Standard tests: reduced client count; Full tests: `-Dlarge_tests=true`
- JUnit 5 with Mockito, AssertJ

### CRITICAL: Member Map Ordering in Fireflies Tests
When writing Fireflies tests that create member maps and seed lists, **you MUST use LinkedHashMap** to preserve insertion order. HashMap has non-deterministic iteration order which causes seeds to contact wrong nodes.

**The Bug Pattern (DO NOT USE):**
```java
// WRONG - HashMap has random iteration order
members = new HashMap<>();
identities.forEach((d, id) -> members.put(d, new ControlledIdentifierMember(id)));

// WRONG - Collectors.toMap() creates HashMap by default
members = identities.values().stream()
    .map(identity -> new ControlledIdentifierMember(identity))
    .collect(Collectors.toMap(m -> m.getId(), m -> m));
```

**The Correct Pattern (ALWAYS USE):**
```java
// CORRECT - LinkedHashMap preserves insertion order
members = new LinkedHashMap<>();
identities.forEach((d, id) -> members.put(d, new ControlledIdentifierMember(id)));

// CORRECT - Explicit LinkedHashMap supplier
members = identities.values().stream()
    .map(identity -> new ControlledIdentifierMember(identity))
    .collect(Collectors.toMap(m -> m.getId(), m -> m, (a, b) -> a, LinkedHashMap::new));
```

**Why This Matters:**
- `identities` is typically a TreeMap (sorted by digest)
- `views` are created by iterating over identities or members
- `seedList` is created from `members.values()`
- If members is a HashMap, `seedList.get(0)` may not correspond to `views.get(0)` (the kernel)
- Seeds then contact unstarted nodes, causing cascade join failures

### Deterministic SQL
The `h2-deterministic` and `liquibase-deterministic` modules provide deterministic SQL execution for replicated state machines. The `h2-deterministic` module uses package shading and must NOT be imported into IDEs.

## Technology Stack
- Java 23+ (configured in pom.xml)
- Maven 3.8.1+ with Maven Wrapper
- gRPC 1.68.0 / Protobuf 4.28.2
- H2 Database, JOOQ, Liquibase
- Netty 4.1.x for networking
- Bouncy Castle for cryptography
- Dropwizard Metrics
- SLF4J/Logback logging

## Module Dependencies
Modules depend on each other through the local Maven repository. Always run `install` (not just `compile`) when building. The parent POM enforces dependency convergence.