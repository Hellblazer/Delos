# Delos Fireflies Remediation Project - Index

**Project Started**: 2026-01-01
**Status**: Infrastructure setup complete, ready for Phase 0
**Infrastructure Location**: `/Users/hal.hildebrand/git/Delos/.pm/`

## Start Here

### First Time Users (10 minutes)

1. **README.md** (2 min) - Project overview and quick start
2. **CONTINUATION.md** (5 min) - Current status and resume guide
3. Run `bd ready` (1 min) - See unblocked work
4. Start working!

### All Project Files (Alphabetical)

#### Essential Core Files

| File | Purpose | Update | Size |
|------|---------|--------|------|
| **CONTINUATION.md** | Session resumption guide | Before breaks | 6 KB |
| **EXECUTION_STATE.md** | Current metrics, phase, blockers | Daily | 4 KB |
| **METHODOLOGY.md** | Engineering discipline standards | Reference | 11 KB |
| **README.md** | Project overview and quick start | As needed | 13 KB |
| **AGENT_INSTRUCTIONS.md** | Instructions for spawned agents | Reference | 11 KB |
| **RISK_REGISTER.md** | Risk assessment and mitigation | Per discovery | 12 KB |

#### Setup & Summary

| File | Purpose |
|------|---------|
| **SETUP_SUMMARY.md** | What was created and next steps |
| **INDEX.md** | This file - navigation guide |

#### Phase Planning (4 phases, 18 total issues)

| File | Phase | Issues | Duration | Target |
|------|-------|--------|----------|--------|
| **phases/phase-0-immediate.md** | 0 | 8 critical | 2 weeks | 2026-01-15 |
| **phases/phase-1-safety.md** | 1 | 3 high-priority | 2 weeks | 2026-02-01 |
| **phases/phase-2-architecture.md** | 2 | 4 architecture | 2 weeks | 2026-02-15 |
| **phases/phase-3-testing.md** | 3 | 2 testing/docs | 2 weeks | 2026-03-01 |

#### Working Directories (For Your Work)

