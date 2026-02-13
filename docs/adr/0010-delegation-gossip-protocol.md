# ADR-0010: Delegation Gossip Protocol for KERI Credential Distribution

**Status**: ACCEPTED

**Date**: 2026-02-13

**Context**

Delos uses KERI (Key Event Receipt Infrastructure) for decentralized identity management. When a ProcessContainerDomain spawns a subdomain, it delegates identity to the subdomain through KERI delegation events. These delegation credentials must be distributed to all members of the subdomain's context for verification.

**Problem**: How should KERI delegation credentials propagate across subdomain members?

Key requirements:
1. All members must receive delegation credentials
2. Distribution must be Byzantine fault tolerant
3. Credentials must propagate even if some members are offline
4. Must scale to hundreds of members per subdomain
5. Must converge despite network partitions
6. Must not overwhelm network with redundant traffic

**Decision**

**Use anti-entropy gossip protocol with Bloom filter reconciliation and reservoir sampling.**

Protocol structure:
```protobuf
message DelegationUpdate {
  Biff have = 1;                      // Bloom filter of sender's delegates
  repeated SignedDelegate update = 2; // Delegates receiver doesn't have
  int32 ring = 3;                     // Ring number for context routing
}
```

Gossip rounds:
1. **Initiator** sends `have` Bloom filter of local delegates
2. **Responder** compares against local delegates:
   - Filters delegates initiator doesn't have (not in Bloom filter)
   - Samples up to `maxTransfer` delegates (reservoir sampling)
   - Responds with `have` + `update` list
3. **Initiator** merges received delegates into local state
4. Repeat on successor rings with exponential backoff

Key parameters:
- `maxTransfer`: 100 delegates per gossip round (bounded bandwidth)
- `fpr`: 0.01 false positive rate for Bloom filters (1% redundancy)
- `gossipInterval`: 1 second (tunable based on network)

**Rationale**

**Why gossip (not broadcast)?**
- Broadcast requires all-to-all communication (O(n²) messages)
- Gossip achieves probabilistic reliability with O(n) messages
- Handles network partitions gracefully (eventual consistency)
- Well-studied in Byzantine contexts (Fireflies uses similar protocol)

**Why Bloom filters?**
- Compact representation of delegate set (~1 bit per delegate)
- Enables efficient set reconciliation without transferring full state
- False positives acceptable (redundant transfer cheaper than round-trips)
- Fireflies already uses Bloom filters (proven in production)

**Why reservoir sampling?**
- Bounds bandwidth per round (prevents network saturation)
- Provides uniform random sample (fair delegate distribution)
- Simple algorithm (O(n) single-pass)
- Unbiased selection (all delegates equally likely)

**Why anti-entropy (not rumor-mongering)?**
- Anti-entropy guarantees eventual convergence
- Rumor-mongering may leave stragglers uninformed
- Critical for security: all members must verify delegation chain
- Small overhead cost justified by reliability

**Alternatives Considered**

**A. Broadcast to all members**
- Rejected: O(n²) message complexity
- Network saturation with large member count
- No fault tolerance for offline members

**B. Hierarchical distribution tree**
- Rejected: Tree structure fragile to Byzantine failures
- Single point of failure at root
- Complex to maintain under churn
- Doesn't align with Fireflies peer model

**C. Publish-subscribe (e.g., NATS)**
- Rejected: Introduces external dependency
- Centralized broker conflicts with Byzantine design
- Fireflies already provides peer-to-peer substrate
- Simpler to reuse existing gossip infrastructure

**D. DHT-based storage (like Thoth)**
- Rejected: Delegation credentials are small (few KB)
- DHT overhead not justified for small datasets
- Members need full set locally (not just lookup)
- Adds complexity without benefit

**E. Full state exchange (no Bloom filters)**
- Rejected: Bandwidth waste for large delegate sets
- No early-exit when sets identical
- Bloom filter compression saves ~90% bandwidth

**Consequences**

**Positive:**
- ✅ Eventual consistency guaranteed (anti-entropy)
- ✅ Byzantine fault tolerant (survives malicious members)
- ✅ Scalable bandwidth (O(1) per round via sampling)
- ✅ Network partition tolerant (gossip reconnects)
- ✅ Reuses Fireflies infrastructure (no new dependencies)
- ✅ Simple implementation (~100 LOC)

**Negative:**
- ⚠️ Convergence time proportional to member count
- ⚠️ Redundant transfers due to Bloom filter false positives (~1%)
- ⚠️ No delivery order guarantees (may receive delegates out of sequence)
- ⚠️ No priority/urgency mechanism (all delegates equal)

**Trade-offs:**
- Bandwidth vs latency: Bounded transfer delays convergence
- Accuracy vs overhead: Higher FPR = fewer redundant transfers but larger Bloom filters
- Simplicity vs features: No QoS, prioritization, or ordering

**Mitigations:**
- Convergence time: Tunable gossip interval and maxTransfer
- False positives: Low FPR (0.01) minimizes waste
- Out-of-order delivery: Delegates have sequence numbers for ordering
- No priority: All delegates equally critical for verification

**Performance Characteristics:**

**Theoretical convergence time** (pessimistic):
```
T = log₂(N) × gossipInterval
N = 100 members, interval = 1s → ~7 seconds
N = 1000 members, interval = 1s → ~10 seconds
```

**Bandwidth per member per round:**
```
Bloom filter: ~1 KB (10K delegates @ 0.01 FPR)
Delegates: ~10 KB (100 delegates @ ~100 bytes each)
Total: ~11 KB per round, ~11 KB/s sustained
```

**Storage:**
```
H2 MVStore with delegates map
1000 delegates × 100 bytes = ~100 KB in memory
```

**Related Decisions:**
- ADR-0008: Subdomain isolation strategy (delegation identity model)
- Fireflies gossip protocol (same Bloom filter reconciliation pattern)

**Implementation Status:**
- ✅ Complete (Delos-4hby)
  - gossip() method: Bloom filter reconciliation
  - update() method: Delegate merging
  - Reservoir sampling for bounded transfer
  - Integration with SubDomain lifecycle

**Future Considerations:**
- Priority delegation propagation (for time-sensitive credentials)
- Compression for large delegate sets (gzip before transfer)
- Adaptive gossip intervals (faster convergence under load)
- Metrics for convergence time monitoring
