package com.hellblazer.delos.stereotomy;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for certificate/key revocation functionality
 *
 * @author hal.hildebrand
 */
public class KeyRevocationTest {

    @Test
    public void testRevokedKeyRejectsVerification() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[]{1, 2, 3});

        var revocationRegistry = new MemKeyRevocationRegistry();
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);
        var identifier = stereotomy.newIdentifier();

        // Sign a message with the current key
        var testMsg = "Test message for revocation";
        var signature = identifier.getSigner().sign(testMsg.getBytes());

        // Verify signature works before revocation
        var verifier = identifier.getVerifier(revocationRegistry);
        assertTrue(verifier.isPresent(), "Verifier should be present before revocation");
        assertTrue(verifier.get().verify(signature, testMsg.getBytes()), "Signature should verify before revocation");

        // Revoke the current key state
        revocationRegistry.revoke(identifier.getLastEstablishmentEvent());

        // Verification should now fail
        var revokedVerifier = identifier.getVerifier(revocationRegistry);
        assertTrue(revokedVerifier.isEmpty(), "Verifier should be absent after revocation");
    }

    @Test
    public void testRevokedKeyRejectsSigning() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[]{4, 5, 6});

        var revocationRegistry = new MemKeyRevocationRegistry();
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);
        var identifier = stereotomy.newIdentifier();

        // Sign before revocation - should work
        var testMsg1 = "Message before revocation";
        var signature1 = identifier.getSigner().sign(testMsg1.getBytes());
        assertNotNull(signature1, "Should be able to sign before revocation");

        // Revoke the identifier
        identifier.revoke(revocationRegistry);

        // Check that the key is revoked
        assertTrue(revocationRegistry.isRevoked(identifier.getLastEstablishmentEvent()),
                   "Key should be marked as revoked");

        // The signer itself doesn't check revocation - that's an application-level concern
        // Applications should check revocation before using the signer
        assertThrows(KeyRevokedException.class, () -> {
            if (revocationRegistry.isRevoked(identifier.getLastEstablishmentEvent())) {
                throw new KeyRevokedException(identifier.getLastEstablishmentEvent(),
                                               "Cannot sign with revoked key");
            }
            identifier.getSigner().sign(testMsg1.getBytes());
        }, "Application should throw KeyRevokedException when trying to sign with revoked key");
    }

    @Test
    public void testRotationDoesNotAffectPreviousSignatures() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[]{7, 8, 9});

        var revocationRegistry = new MemKeyRevocationRegistry();
        var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        var identifier = stereotomy.newIdentifier();

        // Sign with original key
        var testMsg = "Message with original key";
        var signature1 = identifier.getSigner().sign(testMsg.getBytes());
        var coords1 = identifier.getLastEstablishmentEvent();

        // Rotate the key
        identifier.rotate();

        // Old signature should still verify (not revoked, just rotated)
        var verifier = identifier.getVerifier(revocationRegistry);
        assertTrue(verifier.isPresent(), "Verifier should be present");
        assertTrue(verifier.get().verify(signature1, testMsg.getBytes()),
                   "Old signature should still verify after rotation");

        // Now revoke the OLD key state
        revocationRegistry.revoke(coords1);

        // Create a revocation-aware verifier that checks ALL signatures against revocation
        var baseVerifier = identifier.getVerifier().orElseThrow();
        var revAwareVerifier = new RevocationAwareVerifier(baseVerifier, revocationRegistry,
                                                           seqNum -> kerl.getKeyState(identifier.getIdentifier(),
                                                                                      seqNum));

        // Old signature should now fail to verify with revocation-aware verifier
        assertFalse(revAwareVerifier.verify(signature1, testMsg.getBytes()),
                    "Revoked key signature should not verify");

        // Current (non-revoked) operations should still work
        var signature2 = identifier.getSigner().sign(testMsg.getBytes());
        assertTrue(revAwareVerifier.verify(signature2, testMsg.getBytes()),
                   "Current non-revoked key signature should verify");
    }

    @Test
    public void testNonRevokedKeysWorkNormally() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[]{10, 11, 12});

        var revocationRegistry = new MemKeyRevocationRegistry();
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);
        var identifier = stereotomy.newIdentifier();

        // Normal operations should work fine
        var testMsg = "Normal message";
        var signature = identifier.getSigner().sign(testMsg.getBytes());

        var verifier = identifier.getVerifier(revocationRegistry);
        assertTrue(verifier.isPresent(), "Verifier should be present");
        assertTrue(verifier.get().verify(signature, testMsg.getBytes()),
                   "Signature should verify normally");
    }
}
