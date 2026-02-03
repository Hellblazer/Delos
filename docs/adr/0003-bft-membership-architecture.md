# ADR-0003: Byzantine Fault Tolerant Membership Architecture

**Status**: ACCEPTED

**Date**: 2026-01-06

**Context**

Delos requires a secure, scalable membership service that can tolerate Byzantine (malicious) node failures in a distributed network. Traditional membership protocols assume fail-stop semantics; Byzantine nodes can lie, forge messages, and coordinate attacks. Additionally, membership changes must be stable and agreed upon across the group to serve as a foundation for consensus.

This ADR documents the architecture of the Fireflies module, which provides a stable, Byzantine fault-tolerant membership service combining insights from three foundational papers.

**Decision**

**We adopt a Byzantine fault-tolerant membership service based on three key design papers:**

1. **[Fireflies: A Secure and Scalable Membership and Gossip Service](https://ymsir.com/papers/fireflies-tocs.pdf)**
   - Core gossip protocol and Byzantine failure detection
   - Accusation mechanism with rebuttal protocol
   - Secure overlay for group communication

2. **[Stable and Consistent Membership at Scale with Rapid](https://www.usenix.org/system/files/conference/atc18/atc18-suresh.pdf)**
   - Virtually synchronous stable membership views
   - Scalable BFT voting for view changes
   - View stability concepts (amplification, shunning)

3. **[Self-stabilizing and Byzantine-Tolerant Overlay Network](https://www.cs.huji.ac.il/~dolev/pubs/opodis07-DHR-fulltext.pdf)**
   - Self-stabilization properties
   - Byzantine tolerance guarantees

**Architecture Components:**

1. **View: Membership Group Representation**
   - Immutable snapshot of agreed-upon group membership at a logical time
   - Members identified via KERI SelfAddressingIdentifiers (Stereotomy)
   - View identity: Hash of HexBloom crown (XOR of all member digests)
   - Location: `fireflies/src/main/java/com/hellblazer/delos/fireflies/View.java`

2. **Binding: Join Protocol Implementation**
   - Handles new members joining existing groups
   - Two-phase protocol: seed contact → redirect to BFT members
   - Byzantine-secure: Requires BFT quorum agreement on membership
   - Gossip-optimized state transfer (not returned by join)
   - Location: `fireflies/src/main/java/com/hellblazer/delos/fireflies/Binding.java`

3. **ViewManagement: Consensus on Membership Changes**
   - Tracks pending joins/leaves
   - BFT subset voting: Only small BFT-selected members vote
   - Vote counts collected by all; membership changes determined atomically
   - Stability detection: Vote only when no rebuttal timers active
   - Location: `fireflies/src/main/java/com/hellblazer/delos/fireflies/ViewManagement.java`

4. **Failure Detection & Liveness**
   - Integrated with gossip (no separate ping protocol)
   - Member considered live if gossip succeeds
   - Accusation mechanism: Members accuse monitored members of failure
   - Rebuttal protocol: Failed members can prove liveness
   - Shunning: Failed members excluded, must rejoin
   - Location: `fireflies/src/main/java/com/hellblazer/delos/fireflies/PhiAccrualFailureDetector.java`

5. **Gossip Protocol**
   - Ring structure: Members arranged in BFT-ordered ring
   - State reconciliation: Bloom filters for efficient diff
   - Gossip partners: Reservoir sampling of membership
   - BFT ring property: Byzantine members in ring isolated
   - Location: `fireflies/src/main/java/com/hellblazer/delos/fireflies/comm/gossip/`

**Rationale**

**Why Combine Three Papers?**

1. **Fireflies**: Strong Byzantine failure detection and attack tolerance
2. **Rapid**: Stable, scalable consensus on membership (not just failure detection)
3. **DHR**: Self-stabilization guarantees: system recovers from any transient state

**Why BFT Voting Subset Instead of Quorum-of-All?**

- **Scalability**: For 1 million members, BFT subset is 13-42 nodes vs 750K quorum members
- **Efficiency**: Reduces state replication and voting rounds by orders of magnitude
- **Determinism**: BFT subset computed via `Context.bftSubset(crown)` using view identity
- **Fairness**: Ring structure ensures fair rotation of voting duty

**Why HexBloom for View Identity?**

From [HEX-BLOOM: An Efficient Method for Authenticity and Integrity Verification](https://eprint.iacr.org/2021/773.pdf):
- XOR of member digests: Compact aggregate authentication
- Rehashing crown: Strong binding of identity to membership
- Tight Bloom filter: Efficient membership proof for joining members
- Prevents tampering: Any membership modification changes view identity

**Why Integrate Failure Detection with Gossip?**

- **Simplicity**: No separate monitoring protocol needed
- **Accuracy**: Liveness defined functionally (can gossip successfully)
- **Efficiency**: Amortizes monitoring cost across gossip
- **GRPC benefit**: GRPC already provides ping-based liveness detection

**Security Properties Guaranteed**

1. **Byzantine Tolerance**: Up to f < n/3 Byzantine members (BFT threshold)
2. **Membership Agreement**: All honest members agree on current view within bounded time
3. **Atomicity**: View changes happen synchronously across all members
4. **Stability**: Members don't vote during instability (rebuttal timers active)
5. **Failure Detection**: Failed members detected and excluded within bounded time
6. **Liveness**: System recovers from temporary state divergences (self-stabilization)

**Design Deviations from Original Papers**

1. **KERI Integration**
   - Members are ControlledIdentifiers (Stereotomy)
   - Gossip includes SignedNotes (equivalent to X509 certificates)
   - Key rotation via Stereotomy key events

2. **Async Membership Discovery**
   - Joining members don't receive all member notes in Join response
   - Distributed state transfer via gossip (reduces join latency)
   - Members pending until crown acquired

3. **No Separate Shunning List**
   - Failed members simply excluded from next view
   - Must use Entrance protocol to rejoin

4. **Practical BFT Tuning**
   - Parameters configurable (fByz, viewSize, gossip fanout)
   - Phi-accrual failure detector for adaptive liveness
   - Exponential backoff for failed contacts

**Integration Points**

1. **Stereotomy**: Identity and key management
2. **KERI SignedNotes**: Member credentials gossiped in state
3. **Gorgoneion**: Bootstrap identities and initial membership
4. **Choam**: Consensus layer (view consensus informs Choam leader)
5. **Ethereal**: Gossip (built on Fireflies overlay)
6. **Thoth**: Decentralized KERL storage (joined members discover via Fireflies)

**Operational Guarantees**

| Property | Guarantee |
|----------|-----------|
| Byzantine Tolerance | f < n/3 nodes |
| View Stability | All honest members converge within ~1 round |
| Join Latency | ~2 gossip rounds + membership discovery |
| Failure Detection | Adaptive via Phi-accrual (typically 5-30 seconds) |
| Gossip Overhead | Bounded per member (fanout × round) |
| View Identity Change | Only on membership change (deterministic) |
| Recovery from Partition | Self-stabilizing, rejoins on network heal |

**Test Coverage**

The Fireflies module achieved comprehensive test coverage as of January 2026:

- **Functionality Tests**: 25+ covering basic operations
- **Byzantine Behavior**: Node lying, message forgery, coordinated attacks
- **Network Partitions**: Member splits, healed partitions
- **Race Conditions**: Concurrent joins, leaves, failures
- **Resource Exhaustion**: Memory, connection limits
- **Recovery Mechanisms**: Failure recovery, view reconciliation
- **Large-Scale Tests**: 100+ member groups, 1000+ member groups

Total: 109 issues resolved, production-ready status

**References**

- Fireflies paper: https://ymsir.com/papers/fireflies-tocs.pdf
- Rapid paper: https://www.usenix.org/system/files/conference/atc18/atc18-suresh.pdf
- DHR paper: https://www.cs.huji.ac.il/~dolev/pubs/opodis07-DHR-fulltext.pdf
- HEX-BLOOM: https://eprint.iacr.org/2021/773.pdf
- Core Implementation: `fireflies/src/main/java/com/hellblazer/delos/fireflies/`
- README: `fireflies/README.md` (detailed design documentation)
- Test Suite: `fireflies/src/test/java/com/hellblazer/delos/fireflies/`

**Related ADRs**

- ADR-0002: KERI Implementation (identity system)
- ADR-0004: Consensus Design (Choam integration)
- ADR-0005: Deterministic SQL (state machine on Fireflies)

---

**Decision Made By**: Quality Initiative Phase 1b Team
**Last Updated**: 2026-01-06
**Status**: ACCEPTED - Implementation complete, 109 issues resolved, production-ready
