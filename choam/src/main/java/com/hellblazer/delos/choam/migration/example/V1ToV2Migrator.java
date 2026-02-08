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
import java.nio.ByteBuffer;

/**
 * Example migrator from CHOAM 0.0.6 to 0.0.7.
 * <p>
 * <b>Migration changes</b>:
 * - Adds nonce field to state (default: 0)
 * - Converts 32-bit block height to 64-bit
 * <p>
 * <b>State format</b>:
 * <pre>
 * V1 (0.0.6):
 *   [height: int32] [hash: bytes32] [data: bytes]
 *
 * V2 (0.0.7):
 *   [height: int64] [nonce: int64] [hash: bytes32] [data: bytes]
 * </pre>
 *
 * @author hal.hildebrand
 */
public class V1ToV2Migrator implements StateMigrator {

    @Override
    public String getSourceVersion() {
        return "0.0.6";
    }

    @Override
    public String getTargetVersion() {
        return "0.0.7";
    }

    @Override
    public void migrate(InputStream source, OutputStream target) throws MigrationException {
        try (var input = new DataInputStream(source);
             var output = new DataOutputStream(target)) {

            // Read V1 state
            var heightV1 = input.readInt();           // int32
            var hash = new byte[32];
            input.readFully(hash);                     // bytes32
            var data = input.readAllBytes();           // bytes

            // Write V2 state
            output.writeLong(heightV1);                // int32 → int64 (height)
            output.writeLong(0L);                      // int64 (nonce, default: 0)
            output.write(hash);                        // bytes32 (hash, unchanged)
            output.write(data);                        // bytes (data, unchanged)

        } catch (IOException e) {
            throw new MigrationException("V1→V2 migration failed", e);
        }
    }

    @Override
    public String getDescription() {
        return "Add nonce field and expand height to 64-bit";
    }
}
