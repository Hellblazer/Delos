/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.committee;

import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSKeyPair;
import com.hellblazer.delos.cryptography.bls.BLSProvider;
import com.hellblazer.delos.cryptography.bls.BLSTestFixtures;
import com.hellblazer.delos.stereotomy.identifier.BasicIdentifier;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.WitnessParameters;
import com.hellblazer.delos.witness.migration.MigrationPhase;
import com.hellblazer.delos.witness.migration.MigrationStateTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for GenesisTransitionCoordinator.
 * Tests the state machine for genesis phase transition from DUAL to BLS_ONLY.
 * <p>
 * Target: 25 comprehensive tests covering:
 * - Constructor validation (2 tests)
 * - Transition initiation (5 tests)
 * - State tracking (4 tests)
 * - Drain period (4 tests)
 * - Soft rollback (3 tests)
 * - Integration workflow (3 tests)
 * - Error conditions (4 tests)
 * <p>
 * Uses real instances instead of mocks for better integration testing
 * and to avoid issues with final classes.
 *
 * @author hal.hildebrand
 */
@DisplayName("GenesisTransitionCoordinator Tests")
class GenesisTransitionCoordinatorTest {

    private CommitteeBLSKeyStore keyStore;
    private TransitionReadinessChecker checker;
    private MigrationStateTracker tracker;
    private WitnessParameters parameters;
    private GenesisTransitionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        // Use real instances for better integration testing
        keyStore = new InMemoryCommitteeBLSKeyStore();

        // Default: k=4, threshold=3, epoch=0, drain=100ms for fast tests
        parameters = WitnessParameters.newBuilder()
            .k(4)
            .threshold(3)
            .epoch(0L)
            .drainPeriod(Duration.ofMillis(100))
            .build();

        checker = new TransitionReadinessChecker(keyStore, parameters);

