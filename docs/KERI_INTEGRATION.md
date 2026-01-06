# KERI Integration Guide

**Status:** Complete
**Last Updated:** 2026-01-06
**Audience:** Operators, Developers, Architects

---

## Overview

Delos uses KERI (Key Event Receipt Infrastructure) for decentralized identity and key management across the entire cluster. This guide covers KERI integration patterns, operational procedures, and troubleshooting.

**Key concepts:**
- **Identity:** Each member has a unique KERI Self-Addressing Identifier (SAI)
- **Key Events:** Cryptographic events in the KERL that prove key state and rotations
- **Stereotomy:** Delos module providing KERI protocol implementation
- **Gorgoneion:** Identity bootstrap and attestation service

---

## Architecture

```
┌──────────────────────────────────────┐
│ Decentralized Identity Management    │
├──────────────────────────────────────┤
│ KERI (Key Event Receipt Infrastructure)
│  ├─ Self-Addressing Identifiers (SAI)
│  ├─ Key Event Receipt Log (KERL)
│  ├─ Witness network (for attestation)
│  └─ Delegation model (trust delegation)
└──────────────────────────────────────┘
         │
         ▼
┌──────────────────────────────────────┐
│ Delos Integration Layer              │
├──────────────────────────────────────┤
│ Stereotomy Module:
│  ├─ KeyEventProcessor (validation)
│  ├─ MemKERL (in-memory KERL storage)
│  ├─ UniKERL (persistent KERL storage)
│  └─ EphemeralKeyGenerator (per-view keys)
│
│ Gorgoneion Module:
│  ├─ IdentityBootstrapper (new members)
│  ├─ WitnessAttestor (KERI witness)
│  └─ KeyRotationManager
└──────────────────────────────────────┘
         │
         ▼
┌──────────────────────────────────────┐
│ Delos Core Modules                   │
├──────────────────────────────────────┤
│ Fireflies:     Uses identity in gossip protocol
│ CHOAM:         Per-view ephemeral keys
│ SQL-State:     Identity in audit trail
│ Thoth:         DHT for key distribution
└──────────────────────────────────────┘
```

---

## Member Identity Lifecycle

### 1. Identity Creation

**Process:**
```
┌─────────────────────┐
│ Create Key Pair     │ (offline, secure)
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ Generate Inception  │ (create SAI from public key)
│ Event              │
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ Create KERL Entry   │ (prove key event)
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ Distribute to Peers │ (via Gorgoneion)
└─────────────────────┘
```

**Commands:**

```bash
# 1. Generate private key (offline, secure machine)
openssl genpkey -algorithm ED25519 -out member-id-key.pem

# 2. Extract public key
openssl pkey -in member-id-key.pem -pubout -out member-id-pub.pem

# 3. Generate inception event (via Delos CLI)
delos identity create \
  --private-key member-id-key.pem \
  --name "Member1" \
  --output member1-inception.keri

# 4. Distribute inception event to cluster (via Gorgoneion)
delos identity publish member1-inception.keri --witnesses [node1, node2, node3]
```

### 2. Identity Bootstrap (New Member Join)

**Process:**
```
New Member
    │
    ▼
┌──────────────────────────────┐
│ 1. Seed Contact              │ (reach existing member)
└──────────┬───────────────────┘
           │ gossip protocol
           ▼
┌──────────────────────────────┐
│ 2. Obtain KERL               │ (download key event history)
└──────────┬───────────────────┘
           │
           ▼
┌──────────────────────────────┐
│ 3. Verify Key Events         │ (validate KERI signatures)
└──────────┬───────────────────┘
           │
           ▼
┌──────────────────────────────┐
│ 4. Join Fireflies View       │ (establish membership)
└──────────┬───────────────────┘
           │
           ▼
┌──────────────────────────────┐
│ 5. Ephemeral Keys Issued     │ (per-view signing keys)
└──────────────────────────────┘
```

**Configuration in delos.yaml:**

