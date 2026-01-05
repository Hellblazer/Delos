# BFT Assumptions in Delos Fireflies

**Date**: 2026-01-01
**Bead**: Delos-b11
**Status**: Complete

## Executive Summary

Delos Fireflies implements a Byzantine fault-tolerant membership service that synthesizes ideas from three academic papers: the original Fireflies paper, Rapid, and Stable-Fireflies. The system uses a **probabilistic ring-based approach** to achieve BFT properties with dramatically reduced voting overhead compared to traditional approaches.

Key findings:
- toleranceLevel = (ringCount - 1) / bias (default bias = 2)
- Two different majority thresholds: context.majority() for general BFT ops, 3/4 for view consensus
- BFT subset selection is deterministic and verifiable
- System provides probabilistic rather than deterministic BFT guarantees

---

## 1. Tolerance Formula

### Code Location
**File**: `/Users/hal.hildebrand/git/Delos/memberships/src/main/java/com/hellblazer/delos/context/Context.java`
**Lines**: 42-44, 504-506

### Formula

```java
// Static method (Context.java lines 42-44)
static int toleranceLevel(int rings, int bias) {
    return ((rings - 1) / bias);
}

// Instance method (Context.java lines 504-506)
default int toleranceLevel() {
    return (getRingCount() - 1) / getBias();
}
```

### Interpretation

With default **bias = 2**:
- `toleranceLevel = (ringCount - 1) / 2`

| ringCount | toleranceLevel (t) | Interpretation |
|-----------|-------------------|----------------|
| 5 | 2 | System tolerates 2 Byzantine nodes |
| 11 | 5 | System tolerates 5 Byzantine nodes |
| 13 | 6 | System tolerates 6 Byzantine nodes |
| 21 | 10 | System tolerates 10 Byzantine nodes |

### Ring Count Derivation

**File**: `/Users/hal.hildebrand/git/Delos/memberships/src/main/java/com/hellblazer/delos/context/DynamicContextImpl.java`
**Lines**: 52-61

```java
for (int i = 0; i < (minMajority(pByz, cardinality, epsilon, bias) * bias) + 1; i++) {
    rings.add(new Ring<>(i, this));
}
```

So: `ringCount = minMajority * bias + 1 = t * 2 + 1 = 2t + 1`

The `minMajority` function (Context.java lines 62-81) computes the minimum t such that the probability of more than t Byzantine nodes in a (bias*t+1) subset is <= epsilon/cardinality, using binomial probability.

### Default Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| pByz | 0.1 | Probability any node is Byzantine (10%) |
| epsilon | 0.99999 | Reliability guarantee (five 9s) |
| bias | 2 | Multiplier for Byzantine tolerance |

### Guarantee

**Probabilistic n/3 bound**: Given the probabilistic calculation, the system aims to tolerate approximately n/3 Byzantine nodes with probability >= 1 - epsilon/n, assuming pByz is accurate.

**IMPORTANT**: This is a **probabilistic guarantee**, not a deterministic one. The classic BFT bound of f < n/3 is approximated through probabilistic ring sampling.

---

## 2. Majority Threshold

### Code Location
**File**: `/Users/hal.hildebrand/git/Delos/memberships/src/main/java/com/hellblazer/delos/context/Context.java`
**Lines**: 38-40, 255-257

### Formula

```java
// Static method (lines 38-40)
static int majority(int rings, int bias) {
    return (bias - 1) * toleranceLevel(rings, bias);
}

// Instance method (lines 255-257)
default int majority() {
    return getRingCount() - toleranceLevel();
}
```

### Interpretation

With default bias = 2:
- `majority = ringCount - toleranceLevel = ringCount - (ringCount-1)/2`

| ringCount | toleranceLevel | majority | Percentage |
|-----------|---------------|----------|------------|
| 5 | 2 | 3 | 60% |
| 11 | 5 | 6 | 54.5% |
| 13 | 6 | 7 | 53.8% |
| 21 | 10 | 11 | 52.4% |

### Critical Distinction: View Change Supermajority

**File**: `/Users/hal.hildebrand/git/Delos/fireflies/src/main/java/com/hellblazer/delos/fireflies/View.java`
**Lines**: 433-434

```java
final var supermajority = context.getRingCount() * 3 / 4;
final var majority = context.size() == 1 ? 1 : supermajority;
```

**View change uses 3/4 threshold on ring count!** This is Rapid-style consensus.

| ringCount | context.majority() | View supermajority (3/4) |
|-----------|-------------------|-------------------------|
| 11 | 6 | 8 |
| 13 | 7 | 9 |
| 21 | 11 | 15 |

### Usage Contexts

| Operation | Threshold Used | Code Location |
|-----------|---------------|---------------|
| Mask validity | context.majority() | View.java:163 |
| View change consensus | ringCount * 3/4 | View.java:433 |
| Join validation | context.majority() | ViewManagement.java:392 |
| hasMajorityObservations() | context.majority() | View.java:480 |

### Threshold Decision Documentation

A detailed analysis is available at:
**File**: `/Users/hal.hildebrand/git/Delos/memberships/docs/THRESHOLD_DECISION.md`

