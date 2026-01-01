# Byzantine Fault Tolerance Assumptions in Fireflies

## Executive Summary

Delos Fireflies provides Byzantine fault-tolerant membership and gossip services with probabilistic guarantees. This document formalizes the BFT assumptions, failure thresholds, safety properties, and known limitations.

**Core Guarantee**: The system tolerates up to `t` Byzantine nodes where `t = (ringCount - 1) / bias` with probability `>= 1 - epsilon/n`.

## 1. BFT Model and Parameters

### 1.1 Fundamental Formula

**Tolerance Level**:
```java
toleranceLevel = (ringCount - 1) / bias
```

**Ring Count Derivation**:
```java
ringCount = (bias * minMajority) + 1
```

**Default Parameters** (from Context.java):
- `pByz = 0.1` - Probability that any given node is Byzantine (10%)
- `epsilon = 0.99999` - Reliability target (five 9s)
- `bias = 2` - Byzantine tolerance multiplier

**Example Configurations**:

| ringCount | bias | toleranceLevel (t) | Can tolerate | Total nodes needed |
|-----------|------|-------------------|--------------|-------------------|
| 11        | 2    | 5                 | 5 Byzantine  | 16+ nodes        |
| 13        | 2    | 6                 | 6 Byzantine  | 19+ nodes        |
| 21        | 2    | 10                | 10 Byzantine | 31+ nodes        |

### 1.2 Probabilistic Guarantee

The system computes `minMajority(pByz, cardinality, epsilon, bias)` such that:

**Probability of failure** `pf <= epsilon / cardinality`

Where failure means: more than `t` out of `(bias * t) + 1` monitors are Byzantine.

**Source**: Context.java lines 62-81
```java
double pf = 1.0 - Util.binomialc(t, (bias * t) + 1, pByz);
```

This uses binomial probability to ensure that with high confidence (epsilon), the system can tolerate Byzantine behavior.

### 1.3 Two Different Majority Thresholds

**CRITICAL DISTINCTION**: Fireflies uses TWO different majority thresholds for different purposes.

#### Mask Majority (Ring Participation)
**Formula**: `majority = ringCount - toleranceLevel`

**Purpose**: Defines how many rings a member must actively participate in.

**Source**: Context.java lines 38-40
```java
static int majority(int rings, int bias) {
    return (bias - 1) * toleranceLevel(rings, bias);
}
```

**Example** (ringCount=11, bias=2, toleranceLevel=5):
```
majority = 11 - 5 = 6 rings
```

Members must have at least 6 enabled rings in their participation mask.

#### View Change Supermajority (Consensus)
**Formula**: `supermajority = ringCount * 3 / 4`

**Purpose**: Quorum for view change consensus (inherited from Rapid paper).

**Source**: View.java lines 396-397
```java
final var supermajority = context.getRingCount() * 3 / 4;
final var majority = context.size() == 1 ? 1 : supermajority;
```

**Example** (ringCount=11):
```
supermajority = 11 * 3 / 4 = 8 observations required
```

**Rationale** (README.md line 86): Rapid paper uses 3/4+1 threshold to prevent split-brain during view changes. Fireflies adopted this for stability.

**Comparison Table**:

| ringCount | Mask majority | View supermajority (3/4) |
|-----------|---------------|-------------------------|
| 11        | 6             | 8                       |
| 13        | 7             | 9                       |
| 21        | 11            | 15                      |

## 2. Network Assumptions

### 2.1 Synchrony Model

**Partial Synchrony**: The system assumes:
- Messages eventually delivered (no permanent partition)
- Bounded message delays (eventually)
- Nodes have approximately synchronized clocks for timeouts

**Gossip-Based Liveness**: Unlike traditional BFT (which requires explicit timeouts), Fireflies uses gossip completion as the liveness signal.

**Source**: README.md lines 134-149
- No separate ping protocol
- Gossip success = liveness
- View mismatch = failure

### 2.2 Communication Model

- **Authenticated Channels**: All messages use MTLS with KERI-based identities
- **No Message Loss**: GRPC provides reliable delivery
- **Fair Message Exchange**: Gossip ensures all correct nodes receive updates despite Byzantine interference

**Failure Detection**: Phi Accrual Failure Detector (adaptive to network conditions)
- Default threshold: `phi = 16.0`
- Statistical model adjusts to observed latency patterns

## 3. Cryptographic Assumptions

### 3.1 Digital Signatures

