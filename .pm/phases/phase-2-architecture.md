# Phase 2: Architecture & Refactoring

**Target Completion**: 2026-02-15
**Priority**: MEDIUM - Structural improvements
**Status**: PLANNED - Starts after Phase 1 completion

## Overview

Phase 2 addresses 4 architecture and refactoring items that improve code maintainability, testing, and future extensibility.

## Architecture Issues

### Issue 1: Code Organization and Modularity
**Bead**: fireflies-p2-001
**Severity**: MEDIUM (P2)
**Dependency**: Phase 0-1 completion

**Problem**:
- Mixed concerns in main classes
- Difficult to extend functionality
- Test dependencies tangled

**Approach**:
1. Separate concerns (state machine, networking, consensus)
2. Improve interface boundaries
3. Reduce circular dependencies
4. Enable independent testing

---

### Issue 2: Dependency Injection Patterns
**Bead**: fireflies-p2-002
**Severity**: MEDIUM (P2)

**Problem**:
- Hard-coded dependencies make testing difficult
- Configuration not centralized
- Difficult to swap implementations

**Approach**:
1. Introduce dependency injection
2. Centralize configuration
3. Enable testing with mocks
4. Improve extensibility

---

### Issue 3: Testing Infrastructure Improvements
**Bead**: fireflies-p2-003
**Severity**: MEDIUM (P2)

**Problem**:
- Test fixtures duplicated across tests
- Byzantine scenario setup complex
- No standard test harness

**Approach**:
1. Create reusable test fixtures
2. Build Byzantine scenario builder
3. Standardize test setup/teardown
4. Improve test maintainability

---

### Issue 4: Performance Optimization
**Bead**: fireflies-p2-004
**Severity**: MEDIUM (P2)

**Problem**:
- Consensus latency higher than target
- Memory usage under stress
- GC pause time impacting consensus

**Approach**:
1. Profile consensus path
2. Optimize hot spots
3. Reduce allocations
4. Verify latency targets

---

## Success Criteria

- [ ] Code organization improved
- [ ] Dependency injection implemented
- [ ] Test infrastructure standardized
- [ ] Performance targets achieved
- [ ] No regressions in functionality

## Timeline

| Week | Focus |
|------|-------|
| 1 | Code organization, DI patterns |
| 1 | Testing infrastructure |
| 2 | Performance optimization |
| 2 | Integration and validation |

---

**Phase Planned**: 2026-01-01
**Status**: Ready after Phase 1 completion
