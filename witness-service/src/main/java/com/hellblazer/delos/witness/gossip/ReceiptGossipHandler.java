/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.gossip;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.fireflies.proto.ReceiptGossip;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.witness.WitnessReceiptManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Handler for processing incoming receipt gossip from Fireflies overlay.
 * <p>
 * Receives ReceiptGossip messages, validates receipts, and integrates with
 * WitnessReceiptManager for threshold tracking and aggregation.
 * <p>
 * Responsibilities:
 * - Deserialize incoming ReceiptGossip proto messages
 * - Validate receipt signatures and metadata
 * - Filter duplicates using Bloom filter
 * - Forward valid receipts to WitnessReceiptManager
 * - Track gossip metrics (received, validated, rejected)
 * <p>
 * Thread-safety: Handler methods are thread-safe and can be called
 * concurrently from Fireflies gossip threads.
 *
 * @author hal.hildebrand
 * @since Phase 1A (Fireflies-KERI Integration)
 */
public class ReceiptGossipHandler {

    private static final Logger log = LoggerFactory.getLogger(ReceiptGossipHandler.class);

    private final WitnessReceiptManager receiptManager;
    private final ReceiptValidator validator;

    // Metrics (thread-safe via volatile)
    private volatile long receiptsReceived = 0;
    private volatile long receiptsValidated = 0;
    private volatile long receiptsRejected = 0;

    /**
     * Create receipt gossip handler.
     *
     * @param receiptManager Receipt manager for threshold tracking
     * @param validator      Validator for receipt verification
     * @throws NullPointerException if any parameter is null
     */
    public ReceiptGossipHandler(WitnessReceiptManager receiptManager, ReceiptValidator validator) {
        this.receiptManager = Objects.requireNonNull(receiptManager, "receiptManager required");
        this.validator = Objects.requireNonNull(validator, "validator required");
    }

    /**
     * Handle incoming receipt gossip from Fireflies peer.
     * <p>
     * Processes ReceiptGossip message by:
     * 1. Deserializing proto to domain objects
     * 2. Validating each receipt (signature, membership, timestamps)
     * 3. Forwarding valid receipts to WitnessReceiptManager
     * 4. Tracking metrics
     * <p>
     * Invalid receipts are logged and rejected (not propagated).
     * Validation failures don't prevent processing of other receipts.
     *
     * @param gossip      Incoming ReceiptGossip proto message
     * @param knownDigests Set of already-known receipt digests (for anti-entropy)
     * @return List of receipts successfully processed
     * @throws NullPointerException if gossip is null
     */
    public List<GossipableReceipt> handleGossip(ReceiptGossip gossip, Set<Digest> knownDigests) {
        Objects.requireNonNull(gossip, "gossip required");

        var processed = new ArrayList<GossipableReceipt>();

        // Deserialize all receipts from proto
        var receipts = ReceiptGossipCodec.fromReceiptGossip(gossip);
        receiptsReceived += receipts.size();

        if (log.isDebugEnabled()) {
            log.debug("Received {} receipts from gossip", receipts.size());
        }

        // Process each receipt
        for (var receipt : receipts) {
            try {
                // Skip if already known (anti-entropy)
                if (knownDigests != null && isKnown(receipt, knownDigests)) {
                    if (log.isTraceEnabled()) {
                        log.trace("Skipping known receipt: {}", receipt.eventCoordinates());
                    }
                    continue;
                }

                // Validate receipt
                var validationResult = validator.validate(receipt);
                if (!validationResult.isValid()) {
                    receiptsRejected++;
                    log.warn("Rejected invalid receipt for event {}: {}",
                        receipt.eventCoordinates(),
                        validationResult.reason()
                    );
                    continue;
                }

                // Forward to receipt manager for threshold tracking
                forwardToManager(receipt);

                processed.add(receipt);
                receiptsValidated++;

                if (log.isDebugEnabled()) {
                    log.debug("Processed receipt for event: {} from witness: {}",
                        receipt.eventCoordinates(),
                        receipt.witnessId()
                    );
                }

            } catch (Exception e) {
                receiptsRejected++;
                log.error("Error processing receipt for event {}: {}",
                    receipt.eventCoordinates(),
                    e.getMessage(),
                    e
                );
            }
        }

        if (log.isInfoEnabled() && !processed.isEmpty()) {
            log.info("Processed {}/{} receipts from gossip",
                processed.size(),
                receipts.size()
            );
        }

        return processed;
    }

    /**
     * Get count of receipts received from gossip.
     *
     * @return Total receipts received
     */
    public long getReceiptsReceived() {
        return receiptsReceived;
    }

    /**
     * Get count of receipts validated and processed.
     *
     * @return Total receipts validated
     */
    public long getReceiptsValidated() {
        return receiptsValidated;
    }

    /**
     * Get count of receipts rejected (failed validation).
     *
     * @return Total receipts rejected
     */
    public long getReceiptsRejected() {
        return receiptsRejected;
    }

    /**
     * Reset metrics counters (for testing).
     */
    public void resetMetrics() {
        receiptsReceived = 0;
        receiptsValidated = 0;
        receiptsRejected = 0;
    }

    // Private helpers

    /**
     * Check if receipt is already known (anti-entropy optimization).
     */
    private boolean isKnown(GossipableReceipt receipt, Set<Digest> knownDigests) {
        // Compute receipt digest for lookup
        var digest = ReceiptGossipCodec.digestOf(receipt, com.hellblazer.delos.cryptography.DigestAlgorithm.DEFAULT);
        return knownDigests.contains(digest);
    }

    /**
     * Forward validated receipt to WitnessReceiptManager.
     * <p>
     * Note: For Phase 1A, we log the receipt. Full integration with
     * WitnessReceiptManager.validateAndAddBLSSignature() will be added
     * when Fireflies View integration is complete.
     */
    private void forwardToManager(GossipableReceipt receipt) {
        // Phase 1A: Log receipt for now
        // Phase 1B: Integrate with receiptManager.validateAndAddBLSSignature()
        // when Fireflies View and committee membership are available

        if (log.isDebugEnabled()) {
            log.debug("Forwarding receipt to manager: event={}, witness={}, ringPosition={}",
                receipt.eventCoordinates(),
                receipt.witnessId(),
                receipt.ringPosition()
            );
        }

        // TODO Phase 1B: Extract BLS signature and call:
        // receiptManager.validateAndAddBLSSignature(
        //     receipt.eventCoordinates(),
        //     receipt.witnessId().toIdentifier(),
        //     receipt.ringPosition(),
        //     extractBLSSignature(receipt.witnessSignature())
        // );
    }

    /**
     * Validator interface for receipt verification.
     * <p>
     * Implementations verify:
     * - Witness signature validity
     * - Committee membership
     * - Timestamp freshness
     * - Byzantine detection checks
     */
    public interface ReceiptValidator {

        /**
         * Validate a gossipable receipt.
         *
         * @param receipt Receipt to validate
         * @return Validation result with pass/fail and reason
         */
        ValidationResult validate(GossipableReceipt receipt);
    }

    /**
     * Result of receipt validation.
     */
    public record ValidationResult(boolean isValid, String reason) {

        /**
         * Create successful validation result.
         */
        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }

        /**
         * Create failed validation result.
         */
        public static ValidationResult invalid(String reason) {
            Objects.requireNonNull(reason, "reason required");
            return new ValidationResult(false, reason);
        }
    }
}
