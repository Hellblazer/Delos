# Phase 1B-3: Genesis Migration Boundary Implementation Plan

## Executive Summary

Phase 1B-3 implements the infrastructure for production deployment of BLS receipt aggregation, specifically the "genesis" migration boundary where BLS becomes the canonical signature format. This phase builds on completed Phase 1B-2-C (Compatibility Layer, 480 tests passing) and delivers:

1. **Committee BLS Key Infrastructure**: Key storage, registration, and Proof of Possession validation
2. **Genesis Transition Mechanics**: Protocol for DUAL -> BLS_ONLY phase transition
3. **Operational Integration**: Metrics, health checks, Byzantine detection, Fireflies shunning
4. **Production Readiness**: Comprehensive testing, rollback procedures, operations runbook

**Estimated Effort**: 13-16 days (104 hours + 20% buffer)
**Tests**: 95 new tests
**Dependencies**: Phase 1B-2-C complete (all prerequisites met)
**Critical Deliverable**: `WitnessContext.getCommitteeBLSKeys()` implementation

---

## What "Genesis" Means in This Context

The term "genesis" in Phase 1B-3 does **NOT** refer to creating a new blockchain genesis block. Instead, it refers to:

1. **Migration Boundary**: The first epoch where BLS becomes the canonical signature format
2. **Phase Transition**: The transition from DUAL phase to BLS_ONLY phase
3. **Production Milestone**: The point at which Ed25519 signatures are no longer accepted for new receipts

Historical receipts with Ed25519 signatures remain valid and queryable. Only NEW receipts must use BLS format after genesis transition.

---

## Architecture Overview

### Component Diagram

```
                     +---------------------------+
                     |    WitnessServiceImpl     |
                     +---------------------------+
                                |
                                v
+--------------------+    +------------------------+
| KeyRegistration    |    | ReceiptCompatibility   |
| Service (gRPC)     |    | Layer (Phase 1B-2-C)   |
+--------------------+    +------------------------+
         |                         |
         v                         v
+--------------------+    +------------------------+
| CommitteeBLS       |    | MigrationState         |
| KeyStore           |    | Tracker                |
+--------------------+    +------------------------+
         |                         |
         +------------+------------+
                      |
                      v
              +---------------+
              | WitnessContext|
              | .getCommittee |
              | BLSKeys()     |
              +---------------+
                      |
                      v
          +-----------------------+
          | GenesisTransition     |
          | Coordinator           |
          +-----------------------+
                      |
        +-------------+-------------+
        |             |             |
        v             v             v
+-------------+ +------------+ +---------------+
| Fireflies   | | CHOAM      | | Operations    |
| Shunning    | | Recorder   | | Monitoring    |
+-------------+ +------------+ +---------------+
```

### Phase Transition State Machine

```
+----------+     BLS keys       +----------+     All keys +     +-----------+
|   INIT   | ----------------> |   DUAL   | ------------> | BLS_ONLY  |
| Ed25519  |  registered +     |  Both    |  canAdvance   | BLS only  |
|   only   |  config flag      | formats  |  + trigger    | (terminal)|
+----------+                   +----------+               +-----------+
                                    ^                          ^
                                    |                          |
                           Manual override            Manual override
                           (admin gRPC)               (admin gRPC)
```

---

## Sub-Phase Structure

### Phase 1B-3-A: Committee BLS Key Infrastructure (3 days, 30 tests)

**Goal**: Implement key storage, registration, and the critical `getCommitteeBLSKeys()` method

#### Deliverables

##### 1. CommitteeBLSKeyStore.java (~200 lines)

**Location**: `witness-service/src/main/java/com/hellblazer/delos/witness/committee/CommitteeBLSKeyStore.java`

```java
package com.hellblazer.delos.witness.committee;

import com.hellblazer.delos.cryptography.bls.BLSPublicKey;
import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Storage for committee member BLS public keys.
 * Thread-safe implementation for concurrent access during receipt validation.
 */
public interface CommitteeBLSKeyStore {

    /**
     * Register a new BLS public key for a committee member.
     *
     * @param registration Key registration with PoP
     * @return true if registration successful, false if key already exists
     * @throws InvalidProofOfPossessionException if PoP validation fails
     */
    boolean registerKey(BLSKeyRegistration registration) throws InvalidProofOfPossessionException;

    /**
     * Get BLS public key for a committee member.
     *
     * @param memberId Committee member identifier
     * @return Optional containing the public key if registered
     */
    Optional<BLSPublicKey> getPublicKey(Identifier memberId);

    /**
     * Get BLS public keys for multiple committee members.
     * Filters out members without registered keys.
     *
     * @param memberIds Set of committee member identifiers
     * @return List of public keys for members with registered keys
     */
    List<BLSPublicKey> getPublicKeys(Set<Identifier> memberIds);

    /**
     * Check if a member has a registered BLS key.
     *
     * @param memberId Committee member identifier
     * @return true if key is registered
     */
    boolean hasKey(Identifier memberId);

    /**
     * Remove a member's BLS key (for key rotation or member removal).
     *
     * @param memberId Committee member identifier
     * @return true if key was removed, false if not found
     */
    boolean removeKey(Identifier memberId);

    /**
     * Get count of registered keys.
     *
     * @return Number of registered BLS keys
     */
    int keyCount();

    /**
     * Get all registered member IDs (for transition readiness check).
     *
     * @return Set of member IDs with registered keys
     */
    Set<Identifier> registeredMembers();
}
```

##### 2. BLSKeyRegistration.java (~80 lines)

**Location**: `witness-service/src/main/java/com/hellblazer/delos/witness/committee/BLSKeyRegistration.java`

