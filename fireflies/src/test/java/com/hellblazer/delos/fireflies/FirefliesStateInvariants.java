/*
 * Copyright (c) 2025, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.membership.RoundScheduler;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reusable state invariant checkers for Fireflies View concurrent data structures.
 * Uses reflection to access private fields for testing without modifying production code.
 *
 * @author hal.hildebrand
 */
public final class FirefliesStateInvariants {

    private FirefliesStateInvariants() {
        // utility class
    }

    /**
     * Result of an invariant check.
     */
    public record InvariantResult(String invariantName, boolean passed, String message) {
        public static InvariantResult pass(String name) {
            return new InvariantResult(name, true, "OK");
        }

        public static InvariantResult fail(String name, String message) {
            return new InvariantResult(name, false, message);
        }
    }

    /**
     * Check that observations size does not exceed context size.
     * Invariant: observations.size() <= context.size()
     */
    public static InvariantResult checkObservationsSizeInvariant(View view) {
        try {
            var observations = getObservations(view);
            var contextSize = view.getContext().size();
            var observationsSize = observations.size();

            if (observationsSize <= contextSize) {
                return InvariantResult.pass("observations.size() <= context.size()");
            } else {
                return InvariantResult.fail("observations.size() <= context.size()",
                                            String.format("observations=%d > context=%d", observationsSize, contextSize));
            }
        } catch (Exception e) {
            return InvariantResult.fail("observations.size() <= context.size()", "Reflection failed: " + e.getMessage());
        }
    }

    /**
     * Check that all observation keys are valid member IDs.
     * Invariant: all observation keys exist in context as members
     */
    public static InvariantResult checkObservationsKeysInvariant(View view) {
        try {
            var observations = getObservations(view);
            var activeIds = view.getContext()
                                .activeMembers()
                                .stream()
                                .map(Participant::getId)
                                .collect(Collectors.toSet());

            var invalidKeys = observations.keySet()
                                          .stream()
                                          .filter(id -> !activeIds.contains(id))
                                          .toList();

            if (invalidKeys.isEmpty()) {
                return InvariantResult.pass("observations keys are valid member IDs");
            } else {
                return InvariantResult.fail("observations keys are valid member IDs",
                                            String.format("invalid keys: %s", invalidKeys));
            }
        } catch (Exception e) {
            return InvariantResult.fail("observations keys are valid member IDs", "Reflection failed: " + e.getMessage());
        }
    }

    /**
     * Check that pendingRebuttals only contains accused member IDs.
     * This is a weaker invariant - we check that all keys are valid digest IDs.
     */
    public static InvariantResult checkPendingRebuttalsInvariant(View view) {
        try {
            var pendingRebuttals = getPendingRebuttals(view);

            // Keys should be non-null digests
            var nullKeys = pendingRebuttals.keySet().stream().filter(k -> k == null).count();
            if (nullKeys > 0) {
                return InvariantResult.fail("pendingRebuttals has valid keys",
                                            String.format("%d null keys found", nullKeys));
            }

            return InvariantResult.pass("pendingRebuttals has valid keys");
        } catch (Exception e) {
            return InvariantResult.fail("pendingRebuttals has valid keys", "Reflection failed: " + e.getMessage());
        }
    }

    /**
     * Check that shunned members are not active in the context.
     * Invariant: shunned.intersection(activeMembers) == empty
     */
    public static InvariantResult checkShunnedMembersInvariant(View view) {
        try {
            var shunned = getShunned(view);
            var activeIds = view.getContext()
                                .activeMembers()
                                .stream()
                                .map(Participant::getId)
                                .collect(Collectors.toSet());

            var shunnedButActive = shunned.stream().filter(activeIds::contains).toList();

            if (shunnedButActive.isEmpty()) {
                return InvariantResult.pass("shunned members are not active");
            } else {
                return InvariantResult.fail("shunned members are not active",
                                            String.format("shunned but active: %s", shunnedButActive));
            }
        } catch (Exception e) {
            return InvariantResult.fail("shunned members are not active", "Reflection failed: " + e.getMessage());
        }
    }

    /**
     * Run all invariant checks and return results.
     */
    public static List<InvariantResult> checkAllInvariants(View view) {
        var results = new ArrayList<InvariantResult>();
        results.add(checkObservationsSizeInvariant(view));
        results.add(checkObservationsKeysInvariant(view));
        results.add(checkPendingRebuttalsInvariant(view));
        results.add(checkShunnedMembersInvariant(view));
        return results;
    }

    /**
     * Get all invariant violations (failed checks).
     */
    public static List<InvariantResult> getViolations(View view) {
        return checkAllInvariants(view).stream().filter(r -> !r.passed()).toList();
    }

    /**
     * Assert that all invariants hold. Throws AssertionError if any fail.
     */
    public static void assertAllInvariants(View view) {
        var violations = getViolations(view);
        assertTrue(violations.isEmpty(),
                   "State invariant violations: " + violations.stream()
                                                              .map(v -> v.invariantName() + ": " + v.message())
                                                              .collect(Collectors.joining("; ")));
    }

    /**
     * Assert that all invariants hold for multiple views.
     */
    public static void assertAllInvariants(List<View> views) {
        for (var view : views) {
            assertAllInvariants(view);
        }
    }

    // Reflection helpers

    @SuppressWarnings("unchecked")
    private static Map<Digest, ?> getObservations(View view) throws Exception {
        var field = View.class.getDeclaredField("observations");
        field.setAccessible(true);
        return (Map<Digest, ?>) field.get(view);
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentMap<Digest, RoundScheduler.Timer> getPendingRebuttals(View view) throws Exception {
        var field = View.class.getDeclaredField("pendingRebuttals");
        field.setAccessible(true);
        return (ConcurrentMap<Digest, RoundScheduler.Timer>) field.get(view);
    }

    @SuppressWarnings("unchecked")
    private static Set<Digest> getShunned(View view) throws Exception {
        var field = View.class.getDeclaredField("shunned");
        field.setAccessible(true);
        return (Set<Digest>) field.get(view);
    }

    /**
     * Get the current state snapshot for debugging.
     */
    public static StateSnapshot getStateSnapshot(View view) {
        try {
            var observations = getObservations(view);
            var pendingRebuttals = getPendingRebuttals(view);
            var shunned = getShunned(view);
            var activeCount = view.getContext().activeCount();
            var contextSize = view.getContext().size();

            return new StateSnapshot(observations.size(), pendingRebuttals.size(), shunned.size(), activeCount,
                                     contextSize);
        } catch (Exception e) {
            return new StateSnapshot(-1, -1, -1, -1, -1);
        }
    }

    /**
     * Snapshot of view state for debugging and comparison.
     */
    public record StateSnapshot(int observationsSize, int pendingRebuttalsSize, int shunnedSize, int activeCount,
                                int contextSize) {
        @Override
        public String toString() {
            return String.format("StateSnapshot[observations=%d, pendingRebuttals=%d, shunned=%d, active=%d, context=%d]",
                                 observationsSize, pendingRebuttalsSize, shunnedSize, activeCount, contextSize);
        }
    }
}
