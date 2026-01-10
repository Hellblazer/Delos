# Weekly Sprint Review Template

**Project**: CHOAM Security & Architecture Refactoring

---

## Template

```markdown
# Week N Review: YYYY-MM-DD

**Phase**: [0 | 1 | 2]
**Week**: N of [Phase Total Weeks]

---

## Summary

[1-2 paragraph summary of the week]

---

## Completed Work

### Beads Closed

| Bead | Title | Effort | Notes |
|------|-------|--------|-------|
| Delos-XXXX | | X days | |

### Tests Added

| Area | Tests | Coverage Delta |
|------|-------|----------------|
| | | |

### Code Merged

| PR/Commit | Description | Reviewed By |
|-----------|-------------|-------------|
| | | |

---

## In Progress

| Bead | Title | Progress | Blockers |
|------|-------|----------|----------|
| | | X% | |

---

## Metrics

### Progress

| Metric | Start | End | Delta |
|--------|-------|-----|-------|
| Beads completed | | | |
| Coverage | | | |
| CHOAM.java lines | | | |

### Quality

| Gate | Status |
|------|--------|
| Test pass rate | X% |
| Critical bugs | X |
| Determinism tests | [PASS/FAIL/N/A] |

---

## Blockers Encountered

| Blocker | Impact | Resolution |
|---------|--------|------------|
| | X days | |

---

## Learnings

### What Went Well

- [Learning 1]
- [Learning 2]

### What Could Improve

- [Improvement 1]
- [Improvement 2]

### Key Technical Insights

- [Insight 1]
- [Insight 2]

---

## Next Week Plan

### Goals

1. [Goal 1]
2. [Goal 2]
3. [Goal 3]

### Beads to Complete

| Bead | Title | Estimated Effort |
|------|-------|------------------|
| | | |

### Risks for Next Week

- [Risk 1]
- [Risk 2]

---

## Gate Review Decision

**Weekly Gate**: [ ] GO / [ ] NO-GO / [ ] CONDITIONAL

**Criteria**:
- Test pass rate: X% (target: >95%)
- Critical bugs: X (target: 0)
- Schedule: [on track / behind / ahead]

**Decision Notes**:
[If not GO, explain conditions or concerns]

---

## Action Items

| Action | Owner | Due |
|--------|-------|-----|
| | | |
```

---

## Example Review

```markdown
# Week 1 Review: 2026-01-16

**Phase**: 0
**Week**: 1 of 2

---

## Summary

Good progress on Phase 0 security fixes. P0-1 (Transaction Signature Validation) completed and merged. P0-2 (Queue Bounds) nearly complete. On track for Phase 0 completion next week.

---

## Completed Work

### Beads Closed

| Bead | Title | Effort | Notes |
|------|-------|--------|-------|
| Delos-ki6t | P0-1: Transaction Signature Validation | 3 days | Security review passed |

### Tests Added

| Area | Tests | Coverage Delta |
|------|-------|----------------|
| Signature validation | 15 | +5% |
| Queue bounds | 8 | +3% |

### Code Merged

| PR/Commit | Description | Reviewed By |
|-----------|-------------|-------------|
| abc123 | Add transaction signature validation | @security-lead |

---

## Metrics

### Progress

| Metric | Start | End | Delta |
|--------|-------|-----|-------|
| Beads completed | 0 | 1 | +1 |
| Coverage | 21% | 29% | +8% |

### Quality

| Gate | Status |
|------|--------|
| Test pass rate | 100% |
| Critical bugs | 0 |
| Determinism tests | N/A (Phase 1) |

---

## Weekly Gate Decision

**Weekly Gate**: [x] GO

All criteria met. Proceeding to complete P0-2 and start P0-3/P0-4.
```

---

## Review Guidelines

### When to Conduct

- End of each work week
- Before phase gate reviews
- After significant milestones

### Participants

- Lead Engineer (required)
- Architect (Phase 1)
- Test Lead (all phases)
- Project Lead (gates)

### Duration

- 30-60 minutes
- Focus on blockers and learnings
- Gate decision must be made

---

**Template Established**: 2026-01-09
