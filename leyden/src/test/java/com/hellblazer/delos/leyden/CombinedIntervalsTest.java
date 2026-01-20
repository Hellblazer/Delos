/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.leyden;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author hal.hildebrand
 * @since 220
 */
public class CombinedIntervalsTest {

    @Test
    public void smoke() {
        List<KeyInterval> intervals = new ArrayList<>();

        intervals.add(new KeyInterval(Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, (byte) 200 }),
                                      Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, (byte) 241 })));
        intervals.add(new KeyInterval(Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, 50 }),
                                      Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, 75 })));
        intervals.add(new KeyInterval(Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, 50 }),
                                      Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, 90 })));
        intervals.add(new KeyInterval(Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, 25 }),
                                      Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, 49 })));
        intervals.add(new KeyInterval(Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, 25 }),
                                      Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, 49 })));
        intervals.add(new KeyInterval(Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, (byte) 128 }),
                                      Digest.normalized(DigestAlgorithm.DEFAULT, new byte[] { 0, (byte) 175 })));
        CombinedIntervals combined = new CombinedIntervals(intervals);
        var compressed = combined.intervals().toList();
        System.out.println(compressed);
        assertEquals(4, compressed.size());
    }
}
