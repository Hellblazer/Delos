# Delos Architecture Remediation Plan

## Executive Summary

This comprehensive remediation plan addresses critical issues identified in the architectural critique stored in ChromaDB at `critique::architecture::delos-comprehensive-2025-12-31`. The plan covers 35 tasks across 4 phases, with a focus on security, availability, reliability, and maintainability improvements.

**Epic**: `Delos-u9e` - Delos Comprehensive Remediation Phase 2
**Source**: Architectural critique dated 2025-12-31
**Status**: Beads created, dependencies pending

---

## Issue Summary from Critique

### Critical Issues (6)
| Issue | Location | Status |
|-------|----------|--------|
| CHOAM God Object (1739 lines) | CHOAM.java | Phase 3 tasks created |
| Domain complexity | Domain.java | Phase 3: Delos-ct6 |
| Leaky abstractions (SQLException) | Oracle.java | Phase 3: Delos-lk5 |
| Missing security (content-change) | Oracle.java:110 | DONE in Phase 1 |
| Unbounded pending queue | CHOAM.java:91 | Phase 0: Delos-yl3 |
| No circuit breaker | CHOAM.java:1203-1233 | Phase 3: Delos-o7w |

### High Priority Items (4)
| Issue | Status |
|-------|--------|
| CHOAM refactoring | Phase 3: 5 extraction tasks |
| Domain Builder | Phase 3: Delos-ct6 |
| StateExecutor interfaces | Phase 3: Delos-yxv |
| Committee refactoring | Addressed via CHOAM decomposition |

### Medium Priority Items (4)
| Issue | Status |
|-------|--------|
| OracleException abstraction | Phase 3: Delos-lk5 |
| Watch API | DONE in Phase 1 |
| Replay prevention | Phase 1: Delos-mqv |
| Rate limiting | Phase 1: Delos-amc |

---

## Phase 0: Critical Issues (P0 - IMMEDIATE)

**Target**: Week 1-2
**Status**: All tasks created, no blocking dependencies
**Parallelization**: All 10 tasks can run in parallel (different modules)

### CHOAM Race Conditions

| Bead ID | Title | Module | Lines |
|---------|-------|--------|-------|
| Delos-qq9 | Fix CHOAM block processing race | choam | 543-602 |
| Delos-0ct | Fix CHOAM view change linearization | choam | 754-793 |
| Delos-an5 | Fix CHOAM silent consumer thread failure | choam | 604-627 |
| Delos-yl3 | Add bounded pending block queue | choam | 91 |

### Ethereal Security Stubs

| Bead ID | Title | Module | Lines |
|---------|-------|--------|-------|
| Delos-jdc | Implement validate(SignedCommit) | ethereal | 772-775 |
| Delos-m1e | Implement validate(SignedPreVote) | ethereal | 777-780 |

**CRITICAL SECURITY**: Both methods return `true` unconditionally, compromising Byzantine fault tolerance.

### Stereotomy/KERI Vulnerabilities

| Bead ID | Title | Module |
|---------|-------|--------|
| Delos-rsj | Implement KeyEventProcessor authentication | stereotomy |
| Delos-mot | Fix StereotomyImpl key rotation race | stereotomy |

### Memberships and Model

| Bead ID | Title | Module |
|---------|-------|--------|
| Delos-gph | Fix DynamicContextImpl predecessors null | memberships |
| Delos-9qr | Fix JniBridge.stop() calling start() | model |

---

## Phase 1: Security Hardening (P1)

**Target**: Week 3-4
**Status**: Tasks created, some have blocking dependencies

| Bead ID | Title | Blocked By |
|---------|-------|------------|
| Delos-bfb | Implement Gorgoneion replay protection | Delos-rsj |
| Delos-fph | Fix weak default verifier | - |
| Delos-nrp | Fix empty cert validator leak | - |
| Delos-amc | Fix streaming RPC rate limiting | - |
| Delos-0b4 | Secure SQL-State Script compilation | - |
| Delos-mqv | Implement transaction replay prevention | Delos-qq9, 0ct, an5, yl3 |
| Delos-9um | Implement certificate revocation | - |

---

## Phase 2: Correctness Fixes (P1-P2)

**Target**: Week 5-6
**Status**: Tasks created, some have blocking dependencies

| Bead ID | Title | Blocked By |
|---------|-------|------------|
| Delos-2dh | Fix Thoth KeyInterval predicate | - |
| Delos-ozj | Add soft-delete filters to Delphinius | - |
| Delos-30j | Complete temporal query primitives | - |
| Delos-g0p | Add witness receipt bounds checking | Delos-rsj |
| Delos-ois | Fix Tron getCurrentState thread safety | - |
| Delos-yrv | Fix Fireflies View lifecycle race | - |
| Delos-5rz | Re-enable Ethereal fork tests | Delos-jdc, m1e |

---

## Phase 3: Architecture Refactoring (P2-P3)

