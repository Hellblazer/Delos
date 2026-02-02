/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.fireflies;

import com.hellblazer.delos.membership.byzantine.MemberRiskProfile;
import com.hellblazer.delos.membership.byzantine.ResponseHandler;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * ResponseHandler implementation that uses Fireflies shunning for Byzantine response.
 * <p>
 * When the ByzantineIntelligenceCoordinator detects a critical threat, this handler
 * shuns the member via the View's shunning mechanism, preventing further communication.
 * </p>
 * <p>
 * <b>Idempotence</b>:
 * This handler is idempotent. Shunning an already-shunned member returns false
 * (no action taken) rather than failing.
 * </p>
 * <p>
 * <b>Thread Safety</b>:
 * This implementation is thread-safe. The underlying View.shun() operation
 * handles concurrent access appropriately.
 * </p>
 *
 * @author hal.hildebrand
 * @see ResponseHandler
 * @see View#shun(com.hellblazer.delos.cryptography.Digest)
 */
public class FirefliesResponseHandler implements ResponseHandler {

    private static final Logger log = LoggerFactory.getLogger(FirefliesResponseHandler.class);

    private final View view;
    private final FireflyMetrics metrics;

    /**
     * Create a response handler for the given View.
     *
     * @param view    The Fireflies view to use for shunning
     * @param metrics Optional metrics for recording actions (may be null)
     */
    public FirefliesResponseHandler(View view, FireflyMetrics metrics) {
        this.view = Objects.requireNonNull(view, "view cannot be null");
        this.metrics = metrics;
    }

    /**
     * Create a response handler without metrics.
     *
     * @param view The Fireflies view to use for shunning
     */
    public FirefliesResponseHandler(View view) {
        this(view, null);
    }

    @Override
    public CompletableFuture<Boolean> handleCritical(Identifier memberId, MemberRiskProfile profile) {
        if (!(memberId instanceof SelfAddressingIdentifier sai)) {
            log.warn("Cannot shun member with non-SelfAddressingIdentifier: {}", memberId.getClass().getSimpleName());
            return CompletableFuture.completedFuture(false);
        }

        var digest = sai.getDigest();
        log.info("Handling CRITICAL Byzantine detection for {}: score={}, sources={}",
                 digest, String.format("%.3f", profile.getAggregatedScore()), profile.getActiveSignalSources());

        return view.shun(digest)
                   .thenApply(shunned -> {
                       if (shunned) {
                           log.info("Successfully shunned Byzantine member: {}", digest);
                           if (metrics != null) {
                               metrics.recordShunnedGossip();
                           }
                       } else {
                           log.debug("Member already shunned or not found: {}", digest);
                       }
                       return shunned;
                   })
                   .exceptionally(e -> {
                       log.error("Failed to shun member {}: {}", digest, e.getMessage(), e);
                       return false;
                   });
    }

    @Override
    public CompletableFuture<Void> handleWarning(Identifier memberId, MemberRiskProfile profile) {
        // For warnings, we just log - no action taken
        // This could be enhanced to increase monitoring or adjust gossip frequency
        if (memberId instanceof SelfAddressingIdentifier sai) {
            log.warn("WARNING Byzantine detection for {}: score={}, sources={}",
                     sai.getDigest(),
                     String.format("%.3f", profile.getAggregatedScore()),
                     profile.getActiveSignalSources());
        } else {
            log.warn("WARNING Byzantine detection for {}: score={}, sources={}",
                     memberId,
                     String.format("%.3f", profile.getAggregatedScore()),
                     profile.getActiveSignalSources());
        }
        return CompletableFuture.completedFuture(null);
    }
}
