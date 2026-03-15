---
title: "Portal Routing Registration for Multi-Tenant ProcessContainerDomain"
id: RDR-002
type: design
status: accepted
accepted_date: 2026-03-14
reviewed-by: self
priority: P0
created: 2026-03-14
revised: 2026-03-14
author: hal.hildebrand
gate-history: "2026-03-14: BLOCKED (4C/3S) → 2026-03-14: PASSED (0C/2S/3O)"
---

# RDR-002: Portal Routing Registration for Multi-Tenant ProcessContainerDomain

## Problem Statement

Multi-tenant routing in `ProcessContainerDomain` is non-functional due to three gaps:

1. **The bridge gRPC server is never started.** `outerContextService()` (line 256) is dead code — no `NettyServerBuilder` binds it. Subdomains call `outer.register(SubContext)` but nothing is listening. Registration, deregistration, and `markRunning()` are all unreachable in production.
2. **No client-facing routing API.** Callers have no way to obtain a gRPC channel to a subdomain. Portal routes via bridge socket + metadata headers, but SubDomainHandle exposes no channel accessor.
3. **No deregistration on stop.** Routes are never cleaned up when a subdomain shuts down or crashes.

`DemesneTest.smokin()` demonstrates a working registration flow by manually constructing a `NettyServerBuilder` (lines 239-248) — the production code needs to replicate this pattern.

## Context

### Source
- Known issue Delos-mka0 (P0 — description outdated, see Finding 5)
- ADR-0009: Portal Routing Semantics
- ADR-0012: Unix Domain Socket Architecture

### Current State (verified 2026-03-14, revised after gate critique)

**What IS implemented:**
- `SubContext` proto in `demesne.proto` has `portal_address` (field 3)
- `ProcessContainerDomain.outerContextService()` (line 256) constructs the `OuterContextServer` with register/deregister callbacks — but is never called
- `ProcessContainerDomain.register()` callback (lines 271-292) correctly populates routes map and calls `handle.markRunning()`
- `DemesneImpl.registerContext()` (lines 233-263) validates portal path and calls `outer.register(SubContext)`
- Portal constructor accepts `Function<String, UnixDomainSocketAddress> router` and routes via `METADATA_CONTEXT_KEY` headers
- `SubDomainHandleImpl` tracks status: STARTING → RUNNING → STOPPING → STOPPED
- Routes stored in `ConcurrentHashMap<String, UnixDomainSocketAddress>`

**What is NOT implemented:**
1. **Bridge gRPC server** — `outerContextService()` is dead code (0 callers). No `NettyServerBuilder` in `startServices()`.
2. **Channel accessor** — Portal has only `start()`, `close(Duration)`, and constructor. No `link()` or channel factory. Routing is transparent via bridge socket + metadata headers (callers connect to bridge and set `METADATA_CONTEXT_KEY`).
3. **Subdomain service contract** — `SubDomainHandle.send(T)` uses unbound generics `<T, R>` with no gRPC service binding. Must either define a well-known service or replace `send()` with `getChannel()`.
4. **Deregistration** — `OuterContextService.deregister(Digeste)` is defined but never called during shutdown. Both cooperative (subdomain-initiated) and crash (parent-detected) paths are missing.
5. **End-to-end test** — `DemesneTest.smokin()` tests registration in isolation (hand-rolled server), not the integrated `ProcessContainerDomain` path.

### Portal Routing Architecture (from ADR-0009, verified in Portal.java)

Portal does **not** expose per-subdomain channels. It works as a transparent gRPC proxy:
```
Caller → connect to Portal bridge socket
       → set METADATA_CONTEXT_KEY = qb64(contextDigest) in gRPC headers
       → Portal's Demultiplexer reads header
       → routes to subdomain's Unix socket via router function
       → subdomain receives call on its gRPC server
```

This means:
- **No new Portal API is needed.** The existing architecture is correct.
- Callers need the bridge socket address and the context digest to route requests.
- `SubDomainHandle` should expose a channel to the bridge (or a typed stub), not a per-subdomain channel.

### Impact
- Multi-tenant `ProcessContainerDomain` is ❌ Not ready
- Subdomains spawn but remain in STARTING state indefinitely (markRunning unreachable)
- No inter-subdomain communication path
- Blocks any production multi-tenant deployment

