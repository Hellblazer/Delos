# Project Management Infrastructure - Complete Index

**Project**: Delos Gorgoneion Security & Quality Remediation
**Location**: `/Users/hal.hildebrand/git/Delos/.pm/`
**Last Updated**: 2026-01-08
**Status**: COMPLETE AND READY FOR USE

---

## Quick Navigation

### For First-Time Users
1. **START HERE**: Read `/Users/hal.hildebrand/git/Delos/.pm/00-START-HERE.md` (5 min)
2. **Project Overview**: Read `/Users/hal.hildebrand/git/Delos/.pm/README.md` (5 min)
3. **Current Status**: Read `/Users/hal.hildebrand/git/Delos/.pm/CONTINUATION.md` (5 min)
4. **Begin Work**: Run `bd ready` to see unblocked work

### For Resuming Work
1. **Restore Session**: Run `/load`
2. **Check Status**: Run `bd list --status=in_progress`
3. **Read Continuation**: See `/Users/hal.hildebrand/git/Delos/.pm/CONTINUATION.md`
4. **Continue Working**: Check last checkpoint for context

### For Quick Reference
- **Current Status**: `/Users/hal.hildebrand/git/Delos/.pm/EXECUTION_STATE.md`
- **How to Work**: `/Users/hal.hildebrand/git/Delos/.pm/METHODOLOGY.md`
- **Phase Details**: See appropriate phase file below
- **All Risks**: `/Users/hal.hildebrand/git/Delos/.pm/RISK_REGISTER.md`

---

## Core Documentation Files

### Essential Daily Use (2-5 min each)

| File | Purpose | Best For |
|------|---------|----------|
| **00-START-HERE.md** | Entry point, quick orientation | New to project |
| **CONTINUATION.md** | Resume context, current phase | Resuming work |
| **EXECUTION_STATE.md** | Real-time metrics, timeline | Daily standup |
| **README.md** | Project overview, architecture | Understanding scope |

### Engineering Discipline (10-15 min)

| File | Purpose | When Needed |
|------|---------|-------------|
| **METHODOLOGY.md** | How to work, TFD, security-first | Starting new issue |
| **AGENT_INSTRUCTIONS.md** | Delegating to agents | Spawning agents |
| **RISK_REGISTER.md** | All identified risks | Planning phase work |

### Planning & Navigation (Reference as needed)

| File | Purpose | When Needed |
|------|---------|-------------|
| **INDEX.md** | This file, complete navigation | Finding something |
| **SETUP_SUMMARY.md** | What was created, stats | Understanding infrastructure |

---

## Phase Documentation

### Phase 0: Critical Security Issues (1-2 weeks)

**File**: `/Users/hal.hildebrand/git/Delos/.pm/phases/phase-0-critical-security.md`

**What**: 4 critical security vulnerabilities
- Cryptographic validation
- Authentication bypass prevention
- Key management security
- Attestation protocol enforcement

**Success Gate**: All 4 fixed, code review approved, no regressions

**Use This When**: Planning Phase 0 work, understanding security requirements

---

### Phase 1: High-Priority Design Issues (2-3 weeks)

**File**: `/Users/hal.hildebrand/git/Delos/.pm/phases/phase-1-high-priority-design.md`

**What**: 4 architectural improvements
- Service bootstrap
- Dependency injection
- State consistency
- Error handling

**Success Gate**: Design review approved, state machine verified

**Use This When**: Phase 0 complete, planning Phase 1

---

### Phase 2: Code Quality (3-4 weeks)

**File**: `/Users/hal.hildebrand/git/Delos/.pm/phases/phase-2-code-quality.md`

**What**: 29 quality improvements
- Test coverage gaps (~10)
- Documentation deficiencies (~8)
- Performance issues (~6)
- Refactoring needs (~5)

**Success Gate**: >95% critical, >85% overall coverage

**Use This When**: Phase 1 complete, planning Phase 2

---

