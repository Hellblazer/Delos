/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.migration;

import com.hellblazer.delos.cryptography.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Migration tool for transitioning from in-memory to persistent nonce storage.
 * Handles safe migration with rollback capability and in-flight transaction support.
 *
 * Usage:
 * <pre>
 * var migration = new NonceMigrationTool(oldStore, newStore);
 * migration.setGracePeriod(Duration.ofSeconds(30));
 *
 * // Execute migration
 * MigrationResult result = migration.migrate();
 *
 * // If problems, rollback
 * if (!result.success()) {
 *     migration.rollback();
 * }
 * </pre>
 *
 * @author hal.hildebrand
 */
public class NonceMigrationTool {
    private static final Logger log = LoggerFactory.getLogger(NonceMigrationTool.class);

    private final NonceStore                oldStore;
    private final NonceStore                newStore;
    private final AtomicBoolean             migrated = new AtomicBoolean(false);
    private       Duration                  gracePeriod = Duration.ofSeconds(30);
    private       ScheduledExecutorService  scheduler;
    private       Map<Digest, Integer>      snapshot;
    private       Instant                   migrationTime;

    public NonceMigrationTool(NonceStore oldStore, NonceStore newStore) {
        this.oldStore = oldStore;
        this.newStore = newStore;
        this.scheduler = Executors.newScheduledThreadPool(1);
    }

    /**
     * Set grace period for in-flight transactions
     */
    public void setGracePeriod(Duration gracePeriod) {
        this.gracePeriod = gracePeriod;
    }

    /**
     * Execute migration from old to new nonce store
     */
    public MigrationResult migrate() {
        if (migrated.get()) {
            return new MigrationResult(false, "Migration already executed", null);
        }

        try {
            log.info("Starting nonce migration with grace period: {}", gracePeriod);
            migrationTime = Instant.now();

            // Phase 1: Snapshot current state
            log.info("Phase 1: Capturing nonce snapshot");
            snapshot = oldStore.snapshot();
            log.info("Captured {} nonce entries", snapshot.size());

            // Phase 2: Initialize new store
            log.info("Phase 2: Initializing new persistent store");
            newStore.initialize(snapshot);

            // Phase 3: Grace period for in-flight transactions
            log.info("Phase 3: Grace period for in-flight transactions");
            CountDownLatch graceLatch = new CountDownLatch(1);
            scheduler.schedule(() -> graceLatch.countDown(), gracePeriod.toMillis(), TimeUnit.MILLISECONDS);

            if (!graceLatch.await(gracePeriod.toMillis() + 1000, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Grace period timeout");
            }

            // Phase 4: Final sync (catch any nonces incremented during grace period)
            log.info("Phase 4: Final synchronization");
            var delta = oldStore.delta(snapshot);
            if (!delta.isEmpty()) {
                log.info("Syncing {} additional nonces from grace period", delta.size());
                newStore.update(delta);
            }

            // Phase 5: Validation
            log.info("Phase 5: Validating migration");
            var validation = validateMigration();
            if (!validation.valid()) {
                throw new IllegalStateException("Migration validation failed: " + validation.message());
            }

            migrated.set(true);
            log.info("Nonce migration completed successfully");
            return new MigrationResult(true, "Migration successful", snapshot);

        } catch (Exception e) {
            log.error("Migration failed", e);
            return new MigrationResult(false, "Migration failed: " + e.getMessage(), snapshot);
        }
    }

    /**
     * Rollback to old nonce store
     */
    public RollbackResult rollback() {
        if (!migrated.get()) {
            return new RollbackResult(false, "No migration to rollback");
        }

        try {
            log.warn("Rolling back nonce migration");

            // Restore from snapshot
            if (snapshot != null) {
                oldStore.restore(snapshot);
                log.info("Restored {} nonce entries from snapshot", snapshot.size());
            }

            // Clear new store
            newStore.clear();
            log.info("Cleared new persistent store");

            migrated.set(false);
            log.info("Rollback completed successfully");
            return new RollbackResult(true, "Rollback successful");

        } catch (Exception e) {
            log.error("Rollback failed", e);
            return new RollbackResult(false, "Rollback failed: " + e.getMessage());
        }
    }

    /**
     * Validate that migration was successful
     */
    private ValidationResult validateMigration() {
        try {
            // Check new store contains all old entries
            var oldEntries = oldStore.snapshot();
            var newEntries = newStore.snapshot();

            if (newEntries.size() < oldEntries.size()) {
                return new ValidationResult(false,
                    String.format("New store has fewer entries (%d) than old (%d)",
                                  newEntries.size(), oldEntries.size()));
            }

            // Check nonce values are preserved or advanced
            for (var entry : oldEntries.entrySet()) {
                var digest = entry.getKey();
                var oldNonce = entry.getValue();
                var newNonce = newEntries.get(digest);

                if (newNonce == null) {
                    return new ValidationResult(false,
                        String.format("Missing nonce for %s in new store", digest));
                }

                if (newNonce < oldNonce) {
                    return new ValidationResult(false,
                        String.format("Nonce regression for %s: old=%d, new=%d",
                                      digest, oldNonce, newNonce));
                }
            }

            return new ValidationResult(true, "Validation successful");

        } catch (Exception e) {
            return new ValidationResult(false, "Validation error: " + e.getMessage());
        }
    }

    /**
     * Shutdown migration tool resources
     */
    public void shutdown() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Result of migration operation
     */
    public record MigrationResult(boolean success, String message, Map<Digest, Integer> snapshot) {}

    /**
     * Result of rollback operation
     */
    public record RollbackResult(boolean success, String message) {}

    /**
     * Result of validation check
     */
    private record ValidationResult(boolean valid, String message) {}

    /**
     * Interface for nonce storage (implemented by both old and new stores)
     */
    public interface NonceStore {
        /**
         * Capture current state snapshot
         */
        Map<Digest, Integer> snapshot();

        /**
         * Get nonces that changed since snapshot
         */
        Map<Digest, Integer> delta(Map<Digest, Integer> baseline);

        /**
         * Initialize store with snapshot data
         */
        void initialize(Map<Digest, Integer> data);

        /**
         * Update store with delta data
         */
        void update(Map<Digest, Integer> data);

        /**
         * Restore store from snapshot
         */
        void restore(Map<Digest, Integer> data);

        /**
         * Clear all data
         */
        void clear();
    }
}
