# Fireflies Synchronization Refactoring - Bootstrap Stability Fix

**Parent Project**: Delos Fireflies Remediation
**New Focus Area**: Architectural synchronization layer refactoring
**Status**: Planning Phase
**Target Start**: 2026-01-04
**Estimated Duration**: 4-5 weeks

---

## Executive Summary

This is a focused architectural refactoring to eliminate lock contention between consensus operations and join requests in the Fireflies membership service.

### Problem Statement
- **Root Cause**: ReadWriteLock contention between viewChange write lock and join read lock
- **Symptoms**: BootstrapTest timeouts (all 6 methods), ChurnTest convergence failures
- **Impact**: Blocks new member joins during consensus, cascades to bootstrap failure
- **Lock Location**: `View.java` - `viewChange` ReadWriteLock

### Investigation Results (Complete)
Investigation commit `2097442` identified:
- Lock hold time analysis
- Three architectural solutions evaluated
- Performance implications of each approach
- Test infrastructure ready for validation

---

## Project Structure

### Four Phases (Estimated Timeline)

```
Phase 1: Design & Validation (Week 1)
    ├─ Select synchronization approach
    ├─ Validate against safety properties
    └─ Create implementation plan

Phase 2: Foundation (Week 2)
    ├─ Extract/refactor foundation classes
    ├─ Create membrane between consensus/join paths
    └─ Establish test infrastructure

Phase 3: Implementation (Weeks 3-4)
    ├─ Implement chosen synchronization strategy
    ├─ Integrate with View and ViewManagement
    └─ Validation testing

Phase 4: Testing & Hardening (Week 5)
    ├─ Comprehensive test suite
    ├─ Performance regression detection
    ├─ Failure mode validation
    └─ Stabilization
```

---

## Phase 1: Design & Validation

**Duration**: 1 week
**Dependency**: None (starts immediately)

### Tasks

#### 1.1 Evaluate Synchronization Approaches (READY)
**Bead**: Delos-7ws (not yet created - create when approved)
**Status**: Ready for planning

**Three Approaches to Evaluate**:

1. **Lock-Free Synchronization** (ConcurrentHashMap-based)
   - Pros: No contention, scales to many cores, modern patterns
   - Cons: Eventual consistency, complex validation
   - Risk: Byzantine tolerance implications

2. **Separate Locks** (Split read/write concerns)
   - Pros: Reduces contention, maintains strong consistency
   - Cons: Deadlock risk, complexity in orchestration
   - Risk: Coordination failures under Byzantine conditions

3. **Non-Blocking Reads** (Read-only fast path)
   - Pros: Minimal write lock holding, reads never block
   - Cons: Temporary inconsistency windows, snapshot semantics
   - Risk: Consensus validation window races

**Validation Criteria**:
- [ ] Safety properties hold under Byzantine conditions
- [ ] No deadlock or livelock scenarios
- [ ] Performance improvement measured (target: 3x faster joins)
- [ ] Backward compatibility maintained
- [ ] Test coverage plan exists for edge cases

**Deliverable**: Decision document in ChromaDB with:
- Trade-off analysis
- Safety proof sketch
- Risk assessment
- Implementation roadmap

#### 1.2 Architecture Design & Validation (READY)
**Bead**: Delos-7wt (not yet created)
**Status**: Ready after approach selected

**Activities**:
- Design class hierarchy for chosen approach
- Define synchronization protocol
- Create state machine for lock acquisition
- Design failure recovery path
- Validate membrane between consensus and join

**Deliverable**: Architecture document with:
- Class diagrams
- Sequence diagrams for normal + failure cases
- Lock acquisition protocol
- Byzantine scenario handling

#### 1.3 Test Strategy & Harness (READY)
**Bead**: Delos-7wu (not yet created)
**Status**: Ready after architecture designed

**Test Categories**:
- Concurrent join + consensus scenarios
- Byzantine failure injection
- Lock contention stress tests
- Bootstrap progression validation
- ChurnTest convergence validation

**Deliverable**: Test strategy document with:
- Test categories and scenarios
- BootstrapTest modifications needed
- ChurnTest validation criteria
- Performance regression test suite

---

## Phase 2: Foundation

**Duration**: 1 week
**Dependency**: Phase 1 decisions complete

### Tasks

#### 2.1 Extract Synchronization Coordinator (READY after P1)
**Bead**: Delos-7wv (not yet created)
**Status**: Blocked by Phase 1

**Extract new class**: `SynchronizationCoordinator`
- Encapsulates lock management strategy
- Protocol for consensus vs join coordination
- Byzantine failure handling
- Pluggable implementations (Lock-Free, Separate Locks, Non-Blocking)