**Assumption**: KERI (Key Event Receipt Infrastructure) signatures are:
- Unforgeable under chosen message attack
- Verifiable by all participants
- Bound to member identity

**Source**: Stereotomy module provides KERI identities

**Byzantine CANNOT**:
- Forge signatures of honest nodes
- Impersonate honest members (without key compromise)

### 3.2 Hash Functions

**Assumption**: Cryptographic hash functions (default SHA-256) provide:
- Collision resistance: infeasible to find `x != y` where `H(x) = H(y)`
- Second preimage resistance: given `x`, infeasible to find `y` where `H(x) = H(y)`
- Pseudo-random output for ring placement

**Usage**:
- Ring member ordering: `hashFor(contextId, ringIndex, memberId)`
- View identity: `digest(HexBloom.compact())`
- BFT subset selection: deterministic from hash

### 3.3 HexBloom Crown

**View Identity Construction**:
```
crown = XOR(member_digest_1, member_digest_2, ..., member_digest_n)
viewId = digest(crown.compact())
```

**Security Property**: Membership changes imply view identity changes.

**Collision Resistance**: With SHA-256 (256 bits), birthday bound ≈ 2^128 operations. For 1M members, collision probability ≈ 10^-65 (negligible).

**Source**: README.md lines 122-130

## 4. Safety Properties

### 4.1 View Consistency

**Property**: All correct nodes eventually agree on the same view membership.

**Mechanism**:
- BFT subset votes on view changes
- 3/4 supermajority required for consensus
- View ID cryptographically binds membership set

**Guarantee**: As long as `<= t` Byzantine nodes exist in a ring of `2t+1`, honest nodes converge to same view.

### 4.2 Membership Integrity

**Property**: Byzantine nodes cannot unilaterally add or remove honest members.

**Mechanism**:
- Join requires BFT majority agreement (Context.bftSubset approval)
- Shunning requires accusation + failed rebuttal
- View changes require 3/4 consensus

**Source**: ViewManagement.java lines 344-413 (join protocol)

### 4.3 Accusation Safety

**Property**: Honest nodes can rebut false accusations.

**Mechanism**:
- Accused node has `rebuttalTimeout` rounds to issue new Note
- New Note with updated epoch invalidates accusation
- Shunning only occurs after failed rebuttal

**Rebuttal Timeout**: `2 * timeToLive()` rounds (typically ~6 rounds, ~60 seconds)

**Source**: Parameters.java, View.java accusation protocol

### 4.4 Liveness

**Property**: System makes progress as long as `< 1/4` observers are Byzantine.

**Conditions for Progress**:
1. Gossip succeeds between honest nodes
2. View consensus achieves 3/4 supermajority
3. No pending rebuttals (stable state)

**Liveness Violation Scenarios**:
- `>= 1/4` of observers Byzantine → can block view changes
- All BFT subset members Byzantine → can prevent join
- Network partition → honest majority isolated

## 5. BFT Subset Selection

### 5.1 Deterministic Selection Algorithm

**Source**: Context.java lines 111-124, DynamicContextImpl.java lines 732-739

```java
default SequencedSet<T> bftSubset(Digest hash, Predicate<T> filter) {
    var collector = new LinkedHashSet<T>();
    uniqueSuccessors(hash, filter, collector);
    return collector;
}
```

**Algorithm**:
1. For each ring `r` in `[0, ringCount)`:
   - Find successor of `hash` on ring `r`
   - Add to subset if unique and passes filter
2. Return unique members

**Properties**:
- **Deterministic**: Same hash + same membership → same subset
- **Verifiable**: Any node can independently compute
- **Pseudo-random**: Hash acts as seed for selection
- **Maximum size**: `ringCount` (one unique member per ring)

### 5.2 BFT Subset Size

**Typical Sizes** (depends on membership overlap across rings):

| Total Members | ringCount | Typical BFT Subset Size |
|---------------|-----------|------------------------|
| 100           | 11        | 10-11                  |
| 1,000         | 13        | 12-13                  |
| 1,000,000     | 42        | 38-42                  |

**Contrast with Rapid**: For 1M members, Rapid would require 750K votes. Fireflies uses ~40.

### 5.3 Byzantine Attack Resistance

**Byzantine CAN**:
- Refuse to gossip (triggers accusation)
- Send conflicting votes (signatures provide evidence of equivocation)
- Delay responses (timeout triggers failure detection)

**Byzantine CANNOT** (with `f <= t`):
- Manipulate bftSubset selection (deterministic from hash)
- Unilaterally prevent view change (need >= 1/4 to block)
- Forge view consensus (signatures prevent forgery)

