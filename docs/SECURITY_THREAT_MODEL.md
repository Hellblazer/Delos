# Delos Security Threat Model & Defense Strategy

**Version**: 1.0
**Date**: January 9, 2026
**Status**: Production Ready

## Executive Summary

Delos is a distributed multi-tenant database platform designed to operate securely in adversarial environments. This document provides the unified threat model across all architectural layers and describes the defense mechanisms that mitigate identified threats.

### Security Guarantees

Delos provides the following guarantees assuming correct implementation and proper deployment:

1. **Byzantine Fault Tolerance**: System tolerates up to f < n/3 simultaneously malicious nodes in membership consensus
2. **Cryptographic Integrity**: All identities, messages, and state transitions are cryptographically authenticated
3. **Decentralized Identity**: No central certificate authority required; identities are self-contained and verifiable
4. **Consistent Replication**: All replicas converge to identical state despite Byzantine failures
5. **Secure Communications**: All inter-node communication is encrypted and authenticated via MTLS with KERI-based certificates

### Scope & Assumptions

This threat model covers:
- Fireflies membership consensus layer
- Ethereal Byzantine atomic broadcast (consensus)
- CHOAM state machine replication committee
- SQL-State deterministic execution
- Stereotomy key event receipt infrastructure
- Gorgoneion identity bootstrapping

**Out of scope**:
- Operating system vulnerabilities
- Network hardware attacks
- Side-channel attacks (timing, cache, power)
- Quantum computing threats
- Client software vulnerabilities

---

## Part 1: Threat Model Overview

### 1.1 Adversary Model

#### Threat Level 1: Passive Network Adversary
- **Capability**: Eavesdrop on network traffic
- **Goal**: Obtain confidential information
- **Assumption**: Cannot forge valid cryptographic signatures

**Mitigations**:
- All network communication encrypted via MTLS
- KERI certificates bind identities to cryptographic keys
- See Section 2.1: Network Security

#### Threat Level 2: Active Network Adversary
- **Capability**: Intercept, modify, or replay network messages
- **Goal**: Disrupt service, forge transactions, cause incorrect state
- **Assumption**: Cannot break cryptographic primitives (SHA256, HMAC, signatures)

**Mitigations**:
- Message authentication codes prevent tampering
- Nonce tracking prevents replay attacks
- Cryptographic signature verification validates source
- See Section 2.2: Message Authentication & Replay Prevention

#### Threat Level 3: Byzantine Node Adversary
- **Capability**: Control up to f < n/3 nodes in the cluster
- **Goal**: Disrupt consensus, forge incorrect state, prevent progress
- **Assumption**: Cannot control more than f nodes; remaining 2f+1 nodes are correct

**Mitigations**:
- Fireflies ensures Byzantine-resilient membership voting
- Ethereal consensus tolerates f Byzantine voters
- CHOAM enforces committee-based execution
- See Section 3: Byzantine Fault Tolerance

#### Threat Level 4: Quorum Loss / Catastrophic Failure
- **Scenario**: More than f nodes simultaneously fail
- **Assumption**: Recovery procedures restore failed nodes or add new members

**Mitigations**:
- Checkpoint procedures enable state recovery
- Backup and replication prevent data loss
- See Section 4: Disaster Recovery

### 1.2 Assets

| Asset | Impact of Loss | Holder | Protection |
|-------|----------------|--------|-----------|
| Private Cryptographic Keys | Complete system compromise | Each member | KERI key rotation, hardware security modules |
| Transaction Data | Loss of business records | All replicas | Byzantine consensus, checkpoints |
| Membership Consensus | Complete service outage | Fireflies layer | BFT voting, 3f+1 quorum |
| Block Ordering | State inconsistency | Ethereal consensus | DAG-based causal ordering |
| Committee State | Loss of recent transactions | CHOAM committee | Reliable broadcast, replication |

### 1.3 Attack Vectors by Layer

#### Membership Layer (Fireflies)
1. **False Join**: Attacker joins with forged credentials
   - **Mitigation**: KERI signature verification, BFT majority required for view change
2. **Sybil Attack**: Attacker controls multiple identities
   - **Mitigation**: Explicit membership voting required; each identity has equal weight
