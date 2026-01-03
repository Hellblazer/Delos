# Stereotomy KERI Security Remediation - Project Management

**Project**: Delos Stereotomy KERI Security Remediation
**Status**: PHASE 0 - INITIATING
**Duration**: 4-6 weeks (6 phases × 3-5 days each)
**Methodology**: Test-Driven Design (TDD) for Security

## Quick Start

**First time here?** Read these in order:
1. **CONTINUATION.md** - Current status and what to do next (5 min)
2. **EXECUTION_STATE.md** - Project metrics and phase details (10 min)
3. **METHODOLOGY.md** - How we work and process (15 min)
4. **AGENT_INSTRUCTIONS.md** - For spawned agents (10 min)

**Resuming after break?** Just read **CONTINUATION.md** (2 min)

## Project Overview

This project remediates 6 critical security vulnerabilities in the Delos stereotomy module (KERI implementation) using Test-Driven Design methodology. Each vulnerability is addressed systematically across 6 phases with comprehensive testing and validation.

### The 6 Critical Issues

| Priority | Issue | File | Impact | Status |
|----------|-------|------|--------|--------|
| 1 | CRIT-1: XOR Commutativity | KeyConfigurationDigester.java | HIGH | Pending |
| 2 | CRIT-2: Race in Append | MemKERL.java | HIGH | Pending |
| 3 | CRIT-3: No Inception Sig | KeyEventProcessor.java | CRITICAL | Pending |
| 4 | CRIT-4: No Transactions | UniKERL.java | HIGH | Pending |
| 5 | CRIT-5: Non-Atomic State | KeyStateProcessor.java | HIGH | Pending |
| 6 | CRIT-6: Key Not Cleared | JksKeyStore.java | MEDIUM | Pending |

## Phase Roadmap

```
Phase 0: TDD Setup (3-4 days)          [INITIATING]
  └─ Write 6 failing security tests

Phase 1: Fix CRIT-1 (2-3 days)         [BLOCKED]
  └─ XOR → Order-preserving hash

Phase 2: Fix CRIT-2,4,5 (4-5 days)     [BLOCKED]
  └─ Concurrency & Transactions

Phase 3: Fix CRIT-3 (2 days)           [BLOCKED]
  └─ Inception signature verification

Phase 4: Fix CRIT-6 (2-3 days)         [BLOCKED]
  └─ Key material handling

Phase 5: Audit & Stress (3-4 days)     [BLOCKED]
  └─ Comprehensive validation

Phase 6: Release (1-2 days)            [BLOCKED]
  └─ Merge to main branch
```

## Directory Structure

```
.pm-stereotomy/
├── README.md                      ← You are here
├── EXECUTION_STATE.md             ← Current phase and metrics
├── CONTINUATION.md                ← Resume guide (read this!)
├── METHODOLOGY.md                 ← Process & discipline
├── AGENT_INSTRUCTIONS.md          ← For spawned agents
│
├── checkpoints/
│   ├── TEMPLATE.md               ← Copy for each phase completion
│   ├── phase-0-complete.md       ← (To be created)
│   ├── phase-1-complete.md       ← (To be created)
│   └── ...
│
├── hypotheses/
│   ├── TEMPLATE.md               ← Copy for each design decision
│   ├── H-1-xor-fix.md           ← (To be created)
│   ├── H-2-race-fix.md          ← (To be created)
│   └── ...
│
├── learnings/
│   ├── TEMPLATE.md               ← Copy for each insight
│   ├── L-1-xor-commutativity.md ← (To be created)
│   ├── L-2-race-conditions.md   ← (To be created)
│   └── ...
│
├── metrics/
│   ├── baseline.md               ← Phase 0 metrics (to be created)
│   ├── phase-1-metrics.md        ← Phase 1 metrics (to be created)
│   └── ...
│
├── audits/
│   ├── pre-audit.md              ← Before final security audit
│   └── post-audit.md             ← After final security audit
│
├── thinking/
│   └── phase-0-analysis.md       ← Deep analysis sessions
│
└── tests/
    └── security-test-plan.md     ← Test strategy for Phase 0
```

## Key Documents

### Essential (Read First)
- **CONTINUATION.md** - Quick resume guide with immediate actions
- **EXECUTION_STATE.md** - Current status, metrics, blockers
- **METHODOLOGY.md** - How we work, quality gates, success criteria

### For Implementation
- **AGENT_INSTRUCTIONS.md** - Spawn agents with this context
- **hypotheses/TEMPLATE.md** - Document design decisions
- **checkpoints/TEMPLATE.md** - Document phase completions

### For Learning
- **learnings/TEMPLATE.md** - Record insights and lessons

## Technology Stack

- **Java**: 23+ (var, records, pattern matching)
- **Testing**: JUnit 5, Mockito, AssertJ
- **Build**: Maven, local repository
- **Cryptography**: Bouncy Castle
- **Database**: H2 (with deterministic module)
- **Concurrency**: ReentrantLock, ConcurrentHashMap

## Bead IDs (To Be Created)

