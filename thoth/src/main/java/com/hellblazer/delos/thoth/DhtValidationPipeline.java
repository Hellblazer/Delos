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

import java.time.Duration;
import java.util.Set;

/**
 * Post-quorum validation pipeline for DHT responses.
 * <p>
 * Validates response content using KERI cryptography (Ani) and reports
 * Byzantine behavior to ThothByzantineStateProvider. Validation is
 * advisory-only: failures are logged and reported but do not reject responses.
 * </p>
 * <p>
 * Thread Safety: Designed for use within read() method, single-threaded per
 * quorum operation.
 * </p>
 *
 * @author hal.hildebrand
 */
public class DhtValidationPipeline {
    private static final Logger                    log = LoggerFactory.getLogger(DhtValidationPipeline.class);
    private final        Ani                       ani;
    private final        KERL                      kerl;
    private final        Duration                  validationTimeout;
    private final        ThothByzantineStateProvider byzantineProvider;
    private final        KerlDhtMetrics            metrics;

    public DhtValidationPipeline(Ani ani, KERL kerl, Duration validationTimeout,
                                 ThothByzantineStateProvider byzantineProvider, KerlDhtMetrics metrics) {
        this.ani = ani;
        this.kerl = kerl;
        this.validationTimeout = validationTimeout;
        this.byzantineProvider = byzantineProvider;
        this.metrics = metrics;
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

        try {
            // Phase 3: Basic validation - check if state has establishment event
            if (!state.hasLastEstablishmentEvent()) {
                // No establishment event to validate
                metrics.incrementValidationSkipped("keyState", "no_establishment_event");
                return ValidationResult.valid(state, "keyState");
            }

            // Get coordinates for the establishment event
            var estCoords = EventCoordinates.from(state.getLastEstablishmentEvent());
            var validation = ani.eventValidation(validationTimeout);

            // Try to validate the establishment event
            var event = kerl.getKeyEvent(estCoords);
            if (event instanceof EstablishmentEvent est) {
                if (!validation.validate(est)) {
                    var reason = "Establishment event validation failed for " + estCoords;
                    log.warn("KeyState validation failed: {} from members: {}", reason,
                             providers.stream().map(m -> m.getId().toString()).toList());
                    return ValidationResult.invalid(state, providers, reason, "keyState");
                }
            } else {
                // Not an establishment event - skip validation
                metrics.incrementValidationSkipped("keyState", "not_establishment_event");
            }

            metrics.incrementValidationSuccess("keyState");
            return ValidationResult.valid(state, "keyState");

        } catch (Exception e) {
            // Validation infrastructure failure - log, report skipped, don't block
            log.warn("Validation infrastructure error: {}", e.getMessage(), e);
            metrics.incrementValidationSkipped("keyState", "missing_local_data");
            return ValidationResult.valid(state, "keyState");
        }
    }

    /**
     * Validate a KeyStates response from write operations (append).
     * <p>
     * Phase 4 implementation: Validates each KeyState_ in the response.
     * Checks that returned states are structurally valid and can be verified.
     * Validation failures are advisory-only: reported but do not reject response.
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

        try {
            // Validate each KeyState_ in the response
            var states = keyStates.getKeyStatesList();
            for (var state : states) {
                var stateResult = validateKeyState(state, providers);
                if (!stateResult.valid()) {
                    // One invalid state makes the whole response invalid
                    var reason = "KeyStates contains invalid state: " + stateResult.failureReason();
                    log.warn("KeyStates validation failed: {} from members: {}", reason,
                             providers.stream().map(m -> m.getId().toString()).toList());
                    return ValidationResult.invalid(keyStates, providers, reason, "keyStates");
                }
            }

            metrics.incrementValidationSuccess("keyStates");
            return ValidationResult.valid(keyStates, "keyStates");

        } catch (Exception e) {
            // Validation infrastructure failure - log, report skipped, don't block
            log.warn("Validation infrastructure error for KeyStates: {}", e.getMessage(), e);
            metrics.incrementValidationSkipped("keyStates", "validation_error");
            return ValidationResult.valid(keyStates, "keyStates");
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