**Target**: Week 7-12
**Status**: Tasks created with sequential dependencies

### CHOAM Decomposition Chain

```
Delos-z6p (BlockProducer)
    <- Delos-qq9, 0ct, an5, yl3 (CHOAM fixes)
    |
    v
Delos-l8j (ConsensusCoordinator)
    |
    +-- Delos-jec (ViewManager)
    |       |
    |       v
    |   Delos-8a9 (SynchronizationProtocol)
    |       |
    |       v
    |   Delos-o7w (Circuit Breaker)
    |
    +-- Delos-4cw (TransactionRouter)
```

| Bead ID | Title | Blocked By |
|---------|-------|------------|
| Delos-z6p | Extract BlockProducer | Delos-qq9, 0ct, an5, yl3 |
| Delos-l8j | Extract ConsensusCoordinator | Delos-z6p |
| Delos-jec | Extract ViewManager | Delos-l8j |
| Delos-4cw | Extract TransactionRouter | Delos-l8j |
| Delos-8a9 | Extract SynchronizationProtocol | Delos-jec |
| Delos-o7w | Add circuit breaker | Delos-8a9 |

### Interface Abstractions (Parallel)

| Bead ID | Title | Blocked By |
|---------|-------|------------|
| Delos-lk5 | Replace SQLException with OracleException | - |
| Delos-yxv | Add StateExecutor abstraction | Delos-z6p |
| Delos-ct6 | Add Domain Builder pattern | - |
| Delos-7mt | Abstract X509 from Member interface | - |
| Delos-rjw | Fix reflection method ordering | - |

---

## Dependency Graph

```
PHASE 0 (All Parallel - No Dependencies)
+-- Delos-qq9 (CHOAM block race) -------+
+-- Delos-0ct (CHOAM view linear) ------+---> Delos-mqv (Tx replay prevention)
+-- Delos-an5 (CHOAM consumer) ---------+         |
+-- Delos-yl3 (CHOAM queue) ------------+---> Delos-z6p (BlockProducer)
                                                   |
+-- Delos-jdc (Ethereal validate) ------+         v
+-- Delos-m1e (Ethereal validate) ------+---> Delos-5rz (Fork tests)
                                                   |
+-- Delos-rsj (KERI auth) --------------+---> Delos-bfb (Gorgoneion replay)
         |                                         |
         +-----------------------------------> Delos-g0p (Witness bounds)

+-- Delos-mot (KERI rotation race)
+-- Delos-gph (predecessors null)
+-- Delos-9qr (JniBridge stop)

PHASE 3 CHOAM CHAIN:
Delos-z6p -> Delos-l8j -> Delos-jec -> Delos-8a9 -> Delos-o7w
                    |
                    +---> Delos-4cw
                    |
                    +---> Delos-yxv (StateExecutor)
```

### Critical Path

The longest dependency chain determining minimum remediation time:

```
CHOAM fixes (qq9,0ct,an5,yl3) -> z6p -> l8j -> jec -> 8a9 -> o7w
```

**Critical Path Estimate**: 8-10 weeks for CHOAM refactor chain

---

## Dependency Setup Commands

Execute these commands to establish bead dependencies:

```bash
# Phase 1 Dependencies
bd dep add Delos-bfb Delos-rsj              # Gorgoneion replay <- KERI auth
bd dep add Delos-mqv Delos-qq9              # Tx replay <- CHOAM block race
bd dep add Delos-mqv Delos-0ct              # Tx replay <- CHOAM view
bd dep add Delos-mqv Delos-an5              # Tx replay <- CHOAM consumer
bd dep add Delos-mqv Delos-yl3              # Tx replay <- CHOAM queue

# Phase 2 Dependencies
bd dep add Delos-g0p Delos-rsj              # Witness bounds <- KERI auth
bd dep add Delos-5rz Delos-jdc              # Fork tests <- Ethereal validate
bd dep add Delos-5rz Delos-m1e              # Fork tests <- Ethereal validate

# Phase 3 CHOAM Chain Dependencies
bd dep add Delos-z6p Delos-qq9              # BlockProducer <- CHOAM fixes
bd dep add Delos-z6p Delos-0ct
bd dep add Delos-z6p Delos-an5
bd dep add Delos-z6p Delos-yl3
bd dep add Delos-l8j Delos-z6p              # ConsensusCoordinator <- BlockProducer
bd dep add Delos-jec Delos-l8j              # ViewManager <- ConsensusCoordinator
bd dep add Delos-4cw Delos-l8j              # TransactionRouter <- ConsensusCoordinator
bd dep add Delos-8a9 Delos-jec              # SyncProtocol <- ViewManager
bd dep add Delos-o7w Delos-8a9              # Circuit breaker <- SyncProtocol
bd dep add Delos-yxv Delos-z6p              # StateExecutor <- BlockProducer
```

---

## Risk Assessment

