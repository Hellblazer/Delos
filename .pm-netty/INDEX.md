# Netty Elimination Project - Navigation Index

**Project**: Netty Native Library Elimination
**Updated**: 2026-01-15
**Quick Links**: Below

---

## Essential Documents (Read These First)

| Document | Purpose | Read When | Time |
|----------|---------|-----------|------|
| [README.md](README.md) | Project overview, quick start | First visit | 10 min |
| [CONTINUATION.md](CONTINUATION.md) | Resume from where you left off | Starting session | 5 min |
| [METHODOLOGY.md](METHODOLOGY.md) | How to approach the work | Before Phase 1 | 15 min |
| [execution_state.json](execution_state.json) | Current state, phases, beads | Check status | 2 min |

## Management & Planning

| Document | Purpose | Update Frequency | Audience |
|----------|---------|-----------------|----------|
| [execution_state.json](execution_state.json) | Project status, phases, metrics | After each session | Team |
| [CONTINUATION.md](CONTINUATION.md) | Session handoff template | Before ending session | Next session |
| [metrics/progress.md](metrics/progress.md) | Completion %, bead tracking | After each bead | All |
| [metrics/performance.md](metrics/performance.md) | Before/after performance data | Phase 4 | Technical |
| [RISK_REGISTER.md](RISK_REGISTER.md) | Risk analysis, blockers | As risks change | Team |
| [checkpoints/](checkpoints/) | Session progress snapshots | After milestones | All |

## Implementation Guidance

| Document | Purpose | Use When | Details |
|----------|---------|----------|---------|
| [METHODOLOGY.md](METHODOLOGY.md) | Engineering discipline | Planning work | Testing strategy, safe execution |
| [checkpoints/TEMPLATE.md](checkpoints/TEMPLATE-checkpoint.md) | Record session progress | Session complete | What to document |
| Phase 1 code locations | Where to make changes | Starting Phase 1 | See README.md section "Important Files" |

## Knowledge Base

| Location | Content | Access | Status |
|----------|---------|--------|--------|
| ChromaDB: `debug::isolates::netty-native-library-conflict` | Root cause analysis | Search | Complete |
| ChromaDB: `research::isolates::nio-domain-sockets-solution` | JEP 380 research | Search | Complete |
| Memory Bank: `delos_active/netty-elimination-findings.md` | Session discoveries | Read/Write | In use |
| Memory Bank: `delos_active/netty-elimination-blockers.md` | Blocking issues | Read/Write | In use |
| Memory Bank: `delos_active/netty-elimination-test-results.md` | Test results & logs | Read/Write | In use |

## Project Structure

```
.pm-netty/                     # PM Infrastructure
├── README.md                  # Project overview (START HERE)
├── CONTINUATION.md            # Session resumption guide
├── METHODOLOGY.md             # Engineering discipline
├── RISK_REGISTER.md           # Risk analysis (terminal hangs, perf)
├── INDEX.md                   # This file
├── execution_state.json       # Current state & phases
│
├── checkpoints/               # Session progress snapshots
│   ├── TEMPLATE-checkpoint.md # Template for new checkpoints
│   ├── phase1-checkpoint-1.md # Example: protocols module
│   └── [future checkpoints]
│
├── metrics/                   # Progress & performance tracking
│   ├── progress.md           # Completion %, beads, milestones
│   ├── performance.md        # Before/after performance metrics
│   └── bead-tracking.md      # Bead status summary
│
├── learnings/                # Key insights & decisions
│   ├── L0-socket-compat.md   # Learning about NIO vs native
│   ├── L1-performance.md     # Performance expectations
│   └── [future learnings]
│
├── hypotheses/               # Technical decisions
│   ├── H0-nio-approach.md    # Why NIO not other solutions
│   └── [future hypotheses]
│
└── audits/                   # Quality gates & reviews
    ├── code-review-checklist.md
    └── [future audits]
```

## Quick Navigation by Role

### If You're Starting the Project
1. Read [README.md](README.md) (overview)
2. Read [CONTINUATION.md](CONTINUATION.md) (context)
3. Read [METHODOLOGY.md](METHODOLOGY.md) (approach)
4. Check `bd list --status=ready`
5. Start with Phase 1

