# Fireflies Synchronization Refactoring - Quick Start Card

**Print or bookmark this. It's everything you need to resume work.**

---

## What Is This?

A focused architectural refactoring to fix lock contention between consensus and join operations in Fireflies.

**Problem**: ReadWriteLock on `View.viewChange` blocks joins during consensus
**Goal**: Eliminate contention, improve join latency 3-5x, fix bootstrap/churn test timeouts
**Timeline**: 4-5 weeks (5 phases)

---

## Quick Status

| Item | Status |
|------|--------|
| Investigation | ✅ COMPLETE (commit 2097442) |
| Current Phase | Phase 0 → Phase 1 (Design & Validation) |
| Start Date | 2026-01-04 |
| Target Completion | 2026-02-07 |
| Test Status | BootstrapTest FAILING (all 6 timeout), ChurnTest FAILING |
| Next Action | Evaluate 3 synchronization approaches, select 1 |

---

## Resume in 60 Seconds

```bash
# 1. Restore session
/load

# 2. Read these (in order):
cat .pm/SYNCHRONIZATION_CONTINUATION.md
cat .pm/phases/SYNCHRONIZATION_REFACTORING.md

# 3. Check work
bd ready

# 4. Continue from checkpoint
# (Work is tracked in .pm/checkpoints/YYYYMMDD-*.md)
```

---

## Three Candidate Solutions

| Approach | Performance | Complexity | Risk |
|----------|-------------|-----------|------|
| Lock-Free | 5x | Medium | Byzantine tolerance |
| Separate Locks | 2-3x | High | Deadlock/coordination |
| Non-Blocking Reads | 3-4x | Medium | Snapshot races |

**Phase 1 Task**: Evaluate, select one, validate safety properties

---

## Phase Timeline

```
Week 1 (Jan 4-10):    Phase 1 - Design & Validation    → CURRENT
Week 2 (Jan 11-17):   Phase 2 - Foundation
Weeks 3-4 (Jan 18-31): Phase 3 - Implementation
Week 5 (Feb 1-7):     Phase 4 - Testing & Hardening
```

---

## Commands You'll Use

### Daily Work
```bash
bd ready                          # See unblocked work
bd show Delos-XXXX                # Task details
bd update Delos-XXXX --status in_progress
git commit -m "message [Delos-XXXX]"
bd close Delos-XXXX               # Complete task
```

### Testing
```bash
./mvnw test -Dtest=BootstrapTest  # Run failing test
./mvnw test -Dtest=ChurnTest      # Run canary test
./mvnw clean install -Dlarge_tests=true  # Full suite
```

### Session Management
```bash
/check                            # Save state
/load                             # Resume state
```

---

## Key Files

| File | Purpose | Read Time |
|------|---------|-----------|
| SYNCHRONIZATION_CONTINUATION.md | Resume guide | 5 min |
| phases/SYNCHRONIZATION_REFACTORING.md | Phase plan | 10 min |
| SYNCHRONIZATION_REFACTORING_STATE.md | Metrics & status | 5 min |
| SYNCHRONIZATION_SETUP_SUMMARY.md | Setup overview | 10 min |
| commit 2097442 | Investigation findings | 10 min |

---

## Success Criteria

### Phase 1 (This Week)
- [ ] Approach selected
- [ ] Architecture designed
- [ ] Test strategy planned

### Project Complete
- [ ] BootstrapTest: All 6 methods pass
- [ ] ChurnTest: Convergence succeeds
- [ ] Performance: 3-5x improvement
- [ ] Coverage: 95%+ on critical paths
- [ ] Code review: Approved

---

## Planned Beads

### Phase 1 (Ready to create)
- Delos-7ws: Evaluate approaches
- Delos-7wt: Architecture design
- Delos-7wu: Test strategy

### Phases 2-4 (Blocked until Phase 1 complete)
- Delos-7wv through Delos-7x2 (10 more tasks)

---

## When You Get Stuck

1. **Design question?** → Read `phases/SYNCHRONIZATION_REFACTORING.md` Phase 1
2. **Implementation?** → Read `.pm/METHODOLOGY.md`
3. **Test question?** → Check test strategy in phase plan
4. **Need escalation?** → See AGENT_INSTRUCTIONS.md

---

## Knowledge Base

**ChromaDB**: `analysis::fireflies::synchronization-lock-contention-2026-01-04`
- Three solutions analysis
- Root cause deep-dive
- Implementation recommendations

**Memory Bank**: `Delos_active/synchronization-refactoring.md` (create as you work)
- Session notes
- Design hypotheses
- Blockers and resolutions

---

## Daily Checklist

### Morning (5 min)
- [ ] `/load` - restore session
- [ ] `bd ready` - see work
- [ ] Read most recent checkpoint file

### During Day
- [ ] Test first
- [ ] Implement
- [ ] Document decisions in ChromaDB
- [ ] Update checkpoint

### End of Day
- [ ] Commit: `git commit -m "message [Delos-XXXX]"`
- [ ] `/check` - save state

---

## Test Targets

### BootstrapTest
```
Current: All 6 methods TIMEOUT (>30s)
Target:  All 6 methods PASS (<5s)
Methods: testSingleNodeBootstrap through testSixNodeBootstrap
```

### ChurnTest
```
Current: Convergence TIMEOUT
Target:  Successful convergence
Scenario: 12 nodes → scale to 50 with removals
```

### Performance
```
Current: Blocked by timeouts
Target:  3-5x improvement in join latency
         No regression in consensus latency
```

---

## Phase 1 Checklist (This Week)

**Monday-Tuesday**:
- [ ] Create bead Delos-7ws
- [ ] Evaluate three approaches
- [ ] Create decision matrix
- [ ] Document pros/cons

**Wednesday-Thursday**:
- [ ] Create bead Delos-7wt
- [ ] Design chosen approach
- [ ] Create class diagrams
- [ ] Define protocols

**Friday**:
- [ ] Create bead Delos-7wu
- [ ] Plan test categories
- [ ] Design test modifications
- [ ] Prepare Phase 2

---

## Next 5 Minutes

1. Read this file (just now)
2. Run: `/load` (restore session)
3. Run: `bd ready` (see work)
4. Read: `.pm/SYNCHRONIZATION_CONTINUATION.md`
5. Begin Phase 1 work (if starting fresh)

---

## Important Policies

- **NO AI attribution** in commits (company policy)
- **Test-first**: Write test before fix
- **Coverage**: 95%+ on critical paths
- **Byzantine**: All changes must preserve safety
- **Beads**: Use for all task tracking
- **ChromaDB**: Store permanent decisions

---

## Emergency Contacts

**Stuck on Architecture?**
- Escalate to: `java-architect-planner`
- Provide: Current hypothesis, trade-offs, recommendation

**Stuck on Implementation?**
- Escalate to: `java-developer` or `java-debugger`
- Provide: Task description, failing test, expected outcome

**Stuck on Testing?**
- Escalate to: `test-validator`
- Provide: Current test scope, coverage report

**Stuck on Code Quality?**
- Escalate to: `code-review-expert`
- Provide: Code changes, coverage, test results

---

## Bottom Line

**You have**: Complete infrastructure for 4-5 week project
**You know**: Three candidate solutions to evaluate
**You need**: Phase 1 complete by end of this week
**Success**: BootstrapTest + ChurnTest passing, 3-5x join improvement

**Start now**: Read SYNCHRONIZATION_CONTINUATION.md, then begin Phase 1.1

---

**Last Updated**: 2026-01-04
**Status**: Ready to Start Phase 1
**Duration**: ~4-5 weeks total
**Target Completion**: 2026-02-07
