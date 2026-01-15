# Agent Instructions for Netty Elimination Project

**Project**: Netty Native Library Elimination
**Updated**: 2026-01-15
**For**: Downstream agents (code-review-expert, test-validator, strategic-planner, etc.)

---

## Overview

This document guides agents working on the Netty native library elimination project. It defines:
1. **Project Context**: What problem are we solving?
2. **Approach**: How should we solve it?
3. **Quality Standards**: What does "done" look like?
4. **Escalation Paths**: When do we need team input?

---

## Project Context

### Problem
Netty native transport libraries (libnetty_transport_native_kqueue/epoll) crash when loaded in both main JVM and GraalVM isolates (double-loading). This blocks multi-tenant enclaves (isolates).

### Solution
Replace with pure-Java NIO (JEP 380, Netty 4.1.110+). Simple, standard, proven approach.

### Impact
- Delete 3 modules (domain-kqueue, domain-epoll, domain-sockets)
- Update 5 modules (protocols, model, memberships, isolates, isolate-ftesting)
- Fix GraalVM isolates crash
- Simplify codebase

---

## For Code Review Agent

### Task: Review Phase 1 Code Changes

**When**: After Phase 1 modules are updated
**What**: Review changes to 5 modules
**Success**: All changes follow NIO pattern, no lingering native refs

### Code Review Checklist

- [ ] **NIO Transport Usage**
  - All `KQueueDomainSocketChannel` → `NioDomainSocketChannel`
  - All `EpollDomainSocketChannel` → `NioDomainSocketChannel`
  - All `KQueueEventLoopGroup` → `NioEventLoopGroup`
  - All `EpollEventLoopGroup` → `NioEventLoopGroup`

- [ ] **No Platform-Specific Code**
  - No remaining references to `DomainSocketsOSX`
  - No remaining references to `DomainSocketsLinux`
  - No remaining references to `domain-kqueue`
  - No remaining references to `domain-epoll`

- [ ] **Code Quality**
  - Comments explain why NIO instead of native
  - No performance hotspots introduced
  - No unnecessary complexity
  - Follows existing code style

- [ ] **Import Statements**
  - Remove imports from deleted modules
  - Add imports for Netty NIO classes
  - No dangling/unused imports

- [ ] **Configuration**
  - pom.xml updated if dependencies changed
  - No remaining dependency on deleted modules

### What to Look For (Anti-patterns)

**DON'T Accept**:
```java
// Bad: Still using native transport
KQueueEventLoopGroup group = new KQueueEventLoopGroup();
KQueueDomainSocketChannel channel = ...
```

**DO Accept**:
```java
// Good: Using pure-Java NIO
NioEventLoopGroup group = new NioEventLoopGroup();
NioDomainSocketChannel channel = ...
```

### Approval Criteria

✅ **Approve If**:
- All socket channels use NIO
- No platform-specific code remains
- Code compiles and tests pass
- Comments explain changes
- Performance not regressed

❌ **Reject If**:
- Any KQueue/Epoll references remain
- Platform-specific code still present
- Tests fail
- Performance significantly degraded

### Questions to Ask Developer

1. "Did you search for all socket channel creations? Use: `grep -r "DomainSocketChannel\|EventLoopGroup" --include="*.java" src/`"
2. "Did you verify the module tests pass?"
3. "Did you check if any non-socket code was affected?"
4. "Are there any edge cases with NIO vs native transport behavior?"

---

## For Test Validator Agent

### Task: Validate Test Coverage (Phase 4)

**When**: After Phase 4 testing is complete
**What**: Verify all modules adequately tested
**Success**: > 95% test coverage, all suites pass

### Test Coverage Checklist

- [ ] **Protocol Layer Tests**
  - DomainSocketServerInterceptor tested
  - Socket creation/teardown validated
  - Multi-connection handling verified

- [ ] **Model Layer Tests**
  - DemesneImpl transport selection tested
  - Message passing validated
  - Timeout behavior verified

- [ ] **Memberships Tests**
  - Enclave initialization tested
  - Portal socket handling validated
  - Gossip communication verified

- [ ] **Isolate Tests**
  - DemesneIsolateTest passes
  - Multi-isolate scenarios tested
  - JNI bridge functioning

- [ ] **Integration Tests**
  - Full stack communication tested
  - Multi-node scenarios validated
  - No hangs or crashes

### Test Results to Validate

| Test Suite | Expected | Must Pass | Notes |
|------------|----------|-----------|-------|
| protocols | 20+ tests | 100% | Protocol layer |
| model | 50+ tests | 100% | Core domain |
| memberships | 20+ tests | 100% | Gossip/membership |
| isolate-ftesting | 10+ tests | 100% | Isolates (high risk) |
| Full build | All | 100% | End-to-end |

### Performance Metrics to Validate

- [ ] Baseline recorded (before Phase 1)
- [ ] Post-Phase4 metrics recorded (after Phase 4)
- [ ] Degradation < 20% (expect < 10%)
- [ ] No memory leaks observed
- [ ] GC behavior acceptable