3. **Membership Eclipse**: Attacker isolates nodes from network
   - **Mitigation**: Gossip-based membership discovery, ring structure resilience
4. **Unstable View**: Attacker triggers rapid view changes
   - **Mitigation**: Stability detection, view change rate limiting

#### Consensus Layer (Ethereal)
1. **Equivocation**: Byzantine node votes for conflicting blocks
   - **Mitigation**: DAG structure prevents conflicting heights; all votes logged
2. **Consensus Stall**: Byzantine nodes delay voting
   - **Mitigation**: Timeout-based view rotation, leader selection rotation
3. **Block Withholding**: Byzantine nodes refuse to gossip blocks
   - **Mitigation**: Reliable broadcast ensures all nodes eventually receive blocks
4. **Fork**: Byzantine majority creates different histories
   - **Mitigation**: Impossible with f < n/3 Byzantine nodes; majority correctness ensures single history

#### Replication Layer (CHOAM)
1. **Out-of-Order Execution**: Transactions executed in wrong order
   - **Mitigation**: Committee-based serialization, consensus ordering
2. **State Divergence**: Replicas execute differently
   - **Mitigation**: Deterministic execution rules, JOOQ determinism guarantees
3. **Checksum Mismatch**: Replicas disagree on state hash
   - **Mitigation**: Deterministic replication, periodic state validation
4. **Lost Transactions**: Committed transactions not persisted
   - **Mitigation**: Durable logging, quorum write requirements

#### Execution Layer (SQL-State)
1. **Non-Deterministic Execution**: SQL operations produce inconsistent results
   - **Mitigation**: Banned non-deterministic functions, JOOQ-based determinism
2. **Unauthorized Modification**: Attacker modifies data without quorum agreement
   - **Mitigation**: CHOAM consensus requirement before state changes
3. **Schema Tampering**: Attacker modifies table structure
   - **Mitigation**: Consensus requirement for DDL, audit trail

#### Identity Layer (Stereotomy/KERI)
1. **Private Key Compromise**: Attacker obtains private key
   - **Mitigation**: Key rotation protocol, key escrow with witness detection
2. **Rotation Theft**: Attacker performs unauthorized key rotation
   - **Mitigation**: Rotation witness threshold, signature requirements
3. **Derivation Attack**: Attacker computes non-derivable identifier
   - **Mitigation**: Cryptographic derivation validation
4. **Inception Collision**: Two identities created with same inception event
   - **Mitigation**: Sequential numbering, hash-based deduplication

#### Bootstrap Layer (Gorgoneion)
1. **Credential Forgery**: Attacker creates fake credentials
   - **Mitigation**: Digital signature verification, issuer validation
2. **Replay Attack**: Attacker replays old credential
   - **Mitigation**: Nonce tracking, timestamp validation, TTL enforcement
3. **Man-in-the-Middle**: Attacker intercepts credential exchange
   - **Mitigation**: MTLS with certificate pinning, KERI signature verification
4. **Clock Skew Exploitation**: Attacker uses time mismatch
   - **Mitigation**: Configurable clock skew tolerance (5-30 seconds recommended)

---

## Part 2: Defense Mechanisms by Layer

### 2.1 Network Security: MTLS with KERI

**Threat**: Network eavesdropping, man-in-the-middle attacks, identity spoofing

**Defense**:
- All inter-node communication via MTLS (TLS 1.3+)
- KERI-derived certificates bind identities to public keys
- Certificate pinning prevents MITM via compromised CAs
- Mutual authentication: client and server verify each other

**Implementation**:
```java
// Certificate validation uses KERI identity
RSAPublicKey publicKey = member.getPublicKey(); // From KERI identity
X509Certificate cert = member.getCertificate();
// Verify cert signed by member's key
cert.verify(publicKey);
```

**Configuration Checklist**:
- [ ] TLS 1.3 or higher enabled
- [ ] Certificate rotation configured (annually recommended)
- [ ] Strong cipher suites only (no legacy algorithms)
- [ ] OCSP stapling enabled for revocation checking
- [ ] Certificate pinning implemented for critical peers