### Phase 3: Validation, Integration & Closure (2 weeks)

**File**: `/Users/hal.hildebrand/git/Delos/.pm/phases/phase-3-validation-closure.md`

**What**: Final validation and production readiness
- Integration testing
- Performance baseline
- Production readiness checklist
- Team knowledge transfer
- Deployment planning

**Success Gate**: All integration tests pass, stakeholder sign-off obtained

**Use This When**: Phase 2 complete, final validation

---

## Work Directories

### Checkpoints (Record Your Work Sessions)

**Location**: `/Users/hal.hildebrand/git/Delos/.pm/checkpoints/`

**What**: Session records documenting progress on each issue

**Structure**: `YYYYMMDD-HHMM-[Delos-XXXX].md`

**Template**: `/Users/hal.hildebrand/git/Delos/.pm/checkpoints/TEMPLATE-checkpoint.md`

**How to Use**:
1. When starting an issue: Create checkpoint file
2. During work: Update with progress
3. End of session: Save checkpoint, commit code
4. Next session: Read checkpoint for context

**Key Sections**:
- Work Completed This Session
- Key Learnings
- Testing Coverage
- Blockers Encountered
- Next Steps
- Commit Information

**Example**: `/Users/hal.hildebrand/git/Delos/.pm/checkpoints/20260108-1430-Delos-1001.md`

---

### Learnings (Persistent Knowledge)

**Location**: `/Users/hal.hildebrand/git/Delos/.pm/learnings/`

**What**: Insights and discoveries that should be remembered

**Structure**: `[YYYYMMDD]-[topic-name].md`

**Template**: `/Users/hal.hildebrand/git/Delos/.pm/learnings/TEMPLATE-learning.md`

**How to Use**:
1. After discovering something important: Create learning file
2. Document: What was learned, why it matters, evidence
3. Share: Store in ChromaDB if foundational
4. Reference: Link from related checkpoint files

**When to Create**:
- Discovered a design pattern
- Found unexpected behavior
- Learned how component works
- Validated/rejected assumption
- Solved a tricky problem

**Key Sections**:
- The Learning (what was discovered)
- Evidence (how we know this)
- Why It Matters
- Implications
- Related Issues

---

### Hypotheses (Design Decisions)

**Location**: `/Users/hal.hildebrand/git/Delos/.pm/hypotheses/`

**What**: Design decisions and solution approaches before implementation

**Structure**: `[YYYYMMDD]-[hypothesis-topic].md`

**Template**: `/Users/hal.hildebrand/git/Delos/.pm/hypotheses/TEMPLATE-hypothesis.md`

**How to Use**:
1. Before implementing: Propose hypothesis
2. Validate: Run tests, gather evidence
3. Decision: Approve or reject
4. Track: Link to implementation bead
5. Document: Learning from decision

**When to Create**:
- Multiple approaches possible
- Complex architectural decision
- Security implications
- Performance implications
- Uncertain about best approach

**Key Sections**:
- Problem & Proposed Solution
- Rationale & Trade-offs
- Validation Criteria
- Testing Approach
- Investigation Results
- Final Decision

---

### Audits (Quality Gates)

**Location**: `/Users/hal.hildebrand/git/Delos/.pm/audits/`

**What**: Phase completion audits and quality gates

**Structure**: `phase-[N]-audit.md`

**How to Use**:
1. When completing phase: Create audit file
2. Document: What was checked, results
3. Verify: All success criteria met
4. Sign-off: Get approvals before proceeding
5. Archive: Keep for future reference

**Phase Gate Audits**:
- phase-0-audit.md - Security issues closed, tests pass
- phase-1-audit.md - Design issues fixed, architecture verified
- phase-2-audit.md - Code quality targets met, coverage verified
- phase-3-audit.md - Production readiness confirmed

---

### Thinking (Deep Analysis)

**Location**: `/Users/hal.hildebrand/git/Delos/.pm/thinking/`

