# Phase 1: Architecture Refactoring - Milestones

**Phase**: 1 - Architecture Refactoring
**Duration**: 4 weeks (20 working days)
**Start Date**: After Phase 0 Gate
**Target End**: Phase 0 Gate + 4 weeks

---

## Week 1: Interface Extraction

### Day 1-2: Coverage Baseline & BlockStore

**Objectives**:
- [ ] Establish coverage baseline
- [ ] Complete P1-1 (BlockStore interface extraction)

**Coverage Work**:
- [ ] Run coverage report on CHOAM.java
- [ ] Document current coverage by area
- [ ] Identify critical gaps for P1-3 preparation
- [ ] Begin writing tests for CHOAM core paths

**BlockStore Work**:
- [ ] Define BlockStore interface
- [ ] Write interface tests
- [ ] Create FileBlockStore implementation
- [ ] Begin extraction from CHOAM.java

**Checkpoint**: End of Day 2
- Coverage baseline documented
- BlockStore interface defined
- Initial tests written

### Day 3-4: BlockStore Complete, ConsensusEngine Start

**Objectives**:
- [ ] Complete P1-1 (BlockStore)
- [ ] Start P1-2 (ConsensusEngine)

**BlockStore Deliverables**:
- [ ] Extraction from CHOAM.java complete
- [ ] All interface tests passing
- [ ] Integration with CHOAM verified
- [ ] Code review submitted

**ConsensusEngine Work**:
- [ ] Define ConsensusEngine interface
- [ ] Write interface tests
- [ ] Begin EtherealConsensusEngine implementation

**Checkpoint**: End of Day 4
- P1-1 complete or nearly complete
- ConsensusEngine interface defined

### Day 5: Week 1 Wrap + Weekly Gate

**Objectives**:
- [ ] P1-1 merged
- [ ] P1-2 in progress
- [ ] Weekly gate review

**Weekly Gate Review**:
- Test pass rate: __% (target >95%)
- Critical bugs: ___ (target: 0)
- Schedule status: [on track / behind / ahead]
- Decision: [GO / NO-GO / CONDITIONAL]

**Coverage Progress**:
- [ ] Coverage at __% (target: +5% per week)

---

## Week 2: ConsensusEngine & Decomposition Start

### Day 6-7: ConsensusEngine Complete

**Objectives**:
- [ ] Complete P1-2 (ConsensusEngine)
- [ ] Address any P1-1 review feedback

**ConsensusEngine Deliverables**:
- [ ] Extraction from CHOAM.java complete
- [ ] Ethereal integration maintained
- [ ] All interface tests passing
- [ ] Code review submitted

**Checkpoint**: End of Day 7
- P1-2 complete or nearly complete
- Ready to start P1-3

### Day 8-10: CHOAM Decomposition Phase 1

**Objectives**:
- [ ] Start P1-3 (CHOAM Decomposition)
- [ ] Extract BlockProcessor
- [ ] Begin ViewManager extraction

**Decomposition Work**:
- [ ] Create determinism verification tests FIRST
- [ ] Extract BlockProcessor component
- [ ] Write BlockProcessor tests
- [ ] Begin ViewManager extraction

**Critical**: Run determinism tests after each extraction!

**Checkpoint**: End of Day 10
- Determinism tests created and passing
- BlockProcessor extracted
- ViewManager in progress

### Weekly Gate Review (Day 10)

- Test pass rate: __% (target >95%)
- Determinism tests: [PASS / FAIL]
- Critical bugs: ___ (target: 0)
- Schedule status: [on track / behind / ahead]
- Decision: [GO / NO-GO / CONDITIONAL]

---

## Week 3: Core Decomposition

### Day 11-13: ViewManager, SessionManager

**Objectives**:
- [ ] Complete ViewManager extraction
- [ ] Extract SessionManager
- [ ] Continue coverage expansion (P1-5)

**Decomposition Work**:
- [ ] Complete ViewManager extraction
- [ ] Write ViewManager tests
- [ ] Extract SessionManager component
- [ ] Write SessionManager tests

**Checkpoint**: End of Day 13
- ViewManager complete
- SessionManager complete or nearly complete
- Determinism tests still passing

### Day 14-15: CheckpointManager, CHOAM Refactor Start

**Objectives**:
- [ ] Extract CheckpointManager
- [ ] Begin CHOAM coordinator refactoring

**Decomposition Work**:
- [ ] Extract CheckpointManager component
- [ ] Write CheckpointManager tests
- [ ] Begin refactoring CHOAM to coordinator role
- [ ] Update dependency injection