### 2.2 Message Authentication & Replay Prevention

**Threat**: Message tampering, replay attacks, credential reuse

**Defense - Replay Cache**:
- Central cache of (noise, issuer, timestamp) tuples
- TTL: maxDuration + clockSkewTolerance (default: 35 seconds)
- LRU eviction at 10,000 entries
- Thread-safe atomic check-then-admit pattern

**Implementation** (Gorgoneion.ReplayCache):
```java
public class ReplayCache {
    private final Cache<NonceKey, Boolean> seenNonces;

    public boolean tryAdmit(NonceKey nonce) {
        // Atomic: check if exists, then add if not
        return seenNonces.putIfAbsent(nonce, true) == null;
    }

    public record NonceKey(Digest noise, Digest issuer, Timestamp timestamp) {}
}
```

**Defense - Timestamp Validation**:
- All credentials timestamped at issuance
- Validation enforces clock skew tolerance (±5s default)
- Validation enforces maximum age (30s default)
- Boundary testing required for deployment

**Configuration**:
```properties
# Gorgoneion Parameters
credentials.clockSkewTolerance=5s          # Allow ±5s clock drift
credentials.maxDuration=30s                # Reject credentials >30s old
credentials.replayCacheSize=10_000         # Max nonces cached
credentials.replayCacheTTL=35s             # Expire after TTL
```

**Validation Checklist**:
- [ ] Replay cache configured and tested
- [ ] Clock skew tolerance set appropriately for environment
- [ ] Maximum duration set based on transaction latency requirements
- [ ] Cache size tested under load
- [ ] TTL tested for timing edge cases

### 2.3 Byzantine Fault Tolerance: Fireflies

**Threat**: Byzantine nodes attempting consensus manipulation, membership forgery

**Defense**:
- **BFT Ring Structure**: Members arranged in rings; each ring provides independent view
- **Accusation Protocol**: Monitors detect failed members, issue accusations
- **Shunning**: Failed members removed from view, must rejoin
- **Amplification**: Monitors reinforce accusations when others shun same member
- **BFT Voting**: View changes require f < n/3 adversaries can't prevent with Byzantine majority

**Key Properties**:
- Membership agreement requires majority (>50% Byzantine-resistant votes)
- View changes only via consensus voting
- Join protocol requires BFT majority agreement on membership crown

**Attack Resistance**:
| Attack | Resistant? | Reason |
|--------|-----------|--------|
| Single Byzantine member accusation | ✓ | Rebuttal required; majority decides |
| Multiple Byzantine accusations | ✓ | Would require >f Byzantine nodes (impossible) |
| Forged membership bloom filter | ✓ | Requires BFT majority agreement |
| Sybil attack | ✓ | Each identity given equal weight; quorum voting required |

**Testing Requirements** (from GorgoneionByzantineAttackTest):
- [ ] Replay flood attack detection (100x replays rejected)
- [ ] BFT subset enforcement (non-members rejected)
- [ ] Cascade failure prevention (failures isolated)
- [ ] Performance under stress (1000 rapid replays < 100ms)

### 2.4 Consensus: Ethereal Atomic Broadcast

**Threat**: Byzantine consensus manipulation, state fork, equivocation

**Defense**:
- **DAG-Based Ordering**: Directed acyclic graph prevents cycles, cycles
- **Causal Ordering**: Dependencies prevent out-of-order delivery
- **Pre-Block Attestation**: Leaders attest to proposed blocks before voting
- **Quorum Signing**: Block finalization requires f+1 signatures
- **View Rotation**: Leaders selected based on view number; rotation prevents leader dominance

**Safety Property**: No two correct nodes will deliver conflicting blocks at same height

**Liveness Property**: If majority nodes correct, blocks eventually delivered

**Proof Sketch**:
- If attacker controls ≤f nodes, correct nodes form 2f+1 quorum
- Any two quorums overlap in >f nodes
- Overlapping nodes ensure agreement between quorums

