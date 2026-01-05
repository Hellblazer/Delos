# Fireflies Synchronization Refactoring - Project README

**Status**: Infrastructure Setup COMPLETE - Ready for Phase 1
**Date**: 2026-01-04
**Location**: `/Users/hal.hildebrand/git/Delos/.pm/`

---

## Welcome to the Synchronization Refactoring Project

This is a focused architectural refactoring to eliminate lock contention between consensus operations and join requests in the Fireflies membership service.

### The Problem
- **Root Cause**: ReadWriteLock on `View.viewChange` blocks join requests during consensus
- **Symptoms**: BootstrapTest timeouts (all 6 methods), ChurnTest convergence failures
- **Impact**: Cascading bootstrap failures when consensus and joins overlap
- **Lock Location**: `View.java` - `viewChange` ReadWriteLock

### The Goal
- Fix BootstrapTest and ChurnTest timeouts
- Improve join latency 3-5x
- Preserve Byzantine fault tolerance
- Complete within 4-5 weeks

### Current Status
- Investigation: ✅ COMPLETE (commit 2097442)
- Three solutions identified and analyzed
- Infrastructure ready for Phase 1 (Design & Validation)

---

## Quick Navigation

### If You're Starting Fresh
1. **First Read** (5 min): `SYNCHRONIZATION_QUICK_START.md`
2. **Then Read** (5 min): `SYNCHRONIZATION_CONTINUATION.md`
3. **Then Deep Dive** (10 min): `phases/SYNCHRONIZATION_REFACTORING.md` (Phase 1 section)

### If You're Resuming Work
1. **Restore Session**: `/load`
2. **Check Current State**: `bd ready`
3. **Read Resume Guide**: `SYNCHRONIZATION_CONTINUATION.md`
4. **Continue from Checkpoint**: See `.pm/checkpoints/` directory

### If You Need a Reference
- **Command Cheat Sheet**: See `SYNCHRONIZATION_QUICK_START.md`
- **Full Phase Plan**: See `phases/SYNCHRONIZATION_REFACTORING.md`
- **Current Metrics**: See `SYNCHRONIZATION_REFACTORING_STATE.md`

---

## The 5-Phase Roadmap

```
Phase 0: Investigation                    ✅ COMPLETE
         Root cause identified (commit 2097442)
         Three solutions analyzed
         Test infrastructure ready

Phase 1: Design & Validation              → NEXT (This Week)
         Evaluate three approaches
         Select best approach
         Design architecture
         Plan test strategy

Phase 2: Foundation                       (Week 2)
         Extract SynchronizationCoordinator
         Create ConsensusJoinMembrane
         Build test utilities

Phase 3: Implementation                   (Weeks 3-4)
         Implement chosen strategy
         Integrate with View & ViewManagement
         Run integration tests

Phase 4: Testing & Hardening             (Week 5)
         Comprehensive test suite
         Performance validation
         Stabilization & merge
```

---

## Three Candidate Solutions

### 1. Lock-Free Synchronization
**Performance**: 5x (BEST)
**Complexity**: Medium
**Risk**: Byzantine tolerance validation needed
**Best For**: High-throughput scenarios, no contention

### 2. Separate Locks Strategy
**Performance**: 2-3x
**Complexity**: High
**Risk**: Deadlock/coordination failures
**Best For**: Strong consistency requirements

### 3. Non-Blocking Reads
**Performance**: 3-4x (GOOD)
**Complexity**: Medium
**Risk**: Temporary inconsistency windows
**Best For**: Read-heavy workloads (join path)

**Phase 1 Task**: Evaluate, select one, validate safety properties

---

## Test Targets

### BootstrapTest
```
Current Status: FAILING - All 6 methods timeout (>30s)
Target Status:  ALL PASS - Complete in < 5 seconds total

Methods to Fix:
  ✓ testSingleNodeBootstrap     (1 node)
  ✓ testTwoNodeBootstrap        (2 nodes)
  ✓ testThreeNodeBootstrap      (3 nodes)
  ✓ testFourNodeBootstrap       (4 nodes)
  ✓ testFiveNodeBootstrap       (5 nodes)
  ✓ testSixNodeBootstrap        (6 nodes)
```

### ChurnTest
```
Current Status: FAILING - Convergence timeout
Target Status:  ALL PASS - Full convergence success

Scenario: 12 initial nodes → scale to 50 with removals
Expected: All members adopt, no isolation due to lock contention
```

### Performance
```
Baseline:       Currently blocked by timeouts (unmeasured)
Target:         3-5x improvement in join latency
                No regression in consensus latency
                Lock contention < 10% measured
```

---

## Planned Work Items (13 Total Beads)

### Phase 1: Design & Validation (This Week)
- **Delos-7ws** - Evaluate synchronization approaches
- **Delos-7wt** - Architecture design & validation
- **Delos-7wu** - Test strategy & harness planning