**Attack Vector**: If adversary controls `t+1` Byzantine nodes and gets lucky with `bftSubset(digest)` selection such that all observers are Byzantine, they can block that specific operation. However:
- Probability decreases exponentially with cluster size
- Different operations use different digests → different subsets
- System uses multiple independent consensus operations

## 6. Timing Assumptions

### 6.1 Time-to-Live (TTL)

**Formula** (Context.java):
```java
timeToLive = (ringCount * diameter) + 1
```

**Diameter Calculation**:
```java
diameter = log(cardinality) / log(cardinality * pN)
where pN = (bias * toleranceLevel) / cardinality
```

**Purpose**: Number of gossip rounds for message propagation across cluster.

**CRITICAL**: TTL must be recalculated when membership changes significantly.

**Known Issue** (Delos-8b7): Static TTL can become invalid after rebalance. View.java lines 133-135 document this:
```java
// CRITICAL: Must use static timeToLive() for safety-critical timers
// dynamicTimeToLive() can cause timing violations during network growth
```

**Implication**: If membership grows significantly, accusations may expire before reaching all nodes.

### 6.2 Rebuttal Timeout

**Value**: `2 * timeToLive()` rounds

**Purpose**: Give accused nodes time to rebut across entire cluster.

**Failure Mode**: If TTL is too short (e.g., after rebalance), honest nodes may fail to rebut in time.

## 7. Known Limitations and Attack Vectors

### 7.1 Probabilistic Tolerance

**Limitation**: BFT guarantee is probabilistic, not deterministic.

**Assumption**: The actual Byzantine ratio in the cluster matches `pByz` (default 10%).

**Risk**: If actual Byzantine ratio exceeds `pByz`, the probability guarantee weakens.

**Mitigation**: Conservative default (10%) provides margin. Production deployments should tune `pByz` based on threat model.

### 7.2 BFT Subset Variance

**Limitation**: BFT subset size can be smaller than `ringCount` in small clusters.

**Scenario**: With 5 members and 11 rings, many rings will have overlapping successors.

**Impact**: Effective BFT subset may be only 5 members instead of 11.

**Mitigation**: System designed for large clusters (100+ members). Small clusters have reduced Byzantine tolerance.

### 7.3 Sybil Attack Surface

**Attack**: Adversary floods join requests with multiple identities.

**Current Defense**:
- `maxPending = 200` join slots (soft limit)
- KERI identity validation (computational cost)

**Gap**: No explicit rate limiting on join requests per source.

**Mitigation**: Deploy identity bootstrapping (Gorgoneion) with proof-of-stake or proof-of-work.

### 7.4 Coordinated Byzantine Subset Capture

**Attack**: `t+1` Byzantine nodes coordinate to dominate a specific `bftSubset(digest)`.

**Probability**: Decreases with cluster size. For 1000 members with `t=6`:
- Adversary needs all 6+ Byzantine nodes selected
- Probability ≈ (7/1000)^6 ≈ 1.18 × 10^-13

**Mitigation**: System uses many independent `bftSubset` calls with different digests for different operations.

### 7.5 View Change Denial-of-Service

**Attack**: Byzantine observers refuse to vote, blocking view changes.

**Threshold**: Requires `>= 1/4` of `ringCount` observers Byzantine.

**Mitigation**:
- 3/4 threshold means 1/4 can fail without blocking
- Failed observers get accused and shunned
- System eventually excludes Byzantine nodes

### 7.6 Static TTL After Rebalance

**Issue**: TTL not updated when membership changes (Delos-8b7).

**Impact**: Accusations may not propagate in time in growing clusters.

**Status**: Known bug, documented in code comments (View.java lines 133-135).

**Workaround**: Avoid massive membership growth (10x) without restart.

## 8. Comparison with Classical BFT

### 8.1 Classical BFT (PBFT, Tendermint)

**Tolerance**: `f < n/3` deterministic
- System of `n = 3f + 1` nodes tolerates `f` Byzantine failures

**Quorum**: `2f + 1` for safety (majority of `3f + 1`)

**Synchrony**: Partial synchrony with explicit timeouts

### 8.2 Fireflies BFT

**Tolerance**: `f < n/3` probabilistic
- System of `ringCount = (bias * t) + 1` rings tolerates `t` Byzantine with probability `>= 1 - epsilon/n`
- Actual node count `n` can be >> `3t + 1` (e.g., 1M nodes, 42 rings)

