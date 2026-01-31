/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.gossip;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.stereotomy.EventCoordinates;

import java.time.Instant;
import java.util.Objects;

/**
 * Gossipable witness receipt for Fireflies propagation.
 * <p>
 * Lightweight receipt representation for gossiping witness attestations across
 * the Fireflies overlay. Contains minimal data needed for receipt validation
 * and propagation.
 * <p>
 * Design:
 * - Immutable record for thread-safety
 * - Self-contained (no external dependencies for validation)
 * - Serializable to Fireflies WitnessReceipt proto
 * <p>
 * Usage:
 * <pre>{@code
 * var receipt = new GossipableReceipt(
 *     eventCoords,
 *     witnessId,
 *     witnessSignature,
 *     Instant.now(),
 *     ringPosition
 * );
 *
 * // Convert to proto for gossip
 * var proto = ReceiptGossipCodec.toProto(receipt, signerKey);
 * }</pre>
 *
 * @param eventCoordinates KERI event coordinates being witnessed
 * @param witnessId        Digest identifying the witness (member ID)
 * @param witnessSignature Witness signature over the event
 * @param timestamp        When the receipt was created
 * @param ringPosition     Fireflies ring position of the witness
 * @author hal.hildebrand
 * @since Phase 1A (Fireflies-KERI Integration)
 */
public record GossipableReceipt(
    EventCoordinates eventCoordinates,
    Digest witnessId,
    JohnHancock witnessSignature,
    Instant timestamp,
    int ringPosition
) {

    /**
     * Compact constructor with validation.
     */
    public GossipableReceipt {
        Objects.requireNonNull(eventCoordinates, "eventCoordinates required");
        Objects.requireNonNull(witnessId, "witnessId required");
        Objects.requireNonNull(witnessSignature, "witnessSignature required");
        Objects.requireNonNull(timestamp, "timestamp required");

        if (ringPosition < 0) {
            throw new IllegalArgumentException("ringPosition must be >= 0, got: " + ringPosition);
        }
    }

    /**
     * Create a receipt for gossiping.
     * Convenience factory method.
     *
     * @param eventCoordinates KERI event coordinates
     * @param witnessId        Witness identifier
     * @param witnessSignature Witness signature
     * @param ringPosition     Fireflies ring position
     * @return New gossipable receipt with current timestamp
     */
    public static GossipableReceipt create(
        EventCoordinates eventCoordinates,
        Digest witnessId,
        JohnHancock witnessSignature,
        int ringPosition
    ) {
        return new GossipableReceipt(
            eventCoordinates,
            witnessId,
            witnessSignature,
            Instant.now(),
            ringPosition
        );
    }
}