### Terminal Hang Validation

- [ ] No hangs during protocol tests
- [ ] No hangs during isolate tests
- [ ] Test timeout mechanism working (timeout 30s)
- [ ] Recovery procedure documented if hang occurred

### Coverage Analysis

```bash
# Run with coverage (if available)
./mvnw clean install -DargLine="-javaagent:jacoco-agent.jar"

# Verify:
# - protocols: > 90% coverage
# - model: > 85% coverage
# - memberships: > 80% coverage
# - isolates: > 75% coverage (harder to test)
```

### Approval Criteria

✅ **Approve If**:
- All test suites pass
- Coverage > 95% overall
- Performance acceptable (< 20% degradation)
- No terminal hangs
- Isolates functional

❌ **Reject If**:
- Any test suite fails
- Coverage < 85% overall
- Performance degraded > 20%
- Terminal hangs occurred unresolved
- Isolates not working

### Questions to Ask Developer

1. "Did you record baseline metrics BEFORE Phase 1?"
2. "Were all tests run with NIO transport (not falling back to native)?"
3. "Did any terminal hangs occur? How were they recovered?"
4. "What's the performance degradation percentage?"

---

## For Strategic Planner Agent

### Task: Create Implementation Plan (If Requested)

**When**: Before Phase 1 starts (if agent is deployed)
**What**: Detailed implementation roadmap
**Success**: Plan matches or improves on 4-phase approach

### Plan Alignment with Existing Structure

**DO Reconcile**:
- Phase 1: Code migration (update 5 modules)
- Phase 2: Module deletion (delete 3 modules)
- Phase 3: Metadata cleanup (remove native libs)
- Phase 4: Testing & validation (comprehensive testing)

**Plan Should Address**:
1. **Detailed Task Breakdown**: Which files to modify in which order
2. **Risk Mitigation**: Terminal hang prevention strategy
3. **Testing Strategy**: How to safely test isolates
4. **Timeline**: Realistic estimates per module
5. **Dependencies**: Phase gating, task sequencing

### Deliverables Expected from Planner

✅ **Accept**:
- Detailed file locations with line numbers
- Specific code changes needed
- Testing approach per module
- Performance baseline strategy
- Terminal hang prevention measures

❌ **Don't Accept**:
- Vague guidance ("update the modules")
- Approach that mixes phases
- Testing without timeout/recovery
- Inadequate risk management

### Information to Provide Planner

**Input**:
1. Module locations: `protocols/`, `model/`, `memberships/`, `isolates/`, `isolate-ftesting/`
2. Modules to delete: `domain-kqueue/`, `domain-epoll/`, `domain-sockets/`
3. Replace pattern: `KQueue*/Epoll*` → `Nio*`
4. Critical risk: Terminal hangs during isolate testing
5. Success target: < 20% performance degradation

### Plan Review Questions

1. "Does the plan phase-gate work correctly (Phase 1 before 2, etc.)?"
2. "Does the plan address terminal hang risk?"
3. "Are performance baselines measured before Phase 1?"
4. "Does the plan break isolate testing into safe increments?"
5. "Are all 5 modules covered in Phase 1?"

---

## For Code Reviewer (General)

### Standards for All Commits

**Commit Message Format**:
```
Phase X: [Module/Task] - [What was done]

Detailed description of changes, why they were needed.

References: Delos-1b9y (Epic), Delos-1b9y-p1-[module] (Task bead)
```

**Good Example**:
```
Phase 1: Replace KQueue with NIO in protocols module

Updated DomainSocketServerInterceptor to use NioEventLoopGroup
instead of KQueueEventLoopGroup. This removes dependency on native
transport library and enables GraalVM isolates support.

- Removed KQueueEventLoopGroup initialization
- Added NioEventLoopGroup initialization
- Verified all protocol tests pass
- Performance: < 5% degradation

References: Delos-1b9y, Delos-1b9y-p1-protocols
```

**Bad Example**:
```
Fix Netty native libraries

Generated with Claude Code

Co-Authored-By: Claude
```

### No AI Attribution in Commits
**CRITICAL**: Never include AI attribution in commit messages (company policy). Focus on technical content and bead references.

---

## For Performance Analyst

### Baseline Setup

**Before Phase 1 Starts**:
1. Identify baseline test (ChurnTest or similar high-throughput test)
2. Record metrics:
   - Throughput (messages/second)
   - Latency p50, p99 (milliseconds)
   - Memory usage (MB)
   - GC pause time (milliseconds)
3. Save to: `.pm-netty/metrics/baseline.txt`

**Setup Command**:
```bash
# Example baseline measurement
./mvnw clean install
./mvnw test -pl model -Dtest=ChurnTest -Dclass=ChurnTest \
  -DargLine="-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints \
  -XX:+LogGCDetails -XX:+PrintGCTimeStamps"
```

### Post-Phase4 Measurement

**After Phase 4 Tests Pass**:
1. Run identical test
2. Record same metrics
3. Calculate degradation:
   - (Before - After) / Before * 100
4. Assess:
   - < 10%: Excellent
   - 10-20%: Acceptable
   - > 20%: Investigate

