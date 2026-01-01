/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.comm.grpc;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.cert.CertificateWithPrivateKey;
import com.hellblazer.delos.cryptography.cert.Certificates;
import com.hellblazer.delos.cryptography.ssl.CertificateValidator;
import com.hellblazer.delos.utils.Utils;
import io.netty.handler.ssl.ClientAuth;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.Provider;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test that certificate validator security is enforced
 *
 * @author hal.hildebrand
 */
public class TestCertificateValidatorSecurity {

    private static CertificateWithPrivateKey createTestCert() {
        var keyPair = SignatureAlgorithm.ED_25519.generateKeyPair();
        var notBefore = Instant.now();
        var notAfter = Instant.now().plusSeconds(10_000);
        var localhost = InetAddress.getLoopbackAddress().getHostName();
        var cert = Certificates.selfSign(false,
                                         Utils.encode(DigestAlgorithm.DEFAULT.getOrigin(), localhost,
                                                     Utils.allocatePort(), keyPair.getPublic()),
                                         keyPair, notBefore, notAfter, Collections.emptyList());
        return new CertificateWithPrivateKey(cert, keyPair.getPrivate());
    }

    @Test
    public void testNullValidatorRejectedInServerContextSupplier() {
        var serverCert = createTestCert();

        // Test that forServer directly rejects null validator
        var exception = assertThrows(IllegalArgumentException.class, () -> {
            MtlsServer.forServer(ClientAuth.REQUIRE, "test", serverCert.getX509Certificate(),
                               serverCert.getPrivateKey(), null);
        }, "Should reject null CertificateValidator");

        assertTrue(exception.getMessage().contains("cannot be null"),
                  "Exception message should mention null: " + exception.getMessage());
    }

    @Test
    public void testEmptyValidatorRejectedInServerContextSupplier() {
        var serverCert = createTestCert();

        // Test that forServer directly rejects CertificateValidator.NONE
        var exception = assertThrows(IllegalArgumentException.class, () -> {
            MtlsServer.forServer(ClientAuth.REQUIRE, "test", serverCert.getX509Certificate(),
                               serverCert.getPrivateKey(), CertificateValidator.NONE);
        }, "Should reject CertificateValidator.NONE");

        assertTrue(exception.getMessage().contains("NONE") || exception.getMessage().contains("not permitted"),
                  "Exception message should mention NONE or not permitted: " + exception.getMessage());
    }

    @Test
    public void testNullValidatorRejectedInClientContextSupplier() {
        var clientCert = createTestCert();

        // Test that forClient directly rejects null validator
        var exception = assertThrows(IllegalArgumentException.class, () -> {
            MtlsServer.forClient(ClientAuth.REQUIRE, "test", clientCert.getX509Certificate(),
                               clientCert.getPrivateKey(), null);
        }, "Should reject null CertificateValidator");

        assertTrue(exception.getMessage().contains("cannot be null"),
                  "Exception message should mention null: " + exception.getMessage());
    }

    @Test
    public void testEmptyValidatorRejectedInClientContextSupplier() {
        var clientCert = createTestCert();

        // Test that forClient directly rejects CertificateValidator.NONE
        var exception = assertThrows(IllegalArgumentException.class, () -> {
            MtlsServer.forClient(ClientAuth.REQUIRE, "test", clientCert.getX509Certificate(),
                               clientCert.getPrivateKey(), CertificateValidator.NONE);
        }, "Should reject CertificateValidator.NONE");

        assertTrue(exception.getMessage().contains("NONE") || exception.getMessage().contains("not permitted"),
                  "Exception message should mention NONE or not permitted: " + exception.getMessage());
    }
}
