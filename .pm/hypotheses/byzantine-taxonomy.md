# Byzantine Behavior Taxonomy for Delos/Fireflies

**Created**: 2026-01-01
**Bead**: Delos-xgy (P0: Document Byzantine behavior taxonomy)
**Purpose**: Define what constitutes Byzantine behavior for Phase 0 testing scenarios
**References**: substantive-critique Testing Gap #2, phase-0-immediate.md

---

## Executive Summary

This document provides a comprehensive taxonomy of Byzantine behaviors relevant to the Delos/Fireflies system. Each category defines specific attack vectors, simulation strategies for testing, and mappings to Phase 0 beads. This taxonomy addresses the substantive-critic's identification of "Testing Gap #2 - no Byzantine definition" by providing explicit, testable definitions of Byzantine behavior.

**Key Principle**: A Byzantine node may exhibit any behavior that deviates from the protocol specification, including but not limited to: sending invalid messages, withholding messages, sending conflicting messages to different nodes, or coordinating with other Byzantine nodes.

**BFT Assumption**: The system tolerates up to `t` Byzantine nodes where committee size is `3t+1`. The tolerance level is calculated as `(ringCount - 1) / bias`.

---

## 1. Signature Attacks

### 1.1 Overview
Attacks that exploit signature validation weaknesses in cryptographic message authentication.

### 1.2 Attack Vectors

| Attack | Description | Affected Messages | Severity |
|--------|-------------|-------------------|----------|
| Invalid Signature | Message signed with wrong/invalid private key | All signed messages | CRITICAL |
| Forged Signature | Non-member attempts to sign as member | SignedNote, SignedAccusation, SignedViewChange | CRITICAL |
| Expired Signature | Signature from old epoch presented as current | SignedNote (epoch field) | HIGH |
| Wrong View Signature | Signature valid but for different view | All signed messages with currentView | HIGH |
| Missing Signature | Message with empty/null signature field | All signed messages | HIGH |
| Truncated Signature | Malformed signature bytes | crypto.Sig fields | MEDIUM |
| Signature Reuse | Using same signature on different message content | All signed messages | CRITICAL |

### 1.3 Affected Protocol Messages

```protobuf
// Fireflies - membership messages
SignedNote { Note note; crypto.Sig signature; }
SignedAccusation { Accusation accusation; crypto.Sig signature; }
SignedViewChange { ViewChange change; crypto.Sig signature; }

// Ethereal - consensus messages
PreUnit_s { ...; crypto.Sig signature; }
SignedPreVote { crypto.Sig signature; PreVote vote; }
SignedCommit { crypto.Sig signature; Commit commit; }
EpochProof { Proof msg; int32 owner; crypto.Sig signature; }
```

### 1.4 Test Simulation Strategy

```java
// Example test structure for signature attacks
@Test
void testInvalidSignature_rejected() {
    // Create message with wrong key
    var wrongKey = generateTestKeyPair();
    var note = createValidNote(legitimateMember);
    var signedNote = sign(note, wrongKey.getPrivate()); // Wrong key

    // Submit to view
    var result = view.processNote(signedNote);

    // Verify rejection
    assertThat(result).isEqualTo(ValidationResult.INVALID_SIGNATURE);
    assertThat(view.hasMember(note.getIdentifier())).isFalse();
}

@Test
void testExpiredEpochSignature_rejected() {
    var oldNote = createNoteWithEpoch(member, currentEpoch - 5);
    var signedNote = sign(oldNote, member.getPrivateKey());

    var result = view.processNote(signedNote);

    assertThat(result).isEqualTo(ValidationResult.STALE_EPOCH);
}
```

### 1.5 Phase 0 Bead Mapping

| Bead | Signature Attack Coverage |
|------|---------------------------|
| **Delos-sht** (Issue 1) | All signature attacks - PRIMARY |
| **fireflies-p0-006** (Issue 6) | Bootstrap identity signatures |

### 1.6 Acceptance Criteria

- [ ] All invalid signatures rejected (100% of test cases)
- [ ] Rejection occurs before any state modification
- [ ] Error codes distinguish signature failure types
- [ ] Gossip rejects invalid member signatures
- [ ] Consensus rejects invalid PreUnit signatures
- [ ] Cryptography expert review completed