**Files Modified**:
- Create: `SynchronizationCoordinator.java` (new)
- Create: `LockFreeCoordinator.java` (strategy impl - if chosen)
- Create: `SeparateLocksCoordinator.java` (strategy impl - if chosen)
- Create: `NonBlockingReadsCoordinator.java` (strategy impl - if chosen)
- Modify: `View.java` (delegated to coordinator)
- Create: `SynchronizationCoordinatorTest.java`

#### 2.2 Create Consensus-Join Membrane (READY after P1)
**Bead**: Delos-7ww (not yet created)
**Status**: Blocked by Phase 1

**Design membrane protocol**:
- Message ordering guarantees
- State snapshot for consensus window
- Join queue management during consensus
- Recovery from interrupted operations

**Files Modified**:
- Create: `ConsensusJoinMembrane.java` (new)
- Modify: `ViewChangeCoordinatorImpl.java` (use membrane)
- Modify: `ViewService.java` (route through membrane)
- Create: `ConsensusJoinMembraneTest.java`

#### 2.3 Establish Test Infrastructure (READY after P1)
**Bead**: Delos-7wx (not yet created)
**Status**: Blocked by Phase 1

**Create test utilities**:
- Lock contention monitoring tools
- Byzantine fault injectors
- Bootstrap progression tracker
- Performance regression baselines

**Files Created**:
- Create: `LockContentionMonitor.java` (test utility)
- Create: `ByzantineFaultInjector.java` (test utility)
- Create: `SynchronizationTestHarness.java` (test utilities)

---

## Phase 3: Implementation

**Duration**: 2 weeks
**Dependency**: Phase 2 complete

### Tasks

#### 3.1 Implement Synchronization Strategy (READY after P2)
**Bead**: Delos-7wy (not yet created)
**Status**: Blocked by Phase 2

**Implementation for chosen approach**:
- Core synchronization logic
- Consensus operation integration
- Join request handling
- Byzantine failure recovery

**Files Modified**:
- Modify: `View.java` (use new coordinator)
- Modify: `ViewManagement.java` (state management)
- Modify: `ViewChangeCoordinatorImpl.java` (integrate with membrane)
- Create: `SynchronizationStrategy.java` (interface)
- Create: `<ChosenStrategy>Impl.java` (implementation)

#### 3.2 Integration Testing (READY after 3.1)
**Bead**: Delos-7wz (not yet created)
**Status**: Blocked by implementation

**Integration scenarios**:
- Consensus + join interleaving
- State consistency validation
- Byzantine member behavior
- Network partition recovery

**Files Modified**:
- Create: `SynchronizationIntegrationTest.java`
- Modify: `ChurnTest.java` (canary test validation)

---

## Phase 4: Testing & Hardening

**Duration**: 1 week
**Dependency**: Phase 3 implementation complete

### Tasks

#### 4.1 Comprehensive Test Suite (READY after P3)
**Bead**: Delos-7x0 (not yet created)
**Status**: Blocked by Phase 3

**Test categories**:
- Byzantine behavior under lock contention
- Resource exhaustion scenarios
- Network partition handling
- State machine consistency

**Test Files**:
- Create: `SynchronizationByzantineTest.java`
- Create: `SynchronizationContentionTest.java`
- Create: `SynchronizationFailureTest.java`
- Modify: `BootstrapTest.java` (validation)
- Modify: `ChurnTest.java` (validation)

#### 4.2 Performance Validation (READY after 4.1)
**Bead**: Delos-7x1 (not yet created)
**Status**: Blocked by Phase 4.1

**Performance criteria**:
- Join latency: < baseline (3-5x improvement target)
- Consensus latency: no regression
- Lock contention: < 10% (measured)
- Memory footprint: no increase

**Validation**:
- Baseline comparison tests
- Load tests (100+ concurrent joins)
- Long-run stability tests (24h+ under churn)

#### 4.3 Stabilization & Merge (READY after 4.2)
**Bead**: Delos-7x2 (not yet created)
**Status**: Blocked by Phase 4.2

**Final validation**:
- All tests pass (unit + integration)
- Code review approved
- No regressions in existing test suite
- Performance metrics meet targets
- Documentation complete

---

## Success Criteria

### Functional Requirements
- [x] Investigation complete with three solutions identified
- [ ] Phase 1: Approach selected and validated
- [ ] Phase 2: Foundation classes extracted and tested
- [ ] Phase 3: New synchronization strategy implemented
- [ ] Phase 4: All tests pass, performance validated

### Quality Gates

**Per Phase**:
- All test cases pass (unit + integration)
- Code review approved
- No regressions in existing functionality
- Performance baseline established

