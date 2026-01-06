# ADR-0000: Use Markdown Architecture Decision Records

**Status**: ACCEPTED

**Context**

This project requires documenting architectural decisions in a way that is:
- Searchable and reviewable in git
- Linked to code and related decisions
- Written in a standard format that others can follow
- Easy to update as decisions evolve

**Decision**

We adopt Markdown Architecture Decision Records (MADR) as our format for documenting architectural decisions. This is consistent with industry best practices and enables better knowledge sharing.

**Format**

All ADRs shall follow this template:

```
# ADR-XXXX: [Short Title]

**Status**: PROPOSED | ACCEPTED | DEPRECATED | SUPERSEDED

**Context**

What is the issue that we're seeing that is motivating this decision or change?

**Decision**

What is the change that we're proposing and/or doing?

**Consequences**

What becomes easier or more difficult to do because of this change?

## Related Decisions

- ADR-XXXX: [Related decision]
- ADR-XXXX: [Related decision]

## References

- [Link to relevant documentation]
```

**Consequences**

- ADRs provide a permanent record of architectural decisions and their rationale
- Decisions are version-controlled and linked to commits
- Teams can easily understand the evolution of architecture over time
- New team members can quickly understand architectural context

**Conversion Note**

Existing Decision Record Records (DRRs) will be migrated to MADR format while preserving:
- Original hypothesis and verification details
- Confidence levels
- Impact assessment
- All supporting evidence and rationale

---

## ADR Naming Convention

- Format: `XXXX-short-title.md` (e.g., `0001-use-keri-for-identity.md`)
- Status: PROPOSED (new), ACCEPTED (approved), DEPRECATED (no longer used), SUPERSEDED (replaced by newer ADR)
- Location: All ADRs in `docs/adr/` directory

## ADR Index

| # | Title | Status |
|---|-------|--------|
| 0000 | Use Markdown Architecture Decision Records | ACCEPTED |
| 0001 | *To be created* | PROPOSED |
| 0002 | *KERI Implementation* | *Pending* |
| 0003 | *BFT Membership* | *Pending* |
| 0004 | *Consensus Design* | *Pending* |
| 0005 | *Deterministic SQL* | *Pending* |
| 0006 | *Execution Strategy* | *Pending* |

See `QUALITY_INITIATIVE_PLAN.md` Phase 1b for creation schedule.

---

**Last Updated**: 2026-01-06
**Created By**: Quality Initiative Phase 0 Setup
