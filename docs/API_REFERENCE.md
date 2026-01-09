# Delos API Reference

**Public API catalog for building applications on Delos**

**Audience**: Application developers
**Sections**: 30+ core APIs organized by module
**Cross-references**: Module READMEs and test examples

---

## Overview

This document catalogs the primary public APIs for building applications on Delos. Most application development flows through a small set of core interfaces. This reference provides method signatures, parameters, and usage examples.

For complete implementation details, see the individual module READMEs linked throughout.

**Related**: [TRANSACTION_FLOW_GUIDE.md](TRANSACTION_FLOW_GUIDE.md), [IDE_SETUP.md](IDE_SETUP.md)

---

## CHOAM: Replicated State Machines

**Module**: `choam` | **Package**: `com.hellblazer.delos.choam`
**Purpose**: Committee-based consensus for replicated state machines

### CHOAM.Builder

**Class**: `com.hellblazer.delos.choam.CHOAM.Builder`
**Purpose**: Configure and create a CHOAM state machine replication instance

**Key Methods**:
```java
Builder setComm(Comm comm)
    // Set the communication layer (usually auto-configured)
    // Typically not called directly

Builder setContext(Context<Member> context)
    // Set the cluster membership context
    // Required: cluster knowledge, member info

Builder setLogRoot(File logRoot)
    // Set directory for consensus log persistence
    // Path: /opt/delos/data/choam/ (typical)
    // Required: must be writable

Builder setLabel(String label)
    // Optional label for this instance
    // Used in logging and metrics

Builder setMaxBatchByteSize(int maxBatchByteSize)
    // Maximum bytes per batch (default 1MB)
    // Tune for your workload

Builder setMaxBatchSize(int maxBatchSize)
    // Maximum transactions per batch (default 100)
    // Tune for latency vs throughput

CHOAM build()
    // Create and initialize the CHOAM instance
    // Throws: IOException if log directory issues

void start()
    // Start consensus (called on built instance)
    // Initiates leader election and gossip
```

**Properties**:
- **Thread-safe**: Builder is thread-safe
- **Configuration**: Must be called before build()
- **Stateful**: start() must be called before submitting

**Guarantees**:
- Transactions are ordered consistently across all replicas
- All correct replicas reach same state
- Byzantine nodes tolerated (up to f < n/3)

**Integration Notes**:
- Typically created during application startup
- Context must be initialized first (Fireflies membership)
- LogRoot directory must be durable storage (SSD recommended)

**Example**:
```java
// Create CHOAM instance
Context<Member> context = fireflies.getContext();
CHOAM choam = new CHOAM.Builder()
    .setContext(context)
    .setLogRoot(new File("/opt/delos/data/choam"))
    .setLabel("my-state-machine")
    .setMaxBatchSize(100)
    .build();

// Start consensus
choam.start();

// Use it (via Session - see below)
```

---

### Session

**Class**: `com.hellblazer.delos.choam.Session`
**Purpose**: Submit transactions to consensus

**Key Methods**:
```java
CompletableFuture<byte[]> submit(byte[] transaction, Digest txnId)
    // Submit transaction to consensus
    // txnId: unique transaction identifier
    // Returns: CompletableFuture with result bytes
    // Completes: when consensus reached and execution done
    // Exception: cancelled if Byzantine failure

Digest getTransactionId()
    // Get the transaction ID (unique identifier)
    // Used for logging and debugging

byte[] getPayload()
    // Get the transaction payload bytes
```

**Properties**:
- **Async**: Returns CompletableFuture (non-blocking)
- **Ordered**: Transactions ordered by consensus
- **Atomic**: All-or-nothing semantics

**Guarantees**:
- Transaction will eventually commit (or failure)
- Order respected across all replicas
- Byzantine nodes cannot cause reordering

**Integration Notes**:
- Typically wrapped in JDBC interface (SqlStateMachine)
- Don't create directly in application code (use JDBC)
- Transaction ID should be UUID or content digest

**Example** (low-level):
```java
// Get session from CHOAM
Session session = choam.createSession();

// Submit transaction
byte[] txnData = serializeTransaction(...);
Digest txnId = Digest.digest(DigestAlgorithm.DEFAULT, txnData);

CompletableFuture<byte[]> result = session.submit(txnData, txnId);

// Wait for completion
byte[] response = result.get();  // Blocks until committed
```

