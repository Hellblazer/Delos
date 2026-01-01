/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.processing;

import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.stereotomy.KeyState;
import com.hellblazer.delos.stereotomy.event.KeyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * @author hal.hildebrand
 */
public interface KeyEventVerifier {
    Logger log = LoggerFactory.getLogger(KeyEventVerifier.class);

    default Map<Integer, JohnHancock> verifyEndorsements(KeyState state, KeyEvent event,
                                                         Map<Integer, JohnHancock> receipts) {
        var validReceipts = new HashMap<Integer, JohnHancock>();
        var witnessCount = state.getWitnesses().size();

        for (var entry : receipts.entrySet()) {
            var witnessIndex = entry.getKey();

            // Validate witness index bounds
            if (witnessIndex < 0 || witnessIndex >= witnessCount) {
                throw new InvalidWitnessReceiptException(event, witnessIndex, witnessCount);
            }

            var publicKey = state.getWitnesses().get(witnessIndex).getPublicKey();

            var ops = SignatureAlgorithm.lookup(publicKey);
            if (ops.verify(publicKey, entry.getValue(), event.getBytes())) {
                validReceipts.put(witnessIndex, entry.getValue());
            }
        }

        if (validReceipts.size() < state.getWitnessThreshold()) {
            throw new UnmetWitnessThresholdException(event);
        }

        return validReceipts;
    }
}
