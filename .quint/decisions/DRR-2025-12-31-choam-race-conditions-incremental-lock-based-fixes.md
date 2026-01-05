---
winner_id: choam-incremental-lock-based-fixes
created: 2025-12-31T11:05:32-08:00
type: DRR
content_hash: 8fc020ef0eccceed0079dbf2ca07c398
---

# CHOAM Race Conditions: Incremental Lock-Based Fixes

## Context
CHOAM.java has 4 race conditions (qq9, 0ct, an5, yl3) that must be fixed in Phase 0. Two approaches: incremental targeted fixes vs unified threading model refactor.

## Decision
**Selected Option:** choam-incremental-lock-based-fixes

Fix each CHOAM race condition independently with targeted locking: ReentrantLock for consume() critical section, AtomicReference/CAS for view state, try-catch with restart for consumer thread, bounded capacity for pending queue.

## Rationale
Lower risk approach for Phase 0. Each fix is independent and testable in isolation. Uses existing AtomicReference patterns already in codebase. Aligns with INV-C2 (no synchronized, uses concurrent utilities). R_eff: 1.00. Unified refactor deferred to Phase 3 where it aligns with CHOAM decomposition beads (z6p chain).

### Characteristic Space (C.16)
Risk: LOW. Scope: CONTAINED. Phase 3 alignment: PREPARES.

## Consequences
May not address systemic threading issues - those will be resolved in Phase 3 unified refactor. Requires benchmarking after each fix to detect contention. Full CHOAM integration test required after all 4 fixes complete. Creates foundation for Phase 3 decomposition.
