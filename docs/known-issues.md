# Known Issues

This document tracks known issues across Delos components with their status, workarounds, and fix timelines.

---

## Model Module (Multi-Tenancy)

### ProcessContainerDomain (Single-Tenant Mode)

**Status**: ⚠️ Use with caution — Thread safety and resource leak issues fixed as of 2026-02-13

#### Fixed Issues (2026-02-13)

**1. JDBC Connection Thread Safety** ✅ FIXED (Delos-ae0f)
- **Issue**: Oracle operations shared single JDBC Connection across virtual threads
- **Impact**: Rare race conditions in Statement lifecycle under high concurrency
- **Workaround**: Avoid concurrent spawn operations (no longer needed)
- **Fix**: Upgraded to DataSource pooling with connection-per-operation model
- **Safe After**: Commit 704269cc (2026-02-13)
- **Details**: See ADR-0011 (JDBC Connection Pooling for Oracle)

**2. DelegatedDomain Scheduler Leak** ✅ FIXED (Delos-c3k3)
- **Issue**: ScheduledExecutorService not shut down in DelegatedDomain.stop()
- **Impact**: Virtual thread pool leaked on every subdomain lifecycle
- **Workaround**: Monitor thread count, restart periodically (no longer needed)
- **Fix**: Added scheduler.shutdown() with 5-second timeout and force-shutdown fallback
- **Safe After**: Commit 814e663a (2026-02-13)

**3. ProcessDomain Connection Pool Leak** ✅ FIXED (Delos-we2d)
- **Issue**: JdbcConnectionPool created but never disposed
- **Impact**: JDBC connections leaked on every domain lifecycle (typically 10-20 per instance)
- **Workaround**: Monitor connections via `SHOW PROCESSLIST`, restart periodically (no longer needed)
- **Fix**: Stored pool as instance field, call dispose() in stopServices()
- **Safe After**: Commit 03649a5e (2026-02-13)

**4. Test Failures from Schema Visibility** ✅ FIXED
- **Issue**: H2 in-memory databases don't share schema across separate connection pools
- **Impact**: Tests failed with "Schema DELPHINIUS not found"
- **Workaround**: N/A (test infrastructure issue)
- **Fix**: Created SqlStateMachineDataSource wrapper to reuse SqlStateMachine connections
- **Safe After**: Commit 704269cc (2026-02-13)

#### Open Issues

**5. Event Loop Shutdown Logging** ⏸️ OPEN (Delos-773l) - Priority P2
- **Issue**: Shutdown logs misleading (success reported as failure)
- **Impact**: Cosmetic, causes confusion during debugging
- **Workaround**: Ignore "did not shutdown" logs during normal shutdown
- **Fix**: Pending (P2 priority, non-blocking)
- **Estimated Fix**: TBD

**6. Portal Routing Registration** ⏸️ BLOCKED (Delos-mka0) - Priority P0
- **Issue**: Portal created but route registration disabled (line 206 commented out)
- **Impact**: Portal routing does not work (multi-tenant communication broken)
- **Workaround**: Do not use Portal in single-tenant mode
- **Fix**: Blocked on protobuf changes (add portal_address field to SubContext)
- **Dependencies**: Design approval needed
- **Estimated Fix**: 3-4 days after design approval
- **Details**: See ADR-0009 (Portal Routing Semantics)

**7. Subdomain Lifecycle Management** ⏸️ OPEN (Delos-mj8z) - Priority P1
- **Issue**: spawn() works but no control API (start, stop, status)
- **Impact**: Cannot manage spawned subdomains programmatically
- **Workaround**: Do not use spawn() until API ready
- **Fix**: Pending (DelegatedDomainHandle API design)
- **Estimated Fix**: TBD

---

## Thoth Module (KERI DHT)

### Current Status

**Status**: ✅ Production-ready — All Phase 5 BFT integration work complete as of 2026-02-14

#### Completed Features (2026-02-14)

**1. Byzantine Fault Tolerance Integration** ✅ COMPLETE (Delos-io5z, Phase 5)
- **Feature**: ThothByzantineStateProvider integrated with ByzantineIntelligenceCoordinator
- **Implementation**:
  - Five signal types tracked (validation, signature, quorum, timeout, connection failures)
  - Post-quorum validation pipeline (Ani → Maat → DHT write)
  - Automatic registration in KerlDHT lifecycle
- **Tests**: ByzantineDetectionIntegrationTest (5 E2E scenarios), ByzantineFaultToleranceTest (11 fault injection)
- **Safe After**: Commit d4b36407 (2026-02-14)
- **Details**: See ADR-0013 (Thoth Byzantine Fault Tolerance)

