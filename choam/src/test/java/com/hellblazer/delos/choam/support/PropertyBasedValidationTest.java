/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.fsm.Combine;
import java.security.SecureRandom;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.IntStream;

import static com.hellblazer.delos.choam.fsm.Combine.Mercantile.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for state machine validation.
 * Generates random valid transition sequences and verifies:
 * 1. All valid transitions are accepted (no false positives)
 * 2. Invariants hold throughout execution
 * 3. Pre/post conditions are satisfied
 * 4. Snapshot consistency (TOCTOU prevention)
 *
 * Uses SecureRandom with fixed seed for deterministic, reproducible tests.
 *
 * @author hal.hildebrand
 */
public class PropertyBasedValidationTest {

    private StateTransitionValidator validator;
    private SimpleMeterRegistry metrics;
    private SecureRandom random;

    @BeforeEach
    public void setup() {
        var matrix = StateTransitionMatrix.getInstance();
        metrics = new SimpleMeterRegistry();
        validator = new StateTransitionValidator(matrix, metrics);
        random = new SecureRandom();
        random.setSeed("property-based-test-seed".getBytes());
    }

    /**
     * Property: All valid INITIAL→RECOVERING→BOOTSTRAPPING sequences succeed
     */
    @Test
    public void testBootstrapSequenceProperty() {
        // Generate 100 random bootstrap sequences
        IntStream.range(0, 100).forEach(i -> {
            // INITIAL state
            var initialSnapshot = createSnapshot(INITIAL,
                false, false,  // not started
                false, null,   // no committee
                false, false, -1,  // no genesis
                false, -1, 0,  // no view
                0, false, false
            );

            // Validate INITIAL invariant
            var initialInvariant = validator.validateInvariant(initialSnapshot);
            assertTrue(initialInvariant.valid(), "INITIAL invariant should hold");

            // Validate INITIAL→start precondition
            var startPre = validator.validatePrecondition(INITIAL, "start", initialSnapshot);
            assertTrue(startPre.valid(), "INITIAL→start precondition should hold");

            // RECOVERING state after start
            var recoveringSnapshot = createSnapshot(RECOVERING,
                true, false,  // started
                false, null,  // no committee yet
                false, false, -1,  // no genesis yet
                false, -1, 0,  // no view
                0, false, false
            );

            // Validate INITIAL→start postcondition
            var startPost = validator.validatePostcondition(INITIAL, "start", initialSnapshot, recoveringSnapshot);
            assertTrue(startPost.valid(), "INITIAL→start postcondition should hold");

            // Validate RECOVERING invariant
            var recoveringInvariant = validator.validateInvariant(recoveringSnapshot);
            assertTrue(recoveringInvariant.valid(), "RECOVERING invariant should hold");

            // Validate RECOVERING→bootstrap precondition
            var bootstrapPre = validator.validatePrecondition(RECOVERING, "bootstrap", recoveringSnapshot);
            assertTrue(bootstrapPre.valid(), "RECOVERING→bootstrap precondition should hold");

            // BOOTSTRAPPING state after bootstrap
            var bootstrappingSnapshot = createSnapshot(BOOTSTRAPPING,
                true, false,  // started
                true, "GenesisFormation",  // genesis committee
                false, false, -1,  // no genesis block yet
                false, -1, 0,  // no view
                0, true, false  // bootstrap active
            );

            // Validate RECOVERING→bootstrap postcondition
            var bootstrapPost = validator.validatePostcondition(RECOVERING, "bootstrap", recoveringSnapshot, bootstrappingSnapshot);
            assertTrue(bootstrapPost.valid(), "RECOVERING→bootstrap postcondition should hold");

            // Validate BOOTSTRAPPING invariant
            var bootstrappingInvariant = validator.validateInvariant(bootstrappingSnapshot);
            assertTrue(bootstrappingInvariant.valid(), "BOOTSTRAPPING invariant should hold");
        });

        // Verify no violations recorded
        assertEquals(0, metrics.counter("validation.violations").count(), "No false positives expected");
    }

