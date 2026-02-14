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
    private static final int    MIN_DIGEST_BYTES         = 32;  // BLAKE3_256 minimum
    private static final String VALIDATION_FAILURE_PREFIX = "MAAT_BLS_VALIDATION_FAILURE";

    private final ThothByzantineStateProvider byzantineProvider;
    private final Context<Member>             context;
    private final KERL                        validators;

    /**
     * Construct Maat with Byzantine signal recording.
     *
     * @param context           Dynamic context for BFT subset selection
     * @param delegate          Underlying KERL for append operations
     * @param validators        KERL containing validator establishment events
     * @param byzantineProvider Provider for recording Byzantine signals (null = no-op)
     */
    public Maat(DynamicContext<Member> context, AppendKERL delegate, KERL validators,
                ThothByzantineStateProvider byzantineProvider) {
        super(delegate);
        this.context = context;
        this.validators = validators;
        this.byzantineProvider = byzantineProvider;
    }

    /**
     * Backward-compatible constructor without Byzantine signal recording.
     */
    public Maat(DynamicContext<Member> context, AppendKERL delegate, KERL validators) {
        this(context, delegate, validators, null);
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
                    log.warn("Establishment event validation failed, filtering out: {}", est.getCoordinates());
                }
                return valid;
            }
            return true;
        }).toList();
        return filtered.isEmpty() && attachments.isEmpty() ? Collections.emptyList()
                                                           : super.append(filtered, attachments);
    }

    /**
     * Validate establishment event and record Byzantine signals on failure.
     * Invalid events will be filtered out of append operations.
     *
     * @param event Establishment event to validate
     * @return true if validation passes, false otherwise (invalid events are filtered)
     */
    private boolean validateWithSignals(EstablishmentEvent event) {
        var result = validate(event);
        if (!result && byzantineProvider != null) {
            // Record Byzantine signal for validation failure
            Digest identifier = event.getIdentifier() instanceof SelfAddressingIdentifier said ? said.getDigest()
                                                                                               : digestOf(
                                                                                               event.getIdentifier()
                                                                                                    .toIdent(),
                                                                                               context.getId()
                                                                                                      .getAlgorithm());
            var reason = String.format("%s: Event %s failed BLS signature validation", VALIDATION_FAILURE_PREFIX,
                                       event.getCoordinates());
            byzantineProvider.recordValidationFailure(identifier, reason);
            log.debug("Recorded Byzantine signal for validation failure: {}", event.getCoordinates());
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
            log.warn("No validations of: {} ", event.getCoordinates());
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

