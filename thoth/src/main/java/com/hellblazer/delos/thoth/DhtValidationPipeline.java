/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import com.google.protobuf.Empty;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.KERL;
import com.hellblazer.delos.stereotomy.event.EstablishmentEvent;
import com.hellblazer.delos.stereotomy.event.proto.KeyState_;
import com.hellblazer.delos.thoth.metrics.KerlDhtMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hellblazer.delos.thoth.support.ValidationCircuitBreaker;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Post-quorum validation pipeline for DHT responses.
 * <p>
 * Validates response content using KERI cryptography (Ani) and reports
 * Byzantine behavior to ThothByzantineStateProvider. Validation is
 * advisory-only: failures are logged and reported but do not reject responses.
 * </p>
 * <p>
 * Thread Safety: This class is thread-safe. Instances are shared across
 * concurrent virtual threads handling different quorum operations. All
 * dependencies (Ani, KERL, ByzantineProvider, Metrics) must be thread-safe.
 * QuorumResponseTracker access is protected by sequential callback execution
 * in SliceIterator.
 * </p>
 *
 * @author hal.hildebrand
 */
public class DhtValidationPipeline {
    private static final Logger                      log = LoggerFactory.getLogger(DhtValidationPipeline.class);
    private final        Ani                         ani;
    private final        KERL                        kerl;
    private final        Duration                    validationTimeout;
    private final        ThothByzantineStateProvider byzantineProvider;
    private final        KerlDhtMetrics              metrics;
    private final        ValidationCircuitBreaker    circuitBreaker;
    private final        ScheduledExecutorService    scheduler;

    public DhtValidationPipeline(Ani ani, KERL kerl, Duration validationTimeout,
                                 ThothByzantineStateProvider byzantineProvider, KerlDhtMetrics metrics,
                                 ScheduledExecutorService scheduler) {
        this.ani = ani;
        this.kerl = kerl;
        this.validationTimeout = validationTimeout;
        this.byzantineProvider = byzantineProvider;
        this.metrics = metrics;
        this.circuitBreaker = new ValidationCircuitBreaker(); // 10 failures, 1 minute timeout
        this.scheduler = scheduler;
    }

