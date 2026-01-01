# Delos Remediation - Agent Instructions

## Overview

This document provides instructions for all agents working on the Delos Remediation Project. Follow these protocols to ensure consistent, high-quality work and proper handoffs.

---

## Context Retrieval Protocol

### Before Starting Any Task

1. **Read PM Infrastructure**
   ```
   Read: .pm/CONTINUATION.md      # Current state and context
   Read: .pm/EXECUTION_STATE.md   # Detailed progress tracking
   Read: .pm/METHODOLOGY.md       # Engineering standards
   ```

2. **Check Bead Status**
   ```bash
   bd show <bead-id>              # Get task details
   bd list --status=in_progress  # See active work
   ```

3. **Search Knowledge Bases**
   ```
   # ChromaDB for critique and cross-references
   mcp__chromadb__search_similar(query="<topic> delos implementation", num_results=10)

   # Mixedbread for academic papers
   mcp__mixedbread__store_search(query="<algorithm>", store_identifiers=["delos"])
   ```

4. **Review Relevant Source Files**
   - Check KNOWLEDGE_MAP.md for file locations
   - Read existing tests for expected behavior
   - Review related code for context

---

## Handoff Format

### When Receiving Work

Expect to receive:
```
## Handoff: [Your Agent Type]

**Task**: [1-2 sentence summary]
**Bead**: [ID] (status: [status])

### Input Artifacts
- ChromaDB: [document IDs or "none"]
- Memory Bank: [file path or "none"]
- Files: [key files touched]

### Deliverable
[What you should produce]

### Quality Criteria
- [ ] [Criterion 1]
- [ ] [Criterion 2]

### Context Notes
[Special context, blockers, or warnings]
```

### When Handing Off Work

Produce handoff in this format:
```
## Handoff: [Target Agent]

**Task**: [1-2 sentence summary]
**Bead**: [ID] (status: [new status])

### Work Completed
- [What was done]
- [Key decisions made]

### Output Artifacts
- ChromaDB: [any new document IDs]
- Files Modified: [list of files changed]

### Remaining Work
[What the next agent should do]

### Quality Criteria
- [ ] [Criterion for next agent]

### Blockers or Warnings
[Any issues to be aware of]
```

---

## Bead Update Requirements

### Status Transitions

```
PENDING -> IN_PROGRESS: When starting work
IN_PROGRESS -> BLOCKED: When hitting blocker
BLOCKED -> IN_PROGRESS: When blocker resolved
IN_PROGRESS -> REVIEW: When implementation complete
REVIEW -> IN_PROGRESS: When review finds issues
REVIEW -> CLOSED: When review passes
```

### Required Updates

When starting work:
```bash
bd update <id> --status in_progress
```

When blocked:
```bash
bd update <id> --status blocked
bd note <id> "Blocked by: <reason>"
```

When completing:
```bash
bd update <id> --status review
bd note <id> "Ready for review. Tests: <passing/failing>. Files: <list>"
```

After review approval:
```bash
bd close <id>
bd note <id> "Completed. Commit: <hash>"
```

---

## Quality Criteria by Agent Type

### java-developer

- [ ] Tests written BEFORE implementation
- [ ] All existing tests still pass
- [ ] Code follows Java 24 patterns (var, no synchronized)
- [ ] SLF4J logging uses {} placeholders
- [ ] No TODO comments without bead reference
- [ ] Dynamic ports in tests
- [ ] FP32 for any neural network code

### code-review-expert

- [ ] Root cause correctly identified
- [ ] Fix addresses root cause
- [ ] No unintended side effects
- [ ] Edge cases handled
- [ ] Thread safety verified
- [ ] Performance acceptable
- [ ] Documentation updated

### java-debugger

- [ ] Hypothesis clearly stated
- [ ] Evidence gathered systematically
- [ ] Root cause identified
- [ ] Reproduction steps documented
- [ ] Fix approach recommended

### java-architect-planner

- [ ] Paper reference included
- [ ] Design documented
- [ ] Trade-offs explained
- [ ] Integration points identified
- [ ] Testing strategy defined

---

## Module-Specific Context

### Ethereal (Phase 0)

**Key Files**:
- `ethereal/src/main/java/com/hellblazer/delos/ethereal/linear/UnanimousVoter.java`

**Critical Bug Location**: Line 283-289 (switch fall-through)

**Paper Reference**: `mcp__mixedbread__store_search(query="Aleph BFT consensus voting", store_identifiers=["delos"])`

