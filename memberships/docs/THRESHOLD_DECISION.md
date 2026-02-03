# Threshold Decision: 2/3+1 vs 3/4 for Byzantine Fault Tolerance

## Summary

Fireflies uses a **2t+1 super-majority threshold** (approximately 2/3+1 of nodes) for Byzantine fault tolerance decisions, rather than the 3/4 threshold specified in some membership protocols like Rapid. This document explains the rationale, security implications, and trade-offs of this architectural decision.

**Decision**: Use the classical BFT threshold of `2t+1` where `t = floor((n-1)/3)` is the maximum number of tolerated Byzantine faults.

## Context

### The Problem

Distributed membership protocols must determine how many nodes need to agree before taking action (such as removing a suspected-faulty node). Two common thresholds are:

1. **2t+1 (approximately 2/3+1)**: The classical Byzantine Fault Tolerance threshold
2. **3/4**: Used in protocols like Rapid for membership consensus

The choice affects:
- **Safety**: Protection against Byzantine attackers
- **Liveness**: Ability to make progress under failures
- **Fault tolerance capacity**: Maximum number of faulty nodes tolerated

## Decision

### Threshold Formulas

The threshold calculations are defined in `DynamicContext.java`:

```java
// memberships/src/main/java/com/hellblazer/delos/context/DynamicContext.java

public static int toleranceLevel(int cardinality) {
    return (cardinality - 1) / 3;  // t = floor((n-1)/3)
}

public static int majority(int cardinality) {
    return toleranceLevel(cardinality) + 1;  // t + 1
}

public static int superMajority(int cardinality) {
    return 2 * toleranceLevel(cardinality) + 1;  // 2t + 1
}
```

### Numerical Examples

| Nodes (n) | Tolerance (t) | Super-majority (2t+1) | Percentage | 3/4 Threshold |
|-----------|--------------|----------------------|------------|---------------|
| 4         | 1            | 3                    | 75.0%      | 3             |
| 7         | 2            | 5                    | 71.4%      | 6             |
| 10        | 3            | 7                    | 70.0%      | 8             |
| 13        | 4            | 9                    | 69.2%      | 10            |
| 31        | 10           | 21                   | 67.7%      | 24            |
| 100       | 33           | 67                   | 67.0%      | 75            |
| infinity  | n/3          | 2n/3 + 1             | 66.7%      | 75.0%         |

As cluster size increases, 2t+1 approaches 2/3, while 3/4 remains constant at 75%.

### Usage in Fireflies

The super-majority threshold is used in `View.java` for accusation decisions:

```java
// fireflies/src/main/java/com/hellblazer/delos/fireflies/View.java

private final int superMajority;

// Initialization in constructor:
this.superMajority = context.superMajority();

// Usage - checking rebuttal majority:
public boolean isRebuttalMajority(Digest from, Set<Participant> memberSet) {
    return cardinality >= superMajority;
}
```

## Security Analysis

### 2t+1 Threshold (Delos Choice)

**Guarantees**:
- Tolerates up to `t < n/3` Byzantine nodes
- Any two quorums of size 2t+1 intersect in at least one honest node
- Adversary needs control of `> n/3` nodes to violate safety

**Mathematical Proof of Safety**:
- Two quorums of size 2t+1 each, drawn from n = 3t+1 nodes
- Total slots: 2(2t+1) = 4t+2
- Available nodes: 3t+1
- Overlap: 4t+2 - (3t+1) = t+1
- Since at most t nodes are Byzantine, at least one honest node is in both quorums

### 3/4 Threshold (Rapid Approach)

**Guarantees**:
- Tolerates up to `t < n/4` Byzantine nodes for certain properties
- Higher redundancy in quorum intersection
- Adversary needs control of `> n/4` nodes to violate safety

**Trade-off**:
- 25% lower fault tolerance capacity (n/4 vs n/3)
- 12% higher agreement requirement (75% vs 67%)

### Comparative Analysis