---

## 2. Message Attacks

### 2.1 Overview
Attacks that manipulate message content, structure, or delivery to violate protocol invariants.

### 2.2 Attack Vectors

| Attack | Description | Impact | Severity |
|--------|-------------|--------|----------|
| Replay Attack | Resend valid message from previous round/epoch | State duplication, resource exhaustion | CRITICAL |
| Out-of-Order Messages | PreUnits with incorrect DAG parent references | Consensus ordering violation | CRITICAL |
| Malformed Messages | Invalid protobuf structure, missing required fields | Parser crashes, undefined behavior | HIGH |
| Empty Payload | Valid signature over empty/null content | Bypass validation logic | HIGH |
| Field Tampering | Modify unsigned fields after signing | Inconsistent state | MEDIUM |
| Message Amplification | Single message causes disproportionate processing | DoS, resource exhaustion | HIGH |

### 2.3 Specific Replay Scenarios

1. **Note Replay**: Replay old SignedNote to resurrect shunned member
2. **Accusation Replay**: Replay old SignedAccusation to trigger false shunning
3. **ViewChange Replay**: Replay old SignedViewChange to disrupt view consensus
4. **PreUnit Replay**: Replay old consensus units to cause DAG confusion
5. **Join Replay**: Replay old Join message to bypass current validation

### 2.4 Test Simulation Strategy

```java
@Test
void testReplayAttack_duplicateRejected() {
    // Capture valid message
    var validNote = captureValidSignedNote();

    // First submission succeeds
    var result1 = view.processNote(validNote);
    assertThat(result1).isEqualTo(ValidationResult.ACCEPTED);

    // Replay same message - should be rejected
    var result2 = view.processNote(validNote);
    assertThat(result2).isEqualTo(ValidationResult.DUPLICATE);
}

@Test
void testMalformedMessage_gracefulRejection() {
    var malformed = createMalformedProtobuf();

    // Should not throw exception
    assertDoesNotThrow(() -> view.processMessage(malformed));

    // State should be unchanged
    assertThat(view.getMemberCount()).isEqualTo(initialCount);
}
```

### 2.5 Phase 0 Bead Mapping

| Bead | Message Attack Coverage |
|------|-------------------------|
| **Delos-sht** (Issue 1) | Replay detection, message validation |
| **fireflies-p0-006** (Issue 6) | Join message validation |
| **fireflies-p0-008** (Issue 8) | Message amplification limits |

### 2.6 Acceptance Criteria

- [ ] Duplicate messages detected and rejected
- [ ] No state changes from malformed messages
- [ ] Parser errors don't cause crashes
- [ ] Message age tracking for TTL enforcement
- [ ] Epoch validation prevents old message acceptance

---

## 3. Timing Attacks

### 3.1 Overview
Attacks that exploit message timing, delays, and timeouts to disrupt protocol operation.

### 3.2 Attack Vectors

| Attack | Description | Target | Severity |
|--------|-------------|--------|----------|
| Delayed Delivery | Hold messages past critical timeouts | Rebuttal timeout, view change | HIGH |
| Early Messages | Send messages for future epochs | Epoch synchronization | MEDIUM |
| Message Withholding | Not forwarding gossip to other nodes | Gossip convergence | HIGH |
| Selective Delivery | Send different states to different nodes | View consistency | CRITICAL |
| Timeout Exploitation | Trigger timeout edge cases | Election, recovery | HIGH |
| Heartbeat Manipulation | Fake liveness while actually offline | Phi accrual detector | MEDIUM |

### 3.3 Critical Timing Parameters

From `Parameters.java`:
- `rebuttalTimeout`: Number of TTL rounds accused member has to rebut (default: 2)
- `viewChangeRounds`: Rounds for view change consensus
- `joinRetries`: Maximum join attempt retries

### 3.4 Test Simulation Strategy

