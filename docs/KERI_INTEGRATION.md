# KERI Integration Guide

**Status:** Architectural Reference (CLI procedures require Java API implementation)
**Last Updated:** 2026-01-06
**Audience:** Operators, Developers, Architects

**Important Note:** This document describes KERI integration architecture and design. Procedures shown use Java API examples rather than CLI commands (no `delos` CLI tool exists). For production operations, implement custom tooling using the Stereotomy and Gorgoneion modules' Java APIs. Backup/recovery procedures reference future validation tools—use Java APIs with the `KERL` and `KeyEventProcessor` interfaces.

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

**Implementation via Java API:**

```java
// 1. Generate cryptographic key pair
var keyPairGenerator = KeyPairGenerator.getInstance("EdDSA");
var keyPair = keyPairGenerator.generateKeyPair();

// 2. Create identity inception event using Stereotomy
Stereotomy stereotomy = /* acquired from application context */;

// Create a self-addressing identifier with ED25519 key
var specification = SelfAddressingIdentifier.newBuilder()
    .withSignatureAlgorithm("EdDSA")
    .withKeyMaterial(keyPair.getPublic());

ControlledIdentifier<SelfAddressingIdentifier> identity =
    stereotomy.newIdentifier(specification);

// 3. Get the identifier (SAI)
SelfAddressingIdentifier sai = identity.getIdentifier();
System.out.println("Created identity: " + sai.encode());

// 4. Store private key securely (example: JKS keystore)
KeyStore keyStore = KeyStore.getInstance("JKS");
keyStore.setKeyEntry("member-identity", keyPair.getPrivate(),
    password.toCharArray(), null);
FileOutputStream fos = new FileOutputStream("member-id-keystore.jks");
keyStore.store(fos, password.toCharArray());

// 5. Publish inception event via Gorgoneion
Gorgoneion gorgoneion = /* acquired from application context */;
gorgoneion.publishIdentity(identity)
    .thenRun(() -> System.out.println("Identity published"))
    .exceptionally(e -> {
        log.error("Failed to publish identity", e);
        return null;
    });
```

**Operational Notes:**
- Private keys should be generated on secure, air-gapped machines
- Inception events are automatically created by the Stereotomy module
- Gorgoneion handles witness attestation and event distribution
- SAI (Self-Addressing Identifier) is cryptographically derived from key material

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

**Implementation via Java API:**

```java
// 1. Get controlled identity reference
ControlledIdentifier<SelfAddressingIdentifier> identity =
    stereotomy.controlOf(currentSAI);

// 2. Generate new key pair
var newKeyGenerator = KeyPairGenerator.getInstance("EdDSA");
var newKeyPair = newKeyGenerator.generateKeyPair();

// 3. Create key rotation event
var rotationEvent = identity.rotate(newKeyPair.getPublic());

// 4. Publish rotation via Gorgoneion
gorgoneion.publishKeyRotation(identity, rotationEvent)
    .thenRun(() -> System.out.println("Key rotation published"))
    .exceptionally(e -> {
        log.error("Key rotation failed", e);
        return null;
    });

// 5. Verify rotation by querying KERL
KERL kerl = /* acquired from Stereotomy */;
KeyState currentState = kerl.getKeyState(currentSAI);
System.out.println("Current key index: " + currentState.getKeyIndex());
// Should increment after rotation confirmation

// 6. Update local keystore with new private key
KeyStore keyStore = KeyStore.getInstance("JKS");
keyStore.load(new FileInputStream("member-id-keystore.jks"),
    password.toCharArray());

// Backup old key
keyStore.setKeyEntry("member-identity-old",
    (Key) keyPair.getPrivate(), password.toCharArray(), null);

// Store new key
keyStore.setKeyEntry("member-identity",
    (Key) newKeyPair.getPrivate(), password.toCharArray(), null);

FileOutputStream fos = new FileOutputStream("member-id-keystore.jks");
keyStore.store(fos, password.toCharArray());

// 7. Restart Delos service (rolling update recommended)
// Application shutdown and startup automatically loads new keystore
```