    /**
     * Property: All valid OPERATIONAL loopback transitions succeed
     */
    @Test
    public void testOperationalLoopbackProperty() {
        // Generate 100 random OPERATIONAL loopback sequences
        IntStream.range(0, 100).forEach(i -> {
            var operationalSnapshot = createSnapshot(OPERATIONAL,
                true, false,
                true, "Standard",
                true, true, randomHeight(),
                true, randomHeight(), randomInt(0, 10),
                randomInt(0, 5), false, false
            );

            // Validate invariant
            var invariant = validator.validateInvariant(operationalSnapshot);
            assertTrue(invariant.valid(), "OPERATIONAL invariant should hold");

            // Test all loopback transitions
            List<String> loopbackTransitions = List.of("combine", "rotateViewKeys", "nextView");

            for (var transition : loopbackTransitions) {
                // Validate precondition
                var pre = validator.validatePrecondition(OPERATIONAL, transition, operationalSnapshot);
                assertTrue(pre.valid(), "OPERATIONAL→" + transition + " precondition should hold");

                // Loopback: post-snapshot same as pre-snapshot (state unchanged)
                var post = validator.validatePostcondition(OPERATIONAL, transition, operationalSnapshot, operationalSnapshot);
                assertTrue(post.valid(), "OPERATIONAL→" + transition + " postcondition should hold for loopback");
            }
        });

        // Verify no violations
        assertEquals(0, metrics.counter("validation.violations").count(), "No false positives for loopback transitions");
    }

    /**
     * Property: Random valid state paths never produce violations
     */
    @Test
    public void testRandomValidPathProperty() {
        // Generate 50 random valid paths through the state machine
        IntStream.range(0, 50).forEach(pathId -> {
            var path = generateRandomValidPath();

            // Execute path and verify no violations
            for (var transition : path) {
                var preSnapshot = transition.preSnapshot();
                var postSnapshot = transition.postSnapshot();

                // Validate invariants
                var preInvariant = validator.validateInvariant(preSnapshot);
                assertTrue(preInvariant.valid(), "Pre-transition invariant should hold for " + transition.name());

                var postInvariant = validator.validateInvariant(postSnapshot);
                assertTrue(postInvariant.valid(), "Post-transition invariant should hold for " + transition.name());

                // Validate precondition
                var pre = validator.validatePrecondition(transition.source(), transition.name(), preSnapshot);
                assertTrue(pre.valid(), "Precondition should hold for " + transition.name());

                // Validate postcondition
                var post = validator.validatePostcondition(transition.source(), transition.name(), preSnapshot, postSnapshot);
                assertTrue(post.valid(), "Postcondition should hold for " + transition.name());
            }
        });

        // Verify no violations across all paths
        assertEquals(0, metrics.counter("validation.violations").count(), "No false positives across random paths");
    }

    /**
     * Property: Snapshot consistency (TOCTOU prevention)
     * Verifies that validation reads create consistent snapshots
     */
    @Test
    public void testSnapshotConsistencyProperty() {
        // Generate 100 snapshots and verify internal consistency
        IntStream.range(0, 100).forEach(i -> {
            var state = randomState();
            var snapshot = createValidSnapshotForState(state);

            // Verify snapshot internal consistency
            verifySnapshotConsistency(snapshot);

            // Validate invariant - should always pass for valid snapshot
            var result = validator.validateInvariant(snapshot);
            assertTrue(result.valid(), "Consistent snapshot should satisfy invariant for " + state);
        });

        assertEquals(0, metrics.counter("validation.violations").count());
    }

