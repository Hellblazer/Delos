# Context Protocol & Session Lifecycle

**Project**: CHOAM Security & Architecture Refactoring
**Version**: 1.0
**Effective Date**: 2026-01-09

---

## Purpose

This document defines the standard protocol for context management across sessions and agent handoffs. All agents working on this project MUST follow this protocol.

---

## Session Lifecycle

### SessionStart

When starting a new session or resuming work:

1. **Auto-load `.pm/` Context**
   - Session hooks automatically detect `.pm/` directory
   - Load current phase, next action, blockers, ready beads

2. **Read CONTINUATION.md**
   ```
   Read: .pm/CONTINUATION.md
   ```
   - Current phase and status
   - Recent work completed
   - Active blockers
   - Next actions

3. **Check Bead Status**
   ```bash
   bd ready                    # See unblocked work
   bd list --status=in_progress  # See current work
   ```

4. **Check Memory Bank**
   ```
   mcp__allPepper-memory-bank__memory_bank_read(
     projectName: "Delos_active",
     fileName: "choam-status.md"
   )
   ```

5. **Search ChromaDB for Prior Art**
   ```
   mcp__chromadb__search_similar(
     query: "CHOAM [current topic]",
     num_results: 5
   )
   ```

### During Work

Follow the RECEIVE/PRODUCE/HANDOFF protocol:

#### RECEIVE (Before Starting Any Task)

Check these sources in order:

1. **Bead**: `bd show <id>` for task context, design field, dependencies
2. **Project Infrastructure**: `.pm/CONTEXT_PROTOCOL.md` (this file)
3. **ChromaDB**: Search for prior work on the component
4. **Memory Bank**: Check session state files

If handoff received, verify it contains:
- [ ] Bead ID(s) with current status
- [ ] ChromaDB document references (if prior work exists)
- [ ] Memory Bank location (if session state exists)
- [ ] Quality criteria for completion

#### PRODUCE (During Work)

Create appropriate artifacts:

- **Code Changes**: Follow METHODOLOGY.md
- **Checkpoint**: `.pm/checkpoints/YYYYMMDD-HHMM-Delos-XXXX.md`
- **Learnings**: `.pm/learnings/LXXX-[topic].md`
- **Decisions**: Store in ChromaDB with proper ID format
- **Commit**: Reference bead ID

#### HANDOFF (When Completing or Passing Work)

Use standardized format:

```
## Handoff: [Target Agent]

**Task**: [1-2 sentence summary]
**Bead**: [ID] (status: [status])

### Input Artifacts
- ChromaDB: [document IDs or "none"]
- Memory Bank: [file path or "none"]
- Files: [key files touched]

### Deliverable
[What the receiving agent should produce]

### Quality Criteria
- [ ] [Criterion 1]
- [ ] [Criterion 2]

### Context Notes
[Special context, blockers, or warnings]
```

### PreCompact

Before session ends or context compacts:

1. **Save Session State**
   ```bash
   /check
   ```

2. **Update Checkpoint**
   - Record progress in `.pm/checkpoints/`
   - Note what's incomplete

3. **Update Memory Bank**
   ```
   mcp__allPepper-memory-bank__memory_bank_update(
     projectName: "Delos_active",
     fileName: "choam-status.md",
     content: "[current status]"
   )
   ```

4. **Persist Important Findings to ChromaDB**
   - Decisions that should survive sessions
   - Research findings
   - Architecture insights

---

## Storage Hierarchy

### 1. Beads (Task Tracking - Primary)

**Purpose**: Task tracking, dependencies, status

**When to Use**:
- All work items
- Dependencies between tasks
- Status tracking

**Commands**:
```bash
bd ready                          # Unblocked work
bd create "Title" -t feature -p 1 # Create new
bd update <id> --status in_progress
bd close <id>
bd dep add <id> <blocker-id>
```

### 2. ChromaDB (Permanent Knowledge)

**Purpose**: Decisions, research, patterns, cross-session context

**When to Use**:
- Architecture decisions
- Security findings
- Design patterns
- Research results

**ID Conventions**:
- `decision::choam::[topic]` - Architectural decisions
- `research::choam::[topic]` - Research findings
- `pattern::choam::[name]` - Reusable patterns
- `debug::choam::[issue]` - Debugging insights
- `analysis::choam::[area]` - Analysis results

**Example**:
```
mcp__chromadb__create_document(
  document_id: "decision::choam::signature-validation",
  content: "# Transaction Signature Validation\n\n## Decision\n...",
  metadata: {
    "component": "choam",
    "type": "decision",
    "phase": "0",
    "bead": "Delos-ki6t",
    "date": "2026-01-09"
  }
)
```

### 3. Memory Bank (Session State)

**Purpose**: Ephemeral session work, agent coordination, temp state

**When to Use**:
- Active hypotheses
- Blockers
- In-progress findings
- Cross-agent handoffs

**Project**: `Delos_active`

**Files**:
- `choam-status.md` - Current phase status
- `choam-blockers.md` - Active blockers
- `choam-hypotheses.md` - Under investigation
- `choam-handoff.md` - Agent coordination