### Critical Risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Ethereal validation stubs exploited | HIGH | CRITICAL | Immediate Phase 0 priority |
| CHOAM race causes consensus fork | MEDIUM | CRITICAL | Comprehensive testing |
| JniBridge resource leak in prod | HIGH | HIGH | Simple fix, immediate deploy |

### High Risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| CHOAM refactor introduces bugs | MEDIUM | HIGH | Incremental extraction, extensive testing |
| Script execution RCE exploited | LOW | CRITICAL | Sandbox before production use |
| Transaction replay attacks | MEDIUM | HIGH | Phase 1 priority |

---

## Parallel Execution Opportunities

### Maximum Parallelism Points

1. **Phase 0**: All 10 critical fixes can run in parallel (different modules)
2. **Phase 1**: 5 of 7 security tasks can run in parallel
3. **Phase 2**: 6 of 7 correctness tasks can run in parallel
4. **Phase 3**: CHOAM chain is sequential; 5 other tasks are parallel

### Suggested Agent Allocation

| Track | Focus | Beads |
|-------|-------|-------|
| Track A | CHOAM | qq9, 0ct, an5, yl3, z6p, l8j, jec, 4cw, 8a9, o7w |
| Track B | Ethereal | jdc, m1e, 5rz |
| Track C | Stereotomy/KERI | rsj, mot, g0p |
| Track D | Security | bfb, fph, nrp, amc, 0b4, mqv, 9um |
| Track E | Misc | gph, 9qr, 2dh, ozj, 30j, ois, yrv, lk5, yxv, ct6, 7mt, rjw |

---

## TDD Requirements

Every task must follow:

1. **Write failing test** demonstrating the bug or missing feature
2. **Verify test fails** for the right reason
3. **Implement the fix**
4. **Verify test passes**
5. **Run full module test suite**
6. **Submit for code review**

### Security Fixes Additional Requirements

- Document threat model before implementation
- Write attack test demonstrating vulnerability (if safe)
- Verify fix blocks attack vector
- Extra scrutiny from substantive-critic agent

---

## Related Documents

### ChromaDB
- `critique::architecture::delos-comprehensive-2025-12-31` - Source critique
- `plan::remediation::delos-architecture-2025-12-31` - This plan summary

### PM Infrastructure
- `.pm/EXECUTION_STATE.md` - Progress tracking
- `.pm/CONTINUATION.md` - Session context
- `.pm/METHODOLOGY.md` - Engineering standards
- `.pm/AGENT_INSTRUCTIONS.md` - Agent workflow
- `.pm/designs/CHOAM_DECOMPOSITION.md` - CHOAM design details

---

## Approval and Audit

**Created**: 2025-12-31
**Author**: Strategic Planner Agent
**Status**: PENDING plan-auditor review

### Audit Checklist

- [ ] All issues from architectural critique addressed
- [ ] Dependencies properly ordered
- [ ] TDD compliance embedded
- [ ] Security issues prioritized in Phase 0
- [ ] Risks identified with mitigations
- [ ] Parallel execution opportunities maximized

---

## Bead Quick Reference

### Phase 0 (P0 - Critical)
| ID | Short Name |
|----|------------|
| Delos-qq9 | CHOAM block race |
| Delos-0ct | CHOAM view linear |
| Delos-an5 | CHOAM consumer |
| Delos-yl3 | CHOAM bounded queue |
| Delos-jdc | Ethereal SignedCommit |
| Delos-m1e | Ethereal SignedPreVote |
| Delos-rsj | KERI auth |
| Delos-mot | KERI rotation race |
| Delos-gph | predecessors null |
| Delos-9qr | JniBridge stop |

### Phase 1 (P1 - Security)
| ID | Short Name |
|----|------------|
| Delos-bfb | Gorgoneion replay |
| Delos-fph | weak verifier |
| Delos-nrp | cert validator leak |
| Delos-amc | RPC rate limiting |
| Delos-0b4 | Script sandbox |
| Delos-mqv | tx replay |
| Delos-9um | cert revocation |

### Phase 2 (P2 - Correctness)
| ID | Short Name |
|----|------------|
| Delos-2dh | Thoth KeyInterval |
| Delos-ozj | soft-delete |
| Delos-30j | temporal queries |
| Delos-g0p | witness bounds |
| Delos-ois | Tron thread safety |
| Delos-yrv | View lifecycle |
| Delos-5rz | fork tests |

### Phase 3 (P3 - Architecture)
| ID | Short Name |
|----|------------|
| Delos-z6p | BlockProducer |
| Delos-l8j | ConsensusCoordinator |
| Delos-jec | ViewManager |
| Delos-4cw | TransactionRouter |
| Delos-8a9 | SyncProtocol |
| Delos-o7w | circuit breaker |
| Delos-lk5 | OracleException |
| Delos-yxv | StateExecutor |
| Delos-ct6 | Domain Builder |
| Delos-7mt | Member abstraction |
| Delos-rjw | reflection ordering |

---

*Last Updated: 2025-12-31*