```yaml
identity:
  # Member's KERI identity
  sai: "EJxOVaK_...mL1k"  # Self-Addressing Identifier

  # Key material storage
  keyStore:
    type: JKS
    path: /opt/delos/keys/member-id-keystore.jks
    password: ${KEYSTORE_PASSWORD}  # Use environment variable!

  # KERL storage configuration
  kerl:
    type: persistent         # or 'memory' for testing
    backend: h2             # h2 recommended for production
    path: /opt/delos/data/kerl.h2

# Gorgoneion bootstrap settings
bootstrap:
  # Witness nodes for identity attestation
  witnesses:
    - "node1.delos.local:50052"
    - "node2.delos.local:50052"
    - "node3.delos.local:50052"

  # Time to wait for witness signatures
  witnessTimeoutMs: 5000

  # Require quorum of witnesses (f + 1)
  requireQuorum: true
```

### 3. Key Rotation

**When to rotate:**
- Quarterly maintenance (scheduled)
- Key compromise detected
- Member rejoining after extended outage
- Security incident investigation

**Process:**

```bash
# 1. Generate new key pair
openssl genpkey -algorithm ED25519 -out member-id-new-key.pem

# 2. Create key rotation event
delos identity rotate \
  --old-key member-id-key.pem \
  --new-key member-id-new-key.pem \
  --sai "EJxOV...mL1k"

# 3. Publish rotation event (via Gorgoneion)
delos identity publish \
  member-id-rotation.keri \
  --witnesses [node1, node2, node3]

# 4. Verify rotation in KERL
delos kerl show --sai "EJxOV...mL1k" | tail -5
# Should show: Inception → Rotation → Current

# 5. Update keystore on this node
cp member-id-key.pem member-id-key.pem.old
cp member-id-new-key.pem member-id-key.pem

# 6. Restart Delos service (rolling update recommended)
systemctl restart delos
```

### 4. Identity Validation

**Periodic validation (daily recommended):**

```bash
# Verify all members in current view
delos identity validate-view

# Expected output:
# ✓ Member 1: EJxOV...mL1k - Keys valid, last event: block 12345
# ✓ Member 2: FAJxO...mL1k - Keys valid, last event: block 12346
# ✓ Member 3: GBJxO...mL1k - Keys valid, last event: block 12347
# ✓ 3/3 members healthy
```

---

## Ephemeral Keys Per View

Delos generates ephemeral (single-use) keys for each view to minimize key exposure:

**Strategy:**
- Generate new keypair when entering view
- Use ephemeral keys for block signing
- Delete ephemeral keys after view change
- Recover via identity key if needed

**Implementation:**

```java
// When entering new view
View newView = /* consensus on new membership */;

// Generate ephemeral key for this view
EphemeralKeyGenerator generator = new EphemeralKeyGenerator(memberIdentity);
KeyPair ephemeralKeys = generator.generateForView(newView.getId());

// Use ephemeral key for block signing
Block block = new Block(...);
block.sign(ephemeralKeys.getPrivate());

// On view change, ephemeral key is discarded
// New view starts with fresh ephemeral key
```

**Configuration:**

```yaml
cryptography:
  ephemeralKeys:
    # Ephemeral keys valid for entire view
    rotationPolicy: per-view

    # Key material clearing on view change
    secureClear: true  # Overwrite memory with zeros

    # Backup ephemeral keys for view recovery
    backupLocation: /opt/delos/keys/ephemeral-backups/
```

---

## Integration Points

### Fireflies Gossip

Identity used in gossip headers for authentication:

```java
// Member sends gossip message
GossipMessage msg = new GossipMessage(
    memberIdentity.getSAI(),      // Sender's SAI
    view.getId(),
    gossipData
);

// Receiver validates signature
KeyEventProcessor processor = new KeyEventProcessor();
boolean valid = processor.validateSignature(
    msg.getSignature(),
    msg.getData(),
    memberIdentity.getCurrentKey()  // Retrieved from KERL
);
```

