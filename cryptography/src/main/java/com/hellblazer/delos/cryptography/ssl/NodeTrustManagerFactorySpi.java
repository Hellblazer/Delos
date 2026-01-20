/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.cryptography.ssl;

import java.security.InvalidAlgorithmParameterException;
import java.security.KeyStore;
import java.security.KeyStoreException;

import javax.net.ssl.ManagerFactoryParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactorySpi;

public class NodeTrustManagerFactorySpi extends TrustManagerFactorySpi {

    private final CertificateValidator validator;

    public NodeTrustManagerFactorySpi(CertificateValidator validator) {
        this.validator = validator;
    }

    @Override
    protected TrustManager[] engineGetTrustManagers() {
        return new TrustManager[] { new Trust(validator) };
    }

    @Override
    protected void engineInit(KeyStore ks) throws KeyStoreException {
    }

    @Override
    protected void engineInit(ManagerFactoryParameters spec) throws InvalidAlgorithmParameterException {
    }

}
