# Netty Native Library Elimination - PM Infrastructure

**Project**: Eliminate Netty native transport libraries (kqueue/epoll) and replace with JEP 380 pure-Java NIO
**Duration**: 3 weeks (2026-01-15 to 2026-02-05)
**Branch**: `feature/netty-elimination`
**Status**: Infrastructure Setup Complete - Ready for Phase 1

## Quick Navigation

### Start Here
1. **[CONTINUATION.md](CONTINUATION.md)** - Session resumption guide (how to continue where you left off)
2. **[execution_state.json](execution_state.json)** - Project state, phases, beads, blockers
3. **[METHODOLOGY.md](METHODOLOGY.md)** - Engineering discipline and testing approach

### Deep Dives
- **[RISK_REGISTER.md](RISK_REGISTER.md)** - Detailed risk analysis (terminal hangs, performance, peer credentials)
- **[checkpoints/](checkpoints/)** - Progress snapshots after each major milestone
- **[metrics/](metrics/)** - Progress tracking, performance benchmarks
- **[learnings/](learnings/)** - Key insights and decisions

### Daily Work
- **Beads**: `bd list --status=ready` to see what's ready to start
- **Progress**: Update `metrics/progress.md` after each bead completion
- **Checkpoint**: Create checkpoint after completing major milestones
- **Blockers**: Update `RISK_REGISTER.md` if new blockers discovered

## Project Overview

### Problem
Netty native transport libraries (libnetty_transport_native_kqueue/epoll) crash when loaded in both:
1. Main JVM (for domain socket channels)
2. GraalVM isolate (for multi-tenant enclaves)

This double-loading causes fatal crash: "Unhandled exception" (Exit Code 99)

### Solution
Replace native transports with **JEP 380 pure-Java NIO** domain sockets (Java 16+, Netty 4.1.110+)
- Pure Java, no native libraries
- Cross-platform (Windows/Linux/macOS)
- Works in GraalVM isolates
- Well-tested, standard solution

### Impact
- **Delete**: 3 modules (domain-kqueue, domain-epoll, domain-sockets)
- **Update**: 5 modules (protocols, model, isolates, isolate-ftesting, memberships)
- **Result**: Simpler codebase, working isolates, greenfield architecture

### Timeline
| Phase | Duration | Status | Key Deliverable |
|-------|----------|--------|-----------------|
| 1: Code Migration | 1 week | READY | 5 modules updated, tests pass |
| 2: Module Deletion | 0.5 days | PENDING | 3 modules deleted |
| 3: Metadata Cleanup | 0.5 days | PENDING | Native library refs removed |
| 4: Testing & Validation | 1 week | PENDING | All tests pass, perf validated |

## Files & Structure

```
.pm-netty/
├── execution_state.json          # Current state, phases, metrics, blockers
├── CONTINUATION.md               # How to resume after session break
├── METHODOLOGY.md                # Engineering discipline & testing strategy
├── RISK_REGISTER.md              # Risk analysis (terminal hangs, performance)
├── README.md                     # This file
├── checkpoints/                  # Progress snapshots
│   ├── TEMPLATE-checkpoint.md    # Checkpoint template
│   ├── phase1-checkpoint-1.md    # Example: protocols module complete
│   └── ...
├── metrics/                      # Progress tracking
│   ├── progress.md               # Completion percentages per phase
│   ├── performance.md            # Before/after performance metrics
│   └── bead-tracking.md          # Bead status summary
├── learnings/                    # Key insights & decisions
│   ├── L0-socket-compatibility.md    # Learning about NIO vs native
│   ├── L1-performance-expectations.md
│   └── ...
├── hypotheses/                   # Technical decisions & validation
│   ├── H0-nio-approach.md        # Why NIO instead of other solutions
│   └── ...
└── audits/                       # Quality gates & reviews
    ├── code-review-checklist.md
    └── ...
```

## Key Concepts

### Greenfield Approach
This is **not** a debugging project. We're **replacing** native libraries entirely with a well-defined, standard solution (JEP 380 NIO). The approach is clear, the risk is manageable.

### Phase Gating
Each phase must **fully complete** before next begins. No mixing phases:
- Phase 1: Update all 5 modules, tests pass
- Phase 2: Delete 3 modules, pom.xml cleaned
- Phase 3: Remove metadata
- Phase 4: Comprehensive validation