This document explains why 2/3+1 (2t+1) was chosen over Rapid's 3/4 threshold for most operations.

---

## 3. BFT Subset Selection

### Code Location
**File**: `/Users/hal.hildebrand/git/Delos/memberships/src/main/java/com/hellblazer/delos/context/Context.java`
**Lines**: 111-124

### Implementation

```java
default SequencedSet<T> bftSubset(Digest hash) {
    return bftSubset(hash, m -> true);
}

default SequencedSet<T> bftSubset(Digest hash, Predicate<T> filter) {
    var collector = new LinkedHashSet<T>();
    uniqueSuccessors(hash, filter, collector);
    return collector;
}
```

And the uniqueSuccessors implementation (DynamicContextImpl.java lines 732-739):

```java
public void uniqueSuccessors(Digest key, Predicate<T> test, Set<T> collector) {
    for (Ring<T> ring : rings) {
        T successor = ring.successor(key, m -> !collector.contains(m) && test.test(m));
        if (successor != null) {
            collector.add(successor);
        }
    }
}
```

### Algorithm

1. Take a hash (digest) as seed
2. For each ring in order:
   a. Find first successor of hash on that ring
   b. If successor passes filter and not already collected, add to result
3. Return LinkedHashSet (preserves insertion order)

### Properties

| Property | Value | Explanation |
|----------|-------|-------------|
| **Deterministic** | Yes | Same hash + same membership = same subset |
| **Verifiable** | Yes | Any node can compute independently |
| **Pseudo-random** | Yes | Hash determines position on each ring |
| **Maximum size** | ringCount | One unique member per ring |
| **Minimum size** | 1 | Same member could be successor on all rings |

### Distribution Analysis

The selection is **uniform** given:
1. Hash function is cryptographically random
2. Rings use different hash orderings (via context hash prefixing)
3. Members are uniformly distributed on each ring

**Verification** (DynamicContextImpl.java line 749-752):
```java
private Digest[] hashesFor(T m) {
    for (int ring = 0; ring < rings.size(); ring++) {
        s[ring] = hashFor(key, ring);  // Different hash per ring
    }
}
```

### View Observer Selection

**File**: `/Users/hal.hildebrand/git/Delos/fireflies/src/main/java/com/hellblazer/delos/fireflies/ViewManagement.java`
**Lines**: 644-652

```java
private void resetObservers() {
    observers.clear();
    context.bftSubset(diadem.get().compact(), context::isActive)
           .stream()
           .map(Member::getId)
           .forEach(d -> observers.put(d, -1));
    if (observers.isEmpty()) {
        observers.put(node.getId(), -1); // bootstrap case
    }
}
```

The **view identity hash** (diadem.compact()) seeds the BFT subset selection, ensuring:
- Observers are tied to the current view
- All nodes compute the same observer set
- View change produces new observer set

---

## 4. Attack Vectors Under f Byzantine Nodes

### Byzantine Node Capabilities (f <= toleranceLevel)

| Attack | Possible? | Mitigation |
|--------|-----------|------------|
| Refuse gossip | Yes | Accusation + shunning after rebuttalTimeout |
| Send conflicting votes | Yes | Signatures provide non-repudiation |
| Delay responses | Yes | Timeout triggers failure detection |
| Coordinate accusations | Yes | Need t+1 accusations; honest can rebut |
| Flood join requests | Limited | maxPending bounds pending joins |
| Eclipse attack | Difficult | Ring structure provides multiple paths |

### Byzantine Node Limitations (f <= toleranceLevel)

| Attack | Prevented? | Mechanism |
|--------|-----------|-----------|
| Forge signatures | Yes | Cryptographic assumption (KERI/Stereotomy) |
| Unilateral view change | Yes | Requires 3/4 observer agreement |
| Permanently shun honest node | Yes | Honest nodes can rebut accusations |
| Corrupt HexBloom crown | Yes | XOR of all member IDs, validated |
| Manipulate bftSubset | Yes | Deterministic from view hash |
| Partition honest majority | Yes | Gossip ensures TTL propagation |

### Safety Properties

1. **View Consistency**: All honest nodes converge to the same view
   - Guaranteed by 3/4 observer supermajority (View.java:452)
   - View ID = HexBloom crown hash (unforgeable)

2. **Membership Integrity**: Crown hash validates membership set
   - HexBloom XOR construction (ViewManagement.java:278-280)
   - Joining member verifies crown + bloom filter

3. **Liveness**: Honest nodes can always rebut false accusations
   - Rebuttal timeout = 2 * TTL rounds (Parameters.java)
   - New Note with incremented epoch rebuts

4. **Eventual Progress**: System makes progress if < 1/4 observers Byzantine
   - View change requires 3/4 observer agreement
   - With ringCount observers, need > ringCount/4 Byzantine to block

### Attack Scenario Analysis

**Scenario 1: Coordinated Accusation Attack**
- Byzantine nodes coordinate to accuse honest node H
- Need t+1 accusations on SAME RING to exceed tolerance
- H can rebut by issuing new Note within rebuttalTimeout
- Amplification only occurs AFTER failed rebuttal

