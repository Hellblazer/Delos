/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.gorgoneion.comm.endorsement;

import com.hellblazer.delos.gorgoneion.proto.Credentials;
import com.hellblazer.delos.gorgoneion.proto.MemberSignature;
import com.hellblazer.delos.gorgoneion.proto.Nonce;
import com.hellblazer.delos.gorgoneion.proto.Notarization;
import com.hellblazer.delos.stereotomy.event.proto.Validation_;
import com.hellblazer.delos.cryptography.Digest;

/**
 * Endorsement service interface for BFT consensus operations during admission protocol.
 * <p>
 * This interface defines the three BFT coordination operations used by Gorgoneion members
 * to reach Byzantine consensus on identity admissions:
 * <ul>
 *   <li><b>endorse</b> - Sign nonces during nonce generation phase</li>
 *   <li><b>validate</b> - Validate credentials during registration phase</li>
 *   <li><b>enroll</b> - Publish notarized KERL to unified log</li>
 * </ul>
 * </p>
 *
 * <h2>BFT Protocol Flow</h2>
 * <ol>
 *   <li>Facilitating member generates nonce and requests {@link #endorse} from BFT subset</li>
 *   <li>Subset members sign the nonce and return signatures</li>
 *   <li>After client attestation, facilitating member requests {@link #validate} from BFT subset</li>
 *   <li>Subset members validate credentials and return signed validations</li>
 *   <li>Facilitating member calls {@link #enroll} on BFT subset to publish validated KERL</li>
 * </ol>
 *
 * <h2>BFT Subset Membership</h2>
 * <p>
 * The BFT subset is deterministically computed using:
 * {@code context.bftSubset(digestOf(identifier))}
 * <br>
 * This ensures all members agree on which subset validates a given identifier.
 * </p>
 *
 * <h2>Thread Safety</h2>
 * <p>Implementations must be thread-safe as methods may be called concurrently from GRPC threads.</p>
 *
 * <h2>Error Handling</h2>
 * <p>
 * Implementations should throw StatusRuntimeException with appropriate codes:
 * <ul>
 *   <li>UNAUTHENTICATED - Invalid signatures, timestamps, or KERL</li>
 *   <li>PERMISSION_DENIED - Sender not in expected BFT subset</li>
 *   <li>INTERNAL - Unexpected server error</li>
 * </ul>
 * </p>
 *
 * @author hal.hildebrand
 * @see com.hellblazer.delos.gorgoneion.Gorgoneion
 * @see AdmissionsService
 */
public interface EndorsementService {

    /**
     * Endorse a nonce by signing it as part of the BFT subset.
     * <p>
     * This method is called during nonce generation. The BFT subset members
     * validate the nonce (issuer, timestamp, noise) and return their signature
     * if valid. The facilitating member gathers these signatures until a majority
     * is reached, then returns the signed nonce to the applicant.
     * </p>
     *
     * <h3>Preconditions</h3>
     * <ul>
     *   <li>Nonce issuer must be a valid context member</li>
     *   <li>Nonce sender (from) must equal issuer (no delegation)</li>
     *   <li>Nonce timestamp must be fresh (within clock skew tolerance)</li>
     *   <li>Nonce noise must be non-empty (random digest for uniqueness)</li>
     *   <li>Nonce member identifier must be non-empty</li>
     * </ul>
     *
     * <h3>Postconditions (Success)</h3>
     * <ul>
     *   <li>Returns MemberSignature containing this member's ID and signature</li>
     *   <li>Signature covers the entire nonce protobuf</li>
     * </ul>
     *
     * <h3>Replay Prevention</h3>
     * <p>
     * Nonce endorsement does NOT check replay cache. Multiple BFT members
     * legitimately sign the same nonce during generation. Replay prevention
     * occurs during credential registration.
     * </p>
     *
     * <h3>Error Cases</h3>
     * <ul>
     *   <li>Invalid nonce → UNAUTHENTICATED</li>
     *   <li>Sender not issuer → UNAUTHENTICATED</li>
     * </ul>
     *
     * @param request the nonce to endorse (timestamp, issuer, noise, member identifier)
     * @param from    the digest ID of the sender (must equal nonce.issuer)
     * @return member signature covering the nonce
     * @throws io.grpc.StatusRuntimeException if nonce validation fails
     */
    MemberSignature endorse(Nonce request, Digest from);