---

## SQL-State: Replicated Database

**Module**: `sql-state` | **Package**: `com.hellblazer.delos.sql_state`
**Purpose**: JDBC-accessible replicated SQL state machine

### SqlStateMachine

**Class**: `com.hellblazer.delos.sql_state.SqlStateMachine`
**Purpose**: Replicated SQL database via JDBC

**Key Methods**:
```java
Connection getConnection()
    // Get JDBC connection to replicated database
    // Returns: Standard java.sql.Connection
    // Usage: Standard JDBC API (Statement, ResultSet, etc.)
    // Note: Local connection to replica, not network remote

DataSource getDataSource()
    // Get DataSource for connection pooling
    // Returns: javax.sql.DataSource
    // Usage: Inject into application layer

void close()
    // Shutdown state machine gracefully
    // Flushes pending transactions
    // Closes database

void start()
    // Start state machine
    // Must be called before getting connections
```

**Properties**:
- **JDBC Standard**: Uses standard Connection/Statement/ResultSet
- **Deterministic**: Same input sequence → same output on all replicas
- **Replicated**: Changes replicated to all nodes via CHOAM
- **Local**: No network latency for reads/writes (local to replica)

**Guarantees**:
- ACID semantics (Atomicity, Consistency, Isolation, Durability)
- Serializable isolation level (highest)
- All replicas reach identical state

**Integration Notes**:
- Single DataSource per replica (not pooled across cluster)
- Typical: one SqlStateMachine per node
- Connect via JDBC as usual

**Example**:
```java
// Create and start state machine
SqlStateMachine state = new SqlStateMachine.Builder()
    .setChoam(choam)
    .setDataDir(new File("/opt/delos/data/state"))
    .build();
state.start();

// Get JDBC connection
try (Connection conn = state.getConnection();
     Statement stmt = conn.createStatement()) {

    // Standard SQL operations
    stmt.execute("CREATE TABLE users (id INT, name VARCHAR(255))");
    stmt.executeUpdate("INSERT INTO users VALUES (1, 'Alice')");

    try (ResultSet rs = stmt.executeQuery("SELECT * FROM users")) {
        while (rs.next()) {
            System.out.println(rs.getInt(1) + ": " + rs.getString(2));
        }
    }
}

// Shutdown
state.close();
```

---

## Cryptography: Identifiers and Signatures

**Module**: `cryptography` | **Package**: `com.hellblazer.delos.cryptography`
**Purpose**: Self-describing digests and signatures

### Digest

**Class**: `com.hellblazer.delos.cryptography.Digest`
**Purpose**: Algorithm-agnostic content identifier (hash)

**Key Methods**:
```java
static Digest create(byte[] encoded)
    // Create Digest from encoded bytes
    // encoded: [algorithm_tag:1][hash_bytes:32-64]

static Digest digest(DigestAlgorithm algo, byte[] content)
    // Hash content into digest
    // algo: algorithm to use (DigestAlgorithm.DEFAULT recommended)
    // Returns: Digest with algorithm embedded

byte[] toBytes()
    // Get encoded bytes (including algorithm tag)
    // Size: 33-65 bytes (1 byte tag + 32-64 bytes hash)

String toHex()
    // Get hex string representation
    // For JSON/storage/logging

DigestAlgorithm algorithm()
    // Get algorithm used

boolean equals(Object o)
    // Compare digests (algorithm-aware)
```

**Properties**:
- **Immutable**: Cannot be modified
- **Self-Describing**: Algorithm embedded in bytes
- **Comparable**: Implements Comparable<Digest>
- **Serializable**: Safe for JSON, storage

**Guarantees**:
- Same content → same digest (deterministic)
- Different algorithms → different digests
- Algorithm cannot be spoofed

**Integration Notes**:
- Use for transaction IDs, event IDs, content addresses
- Use DigestAlgorithm.DEFAULT (SHA-256) for new code

