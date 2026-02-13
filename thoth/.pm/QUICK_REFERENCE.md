# Thoth Module - Quick Reference Card

Print this for easy access during development sessions.

## Starting Your Session

```bash
# 1. Load previous session context
/load

# 2. Check active work
bd list --status=in_progress

# 3. Read current status
cat .pm/CONTINUATION_PROMPT.md

# 4. Verify tests pass
./mvnw test -pl thoth
```

## Essential Commands

### Project Management
```bash
# List active beads
bd list --status=in_progress

# Show bead details
bd show THOTH-xxx

# Create new bead
bd create "Thoth: [Description]" -t feature

# Update bead status
bd update THOTH-xxx --status in_progress

# Close completed bead
bd close THOTH-xxx
```

### Testing
```bash
# All thoth tests
./mvnw test -pl thoth

# Single test class
./mvnw test -pl thoth -Dtest=KerlDhtTest

# Single test method
./mvnw test -pl thoth -Dtest=KerlDhtTest#shouldRebalanceAfterMembershipChange

# With longer timeout (for Byzantine tests)
./mvnw test -pl thoth -DargLine="-Dtimeout=600000"

# Clean and rebuild
./mvnw clean install -pl thoth
```

### Git
```bash
# See status
git status

# Add changes
git add [files]

# Commit with bead reference
git commit -m "Description

References: THOTH-xxx"

# View recent commits
git log --oneline -10
```

### Session Management
```bash
# Save session state
/check

# Load saved session
/load

# List all sessions
/sessions
```

## File Locations

| What | Where |
|------|-------|
| Current status | `.pm/CONTINUATION_PROMPT.md` |
| Central metrics | `.pm/execution_state.json` |
| Work checkpoints | `.pm/checkpoints/CHECKPOINT-n-*.md` |
| Validated learnings | `.pm/learnings/L*.md` |
| Architecture decisions | `.pm/hypotheses/H*.md` |
| Code review findings | `.pm/audits/AUDIT-*.md` |
| Deep analysis | `.pm/thinking/ANALYSIS-*.md` |
| Test coverage tracking | `.pm/metrics/TEST-COVERAGE.md` |

## Testing Pattern (TDD)

```bash
# 1. Write failing test
# File: src/test/java/.../YourTest.java
@Test
void shouldImplementFeature() {
    // Arrange, Act, Assert (should fail)
}

# 2. Run test (watch it fail)
./mvnw test -pl thoth -Dtest=YourTest

# 3. Implement code to pass test
# File: src/main/java/.../YourClass.java

# 4. Run test (watch it pass)
./mvnw test -pl thoth -Dtest=YourTest

# 5. Run full suite (ensure no regressions)
./mvnw test -pl thoth

# 6. Commit
git commit -m "Feature: [description]

References: THOTH-xxx"
```

## Byzantine Fault Tolerance Checklist

When writing tests for consensus operations:

- [ ] Test with f Byzantine nodes in 3f+1 setup
- [ ] Test membership change triggers rebalancing
- [ ] Test witness majority enforcement (majority of 3f+1)
- [ ] Test network partition scenarios
- [ ] Test KERL consistency during rebalancing
- [ ] Document BFT assumptions in test comments

Example:
```java
@Test
void shouldMaintainConsistencyWithByzantineNodes() {
    // Setup: 3 nodes (f=1 Byzantine)
    // Test membership change with 1 Byzantine node
    // Assert: KERL consistency maintained
}
```

## Key Components at a Glance

| Component | File | Purpose | Tests |
|-----------|------|---------|-------|
| **KerlDht** | `KerlDht.java` | Core DHT storage and routing | `KerlDhtTest.java` |
| **Ani** | `Ani.java` | Event validation | `AniTest.java` |
| **Maat** | `Maat.java` | Witness coordination | `MaatTest.java` |
| **ReconciliationService** | `ReconciliationService.java` | Gossip rebalancing | `PublisherTest.java` |

