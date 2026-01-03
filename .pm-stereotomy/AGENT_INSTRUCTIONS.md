# Agent Instructions for Stereotomy Security Remediation

**Project**: Delos Stereotomy KERI Security Remediation
**Branch**: feat/stereotomy-review
**PM Location**: `/Users/hal.hildebrand/git/Delos/.pm-stereotomy/`

## Context Protocol

### RECEIVE (Before Starting Work)

Check these sources in order:

1. **Bead Status**: `bd show <id>` for current task state, design notes
2. **Execution State**: `.pm-stereotomy/EXECUTION_STATE.md` for phase context
3. **Continuation**: `.pm-stereotomy/CONTINUATION.md` for session resume
4. **ChromaDB**: Search for related analyses (see queries below)
5. **Memory Bank**: `delos_active/stereotomy-phase-N.md` for active state

### Handoff Format

All handoffs to downstream agents use this structure:

```
## Handoff: [Target Agent Name]

**Task**: [1-2 sentence summary of what downstream agent should do]
**Bead**: [ID] (status: [current-status])

### Input Artifacts
- ChromaDB: [document IDs provided, e.g., "critique::stereotomy::deep-analysis-2026-01-02"]
- Memory Bank: [file path, e.g., "delos_active/stereotomy-phase1.md"]
- Files: [key source files, e.g., "KeyConfigurationDigester.java"]
- Branch: feat/stereotomy-review

### Deliverable
[What the receiving agent should produce]

### Quality Criteria
- [ ] [Criterion 1]
- [ ] [Criterion 2]
- [ ] [Criterion 3]

### Context Notes
[Special context, blockers, warnings, or hints for the downstream agent]
```

### PRODUCE (What This Agent Creates)

- **Beads**: Create/update beads for tracking progress
- **Checkpoints**: Document completed phases (`.pm-stereotomy/checkpoints/`)
- **Learnings**: Record findings and insights (`.pm-stereotomy/learnings/`)
- **Hypotheses**: Document design decisions (`.pm-stereotomy/hypotheses/`)
- **Code**: Implementation changes with tests
- **Tests**: Security tests, regression tests, stress tests

### HANDOFF (Downstream Targets)

**From**: java-developer (implementation phase)
- **To**: code-review-expert (review phase)
- **To**: test-validator (validation phase)

**From**: java-debugger (debugging phase)
- **To**: java-developer (fix implementation)

## Agent Roles

### java-developer (Primary Development)

**When to spawn**: For implementing fixes CRIT-1 through CRIT-6

**Instructions**:
```
Handoff: java-developer

Task: Implement fix for CRIT-1 (XOR Commutativity Attack) with TDD methodology
Bead: [CRIT-1-ID] (status: in_progress)

Input Artifacts:
- ChromaDB: critique::stereotomy::deep-analysis-2026-01-02
- ChromaDB: analysis::codebase-deep-analyzer::stereotomy-module-2026-01-02
- Memory Bank: delos_active/stereotomy-phase1.md
- Files: stereotomy/src/main/java/.../KeyConfigurationDigester.java
- Test: stereotomy/src/test/java/.../SecurityTests.java (CRIT-1 failing test)
- Branch: feat/stereotomy-review

Deliverable:
1. Implementation of fix replacing XOR with order-preserving hash
2. CRIT-1 test changes from FAIL to PASS
3. Full regression suite passes
4. Code review ready

Quality Criteria:
- [ ] CRIT-1 test now passes
- [ ] No regression in KeyConfigurationDigester tests
- [ ] Code follows Java 24 style (var, records, pattern matching)
- [ ] Performance within baseline +/- 10%
- [ ] Commit message references bead ID
- [ ] Ready for code review

Context Notes:
- This is TDD: test was written first (Phase 0), now implement fix
- Attack vector: attacker rotates keys in different order than committed
- Must preserve backward compatibility with existing valid configurations
- See Section 3 of deep-analysis for XOR commutativity details
```

### code-review-expert (Code Review)

**When to spawn**: After java-developer completes each CRIT-X fix

**Instructions**:
```
Handoff: code-review-expert

Task: Review CRIT-1 fix implementation for security and code quality
Bead: [CRIT-1-ID] (status: review-needed)

Input Artifacts:
- GitHub PR with CRIT-1 fix
- ChromaDB: critique::stereotomy::deep-analysis-2026-01-02
- Test: SecurityTests.java (CRIT-1 test)
- Branch: feat/stereotomy-review

Deliverable:
1. Code review approval or list of issues
2. Security validation checklist completed
3. Performance impact assessment
4. Documentation review

Quality Criteria:
- [ ] Fix correctly addresses root cause (XOR commutativity)
- [ ] No security bypasses remain
- [ ] Code quality meets project standards
- [ ] Test coverage adequate
- [ ] Performance acceptable
- [ ] Ready to merge
```

