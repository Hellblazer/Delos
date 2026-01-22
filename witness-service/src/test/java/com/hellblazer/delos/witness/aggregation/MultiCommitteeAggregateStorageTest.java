/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSProvider;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Storage compression and efficiency tests for MultiCommitteeAggregate.
 * <p>
 * Phase 1C-2-E: Validates storage compression targets:
 * - 90%+ compression for typical scenarios (21+ signers)
 * - Storage size breakdown analysis
 * - Comparison: single large aggregate vs multi-committee
 * - Bitmap efficiency
 * <p>
 * Target: 105 signers (5 committees × 21 signers) = 10,080 bytes → ~176 bytes (98.3% compression)
 *
 * @author hal.hildebrand
 */
@DisplayName("MultiCommitteeAggregate - Storage Compression Tests")
class MultiCommitteeAggregateStorageTest {

    private static BLSProvider provider;
    private static SecureRandom random;
    private static List<BLSProvider.KeyPair> keyPool;
    private static EventCoordinates testEvent;
    private static byte[] testMessage;

    @BeforeAll
    static void setup() {
        provider = BLSProvider.getDefault();
        random = new SecureRandom();

        // Generate key pool
        keyPool = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            keyPool.add(provider.generateKeyPair(random));
        }

        // Create test event
        var identifier = new SelfAddressingIdentifier(
            DigestAlgorithm.DEFAULT.digest("test".getBytes())
        );
        testEvent = new EventCoordinates(
            identifier,
            ULong.valueOf(1),
            DigestAlgorithm.DEFAULT.digest("event".getBytes()),
            "test_event"
        );
        testMessage = testEvent.getDigest().getBytes();
    }

    @Test
    @DisplayName("Target compression: 5 committees × 21 signers = 98%+ compression")
    void targetCompressionFiveCommittees21Signers() {
        var aggregator = new CrownAggregator(provider);

        // Create 5 committees with 21 signers each
        var committeeSignatures = new HashMap<Long, List<BLSSignature>>();
        for (int i = 0; i < 5; i++) {
            var epoch = (i + 1) * 100L;
            var startKey = i * 21;
            var signatures = createSignatures(startKey, startKey + 21);
            committeeSignatures.put(epoch, signatures);
        }

        var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent);

        var individualSize = 105 * 96; // 105 signers × 96 bytes = 10,080 bytes
        var crownSize = crown.estimatedStorageBytes();
        var compressionRatio = 100.0 * (1 - (double) crownSize / individualSize);

        System.out.printf("Target compression test:%n");
        System.out.printf("  Individual signatures: %d bytes (105 × 96)%n", individualSize);
        System.out.printf("  Crown aggregate:       %d bytes%n", crownSize);
        System.out.printf("  Compression ratio:     %.2f%%%n", compressionRatio);

        assertThat(compressionRatio).isGreaterThanOrEqualTo(98.0);
        assertThat(crownSize).isLessThanOrEqualTo(200); // Should be ~176 bytes
    }

    @Test
    @DisplayName("Compression improves with more signers")
    void compressionImprovesWithMoreSigners() {
        var aggregator = new CrownAggregator(provider);

        // Test increasing signer counts
        var signerCounts = List.of(7, 14, 21, 42, 105);
        var compressionRatios = new ArrayList<Double>();

        System.out.printf("Compression vs signer count:%n");

        for (var signerCount : signerCounts) {
            var committeeSignatures = new HashMap<Long, List<BLSSignature>>();
            var signatures = createSignatures(0, signerCount);
            committeeSignatures.put(100L, signatures);

            var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent);

            var individualSize = signerCount * 96;
            var crownSize = crown.estimatedStorageBytes();
            var compressionRatio = 100.0 * (1 - (double) crownSize / individualSize);
            compressionRatios.add(compressionRatio);

            System.out.printf("  %3d signers: %5d → %3d bytes (%.2f%%)%n",
                              signerCount, individualSize, crownSize, compressionRatio);
        }

        // Compression should improve monotonically
        for (int i = 1; i < compressionRatios.size(); i++) {
            assertThat(compressionRatios.get(i)).isGreaterThan(compressionRatios.get(i - 1));
        }

        // Validate specific thresholds
        assertThat(compressionRatios.get(0)).isGreaterThan(75.0);  // 7 signers > 75%
        assertThat(compressionRatios.get(2)).isGreaterThan(90.0);  // 21 signers > 90%
        assertThat(compressionRatios.get(4)).isGreaterThan(98.0);  // 105 signers > 98%
    }

    @Test
    @DisplayName("Storage breakdown: signature + metadata analysis")
    void storageBreakdownAnalysis() {
        var aggregator = new CrownAggregator(provider);

        // Create aggregate with known structure
        var committees = 3;
        var signersPerCommittee = 7;

        var committeeSignatures = new HashMap<Long, List<BLSSignature>>();
        for (int i = 0; i < committees; i++) {
            var epoch = (i + 1) * 100L;
            var startKey = i * signersPerCommittee;
            var signatures = createSignatures(startKey, startKey + signersPerCommittee);
            committeeSignatures.put(epoch, signatures);
        }

        var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent);

        // Calculate component sizes
        var signatureSize = 96; // BLS signature
        var committeeBitmapSize = crown.committeeContributionBitmap().length;
        var totalSignerCountSize = 4; // int

        var contributionsSize = crown.contributions().stream()
            .mapToInt(c -> 8 + c.signerBitmap().length + 4) // epoch(8) + bitmap + count(4)
            .sum();

        var totalSize = signatureSize + committeeBitmapSize + totalSignerCountSize + contributionsSize;

        System.out.printf("Storage breakdown (%d committees, %d signers):%n", committees, committees * signersPerCommittee);
        System.out.printf("  Signature:          %3d bytes%n", signatureSize);
        System.out.printf("  Committee bitmap:   %3d bytes%n", committeeBitmapSize);
        System.out.printf("  Total signer count: %3d bytes%n", totalSignerCountSize);
        System.out.printf("  Contributions:      %3d bytes%n", contributionsSize);
        System.out.printf("  Total:              %3d bytes%n", totalSize);

        assertThat(crown.estimatedStorageBytes()).isEqualTo(totalSize);
    }

    @Test
    @DisplayName("Comparison: single aggregate vs multi-committee aggregate")
    void comparisonSingleVsMultiCommittee() {
        var aggregator = new CrownAggregator(provider);

        var totalSigners = 21;

        // Scenario 1: Single committee with 21 signers
        var singleCommitteeSignatures = new HashMap<Long, List<BLSSignature>>();
        singleCommitteeSignatures.put(100L, createSignatures(0, 21));
        var singleCommitteeAggregate = aggregator.createMultiCommitteeAggregate(singleCommitteeSignatures, testEvent);

        // Scenario 2: 3 committees with 7 signers each
        var multiCommitteeSignatures = new HashMap<Long, List<BLSSignature>>();
        for (int i = 0; i < 3; i++) {
            var epoch = (i + 1) * 100L;
            var startKey = i * 7;
            multiCommitteeSignatures.put(epoch, createSignatures(startKey, startKey + 7));
        }
        var multiCommitteeAggregate = aggregator.createMultiCommitteeAggregate(multiCommitteeSignatures, testEvent);

        System.out.printf("Single vs Multi-committee comparison (21 signers total):%n");
        System.out.printf("  Single committee (21):     %d bytes%n", singleCommitteeAggregate.estimatedStorageBytes());
        System.out.printf("  Multi-committee (3×7):     %d bytes%n", multiCommitteeAggregate.estimatedStorageBytes());

        // Multi-committee should have slightly more overhead (3 contributions vs 1)
        // but should still be very compact
        var overhead = multiCommitteeAggregate.estimatedStorageBytes() - singleCommitteeAggregate.estimatedStorageBytes();
        System.out.printf("  Overhead for multi-committee: %d bytes%n", overhead);

        // Overhead should be small (roughly 2 extra contributions × 13 bytes = 26 bytes)
        assertThat(overhead).isLessThan(50);

        // Both should achieve > 90% compression
        var individualSize = totalSigners * 96;
        var singleRatio = 100.0 * (1 - (double) singleCommitteeAggregate.estimatedStorageBytes() / individualSize);
        var multiRatio = 100.0 * (1 - (double) multiCommitteeAggregate.estimatedStorageBytes() / individualSize);

        assertThat(singleRatio).isGreaterThan(90.0);
        assertThat(multiRatio).isGreaterThan(90.0);
    }

    @Test
    @DisplayName("Bitmap efficiency: signer count vs bitmap size")
    void bitmapEfficiencySignerCountVsBitmapSize() {
        var aggregator = new CrownAggregator(provider);

        // Test various signer counts
        var signerCounts = List.of(1, 7, 8, 9, 16, 21, 32, 50, 64, 100);

        System.out.printf("Bitmap efficiency:%n");

        for (var signerCount : signerCounts) {
            var committeeSignatures = new HashMap<Long, List<BLSSignature>>();
            var signatures = createSignatures(0, signerCount);
            committeeSignatures.put(100L, signatures);

            var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent);

            var contribution = crown.getContribution(100L).orElseThrow();
            var bitmapSize = contribution.signerBitmap().length;
            var expectedSize = (signerCount + 7) / 8;

            System.out.printf("  %3d signers: %2d bytes bitmap (expected: %2d)%n",
                              signerCount, bitmapSize, expectedSize);

            assertThat(bitmapSize).isEqualTo(expectedSize);
        }
    }

    @Test
    @DisplayName("Committee bitmap size scales correctly")
    void committeeBitmapSizeScalesCorrectly() {
        var aggregator = new CrownAggregator(provider);

        // Test various committee counts
        var committeeCounts = List.of(1, 2, 8, 9, 16, 17, 32, 64);

        System.out.printf("Committee bitmap size scaling:%n");

        for (var committeeCount : committeeCounts) {
            var committeeSignatures = new HashMap<Long, List<BLSSignature>>();
            for (int i = 0; i < committeeCount; i++) {
                var epoch = (i + 1) * 100L;
                var signatures = createSignatures(0, 1);
                committeeSignatures.put(epoch, signatures);
            }

            var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent);

            var bitmapSize = crown.committeeContributionBitmap().length;
            var expectedSize = (committeeCount + 7) / 8;

            System.out.printf("  %2d committees: %2d bytes bitmap (expected: %2d)%n",
                              committeeCount, bitmapSize, expectedSize);

            assertThat(bitmapSize).isEqualTo(expectedSize);
        }
    }

    @Test
    @DisplayName("Large-scale compression: 10 committees × 10 signers")
    void largeScaleCompressionTenCommittees() {
        var aggregator = new CrownAggregator(provider);

        // 10 committees with 10 signers each = 100 total signers
        var committeeSignatures = new HashMap<Long, List<BLSSignature>>();
        for (int i = 0; i < 10; i++) {
            var epoch = (i + 1) * 100L;
            var startKey = i * 10;
            var signatures = createSignatures(startKey, startKey + 10);
            committeeSignatures.put(epoch, signatures);
        }

        var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent);

        var individualSize = 100 * 96; // 9,600 bytes
        var crownSize = crown.estimatedStorageBytes();
        var compressionRatio = 100.0 * (1 - (double) crownSize / individualSize);

        System.out.printf("Large-scale compression (10 committees × 10 signers):%n");
        System.out.printf("  Individual signatures: %d bytes%n", individualSize);
        System.out.printf("  Crown aggregate:       %d bytes%n", crownSize);
        System.out.printf("  Compression ratio:     %.2f%%%n", compressionRatio);

        assertThat(compressionRatio).isGreaterThan(97.0);
    }

    @Test
    @DisplayName("Worst-case compression: 1 committee × 1 signer")
    void worstCaseCompressionSingleSigner() {
        var aggregator = new CrownAggregator(provider);

        var committeeSignatures = new HashMap<Long, List<BLSSignature>>();
        committeeSignatures.put(100L, createSignatures(0, 1));

        var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent);

        var individualSize = 1 * 96; // 96 bytes
        var crownSize = crown.estimatedStorageBytes();
        var overhead = crownSize - individualSize;

        System.out.printf("Worst-case scenario (1 signer):%n");
        System.out.printf("  Individual signature: %d bytes%n", individualSize);
        System.out.printf("  Crown aggregate:      %d bytes%n", crownSize);
        System.out.printf("  Overhead:             %d bytes%n", overhead);

        // Even worst case should have minimal overhead
        // Overhead: committee bitmap (1) + total count (4) + contribution (epoch(8) + bitmap(1) + count(4)) = 18 bytes
        // Total: 96 + 18 = 114 bytes
        assertThat(overhead).isLessThan(30);
    }

    @Test
    @DisplayName("Metadata overhead percentage vs signer count")
    void metadataOverheadPercentageVsSignerCount() {
        var aggregator = new CrownAggregator(provider);

        var signerCounts = List.of(1, 7, 21, 50, 100);

        System.out.printf("Metadata overhead analysis:%n");

        for (var signerCount : signerCounts) {
            var committeeSignatures = new HashMap<Long, List<BLSSignature>>();
            var signatures = createSignatures(0, signerCount);
            committeeSignatures.put(100L, signatures);

            var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent);

            var signatureSize = 96;
            var metadataSize = crown.estimatedStorageBytes() - signatureSize;
            var overheadPercent = 100.0 * metadataSize / crown.estimatedStorageBytes();

            System.out.printf("  %3d signers: metadata=%3d bytes (%.1f%% of total)%n",
                              signerCount, metadataSize, overheadPercent);
        }

        // Metadata overhead should decrease as signer count increases
        // (signature is constant 96 bytes, metadata is small and fixed)
    }

    // Helper methods

    private List<BLSSignature> createSignatures(int startIdx, int endIdx) {
        var signatures = new ArrayList<BLSSignature>();
        for (int i = startIdx; i < endIdx && i < keyPool.size(); i++) {
            var sig = provider.sign(keyPool.get(i).secretKey(), testMessage);
            signatures.add(new BLSSignature(sig));
        }
        return signatures;
    }
}