    /**
     * Validate a KeyState_ response from quorum.
     * <p>
     * Phase 3 implementation: Basic structural validation with Ani integration.
     * Validates that the KeyState_ has establishment event that can be validated.
     * Validation failures are advisory-only: reported but do not reject response.
     * </p>
     *
     * @param state     KeyState_ to validate
     * @param providers Members that provided this state
     * @return ValidationResult indicating success or failure
     */
    public ValidationResult<KeyState_> validateKeyState(KeyState_ state, Set<Member> providers) {
        // Null or default state is valid (empty response)
        if (state == null || state.equals(KeyState_.getDefaultInstance())) {
            return ValidationResult.valid(state, "keyState");
        }

        // Circuit breaker: Skip validation if circuit is open (infrastructure failing)
        if (circuitBreaker.isOpen()) {
            log.debug("Validation circuit breaker OPEN - skipping KeyState validation");
            metrics.incrementValidationSkipped("keyState", "circuit_breaker_open");
            return ValidationResult.valid(state, "keyState");
        }

        var startTime = System.nanoTime();
        try {
            // Phase 3: Basic validation - check if state has establishment event
            if (!state.hasLastEstablishmentEvent()) {
                // No establishment event to validate
                metrics.incrementValidationSkipped("keyState", "no_establishment_event");
                metrics.recordValidationLatency("keyState", System.nanoTime() - startTime);
                return ValidationResult.valid(state, "keyState");
            }

            // Get coordinates for the establishment event
            var estCoords = EventCoordinates.from(state.getLastEstablishmentEvent());
            var validation = ani.eventValidation(validationTimeout);

            // Try to validate the establishment event
            var event = kerl.getKeyEvent(estCoords);
            if (event == null) {
                // Event not found in local KERL - Byzantine attack vector
                var reason = "Establishment event not found in local KERL: " + estCoords;
                log.warn("KeyState validation failed: {} from members: {}", reason,
                         providers.stream().map(m -> m.getId().toString()).toList());
                metrics.recordValidationLatency("keyState", System.nanoTime() - startTime);
                return ValidationResult.invalid(state, providers, reason, "keyState");
            }
            if (!(event instanceof EstablishmentEvent est)) {
                // Wrong event type - Byzantine attack vector
                var reason = "Expected EstablishmentEvent but got " + event.getClass().getSimpleName() + " for "
                             + estCoords;
                log.warn("KeyState validation failed: {} from members: {}", reason,
                         providers.stream().map(m -> m.getId().toString()).toList());
                metrics.recordValidationLatency("keyState", System.nanoTime() - startTime);
                return ValidationResult.invalid(state, providers, reason, "keyState");
            }
            // Validate establishment event with Ani
            if (!validation.validate(est)) {
                var reason = "Establishment event validation failed for " + estCoords;
                log.warn("KeyState validation failed: {} from members: {}", reason,
                         providers.stream().map(m -> m.getId().toString()).toList());
                circuitBreaker.recordSuccess(); // Validation executed (even if failed), infrastructure is OK
                metrics.recordValidationLatency("keyState", System.nanoTime() - startTime);
                return ValidationResult.invalid(state, providers, reason, "keyState");
            }

            metrics.incrementValidationSuccess("keyState");
            circuitBreaker.recordSuccess(); // Validation succeeded
            metrics.recordValidationLatency("keyState", System.nanoTime() - startTime);
            return ValidationResult.valid(state, "keyState");

        } catch (Exception e) {
            // Check if this is an infrastructure failure (data access) or unexpected error
            var isInfrastructureFailure = isInfrastructureException(e);
            if (isInfrastructureFailure) {
                // Data access failure (KERL unavailable) - fail open with warning
                circuitBreaker.recordFailure(); // Infrastructure failing - increment circuit breaker
                log.warn("KERL access failure during validation - accepting response: {}", e.getMessage(), e);
                metrics.incrementValidationSkipped("keyState", "kerl_access_failure");
                metrics.recordValidationLatency("keyState", System.nanoTime() - startTime);
                return ValidationResult.valid(state, "keyState");
            } else {
                // Unexpected error (programming error, resource exhaustion) - fail closed to be safe
                circuitBreaker.recordSuccess(); // Validation executed, infrastructure is OK (even if logic error)
                var reason = "Infrastructure error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
                log.error("Unexpected validation error - rejecting response: {}", reason, e);
                metrics.recordValidationLatency("keyState", System.nanoTime() - startTime);
                return ValidationResult.invalid(state, providers, reason, "keyState");
            }
        }
    }