```java
@Test
void testDelayedRebuttal_memberShunned() {
    // Accuse member
    view.accuse(member, ring, new TestException());

    // Advance time past rebuttal timeout
    advanceRounds(params.rebuttalTimeout() + 1);

    // Member should be shunned
    assertThat(view.isShunned(member.getId())).isTrue();
}

@Test
void testFutureEpochMessage_rejected() {
    var futureNote = createNoteWithEpoch(member, currentEpoch + 100);

    var result = view.processNote(futureNote);

    assertThat(result).isEqualTo(ValidationResult.FUTURE_EPOCH);
}

@Test
void testSelectiveDelivery_viewDivergenceDetected() {
    // Byzantine node sends conflicting view changes
    byzantineNode.sendToSubset(viewChangeA, subset1);
    byzantineNode.sendToSubset(viewChangeB, subset2);

    // System should detect and handle equivocation
    awaitViewConsensus();
    assertThat(getViewConsistencyScore()).isGreaterThan(0.99);
}
```

### 3.5 Phase 0 Bead Mapping

| Bead | Timing Attack Coverage |
|------|------------------------|
| **fireflies-p0-004** (Issue 4) | Recovery timing, zombie detection |
| **fireflies-p0-005** (Issue 5) | Election timeout, thrashing prevention |
| **fireflies-p0-008** (Issue 8) | Rate limiting timing bounds |

### 3.6 Acceptance Criteria

- [ ] Rebuttal timeout correctly triggers shunning
- [ ] Future epoch messages rejected
- [ ] Message withholding doesn't block honest nodes
- [ ] Election completes within bounded time
- [ ] No timing-based DoS vulnerabilities

---

## 4. Identity Attacks

### 4.1 Overview
Attacks that exploit identity management and membership to inject unauthorized participants.

### 4.2 Attack Vectors

| Attack | Description | Entry Point | Severity |
|--------|-------------|-------------|----------|
| Sybil Attack | Create multiple fake member identities | Join protocol | CRITICAL |
| Identity Impersonation | Claim existing member's identifier | SignedNote identifier | CRITICAL |
| Key Compromise | Use stolen/leaked private keys | Any signed message | CRITICAL |
| Join Flooding | Overwhelm join protocol with requests | Entrance.seed/join | HIGH |
| Bootstrap Injection | Insert Byzantine nodes during startup | Genesis formation | CRITICAL |
| View Crown Manipulation | Falsify HexBloom diadem | View identity | CRITICAL |

### 4.3 Identity Verification Points

1. **Registration (Entrance.seed)**: Initial contact with network
2. **Join (Entrance.join)**: BFT majority agreement on membership
3. **Gossip (Fireflies.gossip)**: Ongoing identity verification
4. **View Change**: Identity in new view validation

### 4.4 Test Simulation Strategy

```java
@Test
void testSybilAttack_fakeMemberRejected() {
    // Create valid-looking identity not in view
    var fakeIdentity = generateFakeIdentity();
    var fakeNote = createSignedNote(fakeIdentity);

    // Attempt to inject via gossip
    var result = view.processNote(fakeNote);

    // Should be rejected - not in membership
    assertThat(result).isEqualTo(ValidationResult.UNKNOWN_MEMBER);
}

@Test
void testIdentityImpersonation_detected() {
    var legitimateMember = view.getMembers().iterator().next();

    // Create note claiming legitimate member's ID with different key
    var impersonationNote = createNoteWithId(
        legitimateMember.getId(),
        attackerKey
    );

    var result = view.processNote(impersonationNote);

    assertThat(result).isEqualTo(ValidationResult.IMPERSONATION_DETECTED);
}

@Test
void testJoinFlooding_rateLimited() {
    var joinCount = 0;
    var rejected = 0;

    // Flood join requests
    for (int i = 0; i < 1000; i++) {
        var result = entrance.join(createJoinRequest());
        if (result.isRejected()) rejected++;
        else joinCount++;
    }

    // Most should be rejected
    assertThat(rejected).isGreaterThan(900);
}
```

### 4.5 Phase 0 Bead Mapping

| Bead | Identity Attack Coverage |
|------|--------------------------|
| **fireflies-p0-002** (Issue 2) | Atomic membership updates prevent injection |
| **fireflies-p0-006** (Issue 6) | Bootstrap identity validation |
| **Delos-sht** (Issue 1) | Identity verification via signatures |

### 4.6 Acceptance Criteria

