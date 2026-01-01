# Phase 0: Critical Bugs

## Phase Overview

**Status**: NOT STARTED
**Priority**: P0 - IMMEDIATE
**Target Duration**: Week 1
**Dependencies**: None (blocking all other phases)

---

## Objectives

1. Fix consensus-breaking switch fall-through bug in Ethereal
2. Fix authorization vulnerability in Delphinius staleness check
3. Complete broken expand(Subject) API in Delphinius

---

## Task 1: Ethereal Switch Fall-Through

### Description

The switch statement in `UnanimousVoter.java` is missing break statements, causing POPULAR votes to execute both the POPULAR and UNPOPULAR cases, corrupting consensus voting.

### Location

**File**: `ethereal/src/main/java/com/hellblazer/delos/ethereal/linear/UnanimousVoter.java`
**Lines**: 283-289

### Current Code

```java
switch (counted.vote) {
case POPULAR:
    votesOne = true;
    // MISSING BREAK - falls through!
case UNPOPULAR:
    votesZero = true;
    // MISSING BREAK - falls through!
default:
}
```

### Expected Code

```java
switch (counted.vote) {
case POPULAR:
    votesOne = true;
    break;
case UNPOPULAR:
    votesZero = true;
    break;
default:
    break;
}
```

### Impact

- POPULAR case sets BOTH votesOne AND votesZero to true
- Corrupts consensus voting logic
- May cause incorrect consensus decisions
- Severity: CRITICAL

### Test Strategy

1. **Write failing test first**:
   ```java
   @Test
   void shouldNotFallThroughOnPopularVote() {
       // Setup voter with POPULAR vote
       // Assert only votesOne is true
       // Assert votesZero is false (will fail before fix)
   }
   ```

2. **Verify test fails** for the right reason

3. **Apply fix** (add break statements)

4. **Verify test passes**

5. **Run full Ethereal test suite**

### Paper Reference

Search mixedbread: `"Aleph BFT voting consensus"`

### ChromaDB Reference

- `crossref::ethereal::implementation`

### Bead

- ID: TBD
- Type: bug
- Priority: 1 (critical)

---

## Task 2: Delphinius Staleness Check

### Description

The `check()` method accepts a timestamp but reads current state instead of state at that timestamp, enabling the "New Enemy Problem" where revoked users can access new content.

### Location

**File**: `delphinius/src/main/java/com/hellblazer/delos/delphinius/DirectOracle.java`
**Lines**: 101-107

### Current Code

```java
public boolean check(Assertion assertion, ULong valid) throws SQLException {
    if (valid.compareTo(clock.get()) > 0) {
        return false;  // Only rejects FUTURE timestamps
    }
    return check(assertion);  // Reads CURRENT state, not snapshot at 'valid'
}
```

### Required Behavior

Should implement true snapshot reads at the specified timestamp, similar to Zanzibar's consistency model.

### Attack Scenario

1. User A has access to resource R at time T1
2. User A's access is revoked at time T2
3. New document D is created at time T3
4. User A queries with timestamp T1
5. Current implementation returns current state including D
6. User A gains unauthorized access to D

### Impact

- Authorization vulnerability
- Violates Zanzibar's consistency guarantees
- Potential data exfiltration
- Severity: CRITICAL

### Test Strategy

1. **Write test demonstrating vulnerability**:
   ```java
   @Test
   void shouldNotAccessNewContentWithOldTimestamp() {
       // Grant access to user at T1
       // Revoke access at T2
       // Create new content at T3
       // Query with timestamp T1
       // Assert new content NOT accessible (will fail before fix)
   }
   ```

2. **Design snapshot read mechanism**
   - Use SQL temporal queries
   - Consider MVCC approach
   - Reference Zanzibar paper

3. **Implement fix**

4. **Verify test passes**

### Paper Reference

Search mixedbread: `"Zanzibar authorization staleness zookie"`

### ChromaDB Reference

- `crossref::delphinius::implementation`
- `critique::delphinius::zanzibar-comparison`

### Bead

- ID: TBD
- Type: bug
- Priority: 1 (critical)

---

## Task 3: Delphinius expand(Subject)

### Description

The `expand(Subject)` method returns `Stream.empty()` with a comment indicating incomplete implementation.

### Location

**File**: `delphinius/src/main/java/com/hellblazer/delos/delphinius/AbstractOracle.java`
**Line**: 976

### Current Code

```java
return Stream.empty(); // my brain hurts too much currently to construct the sql
```

### Required Behavior

Should return all objects that the given subject has access to, following the pattern of the `subjects()` method.

### Impact

- Core API broken
- Cannot discover what objects a subject can access
- Limits usability of authorization system
- Severity: HIGH

### Implementation Strategy

1. **Study subjects() implementation**
   - Understand SQL pattern used
   - Identify inverse query structure

2. **Write test for expand(Subject)**:
   ```java
   @Test
   void shouldReturnObjectsForSubject() {
       // Create subject S
       // Grant access to objects O1, O2, O3
       // Call expand(S)
       // Assert returns O1, O2, O3
   }
   ```

3. **Implement SQL query**
   - Follow transitive closure approach
   - Handle indirect access via groups

4. **Verify performance acceptable**

### Paper Reference

Search mixedbread: `"Zanzibar expand check authorization"`

### ChromaDB Reference

- `crossref::delphinius::implementation`

### Bead

- ID: TBD
- Type: bug
- Priority: 2 (high)

---

## Definition of Done

### Per Task

- [ ] Failing test demonstrates bug
- [ ] Fix implemented
- [ ] Test passes
- [ ] All existing tests pass
- [ ] Code reviewed
- [ ] Bead closed

### Phase Complete

- [ ] All 3 tasks complete
- [ ] Full test suite passes
- [ ] No regressions introduced
- [ ] EXECUTION_STATE.md updated
- [ ] CONTINUATION.md updated
- [ ] Ready for Phase 1

---

## Estimated Effort

| Task | Research | Test | Implementation | Review | Total |
|------|----------|------|----------------|--------|-------|
| Ethereal switch | 0.5h | 1h | 0.5h | 1h | 3h |
| Delphinius staleness | 2h | 2h | 4h | 2h | 10h |
| Delphinius expand | 1h | 1h | 3h | 1h | 6h |
| **Total** | **3.5h** | **4h** | **7.5h** | **4h** | **19h** |

---

## Dependencies

### Blocking

Phase 0 must complete before:
- Phase 1 (Security Concerns)
- Phase 2 (API Completion)
- Phase 3 (Documentation)
- Phase 4 (Architecture Review)

### Required Resources

- java-developer for implementation
- code-review-expert for review
- java-debugger if issues arise
- Access to Ethereal and Delphinius test suites

---

## Risk Summary

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Additional bugs discovered | MEDIUM | MEDIUM | Add to Phase 0 scope |
| Tests need significant updates | LOW | LOW | TDD approach |
| Performance regression | LOW | MEDIUM | Benchmark before/after |

---

*Last Updated: 2025-12-30*
