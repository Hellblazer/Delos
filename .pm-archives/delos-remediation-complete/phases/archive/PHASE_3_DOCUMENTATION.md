# Phase 3: Documentation

## Phase Overview

**Status**: NOT STARTED
**Priority**: P3 - MEDIUM
**Target Duration**: Week 5
**Dependencies**: Phase 0, 1, 2 complete

---

## Objectives

1. Document 2/3 vs 3/4 threshold decision for Fireflies
2. Document recovery protocol mapping to lightweight-SMR
3. Document thread safety models for key components

---

## Task 1: Fireflies Threshold Decision

### Description

Fireflies uses 2/3+1 threshold while the Rapid paper specifies 3/4 for cut detection. This deviation needs documented justification.

### Location

**Module**: `fireflies`
**Key File**: `fireflies/src/main/java/com/hellblazer/delos/fireflies/View.java`

### Paper References

- **Rapid paper**: Uses 3/4 quorum for cut detection
- **Fireflies paper**: May have different threshold requirements

### Documentation Required

```markdown
## Fireflies Membership Threshold Decision

### Background
The Rapid paper specifies a 3/4 (75%) threshold for cut detection in
membership changes. The Delos implementation uses a 2/3+1 (~67%) threshold.

### Rationale for Deviation
[Document why 2/3+1 was chosen]

### Trade-off Analysis

| Aspect | 3/4 Threshold | 2/3+1 Threshold |
|--------|---------------|-----------------|
| Safety margin | Higher | Lower |
| Liveness | Lower | Higher |
| Byzantine tolerance | f < n/4 | f < n/3 |
| Convergence time | Slower | Faster |

### Adversarial Scenarios
[Document scenarios where difference matters]

### Recommendation
[Keep current or change?]

### Configurable Threshold
Consider making threshold configurable:
```java
public class View {
    private final double membershipThreshold;  // Default 2/3+1

    public View(ViewConfig config) {
        this.membershipThreshold = config.getThreshold();
    }
}
```
```

### Test Strategy

```java
@Test
void shouldDocumentThresholdBehavior() {
    // Test behavior at exactly 2/3+1 threshold
    // Document expected behavior in test comments
}
```

### Paper Search

```
mcp__mixedbread__store_search(
    query="Rapid membership threshold quorum cut detection",
    store_identifiers=["delos"]
)
```

### Bead

- ID: TBD
- Type: documentation
- Priority: 3 (medium)

---

## Task 2: Recovery Protocol Mapping

### Description

CHOAM's recovery is implicit in Bootstrapper/Synchronizer. The lightweight-SMR paper (Algorithm 6) defines formal T-window recovery. Document how implementation maps to paper.

### Location

**Module**: `choam`
**Key Files**:
- `choam/src/main/java/com/hellblazer/delos/choam/Bootstrapper.java`
- `choam/src/main/java/com/hellblazer/delos/choam/Synchronizer.java`

### Documentation Required

```markdown
## CHOAM Recovery Protocol Mapping

### Paper Reference
Lightweight-SMR Algorithm 6: T-window recovery protocol

### Implementation Mapping

| Paper Step | Implementation Location | Notes |
|------------|------------------------|-------|
| Initialize T-window | Bootstrapper.init() | [details] |
| Fetch missing logs | Synchronizer.fetch() | [details] |
| Apply transactions | Bootstrapper.apply() | [details] |
| Validate state | Synchronizer.validate() | [details] |

### Recovery Guarantees

| Guarantee | Paper Specification | Implementation Status |
|-----------|--------------------|-----------------------|
| T-window recovery | Within T blocks | [Verified/TBD] |
| State consistency | Hash verification | [Verified/TBD] |
| Log completeness | All txns applied | [Verified/TBD] |

### Code Comments

Add inline documentation mapping to paper:
```java
/**
 * Implements Algorithm 6 Step 2: Fetch missing log entries.
 *
 * Paper: "For each missing block b in range [last_committed, current_height]:
 *         request b from peers with matching state hash"
 */
public void fetchMissingLogs() {
    // Implementation
}
```

### Verification

Document how to verify recovery works correctly:
1. Checkpoint test
2. Recovery from crash test
3. Network partition recovery test
```

### Paper Search

```
mcp__mixedbread__store_search(
    query="lightweight SMR recovery protocol T-window Algorithm 6",
    store_identifiers=["delos"]
)
```

### Bead