        // Default: current phase is DUAL (ready for transition)
        tracker = new MigrationStateTracker(MigrationPhase.DUAL, 0L);
    }

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.shutdown();
        }
    }

    /**
     * Helper: Register enough BLS keys to satisfy BFT quorum.
     * For k=4, need 3 keys (2f+1 where f=1).
     */
    private void registerQuorumKeys() {
        // Register 3 keys for k=4 committee (satisfies BFT quorum)
        for (int i = 0; i < 3; i++) {
            var memberId = createMemberId(i + 1);
            registerKey(keyStore, memberId);
        }
    }

    /**
     * Helper: Register insufficient keys (below quorum).
     */
    private void registerInsufficientKeys() {
        // Register only 2 keys for k=4 committee (below quorum of 3)
        for (int i = 0; i < 2; i++) {
            var memberId = createMemberId(i + 1);
            registerKey(keyStore, memberId);
        }
    }

    /**
     * Create a deterministic test member identifier.
     */
    private Identifier createMemberId(int seed) {
        var random = BLSTestFixtures.deterministicRandom(0xAAAAL + seed);
        var idKey = SignatureAlgorithm.ED_25519.generateKeyPair(random);
        return new BasicIdentifier(idKey.getPublic());
    }

    /**
     * Register a BLS key for a member.
     */
    private void registerKey(CommitteeBLSKeyStore store, Identifier memberId) {
        var registration = createRegistration(memberId, memberId.hashCode());
        store.registerKey(registration);
    }

    /**
     * Create a BLS key registration for testing.
     * Uses deterministic key generation for reproducibility.
     */
    private BLSKeyRegistration createRegistration(Identifier memberId, int seed) {
        var blsProvider = BLSProvider.getDefault();
        var random = BLSTestFixtures.deterministicRandom(0x1000L + seed);
        var keyPair = BLSKeyPair.generate(random, blsProvider);

        var publicKey = keyPair.publicKey();
        var pop = publicKey.proofOfPossession();
        var signature = keyPair.sign(memberId.toIdent().toByteArray());

        return new BLSKeyRegistration(
            memberId,
            publicKey,
            pop,
            signature,
            0L,  // epoch
            Instant.now()
        );
    }

    // ========================================
    // Constructor Validation Tests (2)
    // ========================================

    @Test
    @DisplayName("Constructor: null readiness checker throws NullPointerException")
    void constructorNullChecker() {
        assertThatThrownBy(() -> new GenesisTransitionCoordinator(null, tracker, parameters))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("checker");
    }

    @Test
    @DisplayName("Constructor: null state tracker throws NullPointerException")
    void constructorNullTracker() {
        assertThatThrownBy(() -> new GenesisTransitionCoordinator(checker, null, parameters))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("tracker");
    }

    // ========================================
    // Transition Initiation Tests (5)
    // ========================================

    @Test
    @DisplayName("Initiate: ready committee initiates successfully")
    void initiateWhenReady() {
        registerQuorumKeys(); // Register 3 keys for k=4 (meets quorum)

        coordinator = new GenesisTransitionCoordinator(checker, tracker, parameters);

        var result = coordinator.initiateTransition();

        assertThat(result).isTrue();
        assertThat(coordinator.getStatus()).isEqualTo(TransitionStatus.DRAINING);
    }

    @Test
    @DisplayName("Initiate: not ready throws TransitionNotReadyException")
    void initiateWhenNotReady() {
        registerInsufficientKeys(); // Register only 2 keys (below quorum of 3)

        coordinator = new GenesisTransitionCoordinator(checker, tracker, parameters);

        assertThatThrownBy(() -> coordinator.initiateTransition())
            .isInstanceOf(TransitionNotReadyException.class)
            .hasMessageContaining("not ready")
            .hasMessageContaining("2")
            .hasMessageContaining("3");

        assertThat(coordinator.getStatus()).isEqualTo(TransitionStatus.NOT_STARTED);
    }

    @Test
    @DisplayName("Initiate: already in progress throws TransitionInProgressException")
    void initiateWhenAlreadyInProgress() {
        registerQuorumKeys();

        coordinator = new GenesisTransitionCoordinator(checker, tracker, parameters);

        // First initiation succeeds
        coordinator.initiateTransition();

        // Second initiation fails
        assertThatThrownBy(() -> coordinator.initiateTransition())
            .isInstanceOf(TransitionInProgressException.class)
            .hasMessageContaining("already in progress");
    }

    @Test
    @DisplayName("Initiate: wrong phase (INIT) throws IllegalStateException")
    void initiateWhenInInitPhase() {
        registerQuorumKeys();

        // Create tracker in INIT phase
        var initTracker = new MigrationStateTracker(MigrationPhase.INIT, 0L);
        coordinator = new GenesisTransitionCoordinator(checker, initTracker, parameters);

        assertThatThrownBy(() -> coordinator.initiateTransition())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("wrong phase")
            .hasMessageContaining("INIT");
    }

    @Test
    @DisplayName("Initiate: wrong phase (BLS_ONLY) throws IllegalStateException")
    void initiateWhenInBlsOnlyPhase() {
        registerQuorumKeys();

        // Create tracker in BLS_ONLY phase
        var blsTracker = new MigrationStateTracker(MigrationPhase.BLS_ONLY, 0L);
        coordinator = new GenesisTransitionCoordinator(checker, blsTracker, parameters);

        assertThatThrownBy(() -> coordinator.initiateTransition())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("wrong phase")
            .hasMessageContaining("BLS_ONLY");
    }

    // ========================================
    // State Tracking Tests (4)
    // ========================================

    @Test
    @DisplayName("State: initial state is NOT_STARTED")
    void initialState() {
        coordinator = new GenesisTransitionCoordinator(checker, tracker, parameters);

        assertThat(coordinator.getStatus()).isEqualTo(TransitionStatus.NOT_STARTED);
        assertThat(coordinator.getProgressPercent()).isEqualTo(0);
        assertThat(coordinator.getRemainingDrainTime()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("State: changes to DRAINING on initiation")
    void stateChangesToDraining() {
        registerQuorumKeys();

        coordinator = new GenesisTransitionCoordinator(checker, tracker, parameters);
        coordinator.initiateTransition();

        assertThat(coordinator.getStatus()).isEqualTo(TransitionStatus.DRAINING);
        assertThat(coordinator.getProgressPercent()).isGreaterThanOrEqualTo(50);
    }

    @Test
    @DisplayName("State: changes to COMPLETE after drain period")
    void stateChangesToComplete() throws Exception {
        registerQuorumKeys();

        coordinator = new GenesisTransitionCoordinator(checker, tracker, parameters);
        coordinator.initiateTransition();

        // Wait for drain period (100ms) plus buffer
        Thread.sleep(150);

        assertThat(coordinator.getStatus()).isEqualTo(TransitionStatus.COMPLETE);
        assertThat(coordinator.getProgressPercent()).isEqualTo(100);
        assertThat(coordinator.getRemainingDrainTime()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("State: progress goes from 0% -> 50%+ -> 100%")
    void progressTracking() throws Exception {
        registerQuorumKeys();

        coordinator = new GenesisTransitionCoordinator(checker, tracker, parameters);

        // Initial: 0%
        assertThat(coordinator.getProgressPercent()).isEqualTo(0);

        coordinator.initiateTransition();

        // During drain: 50%+
        var duringDrain = coordinator.getProgressPercent();
        assertThat(duringDrain).isBetween(50, 99);

        // After drain: 100%
        Thread.sleep(150);
        assertThat(coordinator.getProgressPercent()).isEqualTo(100);
    }

    // ========================================
    // Drain Period Tests (4)
    // ========================================

    @Test
    @DisplayName("Drain: period matches WitnessParameters")
    void drainPeriodMatchesParameters() {
        registerQuorumKeys();

        var customParameters = WitnessParameters.newBuilder()
            .k(4)
            .threshold(3)
            .epoch(0L)
            .drainPeriod(Duration.ofMillis(250))
            .build();

        var customChecker = new TransitionReadinessChecker(keyStore, customParameters);
        coordinator = new GenesisTransitionCoordinator(customChecker, tracker, customParameters);
        coordinator.initiateTransition();

        // Remaining time should be close to 250ms
        var remaining = coordinator.getRemainingDrainTime();
        assertThat(remaining.toMillis()).isBetween(200L, 250L);
    }

    @Test
    @DisplayName("Drain: getRemainingDrainTime() decreases over time")
    void drainTimeDecreases() throws Exception {
        registerQuorumKeys();

        coordinator = new GenesisTransitionCoordinator(checker, tracker, parameters);
        coordinator.initiateTransition();

        var time1 = coordinator.getRemainingDrainTime();
        Thread.sleep(30);
        var time2 = coordinator.getRemainingDrainTime();

        assertThat(time2).isLessThan(time1);
    }

    @Test
    @DisplayName("Drain: progress calculation based on elapsed time")
    void drainProgressCalculation() throws Exception {
        registerQuorumKeys();

        coordinator = new GenesisTransitionCoordinator(checker, tracker, parameters);
        coordinator.initiateTransition();

        // Immediately after start: ~50%
        var progress1 = coordinator.getProgressPercent();
        assertThat(progress1).isBetween(50, 60);

        // Halfway through drain (50ms): ~75%
        Thread.sleep(50);
        var progress2 = coordinator.getProgressPercent();
        assertThat(progress2).isBetween(70, 85);

        // After drain complete: 100%
        Thread.sleep(60);
        var progress3 = coordinator.getProgressPercent();
        assertThat(progress3).isEqualTo(100);
    }

    @Test
    @DisplayName("Drain: completion transitions phase to BLS_ONLY")
    void drainCompletionTransitionsPhase() throws Exception {
        registerQuorumKeys();

        coordinator = new GenesisTransitionCoordinator(checker, tracker, parameters);
        coordinator.initiateTransition();

        // Wait for drain period
        Thread.sleep(150);

        // Verify phase transition occurred
        assertThat(tracker.getCurrentPhase()).isEqualTo(MigrationPhase.BLS_ONLY);
        assertThat(coordinator.getStatus()).isEqualTo(TransitionStatus.COMPLETE);
    }

    // ========================================
    // Soft Rollback Tests (3)
    // ========================================

    @Test
    @DisplayName("Rollback: during DRAINING succeeds")
    void rollbackDuringDraining() {
        registerQuorumKeys();

        coordinator = new GenesisTransitionCoordinator(checker, tracker, parameters);
        coordinator.initiateTransition();

        assertThat(coordinator.getStatus()).isEqualTo(TransitionStatus.DRAINING);

        var result = coordinator.attemptSoftRollback();

        assertThat(result).isTrue();
        assertThat(coordinator.getStatus()).isEqualTo(TransitionStatus.NOT_STARTED);
    }

    @Test
    @DisplayName("Rollback: after COMPLETE fails (too late)")
    void rollbackAfterComplete() throws Exception {
        registerQuorumKeys();

        coordinator = new GenesisTransitionCoordinator(checker, tracker, parameters);
        coordinator.initiateTransition();

        // Wait for completion
        Thread.sleep(150);
        assertThat(coordinator.getStatus()).isEqualTo(TransitionStatus.COMPLETE);

        var result = coordinator.attemptSoftRollback();

        assertThat(result).isFalse();
        assertThat(coordinator.getStatus()).isEqualTo(TransitionStatus.COMPLETE);
    }

    @Test
    @DisplayName("Rollback: cancels remaining drain time")
    void rollbackCancelsDrain() {
        registerQuorumKeys();

        coordinator = new GenesisTransitionCoordinator(checker, tracker, parameters);
        coordinator.initiateTransition();

        assertThat(coordinator.getRemainingDrainTime().toMillis()).isGreaterThan(0);

        coordinator.attemptSoftRollback();

        assertThat(coordinator.getRemainingDrainTime()).isEqualTo(Duration.ZERO);
        assertThat(coordinator.getStatus()).isEqualTo(TransitionStatus.NOT_STARTED);
    }

    // ========================================
    // Integration Workflow Tests (3)
    // ========================================

    @Test
    @DisplayName("Integration: full workflow from ready to complete")
    void fullWorkflowIntegration() throws Exception {
        registerQuorumKeys();

        coordinator = new GenesisTransitionCoordinator(checker, tracker, parameters);

        // 1. Initial state
        assertThat(coordinator.getStatus()).isEqualTo(TransitionStatus.NOT_STARTED);
        assertThat(coordinator.getProgressPercent()).isEqualTo(0);

        // 2. Initiate transition
        var initiated = coordinator.initiateTransition();
        assertThat(initiated).isTrue();
        assertThat(coordinator.getStatus()).isEqualTo(TransitionStatus.DRAINING);
        assertThat(coordinator.getProgressPercent()).isGreaterThanOrEqualTo(50);

        // 3. Wait for drain
        Thread.sleep(150);

        // 4. Verify completion
        assertThat(coordinator.getStatus()).isEqualTo(TransitionStatus.COMPLETE);
        assertThat(coordinator.getProgressPercent()).isEqualTo(100);
        assertThat(tracker.getCurrentPhase()).isEqualTo(MigrationPhase.BLS_ONLY);
    }

    @Test
    @DisplayName("Integration: phase transition confirmed via tracker")
    void phaseTransitionConfirmed() throws Exception {
        registerQuorumKeys();

        coordinator = new GenesisTransitionCoordinator(checker, tracker, parameters);

        // Initial phase should be DUAL
        assertThat(tracker.getCurrentPhase()).isEqualTo(MigrationPhase.DUAL);

        coordinator.initiateTransition();

        Thread.sleep(150);

        // Phase should now be BLS_ONLY
        assertThat(tracker.getCurrentPhase()).isEqualTo(MigrationPhase.BLS_ONLY);
    }

    @Test
    @DisplayName("Integration: progress tracking accuracy within tolerance")
    void progressTrackingAccuracy() throws Exception {
        registerQuorumKeys();

        coordinator = new GenesisTransitionCoordinator(checker, tracker, parameters);
        coordinator.initiateTransition();

        // Check progress at 25ms (~25% through 100ms drain = ~62.5% total)
        Thread.sleep(25);
        var progress25 = coordinator.getProgressPercent();
        assertThat(progress25).isBetween(55, 75); // 62.5% ± 10% (timing variability)

        // Check progress at 50ms (~50% through drain = ~75% total)
        Thread.sleep(25);
        var progress50 = coordinator.getProgressPercent();
        assertThat(progress50).isBetween(65, 85); // 75% ± 10%

        // Check progress at 75ms (~75% through drain = ~87.5% total)
        Thread.sleep(25);
        var progress75 = coordinator.getProgressPercent();
        assertThat(progress75).isBetween(78, 98); // 87.5% ± 10%

        // Verify monotonic increase
        assertThat(progress50).isGreaterThan(progress25);
        assertThat(progress75).isGreaterThan(progress50);
    }

    // ========================================
    // Error Condition Tests (4)
    // ========================================

    @Test
    @DisplayName("Error: concurrent initiation attempts handled safely")
    void concurrentInitiationAttempts() throws Exception {
        registerQuorumKeys();

        coordinator = new GenesisTransitionCoordinator(checker, tracker, parameters);

        var successCount = new AtomicInteger(0);
        var exceptionCount = new AtomicInteger(0);
        var latch = new CountDownLatch(2);

        // Two threads attempt initiation simultaneously
        Thread.ofVirtual().start(() -> {
            try {
                coordinator.initiateTransition();
                successCount.incrementAndGet();
            } catch (TransitionInProgressException e) {
                exceptionCount.incrementAndGet();
            } finally {
                latch.countDown();
            }
        });

        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(10); // Slight delay
                coordinator.initiateTransition();
                successCount.incrementAndGet();
            } catch (TransitionInProgressException e) {
                exceptionCount.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        });

        assertThat(latch.await(500, TimeUnit.MILLISECONDS)).isTrue();

        // Exactly one should succeed, one should get TransitionInProgressException
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(exceptionCount.get()).isEqualTo(1);
        assertThat(coordinator.getStatus()).isIn(TransitionStatus.DRAINING, TransitionStatus.COMPLETE);
    }

    @Test
    @DisplayName("Error: null parameters in constructor throws NullPointerException")
    void constructorNullParameters() {
        assertThatThrownBy(() -> new GenesisTransitionCoordinator(checker, tracker, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("parameters");
    }

    @Test
    @DisplayName("Error: getStatus never returns null")
    void getStatusNeverNull() {
        coordinator = new GenesisTransitionCoordinator(checker, tracker, parameters);

        assertThat(coordinator.getStatus()).isNotNull();
    }

    @Test
    @DisplayName("Error: progress percent always in valid range [0, 100]")
    void progressPercentValidRange() throws Exception {
        registerQuorumKeys();

        coordinator = new GenesisTransitionCoordinator(checker, tracker, parameters);

        // Before initiation
        assertThat(coordinator.getProgressPercent()).isBetween(0, 100);

        coordinator.initiateTransition();

        // During drain (check multiple times)
        for (int i = 0; i < 5; i++) {
            assertThat(coordinator.getProgressPercent()).isBetween(0, 100);
            Thread.sleep(20);
        }

        // After completion
        Thread.sleep(50);
        assertThat(coordinator.getProgressPercent()).isBetween(0, 100);
    }
}
