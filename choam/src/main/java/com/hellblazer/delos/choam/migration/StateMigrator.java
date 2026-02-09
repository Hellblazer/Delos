/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.migration;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Interface for state schema migrations across CHOAM versions.
 * <p>
 * State migrations enable zero-downtime upgrades by providing backward
 * and forward compatibility between versions. Migrations are applied
 * when loading checkpoints from previous versions or creating checkpoints
 * compatible with previous versions.
 * <p>
 * <b>Migration Contract</b>:
 * - <b>Idempotent</b>: Applying migration multiple times produces same result
 * - <b>Deterministic</b>: All nodes produce identical migrated state
 * - <b>Backward compatible</b>: Version N+1 can read Version N snapshots
 * - <b>Rollback safe</b>: Version N can read Version N+1 snapshots (within compatibility window)
 * <p>
 * <b>Example Usage</b>:
 * <pre>
 * // Version 1 to Version 2 migration (adds "nonce" field)
 * public class V1ToV2Migrator implements StateMigrator {
 *     {@literal @}Override
 *     public String getSourceVersion() { return "0.0.6"; }
 *
 *     {@literal @}Override
 *     public String getTargetVersion() { return "0.0.7"; }
 *
 *     {@literal @}Override
 *     public void migrate(InputStream source, OutputStream target) throws MigrationException {
 *         var stateV1 = deserialize(source, StateV1.class);
 *         var stateV2 = new StateV2(
 *             stateV1.getHeight(),
 *             stateV1.getHash(),
 *             0L  // Default nonce for migrated states
 *         );
 *         serialize(stateV2, target);
 *     }
 * }
 * </pre>
 * <p>
 * <b>Compatibility Matrix</b>:
 * <ul>
 *   <li>Version N-1: Compatible with N (forward), incompatible with N+1</li>
 *   <li>Version N: Compatible with N-1 (backward) and N+1 (forward)</li>
 *   <li>Version N+1: Compatible with N (backward), incompatible with N-1</li>
 * </ul>
 *
 * @author hal.hildebrand
 * @see MigrationException
 * @see MigrationRegistry
 */
public interface StateMigrator {

    /**
     * Get the source version this migrator upgrades from.
     *
     * @return version string (e.g., "0.0.6")
     */
    String getSourceVersion();

    /**
     * Get the target version this migrator upgrades to.
     *
     * @return version string (e.g., "0.0.7")
     */
    String getTargetVersion();

    /**
     * Migrate state from source version to target version.
     * <p>
     * <b>Requirements</b>:
     * - <b>Idempotent</b>: Applying twice produces same result
     * - <b>Deterministic</b>: All nodes produce identical output
     * - <b>Non-destructive</b>: Source stream is read-only
     * - <b>Validated</b>: Throw MigrationException on validation failure
     *
     * @param source checkpoint stream in source version format
     * @param target checkpoint stream in target version format (output)
     * @throws MigrationException if migration fails
     */
    void migrate(InputStream source, OutputStream target) throws MigrationException;

    /**
     * Validate that a checkpoint can be migrated without applying the migration.
     * <p>
     * Useful for pre-flight checks before upgrade. Default implementation
     * attempts migration to a null output stream.
     * <p>
     * <b>Important</b>: This method consumes the source stream. Caller is responsible
     * for closing the source stream and should use mark/reset if stream reuse is needed.
     *
     * @param source checkpoint stream to validate (caller must close)
     * @return true if migration would succeed, false otherwise
     */
    default boolean canMigrate(InputStream source) {
        try (var nullOutput = OutputStream.nullOutputStream()) {
            migrate(source, nullOutput);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check if this migrator supports the given version transition.
     *
     * @param sourceVersion version to upgrade from
     * @param targetVersion version to upgrade to
     * @return true if this migrator supports the transition
     */
    default boolean supports(String sourceVersion, String targetVersion) {
        return getSourceVersion().equals(sourceVersion) &&
               getTargetVersion().equals(targetVersion);
    }

    /**
     * Get a human-readable description of this migration.
     * <p>
     * Used for logging and audit trails during upgrade.
     *
     * @return description (e.g., "Add nonce field to state")
     */
    default String getDescription() {
        return String.format("Migrate from %s to %s", getSourceVersion(), getTargetVersion());
    }
}
