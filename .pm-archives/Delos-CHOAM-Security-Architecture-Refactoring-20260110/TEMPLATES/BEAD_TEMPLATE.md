# Bead Creation Template

**Project**: CHOAM Security & Architecture Refactoring

---

## Creating a New Bead

### Command

```bash
bd create "Title" -t [type] -p [priority]
```

### Types

| Type | Use For |
|------|---------|
| bug | Security vulnerabilities, defects |
| feature | New functionality |
| task | Implementation work, tests |
| epic | Phase-level tracking |
| chore | Cleanup, documentation |

### Priorities

| Priority | Use For |
|----------|---------|
| P0 | Critical security issues, blockers |
| P1 | High priority, phase goals |
| P2 | Medium priority, quality items |
| P3 | Low priority, nice-to-have |

---

## Bead Templates by Type

### Security Fix Bead (Phase 0)

```bash
bd create "P0-X: [Security Issue Name]" -t bug -p 0
```

**Description Template**:
```
## Security Issue

**Vulnerability**: [Brief description]
**Impact**: [What could happen if exploited]
**Severity**: Critical

## Requirements

- [ ] Identify vulnerable code paths
- [ ] Write failing security tests
- [ ] Implement fix
- [ ] Security code review
- [ ] Integration test

## Success Criteria

- [ ] Vulnerability demonstrated in test
- [ ] Fix passes all tests
- [ ] Security review approved
- [ ] No regressions

## Context

- Phase: 0
- Reference: .pm/PHASE_0/PLAN.md
- Security Checklist: .pm/KNOWLEDGE/SECURITY_CHECKLIST.md
```

### Architecture Bead (Phase 1)

```bash
bd create "P1-X: [Component Name]" -t feature -p 1
```

**Description Template**:
```
## Architecture Task

**Component**: [What to extract/create]
**Goal**: [What this achieves]

## Requirements

- [ ] Define interface
- [ ] Write interface tests
- [ ] Extract implementation
- [ ] Wire with dependency injection
- [ ] Run determinism tests

## Success Criteria

- [ ] Interface clean and testable
- [ ] Implementation extracted
- [ ] All tests passing
- [ ] Determinism tests passing
- [ ] Architecture review approved

## Context

- Phase: 1
- Reference: .pm/PHASE_1/PLAN.md
- Dependencies: .pm/PHASE_1/DEPENDENCIES.md
```

### Enhancement Bead (Phase 2)

```bash
bd create "P2-X: [Enhancement Name]" -t task -p 1
```

**Description Template**:
```
## Enhancement Task

**Goal**: [What this achieves]
**Type**: [Tests/Quality/Performance/Audit]

## Requirements

- [ ] [Requirement 1]
- [ ] [Requirement 2]
- [ ] [Requirement 3]

## Success Criteria

- [ ] [Criterion 1]
- [ ] [Criterion 2]
- [ ] [Criterion 3]

## Context

- Phase: 2
- Reference: .pm/PHASE_2/PLAN.md
```

---

## Bead Workflow Commands

### Starting Work

```bash
# See what's unblocked
bd ready

# Get bead details
bd show Delos-XXXX

# Start working
bd update Delos-XXXX --status in_progress
```

### During Work

```bash
# Update description
bd update Delos-XXXX -d "New description"

# Add notes
bd note Delos-XXXX "Progress update: 50% complete"
```

### Completing Work

```bash
# Close bead
bd close Delos-XXXX
```

### Managing Dependencies

```bash
# Add dependency (A depends on B)
bd dep add Delos-A Delos-B

# View dependencies
bd show Delos-A
```

---

## Existing Project Beads

### Epic
- **Delos-disp**: CHOAM Security & Architecture Refactoring

### Phase 0 Beads
- **Delos-ki6t**: P0-1 Transaction Signature Validation
- **Delos-k0bz**: P0-2 Pending Queue Bounds Verification
- **Delos-tm53**: P0-3 Synchronization Circuit Breaker
- **Delos-vud5**: P0-4 Checkpoint Validation Race Fix

### Phase 1 Beads (To Be Created)
- P1-1: BlockStore Interface Extraction
- P1-2: ConsensusEngine Interface Extraction
- P1-3: CHOAM Decomposition
- P1-4: Admission Control Implementation
- P1-5: Test Coverage Expansion

### Phase 2 Beads (To Be Created)
- P2-1: Byzantine Failure Test Suite
- P2-2: Code Quality Improvements
- P2-3: Rate Limiting Framework
- P2-4: Performance Benchmarking
- P2-5: Security Audit Preparation

---

## Bead Naming Convention

```
P[Phase]-[Number]: [Brief Description]

Examples:
- P0-1: Transaction Signature Validation
- P1-3: CHOAM Decomposition
- P2-1: Byzantine Failure Test Suite
```

---

## Commit Message Integration

Always reference bead in commits:

```
fix: add transaction signature validation

- Add SignatureValidator class
- Validate all transactions at submit
- Reject invalid signatures
- Add 15 tests

References: Delos-ki6t
```

---

**Template Established**: 2026-01-09
