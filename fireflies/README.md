# Delos Fireflies

_The bioluminescent family of winged beetles model not only the on/off behavior of members, but like Byzantine members
they are also known for their aggressive mimicry in order to dupe and devour related species._

---
Delos Fireflies is fundamentally based on the most excellent
paper [Fireflies: A Secure and Scalable Membership and Gossip Service](https://ymsir.com/papers/fireflies-tocs.pdf).
Fireflies provides the base secure, byzantine intrusion tolerant communications overlay for the rest of the Delos
stack. This implementation relies upon the base  _Context_  and  _Member_  abstraction from the  *membership* module of
Delos.

## Design

The Fireflies implementation in Delos differs significantly from the original paper. The Delos implementation combines
the ideas of the [original Fireflies paper](https://ymsir.com/papers/fireflies-tocs.pdf) as well as the additional ideas
from two most excellent papers:

* [Self-stabilizing and Byzantine-Tolerant Overlay Network](https://www.cs.huji.ac.il/~dolev/pubs/opodis07-DHR-fulltext.pdf)
* [Stable and Consistent Membership at Scale with Rapid](https://www.usenix.org/system/files/conference/atc18/atc18-suresh.pdf)

and synthesizes the three designs into a BFT stable Rapid like membership and secure overlay service.

### Stable, Virtually Synchronous View Membership Service

Delos Fireflies provides a stable, virtually synchronous membership view in much the same fashion as the Rapid paper
describes. Membership is agreed upon across the group and changes only by consensus within the group. This provides an
incredibly stable foundation for utilizing the BFT secure overlay that Fireflies provides.

Members must formally join a context. This is a two phase protocol of first contacting a seed (any one will do) and then
following the redirection to further members of the context to await the next view that includes the joining member. To formally join
a context (cluster) of nodes, the joining member needs to know the complete membership of the context.  This membership 
can be quite large.  Thus, the membership of the view is discovered, rather than returned from the Join prototocol.

The join response the member receives is a Gateway from the
redirect members (the seeding members supplied by the first phase of the Join protocol).
The Gateway of this join contains a hash of the  _crown_  of the view (see view identity below) as well as a tight Bloom
Filter that defines the membership set. This scheme is based off the most excellent
paper [HEX-BLOOM: An Efficient Method for Authenticity and Integrity Verification in Privacy-preserving Computing](https://eprint.iacr.org/2021/773.pdf).
This allows the joining member to precisely validate the membership of the joined View that it quickly acquires via the underlying gossip of the View.

This Join protocol is scalable and reuses the underlying Fireflies state reconcilliation to fill out the remaining
membership using gossip across the membership. After the Join protocol completes, the member gossips normally with peers 
in the same view and participates in view changes. A member will not vote upon a view or accept Seed or Join 
requests until the view it is joining membership has been fully acquired.

### Byzantine Fault Tolerant Join Protocol

Delos Fireflies extends the *Rapid* Join protocol into a BFT form. This fits naturally into the Fireflies model and
relies, of course, on the BFT ring structure of the Fireflies *View* context. When the joining member contacts a seed,
the redirect is to the BFT members of the seed's context, returning what would be the successors of the joining member
in the current context. The joining member must acquire a BFT majority agreement on the *crown* of the joining view and
the membership bloom filter that defines the membership set. This provides Delos Fireflies with a byzantine fault
tolerand and secure Join protocol. Which is pretty cool.

### Gossip Optimized Join

Note that Delos Fireflies does not return all the _SignedNotes_ (Delos Fireflies KERI equivalent of an X509
certificate) of all the members known in a view when responding to the Join. During the Join protocol members may 
not exist in the joining member's context beyond the seed set and cannot be contacted. They are essentially in a
pending state where the joining member is awaiting the state transfer of the these members'  _SignedNotes_ . Upon
obtaining all members' notes the member then computes the  _crown_  of the membership and the view ID is the digest
value of  _crown.compact()_.

Delos Fireflies reuses the  _redirect_  part of the original Fireflies protocol to redirect the joining members to
their correct successors in the current state of the view. The redirected joining member uses this discovery to fill out
it's membership in the context. The underlying gossip state replication provides the state transfer of all the member's
SignedNote state for the view to the joining member. This occurs rather quickly and spreads this rather large plug of
state transfer required by the join of a view across the system, rather than punishing a member or two haphazardly. 
It's also async, schocastic and BFT, which is nice.

### View Consensus Voting
After a member has joined, the member can then participate in the global consensus that determines the view membership
evolution. This process deviates significantly from
the [Rapid paper](https://www.usenix.org/system/files/conference/atc18/atc18-suresh.pdf).  In this implementation, there
is a distinct BFT subset of the View membership that are responsible for Observations - that is, the ballots for changing
a View from mebership set A to membership set B.  This set is chosen using the Context.bftSubset(digest) method and results
in a small number of members Fireflies delegates the view change voting responsibilities.

In response to a number of Joins or Leaves, a vote will be scheduled on each View.  Only the bft membership subset defined by
using the compact hash of the View's HexBloom crown may vote and only their votes are counted across the membership.  This
gives use a byzantine fault tolerant way of cheaply getting trustworthy votes.  When voting occurs, all members count the votes
and change the view accordingly, but only the bft meber subset voted.  Besides being quite secure and highly available, using this
scheme is incredibly cheap as this BFT Subset cardinality is way, way less - in general - than the cardinality of the group.

In the original Rapid paper, a 3/4+1 consensus is used and Fireflies had that for a while as well.  However, this would
not scale to the extent we would like - e.g. 1,000,000 members.  So using the BFT subset, we're reusing the fundamental
BFT Ring Structure of the Fireflies context  _itself_  which I also find highly satisfying.  So for 1,000,000 members
rather than having 750,000 ballots we can leverage the View Context's rings to compute the BFT subset and get away with
13-42 (depending on pByz one chooses).  This is a dramatic reduction both in state - we don't need 750K ballots to replicate
around to everyone, nor do we need to get them to all agree and count them up.  So the Voting protocol now seems
both secure and efficient.

Only incremental state transfer occurs for existing members during future joins, modulo any state reconcilliation
transfer for joining members. Steady state overhead is bounded and independent of the number of members in the view.
As this is all based on the underlying gossip protocol, and the network bandwidth and compute spread evenly across the system.

### Designed For Stablity

Delos Fireflies leverages the same sort of  __"almost everywhere"__  agreement that  _Rapid_  provides. Rather than
using a clever windowing proxy for stability as Rapid does, Delos reuses the Accusation mechanism of the Fireflies
protocol. Member  _instability_  is defined as existing  _rebuttal timers_  when the vote is proposed. If there are no
rebuttal timers, the View is considered to be in a  _stable_  state for the member. When a member is stable, and if
there are non zero joining/leavings, and the member is an Observer (member of the BFT subset), then a  _Vote_  on 
the new membership view is created and submitted. After the vote is submitted, the member awaits the fast path 
consensus on the membership.

#### Shunning

Delos Fireflies also differs from the original Fireflies by enforcing the _shunning_ of members that have failed. In
the original protocol, members can come back to life after failing to rebut accusations if they issue a new note. In
Delos Fireflies, like Rapid, the failing member is shunned after failing to rebut and must rejoin the system again.

#### Amplification

Delos also implements the same  _amplification_  strategy of Rapid. When a member fails to rebut an accusation, the
member is now _shunned_ and will not be responded to. All members that witness the failing member that are assigned as
monitors of the failing member will issue  __additional__  Accusations if they have not already done so. This positive
feedback reinforces the consensus decision that the member is dead, Jim, and drives the system to a common decision
quite quickly.

## View Identity

Views are membership groups and are identified by a hash (32 bytes by default). The  _crown_  of a View in Delos
Fireflies is a HexBloom (see the cryptography module) and is defined by the set of digest IDs of the total membership
XORd together. The  _identity_  of the view is the public rehashing of the crowns of the HexBloom. This final rehashing
of the crown becomes the View ID. The integration of HexBloom into the calculation of the view id ensures that
individual membership identities are authenticated in aggregate simply and compactly and validated with a strong binding
through rehashing to produce the view identifier. Group membership is strongly bound to the  _identifier_  of the View.
When the group membership set changes - i.e. members joining or leaving - the view identifier also changes.

## Liveness, Failure Detection and Monitoring

Unlike other popular solutions, Delos Fireflies does not have a separate monitoring  _ping_  failure detection
protocol. For example, the Fireflies papers and Rapid both use a separate monitoring  _ping_  for failure detection.
Delos Fireflies depends on the continual gossip with partners and thus the monitored member is also the member gossiped
with. Delos Fireflies combines these two logical operations into one. Therefore, a member is considered  _live_  if
they can complete gossip communication without failure or exception.

Note that  _failure_  and  _liveness_  now include understanding the state of consensus membership. Views that do not
share a common view identity do not communicate with each other and will treat this as a failure and thus accuse
appropriately. Likewise when a member is shunned, or the receiving member is in the process of joining a view.

This is a crisp definition of  _liveness_  that also matches the functional operation of the protocol. This does mean
that, unlike Rapid, Delos Fireflies cannot (currently) monitor non Fireflies members; Delos requires gossip with
monitored members to ensure liveness. Delos Fireflies view membership maintenance protocol is intimately tied to the
functional use of the system, so this makes perfect sense to me. Also, Delos Fireflies operates over GRPC, which itself
does  _liveness_  pings, etc. So Delos Fireflies merely leverages this existing communications infrastructure to elide
the necessity of a separate monitoring protocol.

## Current Limitations

The Fireflies module is functionally complete. Full bootstrap and join integration with Thoth is complete and tested.

## Status

Delos Fireflies is functionally complete and production-ready. As of January 2026, the module has undergone comprehensive
remediation addressing 109 issues across 4 phases: critical production blockers, security hardening, architectural
decomposition, and comprehensive testing. Full test suite added including Byzantine behavior, network partition, race
condition, resource exhaustion, and recovery mechanism tests.

__Current Functionality__

* Byzantine intrusion tolerant secure communications overlay
* Robust, stable, virtually synchronous view membership
* Incredibly cheap, highly scalable gossip-based implementation
* PKI integration
    * Pluggable authentication. KERI-based authorization as well as simple, trad CA authentication.
    * Node identity, validation, and key management managed via Stereotomy Identifiers
* Certificate key rotation
    * provided by Stereotomy key rotation facilities
* Identity bootstrapping
    * provided by Gorgoneion module
* Full generalization of key algorithms, hash and signing algorthm, etc
    * provided by Stereotomy and the crypto utils

## Public API Reference

### Core Classes

#### `View` (Membership Representation)
**Location**: `fireflies/src/main/java/com/hellblazer/delos/fireflies/View.java`

Immutable representation of consensus-agreed membership at a logical time.

**Key Methods**:
- `getId(): Digest` - Returns view identity (rehashed HexBloom crown)
- `getCrown(): Digest` - Returns XOR aggregate of all member digests
- `getMembers(): Set<Member>` - Returns current live members
- `getFailed(): Set<Member>` - Returns known failed members (shunned)
- `getContext(): Context<Member>` - Returns underlying Fireflies context
- `allMembers(): Stream<Member>` - Returns all members (live + failed)
- `isStable(): boolean` - Returns true if no rebuttal timers active
- `totalMembers(): int` - Returns count of all known members

**Properties**:
- **Immutable**: No mutations after creation
- **Self-verifying**: Identity bound to membership via HexBloom
- **Cryptographically authenticated**: Rehashed crown prevents tampering
- **Thread-safe**: Used across gossip, voting, and failure detection concurrently

**Integration**:
- Created by ViewManagement consensus on membership changes
- Used by Binding join protocol for bloom filter verification
- Referenced by failure detection for member status

#### `Binding` (Join Protocol)
**Location**: `fireflies/src/main/java/com/hellblazer/delos/fireflies/Binding.java`

Two-phase Byzantine-fault-tolerant join protocol for new members.

**Key Methods**:
- `join(seed: Member): CompletableFuture<Gateway>` - Join via seed member
- `complete(gateway: Gateway): CompletableFuture<View>` - Complete join with gateway
- `cancel()` - Cancel pending join operation

**Phases**:
1. **Seed Contact**: Joining member contacts any known member
2. **BFT Redirect**: Seed redirects to BFT members of current view
3. **Quorum Agreement**: Joining member acquires 2/3+1 agreement on view identity
4. **State Transfer**: Remaining membership discovered via gossip (not returned by join)

**Return Values**:
- `Gateway`: Contains view identity hash, HexBloom bloom filter, redirect member list
- Joining member validates bloom filter against subsequently discovered members

**Guarantees**:
- Byzantine-fault-tolerant: Cannot join without 2/3+1 member agreement
- Non-blocking: State transfer occurs asynchronously via gossip
- Scalable: Network load distributed across membership

#### `ViewManagement` (Consensus on Changes)
**Location**: `fireflies/src/main/java/com/hellblazer/delos/fireflies/ViewManagement.java`

Manages consensus on membership view changes (joins/leaves/failures).

**Key Methods**:
- `createVote(joins: Set<Member>, leaves: Set<Member>): long` - Create view change proposal
- `vote(voterIndex: int, joinSize: int, leaveSize: int)` - Record vote from BFT member
- `allMembers(): Set<Member>` - Get all members in current view
- `addAccusation(member: Member, accusedId: Digest)` - Record member accusation
- `rebut(member: Member)` - Record rebuttal from accused member

**Voting Rules**:
- Only BFT subset (computed via `Context.bftSubset(viewId)`) can vote
- Requires f < n/3 quorum (Byzantine threshold)
- Vote only when view is stable (no active rebuttal timers)
- All members count votes; membership changes deterministically

**State Machine**:
- **Pending**: Joins/leaves awaiting vote
- **Voting**: Vote in progress from BFT subset
- **Complete**: Consensus reached; new view accepted

#### `PhiAccrualFailureDetector` (Liveness Detection)
**Location**: `fireflies/src/main/java/com/hellblazer/delos/fireflies/PhiAccrualFailureDetector.java`

Adaptive failure detection based on gossip success rate.

**Key Methods**:
- `isAlive(member: Member): boolean` - Check if member is live
- `sample(member: Member)` - Record gossip success/failure
- `phi(member: Member): double` - Get suspicion level (0-1)

**Properties**:
- **Integrated with gossip**: Uses gossip success/failure for liveness
- **Adaptive**: Adjusts sensitivity based on historical patterns
- **No separate ping**: Eliminates redundant monitoring protocol

**Usage**:
- Internally used by gossip coordinator
- Suspicion threshold can be tuned via Phi parameter
- Failed members undergo accusation/rebuttal process

#### `Gossip Protocol` (State Reconciliation)
**Location**: `fireflies/src/main/java/com/hellblazer/delos/fireflies/comm/gossip/`

Ring-structured, gossip-optimized state reconciliation.

**Key Classes**:
- `GossipCoordinator`: Drives periodic gossip rounds
- `Gossip`: Single gossip exchange between partners
- `Bloom filters`: Efficient state diffs via probabilistic data structures

**Gossip Rounds**:
- Members arranged in BFT-ordered ring
- Gossip partners selected via reservoir sampling
- Ring structure ensures Byzantine members isolated
- Bloom filter diffs minimize bandwidth

**Membership Discovery**:
- Joining members discover full membership via gossip
- Load distributed across all members
- Asynchronous and fault-tolerant
- No bottleneck on particular members

### Supporting Interfaces

#### `Context<Member>` (Membership Ring)
**Location**: `memberships/src/main/java/com/hellblazer/delos/context/Context.java`

Abstract membership context providing ring structure and BFT subset.

**Key Methods**:
- `getId(): Digest` - Context identifier
- `bftSubset(hash: Digest): Set<Member>` - Get BFT-ordered successors
- `getProbabilityByzantine(): double` - Byzantine failure probability (for f = n/3)
- `Ring structure**: Provides ordered member access for BFT properties
- `getMembers(): Set<Member>` - All members in context

**Integration with Fireflies**:
- Underlying context for all rings
- BFT subset selection for voting members
- Member lookup via digest

## Usage Examples

### 1. Bootstrap Node and Discover Membership

```java
// Create local identity via Stereotomy
ControlledIdentifier id = stereotomy.newIdentifier(params);

// Create local context for membership ring
Context<Member> context = new StaticContext<>(
    id.getIdentifier(),
    0.25,  // probability Byzantine (f < n/3)
    3,     // witness threshold
    Collections.emptySet(),  // initial empty membership
    0.1    // epsilon for gossip
);

// Create Fireflies instance
Fireflies fireflies = new Fireflies(
    params,
    id,
    context,
    router,  // GRPC router for communications
    metrics
);

// Start membership service
fireflies.start();

// Discover current membership from context updates
fireflies.currentView().thenAccept(view -> {
    System.out.println("Current view ID: " + view.getId());
    System.out.println("Members: " + view.getMembers().size());
});
```

### 2. Join Existing Group

```java
// Contact seed member (via bootstrap or DNS)
Member seed = contactSeed("192.168.1.10:8443");

// Bind to existing group
Binding binding = fireflies.join(seed);

// Get gateway with view ID and bloom filter
Gateway gateway = binding.join(seed).get();

// Validate bloom filter
boolean valid = validateMembership(gateway.getCrown(), gateway.getBloomFilter());

// Complete join - membership discovered asynchronously via gossip
View joinedView = binding.complete(gateway).get();
System.out.println("Joined view: " + joinedView.getId());
```

### 3. Monitor Member Failures and View Changes

```java
// Subscribe to membership changes
fireflies.membership().changes().subscribe(newView -> {
    System.out.println("View changed to: " + newView.getId());
    System.out.println("Stable: " + newView.isStable());
    System.out.println("Member count: " + newView.getMembers().size());
});

// Check individual member liveness
Member member = context.getMember(digest);
fireflies.isLive(member).thenAccept(alive -> {
    if (!alive) {
        System.out.println("Member " + digest + " is dead");
    }
});

// Access failed/shunned members
fireflies.currentView().thenAccept(view -> {
    Set<Member> failed = view.getFailed();
    System.out.println("Failed members: " + failed.size());
});
```

### 4. Participate in View Consensus

```java
// Fireflies automatically participates if member is BFT observer
// BFT subset is computed: Context.bftSubset(currentViewId)
// Voting occurs when:
// 1. New members join (joins pending)
// 2. Members fail and are accused (leaves pending)
// 3. View is stable (no active rebuttal timers)

// Monitor voting state
fireflies.viewManagement().votes().subscribe(vote -> {
    System.out.println("Participated in vote: height=" + vote.height());
});

// View changes are atomic across all members
fireflies.membership().changes()
    .filter(view -> view.getId().equals(expectedViewId))
    .findFirst()
    .thenRun(() -> System.out.println("View change consensus reached"));
```

### 5. Handle Accusations and Rebuttals

```java
// Accusations are generated by monitors when liveness fails
// Monitored members can rebut by demonstrating liveness
// Fireflies automatically rebuts gossip failures

// Shunning: Failed members that cannot rebut are excluded
// Must use Join protocol to rejoin after shunning

// Monitor accusation/rebuttal activity
fireflies.accusations().subscribe(accusation -> {
    System.out.println("Accused: " + accusation.getAccused() +
                      " by: " + accusation.getAccuser());
});

fireflies.rebuttals().subscribe(rebuttal -> {
    System.out.println("Rebutted: " + rebuttal.getAccused());
});
```

### 6. Broadcast Messages (via Ethereal)

```java
// Fireflies provides secure overlay; Ethereal provides consensus broadcast
BoundedEpidemicGossip broadcaster = ethereal.getBroadcaster();

Message msg = buildMessage(payload);
broadcaster.publish(msg).thenAccept(result -> {
    System.out.println("Message " + result.height() + " globally ordered");
});

// All nodes process messages in same order
broadcaster.subscribe(message -> {
    processMessage(message);
});
```

## Performance Characteristics

### Gossip Overhead
- **Bounded per member**: `O(fanout × gossip_interval)`
- **Default**: fanout=4, interval=500ms → ~2-4KB/sec per member
- **Tuning**: Adjust fanout and interval for network conditions

### View Stability
- **Convergence time**: Typically 1-2 gossip rounds (~1-2 seconds)
- **Stability**: Once stable, remains stable unless failures occur
- **Rebuttal window**: ~5-30 seconds (configurable)

### Join Latency
- **Two-phase**: ~2 gossip rounds + membership discovery
- **Typical**: 2-5 seconds depending on network
- **Bottleneck**: Member state transfer via gossip (parallel load)

### Failure Detection
- **Time to detect**: Adaptive phi-accrual, typically 5-30 seconds
- **False positives**: Tunable; default <1% in stable conditions
- **Network partitions**: Detected as Byzantine failures

### Scalability
- **BFT voting subset**: O(log n) to O(n^1/3) members
- **Gossip ring**: O(n) but each member gossips with O(fanout) peers
- **Memory**: O(n) for membership, O(log n) for voting subset
- **Tested**: 100+ member groups, 1000+ member groups

## Security and Threat Model

**See**: [ADR-0003: BFT Membership Architecture](../docs/adr/0003-bft-membership-architecture.md) for comprehensive threat model and security guarantees.

**Quick Reference**:
- **Byzantine Tolerance**: f < n/3 malicious members
- **Membership Agreement**: Synchronized across all honest members
- **Identity Binding**: KERI-based identifiers prevent spoofing
- **Message Authentication**: Digital signatures on all state
- **Network Isolation**: Partitioned groups detected via view ID mismatch

## Testing and Validation

**See Also**: [docs/TESTING_GUIDE.md](../docs/TESTING_GUIDE.md) - Comprehensive testing patterns, Byzantine fault injection, deterministic testing, and best practices.

**Test Suite Location**: `fireflies/src/test/java/com/hellblazer/delos/fireflies/`

**Test Coverage** (109+ tests):
- **Functionality**: Joins, leaves, votes, gossip, membership discovery
- **Byzantine Behavior**: Malicious members, message forgery, coordinated attacks
- **Network Partitions**: Split groups, partition healing, Byzantine isolation
- **Race Conditions**: Concurrent joins, leaves, view changes
- **Resource Exhaustion**: Memory limits, connection handling, garbage collection
- **Recovery**: Failure recovery, view reconciliation, shunning/rejoin
- **Determinism**: Seeded randomness, time control, reproducible behavior

**Running Tests**:
```bash
# All fireflies tests (fast mode)
./mvnw test -pl fireflies

# Specific test class
./mvnw test -pl fireflies -Dtest=ByzantineScenarioTest

# Large-scale tests (thorough mode)
./mvnw test -pl fireflies -Dlarge_tests=true

# All cluster tests with Byzantine members
./mvnw test -pl fireflies -Dtest="*ByzantineTest"
```

**Canary Tests** (Integration Health):
- `ChurnTest`: Continuous joins/leaves with concurrent failures
- `SwarmTest`: Large group (100+) membership stabilization
- `E2ETest`: End-to-end protocol from bootstrap through consensus

**Test Infrastructure**:
- **GorgoneionBftTestHelpers**: Fault injection framework for Byzantine behavior
- **TestContext**: Cluster formation and lifecycle management
- **SeededSecureRandom**: Deterministic randomness for reproducible tests
- **Clock.fixed()**: Time control for timing-dependent operations

## Metrics

Fireflies exposes operational metrics via Dropwizard Metrics (Codahale Metrics), available at the metrics endpoint.

### Core Implementation

Actual metrics are defined in `FireflyMetrics` interface and tracked via Meters (counters), Timers, and Histograms:

**Source**: `fireflies/src/main/java/com/hellblazer/delos/fireflies/FireflyMetrics.java`

### Membership Events (Meter - tracks counts)

| Metric | Description | Healthy |
|--------|-------------|---------|
| `joins()` | Total join operations | Increases with member additions |
| `leaves()` | Total leave operations | Increases with member removals |
| `viewChanges()` | Total view change events | 0-1 per hour (stable) |
| `accusations()` | Total accusations (failure detections) | < 1 per 5 minutes |
| `shunnedGossip()` | Gossip messages from shunned members | ~0 (indicates Byzantine behavior if > 0) |

### Message Activity (Histogram - tracks counts/sizes)

| Metric | Description | Purpose |
|--------|-------------|---------|
| `inboundGossip()`, `outboundGossip()` | Gossip message sizes | Network bandwidth analysis |
| `inboundJoin()`, `outboundJoin()` | Join operation sizes | Protocol efficiency |
| `inboundUpdate()`, `outboundUpdate()` | Update message sizes | View update efficiency |
| `inboundRedirect()`, `outboundRedirect()` | Redirect message sizes | Join path analysis |
| `inboundSeed()`, `outboundSeed()` | Seed message sizes | Bootstrap efficiency |
| `inboundGateway()`, `outboundGateway()` | Gateway message sizes | External communication |

### Timing Metrics (Timer - tracks latency)

| Metric | Description | Healthy Range |
|--------|-------------|---|
| `inboundGossipDuration()` | Inbound gossip processing time | p95 < 100ms |
| `inboundJoinDuration()` | Inbound join processing time | p95 < 500ms |
| `inboundSeedDuration()` | Inbound seed processing time | p95 < 100ms |
| `inboundUpdateTimer()` | Inbound update processing time | p95 < 100ms |
| `joinDuration()` | Total join operation time | p95 < 5s |
| `seedDuration()` | Total seed operation time | p95 < 500ms |
| `outboundUpdateTimer()` | Outbound update processing time | p95 < 50ms |

### Response Metrics (Histogram - tracks payload sizes)

| Metric | Description |
|--------|-------------|
| `gossipReply()`, `gossipResponse()` | Gossip response payload sizes |
| `notes()` | Notes exchanged |
| `filteredNotes()` | Filtered notes (after filtering invalid) |

### Performance Characteristics

**Gossip Protocol:**
- Expected message latency (p95): < 100-500ms depending on network
- Join protocol: 500ms - 5 seconds depending on network latency
- View change: < 1 second if network is healthy

**Monitoring:**
- Access metrics via `/metrics` HTTP endpoint
- Integrate with Prometheus for time-series analysis
- Set alerts on Meter rates exceeding expected churn rates
- Monitor Timer percentiles (p95, p99) for latency degradation

### Notes on Metric Availability

**Status**: Metrics interfaces defined and integrated; Prometheus export layer available via metrics endpoint

**Limitations**:
- No pre-aggregated "view_size", "members_stable_count" gauges (derive from membership context)
- Metric names follow Dropwizard conventions (method names), not Prometheus conventions
- Requires integration with Prometheus metrics exporter for dashboard display

### Related Guides

- Monitoring Guide: [../docs/MONITORING_GUIDE.md](../docs/MONITORING_GUIDE.md)
- Troubleshooting Guide: [../docs/TROUBLESHOOTING_GUIDE.md](../docs/TROUBLESHOOTING_GUIDE.md)
- ADR-0003: [../docs/adr/0003-bft-membership-architecture.md](../docs/adr/0003-bft-membership-architecture.md)

## References

- **Design Papers**:
  - Fireflies: https://ymsir.com/papers/fireflies-tocs.pdf
  - Rapid: https://www.usenix.org/system/files/conference/atc18/atc18-suresh.pdf
  - DHR: https://www.cs.huji.ac.il/~dolev/pubs/opodis07-DHR-fulltext.pdf
  - HexBloom: https://eprint.iacr.org/2021/773.pdf

- **Related ADRs**:
  - ADR-0003: BFT Membership Architecture
  - ADR-0002: KERI Implementation (Stereotomy)
  - ADR-0003: BFT Membership

- **Source Code**:
  - Main: `fireflies/src/main/java/com/hellblazer/delos/fireflies/`
  - Tests: `fireflies/src/test/java/com/hellblazer/delos/fireflies/`
  - Protocol Buffers: `grpc/src/main/proto/fireflies.proto`

- **Integration Points**:
  - Stereotomy: Identity and key management
  - Ethereal: Consensus layer (built on Fireflies)
  - Choam: State machine replication
  - Thoth: Distributed hash table for member discovery
