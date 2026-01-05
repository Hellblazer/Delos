# Fireflies Synchronization Refactoring - Continuation Guide

**Quick Start**: Read this section, then refer to detailed documents as needed.

---

## What Is This Project?

A focused architectural refactoring to fix lock contention between consensus and join operations in Fireflies membership service.

**Problem**: ReadWriteLock on `View.viewChange` blocks join requests during consensus, causing bootstrap failures
**Goal**: Eliminate contention, improve join latency 3-5x, fix BootstrapTest/ChurnTest timeouts
**Duration**: 4-5 weeks (5 phases: 1 investigation + 4 implementation)

---

## Current Status (As of 2026-01-04 - Session 2)

| Phase | Name | Status | Timeline | Progress |
|-------|------|--------|----------|----------|
| Phase 0 | Investigation | ✅ COMPLETE | Completed (commit 2097442) | 100% |
| Phase 1 | Instrumentation & Baseline | 🟡 80% COMPLETE | 2026-01-04 (active) | See below |
| Phase 2 | Foundation | Blocked by P1 | 2026-01-11 to 2026-01-17 | Waiting |
| Phase 3 | Implementation | Blocked by P2 | 2026-01-18 to 2026-01-31 | Waiting |
| Phase 4 | Testing & Hardening | Blocked by P3 | 2026-02-01 to 2026-02-07 | Waiting |

**Phase 1 Progress**:
- ✅ ViewLockMetrics interface + ViewLockMetricsImpl created
- ✅ View.java instrumented (stable() and viewChange() methods)
- 🟡 LockContentionTest harness 80% complete (API fixes needed)
- ⏳ Baseline metrics collection pending
- ⏳ ChromaDB documentation pending

**Next Action**: Complete Phase 1.1 and move to Phase 2

---

## Three Immediate Reads (In Order)

1. **This File** (SYNCHRONIZATION_CONTINUATION.md) - Resume context (5 min)
2. **phases/SYNCHRONIZATION_REFACTORING.md** - Detailed phase plan (10 min)
3. **SYNCHRONIZATION_REFACTORING_STATE.md** - Metrics and status (5 min)

---

## Three Candidate Solutions (Identified)

### Solution 1: Lock-Free Synchronization
```
Approach: Replace ReadWriteLock with ConcurrentHashMap-based coordination
Pros:     No contention, scalable, modern Java patterns
Cons:     Eventual consistency, complex validation logic
Risk:     Byzantine tolerance implications - needs validation
```

### Solution 2: Separate Locks
```
Approach: Split read/write concerns into separate lock objects
Pros:     Reduces contention, maintains strong consistency
Cons:     Deadlock risk, coordination complexity
Risk:     Byzantine coordination failures under partition
```

### Solution 3: Non-Blocking Reads
```
Approach: Read-only fast path with snapshot semantics
Pros:     Minimal write lock holding, reads never block
Cons:     Temporary inconsistency, snapshot windows
Risk:     Race conditions in Byzantine consensus validation
```

**Phase 1 Task**: Evaluate these, select one, validate safety properties.

---

## What Needs Doing (Next Steps)

### This Week (Phase 1)

**1.1 - Evaluate Approaches** (Monday-Tuesday)
- Analyze each solution's trade-offs
- Measure potential improvements
- Create decision matrix
- **Deliverable**: Approach evaluation document

**1.2 - Architecture Design** (Wednesday-Thursday)
- Design chosen approach
- Create class hierarchy
- Define synchronization protocol
- **Deliverable**: Architecture document with diagrams

**1.3 - Test Strategy** (Friday)
- Plan test categories
- Design BootstrapTest modifications
- Create performance regression tests
- **Deliverable**: Test strategy document

**Outcome**: Phase 1 complete by end of week, ready to start Phase 2

### Next Week (Phase 2)

**2.1 - Extract SynchronizationCoordinator**
- New class to encapsulate lock management
- Pluggable strategy implementations
- Files: View.java integration

**2.2 - Create ConsensusJoinMembrane**
- Protocol for consensus vs join coordination
- Message ordering guarantees
- Byzantine failure handling