**Example**:
```java
// Create digest of content
Digest d = Digest.digest(DigestAlgorithm.DEFAULT, "hello".getBytes());

// Store hex representation in JSON
String json = "{\"contentId\": \"" + d.toHex() + "\"}";

// Later: recreate from stored bytes
Digest d2 = Digest.create(d.toBytes());

// Both are equal
assert d.equals(d2);
```

---

### Signer / Verifier

**Classes**: `com.hellblazer.delos.cryptography.Signer`, `Verifier`
**Purpose**: Digital signature creation and verification

**Key Methods**:
```java
// Signer interface
byte[] sign(byte[] content)
    // Create digital signature for content
    // Returns: signature bytes (64 bytes for Ed25519)

byte[] sign(byte[]... contents)
    // Sign multiple content blocks (concatenated)

// Verifier interface
boolean verify(byte[] signature, byte[] content)
    // Verify signature for content
    // Returns: true if valid, false if invalid
    // No exception thrown

void verifyThrows(byte[] signature, byte[] content)
    // Verify signature, throw if invalid
    // Throws: SignatureException if verification fails
```

**Properties**:
- **Deterministic**: Same content → same signature
- **Fast**: Ed25519 < 0.1ms per signature
- **Standard**: Uses RFC 8032 (Ed25519)