### test-validator (Testing & Validation)

**When to spawn**: After all CRIT-X fixes to validate full suite

**Instructions**:
```
Handoff: test-validator

Task: Validate all security fixes through comprehensive test execution
Bead: [PHASE-5-ID] (status: validation)

Input Artifacts:
- All 6 CRIT-X bead IDs
- Branch: feat/stereotomy-review
- Test suite: SecurityTests.java (all 6 tests)

Deliverable:
1. Full test suite execution report
2. Stress test results (1000+ events)
3. Memory leak analysis
4. Performance regression report
5. Security validation checklist

Quality Criteria:
- [ ] All 6 security tests pass
- [ ] Regression tests pass (100%)
- [ ] Stress test: 1000+ events processed correctly
- [ ] No memory leaks detected
- [ ] Performance within baseline +/- 10%
- [ ] Ready for security audit
```

### java-debugger (If Blocking Issues Found)

**When to spawn**: If fix doesn't resolve test failure or new issues found

**Instructions**:
```
Handoff: java-debugger

Task: Debug failing security test to identify root cause
Bead: [CRIT-X-ID] (status: blocked)

Input Artifacts:
- Failing test output
- Expected vs actual behavior
- Related source code sections
- Branch: feat/stereotomy-review

Deliverable:
1. Root cause analysis with evidence
2. Recommended fix approach
3. Updated test if test was incorrect
4. Blockers and decision points

Quality Criteria:
- [ ] Root cause identified
- [ ] Evidence provided (stack traces, logs, code analysis)
- [ ] Recommendation clear and actionable
```

## ChromaDB Search Patterns

Use these queries to find relevant analyses:

### Security Analysis
```
Search: "stereotomy KERI security vulnerabilities critical"
Documents:
- critique::stereotomy::deep-analysis-2026-01-02 (PRIMARY)
- review::stereotomy::security-issues-2026-01-02 (if exists)
```

### Module Architecture
```
Search: "stereotomy KERL KeyEventProcessor signature verification"
Documents:
- analysis::codebase-deep-analyzer::stereotomy-module-2026-01-02
- delos::implementation::keri-event-authentication
```

### KERI Patterns
```
Search: "KERI key event receipt infrastructure identity rotation"
Documents:
- delos::pattern::keri-identity
- crossref::stereotomy-thoth::implementation
```

### Concurrency Patterns
```
Search: "stereotomy concurrent MemKERL race condition append"
Documents: critique::stereotomy::deep-analysis-2026-01-02 (CRIT-2 section)
```

## Project Structure

### Beads Hierarchy
```
Stereotomy KERI Security Remediation (Epic)
├─ Phase 0: TDD & Security Tests (Task)
│  ├─ CRIT-1: XOR Commutativity Attack (Bug, P1)
│  ├─ CRIT-2: Race Condition in Event Append (Bug, P1)
│  ├─ CRIT-3: Inception Signature Not Verified (Bug, P1)
│  ├─ CRIT-4: No Database Transaction Boundaries (Bug, P1)
│  ├─ CRIT-5: Non-Atomic State Transitions (Bug, P1)
│  └─ CRIT-6: Key Material Not Cleared (Bug, P1)
├─ Phase 1: Fix CRIT-1 (Task, depends on Phase 0)
├─ Phase 2: Fix CRIT-2, 4, 5 (Task, depends on Phase 1)
├─ Phase 3: Fix CRIT-3 (Task, depends on Phase 2)
├─ Phase 4: Fix CRIT-6 (Task, depends on Phase 3)
├─ Phase 5: Security Audit & Stress Testing (Task, depends on Phase 4)
└─ Phase 6: Production Readiness (Task, depends on Phase 5)
```

