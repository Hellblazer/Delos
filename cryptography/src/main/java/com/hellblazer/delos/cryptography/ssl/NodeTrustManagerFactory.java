/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.cryptography.ssl;

import java.security.Provider;

import javax.net.ssl.TrustManagerFactory;

public class NodeTrustManagerFactory extends TrustManagerFactory {

    public NodeTrustManagerFactory(CertificateValidator validator, Provider provider) {
        super(new NodeTrustManagerFactorySpi(validator), provider, "Trust");
    }

}
