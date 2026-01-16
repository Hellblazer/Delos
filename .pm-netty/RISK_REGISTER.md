# Netty Elimination - Risk Register

**Project**: Netty Native Library Elimination
**Updated**: 2026-01-15
**Severity Levels**: CRITICAL | HIGH | MEDIUM | LOW

---

## Active Risks (Must Monitor)

### RISK-NIO-001: Terminal Hang from Isolate Testing

**Severity**: HIGH
**Category**: Execution Risk
**Probability**: MEDIUM (happened before in isolate testing)
**Impact**: High (terminal becomes unresponsive, requires kill -9)

**Description**:
Previous isolate testing (GraalVM 25.0.1 native image) caused terminal to hang during DemesneIsolateTest execution. Terminal became completely unresponsive, required `killall -9 java` from another terminal to recover.

**When It Triggers**:
- During isolate functional testing (Phase 4)
- When DemesneIsolateTest runs complex scenarios
- During multi-isolate creation/destruction

**Mitigation Strategy**:
1. **Phase 1-3**: NO isolate testing - only unit/integration tests (safe)
2. **Phase 4 Preparation**:
   - Build recovery script: `killall-java.sh`
   - Set up second terminal for monitoring
   - Use timeout wrapper: `timeout 30s <test-command>`

3. **During Phase 4 Isolate Tests**:
   - Run with explicit timeout (30 seconds max)
   - Log to file (not console buffering)
   - Monitor output from separate terminal
   - Have kill command ready

4. **If Hang Occurs**:
   - From second terminal: `killall -9 java`
   - Wait 5 seconds for OS cleanup
   - Analyze which test caused hang
   - Document in checkpoint
   - Continue with next test

**Acceptance**: ACCEPTED - Mitigated through Phase sequencing and safe testing practices

**Owner**: Development Team
**Status**: ACTIVE (must enforce safe testing practices)

---

### RISK-NIO-002: Performance Degradation Exceeds Target

**Severity**: MEDIUM
**Category**: Quality Risk
**Probability**: LOW (NIO is well-optimized, expect < 10% degradation)
**Impact**: Medium (may be unacceptable for deployment)

**Description**:
Pure-Java NIO transport may be slower than native Epoll/KQueue. If degradation exceeds 20% (acceptable threshold), may need to reconsider approach or optimize NIO usage.

**Expected Degradation**: < 10%
**Acceptable Threshold**: < 20%
**Unacceptable**: > 20%

**Metrics to Track**:
- Throughput (messages/second)
- Latency p50/p99 (milliseconds)
- Memory usage (MB per isolate)
- GC pause time (milliseconds)

**Mitigation Strategy**:
1. **Baseline Recording** (Before Phase 1):
   - Run ChurnTest under profiler (JProfiler or JFR)
   - Record: throughput, latency, memory, GC pauses
   - Save to `.pm-netty/metrics/baseline.txt`

2. **Post-Phase 4 Measurement**:
   - Run identical test with NIO transport
   - Record same metrics
   - Calculate degradation percentage
   - Document in `.pm-netty/metrics/performance.md`

3. **If Degradation Acceptable**:
   - Accept and document
   - Update deployment guidelines

4. **If Degradation Unacceptable** (> 20%):
   - Investigate NIO bottleneck
   - Profile for hotspots
   - Consider Netty-specific optimizations
   - OR: Reconsider approach (unlikely)

**Acceptance**: CONDITIONAL - Acceptable if < 20%, must measure in Phase 4

**Owner**: Performance Team
**Status**: PENDING (measurement in Phase 4)

---

### RISK-NIO-003: Netty NIO Domain Socket Compatibility Issues

**Severity**: MEDIUM
**Category**: Technical Risk
**Probability**: LOW (Netty NIO is well-tested, JEP 380 is standard)
**Impact**: Medium (may require workarounds or fallbacks)

**Description**:
Netty's NioDomainSocketChannel may have subtle differences from native KQueue/Epoll implementations:
- Different buffer management
- Different error handling
- Platform-specific edge cases
- Timeout behavior differences

**Known Variations**:
- Epoll: Supports SO_REUSEPORT, NIO doesn't (need explicit handling)
- KQueue: File descriptor behavior, NIO abstracts differently
- Windows: Unix domain sockets via AF_UNIX (Win10+), compatibility unclear

**When It Manifests**:
- Protocol tests fail with "Connection refused"
- Timeouts behave differently (p99 latency spikes)
- Peer credential extraction fails
- Memory leaks under high connection churn

