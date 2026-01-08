/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.gorgoneion.comm.admissions;

import com.codahale.metrics.Timer.Context;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.gorgoneion.proto.Credentials;
import com.hellblazer.delos.gorgoneion.proto.Establishment;
import com.hellblazer.delos.gorgoneion.proto.SignedNonce;
import com.hellblazer.delos.stereotomy.event.proto.KERL_;
import io.grpc.stub.StreamObserver;

/**
 * Admissions service interface for client-facing identity bootstrapping operations.
 * <p>
 * This interface defines the two primary client operations in the Gorgoneion admission protocol:
 * <ul>
 *   <li><b>apply</b> - Initial application with KERL, returns BFT-endorsed nonce</li>
 *   <li><b>register</b> - Final registration with attested credentials, returns establishment</li>
 * </ul>
 * </p>
 *
 * <h2>Protocol Flow</h2>
 * <ol>
 *   <li>Client calls {@link #apply} with their KERL</li>
 *   <li>Server validates KERL and generates nonce with BFT endorsements</li>
 *   <li>Client uses nonce to obtain attestation from external service (AWS/GCP/Azure)</li>
 *   <li>Client calls {@link #register} with signed credentials</li>
 *   <li>Server validates credentials with BFT subset and returns establishment</li>
 * </ol>
 *
 * <h2>Thread Safety</h2>
 * <p>Implementations must be thread-safe as methods may be called concurrently from GRPC threads.</p>
 *
 * <h2>Error Handling</h2>
 * <p>
 * Implementations should use GRPC status codes for errors:
 * <ul>
 *   <li>UNAUTHENTICATED - Invalid KERL, credentials, or signatures</li>
 *   <li>ABORTED - Unable to gather BFT quorum</li>
 *   <li>INTERNAL - Unexpected server error</li>
 * </ul>
 * </p>
 *
 * @author hal.hildebrand
 * @see com.hellblazer.delos.gorgoneion.Gorgoneion
 */
public interface AdmissionsService {

    /**
     * Process an initial application for admission with the applicant's KERL.
     * <p>
     * This is phase 1 of the admission protocol. The server:
     * <ol>
     *   <li>Validates the KERL chain (signatures, sequence, establishment events)</li>
     *   <li>Verifies the identifier matches the sender digest</li>
     *   <li>Generates a fresh nonce with timestamp and random noise</li>
     *   <li>Obtains BFT subset endorsements for the nonce</li>
     *   <li>Returns the signed nonce to the applicant</li>
     * </ol>
     * </p>
     *
     * <h3>Preconditions</h3>
     * <ul>
     *   <li>KERL must start with InceptionEvent and end with EstablishmentEvent</li>
     *   <li>All events must have valid signatures and sequence numbers</li>
     *   <li>Identifier must be SelfAddressingIdentifier matching sender</li>
     * </ul>
     *
     * <h3>Postconditions (Success)</h3>
     * <ul>
     *   <li>Returns SignedNonce with BFT majority endorsements</li>
     *   <li>Nonce timestamp is fresh (within maxDuration + clockSkewTolerance)</li>
     *   <li>Nonce is endorsed by deterministic BFT subset for the identifier</li>
     * </ul>
     *
     * <h3>Error Cases</h3>
     * <ul>
     *   <li>Invalid KERL → UNAUTHENTICATED</li>
     *   <li>Cannot gather BFT quorum → ABORTED</li>
     *   <li>Unexpected error → INTERNAL</li>
     * </ul>
     *
     * @param application      the applicant's KERL (Key Event Receipt Log)
     * @param from             the digest ID of the applicant (must match KERL identifier)
     * @param responseObserver GRPC observer for streaming the signed nonce response
     * @param timer            metrics timer context for operation duration tracking
     */
    void apply(KERL_ application, Digest from, StreamObserver<SignedNonce> responseObserver, Context timer);

    /**
     * Register credentials with attested nonce to complete admission.
     * <p>
     * This is phase 2 of the admission protocol. The server:
     * <ol>
     *   <li>Validates the nonce signatures (BFT subset, timestamp freshness)</li>
     *   <li>Validates the KERL chain (full signature chain verification)</li>
     *   <li>Validates the attestation signature using external verifier</li>
     *   <li>Checks replay cache to prevent duplicate submissions</li>
     *   <li>Gathers BFT subset validations</li>
     *   <li>Notarizes the KERL to BFT subset for publication</li>
     *   <li>Returns establishment with provisioning data</li>
     * </ol>
     * </p>
     *
     * <h3>Preconditions</h3>
     * <ul>
     *   <li>Nonce must have BFT majority endorsements from expected subset</li>
     *   <li>Nonce timestamp must be fresh and not before attestation timestamp</li>
     *   <li>KERL must validate completely (same requirements as apply)</li>
     *   <li>Attestation signature must verify using external verifier</li>
     *   <li>Credentials must not have been previously submitted (replay check)</li>
     * </ul>
     *
     * <h3>Postconditions (Success)</h3>
     * <ul>
     *   <li>Returns Establishment with BFT validations and provisioning data</li>
     *   <li>KERL is published to unified KERL via BFT subset</li>
     *   <li>Nonce is cached to prevent replay attacks</li>
     * </ul>
     *
     * <h3>Error Cases</h3>
     * <ul>
     *   <li>Invalid credentials → UNAUTHENTICATED</li>
     *   <li>Replay attack detected → UNAUTHENTICATED</li>
     *   <li>Cannot gather BFT quorum → ABORTED</li>
     *   <li>Unexpected error → INTERNAL</li>
     * </ul>
     *
     * @param request          the credentials (signed nonce + signed attestation)
     * @param from             the digest ID of the applicant (must match KERL identifier)
     * @param responseObserver GRPC observer for streaming the establishment response
     * @param timer            metrics timer context for operation duration tracking
     */
    void register(Credentials request, Digest from, StreamObserver<Establishment> responseObserver, Context timer);

}
