---
id: 2025-12-31-internal-choam-unified-threading-model-refactor.md
type: internal
target: choam-unified-threading-model-refactor
verdict: pass
assurance_level: L2
carrier_ref: test-runner
valid_until: 2026-03-31
date: 2025-12-31
content_hash: 12faf4002c75c35234d908f4898d44fa
---

Code inspection shows CHOAM.java is 1100+ lines with interleaved threading concerns. Unified refactor would require: (1) Extract BlockProcessor - consumer() at lines 604-627; (2) Extract ViewStateManager - view/nextViewId/current state; (3) Message passing for combine() handler at lines 119-128. This aligns with Phase 3 beads (z6p, l8j, jec). Valid but higher risk for Phase 0. Recommend for Phase 3 instead.