    /**
     * Validate a KeyStates response from write operations (append).
     * <p>
     * Phase 4 implementation: Validates each KeyState_ in the response in parallel.
     * Checks that returned states are structurally valid and can be verified.
     * Validation failures are advisory-only: reported but do not reject response.
     * </p>
     * <p>
     * Performance: States are validated concurrently using CompletableFuture to
     * reduce total validation time for large batches.
     * </p>
     *
     * @param keyStates KeyStates response containing list of KeyState_
     * @param providers Members that provided this response
     * @return ValidationResult indicating success or failure
     */
    public ValidationResult<com.hellblazer.delos.stereotomy.services.grpc.proto.KeyStates> validateKeyStates(
        com.hellblazer.delos.stereotomy.services.grpc.proto.KeyStates keyStates, Set<Member> providers) {
        // Null or default response is valid (empty response)
        if (keyStates == null
            || keyStates.equals(com.hellblazer.delos.stereotomy.services.grpc.proto.KeyStates.getDefaultInstance())) {
            return ValidationResult.valid(keyStates, "keyStates");
        }

        // Circuit breaker: Skip validation if circuit is open (infrastructure failing)
        if (circuitBreaker.isOpen()) {
            log.debug("Validation circuit breaker OPEN - skipping KeyStates validation");
            metrics.incrementValidationSkipped("keyStates", "circuit_breaker_open");
            return ValidationResult.valid(keyStates, "keyStates");
        }

        var startTime = System.nanoTime();
        try {
            // Validate each KeyState_ in the response - parallelize for performance
            var states = keyStates.getKeyStatesList();

            // Create parallel validation tasks for all states
            var validationFutures = states.stream()
                .map(state -> CompletableFuture.supplyAsync(() -> validateKeyState(state, providers), scheduler))
                .toList();

            // Wait for all validations to complete
            CompletableFuture.allOf(validationFutures.toArray(new CompletableFuture[0])).join();

            // Check if any validation failed
            for (var future : validationFutures) {
                var stateResult = future.join();
                if (!stateResult.valid()) {
                    // One invalid state makes the whole response invalid
                    var reason = "KeyStates contains invalid state: " + stateResult.failureReason();
                    log.warn("KeyStates validation failed: {} from members: {}", reason,
                             providers.stream().map(m -> m.getId().toString()).toList());
                    metrics.recordValidationLatency("keyStates", System.nanoTime() - startTime);
                    return ValidationResult.invalid(keyStates, providers, reason, "keyStates");
                }
            }

            metrics.incrementValidationSuccess("keyStates");
            circuitBreaker.recordSuccess(); // Validation succeeded
            metrics.recordValidationLatency("keyStates", System.nanoTime() - startTime);
            return ValidationResult.valid(keyStates, "keyStates");

        } catch (Exception e) {
            // Check if this is an infrastructure failure (data access) or unexpected error
            var isInfrastructureFailure = isInfrastructureException(e);
            if (isInfrastructureFailure) {
                // Data access failure (KERL unavailable) - fail open with warning
                circuitBreaker.recordFailure(); // Infrastructure failing - increment circuit breaker
                log.warn("KERL access failure during KeyStates validation - accepting response: {}", e.getMessage(),
                         e);
                metrics.incrementValidationSkipped("keyStates", "kerl_access_failure");
                metrics.recordValidationLatency("keyStates", System.nanoTime() - startTime);
                return ValidationResult.valid(keyStates, "keyStates");
            } else {
                // Unexpected error (programming error, resource exhaustion) - fail closed to be safe
                circuitBreaker.recordSuccess(); // Validation executed, infrastructure is OK (even if logic error)
                var reason = "Infrastructure error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
                log.error("Unexpected KeyStates validation error - rejecting response: {}", reason, e);
                metrics.recordValidationLatency("keyStates", System.nanoTime() - startTime);
                return ValidationResult.invalid(keyStates, providers, reason, "keyStates");
            }
        }
    }

    /**
     * Validate an Empty response (used in mutate operations).
     * <p>
     * Empty responses don't have content to validate - always valid.
     * </p>
     *
     * @param empty     Empty response
     * @param providers Members that provided this response
     * @return ValidationResult indicating success
     */
    public ValidationResult<Empty> validateEmpty(Empty empty, Set<Member> providers) {
        return ValidationResult.valid(empty, "empty");
    }

    /**
     * Check if exception indicates infrastructure failure (data access) vs unexpected error.
     * <p>
     * Infrastructure failures (SQL/IO errors) should fail open (accept response).
     * Unexpected errors (NPE, illegal state) should fail closed (reject response).
     * </p>
     *
     * @param e Exception to check
     * @return true if infrastructure failure, false if unexpected error
     */
    private boolean isInfrastructureException(Exception e) {
        // Check exception and its cause chain for SQL/IO errors
        var current = (Throwable) e;
        while (current != null) {
            var className = current.getClass().getName();
            var message = current.getMessage() != null ? current.getMessage() : "";
            // Check for SQL, IO, or database-related exceptions (class name or message)
            if (className.contains("SQLException") || className.contains("IOException")
                || className.contains("Database") || className.contains("Connection")
                || className.contains("JDBCException") || message.contains("SQLException:")
                || message.contains("IOException:") || message.contains("Database connection")
                || message.contains("I/O error")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Report validation failure to Byzantine detection infrastructure.
     * <p>
     * Records validation failures to ThothByzantineStateProvider for anomaly
     * tracking and reports Byzantine detection metrics.
     * </p>
     *
     * @param result Validation result containing failure details
     */
    public void reportFailure(ValidationResult<?> result) {
        if (result.valid()) {
            return; // Nothing to report
        }

        for (var member : result.suspects()) {
            byzantineProvider.recordValidationFailure(member.getId(), result.failureReason());
        }
        metrics.incrementValidationFailure(result.operation(), result.failureReason());
        metrics.incrementByzantineDetection("VALIDATION");

        log.info("Reported validation failure: {} for {} suspect members", result.failureReason(),
                 result.suspects().size());
    }
}