**2.3 - Test Infrastructure**
- Lock contention monitoring
- Byzantine fault injectors
- Bootstrap progression tracking

---

## Key Files to Know

### Must Read
- `phases/SYNCHRONIZATION_REFACTORING.md` - Full phase plan
- `SYNCHRONIZATION_REFACTORING_STATE.md` - Current state & metrics
- Commit `2097442` - Investigation findings

### Will Modify
- `fireflies/src/main/java/.../View.java` - Core class (~1223 lines)
- `fireflies/src/main/java/.../ViewChangeCoordinatorImpl.java` - Consensus logic
- `fireflies/src/main/java/.../ViewService.java` - Join handler
- `fireflies/src/main/java/.../ViewManagement.java` - Membership state

### Will Create
- `SynchronizationCoordinator.java` + implementations
- `ConsensusJoinMembrane.java`
- Test files for each phase

### Will Test
- `fireflies/src/test/java/.../BootstrapTest.java` - 6 methods failing
- `fireflies/src/test/java/.../ChurnTest.java` - Convergence failing

---

## Commands You'll Use

### Task Tracking (Use Often)
```bash
bd ready                           # See unblocked work
bd show Delos-XXXX                 # Details of specific task
bd create "Title" -t feature -p 1  # Create new task
bd update Delos-XXXX --status in_progress
bd close Delos-XXXX                # Complete task
```

### Build & Test
```bash
./mvnw install -amd -pl fireflies       # Build module + deps
./mvnw test -pl fireflies                # Test module
./mvnw test -Dtest=BootstrapTest        # Specific test class
./mvnw test -Dtest=BootstrapTest#testSingleNodeBootstrap  # Single method
./mvnw clean install -Dlarge_tests=true # Full integration tests
```

### Session Management
```bash
/check                          # Save state before break
/load                           # Restore state when resuming
git status
git add <files>
git commit -m "message [Bead-XXXX]"
```

### Search Knowledge Base
```bash
# Find prior decisions/patterns
mcp__chromadb__search_similar(
  query: "synchronization consensus join",
  num_results: 10
)

# Store new findings
mcp__chromadb__create_document(
  document_id: "decision::fireflies::synchronization-chosen",
  content: "...",
  metadata: {"component": "fireflies", "type": "decision"}
)
```

---

## Daily Workflow (While Working on Phase)

### Morning Standup (5 min)
```bash
bd ready                           # What's unblocked?
bd show Delos-XXXX                 # Current task details
bd update Delos-XXXX --status in_progress  # Mark as working
```

### During Day
1. **Test First** - Write test before implementation
2. **Implement** - Make test pass
3. **Validate** - All tests pass (unit + integration)
4. **Document** - Code comments + ChromaDB decision
5. **Checkpoint** - Save session state periodically

### End of Day
```bash
git status                         # What changed?
git add <modified files>
git commit -m "fix: description [Delos-XXXX]"
/check                             # Save state
```

### When Resuming Next Session
```bash
/load                              # Restore context
# Read SYNCHRONIZATION_CONTINUATION.md (this file)
bd ready                           # See work
# Continue from checkpoint
```

---

## Test Targets (What Success Looks Like)

### BootstrapTest
```
Current: All 6 methods TIMEOUT (>30s)
Target:  All 6 methods PASS (<5s total)

Methods:
  ✅ testSingleNodeBootstrap
  ✅ testTwoNodeBootstrap
  ✅ testThreeNodeBootstrap
  ✅ testFourNodeBootstrap
  ✅ testFiveNodeBootstrap
  ✅ testSixNodeBootstrap
```

### ChurnTest
```
Current: Convergence TIMEOUT
Target:  Full convergence success

Success: 12 initial nodes → 50 max → stable with churn
         All members adopt, no isolation
```

### Performance
```
Current: Unmeasured (blocked by timeout)
Target:  3-5x improvement in join latency
         No regression in consensus latency
         Lock contention < 10%
```

---

## Important Policies

### Code Quality Standards
- **Java 23+**: Use `var`, modern patterns, no `synchronized`
- **Test-First**: Write test before fix
- **Coverage**: 95%+ on critical paths
- **Byzantine**: All changes must preserve safety properties