```java
package com.hellblazer.delos.witness.committee;

import com.hellblazer.delos.cryptography.bls.BLSPublicKey;
import com.hellblazer.delos.cryptography.bls.ProofOfPossession;
import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.time.Instant;
import java.util.Objects;

/**
 * BLS key registration record with Proof of Possession.
 * Immutable record for thread-safe sharing.
 */
public record BLSKeyRegistration(
    Identifier memberId,
    BLSPublicKey publicKey,
    ProofOfPossession proofOfPossession,
    long registrationEpoch,
    Instant registrationTime
) {
    public BLSKeyRegistration {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        Objects.requireNonNull(publicKey, "publicKey cannot be null");
        Objects.requireNonNull(proofOfPossession, "proofOfPossession cannot be null");
        Objects.requireNonNull(registrationTime, "registrationTime cannot be null");
        if (registrationEpoch < 0) {
            throw new IllegalArgumentException("registrationEpoch must be >= 0");
        }
    }

    /**
     * Create registration for current epoch.
     */
    public static BLSKeyRegistration create(
        Identifier memberId,
        BLSPublicKey publicKey,
        ProofOfPossession proofOfPossession,
        long currentEpoch
    ) {
        return new BLSKeyRegistration(
            memberId,
            publicKey,
            proofOfPossession,
            currentEpoch,
            Instant.now()
        );
    }
}
```

##### 3. ProofOfPossessionValidator.java (~100 lines)

**Location**: `witness-service/src/main/java/com/hellblazer/delos/witness/committee/ProofOfPossessionValidator.java`

```java
package com.hellblazer.delos.witness.committee;

import com.hellblazer.delos.cryptography.bls.BLSOperations;
import com.hellblazer.delos.cryptography.bls.BLSPublicKey;
import com.hellblazer.delos.cryptography.bls.ProofOfPossession;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Validates Proof of Possession for BLS key registration.
 * Prevents key impersonation attacks.
 */
public final class ProofOfPossessionValidator {

    private static final Logger log = LoggerFactory.getLogger(ProofOfPossessionValidator.class);

    private final BLSOperations blsOperations;

    public ProofOfPossessionValidator(BLSOperations blsOperations) {
        this.blsOperations = Objects.requireNonNull(blsOperations, "blsOperations cannot be null");
    }

    /**
     * Validate Proof of Possession for a key registration.
     *
     * PoP proves that the registrant possesses the private key corresponding
     * to the public key being registered. This prevents Eve from registering
     * Alice's public key under Eve's identifier.
     *
     * @param registration Key registration to validate
     * @return ValidationResult indicating success or failure reason
     */
    public ValidationResult validate(BLSKeyRegistration registration) {
        Objects.requireNonNull(registration, "registration cannot be null");

        try {
            // PoP is a signature over the member's identifier using the BLS private key
            var message = registration.memberId().getDigest().getBytes();
            var isValid = blsOperations.verifyProofOfPossession(
                registration.publicKey(),
                registration.proofOfPossession(),
                message
            );

            if (isValid) {
                log.debug("PoP validation successful for member: {}", registration.memberId());
                return new ValidationResult.Valid(registration);
            } else {
                log.warn("PoP validation failed for member: {}", registration.memberId());
                return new ValidationResult.Invalid("Proof of Possession signature verification failed");
            }

        } catch (Exception e) {
            log.error("PoP validation error for member: {}", registration.memberId(), e);
            return new ValidationResult.Error("PoP validation exception: " + e.getMessage());
        }
    }

    /**
     * Validation result sealed interface.
     */
    public sealed interface ValidationResult {
        record Valid(BLSKeyRegistration registration) implements ValidationResult {}
        record Invalid(String reason) implements ValidationResult {}
        record Error(String message) implements ValidationResult {}
    }
}
```

##### 4. WitnessContext.getCommitteeBLSKeys() Implementation (~30 lines)

**Location**: Update `witness-service/src/main/java/com/hellblazer/delos/witness/WitnessContext.java`

```java
// Add field
private final CommitteeBLSKeyStore committeeBLSKeyStore;

// Update constructor to accept key store
public WitnessContext(Context<?> firefliesContext, WitnessParameters parameters,
                      DigestAlgorithm digestAlgorithm, CommitteeBLSKeyStore keyStore) {
    // ... existing initialization ...
    this.committeeBLSKeyStore = Objects.requireNonNull(keyStore, "keyStore cannot be null");
}

/**
 * Get the committee BLS public keys.
 * Returns keys for all current committee members with registered BLS keys.
 *
 * @return List of BLS public keys for committee members
 */
public List<BLSPublicKey> getCommitteeBLSKeys() {
    lock.readLock().lock();
    try {
        return committeeBLSKeyStore.getPublicKeys(currentMembers);
    } finally {
        lock.readLock().unlock();
    }
}

/**
 * Get the committee BLS public keys for specific event.
 * Uses event-specific committee selection.
 *
 * @param eventCoordinates Event to get committee for
 * @return List of BLS public keys for the event's committee
 */
public List<BLSPublicKey> getCommitteeBLSKeys(EventCoordinates eventCoordinates) {
    var committee = selectCommittee(eventCoordinates);
    return committeeBLSKeyStore.getPublicKeys(
        committee.stream()
            .map(this::toIdentifier)
            .collect(Collectors.toSet())
    );
}
```

##### 5. KeyRegistrationService.java (~150 lines)

**Location**: `witness-service/src/main/java/com/hellblazer/delos/witness/committee/KeyRegistrationService.java`

