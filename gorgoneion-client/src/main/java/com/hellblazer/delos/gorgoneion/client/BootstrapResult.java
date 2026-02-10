/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.gorgoneion.client;

import com.hellblazer.delos.gorgoneion.proto.Credentials;
import com.hellblazer.delos.gorgoneion.proto.Establishment;
import com.hellblazer.delos.gorgoneion.proto.SignedNonce;

/**
 * Result of a bootstrap operation tracking the phase of completion for safe retry strategies.
 *
 * @author hal.hildebrand
 */
public sealed interface BootstrapResult {

    /**
     * Bootstrap completed successfully with full registration.
     *
     * @param establishment The establishment credentials received from the server
     */
    record Success(Establishment establishment) implements BootstrapResult {}

    /**
     * Bootstrap partially completed - nonce was generated but registration failed.
     * Client can retry registration with the existing credentials without generating a new nonce.
     *
     * @param credentials The credentials with nonce that can be retried
     * @param cause The exception that caused the registration failure
     */
    record PartialSuccess(Credentials credentials, Throwable cause) implements BootstrapResult {}

    /**
     * Bootstrap failed completely - no nonce was generated, client must start over.
     *
     * @param cause The exception that caused the complete failure
     */
    record Failure(Throwable cause) implements BootstrapResult {}
}
