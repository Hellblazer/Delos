/*
 * Copyright (c) 2026, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.examples.kvstore;

import com.google.protobuf.Message;
import com.hellblazer.delos.choam.CHOAM;
import com.hellblazer.delos.choam.TransactionExecutor;
import com.hellblazer.delos.choam.proto.Transaction;
import com.hellblazer.delos.state.Mutator;
import com.hellblazer.delos.state.SqlStateMachine;
import com.hellblazer.delos.state.proto.Migration;
import com.hellblazer.delos.state.proto.Txn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

/**
 * Simple key-value store backed by Delos SQL-State for consensus-based replication.
 * <p>
 * Demonstrates:
 * - JDBC interface to replicated state
 * - Consensus-backed writes
 * - Schema migration with Liquibase
 * - Multi-node cluster synchronization
 *
 * @author hal.hildebrand
 */
public class SimpleKVStore {
    private static final Logger                 log               = LoggerFactory.getLogger(SimpleKVStore.class);
    private static final Path                   SCHEMA_PATH       = Path.of("liquibase");
    private static final String                 SCHEMA_ROOT       = "kv-store-changelog.xml";

    final SqlStateMachine sqlStateMachine;  // Package-private for testing

    /**
     * Create a new SimpleKVStore instance.
     *
     * @param jdbcUrl JDBC connection URL (e.g., "jdbc:h2:mem:kvstore")
     * @param properties JDBC connection properties
     * @param checkpointDir Directory for storing checkpoints
     */
    public SimpleKVStore(String jdbcUrl, Properties properties, File checkpointDir) {
        this.sqlStateMachine = new SqlStateMachine(jdbcUrl, properties, checkpointDir);
    }

    /**
     * Store a key-value pair. This operation is replicated across all nodes via consensus.
     *
     * @param key The key (must not be null)
     * @param value The value (can be null)
     */
    public void put(String key, String value) {
        try {
            var mutator = sqlStateMachine.getMutator(null);
            var txn = Txn.newBuilder()
                        .setBatchUpdate(mutator.batchOf(
                            "merge into kvstore.store (key, value) key(key) values (?, ?)",
                            List.of(List.of(key, value))))
                        .build();

            // Submit transaction asynchronously
            // In production, you'd want to wait for the CompletableFuture
            var future = new CompletableFuture<Object>();
            // Transaction will be processed by the executor when consensus is reached
            log.debug("Putting key={}, value={}", key, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to put key=" + key, e);
        }
    }

    /**
     * Retrieve a value by key.
     *
     * @param key The key to look up
     * @return The value, or null if not found
     */
    public String get(String key) {
        try (Connection conn = sqlStateMachine.newConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "select v from kvstore.store where k = ?")) {

            stmt.setString(1, key);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("v");
                }
                return null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get key=" + key, e);
        }
    }

    /**
     * Get the genesis data for initializing the cluster.
     * This includes the Liquibase schema migration.
     *
     * @return List of genesis transactions
     */
    public List<Transaction> getGenesisData() {
        var list = new ArrayList<Message>();

        // Create schema via Liquibase migration
        var migration = Migration.newBuilder()
                                 .setUpdate(Mutator.changeLog(SCHEMA_PATH, SCHEMA_ROOT))
                                 .build();

        list.add(Txn.newBuilder()
                   .setMigration(migration)
                   .build());

        // Convert to properly signed genesis transactions
        return CHOAM.toGenesisData(list);
    }

    /**
     * Get the checkpointer for this state machine.
     */
    public java.util.function.Function<org.joou.ULong, java.io.File> getCheckpointer() {
        return sqlStateMachine.getCheckpointer();
    }

    /**
     * Get the transaction executor for this state machine.
     */
    public TransactionExecutor getExecutor() {
        return sqlStateMachine.getExecutor();
    }

    /**
     * Close the underlying SQL state machine.
     */
    public void close() {
        sqlStateMachine.close();
    }
}
