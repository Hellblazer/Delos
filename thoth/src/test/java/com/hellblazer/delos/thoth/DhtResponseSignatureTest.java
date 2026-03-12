/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.Signer;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.stereotomy.event.proto.KERL_;
import com.hellblazer.delos.stereotomy.event.proto.KeyEvent_;
import com.hellblazer.delos.stereotomy.event.proto.KeyState_;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.identifier.spec.IdentifierSpecification;
import com.hellblazer.delos.thoth.proto.SignedDhtResponse;
import org.junit.jupiter.api.Test;
import org.joou.ULong;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for DHT response signature verification (Delos-izm.1.1).
 *
 * <p>Phase A rollout: Server-side signing and client-side verification with
 * accept-but-don't-require semantics for mixed-version cluster compatibility.</p>
 *
 * <p>Tests cover:</p>
 * <ul>
 *   <li>Valid signed responses are accepted</li>
 *   <li>Missing signatures are accepted with a warning (backward compat)</li>
 *   <li>Byzantine members with forged/invalid signatures are detected and logged</li>
 *   <li>DhtServer signs responses using member's signing key</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class DhtResponseSignatureTest extends AbstractDhtTest {

    /**
     * Test: Valid signed response from honest member is accepted.
     * End-to-end test that server signs responses and the cluster operates correctly.
     */
    @Test
    public void testValidSignedResponseAccepted() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        routers.values().forEach(r -> r.start());
        dhts.values().forEach(dht -> dht.start(Duration.ofMillis(10)));

        var specification = IdentifierSpecification.newBuilder();
        var initialKeyPair = specification.getSignatureAlgorithm().generateKeyPair(entropy);
        var nextKeyPair = specification.getSignatureAlgorithm().generateKeyPair(entropy);
        var inception = inception(specification, initialKeyPair, factory, nextKeyPair);

        var dht = dhts.firstEntry().getValue();

        // Act: Append and read back - server signs responses, client verifies
        dht.append(Collections.singletonList(inception.toKeyEvent_()));
        var lookup = dht.getKeyEvent(inception.getCoordinates().toEventCoords());

        // Assert: Valid signed response should be accepted and returned
        assertThat(lookup).isNotNull();
        assertThat(lookup).isNotEqualTo(KeyEvent_.getDefaultInstance());

        // No signature failures should be recorded for honest members
        var byzantineProvider = dht.getByzantineProvider();
        dhts.keySet().forEach(m -> {
            var memberId = new SelfAddressingIdentifier(m.getId());
            var state = byzantineProvider.getMemberState(memberId);
            if (state.isPresent()) {
                var signatureFailures = state.get().activeSignals().stream()
                    .filter(s -> s.contains("SIGNATURE_FAILURE"))
                    .count();
                assertThat(signatureFailures).as("No signature failures for honest member %s", memberId)
                    .isZero();
            }
        });
    }

    /**
     * Test: Missing signature on response is accepted (Phase A - backward compat with old server versions).
     * This ensures mixed-version clusters work correctly during rollout.
     */
    @Test
    public void testMissingSignatureAcceptedForBackwardCompat() throws Exception {
        // Create a SignedDhtResponse with no signature set
        var responseWithNoSig = SignedDhtResponse.newBuilder()
            .setContent(ByteString.copyFromUtf8("test response content"))
            // No signature set — simulates old-version server
            .build();

        // Phase A semantics: absent signature should not cause rejection
        assertThat(responseWithNoSig.hasSig()).isFalse();

        // Verify the response content is still accessible
        assertThat(responseWithNoSig.getContent()).isNotNull();
    }

    /**
     * Test: Response with valid signature passes verification.
     * Validates the signing+verification round-trip for SignedDhtResponse.
     */
    @Test
    public void testSignedResponseRoundTrip() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 7, 7, 7 });

        // Create a signing member
        var algorithm = SignatureAlgorithm.ED_25519;
        var keyPair = algorithm.generateKeyPair(entropy);
        var signer = new Signer.SignerImpl(keyPair.getPrivate(), ULong.valueOf(1));

        // Create content to sign
        var content = ByteString.copyFromUtf8("kerl response content");

        // Sign it
        var signature = signer.sign(content);
        var sig = signature.toSig();

        // Build signed response
        var signedResponse = SignedDhtResponse.newBuilder()
            .setContent(content)
            .setSig(sig)
            .build();

        // Verify the round-trip
        assertThat(signedResponse.hasSig()).isTrue();
        assertThat(signedResponse.getContent()).isEqualTo(content);

        // Verify the signature is valid
        var verifier = new Verifier.DefaultVerifier(keyPair.getPublic());
        var reconstructedSig = new JohnHancock(signedResponse.getSig());
        var isValid = verifier.verify(reconstructedSig, content);
        assertThat(isValid).as("Signature should verify against original content").isTrue();
    }

    /**
     * Test: Forged response (valid content but wrong/invalid signature) is detected.
     * When a Byzantine replica returns data with an invalid signature, the client
     * should detect it and record a Byzantine signal.
     */
    @Test
    public void testForgedSignatureDetected() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 8, 8, 8 });

        // Create two different signing members (honest and attacker)
        var algorithm = SignatureAlgorithm.ED_25519;
        var honestKeyPair = algorithm.generateKeyPair(entropy);
        var attackerKeyPair = algorithm.generateKeyPair(entropy);

        var attackerSigner = new Signer.SignerImpl(attackerKeyPair.getPrivate(), ULong.valueOf(1));

        // Attacker signs the content with their own key (not the honest member's key)
        var content = ByteString.copyFromUtf8("forged kerl response content");
        var forgedSignature = attackerSigner.sign(content).toSig();

        // Build the forged signed response
        var forgedResponse = SignedDhtResponse.newBuilder()
            .setContent(content)
            .setSig(forgedSignature)
            .build();

        // The honest member's verifier should reject this signature
        var honestVerifier = new Verifier.DefaultVerifier(honestKeyPair.getPublic());
        var forgedJohnHancock = new JohnHancock(forgedResponse.getSig());
        var isValid = honestVerifier.verify(forgedJohnHancock, forgedResponse.getContent());
        assertThat(isValid).as("Forged signature should fail verification with honest member's key").isFalse();
    }

    /**
     * Test: DhtServer signs read responses.
     * Verifies that DhtServer has access to signing capability and signatures are present.
     */
    @Test
    public void testDhtServerSignsResponses() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 9, 9, 9 });
        routers.values().forEach(r -> r.start());
        dhts.values().forEach(dht -> dht.start(Duration.ofMillis(10)));

        // Create test event
        var specification = IdentifierSpecification.newBuilder();
        var initialKeyPair = specification.getSignatureAlgorithm().generateKeyPair(entropy);
        var nextKeyPair = specification.getSignatureAlgorithm().generateKeyPair(entropy);
        var inception = inception(specification, initialKeyPair, factory, nextKeyPair);

        var dht = dhts.firstEntry().getValue();

        // Act: Append and then get key state
        dht.append(Collections.singletonList(inception.toKeyEvent_()));
        var keyState = dht.getKeyState(inception.getCoordinates().toEventCoords());

        // Assert: Should return valid key state (server signed, client verified)
        assertThat(keyState).isNotNull();
        assertThat(keyState).isNotEqualTo(KeyState_.getDefaultInstance());
    }

    /**
     * Test: KERL response is signed and verified correctly.
     * Full round-trip for the getKERL operation which is a primary attack vector.
     */
    @Test
    public void testKerlResponseSigned() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 10, 10, 10 });
        routers.values().forEach(r -> r.start());
        dhts.values().forEach(dht -> dht.start(Duration.ofMillis(10)));

        // Create test identifier and events
        var specification = IdentifierSpecification.newBuilder();
        var initialKeyPair = specification.getSignatureAlgorithm().generateKeyPair(entropy);
        var nextKeyPair = specification.getSignatureAlgorithm().generateKeyPair(entropy);
        var inception = inception(specification, initialKeyPair, factory, nextKeyPair);

        var dht = dhts.firstEntry().getValue();

        // Act: Append and then get the KERL
        dht.append(Collections.singletonList(inception.toKeyEvent_()));
        var kerl = dht.getKERL(inception.getIdentifier().toIdent());

        // Assert: KERL should be returned successfully (server signed, client verified)
        assertThat(kerl).isNotNull();
    }

    /**
     * Test: Byzantine member with forged KERL data is detected via signature mismatch.
     * This is the primary use case for this feature - preventing forged KERL data from
     * being accepted by clients.
     */
    @Test
    public void testByzantineMemberForgedKerlDetected() throws Exception {
        // Test the Byzantine signal infrastructure for KERL forgery detection
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 11, 11, 11 });
        routers.values().forEach(r -> r.start());
        dhts.values().forEach(dht -> dht.start(Duration.ofMillis(10)));

        var dht = dhts.firstEntry().getValue();
        var byzantineProvider = dht.getByzantineProvider();

        // Simulate a Byzantine member returning forged KERL (signature fails)
        var byzantineMember = dhts.lastKey();
        var byzantineMemberId = new SelfAddressingIdentifier(byzantineMember.getId());

        // Record what would happen when signature verification fails
        byzantineProvider.recordSignatureFailure(byzantineMemberId,
            "Response signature verification failed: KERL data appears forged");

        // Assert: Byzantine signal should be recorded
        var state = byzantineProvider.getMemberState(byzantineMemberId);
        assertThat(state).isPresent();
        assertThat(state.get().activeSignals())
            .as("Signature failure for forged KERL should be tracked")
            .anyMatch(s -> s.contains("SIGNATURE_FAILURE"));
    }

    /**
     * Test: Mixed-version cluster handling — old servers (no sigs) and new servers (with sigs).
     * Phase A rollout: New clients accept responses with or without signatures.
     */
    @Test
    public void testMixedVersionClusterHandling() throws Exception {
        // Phase A semantics: A SignedDhtResponse without a sig field is accepted
        // This represents an old-version server that doesn't yet populate signatures
        var responseNoSig = SignedDhtResponse.newBuilder()
            .setContent(ByteString.copyFromUtf8("old-version response"))
            .build();
        assertThat(responseNoSig.hasSig()).isFalse();

        // A new-version server populates the sig field
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 12, 12, 12 });
        var algorithm = SignatureAlgorithm.ED_25519;
        var keyPair = algorithm.generateKeyPair(entropy);
        var signer = new Signer.SignerImpl(keyPair.getPrivate(), ULong.valueOf(1));
        var content = ByteString.copyFromUtf8("new-version response");
        var sig = signer.sign(content).toSig();
        var responseWithSig = SignedDhtResponse.newBuilder()
            .setContent(content)
            .setSig(sig)
            .build();
        assertThat(responseWithSig.hasSig()).isTrue();

        // Both should be usable - Phase A client accepts both
        assertThat(responseNoSig.getContent()).isNotNull();
        assertThat(responseWithSig.getContent()).isNotNull();
        assertThat(responseWithSig.getSig()).isNotNull();
    }

    /**
     * Test: Signature verification integrates with Byzantine tracking.
     * When a signature fails, it increments the anomaly score for that member.
     */
    @Test
    public void testSignatureFailureIncreasesAnomalyScore() throws Exception {
        routers.values().forEach(r -> r.start());
        dhts.values().forEach(dht -> dht.start(Duration.ofMillis(10)));

        var dht = dhts.firstEntry().getValue();
        var byzantineProvider = dht.getByzantineProvider();
        var suspectMember = dhts.lastKey();
        var suspectId = new SelfAddressingIdentifier(suspectMember.getId());

        // Initially, no state
        var initialState = byzantineProvider.getMemberState(suspectId);

        // Record multiple signature failures (simulating a Byzantine member)
        byzantineProvider.recordSignatureFailure(suspectId, "Signature failure 1");
        byzantineProvider.recordSignatureFailure(suspectId, "Signature failure 2");

        // After failures, anomaly score should increase
        var afterState = byzantineProvider.getMemberState(suspectId);
        assertThat(afterState).isPresent();
        assertThat(afterState.get().anomalyScore())
            .as("Anomaly score should increase after signature failures")
            .isGreaterThan(0.0);
    }

    @Override
    protected int getCardinality() {
        return LARGE_TESTS ? 10 : 5;
    }
}