    /**
     * Enroll a notarized KERL by publishing it to the unified KERL log.
     * <p>
     * This is the final phase of admission. After the facilitating member
     * gathers BFT majority validations, it sends the notarization (KERL +
     * validations) to the BFT subset. Each member verifies the validations
     * come from the correct BFT subset and form a majority, then publishes
     * the KERL to the unified log via the observer.
     * </p>
     *
     * <h3>Preconditions</h3>
     * <ul>
     *   <li>KERL must end with EstablishmentEvent</li>
     *   <li>Validations must be from expected BFT subset for this identifier</li>
     *   <li>Validations must verify using validators' current keys</li>
     *   <li>Must have BFT majority of valid signatures</li>
     * </ul>
     *
     * <h3>Postconditions (Success)</h3>
     * <ul>
     *   <li>KERL and validations published to unified KERL via observer</li>
     *   <li>Identifier is now admitted and recognized by the cluster</li>
     * </ul>
     *
     * <h3>BFT Subset Verification</h3>
     * <p>
     * Each validation signature is checked:
     * <ol>
     *   <li>Validator must be in expected BFT subset (deterministic computation)</li>
     *   <li>Signature must verify using validator's current KERL state keys</li>
     *   <li>Must reach BFT majority threshold</li>
     * </ol>
     * </p>
     *
     * <h3>Error Cases</h3>
     * <ul>
     *   <li>Invalid notarization → UNAUTHENTICATED</li>
     *   <li>Validators not in BFT subset → UNAUTHENTICATED (logged and skipped)</li>
     *   <li>No majority → UNAUTHENTICATED</li>
     * </ul>
     *
     * @param request the notarization (KERL + BFT validations)
     * @param from    the digest ID of the facilitating member
     * @throws io.grpc.StatusRuntimeException if notarization validation fails
     */
    void enroll(Notarization request, Digest from);

    /**
     * Validate credentials by verifying attestation and signing KERL inception event.
     * <p>
     * This method is called during the registration phase. BFT subset members
     * validate the credentials (nonce + attestation) and return a signed validation
     * of the inception event if valid. The facilitating member gathers these
     * validations to form the establishment.
     * </p>
     *
     * <h3>Preconditions</h3>
     * <ul>
     *   <li>Credentials must pass full validation (nonce, KERL, attestation)</li>
     *   <li>Nonce must have BFT majority endorsements from expected subset</li>
     *   <li>KERL must validate completely (chain, signatures, sequence)</li>
     *   <li>Attestation must verify using external verifier predicate</li>
     * </ul>
     *
     * <h3>Postconditions (Success)</h3>
     * <ul>
     *   <li>Returns Validation_ containing validator's signature on inception event</li>
     *   <li>Validation includes validator's KERL coordinates for key lookup</li>
     * </ul>
     *
     * <h3>Validation Process</h3>
     * <ol>
     *   <li>Validate credentials using full credential validation logic</li>
     *   <li>Extract inception event from KERL</li>
     *   <li>Sign inception event using validator's signing key</li>
     *   <li>Return validation with signature and validator coordinates</li>
     * </ol>
     *
     * <h3>Error Cases</h3>
     * <ul>
     *   <li>Invalid credentials → UNAUTHENTICATED</li>
     *   <li>Attestation verification fails → returns null (caller handles)</li>
     * </ul>
     *
     * @param credentials the credentials to validate (signed nonce + signed attestation)
     * @param id          the digest ID of the applicant
     * @return validation with signature on inception event, or null if attestation fails
     * @throws io.grpc.StatusRuntimeException if credential validation fails
     */
    Validation_ validate(Credentials credentials, Digest id);
}