**What**: Deep analysis, investigation notes, problem-solving sessions

**When to Create**:
- Complex problem investigation
- Multiple possible solutions
- Understanding unfamiliar code
- Performance analysis
- Byzantine failure scenarios
- Integration troubleshooting

**How to Use**:
1. Document your thinking process
2. Show evidence gathering
3. Explain reasoning
4. Conclude with insights
5. Link to learnings/hypotheses

**Structure**: `[YYYYMMDD]-[investigation-topic].md`

---

### Metrics (Progress Tracking)

**Location**: `/Users/hal.hildebrand/git/Delos/.pm/metrics/`

**What**: Performance metrics, progress charts, quality measurements

**Files to Create**:
- `code-coverage.md` - Test coverage by phase
- `performance-baseline.md` - Performance metrics
- `issue-velocity.md` - Beads closed per week
- `quality-trends.md` - Code quality improvements
- `risk-status.md` - Risk changes over time

**How to Use**:
1. End of each week: Update metrics
2. End of each phase: Comprehensive metrics
3. Dashboard: EXECUTION_STATE.md shows summary
4. Track: Trends to verify we're on schedule

---

## Configuration & Setup Files

### SETUP_SUMMARY.md
**What**: Summary of what was created, statistics
**When to Read**: If wondering what infrastructure exists
**Location**: `/Users/hal.hildebrand/git/Delos/.pm/SETUP_SUMMARY.md`

---

## External Knowledge Integration

### ChromaDB Integration
**Purpose**: Permanent knowledge storage across sessions

**How to Store**:
```bash
# Create new knowledge document
mcp__chromadb__create_document(
  document_id: "decision::gorgoneion::issue-name",
  content: "Full decision documentation...",
  metadata: {"component": "gorgoneion", "type": "decision"}
)
```

**Naming Convention**: `{domain}::{component}::{topic}`

**When to Store**:
- Significant design decisions
- Security findings
- Performance optimizations
- Integration patterns
- Risk resolutions

**Search Before Starting**:
```bash
mcp__chromadb__search_similar({query: "your topic", num_results: 5})
```

---

### Memory Bank Integration
**Purpose**: Session-specific work state and coordination

**Project Location**: `Delos_active`

**When to Use**:
- Session notes and progress
- Blocker tracking
- Agent coordination
- Temporary findings
- Session state

**Example Files**:
- `Delos_active/gorgoneion-phase0.md` - Phase 0 session notes
- `Delos_active/blockers.md` - Active blockers
- `Delos_active/hypotheses.md` - In-progress hypotheses

---

## Bead-Based Task Tracking

### Bead Structure
```bash
# Epic
Delos-1000: Gorgoneion Security & Quality Remediation (Epic)

# Phase 0 - Critical Security
Delos-1001: Cryptographic Validation (Feature, depends on Epic)
Delos-1002: Authentication Bypass Prevention (Feature, depends on 1001)
Delos-1003: Key Management Security (Feature, depends on 1002)
Delos-1004: Attestation Protocol Enforcement (Feature, depends on 1003)

# Phase 1 - High Priority Design
Delos-1005-1008: [High priority design issues]

# Phase 2 - Code Quality
Delos-1009-1037: [Quality improvements, organized by category]

# Phase 3 - Validation
Delos-1038+: [Integration testing, verification, documentation]
```

### Daily Bead Workflow
```bash
# See what's ready to work
bd ready

# Pick an issue
bd show Delos-XXXX

# Mark in progress
bd update Delos-XXXX --status in_progress

# When done
bd close Delos-XXXX
```

---

## Directory Tree

