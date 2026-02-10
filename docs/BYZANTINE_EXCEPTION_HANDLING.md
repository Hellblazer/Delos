# Byzantine Exception Handling Strategy

**Status**: Production Standard
**Last Updated**: 2026-02-08
**Applies To**: All Delos modules (CHOAM, Ethereal, Fireflies, SQL-State, Gorgoneion)

## Overview

This document defines the canonical Byzantine exception handling strategy for Delos distributed systems. Byzantine faults require special exception handling to maintain consensus, detect malicious actors, and ensure system safety.

## Core Principles

### 1. **Graceful Degradation** (Primary Strategy)
**When to use**: RPC failures, gossip protocol, message processing

**Pattern**:
```java
try {
    processMessage(msg);
} catch (IllegalStateException e) {
    // Byzantine behavior detected (equivocation, invalid signature, etc.)
    log.warn("Byzantine behavior detected from {}: {}", memberId, e.getMessage());
    // Continue processing - do NOT propagate exception up
    // RPC continues, gossip continues, cluster remains operational
}
```

**Rationale**:
- Byzantine nodes may intentionally throw exceptions to DoS honest nodes
- Propagating exceptions up allows Byzantine nodes to halt honest nodes
- Logging provides observability without compromising availability
- 3f+1 honest nodes maintain consensus despite f Byzantine nodes

**Examples**:
- `ChRbcGossip.Terminal.update()` - equivocation detection (lines 153-165)
- Gossip anti-entropy - continue despite bad messages
- Membership protocol - isolate Byzantine members without halting

---

### 2. **Deterministic Normalization** (Consensus Safety)
**When to use**: Exceptions that become part of transaction results or replicated state

**Pattern**:
```java
try {
    result = executeTransaction(txn);
} catch (Exception e) {
    // CRITICAL: Normalize exception before storing in transaction result
    Throwable normalized = ExceptionNormalizer.normalize(e);
    return TransactionResult.failure(normalized);
}
```

**Rationale**:
- Non-deterministic exceptions cause state divergence across replicas
- Thread IDs, timestamps, memory addresses vary per replica
- State divergence breaks consensus and causes Byzantine failure
- Normalization ensures identical exception strings on all replicas

**Sources of Non-Determinism**:
| Source | Example | Normalized To |
|--------|---------|---------------|
| Thread IDs | `Thread-12` | `Thread-*` |
| Memory addresses | `@3e25a5` | `@*` |
| Timestamps (ISO 8601) | `2024-01-01T10:00:00.123Z` | `[timestamp]` |
| Epoch milliseconds | `1704067200000` | `[timestamp]` |
| Absolute paths | `/home/user/data` | `[path]` |

**Implementation**: `sql-state/ExceptionNormalizer.java`

**Examples**:
- `SqlStateMachine.execute()` - SQL execution errors
- `CHOAM` transaction results - business logic exceptions
- State machine checkpoints - error recovery

---

### 3. **Violation Classification and Tracking**
**When to use**: Byzantine fault detection, monitoring, diagnostics

**Pattern**:
```java
ValidationResult result = validator.validate(block);
if (!result.isValid()) {
    ByzantineViolation violation = bftValidator.mapViolation(result);
    if (violation != null) {
        log.error("Byzantine violation detected: type={}, member={}, details={}",
                  violation.getType(), violation.getMemberId(), violation.getDetails());
        // Take action based on violation type
        switch (violation.getType()) {
            case SIGNATURE_FORGERY -> isolateMember(violation.getMemberId());
            case EQUIVOCATION -> recordDoubleVote(violation.getMemberId());
            case TIMING_ANOMALY -> adjustTimeout(violation.getMemberId());
        }
    }
}
```

**Violation Types** (`ByzantineViolationType`):
- `SIGNATURE_FORGERY` - Invalid cryptographic signature
- `EQUIVOCATION` - Conflicting messages (double voting, forking)
- `TIMING_ANOMALY` - Message timing violations (early heartbeat, late precommit)
- `THRESHOLD_BYPASS` - Attempting to bypass T-of-N signature requirements
- `STATE_FORK` - Conflicting state claims
- `REPLAY_ATTACK` - Message replay detected

**Thread Safety**: All violation tracking must be thread-safe (concurrent block validation)

**Implementation**: `choam/BFTValidator.java`, `choam/DefaultBFTValidator.java`