### If You're Resuming After a Break
1. Read [CONTINUATION.md](CONTINUATION.md) (what you were doing)
2. Check latest checkpoint in [checkpoints/](checkpoints/)
3. Review [metrics/progress.md](metrics/progress.md) (where you left off)
4. Check [RISK_REGISTER.md](RISK_REGISTER.md) (new blockers?)
5. Pick up from "Next Steps" in latest checkpoint

### If You're Reviewing Progress
1. Check [metrics/progress.md](metrics/progress.md) (completion %)
2. Review latest checkpoint in [checkpoints/](checkpoints/)
3. Check [RISK_REGISTER.md](RISK_REGISTER.md) (active risks)
4. Check `bd list` for bead status

### If You're Troubleshooting an Issue
1. Check [RISK_REGISTER.md](RISK_REGISTER.md) (known risks)
2. Search [checkpoints/](checkpoints/) for similar issues
3. Check Memory Bank for findings
4. Check ChromaDB for research
5. Update RISK_REGISTER.md with new blocker if needed

### If You're Planning the Next Phase
1. Check [execution_state.json](execution_state.json) (current phase)
2. Review phase success gates
3. Check progress towards completion
4. Review RISK_REGISTER.md for phase-specific risks
5. Plan next phase beads

## Document Purposes Explained

### README.md
**What**: Project overview, quick start guide, file locations
**Read When**: First visit, orientation
**Update**: Rarely, only for major changes

### CONTINUATION.md
**What**: How to resume from where you left off
**Read When**: Starting a new session
**Update**: Before ending each session with latest context

### METHODOLOGY.md
**What**: Engineering discipline, testing strategy, phase gating
**Read When**: Before starting Phase 1, when uncertain about approach
**Update**: If approach changes significantly

### RISK_REGISTER.md
**What**: Detailed risk analysis (terminal hangs, performance, compatibility)
**Read When**: Before Phase 4 (isolate testing), if issues occur
**Update**: As risks discovered or resolved

### execution_state.json
**What**: Project metadata, phases, success criteria, beads, blockers
**Read When**: Need current status, metrics, upcoming milestones
**Update**: Regularly as project progresses

### progress.md
**What**: Completion percentages, test results, performance data
**Read When**: Tracking project progress, reporting status
**Update**: After each bead completion, after each phase

### checkpoints/TEMPLATE-checkpoint.md
**What**: Template for recording session progress
**Read When**: Need guidance on what to document
**Use**: Copy to new checkpoint file after session

### Recent Checkpoints
**What**: Snapshots of work completed in each session
**Read When**: Need to understand what was done, when, and why
**Location**: checkpoints/ directory (sorted by date)

---

## File Location Reference

### Code to Update (Phase 1)
- `protocols/src/main/java/com/hellblazer/delos/comm/grpc/DomainSocketServerInterceptor.java`
- `model/src/main/java/com/hellblazer/delos/model/demesnes/DemesneImpl.java`
- `memberships/src/main/java/com/hellblazer/delos/archipelago/Enclave.java`
- `isolates/src/main/java/...` (various files)
- `isolate-ftesting/src/test/java/...` (test infrastructure)

### Modules to Delete (Phase 2)
- `domain-kqueue/` (entire directory)
- `domain-epoll/` (entire directory)
- `domain-sockets/` (entire directory)

### Configuration to Update
- `pom.xml` - Remove native library dependencies
- `isolates/pom.xml` - Update or remove native profiles

### Metadata Files
- `isolates/src/main/resources/META-INF/native-image/reachability-metadata.json`
- `isolates/src/main/resources/META-INF/native-image/resource-config.json` (delete)

### Test Files
- `isolate-ftesting/src/test/java/com/hellblazer/delos/model/demesnes/DemesneIsolateTest.java`
- Various protocol and model tests

---

## Bead Management

### Bead Commands
```bash
# List ready beads
bd list --status=ready

# Show specific bead
bd show Delos-1b9y

# Update bead status
bd update <id> --status in_progress
bd close <id>

# Create bead (if needed)
bd create "Phase 1: Update protocols module" -t task -p 1

# Add dependency
bd dep add <id> <blocker-id>
```