| Property | 2t+1 (Delos) | 3/4 (Rapid) | Winner |
|----------|--------------|-------------|--------|
| Max Byzantine tolerance | n/3 | n/4 | 2t+1 |
| Required agreement | 67% | 75% | 2t+1 |
| Quorum intersection | t+1 | n/2 | 3/4 |
| Latency (responses needed) | Lower | Higher | 2t+1 |
| Partition tolerance | Standard | Higher | 3/4 |

## Trade-offs

### Advantages of 2t+1

1. **Higher Fault Tolerance**: Tolerates 33% more Byzantine nodes than 3/4 threshold
   - Example: 100-node cluster tolerates 33 vs 25 Byzantine nodes

2. **Better Liveness**: Requires fewer responses to make progress
   - Example: 100-node cluster needs 67 vs 75 responses
   - Reduces tail latency in heterogeneous networks

3. **Theoretical Optimality**: Proven minimal threshold for BFT safety
   - Established in DLS (1988) and PBFT (1999)
   - Used in Tendermint, HotStuff, and other modern BFT protocols

4. **Ecosystem Consistency**: Matches other Delos components
   - CHOAM uses same quorum sizes
   - Ethereal (Aleph BFT) uses same threshold
   - Consistent security model across the stack

### Advantages of 3/4

1. **Larger Safety Margin**: More nodes must agree, reducing edge cases

2. **Better Partition Handling**: Harder for either side of a partition to make progress alone

3. **Deterministic Convergence**: Rapid's design assumes this threshold for its proofs

### Why 2t+1 is Appropriate for Fireflies

Fireflies compensates for the smaller quorum intersection with additional mechanisms:

1. **Cryptographic Accusations**: All accusations are signed, providing non-repudiation

2. **Ring-Based Redundancy**: Each node is monitored by multiple ring successors

3. **Rebuttal Windows**: Time-bounded opportunity to defend against accusations

4. **Probabilistic View Convergence**: Gossip-based view propagation

## Code References

### Primary Threshold Calculations
- **File**: `memberships/src/main/java/com/hellblazer/delos/context/DynamicContext.java`
- **Methods**: `toleranceLevel()`, `majority()`, `superMajority()`

### Threshold Usage in Accusations
- **File**: `fireflies/src/main/java/com/hellblazer/delos/fireflies/View.java`
- **Field**: `superMajority`
- **Method**: `isRebuttalMajority()`

### Context Interface
- **File**: `memberships/src/main/java/com/hellblazer/delos/context/Context.java`
- **Methods**: `toleranceLevel()`, `majority()`, `superMajority()`, `isSuperMajority()`

## References

### Academic Papers

1. **DLS (1988)**: Dwork, Lynch, Stockmeyer - "Consensus in the Presence of Partial Synchrony"
   - Establishes n >= 3t+1 lower bound for Byzantine consensus

2. **PBFT (1999)**: Castro, Liskov - "Practical Byzantine Fault Tolerance"
   - Proves 2t+1 quorum sufficiency for BFT state machine replication

3. **Rapid (2018)**: Suresh et al. - "Stable and Consistent Membership at Scale with Rapid"
   - USENIX ATC '18 - Uses 3/4 threshold for deterministic membership convergence

4. **HotStuff (2019)**: Yin et al. - "HotStuff: BFT Consensus with Linearity and Responsiveness"
   - Modern BFT using 2t+1 threshold

### Implementation References

- **Tendermint**: Uses 2/3+1 threshold (same as Delos)
- **Ethereum 2.0**: Uses 2/3 threshold for attestations
- **LibraBFT/DiemBFT**: Uses 2t+1 threshold

## Conclusion

The 2t+1 threshold is the correct choice for Fireflies because:

1. It provides the **theoretical maximum** Byzantine fault tolerance (n/3 nodes)
2. It offers **better liveness** properties for practical deployments
3. It is **consistent** with the broader Delos architecture
4. Fireflies' **cryptographic mechanisms** compensate for smaller quorum intersection
5. It follows **established BFT theory** proven over decades of research

---

*Document Version*: 1.0
*Last Updated*: 2025-12-31
*Related Beads*: Delos-868.14