    /**
     * Property: Validation is deterministic (same snapshot → same result)
     */
    @Test
    public void testDeterministicValidationProperty() {
        // Generate 50 snapshots and validate each 3 times
        IntStream.range(0, 50).forEach(i -> {
            var snapshot = createValidSnapshotForState(randomState());

            // Validate 3 times, results should be identical
            var result1 = validator.validateInvariant(snapshot);
            var result2 = validator.validateInvariant(snapshot);
            var result3 = validator.validateInvariant(snapshot);

            assertEquals(result1.valid(), result2.valid(), "Validation should be deterministic");
            assertEquals(result2.valid(), result3.valid(), "Validation should be deterministic");
        });
    }

    // ==================== Helper Methods ====================

    private List<TransitionRecord> generateRandomValidPath() {
        var path = new ArrayList<TransitionRecord>();

        // All paths start at INITIAL
        var currentState = INITIAL;

        // Generate 3-7 random valid transitions
        int pathLength = random.nextInt(3, 8);

        for (int i = 0; i < pathLength; i++) {
            var validTransitions = getValidTransitionsFrom(currentState);
            if (validTransitions.isEmpty()) break;

            var transitionName = validTransitions.get(random.nextInt(validTransitions.size()));
            var preSnapshot = createValidSnapshotForState(currentState);

            // Determine target state
            var targetState = getTargetState(currentState, transitionName);
            var postSnapshot = createValidSnapshotForState(targetState);

            path.add(new TransitionRecord(currentState, transitionName, preSnapshot, postSnapshot));
            currentState = targetState;
        }

        return path;
    }

    private List<String> getValidTransitionsFrom(Combine.Mercantile state) {
        return switch (state) {
            case INITIAL -> List.of("start");
            case RECOVERING -> List.of("bootstrap", "regenerate", "synchronizationFailed");
            case BOOTSTRAPPING -> List.of("synchronizing");
            case SYNCHRONIZING -> List.of("synchd");
            case OPERATIONAL -> List.of("combine", "rotateViewKeys", "nextView", "beginCheckpoint");
            case CHECKPOINTING -> List.of("finishCheckpoint");
            case REGENERATING -> List.of("combine", "regenerated", "rotateViewKeys", "nextView");
            case AWAITING_REGENERATION -> List.of("combine", "synchronizationFailed");
            case PROTOCOL_FAILURE -> List.of(); // Terminal state
        };
    }

    private Combine.Mercantile getTargetState(Combine.Mercantile source, String transition) {
        return switch (source) {
            case INITIAL -> "start".equals(transition) ? RECOVERING : source;
            case RECOVERING -> switch (transition) {
                case "bootstrap" -> BOOTSTRAPPING;
                case "regenerate" -> REGENERATING;
                case "synchronizationFailed" -> AWAITING_REGENERATION;
                default -> source;
            };
            case BOOTSTRAPPING -> "synchronizing".equals(transition) ? SYNCHRONIZING : source;
            case SYNCHRONIZING -> "synchd".equals(transition) ? OPERATIONAL : source;
            case OPERATIONAL -> switch (transition) {
                case "beginCheckpoint" -> CHECKPOINTING;
                case "regenerate" -> REGENERATING;
                default -> OPERATIONAL; // Loopbacks
            };
            case CHECKPOINTING -> "finishCheckpoint".equals(transition) ? OPERATIONAL : source;
            case REGENERATING -> switch (transition) {
                case "combine" -> REGENERATING;  // Loopback
                case "regenerated" -> OPERATIONAL;
                case "nextView" -> RECOVERING;
                case "rotateViewKeys" -> OPERATIONAL;
                default -> source;
            };
            case AWAITING_REGENERATION -> AWAITING_REGENERATION; // Loopbacks
            case PROTOCOL_FAILURE -> PROTOCOL_FAILURE; // Terminal
        };
    }