**Examples**:
- `CHOAM` block validation - classify validation failures
- Witness service - BLS signature validation
- Fireflies - membership protocol violations

---

### 4. **Fail-Fast** (Safety Critical Paths)
**When to use**: Cryptographic failures, state corruption, invariant violations

**Pattern**:
```java
if (!signature.verify(publicKey, message)) {
    throw new SecurityException("Signature verification failed for member: " + memberId);
}

if (state.getEpoch() > currentEpoch + 1) {
    throw new IllegalStateException("State epoch violation: state=" + state.getEpoch() +
                                    ", current=" + currentEpoch);
}
```

**Rationale**:
- Some failures indicate fundamental safety violations
- Fail-fast prevents silent corruption
- Allows detection and remediation at higher layers

**When NOT to use**:
- RPC failures (use graceful degradation)
- Gossip protocol (use graceful degradation)
- User input validation (use domain-specific error handling)

**Examples**:
- Cryptographic signature verification failures
- State machine invariant violations
- Configuration validation (startup time)

---

## Decision Tree

```
┌─────────────────────────────────────┐
│ Exception Occurs                    │
└──────────────┬──────────────────────┘
               │
               ▼
    ┌──────────────────────┐
    │ Is it part of        │ YES ──► Use Deterministic Normalization
    │ transaction result?  │         (ExceptionNormalizer.normalize)
    └──────────┬───────────┘
               │ NO
               ▼
    ┌──────────────────────┐
    │ Is it RPC/gossip/    │ YES ──► Use Graceful Degradation
    │ message processing?  │         (log WARN, continue)
    └──────────┬───────────┘
               │ NO
               ▼
    ┌──────────────────────┐
    │ Is it Byzantine      │ YES ──► Use Violation Classification
    │ validation failure?  │         (BFTValidator.mapViolation)
    └──────────┬───────────┘
               │ NO
               ▼
    ┌──────────────────────┐
    │ Is it safety-        │ YES ──► Use Fail-Fast
    │ critical failure?    │         (throw exception)
    └──────────┬───────────┘
               │ NO
               ▼
    ┌──────────────────────┐
    │ Use standard Java    │
    │ exception handling   │
    └──────────────────────┘
```

---

## Anti-Patterns

### ❌ **Anti-Pattern 1: Propagating RPC Exceptions**
```java
// WRONG - Byzantine node can DoS honest nodes
try {
    remoteNode.processMessage(msg);
} catch (StatusRuntimeException e) {
    throw e;  // ❌ Byzantine node causes honest node to fail
}
```

```java
// CORRECT - Log and continue
try {
    remoteNode.processMessage(msg);
} catch (StatusRuntimeException e) {
    log.warn("RPC failed for {}: {}", memberId, e.getMessage());
    // Continue - 3f+1 honest nodes maintain consensus
}
```

---

### ❌ **Anti-Pattern 2: Non-Deterministic Exception Messages**
```java
// WRONG - State divergence across replicas
catch (SQLException e) {
    String msg = "Error at " + System.currentTimeMillis() + ": " + e.getMessage();
    return TransactionResult.failure(new SQLException(msg));
}
```

```java
// CORRECT - Deterministic normalization
catch (SQLException e) {
    Throwable normalized = ExceptionNormalizer.normalize(e);
    return TransactionResult.failure(normalized);
}
```

---

### ❌ **Anti-Pattern 3: Silent Byzantine Failures**
```java
// WRONG - Byzantine violation not logged or tracked
if (!signature.verify()) {
    return null;  // ❌ Silent failure, no observability
}
```

```java
// CORRECT - Classify and track violation
ValidationResult result = validator.validate(block);
if (!result.isValid()) {
    ByzantineViolation violation = bftValidator.mapViolation(result);
    log.error("Byzantine violation: {}", violation);
    metrics.recordViolation(violation.getType());
}
```

---

### ❌ **Anti-Pattern 4: Overly Aggressive Fail-Fast**
```java
// WRONG - Fail-fast in RPC processing
try {
    process(gossipMessage);
} catch (IllegalArgumentException e) {
    throw new RuntimeException(e);  // ❌ Byzantine DoS vector
}
```

```java
// CORRECT - Graceful degradation for message processing
try {
    process(gossipMessage);
} catch (IllegalArgumentException e) {
    log.warn("Invalid gossip message from {}: {}", sender, e.getMessage());
    // Continue - don't let Byzantine nodes halt us
}
```

