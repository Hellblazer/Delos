/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.joou.ULong;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Test MultiCommitteeAggregate record.
 * Phase 1C-2-B: Multi-committee aggregate data structure.
 *
 * @author hal.hildebrand
 */
class MultiCommitteeAggregateTest {

    private static final EventCoordinates TEST_EVENT = createTestEvent();

    private static EventCoordinates createTestEvent() {
        var identifier = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("test".getBytes()));
        return new EventCoordinates(
            identifier,
            ULong.valueOf(1),
            DigestAlgorithm.DEFAULT.digest("event".getBytes()),
            "test_event"
        );
    }

    @Test
    void shouldCreateValidAggregate() {
        var signature = createTestSignature();
        var contribution1 = new CommitteeContribution(100L, new byte[]{0x01}, 1);
        var contribution2 = new CommitteeContribution(200L, new byte[]{0x02}, 1);
        var contributions = List.of(contribution1, contribution2);
        var committeeBitmap = new byte[]{0x03};
        var totalSigners = 2;

        var aggregate = new MultiCommitteeAggregate(
            signature, contributions, committeeBitmap, totalSigners, TEST_EVENT
        );

        assertThat(aggregate.aggregatedSignature()).isEqualTo(signature);
        assertThat(aggregate.contributions()).isEqualTo(contributions);
        assertThat(aggregate.committeeContributionBitmap()).isEqualTo(committeeBitmap);
        assertThat(aggregate.totalSignerCount()).isEqualTo(totalSigners);
        assertThat(aggregate.event()).isEqualTo(TEST_EVENT);
    }

    @Test
    void shouldReturnDefensiveCopyOfCommitteeBitmap() {
        var aggregate = createTestAggregate(2);

        var original = new byte[]{0x03};
        var bitmap1 = aggregate.committeeContributionBitmap();
        bitmap1[0] = 0;

        var bitmap2 = aggregate.committeeContributionBitmap();
        assertThat(bitmap2).isEqualTo(original);
    }

    @Test
    void shouldReturnImmutableContributionsList() {
        var aggregate = createTestAggregate(2);

        assertThatThrownBy(() -> aggregate.contributions().add(
            new CommitteeContribution(300L, new byte[]{0x01}, 1)
        )).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldGetCommitteeCount() {
        var aggregate = createTestAggregate(3);

        assertThat(aggregate.getCommitteeCount()).isEqualTo(3);
    }

    @Test
    void shouldGetCommitteeEpochs() {
        var aggregate = createTestAggregate(3);

        assertThat(aggregate.getCommitteeEpochs()).containsExactly(100L, 200L, 300L);
    }

    @Test
    void shouldGetContributionByEpoch() {
        var aggregate = createTestAggregate(3);

        var contribution = aggregate.getContribution(200L);

        assertThat(contribution).isPresent();
        assertThat(contribution.get().epoch()).isEqualTo(200L);
    }

    @Test
    void shouldReturnEmptyForUnknownEpoch() {
        var aggregate = createTestAggregate(2);

        var contribution = aggregate.getContribution(999L);

        assertThat(contribution).isEmpty();
    }

    @Test
    void shouldCheckIfCommitteeContributed() {
        var aggregate = createTestAggregate(3);

        assertThat(aggregate.hasCommittee(100L)).isTrue();
        assertThat(aggregate.hasCommittee(200L)).isTrue();
        assertThat(aggregate.hasCommittee(300L)).isTrue();
        assertThat(aggregate.hasCommittee(999L)).isFalse();
    }

    @Test
    void shouldCalculateStorageSize() {
        var aggregate = createTestAggregate(3);

        // Signature: 96 bytes
        // Committee bitmap: 1 byte
        // Total signer count: 4 bytes
        // Per contribution: epoch(8) + bitmap(1) + count(4) = 13 bytes * 3 = 39 bytes
        // Total: 96 + 1 + 4 + 39 = 140 bytes

        assertThat(aggregate.estimatedStorageBytes()).isEqualTo(140);
    }

    @Test
    void shouldRejectNullSignature() {
        assertThatThrownBy(() -> new MultiCommitteeAggregate(
            null,
            List.of(new CommitteeContribution(100L, new byte[]{0x01}, 1)),
            new byte[]{0x01},
            1,
            TEST_EVENT
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("aggregatedSignature");
    }

    @Test
    void shouldRejectNullContributions() {
        assertThatThrownBy(() -> new MultiCommitteeAggregate(
            createTestSignature(),
            null,
            new byte[]{0x01},
            1,
            TEST_EVENT
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("contributions");
    }

    @Test
    void shouldRejectEmptyContributions() {
        assertThatThrownBy(() -> new MultiCommitteeAggregate(
            createTestSignature(),
            List.of(),
            new byte[]{0x01},
            1,
            TEST_EVENT
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("At least one committee");
    }

    @Test
    void shouldRejectNullCommitteeBitmap() {
        assertThatThrownBy(() -> new MultiCommitteeAggregate(
            createTestSignature(),
            List.of(new CommitteeContribution(100L, new byte[]{0x01}, 1)),
            null,
            1,
            TEST_EVENT
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("committeeContributionBitmap");
    }

    @Test
    void shouldRejectEmptyCommitteeBitmap() {
        assertThatThrownBy(() -> new MultiCommitteeAggregate(
            createTestSignature(),
            List.of(new CommitteeContribution(100L, new byte[]{0x01}, 1)),
            new byte[]{},
            1,
            TEST_EVENT
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least 1 byte");
    }

    @Test
    void shouldRejectZeroTotalSignerCount() {
        assertThatThrownBy(() -> new MultiCommitteeAggregate(
            createTestSignature(),
            List.of(new CommitteeContribution(100L, new byte[]{0x01}, 1)),
            new byte[]{0x01},
            0,
            TEST_EVENT
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");
    }

    @Test
    void shouldRejectMismatchedTotalSignerCount() {
        // Contribution says 5 signers, but totalSignerCount is 10
        assertThatThrownBy(() -> new MultiCommitteeAggregate(
            createTestSignature(),
            List.of(new CommitteeContribution(100L, new byte[]{0x01}, 5)),
            new byte[]{0x01},
            10,
            TEST_EVENT
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not match sum");
    }

    @Test
    void shouldRejectUnorderedContributions() {
        var contribution1 = new CommitteeContribution(300L, new byte[]{0x01}, 1);
        var contribution2 = new CommitteeContribution(100L, new byte[]{0x02}, 1);

        assertThatThrownBy(() -> new MultiCommitteeAggregate(
            createTestSignature(),
            List.of(contribution1, contribution2),
            new byte[]{0x03},
            2,
            TEST_EVENT
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ordered by epoch");
    }

    @Test
    void shouldRejectDuplicateEpochs() {
        var contribution1 = new CommitteeContribution(100L, new byte[]{0x01}, 1);
        var contribution2 = new CommitteeContribution(100L, new byte[]{0x02}, 1);

        assertThatThrownBy(() -> new MultiCommitteeAggregate(
            createTestSignature(),
            List.of(contribution1, contribution2),
            new byte[]{0x03},
            2,
            TEST_EVENT
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ordered by epoch");
    }

    @Test
    void shouldRejectNullEvent() {
        assertThatThrownBy(() -> new MultiCommitteeAggregate(
            createTestSignature(),
            List.of(new CommitteeContribution(100L, new byte[]{0x01}, 1)),
            new byte[]{0x01},
            1,
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("event");
    }

    @Test
    void shouldImplementEqualsCorrectly() {
        var aggregate1 = createTestAggregate(2);
        var aggregate2 = createTestAggregate(2);

        assertThat(aggregate1).isEqualTo(aggregate2);
        assertThat(aggregate1).isNotEqualTo(null);
        assertThat(aggregate1).isNotEqualTo("not an aggregate");
    }

    @Test
    void shouldImplementHashCodeCorrectly() {
        var aggregate1 = createTestAggregate(2);
        var aggregate2 = createTestAggregate(2);

        assertThat(aggregate1.hashCode()).isEqualTo(aggregate2.hashCode());
    }

    @Test
    void shouldHaveInformativeToString() {
        var aggregate = createTestAggregate(3);

        var toString = aggregate.toString();

        assertThat(toString).contains("3 committees");
        assertThat(toString).contains("3 signers");
        assertThat(toString).contains("bytes");
    }

    // Helper methods

    private static BLSSignature createTestSignature() {
        var bytes = new byte[96];
        bytes[0] = 1;  // Non-zero to make it valid
        return new BLSSignature(bytes);
    }

    private static MultiCommitteeAggregate createTestAggregate(int committeeCount) {
        var signature = createTestSignature();
        var contributions = new java.util.ArrayList<CommitteeContribution>();

        for (int i = 0; i < committeeCount; i++) {
            var epoch = (i + 1) * 100L;
            var bitmap = new byte[]{(byte) (0x01 << i)};
            contributions.add(new CommitteeContribution(epoch, bitmap, 1));
        }

        var committeeBitmap = new byte[]{(byte) ((1 << committeeCount) - 1)};
        var totalSigners = committeeCount;

        return new MultiCommitteeAggregate(
            signature, contributions, committeeBitmap, totalSigners, TEST_EVENT
        );
    }
}