```java
package com.hellblazer.delos.witness.committee;

import com.hellblazer.delos.cryptography.bls.BLSPublicKey;
import com.hellblazer.delos.cryptography.bls.ProofOfPossession;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.WitnessContext;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * gRPC service for BLS key registration.
 * Committee members call this service to register their BLS public keys.
 */
public class KeyRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(KeyRegistrationService.class);

    private final CommitteeBLSKeyStore keyStore;
    private final ProofOfPossessionValidator popValidator;
    private final WitnessContext witnessContext;

    // Metrics
    private final AtomicLong registrationAttempts = new AtomicLong(0);
    private final AtomicLong registrationSuccesses = new AtomicLong(0);
    private final AtomicLong registrationFailures = new AtomicLong(0);

    public KeyRegistrationService(
        CommitteeBLSKeyStore keyStore,
        ProofOfPossessionValidator popValidator,
        WitnessContext witnessContext
    ) {
        this.keyStore = Objects.requireNonNull(keyStore);
        this.popValidator = Objects.requireNonNull(popValidator);
        this.witnessContext = Objects.requireNonNull(witnessContext);
    }

    /**
     * Register a BLS public key for a committee member.
     *
     * @param memberId Member identifier
     * @param publicKey BLS public key
     * @param pop Proof of Possession
     * @return Registration result
     */
    public RegistrationResult registerKey(
        Identifier memberId,
        BLSPublicKey publicKey,
        ProofOfPossession pop
    ) {
        registrationAttempts.incrementAndGet();

        // Validate member is in current committee
        if (!witnessContext.getCurrentMembers().contains(memberId)) {
            registrationFailures.incrementAndGet();
            log.warn("Key registration rejected: {} not in current committee", memberId);
            return new RegistrationResult.NotCommitteeMember(memberId);
        }

        // Create registration record
        var registration = BLSKeyRegistration.create(
            memberId,
            publicKey,
            pop,
            witnessContext.getEpoch()
        );

        // Validate PoP
        var popResult = popValidator.validate(registration);

        if (popResult instanceof ProofOfPossessionValidator.ValidationResult.Invalid invalid) {
            registrationFailures.incrementAndGet();
            log.warn("PoP validation failed for {}: {}", memberId, invalid.reason());
            return new RegistrationResult.InvalidPoP(invalid.reason());
        }

        if (popResult instanceof ProofOfPossessionValidator.ValidationResult.Error error) {
            registrationFailures.incrementAndGet();
            log.error("PoP validation error for {}: {}", memberId, error.message());
            return new RegistrationResult.ValidationError(error.message());
        }

        // Store key
        try {
            var stored = keyStore.registerKey(registration);
            if (stored) {
                registrationSuccesses.incrementAndGet();
                log.info("BLS key registered for member: {}", memberId);
                return new RegistrationResult.Success(registration);
            } else {
                log.info("BLS key already registered for member: {}", memberId);
                return new RegistrationResult.AlreadyRegistered(memberId);
            }
        } catch (Exception e) {
            registrationFailures.incrementAndGet();
            log.error("Key storage failed for {}: {}", memberId, e.getMessage(), e);
            return new RegistrationResult.StorageError(e.getMessage());
        }
    }

    /**
     * Registration result sealed interface.
     */
    public sealed interface RegistrationResult {
        record Success(BLSKeyRegistration registration) implements RegistrationResult {}
        record AlreadyRegistered(Identifier memberId) implements RegistrationResult {}
        record NotCommitteeMember(Identifier memberId) implements RegistrationResult {}
        record InvalidPoP(String reason) implements RegistrationResult {}
        record ValidationError(String message) implements RegistrationResult {}
        record StorageError(String message) implements RegistrationResult {}
    }

    // Metrics accessors
    public long getRegistrationAttempts() { return registrationAttempts.get(); }
    public long getRegistrationSuccesses() { return registrationSuccesses.get(); }
    public long getRegistrationFailures() { return registrationFailures.get(); }
}
```

#### Tests for Phase 1B-3-A (30 tests)

```
CommitteeBLSKeyStoreTest.java (12 tests)
- testRegisterKey_Success
- testRegisterKey_DuplicateRejected
- testGetPublicKey_Found
- testGetPublicKey_NotFound
- testGetPublicKeys_FiltersUnregistered
- testHasKey_True
- testHasKey_False
- testRemoveKey_Success
- testRemoveKey_NotFound
- testKeyCount_Accurate
- testRegisteredMembers_ReturnsAll
- testConcurrentRegistration_ThreadSafe

BLSKeyRegistrationTest.java (6 tests)
- testValidConstruction
- testNullMemberIdThrows
- testNullPublicKeyThrows
- testNullPoPThrows
- testNegativeEpochThrows
- testCreateFactoryMethod

ProofOfPossessionValidatorTest.java (6 tests)
- testValidPoP_ReturnsValid
- testInvalidSignature_ReturnsInvalid
- testCorruptedPoP_ReturnsInvalid
- testWrongKey_ReturnsInvalid
- testValidationException_ReturnsError
- testNullRegistration_Throws

KeyRegistrationServiceTest.java (6 tests)
- testRegisterKey_FullFlow_Success
- testRegisterKey_NotCommitteeMember_Rejected
- testRegisterKey_InvalidPoP_Rejected
- testRegisterKey_AlreadyRegistered_Returns
- testRegisterKey_MetricsUpdated
- testConcurrentRegistrations_ThreadSafe
```

---

### Phase 1B-3-B: Genesis Transition Mechanics (4 days, 25 tests)

**Goal**: Implement the protocol for transitioning from DUAL to BLS_ONLY phase

#### Deliverables

##### 1. TransitionReadinessChecker.java (~150 lines)

**Location**: `witness-service/src/main/java/com/hellblazer/delos/witness/migration/TransitionReadinessChecker.java`