---

## Module-Specific Guidance

### CHOAM (State Machine Replication)
- **Transaction execution**: Deterministic normalization (ExceptionNormalizer)
- **Block validation**: Violation classification (BFTValidator)
- **RPC errors**: Graceful degradation
- **State invariant violations**: Fail-fast

### Ethereal (Consensus Protocol)
- **Gossip processing**: Graceful degradation (ChRbcGossip pattern)
- **Equivocation**: Log WARN, continue RPC
- **Signature verification**: Fail-fast
- **Timing anomalies**: Violation classification + continue

### Fireflies (Membership Protocol)
- **Anti-entropy**: Graceful degradation
- **Invalid notes**: Log WARN, continue gossip
- **Fireflies shunning**: Violation classification
- **Cryptographic failures**: Fail-fast

### SQL-State (Replicated SQL)
- **SQL execution errors**: Deterministic normalization (CRITICAL)
- **Constraint violations**: Normalize + store in transaction result
- **JVM validation**: Fail-fast (startup time)

### Gorgoneion (Identity Bootstrapping)
- **Phase-aware recovery**: Graceful degradation with BootstrapResult variants
- **Signature validation**: Fail-fast
- **Timeout failures**: Graceful degradation (PartialSuccess)

---

## Testing Requirements

### 1. **Byzantine Fault Injection**
Every module MUST have tests that inject Byzantine faults and validate exception handling:

```java
@Test
public void testEquivocationDetection() {
    // Inject equivocating message
    faultInjector.injectEquivocation(byzantineNode);

    // Verify graceful degradation
    cluster.execute(transaction);
    assertThat(cluster.consensusAchieved()).isTrue();

    // Verify violation tracking
    assertThat(bftValidator.getViolationCount(EQUIVOCATION)).isEqualTo(1);
}
```

### 2. **Determinism Validation**
Transaction result exceptions MUST be validated for determinism:

```java
@Test
public void testExceptionDeterminism() {
    // Execute same transaction on 4 replicas
    List<TransactionResult> results = replicas.stream()
        .map(r -> r.execute(transaction))
        .collect(toList());

    // All exception messages must be identical
    Set<String> exceptionMessages = results.stream()
        .map(r -> r.getError().getMessage())
        .collect(toSet());

    assertThat(exceptionMessages).hasSize(1)  // All identical
        .as("Exception messages must be deterministic across replicas");
}
```

### 3. **Graceful Degradation Validation**
RPC failures MUST NOT halt honest nodes:

```java
@Test
public void testByzantineNodeDoesNotHaltCluster() {
    // One Byzantine node throws exceptions
    cluster.setByzantine(node3, () -> {
        throw new IllegalStateException("Byzantine fault");
    });

    // Cluster continues operating
    for (int i = 0; i < 100; i++) {
        cluster.execute(transaction);
    }

    // 3 of 4 nodes maintain consensus
    assertThat(cluster.getSuccessRate()).isGreaterThan(0.75);
}
```

---

## References

### Implementation Files
- `sql-state/src/main/java/com/hellblazer/delos/state/ExceptionNormalizer.java`
- `choam/src/main/java/com/hellblazer/delos/choam/validation/BFTValidator.java`
- `ethereal/src/main/java/com/hellblazer/delos/ethereal/memberships/ChRbcGossip.java`
- `gorgoneion-client/src/main/java/com/hellblazer/delos/gorgoneion/client/BootstrapResult.java`

### Test Files
- `ethereal/src/test/java/com/hellblazer/delos/ethereal/ByzantineAttackTest.java`
- `witness-service/src/test/java/com/hellblazer/delos/witness/validation/*`
- `gorgoneion/src/test/java/com/hellblazer/delos/gorgoneion/GorgoneionBftConsensusFailureTest.java`

### Documentation
- `docs/TESTING_GUIDE.md` - Byzantine fault testing
- `docs/CODE_QUALITY_STANDARDS.md` - Exception handling standards
- `choam/docs/ARCHITECTURE.md` - BFT validator architecture

---

## Revision History

| Date | Version | Author | Changes |
|------|---------|--------|---------|
| 2026-02-08 | 1.0 | Initial | Created comprehensive Byzantine exception handling strategy |

