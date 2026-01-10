# Phase 1: Architecture Refactoring

**Project**: CHOAM Security & Architecture Refactoring
**Phase**: 1 - Architecture Refactoring
**Duration**: 4 weeks
**Issues**: 5 major architecture improvements
**Status**: PLANNED - Starts after Phase 0 Gate

---

## Executive Summary

Phase 1 addresses the architectural decomposition of CHOAM, transforming it from a 1,796-line god object into well-separated, testable components:

1. **P1-1: Extract BlockStore Interface** - Separate storage concerns
2. **P1-2: Extract ConsensusEngine Interface** - Abstract consensus operations
3. **P1-3: Decompose CHOAM God Object** - Break into focused components
4. **P1-4: Implement Admission Control** - Rate limiting framework
5. **P1-5: Expand Test Coverage** - From 21% to 60%+

These improvements enable maintainability, testability, and future enhancements.

---

## Prerequisites

Before starting Phase 1:

- [ ] Phase 0 gate passed
- [ ] All security fixes merged
- [ ] Security review approved
- [ ] CHOAM.java coverage expanded to 40%+ (per critic recommendation)
- [ ] Determinism verification test suite created

---

## Issue Details

### P1-1: Extract BlockStore Interface

**Priority**: High
**Estimated Effort**: 3-4 days
**Dependencies**: Phase 0 complete

**Problem**:
- Block storage logic is interleaved with business logic in CHOAM.java
- Storage cannot be tested independently
- Different storage backends cannot be swapped

**Goal**:
- Clean BlockStore interface
- Testable storage abstraction
- Enable alternative implementations

**Interface Design** (preliminary):
```java
public interface BlockStore {
    void store(Block block);
    Optional<Block> retrieve(Digest hash);
    Optional<Block> getLatest();
    void checkpoint(ULong height);
    boolean contains(Digest hash);
    Stream<Block> getBlocksAfter(ULong height);
}
```

**Implementation Steps**:
1. Define BlockStore interface
2. Write interface tests
3. Extract storage methods from CHOAM.java
4. Create FileBlockStore implementation
5. Wire into CHOAM via constructor injection
6. Verify all existing tests pass

**Success Criteria**:
- [ ] BlockStore interface defined
- [ ] Storage logic extracted from CHOAM.java
- [ ] Interface tests comprehensive
- [ ] All existing tests pass
- [ ] No behavior changes

---

### P1-2: Extract ConsensusEngine Interface

**Priority**: High
**Estimated Effort**: 3-4 days
**Dependencies**: P1-1 complete

**Problem**:
- Consensus logic tightly coupled to CHOAM
- Cannot test consensus independent of state machine
- Ethereal integration hard to mock

**Goal**:
- Clean ConsensusEngine interface
- Testable consensus abstraction
- Enable alternative consensus (future)

**Interface Design** (preliminary):
```java
public interface ConsensusEngine {
    void propose(Transaction transaction);
    void handleBlock(Block block);
    void onViewChange(View newView);
    boolean isLeader();
    ULong getCurrentHeight();
}
```

**Implementation Steps**:
1. Define ConsensusEngine interface
2. Write interface tests
3. Extract consensus methods from CHOAM.java
4. Create EtherealConsensusEngine implementation
5. Wire into CHOAM
6. Verify all existing tests pass

**Success Criteria**:
- [ ] ConsensusEngine interface defined
- [ ] Consensus logic extracted
- [ ] Interface tests comprehensive
- [ ] Ethereal integration maintained
- [ ] No behavior changes

---

### P1-3: Decompose CHOAM God Object

**Priority**: Critical
**Estimated Effort**: 10-13 days (includes buffer)
**Dependencies**: P1-1 and P1-2 complete
**Risk Level**: HIGH

**Problem**:
- CHOAM.java at 1,796 lines is a god object
- Multiple responsibilities mixed together
- Hard to understand, test, and maintain
- Inner classes add ~400 more lines of complexity

**Goal**:
- Reduce CHOAM.java to ~500 lines (coordination only)
- Extract focused component classes
- Clear component boundaries
- Each component independently testable

**Decomposition Plan**:

| Component | Responsibility | Est. Lines | Status |
|-----------|---------------|------------|--------|
| BlockProcessor | Block handling, validation | ~300 | Extract |
| ViewManager | View changes, membership | ~250 | Extract |
| SessionManager | Transaction sessions | ~200 | Extract |
| CheckpointManager | Checkpoint operations | ~200 | Extract |
| Coordinator (CHOAM) | Orchestration only | ~500 | Refactor |

**Inner Class Handling** (per critic recommendation):