**Checkpoint**: End of Day 15
- CheckpointManager complete
- CHOAM refactoring in progress
- CHOAM.java approaching ~600 lines

### Weekly Gate Review (Day 15)

- Test pass rate: __% (target >95%)
- Determinism tests: [PASS / FAIL]
- CHOAM.java lines: ___ (target: <700)
- Critical bugs: ___ (target: 0)
- Schedule status: [on track / behind / ahead]
- Decision: [GO / NO-GO / CONDITIONAL]

---

## Week 4: Completion & Polish

### Day 16-17: Inner Class Extraction, CHOAM Final

**Objectives**:
- [ ] Complete CHOAM refactoring
- [ ] Extract identified inner classes
- [ ] CHOAM.java at ~500 lines

**Decomposition Work**:
- [ ] Extract Administration inner class
- [ ] Extract Client inner class
- [ ] Extract Formation inner class
- [ ] Extract Synchronizer inner class
- [ ] Finalize CHOAM coordinator

**Checkpoint**: End of Day 17
- All extractions complete
- CHOAM.java at ~500 lines
- All tests passing

### Day 18: Admission Control (P1-4)

**Objectives**:
- [ ] Implement P1-4 (Admission Control)
- [ ] Integrate with refactored CHOAM

**Admission Control Work**:
- [ ] Define AdmissionController interface
- [ ] Implement TokenBucketAdmissionController
- [ ] Integrate with SessionManager
- [ ] Write admission tests
- [ ] Load test rate limiting

**Checkpoint**: End of Day 18
- Admission control complete
- Rate limiting verified

### Day 19-20: Integration & Gate

**Objectives**:
- [ ] Complete P1-5 (60%+ coverage)
- [ ] Full integration testing
- [ ] Phase 1 Gate

**Integration Testing**:
```bash
# Full build
./mvnw clean install

# Large tests
./mvnw clean install -Dlarge_tests=true

# Ethereal integration
./mvnw test -pl choam,ethereal

# Fireflies integration
./mvnw test -pl choam,fireflies
```

**Final Coverage Push**:
- [ ] Coverage at 60%+
- [ ] Critical paths at 95%+
- [ ] Coverage report generated

**Phase 1 Gate**:
- [ ] All P1-* issues closed
- [ ] All tests passing
- [ ] CHOAM.java ~500 lines
- [ ] Architecture review approved
- [ ] Ready for Phase 2

### Weekly Gate Review (Day 20)

- Test pass rate: __% (target >95%)
- Determinism tests: [PASS / FAIL]
- CHOAM.java lines: ___ (target: ~500)
- Coverage: __% (target: 60%+)
- Critical bugs: ___ (target: 0)
- Decision: [PHASE 1 COMPLETE / EXTEND / ABORT]

---

## Milestone Tracking

| Milestone | Target Day | Actual Day | Status |
|-----------|------------|------------|--------|
| Coverage baseline | 1 | | |
| P1-1 BlockStore interface | 2 | | |
| P1-1 Complete | 4 | | |
| Week 1 Gate | 5 | | |
| P1-2 ConsensusEngine interface | 6 | | |
| P1-2 Complete | 7 | | |
| Determinism tests created | 8 | | |
| BlockProcessor extracted | 9 | | |
| Week 2 Gate | 10 | | |
| ViewManager extracted | 12 | | |
| SessionManager extracted | 13 | | |
| CheckpointManager extracted | 15 | | |
| Week 3 Gate | 15 | | |
| Inner classes extracted | 17 | | |
| CHOAM.java ~500 lines | 17 | | |
| P1-4 Admission control | 18 | | |
| P1-5 Coverage 60%+ | 19 | | |
| **Phase 1 Gate** | 20 | | |

---

## Weekly Summary Template

### Week N Summary

**Completed**:
- [List completed items]

**In Progress**:
- [List in-progress items]

**Metrics**:
- CHOAM.java lines: ___ (started: 1,796)
- Test coverage: ___% (started: 21%)
- Tests written: ___
- Determinism tests: [PASS/FAIL]

**Gate Review**:
- Test pass rate: ___%
- Critical bugs: ___
- Schedule: [on track / behind / ahead]
- Decision: [GO / NO-GO]

**Blockers**:
- [List any blockers]

**Week N+1 Focus**:
- [List planned items]

---

**Milestones Established**: 2026-01-09
**Review Cadence**: Daily standup, weekly gate
