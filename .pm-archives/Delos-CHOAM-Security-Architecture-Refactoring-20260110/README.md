# CHOAM Security & Architecture Refactoring

**Project**: CHOAM Security & Architecture Refactoring
**Module**: choam (with fireflies, ethereal integration)
**Scope**: Security fixes + Architecture decomposition + Enhancement
**Status**: PLANNED - Ready for Phase 0 Implementation
**Timeline**: 6-8 weeks (3 phases)
**Success Criteria**: All security vulnerabilities fixed, CHOAM.java decomposed (1,796 -> ~500 lines), 60%+ test coverage, production ready

---

## Executive Summary

CHOAM (Committee-based Highly Available Object Management) is the replicated state machine engine in Delos, implementing Byzantine fault tolerant consensus via Ethereal (Aleph-BFT). The module is production-critical and must be hardened and decomposed before deployment.

This project addresses:
- **4 critical security vulnerabilities** blocking production (Phase 0)
- **5 major architecture improvements** decomposing CHOAM god object (Phase 1)
- **5 enhancement tasks** for byzantine testing and optimization (Phase 2)

### Key Constraints

- Depends on Ethereal for Aleph-BFT consensus
- Depends on Fireflies for Byzantine membership services
- Security-critical path (transaction signatures, checkpoints)
- Byzantine fault tolerance requirements (3f+1 tolerance)
- Production deployment gate

---

## Project Structure

### CHOAM Module

**Main Components**:
- `CHOAM.java` - Primary service implementation (1,796 lines - god object)
- `Parameters.java` - Configuration and initialization
- `Session.java` - Transaction session management
- `ViewAssembly.java` - View change coordination

**Communication Layer**:
- `comm/Terminal.java` - GRPC terminal connections
- `comm/Concierge.java` - Service request handling

**Current Statistics**:
- Lines: ~4,500 across module
- Test Coverage: 21% (target: 60%+)
- Test Files: 8 test classes

### Dependencies

```
CHOAM
+-- Ethereal (Aleph-BFT consensus)
+-- Fireflies (BFT membership service)
+-- gRPC (services)
+-- Cryptography (signatures, digests)
+-- SQL-State (JDBC state machines)
```

---

## Phase Structure

### Phase 0: Security Fixes (2 weeks)

**4 critical vulnerabilities** blocking production deployment:

1. **P0-1: Transaction Signature Validation** - Ensure all transactions are cryptographically validated
2. **P0-2: Pending Queue Bounds** - Verify pending queue cannot be exploited for DoS
3. **P0-3: Sync Circuit Breaker** - Implement circuit breaker for sync operations
4. **P0-4: Checkpoint Validation Race** - Fix race condition in checkpoint validation

**Success Criteria**:
- All vulnerabilities fixed with test-driven development
- Code review approved by security team
- No regressions in Ethereal/Fireflies integration
- Baseline security metrics established

### Phase 1: Architecture Refactoring (4 weeks)

**5 architectural improvements** for maintainability:

1. **Extract BlockStore Interface** - Separate storage concerns
2. **Extract ConsensusEngine Interface** - Abstract consensus operations
3. **Decompose CHOAM God Object** - Break into focused components
4. **Implement Admission Control** - Rate limiting framework
5. **Expand Test Coverage** - From 21% to 60%+

**Success Criteria**:
- CHOAM.java reduced from 1,796 to ~500 lines
- Clean separation of concerns
- No state machine violations
- All error paths tested
- Design review approved

### Phase 2: Enhancement (2-3 weeks)

**5 enhancement tasks** across:

1. **Byzantine Failure Test Suite** - Comprehensive BFT testing
2. **Code Quality Improvements** - Reduce complexity
3. **Rate Limiting Framework** - Admission control implementation
4. **Performance Benchmarking** - Baseline and optimization
5. **Security Audit** - External review preparation

**Success Criteria**:
- Byzantine tests comprehensive
- Security audit ready
- Performance baselines documented
- Production readiness checklist complete

---

## Working Model

### Test-First Development

All work follows Test-First Development (TFD):

1. **Write test first** - Demonstrate the issue or desired behavior
2. **Implement fix** - Make the test pass
3. **Run full suite** - Ensure no regressions
4. **Code review** - Peer review before merge
5. **Integration test** - Validate with Ethereal/Fireflies
6. **Close bead** - Track in beads system

### Parallel Execution

**Independent work runs in parallel**:
- Phase 0 security issues can be fixed in parallel (with dependency tracking)
- Code review runs while next issue is being developed
- Test-validator runs concurrently on completed features

### Agent Coordination

**Agents used throughout**:
- **java-developer** - Implementation work
- **java-debugger** - Test failures or complex bugs
- **code-review-expert** - Code quality validation
- **test-validator** - Test coverage and quality
- **java-architect-planner** - Design decisions and architecture
- **strategic-planner** - Phase planning and prioritization

### Knowledge Management

**Three-tier knowledge storage**:

1. **Beads** (Task Tracking)
   - All work tracked in beads system
   - Dependencies explicitly modeled
   - Status updates as work progresses

2. **ChromaDB** (Permanent Knowledge)
   - Design decisions with rationale
   - Security findings and mitigations
   - Architecture patterns and tradeoffs
   - Research findings on dependencies

