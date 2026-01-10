# Daily Standup Template

**Project**: CHOAM Security & Architecture Refactoring

---

## Template

```markdown
# Daily Standup: YYYY-MM-DD

**Phase**: [0 | 1 | 2]
**Day**: X of [Phase Duration]

## Yesterday

- [What was completed]
- [What was worked on]
- [Tests written/passing]

## Today

- [What will be worked on]
- [Expected outcomes]
- [Tests to write]

## Blockers

- [ ] [Blocker description - status]
- [ ] [Blocker description - status]

## Metrics

- Beads in progress: X
- Beads closed yesterday: X
- Tests written: X
- Coverage: X% (+/-X%)

## Risks/Concerns

- [Any new risks identified]
- [Any concerns about schedule/quality]

## Questions

- [Questions for team discussion]
```

---

## Example Standup

```markdown
# Daily Standup: 2026-01-10

**Phase**: 0
**Day**: 2 of 10

## Yesterday

- Started P0-1 (Transaction Signature Validation)
- Analyzed CHOAM.java submit() method
- Wrote 5 failing tests for signature validation
- Identified 3 code paths needing validation

## Today

- Implement signature validation in CHOAM.java
- Make failing tests pass
- Write additional edge case tests
- Submit for code review if complete

## Blockers

- [ ] None currently

## Metrics

- Beads in progress: 1 (Delos-ki6t)
- Beads closed yesterday: 0
- Tests written: 5
- Coverage: 21% (baseline)

## Risks/Concerns

- Signature validation more complex than expected
- May need additional day

## Questions

- Where is the Stereotomy signature API documented?
```

---

## Standup Guidelines

### Timing

- **When**: Start of each work day
- **Duration**: 5-10 minutes
- **Format**: Async written update preferred

### Content Rules

1. **Be specific**: "Wrote 5 tests" not "worked on tests"
2. **Include metrics**: Always include quantifiable progress
3. **Flag blockers early**: Don't wait for blocker to escalate
4. **Update bead status**: Ensure beads reflect standup

### Follow-up Actions

- Update bead status if changed
- Create blocker issues if needed
- Update checkpoint if significant progress

---

## Weekly Pattern

| Day | Focus |
|-----|-------|
| Monday | Week plan, review blockers |
| Tuesday | Deep work |
| Wednesday | Mid-week check, adjust if needed |
| Thursday | Deep work |
| Friday | Week wrap, next week prep |

---

**Template Established**: 2026-01-09