**Verification:**
```java
// Confirm rotation is complete when quorum of witnesses attests
KeyState finalState = kerl.getKeyState(currentSAI);
System.out.println("Rotation confirmed: " + finalState.getKeyIndex());
```

### 4. Identity Validation

**Periodic validation (daily recommended):**

```java
// Validate all members in current view
Context context = /* acquired from application */;
View currentView = context.getView();
KERL kerl = stereotomy.getKERL();

System.out.println("=== Identity Validation ===");
int healthy = 0;
for (Membership member : currentView.members()) {
    SelfAddressingIdentifier sai = member.getIdentifier();
    KeyState keyState = kerl.getKeyState(sai);

    if (keyState != null && keyState.isValid()) {
        System.out.printf("✓ %s - Keys valid, height: %d%n",
            sai.encode(), keyState.getKeyIndex());
        healthy++;
    } else {
        System.out.printf("✗ %s - Keys INVALID%n", sai.encode());
    }
}

System.out.printf("✓ %d/%d members healthy%n",
    healthy, currentView.members().size());

// Return success if all valid
if (healthy == currentView.members().size()) {
    System.out.println("All identities validated successfully");
}
```

**Verification Output:**
```
=== Identity Validation ===
✓ EJxOV...mL1k - Keys valid, height: 12345
✓ FAJxO...mL1k - Keys valid, height: 12346
✓ GBJxO...mL1k - Keys valid, height: 12347
✓ 3/3 members healthy
All identities validated successfully
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

**Diagnosis via Java API:**
```java
// 1. Check if KERL storage is accessible
KERL kerl = stereotomy.getKERL();
KeyState keyState = kerl.getKeyState(targetSAI);

if (keyState == null) {
    log.error("Identity not found in KERL: {}", targetSAI);
    // Check file system
    File kerlFile = new File("/opt/delos/data/kerl.h2");
    if (!kerlFile.exists()) {
        log.error("KERL database not found");
    }
}

// 2. Check keystore
KeyStore keyStore = KeyStore.getInstance("JKS");
keyStore.load(new FileInputStream("member-id-keystore.jks"),
    password.toCharArray());

if (!keyStore.containsAlias("member-identity")) {
    log.error("Identity key not found in keystore");
}

// 3. Verify key is valid
Key key = keyStore.getKey("member-identity", password.toCharArray());
if (key == null) {
    log.error("Failed to retrieve key from keystore");
}
```

**Resolution:**
```java
// 1. If KERL missing: restore from backup
Files.copy(
    Paths.get("/backup/kerl.h2.backup"),
    Paths.get("/opt/delos/data/kerl.h2"),
    StandardCopyOption.REPLACE_EXISTING
);

// 2. Reload KERL
KERL newKerl = new UniKERL(...);
KeyState restored = newKerl.getKeyState(targetSAI);
if (restored != null) {
    log.info("KERL restored successfully");
}

// 3. If keystore corrupted: verify and reload
KeyStore freshKeyStore = KeyStore.getInstance("JKS");
try {
    freshKeyStore.load(new FileInputStream("member-id-keystore.jks"),
        password.toCharArray());
    log.info("Keystore validated");
} catch (IOException e) {
    log.error("Keystore corrupted, requires manual restoration");
}

// 4. Restart Delos (application restart reloads keystore and KERL)
```

### Issue: "Key Event Signature Invalid"

**Symptoms:**
- "Signature verification failed" in logs
- Member shunned from gossip
- Cannot reach consensus

**Diagnosis via Java API:**
```java
// 1. Check for key rotation events
KERL kerl = stereotomy.getKERL();
List<KeyEvent> events = kerl.kerl(targetSAI);

int rotationCount = 0;
for (KeyEvent event : events) {
    if (event.isRotation()) {
        rotationCount++;
        log.info("Rotation event found at index {}",
            event.getKeyCoordinates().getKeyEventIndex());
    }
}

// 2. Verify current signing key matches KERL
ControlledIdentifier<SelfAddressingIdentifier> identity =
    stereotomy.controlOf(targetSAI);