```java
package com.hellblazer.delos.witness.migration;

import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.WitnessContext;
import com.hellblazer.delos.witness.WitnessParameters;
import com.hellblazer.delos.witness.committee.CommitteeBLSKeyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Set;

/**
 * Checks if the system is ready for BLS_ONLY phase transition.
 * Validates that all prerequisites are met before allowing genesis transition.
 */
public final class TransitionReadinessChecker {

    private static final Logger log = LoggerFactory.getLogger(TransitionReadinessChecker.class);

    private final WitnessContext witnessContext;
    private final CommitteeBLSKeyStore keyStore;
    private final MigrationStateTracker stateTracker;
    private final WitnessParameters parameters;

    public TransitionReadinessChecker(
        WitnessContext witnessContext,
        CommitteeBLSKeyStore keyStore,
        MigrationStateTracker stateTracker,
        WitnessParameters parameters
    ) {
        this.witnessContext = Objects.requireNonNull(witnessContext);
        this.keyStore = Objects.requireNonNull(keyStore);
        this.stateTracker = Objects.requireNonNull(stateTracker);
        this.parameters = Objects.requireNonNull(parameters);
    }

    /**
     * Check if system is ready for BLS_ONLY transition.
     *
     * @return TransitionReadiness with detailed status
     */
    public TransitionReadiness checkReadiness() {
        var currentPhase = stateTracker.getCurrentPhase();
        var currentMembers = witnessContext.getCurrentMembers();
        var membersWithKeys = countMembersWithKeys(currentMembers);
        var totalMembers = currentMembers.size();
        var requiredQuorum = calculateRequiredQuorum(totalMembers);

        var allMembersHaveKeys = membersWithKeys == totalMembers;
        var quorumAvailable = membersWithKeys >= requiredQuorum;
        var inDualPhase = currentPhase == MigrationPhase.DUAL;

        return new TransitionReadiness(
            allMembersHaveKeys,
            membersWithKeys,
            totalMembers,
            quorumAvailable,
            requiredQuorum,
            witnessContext.getEpoch(),
            inDualPhase,
            allMembersHaveKeys && quorumAvailable && inDualPhase
        );
    }

    /**
     * Get list of members without BLS keys (for operational debugging).
     */
    public Set<Identifier> getMembersWithoutKeys() {
        var currentMembers = witnessContext.getCurrentMembers();
        var registeredMembers = keyStore.registeredMembers();

        return currentMembers.stream()
            .filter(m -> !registeredMembers.contains(m))
            .collect(java.util.stream.Collectors.toSet());
    }

    private int countMembersWithKeys(Set<Identifier> members) {
        return (int) members.stream()
            .filter(keyStore::hasKey)
            .count();
    }

    private int calculateRequiredQuorum(int totalMembers) {
        // BFT quorum: 2f+1 where f = (n-1)/3
        int f = (totalMembers - 1) / 3;
        return 2 * f + 1;
    }

    /**
     * Transition readiness status record.
     */
    public record TransitionReadiness(
        boolean allMembersHaveBlsKeys,
        int membersWithKeys,
        int totalMembers,
        boolean quorumAvailable,
        int requiredQuorum,
        long currentEpoch,
        boolean inDualPhase,
        boolean canTransition
    ) {
        public String summary() {
            return String.format(
                "Readiness: keys=%d/%d, quorum=%s (%d required), phase=%s, canTransition=%s",
                membersWithKeys, totalMembers,
                quorumAvailable ? "YES" : "NO", requiredQuorum,
                inDualPhase ? "DUAL" : "NOT_DUAL",
                canTransition ? "YES" : "NO"
            );
        }
    }
}
```

##### 2. GenesisTransitionCoordinator.java (~200 lines)

**Location**: `witness-service/src/main/java/com/hellblazer/delos/witness/migration/GenesisTransitionCoordinator.java`

```java
package com.hellblazer.delos.witness.migration;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.witness.WitnessContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Coordinates the genesis transition from DUAL to BLS_ONLY phase.
 * Handles drain period, safety checks, and transition recording.
 */
public final class GenesisTransitionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(GenesisTransitionCoordinator.class);

    private final TransitionReadinessChecker readinessChecker;
    private final MigrationStateTracker stateTracker;
    private final WitnessContext witnessContext;
    private final Duration drainPeriod;

    private final AtomicBoolean transitionInProgress = new AtomicBoolean(false);
    private final AtomicReference<GenesisTransition> lastTransition = new AtomicReference<>();

    public GenesisTransitionCoordinator(
        TransitionReadinessChecker readinessChecker,
        MigrationStateTracker stateTracker,
        WitnessContext witnessContext,
        Duration drainPeriod
    ) {
        this.readinessChecker = Objects.requireNonNull(readinessChecker);
        this.stateTracker = Objects.requireNonNull(stateTracker);
        this.witnessContext = Objects.requireNonNull(witnessContext);
        this.drainPeriod = Objects.requireNonNull(drainPeriod);
    }

    /**
     * Attempt to transition to BLS_ONLY phase.
     *
     * @param initiator Identifier of who initiated (admin ID or "auto")
     * @param force Skip safety checks (use with caution)
     * @return TransitionResult indicating outcome
     */
    public TransitionResult attemptTransition(String initiator, boolean force) {
        Objects.requireNonNull(initiator, "initiator cannot be null");

        // Prevent concurrent transitions
        if (!transitionInProgress.compareAndSet(false, true)) {
            log.warn("Transition attempt rejected: another transition in progress");
            return new TransitionResult.AlreadyInProgress();
        }

        try {
            return executeTransition(initiator, force);
        } finally {
            transitionInProgress.set(false);
        }
    }

    /**
     * Attempt automatic transition based on epoch threshold.
     * Called during view change processing.
     *
     * @param newEpoch New epoch from view change
     * @param targetEpoch Target epoch for automatic transition
     * @return true if transition occurred
     */
    public boolean attemptAutoTransition(long newEpoch, long targetEpoch) {
        if (newEpoch < targetEpoch) {
            return false;
        }

        var result = attemptTransition("auto", false);
        return result instanceof TransitionResult.Success;
    }

    private TransitionResult executeTransition(String initiator, boolean force) {
        // Check readiness
        var readiness = readinessChecker.checkReadiness();

        if (!force && !readiness.canTransition()) {
            log.warn("Transition blocked: {}", readiness.summary());
            return new TransitionResult.NotReady(readiness);
        }

        if (force && !readiness.quorumAvailable()) {
            log.error("Forced transition rejected: quorum not available ({}/{})",
                      readiness.membersWithKeys(), readiness.requiredQuorum());
            return new TransitionResult.QuorumUnavailable(readiness);
        }

        log.info("Beginning genesis transition. Initiator: {}, Force: {}", initiator, force);

        // Wait for drain period to allow in-flight operations to complete
        try {
            log.info("Entering drain period: {}ms", drainPeriod.toMillis());
            Thread.sleep(drainPeriod.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TransitionResult.DrainInterrupted();
        }

        // Execute phase transition
        var previousPhase = stateTracker.getCurrentPhase();

        try {
            stateTracker.manualAdvance(MigrationPhase.BLS_ONLY);
        } catch (IllegalArgumentException e) {
            log.error("Phase transition failed: {}", e.getMessage());
            return new TransitionResult.TransitionFailed(e.getMessage());
        }

        // Record transition
        var transition = new GenesisTransition(
            witnessContext.getEpoch(),
            Instant.now(),
            null, // Block hash would come from CHOAM integration
            initiator,
            previousPhase,
            MigrationPhase.BLS_ONLY
        );
        lastTransition.set(transition);

        log.info("Genesis transition complete: {} -> BLS_ONLY at epoch {}",
                 previousPhase, transition.epochTransitioned());

        return new TransitionResult.Success(transition);
    }

    /**
     * Get the last recorded transition (for audit).
     */
    public GenesisTransition getLastTransition() {
        return lastTransition.get();
    }

    /**
     * Check if a transition is currently in progress.
     */
    public boolean isTransitionInProgress() {
        return transitionInProgress.get();
    }

    /**
     * Genesis transition record for audit trail.
     */
    public record GenesisTransition(
        long epochTransitioned,
        Instant timestamp,
        Digest transitionBlockHash,
        String initiatedBy,
        MigrationPhase fromPhase,
        MigrationPhase toPhase
    ) {}

    /**
     * Transition result sealed interface.
     */
    public sealed interface TransitionResult {
        record Success(GenesisTransition transition) implements TransitionResult {}
        record NotReady(TransitionReadinessChecker.TransitionReadiness readiness) implements TransitionResult {}
        record QuorumUnavailable(TransitionReadinessChecker.TransitionReadiness readiness) implements TransitionResult {}
        record AlreadyInProgress() implements TransitionResult {}
        record DrainInterrupted() implements TransitionResult {}
        record TransitionFailed(String reason) implements TransitionResult {}
    }
}
```