- ID: TBD
- Type: documentation
- Priority: 3 (medium)

---

## Task 3: Thread Safety Documentation

### Description

Several components have thread safety concerns noted in the critique. Document the threading models and verify correctness.

### Components

#### 3.1 BlockClock Thread Safety

**Location**: `h2-deterministic/src/main/java/org/h2/util/BlockClock.java:39-48`

**Concern**: Volatile fields with compound read-modify-write

**Documentation Required**:
```markdown
## BlockClock Threading Model

### Current Implementation
- Uses volatile fields for height and txn
- Compound operations (read-modify-write) are NOT atomic

### Threading Assumption
BlockClock is designed for single-threaded execution within
deterministic SQL state machine. Concurrent access is not supported.

### Verification
[How to verify single-threaded access is enforced]

### Alternative
If concurrent access needed, use AtomicLong:
```java
private final AtomicLong height = new AtomicLong();
private final AtomicLong txn = new AtomicLong();
```
```

#### 3.2 BloomWindow Thread Safety

**Location**: `cryptography/src/main/java/com/hellblazer/delos/bloomFilters/BloomWindow.java`

**Concern**: Potential false negatives during buffer transitions

**Documentation Required**:
```markdown
## BloomWindow Threading Model

### Current Implementation
- Maintains rotating buffers for time-windowed membership
- Buffer swap may cause false negatives

### Mitigation
Overlap membership during transition period:
```java
public boolean contains(byte[] element) {
    // Check both current and previous buffer during transition
    return current.contains(element) || previous.contains(element);
}
```

### Trade-offs
- Overlap: Slightly higher memory, fewer false negatives
- No overlap: Lower memory, potential false negatives at boundary
```

#### 3.3 Voting Memo Thread Safety

**Location**: `ethereal/src/main/java/com/hellblazer/delos/ethereal/linear/UnanimousVoter.java`

**Concern**: HashMap passed to constructor, not concurrent

**Documentation Required**:
```markdown
## UnanimousVoter Threading Model

### Current Implementation
- Uses HashMap for voting memos
- Not thread-safe for concurrent access

### Recommendation
If concurrent access needed:
```java
private final Map<Key, Vote> memos = new ConcurrentHashMap<>();
```

Or document single-threaded access requirement:
```java
/**
 * Note: This class is NOT thread-safe.
 * All access must be from a single thread.
 */
public class UnanimousVoter { ... }
```
```

### Overall Documentation Structure

Create `docs/THREADING_MODELS.md`:
```markdown
# Delos Threading Models

## Single-Threaded Components
- BlockClock (deterministic execution)
- [others]

## Concurrent-Safe Components
- [list with mechanisms]

## Thread-Confined Components
- [list with confinement strategy]

## Synchronization Patterns Used
- Volatile fields: [where, why]
- Concurrent collections: [where, why]
- Locks: [where, why]
```

### Bead

- ID: TBD
- Type: documentation
- Priority: 3 (medium)

---

## Definition of Done

### Per Task

- [ ] Research complete
- [ ] Documentation written
- [ ] Code comments added where needed
- [ ] Technical review completed
- [ ] Examples included
- [ ] Bead closed

### Phase Complete

- [ ] All 3 tasks complete
- [ ] Documentation reviewed for accuracy
- [ ] Cross-references verified
- [ ] EXECUTION_STATE.md updated
- [ ] Ready for Phase 4

---

## Estimated Effort

| Task | Research | Writing | Review | Total |
|------|----------|---------|--------|-------|
| Threshold decision | 3h | 3h | 1h | 7h |
| Recovery mapping | 4h | 4h | 2h | 10h |
| Thread safety | 3h | 4h | 2h | 9h |
| **Total** | **10h** | **11h** | **5h** | **26h** |

---

## Dependencies

### Blocking

Best done after implementation phases complete:
- Phase 0 (Critical Bugs) - understand fixes
- Phase 1 (Security) - document security models
- Phase 2 (API) - document new APIs

### Can Run In Parallel

Some documentation can start earlier if needed.

### Required Resources

- deep-research-synthesizer for paper analysis
- java-architect-planner for technical review
- Technical writer if available

---

## Risk Summary

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Inaccurate documentation | MEDIUM | HIGH | Technical review |
| Missing edge cases | LOW | MEDIUM | Test-based verification |
| Documentation rot | MEDIUM | LOW | Link to code, not copy |

---

*Last Updated: 2025-12-30*
