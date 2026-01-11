/*
 * Copyright (c) 2026, Delos Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies.regression;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.fireflies.View;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.membership.RoundScheduler;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentMap;

/**
 * State invariant checkers for Fireflies View concurrent data structures.
 * Uses reflection to access private fields for testing purposes.
 * Thread-safe for use in concurrent test scenarios.
 *
 * @author hal.hildebrand
 */
public final class FirefliesStateInvariants {

    private FirefliesStateInvariants() {} // Utility class

    /**
     * Record representing an invariant violation with full context.
     */
    public record InvariantViolation(
        String invariantName,
        String description,
        Object expectedValue,
        Object actualValue,
        Instant timestamp,
        StackTraceElement[] stackTrace
    ) {
        public InvariantViolation(String name, String desc, Object expected, Object actual) {
            this(name, desc, expected, actual, Instant.now(), Thread.currentThread().getStackTrace());
        }
    }

    /**
     * Result of invariant check with optional violation.
     */
    public record InvariantCheckResult(
        String invariantName,
        boolean passed,
        Optional<InvariantViolation> violation
    ) {
        public static InvariantCheckResult pass(String name) {
            return new InvariantCheckResult(name, true, Optional.empty());
        }

        public static InvariantCheckResult fail(InvariantViolation violation) {
            return new InvariantCheckResult(violation.invariantName(), false, Optional.of(violation));
        }
    }

    // ========== Invariant Checkers ==========

    /**
     * Check: observations.size() <= context.size()
     * The observations map should not contain more entries than context members.
     */
    @SuppressWarnings("unchecked")
    public static InvariantCheckResult checkObservationsSizeInvariant(View view) {
        var invariantName = "OBSERVATIONS_SIZE_BOUND";
        try {
            var observations = getPrivateField(view, "observations", Map.class);
            var context = view.getContext();
            var observationsSize = observations.size();
            var contextSize = context.size();

            if (observationsSize <= contextSize) {
                return InvariantCheckResult.pass(invariantName);
            }

            return InvariantCheckResult.fail(new InvariantViolation(
                invariantName,
                "observations.size() exceeds context.size()",
                "observations.size() <= " + contextSize,
                observationsSize
            ));
        } catch (Exception e) {
            return InvariantCheckResult.fail(new InvariantViolation(
                invariantName,
                "Failed to check invariant: " + e.getMessage(),
                "No exception",
                e.getClass().getSimpleName()
            ));
        }
    }

    /**
     * Check: observations keys are valid member IDs
     * Each observation should be from a member that exists in context.
     */
    @SuppressWarnings("unchecked")
    public static InvariantCheckResult checkObservationsKeysInvariant(View view) {
        var invariantName = "OBSERVATIONS_VALID_KEYS";
        try {
            var observations = getPrivateField(view, "observations", Map.class);
            var context = view.getContext();

            var invalidKeys = new ArrayList<Digest>();
            for (var key : ((Map<Digest, ?>) observations).keySet()) {
                if (context.getMember(key) == null) {
                    invalidKeys.add(key);
                }
            }

            if (invalidKeys.isEmpty()) {
                return InvariantCheckResult.pass(invariantName);
            }

            return InvariantCheckResult.fail(new InvariantViolation(
                invariantName,
                "observations contains keys for non-existent members",
                "All keys in context",
                invalidKeys.size() + " invalid keys: " + invalidKeys
            ));
        } catch (Exception e) {
            return InvariantCheckResult.fail(new InvariantViolation(
                invariantName,
                "Failed to check invariant: " + e.getMessage(),
                "No exception",
                e.getClass().getSimpleName()
            ));
        }
    }

    /**
     * Check: pendingRebuttals keys are valid member IDs in context
     * Each pending rebuttal should be for a member that exists in context.
     */
    @SuppressWarnings("unchecked")
    public static InvariantCheckResult checkPendingRebuttalsInvariant(View view) {
        var invariantName = "PENDING_REBUTTALS_VALID_MEMBERS";
        try {
            var pendingRebuttals = getPrivateField(view, "pendingRebuttals", ConcurrentMap.class);
            var context = view.getContext();

            var invalidKeys = new ArrayList<Digest>();
            for (var entry : ((ConcurrentMap<Digest, RoundScheduler.Timer>) pendingRebuttals).entrySet()) {
                var memberId = entry.getKey();
                var member = context.getMember(memberId);

                // Member must exist in context
                if (member == null) {
                    invalidKeys.add(memberId);
                }
            }

            if (invalidKeys.isEmpty()) {
                return InvariantCheckResult.pass(invariantName);
            }

            return InvariantCheckResult.fail(new InvariantViolation(
                invariantName,
                "pendingRebuttals contains keys for non-existent members",
                "All keys for members in context",
                invalidKeys.size() + " non-existent: " + invalidKeys
            ));
        } catch (Exception e) {
            return InvariantCheckResult.fail(new InvariantViolation(
                invariantName,
                "Failed to check invariant: " + e.getMessage(),
                "No exception",
                e.getClass().getSimpleName()
            ));
        }
    }