##### 3. MigrationStateTracker Enhancements (~50 lines)

Add to existing `MigrationStateTracker.java`:

```java
// Add field
private final TransitionReadinessChecker readinessChecker;

/**
 * Check if automatic transition to BLS_ONLY should occur.
 * Called during view change processing.
 *
 * @param currentEpoch Current epoch
 * @param targetEpoch Target epoch for transition
 * @return true if should attempt transition
 */
public boolean shouldAutoTransition(long currentEpoch, long targetEpoch) {
    if (getCurrentPhase() != MigrationPhase.DUAL) {
        return false;
    }
    if (currentEpoch < targetEpoch) {
        return false;
    }
    if (readinessChecker == null) {
        return false;
    }
    return readinessChecker.checkReadiness().canTransition();
}

/**
 * Get current transition readiness status.
 */
public TransitionReadinessChecker.TransitionReadiness getTransitionReadiness() {
    if (readinessChecker == null) {
        throw new IllegalStateException("TransitionReadinessChecker not configured");
    }
    return readinessChecker.checkReadiness();
}
```

##### 4. Proto Extensions for Phase Transition

**Location**: `grpc/src/main/proto/witness.proto`

```protobuf
// Phase transition request (admin endpoint)
message PhaseTransitionRequest {
    enum TargetPhase {
        DUAL = 0;
        BLS_ONLY = 1;
    }
    TargetPhase target_phase = 1;
    bool force = 2;
    string justification = 3;  // Audit trail
}

message PhaseTransitionResponse {
    enum Status {
        SUCCESS = 0;
        NOT_READY = 1;
        QUORUM_UNAVAILABLE = 2;
        ALREADY_IN_PROGRESS = 3;
        TRANSITION_FAILED = 4;
    }
    Status status = 1;
    string previous_phase = 2;
    string new_phase = 3;
    int64 epoch_transitioned = 4;
    string message = 5;
}

// Transition readiness query
message TransitionReadinessRequest {}

message TransitionReadinessResponse {
    bool can_transition = 1;
    bool all_members_have_keys = 2;
    int32 members_with_keys = 3;
    int32 total_members = 4;
    bool quorum_available = 5;
    int32 required_quorum = 6;
    int64 current_epoch = 7;
    string current_phase = 8;
    repeated string members_without_keys = 9;
}

// Add to WitnessService
service WitnessService {
    // ... existing methods ...

    // Admin: Manual phase transition
    rpc TransitionPhase(PhaseTransitionRequest) returns (PhaseTransitionResponse);

    // Query: Check transition readiness
    rpc GetTransitionReadiness(TransitionReadinessRequest) returns (TransitionReadinessResponse);
}
```

##### 5. AdminPhaseTransitionService.java (~100 lines)

**Location**: `witness-service/src/main/java/com/hellblazer/delos/witness/migration/AdminPhaseTransitionService.java`

Implementation of the gRPC endpoints for phase transition management.

#### Tests for Phase 1B-3-B (25 tests)

