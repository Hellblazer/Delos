/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine.testing;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.byzantine.IntelligenceConfig;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Abstract base class for Byzantine cluster tests.
 * <p>
 * Provides common test infrastructure:
 * <ul>
 *   <li>Deterministic entropy for reproducibility</li>
 *   <li>Fixed clock for timing tests</li>
 *   <li>Test harness with standard layers</li>
 *   <li>Automatic cleanup after each test</li>
 * </ul>
 * <p>
 * Subclasses implement specific test scenarios:
 * <pre>{@code
 * class EquivocationDetectionTest extends AbstractByzantineClusterTest {
 *
 *     @Test
 *     void shouldDetectEquivocation() {
 *         var member = createMember("byzantine-node");
 *         injectEquivocation(member);
 *
 *         var result = evaluate(member);
 *
 *         assertThat(result.isAnomalous()).isTrue();
 *         assertThat(result.compositeScore()).isGreaterThan(0.8);
 *     }
 * }
 * }</pre>
 *
 * @author hal.hildebrand
 * @see ByzantineTestHarness
 */
public abstract class AbstractByzantineClusterTest {

    protected ByzantineTestHarness harness;
    protected SecureRandom entropy;
    protected Clock clock;
    protected List<Identifier> testMembers;

    /**
     * Sets up test infrastructure before each test.
     */
    @BeforeEach
    protected void setUp() {
        entropy = createDeterministicEntropy();
        clock = createFixedClock();
        testMembers = new ArrayList<>();

        harness = ByzantineTestHarness.builder()
                                       .withEntropy(entropy)
                                       .withClock(clock)
                                       .withStandardLayers()
                                       .withConfig(createConfig())
                                       .build();
    }

    /**
     * Cleans up after each test.
     */
    @AfterEach
    protected void tearDown() {
        if (harness != null) {
            harness.close();
        }
        testMembers.clear();
    }

    // ========== Factory Methods ==========

    /**
     * Creates deterministic entropy for reproducible tests.
     *
     * @return seeded secure random
     */
    protected SecureRandom createDeterministicEntropy() {
        try {
            var random = SecureRandom.getInstance("SHA1PRNG");
            random.setSeed(new byte[] { 6, 6, 6 });
            return random;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create deterministic entropy", e);
        }
    }

    /**
     * Creates a fixed clock for deterministic timing.
     *
     * @return fixed clock at known instant
     */
    protected Clock createFixedClock() {
        return Clock.fixed(Instant.parse("2026-01-31T12:00:00Z"), ZoneId.of("UTC"));
    }

    /**
     * Creates the intelligence config for tests.
     * Override to customize thresholds.
     *
     * @return intelligence config
     */
    protected IntelligenceConfig createConfig() {
        return IntelligenceConfig.builder()
                                  .criticalThreshold(0.8)
                                  .warningThreshold(0.4)
                                  .defaultPollInterval(Duration.ofMillis(100))
                                  .build();
    }

    // ========== Member Creation ==========

    /**
     * Creates a test member with deterministic ID.
     *
     * @param seed the seed string
     * @return member identifier
     */
    protected Identifier createMember(String seed) {
        var member = harness.createMember(seed);
        testMembers.add(member);
        return member;
    }

    /**
     * Creates multiple test members.
     *
     * @param count number of members
     * @param prefix name prefix
     * @return list of member identifiers
     */
    protected List<Identifier> createMembers(int count, String prefix) {
        var members = new ArrayList<Identifier>();
        for (int i = 0; i < count; i++) {
            members.add(createMember(prefix + "-" + i));
        }
        return members;
    }

    // ========== Fault Injection Shortcuts ==========

    /**
     * Injects a crash fault on the default layer (fireflies).
     *
     * @param memberId the member
     * @return injection handle
     */
    protected FaultInjectionHandle injectCrash(Identifier memberId) {
        return harness.injectFault(IntelligenceConfig.LAYER_FIREFLIES, memberId, FaultType.CRASH);
    }

    /**
     * Injects a delay fault.
     *
     * @param memberId the member
     * @param delay    the delay duration
     * @return injection handle
     */
    protected FaultInjectionHandle injectDelay(Identifier memberId, Duration delay) {
        return harness.injectFault(IntelligenceConfig.LAYER_FIREFLIES, memberId,
                                   FaultType.DELAY, delay);
    }

