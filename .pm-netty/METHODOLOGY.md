# Netty Elimination - Engineering Methodology

## Overview

This project eliminates Netty native library dependencies from Delos by replacing platform-specific transports (KQueue/Epoll) with pure-Java NIO (JEP 380). The approach is **greenfield** (not incremental patching) with emphasis on safe, validated transitions and avoiding terminal hangs from previous isolate testing disasters.

## Core Principles

### 1. Greenfield Thinking

**NOT**: "Fix the native library loading to work with isolates"
**YES**: "Replace native libraries entirely with pure-Java solution"

This is a **replacement project**, not a debugging exercise. The solution is well-defined (JEP 380 NIO), the path is clear (replace Netty transports), the risk is manageable (NIO is standard, well-tested).

### 2. Phase-Gated Progression

Each phase must **fully complete** before moving to the next:

1. **Phase 1**: Code migration with validation
   - All 5 modules updated and tested
   - All protocol tests pass
   - No code committed until ENTIRE phase complete

2. **Phase 2**: Atomic module deletion
   - Delete all 3 modules
   - Update pom.xml
   - Final compilation verification

3. **Phase 3**: Metadata cleanup
   - Remove native library references
   - GraalVM configuration updated

4. **Phase 4**: Comprehensive validation
   - Unit tests, integration tests, isolate tests
   - Performance benchmarking
   - Terminal stability verified

**Rule**: Never mix phases. Phase 1 must complete before Phase 2 starts.

### 3. Safe Testing Strategy (Terminal Hang Prevention)

**Previous Disaster**: Isolate testing crashed terminal (unrecoverable)

**This Project's Approach**:

#### Phase 1-3: Protocol/Unit Tests (SAFE)
```bash
# Safe - no isolates involved
./mvnw test -pl protocols -DskipTests=false
./mvnw test -pl model -DskipTests=false
./mvnw test -pl memberships -DskipTests=false
```

#### Phase 4: Isolate Tests (HIGH RISK - EXTREME CAUTION)
```bash
# Step 1: Run with timeout
timeout 30s ./mvnw test -pl isolate-ftesting -Pisolates -Dtest=DemesneIsolateTest 2>&1 | tee /tmp/isolate-test.log

# Step 2: Monitor during execution
# In another terminal: tail -f /tmp/isolate-test.log

# Step 3: If test hangs (no output for 15 seconds):
# In another terminal: killall -9 java
# Analyze log, update checkpoint with findings
```

#### Recovery If Terminal Hangs
1. Open second terminal (current one is hung)
2. `killall -9 java` (force kill all Java processes)
3. Wait 5 seconds for OS cleanup
4. `ps aux | grep java` (verify all killed)
5. Document which test caused hang in `.pm-netty/checkpoints/`

### 4. Incremental Validation

Each module update follows this pattern:

```
1. Locate socket channel creation
   ↓
2. Replace KQueue/Epoll with Nio
   ↓
3. Compile: ./mvnw compile -amd -pl <module>
   ↓
4. Unit tests: ./mvnw test -pl <module>
   ↓
5. Integration tests: ./mvnw test -pl <module> -Pintegration (if exists)
   ↓
6. Create checkpoint documenting success
   ↓
7. Commit working state (not pushed yet)
```

### 5. Performance-First Benchmarking

**Phase 4 includes performance validation**:

- **Baseline**: Record metrics BEFORE Phase 1 starts
- **Target**: < 20% degradation acceptable (< 10% expected)
- **Measurement**: Use ChurnTest or similar under JProfiler/JFR
- **Documentation**: Record before/after in `.pm-netty/metrics/performance.md`

If degradation > 20%, pause and investigate before completion.

## Engineering Discipline

### Bead Workflow

Every task is a bead:

```bash
# Start session: check ready work
bd list --status=ready

# Pick a bead
bd show <id>

# Start work
bd update <id> --status in_progress

# After meaningful progress: create checkpoint
# (Copy .pm-netty/checkpoints/TEMPLATE-checkpoint.md)

# Complete bead (only when tests pass)
bd close <id>

# Update metrics
# (Edit .pm-netty/metrics/progress.md)
```

### Code Review Checklist

Before committing any changes:

- [ ] **NIO Usage**: All domain socket channels use NioDomainSocketChannel
- [ ] **Transport Type**: All EventLoopGroup creation uses NioEventLoopGroup
- [ ] **No Platform Checks**: No remaining references to KQueue/Epoll/DomainSockets modules
- [ ] **Tests Pass**: Unit + integration tests for module pass
- [ ] **Compilation**: Module compiles without warnings
- [ ] **No Performance Regression**: Benchmarked vs baseline
- [ ] **Documentation**: Code comments explain transport choice
- [ ] **Git Status**: Only expected files modified
- [ ] **Checkpoint Created**: Progress documented

### Test Coverage Strategy

| Module | Test Type | Command | Risk | Notes |
|--------|-----------|---------|------|-------|
| protocols | Unit | `mvnw test -pl protocols` | LOW | Safe, no isolates |
| model | Unit + Integration | `mvnw test -pl model` | LOW | Tests DemesneImpl |
| memberships | Unit | `mvnw test -pl memberships` | LOW | Tests Enclave/Portal |
| isolates | Compilation only | `mvnw compile -pl isolates -Pisolates` | LOW | No runtime tests yet |
| isolate-ftesting | Full integration | `mvnw test -pl isolate-ftesting -Pisolates` | MEDIUM | Isolate tests - use timeout |

### Documentation Requirements

Every completed bead requires:

1. **Code Comments**: Explain why NIO instead of native transport
2. **Checkpoint Entry**: Summary of changes, test results, any issues
3. **Metrics Update**: Completion percentage, test results
4. **Risk Log**: Any new blockers discovered

## Critical Sections

### Terminal Hang Risk Management

**Background**: Previous isolate testing caused terminal to hang (unresponsive, required kill -9)

**This Project's Safeguards**:

1. **Phases 1-3**: NO isolate testing (pure unit/integration tests)
2. **Phase 4 Isolate Tests**:
   - Run with 30-second timeout
   - Log to file (no console buffering issues)
   - Monitor independently
   - Have kill command ready

3. **Recovery Procedure**:
   - Kill process: `killall -9 java`
   - Investigate: Review log file
   - Document: Update checkpoint
   - Restart: Continue with next test

**Never Assume Terminal is Lost**: Can always `killall -9` from another terminal

### Performance Degradation Target

- **Acceptable**: < 20% slower than native transports
- **Expected**: < 10% slower (NIO is very efficient)
- **Measurement**: Throughput (msgs/sec) and latency (p99 ms)

If degradation exceeds 20%, investigate before declaring victory.

## Collaboration & Handoffs

### With Strategic Planner

Strategic planner may produce detailed implementation plan. When it arrives:

1. Review plan against this methodology
2. Update beads and phase schedule if plan differs
3. Follow plan's sequencing but keep this methodology's discipline

### With Code Review Agent

Before merging any changes:

1. Request code review: Mention greenfield approach, NIO replacement
2. Provide baseline metrics from Phase 4 benchmarking
3. Highlight terminal hang prevention measures taken
4. Ask for validation of performance vs targets

### With Test Validator

Before Phase 4 completion:

1. Request test validation: Coverage analysis
2. Provide test results and logs
3. Ask for performance profile analysis
4. Validate isolate functional testing approach

## Metrics & Dashboards

### Progress Dashboard (`.pm-netty/metrics/progress.md`)

Track completion percentage per phase:
- Phase 1: modules_updated / 5
- Phase 2: modules_deleted / 3
- Phase 3: metadata_cleaned / 1
- Phase 4: test_suites_passing / 5 (protocols, model, memberships, isolate-ftesting, full build)

### Performance Dashboard (`.pm-netty/metrics/performance.md`)

Track before/after metrics:
- Throughput (messages/sec): baseline → post-Phase4
- Latency p50/p99: baseline → post-Phase4
- Memory usage: baseline → post-Phase4
- GC pause time: baseline → post-Phase4

### Risk Dashboard (`.pm-netty/RISK_REGISTER.md`)

Track active blockers:
- Terminal hang incidents
- Performance degradation status
- Peer credential extraction compatibility
- Platform-specific NIO issues

## Success Definition

### Phase 1 Success
✅ All 5 modules updated to use NIO
✅ Code compiles without errors
✅ All protocol/unit tests pass
✅ No terminal hangs observed

### Phase 2 Success
✅ 3 modules deleted (domain-kqueue, domain-epoll, domain-sockets)
✅ Parent pom.xml references removed
✅ No dangling module references in build

### Phase 3 Success
✅ Native library metadata removed
✅ GraalVM reachability-metadata.json updated
✅ No native library loading in code

### Phase 4 Success
✅ All test suites pass (unit, integration, isolate-ftesting)
✅ No terminal hangs during extended testing
✅ Performance degradation < 20% (expect < 10%)
✅ Isolates functional tests pass
✅ Documentation updated

## Tool Usage

### Beads (`bd` command)
- Track all tasks
- Set dependencies between beads
- Update status as work progresses
- Close bead only when tests pass

### ChromaDB
- Search for prior knowledge: `debug::isolates::netty-native-library-conflict`
- Document decisions: `decision::netty-elimination::nio-transport-choice`
- Research findings: `research::isolates::nio-domain-sockets-solution`

### Memory Bank
- Session state: `delos_active/netty-elimination-findings.md`
- Blockers: `delos_active/netty-elimination-blockers.md`
- Test results: `delos_active/netty-elimination-test-results.md`

### Git & Commits

**DO**:
- Commit per-module changes (not all at once)
- Use clear messages: "Phase 1: Replace KQueue with NIO in protocols module"
- Reference bead IDs: "References: Delos-1b9y"

**DON'T**:
- Commit incomplete phases
- Skip tests
- Delete modules before Phase 2 gate
- Mention AI attribution in commits

## Emergency Escalation

If you encounter:

1. **Terminal Hang**: Kill Java, document, continue
2. **Peer Credential Loss**: Research SO_PEERCRED compatibility, document gap
3. **Performance Degradation > 20%**: Pause, investigate, document findings
4. **Unexpected NIO Incompatibility**: Review blockers, update RISK_REGISTER.md

## Estimated Time per Phase

| Phase | Duration | Effort | Critical Path | Notes |
|-------|----------|--------|----------------|-------|
| 1 | 1 week | 5 modules × 4-6 hours each | protocols → model → memberships | Includes testing |
| 2 | 0.5 days | 2-3 hours | Atomic deletion | Fast once Phase 1 complete |
| 3 | 0.5 days | 1-2 hours | Metadata cleanup | Usually straightforward |
| 4 | 1 week | Full test suite + benchmarking | isolate-ftesting last | Terminal hang risk |
| **Total** | **3 weeks** | **~40 hours** | N/A | Single engineer |

## Version Management

**Netty**: 4.1.110+ (or current in pom.xml)
**Java**: 16+ (for JEP 380, currently using Java 25)
**GraalVM**: 25.0.1 (if isolates enabled)

No version changes needed - just transport selection within existing Netty.