**ChromaDB Reference**: `crossref::ethereal::implementation`

### Delphinius (Phase 0, 1, 2)

**Key Files**:
- `delphinius/src/main/java/com/hellblazer/delos/delphinius/DirectOracle.java`
- `delphinius/src/main/java/com/hellblazer/delos/delphinius/AbstractOracle.java`

**Critical Issues**:
1. `check()` at line 101-107 (staleness)
2. `expand(Subject)` at line 976 (incomplete)

**Paper Reference**: `mcp__mixedbread__store_search(query="Zanzibar authorization", store_identifiers=["delos"])`

**ChromaDB Reference**: `crossref::delphinius::implementation`, `critique::delphinius::zanzibar-comparison`

### Cryptography (Phase 1)

**Key Files**:
- `cryptography/src/main/java/com/hellblazer/delos/bloomFilters/Hash.java`
- `cryptography/src/main/java/com/hellblazer/delos/cryptography/HexBloom.java`

**Security Concerns**:
1. MurmurHash3 in security context
2. HexBloom crown XOR without domain separator

**ChromaDB Reference**: `crossref::cryptography::implementation`

---

## Common Patterns

### Test-First Implementation

```java
@Test
void shouldNotFallThroughOnPopularVote() {
    // Arrange
    var voter = new UnanimousVoter(...);
    var counted = new CountedVotes(Vote.POPULAR, ...);

    // Act - before fix, this would set both votesOne AND votesZero
    voter.processVote(counted);

    // Assert - after fix, only votesOne should be set
    assertTrue(voter.getVotesOne());
    assertFalse(voter.getVotesZero());  // This would fail before fix
}
```

### Sequential Thinking for Analysis

```
mcp__sequential-thinking__sequentialthinking({
    thought: "Hypothesis: The switch fall-through causes POPULAR votes to also execute UNPOPULAR case",
    nextThoughtNeeded: true,
    thoughtNumber: 1,
    totalThoughts: 5
})
```

### ChromaDB Storage

```
mcp__chromadb__create_document({
    document_id: "fix::ethereal::switch-fallthrough",
    content: "## Fix Details\n...",
    metadata: {
        type: "fix",
        module: "ethereal",
        bead: "BD-XXX",
        date: "2025-12-30"
    }
})
```

---

## Error Recovery

### If Context Is Missing

1. Search ChromaDB for related work:
   ```
   mcp__chromadb__search_similar(query="ethereal switch fix", num_results=5)
   ```

2. Check Memory Bank for session state:
   ```
   mcp__allPepper-memory-bank__memory_bank_read(projectName="Delos_active", fileName="current_phase.md")
   ```

3. Query beads for active work:
   ```bash
   bd list --status=in_progress
   ```

4. Document assumption in bead notes:
   ```bash
   bd note <id> "Assumption: <what was assumed due to missing context>"
   ```

### If Build Fails

1. Run module-specific tests first:
   ```bash
   ./mvnw test -pl <module>
   ```

2. Check for dependency issues:
   ```bash
   ./mvnw dependency:tree -pl <module>
   ```

3. If h2-deterministic issue:
   ```bash
   ./mvnw clean install -Ppre -DskipTests
   ```

### If Tests Fail Unexpectedly

1. Run single failing test:
   ```bash
   ./mvnw test -Dtest=ClassName#methodName -pl <module>
   ```

2. Check for port conflicts (use dynamic ports)

3. Check for resource cleanup issues

4. Document in bead notes

---

## Communication Protocol

### Escalation Path

| Issue Type | First Contact | Escalation |
|------------|---------------|------------|
| Technical block | java-debugger | java-architect-planner |
| Design question | java-architect-planner | deep-research-synthesizer |
| Paper clarification | deep-research-synthesizer | devonthink-researcher |
| Quality concern | code-review-expert | substantive-critic |

### Status Updates

Update EXECUTION_STATE.md when:
- Starting a new task
- Completing a task
- Hitting a blocker
- Making a significant decision

Update CONTINUATION.md when:
- Completing a phase
- Major context change
- New blockers identified
- Key decisions made

---

## Do's and Don'ts

### DO
- Read PM files before starting
- Update beads at every status change
- Write tests first
- Search ChromaDB for prior art
- Document assumptions
- Follow handoff format

### DON'T
- Skip test-first development
- Use synchronized (use concurrent collections)
- Ignore existing tests
- Make changes without bead reference
- Use Python-style SLF4J formatting
- Include AI attribution in commits

---

*Last Updated: 2025-12-30*
