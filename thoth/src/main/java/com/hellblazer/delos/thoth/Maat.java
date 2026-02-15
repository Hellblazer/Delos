/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.Verifier.DefaultVerifier;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.stereotomy.DelegatedKERL;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.KERL;
import com.hellblazer.delos.stereotomy.KeyState;
import com.hellblazer.delos.stereotomy.event.AttachmentEvent;
import com.hellblazer.delos.stereotomy.event.EstablishmentEvent;
import com.hellblazer.delos.stereotomy.event.KeyEvent;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.joou.ULong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static com.hellblazer.delos.stereotomy.event.protobuf.ProtobufEventFactory.digestOf;

/**
 * Maat provides BFT signature validation for KERI establishment events.
 * Validates BLS signatures from BFT subset members and blocks invalid events.
 * Records Byzantine signals for validation failures to enable detection and member expulsion.
 *
 * @author hal.hildebrand
 */
public class Maat extends DelegatedKERL {
    private static final Logger log                      = LoggerFactory.getLogger(Maat.class);
    private static final Logger validationLog            = LoggerFactory.getLogger(Maat.class.getName() + ".validation");
    private static final int    MIN_DIGEST_BYTES         = 32;  // BLAKE3_256 minimum
    private static final String VALIDATION_FAILURE_PREFIX = "MAAT_BLS_VALIDATION_FAILURE";
    private static final String ANI_FAILURE_PREFIX        = "ANI_KERI_VALIDATION_FAILURE";

    private final ThothByzantineStateProvider byzantineProvider;
    private final Context<Member>             context;
    private final KERL                        validators;
    private final Ani                         ani;  // Optional Ani validator for ordered pipeline
    private final java.time.Duration          validationTimeout;

    /**
     * Construct Maat with ordered validation pipeline (Ani → Maat).
     *
     * @param context           Dynamic context for BFT subset selection
     * @param delegate          Underlying KERL for append operations
     * @param validators        KERL containing validator establishment events
     * @param byzantineProvider Provider for recording Byzantine signals (null = no-op)
     * @param ani               Ani validator for KERI cryptographic validation (null = skip Ani stage)
     * @param validationTimeout Timeout for Ani validation operations
     */
    public Maat(DynamicContext<Member> context, AppendKERL delegate, KERL validators,
                ThothByzantineStateProvider byzantineProvider, Ani ani, java.time.Duration validationTimeout) {
        super(delegate);
        this.context = context;
        this.validators = validators;
        this.byzantineProvider = byzantineProvider;
        this.ani = ani;
        this.validationTimeout = validationTimeout != null ? validationTimeout : java.time.Duration.ofSeconds(10);
    }

    /**
     * Backward-compatible constructor without Ani validation (Maat-only).
     */
    public Maat(DynamicContext<Member> context, AppendKERL delegate, KERL validators,
                ThothByzantineStateProvider byzantineProvider) {
        this(context, delegate, validators, byzantineProvider, null, null);
    }

    /**
     * Backward-compatible constructor without Byzantine signal recording.
     */
    public Maat(DynamicContext<Member> context, AppendKERL delegate, KERL validators) {
        this(context, delegate, validators, null, null, null);
    }

    @Override
    public KeyState append(KeyEvent event) {
        log.trace("Append: {}", event);
        var l = append(Collections.singletonList(event), Collections.emptyList());
        return l.isEmpty() ? null : l.get(0);
    }

    @Override
    public List<KeyState> append(KeyEvent... events) {
        return append(Arrays.asList(events), Collections.emptyList());
    }

    @Override
    public List<KeyState> append(List<KeyEvent> events, List<AttachmentEvent> attachments) {
        // Validate all establishment events and record Byzantine signals on failure
        final List<KeyEvent> filtered = events.stream().filter(e -> {
            if (e instanceof EstablishmentEvent est) {
                var valid = validateWithSignals(est);
                if (!valid) {
                    validationLog.warn("Establishment event validation failed, filtering out: {}", est.getCoordinates());
                }
                return valid;
            }
            return true;
        }).toList();
        return filtered.isEmpty() && attachments.isEmpty() ? Collections.emptyList()
                                                           : super.append(filtered, attachments);
    }