## Requirements

### Phase 1: Bridge Server & Registration (unblocks everything)
1. **Start bridge gRPC server in `startServices()`** — `NettyServerBuilder.forAddress(bridge)` with both `outerContextService()` AND `DemesneKERLServer`, following `DemesneTest.smokin()` pattern (lines 239-248). Both services are required: subdomains connect to `outerContextAddress` for both KERL and OuterContext operations. Shut down in `stopServices()`.
2. **Cooperative deregistration** — `DemesneImpl.stop()` calls `outer.deregister(context.getId().toDigeste())` before `domain.stop()`. `ProcessContainerDomain` removes route from `routes` map and entry from `hostedDomains`.
3. **Deprecate send()** — apply `@Deprecated` to both `send()` overloads in `SubDomainHandle.java`. Update error message in `SubDomainHandleImpl` from "requires Portal.link() API" to "use getChannel() — see RDR-002 D2".

### Phase 2: Client Routing API
3. **Define subdomain service contract** — decide between:
   - **(a)** Replace `send(T)` with `ManagedChannel getChannel()` — callers build typed stubs. Simple, no new proto needed.
   - **(b)** Define a well-known generic service proto (e.g., `SubdomainService.invoke(Any request)`) — callers use a uniform API. Requires new proto.
   - **Recommendation**: Option (a) — `getChannel()` returning a channel connected to the bridge with `METADATA_CONTEXT_KEY` pre-set. This matches Portal's architecture and avoids type-erasure issues. Deprecate `send()`.
4. **Integration test milestone A** — spawn → register → markRunning → stop → deregister. Validates Phase 1 without send/receive.

### Phase 3: End-to-End (after service contract decision)
5. **Integration test milestone B** — spawn → register → getChannel → typed stub call → receive → stop → deregister.

### Non-requirements
- No new Portal API needed (bridge + metadata routing is the correct model)
- Cross-node subdomain routing is out of scope (future RDR)
- Crash-detection deregistration is deferred (see Open Questions)

## Design Decisions

### D1: No Portal.link() API (resolved)
Portal routes transparently via bridge socket + metadata headers. Adding a `link()` method that returns per-subdomain channels would bypass Portal's routing layer and create channel lifecycle ownership ambiguity. Instead, `SubDomainHandle.getChannel()` returns a channel to the **bridge** with context metadata pre-configured.

### D2: Replace send() with getChannel() (proposed)
`SubDomainHandle.send(T)` has unresolvable generics — `<T, R>` have no bounds and no gRPC service binding. Java type erasure prevents runtime dispatch. The correct API is `ManagedChannel getChannel()` allowing callers to create typed stubs. `send()` should be deprecated or removed.

### D3: Bridge server owns registration lifecycle (resolved)
The bridge gRPC server started in `startServices()` hosts `OuterContextService` (register/deregister). Subdomains connect to `parameters.getParent()` which points to this bridge. Server shutdown in `stopServices()` is sufficient — orphaned subdomains will fail to deregister and routes become stale (acceptable for container shutdown).

## Open Questions

1. What happens when `getChannel()` is called on a handle still in STARTING state? (Fail fast with `IllegalStateException`?)
2. Should route health checks detect stale routes from crashed subdomains? (Deferred — acceptable for Phase 1 to leave stale routes until container restart)

### Resolved Questions
- **Channel caching**: `getChannel()` should cache the bridge channel. The bridge address is a final field set at construction and never changes. Per-call channel creation would be wasteful.

## Related Issues
- Delos-mka0 (Portal Routing Registration, P0)
- Delos-mj8z (Subdomain Lifecycle Management, P1) — RDR-003
- Delos-c3k3 (Scheduler Leak, FIXED) — established the shutdown pattern

## Key Files