```
TransitionReadinessCheckerTest.java (8 tests)
- testCheckReadiness_AllMembersHaveKeys
- testCheckReadiness_SomeMembersMissingKeys
- testCheckReadiness_NoKeysRegistered
- testCheckReadiness_QuorumAvailable
- testCheckReadiness_QuorumNotAvailable
- testCheckReadiness_NotInDualPhase
- testGetMembersWithoutKeys_ReturnsCorrect
- testCalculateRequiredQuorum_BFT

GenesisTransitionCoordinatorTest.java (10 tests)
- testAttemptTransition_Success
- testAttemptTransition_NotReady_Blocked
- testAttemptTransition_Force_Success
- testAttemptTransition_Force_NoQuorum_Blocked
- testAttemptTransition_AlreadyInProgress
- testAttemptTransition_DrainPeriodRespected
- testAttemptAutoTransition_EpochReached
- testAttemptAutoTransition_EpochNotReached
- testGetLastTransition_ReturnsRecord
- testConcurrentTransitions_OnlyOneSucceeds

MigrationStateTrackerEnhancementsTest.java (4 tests)
- testShouldAutoTransition_Ready
- testShouldAutoTransition_NotReady
- testShouldAutoTransition_WrongPhase
- testGetTransitionReadiness_Delegated

AdminPhaseTransitionServiceTest.java (3 tests)
- testTransitionPhase_gRPC_Success
- testTransitionPhase_gRPC_Rejected
- testGetTransitionReadiness_gRPC
```

---

### Phase 1B-3-C: Operational Integration (3 days, 20 tests)

**Goal**: Connect BLS infrastructure to production operations

#### Deliverables

##### 1. Byzantine Detection Enhancements

Extend `ByzantineWitnessDetector.java` to track BLS validation failures:

```java
/**
 * Track BLS validation failure for a member.
 * After threshold failures, trigger shunning.
 */
public void recordBlsValidationFailure(Identifier member, String reason) {
    var failures = blsFailureCounts.merge(member, 1, Integer::sum);

    if (failures >= BLS_FAILURE_THRESHOLD) {
        log.warn("Member {} exceeded BLS failure threshold ({} failures), triggering shun",
                 member, failures);
        shunMember(member, "BLS validation failures: " + failures);
    }
}

/**
 * Shun a Byzantine member via Fireflies integration.
 */
private void shunMember(Identifier member, String reason) {
    firefliesShunning.shun(member, reason);
    shunnedMembers.add(member);
    shunEvents.incrementAndGet();
}
```

##### 2. Fireflies Shunning Integration

```java
package com.hellblazer.delos.witness.integration;

import com.hellblazer.delos.stereotomy.identifier.Identifier;

/**
 * Interface for notifying Fireflies of Byzantine members.
 */
public interface FirefliesShunningIntegration {

    /**
     * Mark a member as Byzantine and request shunning.
     *
     * @param member Member to shun
     * @param reason Reason for shunning
     */
    void shun(Identifier member, String reason);

    /**
     * Check if a member is currently shunned.
     */
    boolean isShunned(Identifier member);

    /**
     * Get count of shunned members.
     */
    int shunnedCount();
}
```

##### 3. CHOAM Transition Recorder

Record genesis transition in CHOAM block log for audit trail:

```java
package com.hellblazer.delos.witness.integration;

import com.hellblazer.delos.witness.migration.GenesisTransitionCoordinator.GenesisTransition;

/**
 * Records genesis transition metadata in CHOAM block log.
 */
public interface CHOAMTransitionRecorder {

    /**
     * Record a genesis transition.
     *
     * @param transition Transition metadata
     * @return Block hash where transition was recorded
     */
    Digest recordTransition(GenesisTransition transition);
}
```

##### 4. Metrics Integration

Add Dropwizard metrics to all components:

```java
// Metric names
witness.bls.keys.registered        // Gauge: number of registered keys
witness.bls.keys.coverage          // Gauge: % of committee with keys (0-100)
witness.transition.readiness       // Gauge: 1 if ready, 0 if not
witness.transition.in_progress     // Gauge: 1 if transition in progress
witness.byzantine.shunned          // Counter: total members shunned
witness.byzantine.bls_failures     // Counter: total BLS validation failures
witness.registration.attempts      // Counter: key registration attempts
witness.registration.successes     // Counter: key registration successes
```

##### 5. Health Check Updates

Add Phase 1B-3 health to witness health endpoint:

```java
public record Phase1B3Health(
    MigrationPhase currentPhase,
    TransitionReadinessChecker.TransitionReadiness readiness,
    int registeredKeyCount,
    int shunnedMemberCount,
    long blsValidationsTotal,
    long blsFailuresTotal,
    boolean isHealthy
) {
    public boolean isHealthy() {
        // In BLS_ONLY phase, any Ed25519 validation attempt is unhealthy
        // In DUAL phase, excessive BLS failures are unhealthy
        if (currentPhase == MigrationPhase.BLS_ONLY) {
            return true; // No specific health check for BLS_ONLY
        }
        // DUAL phase: check fallback rate
        return blsFailuresTotal < blsValidationsTotal * 0.1; // <10% failure rate
    }
}
```

#### Tests for Phase 1B-3-C (20 tests)

```
ByzantineDetectorBlsEnhancementsTest.java (6 tests)
- testRecordBlsFailure_IncrementCount
- testRecordBlsFailure_ThresholdTriggerShun
- testRecordBlsFailure_MultipleMembers
- testShunMember_NotifiesFireflies
- testShunMember_MetricsUpdated
- testIsShunned_ChecksCorrectly

FirefliesShunningIntegrationTest.java (4 tests)
- testShun_MemberMarkedByzantine
- testShun_IdempotentCall
- testIsShunned_AfterShun
- testShunnedCount_Accurate

CHOAMTransitionRecorderTest.java (4 tests)
- testRecordTransition_PersistsToLog
- testRecordTransition_ReturnsBlockHash
- testRecordTransition_AuditTrailComplete
- testRecordTransition_FailureHandled

MetricsIntegrationTest.java (4 tests)
- testAllMetricsExposed
- testMetricsAccuracyAfterOperations
- testGaugeValuesCorrect
- testCountersIncrementCorrectly

HealthCheckPhase1B3Test.java (2 tests)
- testHealthy_InDualPhase
- testHealthy_InBlsOnlyPhase
```

---

### Phase 1B-3-D: Production Readiness (3 days, 20 tests)

**Goal**: Validate and prepare for production deployment

#### Deliverables

##### 1. Comprehensive Test Suite

