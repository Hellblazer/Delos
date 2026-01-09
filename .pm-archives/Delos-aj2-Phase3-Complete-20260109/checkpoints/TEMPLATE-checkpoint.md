# Checkpoint: [Issue Title]

**Date**: YYYY-MM-DD HH:MM UTC
**Bead**: [Delos-XXXX]
**Phase**: [Phase N]
**Time Spent**: X hours
**Status**: [In Progress / Completed]

---

## Work Completed This Session

### What Was Done
- [ ] Task 1 - Describe what was accomplished
- [ ] Task 2 - Describe what was accomplished
- [ ] Task 3 - Describe what was accomplished

### Code Changes
- **Files Modified**:
  - `path/to/file1.java` - Brief description of change
  - `path/to/file2.java` - Brief description of change

### Tests Created/Modified
- `path/to/test1.java` - New test demonstrating the issue
- `path/to/test2.java` - Integration test for the fix

### Build Status
- [ ] `./mvnw clean install` - PASS / FAIL
- [ ] Gorgoneion module tests - PASS / FAIL
- [ ] Integration tests - PASS / FAIL (if applicable)
- [ ] Coverage improved - YES / NO

---

## Key Learnings

### Technical Insights
- [Learning 1]: Describe what you discovered about the code/issue
- [Learning 2]: Describe a design decision you made
- [Learning 3]: Describe any unexpected findings

### Design Decisions Made
- [Decision 1]: Why did you choose approach X over Y?
- [Decision 2]: What trade-offs were made?

### Integration Notes (if applicable)
- How does this affect Stereotomy integration?
- How does this affect Fireflies integration?
- Any Byzantine assumptions affected?

---

## Testing Coverage

### Unit Tests
- [ ] Normal case covered
- [ ] Error cases covered
- [ ] Edge cases covered
- [ ] Byzantine scenarios covered (if applicable)

### Test Execution
```
Test Results:
- Total tests: X
- Passed: X
- Failed: 0
- Skipped: 0
- Coverage: XX%
```

### Coverage Gaps (if any)
- Gap 1: Describe any untested paths
- Gap 2: Plan for future coverage

---

## Blockers Encountered

### Resolved Blockers
- [Blocker 1]: Description and how it was resolved
- [Blocker 2]: Description and how it was resolved

### Active Blockers (if any)
- **Blocker Name**: [Description]
  - Impact: How this blocks progress
  - Workaround: Any temporary solution?
  - Escalation: Who to contact?
  - Target Resolution: When will it be fixed?

### Assumptions Made
- Assumption 1: Describe assumption and why it was made
- Assumption 2: Describe assumption and why it was made

---

## Next Steps

### Immediate (Before Next Session)
1. [Next action 1] - What should be done immediately
2. [Next action 2] - What should be done immediately
3. [Next action 3] - What should be done immediately

### This Issue (Remaining Work)
1. [Remaining task 1] - Estimate: X hours
2. [Remaining task 2] - Estimate: X hours
3. [Remaining task 3] - Estimate: X hours

### Dependencies for Next Issue
- [Dependency 1]: This issue depends on Delos-YYYY
- [Dependency 2]: Fireflies integration must be verified

---

## Code Review Readiness

### Pre-Review Checklist
- [ ] Tests pass: `./mvnw clean install`
- [ ] Code follows style guide
- [ ] No commented-out code
- [ ] No debug logging
- [ ] Security review (if applicable) - PASS / FAIL
- [ ] Performance is acceptable
- [ ] Documentation updated

### Review Points
- [ ] Key file 1 (`file1.java`): Lines XX-YY - Explain the critical change
- [ ] Key file 2 (`file2.java`): Lines XX-YY - Explain the critical change
- [ ] Test file: Lines XX-YY - Explain how test validates the fix

### Security Considerations (Phase 0)
- [ ] Cryptographic operations validated
- [ ] No sensitive data in logs
- [ ] Authentication properly enforced
- [ ] Input validation complete
- [ ] Error handling secure

---

## Metrics

### Code Quality
- Lines of code changed: X
- Files modified: X
- Test lines added: X
- Comment ratio: X%

### Performance Impact
- Signature validation time: XXms (was: XXms)
- Memory usage: XXmb (was: XXmb)
- Other performance metrics: [list any]

### Test Coverage
- Lines covered: X/X (XX%)
- Functions covered: X/X (XX%)
- Branches covered: X/X (XX%)

---

## Integration Impact

### Stereotomy Integration
- [ ] No changes to Stereotomy API usage
- [ ] All Stereotomy integration tests pass
- [ ] Signature validation still works
- [ ] Key event handling still works

### Fireflies Integration
- [ ] No changes to Fireflies API usage
- [ ] All Fireflies integration tests pass
- [ ] Byzantine-cut validation still works
- [ ] Membership service still works

### Other Module Impact
- Any other modules affected?
- Any breaking changes?
- Any version compatibility issues?

---

## Session Notes

### What Went Well
- [Positive 1]: Describe what went smoothly
- [Positive 2]: Describe what was efficient

### What Could Be Better
- [Improvement 1]: What slowed you down?
- [Improvement 2]: What was confusing?

### Time Breakdown
- Analysis/Understanding: X hours
- Test Writing: X hours
- Implementation: X hours
- Testing/Debugging: X hours
- Documentation: X hours

---

## Commit Information

### Commit Hash(es)
```
[commit-hash-1]
[commit-hash-2]
[commit-hash-3]
```

### Commit Message Template
```
fix: [Brief description] [Delos-XXXX]

- What was wrong
- How it was fixed
- How it's tested
- Any breaking changes

References: Delos-XXXX
```

---

## Sign-Off

- [ ] Code tested locally - PASS
- [ ] Full build passes - PASS
- [ ] Integration tests pass - PASS
- [ ] Ready for code review - YES
- [ ] Ready to close bead - [YES / NO]

---

## Related Documentation

- **Bead**: [Link to bead in tracking system]
- **Phase Details**: [Link to phase documentation]
- **Risk Register**: [Link to risks if applicable]
- **Methodology**: [Link to engineering discipline]

---

**Checkpoint Author**: [Your name]
**Review Status**: [Pending / In Review / Approved]
**Ready for Merge**: [YES / NO]

---

## For Code Reviewer

### What Changed
[1-2 sentence summary of the change]

### Why It Was Changed
[1-2 sentence explanation of why this was necessary]

### How to Test
```bash
# Command to test this change
./mvnw test -pl gorgoneion -Dtest=[TestClass]
```

### Critical Code Sections
- File 1: Lines XX-YY - [Why this matters]
- File 2: Lines XX-YY - [Why this matters]

### Questions for Reviewer
- [Question 1]: Should we handle case X differently?
- [Question 2]: Is approach Y secure enough?

---

**Last Updated**: YYYY-MM-DD HH:MM UTC
**Status**: [In Progress / Completed / Blocked]
