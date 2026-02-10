/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.gorgoneion.client;

import com.google.protobuf.Any;
import com.google.protobuf.Timestamp;
import com.hellblazer.delos.gorgoneion.client.client.comm.Admissions;
import com.hellblazer.delos.gorgoneion.proto.*;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.event.proto.KERL_;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Function;

/**
 * @author hal.hildebrand
 */
public class GorgoneionClient {
    private static final Logger log = LoggerFactory.getLogger(GorgoneionClient.class);

    private final Function<SignedNonce, Any> attester;
    private final Admissions                 client;
    private final Clock                      clock;
    private final ControlledIdentifierMember member;
    private final PublicKey_                 sessionKey;

    /**
     * Create a GorgoneionClient for identity registration and admission.
     *
     * @param member The controlled identifier member attempting to register
     * @param attester Function to generate attestation proof from nonce.
     *                 Must be idempotent - may be called multiple times with the same nonce during retry scenarios.
     * @param clock Clock for timestamp generation
     * @param client Admissions communication client
     */
    public GorgoneionClient(ControlledIdentifierMember member, Function<SignedNonce, Any> attester, Clock clock,
                            Admissions client) {
        this(member, attester, clock, client, null);
    }

    /**
     * Create a GorgoneionClient with an optional session key.
     *
     * @param member The controlled identifier member attempting to register
     * @param attester Function to generate attestation proof from nonce.
     *                 Must be idempotent - may be called multiple times with the same nonce during retry scenarios.
     * @param clock Clock for timestamp generation
     * @param client Admissions communication client
     * @param sessionKey Optional session public key for secure communication
     */
    public GorgoneionClient(ControlledIdentifierMember member, Function<SignedNonce, Any> attester, Clock clock,
                            Admissions client, PublicKey_ sessionKey) {
        this.member = member;
        this.attester = attester;
        this.clock = clock;
        this.client = client;
        this.sessionKey = sessionKey;
    }

    /**
     * Execute the two-phase bootstrap process: nonce acquisition followed by credential registration.
     * <p>
     * Phase 1: Apply for a signed nonce from the admission server using the member's KERL.
     * Phase 2: Create credentials from the nonce and attester proof, then register with the server.
     * <p>
     * Result variants:
     * - {@link BootstrapResult.Success}: Both phases completed successfully. Use establishment to integrate.
     * - {@link BootstrapResult.PartialSuccess}: Nonce acquired but registration failed. Contains credentials
     *   for retry via {@link #retryRegistration(Credentials, Duration)}.
     * - {@link BootstrapResult.Failure}: Either phase failed completely. Retry the entire process.
     * <p>
     * Recommended retry strategy:
     * 1. On Failure: Retry apply() with exponential backoff (network/server issues)
     * 2. On PartialSuccess: Retry retryRegistration() (avoids generating a new nonce)
     * 3. On Success: Proceed with establishment integration
     *
     * @param timeout Maximum time to wait for each network operation
     * @return Bootstrap result indicating success, partial success with retryable credentials, or complete failure
     */
    public BootstrapResult apply(Duration timeout) {
        KERL_ application = member.kerl();
        SignedNonce nonce;

        // Phase 1: Apply for nonce
        try {
            nonce = client.apply(application, timeout);
            if (nonce == null) {
                return new BootstrapResult.Failure(new IllegalStateException(
                    "Failed to apply for admission: server returned null nonce. " +
                    "This typically indicates the admission server rejected the application " +
                    "or encountered an error processing the KERL."
                ));
            }
        } catch (Exception e) {
            log.error("Phase 1 failed: nonce acquisition failed", e);
            return new BootstrapResult.Failure(e);
        }

        // Phase 2a: Create credentials from nonce
        Credentials credentials;
        try {
            credentials = credentials(nonce);
        } catch (Exception e) {
            log.error("Phase 2a failed: credentials generation failed after nonce acquisition", e);
            return new BootstrapResult.Failure(e);
        }

        // Phase 2b: Register credentials with server
        try {
            Establishment establishment = client.register(credentials, timeout);
            return new BootstrapResult.Success(establishment);
        } catch (Exception e) {
            log.error("Phase 2b failed: registration failed but credentials available for retry", e);
            return new BootstrapResult.PartialSuccess(credentials, e);
        }
    }

    /**
     * Retry registration with existing credentials from a PartialSuccess result.
     * Use this when the initial registration failed but the nonce was successfully generated.
     *
     * @param credentials The credentials from the PartialSuccess result
     * @param timeout Maximum time to wait for registration
     * @return Success if registration completes, Failure if it fails again
     */
    public BootstrapResult retryRegistration(Credentials credentials, Duration timeout) {
        try {
            Establishment establishment = client.register(credentials, timeout);
            return new BootstrapResult.Success(establishment);
        } catch (Exception e) {
            log.error("Retry registration failed", e);
            return new BootstrapResult.Failure(e);
        }
    }

    private SignedAttestation attestation(SignedNonce nonce, Any proof) {
        KERL_ kerl = member.kerl();
        var now = clock.instant();
        var attestation = Attestation.newBuilder()
                                     .setAttestation(proof)
                                     .setKerl(kerl)
                                     .setNonce(member.sign(nonce.toByteString()).toSig())
                                     .setTimestamp(
                                     Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()))
                                     .build();
        return SignedAttestation.newBuilder()
                                .setAttestation(attestation)
                                .setSignature(member.sign(attestation.toByteString()).toSig())
                                .build();

    }

    private Credentials credentials(SignedNonce nonce) {
        KERL_ kerl = member.kerl();
        var attestation = attester.apply(nonce);
        var sa = attestation(nonce, attestation);
        var builder = Credentials.newBuilder().setNonce(nonce).setAttestation(sa);
        if (sessionKey != null) {
            builder.setSessionKey(sessionKey);
        }
        return builder.build();
    }
}
