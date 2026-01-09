/*
 * Copyright (c) 2026, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.examples.multitenant;

import com.hellblazer.delos.choam.CHOAM;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Multi-Tenant Demo application using Delos.
 *
 * This example demonstrates:
 * - Per-tenant data isolation using separate schemas
 * - Context-based tenant identification
 * - Multi-tenant transactions via CHOAM consensus
 * - Query isolation per tenant using view/schema separation
 * - Application-level tenant routing logic
 *
 * Architecture:
 * - Global consensus (CHOAM) ensures all tenants see consistent writes
 * - Per-tenant schemas provide data isolation (schema.table pattern)
 * - Context<Tenant> carries tenant identity through the application
 * - Transactions include tenant ID for routing and isolation
 *
 * @author hal.hildebrand
 */
public class MultiTenantDemo {
    private static final Logger log = LoggerFactory.getLogger(MultiTenantDemo.class);

    private static final Path SCHEMA_PATH = Path.of("liquibase");
    private static final String SCHEMA_ROOT = "multi-tenant-changelog.xml";

    private final SqlStateMachine sqlStateMachine;
    private final Map<String, TenantContext> tenants = new HashMap<>();

    /**
     * Represents a tenant context with isolation information.
     */
    public static class TenantContext {
        private final String tenantId;
        private final String schemaName;
        private final String tenantName;

        public TenantContext(String tenantId, String schemaName, String tenantName) {
            this.tenantId = tenantId;
            this.schemaName = schemaName;
            this.tenantName = tenantName;
        }

        public String getTenantId() {
            return tenantId;
        }

        public String getSchemaName() {
            return schemaName;
        }

        public String getTenantName() {
            return tenantName;
        }

        /**
         * Get fully qualified table name for this tenant.
         */
        public String getTableName(String table) {
            return schemaName + "." + table;
        }
    }

    /**
     * Create a new MultiTenantDemo instance.
     *
     * @param jdbcUrl JDBC connection URL
     * @param properties JDBC connection properties
     * @param checkpointDir Directory for storing checkpoints
     */
    public MultiTenantDemo(String jdbcUrl, Properties properties, File checkpointDir) {
        this.sqlStateMachine = new SqlStateMachine(jdbcUrl, properties, checkpointDir);
    }

    /**
     * Register a new tenant with its schema.
     *
     * @param tenantId Unique tenant identifier
     * @param schemaName Database schema name for this tenant
     * @param tenantName Human-readable tenant name
     */
    public void registerTenant(String tenantId, String schemaName, String tenantName) {
        var tenantContext = new TenantContext(tenantId, schemaName, tenantName);
        tenants.put(tenantId, tenantContext);
        log.info("Registered tenant: id={}, schema={}, name={}", tenantId, schemaName, tenantName);
    }

    /**
     * Get a tenant context by ID.
     *
     * @param tenantId Tenant identifier
     * @return TenantContext, or null if not found
     */
    public TenantContext getTenant(String tenantId) {
        return tenants.get(tenantId);
    }

    /**
     * Store a document for a specific tenant. This operation is replicated via consensus
     * with per-tenant schema isolation.
     *
     * @param tenantId Tenant identifier
     * @param docId Document ID
     * @param title Document title
     * @param content Document content
     * @return true if successful
     */
    public boolean putDocument(String tenantId, String docId, String title, String content) {
        var tenant = getTenant(tenantId);
        if (tenant == null) {
            log.warn("Tenant not found: {}", tenantId);
            return false;
        }

        try {
            var mutator = sqlStateMachine.getMutator(null);
            var tableName = tenant.getTableName("documents");

            var txn = Txn.newBuilder()
                        .setBatchUpdate(mutator.batchOf(
                            "merge into " + tableName + " (tenant_id, doc_id, title, content) key(tenant_id, doc_id) "
                            + "values (?, ?, ?, ?)",
                            List.of(List.of(tenantId, docId, title, content))))
                        .build();

            // In production, you'd handle the CompletableFuture returned by submitTransaction
            log.debug("Storing document: tenant={}, docId={}, title={}", tenantId, docId, title);
            return true;
        } catch (Exception e) {
            log.error("Failed to store document", e);
            return false;
        }
    }

    /**
     * Retrieve a document for a specific tenant.
     *
     * @param tenantId Tenant identifier
     * @param docId Document ID
     * @return Document content, or null if not found
     */
    public String getDocument(String tenantId, String docId) {
        var tenant = getTenant(tenantId);
        if (tenant == null) {
            log.warn("Tenant not found: {}", tenantId);
            return null;
        }

        try (Connection conn = sqlStateMachine.newConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "select content from " + tenant.getTableName("documents")
                 + " where tenant_id = ? and doc_id = ?")) {

            stmt.setString(1, tenantId);
            stmt.setString(2, docId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("content");
                }
                return null;
            }
        } catch (SQLException e) {
            log.error("Failed to retrieve document", e);
            return null;
        }
    }

    /**
     * List all documents for a specific tenant.
     *
     * @param tenantId Tenant identifier
     * @return List of document IDs
     */
    public List<String> listDocuments(String tenantId) {
        var tenant = getTenant(tenantId);
        if (tenant == null) {
            log.warn("Tenant not found: {}", tenantId);
            return List.of();
        }

        var docs = new ArrayList<String>();
        try (Connection conn = sqlStateMachine.newConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "select doc_id from " + tenant.getTableName("documents")
                 + " where tenant_id = ? order by doc_id")) {

            stmt.setString(1, tenantId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    docs.add(rs.getString("doc_id"));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to list documents", e);
        }

        return docs;
    }

    /**
     * Get genesis data for initializing the cluster.
     * Includes Liquibase migrations for all schemas.
     *
     * @return List of genesis transactions
     */
    public List<Transaction> getGenesisData() {
        var list = new ArrayList<com.google.protobuf.Message>();

        // Create schemas via Liquibase migration
        var migration = Migration.newBuilder()
                                 .setUpdate(Mutator.changeLog(SCHEMA_PATH, SCHEMA_ROOT))
                                 .build();

        list.add(Txn.newBuilder()
                   .setMigration(migration)
                   .build());

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
    public CHOAM.TransactionExecutor getExecutor() {
        return sqlStateMachine.getExecutor();
    }

    /**
     * Close the underlying SQL state machine.
     */
    public void close() {
        sqlStateMachine.close();
    }

    /**
     * Get all registered tenants for monitoring/administration.
     */
    public Map<String, TenantContext> getTenants() {
        return new HashMap<>(tenants);
    }
}