```
Stereotomy KERI Security Remediation    [TBD - Epic]
├─ Phase 0: TDD Setup                   [TBD - Task]
├─ CRIT-1: XOR Commutativity           [TBD - Bug, P1]
├─ CRIT-2: Race Condition              [TBD - Bug, P1]
├─ CRIT-3: Inception Signature         [TBD - Bug, P1]
├─ CRIT-4: Transaction Boundaries      [TBD - Bug, P1]
├─ CRIT-5: Atomic State                [TBD - Bug, P1]
├─ CRIT-6: Key Material Clearing       [TBD - Bug, P1]
└─ Phases 1-6 Tasks                    [TBD - Tasks]
```

Create these today: `bd create "..." -t [type] -p [priority]`

## Success Metrics

### Phase 0 Success
- ✅ 6 failing security tests written
- ✅ Baseline metrics captured
- ✅ No regressions in codebase
- ✅ Ready for Phase 1

### Overall Success
- ✅ All 6 CRIT-X issues fixed
- ✅ All tests passing (100%)
- ✅ Performance acceptable (±10%)
- ✅ Security audit passed
- ✅ Ready for merge to main

## Analysis Documents

All critical information available in ChromaDB:

**Primary**: `critique::stereotomy::deep-analysis-2026-01-02`
- Deep analysis of all 6 vulnerabilities
- Attack vectors and impact assessment
- Evidence-based recommendations
- Test strategy for each issue

**Secondary**: `analysis::codebase-deep-analyzer::stereotomy-module-2026-01-02`
- Module architecture and components
- 88 source files analyzed
- Integration points with other modules
- Test coverage assessment

**Reference**: `delos::pattern::keri-identity`
- KERI pattern documentation
- Identity and cryptographic concepts

## Branch & Git

**Branch**: `feat/stereotomy-review`
**Parent**: `main` (but work on feature branch)
**Commit Style**: `Fix CRIT-X: [Description] [Bead-ID]`

Example: `Fix CRIT-1: XOR commutativity attack [Delos-abc]`

**NO AI Attribution** (company policy) - Use professional technical content.

## Useful Commands

### Create Beads
```bash
# Epic for project
bd create "Stereotomy KERI Security Remediation" -t epic -p 0

# Phase task
bd create "Phase 0: TDD & Security Tests Setup" -t task -p 1

# Critical issue
bd create "CRIT-1: XOR Commutativity Attack" -t bug -p 1
```

### Build & Test
```bash
# Build module
mvn clean install -amd -pl stereotomy -DskipTests

# Run security tests
mvn test -Dtest=SecurityTests -pl stereotomy

# Run all tests
mvn test -pl stereotomy
```

### Search & Reference
```bash
# Search ChromaDB
# Use WebSearch tool with queries:
# - "stereotomy KERI security vulnerabilities"
# - "KeyConfigurationDigester XOR"
# - etc.

# View memory bank
# mcp__allPepper-memory-bank__memory_bank_read("delos_active", "stereotomy-phase-0.md")
```

## Process Overview

### TDD Approach (Phase 0)
1. Write failing test (demonstrates vulnerability)
2. Verify test fails
3. Run baseline metrics
4. Document in checkpoint

### Fix Approach (Phases 1-5)
1. Design fix (document in hypothesis)
2. Implement fix (minimal change)
3. Verify test passes
4. Run regression suite
5. Code review and approval
6. Document in checkpoint

### Audit Approach (Phase 5)
1. Run all security tests (6 tests)
2. Run stress test (1000+ events)
3. Analyze memory and performance
4. Security audit checklist
5. Final validation

## Quality Gates

**Phase 0**: All 6 tests fail consistently, baseline metrics ready
**Phase 1**: CRIT-1 test passes, regression suite passes, code review approved
**Phase 2**: CRIT-2,4,5 tests pass, regression suite passes, performance acceptable
**Phase 3**: CRIT-3 test passes, regression suite passes, code review approved
**Phase 4**: CRIT-6 tests pass, regression suite passes, no new vulnerabilities
**Phase 5**: Stress test passes, no memory leaks, audit checklist complete
**Phase 6**: Ready for merge to main

## Getting Started (Today)

### Immediate Actions
1. Create beads for epic and all 6 CRIT issues
2. Create Phase 0 task bead
3. Set up `.pm-stereotomy/metrics/baseline.md` file
4. Read CONTINUATION.md for next steps

### This Week
- Write 6 failing security tests (Phase 0)
- Establish baseline metrics
- Verify all tests fail consistently
- Complete Phase 0 checkpoint

### Next Week
- Begin Phase 1: Fix CRIT-1
- Implement XOR → hash change
- Verify test passes
- Code review and approval

## Help & Support

### If Stuck
1. Check CONTINUATION.md (quick resume)
2. Search ChromaDB for analysis documents
3. Reference Fireflies project `.pm/` (similar pattern)
4. Check METHODOLOGY.md for process guidance

### Documentation
- **CLAUDE.md** - Global project directives
- **METHODOLOGY.md** - Engineering discipline
- **AGENT_INSTRUCTIONS.md** - Agent spawning patterns
- **Fireflies `.pm/`** - Reference implementation

## Contact & Attribution

**Project Owner**: Hal Hildebrand
**Branch**: feat/stereotomy-review
**Start Date**: 2026-01-02

---

**Last Updated**: 2026-01-02
**Status**: PHASE 0 INITIATING
**Next Review**: 2026-01-04 (after Phase 0 TDD setup)