**2. Health Check Integration** ✅ COMPLETE (Delos-d178)
- **Feature**: isHealthy() and getHealthSnapshot() methods following WitnessAdapter pattern
- **Implementation**: Health criteria - validation success rate ≥95%, connection pool <90%, circuit breaker closed
- **Tests**: KerlDHTHealthCheckTest (6 tests covering healthy/degraded states)
- **Safe After**: Commit 97bb9c82 (2026-02-14)

**3. Connection Pool Monitoring** ✅ COMPLETE (Delos-gf3q)
- **Feature**: Periodic monitoring of H2 connection pool with exhaustion detection
- **Implementation**:
  - Scheduled task samples pool every operationsFrequency interval
  - Records active/idle metrics via MicrometerKerlDhtMetrics
  - isPoolExhausted() detects ≥90% utilization
- **Tests**: KerlDHTConnectionPoolTest (5 tests - stress, exhaustion, recovery)
- **Safe After**: Commit 84db4ddf (2026-02-14)

**4. Scheduler Lifecycle Management** ✅ COMPLETE (Delos-toww)
- **Feature**: Configurable shutdown timeout with comprehensive logging
- **Implementation**:
  - shutdownTimeout parameter (default 10s, backward compatible)
  - Logs dropped scheduler and validation executor tasks during forced shutdown
  - Graceful drain of in-flight validations with configurable timeout
- **Tests**: KerlDHTLifecycleTest (7 tests - graceful shutdown, custom timeout, idempotent stop)
- **Safe After**: Commit d8b347b1 (2026-02-14)

**5. Performance Regression Fix** ✅ FIXED (Delos-tegh)
- **Issue**: PerformanceSLATest.testConcurrentOperationsPerformance failing with 33 ops/sec (expected 500)
- **Root Cause**: Missing routers.values().forEach(r -> r.start()) in test setup
- **Impact**: Test-only issue, did not affect production code
- **Fix**: Added router start before DHT initialization in test
- **Result**: Throughput 1078 ops/sec (above 500 threshold), all 7/7 PerformanceSLATest tests passing
- **Safe After**: Commit dc1cf6ff (2026-02-14)

#### Test Coverage

**Total Tests**: 276 (as of 2026-02-14)
- Passing: 275 (99.6%)
- Failures: 1 (BootstrappingTest.smokin - pre-existing, unrelated to BFT work)

**New Tests Added**:
- KerlDHTHealthCheckTest: 6 tests (health check integration)
- KerlDHTConnectionPoolTest: 5 tests (pool monitoring)
- KerlDHTLifecycleTest: 7 tests (shutdown lifecycle)
- ByzantineDetectionIntegrationTest: 5 E2E scenarios (Phase 5)
- PerformanceSLATest: 7 tests (performance validation)

#### Known Limitations

**No current blocking issues.** All Phase 5 work complete. Optional future enhancements:

1. **Multi-signature Aggregation** (deferred, not blocking)
   - Current: Individual BLS signatures verified per response
   - Future: BLS signature aggregation for improved performance
   - Impact: Performance optimization only, correctness unaffected
   - Priority: P4 (nice-to-have)

2. **Advanced Equivocation Response** (future feature)
   - Current: Detection and recording via ThothByzantineStateProvider
   - Future: Configurable response strategies (shun, quarantine, alert-only)
   - Impact: Observability only, detection works correctly
   - Priority: P3 (enhancement)

---

## Production Readiness Matrix

### Model Module

| Component | Status | Safe for Production? | Caveats |
|-----------|--------|---------------------|---------|
| **Domain** | ✅ Production-ready | Yes | None |
| **ProcessDomain** | ✅ Production-ready | Yes | None |
| **ProcessContainerDomain (single-tenant)** | ⚠️ Caution | Yes, with monitoring | All critical fixes applied (ae0f, c3k3, we2d). Event loop logging cosmetic (773l). Portal routing disabled (mka0). |
| **ProcessContainerDomain (multi-tenant)** | ❌ Not ready | No | Portal routing broken (mka0), lifecycle API missing (mj8z), delegation gossip untested. Requires P0 fixes. |
| **DelegatedDomain** | ⚠️ Caution | Yes, for single-tenant only | Scheduler leak fixed (c3k3). Delegation gossip implemented (4hby) but untested in production. |

### Thoth Module (KERI DHT)