### Bead Naming Convention
- Epic: `Delos-1b9y` (main elimination epic)
- Phase 1: `Delos-1b9y-p1-[module]`
- Phase 2: `Delos-1b9y-p2-delete`
- Phase 3: `Delos-1b9y-p3-metadata`
- Phase 4: `Delos-1b9y-p4-[test-suite]`

---

## Key Metrics & Success Criteria

### Phase 1 Success
- All 5 modules updated to use NIO
- Code compiles without errors
- All protocol tests pass
- No terminal hangs

### Phase 2 Success
- 3 modules deleted
- No dangling references

### Phase 3 Success
- Native metadata removed
- GraalVM config updated

### Phase 4 Success
- All tests pass
- Performance < 20% degradation
- No terminal hangs
- Isolates functional

### Overall Success
- All phases complete
- All tests pass
- Isolates working
- Documentation updated

---

## Communication Guidelines

### Update Documentation
**When**: After completing any significant work
**What**: Update checkpoint, metrics, RISK_REGISTER if needed
**Where**: Respective files in `.pm-netty/`

### Escalate Issues
**How**: Update RISK_REGISTER.md with new blocker
**Include**: Impact, mitigation, owner, status
**When**: Immediately if blocking progress

### Document Decisions
**How**: Create entry in `learnings/` or `hypotheses/`
**Include**: Decision, rationale, impact
**When**: After significant decision or learning

### Session Handoff
**What**: Complete checkpoint before ending session
**Include**: What done, what tested, next steps, blockers
**Where**: `checkpoints/phase-X-checkpoint-Y.md`

---

## Troubleshooting Navigation

| Problem | Solution | References |
|---------|----------|-----------|
| Terminal hangs during isolate test | See "Terminal Hang Risk" in RISK_REGISTER.md | RISK-NIO-001 |
| Performance degradation > 20% | See "Performance Degradation" in RISK_REGISTER.md | RISK-NIO-002 |
| NIO incompatibility found | See "NIO Compatibility" in RISK_REGISTER.md | RISK-NIO-003 |
| Unsure about approach | Read METHODOLOGY.md for discipline | Entire document |
| Need to know what was done | Check latest checkpoint in checkpoints/ | Sorted by date |
| Can't find where to make changes | See README.md section "Important Files" | File locations |
| Module not updating correctly | Check Phase 1 checklist in METHODOLOGY.md | Code review section |

---

## Timeline Reference

| Date | Milestone | Phase | Status |
|------|-----------|-------|--------|
| 2026-01-15 | Infrastructure setup | Setup | COMPLETE |
| 2026-01-17 | protocols module | Phase 1 | TBD |
| 2026-01-18 | model module | Phase 1 | TBD |
| 2026-01-19 | memberships module | Phase 1 | TBD |
| 2026-01-22 | Phase 1 complete | Phase 1 | TBD |
| 2026-01-23 | Phase 2 complete | Phase 2 | TBD |
| 2026-01-24 | Phase 3 complete | Phase 3 | TBD |
| 2026-02-05 | Project complete | Phase 4 | TBD |

---

## Resource Guide

### Tools Used
- **Beads** (`bd`): Task tracking and dependencies
- **Maven** (`./mvnw`): Build and test execution
- **Git**: Version control and commits
- **ChromaDB**: Knowledge base (prior art)
- **Memory Bank**: Session state storage

### Essential Commands
```bash
# Beads
bd list --status=ready
bd show <id>
bd update <id> --status in_progress
bd close <id>

# Maven
./mvnw compile -amd -pl <module>
./mvnw test -pl <module>
./mvnw clean install

# Testing (Phase 4 - with caution)
timeout 30s ./mvnw test -pl isolate-ftesting -Pisolates

# Git
git status
git add .pm-netty/...
git commit -m "Progress: Phase X - ..."
```

---

## For Strategic Planner Integration

If strategic planner produces detailed plan:
1. Compare with Phase 1-4 in execution_state.json
2. Reconcile timeline differences
3. Update beads if sequencing differs
4. Note reconciliation in checkpoint
5. Follow plan's sequencing while maintaining methodology discipline

---

**Document Control**:
- Created: 2026-01-15
- Last Updated: 2026-01-15
- Status: ACTIVE
- Version: 1.0