**Implementation Validation**:
```java
// From BftSubsetSignatureEnforcementTest
Set<Digest> bftSubset = context.bftSubset(blockHash);
int validSignatures = validator.countValidSignatures(
    signatures, bftSubset, blockData
);
assertTrue(validator.hasMajority(validSignatures),
    "Must have majority signatures from BFT subset");
```

### 2.5 Replication: CHOAM Deterministic Execution

**Threat**: State divergence, non-deterministic execution, transaction ordering violations

**Defense**:
- **Committee-Based Serialization**: Single committee serializes all transactions
- **Deterministic Execution Rules**: No random values, thread-local state, system time in transactions
- **JOOQ Enforcement**: SQL generated by JOOQ ensures no non-deterministic SQL
- **Periodic Checkpoints**: State snapshots enable detection of divergence
- **Validation Checksums**: Hash of state computed after each block

**Determinism Requirements**:
```sql
-- ALLOWED: Deterministic SQL
SELECT * FROM accounts WHERE id = ? ORDER BY account_id
INSERT INTO transactions (amount) VALUES (?)

-- FORBIDDEN: Non-deterministic SQL
SELECT * FROM accounts LIMIT 5                    -- No ORDER BY
INSERT INTO logs VALUES (CURRENT_TIMESTAMP)       -- Time-dependent
SELECT UUID()                                      -- Random value
```

**Testing** (from CHOAM & SQL-State tests):
- [ ] State hash agreement: all replicas compute same hash
- [ ] Replication lag < 100ms in steady state
- [ ] View changes don't cause state divergence
- [ ] Byzantine member can't cause divergence

### 2.6 Identity Management: Stereotomy / KERI

**Threat**: Private key compromise, unauthorized rotation, identity forgery

**Defense**:
- **Key Event Receipt Infrastructure (KERI)**: Verifiable, self-certifying identities
- **Inception Event**: First event establishes keys and key rotation authority
- **Rotation Event**: Key rotation requires witness threshold signatures
- **Witness Delegation**: Witnesses detect unauthorized rotations
- **Escrow**: Previous keys held in escrow during rotation
- **Keyhead Hash**: Rotating chain prevents resurrection of old keys

**Key Rotation Security**:
```
Event 1 (Inception):
  - Establishes initial keys
  - Specifies witness threshold (e.g., 2-of-3)
  - Specifies next key hash (forward commitment)

Event 2 (Rotation):
  - New keys with new next key hash
  - Signatures from old keys OR witness threshold
  - Witness confirmations prove unauthorized attempts detected

Event 3+ (Subsequent Rotations):
  - Same process repeats
  - Previous keys held in escrow
  - Witnesses detect any duplicate events at same sequence number
```

**Validation Checklist**:
- [ ] All identifiers derive from inception events
- [ ] All rotations verified against witness threshold
- [ ] No duplicate sequence numbers accepted
- [ ] Previous keys retained until rotation confirmed

### 2.7 Credential Validation: Gorgoneion

**Threat**: Stale credentials, forged credentials, credential reuse

**Defense**:
- **Replay Cache**: Nonce tracking prevents reuse (see 2.2)
- **Timestamp Validation**: TTL enforcement (see 2.2)
- **Signature Verification**: Cryptographic validation of issuer
- **BFT Subset Enforcement**: Credentials must come from valid committee members
- **Clock Skew Tolerance**: Allows for reasonable time divergence

**Multi-Layer Validation**:
```java
// From CredentialValidatorTest
boolean isValid =
    validator.isTimestampValid(credential.getTimestamp())  // Time check
    && cache.tryAdmit(nonceKey)                             // Replay check
    && validator.isMember(issuer)                           // Membership check
    && validator.verifySignature(issuer, sig, data);        // Crypto check
```

---

## Part 3: Byzantine Fault Tolerance Analysis

### 3.1 BFT Assumptions & Limits

**Assumption 1: Network Synchrony Bound**
- Assumption: Network messages delivered within bounded time Δ
- Implication: Timeouts can detect failures (not possible in asynchronous networks)
- Testing: Measurement of worst-case network latency + margin

**Assumption 2: Cryptographic Hardness**
- Assumption: SHA256, HMAC, ED25519 computationally hard to break
- Implication: Valid signatures cannot be forged
- Testing: Standard cryptographic validation