```
.pm/
├── 00-START-HERE.md                    ← Begin here!
├── INDEX.md                            ← This file
├── README.md                           ← Project overview
├── CONTINUATION.md                     ← Session resumption
├── EXECUTION_STATE.md                  ← Current metrics
├── METHODOLOGY.md                      ← Engineering discipline
├── AGENT_INSTRUCTIONS.md               ← Agent delegation
├── RISK_REGISTER.md                    ← Risk management
├── SETUP_SUMMARY.md                    ← What was created
│
├── phases/                             ← Phase documentation
│   ├── phase-0-critical-security.md    ← 4 security issues (1-2 weeks)
│   ├── phase-1-high-priority-design.md ← 4 design issues (2-3 weeks)
│   ├── phase-2-code-quality.md         ← 29 quality items (3-4 weeks)
│   └── phase-3-validation-closure.md   ← Final validation (2 weeks)
│
├── checkpoints/                        ← Work session records
│   ├── TEMPLATE-checkpoint.md          ← Template for checkpoints
│   ├── 20260108-1430-Delos-XXXX.md    ← Session record example
│   └── ...                             ← More checkpoint files
│
├── learnings/                          ← Persistent insights
│   ├── TEMPLATE-learning.md            ← Template for learnings
│   ├── 20260108-cryptographic-patterns.md
│   └── ...                             ← More learning files
│
├── hypotheses/                         ← Design decisions
│   ├── TEMPLATE-hypothesis.md          ← Template for hypotheses
│   ├── 20260108-Byzantine-consensus.md
│   └── ...                             ← More hypothesis files
│
├── audits/                             ← Phase completion audits
│   ├── phase-0-audit.md               ← Phase 0 gate audit
│   ├── phase-1-audit.md               ← Phase 1 gate audit
│   ├── phase-2-audit.md               ← Phase 2 gate audit
│   └── phase-3-audit.md               ← Phase 3 gate audit
│
├── thinking/                           ← Deep analysis sessions
│   ├── 20260108-Byzantine-scenarios.md
│   ├── 20260110-Stereotomy-integration.md
│   └── ...                             ← More analysis files
│
└── metrics/                            ← Performance tracking
    ├── code-coverage.md               ← Coverage by phase
    ├── performance-baseline.md        ← Performance metrics
    ├── issue-velocity.md              ← Velocity tracking
    └── ...                            ← More metric files
```

---

## Common Workflows

### Starting a New Phase
1. Read phase documentation: `phases/phase-N-*.md`
2. Create beads for all issues in phase
3. Update EXECUTION_STATE.md with phase start
4. Create Memory Bank file for phase: `Delos_active/phase-N-work.md`
5. Run `bd ready` to see work
6. Start with first issue

### Completing an Issue
1. Write test first (TFD)
2. Implement fix
3. Run full test suite: `./mvnw clean install -Dlarge_tests=true`
4. Create checkpoint: `.pm/checkpoints/YYYYMMDD-HHMM-Delos-XXXX.md`
5. Commit with bead reference
6. Close bead: `bd close Delos-XXXX`
7. Create learning if applicable

### Blocked on an Issue
1. Check RISK_REGISTER.md for known risks
2. Check EXECUTION_STATE.md for blockers
3. Create blocker note in Memory Bank
4. Document in checkpoint file
5. Pick different issue if possible
6. Escalate to architecture lead

### End of Day (Session Save)
1. Update current checkpoint with progress
2. Commit any code changes
3. Save session: `/check`

### Resuming Work
1. Restore session: `/load`
2. Check CONTINUATION.md
3. Run `bd list --status=in_progress`
4. Read last checkpoint
5. Continue from where you left off

### Phase Completion
1. Verify all beads for phase are closed
2. Create phase audit: `.pm/audits/phase-N-audit.md`
3. Verify all success criteria met
4. Get sign-offs from reviewers
5. Archive checkpoints for phase
6. Update EXECUTION_STATE.md
7. Begin next phase

---

## Key Files at a Glance

| Need | File | Time |
|------|------|------|
| Quick Start | 00-START-HERE.md | 2 min |
| Full Overview | README.md | 5 min |
| Current Status | CONTINUATION.md | 5 min |
| Metrics/Timeline | EXECUTION_STATE.md | 2 min |
| How to Work | METHODOLOGY.md | 10 min |
| Phase Details | phases/phase-N-*.md | 5-10 min |
| All Risks | RISK_REGISTER.md | 10 min |
| Find Something | INDEX.md | 2 min |
| Delegate Work | AGENT_INSTRUCTIONS.md | 5 min |