**Final Gate (Merge to Main)**:
- BootstrapTest all 6 methods pass consistently
- ChurnTest completes with full convergence
- No test timeouts or flakes
- Performance meets targets (3-5x faster joins)
- Code maintainability score maintained/improved

### Test Requirements

**BootstrapTest Validation**:
- All 6 bootstrap progression methods pass
- No timeouts or flakes
- Consistent pass rate > 99%

**ChurnTest Validation**:
- Completes within timeout
- Full member convergence
- No Byzantine behavior escapes

**Performance Benchmarks**:
- Baseline join latency measured (current)
- Target join latency: 3-5x improvement
- Regression testing: consensus latency stable

---

## Risk Register (Phase-Specific)

### Phase 1 Risks

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|-----------|
| Approach selection has hidden safety issue | Medium | High | Formal safety proof review by architect |
| Byzantine tolerance not preserved | Medium | Critical | Test Byzantine scenarios early |
| Performance improvement insufficient | Low | Medium | Baseline measure before starting |

### Phase 2-4 Risks

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|-----------|
| Deadlock in new coordination logic | Medium | High | Formal verification of protocols |
| Byzantine attack through new paths | Low | Critical | Security-first code review |
| Performance regression from extraction | Low | Medium | Continuous benchmark comparison |
| Test coverage gaps | Medium | High | Coverage gates at 95%+ critical paths |

---

## Dependencies

### External Dependencies
- Existing test infrastructure (BootstrapTest, ChurnTest)
- View.java and ViewManagement.java stable
- No concurrent refactoring of consensus path

### Internal Dependencies
- Investigation findings (commit 2097442) - **Ready**
- Existing lock contention analysis - **Ready**
- Test infrastructure for synchronization - **To be created in Phase 2**

---

## Team & Responsibilities

### Roles

**Architect**: Design approval for Phase 1 decisions
**Developer**: Implementation (Phases 2-4)
**Test Engineer**: Test strategy and validation
**Code Reviewer**: Quality gate approvals

---

## Communication & Tracking

### Status Updates
- Daily: `bd ready` for unblocked work
- Checkpoint: Each task completion
- Phase completion: EXECUTION_STATE.md update
- Blockers: Documented in EXECUTION_STATE.md

### Escalation Path
- Architecture: java-architect-planner
- Implementation: java-developer
- Test validation: test-validator
- Code review: code-review-expert
- Complex debugging: java-debugger

---

## Next Actions

### Immediate (Today)
1. Review this phase plan
2. Create beads for Phase 1 tasks (Delos-7ws, Delos-7wt, Delos-7wu)
3. Schedule architecture review meeting
4. Begin Phase 1.1 (evaluate synchronization approaches)

### This Week
1. Complete Phase 1.1 (approach evaluation)
2. Architect review and decision
3. Create Phase 1.2 detailed design
4. Complete Phase 1.3 test strategy

### Next Week
1. Begin Phase 2 (Foundation)
2. Extract SynchronizationCoordinator
3. Create ConsensusJoinMembrane
4. Establish test infrastructure

---

## Knowledge Management

### ChromaDB Documents (To Create)

1. **decision::fireflies::synchronization-approach-selected**
   - Final approach decision
   - Trade-off analysis
   - Safety guarantees
   - Implementation plan

2. **pattern::fireflies::consensus-join-coordination**
   - Coordination protocol
   - Message ordering
   - State snapshot semantics
   - Byzantine handling

3. **debug::fireflies::lock-contention-analysis**
   - Root cause deep dive
   - Contention patterns
   - Performance measurements
   - Candidate solutions

### Memory Bank Files (To Create)

1. **Delos_active/synchronization-refactoring.md**
   - Session notes
   - Design hypotheses
   - Active blockers
   - Agent handoffs

---

## References

### Related Issues (Open)
- None (investigation complete, this is new initiative)

### Related Code
- `fireflies/src/main/java/.../View.java` - ~1223 lines
- `fireflies/src/main/java/.../ViewChangeCoordinatorImpl.java` - consensus logic
- `fireflies/src/main/java/.../ViewService.java` - join handler
- `fireflies/src/main/java/.../ViewManagement.java` - membership state

### Investigation Artifacts
- Commit: 2097442 (lock contention analysis)
- Branch: fix/security-tests-p0l-0v5 (current)
- Test files: BootstrapTest.java, ChurnTest.java

---

**Last Updated**: 2026-01-04
**Status**: Planning Phase - Ready for Approval
**Target Start**: 2026-01-04
**Next Review**: Phase 1 completion (Est. 2026-01-11)
