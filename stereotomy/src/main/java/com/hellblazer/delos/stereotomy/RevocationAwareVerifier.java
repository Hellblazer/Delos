/*
 * Copyright (c) 2024, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy;

import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.SigningThreshold;
import com.hellblazer.delos.cryptography.Verifier;
import org.joou.ULong;

import java.io.InputStream;

/**
 * A verifier wrapper that checks revocation status before delegating to the underlying verifier.
 * This verifier requires access to KeyState information to check revocation for the sequence number
 * in the signature.
 *
 * @author hal.hildebrand
 */
public class RevocationAwareVerifier implements Verifier {

    private final Verifier                delegate;
    private final KeyRevocationRegistry   revocationRegistry;
    private final KeyStateProvider        keyStateProvider;

    /**
     * Interface to provide KeyState for a given sequence number
     */
    @FunctionalInterface
    public interface KeyStateProvider {
        KeyState getKeyState(ULong sequenceNumber);
    }

    public RevocationAwareVerifier(Verifier delegate, KeyRevocationRegistry revocationRegistry,
                                   KeyStateProvider keyStateProvider) {
        this.delegate = delegate;
        this.revocationRegistry = revocationRegistry;
        this.keyStateProvider = keyStateProvider;
    }

    @Override
    public boolean verify(JohnHancock signature, InputStream message) {
        if (isRevoked(signature.getSequenceNumber())) {
            return false;
        }
        return delegate.verify(signature, message);
    }

    @Override
    public boolean verify(SigningThreshold threshold, JohnHancock signature, InputStream message) {
        if (isRevoked(signature.getSequenceNumber())) {
            return false;
        }
        return delegate.verify(threshold, signature, message);
    }

    private boolean isRevoked(ULong sequenceNumber) {
        if (revocationRegistry == null || keyStateProvider == null) {
            return false;
        }

        var keyState = keyStateProvider.getKeyState(sequenceNumber);
        if (keyState == null) {
            return false;
        }

        return revocationRegistry.isRevoked(keyState.getLastEstablishmentEvent());
    }
}
