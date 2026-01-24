/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.storage;

/**
 * Exception thrown when storage layer operations fail.
 * <p>
 * Unchecked exception used throughout the storage layer to signal failures such as:
 * <ul>
 *   <li>Database connection failures</li>
 *   <li>Disk I/O errors (disk full, permission denied)</li>
 *   <li>Network failures for distributed stores (CHOAM)</li>
 *   <li>Serialization/deserialization errors</li>
 *   <li>Operations on closed stores</li>
 *   <li>Transient failures requiring retry (deadlocks, timeouts)</li>
 * </ul>
 * <p>
 * <b>Design Rationale</b>:
 * <ul>
 *   <li>Extends RuntimeException for cleaner API (no forced catches)</li>
 *   <li>Supports exception chaining to preserve root cause</li>
 *   <li>Allows callers to distinguish storage failures from logic errors</li>
 * </ul>
 * <p>
 * <b>Usage Example</b>:
 * <pre>{@code
 * public class JDBCReceiptStore implements ReceiptStore<AggregateWitnessReceipt> {
 *     @Override
 *     public void store(String key, AggregateWitnessReceipt receipt) {
 *         try {
 *             // JDBC operations
 *             connection.execute(insertSQL);
 *         } catch (SQLException e) {
 *             throw new StorageException("Failed to store receipt: " + key, e);
 *         }
 *     }
 * }
 *
 * // Caller can handle storage failures
 * try {
 *     store.store(key, receipt);
 * } catch (StorageException e) {
 *     logger.error("Storage unavailable, queuing for retry", e);
 *     retryQueue.add(receipt);
 * }
 * }</pre>
 * <p>
 * <b>Exception Chaining</b>:
 * Always chain the original exception to preserve stack traces for debugging:
 * <pre>{@code
 * catch (IOException e) {
 *     throw new StorageException("Disk write failed", e); // Preserves 'e'
 * }
 * }</pre>
 *
 * @author hal.hildebrand
 */
public class StorageException extends RuntimeException {

    /**
     * Create a StorageException with a message.
     *
     * @param message Error message describing the failure
     */
    public StorageException(String message) {
        super(message);
    }

    /**
     * Create a StorageException with a message and root cause.
     * <p>
     * This is the preferred constructor for wrapping lower-level exceptions
     * (SQLException, IOException, etc.) to preserve stack traces.
     *
     * @param message Error message describing the failure
     * @param cause   Root cause exception
     */
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Create a StorageException wrapping a root cause.
     * <p>
     * Use this when the cause's message is sufficient and no additional
     * context is needed.
     *
     * @param cause Root cause exception
     */
    public StorageException(Throwable cause) {
        super(cause);
    }
}
