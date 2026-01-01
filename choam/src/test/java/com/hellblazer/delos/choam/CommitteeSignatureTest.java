/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.choam.proto.*;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive negative test cases for CHOAM block signature validation in the Committee interface.
 * Tests validation at:
 * - Committee.java:113-138: validate(HashedCertifiedBlock, Certification, validators)
 * - Committee.java:140-159: validate(HashedCertifiedBlock, validators) with threshold
 * - Committee.java:161-171: validateRegeneration() for Genesis blocks
 *
 * @author hal.hildebrand
 */
public class CommitteeSignatureTest {
    private static final Logger           log = LoggerFactory.getLogger(CommitteeSignatureTest.class);
    private              Digest           viewId;
    private              StaticContext<Member> context;
    private              List<SigningMember>   members;
    private              TestCommittee         committee;
    private              Map<Member, Verifier> validators;
    private              Parameters            params;

    @BeforeEach
    public void setup() throws Exception {
        viewId = DigestAlgorithm.DEFAULT.getOrigin().prefix(1);
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        // Create 5 members for BFT context (toleranceLevel = 1 for 5 members)
        members = IntStream.range(0, 5)
                           .mapToObj(i -> stereotomy.newIdentifier())
                           .map(ControlledIdentifierMember::new)
                           .map(e -> (SigningMember) e)
                           .collect(Collectors.toList());

        context = new StaticContext<>(viewId, 0.2, members.stream().map(m -> (Member) m).toList(), 3);

        // Create validators map using consensus keys (for normal blocks)
        validators = members.stream()
                            .collect(Collectors.toMap(m -> (Member) m, m -> (Verifier) m));

        // Create test committee
        params = Parameters.newBuilder()
                           .setGenesisViewId(DigestAlgorithm.DEFAULT.getLast())
                           .build(Parameters.RuntimeParameters.newBuilder()
                                                               .setContext(context)
                                                               .setMember(members.get(0))
                                                               .build());
        committee = new TestCommittee(params);
    }

    /**
     * Test rejection of block with forged certification signature.
     * The signature bytes are manipulated but the certification claims to be from a valid validator.
     */
    @Test
    public void testRejectInvalidBlockCertification() {
        var block = createBlock(1);
        var signer = members.get(0);

        // Create valid signature then corrupt it by replacing with invalid bytes
        var validSignature = signer.sign(block.getHeader().toByteString());
        var corruptedSig = validSignature.toSig().toBuilder()
                                        .clearSignatures()
                                        .addSignatures(com.google.protobuf.ByteString.copyFrom(new byte[64])) // Invalid signature bytes
                                        .build();

        var forgedCert = Certification.newBuilder()
                                      .setId(signer.getId().toDigeste())
                                      .setSignature(corruptedSig)
                                      .build();

        var certifiedBlock = CertifiedBlock.newBuilder()
                                          .setBlock(block)
                                          .addCertifications(forgedCert)
                                          .build();

        var hb = new HashedCertifiedBlock(DigestAlgorithm.DEFAULT, certifiedBlock);

        // Single certification validation should fail
        assertFalse(committee.validate(hb, forgedCert, validators),
                    "Should reject certification with forged signature");

        // Threshold validation should also fail
        assertFalse(committee.validate(hb, validators),
                    "Should reject block with forged certification");
    }