**Assumption 3: Quorum Intersection**
- Assumption: Any two quorums of size >2f out of n = 3f+1 nodes must intersect
- Implication: Majority among quorums remains correctness-preserving
- Math: If n = 3f+1, quorum size = n - f = 2f + 1
  - Two quorums of size 2f+1 intersect in at least f+1 nodes
  - If f nodes Byzantine, at least 1 correct node in intersection

### 3.2 Byzantine Resilience by Failure Count

| Byzantine Nodes (f) | Total Nodes (n=3f+1) | Tolerance | Status |
|---------------------|----------------------|-----------|--------|
| 0 | 1 | None | Development only |
| 1 | 4 | Single Byzantine + single crash | Minimal production |
| 2 | 7 | Two simultaneous Byzantine | Standard production |
| 3 | 10 | Three simultaneous Byzantine | High security |
| 5 | 16 | Five simultaneous Byzantine | Critical infrastructure |

**Selecting Cluster Size**:
- **Small (n=4, f=1)**: Development, testing, low-value data
- **Medium (n=7, f=2)**: Standard production, regional deployments
- **Large (n=10+, f≥3)**: Critical infrastructure, global deployments

### 3.3 Attack Scenarios & Resilience

**Scenario 1: Single Byzantine Node (f=1, n=4)**

Attack: Byzantine node votes for incorrect block
- Result: Block requires majority (3 out of 4) to finalize
- Outcome: Byzantine node's single vote insufficient; correct nodes provide 3 votes
- Resilience: ✓ Guaranteed

**Scenario 2: Network Partition (two partitions, 1 Byzantine)**

Attack: Network splits into (3 correct nodes, 1 Byzantine) and (2 correct nodes)
- Partition A: 3 nodes have quorum (>2), can proceed
- Partition B: 2 nodes lack quorum, cannot proceed
- Outcome: Partition A makes progress; Partition B halts (safe)
- Byzantine Impact: Node in Partition A can be Byzantine but still need majority
- Resilience: ✓ Safe (sacrifices liveness in minority partition)

**Scenario 3: Two Byzantine Nodes (f=2, n=7)**

Attack: Two Byzantine nodes attempt forked history
- Requires: Byzantine nodes to control majority votes
- Reality: Only 2 out of 7 nodes Byzantine; 5 correct
- Quorum: Need 5 votes (>2f) to finalize block
- Outcome: Byzantine nodes' 2 votes insufficient for either fork
- Resilience: ✓ Guaranteed

**Scenario 4: Byzantine + Crashed Nodes (f=2, 1 crash)**

Conditions: 2 Byzantine + 1 crashed = 4 nodes operational (5 total)
- Operational nodes: 4 (1 Byzantine + 3 correct)
- Quorum needed: 4 votes (>2 Byzantine)
- Outcome: 3 correct nodes prevent Byzantine from finalizing blocks
- Resilience: ✓ Guaranteed

**Scenario 5: Multiple Partitions (f=2, n=7)**

Conditions: Partition 1: 4 nodes (2 Byzantine, 2 correct) | Partition 2: 3 nodes (all correct)
- Partition 1: Has majority (4 > 3) and 2 Byzantine, but needs 5 votes for quorum
  - Can produce at most 4 votes
  - Cannot finalize blocks
- Partition 2: 3 correct nodes, needs 5 votes for quorum
  - Cannot finalize blocks
- Outcome: Both partitions halt (safe) until healed
- Resilience: ✓ Safe (liveness sacrificed, safety maintained)

---

## Part 4: Disaster Recovery & Resilience

### 4.1 Failure Scenarios & Recovery

| Scenario | Impact | Recovery Time | Data Loss |
|----------|--------|---|-----------|
| Single node crash | Reduced performance | <10s (detect) + <1m (rejoin) | None |
| Two nodes crash (n=7) | Reduced performance | <10s + <5m | None |
| Quorum loss (>f+1) | Service halt | Restart + state recovery | None if checkpoint exists |
| Malicious node detected | View change | <5-10s | None |
| Data corruption on 1 node | Node restart | <10s + state resync | Covered by replicas |

