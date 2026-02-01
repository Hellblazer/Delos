/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.cryptography.ssl.CertificateValidator;
import com.hellblazer.delos.stereotomy.*;
import com.hellblazer.delos.stereotomy.KEL.KeyStateWithAttachments;
import com.hellblazer.delos.stereotomy.event.EstablishmentEvent;
import com.hellblazer.delos.stereotomy.event.KeyEvent;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.processing.KerlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;

/**
 * Stereotomy key event validation, certificate validator and verifiers
 *
 * @author hal.hildebrand
 */
public class Ani {

    private static final Logger log = LoggerFactory.getLogger(Ani.class);

    private final Digest member;
    private final KERL   kerl;

    public Ani(Digest member, KERL kerl) {
        this.member = member;
        this.kerl = kerl;
    }

    public CertificateValidator certificateValidator(Duration timeout) {
        return new StereotomyValidator(verifiers(timeout));
    }

    public EventValidation eventValidation(Duration timeout) {
        return new EventValidation() {

            @Override
            public boolean validate(EstablishmentEvent event) {
                log.trace("Validate event: {} on: {}", event.getCoordinates(), member);
                var result = Ani.this.validateKerl(event, timeout);
                log.info("Validate event: {}: {} on: {}", event, result, member);
                return result;
            }

            @Override
            public boolean validate(Identifier identifier) {
                log.trace("Validating identifier: {} on: {}", identifier, member);
                var ks = kerl.getKeyState(identifier);
                var ke = kerl.getKeyEvent(ks.getLastEstablishmentEvent());
                var result = Ani.this.validateKerl(ke, timeout);
                log.info("Validating identifier: {}:{} on: {}", identifier, result, member);
                return result;
            }
        };
    }

    public Verifiers verifiers(Duration timeout) {
        return new Verifiers() {

            @Override
            public Optional<Verifier> verifierFor(EventCoordinates coordinates) {
                return Optional.of(new KerlVerifier<>(coordinates.getIdentifier(), kerl));
            }

            @Override
            public Optional<Verifier> verifierFor(Identifier identifier) {
                return Optional.of(new KerlVerifier<>(identifier, kerl));
            }
        };
    }

    private boolean kerlValidate(Duration timeout, KeyStateWithAttachments ksa, KeyEvent event) {
        // TODO Multisig
        var state = ksa.state();
        var endorsements = ksa.attachments().endorsements();

        // Delegate to unified KerlValidator for witness endorsement validation
        var validator = new KerlValidator(kerl);
        boolean witnessed = validator.validateWitnessEndorsements(state, event, endorsements);

        log.trace("Kerl validation: {} for: {} on: {}", witnessed, ksa.state().getCoordinates(), member);
        return witnessed;
    }

    private boolean performKerlValidation(EventCoordinates coord, Duration timeout) {
        var event = kerl.getKeyEvent(coord);
        var ksa = kerl.getKeyStateWithAttachments(coord);
        return kerlValidate(timeout, ksa, event);
    }

    private boolean validateKerl(KeyEvent event, Duration timeout) {
        return performKerlValidation(event.getCoordinates(), timeout);
    }
}
