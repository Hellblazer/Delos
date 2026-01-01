# Delos Remediation - Project Management Infrastructure

## Quick Start

### Resuming Work

1. Read current context:
   ```
   Read: .pm/CONTINUATION.md
   ```

2. Check ready tasks:
   ```bash
   bd ready
   ```

3. Check execution state:
   ```
   Read: .pm/EXECUTION_STATE.md
   ```

### Starting a Task

1. Update bead status:
   ```bash
   bd update <id> --status in_progress
   ```

2. Read phase details:
   ```
   Read: .pm/phases/PHASE_<N>_<NAME>.md
   ```

3. Follow methodology:
   ```
   Read: .pm/METHODOLOGY.md
   ```

---

## Directory Structure

```
.pm/
  CONTINUATION.md          # Session resumption context
  EXECUTION_STATE.md       # Detailed progress tracking
  METHODOLOGY.md           # Engineering discipline standards
  AGENT_INSTRUCTIONS.md    # Instructions for spawned agents
  KNOWLEDGE_MAP.md         # Knowledge organization
  RISK_REGISTER.md         # Project risks
  README.md                # This file

  phases/
    PHASE_0_CRITICAL_BUGS.md    # Critical bug fixes
    PHASE_1_SECURITY.md         # Security concerns
    PHASE_2_API_COMPLETION.md   # API implementation
    PHASE_3_DOCUMENTATION.md    # Documentation tasks
    PHASE_4_ARCHITECTURE.md     # Architecture review

  checkpoints/             # Progress checkpoints
  learnings/               # Accumulated knowledge
  hypotheses/              # Architectural decisions
  audits/                  # Quality gates
  thinking/                # Deep analysis
  metrics/                 # Performance metrics
```

---

## File Purposes

| File | Purpose | When to Read |
|------|---------|--------------|
| CONTINUATION.md | Resume after break | Start of every session |
| EXECUTION_STATE.md | Track progress | Check status, update after work |
| METHODOLOGY.md | Engineering standards | Before implementing |
| AGENT_INSTRUCTIONS.md | Agent protocols | When spawning agents |
| KNOWLEDGE_MAP.md | Find documents | When searching for context |
| RISK_REGISTER.md | Risk awareness | When encountering issues |

---

## Phase Overview

| Phase | Focus | Duration | Priority |
|-------|-------|----------|----------|
| 0 | Critical Bugs | Week 1 | P0 |
| 1 | Security | Week 2 | P1 |
| 2 | API Completion | Week 3-4 | P2 |
| 3 | Documentation | Week 5 | P3 |
| 4 | Architecture | Week 6-8 | P4 |

---

## Bead Workflow

### Creating Tasks

```bash
# Create bug fix
bd create "Fix Ethereal switch fall-through" -t bug -p 1

# Create security fix
bd create "Add crypto hash to BloomFilter" -t security -p 2

# Create feature
bd create "Implement Watch API" -t feature -p 3
```

### Status Updates

```bash
bd update <id> --status in_progress  # Starting work
bd update <id> --status blocked      # Hit blocker
bd update <id> --status review       # Ready for review
bd close <id>                        # Complete
```

### Adding Notes

```bash
bd note <id> "Found root cause: switch fall-through"
bd note <id> "Test written, implementation in progress"
```

---

## Knowledge Sources

### ChromaDB

Primary knowledge base for cross-session context.

```
# Search for context
mcp__chromadb__search_similar(query, num_results=10)

# Key documents
critique::master::delos-codebase-review-2025-12-30
crossref::<module>::implementation
```

### Mixedbread

Academic papers for algorithm reference.

```
# Search papers
mcp__mixedbread__store_search(
    query="<algorithm>",
    store_identifiers=["delos"]
)

# Store: "delos" (18 papers, 774K tokens)
```

### Memory Bank

Session-level working memory.

```
# Read active state
mcp__allPepper-memory-bank__memory_bank_read(
    projectName="Delos_active",
    fileName="current_phase.md"
)
```

---

## Build Commands

```bash
# First-time setup
./mvnw clean install -Ppre -DskipTests

# Standard build
./mvnw clean install

# Run all tests
./mvnw test

# Single module
./mvnw test -pl <module>

# Single test
./mvnw test -Dtest=ClassName#method

# Full test suite
./mvnw clean install -Dlarge_tests=true
```

---

## Agent Reference

| Agent | Model | Use Case |
|-------|-------|----------|
| java-developer | sonnet | Implementation |
| java-architect-planner | opus | Design |
| java-debugger | opus | Bug analysis |
| code-review-expert | sonnet | Code review |
| deep-research-synthesizer | opus | Paper research |

---

## Updating This Infrastructure

### After Completing Task

1. Update EXECUTION_STATE.md task status
2. Update bead status
3. Add checkpoint if significant

### After Completing Phase

1. Update CONTINUATION.md current phase
2. Update EXECUTION_STATE.md phase status
3. Update next actions

### When Finding New Issues

1. Add to RISK_REGISTER.md if risk
2. Create bead for tracking
3. Add to appropriate phase file

---

## Session Hooks

The PM infrastructure integrates with session hooks:

### SessionStart Hook

Automatically loads:
- Current phase from CONTINUATION.md
- Next action
- Blockers
- Ready beads

### PreCompact Hook

Reminds to save:
- Current progress
- Any new learnings
- Updated context

---

*Last Updated: 2025-12-30*