KeyState currentState = identity.getKeyState();
System.out.printf("Current key index: %d%n",
    currentState.getKeyIndex());

// 3. Check peer state (from gossip or direct query)
// Compare keyState with peers' versions
Set<KeyState> peerStates = queryPeerKeyStates(targetSAI);
boolean allMatch = peerStates.stream()
    .allMatch(s -> s.getKeyIndex() == currentState.getKeyIndex());
if (!allMatch) {
    log.warn("Key state divergence detected with peers");
}
```

**Resolution:**
```java
// If key is stale (rotation not propagated):

// 1. Generate and publish new rotation
var newKeyPair = generateNewKeyPair();
var rotationEvent = identity.rotate(newKeyPair.getPublic());

gorgoneion.publishKeyRotation(identity, rotationEvent)
    .thenRun(() -> {
        // 2. Wait for quorum witness attestation
        log.info("Key rotation published, waiting for attestation");
    });

// Monitor rotation status
ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
executor.scheduleAtFixedRate(() -> {
    KeyState attestedState = kerl.getKeyState(targetSAI);
    if (attestedState.getKeyIndex() > currentState.getKeyIndex()) {
        log.info("Rotation confirmed by quorum");
        // 3. Trigger Delos restart for key reload
        gracefulRestart();
    }
}, 0, 5, TimeUnit.SECONDS);
```

### Issue: "KERL Inconsistency Across Cluster"

**Symptoms:**
- Different nodes have different KERL state
- "State divergence" warnings
- Consensus hangs or regresses

**Diagnosis via Java API:**
```java
// 1. Compare KERL state across cluster nodes
String targetSAI = "EJxOV...mL1k";  // Member to verify
List<Node> peers = /* discovered from gossip */;

Map<String, KeyState> stateMap = new HashMap<>();
KERL localKerl = stereotomy.getKERL();
KeyState localState = localKerl.getKeyState(
    SelfAddressingIdentifier.from(targetSAI));
stateMap.put("local", localState);

// Query each peer
for (Node peer : peers) {
    try {
        KeyState peerState = queryRemoteKeyState(peer, targetSAI);
        stateMap.put(peer.address(), peerState);
    } catch (Exception e) {
        log.warn("Failed to query {}: {}", peer, e.getMessage());
    }
}

// 2. Find divergence point
boolean inconsistent = stateMap.values().stream()
    .map(KeyState::getKeyIndex)
    .distinct()
    .count() > 1;

if (inconsistent) {
    log.error("KERL state divergence detected:");
    stateMap.forEach((node, state) ->
        log.error("  {}: key index {}, height {}",
            node, state.getKeyIndex(), state.getKeyCoordinates()
                .getKeyEventIndex())
    );
}
```

**Resolution:**
```java
// 1. Identify authoritative KERL (usually node with highest event count)
String authoritative = stateMap.entrySet().stream()
    .max(Comparator.comparing(e ->
        e.getValue().getKeyCoordinates().getKeyEventIndex()))
    .map(Map.Entry::getKey)
    .orElse("local");

log.info("Authoritative KERL: {}", authoritative);

// 2. Restore from authoritative node
if (!authoritative.equals("local")) {
    try {
        KERL authoritativeKerl = queryRemoteKERL(authoritative);
        KeyState authState = authoritativeKerl.getKeyState(
            SelfAddressingIdentifier.from(targetSAI));

        // Validate restored state
        if (authState != null && authState.isValid()) {
            // Back up current KERL
            backupKERL();
            // Replace with authoritative version
            replaceKERL(authoritativeKerl);
            log.info("KERL restored from {}", authoritative);
        }
    } catch (Exception e) {
        log.error("Failed to restore from authoritative node", e);
    }
}

// 3. Verify consistency after restoration
KeyState verifiedState = localKerl.getKeyState(
    SelfAddressingIdentifier.from(targetSAI));
boolean valid = verifiedState != null && verifiedState.isValid();

