# Phase 4: Architecture Review

## Phase Overview

**Status**: NOT STARTED
**Priority**: P4 - LOW-MEDIUM
**Target Duration**: Week 6-8
**Dependencies**: Phase 0, 1, 2, 3 complete

---

## Objectives

1. Add checkpoint chain verification before state restore
2. Implement dynamic diameter adaptation for membership churn
3. Audit DigestAlgorithm.NONE usage across codebase

---

## Task 1: Checkpoint Chain Verification

### Description

Before restoring state from a checkpoint, verify the checkpoint is part of a valid chain. This prevents state corruption from malicious or corrupted checkpoints.

### Location

**Module**: `choam`
**Related**: State restore in Bootstrapper/Synchronizer

### Current State

- Checkpoints are restored without chain verification
- Trust based on checkpoint source
- No validation of checkpoint history

### Required Implementation

```java
public class CheckpointVerifier {
    /**
     * Verify checkpoint is part of valid chain before restore.
     *
     * @param checkpoint The checkpoint to verify
     * @return true if checkpoint is valid
     * @throws InvalidCheckpointException if verification fails
     */
    public boolean verify(Checkpoint checkpoint) {
        // 1. Verify checkpoint hash
        if (!verifyHash(checkpoint)) {
            throw new InvalidCheckpointException("Hash mismatch");
        }

        // 2. Verify parent exists and is valid
        var parent = fetchParent(checkpoint.parentHash());
        if (parent == null) {
            throw new InvalidCheckpointException("Parent not found");
        }

        // 3. Verify signatures from required witnesses
        if (!verifyWitnesses(checkpoint)) {
            throw new InvalidCheckpointException("Insufficient witnesses");
        }

        return true;
    }
}
```

### Chain Verification Algorithm

```
1. Fetch checkpoint C to restore
2. Verify C.hash matches computed hash of C.data
3. Verify C.parentHash points to valid checkpoint P
4. Verify C has signatures from >= threshold witnesses
5. Recursively verify P (up to genesis or last verified)
6. Only restore if entire chain is valid
```

### Test Strategy

```java
@Test
void shouldRejectCheckpointWithBrokenChain() {
    var orphanCheckpoint = createCheckpoint(
        invalidParentHash()
    );

    assertThrows(InvalidCheckpointException.class,
        () -> verifier.verify(orphanCheckpoint));
}

@Test
void shouldAcceptValidCheckpointChain() {
    var genesis = createGenesisCheckpoint();
    var cp1 = createCheckpoint(genesis.hash());
    var cp2 = createCheckpoint(cp1.hash());

    assertTrue(verifier.verify(cp2));
}
```

### Paper Reference

Search mixedbread: `"checkpoint verification state machine replication"`

### Bead

- ID: TBD
- Type: feature
- Priority: 3 (medium)

---

## Task 2: Dynamic Diameter Adaptation

### Description

The membership ring uses a fixed diameter. Under churn, this may not be optimal. Implement dynamic diameter adaptation based on network conditions.

### Location

**Module**: `memberships`
**Key File**: Ring configuration

### Current State

- Diameter is configured at startup
- Does not adapt to network size or churn
- May be suboptimal under different conditions

### Required Implementation

```java
public class DynamicDiameter {
    private final int minDiameter;
    private final int maxDiameter;
    private final double churnThreshold;

    /**
     * Calculate optimal diameter based on current conditions.
     */
    public int calculateDiameter(NetworkConditions conditions) {
        int memberCount = conditions.getMemberCount();
        double churnRate = conditions.getChurnRate();
        double messageOverhead = conditions.getMessageOverhead();

        // Balance between:
        // - Larger diameter: More reliable failure detection
        // - Smaller diameter: Lower message overhead

        if (churnRate > churnThreshold) {
            // High churn: Increase diameter for stability
            return Math.min(currentDiameter + 1, maxDiameter);
        } else if (messageOverhead > targetOverhead) {
            // High overhead: Decrease diameter
            return Math.max(currentDiameter - 1, minDiameter);
        }

        return currentDiameter;
    }
}
```

### Adaptation Algorithm

```
1. Monitor member count, churn rate, message overhead
2. Every adaptation_interval:
   a. Calculate optimal diameter based on conditions
   b. If different from current, propose diameter change
   c. Reach consensus on new diameter
   d. Transition smoothly to new diameter
```

### Test Strategy