| Component | Status | Safe for Production? | Caveats |
|-----------|--------|---------------------|---------|
| **KerlDHT** | ✅ Production-ready | Yes | All Phase 5 BFT integration complete (io5z). Health monitoring active (d178, gf3q). Configurable shutdown lifecycle (toww). 276 tests, 99.6% passing. |
| **ThothByzantineStateProvider** | ✅ Production-ready | Yes | Integrated with ByzantineIntelligenceCoordinator. Five signal types tracked. 15min failure expiry, thread-safe. |
| **DhtValidationPipeline** | ✅ Production-ready | Yes | Post-quorum validation (Ani → Maat → write). Byzantine signal recording on failures. Ordered execution with timeout handling. |

**Legend:**
- ✅ **Production-ready**: No known blocking issues
- ⚠️ **Caution**: Safe with monitoring, some features limited
- ❌ **Not ready**: Blocking issues prevent production use

---

## Monitoring Recommendations

When using ProcessContainerDomain in single-tenant mode, monitor:

### JDBC Connections
```sql
-- PostgreSQL
SELECT count(*) FROM pg_stat_activity WHERE datname = 'delos';

-- H2 (in-memory)
SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS;
```
**Alert threshold**: > 80% of max_connections

### Virtual Threads
```bash
# Using JMX or jcmd
jcmd <pid> Thread.print | grep "VirtualThread" | wc -l
```
**Alert threshold**: Unbounded growth over time

### File Descriptors
```bash
lsof -p <pid> | wc -l
```
**Alert threshold**: > 50% of ulimit (typically 32768 of 65536)

### Memory Usage
```bash
# Heap usage
jcmd <pid> GC.heap_info
```
**Alert threshold**: > 80% heap utilization sustained

---

## Upgrade Considerations

### From Pre-2026-02-13 Versions

**Action Required:**
1. ✅ No schema changes — Upgrade is binary-compatible
2. ✅ No configuration changes required
3. ✅ Existing domains will benefit from fixes immediately

**Recommended:**
1. Monitor JDBC connections during first week after upgrade
2. Watch for thread count stability
3. Verify no resource leaks in long-running deployments

**Breaking Changes:**
- None (all fixes are backwards-compatible)

### Future Upgrades (Post-mka0)

When Portal routing is implemented (Delos-mka0):
- **Protobuf changes**: SubContext message will add portal_address field
- **API changes**: ProcessContainerDomain.register() will be uncommented
- **Migration**: Existing single-tenant deployments unaffected
- **Multi-tenant**: Will become production-ready

---

## Related Documentation

- [ADR-0008](adr/0008-subdomain-isolation-strategy.md): Subdomain Isolation Strategy
- [ADR-0009](adr/0009-portal-routing-semantics.md): Portal Routing Semantics
- [ADR-0010](adr/0010-delegation-gossip-protocol.md): Delegation Gossip Protocol
- [ADR-0011](adr/0011-jdbc-connection-pooling-for-oracle.md): JDBC Connection Pooling for Oracle
- [ADR-0012](adr/0012-unix-domain-socket-architecture.md): Unix Domain Socket Architecture
- [MONITORING_AND_ALERTING.md](MONITORING_AND_ALERTING.md): Full monitoring guide
- [MIGRATIONS.md](MIGRATIONS.md): Database migration procedures

---

## Build Warnings

### Protobuf sun.misc.Unsafe Deprecation

**Status**: ⚠️ Upstream issue (Google protobuf-java)

**Warning Message**:
```
WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
WARNING: sun.misc.Unsafe::arrayBaseOffset has been called by com.google.protobuf.UnsafeUtil$MemoryAccessor
WARNING: sun.misc.Unsafe::arrayBaseOffset will be removed in a future release
```

**Impact**: Cosmetic only - no functional impact
- Warning appears during test execution
- Google's protobuf-java 4.28.2 uses deprecated sun.misc.Unsafe API
- Will need protobuf-java upgrade when JDK removes sun.misc.Unsafe

**Workaround**: None needed - warning can be safely ignored

**Fix**:
- Waiting for Google to update protobuf-java to use VarHandle or MethodHandles
- Monitor https://github.com/protocolbuffers/protobuf/issues for resolution
- Attempted upgrade to 4.33.5 (2026-02-15) but caused test timeouts - staying with 4.28.2 until compatibility verified

**Related**:
- JEP 471: Deprecate sun.misc.Unsafe for Removal
- Dependency: com.google.protobuf:protobuf-java:4.28.2

---

## Reporting Issues

Found a new issue? Please report via:
- **GitHub Issues**: https://github.com/Hellblazer/Delos/issues
- **Include**: Component, version, reproduction steps, impact assessment
- **Labels**: `bug`, `model-module`, priority (P0-P4)

---

**Last Updated**: 2026-02-14
**Maintainer**: Delos Core Team
