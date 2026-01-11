/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.cryptography.batch;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Factory for creating batch verifiers.
 * Selects the appropriate verifier implementation based on availability:
 * 1. Native JNI (ed25519-donna) if available
 * 2. Pure Java batch (Bouncy Castle) as primary
 * 3. Sequential fallback (JDK EdDSA) as last resort
 *
 * This factory is thread-safe and caches the selected verifier.
 */
public class BatchVerifierFactory {
    private static final AtomicReference<BatchVerifier> CACHED_VERIFIER = new AtomicReference<>();

    private BatchVerifierFactory() {
        // Utility class
    }

    /**
     * Create or return cached batch verifier.
     * Selects the best available implementation.
     */
    public static BatchVerifier createVerifier() {
        var cached = CACHED_VERIFIER.get();
        if (cached != null) {
            return cached;
        }

        // Try to create in order of preference
        BatchVerifier verifier = null;

        // Phase 4: Try native verifier (when implemented)
        // verifier = tryCreateNativeVerifier();

        // Phase 2: Try Bouncy Castle batch verifier (primary)
        if (verifier == null) {
            verifier = tryCreateBouncyCastleVerifier();
        }

        // Fallback: Always available sequential verifier
        if (verifier == null) {
            verifier = new FallbackBatchVerifier();
        }

        // Cache and return
        CACHED_VERIFIER.compareAndSet(null, verifier);
        return CACHED_VERIFIER.get();
    }

    /**
     * Try to create Bouncy Castle batch verifier.
     * Returns null if BC not available or not yet implemented.
     */
    private static BatchVerifier tryCreateBouncyCastleVerifier() {
        try {
            // This will be implemented in Phase 2
            Class<?> bcVerifierClass = Class.forName(
                "com.hellblazer.delos.cryptography.batch.BouncyCastleBatchVerifier"
            );
            if (BatchVerifier.class.isAssignableFrom(bcVerifierClass)) {
                return (BatchVerifier) bcVerifierClass.getDeclaredConstructor().newInstance();
            }
        } catch (Exception e) {
            // BC verifier not available yet
        }
        return null;
    }

    /**
     * Get the minimum batch size for this verifier.
     */
    public static int minBatchSize() {
        return createVerifier().minBatchSize();
    }

    /**
     * Get the name of the active verifier implementation.
     * Useful for logging and diagnostics.
     */
    public static String getVerifierName() {
        var verifier = createVerifier();
        return verifier.getClass().getSimpleName();
    }
}