**Guarantees**:
- Existential unforgeability (can't forge signature)
- Non-repudiation (signer cannot deny)

**Example**:
```java
// Create signer from private key
PrivateKey key = getPrivateKey();
Signer signer = SignatureAlgorithm.DEFAULT.signer(key);

// Sign content
byte[] message = "transaction data".getBytes();
byte[] signature = signer.sign(message);

// Create verifier from public key
PublicKey pub = key.getPublic();
Verifier verifier = SignatureAlgorithm.DEFAULT.verifier(pub);

// Verify signature
if (verifier.verify(signature, message)) {
    System.out.println("Signature valid");
}
```

---

## Memberships: Cluster Context

**Module**: `memberships` | **Package**: `com.hellblazer.delos.memberships`
**Purpose**: Membership model and cluster context

### Context<T>

**Interface**: `com.hellblazer.delos.context.Context<T>`
**Purpose**: Abstraction representing cluster membership and node relationships

**Key Methods**:
```java
Member getMember()
    // Get this node's member info
    // Returns: Self in the cluster

Collection<Member> getMembers()
    // Get all members in the cluster
    // Includes self

Set<Member> getMajority()
    // Get majority quorum (f+1 members)
    // For Byzantine consensus

Member getMember(Digest memberID)
    // Look up member by ID
    // Returns: null if not found

int cardinality()
    // Get cluster size (number of members)

boolean isStale()
    // Check if context needs refresh
    // Called by gossip protocol
```

**Properties**:
- **Immutable**: Represents snapshot of membership
- **Quorum-Aware**: Knows majority size
- **Byzantine-Safe**: Supports 3f+1 model

**Integration Notes**:
- Created by Fireflies (membership service)
- Passed to CHOAM and Ethereal
- Refreshed periodically via gossip

**Example**:
```java
// Get context from membership service
Context<Member> context = fireflies.getContext();

// Get cluster info
System.out.println("Cluster size: " + context.cardinality());
System.out.println("Majority: " + context.getMajority().size());

// Check if Byzantine nodes can be tolerated
int f = (context.cardinality() - 1) / 3;
System.out.println("Byzantine fault tolerance: f=" + f);
```

---

## Stereotomy: KERI Identity

**Module**: `stereotomy` | **Package**: `com.hellblazer.delos.stereotomy`
**Purpose**: Key Event Receipt Infrastructure (KERI) for decentralized identity

### Stereotomy

**Class**: `com.hellblazer.delos.stereotomy.Stereotomy`
**Purpose**: KERI identity management operations

**Key Methods**:
```java
Identifier newIdentifier(String alias, SigningThreshold signing)
    // Create new KERI identity
    // alias: human-readable name
    // signing: threshold for signing keys
    // Returns: new Identifier

Identifier getIdentifier(String alias)
    // Look up existing identifier
    // Returns: null if not found

void rotate(Identifier identifier, SigningThreshold newThreshold)
    // Rotate keys for identity (change signing threshold)
    // Appends key rotation event to KERL
    // Makes previous keys invalid

KeyStateProof getKeyStateProof(Identifier identifier)
    // Get cryptographic proof of key state
    // For credential presentation
```

**Properties**:
- **Decentralized**: No central certificate authority
- **Verifiable**: All operations are cryptographically signed
- **Append-Only**: KERL is immutable log
- **Non-Repudiation**: Signer cannot deny operations

**Integration Notes**:
- KERI is foundational to identity in Delos
- Identifiers are used by Gorgoneion for node attestation
- KERL is replicated via Thoth DHT

**Example**:
```java
// Create KERI identity
Stereotomy kes = new Stereotomy(...);
Identifier node1 = kes.newIdentifier("node-1", defaultThreshold);

// Later: look up identity
Identifier found = kes.getIdentifier("node-1");

// Get key state for attestation
KeyStateProof proof = kes.getKeyStateProof(node1);
```

---

## Gorgoneion: Identity Attestation

**Module**: `gorgoneion` | **Package**: `com.hellblazer.delos.gorgoneion`
**Purpose**: Identity bootstrapping and credential validation

### Gorgoneion

**Class**: `com.hellblazer.delos.gorgoneion.Gorgoneion`
**Purpose**: Node attestation and credential validation

**Key Methods**:
```java
Credential issue(Identifier subject, List<Identifier> witnesses)
    // Issue credential for a subject
    // witnesses: nodes that attest to subject's identity
    // Returns: signed credential

boolean validateCredential(Credential credential)
    // Validate a credential
    // Checks: signatures, timestamp, no replay
    // Returns: true if valid

void attest(Credential credential)
    // Attest to a credential
    // Signs and broadcasts to other nodes
```

**Properties**:
- **Byzantine-Safe**: Requires threshold of witness signatures
- **Replay-Protected**: Prevents replay attacks
- **Timestamp-Validated**: Clock skew tolerance

**Integration Notes**:
- Used at cluster startup for node authentication
- Credentials are cached to prevent replay
- Timestamp tolerance: 5 seconds (default)

**Example**:
```java
// Create credential for node
Gorgoneion gorgoneion = ...;
Credential cred = gorgoneion.issue(nodeIdentifier, witnesses);

// Validate credential
if (gorgoneion.validateCredential(cred)) {
    System.out.println("Credential valid - node authenticated");
} else {
    System.out.println("Credential invalid - reject node");
}
```

---

## Tron: Finite State Machines

**Module**: `tron` | **Package**: `com.chiralbehaviors.tron`
**Purpose**: Type-safe FSM framework using Java Enums

### Fsm<T>

**Class**: `com.chiralbehaviors.tron.Fsm<T>`
**Purpose**: Type-safe state machine execution

**Key Methods**:
```java
T push(State<T> state)
    // Push state onto stack
    // Triggers entry action if defined

T pop()
    // Pop state from stack
    // Triggers exit action if defined

T transitionTo(State<T> newState)
    // Transition to new state
    // Triggers exit on current state, entry on new state

T transitionToSelf()
    // Self-transition (exit then entry)

State<T> getCurrentState()
    // Get current state

void reset()
    // Reset to initial state
```

**Properties**:
- **Type-Safe**: Compile-time safety via generics
- **Enum-Based**: States are Java Enum values
- **Action-Based**: Entry/exit actions via annotations
- **Stack-Based**: Supports hierarchical states via push/pop

**Integration Notes**:
- States are Java Enums with @Entry/@Exit annotations
- Context (generic T) is application-specific
- Typically used for workflow state machines

**Example**:
```java
// Define states as enum
enum OrderState {
    INITIAL,
    PENDING,
    SHIPPED,
    DELIVERED
}

// Create FSM
Fsm<Order> fsm = Fsm.construct(Order.class, OrderState.INITIAL);

// Transition between states
fsm.transitionTo(OrderState.PENDING);
fsm.transitionTo(OrderState.SHIPPED);
fsm.transitionTo(OrderState.DELIVERED);

// Get current state
assert fsm.getCurrentState() == OrderState.DELIVERED;
```

---

## Delphinius: Relation-Based Access Control

**Module**: `delphinius` | **Package**: `com.hellblazer.delos.delphinius`
**Purpose**: Google Zanzibar-style relation-based access control

### Oracle

**Class**: `com.hellblazer.delos.delphinius.Oracle`
**Purpose**: Query authorization relationships

**Key Methods**:
```java
boolean check(Principal principal, Permission permission, Resource resource)
    // Check if principal has permission on resource
    // Returns: true if authorized, false otherwise

Set<Resource> query(Principal principal, Permission permission)
    // Query all resources principal can access with permission
    // Returns: Set of authorized resources

Set<Principal> query(Permission permission, Resource resource)
    // Query all principals that can access resource
    // Returns: Set of authorized principals

void addRelation(Principal principal, Relation relation, Resource resource)
    // Add authorization relation
    // relation: "owner", "editor", "viewer", etc.
```

**Properties**:
- **Expressive**: Supports complex relationships
- **Scalable**: Efficient queries via ZanziBar algorithm
- **Consistent**: Strong consistency across replicas
- **Audit-Trail**: All operations logged

**Integration Notes**:
- Used for application-level authorization
- Relations stored in replicated SQL-State
- Typically queried for each API call

**Example**:
```java
// Create oracle
Oracle oracle = new Oracle(sqlStateMachine);

// Add relation (Alice owns document D1)
Document d1 = new Document("doc-1");
User alice = new User("alice@example.com");
oracle.addRelation(alice, Relation.OWNER, d1);

// Check authorization
if (oracle.check(alice, Permission.EDIT, d1)) {
    System.out.println("Alice can edit document");
}

// Query accessible resources
Set<Document> alicesDocs = oracle.query(alice, Permission.OWNER);
```

---

## Fireflies: Byzantine Membership

**Module**: `fireflies` | **Package**: `com.hellblazer.delos.fireflies`
**Purpose**: Byzantine fault-tolerant membership service

### View

**Class**: `com.hellblazer.delos.fireflies.View`
**Purpose**: Cluster membership view and gossip management

**Key Methods**:
```java
Context<Member> getContext()
    // Get current membership context
    // Updated by gossip protocol

void start()
    // Start gossip and membership service

void stop()
    // Stop gossip and membership service

long getRound()
    // Get current gossip round number

int getViewChanges()
    // Get number of view changes (Byzantine detection)
```

**Properties**:
- **Gossip-Based**: Uses probabilistic infection spreading
- **Byzantine-Safe**: Detects and excludes Byzantine nodes
- **Self-Healing**: Recovers from view changes
- **Adaptive**: Adjusts gossip rate based on cluster state

**Integration Notes**:
- Typically started early in application lifecycle
- Context passed to CHOAM and Ethereal
- Metrics available for monitoring

**Example**:
```java
// Create and start membership
View view = new View.Builder()
    .setMember(selfMember)
    .setMembers(clusterMembers)
    .build();
view.start();

// Get context (used by consensus)
Context<Member> context = view.getContext();

// Monitor Byzantine detection
long changes = view.getViewChanges();
if (changes > 0) {
    System.out.println("Byzantine nodes detected: " + changes);
}
```

---

## Ethereal: Byzantine Consensus

**Module**: `ethereal` | **Package**: `com.hellblazer.delos.ethereal`
**Purpose**: Aleph-BFT asynchronous atomic broadcast

### Ethereal

**Class**: `com.hellblazer.delos.ethereal.Ethereal`
**Purpose**: Byzantine consensus protocol execution

**Key Methods**:
```java
void start()
    // Start consensus protocol

void stop()
    // Stop consensus protocol

CompletableFuture<Long> multicast(byte[] content)
    // Multicast content to consensus
    // Returns: Future with block number when committed
    // Exception: Cancelled if Byzantine failure
```

**Properties**:
- **Asynchronous**: No clock assumptions
- **Byzantine-Safe**: 3f+1 nodes tolerate f Byzantine
- **Atomic**: All-or-nothing commitment
- **Fair**: All nodes' messages get included

**Guarantees**:
- Safety: All correct nodes commit same sequence
- Liveness: All correct messages eventually commit (1/3 not Byzantine)

**Integration Notes**:
- Usually called via CHOAM (not directly)
- Used by CHOAM to order transactions
- Metrics available for monitoring

---

## API Discovery

### Related Resources

**Module READMEs**:
- [Cryptography](../cryptography/README.md) - Full Digest/Signature API
- [CHOAM](../choam/README.md) - Committee consensus details
- [SQL-State](../sql-state/README.md) - State machine details
- [Fireflies](../fireflies/README.md) - Membership protocol
- [Tron](../tron/README.md) - FSM framework details
- [Delphinius](../delphinius/README.md) - Access control details

**Test Examples**:
- `src/test/java/com/hellblazer/delos/*/` - Working examples
- See specific test classes for API usage patterns

**Javadoc**:
Generate and browse Javadoc for implementation details:
```bash
./mvnw javadoc:javadoc
open target/site/apidocs/index.html
```

---

## Common Patterns

### Pattern: Initialize Cluster

```java
// 1. Start membership
View view = new View.Builder()
    .setMember(selfMember)
    .setMembers(allMembers)
    .build();
view.start();

// 2. Start consensus
Ethereal ethereal = new Ethereal.Builder()
    .setContext(view.getContext())
    .build();
ethereal.start();

// 3. Start state machine replication
CHOAM choam = new CHOAM.Builder()
    .setContext(view.getContext())
    .setLogRoot(logDir)
    .build();
choam.start();

// 4. Start replicated database
SqlStateMachine state = new SqlStateMachine.Builder()
    .setChoam(choam)
    .setDataDir(dataDir)
    .build();
state.start();

// Application is now ready
```

### Pattern: Submit Transaction

```java
try (Connection conn = state.getConnection();
     Statement stmt = conn.createStatement()) {

    // Standard JDBC - automatically goes through consensus
    stmt.executeUpdate("INSERT INTO users VALUES (?, ?)", id, name);
    // Blocks until consensus reached and execution complete
}
// Transaction is durable and replicated to all nodes
```

### Pattern: Query Read-Only Data

```java
try (Connection conn = state.getConnection();
     Statement stmt = conn.createStatement();
     ResultSet rs = stmt.executeQuery("SELECT * FROM users")) {

    while (rs.next()) {
        // Read from local replica (no consensus needed)
        int id = rs.getInt(1);
        String name = rs.getString(2);
    }
}
```

---

## Error Handling

### Common Exceptions

| Exception | Cause | Handling |
|-----------|-------|----------|
| `SQLException` | SQL error | Check message, fix query |
| `CancellationException` | Byzantine failure | Retry (may succeed after view change) |
| `TimeoutException` | Consensus timeout | Likely network partition, check cluster health |
| `IllegalStateException` | API misuse | Check state machine lifecycle (started?) |

### Example: Handle Failures

```java
try {
    stmt.executeUpdate("INSERT INTO users ...");
} catch (SQLException e) {
    // SQL error - likely application error
    log.error("SQL error", e);
    // Fix query and retry
} catch (CancellationException e) {
    // Byzantine failure detected
    log.warn("Byzantine failure, retrying", e);
    // Retry - cluster will recover
} catch (TimeoutException e) {
    // Consensus timeout
    log.error("Consensus timeout - possible partition", e);
    // Check cluster health
}
```

---

## API Versioning

**Stability**:
- ✅ **Stable** (production): CHOAM, SqlStateMachine, Digest, Context, Gorgoneion
- ⚠️ **Beta** (likely to change): Ethereal, Fireflies (low-level tuning)
- ◇ **Experimental** (changing): Thoth, Leyden

**Deprecation**: Deprecated APIs are marked with `@Deprecated` and documented in release notes.

---

## Support & Questions

**API Questions**:
1. Check module README for detailed documentation
2. Search test files for usage examples
3. Read Javadoc comments in source code
4. Check [TRANSACTION_FLOW_GUIDE.md](TRANSACTION_FLOW_GUIDE.md) for architectural context

**Bug Reports**: Include your API usage, expected behavior, and actual behavior.

---

Last Updated: 2026-01-09
Status: Phase 2.3 (API Reference)
Epic: Delos-aj2 (Developer Enablement)
Lines: 640+