| Inner Class | Decision | Rationale |
|-------------|----------|-----------|
| Administration | Extract to top-level | Independent lifecycle |
| Associate | Keep inner | Tightly coupled to CHOAM state |
| Client | Extract to top-level | Could be reused |
| Formation | Extract to top-level | Complex enough for isolation |
| Synchronizer | Extract to top-level | Independent concerns |
| Combiner | Keep inner | Simple, state-coupled |
| Trampoline | Keep inner | Control flow only |
| TransSubmission | Keep inner | Simple DTO |

**Implementation Steps**:
1. Create comprehensive test suite first (40%+ coverage)
2. Extract BlockProcessor (Days 1-2)
3. Extract ViewManager (Days 3-4)
4. Extract SessionManager (Days 5-6)
5. Extract CheckpointManager (Days 7-8)
6. Refactor CHOAM to coordinator (Days 9-10)
7. Extract identified inner classes (Days 11-13)
8. Verify all tests pass

**Determinism Test Requirement** (CRITICAL):
```java
@Test
void shouldProduceDeterministicStateAcrossNodes() {
    // Create 4 identical CHOAM instances
    // Submit identical transactions
    // Verify byte-identical state hashes
}
```

**Success Criteria**:
- [ ] CHOAM.java ~500 lines
- [ ] All components extracted
- [ ] Determinism tests passing
- [ ] All existing tests pass
- [ ] No behavior changes
- [ ] Architecture review approved

---

### P1-4: Implement Admission Control

**Priority**: Medium
**Estimated Effort**: 3-4 days
**Dependencies**: P1-3 complete

**Problem**:
- No rate limiting on transaction submission
- DoS possible through transaction flooding
- No admission control framework

**Goal**:
- Admission control framework
- Configurable rate limits
- Backpressure mechanism

**Design** (preliminary):
```java
public interface AdmissionController {
    AdmissionResult admit(Transaction tx);
    void release(Transaction tx);
    AdmissionStats getStats();
}

public enum AdmissionResult {
    ADMITTED,
    RATE_LIMITED,
    QUEUE_FULL,
    REJECTED
}
```

**Implementation Steps**:
1. Define AdmissionController interface
2. Implement TokenBucketAdmissionController
3. Integrate with CHOAM submit path
4. Add configuration parameters
5. Create admission tests
6. Load test to verify limits

**Success Criteria**:
- [ ] Admission control framework in place
- [ ] Rate limiting functional
- [ ] Configurable via Parameters
- [ ] Load tests pass
- [ ] No regressions

---

### P1-5: Expand Test Coverage

**Priority**: High
**Estimated Effort**: 5-7 days (throughout phase)
**Dependencies**: Runs in parallel with other work

**Problem**:
- Current coverage: 21%
- Target coverage: 60%+
- Many code paths untested

**Goal**:
- 60%+ overall coverage
- Critical paths at 95%+
- Error paths comprehensively tested

**Coverage Strategy**:

| Priority | Area | Target | Current |
|----------|------|--------|---------|
| 1 | Transaction submission | 95% | TBD |
| 2 | Block processing | 95% | TBD |
| 3 | View changes | 90% | TBD |
| 4 | Checkpointing | 90% | TBD |
| 5 | Error handling | 90% | TBD |
| 6 | General code | 60% | 21% |

**Test Types to Add**:
- Unit tests for extracted components
- Integration tests for component interaction
- Byzantine tests for fault tolerance
- Load tests for performance

**Success Criteria**:
- [ ] 60%+ overall coverage
- [ ] Critical paths at 95%
- [ ] Coverage report documented
- [ ] No coverage regression during Phase 2

---

## Execution Plan

### Dependency Graph

```
Phase 0 Complete
       |
       v
P1-5 (Coverage) -----> Throughout Phase
       |
       v
P1-1 (BlockStore)
       |
       v
P1-2 (ConsensusEngine)
       |
       v
P1-3 (CHOAM Decomposition) [10-13 days, HIGH RISK]
       |
       v
P1-4 (Admission Control)
       |
       v
Phase 1 Gate
```

### Weekly Schedule

**Week 1** (Days 1-5):
- Complete coverage baseline assessment
- P1-1: Extract BlockStore
- Begin P1-2: Extract ConsensusEngine
- Parallel: Add tests (P1-5)

**Week 2** (Days 6-10):
- Complete P1-2
- Begin P1-3: CHOAM Decomposition
- Extract BlockProcessor, ViewManager
- Parallel: Add tests (P1-5)

**Week 3** (Days 11-15):
- Continue P1-3
- Extract SessionManager, CheckpointManager
- Refactor CHOAM to coordinator
- Parallel: Add tests (P1-5)