| File | Role |
|------|------|
| `model/.../ProcessContainerDomain.java` | Bridge server, routes map, outerContextService() |
| `model/.../SubDomainHandle.java` | Public handle interface (9 methods) |
| `model/.../SubDomainHandleImpl.java` | Handle impl, getChannel() target |
| `model/.../demesnes/DemesneImpl.java` | Subdomain impl, registerContext(), stop() |
| `memberships/.../archipelago/Portal.java` | gRPC multiplexer (no changes needed) |
| `memberships/.../archipelago/Enclave.java` | Subdomain endpoint, context callback |
| `grpc/src/main/proto/demesne.proto` | SubContext proto (complete) |
| `model/.../demesnes/comm/OuterContextService.java` | register/deregister interface |
| `model/src/test/.../DemesneTest.java` | Reference pattern for bridge server (lines 239-248) |

## Research Findings

### Finding 1: SubContext proto already complete (2026-03-14)
**Status**: Verified
`demesne.proto` lines 43-51 define SubContext with `enclave`, `context`, and `portal_address` fields.
The known-issues.md claim that SubContext "lacks a portal_address field" is outdated.

### Finding 2: Registration flow implemented but unreachable (2026-03-14, revised)
**Status**: Verified (revised after gate critique)
`DemesneImpl.registerContext()` calls `outer.register(SubContext)` correctly. `ProcessContainerDomain.register()` correctly populates routes and calls `markRunning()`. However, `outerContextService()` is dead code — no gRPC server listens at the bridge address. The test `DemesneTest.smokin()` works because it manually constructs a `NettyServerBuilder` (lines 239-248). The production path is broken.

### Finding 3: Completion is ~50-60%, not ~85% (2026-03-14, revised)
**Status**: Corrected after gate critique
The original "~85% complete" estimate was not defensible. The bridge server (the foundation for registration) is absent, and the client routing API design is unresolved. Data structures and callbacks exist but are not connected.

### Finding 4: Portal routes via bridge + metadata, not per-subdomain channels (2026-03-14)
**Status**: Verified
Portal.java has only `start()`, `close(Duration)`, and constructor. Routing uses `METADATA_CONTEXT_KEY` header via `Demultiplexer`. No `link()` method exists or should exist. ADR-0009 mentions `Portal.link()` as intent, not API.

### Finding 5: known-issues.md and ADR-0009 are stale (2026-03-14)
**Status**: Action needed
Delos-mka0 says "Blocked on protobuf changes" — the proto is done. ADR-0009 says "Registration: Blocked on protobuf changes" — also done. Both should be updated as part of implementation. The actual blocker is the missing bridge server.

### Finding 6: Deregistration needs two paths (2026-03-14)
**Status**: Identified in gate critique
Cooperative deregistration (subdomain calls `outer.deregister()` during stop) is straightforward.
Crash-detection deregistration (parent detects crashed subdomain, removes stale route) requires health monitoring — deferred to future work.

### Finding 7: Bridge server must include KERL service (2026-03-14, gate-2)
**Status**: Verified
`DemesneTest.smokin()` adds both `kerlServer` and `outerService` to the bridge `NettyServerBuilder` (lines 239-248). `DemesneImpl` connects to `outerContextAddress` for both KERL (`kerlFrom()` at line 111) and OuterContext operations. If the production bridge omits `DemesneKERLServer`, subdomain KERL calls will fail. Phase 1 Requirement 1 must include both services.

## Implementation Status

All requirements are design-specified, none are implemented. This is a design RDR — implementation follows acceptance.

| Requirement | Status |
|-------------|--------|
| Phase 1.1: Bridge gRPC server (+ KERL) | Design specified, not implemented |
| Phase 1.2: Cooperative deregistration | Design specified, not implemented |
| Phase 1.3: Deprecate send() | Design specified, not implemented |
| Phase 2.3: getChannel() API | Design specified, not implemented |
| Phase 2.4: Integration test A | Design specified, not implemented |
| Phase 3.5: Integration test B | Design specified, not implemented |
| Doc: Update known-issues.md | Pending |
| Doc: Update ADR-0009 | Pending |

## Deliverables (documentation updates)
- Update `docs/known-issues.md` Delos-mka0 entry to reflect actual blocking issue
- Update `docs/adr/0009-portal-routing-semantics.md` Implementation Status and remove Portal.link() reference

## Decision

Accepted 2026-03-14. Design approved for phased implementation. Phase 1 (bridge server + deregistration + send() deprecation) unblocks everything.