- [ ] Unknown members rejected at all entry points
- [ ] Impersonation attempts detected and logged
- [ ] Join protocol enforces BFT majority agreement
- [ ] Bootstrap validates initial member set
- [ ] No Sybil attacks possible through any protocol path

---

## 5. Coordinated Attacks

### 5.1 Overview
Attacks requiring coordination between multiple Byzantine nodes to overcome BFT thresholds or amplify impact.

### 5.2 Attack Vectors

| Attack | Description | Nodes Required | Severity |
|--------|-------------|----------------|----------|
| Threshold Attack | t+1 Byzantine nodes exceed tolerance | t+1 | CRITICAL |
| Equivocation | Conflicting messages to different nodes | 1+ | CRITICAL |
| Accusation Flooding | Coordinated false accusations | 2+ | HIGH |
| View Change Manipulation | Force premature/delayed view changes | t | HIGH |
| Gossip Poisoning | Coordinated false Bloom filter state | 2+ | HIGH |
| Split-Brain Induction | Force network partition behavior | t | CRITICAL |

### 5.3 Equivocation Patterns

```
Byzantine Node B sends:
  - SignedViewChange(joins=[A]) to Node 1
  - SignedViewChange(joins=[C]) to Node 2
  - SignedViewChange(leaves=[D]) to Node 3

Result: Honest nodes receive conflicting information about the same view change.
Detection: Cross-referencing signed messages from same source.
```

### 5.4 Coordinated Rate Limit Bypass

```
Attack Scenario:
- Rate limit: 100 messages/second per node
- Byzantine nodes: 10 (each under individual limit)
- Each sends 99 msg/sec = 990 total msg/sec
- Combined load exceeds system capacity

Mitigation: Global rate limiting, not just per-node
```

### 5.5 Test Simulation Strategy

```java
@Test
void testEquivocation_detected() {
    var byzantineNode = createByzantineNode();

    // Send conflicting view changes
    var viewChange1 = createViewChange(joins(memberA));
    var viewChange2 = createViewChange(joins(memberB));

    byzantineNode.sendTo(node1, viewChange1);
    byzantineNode.sendTo(node2, viewChange2);

    // Cross-reference should detect equivocation
    awaitGossipRound();

    // Byzantine node should be accused
    assertThat(node1.hasAccusationAgainst(byzantineNode)).isTrue();
    assertThat(node2.hasAccusationAgainst(byzantineNode)).isTrue();
}

@Test
void testCoordinatedAccusationFlood_noFalseShunning() {
    var byzantineNodes = createByzantineNodes(t - 1);
    var honestTarget = pickRandomHonestNode();

    // Coordinated false accusations
    for (var byzantine : byzantineNodes) {
        byzantine.accuse(honestTarget, ring);
    }

    // Honest nodes should not accept < t accusations
    awaitRebuttalTimeout();

    assertThat(honestTarget.isShunned()).isFalse();
}

@Test
void testGlobalRateLimit_enforced() {
    var byzantineNodes = createByzantineNodes(10);

    // Each sends just under per-node limit
    var messagesPerNode = perNodeLimit - 1;

    for (var byzantine : byzantineNodes) {
        sendMessages(byzantine, messagesPerNode);
    }

    // Global limit should kick in
    assertThat(getSystemMessageRate()).isLessThan(globalLimit);
}
```

### 5.6 Phase 0 Bead Mapping

| Bead | Coordinated Attack Coverage |
|------|------------------------------|
| **fireflies-p0-005** (Issue 5) | Multi-leader prevention, consensus bug |
| **fireflies-p0-008** (Issue 8) | Global rate limiting |
| **fireflies-p0-004** (Issue 4) | Byzantine failure during recovery |

### 5.7 Acceptance Criteria

- [ ] Equivocation detection implemented
- [ ] t Byzantine nodes cannot cause honest node shunning
- [ ] Global rate limits prevent coordinated flooding
- [ ] Split-brain recovery implemented
- [ ] View change requires BFT majority agreement

---

## 6. Protocol Attacks

### 6.1 Overview
Attacks that exploit protocol-level vulnerabilities in join, gossip, and view change mechanisms.

### 6.2 Attack Vectors