    private CHOAMStateSnapshot createValidSnapshotForState(Combine.Mercantile state) {
        return switch (state) {
            case INITIAL -> createSnapshot(state, false, false, false, null, false, false, -1, false, -1, 0, 0, false, false);
            case RECOVERING -> createSnapshot(state, true, false, false, null, false, false, -1, false, -1, 0, 0, false, false);
            case BOOTSTRAPPING -> createSnapshot(state, true, false, true, "GenesisFormation", false, false, -1, false, -1, 0, 0, true, false);
            case SYNCHRONIZING -> createSnapshot(state, true, false, true, "Standard", false, false, -1, false, -1, 0, 0, false, true);
            case OPERATIONAL -> createSnapshot(state, true, false, true, "Standard", true, true, randomHeight(), true, randomHeight(), randomInt(0, 5), randomInt(0, 3), false, false);
            case CHECKPOINTING -> createSnapshot(state, true, false, true, "Standard", true, true, randomHeight(), true, randomHeight(), randomInt(0, 5), randomInt(0, 3), false, false);
            case REGENERATING -> createSnapshot(state, true, false, true, "Standard", false, false, -1, false, -1, randomInt(0, 10), randomInt(0, 3), false, false);
            case AWAITING_REGENERATION -> createSnapshot(state, true, false, false, null, false, false, -1, false, -1, 0, randomInt(1, 5), false, false);
            case PROTOCOL_FAILURE -> {
                // PROTOCOL_FAILURE is terminal - can have any state, but keep it consistent for tests
                boolean started = random.nextBoolean();
                boolean hasCommittee = started && random.nextBoolean();
                boolean hasGenesis = started && hasCommittee && random.nextBoolean();
                boolean hasHead = hasGenesis;  // If has genesis, must have head
                long headHeight = hasHead ? randomHeight() : -1;
                boolean hasView = hasGenesis && random.nextBoolean();
                long viewHeight = hasView ? randomHeight() : -1;
                yield createSnapshot(state, started, false, hasCommittee, hasCommittee ? "Standard" : null,
                    hasGenesis, hasHead, headHeight, hasView, viewHeight,
                    randomInt(0, 5), randomInt(0, 3), false, false);
            }
        };
    }

    private CHOAMStateSnapshot createSnapshot(Combine.Mercantile state,
                                              boolean started, boolean joinOngoing,
                                              boolean hasCommittee, String committeeType,
                                              boolean hasGenesis, boolean hasHead, long headHeight,
                                              boolean hasView, long viewHeight, int pendingViewCount,
                                              int syncAttempts, boolean bootstrapActive, boolean syncScheduled) {
        return new CHOAMStateSnapshot(
            started, joinOngoing,
            hasCommittee, committeeType,
            hasGenesis, hasHead, headHeight,
            hasView, viewHeight, pendingViewCount,
            syncAttempts, bootstrapActive, syncScheduled,
            state.toString()
        );
    }

    private void verifySnapshotConsistency(CHOAMStateSnapshot snapshot) {
        // If has genesis, must have head
        if (snapshot.hasGenesis()) {
            assertTrue(snapshot.hasHead(), "Genesis implies head exists");
            assertTrue(snapshot.headHeight() >= 0, "Genesis implies valid head height");
        }

        // If has head, head height must be valid
        if (snapshot.hasHead()) {
            assertTrue(snapshot.headHeight() >= 0, "Head height must be non-negative");
        }

        // If has view, view height must be valid
        if (snapshot.hasView()) {
            assertTrue(snapshot.viewHeight() >= 0, "View height must be non-negative");
        }

        // If not started, should not have committee/genesis/view
        if (!snapshot.started()) {
            assertFalse(snapshot.hasCommittee(), "Not started implies no committee");
            assertFalse(snapshot.hasGenesis(), "Not started implies no genesis");
            assertFalse(snapshot.hasView(), "Not started implies no view");
        }
    }

    private Combine.Mercantile randomState() {
        var states = Combine.Mercantile.values();
        return states[random.nextInt(states.length)];
    }

    private long randomHeight() {
        return random.nextInt(1, 1000);
    }

    private int randomInt(int min, int max) {
        return random.nextInt(min, max + 1);
    }

    private record TransitionRecord(
        Combine.Mercantile source,
        String name,
        CHOAMStateSnapshot preSnapshot,
        CHOAMStateSnapshot postSnapshot
    ) {}
}