**Quorum**: `3/4` for view consensus (inherited from Rapid)

**Synchrony**: Gossip-based liveness (no explicit timeouts, uses adaptive failure detection)

### 8.3 Key Difference: Scalability

**Classical BFT**: All `n` nodes vote → O(n^2) messages
- 1M nodes: 1M votes, 1 trillion messages

**Fireflies BFT**: `ringCount` nodes vote → O(ringCount^2) messages
- 1M nodes, 42 rings: 42 votes, ~1,764 messages

**Trade-off**: Probabilistic guarantee vs. deterministic, but massive scalability improvement.

## 9. Testing and Validation Gaps

### 9.1 Missing Tests

**Byzantine Behavior Simulation**:
- No tests with malicious nodes forging signatures
- No tests for coordinated Byzantine attacks
- No tests for equivocation (conflicting messages)

**Partition Scenarios**:
- No tests for network partitions
- No tests for partition healing
- No tests for oscillating partitions

**Threshold Edge Cases**:
- No tests verifying `t+1` Byzantine nodes can be tolerated
- No tests validating `3/4` threshold prevents split-brain

### 9.2 Recommended Validations

1. **Monte Carlo Simulation**: Run 10,000 simulations with varying Byzantine ratios to validate probabilistic bound.

2. **Byzantine Test Harness**: Inject Byzantine behaviors (delayed messages, forged signatures, equivocation) and verify detection/shunning.

3. **Partition Recovery Tests**: Split cluster 60/40, verify minority halts, majority continues, and cluster heals on merge.

4. **Stress Tests**:
   - 1M member cluster formation
   - Rapid join/leave churn
   - BFT subset distribution analysis

## 10. Operational Considerations

### 10.1 Tuning Parameters

**Conservative Production Settings**:
```java
pByz = 0.05          // Assume 5% Byzantine (lower risk)
epsilon = 0.999999   // Six 9s reliability
bias = 2             // Standard Byzantine tolerance
```

**High-Risk Environment**:
```java
pByz = 0.20          // Assume 20% Byzantine
epsilon = 0.999999   // Six 9s reliability
bias = 3             // Higher Byzantine tolerance (more rings needed)
```

### 10.2 Monitoring Metrics

**Key Metrics to Track**:
- `accusationRate`: High rate indicates Byzantine activity or network issues
- `rebuttalTimeouts`: Failed rebuttals may indicate Byzantine or timing issues
- `viewChangeFrequency`: Frequent changes indicate instability
- `bftSubsetSize`: Should be close to `ringCount`

### 10.3 Deployment Guidelines

**Minimum Cluster Size**: `(bias * toleranceLevel) + 1 + toleranceLevel`
- For bias=2, toleranceLevel=5: minimum 16 nodes

**Bootstrap Security**: Use trusted seeds for initial join to prevent bootstrap attacks.

**Identity Management**: Deploy Gorgoneion for secure identity bootstrapping with attestation.

## 11. References

### Academic Papers
1. **Fireflies**: [A Secure and Scalable Membership and Gossip Service](https://ymsir.com/papers/fireflies-tocs.pdf) - Amir et al., TOCS 2013
2. **Stable-Fireflies**: [Self-Stabilizing Byzantine Asynchronous Unison](https://www.cs.huji.ac.il/~dolev/pubs/opodis07-DHR-fulltext.pdf) - Dolev et al., OPODIS 2007
3. **Rapid**: [Stable and Consistent Membership at Scale](https://www.usenix.org/system/files/conference/atc18/atc18-suresh.pdf) - Suresh et al., ATC 2018
4. **HEX-BLOOM**: [An Efficient Method for Authenticity and Integrity Verification](https://eprint.iacr.org/2021/773.pdf) - 2021

### Code References
- **Context.java**: Lines 38-44 (tolerance formulas), lines 62-81 (probabilistic calculation), lines 111-124 (bftSubset)
- **View.java**: Lines 396-397 (supermajority), lines 162-173 (mask validation)
- **ViewManagement.java**: Lines 344-413 (join protocol), lines 278-280 (crown validation)
- **README.md**: Lines 86-92 (BFT subset rationale), lines 134-149 (liveness definition)

### Known Issues
- **Delos-8b7**: Static TTL after rebalance (View.java lines 133-135)

## Document Metadata
- **Created**: 2026-01-01
- **Bead**: Delos-b11
- **Author**: java-developer
- **Status**: Phase 0 Documentation
- **Revision**: 1.0