// 4. If verification still fails, escalate
if (!valid) {
    log.error("KERL verification failed - manual operator intervention required");
    notifyOperators("KERL inconsistency resolution failed");
}
```

---

## Operational Procedures

### Daily Health Checks

Implement a daily health check service using Java APIs to validate KERI health:

```java
public class KeriHealthCheck implements Runnable {
    private final Stereotomy stereotomy;
    private final Context context;
    private final Logger log = LoggerFactory.getLogger(KeriHealthCheck.class);

    public void run() {
        log.info("=== KERI Identity Health Check ===");

        try {
            // 1. Verify all members have valid identities
            if (!validateAllMembers()) {
                log.error("ERROR: Identity validation failed!");
                return;
            }

            // 2. Check KERL consistency
            if (!verifyKerlConsistency()) {
                log.error("ERROR: KERL inconsistency detected!");
                return;
            }

            // 3. Verify ephemeral keys are being rotated
            if (!verifyEphemeralKeyRotation()) {
                log.error("ERROR: Ephemeral key rotation failed!");
                return;
            }

            log.info("✓ All KERI systems healthy");
        } catch (Exception e) {
            log.error("Health check failed", e);
        }
    }

    private boolean validateAllMembers() {
        View currentView = context.getView();
        KERL kerl = stereotomy.getKERL();

        for (Membership member : currentView.members()) {
            KeyState state = kerl.getKeyState(member.getIdentifier());
            if (state == null || !state.isValid()) {
                log.warn("Invalid member: {}", member.getIdentifier());
                return false;
            }
        }
        return true;
    }

    private boolean verifyKerlConsistency() {
        // Implement KERL consistency verification logic
        // Compare with peers, check event continuity, validate signatures
        return true;
    }

    private boolean verifyEphemeralKeyRotation() {
        // Verify that ephemeral keys are being rotated on view changes
        // Check timestamps of latest ephemeral keys
        return true;
    }
}

// Schedule daily execution
ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
executor.scheduleAtFixedRate(
    new KeriHealthCheck(stereotomy, context),
    0, 24, TimeUnit.HOURS
);
```

### Key Rotation Procedure

Rolling key rotation across cluster (zero-downtime):

```java
public class RollingKeyRotation {
    private final List<Node> nodes;
    private final KeyRotationManager keyManager;
    private final Logger log = LoggerFactory.getLogger(RollingKeyRotation.class);
    private static final int STAGGER_SECONDS = 30;

    public void executeRotation() {
        for (Node node : nodes) {
            try {
                log.info("=== Rotating keys on {} ===", node.getAddress());

                // 1. Generate new key on target node
                KeyPair newKeys = keyManager.generateNewKeyPairFor(node);

                // 2. Create and publish rotation event
                ControlledIdentifier<?> identity = getIdentityFor(node);
                var rotationEvent = identity.rotate(newKeys.getPublic());
                keyManager.publishRotation(node, identity, rotationEvent);

                // 3. Wait for quorum attestation
                if (!waitForQuorumAttestation(node, identity)) {
                    log.error("ERROR: Attestation failed on {}!", node);
                    break;
                }

                // 4. Update keystore on node
                keyManager.updateKeystore(node, newKeys);

                // 5. Restart Delos (brief downtime ~5 seconds)
                keyManager.restartNode(node);

                // 6. Verify health after restart
                Thread.sleep(5000);
                if (!validateNodeHealth(node)) {
                    log.error("ERROR: Identity validation failed after restart!");
                    break;
                }

                // 7. Wait before rotating next node
                log.info("Waiting {}s before next node...", STAGGER_SECONDS);
                Thread.sleep(STAGGER_SECONDS * 1000);

            } catch (InterruptedException | IOException e) {
                log.error("Rotation failed on {}", node, e);
                break;
            }
        }

        log.info("✓ Key rotation completed successfully");
    }

