/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.cryptography.cert;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * @author hal.hildebrand
 *
 */
public class CertificateWithPrivateKey {
    private final X509Certificate cert;

    private final PrivateKey privateKey;

    public CertificateWithPrivateKey(X509Certificate cert, PrivateKey privateKey) {
        this.cert = cert;
        this.privateKey = privateKey;
    }

    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    public X509Certificate getX509Certificate() {
        return cert;
    }
}