### 4. .pm/ Directory (Project Infrastructure)

**Purpose**: Project structure, methodology, templates

**Files**:
- Core: README.md, CONTINUATION.md, METHODOLOGY.md
- Phases: PHASE_*/PLAN.md, MILESTONES.md
- Knowledge: KNOWLEDGE/*.md
- Metrics: METRICS/*.md
- Templates: TEMPLATES/*.md
- Work: checkpoints/, learnings/, hypotheses/

---

## Naming Conventions

### ChromaDB Document IDs

Format: `{domain}::{component}::{topic}`

Examples:
- `decision::choam::block-store-interface`
- `research::choam::byzantine-test-patterns`
- `pattern::choam::circuit-breaker`
- `analysis::choam::coverage-gaps`

### Memory Bank Files

Format: `{component}-{purpose}.md`

Examples:
- `choam-status.md`
- `choam-blockers.md`
- `choam-phase0-findings.md`

### Checkpoint Files

Format: `YYYYMMDD-HHMM-Delos-XXXX.md`

Example: `20260109-1430-Delos-ki6t.md`

### Learning Files

Format: `LXXX-[topic].md`

Example: `L001-transaction-signature-validation.md`

### Bead Descriptions

Include context reference:
```
Description: Fix transaction signature validation

Context: .pm/PHASE_0/PLAN.md
Related: decision::choam::signature-validation
```

---

## Context Recovery

If expected context not received:

1. **Search ChromaDB**
   ```
   mcp__chromadb__search_similar(
     query: "[topic] CHOAM architecture decision",
     num_results: 10
   )
   ```

2. **Check Memory Bank**
   ```
   mcp__allPepper-memory-bank__list_project_files(
     projectName: "Delos_active"
   )
   ```

3. **Query Active Work**
   ```bash
   bd list --status=in_progress
   bd list --status=open
   ```

4. **Document Assumptions**
   - Note in bead what context was missing
   - Flag incomplete context in handoff

5. **Flag for Resolution**
   - Add to blockers if critical
   - Escalate if blocking work

---

## Agent-Specific Protocols

### java-developer

**RECEIVE**:
- Bead with implementation task
- Design from java-architect-planner (if applicable)
- Phase requirements from PHASE_*/PLAN.md

**PRODUCE**:
- Implementation with tests
- Checkpoint documenting work
- Commit with bead reference

**HANDOFF TO**: code-review-expert

### code-review-expert

**RECEIVE**:
- Bead with review request
- Implementation from java-developer
- Code review checklist from METHODOLOGY.md

**PRODUCE**:
- Review feedback or approval
- Updated checklist status

**HANDOFF TO**: java-developer (if changes needed) or merge

### java-debugger

**RECEIVE**:
- Bead with failing test or bug
- Error logs/stack traces
- Prior attempts from Memory Bank

**PRODUCE**:
- Root cause analysis
- Fix or recommendation
- Learning document if significant

**HANDOFF TO**: java-developer

### java-architect-planner

**RECEIVE**:
- Design question or architecture task
- Current architecture from KNOWLEDGE/ARCHITECTURE.md
- Constraints from phase plan

**PRODUCE**:
- Design decision in ChromaDB
- Implementation guidance
- Updated architecture documentation

**HANDOFF TO**: java-developer

### test-validator

**RECEIVE**:
- Code to validate coverage
- Coverage targets from METHODOLOGY.md
- Current metrics from METRICS/COVERAGE_DASHBOARD.md

**PRODUCE**:
- Coverage report
- Gap analysis
- Approval or gap list

**HANDOFF TO**: java-developer (if gaps) or merge

---

## Cross-Phase Context

### Phase Transitions

When moving between phases:

1. **Close Phase Gate**
   - All beads for phase closed
   - Gate criteria met (see PHASE_*/CHECKPOINTS.md)
   - Update CONTINUATION.md

2. **Archive Phase State**
   - Final checkpoint with phase summary
   - Store learnings in ChromaDB
   - Update METRICS/

3. **Initialize Next Phase**
   - Update execution_state.json
   - Create phase beads (if not exist)
   - Update CONTINUATION.md for new phase

### Knowledge Transfer

When handing off to different team member:

1. **Document Current State**
   - Update all checkpoints
   - Ensure Memory Bank current
   - Update CONTINUATION.md

2. **Create Explicit Handoff**
   - Use handoff format above
   - Include all context sources
   - Note any unwritten knowledge

3. **Verify Understanding**
   - New member reads CONTINUATION.md
   - Runs `bd ready` to see work
   - Confirms understanding

---

## Quality Assurance

### Context Verification

Before starting work, verify:
- [ ] Read CONTINUATION.md
- [ ] Checked bead status
- [ ] Searched ChromaDB
- [ ] Checked Memory Bank
- [ ] Understand phase goals

### Handoff Verification

Before completing handoff, verify:
- [ ] All artifacts listed
- [ ] Quality criteria clear
- [ ] Context notes complete
- [ ] Bead status updated
- [ ] Checkpoint created

---

**Protocol Established**: 2026-01-09
**Status**: ACTIVE
**Review**: At phase boundaries