| Directory | Purpose | When Used |
|-----------|---------|-----------|
| **checkpoints/** | Work session records | After each work session |
| **learnings/** | Accumulated insights | As discoveries are made |
| **hypotheses/** | Design decisions | Before implementation |
| **audits/** | Quality gates, reviews | At phase completion |
| **thinking/** | Deep analysis sessions | During investigation |
| **metrics/** | Progress tracking | Weekly |

## Quick Navigation

### Current Status

- **What's the current status?** → EXECUTION_STATE.md
- **How do I resume?** → CONTINUATION.md
- **What are known risks?** → RISK_REGISTER.md
- **What phase are we in?** → EXECUTION_STATE.md (Phase field)

### Starting Work

- **What do I work on?** → `bd ready` (command)
- **How do I work?** → METHODOLOGY.md
- **What are the issues?** → EXECUTION_STATE.md or bd show <id>
- **Phase details?** → phases/phase-<n>-*.md

### Agent Coordination

- **Spawning an agent?** → AGENT_INSTRUCTIONS.md
- **What should agent do?** → AGENT_INSTRUCTIONS.md (Handoff format)
- **Agent roles?** → AGENT_INSTRUCTIONS.md (Agent-Specific Roles)

### Knowledge Management

- **Storing permanent knowledge?** → ChromaDB (see METHODOLOGY.md)
- **Session work?** → Memory Bank (see METHODOLOGY.md)
- **Task tracking?** → Beads CLI (see METHODOLOGY.md)

### Problem Solving

- **Stuck?** → Check RISK_REGISTER.md for mitigation
- **Design decision?** → Escalate to java-architect-planner
- **Integration failure?** → Escalate to java-debugger
- **Coverage/testing issue?** → Escalate to test-validator

## Beads Quick Reference

### All Beads (18 total)

**Epic** (1):
- `Delos-t48`: Fireflies Remediation - Epic

**Phase 0 - Critical Blockers** (8, P0):
- `Delos-sht`: Ethereal consensus signature validation
- `Delos-bk1`: Membership update atomicity
- `Delos-4r0`: Ring communication state consistency
- `Delos-71j`: Failure recovery protocol
- `Delos-j7c`: Ring election consensus bug
- `Delos-88y`: Service bootstrap validation
- `Delos-agc`: GRPC connection state management
- `Delos-bg6`: Rate limiting edge cases

**Phase 1 - Safety** (3, P1):
- `Delos-3aq`: Membership state machine hardening
- `Delos-6qf`: Byzantine tolerance guarantee validation
- `Delos-os0`: Secure communication overlay robustness

**Phase 2 - Architecture** (4, P2):
- `Delos-wm7`: Code organization and modularity
- `Delos-3s5`: Dependency injection patterns
- `Delos-mu1`: Testing infrastructure improvements
- `Delos-ax3`: Performance optimization

**Phase 3 - Testing/Docs** (2, P3):
- `Delos-6v5`: Unit test coverage gaps
- `Delos-pb1`: API documentation gaps

### Useful Bead Commands

```bash
bd ready                        # Show unblocked work
bd show <id>                    # Show bead details
bd update <id> --status in_progress
bd close <id>                   # Complete bead
bd list --status=in_progress    # Active work
bd dep add <id> <blocker-id>   # Add dependency
```

## Timeline & Phases

```
Phase 0 (Critical)     Phase 1 (Safety)    Phase 2 (Architecture)  Phase 3 (Testing)
├─ 8 critical issues   ├─ 3 high issues    ├─ 4 refactoring items  ├─ 2 testing items
├─ 2 weeks             ├─ 2 weeks          ├─ 2 weeks              ├─ 2 weeks
├─ Due: 2026-01-15     ├─ Due: 2026-02-01  ├─ Due: 2026-02-15      ├─ Due: 2026-03-01
└─ Status: Ready       └─ After P0 done    └─ After P1 done        └─ After P2 done
```

## Critical Files You'll Use

### Every Day
1. **EXECUTION_STATE.md** - Check current metrics and blockers
2. **Beads CLI** - `bd ready` to see work

### When Starting Work
3. **METHODOLOGY.md** - Refresh on discipline (test-first, etc.)
4. **Phase file** - Details of current phase

### When Stopping Work
5. **CONTINUATION.md** - Update with findings before break
6. **Checkpoints/** - Create work session record

### When Resuming
7. **CONTINUATION.md** - Refresh on status
8. **EXECUTION_STATE.md** - Check for new blockers
9. **Memory Bank** - Load active session notes

## Risk Management

See **RISK_REGISTER.md** for:

**Critical Risks** (17+):
- R1: Byzantine tolerance violation
- R2: State consistency under concurrent updates
- R3: Consensus message validation gaps

**High Risks** (10-16):
- R4: Recovery protocol robustness
- R5: Ring election consensus bug
- R6: GRPC connection state management
- R7: Rate limiting edge cases
- R8: Regression in existing functionality
- R9: Testing infrastructure gaps

**Medium/Low Risks** (5-9, 1-4):
- R10: Documentation gaps

## Success Criteria

All documented in EXECUTION_STATE.md:

- [ ] All 8 critical issues resolved with tests
- [ ] All 3 high-priority issues addressed
- [ ] 95%+ test coverage for critical paths
- [ ] 100% documentation complete
- [ ] Zero regressions in existing functionality
- [ ] Code review approval from lead architect
- [ ] Staged deployment validated

## Knowledge Base

### ChromaDB (Permanent Storage)

Pre-existing documents:
- `analysis::codebase-deep-analyzer::fireflies-module-2025-01-01`
- `critique::fireflies::deep-analysis-2026-01-01`

Store new findings:
```bash
mcp__chromadb__create_document(
  document_id: "decision::fireflies::issue-name",
  content: "...",
  metadata: {"component": "fireflies", "type": "decision", ...}
)
```

### Memory Bank (Session Work)

Use for temporary session work:
```bash
mcp__allPepper-memory-bank__memory_bank_write(
  projectName: "Delos_active",
  fileName: "phase0-work.md",
  content: "..."
)
```

## Working Methods

### Session Lifecycle

1. **Start**: Read CONTINUATION.md, run `bd ready`
2. **Work**: Pick bead, test-first, create checkpoint
3. **End**: Save state (`/check`), commit, update CONTINUATION.md
4. **Resume**: Load state (`/load`), read CONTINUATION.md, continue

### Commit Pattern

```bash
git commit -m "fix: Short description [<bead-id>]

Longer explanation of change.

Bead: <bead-id>
Related: <other-ids>
"
```

### Checkpoint Pattern

Create `.pm/checkpoints/YYYYMMDD-HHMM-issue-summary.md` with:
- Work completed
- Decisions made
- Blockers encountered
- Next actions
- Metrics update
- Files modified

## Delegation Pattern

When spawning agents:

```markdown
## Handoff: [Agent Name]

**Task**: [1-2 sentence summary]
**Bead**: [ID] (status: [status])

### Input Artifacts
- ChromaDB: [document IDs or "none"]
- Memory Bank: [file path or "none"]
- Files: [key files to examine]

### Deliverable
[What agent should produce]

### Quality Criteria
- [ ] Criterion 1
- [ ] Criterion 2
```

See AGENT_INSTRUCTIONS.md for full protocol.

## Troubleshooting

### "I'm stuck on an issue"
1. Check RISK_REGISTER.md - is it a known risk?
2. Check Memory Bank for active session notes
3. Search ChromaDB for similar issues
4. Check METHODOLOGY.md for discipline guidance
5. Escalate to appropriate agent if needed

### "What are the dependencies?"
1. Run `bd show <id>` - shows dependencies
2. Check phase-*.md for sequencing
3. Check RISK_REGISTER.md - some risks have dependencies

### "I lost context"
1. Run `/load` - restore session state
2. Read CONTINUATION.md
3. Run `bd ready` - see current work
4. Check Memory Bank: `Delos_active` project

### "How do I start Phase N?"
1. Check EXECUTION_STATE.md - previous phases complete?
2. Read `phases/phase-N-*.md` for details
3. Review risk mitigation for phase
4. Run `bd ready` - see available work
5. Start with lowest ID (dependencies flow upward)

## Key Contacts

**For questions/escalations**:

| Issue Type | Contact | When |
|-----------|---------|------|
| Architecture decision | java-architect-planner (opus) | Design question |
| Implementation | java-developer (sonnet) | Coding work |
| Code review | code-review-expert (sonnet) | Before merge |
| Testing | test-validator (sonnet) | Coverage/strategy |
| Complex debugging | java-debugger (opus) | Integration failures |
| Deep analysis | deep-analyst (opus) | Root cause needed |

## Useful Commands

```bash
# Work tracking
bd ready                        # Unblocked work
bd show <id>                    # Bead details
bd update <id> --status in_progress
bd close <id>

# Session
/check                          # Save state
/load                           # Restore state
/sessions                       # List sessions

# Build/test
./mvnw clean install
./mvnw test -pl fireflies
./mvnw clean install -Dlarge_tests=true

# ChromaDB
mcp__chromadb__search_similar(query, num_results=10)
mcp__chromadb__create_document(document_id, content, metadata)

# Memory Bank
mcp__allPepper-memory-bank__memory_bank_write(projectName, fileName, content)
mcp__allPepper-memory-bank__memory_bank_read(projectName, fileName)
```

## File Locations

```
/Users/hal.hildebrand/git/Delos/
├── .pm/                    ← Project management (YOU ARE HERE)
├── fireflies/              ← Fireflies module source
├── choam/                  ← CHOAM module
├── ethereal/               ← Ethereal module
├── CLAUDE.md               ← Build commands reference
├── pom.xml                 ← Maven parent
└── ... (other modules)
```

## Navigation Tips

- **Just started?** → Start with README.md
- **Resuming work?** → Start with CONTINUATION.md
- **Need a task?** → Run `bd ready`
- **Current status?** → Check EXECUTION_STATE.md
- **Issue details?** → Run `bd show <id>`
- **Phase details?** → Read `phases/phase-N-*.md`
- **Risk question?** → Check RISK_REGISTER.md
- **How to work?** → Read METHODOLOGY.md
- **Need to delegate?** → Follow AGENT_INSTRUCTIONS.md

---

**Index Created**: 2026-01-01 08:45 UTC
**Status**: READY TO USE
**Next Step**: Read README.md for quick start
**Questions**: See CONTINUATION.md or METHODOLOGY.md
