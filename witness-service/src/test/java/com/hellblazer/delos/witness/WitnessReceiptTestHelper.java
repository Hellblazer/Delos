/*
 * Copyright (c) 2024, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.witness.proto.WitnessReceipt;
import com.hellblazer.delos.cryptography.Digest;
import com.google.protobuf.Timestamp;

import java.time.Instant;
import java.util.stream.IntStream;

/**
 * Helper for creating test witness receipts.
 */
public class WitnessReceiptTestHelper {

    private static final DigestAlgorithm ALGORITHM = DigestAlgorithm.DEFAULT;

    /**
     * Create a witness receipt with specified signature count.
     *
     * @param eventCoordinates Event being receipted
     * @param signatureCount Number of signatures to include
     * @return Receipt with specified signature count
     */
    public static WitnessReceipt createReceipt(EventCoordinates eventCoordinates, int signatureCount) {
        var builder = WitnessReceipt.newBuilder()
            .setEventCoordinates(eventCoordinates.toEventCoords())
            .setEventDigest(ALGORITHM.digest("content".getBytes()).toDigeste())
            .setEpoch(0)
            .setViewRef(ALGORITHM.digest("view".getBytes()).toDigeste());

        // Add signatures - Sig uses code/sequenceNumber fields
        IntStream.range(0, signatureCount).forEach(i -> {
            var sig = com.hellblazer.delos.cryptography.proto.Sig.newBuilder()
                .setCode(i)
                .setSequenceNumber((long) i)
                .build();
            builder.addSignatures(sig);
        });

        // Set timestamp
        var now = Instant.now();
        builder.setTimestamp(Timestamp.newBuilder()
            .setSeconds(now.getEpochSecond())
            .setNanos(now.getNano())
            .build());

        return builder.build();
    }

    /**
     * Create a receipt with invalid signature count.
     *
     * @param eventCoordinates Event being receipted
     * @param signatureCount Number of signatures (less than threshold)
     * @return Receipt with insufficient signatures
     */
    public static WitnessReceipt createInvalidReceipt(EventCoordinates eventCoordinates, int signatureCount) {
        return createReceipt(eventCoordinates, signatureCount);
    }
}
