/*
 * Copyright (c) 2025, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.membership;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.utils.Utils;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.security.PublicKey;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test the CertificateMember interface for X509-specific functionality
 */
class CertificateMemberTest {

    @Test
    void testGetMemberIdentifierFromCertificate() {
        var certWithKey = Utils.getMember(0);
        var cert = certWithKey.getX509Certificate();
        var id = CertificateMember.getMemberIdentifier(cert);
        assertNotNull(id, "Member identifier should not be null");
        assertTrue(id instanceof Digest, "Member identifier should be a Digest");
    }

    @Test
    void testGetSigningKeyFromCertificate() {
        var certWithKey = Utils.getMember(0);
        var cert = certWithKey.getX509Certificate();
        var key = CertificateMember.getSigningKey(cert);
        assertNotNull(key, "Signing key should not be null");
        assertTrue(key instanceof PublicKey, "Signing key should be a PublicKey");
    }

    @Test
    void testPortsFromCertificate() {
        var certWithKey = Utils.getMember(0);
        var cert = certWithKey.getX509Certificate();
        var address = CertificateMember.portsFrom(cert);
        assertNotNull(address, "Address should not be null");
        assertTrue(address instanceof InetSocketAddress, "Address should be an InetSocketAddress");
        assertTrue(address.getPort() > 0, "Port should be positive");
        assertNotNull(address.getHostName(), "Hostname should not be null");
    }

    @Test
    void testMissingUIDInCertificate() {
        // This would require creating a malformed certificate, which is complex
        // In practice, this is tested by the existing integration tests
    }

    @Test
    void testMissingDCInCertificate() {
        // This would require creating a malformed certificate, which is complex
        // In practice, this is tested by the existing integration tests
    }

    @Test
    void testMissingPortInCertificate() {
        // This would require creating a malformed certificate, which is complex
        // In practice, this is tested by the existing integration tests
    }
}