## Test Coverage Goals

| Component | Target | Current | Gap |
|-----------|--------|---------|-----|
| KerlDht | 85% | [TBD] | [TBD] |
| Ani | 85% | [TBD] | [TBD] |
| Maat | 85% | [TBD] | [TBD] |
| ReconciliationService | 85% | [TBD] | [TBD] |
| **Overall** | **85%** | **[TBD]** | **[TBD]** |

Check: `.pm/metrics/TEST-COVERAGE.md` for current status

## Ending Your Session

```bash
# 1. Create checkpoint
cp .pm/checkpoints/TEMPLATE-checkpoint.md .pm/checkpoints/CHECKPOINT-N-[title].md
# [Fill in details: what was completed, what's in progress, next actions]

# 2. Update continuation prompt
# [Update: current phase, active work, next actions, metrics]

# 3. Update state tracking
# [Update execution_state.json: progress, metrics, blockers]

# 4. Save session
/check

# 5. Commit checkpoint
git add .pm/
git commit -m "Session checkpoint: Phase N - [summary]

Completed: [items]
In Progress: [items]
References: THOTH-xxx"
```

## When Stuck

### Tests Failing?
```bash
# More details
./mvnw test -pl thoth -X

# Run single test with output
./mvnw test -pl thoth -Dtest=YourTest

# Check if it's a timeout issue
./mvnw test -pl thoth -DargLine="-Dtimeout=600000"
```

### Can't Find Code?
```bash
# Search for class
grep -r "ClassName" src/

# Search for method
grep -r "methodName" src/

# List files in module
find src/main -name "*.java" | sort
```

### Byzantine FT Confusion?
1. Read: `.pm/CONTEXT_PROTOCOL.md` (Byzantine FT section)
2. Review: `.pm/hypotheses/H*-bft*.md` (if exists)
3. Check: Related test class in `src/test/java/`
4. Ask: code-review-expert

### Blocked on Something?
1. Document in bead notes: `bd update THOTH-xxx --notes "Blocker: [description]"`
2. Record in `execution_state.json` blockers array
3. Create analysis: `.pm/thinking/ANALYSIS-[date]-[topic].md`
4. Escalate if needed

## Important Links

- **Project Context**: `.pm/CONTEXT_PROTOCOL.md`
- **Quick Start**: `.pm/README.md`
- **High-Level Overview**: `.pm/PROJECT_MANAGEMENT_SUMMARY.md`
- **Agent Guidance**: `.pm/AGENT_INSTRUCTIONS.md`
- **Full Index**: `.pm/INDEX.md`

## Contact Points

- **Architecture decisions**: See `.pm/hypotheses/`
- **Prior learnings**: See `.pm/learnings/`
- **Code review findings**: See `.pm/audits/`
- **Blocked work**: Update `.pm/execution_state.json`
- **Need research**: Escalate to research-synthesizer

## Phases at a Glance

```
Phase 1: Foundation & Initialization (Pending)
├── Documentation and baselines
├── Test coverage analysis
├── Byzantine FT test suite
└── Architecture decisions

Phase 2: Core Enhancement & Hardening (Pending)
├── Enhanced implementations
├── Error handling framework
├── Witness rotation
└── Performance optimization

Phase 3: Integration & Scalability Testing (Pending)
├── Fireflies + CHOAM integration
├── Scalability testing (10-100+ nodes)
├── Chaos engineering
└── Deployment guides
```

Current: **Phase 1 - Foundation**

## Success Criteria Reminder

**Functional**: All core operations work correctly with Byzantine fault tolerance
**Quality**: Test coverage >= 85%, no critical issues
**Integration**: Works with Fireflies and CHOAM
**Performance**: Meets baseline targets

---

**Last Updated**: [Date]
**Current Phase**: Phase 1 - Foundation
**Progress**: Initialization complete