**Byzantine Failure Tests** (8 tests):
- Invalid PoP submission attack
- Forged BLS signature detection
- Replay attack on key registration
- Key substitution attack attempt
- Split-brain during transition
- Partial key registration state
- Byzantine member detection and shunning
- Quorum maintenance during attack

**Performance Tests** (6 tests):
- Key lookup latency (<1ms target)
- Key registration throughput (>100/sec target)
- Transition completion time (<5 sec target)
- Memory footprint with 1000 keys
- Concurrent validation performance
- Metrics collection overhead

**Edge Case Tests** (6 tests):
- Transition during view change
- Key registration during transition
- Empty committee handling
- Single member committee
- All members fail BLS validation
- Recovery from partial transition

##### 2. Rollback Documentation

**Emergency Rollback Procedures** (rollback-guide.md):
1. Soft rollback (DUAL phase only)
2. Hard rollback (requires restart)
3. Recovery verification steps
4. Post-rollback health checks

##### 3. Operations Runbook

**Pre-Deployment Checklist**:
- [ ] All committee members have BLS keys registered
- [ ] Quorum (2f+1) verified with BLS keys
- [ ] DUAL phase stable for N epochs
- [ ] Fallback rate <10%
- [ ] All metrics and alerts configured

**Monitoring Setup**:
- Dashboard configuration
- Alert thresholds
- Escalation procedures

**Deployment Steps**:
1. Verify readiness via gRPC endpoint
2. Initiate transition (manual or wait for auto)
3. Monitor drain period
4. Verify BLS_ONLY phase active
5. Confirm no Ed25519 validations occurring

#### Tests for Phase 1B-3-D (20 tests)

```
ByzantineFailureTest.java (8 tests)
- testInvalidPoPAttack_Rejected
- testForgedSignature_Detected
- testReplayAttack_Prevented
- testKeySubstitution_Blocked
- testSplitBrainTransition_Handled
- testPartialKeyState_Safe
- testByzantineShunning_Triggered
- testQuorumMaintained_UnderAttack

PerformanceTest.java (6 tests)
- testKeyLookupLatency_Under1ms
- testRegistrationThroughput_Over100PerSec
- testTransitionTime_Under5Sec
- testMemoryFootprint_1000Keys
- testConcurrentValidation_Performance
- testMetricsOverhead_Minimal

EdgeCaseTest.java (6 tests)
- testTransitionDuringViewChange
- testKeyRegistrationDuringTransition
- testEmptyCommittee_Handled
- testSingleMemberCommittee
- testAllMembersFailBls_Handled
- testPartialTransitionRecovery
```

---

## Dependency Graph

```
Phase 1B-2-C (Complete) ──────────────────────────────────────────┐
                                                                   │
Phase 1B-3-A                                                       │
┌──────────────────────────────────────────────────────────────────┘
│
│  1B-3-A-1: CommitteeBLSKeyStore ──────┐
│  1B-3-A-2: BLSKeyRegistration ────────┼──> 1B-3-A-4: WitnessContext.getCommitteeBLSKeys()
│  1B-3-A-3: ProofOfPossessionValidator ┘           │
│  1B-3-A-5: KeyRegistrationService ────────────────┤
│                                                    │
│                                                    v
Phase 1B-3-B                                         │
│  1B-3-B-1: TransitionReadinessChecker <───────────┘
│           │
│           v
│  1B-3-B-2: GenesisTransitionCoordinator ──────────> Phase 1B-3-C
│           │                                                │
│           v                                                v
│  1B-3-B-3: MigrationStateTracker enhancements      1B-3-C-1: Byzantine Detection
│  1B-3-B-4: Proto extensions                        1B-3-C-2: Fireflies Integration
│  1B-3-B-5: AdminPhaseTransitionService             1B-3-C-3: CHOAM Recorder
│                                                    1B-3-C-4: Metrics
│                                                    1B-3-C-5: Health Checks
│                                                            │
│                                                            v
Phase 1B-3-D                                         Production Readiness
│  1B-3-D-1: Byzantine Failure Tests                         │
│  1B-3-D-2: Performance Tests                              │
│  1B-3-D-3: Edge Case Tests                                │
│  1B-3-D-4: Rollback Documentation                         │
│  1B-3-D-5: Operations Runbook                             │
│                                                            │
└──────────────────> PHASE 1B-3 COMPLETE <──────────────────┘
```

## Critical Path

```
1B-3-A-1 → 1B-3-A-4 → 1B-3-B-1 → 1B-3-B-2 → 1B-3-C (all) → 1B-3-D (all)
  Day 1      Day 2      Day 4      Day 6       Day 8        Day 11-13
```

**Total Duration**: 13 days minimum, 16 days with buffer

---

## Risk Analysis

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| PoP validation complexity | Medium | High | Use established Teku BLS library, comprehensive tests |
| Transition timing issues | Low | High | Extensive DUAL phase testing, drain period |
| Fireflies integration complexity | Medium | Medium | Define clear interface contract early |
| CHOAM coordination | Low | Medium | Use existing view change pattern |
| Performance regression | Low | Low | Early benchmarking, performance tests |
| Key rotation during transition | Medium | Medium | Lock key registration during transition |
| Byzantine attack during transition | Low | High | Quorum requirement, drain period |

---

## Effort Estimate Summary

| Phase | Days | Hours | Tests | Risk Level |
|-------|------|-------|-------|------------|
| 1B-3-A: Key Infrastructure | 3 | 24 | 30 | Medium |
| 1B-3-B: Transition Mechanics | 4 | 32 | 25 | Medium-High |
| 1B-3-C: Operational Integration | 3 | 24 | 20 | Low |
| 1B-3-D: Production Readiness | 3 | 24 | 20 | Low |
| **Total** | **13** | **104** | **95** | **Medium** |

**With 20% buffer**: 15-16 days total

---

## Success Criteria

