---
created: 2025-12-31T11:05:32-08:00
type: DRR
winner_id: dependency-ordered-critical-path-radical
content_hash: c7865846ba3bc402067694585c22773c
---

# Phase 0 Execution Strategy: Dependency-Ordered Critical Path

## Context
Delos Phase 2 remediation has 7 open P0 bugs blocking 10+ downstream tasks. Need to determine optimal execution order to maximize unblocking velocity while managing risk.

## Decision
**Selected Option:** dependency-ordered-critical-path-radical

Execute Phase 0 bugs in dependency-ordered waves: Wave 1 starts with rsj (KeyEventProcessor auth) to unblock security tasks bfb/g0p. Wave 2 parallelizes all 4 CHOAM bugs (qq9, 0ct, an5, yl3) to unblock Phase 3 architecture chain. Wave 3 handles independent fixes (mot, gph) in parallel.

## Rationale
Validated against actual bead dependency graph via 'bd blocked'. This strategy optimizes for downstream unblocking rather than module grouping or complexity ordering. rsj unblocks 2 security tasks; CHOAM bugs unblock 10+ Phase 3 tasks. R_eff: 1.00 with low bias risk.

### Characteristic Space (C.16)
Unblocking velocity: HIGH. Risk: LOW. Complexity: MEDIUM.

## Consequences
Requires more context switching between modules. Agents must be briefed per-bug rather than per-module. However, downstream tasks become unblocked faster, enabling parallel security hardening and architecture work. Wave structure provides clear milestones.
