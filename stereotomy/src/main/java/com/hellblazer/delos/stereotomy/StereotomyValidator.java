/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy;

import com.hellblazer.delos.cryptography.ssl.CertificateValidator;
import com.hellblazer.delos.stereotomy.Stereotomy.Decoded;
import com.hellblazer.delos.stereotomy.identifier.BasicIdentifier;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import static com.hellblazer.delos.stereotomy.identifier.QualifiedBase64Identifier.qb64;

/**
 * @author hal.hildebrand
 */
public class StereotomyValidator implements CertificateValidator {

    private final Verifiers verifiers;

    public StereotomyValidator(Verifiers verifiers) {
        this.verifiers = verifiers;
    }

    public void validate(final X509Certificate cert) throws CertificateException {
        var publicKey = cert.getPublicKey();
        var basicId = new BasicIdentifier(publicKey);

        var decoded = Stereotomy.decode(cert);
        if (decoded.isEmpty()) {
            throw new CertificateException();
        }
        final var qb64Id = qb64(basicId);
        Decoded decoder = decoded.get();
        var verifier = verifiers.verifierFor(decoded.get().identifier());
        if (verifier.isEmpty()) {
            throw new CertificateException(String.format("No verifier for identifier: %s", decoded.get().identifier()));
        }
        if (!verifier.get().verify(decoder.signature(), qb64Id)) {
            throw new CertificateException(String.format("Cannot verify cert public key signature for %s", basicId));
        }
    }

    @Override
    public void validateClient(X509Certificate[] chain) throws CertificateException {
        validate(chain[0]);
    }

    @Override
    public void validateServer(X509Certificate[] chain) throws CertificateException {
        validate(chain[0]);
    }
}