| Attack | Description | Protocol Phase | Severity |
|--------|-------------|----------------|----------|
| Join Protocol Abuse | Exploit two-phase join handshake | Entrance.seed/join | HIGH |
| View Change Manipulation | Disrupt view transition | ViewChange voting | CRITICAL |
| Gossip Poisoning | Inject false Bloom filter state | Fireflies.gossip | HIGH |
| Ring State Desync | Cause ring position divergence | Ring communication | CRITICAL |
| Connection State Exploit | Use stale GRPC connections | MemberConnection | MEDIUM |
| Observation Flooding | Overwhelm with SignedViewChange | ViewManagement | HIGH |

### 6.3 Join Protocol Vulnerabilities

```
Two-Phase Join:
1. Registration (Entrance.seed) -> Redirect to successors
2. Join (Entrance.join) -> BFT majority agreement

Attack Points:
- Fake redirects in phase 1
- Bypass majority check in phase 2
- Resource exhaustion during join
- Replay old Gateway responses
```

### 6.4 Gossip Protocol Vulnerabilities

```
Three-Phase Gossip:
1. Send Bloom filter of state
2. Receive missing elements + partner's Bloom filter
3. Send partner's missing elements

Attack Points:
- False positive injection via Bloom filter manipulation
- Withholding elements in phase 2/3
- Sending conflicting state to different partners
- TTL manipulation to prevent message delivery
```

### 6.5 Test Simulation Strategy

```java
@Test
void testJoinProtocolAbuse_invalidRedirectRejected() {
    // Send fake redirect to non-successor
    var fakeRedirect = createRedirect(nonSuccessorNodes);

    joiningMember.receiveRedirect(fakeRedirect);

    // Should validate successors before proceeding
    assertThat(joiningMember.didAcceptRedirect()).isFalse();
}

@Test
void testGossipPoisoning_falseStateRejected() {
    // Create Bloom filter claiming false membership
    var poisonedBloomFilter = createPoisonedBff(fakeMember);

    var gossipRequest = SayWhat.newBuilder()
        .setGossip(Digests.newBuilder()
            .setIdentityBff(poisonedBloomFilter))
        .build();

    var response = view.gossip(gossipRequest);

    // Should not add fake member
    assertThat(view.hasMember(fakeMember)).isFalse();
}

@Test
void testRingStateDesync_detected() {
    // Simulate ring position divergence
    induceRingDesync(node1, node2);

    // Gossip should detect inconsistency
    awaitGossipRound();

    // Should trigger ring reconciliation
    assertThat(getRingConsistencyScore()).isGreaterThan(0.95);
}

@Test
void testStaleConnection_cleaned() {
    var member = view.getMembers().iterator().next();

    // Simulate member departure
    view.remove(member);

    // Connection should be cleaned
    assertThat(connectionManager.hasConnectionTo(member)).isFalse();

    // Messages to stale connection should fail gracefully
    var result = sendMessageTo(member);
    assertThat(result).isEqualTo(SendResult.CONNECTION_CLOSED);
}
```

### 6.6 Phase 0 Bead Mapping

| Bead | Protocol Attack Coverage |
|------|--------------------------|
| **fireflies-p0-002** (Issue 2) | Atomic protocol state updates |
| **fireflies-p0-003** (Issue 3) | Ring state consistency |
| **fireflies-p0-004** (Issue 4) | Recovery protocol robustness |
| **fireflies-p0-006** (Issue 6) | Bootstrap protocol validation |
| **fireflies-p0-007** (Issue 7) | Connection state management |

### 6.7 Acceptance Criteria

- [ ] Join protocol validates all redirects
- [ ] Gossip rejects poisoned Bloom filters
- [ ] Ring state converges after perturbation
- [ ] Stale connections cleaned on member departure
- [ ] View change requires proper observation quorum

---

## Attack-to-Test Mapping Matrix