**Mitigation Strategy**:
1. **Phase 1: Incremental Testing**
   - Test each module (protocols, model, memberships)
   - Document any test failures
   - If failure: debug, document, iterate

2. **Phase 4: Comprehensive Testing**
   - Unit tests for protocol layer
   - Integration tests for full stack
   - Stress tests (high connection churn)
   - Monitor for memory leaks

3. **If Issues Discovered**:
   - Investigate root cause (Netty or our code?)
   - Consult Netty documentation/issues
   - Consider Netty version upgrade
   - Document workaround if needed

**Acceptance**: ACCEPTED - Will be discovered and mitigated during testing

**Owner**: Development Team
**Status**: PENDING (will surface in Phase 1 testing)

---

### RISK-NIO-004: Peer Credential Extraction for Access Control

**Severity**: LOW
**Category**: Capability Risk
**Probability**: LOW (may not be required, fallback available)
**Impact**: Low (graceful degradation possible)

**Description**:
Delos uses Unix domain socket peer credentials (SO_PEERCRED) to verify access control. Pure-Java NIO may not support direct access to peer UID/GID. If lost, access control would need different approach.

**Current Usage**:
- Stereotomy: Verify identity of socket peer
- Fireflies: Authenticate gossip participants
- CHOAM: Verify consensus message sources

**Netty NIO Support**:
- Unknown if NioDomainSocketChannel exposes SO_PEERCRED
- May require JDK 17+ features (UnixDomainSocketAddress)
- May require custom SocketOption registration

**Fallback Strategies**:
1. Use TLS/MTLS for peer authentication (already used for gRPC)
2. Use application-level authentication (KERI identity verification)
3. Use process-based isolation (different JVM per tenant)

**Mitigation Strategy**:
1. **Investigation Phase**:
   - Check Netty source for SO_PEERCRED support
   - Check JDK documentation for Unix domain socket credentials
   - Test: Create simple NIO socket, read peer credentials

2. **If Supported**:
   - Use it, document how
   - Add to Phase 1 tests

3. **If Not Supported**:
   - Fallback to TLS (redundant with gRPC, but acceptable)
   - Document limitation
   - Accept and move forward

4. **If Critical Blocker**:
   - Escalate as BLOCKER-NIO-04
   - Consult with security team
   - Determine if alternative acceptable

**Acceptance**: ACCEPTED - Fallback available if needed

**Owner**: Development Team
**Status**: PENDING (investigation in Phase 1)

---

## Potential Risks (Monitor)

### RISK-NIO-005: GraalVM Native Image Compatibility with NIO

