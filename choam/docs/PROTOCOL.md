# CHOAM Protocol Specification

Formal specification of the CHOAM consensus protocol, view reconfiguration, and Byzantine fault tolerance properties.

**Version**: 0.0.6-SNAPSHOT
**Last Updated**: 2026-02-08
**Status**: Draft

---

## Table of Contents

- [Overview](#overview)
- [Consensus Protocol](#consensus-protocol)
- [View Reconfiguration](#view-reconfiguration)
- [Checkpoint Protocol](#checkpoint-protocol)
- [Byzantine Fault Tolerance](#byzantine-fault-tolerance)
- [Safety and Liveness](#safety-and-liveness)
- [Formal Verification](#formal-verification)

---

## Overview

### System Model

**Nodes**:
- Total nodes: `n ≥ 3f+1` (Byzantine fault tolerance)
- Honest nodes: ≥ `2f+1` (majority)
- Byzantine nodes: ≤ `f` (minority)

**Network**:
- **Asynchronous**: No bounds on message delivery time
- **Eventually synchronous**: Messages delivered eventually (liveness assumption)
- **Authenticated**: Messages signed with KERI identifiers
- **Point-to-point**: Nodes communicate via GRPC MTLS

**Failures**:
- **Byzantine**: Arbitrary malicious behavior (equivocation, forgery, corruption)
- **Crash**: Node stops responding (silent failure)
- **Network partition**: Temporary communication failure

### Trust Assumptions

**Quorum intersection**: Any two quorums (2f+1) share at least one honest node

**Proof**:
```
Given:
  n = 3f+1 (total nodes)
  Q = 2f+1 (quorum size)

Claim: Any two quorums Q1, Q2 share ≥ 1 honest node

Proof:
  |Q1 ∪ Q2| ≤ n = 3f+1
  |Q1| + |Q2| = 2(2f+1) = 4f+2
  |Q1 ∩ Q2| = |Q1| + |Q2| - |Q1 ∪ Q2|
            ≥ 4f+2 - 3f+1 = f+1

  At most f nodes are Byzantine
  Therefore: Q1 ∩ Q2 contains ≥ 1 honest node ∎
```

### Cryptographic Primitives

**Hash function** (SHA-256 or BLAKE2b):
```
H: {0,1}* → {0,1}^256
Properties: Collision-resistant, preimage-resistant
```

**Digital signatures** (BLS-12-381 or ED25519):
```
Sign(sk, m) → σ
Verify(pk, m, σ) → {true, false}
Properties: Unforgeability under chosen message attack
```

**Signature aggregation** (BLS only):
```
Aggregate([σ1, ..., σk]) → σ_agg
VerifyAggregated(pk_set, m, σ_agg) → {true, false}
```

---

## Consensus Protocol

### Protocol Overview

CHOAM achieves consensus via **Ethereal** (Aleph-BFT), an asynchronous Byzantine consensus protocol. CHOAM layers state machine replication on top of Ethereal's total order guarantee.

**Protocol stack**:
```
┌─────────────────────────────────────┐
│  Application State Machine          │  ← User-defined logic
├─────────────────────────────────────┤
│  CHOAM (State Machine Replication)  │  ← Block production, validation
├─────────────────────────────────────┤
│  Ethereal (Aleph-BFT)               │  ← Total order consensus
├─────────────────────────────────────┤
│  Fireflies (Membership)             │  ← Gossip overlay
├─────────────────────────────────────┤
│  Network (GRPC MTLS)                │  ← Transport
└─────────────────────────────────────┘
```

### Block Production

**Roles**:
- **Producer**: Any committee member can propose blocks
- **Consensus**: Ethereal orders proposed blocks
- **Validator**: All committee members validate ordered blocks

**Block structure**:
```
Block {
  height: Integer            // Monotonically increasing
  previous: Digest           // Hash of previous block
  transactions: [Tx]         // Ordered transactions
  view: ViewId               // Current view identifier
  signatures: [Signature]    // 2f+1 member signatures
  timestamp: Timestamp       // Block creation time
}
```

**Block production algorithm**:

```
Algorithm: ProduceBlock()
  Input: pending transactions T
  Output: Block b

  1. Batch transactions:
     txs ← poll(T, batchSize, batchDelay)

  2. Propose to Ethereal:
     preBlock ← Ethereal.propose(txs)

  3. Wait for consensus decision:
     await preBlock.decided()

  4. Assemble CHOAM block:
     b ← Block(
       height: currentHeight + 1,
       previous: H(previousBlock),
       transactions: preBlock.txs,
       view: currentView,
       signatures: [],
       timestamp: now()
     )

  5. Sign block:
     σ ← Sign(viewKey, H(b))
     b.signatures.append(σ)

  6. Broadcast via gossip:
     Gossip.publish(b)

  7. Return block:
     return b
```

### Block Validation

**Validation checks** (all must pass):

```
Algorithm: ValidateBlock(b, committee)
  Input: Block b, Committee committee
  Output: boolean (valid or invalid)

  1. Height validation:
     if b.height ≠ expectedHeight:
       return false

  2. Hash chain validation:
     if b.previous ≠ H(lastBlock):
       return false

  3. Signature validation:
     validSigs ← 0
     for σ in b.signatures:
       if Verify(member.pk, H(b), σ):
         validSigs += 1

     if validSigs < committee.quorum():
       return false  // Need 2f+1 valid signatures

  4. View validation:
     if b.view ≠ currentView:
       return false

  5. State transition validation:
     snapshot ← stateHolder.snapshot()
     for tx in b.transactions:
       if not StateMachine.validate(tx, snapshot):
         return false  // Invalid state transition

  6. All checks passed:
     return true
```

### Block Application

**Application algorithm** (executed on valid blocks only):

```
Algorithm: ApplyBlock(b)
  Input: Block b (assumed valid)
  Output: State s'

  1. Acquire state write lock:
     lock.writeLock().acquire()

  2. Apply transactions sequentially:
     for tx in b.transactions:
       StateMachine.apply(tx, currentState)

  3. Update block height:
     currentHeight ← b.height

  4. Update last block hash:
     lastBlockHash ← H(b)

  5. Release lock:
     lock.writeLock().release()

  6. Checkpoint if needed:
     if b.height % checkpointInterval == 0:
       CreateCheckpoint(b.height)

  7. Return new state:
     return currentState
```

---

## View Reconfiguration

### View Model

**View**: A configuration of the committee with unique membership composition

```
View {
  id: Digest                 // XOR(previousBlock.hash, consensusDiadem)
  epoch: Integer             // Sequential view number
  members: [Member]          // Committee members
  keys: [PublicKey]          // Ephemeral view keys (one per member)
  quorum: Integer            // 2f+1
  tolerance: Integer         // f
}
```

**View lifecycle**:
```
View N        Assembly        Key Collection      View N+1
  │               │                   │               │
  │ PRODUCING     │ AWAIT_KEYS        │ PRODUCING     │
  └──────────────►└──────────────────►└───────────────►
                  ^                   ^
                  │                   │
            Assemble Block      Reconfigure Block
```

### View Reconfiguration Protocol

**Trigger conditions**:
- Periodic rotation (configurable interval, default: 1 hour)
- Committee membership change (node join/leave)
- Byzantine detection (exclude malicious member)

**Protocol steps**:

```
Algorithm: ViewReconfiguration()
  Input: Current view V_n, Fireflies diadem D
  Output: Next view V_{n+1}

  1. Determine consensus view:
     D ← Fireflies.consensusDiadem()

  2. Compute next view ID:
     V_{n+1}.id ← H(lastBlock) XOR D

  3. Select next committee:
     V_{n+1}.members ← ConsistentHash(V_{n+1}.id, FirefliesMembers)

  4. Produce assemble block:
     assembleBlock ← Block(
       type: ASSEMBLE,
       diadem: D,
       nextView: V_{n+1}.id
     )
     Gossip.publish(assembleBlock)

  5. Start block production for V_n:
     Producer.state ← PRODUCING

  6. Collect view keys from V_{n+1} members:
     viewKeys ← []
     while |viewKeys| < V_{n+1}.quorum:
       await ViewKeyProposal(member, pk)
       if member ∈ V_{n+1}.members:
         viewKeys.append(pk)

  7. Produce reconfiguration block:
     reconfigureBlock ← Block(
       type: RECONFIGURE,
       viewKeys: viewKeys,
       nextView: V_{n+1}.id
     )
     Gossip.publish(reconfigureBlock)

  8. Switch to new view:
     currentView ← V_{n+1}
     currentEpoch ← V_n.epoch + 1

  9. Delete old view keys:
     DeleteViewKey(V_n.keys[self])

  10. Return new view:
      return V_{n+1}
```

### View Key Generation

**Key properties**:
- **Ephemeral**: Generated per view, deleted after rotation
- **Pseudo-random**: Deterministic from view ID + member seed
- **Unlinked**: Cannot correlate keys across views

**Key generation algorithm**:

```
Algorithm: GenerateViewKey(viewId, memberSeed)
  Input: View ID viewId, Member seed memberSeed
  Output: KeyPair (sk, pk)

  1. Derive key seed:
     seed ← KDF(viewId || memberSeed)

  2. Generate BLS keypair:
     (sk, pk) ← BLS.KeyGen(seed)

  3. Return keypair:
     return (sk, pk)
```

**Key deletion**:

```
Algorithm: DeleteViewKey(sk)
  Input: Secret key sk

  1. Overwrite key material:
     sk ← random_bytes(len(sk))

  2. Garbage collect:
     sk ← null

  3. Secure deletion (OS-dependent):
     If available: SecureDelete(sk)
```

---

## Checkpoint Protocol

### Checkpoint Model

**Checkpoint**: A snapshot of system state at a specific block height

```
Checkpoint {
  height: Integer            // Block height at checkpoint
  stateRoot: Digest          // Merkle root of state
  provenance: [Block]        // View reconfiguration blocks from genesis
  state: Blob                // Serialized state snapshot
  signatures: [Signature]    // 2f+1 member signatures
}
```

### Checkpoint Creation

**Algorithm**:

```
Algorithm: CreateCheckpoint(height)
  Input: Block height height
  Output: Checkpoint cp

  1. Acquire state write lock:
     lock.writeLock().acquire()

  2. Serialize current state:
     stateBlob ← Serialize(currentState)

  3. Compute Merkle root:
     stateRoot ← MerkleTree(stateBlob).root()

  4. Collect provenance chain:
     provenance ← []
     b ← lastBlock
     while b.type ∈ {ASSEMBLE, RECONFIGURE}:
       provenance.prepend(b)
       b ← b.previous

  5. Create checkpoint:
     cp ← Checkpoint(
       height: height,
       stateRoot: stateRoot,
       provenance: provenance,
       state: stateBlob,
       signatures: []
     )

  6. Sign checkpoint:
     σ ← Sign(viewKey, H(cp))
     cp.signatures.append(σ)

  7. Broadcast checkpoint:
     Gossip.publish(cp)

  8. Release lock:
     lock.writeLock().release()

  9. Return checkpoint:
     return cp
```

### Bootstrap from Checkpoint

**Algorithm**:

```
Algorithm: BootstrapFromCheckpoint(cp)
  Input: Checkpoint cp
  Output: State s

  1. Validate checkpoint:
     if not ValidateCheckpoint(cp):
       abort "Invalid checkpoint"

  2. Deserialize state:
     state ← Deserialize(cp.state)

  3. Verify Merkle root:
     if MerkleTree(cp.state).root() ≠ cp.stateRoot:
       abort "Merkle root mismatch"

  4. Restore state:
     stateHolder.restore(state, cp.height)

  5. Replay provenance chain:
     for block in cp.provenance:
       ViewManager.replay(block)

  6. Resume from checkpoint height:
     currentHeight ← cp.height

  7. Return state:
     return state
```

---

## Byzantine Fault Tolerance

### Byzantine Behaviors

**Detectable Byzantine behaviors**:

1. **Signature forgery**: Invalid cryptographic signature
   ```
   Verify(pk, m, σ) → false
   ```

2. **Equivocation**: Sending different blocks at same height
   ```
   b1.height = b2.height ∧ H(b1) ≠ H(b2)
   ```

3. **Fork creation**: Conflicting previous block hashes
   ```
   b1.previous ≠ b2.previous ∧ b1.height = b2.height
   ```

4. **State corruption**: Invalid state transition
   ```
   StateMachine.validate(tx, snapshot) → false
   ```

5. **Timing anomaly**: Abnormal message timing (statistical)
   ```
   latency > 3 * median_latency
   ```

6. **Rate anomaly**: Excessive message rate (statistical)
   ```
   message_rate > 5 * median_rate
   ```

### Byzantine Detection

**Detection algorithm** (multi-signal):

```
Algorithm: DetectByzantine(member)
  Input: Member member
  Output: boolean (Byzantine or honest)

  1. Signature verification:
     for msg in messages(member):
       if not Verify(member.pk, msg.data, msg.sig):
         RecordAnomaly(member, SIGNATURE_FORGERY)
         return true

  2. Equivocation detection:
     blocksByHeight ← GroupBy(blocks(member), height)
     for (height, blocks) in blocksByHeight:
       if |blocks| > 1:
         RecordAnomaly(member, EQUIVOCATION)
         return true

  3. Timing anomaly:
     latency ← MeasureLatency(member)
     if latency > 3 * medianLatency:
       RecordAnomaly(member, TIMING_ANOMALY)
       return true

  4. Rate anomaly:
     rate ← MeasureMessageRate(member)
     if rate > 5 * medianRate:
       RecordAnomaly(member, RATE_ANOMALY)
       return true

  5. No anomaly detected:
     return false
```

### Byzantine Exclusion

**Exclusion protocol**:

```
Algorithm: ExcludeByzantineMember(member)
  Input: Byzantine member member
  Output: New view V'

  1. Initiate view rotation:
     V' ← InitiateViewChange()

  2. Exclude member from next view:
     V'.members ← currentView.members \ {member}

  3. Ensure quorum:
     if |V'.members| < 3f+1:
       abort "Cannot maintain quorum after exclusion"

  4. Complete view rotation:
     CompleteViewChange(V')

  5. Audit log:
     AuditLog.record(MEMBER_EXCLUDED, member, reason)

  6. Return new view:
     return V'
```

---

## Safety and Liveness

### Safety Property

**Linearizability**: All honest nodes commit blocks in the same order

**Theorem (Safety)**:
```
If two honest nodes commit blocks b1 and b2 at the same height h,
then H(b1) = H(b2) (same block).
```

**Proof sketch**:
```
Assume contradiction:
  Honest nodes N1, N2 commit different blocks b1, b2 at height h

  b1 and b2 each have 2f+1 valid signatures (quorum)
  Total signatures: 4f+2
  Total nodes: 3f+1

  By pigeonhole principle:
    At least f+1 nodes signed both b1 and b2

  At most f nodes are Byzantine
  Therefore:
    At least 1 honest node signed both b1 and b2

  Contradiction:
    Honest nodes never sign conflicting blocks ∎
```

### Liveness Property

**Progress**: If ≤ f nodes are Byzantine, the system makes progress

**Theorem (Liveness)**:
```
Under asynchronous network with eventual synchrony,
if ≤ f nodes are Byzantine,
then the system commits blocks eventually.
```

**Proof sketch (via Ethereal)**:
```
Ethereal (Aleph-BFT) guarantees liveness if:
  1. Network is eventually synchronous (assumption)
  2. ≤ f nodes are Byzantine (assumption)
  3. ≥ 2f+1 nodes participate (follows from n = 3f+1)

CHOAM layers deterministic state machine on Ethereal's total order
Therefore:
  CHOAM inherits liveness from Ethereal ∎
```

### Termination Property

**View reconfiguration terminates**:

**Theorem (Termination)**:
```
View reconfiguration completes in finite time
if ≤ f nodes are Byzantine.
```

**Proof sketch**:
```
View reconfiguration requires:
  1. Consensus on Fireflies diadem (guaranteed by Fireflies)
  2. Collection of 2f+1 view keys (guaranteed by quorum)
  3. Gossip of reconfiguration block (guaranteed by BEG)

Each step terminates in finite time under eventual synchrony
Therefore:
  View reconfiguration terminates ∎
```

---

## Formal Verification

### TLA+ Specification

**CHOAM.tla** (simplified excerpt):

```tla
------------------------ MODULE CHOAM ------------------------
EXTENDS Naturals, FiniteSets, Sequences

CONSTANTS Members, MaxHeight, F
ASSUME F \in Nat /\ Cardinality(Members) = 3*F + 1

VARIABLES
  height,           \* Current block height
  lastBlock,        \* Hash of last committed block
  signatures,       \* Set of signatures for current block
  state             \* Current state machine state

TypeInvariant ==
  /\ height \in Nat
  /\ lastBlock \in [hash: STRING]
  /\ signatures \subseteq Members \X STRING
  /\ state \in [value: STRING]

Init ==
  /\ height = 0
  /\ lastBlock = [hash |-> "genesis"]
  /\ signatures = {}
  /\ state = [value |-> "initial"]

ProduceBlock ==
  /\ Cardinality(signatures) >= 2*F + 1  \* Quorum
  /\ height' = height + 1
  /\ lastBlock' = [hash |-> "hash(height)"]
  /\ signatures' = {}
  /\ state' = [value |-> "updated"]

Safety ==
  \A h \in Nat : Cardinality({b \in DOMAIN lastBlock : b.height = h}) <= 1
  \* At most one block per height

Liveness ==
  height < MaxHeight ~> height = MaxHeight
  \* Eventually reach max height

Spec == Init /\ [][ProduceBlock]_<<height, lastBlock, signatures, state>>
       /\ WF_<<height, lastBlock, signatures, state>>(ProduceBlock)

THEOREM Spec => [](Safety) /\ <>(Liveness)
===========================================================
```

**Model checking**:
```bash
# TLC model checker
tlc CHOAM.tla -config CHOAM.cfg

# Check safety invariant
# Check liveness property under fairness assumptions
```

### Coq Proof Sketch

**Safety proof** (linearizability):

```coq
Require Import List.
Require Import ZArith.

Definition Block := nat.
Definition Height := nat.
Definition Member := nat.
Definition Signature := nat.

Definition Quorum (n f : nat) := 2 * f + 1.

Lemma quorum_intersection :
  forall n f q1 q2,
    n = 3 * f + 1 ->
    length q1 = Quorum n f ->
    length q2 = Quorum n f ->
    exists m, In m q1 /\ In m q2.
Proof.
  (* Proof by pigeonhole principle *)
  intros.
  (* |q1 ∪ q2| <= n = 3f+1 *)
  (* |q1 ∩ q2| = |q1| + |q2| - |q1 ∪ q2| >= (2f+1) + (2f+1) - (3f+1) = f+1 *)
  (* Therefore intersection is non-empty *)
Qed.

Theorem safety :
  forall h b1 b2 q1 q2,
    valid_block h b1 q1 ->
    valid_block h b2 q2 ->
    b1 = b2.
Proof.
  (* Proof using quorum_intersection lemma *)
  (* If honest node signed both, blocks must be identical *)
Qed.
```

---

## Appendix

### Parameter Recommendations

**Committee size** (production):
```
Small: n=7, f=2 (14% Byzantine tolerance)
Medium: n=10, f=3 (30% Byzantine tolerance)
Large: n=13, f=4 (30% Byzantine tolerance)
```

**Trade-offs**:
- Larger n: Higher fault tolerance, lower throughput
- Smaller n: Lower fault tolerance, higher throughput

**Timeouts** (production baseline):
```
Stall timeout: 5s
View change timeout: 30s
Session timeout: 60s
Bootstrap timeout: 120s
Checkpoint interval: 3600s (1 hour)
```

### Security Considerations

**Key management**:
- KERI identifiers: Rotate annually or after suspected compromise
- View keys: Rotated automatically on every view change (ephemeral)
- TLS certificates: Rotate quarterly (standard practice)

**Audit logging**:
- Byzantine incidents: CRITICAL severity, permanent retention
- View changes: INFO severity, 1 year retention
- Block production: DEBUG severity, 30 days retention

**Network security**:
- MTLS required for all GRPC connections
- Firewall rules: Restrict access to committee members only
- DDoS mitigation: Rate limiting, connection limits

---

**Last Updated**: 2026-02-08
**CHOAM Version**: 0.0.6-SNAPSHOT

For implementation details, see [ARCHITECTURE.md](ARCHITECTURE.md).
For deployment guide, see [OPERATOR_GUIDE.md](OPERATOR_GUIDE.md).
For terminology, see [GLOSSARY.md](GLOSSARY.md).
