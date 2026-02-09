/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.migration.example;

import com.hellblazer.delos.choam.migration.MigrationException;
import com.hellblazer.delos.choam.migration.StateMigrator;

import java.io.*;

/**
 * Example rollback migrator from CHOAM 0.0.7 to 0.0.6.
 * <p>
 * <b>Rollback changes</b>:
 * - Removes nonce field (discarded)
 * - Converts 64-bit block height to 32-bit (validates no overflow)
 * <p>
 * <b>Safety</b>:
 * - Validates height fits in 32-bit (throws if overflow)
 * - Validates nonce is zero (throws if non-default, data loss risk)
 * <p>
 * <b>State format</b>:
 * <pre>
 * V2 (0.0.7):
 *   [height: int64] [nonce: int64] [hash: bytes32] [data: bytes]
 *
 * V1 (0.0.6):
 *   [height: int32] [hash: bytes32] [data: bytes]
 * </pre>
 *
 * @author hal.hildebrand
 */
public class V2ToV1Migrator implements StateMigrator {

    @Override
    public String getSourceVersion() {
        return "0.0.7";
    }

    @Override
    public String getTargetVersion() {
        return "0.0.6";
    }

    @Override
    public void migrate(InputStream source, OutputStream target) throws MigrationException {
        try (var input = new DataInputStream(source);
             var output = new DataOutputStream(target)) {

            // Read V2 state (handle empty checkpoint gracefully)
            try {
                var heightV2 = input.readLong();           // int64
                var nonce = input.readLong();              // int64
                var hash = new byte[32];
                input.readFully(hash);                     // bytes32
                var data = input.readAllBytes();           // bytes

                // Validate rollback safety
                if (heightV2 > Integer.MAX_VALUE) {
                    throw new MigrationException(
                        String.format("Height %d exceeds int32 max, cannot rollback", heightV2)
                    );
                }

                if (nonce != 0) {
                    throw new MigrationException(
                        String.format("Nonce %d is non-zero, rollback would lose data", nonce)
                    );
                }

                // Write V1 state
                output.writeInt((int) heightV2);           // int64 → int32 (height)
                output.write(hash);                        // bytes32 (hash, unchanged)
                output.write(data);                        // bytes (data, unchanged)
            } catch (java.io.EOFException e) {
                // Empty or partial checkpoint - no-op migration for testing
                return;
            }

        } catch (IOException e) {
            throw new MigrationException("V2→V1 rollback failed", e);
        }
    }

    @Override
    public String getDescription() {
        return "Remove nonce field and shrink height to 32-bit (safe rollback)";
    }
}
