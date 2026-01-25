/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.cryptography.bls;

import com.hellblazer.delos.cryptography.bls.impl.TekuBLSProvider;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Minimal test to debug the PoP generation issue.
 */
class MinimalBLSTest {

    @Test
    void testSimpleKeyGeneration() {
        var provider = TekuBLSProvider.getInstance();
        var keyPair = BLSKeyPair.generate(provider);

        assertNotNull(keyPair);
        assertNotNull(keyPair.publicKey());
        assertNotNull(keyPair.secretKey());
    }
}
