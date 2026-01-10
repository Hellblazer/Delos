# Test Coverage Dashboard

**Project**: CHOAM Security & Architecture Refactoring
**Last Updated**: 2026-01-09
**Current Coverage**: 21%
**Target Coverage**: 60%+

---

## Coverage Summary

| Phase | Start | Target | Actual | Status |
|-------|-------|--------|--------|--------|
| Baseline | 21% | - | 21% | Measured |
| Phase 0 | 21% | 30%+ | | Pending |
| Pre-Phase 1 | 30% | 40%+ | | Pending |
| Phase 1 | 40% | 60%+ | | Pending |
| Phase 2 | 60% | 60%+ | | Pending |

---

## Coverage by Area

### Security Areas (Phase 0 Focus)

| Area | Target | Current | Tests | Status |
|------|--------|---------|-------|--------|
| Transaction Signature | 100% | | | Pending |
| Pending Queue | 95%+ | | | Pending |
| Circuit Breaker | 95%+ | | | Pending |
| Checkpoint Race | 95%+ | | | Pending |

### Architecture Areas (Phase 1 Focus)

| Component | Target | Current | Tests | Status |
|-----------|--------|---------|-------|--------|
| BlockStore | 90%+ | | | Pending |
| ConsensusEngine | 90%+ | | | Pending |
| BlockProcessor | 90%+ | | | Pending |
| ViewManager | 85%+ | | | Pending |
| SessionManager | 85%+ | | | Pending |
| CheckpointManager | 85%+ | | | Pending |
| AdmissionController | 85%+ | | | Pending |
| CHOAM (Coordinator) | 80%+ | | | Pending |

### Test Categories (Phase 2 Focus)

| Category | Target | Current | Status |
|----------|--------|---------|--------|
| Unit Tests | N/A | | |
| Integration Tests | N/A | | |
| Byzantine Tests | 30+ | 0 | Pending |
| Determinism Tests | Pass | | Pending |
| Performance Tests | Baseline | | Pending |

---

## Weekly Coverage Tracking

### Phase 0

| Week | Date | Coverage | Delta | Notes |
|------|------|----------|-------|-------|
| Start | | 21% | - | Baseline |
| Week 1 | | | | |
| Week 2 | | | | |

### Phase 1

| Week | Date | Coverage | Delta | Notes |
|------|------|----------|-------|-------|
| Start | | | | Pre-decomposition |
| Week 1 | | | | |
| Week 2 | | | | |
| Week 3 | | | | |
| Week 4 | | | | |

### Phase 2

| Week | Date | Coverage | Delta | Notes |
|------|------|----------|-------|-------|
| Start | | | | Post-decomposition |
| Week 1 | | | | |
| Week 2 | | | | |
| Week 3 | | | | |

---

## Coverage Commands

### Generate Coverage Report

```bash
# Generate Jacoco report
./mvnw clean test jacoco:report -pl choam

# View report
open choam/target/site/jacoco/index.html
```

### Coverage by Package

```bash
# Detailed coverage by package
./mvnw jacoco:report -pl choam -Djacoco.reportFormat=xml
```

### Coverage Enforcement

```bash
# Fail build if coverage below threshold
./mvnw verify -pl choam -Djacoco.check.lineRatio=0.60
```

---

## Coverage Goals by Phase

### Phase 0: Security Foundation

**Goal**: Cover all security-critical code paths

```
Security paths: 95%+
Error handling: 90%+
Overall: 30%+
```

### Phase 1: Refactoring Safety Net

**Pre-decomposition Goal**: 40%+ on CHOAM.java

```
CHOAM.java: 40%+
New interfaces: 90%+
Overall: 60%+
```

**Rationale**: Need coverage before decomposition to catch regressions

### Phase 2: Comprehensive Testing

**Goal**: Maintain coverage, add Byzantine tests

```
Critical paths: 95%+
Overall: 60%+ (maintain)
Byzantine tests: 30+
```

---

## Coverage Gaps Analysis

### Known Gaps (Pre-Project)

| Area | Current | Gap | Priority |
|------|---------|-----|----------|
| Error paths | Low | High | P0/P1 |
| Edge cases | Low | High | P0/P1 |
| Byzantine scenarios | None | Critical | P2 |
| Concurrency | Low | High | P0 |

### Gap Remediation Plan

1. **Phase 0**: Cover security-critical paths
2. **Pre-Phase 1**: Cover CHOAM.java core paths
3. **Phase 1**: Cover extracted components
4. **Phase 2**: Cover Byzantine scenarios

---

## Test Counts

### By Type

| Type | Count | Status |
|------|-------|--------|
| Unit Tests | | |
| Integration Tests | | |
| Byzantine Tests | 0 | Pending P2 |
| Determinism Tests | 0 | Pending P1 |
| Performance Tests | 0 | Pending P2 |

### By Phase Addition

| Phase | Tests Added | Cumulative |
|-------|-------------|------------|
| Baseline | | |
| Phase 0 | | |
| Phase 1 | | |
| Phase 2 | | |

---

## Coverage Quality Indicators

### Good Coverage Indicators

- [ ] All public methods tested
- [ ] All error paths tested
- [ ] All edge cases tested
- [ ] All integration points tested
- [ ] Byzantine scenarios tested

### Coverage Anti-Patterns to Avoid

- [ ] Tests that don't assert anything
- [ ] Tests that only exercise happy path
- [ ] Tests without meaningful assertions
- [ ] Coverage without quality

---

## Historical Coverage

Track coverage over time:

```
Date        Coverage  Delta   Notes
--------    --------  ------  -----
2026-01-09  21%       -       Baseline
```

---

**Dashboard Established**: 2026-01-09
**Update Frequency**: Weekly during active development
