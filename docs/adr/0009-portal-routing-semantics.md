# ADR-0009: Portal Routing Semantics for Multi-Tenant DelegatedDomains

**Status**: ACCEPTED

**Date**: 2026-02-13

**Context**

ProcessContainerDomain spawns isolated DelegatedDomains via GraalVM isolates or in-process execution (see ADR-0008). Parent and subdomain communicate over Unix domain sockets using gRPC. The Portal abstraction (from `archipelago` module) provides transparent client-side routing to subdomain services.

**Problem**: How should the parent domain identify and route requests to the correct subdomain?

Key considerations:
1. Subdomain identity is cryptographic (KERI-based digest)
2. Multiple subdomains may exist per parent
3. Routing must work across both isolation modes (JniBridge, DemesneImpl)
4. Unix socket paths must be deterministic for discovery
5. Routing must be thread-safe (concurrent spawn/deregister)
6. Parent must track subdomain lifecycle (register on spawn, deregister on shutdown)

**Decision**

**Portal routing uses the subdomain's context digest as the routing key.**

Routing mechanism:
```java
// Key: base64-encoded context digest (SubContext.context field)
String routingKey = qb64(Digest.from(subContext.getContext()));

// Value: Unix domain socket address for subdomain
UnixDomainSocketAddress socketAddress = UnixDomainSocketAddress.of(socketPath);

// Thread-safe routing map
private final Map<String, UnixDomainSocketAddress> routes = new ConcurrentHashMap<>();
```

Registration flow:
1. ProcessContainerDomain.spawn() creates subdomain
2. Subdomain establishes Unix socket at deterministic path
3. Parent calls `register(SubContext)` with context + socket address
4. Portal can now route `Portal.link(contextDigest)` to correct socket

Deregistration flow:
1. Subdomain.stop() called or crash detected
2. Parent calls `deregister(contextDigest)`
3. Routes entry removed (socket cleanup handled separately)

**Rationale**

**Why context digest (not enclave digest)?**
- `SubContext.context` uniquely identifies the subdomain instance
- `SubContext.enclave` identifies the parent, not useful for routing
- Context digest matches CHOAM's identity model
- Enables consistent routing across isolation modes

**Why ConcurrentHashMap?**
- Portal route resolver runs concurrently with spawn/deregister
- HashMap would cause race conditions (see code review finding)
- ConcurrentHashMap provides lock-free reads, safe writes
- Minimal overhead for routing lookups

**Why deterministic socket paths?**
- Enables discovery without external coordination
- Pattern: `/tmp/delos/{contextDigest}.sock`
- Parent knows socket path from context digest alone
- Cleanup simplified (remove socket file on deregister)

**Why not separate routing service?**
- Portal is already embedded in ProcessContainerDomain
- Routing table is small (# subdomains typically < 100)
- No need for distributed routing (single parent, local sockets)
- Keeps architecture simple

**Alternatives Considered**

**A. Enclave digest as routing key**
- Rejected: Enclave digest identifies parent, not subdomain
- Would require separate tenant-to-enclave mapping

**B. Numeric IDs (auto-increment)**
- Rejected: Not cryptographically verifiable
- Difficult to coordinate across parent restarts
- Loses KERI-based identity guarantees

**C. Separate routing registry service**
- Rejected: Over-engineered for local routing
- Adds latency and complexity
- Portal already provides client-side routing

**D. Service discovery via filesystem**
- Rejected: Timing issues (socket creation race)
- Fragile (relies on filesystem polling)
- No explicit lifecycle management

**Consequences**

**Positive:**
- ✅ Cryptographically verifiable routing keys
- ✅ Thread-safe concurrent routing
- ✅ Deterministic socket paths simplify discovery
- ✅ Consistent identity model (CHOAM context digests)
- ✅ Simple implementation (single ConcurrentHashMap)

**Negative:**
- ⚠️ Routing table in memory only (lost on parent restart)
- ⚠️ Manual registration required (not automatic discovery)
- ⚠️ Socket path length limited by OS (108 chars on Linux)

**Mitigations:**
- Parent restart: Subdomains re-register on reconnection
- Socket path length: Use base64 truncation if needed
- Manual registration: Enforced by spawn() contract

**Related Decisions:**
- ADR-0008: Subdomain isolation strategy (JniBridge vs DemesneImpl)
- ADR-0012: Unix domain socket architecture

**Implementation Status:**
- ConcurrentHashMap: ✅ Complete (Delos-ae0f)
- Registration: ⏸️ Blocked on protobuf changes (Delos-mka0)
- Deregistration: ⏸️ Not implemented