### File Organization
```
.pm-stereotomy/
├── EXECUTION_STATE.md         (Current phase, metrics, blockers)
├── CONTINUATION.md             (Quick resume guide)
├── AGENT_INSTRUCTIONS.md       (This file)
├── METHODOLOGY.md              (Process and discipline)
├── checkpoints/
│  ├── TEMPLATE.md             (Checkpoint template)
│  ├── phase-0-complete.md     (After TDD setup)
│  ├── phase-1-complete.md     (After CRIT-1 fix)
│  └── ...
├── hypotheses/
│  ├── TEMPLATE.md             (Design decision template)
│  ├── H-1-hash-strategy.md    (CRIT-1 fix approach)
│  └── ...
├── learnings/
│  ├── TEMPLATE.md             (Learning template)
│  ├── L-1-xor-commutativity.md (CRIT-1 lesson)
│  └── ...
├── metrics/
│  ├── baseline.md             (Phase 0 baseline)
│  ├── phase-1-metrics.md      (After CRIT-1 fix)
│  └── ...
├── thinking/
│  └── phase-0-analysis.md     (Deep analysis sessions)
├── tests/
│  └── security-test-plan.md   (Test strategy)
└── audits/
   ├── pre-audit.md            (Before security audit)
   └── post-audit.md           (After security audit)
```

## Methodology

### TDD Approach (Phase 0)

1. **Write Test First**: Create failing security test exposing vulnerability
   - Test demonstrates attack scenario
   - Test includes metrics for validating fix
   - Test should fail consistently

2. **Verify Test Fails**: Run `mvn test`, confirm failure
   - Document expected failure mode
   - Verify test error is meaningful

3. **Implement Fix**: Minimal change to make test pass
   - Focus on fix, not refactoring
   - Maintain backward compatibility
   - Follow project style guide

4. **Verify Test Passes**: Run `mvn test`, confirm test passes
   - Run full regression suite
   - Ensure no new failures

5. **Document Lesson**: Record in learnings/
   - What vulnerability was fixed
   - How test caught it
   - What approach worked

### Fix Implementation (Phases 1-5)

Each CRIT-X fix follows the same pattern:
1. Read analysis document (critique::stereotomy::...)
2. Understand root cause and attack vector
3. Design fix approach (document in hypothesis)
4. Implement fix with TDD
5. Verify with regression suite
6. Code review and approval
7. Document in checkpoint

### Quality Gates

**Phase 0 Gate**:
- All 6 tests fail consistently
- Baseline metrics captured
- No regressions in codebase

**Phase 1-5 Gates** (per fix):
- Test passes
- Regression suite passes
- Code review approved
- Performance acceptable

**Phase 6 Gate**:
- All 6 issues fixed
- Security audit passed
- Stress test passed
- Ready for production

## Key Decisions

### TDD-First Approach
- **Rationale**: Security bugs require ironclad validation
- **Alternative**: Bug-fix-first (rejected: leads to undocumented edge cases)

### Per-Issue Beads
- **Rationale**: Each issue has distinct fix, reduces integration risk
- **Alternative**: Batch fixes (rejected: harder to verify)

### Test-Centric Metrics
- **Rationale**: Tests measure fix effectiveness
- **Alternative**: Code inspection only (rejected: insufficient)

## Useful Commands

```bash
# Create epic for project
bd create "Stereotomy KERI Security Remediation" -t epic -p 0

# Create bead for critical issue
bd create "CRIT-1: XOR Commutativity Attack" -t bug -p 1

# Update bead status
bd update <id> --status in_progress
bd update <id> --status completed

# Build module
cd /Users/hal.hildebrand/git/Delos
mvn clean install -amd -pl stereotomy -DskipTests

# Run tests
mvn test -Dtest=*Security* -pl stereotomy

# Run full test suite
mvn test -pl stereotomy

# Search ChromaDB
# (Use Search tool with queries above)

# Memory Bank operations
# (See .CLAUDE.md for examples)
```

## Success Metrics

Project is successful when:

1. **Phase 0**: All 6 security tests fail consistently (TDD harness ready)
2. **Phase 1**: CRIT-1 fixed, test passes, regression suite passes
3. **Phase 2**: CRIT-2, 4, 5 fixed, tests pass, regression suite passes
4. **Phase 3**: CRIT-3 fixed, test passes, regression suite passes
5. **Phase 4**: CRIT-6 fixed, tests pass, regression suite passes
6. **Phase 5**: Stress test passes (1000+ events), no memory leaks
7. **Phase 6**: Security audit passed, ready for merge to main

## References

- **CLAUDE.md**: Global project directives
- **Fireflies PM (.pm/)**: Reference remediation project (similar pattern)
- **ChromaDB**: All analysis documents
- **Module Code**: `/stereotomy/src/main/java/`

---

**Last Updated**: 2026-01-02
**Version**: 1.0
**Reviewed By**: Project Setup