### Safe Testing (Terminal Hang Prevention)
**Critical Rule**: Phases 1-3 do NOT test isolates (safe), Phase 4 tests isolates with extreme caution:
- Use timeout: `timeout 30s <test>`
- Monitor from separate terminal
- Kill command ready: `killall -9 java`
- Document hangs, continue

### Module Updates (Phase 1)
Five modules need socket channel replacement:

| Module | Current | Replacement | Complexity |
|--------|---------|-------------|-----------|
| protocols | DomainSocketServerInterceptor | Use NioEventLoopGroup | LOW |
| model | DemesneImpl transport init | Select NIO transport | LOW |
| memberships | Enclave/Portal creation | NioDomainSocketChannel | LOW |
| isolates | Isolate-specific handling | Adapt for NIO | MEDIUM |
| isolate-ftesting | Test infrastructure | Update test setup | LOW |

## How to Use This Infrastructure

### At Session Start
```bash
# 1. Read this README
# 2. Read CONTINUATION.md for project context
# 3. Check execution_state.json for current phase
# 4. Read METHODOLOGY.md for approach
# 5. List ready work: bd list --status=ready
# 6. Review latest checkpoint: ls -lt checkpoints/ | head -3
```

### During Work on a Module
```bash
# 1. Check which beads are ready
bd list --status=ready

# 2. Pick a bead (e.g., module update)
bd show Delos-xxx

# 3. Start work
bd update Delos-xxx --status in_progress

# 4. Make code changes, run tests
./mvnw test -pl <module>

# 5. Create checkpoint if successful
cp checkpoints/TEMPLATE-checkpoint.md checkpoints/phase1-checkpoint-X.md
# Edit with your progress

# 6. Close bead when tests pass
bd close Delos-xxx

# 7. Update metrics
# Edit metrics/progress.md with completion percentage
```

### Before Ending Session
```bash
# 1. Update current bead status
bd update <id> --status in_progress  # If pausing mid-work
# OR
bd close <id>  # If work complete and tests pass

# 2. Create checkpoint
cp checkpoints/TEMPLATE-checkpoint.md checkpoints/phase-X-checkpoint-Y.md
# Fill in: what you did, what passed, what failed, next steps

# 3. Update metrics
# Edit metrics/progress.md with new completion percentage

# 4. Document any blockers
# Edit RISK_REGISTER.md if new blockers discovered

# 5. Commit progress
git add .pm-netty/checkpoints .pm-netty/metrics .pm-netty/RISK_REGISTER.md
git commit -m "Progress: Phase X - [module name] - [status]"
```

## Key Milestones

### Phase 1 Completion Criteria
- ✅ All 5 modules updated to use NioEventLoopGroup/NioDomainSocketChannel
- ✅ Code compiles without errors
- ✅ All protocol/unit tests pass
- ✅ No terminal hangs
- ✅ Checkpoint documenting all changes

### Phase 2 Completion Criteria
- ✅ 3 modules deleted (domain-kqueue, domain-epoll, domain-sockets)
- ✅ Parent pom.xml cleaned of module references
- ✅ No dangling module references in code

### Phase 3 Completion Criteria
- ✅ Native library metadata removed
- ✅ GraalVM reachability-metadata.json updated
- ✅ No references to native library loading

### Phase 4 Completion Criteria
- ✅ All test suites pass
- ✅ No terminal hangs during extended testing
- ✅ Performance degradation < 20% (expect < 10%)
- ✅ Isolate functional tests pass
- ✅ Documentation updated

## Critical Risks (See RISK_REGISTER.md for details)

### Terminal Hang Risk (HIGH)
**What**: Previous isolate testing caused terminal to hang
**Prevention**: Safe testing with timeout, separate monitoring terminal, kill recovery
**Phase Impact**: Phase 4 isolate testing only

### Performance Degradation (MEDIUM)
**Target**: < 20% acceptable (< 10% expected)
**Measurement**: Baseline before Phase 1, compare post-Phase 4
**Action If Exceeded**: Investigate, document, escalate

### Peer Credentials Extraction (LOW)
**What**: May lose ability to extract socket peer UID/GID
**Impact**: Access control may need to use TLS instead
**Fallback**: Already using gRPC MTLS, can fallback to that

## Important Files in Delos Repo