**Scenario 2: View Splitting Attempt**
- Byzantine observers try to create different view changes
- Need 3/4 of observers (not members) to agree
- With ~ringCount observers and t Byzantine, need t >= ringCount/4
- For ringCount=11, need 3 Byzantine observers (possible but detectable)

**Scenario 3: Join Flood**
- Byzantine attacker floods join requests
- Bounded by maxPending (default 200)
- Each join requires seed + redirect + Gateway
- Rate limiting in effect through pending slot exhaustion

---

## 5. Identified Gaps and Concerns

### 5.1 Threshold Inconsistency

**Issue**: Two different majority calculations are used:
- `context.majority()` for mask validity, general BFT
- `ringCount * 3/4` for view consensus

**Impact**: Could cause confusion; different thresholds for different operations.

**Mitigation**: This is intentional - documented in THRESHOLD_DECISION.md. The 3/4 threshold for view consensus follows Rapid for deterministic convergence, while 2t+1 is used elsewhere for maximum Byzantine tolerance.

### 5.2 Mask Validation Gap

**Location**: View.java lines 162-173

```java
public static boolean isValidMask(BitSet mask, DynamicContext<?> context) {
    if (mask.cardinality() == context.majority()) {
        if (mask.length() <= context.getRingCount()) {
            return true;
        }
    }
    return false;
}
```

**Issue**: Validation only checks:
- Cardinality equals majority
- Length <= ringCount

**Missing**: No verification that disabled rings match accusation state.

**Attack Vector**: Byzantine node could potentially set mask to disable rings where it was NOT accused, evading valid accusations on other rings.

**Recommendation**: Consider adding accusation-aware mask validation.

### 5.3 Static TTL Concern

**Location**: View.java lines 133-135

```java
// CRITICAL: Must use static timeToLive() for safety-critical timers
// dynamicTimeToLive() can cause timing violations during network growth
this.roundTimers = new RoundScheduler(..., context.timeToLive());
```

**Issue**: TTL = ringCount * diameter + 1 is calculated once. If membership changes significantly during operation, TTL may become invalid.

**Reference**: Delos-8b7 bug documented in comment

### 5.4 BFT Subset Size Variance

**Location**: ViewManagement.java lines 653-656

```java
if (observers.size() > 1 && observers.size() < context.getRingCount()) {
    log.debug("Incomplete observers: {} cardinality: {}...");
}
```

**Issue**: In small membership scenarios, same member could be successor on multiple rings, reducing observer count below ringCount.

**Impact**: Reduces effective BFT guarantee; fewer observers to corrupt.

### 5.5 Probabilistic vs Deterministic Guarantee

**Issue**: The system provides a **probabilistic** BFT guarantee based on pByz assumption, not a deterministic n/3 bound.

**Assumption**: pByz = 0.1 (10% of nodes are Byzantine) must hold for probabilistic guarantees to apply.

**If violated**: Higher-than-expected Byzantine ratio could exceed tolerance bounds despite passing binomial probability test.

---

## 6. Summary Formula Table

| Parameter | Formula | Example (ringCount=11, bias=2) |
|-----------|---------|-------------------------------|
| toleranceLevel | (ringCount - 1) / bias | 5 |
| context.majority() | ringCount - toleranceLevel | 6 |
| View supermajority | ringCount * 3/4 | 8 |
| bftSubset size | min(ringCount, unique successors) | ~11 |
| TTL | ringCount * diameter + 1 | ~23 rounds |
| minMajority | computed via binomial prob | depends on pByz, epsilon |

---

## 7. Code Reference Index

| Concept | File | Lines |
|---------|------|-------|
| toleranceLevel() | Context.java | 42-44, 504-506 |
| majority() | Context.java | 38-40, 255-257 |
| bftSubset() | Context.java | 111-124 |
| uniqueSuccessors() | DynamicContextImpl.java | 732-739 |
| minMajority() | Context.java | 62-81 |
| Ring creation | DynamicContextImpl.java | 52-61 |
| View supermajority | View.java | 433-434 |
| Mask validation | View.java | 162-173 |
| Observer reset | ViewManagement.java | 644-659 |

---

## 8. References

### Academic Papers
1. [Fireflies: A Secure and Scalable Membership and Gossip Service](https://ymsir.com/papers/fireflies-tocs.pdf) - Amir et al., TOCS 2013
2. [Stable and Consistent Membership at Scale with Rapid](https://www.usenix.org/system/files/conference/atc18/atc18-suresh.pdf) - Suresh et al., USENIX ATC 2018
3. [Self-stabilizing and Byzantine-Tolerant Overlay Network](https://www.cs.huji.ac.il/~dolev/pubs/opodis07-DHR-fulltext.pdf) - Dolev et al., OPODIS 2007

### Internal Documentation
- `/Users/hal.hildebrand/git/Delos/memberships/docs/THRESHOLD_DECISION.md`
- `/Users/hal.hildebrand/git/Delos/fireflies/README.md`

---

*Document Version*: 1.0
*Created*: 2026-01-01
*Bead*: Delos-b11