### Phase 2: Foundation (Week 2)
- **Delos-7wv** - Extract SynchronizationCoordinator
- **Delos-7ww** - Create ConsensusJoinMembrane
- **Delos-7wx** - Establish test infrastructure

### Phase 3: Implementation (Weeks 3-4)
- **Delos-7wy** - Implement synchronization strategy
- **Delos-7wz** - Integration testing & validation

### Phase 4: Testing & Hardening (Week 5)
- **Delos-7x0** - Comprehensive test suite
- **Delos-7x1** - Performance validation
- **Delos-7x2** - Stabilization & merge

---

## Files in This Infrastructure

### Quick Reference Cards
- `SYNCHRONIZATION_QUICK_START.md` - 60-second resume card
- `SYNCHRONIZATION_README.md` - This file

### Planning Documents
- `SYNCHRONIZATION_CONTINUATION.md` - Resume guide for sessions
- `phases/SYNCHRONIZATION_REFACTORING.md` - Full phase details
- `SYNCHRONIZATION_REFACTORING_STATE.md` - Current metrics & status
- `SYNCHRONIZATION_SETUP_SUMMARY.md` - Infrastructure overview

### Knowledge Base
- ChromaDB: `analysis::fireflies::synchronization-lock-contention-2026-01-04`
  - Three solutions analyzed with trade-offs
  - Root cause deep-dive
  - Implementation recommendations

### Session Management
- `checkpoints/` - Daily work session records
- Memory Bank: `Delos_active/synchronization-refactoring.md` (to create)

---

## How to Get Started (Right Now)

### Step 1: Understand the Problem (5 minutes)
```bash
# Read the investigation findings
git show 2097442 --stat

# Check test failures
./mvnw test -Dtest=BootstrapTest
./mvnw test -Dtest=ChurnTest
```

### Step 2: Read Documentation (15 minutes)
```bash
# 1. Quick start (5 min)
cat .pm/SYNCHRONIZATION_QUICK_START.md

# 2. Resume guide (5 min)
cat .pm/SYNCHRONIZATION_CONTINUATION.md

# 3. Phase 1 details (5 min)
head -200 .pm/phases/SYNCHRONIZATION_REFACTORING.md
```

### Step 3: Prepare to Work (5 minutes)
```bash
# Create Phase 1 beads
bd create "Evaluate synchronization approaches" -t feature -p 1
# Note the bead ID (e.g., Delos-7ws)

bd create "Architecture design & validation" -t feature -p 1
# Note the bead ID (e.g., Delos-7wt)

bd create "Test strategy & harness planning" -t feature -p 1
# Note the bead ID (e.g., Delos-7wu)

# Mark first task as in progress
bd update Delos-7ws --status in_progress
```

### Step 4: Start Phase 1.1 (Rest of Day)
```bash
# Begin evaluating the three synchronization approaches
# Create decision matrix with pros/cons for each
# Document performance estimates
# Target: Complete approach evaluation by end of day or week
```

---

## Daily Workflow While Working

### Morning (5 min)
```bash
/load                                    # Restore session
bd ready                                 # See unblocked work
# Read latest checkpoint from .pm/checkpoints/
```

### During Work
1. Write test first (demonstrate requirement or bug)
2. Implement fix (make test pass)
3. Run tests (unit + integration)
4. Document decision in ChromaDB
5. Create/update checkpoint file

### End of Day
```bash
git commit -m "message [Delos-XXXX]"    # Include bead reference
/check                                   # Save session state
```

### When Resuming
```bash
/load                                    # Restore context
# Read SYNCHRONIZATION_CONTINUATION.md to refresh
bd ready                                 # See work
# Continue from most recent checkpoint
```

---

## Success Criteria

### Phase 1 (This Week - Jan 4-10)
- [ ] Approach evaluated and selected
- [ ] Architecture designed with diagrams
- [ ] Test strategy documented
- [ ] All Phase 1 beads closed

### Intermediate Gates
- **Phase 2**: Foundation classes extracted + tested
- **Phase 3**: Strategy implemented + integrated
- **Phase 4**: All tests passing + performance validated

### Final Success (Project Complete)
- [ ] BootstrapTest: All 6 methods pass consistently
- [ ] ChurnTest: Complete convergence without timeout
- [ ] Performance: 3-5x improvement in join latency
- [ ] Coverage: 95%+ on critical paths
- [ ] Tests: Zero regressions in existing suite
- [ ] Review: Code review approved
- [ ] Status: Merged to main

---

## Key Files to Modify