### Code to Update (Phase 1)
- `protocols/src/main/java/com/hellblazer/delos/comm/grpc/DomainSocketServerInterceptor.java`
- `model/src/main/java/com/hellblazer/delos/model/demesnes/DemesneImpl.java`
- `memberships/src/main/java/com/hellblazer/delos/archipelago/Enclave.java`
- `isolates/src/main/java/...` (various isolate-specific socket usage)
- `isolate-ftesting/src/test/java/...` (test infrastructure)

### Modules to Delete (Phase 2)
- `domain-kqueue/` (complete directory)
- `domain-epoll/` (complete directory)
- `domain-sockets/` (complete directory)

### Dependencies to Update (Phase 1-3)
- `pom.xml` - Remove domain-kqueue, domain-epoll, domain-sockets dependencies
- `isolates/pom.xml` - Remove native library profiles or adapt to NIO
- `isolate-ftesting/pom.xml` - Update test dependencies

## Knowledge Base Integration

### ChromaDB Documents
- `debug::isolates::netty-native-library-conflict` - Root cause analysis
- `research::isolates::nio-domain-sockets-solution` - JEP 380 research

### Memory Bank (`delos_active/`)
- `netty-elimination-findings.md` - Discoveries during work
- `netty-elimination-blockers.md` - Blocking issues
- `netty-elimination-test-results.md` - Test results and logs

## Testing Strategy

### Phase 1-3: Protocol/Unit Tests (SAFE)
```bash
./mvnw test -pl protocols
./mvnw test -pl model
./mvnw test -pl memberships
```

### Phase 4: Integration/Isolate Tests (HIGH RISK - EXTREME CAUTION)
```bash
# With timeout and logging
timeout 30s ./mvnw test -pl isolate-ftesting -Pisolates 2>&1 | tee /tmp/isolate.log
```

## Communication & Escalation

### Document Issues
1. **Code-level**: Comments in Java code
2. **Decision-level**: Create learning in `learnings/`
3. **Blocking-level**: Update `RISK_REGISTER.md`
4. **Team-level**: Create hypothesis in `hypotheses/`

### Before Major Decision
1. Document in hypothesis
2. Add to checkpoint
3. Update RISK_REGISTER.md if impacts timeline

## Success Indicators

- **Code Quality**: All changes use NIO, no lingering native library references
- **Test Coverage**: All tests pass, > 95% module coverage
- **Performance**: < 20% degradation (expect < 10%)
- **Stability**: No terminal hangs, no crashes
- **Documentation**: All changes documented, approach clear

## Quick Commands Reference

```bash
# Beads
bd list --status=ready              # Check what's ready to start
bd show Delos-1b9y                  # Show epic bead
bd update <id> --status in_progress # Start work
bd close <id>                       # Mark complete
bd dep add <id> <blocker>           # Add dependency

# Maven
./mvnw compile -amd -pl <module>    # Compile with dependencies
./mvnw test -pl <module>            # Run module tests
./mvnw clean install                # Full build

# Git
git status                          # Check changes
git log --oneline -10               # Recent commits
git diff <file>                     # See changes
git add <file>; git commit -m ""    # Commit

# File operations
ls -lt .pm-netty/checkpoints/       # Recent checkpoints
tail -f /tmp/isolate.log            # Monitor test output
```

## Support Resources

- **Methodology**: Read `.pm-netty/METHODOLOGY.md` for discipline
- **Risk Details**: See `RISK_REGISTER.md` for full analysis
- **Prior Progress**: Check `checkpoints/` for what's been done
- **Code Examples**: Look at existing socket creation code in modules

## Version Info

- **Java**: 25
- **Netty**: 4.1.110+ (check pom.xml)
- **GraalVM**: 25.0.1 (for isolates)
- **Maven**: 3.9.3+

## Timeline Estimate

| Phase | Duration | Critical Path | Notes |
|-------|----------|----------------|-------|
| 1 | 1 week | protocols → model → memberships | Includes testing |
| 2 | 0.5 days | Atomic deletion | Fast |
| 3 | 0.5 days | Metadata cleanup | Usually quick |
| 4 | 1 week | Full test suite | Terminal hang risk here |
| **TOTAL** | **3 weeks** | N/A | Single engineer estimate |

---

**For detailed guidance**: See CONTINUATION.md to resume from where you left off
**For methodology**: See METHODOLOGY.md for engineering discipline
**For risks**: See RISK_REGISTER.md for detailed risk analysis