### 4.2 Checkpointing & Recovery

**Checkpoint Creation**:
- Periodic snapshots of SQL-State at known block heights
- Checkpoint includes: block height, state hash, state data
- Stored durably on all replicas + offsite backup

**Recovery from Checkpoint**:
```
1. Identify last known good checkpoint
2. Stop all nodes
3. Restore from checkpoint to all nodes
4. Replay consensus logs from checkpoint forward
5. Resume consensus at appropriate view
```

**Time Objectives**:
- RPO (Recovery Point Objective): 0 (Byzantine consensus ensures no data loss)
- RTO (Recovery Time Objective): <5 minutes for single node, <10-15 minutes for quorum loss

---

## Part 5: Security Checklist & Validation

### 5.1 Pre-Deployment Security Checklist

**Cryptography & Keys**:
- [ ] KERI identifiers generated for all cluster members
- [ ] Private keys secured (hardware modules recommended)
- [ ] Certificate rotation policy established (annual)
- [ ] Key ceremony procedures documented

**Network & TLS**:
- [ ] TLS 1.3+ configured for all inter-node communication
- [ ] Certificate pinning implemented for critical peers
- [ ] Firewall rules restrict cluster communication to known nodes
- [ ] Network monitoring enabled for anomalous traffic

**Byzantine Resilience**:
- [ ] Cluster size chosen: n = 3f+1 for desired fault tolerance
- [ ] Committee size justified: minimum 7 nodes recommended for production
- [ ] Monitor setup tested: accusation/rebuttal protocols validated
- [ ] View change procedure tested: stability detection working

**Credential Management**:
- [ ] Replay cache configured: size, TTL, clock skew tolerance
- [ ] Timestamp validation tested: boundary conditions verified
- [ ] Credential validation pipeline tested: all layers working
- [ ] Clock synchronization: NTP or equivalent configured

**State Replication**:
- [ ] Determinism rules enforced: no non-deterministic SQL
- [ ] JOOQ generation validated: all SQL generated from schema
- [ ] Checkpointing tested: recovery from checkpoint works
- [ ] State validation: checksums computed and verified

**Operational Readiness**:
- [ ] Monitoring configured: all metrics exposed
- [ ] Alerting configured: thresholds set for anomalies
- [ ] Backup procedures tested: restore process verified
- [ ] Disaster recovery plan validated: team trained

### 5.2 Ongoing Security Validation

**Weekly**:
- [ ] Review security logs for anomalies
- [ ] Check certificate expiration dates
- [ ] Validate state checksums match across replicas
- [ ] Monitor consensus metrics (latency, view changes)

**Monthly**:
- [ ] Rotate any compromised credentials
- [ ] Audit membership changes
- [ ] Test backup restoration procedure
- [ ] Review Byzantine accusation events

**Quarterly**:
- [ ] Security incident review
- [ ] Penetration testing (if applicable)
- [ ] Dependency security scanning
- [ ] Architecture review for new threats

**Annually**:
- [ ] Key rotation ceremony
- [ ] Cluster-wide security audit
- [ ] Disaster recovery full test
- [ ] Cryptographic algorithm review (post-quantum prep)

---

## Part 6: Threat Mitigation Summary

### Attack Vector: Network Eavesdropping
- **Threat**: Confidential data leaked
- **Mitigation**: MTLS encryption, all channels encrypted
- **Residual Risk**: Side-channel attacks (not in scope)

### Attack Vector: Message Tampering
- **Threat**: Messages modified in transit
- **Mitigation**: Message authentication codes, signatures, TLS integrity
- **Residual Risk**: Cryptographic breakage (requires quantum computing)

### Attack Vector: Replay Attacks
- **Threat**: Old credentials reused
- **Mitigation**: Nonce tracking, TTL enforcement, timestamp validation
- **Residual Risk**: Clock skew exploitation (mitigated via tolerance)

### Attack Vector: Byzantine Consensus Attacks
- **Threat**: Incorrect state, forked history
- **Mitigation**: BFT consensus with f < n/3 bound, DAG ordering, quorum signing
- **Residual Risk**: Exceeding f Byzantine nodes (architectural limit)

