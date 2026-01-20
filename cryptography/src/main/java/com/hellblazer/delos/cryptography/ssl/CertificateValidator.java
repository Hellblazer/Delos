/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.cryptography.ssl;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * @author hal.hildebrand
 *
 */
public interface CertificateValidator {

    static final CertificateValidator NONE = new CertificateValidator() {

        @Override
        public void validateClient(X509Certificate[] chain) throws CertificateException {
        }

        @Override
        public void validateServer(X509Certificate[] chain) throws CertificateException {
        }
    };

    void validateClient(X509Certificate[] chain) throws CertificateException;

    void validateServer(X509Certificate[] chain) throws CertificateException;
}
