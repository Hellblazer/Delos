# Delos Fireflies Remediation Project

**Project Start**: 2026-01-01
**Estimated Duration**: 4-6 weeks
**Status**: SETUP COMPLETE - Ready for Phase 0

This directory contains the project management infrastructure for the comprehensive remediation of the Delos Fireflies Byzantine fault-tolerant membership service module.

## Quick Start

### First Time Setup
1. **Read this file** - Get oriented
2. **Read CONTINUATION.md** - Understand current status
3. **Run** `bd ready` - See unblocked work
4. **Check EXECUTION_STATE.md** - Current metrics and blockers

### Daily Workflow
1. Read CONTINUATION.md for resume context
2. Run `bd ready` to see available work
3. Pick a bead: `bd update <id> --status in_progress`
4. Work on the issue
5. Create checkpoint: `.pm/checkpoints/YYYYMMDD-HHMM-issue.md`
6. Commit with bead reference: `git commit -m "fix: description [<bead-id>]"`
7. Close bead: `bd close <id>`

### Before Taking a Break
- Save state: `/check`
- Update CONTINUATION.md with current findings
- Document any blockers in EXECUTION_STATE.md
- Push changes: `git add .pm/ && git commit && git push`

### When Resuming After a Break
- Load state: `/load`
- Read CONTINUATION.md
- Check EXECUTION_STATE.md for new blockers
- Run `bd ready` to see work
- Continue from where you left off

## Project Overview

### What We're Doing

Systematic remediation of critical issues in the Fireflies module - a Byzantine fault-tolerant membership service that is a core component of the Delos distributed system platform.

**Analysis identified**:
- 8 Critical production blockers (Phase 0)
- 3 High-priority safety/architecture issues (Phase 1)
- 4 Architecture & refactoring items (Phase 2)
- 2 Testing & documentation gaps (Phase 3)

### Why It Matters

Fireflies is foundational to Delos. Issues here affect:
- Byzantine fault tolerance guarantees
- Membership consistency
- Consensus safety
- System availability and recovery
- All dependent services (Ethereal, CHOAM, etc.)

### Success Criteria

- All 8 critical issues resolved with tests
- All 3 high-priority issues addressed
- 95%+ test coverage for critical membership paths
- Byzantine tolerance verified
- Zero regressions in existing functionality
- Code review approved by lead architect
- Deployed and validated in staging

## Directory Structure

```
.pm/
├── README.md                    ← This file
├── CONTINUATION.md              ← Use to resume work
├── EXECUTION_STATE.md           ← Current metrics, phase, blockers
├── METHODOLOGY.md               ← Engineering discipline
├── AGENT_INSTRUCTIONS.md        ← For spawned agents
├── RISK_REGISTER.md            ← Risk assessment & mitigation
│
├── phases/                      ← Phase-specific plans
│   ├── phase-0-immediate.md    ← Critical production blockers
│   ├── phase-1-safety.md       ← Security hardening
│   ├── phase-2-architecture.md ← Refactoring
│   └── phase-3-testing.md      ← Test coverage
│
├── checkpoints/                 ← Work session records
│   └── YYYYMMDD-HHMM-issue.md ← One per work session
│
├── learnings/                   ← Accumulated insights
│   ├── L0-YYYYMMDD-topic.md   ← Preliminary findings
│   ├── L1-YYYYMMDD-topic.md   ← Validated findings
│   └── L2-YYYYMMDD-topic.md   ← Stable knowledge
│
├── hypotheses/                  ← Design decisions & validation
│   ├── H0-YYYYMMDD-topic.md   ← Proposed approach
│   ├── H1-YYYYMMDD-topic.md   ← Verified approach
│   └── H2-YYYYMMDD-topic.md   ← Implemented approach
│
├── audits/                      ← Quality gates & retrospectives
│   ├── phase-0-exit-criteria.md
│   ├── code-review-summary.md
│   └── risk-assessment.md
│
├── thinking/                    ← Deep analysis sessions
│   └── YYYYMMDD-analysis.md   ← Detailed design thinking
│
└── metrics/                     ← Progress & performance tracking
    ├── phase-0-metrics.md      ← Phase completion metrics
    ├── coverage-report.md      ← Test coverage tracking
    └── timeline.md             ← Schedule tracking
```

## Key Files to Know

### Essential Reading (Start Here)
1. **CONTINUATION.md** - Resume work, understand current state
2. **EXECUTION_STATE.md** - Current metrics, phase, blockers
3. **METHODOLOGY.md** - How we work (test-first, systematic knowledge, etc.)

### Agent Coordination
4. **AGENT_INSTRUCTIONS.md** - Instructions for spawned agents
5. **phases/phase-*.md** - Phase-specific context