### CHOAM Consensus

Ephemeral keys used for block signing:

```java
// Generate block
Block block = new Block(
    blockNumber,
    transactions,
    previousBlockHash
);

// Sign with ephemeral key (per-view)
byte[] ephemeralPrivateKey = getEphemeralKeyForView(view);
block.sign(ephemeralPrivateKey);

// On view change: ephemeral key discarded, new one generated
```

### SQL-State Audit Trail

Identity recorded for all state changes:

```sql
-- State machine audit table
CREATE TABLE audit_log (
    id BIGINT PRIMARY KEY,
    block_number BIGINT,
    member_sai VARCHAR(255),      -- Who executed this
    transaction_hash BLOB,
    timestamp TIMESTAMP,
    action VARCHAR(100)
);

-- Every transaction records executing member's SAI
INSERT INTO audit_log(id, block_number, member_sai, transaction_hash, timestamp, action)
VALUES(1, 12345, 'EJxOV...mL1k', 0x..., NOW(), 'CREATE_TABLE');
```

---

## Troubleshooting

### Issue: "Identity Key Not Found"

**Symptoms:**
- Member cannot join cluster
- Logs show "SAI validation failed"
- KERL queries return empty

**Diagnosis:**
```bash
# 1. Check KERL storage
ls -lh /opt/delos/data/kerl.h2*

# 2. Query KERL directly
delos kerl query --sai "EJxOV...mL1k"
# Should return inception event and all subsequent events

# 3. Verify keystore
keytool -list -v -keystore member-id-keystore.jks -storepass PASSWORD
# Should show Member CN and valid certificate chain
```

**Resolution:**
```bash
# 1. If KERL missing: restore from backup
cp /backup/kerl.h2.backup /opt/delos/data/kerl.h2

# 2. If keystore corrupted: regenerate from private key
openssl pkcs12 -export -in member-id-cert.pem -inkey member-id-key.pem \
  -out member-id-keystore.p12 -passout pass:PASSWORD

# 3. Restart Delos
systemctl restart delos

# 4. Verify join
delos identity validate-view
```

### Issue: "Key Event Signature Invalid"

**Symptoms:**
- "Signature verification failed" in logs
- Member shunned from gossip
- Cannot reach consensus

**Diagnosis:**
```bash
# 1. Check for key rotation events
delos kerl show --sai "EJxOV...mL1k" | grep -i rotation

# 2. Verify current signing key matches KERL
delos identity status
# Shows: Current SAI, Current Key, Last Rotation Time

# 3. Compare with peer state
delos identity sync-check
# Should show all peers have same KERL state
```

**Resolution:**
```bash
# If key is stale (rotation not propagated):
#1. Publish rotation event again
delos identity rotate --new-key member-id-new-key.pem

# 2. Wait for quorum of witnesses to attest
delos identity status  # Monitor "Attestation Status"

# 3. Restart Delos after rotation is confirmed
systemctl restart delos
```

### Issue: "KERL Inconsistency Across Cluster"

**Symptoms:**
- Different nodes have different KERL state
- "State divergence" warnings
- Consensus hangs or regresses

**Diagnosis:**
```bash
# 1. Compare KERL hashes across all nodes
for node in node1 node2 node3; do
  echo "=== $node ==="
  ssh $node "delos kerl hash --sai <SAI>"
done

# 2. Find divergence point
delos kerl diff --sai <SAI> node1 node2
```

**Resolution:**
```bash
# 1. Identify authoritative KERL (usually node with most events)
delos kerl height --sai <SAI> --all-nodes | sort -rn | head -1

# 2. Restore from authoritative node
delos kerl restore \
  --source-node node1 \
  --sai <SAI> \
  --destination /opt/delos/data/kerl.h2

# 3. Verify consistency
delos kerl verify --sai <SAI>

# 4. If verification fails: contact cluster operator for manual recovery
```

---

## Operational Procedures

### Daily Health Checks

