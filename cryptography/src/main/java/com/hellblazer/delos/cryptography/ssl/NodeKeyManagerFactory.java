/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.cryptography.ssl;

import java.security.PrivateKey;
import java.security.Provider;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManagerFactory;

public class NodeKeyManagerFactory extends KeyManagerFactory {

    public NodeKeyManagerFactory(String alias, X509Certificate certificate, PrivateKey privateKey, Provider provider) {
        super(new NodeKeyManagerFactorySpi(alias, certificate, privateKey), provider, "Keys");
    }

}