    /**
     * Test rejection of certification from a member not in the validators map.
     * This simulates an attacker who is not part of the committee trying to certify a block.
     */
    @Test
    public void testRejectCertificationFromNonValidator() throws Exception {
        var block = createBlock(1);

        // Create a rogue member not in the validators map
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 9, 9, 9 });
        var rogueSterotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);
        var rogueMember = new ControlledIdentifierMember(rogueSterotomy.newIdentifier());

        // Rogue member creates a valid signature
        var rogueSignature = rogueMember.sign(block.getHeader().toByteString());
        var rogueCert = Certification.newBuilder()
                                     .setId(rogueMember.getId().toDigeste())
                                     .setSignature(rogueSignature.toSig())
                                     .build();

        var certifiedBlock = CertifiedBlock.newBuilder()
                                          .setBlock(block)
                                          .addCertifications(rogueCert)
                                          .build();

        var hb = new HashedCertifiedBlock(DigestAlgorithm.DEFAULT, certifiedBlock);

        // Should reject because rogue member is not in validators map
        assertFalse(committee.validate(hb, rogueCert, validators),
                    "Should reject certification from non-validator member");

        assertFalse(committee.validate(hb, validators),
                    "Should reject block certified by non-validator");
    }

    /**
     * Test rejection of certification with tampered block hash.
     * Valid signature but it was created for a different block.
     */
    @Test
    public void testRejectCertificationWithTamperedBlockHash() {
        var block = createBlock(1);
        var signer = members.get(0);

        // Create signature for a DIFFERENT block
        var differentBlock = createBlock(2);
        var signatureForDifferentBlock = signer.sign(differentBlock.getHeader().toByteString());

        // Apply this signature to the original block (wrong signature for this block)
        var wrongCert = Certification.newBuilder()
                                     .setId(signer.getId().toDigeste())
                                     .setSignature(signatureForDifferentBlock.toSig())
                                     .build();

        var certifiedBlock = CertifiedBlock.newBuilder()
                                          .setBlock(block)
                                          .addCertifications(wrongCert)
                                          .build();

        var hb = new HashedCertifiedBlock(DigestAlgorithm.DEFAULT, certifiedBlock);

        // Should reject because signature doesn't match block header
        assertFalse(committee.validate(hb, wrongCert, validators),
                    "Should reject certification with signature for different block");

        assertFalse(committee.validate(hb, validators),
                    "Should reject block with mismatched certification signature");
    }

    /**
     * Test validation threshold requirement.
     * For BFT with 5 members, toleranceLevel = 1, so need > 1 valid signatures (at least 2).
     */
    @Test
    public void testValidationThreshold() {
        var block = createBlock(1);

        // Test with exactly toleranceLevel valid signatures (should FAIL)
        var oneCert = createValidCertification(block, members.get(0));
        var certifiedBlockWithOne = CertifiedBlock.newBuilder()
                                                  .setBlock(block)
                                                  .addCertifications(oneCert)
                                                  .build();
        var hbOne = new HashedCertifiedBlock(DigestAlgorithm.DEFAULT, certifiedBlockWithOne);

        // toleranceLevel = 1, so 1 valid signature should FAIL (need > 1)
        assertFalse(committee.validate(hbOne, validators),
                    "Should reject block with only toleranceLevel signatures (need > toleranceLevel)");

        // Test with toleranceLevel + 1 valid signatures (should PASS)
        var twoCerts = CertifiedBlock.newBuilder()
                                     .setBlock(block)
                                     .addCertifications(createValidCertification(block, members.get(0)))
                                     .addCertifications(createValidCertification(block, members.get(1)))
                                     .build();
        var hbTwo = new HashedCertifiedBlock(DigestAlgorithm.DEFAULT, twoCerts);

        // 2 valid signatures should PASS (> toleranceLevel)
        assertTrue(committee.validate(hbTwo, validators),
                   "Should accept block with toleranceLevel + 1 valid signatures");

        // Test with mixed valid and invalid certifications
        var mixedCerts = CertifiedBlock.newBuilder()
                                       .setBlock(block)
                                       .addCertifications(createValidCertification(block, members.get(0)))
                                       .addCertifications(createInvalidCertification(block, members.get(1)))
                                       .addCertifications(createValidCertification(block, members.get(2)))
                                       .build();
        var hbMixed = new HashedCertifiedBlock(DigestAlgorithm.DEFAULT, mixedCerts);

        // 2 valid out of 3 should PASS
        assertTrue(committee.validate(hbMixed, validators),
                   "Should accept block with sufficient valid signatures despite some invalid");
    }

    /**
     * Test rejection of Genesis block signed with wrong identity key.
     * During Genesis, certifications use member identity keys, not consensus keys.
     */
    @Test
    public void testRejectGenesisMismatchedIdentityKey() throws Exception {
        // Create genesis block with reconfiguration
        var reconfigure = createReconfigure(viewId, members);
        var genesisBlock = Block.newBuilder()
                               .setHeader(Header.newBuilder()
                                                .setHeight(0)
                                                .setPrevious(DigestAlgorithm.DEFAULT.getOrigin().toDigeste())
                                                .build())
                               .setGenesis(Genesis.newBuilder()
                                                  .setInitialView(reconfigure)
                                                  .build())
                               .build();

        // Create rogue member not in the initial view
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 7, 7, 7 });
        var rogueSterotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);
        var rogueMember = new ControlledIdentifierMember(rogueSterotomy.newIdentifier());

        // Rogue member signs with their identity key
        var rogueSignature = rogueMember.sign(genesisBlock.getHeader().toByteString());
        var rogueCert = Certification.newBuilder()
                                     .setId(rogueMember.getId().toDigeste())
                                     .setSignature(rogueSignature.toSig())
                                     .build();

        var certifiedGenesisBlock = CertifiedBlock.newBuilder()
                                                  .setBlock(genesisBlock)
                                                  .addCertifications(rogueCert)
                                                  .build();

        var hb = new HashedCertifiedBlock(DigestAlgorithm.DEFAULT, certifiedGenesisBlock);

        // Should reject because rogue member is not in initial view
        assertFalse(committee.validateRegeneration(hb),
                    "Should reject genesis block signed by non-member identity key");
    }

    /**
     * Positive test: valid block certification should pass.
     */
    @Test
    public void testAcceptValidBlockCertification() {
        var block = createBlock(1);

        // Create sufficient valid certifications (need > toleranceLevel = 1)
        var cert1 = createValidCertification(block, members.get(0));
        var cert2 = createValidCertification(block, members.get(1));

        var certifiedBlock = CertifiedBlock.newBuilder()
                                          .setBlock(block)
                                          .addCertifications(cert1)
                                          .addCertifications(cert2)
                                          .build();

        var hb = new HashedCertifiedBlock(DigestAlgorithm.DEFAULT, certifiedBlock);

        // Individual certification validation
        assertTrue(committee.validate(hb, cert1, validators),
                   "Should accept valid certification");

        // Threshold validation
        assertTrue(committee.validate(hb, validators),
                   "Should accept block with sufficient valid certifications");
    }

    /**
     * Positive test: valid genesis block should pass.
     * Note: Genesis blocks use consensus keys from ViewMembers for validation,
     * same as regular blocks. The createReconfigureWithSigners helper creates
     * both the reconfigure and the corresponding signers.
     */
    @Test
    public void testAcceptValidGenesisBlock() {
        // Create genesis block with reconfiguration and get the signers
        var reconfigureWithSigners = createReconfigureWithSigners(viewId, members);
        var genesisBlock = Block.newBuilder()
                               .setHeader(Header.newBuilder()
                                                .setHeight(0)
                                                .setPrevious(DigestAlgorithm.DEFAULT.getOrigin().toDigeste())
                                                .build())
                               .setGenesis(Genesis.newBuilder()
                                                  .setInitialView(reconfigureWithSigners.reconfigure)
                                                  .build())
                               .build();

        // Create valid certifications using consensus key signers
        // Need > toleranceLevel = 1, so at least 2 certifications
        var signer0 = reconfigureWithSigners.signers.get(0);
        var signer1 = reconfigureWithSigners.signers.get(1);

        var cert1 = Certification.newBuilder()
                                 .setId(members.get(0).getId().toDigeste())
                                 .setSignature(signer0.sign(genesisBlock.getHeader().toByteString()).toSig())
                                 .build();

        var cert2 = Certification.newBuilder()
                                 .setId(members.get(1).getId().toDigeste())
                                 .setSignature(signer1.sign(genesisBlock.getHeader().toByteString()).toSig())
                                 .build();

        var certifiedGenesisBlock = CertifiedBlock.newBuilder()
                                                  .setBlock(genesisBlock)
                                                  .addCertifications(cert1)
                                                  .addCertifications(cert2)
                                                  .build();

        var hb = new HashedCertifiedBlock(DigestAlgorithm.DEFAULT, certifiedGenesisBlock);

        // Should accept valid genesis block with sufficient consensus-key signatures
        assertTrue(committee.validateRegeneration(hb),
                   "Should accept valid genesis block with proper consensus key certifications");
    }

    // Helper methods

    private Block createBlock(long height) {
        return Block.newBuilder()
                   .setHeader(Header.newBuilder()
                                    .setHeight(height)
                                    .setPrevious(DigestAlgorithm.DEFAULT.getOrigin().toDigeste())
                                    .setBodyHash(DigestAlgorithm.DEFAULT.getOrigin().toDigeste())
                                    .build())
                   .setExecutions(Executions.newBuilder().build())
                   .build();
    }

    private Certification createValidCertification(Block block, SigningMember signer) {
        var signature = signer.sign(block.getHeader().toByteString());
        return Certification.newBuilder()
                           .setId(signer.getId().toDigeste())
                           .setSignature(signature.toSig())
                           .build();
    }

    private Certification createInvalidCertification(Block block, SigningMember signer) {
        // Create signature for different data to make it invalid
        var wrongSignature = signer.sign(DigestAlgorithm.DEFAULT.getOrigin().toDigeste().toByteString());
        return Certification.newBuilder()
                           .setId(signer.getId().toDigeste())
                           .setSignature(wrongSignature.toSig())
                           .build();
    }

    private record ReconfigureWithSigners(Reconfigure reconfigure, List<com.hellblazer.delos.cryptography.Signer> signers) {}

    /**
     * Creates a Reconfigure with consensus keys AND returns the corresponding signers.
     * This is needed for Genesis block testing where blocks must be signed with consensus keys.
     */
    private ReconfigureWithSigners createReconfigureWithSigners(Digest viewId, List<SigningMember> members) {
        var joins = new java.util.ArrayList<Join>();
        var signers = new java.util.ArrayList<com.hellblazer.delos.cryptography.Signer>();

        for (var member : members) {
            var keyPair = params.viewSigAlgorithm().generateKeyPair();
            var signer = new com.hellblazer.delos.cryptography.Signer.SignerImpl(keyPair.getPrivate(), ULong.valueOf(0));
            signers.add(signer);

            var consensusKey = com.hellblazer.delos.cryptography.QualifiedBase64.bs(keyPair.getPublic());
            var vm = ViewMember.newBuilder()
                              .setId(member.getId().toDigeste())
                              .setView(viewId.toDigeste())
                              .setConsensusKey(consensusKey)
                              .setSignature(member.sign(consensusKey.toByteString()).toSig())
                              .build();

            var svm = SignedViewMember.newBuilder()
                                      .setVm(vm)
                                      .setSignature(member.sign(vm.toByteString()).toSig())
                                      .build();

            joins.add(Join.newBuilder().setMember(svm).build());
        }

        var reconfigure = Reconfigure.newBuilder()
                                     .setId(viewId.toDigeste())
                                     .setCheckpointTarget(10)
                                     .addAllJoins(joins)
                                     .build();

        return new ReconfigureWithSigners(reconfigure, signers);
    }

    private Reconfigure createReconfigure(Digest viewId, List<SigningMember> members) {
        return createReconfigureWithSigners(viewId, members).reconfigure;
    }

    private Join createJoin(SigningMember member) {
        // Generate consensus key for this member
        var keyPair = params.viewSigAlgorithm().generateKeyPair();
        var consensusKey = com.hellblazer.delos.cryptography.QualifiedBase64.bs(keyPair.getPublic());

        var vm = ViewMember.newBuilder()
                          .setId(member.getId().toDigeste())
                          .setView(viewId.toDigeste())
                          .setConsensusKey(consensusKey)
                          .setSignature(member.sign(consensusKey.toByteString()).toSig())
                          .build();

        var svm = SignedViewMember.newBuilder()
                                  .setVm(vm)
                                  .setSignature(member.sign(vm.toByteString()).toSig())
                                  .build();

        return Join.newBuilder()
                  .setMember(svm)
                  .build();
    }

    /**
     * Test implementation of Committee interface for validation testing.
     */
    private static class TestCommittee implements Committee {
        private final Parameters params;

        TestCommittee(Parameters params) {
            this.params = params;
        }

        @Override
        public void accept(HashedCertifiedBlock next) {
        }

        @Override
        public void complete() {
        }

        @Override
        public boolean isMember() {
            return true;
        }

        @Override
        public Logger log() {
            return log;
        }

        @Override
        public void nextView(Digest diadem, com.hellblazer.delos.context.Context<Member> pendingView) {
        }

        @Override
        public Parameters params() {
            return params;
        }

        @Override
        public boolean validate(HashedCertifiedBlock hb) {
            throw new UnsupportedOperationException("Use validate(hb, validators) instead");
        }
    }
}