---

## Templates Available

All templates follow consistent patterns to reduce decision-making:

| Template | Location | Use When |
|----------|----------|----------|
| **Checkpoint** | `checkpoints/TEMPLATE-checkpoint.md` | Finishing a work session |
| **Learning** | `learnings/TEMPLATE-learning.md` | Discovering important insight |
| **Hypothesis** | `hypotheses/TEMPLATE-hypothesis.md` | Before major implementation |

Copy template, rename, and fill in your content.

---

## Quality Checkpoints

### Daily (Every Standup)
- [ ] All tests passing
- [ ] No new blockers
- [ ] Progress on current issue visible

### End of Day (Session Save)
- [ ] Checkpoint updated
- [ ] Code committed with bead reference
- [ ] Session saved with `/check`
- [ ] No uncommitted changes

### End of Week
- [ ] Update metrics in EXECUTION_STATE.md
- [ ] Review blockers, escalate if needed
- [ ] Create weekly summary checkpoint

### End of Phase
- [ ] All beads closed
- [ ] Phase audit completed
- [ ] Sign-offs obtained
- [ ] Ready to begin next phase

---

## Support & Escalation

### Blocked on an Issue?
1. Check RISK_REGISTER.md
2. Check Memory Bank for Delos_active blockers
3. Add to checkpoint file
4. Contact: Architecture lead
5. Consider picking different issue

### Need Guidance?
1. METHODOLOGY.md - How to work
2. AGENT_INSTRUCTIONS.md - Delegation
3. phases/phase-N-*.md - Phase details
4. README.md - Architecture overview
5. Contact: Architecture lead if unclear

### Found a Bug in Tests?
1. Update appropriate checkpoint
2. Document in Memory Bank
3. Contact: QA lead
4. Create test case demonstrating bug
5. Pick different issue while waiting

### Integration Issues?
1. Check Stereotomy/Fireflies status
2. Update Memory Bank
3. Run integration tests
4. Contact: Integration lead
5. Document findings in thinking directory

---

## Success Indicators

### You're on Track When:
- Completing 1-2 beads per week per developer
- Checkpoints created at end of sessions
- Learnings documented for insights
- Code review approved before merge
- Tests passing consistently
- No regressions in integration

### You're Behind When:
- Beads stalling without progress
- Blockers unresolved for >2 days
- Tests repeatedly failing
- Code quality declining
- Integration tests failing
- Regressions appearing

### You're Done When:
- All 37 beads closed
- Phase 3 audit approved
- Stakeholder sign-off obtained
- Deployment plan ready
- Team trained
- Production ready

---

## Quick Reference Commands

```bash
# See your work
bd ready                    # Unblocked issues
bd show Delos-XXXX         # Details of specific issue
bd list --status=in_progress # Currently working on

# Mark work status
bd update Delos-XXXX --status in_progress
bd close Delos-XXXX        # Done with issue

# Session management
/check                     # Save state (before break)
/load                      # Restore state (when resuming)

# Build and test
./mvnw clean install       # Full build
./mvnw test -pl gorgoneion  # Test module only
./mvnw clean install -Dlarge_tests=true  # Full integration tests

# Find files
find .pm -name "*gorgoneion*"
find .pm/checkpoints -name "*.md" | sort
ls -ltr .pm/checkpoints    # Most recent first
```

---

**Last Updated**: 2026-01-08 10:30 UTC
**Status**: COMPLETE AND READY
**Total Files**: 18+ core files + phase details + templates
**Total Documentation**: 150+ pages of guidance
**Automation**: Beads for all 37 issues, templates for all work types

You have everything you need. Start with 00-START-HERE.md!
