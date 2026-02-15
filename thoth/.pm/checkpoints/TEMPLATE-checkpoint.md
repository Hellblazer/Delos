# Checkpoint: [Phase Number] - [Brief Title]

**Session Date**: [YYYY-MM-DD]
**Session Duration**: [Hours]
**Bead(s)**: THOTH-xxx, THOTH-yyy
**Status**: [in_progress/completed]

## Context

**Current Phase**: [Phase number and name]
**What Were You Doing**: [Brief summary of work focus]
**Where Did You Leave Off**: [Specific point in code/tests]

## Work Completed This Session

### Completed Items
- [ ] [Item 1 with brief description]
- [ ] [Item 2 with brief description]
- [ ] [Item 3 with brief description]

### Code Changes
- **File 1**: [Brief description of changes]
- **File 2**: [Brief description of changes]

### Tests Added/Modified
- **TestClass1**: [Number of tests, coverage areas]
- **TestClass2**: [Number of tests, coverage areas]

### Byzantine FT Verification
- [ ] Tested with f Byzantine nodes in 3f+1 setup
- [ ] Membership change scenarios verified
- [ ] Network partition scenarios tested
- [ ] Witness majority requirements validated

## In Progress

### Currently Working On
- **Task**: [Description]
- **File**: [Primary file being edited]
- **Status**: [% complete if estimable]
- **Next Step**: [What to do next]

### Blockers & Issues
- [ ] [Blocker 1 - what blocks progress?]
- [ ] [Blocker 2 - what blocks progress?]

### Decisions Pending
- [ ] [Decision 1 - what needs to be decided?]
- [ ] [Decision 2 - what needs to be decided?]

## Key Discoveries & Learnings

### New Learning 1
- **Discovery**: [What did you learn?]
- **Why It Matters**: [Importance for the module]
- **Evidence**: [Code references, test results]
- **Action**: [What should be done with this learning?]

### New Learning 2
- **Discovery**: [What did you learn?]
- **Why It Matters**: [Importance for the module]
- **Evidence**: [Code references, test results]
- **Action**: [What should be done with this learning?]

## Test Results

### Test Execution
```
Test Suite: thoth
Total Tests: [Number]
Passed: [Number]
Failed: [Number]
Skipped: [Number]
Duration: [Minutes:Seconds]
```

### Coverage Analysis
- **Overall Coverage**: [X%]
- **KerlDht**: [X%]
- **Ani**: [X%]
- **Maat**: [X%]
- **ReconciliationService**: [X%]

### Byzantine FT Test Results
- **Scenarios**: [Number total]
- **Passing**: [Number]
- **Failing**: [Number]
- **Coverage Notes**: [f+1 tested with N nodes, etc.]

### Test Gaps Identified
- [ ] [Gap 1 - not yet tested]
- [ ] [Gap 2 - not yet tested]

## Metrics Updated

### Test Coverage
- Previous: [X%]
- Current: [X%]
- Target: 85%
- Change: [±X%]

### Performance Baselines
- DHT Lookup: [Xms] (prev: [Xms])
- DHT Insert: [Xms] (prev: [Xms])
- Rebalancing: [Xs] (prev: [Xs])
- Validation: [X events/sec] (prev: [X events/sec])

### Other Metrics
- [Metric Name]: [Current value]
- [Metric Name]: [Current value]

## Files Modified

```
Affected Files:
- src/main/java/com/hellblazer/delos/thoth/KerlDht.java
- src/main/java/com/hellblazer/delos/thoth/Ani.java
- src/test/java/com/hellblazer/delos/thoth/KerlDhtTest.java

Files Created:
- src/test/java/com/hellblazer/delos/thoth/ByzantineFtTest.java
- .pm/hypotheses/H1-kerl-dht-topology.md

Files Deleted:
- [None]
```

## Code Review Findings (If Applicable)

### Issues Addressed
- [ ] [Issue 1 - what was the issue and how was it fixed?]
- [ ] [Issue 2 - what was the issue and how was it fixed?]

### Deferred Items
- [ ] [Item 1 - why is it deferred?]
- [ ] [Item 2 - why is it deferred?]

## Immediate Next Actions

### Priority 1 (Critical Path)
- [ ] **Action 1**: [What needs to be done]
  - Bead: THOTH-xxx
  - Estimated Time: [X hours]
  - Dependencies: [What blocks this?]

### Priority 2 (High)
- [ ] **Action 2**: [What needs to be done]
  - Bead: THOTH-xxx
  - Estimated Time: [X hours]

### Priority 3 (Medium)
- [ ] **Action 3**: [What needs to be done]
  - Bead: THOTH-xxx
  - Estimated Time: [X hours]

## Lessons Learned

### What Went Well
- [Positive observation 1]
- [Positive observation 2]

### What Could Be Better
- [Improvement opportunity 1]
- [Improvement opportunity 2]

### Changes for Next Session
- [ ] [Change 1]
- [ ] [Change 2]

## Session Summary for Next Resume

**Quick Facts for Next Session**:
- Current phase: [Phase X - Name]
- Progress: [X% complete]
- Main focus: [What was this session about?]
- Key file being worked on: [File name]
- Latest test results: [Passing/Failing summary]
- Main blocker (if any): [Blocker description]

**To Resume**:
1. Read this checkpoint
2. Check active beads: `bd list --status=in_progress`
3. Review blockers section above
4. Run: `./mvnw test -pl thoth` to verify current state
5. Continue with Priority 1 actions above

---

## Checkpoint Metadata

- **Checkpoint Number**: [N]
- **Phase**: [Phase number]
- **Contributor**: [Your name]
- **Session Duration**: [Hours:Minutes]
- **Total Productive Hours on Phase**: [Cumulative]
- **Estimated Remaining for Phase**: [Hours]
- **Phase Progress**: [X%]

