# Netty Native Library Elimination - Project Summary

**Project Name**: Netty Native Library Elimination
**Epic Bead**: Delos-1b9y
**Status**: Infrastructure Setup Complete - Ready for Phase 1
**Start Date**: 2026-01-15
**Estimated Completion**: 2026-02-05
**Duration**: 3 weeks
**Branch**: feature/netty-elimination

---

## Executive Summary

Eliminate Netty native transport library dependencies (libnetty_transport_native_kqueue/epoll) from Delos by replacing with pure-Java NIO (JEP 380). This solves critical GraalVM isolates crash caused by native library double-loading, simplifies codebase, and enables working multi-tenant enclaves.

**The Problem**: Native libraries crash when loaded in both main JVM and GraalVM isolates.
**The Solution**: Replace with pure-Java NIO domain sockets (Java 16+, Netty 4.1.110+).
**The Impact**: Delete 3 modules, update 5 modules, fix isolates, simpler build.

---

## Problem Statement

### Root Cause
Netty native transport libraries (`libnetty_transport_native_kqueue` on macOS, `libnetty_transport_native_epoll` on Linux) are loaded once at process startup via JNI. When GraalVM isolates try to load the same library inside the isolate context, the OS prevents double-loading, causing fatal crash.

### Manifestation
- Test: `DemesneIsolateTest.smokin()`
- Error: "Fatal error: Unhandled exception" (Exit Code 99)
- Stack: Crash during `new DemesneImpl()` initialization inside isolate

### Business Impact
- Isolates module non-functional
- Multi-tenant enclaves blocked
- Architecture regression from working state

---

## Solution Overview

### Approach: Greenfield Replacement
Not debugging or patching. **Replacing** native libraries entirely with standard solution.

**Key Insight**: JEP 380 (Java 16+) and Netty 4.1.110+ support pure-Java Unix domain sockets via NIO on ALL platforms. No native libraries needed.

### Benefits
- **Isolates Work**: No native library double-loading
- **Simpler Build**: Delete 3 modules, remove native dependencies
- **Cross-Platform**: Works on Windows/Linux/macOS
- **Standard**: Well-tested, Java API
- **Equivalent Performance**: < 10% degradation expected

---

## Scope & Impact

### In Scope
1. **Phase 1**: Update 5 modules to use NioEventLoopGroup/NioDomainSocketChannel
   - protocols
   - model
   - memberships
   - isolates
   - isolate-ftesting

2. **Phase 2**: Delete 3 modules (complete directory removal)
   - domain-kqueue
   - domain-epoll
   - domain-sockets

3. **Phase 3**: Clean GraalVM native image metadata
   - Remove native library references
   - Update build configuration

4. **Phase 4**: Comprehensive testing & validation
   - Unit/integration tests
   - Isolate functional tests (terminal hang risk)
   - Performance benchmarking
   - Documentation

### Out of Scope
- Other Netty features (just transport selection)
- Netty version upgrade (using current version)
- Non-Delos system changes

---

## Technical Details

### Modules to Update (Phase 1)

| Module | Current Code | Replacement | Complexity |
|--------|------------|-------------|-----------|
| protocols | `DomainSocketServerInterceptor` | Use `NioEventLoopGroup` | LOW |
| model | Transport selection in `DemesneImpl` | Select `NIO` transport | LOW |
| memberships | `Enclave`/`Portal` socket creation | `NioDomainSocketChannel` | LOW |
| isolates | Isolate-specific transport init | Adapt for `NIO` | MEDIUM |
| isolate-ftesting | Test infrastructure | Update test setup | LOW |

### Modules to Delete (Phase 2)

| Module | Status | Notes |
|--------|--------|-------|
| domain-kqueue | Delete entire | KQueue transport abstraction |
| domain-epoll | Delete entire | Epoll transport abstraction |
| domain-sockets | Delete entire | Base socket abstraction (replaced by Netty NIO) |

### Key Files to Modify (Phase 1)