### Risk & Quality
6. **RISK_REGISTER.md** - Known risks and mitigation
7. **audits/** - Quality gates and reviews

### Knowledge & Progress
8. **checkpoints/** - Work session records
9. **learnings/** - Accumulated insights
10. **metrics/** - Progress tracking

## Core Workflow Patterns

### Creating a New Task
```bash
bd create "Title of issue" -t feature -p 1
# Types: bug, feature, task, epic, chore
# Priority: 0 (blocker), 1 (critical), 2 (high), 3 (normal), 4 (low)
```

### Starting Work on a Task
```bash
bd update <id> --status in_progress
# Search ChromaDB for prior art
# Write test case (test-first)
# Implement fix
# Run tests: mvn test -pl fireflies
```

### Completing Work on a Task
```bash
# Create checkpoint file
cat > .pm/checkpoints/$(date +%Y%m%d-%H%M)-issue-summary.md << 'EOF'
# Checkpoint: Issue Summary

## Work Completed
- List what was done

## Decisions Made
- Trade-offs, alternatives

## Blockers Encountered
- Any issues blocking progress

## Next Actions
- What's next

## Metrics Update
- Coverage, performance, etc.

## Files Modified
- Key files changed
EOF

# Commit with bead reference
git commit -m "fix: Short description [<bead-id>]"

# Close the bead
bd close <id>
```

### Code Review Workflow
```bash
# Create feature branch
git checkout -b feature/issue-name

# Do work (as above)
# Push branch
git push origin feature/issue-name

# Request code review
# (Handoff to code-review-expert)
```

## Build & Test Commands

```bash
# Full build (install required - modules depend on each other)
./mvnw clean install

# Test single module
./mvnw test -pl fireflies

# Test single class
./mvnw test -Dtest=ViewContextTest

# Test single method
./mvnw test -Dtest=ViewContextTest#testMembershipUpdate

# Full integration tests (resource-intensive)
./mvnw clean install -Dlarge_tests=true
```

## Agent Roles

### Who Does What

| Role | Agent | Responsibilities |
|------|-------|------------------|
| Architecture | java-architect-planner (opus) | Design, trade-off evaluation, BFT validation |
| Implementation | java-developer (sonnet) | Coding, test writing, Java patterns |
| Code Review | code-review-expert (sonnet) | Quality, coverage, regression checking |
| Testing | test-validator (sonnet) | Test strategy, coverage analysis |
| Debugging | java-debugger (opus) | Complex failures, root cause analysis |
| Analysis | deep-analyst (opus) | Issue deep-dive, pattern recognition |

### When to Request Each Agent

**Architecture decision?** → java-architect-planner
**Implementation work?** → java-developer
**Code review?** → code-review-expert
**Test strategy/coverage?** → test-validator
**Complex debugging?** → java-debugger
**Deep analysis?** → deep-analyst

### Handoff Format

```markdown
## Handoff: [Agent Name]

**Task**: [1-2 sentence summary]
**Bead**: [ID] (status: [status])

### Input Artifacts
- ChromaDB: [document IDs or "none"]
- Memory Bank: [file path or "none"]
- Files: [key files to examine]

### Deliverable
[What you expect to receive]

### Quality Criteria
- [ ] Criterion 1
- [ ] Criterion 2
```

## Knowledge Management

### ChromaDB (Permanent Knowledge Base)

Store permanent findings, decisions, and patterns:

```bash
mcp__chromadb__search_similar(
  query: "fireflies ethereal validation",
  num_results: 10
)

mcp__chromadb__create_document(
  document_id: "decision::fireflies::signature-validation",
  content: "Decision, trade-offs, implementation approach...",
  metadata: {
    "component": "fireflies",
    "type": "decision",
    "status": "implemented"
  }
)
```

**Known ChromaDB Documents**:
- `analysis::codebase-deep-analyzer::fireflies-module-2025-01-01`
- `critique::fireflies::deep-analysis-2026-01-01`

### Memory Bank (Session Work)

Store session-specific work and agent handoffs:

```bash
mcp__allPepper-memory-bank__memory_bank_write(
  projectName: "Delos_active",
  fileName: "phase0-work.md",
  content: "Session notes, active hypotheses, blockers..."
)
```

## Key Principles

### Test-First Development
- Write test before implementation
- Test demonstrates the bug/requirement
- All tests pass before commit
- Integration tests required for critical paths

### Systematic Knowledge
- ChromaDB for permanent findings
- Memory Bank for session work
- Beads for task tracking
- Documentation by default

### Delegated Parallel Work
- Spawn agents at top level
- Don't do subtask work directly
- Follow handoff protocol
- Coordinate via Memory Bank

### Git Discipline
- No AI attribution in commits (company policy)
- Reference beads in commit messages
- Atomic commits (one logical change)
- Professional technical language

## Critical Issues (Phase 0)

| ID | Issue | Status | Owner |
|----|-------|--------|-------|
| 001 | Ethereal consensus signature validation | Pending | Developer |
| 002 | Membership update atomicity | Pending | Developer |
| 003 | Ring communication state consistency | Pending | Developer |
| 004 | Failure recovery protocol | Pending | Developer |
| 005 | Ring election consensus bug | Pending | Architect |
| 006 | Service bootstrap validation | Pending | Developer |
| 007 | GRPC connection state management | Pending | Developer |
| 008 | Rate limiting edge cases | Pending | Developer |

See EXECUTION_STATE.md for current metrics.

## Blockers & Dependencies

**Current Blockers**: None - ready to start Phase 0

**Active Dependencies**: None

For current blockers, see EXECUTION_STATE.md

## Timeline

| Phase | Focus | Target Completion | Duration |
|-------|-------|-------------------|----------|
| 0 | Critical blockers (8 issues) | 2026-01-15 | 2 weeks |
| 1 | Safety hardening (3 issues) | 2026-02-01 | 2 weeks |
| 2 | Architecture (4 issues) | 2026-02-15 | 2 weeks |
| 3 | Testing & docs (2 issues) | 2026-03-01 | 2 weeks |

## Success Metrics

| Metric | Target | Current |
|--------|--------|---------|
| Critical issues resolved | 8/8 | 0/8 |
| High priority issues resolved | 3/3 | 0/3 |
| Test coverage (critical paths) | 95%+ | TBD |
| Documentation complete | 100% | 0% |
| No regressions | 100% | TBD |
| Code review approved | Yes | Pending |

See EXECUTION_STATE.md for current metrics.

## Support & Escalation

### Questions?
1. Check CONTINUATION.md - quick context
2. Check EXECUTION_STATE.md - current metrics
3. Check METHODOLOGY.md - discipline guidelines
4. Check RISK_REGISTER.md - known risks
5. Search ChromaDB for prior decisions

### Blockers?
1. Document in EXECUTION_STATE.md
2. Update bead with blocker status
3. Escalate to java-architect-planner if design decision
4. Escalate to java-debugger if complex failure

### Need to Resume?
1. Run `/load` to restore session state
2. Read CONTINUATION.md
3. Check EXECUTION_STATE.md for blockers
4. Run `bd ready` to see unblocked work
5. Continue from last checkpoint

## Session Management

### Before Stopping Work
```bash
# Save state
/check

# Update CONTINUATION.md with findings
# Update EXECUTION_STATE.md if blockers emerged
# Commit: git add .pm/ && git commit -m "pm: session checkpoint"
# Push: git push origin <branch>
```

### When Resuming Later
```bash
# Load state
/load

# Read CONTINUATION.md
# Check EXECUTION_STATE.md
# Run bd ready
# Continue work
```

## Additional Resources

- **Fireflies Module**: `/Users/hal.hildebrand/git/Delos/fireflies`
- **Build Guide**: See CLAUDE.md (Build Commands section)
- **Architecture**: See modules/fireflies/README.md (if exists)
- **Test Guide**: METHODOLOGY.md (Testing Strategy section)

## Useful Commands Quick Reference

```bash
# Beads
bd ready                          # Unblocked work
bd create "Title" -t feature -p 1 # Create task
bd update <id> --status in_progress
bd close <id>
bd list --status=in_progress

# Session
/check                            # Save state
/load                             # Resume state
/sessions                         # List sessions

# Build/Test
./mvnw clean install              # Full build
./mvnw test -pl fireflies         # Test module
mvn test -Dtest=Class#method      # Single test
./mvnw clean install -Dlarge_tests=true  # Integration tests

# Git
git status
git add <files>
git commit -m "message"
git push origin <branch>

# ChromaDB
mcp__chromadb__search_similar(query, num_results=10)
mcp__chromadb__create_document(document_id, content, metadata)

# Memory Bank
mcp__allPepper-memory-bank__memory_bank_read(projectName, fileName)
mcp__allPepper-memory-bank__memory_bank_write(projectName, fileName, content)
```

## Project Status

**Created**: 2026-01-01 08:00 UTC
**Infrastructure**: COMPLETE
**Ready for**: Phase 0 implementation
**Estimated Completion**: 2026-03-01

---

**Last Updated**: 2026-01-01
**Next Step**: Create beads for Phase 0 critical issues
**Questions**: See CONTINUATION.md or METHODOLOGY.md
