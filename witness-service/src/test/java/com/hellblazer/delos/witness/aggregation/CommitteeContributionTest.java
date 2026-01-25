/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Test CommitteeContribution record.
 * Phase 1C-2-B: Multi-committee aggregate data structure.
 *
 * @author hal.hildebrand
 */
class CommitteeContributionTest {

    @Test
    void shouldCreateValidContribution() {
        var epoch = 12345L;
        var bitmap = new byte[]{0b00001111};
        var signerCount = 4;

        var contribution = new CommitteeContribution(epoch, bitmap, signerCount);

        assertThat(contribution.epoch()).isEqualTo(epoch);
        assertThat(contribution.signerBitmap()).isEqualTo(bitmap);
        assertThat(contribution.signerCount()).isEqualTo(signerCount);
    }

    @Test
    void shouldReturnDefensiveCopyOfBitmap() {
        var epoch = 123L;
        var originalBitmap = new byte[]{(byte) 0b11110000};
        var signerCount = 4;

        var contribution = new CommitteeContribution(epoch, originalBitmap, signerCount);

        // Mutate original
        originalBitmap[0] = 0;

        // Contribution should be unchanged
        assertThat(contribution.signerBitmap()).isEqualTo(new byte[]{(byte) 0b11110000});
    }

    @Test
    void shouldReturnDefensiveCopyOnAccess() {
        var contribution = new CommitteeContribution(123L, new byte[]{(byte) 0b11110000}, 4);

        var bitmap1 = contribution.signerBitmap();
        bitmap1[0] = 0;

        // Second access should return original value
        var bitmap2 = contribution.signerBitmap();
        assertThat(bitmap2).isEqualTo(new byte[]{(byte) 0b11110000});
    }

    @Test
    void shouldDecodeSignerIndicesCorrectly() {
        // Bitmap: bits 0, 2, 4, 7 set in first byte
        var bitmap = new byte[]{(byte) 0b10010101};
        var contribution = new CommitteeContribution(100L, bitmap, 4);

        var indices = contribution.getSignerIndices();

        assertThat(indices).containsExactly(0, 2, 4, 7);
    }

    @Test
    void shouldDecodeMultiByteSignerIndices() {
        // Bitmap: bits 0, 7 in first byte; bits 8, 15 in second byte
        var bitmap = new byte[]{(byte) 0b10000001, (byte) 0b10000001};
        var contribution = new CommitteeContribution(200L, bitmap, 4);

        var indices = contribution.getSignerIndices();

        assertThat(indices).containsExactly(0, 7, 8, 15);
    }

    @Test
    void shouldHandleFullByteSignerIndices() {
        // All 8 bits set
        var bitmap = new byte[]{(byte) 0xFF};
        var contribution = new CommitteeContribution(300L, bitmap, 8);

        var indices = contribution.getSignerIndices();

        assertThat(indices).containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
    }

    @Test
    void shouldRejectNullBitmap() {
        assertThatThrownBy(() -> new CommitteeContribution(123L, null, 4))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("signerBitmap");
    }

    @Test
    void shouldRejectEmptyBitmap() {
        assertThatThrownBy(() -> new CommitteeContribution(123L, new byte[]{}, 4))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least 1 byte");
    }

    @Test
    void shouldRejectZeroSignerCount() {
        assertThatThrownBy(() -> new CommitteeContribution(123L, new byte[]{0x01}, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");
    }

    @Test
    void shouldRejectNegativeSignerCount() {
        assertThatThrownBy(() -> new CommitteeContribution(123L, new byte[]{0x01}, -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");
    }

    @Test
    void shouldImplementEqualsCorrectly() {
        var c1 = new CommitteeContribution(123L, new byte[]{0x01, 0x02}, 3);
        var c2 = new CommitteeContribution(123L, new byte[]{0x01, 0x02}, 3);
        var c3 = new CommitteeContribution(456L, new byte[]{0x01, 0x02}, 3);
        var c4 = new CommitteeContribution(123L, new byte[]{0x01, 0x03}, 3);
        var c5 = new CommitteeContribution(123L, new byte[]{0x01, 0x02}, 4);

        assertThat(c1).isEqualTo(c2);
        assertThat(c1).isNotEqualTo(c3); // Different epoch
        assertThat(c1).isNotEqualTo(c4); // Different bitmap
        assertThat(c1).isNotEqualTo(c5); // Different count
        assertThat(c1).isNotEqualTo(null);
        assertThat(c1).isNotEqualTo("not a contribution");
    }

    @Test
    void shouldImplementHashCodeCorrectly() {
        var c1 = new CommitteeContribution(123L, new byte[]{0x01, 0x02}, 3);
        var c2 = new CommitteeContribution(123L, new byte[]{0x01, 0x02}, 3);

        assertThat(c1.hashCode()).isEqualTo(c2.hashCode());
    }

    @Test
    void shouldHandleLargeBitmaps() {
        // 50 signers = 7 bytes (ceil(50/8))
        var bitmap = new byte[7];
        bitmap[0] = (byte) 0xFF;  // 8 signers
        bitmap[6] = (byte) 0x03;  // 2 signers in last byte

        var contribution = new CommitteeContribution(999L, bitmap, 10);

        var indices = contribution.getSignerIndices();
        assertThat(indices).hasSize(10);
        assertThat(indices).contains(0, 1, 2, 3, 4, 5, 6, 7, 48, 49);
    }
}