    /**
     * Validate establishment event through ordered pipeline (Ani → Maat) and record Byzantine signals on failure.
     * Invalid events will be filtered out of append operations.
     *
     * Pipeline stages:
     * 1. Ani validation (KERI cryptographic validation) - if ani != null
     * 2. Maat validation (BFT signature validation)
     *
     * Short-circuit behavior:
     * - Ani failure → record signal, return false (skip Maat)
     * - Maat failure → record signal, return false
     *
     * @param event Establishment event to validate
     * @return true if validation passes, false otherwise (invalid events are filtered)
     */
    private boolean validateWithSignals(EstablishmentEvent event) {
        // Stage 1: Ani validation (KERI cryptographic)
        if (ani != null) {
            var aniValidation = ani.eventValidation(validationTimeout);
            if (!aniValidation.validate(event)) {
                // Ani validation failed - record signal and short-circuit
                if (byzantineProvider != null) {
                    var reason = String.format("%s: Event %s failed KERI cryptographic validation",
                                               ANI_FAILURE_PREFIX, event.getCoordinates());
                    byzantineProvider.recordValidationFailure(event.getIdentifier(), reason);
                    validationLog.warn("Ani validation failed (short-circuit): {}", event.getCoordinates());
                }
                return false;  // Short-circuit: don't call Maat
            }
            log.trace("Ani validation passed for: {}", event.getCoordinates());
        }

        // Stage 2: Maat validation (BFT signatures)
        var result = validate(event);
        if (!result && byzantineProvider != null) {
            // Record Byzantine signal for Maat validation failure
            var reason = String.format("%s: Event %s failed BLS signature validation", VALIDATION_FAILURE_PREFIX,
                                       event.getCoordinates());
            byzantineProvider.recordValidationFailure(event.getIdentifier(), reason);
            validationLog.warn("Maat validation failed: {}", event.getCoordinates());
        }
        return result;
    }

    /**
     * Validate establishment event BLS signatures.
     * Package-private for testing.
     */
    boolean validate(EstablishmentEvent event) {
        Digest digest;
        if (event.getIdentifier() instanceof SelfAddressingIdentifier said) {
            digest = said.getDigest();
        } else {
            log.warn("Event identifier not self-addressing: {}", event.getCoordinates());
            return false;
        }

        // Validate digest bytes (defense-in-depth)
        var digestBytes = digest.getBytes();
        if (digestBytes == null || digestBytes.length < MIN_DIGEST_BYTES) {
            log.warn("Invalid digest bytes for event: {} (length: {})", event.getCoordinates(),
                     digestBytes == null ? 0 : digestBytes.length);
            return false;
        }

        final Context<Member> ctx = context;
        var successors = ctx.bftSubset(digestOf(event.getIdentifier().toIdent(), digest.getAlgorithm()))
                            .stream()
                            .map(m -> m.getId())
                            .collect(Collectors.toSet());

        record validator(EstablishmentEvent validating, JohnHancock signature) {
        }
        var mapped = new CopyOnWriteArrayList<validator>();
        final var serialized = event.toKeyEvent_().toByteString();

        Map<EventCoordinates, JohnHancock> validations = delegate.getValidations(event.getCoordinates());
        validations.entrySet().forEach(e -> {
            KeyEvent ev = validators.getKeyEvent(e.getKey());
            if (ev == null) {
                return;
            }
            var signer = (EstablishmentEvent) ev;
            if ((signer.getIdentifier() instanceof SelfAddressingIdentifier sai)) {
                if (!successors.contains(sai.getDigest())) {
                    log.warn("Rejecting signature: {} not successor of: {} ", signer.getCoordinates(), event.getCoordinates());
                    return;
                }
                mapped.add(new validator(signer, e.getValue()));
                log.trace("Signature: {} valid for: {}", signer.getCoordinates(), event.getCoordinates());
            } else {
                log.warn("Signature not SAI: {} for: {}", signer.getCoordinates(), event.getCoordinates(),
                         event.getCoordinates());
            }
        });

        log.trace("Evaluating validation of: {} validations: {} mapped: {}", event.getCoordinates(), validations.size(),
                  mapped.size());
        if (mapped.size() == 0) {
            validationLog.warn("No validations of: {} ", event.getCoordinates());
            return false;
        }

        var verified = 0;
        for (var r : mapped) {
            if (r.validating.getKeys().isEmpty()) {
                log.warn("Validator has no keys: {} for event: {}", r.validating.getCoordinates(), event.getCoordinates());
                continue;
            }
            var verifier = new DefaultVerifier(r.validating.getKeys().get(0));
            if (verifier.verify(r.signature, serialized)) {
                verified++;
            } else {
                log.trace("Cannot verify sig: {} of: {} by: {}", r.signature, event.getCoordinates(),
                          r.validating.getIdentifier());
            }
        }
        var ctxMajority = context.size() == 1 ? 1 : context.majority();
        var validated = verified >= ctxMajority;

        log.trace("Validated: {} valid: {} out of: {} required: {} for: {}  ", validated, verified, mapped.size(),
                  ctx.majority(), event.getCoordinates());
        return validated;
    }
}