### Attack Vector: State Divergence
- **Threat**: Replicas become inconsistent
- **Mitigation**: Deterministic execution, CHOAM ordering, checksums
- **Residual Risk**: Implementation bugs (mitigated via testing)

### Attack Vector: Key Compromise
- **Threat**: Private key used maliciously
- **Mitigation**: KERI rotation, witness thresholds, key escrow
- **Residual Risk**: Compromise before detection (mitigated via monitoring)

### Attack Vector: Identity Forgery
- **Threat**: Unauthorized identity claims
- **Mitigation**: Cryptographic signatures, KERI validation, BFT membership voting
- **Residual Risk**: Cryptographic breakage

---

## Part 7: References

### Papers
- [Fireflies: A Secure and Scalable Membership Service](https://ymsir.com/papers/fireflies-tocs.pdf)
- [Key Event Receipt Infrastructure (KERI) Whitepaper](https://github.com/decentralized-identity/keri/blob/main/docs/whitepaper.md)
- [Byzantine Fault Tolerance: From Theory to Practice](https://www.microsoft.com/en-us/research/publication/byzantine-fault-tolerance-from-theory-to-practice/)
- [Self-Stabilizing and Byzantine-Tolerant Overlay Network](https://www.cs.huji.ac.il/~dolev/pubs/opodis07-DHR-fulltext.pdf)

### ADRs
- ADR-0001: Byzantine Fault Tolerant Membership Architecture
- ADR-0002: KERI Implementation Architecture
- ADR-0003: Consensus Design - Committee-based State Machine Replication

### Related Documentation
- `DEPLOYMENT_GUIDE.md` - Operational security procedures
- `MONITORING_GUIDE.md` - Security monitoring and alerting
- `TROUBLESHOOTING_GUIDE.md` - Incident response procedures
- `docs/adr/` - Architecture decision records

---

## Appendix A: Cryptographic Primitives & Algorithm Matrix

### A.1 Complete Algorithm Registry

| Category | Algorithm | Purpose | Standard | Security Level | Phase |
|----------|-----------|---------|----------|----------------|-------|
| **Identity & Signatures** | ED25519 | KERI identity, key rotation, event signing | RFC 8032 | 128-bit | 1A+ |
| **Witness Signatures** | BLS-12-381 | Receipt aggregation, witness consensus | IETF draft | 128-bit | 1B+ |
| **Encryption** | X25519 | ECDH key agreement, session encryption | RFC 7748 | 128-bit | 1A+ |
| | AES-256-GCM | Symmetric encryption (via TLS 1.3+) | FIPS 197 | 256-bit | 1A+ |
| | ChaCha20-Poly1305 | Alternative AEAD cipher | RFC 8439 | 256-bit | 1A+ |
| **Hashing** | SHA-256 | Digests, identifiers, checksums, HMAC | FIPS 180-4 | 128-bit | 1A+ |
| | SHA-512 | Ed25519 seed expansion | FIPS 180-4 | 256-bit | 1A+ |
| | SHAKE256 | Ed448 seed expansion, XOF | FIPS 202 | ≥256-bit | Future |
| **Key Derivation** | HKDF | Derives keys from master secret | RFC 5869 | 128/256-bit | 1A+ |
| | PBKDF2 | Derives encryption keys from passphrases | RFC 2898 | 128/256-bit | 1A+ |

### A.2 Algorithm Usage by System Component

| Component | Signing Algorithm | Encryption | Hash | Notes |
|-----------|-------------------|-----------|------|-------|
| **KERI Identity** | ED25519 | X25519 | SHA-256 | All identity events signed with ED25519 |
| **Fireflies Gossip** | ED25519 | TLS 1.3 | SHA-256 | Ring messages signed by members |
| **Ethereal Consensus** | ED25519 | TLS 1.3 | SHA-256 | Block signatures from committee |
| **CHOAM Replication** | ED25519 | TLS 1.3 | SHA-256 | Transaction signatures |
| **Witness Service** | ED25519 + BLS-12-381 | TLS 1.3 | SHA-256 | Dual signatures: ED25519 (Phase 1A), BLS aggregates (Phase 1B+) |
| **Gorgoneion Bootstrap** | ED25519 | TLS 1.3 | SHA-256 | Credential attestation |

### A.3 Cryptographic Safety Properties

| Property | Algorithm | Status | Evidence |
|----------|-----------|--------|----------|
| **Deterministic Signatures** | ED25519 | ✓ | RFC 8032: deterministic ECDSA variant |
| **No Malleability** | ED25519 | ✓ | Unique signature per message |
| **Signature Aggregation** | BLS-12-381 | ✓ | Pairing-friendly curve property |
| **Forward Secrecy** | X25519 ephemeral | ✓ | Ephemeral ECDH per session |
| **Replay Prevention** | (TLS nonce tracking) | ✓ | Nonce validation + timestamp TTL |

### A.4 Hash Functions

**SHA-256** (Primary):
- Digests, identifiers, checksums
- 256-bit output, 128-bit collision resistance
- Hardware acceleration available (SHA-NI)
- NIST FIPS 180-4 standard

**SHA-512** (EdDSA only):
- Ed25519 private scalar derivation
- 512-bit output, 256-bit security
- Part of RFC 8032 specification

**SHAKE256** (Ed448 future):
- Ed448 private scalar derivation
- Extensible output (114 bytes for Ed448)
- FIPS 202 XOF standard

### A.5 Key Derivation Functions

**HKDF** (HMAC-based Extract-Expand):
- Derives session keys from shared secrets
- RFC 5869 standard
- Used for TLS key derivation

**PBKDF2** (Password-Based Key Derivation):
- Derives encryption keys from passphrases
- RFC 2898 standard
- Used for keystore password protection

### A.6 Performance Characteristics

| Operation | Algorithm | Latency | Throughput | Notes |
|-----------|-----------|---------|-----------|-------|
| **Sign** | ED25519 | 1.5ms | ~667/sec | Per signature |
| **Verify** | ED25519 | 2.5ms | ~400/sec | Single signature |
| **Aggregate** | BLS-12-381 | <1ms | 10K+/sec | Create single from k |
| **Verify Agg** | BLS-12-381 | <5ms (k=7) | ~200/sec | Batch verification |
| **ECDH** | X25519 | 2-3ms | ~400/sec | Per connection |
| **TLS handshake** | AES-256-GCM | <100ms | 1K/sec | Per connection |
| **SHA-256** | N/A | <1μs per 64B | >1GB/sec | Hardware accelerated |

### A.7 Post-Quantum Migration Planning

**Timeline** (2026-2030):
1. **Phase 1**: Monitor NIST PQC standardization (2024-2025)
2. **Phase 2**: Evaluate hybrid ED25519 + PQC signatures (2026-2027)
3. **Phase 3**: Implement gradual key rotation to PQC (2027-2029)
4. **Phase 4**: Full migration complete (2029-2030)

**Recommended Algorithm Family**:
- **ML-KEM** (lattice-based key encapsulation): Replace X25519
- **ML-DSA** (lattice-based signatures): Hybrid with ED25519
- **SLH-DSA** (hash-based signatures): Backup option

**Current Status**: ED25519/BLS-12-381 remain secure against classical computers through 2040+

---

## Appendix B: Security Incident Response

**If Byzantine Node Detected**:
1. Monitor detects accusations + rebuttal timeouts
2. Node automatically shunned by other members
3. View change initiated to remove node
4. New member can rejoin if keys reissued

**If Quorum Loss (>f Nodes Down)**:
1. System halts (cannot achieve consensus)
2. Restore failed nodes or add new members
3. Wait for quorum restoration
4. Consensus resumes automatically

**If Data Corruption Detected**:
1. Node detects checksum mismatch with majority
2. Restore from latest checkpoint
3. Replay consensus logs forward
4. Rejoin cluster

**If Private Key Compromised**:
1. Immediately issue key rotation event
2. Sign rotation with witness threshold
3. Old key escrow holds for interval
4. Witnesses monitor for unauthorized rotations

---

**Document Status**: Ready for Production
**Last Updated**: January 9, 2026
**Next Review**: April 9, 2026