```bash
#!/bin/bash
# Daily KERI health check

echo "=== KERI Identity Health Check ==="

# 1. Verify all members have valid identities
delos identity validate-view
if [ $? -ne 0 ]; then
  echo "ERROR: Identity validation failed!"
  exit 1
fi

# 2. Check KERL consistency
delos kerl verify --all-sais
if [ $? -ne 0 ]; then
  echo "ERROR: KERL inconsistency detected!"
  exit 1
fi

# 3. Verify ephemeral keys are being rotated
delos ephemeral-keys check
if [ $? -ne 0 ]; then
  echo "ERROR: Ephemeral key rotation failed!"
  exit 1
fi

echo "✓ All KERI systems healthy"
exit 0
```

### Key Rotation Procedure

```bash
#!/bin/bash
# Rolling key rotation across cluster (zero-downtime)

NODES=(node1 node2 node3 node4 node5 node6 node7)
STAGGER_SECONDS=30  # Wait before rotating next node

for node in "${NODES[@]}"; do
  echo "=== Rotating keys on $node ==="

  # 1. Generate new key on target node
  ssh $node "openssl genpkey -algorithm ED25519 \
    -out /opt/delos/keys/member-id-new-key.pem"

  # 2. Create rotation event
  ssh $node "delos identity rotate \
    --old-key /opt/delos/keys/member-id-key.pem \
    --new-key /opt/delos/keys/member-id-new-key.pem"

  # 3. Wait for quorum attestation
  ssh $node "delos identity status | grep -q 'Attestation: OK'"
  if [ $? -ne 0 ]; then
    echo "ERROR: Attestation failed on $node!"
    exit 1
  fi

  # 4. Swap key files
  ssh $node "mv /opt/delos/keys/member-id-key.pem \
    /opt/delos/keys/member-id-key.pem.prev && \
    mv /opt/delos/keys/member-id-new-key.pem \
    /opt/delos/keys/member-id-key.pem"

  # 5. Restart Delos (brief downtime ~5 seconds)
  ssh $node "systemctl restart delos"

  # 6. Verify health after restart
  sleep 5
  ssh $node "delos identity validate-view" || {
    echo "ERROR: Identity validation failed after restart!"
    exit 1
  }

  # 7. Wait before rotating next node
  echo "Waiting ${STAGGER_SECONDS}s before next node..."
  sleep $STAGGER_SECONDS
done

echo "✓ Key rotation completed successfully"
```

---

## Security Best Practices

1. **Private Key Protection**
   - Store member private keys offline (air-gapped machine)
   - Use HSM or secure key storage for production
   - Never commit private keys to version control

2. **KERL Backup Strategy**
   - Backup KERL daily with encrypted storage
   - Store backups off-site (encrypted, duplicate on 3+ locations)
   - Test restore procedures quarterly

3. **Witness Attestation**
   - Use minimum 3 witness nodes for production (f+1 for Byzantine tolerance)
   - Distribute witness nodes across geography if possible
   - Monitor witness responsiveness

4. **Identity Audit Trail**
   - Enable audit logging for all identity operations
   - Review logs weekly for unauthorized access
   - Alert on multiple failed identity validations

5. **Key Rotation Schedule**
   - Rotate keys quarterly (90 days)
   - Document all rotations with timestamps and operators
   - Test key rotation in staging before production

---

## Related Documentation

- [KERI Specification](https://github.com/decentralized-identity/keri)
- [Stereotomy Module](../stereotomy/README.md)
- [Stereotomy Threat Model](../stereotomy/docs/THREAT_MODEL.md)
- [DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md) - Identity section
- [GLOSSARY.md](GLOSSARY.md) - KERI terminology
- [ADR-0002: KERI Implementation Architecture](adr/0002-keri-implementation-architecture.md)
- [Fireflies: Membership Service](../fireflies/README.md)

---

**Last Updated:** 2026-01-06
**Status:** Production-Ready
**Owner:** Delos Operations
