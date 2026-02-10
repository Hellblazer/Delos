/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.validation.BFTValidator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mock implementation of BFTValidator for unit tests. Provides configurable violation tracking
 * with thread-safe violation recording and retrieval.
 * <p>
 * Thread Safety: All operations are thread-safe using concurrent collections. Violation counts
 * and lists are consistent and atomic.
 *
 * @author hal.hildebrand
 */
public class MockBFTValidator implements BFTValidator {
    private final Map<ByzantineViolationType, Long>               violationCounts = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<ByzantineViolation>       allViolations   = new CopyOnWriteArrayList<>();
    private final Map<ByzantineViolationType, List<ByzantineViolation>> violationsByType = new ConcurrentHashMap<>();
    private final AtomicLong                                      totalViolationCount = new AtomicLong(0);

    /**
     * Configure whether violations should be automatically created for failed validations.
     * If false, mapViolation always returns null. Default: true.
     */
    private volatile boolean detectViolations = true;

    /**
     * Maximum number of violations to retain in history. Default: 100.
     */
    private volatile int maxHistorySize = 100;

    @Override
    public ByzantineViolation mapViolation(ValidationResult result) {
        if (!detectViolations || result.valid()) {
            return null;
        }

        // Determine violation type from result
        var violationType = determineViolationType(result);
        if (violationType == null) {
            return null;  // Unknown failure type, not a Byzantine violation
        }

        // Determine severity from violation type
        var severity = determineSeverity(violationType);

        // Create violation record using factory method
        var violation = ByzantineViolation.from(
            violationType,
            severity,
            result,
            "Mock violation detected during test"
        );

        // Record violation
        recordViolation(violation);

        return violation;
    }

    @Override
    public long getViolationCount(ByzantineViolationType type) {
        return violationCounts.getOrDefault(type, 0L);
    }

    @Override
    public long getTotalViolationCount() {
        return totalViolationCount.get();
    }

    @Override
    public List<ByzantineViolation> getRecentViolations() {
        var size = Math.min(allViolations.size(), maxHistorySize);
        if (size == 0) {
            return Collections.emptyList();
        }
        // Most recent first (reverse chronological)
        var recent = new ArrayList<ByzantineViolation>(size);
        for (int i = allViolations.size() - 1; i >= Math.max(0, allViolations.size() - size); i--) {
            recent.add(allViolations.get(i));
        }
        return Collections.unmodifiableList(recent);
    }

    @Override
    public List<ByzantineViolation> getRecentViolations(ByzantineViolationType type) {
        var typeViolations = violationsByType.get(type);
        if (typeViolations == null || typeViolations.isEmpty()) {
            return Collections.emptyList();
        }
        var size = Math.min(typeViolations.size(), maxHistorySize);
        // Most recent first (reverse chronological)
        var recent = new ArrayList<ByzantineViolation>(size);
        for (int i = typeViolations.size() - 1; i >= Math.max(0, typeViolations.size() - size); i--) {
            recent.add(typeViolations.get(i));
        }
        return Collections.unmodifiableList(recent);
    }

    @Override
    public void reset() {
        totalViolationCount.set(0);  // Reset cumulative count
        violationCounts.clear();
        allViolations.clear();
        violationsByType.clear();
    }

    /**
     * Configure violation detection behavior.
     *
     * @param enabled if true, violations are created for failed validations; if false, always returns null
     */
    public void setDetectViolations(boolean enabled) {
        this.detectViolations = enabled;
    }

    /**
     * Configure maximum history size for violation tracking.
     *
     * @param maxSize maximum number of violations to retain (must be > 0)
     */
    public void setMaxHistorySize(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxHistorySize must be > 0, got: " + maxSize);
        }
        this.maxHistorySize = maxSize;
    }

    /**
     * Record a violation (used internally by mapViolation or for test injection).
     * Enforces maxHistorySize to prevent unbounded memory growth.
     */
    private synchronized void recordViolation(ByzantineViolation violation) {
        totalViolationCount.incrementAndGet();  // Track cumulative count
        allViolations.add(violation);
        violationCounts.merge(violation.type(), 1L, Long::sum);
        violationsByType.computeIfAbsent(violation.type(), k -> new CopyOnWriteArrayList<>()).add(violation);

        // Enforce history size cap to prevent unbounded growth in long-running tests
        if (allViolations.size() > maxHistorySize * 2) {
            // Trim to maxHistorySize (keep most recent)
            // NOTE: CopyOnWriteArrayList.subList().clear() is not thread-safe
            // Create new list with last maxHistorySize elements instead
            var size = allViolations.size();
            var newList = new CopyOnWriteArrayList<>(allViolations.subList(size - maxHistorySize, size));
            allViolations.clear();
            allViolations.addAll(newList);
        }
    }

    /**
     * Determine violation type from validation result.
     * This is a simplified heuristic for testing - production uses more sophisticated analysis.
     */
    private ByzantineViolationType determineViolationType(ValidationResult result) {
        // Use validation type to determine Byzantine violation type
        return switch (result.validationType()) {
            case INVARIANT -> ByzantineViolationType.STATE_INVARIANT_VIOLATION;
            case PRECONDITION -> ByzantineViolationType.PRECONDITION_VIOLATION;
            case POSTCONDITION -> ByzantineViolationType.POSTCONDITION_VIOLATION;
            case ENTRY_ACTION, EXIT_ACTION -> ByzantineViolationType.STATE_INCONSISTENCY;
        };
    }

    /**
     * Determine severity from violation type.
     */
    private ByzantineViolation.Severity determineSeverity(ByzantineViolationType type) {
        return switch (type) {
            case STATE_INVARIANT_VIOLATION, EQUIVOCATION -> ByzantineViolation.Severity.CRITICAL;
            case PRECONDITION_VIOLATION, POSTCONDITION_VIOLATION -> ByzantineViolation.Severity.HIGH;
            case STATE_INCONSISTENCY, TIMING_ANOMALY -> ByzantineViolation.Severity.MEDIUM;
            case UNKNOWN -> ByzantineViolation.Severity.LOW;
        };
    }
}