**Measurement Command**:
```bash
# Identical to baseline
./mvnw clean install
./mvnw test -pl model -Dtest=ChurnTest -Dclass=ChurnTest \
  -DargLine="-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints \
  -XX:+LogGCDetails -XX:+PrintGCTimeStamps"
```

### Report Format

```markdown
# Performance Analysis

## Baseline (Before Phase 1)
- Throughput: X msgs/sec
- Latency p50: X ms
- Latency p99: X ms
- Memory: X MB
- GC Pause: X ms

## Post-Phase4 (After all changes)
- Throughput: Y msgs/sec (degradation: Z%)
- Latency p50: Y ms (degradation: Z%)
- Latency p99: Y ms (degradation: Z%)
- Memory: Y MB (change: Z%)
- GC Pause: Y ms (change: Z%)

## Assessment
[PASS if all < 20% degradation, FAIL if any > 20%]
```

---

## Escalation Criteria

### Escalate to Team If

1. **Terminal Hang Cannot Be Recovered**
   - Indicator: `killall -9 java` doesn't work
   - Action: Document hang details, escalate for investigation

2. **Performance Degradation > 20%**
   - Indicator: NIO is significantly slower than native
   - Action: Investigate cause, consider alternatives

3. **Peer Credentials Extraction Fails**
   - Indicator: SO_PEERCRED not accessible via NIO
   - Action: Verify if actually needed, fallback to TLS

4. **GraalVM Native Image Build Breaks**
   - Indicator: `mvnw compile -pl isolates -Pisolates` fails
   - Action: Update metadata, add JNI registrations

5. **Unexpected Module Incompatibility**
   - Indicator: Test fails with NIO that passed with native
   - Action: Debug, document workaround, escalate if unresolvable

### Escalation Format

When escalating, provide:
1. **Problem**: Clear description of issue
2. **Context**: Where it occurred (module, phase, test)
3. **Impact**: What does it block?
4. **Data**: Error messages, stack traces, logs
5. **Attempts**: What have you tried?
6. **Recommendation**: What should the team do?

---

## Git Workflow for Agents

### Before Submitting Code

```bash
# Verify clean branch
git status  # Should only show .pm-netty changes + code changes

# Check recent commits
git log --oneline -3

# Verify compilation
./mvnw clean compile -amd

# Run tests for module
./mvnw test -pl <module>

# No unstaged changes?
git diff --name-only

# Ready to review
```

### Commit Frequency

- **Per Module**: One commit per module (Phase 1)
- **Dependency Updates**: Separate commit if pom.xml changed
- **Tests**: Same commit as code (not separate)
- **Don't Commit**: Failed experiments, debug code

---

## Communication with Development Team

### Daily Standup Points

1. **Progress**: Which phase, which module, completion %
2. **Blockers**: Any issues blocking progress
3. **Risks**: Identified new risks or mitigations
4. **Next Actions**: What's planned next

### Documentation to Update

- **Checkpoints**: Create after major milestone
- **Metrics**: Update progress.md after each bead
- **Risk Register**: Update if new risks discovered
- **Memory Bank**: Save findings for reuse

### Files Never to Touch

- `execution_state.json` - Update via checkpoint documentation
- Existing checkpoints - Don't modify, only create new ones
- METHODOLOGY.md - Only if approach fundamentally changes

---

## Success Indicators for Agents

### Code Review Agent
✅ All Phase 1 changes reviewed and approved before Phase 2

### Test Validator Agent
✅ Test coverage > 95%, all suites pass before Phase 4 completion

### Strategic Planner Agent
✅ Detailed plan reconciles with 4-phase approach

### Performance Analyst
✅ Performance metrics < 20% degradation, documented

### Documentation Agent (if any)
✅ All documentation updated, README and CLAUDE.md current

---

## Questions Before You Start

1. **What phase are we on?** → Check `execution_state.json`
2. **What was done last session?** → Read latest checkpoint
3. **What are the active risks?** → Check `RISK_REGISTER.md`
4. **What depends on this work?** → Check `BEAD_DEPENDENCY_GRAPH.md`
5. **How should we test this?** → See `METHODOLOGY.md`

---

## Handoff Template (If Passing to Another Agent)

```markdown
## Handoff: [Target Agent Name]

**Task**: [1-2 sentence summary]
**Bead**: Delos-1b9y-[phase]-[name] (status: in_progress)

### Input Artifacts
- Code: [Key files modified so far]
- Tests: [Test results, if any]
- Checkpoint: [Latest checkpoint link]
- Blockers: [Any blockers discovered]

### Deliverable
[What the next agent should produce]

### Quality Criteria
- [ ] [Criterion 1]
- [ ] [Criterion 2]
- [ ] [Criterion 3]

### Context Notes
[Special context, gotchas, warnings]

### Test Status
- [Test Suite]: [PASS/FAIL/PENDING]
```

---

**Document Control**:
- Created: 2026-01-15
- Version: 1.0
- Status: ACTIVE
- Audience: Downstream agents
