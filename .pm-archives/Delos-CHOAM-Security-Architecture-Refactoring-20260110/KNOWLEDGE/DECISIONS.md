# Architecture Decision Records (ADR) Log

**Project**: CHOAM Security & Architecture Refactoring
**Last Updated**: 2026-01-09

---

## ADR Index

| ID | Title | Status | Date |
|----|-------|--------|------|
| ADR-001 | Project Structure | Accepted | 2026-01-09 |
| ADR-002 | CHOAM Decomposition Strategy | Accepted | 2026-01-09 |
| ADR-003 | Testing Strategy | Accepted | 2026-01-09 |
| ADR-004 | Rollback Strategy | Accepted | 2026-01-09 |

---

## ADR-001: Project Structure

**Status**: Accepted
**Date**: 2026-01-09
**Context**: Establishing project management structure

### Context

Need a systematic approach to manage the CHOAM security and architecture refactoring over 6-8 weeks with multiple phases.

### Decision

Use `.pm/` directory structure with:
- Phase-specific planning documents
- Knowledge management section
- Metrics and tracking
- Templates for consistent operations

### Consequences

- **Positive**: Clear structure for multi-week project
- **Positive**: Enables session continuity and handoffs
- **Positive**: Integrates with beads system
- **Negative**: Overhead to maintain documentation

### Alternatives Considered

1. **Ad-hoc tracking**: Rejected - too chaotic for security-critical work
2. **Wiki-based**: Rejected - not integrated with code
3. **Issue tracker only**: Rejected - lacks context continuity

---

## ADR-002: CHOAM Decomposition Strategy

**Status**: Accepted
**Date**: 2026-01-09
**Context**: How to break up CHOAM.java (1,796 lines)

### Context

CHOAM.java is a god object with multiple responsibilities:
- Block storage and retrieval
- Consensus interaction
- Session management
- Checkpoint operations
- View management
- Inner classes (Administration, Client, Formation, etc.)

### Decision

1. **Extract interfaces first**: BlockStore, ConsensusEngine
2. **Extract components in order**: BlockProcessor, ViewManager, SessionManager, CheckpointManager
3. **Inner class handling**: Extract Administration, Client, Formation, Synchronizer; keep simple ones
4. **Dependency injection**: Constructor injection with factory pattern
5. **Determinism tests**: Required before and during decomposition

### Consequences

- **Positive**: Clean separation of concerns
- **Positive**: Independently testable components
- **Positive**: Enables future extensibility
- **Negative**: Significant refactoring effort
- **Negative**: Risk of introducing bugs

### Alternatives Considered

1. **Incremental refactoring without interfaces**: Rejected - still coupled
2. **Complete rewrite**: Rejected - too risky, loses battle-tested code
3. **Leave as-is**: Rejected - unmaintainable

---

## ADR-003: Testing Strategy

**Status**: Accepted
**Date**: 2026-01-09
**Context**: How to ensure correctness during and after refactoring

### Decision

1. **Test-first for security fixes**: Write failing test, then fix
2. **Coverage before decomposition**: 40%+ on CHOAM.java before Phase 1
3. **Determinism verification**: Tests that verify identical state across nodes
4. **Byzantine test suite**: 30+ scenarios covering fault tolerance
5. **Continuous integration**: Run full test suite after each extraction

### Consequences

- **Positive**: High confidence in correctness
- **Positive**: Catches regressions early
- **Positive**: Validates Byzantine tolerance
- **Negative**: Significant test writing effort
- **Negative**: Test suite execution time

### Alternatives Considered

1. **Manual testing only**: Rejected - insufficient for Byzantine systems
2. **Coverage targets only**: Rejected - coverage doesn't ensure correctness
3. **Post-hoc testing**: Rejected - regressions may be introduced

---

## ADR-004: Rollback Strategy

**Status**: Accepted
**Date**: 2026-01-09
**Context**: What to do if Phase 1 decomposition fails

### Decision

1. **Feature branches per component**: Enables partial rollback
2. **Abort criteria**: >3 days blocked, critical bug, determinism failure
3. **Decision authority**: Tech lead with 24-hour notification
4. **Keep tests**: Even on rollback, retain valuable tests
5. **Document failure**: Post-mortem required

### Consequences

- **Positive**: Clear exit strategy
- **Positive**: Prevents sunk cost fallacy
- **Positive**: Preserves valuable test work
- **Negative**: May lose refactoring progress

### Alternatives Considered

1. **No rollback plan**: Rejected - too risky
2. **Full revert only**: Rejected - loses partial progress
3. **Push through regardless**: Rejected - quality risk

---

## Decision Template

```markdown
## ADR-XXX: [Title]

**Status**: [Proposed | Accepted | Deprecated | Superseded]
**Date**: YYYY-MM-DD
**Context**: [Brief context]

### Context

[Detailed context and problem statement]

### Decision

[What we decided to do]

### Consequences

- **Positive**: [Good outcomes]
- **Negative**: [Costs or risks]

### Alternatives Considered

1. **Alternative 1**: [Description] - [Why rejected/accepted]
```

---

## ChromaDB Cross-Reference

Decisions are also stored in ChromaDB for searchability:

- `decision::choam::project-structure`
- `decision::choam::decomposition-strategy`
- `decision::choam::testing-strategy`
- `decision::choam::rollback-strategy`

---

**ADR Log Established**: 2026-01-09
**Update**: Add new ADRs as decisions are made
