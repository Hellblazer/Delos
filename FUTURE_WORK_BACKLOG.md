# Delos Future Work Backlog
**Created**: 2026-01-09
**Status**: Organized for systematic execution
**Total Items**: 16 ready (no blockers) + 18 blocked (awaiting dependencies)

---

## 🟢 READY WORK - Ready to Start Now (16 items)

### P0 (1 item)
- **Delos-ffv9**: Gorgoneion Security Hardening (integration phase)

### P1 (1 item)
- **Delos-1ro4**: P4-4A Final documentation audit

### P2 - Medium Priority (5 items, 3 fully ready)
- **✅ Delos-40h0**: Add stack depth limit validation (tron FSM)
  - Effort: Unknown (likely small)
  - Related to: tron module we just documented
  - Status: NO BLOCKERS - READY NOW

- **✅ Delos-d44**: TLA+ specification for BFT properties
  - Effort: Unknown
  - Purpose: Formal verification of Byzantine fault tolerance properties
  - Addresses: Risk gap R1 (no formal verification for BFT claims)
  - Status: NO BLOCKERS - READY NOW

- **✅ Delos-aoz**: View.java extraction design review (Phase 2 gate)
  - Effort: Unknown
  - Purpose: Formal design review before Phase 2 extractions begin
  - Blocks: Phase 2 implementation work
  - Status: NO BLOCKERS - READY NOW

- **⏳ Delos-kl5v**: API Documentation (gorgoneion module)
  - Effort: MEDIUM (2-3 hours)
  - Tasks: Javadoc public classes, update README with security properties
  - Blocked by: **Delos-svkw** (P2-1A: Validation Logic Consolidation)

- **⏳ Delos-rmk7**: Resource cleanup audit
  - Effort: SMALL (1 hour)
  - Tasks: Verify Closeable resources, add try-with-resources
  - Blocked by: **Delos-lc2c** (P0-4A: Scheduler Resource Cleanup)

### P3 - Lower Priority (4 items)
- **Delos-6v5**: Unit test coverage gaps (95%+ critical path)
  - Type: Coverage analysis
  - Unblocks: 4 downstream P0/P1 test tasks

- **Delos-bdr**: Fix fireflies documentation inconsistencies
  - Type: Documentation
  - Issues: Threshold deviation docs, Monitor docs, architecture accuracy

- **Delos-yuf**: Chaos testing framework
  - Type: Quality/Testing infrastructure
  - Scope: 10,000 operations, random ordering, 0 exceptions

- **Delos-rt6**: State invariant tests for 107 concurrent structures
  - Type: Deep testing (state space coverage, not line coverage)
  - Value: Better than line coverage for concurrency testing

---

## 🔴 BLOCKED WORK (18 items)

### Critical Blockers (Unblock These First)
1. **Delos-svkw**: P2-1A - Validation Logic Consolidation
   - Unblocks: Delos-kl5v (API Documentation)

2. **Delos-lc2c**: P0-4A - Scheduler Resource Cleanup
   - Unblocks: Delos-rmk7 (Resource cleanup audit)

### Other Blocked Items
- 16 additional issues in dependency chains
- Once Delos-svkw and Delos-lc2c complete, ~6 more items become ready

---

## 📋 EXECUTION STRATEGY

### Phase 1: Quick Wins (Start Here)
1. **Delos-40h0** (stack validation)
   - No dependencies, likely small effort
   - Related to work we just completed
   - Expected: 1-2 hours

### Phase 2: Investigate & Unblock
1. Check status of **Delos-svkw** and **Delos-lc2c**
2. If quick to complete, unblock ~6 downstream items
3. If complex, proceed to Phase 3

### Phase 3: High-Value Architecture
1. **Delos-aoz** (View.java design review)
   - Critical gate for Phase 2 extractions

2. **Delos-d44** (TLA+ specification)
   - Addresses formal verification gap
   - High-risk item from risk register

### Phase 4: Quality & Testing
1. **Delos-6v5** (coverage gaps) - Unblocks 4 tests
2. **Delos-yuf** (chaos testing) - Reliability
3. **Delos-rt6** (state invariants) - Deep coverage
4. **Delos-bdr** (documentation fixes)

### Phase 5: Integration
- Complete all ready items before releasing Phase 3 work to production

---

## 📊 Summary

| Category | Count | Status |
|----------|-------|--------|
| Ready (no blockers) | 16 | Can start now |
| Fully unblocked | 11 | Immediate priority |
| Blocked on 2 items | 5 | Unblock Delos-svkw & Delos-lc2c |
| Blocked in chains | 18 | Depends on others |
| **TOTAL** | **50** | **52% ready** |

---

## 🎯 Recommended First Task
**Delos-40h0**: Stack depth limit validation
- No dependencies
- Small effort
- Directly related to tron work completed today

---

## 📚 Related Documentation
- See `.beads/issues.jsonl` for detailed issue tracking
- ChromaDB: `project::delos::remaining-work-2026-01-09` for analysis
- Phase 3 completed: Operations & Maintenance documentation epic (5 guides, 3,835+ lines)
