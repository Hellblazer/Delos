/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.protobuf.Empty;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.KERL;
import com.hellblazer.delos.stereotomy.event.EstablishmentEvent;
import com.hellblazer.delos.stereotomy.event.InceptionEvent;
import com.hellblazer.delos.stereotomy.event.KeyEvent;
import com.hellblazer.delos.stereotomy.event.proto.KeyEventWithAttachmentAndValidations_;
import com.hellblazer.delos.stereotomy.event.proto.KeyEvent_;
import com.hellblazer.delos.stereotomy.event.proto.KeyState_;
import com.hellblazer.delos.stereotomy.event.protobuf.ProtobufEventFactory;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.thoth.metrics.KerlDhtMetrics;
import com.hellblazer.delos.thoth.support.ValidationCircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Post-quorum validation pipeline for DHT responses.
 * <p>
 * Validates response content using KERI cryptography (Ani) and reports
 * Byzantine behavior to ThothByzantineStateProvider. Validation is
 * advisory-only: failures are logged and reported but do not reject responses.
 * </p>
 * <p>
 * Security Trade-Off: Circuit Breaker Fail-Open Policy
 * </p>
 * <p>
 * When the circuit breaker opens (after 10 consecutive KERL failures), validation
 * is skipped to prevent validation storms during infrastructure failures. This creates
 * a limited attack window where Byzantine members could inject invalid states during
 * KERL outages. However:
 * </p>
 * <ul>
 *   <li>Quorum voting still validates majority consensus (Byzantine needs f+1 nodes)</li>
 *   <li>Circuit closes after 1 minute (limited attack window)</li>
 *   <li>Byzantine provider still tracks quorum failures (non-validation signals)</li>
 *   <li>Monitoring dashboards should alert on circuit breaker state</li>
 * </ul>
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
    final                ValidationCircuitBreaker    circuitBreaker; // package-private for testing
    private final        ScheduledExecutorService    scheduler;
    // LRU cache for establishment events to reduce KERL lookups
    private final        Cache<EventCoordinates, KeyEvent> eventCache;

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
        this.eventCache = Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();
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

            // Try to validate the establishment event - check cache first
            var event = eventCache.get(estCoords, k -> kerl.getKeyEvent(estCoords));
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

            // Phase 3 enhancement: Validate inception events have self-addressing identifiers
            if (event instanceof com.hellblazer.delos.stereotomy.event.InceptionEvent icp) {
                var identifier = icp.getIdentifier();
                if (identifier instanceof com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier sap) {
                    // Compute hash of inception statement
                    var computedDigest = sap.getDigest().getAlgorithm().digest(icp.getInceptionStatement());
                    // Verify identifier matches inception statement hash
                    if (!sap.getDigest().equals(computedDigest)) {
                        var reason = "Self-addressing identifier digest mismatch for inception event " + estCoords +
                                     ": expected " + sap.getDigest() + " but computed " + computedDigest;
                        log.warn("KeyState validation failed: {} from members: {}", reason,
                                 providers.stream().map(m -> m.getId().toString()).toList());
                        circuitBreaker.recordSuccess(); // Validation executed, infrastructure OK
                        metrics.recordValidationLatency("keyState", System.nanoTime() - startTime);
                        return ValidationResult.invalid(state, providers, reason, "keyState");
                    }
                }
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

            // Validate each state sequentially (already running async from KerlDHT.completeIt)
            // Avoid parallel validation with join() to prevent scheduler deadlock
            for (var state : states) {
                var stateResult = validateKeyState(state, providers);
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
     * Validate a batch of reconciliation events received from a single peer.
     * <p>
     * Phase A implementation: Pre-insertion validation for reconciliation path. Filters out
     * invalid events before they reach {@code KerlSpace.update()}. Invalid events trigger
     * Byzantine signals against the sender.
     * </p>
     * <p>
     * Two-pass ordering: Establishment events (inception/rotation, seqNum 0) are validated
     * first in pass 1, so that trust bootstrapped by an inception event is available when
     * dependent interaction events are validated in pass 2.
     * </p>
     * <p>
     * Circuit Breaker Fail-Open: If the circuit breaker is open (infrastructure failing),
     * the unfiltered list is returned to prevent blocking reconciliation during outages.
     * </p>
     *
     * @param events List of reconciliation events from a peer; may be null or empty
     * @param peerId Digest identifying the sending peer (used for Byzantine signal recording)
     * @return Filtered list containing only validated events; never null
     */
    public List<KeyEventWithAttachmentAndValidations_> validateReconciliationBatch(
        List<KeyEventWithAttachmentAndValidations_> events, Digest peerId) {

        if (events == null || events.isEmpty()) {
            return List.of();
        }

        // Circuit breaker fail-open: return unfiltered list during infrastructure failures
        if (circuitBreaker.isOpen()) {
            log.debug("Validation circuit breaker OPEN - skipping reconciliation batch validation, {} events",
                      events.size());
            metrics.incrementValidationSkipped("reconciliation", "circuit_breaker_open");
            return events;
        }

        // Two-pass ordering: sort by (seqNum ASC) — inception events (seqNum 0) come first
        // within their identifier, which allows trust bootstrapping for dependent events.
        var sorted = events.stream()
                           .sorted(Comparator.comparingLong(DhtValidationPipeline::extractSequenceNumber))
                           .toList();

        var validated = new ArrayList<KeyEventWithAttachmentAndValidations_>(sorted.size());
        for (var event : sorted) {
            if (validateSingleReconciliationEvent(event, peerId)) {
                validated.add(event);
            }
        }

        log.debug("Reconciliation batch: {} in, {} validated, {} rejected from peer: {}",
                  events.size(), validated.size(), events.size() - validated.size(), peerId);
        return validated;
    }

    /**
     * Validate a single reconciliation event.
     * <p>
     * Converts proto event to domain object, then validates:
     * <ul>
     *   <li>Structural validity (event type present, identifier set)</li>
     *   <li>EstablishmentEvent: Ani validation if event is in local KERL</li>
     *   <li>InceptionEvent: Self-addressing identifier digest check</li>
     *   <li>Interaction events: accepted (no independent cryptographic check without prior state)</li>
     * </ul>
     * </p>
     *
     * @param wrapper event wrapper containing KeyEvent_ and optional attachment/validations
     * @param peerId  peer digest for Byzantine signal recording
     * @return true if event passes validation, false if rejected
     */
    private boolean validateSingleReconciliationEvent(KeyEventWithAttachmentAndValidations_ wrapper, Digest peerId) {
        var startTime = System.nanoTime();
        try {
            var keyEvent_ = wrapper.getEvent();
            if (keyEvent_ == null || keyEvent_.getEventCase() == KeyEvent_.EventCase.EVENT_NOT_SET) {
                recordReconciliationRejection(peerId, "event has no type set", startTime);
                return false;
            }

            // Convert proto to domain object
            var keyEvent = ProtobufEventFactory.from(keyEvent_);
            if (keyEvent == null) {
                recordReconciliationRejection(peerId, "event conversion failed (unknown event type)", startTime);
                return false;
            }

            // Establishment events (inception, rotation): attempt Ani validation
            if (keyEvent instanceof EstablishmentEvent est) {
                var coords = est.getCoordinates();

                // Check cache first, then KERL — for new events not yet inserted, this returns null
                var localEvent = eventCache.get(coords, k -> kerl.getKeyEvent(coords));
                if (localEvent == null) {
                    // New event not yet in local KERL — acceptable for reconciliation.
                    // For inception events, check self-addressing identifier integrity.
                    if (est instanceof InceptionEvent icp) {
                        var identifier = icp.getIdentifier();
                        if (identifier instanceof SelfAddressingIdentifier sai) {
                            var computed = sai.getDigest().getAlgorithm().digest(icp.getInceptionStatement());
                            if (!sai.getDigest().equals(computed)) {
                                var reason = "Self-addressing identifier digest mismatch for inception event "
                                             + coords;
                                recordReconciliationRejection(peerId, reason, startTime);
                                return false;
                            }
                        }
                    }
                    // New event accepted — let kerlSpace.update() do full insertion validation
                    metrics.recordValidationLatency("reconciliation", System.nanoTime() - startTime);
                    return true;
                }

                // Event is already in local KERL — validate via Ani
                if (!(localEvent instanceof EstablishmentEvent localEst)) {
                    var reason = "Expected EstablishmentEvent in local KERL but got "
                                 + localEvent.getClass().getSimpleName() + " for " + coords;
                    recordReconciliationRejection(peerId, reason, startTime);
                    return false;
                }
                var validation = ani.eventValidation(validationTimeout);
                if (!validation.validate(localEst)) {
                    var reason = "Establishment event validation failed for " + coords;
                    recordReconciliationRejection(peerId, reason, startTime);
                    circuitBreaker.recordSuccess(); // Validation executed — infrastructure is OK
                    return false;
                }
                circuitBreaker.recordSuccess();
                metrics.recordValidationLatency("reconciliation", System.nanoTime() - startTime);
                return true;
            }

            // Interaction events: accept without independent cryptographic check
            // (full validation occurs when kerlSpace.update() processes the event)
            metrics.recordValidationLatency("reconciliation", System.nanoTime() - startTime);
            return true;

        } catch (Exception e) {
            if (isInfrastructureException(e)) {
                circuitBreaker.recordFailure();
                log.warn("KERL access failure during reconciliation event validation - accepting event: {}",
                         e.getMessage());
                metrics.incrementValidationSkipped("reconciliation", "kerl_access_failure");
                metrics.recordValidationLatency("reconciliation", System.nanoTime() - startTime);
                return true; // Fail-open for infrastructure failures
            }
            var reason = "Unexpected error during reconciliation validation: " + e.getClass().getSimpleName() + ": "
                         + e.getMessage();
            log.error("Rejecting reconciliation event due to unexpected validation error: {}", reason, e);
            recordReconciliationRejection(peerId, reason, startTime);
            return false;
        }
    }

    /**
     * Record a reconciliation event rejection: log warning, increment metrics, and signal Byzantine detection.
     *
     * @param peerId    peer that sent the rejected event
     * @param reason    human-readable rejection reason
     * @param startTime nanosecond start time for latency recording
     */
    private void recordReconciliationRejection(Digest peerId, String reason, long startTime) {
        log.warn("Reconciliation event rejected: {} from peer: {}", reason, peerId);
        metrics.recordValidationLatency("reconciliation", System.nanoTime() - startTime);
        metrics.incrementValidationFailure("reconciliation", reason);
        metrics.incrementByzantineDetection("RECONCILIATION");
        if (peerId != null) {
            byzantineProvider.recordValidationFailure(peerId, reason);
        }
    }

    /**
     * Extract the sequence number from a reconciliation event wrapper for sorting purposes.
     * <p>
     * Uses the Header.sequenceNumber field which is present on all three event types:
     * InceptionEvent (via specification.header), RotationEvent (via specification.header),
     * InteractionEvent (via specification.header).
     * </p>
     *
     * @param wrapper event wrapper
     * @return sequence number, or Long.MAX_VALUE if extraction fails
     */
    static long extractSequenceNumber(KeyEventWithAttachmentAndValidations_ wrapper) {
        if (wrapper == null) {
            return Long.MAX_VALUE;
        }
        var keyEvent = wrapper.getEvent();
        if (keyEvent == null) {
            return Long.MAX_VALUE;
        }
        return switch (keyEvent.getEventCase()) {
            case INCEPTION -> keyEvent.getInception().getSpecification().getHeader().getSequenceNumber();
            case ROTATION -> keyEvent.getRotation().getSpecification().getHeader().getSequenceNumber();
            case INTERACTION -> keyEvent.getInteraction().getSpecification().getHeader().getSequenceNumber();
            default -> Long.MAX_VALUE;
        };
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
