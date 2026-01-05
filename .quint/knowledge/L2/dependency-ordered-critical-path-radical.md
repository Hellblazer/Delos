---
scope: Delos Phase 2 execution planning
kind: episteme
content_hash: bb87648f907ac405b7f7521f7fb9f244
---

# Hypothesis: Dependency-Ordered Critical Path (Radical)

Execute Phase 0 bugs in strict dependency order to maximize downstream unblocking:

**Wave 1: Unblock Security Hardening**
- Delos-rsj: KeyEventProcessor auth (unblocks Delos-bfb, Delos-g0p)

**Wave 2: Unblock Architecture Refactoring (parallel)**
- Delos-qq9: Block processing race
- Delos-0ct: View change linearization
- Delos-an5: Silent consumer failure
- Delos-yl3: Bounded pending queue
(All 4 unblock Delos-z6p → entire CHOAM extraction chain)

**Wave 3: Independent Fixes (parallel with Wave 2)**
- Delos-mot: Key rotation race
- Delos-gph: predecessors null return

**Method:**
1. Start rsj immediately (single focused agent)
2. As soon as rsj completes, start bfb/g0p in background
3. Parallel 4 CHOAM agents for Wave 2
4. Fill gaps with mot and gph
5. Benefits: Unblocks 10 downstream tasks ASAP

**Risk:** More context switching, but faster value delivery

## Rationale
{"anomaly": "10 downstream tasks blocked by Phase 0 work", "approach": "Prioritize by downstream impact rather than module grouping", "alternatives_rejected": ["Module grouping - ignores blocking relationships", "Random order - inefficient unblocking"]}