**Severity**: MEDIUM
**Category**: Technical Risk
**Probability**: LOW (Netty NIO is standard Java)
**Impact**: High (isolates won't work if native image breaks)

**Description**:
GraalVM native image build for isolates may have issues with Netty NIO transport:
- Reflection metadata for NioDomainSocketChannel
- JNI calls for Unix domain socket operations
- Resource metadata for socket files

**When It Manifests**:
- Native image build fails (missing reflection metadata)
- Native library loads but isolates crash at runtime
- Domain socket operations throw UnsatisfiedLinkError

**Mitigation Strategy**:
1. **Phase 1**: Verify isolates compile with NIO
   - `./mvnw compile -pl isolates -Pisolates`
   - Check for new reflection errors

2. **Phase 3**: Update GraalVM metadata
   - Generate metadata if needed
   - Update reachability-metadata.json
   - Run metadata generation tool if required

3. **Phase 4**: Test isolates with NIO
   - Isolate functional tests must pass
   - If failures: debug reflection issues
   - Add metadata registrations as needed

**Acceptance**: ACCEPTED - Will be addressed during Phase 1/3/4

**Owner**: Development Team
**Status**: PENDING

---

### RISK-NIO-006: Platform-Specific NIO Behavior Differences

**Severity**: LOW
**Category**: Compatibility Risk
**Probability**: MEDIUM (different platforms, different behaviors)
**Impact**: Low (likely minor, documented)

**Description**:
Unix domain socket behavior varies across platforms:
- macOS: kqueue-based, different timeout semantics
- Linux: epoll-based, different buffer handling
- Windows: AF_UNIX (Win10+), relatively new support

NIO abstracts these differences, but edge cases may exist.

**When It Manifests**:
- Tests pass on macOS, fail on Linux
- Timeout values behave differently
- Connection pooling behaves differently
- Resource cleanup behavior varies

**Current Focus**: macOS first (Intel/ARM64), then Linux
**Windows Support**: Not priority, can be addressed later

**Mitigation Strategy**:
1. **Phase 1-4**: Test on macOS first
2. **Phase 4**: Validate on Linux if available
3. **Document**: Any platform-specific behaviors
4. **Accept**: Document and continue

**Acceptance**: ACCEPTED - Platform differences documented

**Owner**: Development Team
**Status**: PENDING

---

### RISK-NIO-007: Incomplete Module Coverage in Phase 1

**Severity**: MEDIUM
**Category**: Scope Risk
**Probability**: MEDIUM (5 modules to update, may miss one)
**Impact**: Medium (missed module would break build)

**Description**:
Phase 1 must update ALL 5 modules that use domain sockets:
1. protocols
2. model
3. isolates
4. isolate-ftesting
5. memberships

If one module is missed, it will:
- Still reference deleted domain-kqueue/epoll modules
- Cause build failure in Phase 2
- Require backtracking to Phase 1

**Mitigation Strategy**:
1. **Before Phase 1 Starts**:
   - Use grep to find all socket channel creations
   - `grep -r "KQueueDomainSocketChannel\|EpollDomainSocketChannel" --include="*.java"`
   - List all files that need updates
   - Create beads for each module

2. **During Phase 1**:
   - Strict checklist per module
   - Code review confirms all instances replaced
   - Tests must pass for each module

3. **End of Phase 1**:
   - Final grep scan: zero remaining references
   - Confirm no "domain-kqueue" or "domain-epoll" in code

**Acceptance**: ACCEPTED - Mitigated through systematic review

**Owner**: Development Team
**Status**: ACTIVE (verify coverage before Phase 1)

---

## Closed/Resolved Risks

### RISK-NIO-RESOLVED-001: Netty Version Compatibility with Java 25

**Status**: ✅ RESOLVED
**Resolution**: Netty 4.1.110+ fully supports Java 25, no version change needed

Current Netty version in Delos (check pom.xml) is compatible with JEP 380.

---

## Risk Assessment Summary

| Risk ID | Severity | Category | Status | Mitigation |
|---------|----------|----------|--------|-----------|
| RISK-NIO-001 | HIGH | Execution | ACTIVE | Safe testing, Phase sequencing, kill recovery |
| RISK-NIO-002 | MEDIUM | Quality | PENDING | Baseline + post-Phase4 measurement |
| RISK-NIO-003 | MEDIUM | Technical | PENDING | Incremental testing per module |
| RISK-NIO-004 | LOW | Capability | PENDING | Investigation + fallback to TLS |
| RISK-NIO-005 | MEDIUM | Technical | PENDING | Phase 1/3/4 GraalVM validation |
| RISK-NIO-006 | LOW | Compatibility | PENDING | Platform-specific testing |
| RISK-NIO-007 | MEDIUM | Scope | ACTIVE | Pre-Phase1 code review, checklist |

**Overall Risk Level**: MEDIUM
**Confidence**: HIGH (solution well-defined, approach sound)

---

## Blockage vs Risk

### Blockers (Block Progress)
- See `.pm-netty/execution_state.json` blockers section

### Risks (May Happen, Managed)
- This document tracks managed risks
- Blockers escalate to risks if mitigations fail

---

## Risk Review Schedule

| Gate | Review | Action |
|------|--------|--------|
| Pre-Phase-1 | Coverage review | Verify all 5 modules identified |
| Phase-1-Complete | Test results | Validate all tests pass |
| Phase-3-Complete | Metadata review | Verify native libs removed |
| Phase-4-Mid | Terminal stability | Check for hangs, document |
| Phase-4-End | Performance review | Validate degradation < 20% |
| Final | Risk acceptance | Close accepted risks |

---

## Emergency Escalation Paths

### If Terminal Hangs During Phase 4
1. Kill Java: `killall -9 java`
2. Document which test caused hang
3. Update checkpoint
4. Continue with different test or Phase

### If Performance Degradation > 20%
1. Pause testing
2. Profile with JProfiler/JFR
3. Identify bottleneck
4. Update RISK_REGISTER.md
5. Decision: Accept, mitigate, or escalate

### If Peer Credentials Cannot Be Extracted
1. Verify it's actually needed (check usage)
2. Investigate Netty NIO support
3. Implement fallback (TLS)
4. Document capability gap

### If GraalVM Native Image Breaks
1. Check GraalVM build output for errors
2. Update reflection metadata
3. Add JNI registrations if needed
4. Re-test native build
5. Escalate if unresolvable

---

## Document Control

**Created**: 2026-01-15
**Version**: 1.0
**Owner**: Development Team
**Status**: ACTIVE
**Next Review**: After Phase 1 completion
