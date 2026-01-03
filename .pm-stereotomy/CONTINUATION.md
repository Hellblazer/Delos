# Stereotomy KERI Security Remediation - Continuation Guide

**Branch**: feat/stereotomy-review
**Last Updated**: 2026-01-02
**Current Phase**: Phase 0 - TDD & Security Tests Setup

## Quick Status

Project to remediate 6 critical security vulnerabilities in the stereotomy (KERI) module using Test-Driven Design methodology. All vulnerabilities identified and documented. Phase 0 task: Create failing security tests.

**Phase**: 0/6 (Initiating)
**Issues Fixed**: 0/6 (CRIT-1 through CRIT-6)
**Beads Created**: 0 (need to create epic + 6 critical beads)
**Failing Tests**: 0/6 (need to write)

## What You Need to Know

### The 6 Critical Issues (In Priority Order)

1. **CRIT-1: XOR Commutativity Attack** (KeyConfigurationDigester.java)
   - Attacker can rotate to keys in different order than committed
   - Fix: Replace XOR with order-preserving concatenated hash
   - Impact: Breaks weighted threshold multisig enforcement

2. **CRIT-2: Race Condition in Event Append** (MemKERL.java)
   - Concurrent appends corrupt sequence numbers and state
   - Fix: Add per-identifier locks
   - Impact: Data corruption under concurrent load

3. **CRIT-3: Inception Signature Not Verified** (KeyEventProcessor.java)
   - Forged signatures accepted if identifier derivation valid
   - Fix: Verify inception signature against inception keys
   - Impact: CRITICAL - Forged identities accepted

4. **CRIT-4: No Database Transaction Boundaries** (UniKERL.java)
   - Multi-step append without transaction, no rollback on failure
   - Fix: Wrap append in explicit database transaction
   - Impact: Partial/inconsistent state after failures

5. **CRIT-5: Non-Atomic State Transitions** (KeyStateProcessor.java)
   - State modified before validation completes
   - Fix: Immutable state computation with atomic commit
   - Impact: Inconsistent witness state

6. **CRIT-6: Key Material Not Cleared** (JksKeyStore, MemKeyStore)
   - Password/key material exposed in memory after use
   - Fix: Use CharArray wrappers, explicit Array.fill()
   - Impact: Key material may leak in memory dumps

### Critical Analysis Documents

All issues documented in ChromaDB:

**Primary Analysis**: `critique::stereotomy::deep-analysis-2026-01-02`
- Deep critique from substantive-critic agent
- Identifies all 6 CRITICAL issues
- Provides attack vectors and impact analysis
- Evidence-based recommendations

**Secondary Analysis**: `analysis::codebase-deep-analyzer::stereotomy-module-2026-01-02`
- Module structure and component relationships
- 88 source files, 10 test files analyzed
- Integration points with other modules
- Test coverage assessment

**Reference**: `delos::pattern::keri-identity`
- KERI pattern documentation
- Identity and cryptographic concepts

## Next Immediate Actions (Ordered)

### Before End of Day (Today)

1. **Create Beads for All Issues**
   ```bash
   # Create epic for project
   bd create "Stereotomy KERI Security Remediation" -t epic -p 0
   # Note: Save the returned epic ID (will be used for dependencies)

   # Create beads for each critical issue (high priority)
   bd create "CRIT-1: XOR Commutativity Attack (KeyConfigurationDigester)" -t bug -p 1
   bd create "CRIT-2: Race Condition in Event Append (MemKERL)" -t bug -p 1
   bd create "CRIT-3: Inception Signature Not Verified (KeyEventProcessor)" -t bug -p 1
   bd create "CRIT-4: No Database Transaction Boundaries (UniKERL)" -t bug -p 1
   bd create "CRIT-5: Non-Atomic State Transitions (KeyStateProcessor)" -t bug -p 1
   bd create "CRIT-6: Key Material Not Cleared (JksKeyStore, MemKeyStore)" -t bug -p 1
   ```

2. **Add Phase 0 Bead**
   ```bash
   bd create "Phase 0: TDD & Security Tests Setup" -t task -p 1
   # Make it depend on the epic
   bd dep add <phase0-bead-id> <epic-id>
   ```

### Tomorrow and Following Days

3. **Write Failing Security Tests** (Phase 0 primary task)
   - Test for CRIT-1 (XOR attack scenario)
   - Test for CRIT-2 (concurrent append race)
   - Test for CRIT-3 (inception signature bypass)
   - Test for CRIT-4 (partial state after crash)
   - Test for CRIT-5 (inconsistent witness state)
   - Test for CRIT-6 (key material in memory)

4. **Verify Tests Fail**
   ```bash
   mvn test -Dtest=*Security* -pl stereotomy
   # All 6 tests should FAIL
   ```

5. **Establish Baseline Metrics**
   - Test execution time
   - Memory usage
   - Code coverage

### After Phase 0 (3-4 Days)

6. **Fix CRIT-1**: Implement hash-based key ordering
7. **Fix CRIT-2**: Add per-identifier locks in MemKERL
8. **Fix CRIT-3**: Add inception signature verification
9. **Fix CRIT-4**: Wrap UniKERL append in transactions
10. **Fix CRIT-5**: Make state transitions atomic
11. **Fix CRIT-6**: Clear key material in keystores

## Key Files

**Project Infrastructure**:
- `.pm-stereotomy/EXECUTION_STATE.md` - Execution state and metrics
- `.pm-stereotomy/CONTINUATION.md` - This file
- `.pm-stereotomy/AGENT_INSTRUCTIONS.md` - Instructions for spawned agents
- `.pm-stereotomy/METHODOLOGY.md` - Engineering discipline
- `.pm-stereotomy/checkpoints/` - Per-phase checkpoints
- `.pm-stereotomy/hypotheses/` - Design decisions

**Source Code**:
- `stereotomy/src/main/java/com/hellblazer/delos/stereotomy/` - Main module
- `stereotomy/src/test/java/com/hellblazer/delos/stereotomy/` - Tests

**Reference**:
- `.pm/` - Previous Fireflies remediation project (reference implementation)
- `CLAUDE.md` - Project directives

## Branch Info

**Current Branch**: feat/stereotomy-review
**Parent Branch**: main (but working on feature branch)
**Commit History**: Focus on commits related to stereotomy analysis (Dec 28 - Jan 2)

## Communication

If stuck or blocked:
1. Check `.pm-stereotomy/METHODOLOGY.md` for process guidance
2. Search ChromaDB for related analysis: `critique::stereotomy`, `analysis::stereotomy`
3. Reference Fireflies project in `.pm/` for similar remediation patterns
4. All major decisions should be documented in hypotheses directory

## Session Lifecycle

**SessionStart Hook**: Auto-loads this continuation context if `.pm-stereotomy/` exists

**During Session**:
- Use beads for task tracking (primary)
- Use memory bank for active work state
- Use ChromaDB for persistent findings

**PreCompact Hook**: Will remind to save state

**Session Resume**: Reload this file to understand current state

---

**Last Updated**: 2026-01-02 00:00
**Next Review**: After Phase 0 TDD setup (3-4 days)
**Estimated Effort**: 4-6 weeks total (6 phases × 3-5 days each)