| Attack Category | Test Class | Phase 0 Bead(s) | Priority |
|-----------------|------------|-----------------|----------|
| **Signature Attacks** | EtherealConsensusSignatureTest | Delos-sht, fireflies-p0-006 | P0 |
| **Message Attacks** | MessageValidationTest | Delos-sht, fireflies-p0-008 | P0 |
| **Timing Attacks** | TimingResilienceTest | fireflies-p0-004, fireflies-p0-005 | P0 |
| **Identity Attacks** | IdentityValidationTest | fireflies-p0-002, fireflies-p0-006 | P0 |
| **Coordinated Attacks** | CoordinatedByzantineTest | fireflies-p0-005, fireflies-p0-008 | P0 |
| **Protocol Attacks** | ProtocolRobustnessTest | fireflies-p0-003, fireflies-p0-004, fireflies-p0-007 | P0 |

---

## Phase 0 Bead Coverage Summary

| Bead ID | Issue | Byzantine Categories Covered |
|---------|-------|------------------------------|
| **Delos-sht** | Ethereal Signature Validation | 1 (Signature), 2 (Message) |
| **fireflies-p0-002** | Membership Atomicity | 4 (Identity), 6 (Protocol) |
| **fireflies-p0-003** | Ring State Consistency | 6 (Protocol) |
| **fireflies-p0-004** | Failure Recovery | 3 (Timing), 6 (Protocol) |
| **fireflies-p0-005** | Ring Election Consensus | 3 (Timing), 5 (Coordinated) |
| **fireflies-p0-006** | Bootstrap Validation | 1 (Signature), 4 (Identity) |
| **fireflies-p0-007** | GRPC Connection State | 6 (Protocol) |
| **fireflies-p0-008** | Rate Limiting | 2 (Message), 5 (Coordinated) |

---

## Test Harness Requirements

### 1. Byzantine Node Simulation Framework

```java
public interface ByzantineNodeSimulator {
    // Signature attacks
    void sendWithInvalidSignature(Message msg, Node target);
    void sendWithExpiredSignature(Message msg, Node target);

    // Message attacks
    void replayMessage(Message oldMsg, Node target);
    void sendMalformedMessage(Node target);

    // Timing attacks
    void delayMessageDelivery(Message msg, Duration delay);
    void withholdMessages(Set<Node> targets);

    // Identity attacks
    Digest createSybilIdentity();
    void impersonate(Member victim, Node target);

    // Coordinated attacks
    void equivocate(Message msg1, Node target1, Message msg2, Node target2);
    void coordinateWith(ByzantineNodeSimulator... others);

    // Protocol attacks
    void poisonBloomFilter(Bff filter);
    void desyncRingState();
}
```

### 2. Chaos Testing Support

```java
public interface ChaosTestHarness {
    // Random Byzantine behavior injection
    void injectRandomByzantineFailure(Duration window);

    // Network conditions
    void inducePartition(Set<Node> group1, Set<Node> group2);
    void healPartition();

    // Timing manipulation
    void randomizeMessageDelays(Duration min, Duration max);

    // Verify invariants
    void assertViewConsistency();
    void assertNoFalseShunning();
    void assertConsensusProgress();
}
```

### 3. Metrics and Observability

- Byzantine message rejection rate
- False positive accusation rate
- View convergence time under attack
- Recovery time after Byzantine failure
- Rate limit effectiveness under coordinated attack

---

## Conclusion

This taxonomy provides a comprehensive framework for testing Byzantine fault tolerance in Delos/Fireflies. Each Phase 0 bead should reference specific attack categories and implement tests that simulate the corresponding Byzantine behaviors.

**Critical Insight**: The substantive-critique correctly identified that Phase 0 tests might validate against wrong BFT assumptions. This taxonomy, combined with the pre-Phase 0 BFT assumption documentation (recommended by the critic), provides the foundation for correct Byzantine testing.

**Next Steps**:
1. Create Byzantine test harness (see Test Harness Requirements)
2. Implement tests for each attack category
3. Map test coverage to Phase 0 beads
4. Validate that t Byzantine nodes cannot violate safety properties

---

**Author**: deep-analyst
**Review Status**: Ready for architect review
**Cross-References**:
- ChromaDB: critique::fireflies::deep-analysis-2026-01-01
- ChromaDB: critique::architecture::delos-comprehensive-2025-12-31
- .pm/audits/substantive-critique.md (Testing Gap #2)
- .pm/phases/phase-0-immediate.md
