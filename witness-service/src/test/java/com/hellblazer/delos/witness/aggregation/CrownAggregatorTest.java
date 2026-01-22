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
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Test CrownAggregator for multi-committee signature aggregation.
 * Phase 1C-2-B: Crown aggregation.
 *
 * @author hal.hildebrand
 */
class CrownAggregatorTest {

    private static BLSProvider provider;
    private static EventCoordinates testEvent;
    private static byte[] testMessage;
    private static List<BLSProvider.KeyPair> keyPairs;

    @BeforeAll
    static void setup() {
        provider = BLSProvider.getDefault();

        // Create test event
        var identifier = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("test".getBytes()));
        testEvent = new EventCoordinates(
            identifier,
            ULong.valueOf(1),
            DigestAlgorithm.DEFAULT.digest("event".getBytes()),
            "test_event"
        );

        testMessage = DigestAlgorithm.DEFAULT.digest("test message".getBytes()).getBytes();

        // Generate test key pairs
        var random = new SecureRandom();
        keyPairs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            keyPairs.add(provider.generateKeyPair(random));
        }
    }

    @Test
    void shouldCreateCrownAggregateFromSingleCommittee() {
        var aggregator = new CrownAggregator(provider);

        // Create signatures from one committee
        var committee1Signatures = new ArrayList<BLSSignature>();
        for (int i = 0; i < 3; i++) {
            var sig = provider.sign(keyPairs.get(i).secretKey(), testMessage);
            committee1Signatures.add(new BLSSignature(sig));
        }

        var committeeSignatures = new HashMap<Long, List<BLSSignature>>();
        committeeSignatures.put(100L, committee1Signatures);

        var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent);

        assertThat(crown).isNotNull();
        assertThat(crown.getCommitteeCount()).isEqualTo(1);
        assertThat(crown.totalSignerCount()).isEqualTo(3);
        assertThat(crown.hasCommittee(100L)).isTrue();
    }

    @Test
    void shouldCreateCrownAggregateFromMultipleCommittees() {
        var aggregator = new CrownAggregator(provider);

        // Create signatures from multiple committees
        var committee1Signatures = new ArrayList<BLSSignature>();
        for (int i = 0; i < 3; i++) {
            var sig = provider.sign(keyPairs.get(i).secretKey(), testMessage);
            committee1Signatures.add(new BLSSignature(sig));
        }

        var committee2Signatures = new ArrayList<BLSSignature>();
        for (int i = 3; i < 6; i++) {
            var sig = provider.sign(keyPairs.get(i).secretKey(), testMessage);
            committee2Signatures.add(new BLSSignature(sig));
        }

        Map<Long, List<BLSSignature>> committeeSignatures = Map.of(
            100L, committee1Signatures,
            200L, committee2Signatures
        );

        var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent);

        assertThat(crown).isNotNull();
        assertThat(crown.getCommitteeCount()).isEqualTo(2);
        assertThat(crown.totalSignerCount()).isEqualTo(6);
        assertThat(crown.hasCommittee(100L)).isTrue();
        assertThat(crown.hasCommittee(200L)).isTrue();
        assertThat(crown.getCommitteeEpochs()).containsExactly(100L, 200L);
    }

    @Test
    void shouldOrderContributionsByEpoch() {
        var aggregator = new CrownAggregator(provider);

        // Add committees in non-sequential order
        var committeeSignatures = new HashMap<Long, List<BLSSignature>>();

        committeeSignatures.put(300L, createSignatures(0, 2));
        committeeSignatures.put(100L, createSignatures(2, 4));
        committeeSignatures.put(200L, createSignatures(4, 6));

        var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent);

        // Should be ordered by epoch
        assertThat(crown.getCommitteeEpochs()).containsExactly(100L, 200L, 300L);
    }

    @Test
    void shouldAggregatePerCommitteeCorrectly() {
        var aggregator = new CrownAggregator(provider);

        var signatures = createSignatures(0, 3);

        var committeeSignatures = Map.of(100L, signatures);

        var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent);

        // Verify we can retrieve committee contribution
        var contribution = crown.getContribution(100L);
        assertThat(contribution).isPresent();
        assertThat(contribution.get().signerCount()).isEqualTo(3);
    }

    @Test
    void shouldCreateContributionsWithCorrectSignerBitmaps() {
        var aggregator = new CrownAggregator(provider);

        var committee1Sigs = createSignatures(0, 4);  // 4 signers
        var committee2Sigs = createSignatures(4, 7);  // 3 signers

        var committeeSignatures = Map.of(
            100L, committee1Sigs,
            200L, committee2Sigs
        );

        var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent);

        var contrib1 = crown.getContribution(100L);
        assertThat(contrib1).isPresent();
        assertThat(contrib1.get().signerCount()).isEqualTo(4);

        var contrib2 = crown.getContribution(200L);
        assertThat(contrib2).isPresent();
        assertThat(contrib2.get().signerCount()).isEqualTo(3);
    }

    @Test
    void shouldRejectNullProvider() {
        assertThatThrownBy(() -> new CrownAggregator(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("provider");
    }

    @Test
    void shouldRejectNullCommitteeSignatures() {
        var aggregator = new CrownAggregator(provider);

        assertThatThrownBy(() -> aggregator.createMultiCommitteeAggregate(null, testEvent))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullEvent() {
        var aggregator = new CrownAggregator(provider);

        var committeeSignatures = Map.of(100L, createSignatures(0, 2));

        assertThatThrownBy(() -> aggregator.createMultiCommitteeAggregate(committeeSignatures, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectEmptyCommitteeSignatures() {
        var aggregator = new CrownAggregator(provider);

        assertThatThrownBy(() -> aggregator.createMultiCommitteeAggregate(Map.of(), testEvent))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("At least one committee");
    }

    @Test
    void shouldRejectCommitteeWithNoSignatures() {
        var aggregator = new CrownAggregator(provider);

        var committeeSignatures = Map.of(100L, List.<BLSSignature>of());

        assertThatThrownBy(() -> aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("No signatures");
    }

    @Test
    void shouldHandleLargeNumberOfCommittees() {
        var aggregator = new CrownAggregator(provider);

        var committeeSignatures = new HashMap<Long, List<BLSSignature>>();
        for (int i = 0; i < 10; i++) {
            var epoch = (i + 1) * 100L;
            committeeSignatures.put(epoch, List.of(createSignatures(i % keyPairs.size(), (i % keyPairs.size()) + 1).get(0)));
        }

        var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent);

        assertThat(crown.getCommitteeCount()).isEqualTo(10);
        assertThat(crown.totalSignerCount()).isEqualTo(10);
    }

    @Test
    void shouldProduceCompactStorage() {
        var aggregator = new CrownAggregator(provider);

        // 5 committees with 21 signers each = 105 total signers
        var committeeSignatures = new HashMap<Long, List<BLSSignature>>();
        for (int i = 0; i < 5; i++) {
            var epoch = (i + 1) * 100L;
            var sigs = new ArrayList<BLSSignature>();
            for (int j = 0; j < 21; j++) {
                var keyIndex = (i * 21 + j) % keyPairs.size();
                var sig = provider.sign(keyPairs.get(keyIndex).secretKey(), testMessage);
                sigs.add(new BLSSignature(sig));
            }
            committeeSignatures.put(epoch, sigs);
        }

        var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent);

        // Individual signatures: 105 * 96 = 10,080 bytes
        // Crown aggregate: ~176 bytes
        var individualSize = 105 * 96;
        var crownSize = crown.estimatedStorageBytes();

        var compressionRatio = 100.0 * (1 - (double) crownSize / individualSize);

        assertThat(compressionRatio).isGreaterThan(90.0);  // Target: 90%+ compression
        assertThat(crownSize).isLessThan(300);  // Should be < 300 bytes
    }

    // Helper method
    private List<BLSSignature> createSignatures(int startIndex, int endIndex) {
        var signatures = new ArrayList<BLSSignature>();
        for (int i = startIndex; i < endIndex; i++) {
            var sig = provider.sign(keyPairs.get(i).secretKey(), testMessage);
            signatures.add(new BLSSignature(sig));
        }
        return signatures;
    }
}