### Git Discipline
- **NO AI Attribution** - Company policy violation
- **Bead References**: Every commit includes `[Delos-XXXX]`
- **Atomic Commits**: One logical change per commit
- **Code Review**: Approval before merge

### Knowledge Management
- **ChromaDB**: Store permanent decisions
- **Memory Bank**: Store session work (Delos_active/)
- **Beads**: Track all tasks
- **Checkpoints**: Save after each work session

---

## Getting Help

### Stuck on Phase 1 Design?
1. Check `phases/SYNCHRONIZATION_REFACTORING.md` Phase 1 section
2. Review investigation findings (commit 2097442)
3. Search ChromaDB for synchronization patterns
4. Escalate to `java-architect-planner` for design review

### Stuck on Implementation?
1. Check `phases/SYNCHRONIZATION_REFACTORING.md` Phase 2-4 sections
2. Read METHODOLOGY.md for discipline guidance
3. Check Memory Bank for prior session notes
4. Escalate to `java-developer` or `java-debugger`

### Stuck on Tests?
1. Check test strategy in SYNCHRONIZATION_REFACTORING.md
2. Read METHODOLOGY.md testing section
3. Check existing test files (BootstrapTest, ChurnTest)
4. Escalate to `test-validator`

### Stuck on Anything Else?
1. Check `.pm/README.md` - general guidance
2. Check `.pm/METHODOLOGY.md` - discipline
3. Check `.pm/AGENT_INSTRUCTIONS.md` - agent roles
4. Check `.pm/RISK_REGISTER.md` - known risks

---

## Timeline at a Glance

```
Week 1 (Jan 4-10): Phase 1 - Design & Validation
  - Select approach
  - Design architecture
  - Plan test strategy

Week 2 (Jan 11-17): Phase 2 - Foundation
  - Extract SynchronizationCoordinator
  - Create ConsensusJoinMembrane
  - Build test infrastructure

Weeks 3-4 (Jan 18-31): Phase 3 - Implementation
  - Implement chosen strategy
  - Integrate with View & ViewManagement
  - Integration testing

Week 5 (Feb 1-7): Phase 4 - Testing & Hardening
  - Comprehensive test suite
  - Performance validation
  - Stabilization & merge to main
```

**Target Completion**: 2026-02-07 (one week buffer)

---

## Quick Reference Links

| Need | File |
|------|------|
| Phase details | `phases/SYNCHRONIZATION_REFACTORING.md` |
| Current metrics | `SYNCHRONIZATION_REFACTORING_STATE.md` |
| Discipline | `.pm/METHODOLOGY.md` |
| Agent roles | `.pm/AGENT_INSTRUCTIONS.md` |
| Known risks | `.pm/RISK_REGISTER.md` |
| Project overview | `.pm/README.md` |
| All files | `.pm/INDEX.md` |

---

## Success Definition

**Phase 1 Complete**: Approach selected + validated, architecture designed, test strategy ready
**Phase 2 Complete**: Foundation classes extracted + tested
**Phase 3 Complete**: Synchronization implemented + integrated
**Phase 4 Complete**: All tests passing, performance validated, code reviewed

**Project Complete**: BootstrapTest (6/6) + ChurnTest passing, 3-5x join latency improvement, zero regressions

---

## Next Action RIGHT NOW

1. ✅ Read this file (SYNCHRONIZATION_CONTINUATION.md)
2. → Read `phases/SYNCHRONIZATION_REFACTORING.md` (Phase 1 section)
3. → Read `SYNCHRONIZATION_REFACTORING_STATE.md` (status overview)
4. → Create Phase 1 beads: Delos-7ws, Delos-7wt, Delos-7wu
5. → Start Phase 1.1 (evaluate synchronization approaches)

---

**Last Updated**: 2026-01-04
**Status**: Ready to begin Phase 1
**Current Phase**: Phase 0 Investigation ✅ COMPLETE
**Next Phase**: Phase 1 Design & Validation (starts today)
**Target Completion**: 2026-02-07
**Time Budget**: ~4-5 weeks (5-10 days per phase)