**Week 4** (Days 16-20):
- Complete P1-3 (inner classes)
- P1-4: Admission Control
- Final coverage push (P1-5)
- Integration testing
- Phase 1 Gate

### Weekly Gate Reviews (per critic recommendation)

**Weekly Review Criteria**:
- Test pass rate >95%
- No critical bugs open
- Schedule on track (or documented variance)
- Go/no-go decision

---

## Rollback Procedure (per critic recommendation)

### Abort Criteria
- >3 days blocked on single issue
- Critical bug discovered in decomposition
- Determinism test failure
- Test pass rate <90%

### Rollback Process
1. Decision authority: Tech lead (24-hour notification)
2. Git strategy: Feature branch per component
3. Rollback: Revert feature branch, keep tests
4. Partial success: May keep completed extractions

### Git Branch Strategy
```
main
  |
  +-- feature/p1-1-block-store
  +-- feature/p1-2-consensus-engine
  +-- feature/p1-3-choam-decomposition
      |
      +-- p1-3a-block-processor
      +-- p1-3b-view-manager
      +-- p1-3c-session-manager
      +-- p1-3d-checkpoint-manager
  +-- feature/p1-4-admission-control
```

---

## Testing Strategy

### Determinism Verification (CRITICAL)

Before decomposition begins:
```java
@Test
void shouldProduceDeterministicStateWithIdenticalInput() {
    // Setup: Create 4 CHOAM instances
    List<CHOAM> nodes = createNodes(4);

    // Execute: Submit identical transactions
    List<Transaction> transactions = generateTransactions(100);
    for (Transaction tx : transactions) {
        for (CHOAM node : nodes) {
            node.submit(tx);
        }
    }

    // Wait for consensus
    waitForConsensus(nodes);

    // Verify: All nodes have identical state
    Digest expectedHash = nodes.get(0).getStateHash();
    for (CHOAM node : nodes) {
        assertThat(node.getStateHash()).isEqualTo(expectedHash);
    }
}
```

### Regression Testing

After each extraction:
```bash
# Full test suite
./mvnw test -pl choam

# Integration tests
./mvnw test -pl choam,ethereal,fireflies

# Large tests
./mvnw clean install -Dlarge_tests=true
```

### Coverage Verification

After each component:
```bash
# Generate coverage report
./mvnw jacoco:report -pl choam

# Verify threshold
# Target: 60%+ overall, 95%+ for extracted component
```

---

## Success Gate Criteria

### Code Completion Gate

| Issue | Interface | Implementation | Tests | Merged |
|-------|-----------|----------------|-------|--------|
| P1-1: BlockStore | [ ] | [ ] | [ ] | [ ] |
| P1-2: ConsensusEngine | [ ] | [ ] | [ ] | [ ] |
| P1-3: Decomposition | [ ] | [ ] | [ ] | [ ] |
| P1-4: Admission Control | [ ] | [ ] | [ ] | [ ] |
| P1-5: Coverage 60%+ | - | - | [ ] | [ ] |

### Metrics Gate

| Metric | Target | Actual | Met |
|--------|--------|--------|-----|
| CHOAM.java lines | ~500 | | [ ] |
| Test coverage | 60%+ | | [ ] |
| Critical path coverage | 95%+ | | [ ] |
| Determinism tests | Pass | | [ ] |
| All tests | Pass | | [ ] |

### Architecture Gate

- [ ] Clean component boundaries
- [ ] No circular dependencies
- [ ] All interfaces well-defined
- [ ] Dependency injection working
- [ ] Architecture review approved

### Integration Gate

- [ ] Ethereal integration verified
- [ ] Fireflies integration verified
- [ ] SQL-State integration verified
- [ ] Full build passing

---

## Risk Management

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Decomposition complexity | High | High | Buffer time, incremental extraction |
| Hidden dependencies | Medium | High | Thorough analysis, good tests |
| Determinism regression | Low | Critical | Determinism tests mandatory |
| Schedule overrun | Medium | Medium | 30% buffer built in |
| Integration breakage | Low | High | Continuous integration testing |

---

## Knowledge Management

### Store in ChromaDB

After each extraction:
```
decision::choam::p1-[N]-[component]

- Why this interface design?
- What tradeoffs were made?
- How does it integrate?
- What tests validate it?
```

### Architecture Documentation

Update `.pm/KNOWLEDGE/ARCHITECTURE.md` with:
- Component diagram
- Interface contracts
- Dependency relationships
- Injection patterns

---

**Phase 1 Planned**: 2026-01-09
**Duration Target**: 4 weeks
**Status**: Planned - starts after Phase 0 gate