1. **protocols**: `DomainSocketServerInterceptor.java` (socket channel creation)
2. **model**: `DemesneImpl.java` (transport selection logic)
3. **memberships**: `Enclave.java`, `Portal.java` (socket initialization)
4. **isolates**: Multiple files in platform-specific code
5. **isolate-ftesting**: Test setup code

### GraalVM Changes (Phase 3)

- Remove: `isolates/src/main/resources/.../resource-config.json` (if present)
- Update: Native image build configuration (remove native lib profiles or adapt)
- Verify: `reachability-metadata.json` doesn't reference deleted modules

---

## Phase Breakdown

### Phase 1: Code Migration (1 week)
**Goal**: Replace all domain socket channel creation with NIO

**Tasks**:
1. protocols: Update DomainSocketServerInterceptor
2. model: Update DemesneImpl transport selection
3. memberships: Update Enclave/Portal socket creation
4. isolates: Update isolate transport handling
5. isolate-ftesting: Update test infrastructure

**Success Criteria**:
- All 5 modules updated
- Code compiles without errors
- All protocol tests pass
- No terminal hangs

**Test Command**: `./mvnw test -pl [module]`

### Phase 2: Module Deletion (0.5 days)
**Goal**: Remove 3 domain socket modules

**Tasks**:
1. Delete domain-kqueue/ directory
2. Delete domain-epoll/ directory
3. Delete domain-sockets/ directory
4. Clean parent pom.xml
5. Verify no dangling references

**Success Criteria**:
- 3 modules deleted
- Parent pom.xml cleaned
- No compilation errors
- `grep -r "domain-kqueue\|domain-epoll\|domain-sockets"` returns nothing

**Test Command**: `./mvnw clean install`

### Phase 3: Metadata Cleanup (0.5 days)
**Goal**: Remove native library metadata from GraalVM

**Tasks**:
1. Delete resource-config.json (if present)
2. Remove native lib references from isolates/pom.xml
3. Update GraalVM build configuration
4. Verify isolates compiles

**Success Criteria**:
- Native metadata removed
- `isolates` compiles with `-Pisolates` profile
- No references to native library loading

**Test Command**: `./mvnw compile -pl isolates -Pisolates`

### Phase 4: Testing & Validation (1 week)
**Goal**: Comprehensive testing with performance validation

**Tasks**:
1. Run protocol/unit tests
2. Run integration tests
3. Run isolate functional tests (extreme caution - terminal hang risk)
4. Performance benchmarking
5. Documentation updates

**Success Criteria**:
- All test suites pass
- Performance degradation < 20% (expect < 10%)
- No terminal hangs
- Isolates functional tests pass
- Documentation updated

**Test Commands**:
```bash
./mvnw test -pl protocols          # Safe
./mvnw test -pl model              # Safe
./mvnw test -pl memberships        # Safe
timeout 30s ./mvnw test -pl isolate-ftesting -Pisolates  # Risk!
```

---

## Risk Management

### Critical Risks

**Risk 1: Terminal Hang (HIGH)**
- **What**: Previous isolate testing caused terminal to hang
- **Mitigation**: Phase 1-3 avoid isolates, Phase 4 uses timeout + kill recovery
- **Plan**: `timeout 30s <test>`, monitor separately, `killall -9 java` if needed

**Risk 2: Performance Degradation (MEDIUM)**
- **Target**: < 20% acceptable
- **Expected**: < 10%
- **Mitigation**: Baseline before Phase 1, measure post-Phase 4
- **Action**: If > 20%, investigate before declaring victory

**Risk 3: NIO Incompatibility (MEDIUM)**
- **What**: NIO may have subtle differences from native transports
- **Mitigation**: Incremental testing per module
- **Plan**: If compatibility issue found, debug and document

### Managed Risks

- **Peer Credentials Loss**: Fallback to TLS (already using gRPC MTLS)
- **Platform Differences**: Document, test on each platform
- **GraalVM Build Changes**: Test native image build in Phase 3
- **Module Coverage**: Pre-Phase 1 code review to ensure all covered

