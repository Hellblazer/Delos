/*
 * Copyright (c) 2025, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.membership;

import com.hellblazer.delos.cryptography.Digest;

import java.net.InetSocketAddress;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.Map;

import static com.hellblazer.delos.cryptography.QualifiedBase64.digest;
import static com.hellblazer.delos.cryptography.QualifiedBase64.publicKey;

/**
 * Extension of Member for X509 certificate-based identities.
 * Provides certificate-specific utilities for extracting member information from X509 certificates.
 *
 * @author hal.hildebrand
 */
public interface CertificateMember extends Member {

    /**
     * Extract the member identifier from an X509 certificate's subject DN.
     * The identifier is expected to be in the UID field of the distinguished name.
     *
     * @param cert the X509 certificate
     * @return the member's unique identifier
     * @throws IllegalArgumentException if the certificate is missing the UID field
     */
    static Digest getMemberIdentifier(X509Certificate cert) {
        var dn = cert.getSubjectX500Principal().getName();
        Map<String, String> decoded = Util.decodeDN(dn);
        var id = decoded.get("UID");
        if (id == null) {
            throw new IllegalArgumentException("Invalid certificate, missing \"UID\" of dn= " + dn);
        }
        return digest(id);
    }

    /**
     * Extract the signing public key from an X509 certificate's subject DN.
     * The key is expected to be encoded in the DC field of the distinguished name.
     *
     * @param cert the X509 certificate
     * @return the member's signing public key
     * @throws IllegalArgumentException if the certificate is missing the DC field
     */
    static PublicKey getSigningKey(X509Certificate cert) {
        var dn = cert.getSubjectX500Principal().getName();
        Map<String, String> decoded = Util.decodeDN(dn);
        var pk = decoded.get("DC");
        if (pk == null) {
            throw new IllegalArgumentException("Invalid certificate, missing \"DC\" of dn= " + dn);
        }
        return publicKey(pk);
    }

    /**
     * Extract the network address (host and port) from an X509 certificate's subject DN.
     * The port is expected to be in the L field and the hostname in the CN field.
     *
     * @param certificate the X509 certificate
     * @return the member's network address
     * @throws IllegalArgumentException if the certificate is missing the L or CN fields
     */
    static InetSocketAddress portsFrom(X509Certificate certificate) {
        var dn = certificate.getSubjectX500Principal().getName();
        Map<String, String> decoded = Util.decodeDN(dn);
        var portString = decoded.get("L");
        if (portString == null) {
            throw new IllegalArgumentException("Invalid certificate, no port encodings in \"L\" of dn= " + dn);
        }
        var port = Integer.parseInt(portString);

        var hostName = decoded.get("CN");
        if (hostName == null) {
            throw new IllegalArgumentException("Invalid certificate, missing \"CN\" of dn= " + dn);
        }
        return new InetSocketAddress(hostName, port);
    }
}
