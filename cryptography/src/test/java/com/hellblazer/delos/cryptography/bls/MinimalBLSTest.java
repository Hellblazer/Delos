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
        var provider = new TekuBLSProvider();
        var keyPair = BLSKeyPair.generate(provider);

        assertNotNull(keyPair);
        assertNotNull(keyPair.publicKey());
        assertNotNull(keyPair.secretKey());
    }
}