See RISK_REGISTER.md for detailed analysis.

---

## Success Metrics

### Code Quality
- 5/5 modules updated to use NIO
- 0 remaining references to native transports
- 0 compilation errors
- > 95% test coverage

### Functional Testing
- protocols tests: PASS
- model tests: PASS
- memberships tests: PASS
- isolate-ftesting tests: PASS
- Full build: PASS

### Performance
- Throughput degradation: < 20% (expect < 10%)
- Latency p99 degradation: < 20% (expect < 10%)
- Memory usage: No significant increase
- GC pauses: No significant increase

### Stability
- No terminal hangs during testing
- No crashes or segmentation faults
- Isolates functional and stable
- No resource leaks

---

## Project Dependencies

### Input Dependencies
- Netty 4.1.110+ (already in pom.xml)
- Java 16+ for JEP 380 support (using Java 25)
- GraalVM 25.0.1 (if isolates enabled)

### Output Dependencies
- Strategic planner (may produce detailed plan - will reconcile)
- Code review agent (review all Phase 1 changes)
- Test validator (validate Phase 4 coverage)

---

## Resources Required

### Personnel
- 1 engineer (3 weeks)
- Code reviewer (after each phase)
- Test validator (Phase 4)

### Equipment
- MacOS/Linux development machine
- Ability to kill hung processes
- Access to Delos repository

### Time Estimates per Phase
- Phase 1: 1 week (5 modules × 4-6 hours)
- Phase 2: 0.5 days
- Phase 3: 0.5 days
- Phase 4: 1 week (testing + benchmarking)
- **Total**: 3 weeks

---

## Key Assumptions

1. **Netty NIO Support**: JEP 380 and Netty 4.1.110+ work as expected
2. **Performance Acceptable**: < 20% degradation achievable
3. **No Peer Credentials**: Fallback to TLS acceptable for access control
4. **GraalVM Compatibility**: Isolates work with NIO transport
5. **Team Expertise**: Engineer has experience with Netty, NIO, GraalVM

---

## Success Criteria Summary

| Phase | Gate | Criteria |
|-------|------|----------|
| 1 | Code Migration | All 5 modules updated, tests pass, no hangs |
| 2 | Module Deletion | 3 modules deleted, pom.xml cleaned |
| 3 | Metadata Cleanup | Native refs removed, GraalVM build works |
| 4 | Testing | All tests pass, perf < 20% degradation, isolates work |

---

## Communication Plan

### Status Updates
- Weekly checkpoint updates
- Session completion documentation
- Risk/blocker escalation as needed

### Deliverables
- Updated code in feature/netty-elimination branch
- Test results documentation
- Performance benchmarking report
- Updated documentation

### Review Gates
- Phase 1: Code review of all changes
- Phase 2: Verification of clean deletion
- Phase 3: GraalVM build verification
- Phase 4: Full test results and performance data

---

## Post-Project Maintenance

### Documentation
- Update CLAUDE.md with Netty elimination outcome
- Document performance characteristics
- Update architecture documentation

### Cleanup
- Delete PM infrastructure (`.pm-netty/`) or archive
- Merge feature branch to main
- Create release notes

### Future Work
- Monitor performance in production
- Update Netty if version upgrade available
- Consider Windows support if needed

---

## Appendix: Key References

### Root Cause Analysis
- ChromaDB: `debug::isolates::netty-native-library-conflict`

### Solution Research
- ChromaDB: `research::isolates::nio-domain-sockets-solution`
- JEP 380: Unix Domain Sockets (Java 16+)
- Netty 4.1.110+ Release Notes

### Prior Work
- Isolates restoration: Phase 1-3 complete, Phase 4 pending
- GraalVM 25.0.1 upgrade: Completed for isolates
- H2 Hardening: Separate P0-P2 project

---

**Document Control**:
- Created: 2026-01-15
- Version: 1.0
- Status: ACTIVE
- Owner: Development Team