### Primary Targets
- `fireflies/src/main/java/.../View.java` - Core class with ReadWriteLock
- `fireflies/src/main/java/.../ViewChangeCoordinatorImpl.java` - Consensus logic
- `fireflies/src/main/java/.../ViewService.java` - Join handler
- `fireflies/src/main/java/.../ViewManagement.java` - Membership state

### Classes to Create
- `SynchronizationCoordinator.java` - New abstraction
- `LockFreeCoordinator.java` (or selected strategy)
- `ConsensusJoinMembrane.java` - Coordination protocol
- Test utilities and new test files

### Tests to Validate
- `fireflies/src/test/java/.../BootstrapTest.java` - 6 methods
- `fireflies/src/test/java/.../ChurnTest.java` - Convergence

---

## Essential Commands

### Task Management
```bash
bd ready                              # See unblocked work
bd create "Title" -t feature -p 1    # Create task
bd show Delos-XXXX                   # Show task details
bd update Delos-XXXX --status in_progress
bd close Delos-XXXX                  # Complete task
```

### Build & Test
```bash
./mvnw install -amd -pl fireflies           # Build + deps
./mvnw test -pl fireflies                    # Test module
./mvnw test -Dtest=BootstrapTest            # Specific test
./mvnw clean install -Dlarge_tests=true     # Full integration
```

### Session & Git
```bash
/load                                # Restore session
/check                               # Save session
git status
git add <files>
git commit -m "message [Delos-XXXX]"
```

---

## Important Policies

### Code Quality
- **Java 23+**: Use `var`, modern patterns, no `synchronized`
- **Test-First**: Write test before implementation
- **Coverage**: 95%+ on critical synchronization paths
- **Byzantine**: All changes must preserve safety properties

### Git Discipline
- **NO AI attribution** in commits (company policy)
- **Bead References**: Every commit includes `[Delos-XXXX]`
- **Atomic Commits**: One logical change per commit
- **Code Review**: Approval required before merge

### Knowledge Management
- **ChromaDB**: Store permanent decisions (lock contention analysis, approach selected, safety proofs)
- **Memory Bank**: Store session work (hypotheses, blockers, agent handoffs)
- **Beads**: Track all tasks (never use markdown TODOs)
- **Checkpoints**: Save after each work session

---

## Getting Help

### Stuck on Architecture?
- Read: `phases/SYNCHRONIZATION_REFACTORING.md` Phase 1
- Check: Commit 2097442 findings
- Escalate: `java-architect-planner` agent

### Stuck on Implementation?
- Read: `.pm/METHODOLOGY.md` for discipline
- Check: Memory Bank for prior session notes
- Escalate: `java-developer` or `java-debugger` agent

### Stuck on Testing?
- Read: Test strategy in phase plan
- Check: Existing test patterns
- Escalate: `test-validator` agent

### General Questions?
1. Check `SYNCHRONIZATION_CONTINUATION.md`
2. Check `.pm/METHODOLOGY.md`
3. Check `.pm/AGENT_INSTRUCTIONS.md`
4. Check Memory Bank for session notes
5. Search ChromaDB for prior decisions

---

## Timeline at a Glance

| Week | Phase | Focus | Outcome |
|------|-------|-------|---------|
| 1 (Jan 4-10) | Phase 1 | Design & Validation | Approach selected, architecture designed |
| 2 (Jan 11-17) | Phase 2 | Foundation | Classes extracted, test infrastructure ready |
| 3-4 (Jan 18-31) | Phase 3 | Implementation | Strategy implemented and integrated |
| 5 (Feb 1-7) | Phase 4 | Testing & Hardening | All tests passing, performance validated |

**Target Completion**: 2026-02-07

---

## Integration with Parent Project

This is a **sub-project** of the larger Delos Fireflies Remediation:

- **Parent Status**: Phases 0-3 complete (90% of work done)
- **This Project**: New focus area, isolated scope
- **Shared Resources**: Same PM infrastructure, agents, knowledge base
- **Independence**: Can proceed in parallel, no blocking relationship
- **Integration**: Uses same METHODOLOGY.md, AGENT_INSTRUCTIONS.md, beads, ChromaDB

---

## Bottom Line

**You have everything needed to systematically refactor the synchronization layer.**

✅ Complete investigation (commit 2097442)
✅ Three solutions analyzed and documented
✅ Phase plan created (5 phases, 13 tasks)
✅ Risk register established
✅ Knowledge base ready (ChromaDB)
✅ Session management infrastructure
✅ Test targets clearly defined
✅ Success metrics specified

**Next Action**: Read SYNCHRONIZATION_CONTINUATION.md, then start Phase 1.1

**Expected Outcome**: BootstrapTest + ChurnTest passing, 3-5x join improvement, by Feb 7

Good luck!

---

**Created**: 2026-01-04
**Status**: Ready to Begin Phase 1
**Duration**: ~4-5 weeks
**Target Completion**: 2026-02-07