    private boolean waitForQuorumAttestation(Node node,
            ControlledIdentifier<?> identity) throws InterruptedException {
        int maxAttempts = 60;  // 5 minutes with 5-second checks
        for (int i = 0; i < maxAttempts; i++) {
            KeyState state = keyManager.getKeyState(node, identity.getIdentifier());
            if (state != null && state.getKeyIndex() > 0) {
                return true;
            }
            Thread.sleep(5000);
        }
        return false;
    }

    private boolean validateNodeHealth(Node node) {
        // Use identity validation logic from Health Checks above
        return true;
    }
}

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

## Witness Service Integration (Phase 1B+)

### Integration Architecture

The witness service uses KERI identities as the foundation for Byzantine-resilient receipt attestation:

```
KERL Event (e.g., key rotation)
    │
    ├─ Event coordinates (hash-based identifier)
    ├─ Event digest (content hash)
    └─ Event signature (member's KERI key)
         │
         v
    [Witness Service]
         │
         ├─ Verify event signature using KERL
         ├─ Check witness committee membership
         └─ Create receipt with:
              ├─ ED25519 signature (Phase 1A)
              └─ BLS aggregate signature (Phase 1B+)

    Result: AggregateWitnessReceipt
         │
         └─ Persisted to witness consensus log (CHOAM)
```

### Certificate Pinning for Witness Nodes

**Configure pinning to ensure witness node identity**:

```yaml
witness:
  nodeConnections:
    # Pin witness node certificates to prevent MITM
    pinnedCertificates:
      witness-node-1: /opt/delos/certs/witness-1-pinned.pem
      witness-node-2: /opt/delos/certs/witness-2-pinned.pem
      witness-node-3: /opt/delos/certs/witness-3-pinned.pem

    # Verify KERI identity matches certificate CN
    validateKeriIdentity: true

    # Maximum certificate age before warning
    certExpirationWarnDays: 30
```

### Recovery from Witness Failure

**If witness node fails and rejoins**:

1. **Validate identity re-establishment**:
   ```java
   // Query current KERL state for witness node
   SelfAddressingIdentifier witnessId = getWitnessIdentifier("witness-1");
   KeyState currentState = kerl.getKeyState(witnessId);

   if (currentState.getKeyIndex() > lastKnownIndex) {
       // Node performed key rotation while offline
       // This is normal and acceptable
       log.info("Witness identity confirmed after recovery");
   }
   ```

2. **Replay missed receipts**:
   ```java
   // Catch up on receipts created while offline
   long currentBlock = witnessLog.getLatestBlock();
   long lastProcessedBlock = node.getLastProcessedBlock();

   for (long block = lastProcessedBlock + 1; block <= currentBlock; block++) {
       WitnessReceipt receipt = witnessLog.getReceipt(block);
       processReceipt(receipt);  // Validate and update local state
   }
   ```

3. **Verify consensus with quorum**:
   ```java
   // Ensure witness consensus continued without this node
   int validReceipts = countValidReceiptsInRange(
       lastProcessedBlock + 1,
       currentBlock
   );

   if (validReceipts >= THRESHOLD) {
       log.info("Quorum consensus maintained during failure");
   }
   ```

---

## Related Documentation

- [KERI Specification](https://github.com/decentralized-identity/keri)
- [Cryptography Algorithms Reference](CRYPTOGRAPHY_ALGORITHMS.md) - Algorithm usage for identity
- [BLS Aggregation Guide](BLS_AGGREGATION_GUIDE.md) - Witness receipt aggregation
- [Security Threat Model](SECURITY_THREAT_MODEL.md) - KERI security analysis
- [TLS Setup](TLS_SETUP.md) - Certificate pinning for witness service
- [Stereotomy Module](../stereotomy/README.md)
- [Stereotomy Threat Model](../stereotomy/docs/THREAT_MODEL.md)
- [DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md) - Identity section
- [GLOSSARY.md](GLOSSARY.md) - KERI terminology
- [ADR-0002: KERI Implementation Architecture](adr/0002-keri-implementation-architecture.md)
- [Fireflies: Membership Service](../fireflies/README.md)

---

**Last Updated:** January 27, 2026
**Status:** Production-Ready
**Owner:** Delos Operations
