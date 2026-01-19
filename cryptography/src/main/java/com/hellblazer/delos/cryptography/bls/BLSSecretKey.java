/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.cryptography.bls;

import java.util.Arrays;
import java.util.Objects;

/**
 * BLS-12-381 secret key (scalar, 32 bytes).
 * Implements AutoCloseable to clear sensitive data from memory.
 * <p>
 * Security critical: This class zeroes memory on close() to prevent key leakage.
 * Always use try-with-resources or explicitly call close() when done.
 *
 * @author hal.hildebrand
 */
public class BLSSecretKey implements AutoCloseable {
    /**
     * Size of a BLS secret key scalar in bytes
     */
    public static final int SCALAR_SIZE = 32;

    private final byte[] scalar;
    private volatile boolean closed = false;
    private final BLSProvider provider;

    /**
     * Construct a BLS secret key from scalar bytes.
     *
     * @param scalar   The secret key scalar (32 bytes)
     * @param provider BLS provider for cryptographic operations
     * @throws NullPointerException     if scalar or provider is null
     * @throws IllegalArgumentException if scalar is not 32 bytes
     */
    public BLSSecretKey(byte[] scalar, BLSProvider provider) {
        Objects.requireNonNull(scalar, "scalar cannot be null");
        Objects.requireNonNull(provider, "provider cannot be null");
        if (scalar.length != SCALAR_SIZE) {
            throw new IllegalArgumentException(
                "Secret key must be " + SCALAR_SIZE + " bytes, got " + scalar.length);
        }
        // Defensive copy
        this.scalar = scalar.clone();
        this.provider = provider;
    }

    /**
     * Get the scalar bytes (defensive copy).
     * <p>
     * WARNING: The returned array contains sensitive cryptographic material.
     * Clear it after use.
     *
     * @return A copy of the scalar bytes
     * @throws IllegalStateException if the key has been closed
     */
    public byte[] getScalar() {
        checkNotClosed();
        return scalar.clone();
    }

    /**
     * Sign a message with this secret key.
     *
     * @param message The message to sign
     * @return BLS signature
     * @throws IllegalStateException if the key has been closed
     */
    public BLSSignature sign(byte[] message) {
        checkNotClosed();
        var signatureBytes = provider.sign(scalar, message);
        return new BLSSignature(signatureBytes);
    }

    /**
     * Close this secret key and zero its memory.
     * After calling this method, all operations will throw IllegalStateException.
     * <p>
     * This method is idempotent - calling it multiple times has no additional effect.
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            // Zero out sensitive data
            Arrays.fill(scalar, (byte) 0);
        }
    }

    /**
     * Check if this key has been closed.
     *
     * @throws IllegalStateException if the key has been closed
     */
    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("Key has been closed");
        }
    }

    @Override
    public String toString() {
        return closed ? "BLSSecretKey[CLOSED]" : "BLSSecretKey[32 bytes]";
    }
}