    /**
     * Check: shunned members are not active in context
     * Shunned members should be offline or completely removed.
     */
    @SuppressWarnings("unchecked")
    public static InvariantCheckResult checkShunnedNotActiveInvariant(View view) {
        var invariantName = "SHUNNED_NOT_ACTIVE";
        try {
            var shunned = getPrivateField(view, "shunned", Set.class);
            var context = view.getContext();

            var activeShunned = new ArrayList<Digest>();
            for (var id : (Set<Digest>) shunned) {
                if (context.isActive(id)) {
                    activeShunned.add(id);
                }
            }

            if (activeShunned.isEmpty()) {
                return InvariantCheckResult.pass(invariantName);
            }

            return InvariantCheckResult.fail(new InvariantViolation(
                invariantName,
                "Shunned members are still active in context",
                "No shunned members active",
                activeShunned.size() + " still active: " + activeShunned
            ));
        } catch (Exception e) {
            return InvariantCheckResult.fail(new InvariantViolation(
                invariantName,
                "Failed to check invariant: " + e.getMessage(),
                "No exception",
                e.getClass().getSimpleName()
            ));
        }
    }

    /**
     * Check: viewSerialization semaphore permits in valid range [0,1]
     * The viewSerialization semaphore should never have more than 1 permit.
     */
    public static InvariantCheckResult checkViewSerializationInvariant(View view) {
        var invariantName = "VIEW_SERIALIZATION_PERMITS";
        try {
            var semaphore = getPrivateField(view, "viewSerialization", java.util.concurrent.Semaphore.class);
            var permits = semaphore.availablePermits();

            if (permits >= 0 && permits <= 1) {
                return InvariantCheckResult.pass(invariantName);
            }

            return InvariantCheckResult.fail(new InvariantViolation(
                invariantName,
                "viewSerialization semaphore has invalid permit count",
                "permits in [0,1]",
                permits
            ));
        } catch (Exception e) {
            return InvariantCheckResult.fail(new InvariantViolation(
                invariantName,
                "Failed to check invariant: " + e.getMessage(),
                "No exception",
                e.getClass().getSimpleName()
            ));
        }
    }

    /**
     * Check all invariants and return results.
     */
    public static List<InvariantCheckResult> checkAllInvariants(View view) {
        return List.of(
            checkObservationsSizeInvariant(view),
            checkObservationsKeysInvariant(view),
            checkPendingRebuttalsInvariant(view),
            checkShunnedNotActiveInvariant(view),
            checkViewSerializationInvariant(view)
        );
    }

    /**
     * Check all invariants and return only violations.
     */
    public static List<InvariantViolation> getViolations(View view) {
        return checkAllInvariants(view).stream()
            .filter(r -> !r.passed())
            .map(r -> r.violation().orElseThrow())
            .toList();
    }

    /**
     * Assert all invariants pass, throwing AssertionError if any fail.
     */
    public static void assertAllInvariants(View view) {
        var violations = getViolations(view);
        if (!violations.isEmpty()) {
            var sb = new StringBuilder("Invariant violations detected:\n");
            for (var v : violations) {
                sb.append("  - ").append(v.invariantName())
                  .append(": ").append(v.description())
                  .append(" (expected: ").append(v.expectedValue())
                  .append(", actual: ").append(v.actualValue())
                  .append(")\n");
            }
            throw new AssertionError(sb.toString());
        }
    }

    // ========== Reflection Helpers ==========

    @SuppressWarnings("unchecked")
    private static <T> T getPrivateField(Object obj, String fieldName, Class<T> type)
            throws NoSuchFieldException, IllegalAccessException {
        var field = findField(obj.getClass(), fieldName);
        field.setAccessible(true);
        return (T) field.get(obj);
    }

    private static Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        var current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName + " not found in " + clazz.getName());
    }
}