    /**
     * Injects an equivocation fault on the default layer.
     *
     * @param memberId the member
     * @return injection handle
     */
    protected FaultInjectionHandle injectEquivocation(Identifier memberId) {
        return harness.injectFault(IntelligenceConfig.LAYER_FIREFLIES, memberId,
                                   FaultType.EQUIVOCATION);
    }

    /**
     * Injects a signature forgery fault.
     *
     * @param memberId the member
     * @return injection handle
     */
    protected FaultInjectionHandle injectSignatureForgery(Identifier memberId) {
        return harness.injectFault(IntelligenceConfig.LAYER_GORGONEION, memberId,
                                   FaultType.SIGNATURE_FORGERY);
    }

    /**
     * Injects a timing attack fault.
     *
     * @param memberId the member
     * @return injection handle
     */
    protected FaultInjectionHandle injectTimingAttack(Identifier memberId) {
        return harness.injectFault(IntelligenceConfig.LAYER_THOTH, memberId,
                                   FaultType.TIMING_ATTACK);
    }

    /**
     * Injects faults on multiple layers.
     *
     * @param memberId the member
     * @param layers   layer names to inject on
     * @param type     the fault type
     * @return list of injection handles
     */
    protected List<FaultInjectionHandle> injectMultiLayer(Identifier memberId,
                                                           List<String> layers,
                                                           FaultType type) {
        var handles = new ArrayList<FaultInjectionHandle>();
        for (var layer : layers) {
            handles.add(harness.injectFault(layer, memberId, type));
        }
        return handles;
    }

    // ========== Detection ==========

    /**
     * Evaluates a member and returns detection result.
     *
     * @param memberId the member
     * @return detection result
     */
    protected DetectionResult evaluate(Identifier memberId) {
        return harness.evaluate(memberId);
    }

    /**
     * Runs a polling cycle.
     */
    protected void poll() {
        harness.runPollingCycle();
    }

    // ========== Assertions ==========

    /**
     * Asserts that a member is detected as anomalous.
     *
     * @param memberId the member
     */
    protected void assertAnomalous(Identifier memberId) {
        var result = evaluate(memberId);
        if (!result.isAnomalous()) {
            throw new AssertionError("Expected member " + memberId +
                                     " to be anomalous but was not. Score: " + result.compositeScore());
        }
    }

    /**
     * Asserts that a member is not detected as anomalous.
     *
     * @param memberId the member
     */
    protected void assertNotAnomalous(Identifier memberId) {
        var result = evaluate(memberId);
        if (result.isAnomalous()) {
            throw new AssertionError("Expected member " + memberId +
                                     " to NOT be anomalous but was. Score: " + result.compositeScore());
        }
    }

    /**
     * Asserts that the critical threshold was exceeded.
     *
     * @param memberId the member
     */
    protected void assertCritical(Identifier memberId) {
        var result = evaluate(memberId);
        if (!result.isCritical()) {
            throw new AssertionError("Expected member " + memberId +
                                     " to be critical but was not. Score: " + result.compositeScore());
        }
    }

    /**
     * Asserts detection metrics meet requirements.
     *
     * @param minTruePositiveRate minimum TPR
     * @param maxFalsePositiveRate maximum FPR
     */
    protected void assertMetricsWithin(double minTruePositiveRate, double maxFalsePositiveRate) {
        var metrics = harness.getMetrics();
        var tpr = metrics.getTruePositiveRate();
        var fpr = metrics.getFalsePositiveRate();

        if (tpr < minTruePositiveRate) {
            throw new AssertionError("True positive rate " + tpr +
                                     " below minimum " + minTruePositiveRate);
        }
        if (fpr > maxFalsePositiveRate) {
            throw new AssertionError("False positive rate " + fpr +
                                     " above maximum " + maxFalsePositiveRate);
        }
    }

    // ========== Utilities ==========

    /**
     * Gets the metrics collector.
     *
     * @return metrics
     */
    protected ByzantineMetricsCollector getMetrics() {
        return harness.getMetrics();
    }

    /**
     * Gets the response handler.
     *
     * @return response handler
     */
    protected ByzantineTestHarness.TrackingResponseHandler getResponseHandler() {
        return harness.getResponseHandler();
    }

    /**
     * Waits for a specified duration (use sparingly).
     *
     * @param duration the duration
     */
    protected void waitFor(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