### Functional Requirements
- [ ] `WitnessContext.getCommitteeBLSKeys()` implemented and integrated
- [ ] Committee members can register BLS keys with PoP validation
- [ ] Phase transition from DUAL to BLS_ONLY works correctly
- [ ] Ed25519 receipts rejected in BLS_ONLY phase
- [ ] Historical receipts remain queryable regardless of format
- [ ] Byzantine members detected and shunned

### Performance Requirements
- [ ] Key lookup latency <1ms
- [ ] Key registration throughput >100/sec
- [ ] Transition completion <5 seconds
- [ ] Zero downtime during transition

### Security Requirements
- [ ] PoP prevents key impersonation
- [ ] Byzantine nodes detected after threshold failures
- [ ] No signature forgery possible
- [ ] Quorum maintained during attacks

### Operational Requirements
- [ ] All metrics exposed via Dropwizard
- [ ] Health checks functional
- [ ] Admin gRPC endpoints working
- [ ] Rollback procedures documented
- [ ] Operations runbook complete

### Quality Gates
- [ ] All 95 new tests passing
- [ ] All existing tests (480+) still passing
- [ ] Zero compilation warnings
- [ ] Documentation complete

---

## Implementation Order

1. **Phase 1B-3-A-1**: CommitteeBLSKeyStore (Day 1)
2. **Phase 1B-3-A-2**: BLSKeyRegistration (Day 1, parallel)
3. **Phase 1B-3-A-3**: ProofOfPossessionValidator (Day 1-2, parallel)
4. **Phase 1B-3-A-4**: WitnessContext.getCommitteeBLSKeys() (Day 2)
5. **Phase 1B-3-A-5**: KeyRegistrationService (Day 2-3)
6. **Phase 1B-3-B-1**: TransitionReadinessChecker (Day 4)
7. **Phase 1B-3-B-2**: GenesisTransitionCoordinator (Day 5-6)
8. **Phase 1B-3-B-3**: MigrationStateTracker enhancements (Day 6)
9. **Phase 1B-3-B-4**: Proto extensions (Day 6-7, parallel)
10. **Phase 1B-3-B-5**: AdminPhaseTransitionService (Day 7)
11. **Phase 1B-3-C**: All operational integration (Days 8-10)
12. **Phase 1B-3-D**: All production readiness (Days 11-13)

---

## Context for Executing Agent

### Knowledge Base Queries

**ChromaDB**:
- "BLS proof of possession validation Delos"
- "Committee key management witness service"
- "Genesis migration phase transition"
- "Byzantine detection shunning Fireflies"

**Memory Bank**:
- `Delos_active/phase1b-2-c-status.md`
- `Delos_active/phase1b-architecture.md`

### Key Files to Reference

- `/Users/hal.hildebrand/git/Delos/witness-service/src/main/java/com/hellblazer/delos/witness/WitnessContext.java`
- `/Users/hal.hildebrand/git/Delos/witness-service/src/main/java/com/hellblazer/delos/witness/migration/MigrationStateTracker.java`
- `/Users/hal.hildebrand/git/Delos/cryptography/src/main/java/com/hellblazer/delos/cryptography/bls/ProofOfPossession.java`
- `/Users/hal.hildebrand/git/Delos/cryptography/src/main/java/com/hellblazer/delos/cryptography/bls/BLSOperations.java`

### TDD Reminders

- Write tests FIRST, then implementation
- All code must compile including tests before proceeding
- Use sequential thinking for complex design decisions
- Virtual thread compatible: No synchronized blocks, use atomics

---

## Beads Structure (to be created)

```bash
# Create epic
bd create "Phase 1B-3: Genesis Migration Boundary" -t epic -p 1

# Phase 1B-3-A tasks
bd create "1B-3-A-1: Implement CommitteeBLSKeyStore interface and in-memory impl" -t task -p 2
bd create "1B-3-A-2: Implement BLSKeyRegistration record type" -t task -p 2
bd create "1B-3-A-3: Implement ProofOfPossessionValidator" -t task -p 2
bd create "1B-3-A-4: Implement WitnessContext.getCommitteeBLSKeys()" -t task -p 2
bd create "1B-3-A-5: Implement KeyRegistrationService gRPC" -t task -p 2

# Phase 1B-3-B tasks
bd create "1B-3-B-1: Implement TransitionReadinessChecker" -t task -p 2
bd create "1B-3-B-2: Implement GenesisTransitionCoordinator" -t task -p 2
bd create "1B-3-B-3: Enhance MigrationStateTracker for transition" -t task -p 2
bd create "1B-3-B-4: Add phase transition proto messages" -t task -p 2
bd create "1B-3-B-5: Implement AdminPhaseTransitionService" -t task -p 2

# Phase 1B-3-C tasks
bd create "1B-3-C-1: Enhance ByzantineWitnessDetector for BLS failures" -t task -p 2
bd create "1B-3-C-2: Implement FirefliesShunningIntegration" -t task -p 2
bd create "1B-3-C-3: Implement CHOAMTransitionRecorder" -t task -p 2
bd create "1B-3-C-4: Add Dropwizard metrics to all components" -t task -p 2
bd create "1B-3-C-5: Update health check for Phase 1B-3" -t task -p 2

# Phase 1B-3-D tasks
bd create "1B-3-D-1: Write Byzantine failure tests (8 tests)" -t task -p 2
bd create "1B-3-D-2: Write performance tests (6 tests)" -t task -p 2
bd create "1B-3-D-3: Write edge case tests (6 tests)" -t task -p 2
bd create "1B-3-D-4: Write rollback documentation" -t task -p 2
bd create "1B-3-D-5: Write operations runbook" -t task -p 2

# Add dependencies (IDs to be filled after creation)
```

---

## Plan Status

**Plan Version**: 1.0
**Created**: 2026-01-19
**Author**: strategic-planner (Opus 4.5)
**Status**: READY FOR AUDIT

**Next Step**: Submit to plan-auditor agent for validation before implementation.