```java
@Test
void shouldIncreaseDiameterUnderHighChurn() {
    var adapter = new DynamicDiameter(config);
    var conditions = highChurnConditions();

    int newDiameter = adapter.calculateDiameter(conditions);

    assertTrue(newDiameter > initialDiameter);
}

@Test
void shouldDecreaseDiameterUnderLowChurn() {
    var adapter = new DynamicDiameter(config);
    var conditions = stableConditions();

    int newDiameter = adapter.calculateDiameter(conditions);

    assertTrue(newDiameter <= initialDiameter);
}
```

### Paper Reference

Search mixedbread: `"Fireflies dynamic diameter membership churn"`

### Bead

- ID: TBD
- Type: feature
- Priority: 4 (low)

---

## Task 3: DigestAlgorithm.NONE Audit

### Description

The codebase includes `DigestAlgorithm.NONE` for cases where hashing is not needed. Audit all usages to ensure none are in security-critical paths.

### Location

**Module**: `cryptography`
**Key File**: `cryptography/src/main/java/com/hellblazer/delos/cryptography/DigestAlgorithm.java`

### Audit Process

1. **Find all usages**:
   ```
   Grep(pattern="DigestAlgorithm\\.NONE", path="/Users/hal.hildebrand/git/Delos")
   ```

2. **Categorize each usage**:
   - Test code: Generally safe
   - Development/debugging: Should not be in production
   - Production code: Evaluate security implications

3. **For each production usage**:
   - Document why NONE is used
   - Assess security implications
   - Recommend change or document acceptance

### Documentation Template

```markdown
## DigestAlgorithm.NONE Usage Audit

### Audit Date: [date]
### Auditor: [agent/person]

### Usage Summary

| Location | Category | Risk | Action |
|----------|----------|------|--------|
| [file:line] | Test | None | Accept |
| [file:line] | Production | [Low/Med/High] | [Accept/Change] |

### Production Usages

#### Usage 1: [location]
- **Purpose**: [why NONE is used]
- **Security Impact**: [assessment]
- **Recommendation**: [action]

### Test-Only Usages
[List of test usages - generally safe]

### Recommendations
1. [Recommendation 1]
2. [Recommendation 2]
```

### Expected Categories

- **Safe (Test)**: Used in tests where digest value doesn't matter
- **Safe (Placeholder)**: Used where digest is immediately replaced
- **Questionable**: Used in production code - needs review
- **Unsafe**: Used in security-critical path - must change

### Bead

- ID: TBD
- Type: task
- Priority: 4 (low)

---

## Definition of Done

### Per Task

- [ ] Design documented
- [ ] Implementation complete (if applicable)
- [ ] Tests verify functionality
- [ ] Code reviewed
- [ ] Documentation updated
- [ ] Bead closed

### Phase Complete

- [ ] All 3 tasks complete
- [ ] Full test suite passes
- [ ] Architecture documentation updated
- [ ] EXECUTION_STATE.md updated
- [ ] Project retrospective completed

---

## Estimated Effort

| Task | Research | Design | Implementation | Review | Total |
|------|----------|--------|----------------|--------|-------|
| Checkpoint verification | 2h | 4h | 8h | 2h | 16h |
| Dynamic diameter | 3h | 4h | 10h | 2h | 19h |
| DigestAlgorithm audit | 2h | 0h | 2h | 2h | 6h |
| **Total** | **7h** | **8h** | **20h** | **6h** | **41h** |

---

## Dependencies

### Blocking

Best done after core functionality is stable:
- Phase 0 (Critical Bugs) - foundation
- Phase 1 (Security) - security model understood
- Phase 2 (API) - APIs stable
- Phase 3 (Documentation) - context documented

### Required Resources

- java-architect-planner for design
- java-developer for implementation
- code-review-expert for review
- Performance testing environment

---

## Risk Summary

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Checkpoint verification overhead | MEDIUM | MEDIUM | Lazy verification |
| Diameter changes cause instability | LOW | HIGH | Gradual transition |
| Missed unsafe NONE usage | LOW | MEDIUM | Comprehensive grep |

---

## Post-Phase Activities

### Project Retrospective

After Phase 4 completion:
1. Document lessons learned
2. Update PM infrastructure templates
3. Archive completed beads
4. Write project summary

### Ongoing Maintenance

- Continue monitoring for new issues
- Update documentation as needed
- Address any deferred items

---

*Last Updated: 2025-12-30*
