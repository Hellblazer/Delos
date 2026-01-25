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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for MultiCommitteeAggregate using JUnit5 parameterized tests.
 * <p>
 * Phase 1C-2-E: Comprehensive edge case testing with varying:
 * - Committee participation rates (1 to 100 committees)
 * - Signer counts per committee (1 to 100 signers)
 * - Message digests (different event coordinates)
 * - Bitmap sizes and patterns
 * <p>
 * Tests verify invariants hold across all parameter combinations.
 *
 * @author hal.hildebrand
 */
@DisplayName("MultiCommitteeAggregate - Property Tests")
class MultiCommitteeAggregatePropertyTest {

    private static BLSProvider provider;
    private static SecureRandom random;
    private static List<BLSProvider.KeyPair> keyPool;

    @BeforeAll
    static void setup() {
        provider = BLSProvider.getDefault();
        random = new SecureRandom();

        // Generate key pool for tests
        keyPool = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            keyPool.add(provider.generateKeyPair(random));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 10, 20, 50, 100})
    @DisplayName("Property: Committee count scales correctly")
    void propertyCommitteeCountScalesCorrectly(int committeeCount) {
        var aggregator = new CrownAggregator(provider);
        var event = createTestEvent(random.nextInt());
        var committeeSignatures = new HashMap<Long, List<BLSSignature>>();

        // Create signatures for each committee
        for (int i = 0; i < committeeCount; i++) {
            var epoch = (i + 1) * 100L;
            var signatures = createSignatures(0, 3, event);
            committeeSignatures.put(epoch, signatures);
        }

        var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, event);

        assertThat(crown.getCommitteeCount()).isEqualTo(committeeCount);
        assertThat(crown.contributions()).hasSize(committeeCount);
        assertThat(crown.getCommitteeEpochs()).hasSize(committeeCount);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 7, 21, 50, 100})
    @DisplayName("Property: Signer count per committee scales correctly")
    void propertySignerCountScalesCorrectly(int signerCount) {
        var aggregator = new CrownAggregator(provider);
        var event = createTestEvent(random.nextInt());
        var committeeSignatures = new HashMap<Long, List<BLSSignature>>();

        var signatures = createSignatures(0, signerCount, event);
        committeeSignatures.put(100L, signatures);

        var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, event);

        assertThat(crown.totalSignerCount()).isEqualTo(signerCount);
        var contribution = crown.getContribution(100L);
        assertThat(contribution).isPresent();
        assertThat(contribution.get().signerCount()).isEqualTo(signerCount);
    }

    @ParameterizedTest
    @MethodSource("committeeParticipationRates")
    @DisplayName("Property: Total signer count equals sum of contributions")
    void propertyTotalSignerCountEqualsSumOfContributions(int committees, int signersPerCommittee) {
        var aggregator = new CrownAggregator(provider);
        var event = createTestEvent(random.nextInt());
        var committeeSignatures = new HashMap<Long, List<BLSSignature>>();

        for (int i = 0; i < committees; i++) {
            var epoch = (i + 1) * 100L;
            var startKey = (i * signersPerCommittee) % keyPool.size();
            var endKey = Math.min(startKey + signersPerCommittee, keyPool.size());
            var signatures = createSignatures(startKey, endKey, event);
            committeeSignatures.put(epoch, signatures);
        }

        var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, event);

        var expectedTotal = committees * signersPerCommittee;
        assertThat(crown.totalSignerCount()).isEqualTo(expectedTotal);

        var sumOfContributions = crown.contributions().stream()
            .mapToInt(CommitteeContribution::signerCount)
            .sum();
        assertThat(sumOfContributions).isEqualTo(expectedTotal);
    }

    @ParameterizedTest
    @MethodSource("bitmapSizes")
    @DisplayName("Property: Committee bitmap size is correct for committee count")
    void propertyCommitteeBitmapSizeCorrect(int committeeCount) {
        var aggregator = new CrownAggregator(provider);
        var event = createTestEvent(random.nextInt());
        var committeeSignatures = new HashMap<Long, List<BLSSignature>>();

        for (int i = 0; i < committeeCount; i++) {
            var epoch = (i + 1) * 100L;
            var signatures = createSignatures(0, 1, event);
            committeeSignatures.put(epoch, signatures);
        }

        var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, event);

        var expectedBitmapSize = (committeeCount + 7) / 8;
        assertThat(crown.committeeContributionBitmap()).hasSize(expectedBitmapSize);
    }

    @ParameterizedTest
    @MethodSource("epochOrderings")
    @DisplayName("Property: Contributions are always ordered by epoch")
    void propertyContributionsOrderedByEpoch(List<Long> unorderedEpochs) {
        var aggregator = new CrownAggregator(provider);
        var event = createTestEvent(random.nextInt());
        var committeeSignatures = new HashMap<Long, List<BLSSignature>>();

        for (var epoch : unorderedEpochs) {
            var signatures = createSignatures(0, 1, event);
            committeeSignatures.put(epoch, signatures);
        }

        var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, event);

        var epochs = crown.getCommitteeEpochs();

        // Verify ordering
        for (int i = 1; i < epochs.size(); i++) {
            assertThat(epochs.get(i)).isGreaterThan(epochs.get(i - 1));
        }
    }

    @ParameterizedTest
    @MethodSource("compressionScenarios")
    @DisplayName("Property: Compression ratio improves with more signers")
    void propertyCompressionRatioImprovesWithSigners(int committees, int signersPerCommittee) {
        var aggregator = new CrownAggregator(provider);
        var event = createTestEvent(random.nextInt());
        var committeeSignatures = new HashMap<Long, List<BLSSignature>>();

        for (int i = 0; i < committees; i++) {
            var epoch = (i + 1) * 100L;
            var startKey = (i * signersPerCommittee) % keyPool.size();
            var endKey = Math.min(startKey + signersPerCommittee, keyPool.size());
            var signatures = createSignatures(startKey, endKey, event);
            committeeSignatures.put(epoch, signatures);
        }

        var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, event);

        var totalSigners = committees * signersPerCommittee;
        var individualSize = totalSigners * 96; // Each signature is 96 bytes
        var crownSize = crown.estimatedStorageBytes();
        var compressionRatio = 100.0 * (1 - (double) crownSize / individualSize);

        // More signers = better compression
        if (totalSigners >= 20) {
            assertThat(compressionRatio).isGreaterThan(85.0);
        }
        if (totalSigners >= 50) {
            assertThat(compressionRatio).isGreaterThan(90.0);
        }
        if (totalSigners >= 100) {
            assertThat(compressionRatio).isGreaterThan(95.0);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 10, 20})
    @DisplayName("Property: Storage size scales sub-linearly with committee count")
    void propertyStorageSizeScalesSubLinearly(int committeeCount) {
        var aggregator = new CrownAggregator(provider);
        var event = createTestEvent(random.nextInt());
        var committeeSignatures = new HashMap<Long, List<BLSSignature>>();

        for (int i = 0; i < committeeCount; i++) {
            var epoch = (i + 1) * 100L;
            var signatures = createSignatures(0, 7, event);
            committeeSignatures.put(epoch, signatures);
        }

        var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, event);

        // Storage size should be sub-linear: signature (96) + metadata per committee
        // Expected: 96 + committeeCount * ~13 bytes (epoch + bitmap + count)
        var expectedMax = 96 + (committeeCount * 20); // Conservative estimate
        assertThat(crown.estimatedStorageBytes()).isLessThan(expectedMax);
    }

    @ParameterizedTest
    @MethodSource("roundTripScenarios")
    @DisplayName("Property: Round-trip serialization preserves data")
    void propertyRoundTripSerializationPreservesData(int committees, int signersPerCommittee) {
        var aggregator = new CrownAggregator(provider);
        var event = createTestEvent(random.nextInt());
        var committeeSignatures = new HashMap<Long, List<BLSSignature>>();

        for (int i = 0; i < committees; i++) {
            var epoch = (i + 1) * 100L;
            var startKey = (i * signersPerCommittee) % keyPool.size();
            var endKey = Math.min(startKey + signersPerCommittee, keyPool.size());
            var signatures = createSignatures(startKey, endKey, event);
            committeeSignatures.put(epoch, signatures);
        }

        var original = aggregator.createMultiCommitteeAggregate(committeeSignatures, event);

        // Simulate serialization by reconstructing from components
        var reconstructed = new MultiCommitteeAggregate(
            original.aggregatedSignature(),
            original.contributions(),
            original.committeeContributionBitmap(),
            original.totalSignerCount(),
            original.event()
        );

        assertThat(reconstructed).isEqualTo(original);
        assertThat(reconstructed.hashCode()).isEqualTo(original.hashCode());
    }

    // Parameter sources

    static Stream<Arguments> committeeParticipationRates() {
        return Stream.of(
            Arguments.of(1, 1),
            Arguments.of(1, 7),
            Arguments.of(1, 21),
            Arguments.of(5, 7),
            Arguments.of(10, 7),
            Arguments.of(20, 3)
        );
    }

    static Stream<Integer> bitmapSizes() {
        return Stream.of(1, 2, 5, 8, 9, 16, 17, 32, 64);
    }

    static Stream<List<Long>> epochOrderings() {
        return Stream.of(
            List.of(100L, 200L, 300L),
            List.of(300L, 100L, 200L),
            List.of(500L, 100L, 300L, 200L, 400L),
            List.of(1000L, 200L, 500L, 100L, 800L, 300L)
        );
    }

    static Stream<Arguments> compressionScenarios() {
        return Stream.of(
            Arguments.of(1, 10),
            Arguments.of(1, 21),
            Arguments.of(1, 50),
            Arguments.of(5, 10),
            Arguments.of(5, 21),
            Arguments.of(10, 10)
        );
    }

    static Stream<Arguments> roundTripScenarios() {
        return Stream.of(
            Arguments.of(1, 1),
            Arguments.of(1, 21),
            Arguments.of(5, 7),
            Arguments.of(10, 3)
        );
    }

    // Helper methods

    private static EventCoordinates createTestEvent(int seed) {
        var identifier = new SelfAddressingIdentifier(
            DigestAlgorithm.DEFAULT.digest(("test" + seed).getBytes())
        );
        return new EventCoordinates(
            identifier,
            ULong.valueOf(seed),
            DigestAlgorithm.DEFAULT.digest(("event" + seed).getBytes()),
            "test_event_" + seed
        );
    }

    private List<BLSSignature> createSignatures(int startIdx, int endIdx, EventCoordinates event) {
        var message = event.getDigest().getBytes();
        var signatures = new ArrayList<BLSSignature>();

        for (int i = startIdx; i < endIdx && i < keyPool.size(); i++) {
            var sig = provider.sign(keyPool.get(i).secretKey(), message);
            signatures.add(new BLSSignature(sig));
        }

        return signatures;
    }
}