3. **Memory Bank** (Session State)
   - Active phase work state
   - Hypotheses under investigation
   - Blocker analysis and workarounds
   - Cross-agent handoff information

---

## Critical Paths

### Security Path (Phase 0)

```
Transaction Signature --> Pending Queue --> Circuit Breaker --> Checkpoint Race
       (P0-1)               (P0-2)            (P0-3)             (P0-4)
          |                    |                  |
    Signature Tests      Bounds Tests      Breaker Tests
```

**Gate**: All four issues fixed and approved by security review

### Architecture Path (Phase 1)

```
BlockStore --> ConsensusEngine --> CHOAM Decomposition --> Admission Control
                                         |
                                  Test Coverage 60%+
```

**Gate**: Design review approved, all tests passing, coverage achieved

### Enhancement Path (Phase 2)

```
Byzantine Tests --> Code Quality --> Rate Limiting --> Performance --> Audit
```

**Gate**: Production readiness checklist complete

---

## Integration Points

### Ethereal Integration

CHOAM uses Ethereal for:
- Aleph-BFT atomic broadcast
- Consensus ordering
- Block production

**Test Strategy**:
- Unit tests with mocked Ethereal
- Integration tests with real Ethereal instance
- Byzantine chaos tests

### Fireflies Integration

CHOAM uses Fireflies for:
- Byzantine membership service
- View change coordination
- Failure detection

**Test Strategy**:
- Unit tests with mocked Fireflies
- Byzantine chaos tests
- Network partition tests

---

## Risk Management

### High-Risk Areas

1. **Transaction Signatures** - Must be bulletproof
2. **Byzantine Fault Tolerance** - State consistency under failures
3. **CHOAM Decomposition** - Refactoring without regressions
4. **Dependency Updates** - Changes to Ethereal/Fireflies impact

### Mitigation Strategies

- **Signatures**: Extensive test coverage, external validation
- **Byzantine**: Property-based testing, chaos testing
- **Refactoring**: Comprehensive tests before refactoring
- **Dependencies**: Integration tests, compatibility matrix

---

## Success Metrics

### Quality Metrics

| Metric | Current | Target | Validation |
|--------|---------|--------|-----------|
| Code Coverage (Overall) | 21% | 60%+ | test-validator |
| CHOAM.java Lines | 1,796 | ~500 | code-review |
| Security Issues | 4 | 0 | security audit |
| Design Issues | 5 | 0 | architecture review |

### Performance Metrics

| Metric | Baseline | Target | Validation |
|--------|----------|--------|-----------|
| Transaction Throughput | TBD | Maintain | perf tests |
| Block Time | TBD | Maintain | perf tests |
| Memory (n=4 nodes) | TBD | <500MB | stress tests |

### Process Metrics

| Metric | Target | Validation |
|--------|--------|-----------|
| Test First | 100% | checkpoint review |
| Code Review | 100% | bead completion |
| Integration Test | 100% | CI passing |

---

## Team Coordination

### Role Definitions

- **Lead Engineer**: Overall implementation, P0 security fixes
- **Architect**: Phase 1 decomposition design, review
- **Test Specialist**: Coverage expansion, Byzantine tests

### Daily Standup

```
- What issues did you close?
- What are you working on?
- Any blockers or risks?
- Any architectural questions?
```

### Gate Review

Before moving to next phase:
1. All beads for current phase closed
2. Code review approved
3. Test coverage acceptable
4. Integration tests passing
5. Risk register updated

---

## Bead System

All work tracked in beads system with explicit dependencies:

```bash
# See ready work
bd ready

# Get issue details
bd show Delos-XXXX

# Start work
bd update Delos-XXXX --status in_progress

# Complete work
bd close Delos-XXXX

# Add dependency
bd dep add Delos-A Delos-B  # A depends on B
```

### Existing Bead Structure

**Epic**: Delos-disp (CHOAM Security & Architecture Refactoring)

**Phase 0 Beads** (already created):
- Delos-ki6t: P0-1 Transaction Signature Validation
- Delos-k0bz: P0-2 Pending Queue Bounds Verification
- Delos-tm53: P0-3 Synchronization Circuit Breaker
- Delos-vud5: P0-4 Checkpoint Validation Race Fix

---

## File Navigation

See **INDEX.md** for complete file guide.

Key files:
- `00-START-HERE.md` - Quick orientation (create this for new team members)
- `CONTINUATION.md` - Resume guide
- `EXECUTION_STATE.md` - Current metrics (JSON)
- `METHODOLOGY.md` - Engineering discipline
- `PHASE_0/PLAN.md` - Phase 0 details
- `PHASE_1/PLAN.md` - Phase 1 details
- `PHASE_2/PLAN.md` - Phase 2 details
- `METRICS/RISKS.md` - Risk assessment
- `AGENT_INSTRUCTIONS.md` - Delegation

---

## Next Actions

1. **Read CONTINUATION.md** - Understand current state
2. **Review PHASE_0/PLAN.md** - Understand scope
3. **Run `bd ready`** - See what's unblocked
4. **Pick first issue** - Start with P0-1 (Transaction Signature)
5. **Create test** - Test-first development
6. **Iterate** - Fix, test, review, integrate

---

**Project Established**: 2026-01-09
**Target Completion**: 2026-02-28
**Status**: READY FOR PHASE 0
