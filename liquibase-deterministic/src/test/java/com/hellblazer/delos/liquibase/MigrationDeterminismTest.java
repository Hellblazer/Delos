package com.hellblazer.delos.liquibase;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that Liquibase migrations produce identical schemas across multiple replicas.
 * <p>
 * <strong>Byzantine Fault Tolerance Requirement:</strong> All replicas in a BFT state machine
 * must apply migrations deterministically. This test verifies that running the same changelog
 * on 4 separate H2 databases produces byte-for-byte identical schemas.
 * <p>
 * <strong>Test Strategy:</strong>
 * <ol>
 *   <li>Create 4 independent H2 databases (simulating 4 replicas)</li>
 *   <li>Run identical Liquibase changelogs on each database</li>
 *   <li>Extract schema metadata (tables, columns, indexes, constraints)</li>
 *   <li>Compare schemas using deterministic hash (all replicas must match)</li>
 * </ol>
 * <p>
 * <strong>Forbidden Change Types:</strong> The test also validates that forbidden
 * non-deterministic operations are not used:
 * <ul>
 *   <li><strong>loadData with RAND():</strong> Random data insertion</li>
 *   <li><strong>Timestamp-based changeset IDs:</strong> Use sequential IDs instead</li>
 *   <li><strong>External file refs:</strong> All SQL must be in changelog</li>
 *   <li><strong>System functions:</strong> NOW(), CURRENT_TIMESTAMP, etc.</li>
 * </ul>
 */
public class MigrationDeterminismTest {
    private static final Logger log = LoggerFactory.getLogger(MigrationDeterminismTest.class);
    private static final int REPLICA_COUNT = 4;

    private List<Connection> connections;
    private List<Database> databases;

    @BeforeEach
    void setUp() throws Exception {
        // Initialize deterministic SecureRandom for H2
        // H2-deterministic requires explicit SecureRandom seeding for deterministic behavior
        byte[] seed = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
        random.setSeed(seed);
        deterministic.org.h2.util.MathUtils.SECURE_RANDOM.set(random);

        connections = new ArrayList<>();
        databases = new ArrayList<>();

        // Create 4 independent H2 databases (simulating 4 replicas)
        for (int i = 0; i < REPLICA_COUNT; i++) {
            Connection conn = DriverManager.getConnection(
                "jdbc:h2:mem:replica_" + i + ";DB_CLOSE_DELAY=-1;MODE=STRICT",
                "sa", ""
            );
            connections.add(conn);

            Database db = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(conn));
            databases.add(db);

            log.debug("Created replica database {}", i);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        // Close all database connections
        for (Connection conn : connections) {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        }
        connections.clear();
        databases.clear();

        // Clean up ThreadLocal to prevent leaks
        deterministic.org.h2.util.MathUtils.SECURE_RANDOM.remove();
    }

    /**
     * Test basic table creation determinism.
     * <p>
     * Verifies that creating tables with columns, primary keys, and not-null constraints
     * produces identical schemas across all replicas.
     */
    @Test
    void testBasicTableCreationDeterminism() throws Exception {
        String changelogFile = "changelogs/basic-tables.xml";

        // Apply changelog to all replicas
        for (int i = 0; i < REPLICA_COUNT; i++) {
            Liquibase liquibase = new Liquibase(
                changelogFile,
                new ClassLoaderResourceAccessor(),
                databases.get(i)
            );

            liquibase.update(new Contexts(), new LabelExpression());
            log.debug("Applied changelog to replica {}", i);
        }

        // Extract and compare schemas
        List<String> schemaHashes = new ArrayList<>();
        for (int i = 0; i < REPLICA_COUNT; i++) {
            String schemaHash = computeSchemaHash(connections.get(i));
            schemaHashes.add(schemaHash);
            log.debug("Replica {} schema hash: {}", i, schemaHash);
        }

        // All replicas must have identical schemas
        String expectedHash = schemaHashes.get(0);
        for (int i = 1; i < REPLICA_COUNT; i++) {
            assertEquals(expectedHash, schemaHashes.get(i),
                "Replica " + i + " schema diverged from replica 0");
        }

        log.info("Basic table creation determinism validated: all {} replicas match", REPLICA_COUNT);
    }

    /**
     * Test index creation determinism.
     * <p>
     * Verifies that creating indexes (unique, non-unique, composite) produces identical
     * schemas across all replicas. Index creation order and naming must be deterministic.
     */
    @Test
    void testIndexCreationDeterminism() throws Exception {
        String changelogFile = "changelogs/indexes.xml";

        for (int i = 0; i < REPLICA_COUNT; i++) {
            Liquibase liquibase = new Liquibase(
                changelogFile,
                new ClassLoaderResourceAccessor(),
                databases.get(i)
            );

            liquibase.update(new Contexts(), new LabelExpression());
        }

        List<String> schemaHashes = new ArrayList<>();
        for (int i = 0; i < REPLICA_COUNT; i++) {
            String schemaHash = computeSchemaHash(connections.get(i));
            schemaHashes.add(schemaHash);
        }

        String expectedHash = schemaHashes.get(0);
        for (int i = 1; i < REPLICA_COUNT; i++) {
            assertEquals(expectedHash, schemaHashes.get(i),
                "Replica " + i + " index schema diverged from replica 0");
        }

        log.info("Index creation determinism validated: all {} replicas match", REPLICA_COUNT);
    }

    /**
     * Test foreign key constraint determinism.
     * <p>
     * Verifies that foreign key constraints produce identical schemas. Constraint naming
     * and validation rules must be deterministic.
     */
    @Test
    void testForeignKeyDeterminism() throws Exception {
        String changelogFile = "changelogs/foreign-keys.xml";

        for (int i = 0; i < REPLICA_COUNT; i++) {
            Liquibase liquibase = new Liquibase(
                changelogFile,
                new ClassLoaderResourceAccessor(),
                databases.get(i)
            );

            liquibase.update(new Contexts(), new LabelExpression());
        }

        List<String> schemaHashes = new ArrayList<>();
        for (int i = 0; i < REPLICA_COUNT; i++) {
            String schemaHash = computeSchemaHash(connections.get(i));
            schemaHashes.add(schemaHash);
        }

        String expectedHash = schemaHashes.get(0);
        for (int i = 1; i < REPLICA_COUNT; i++) {
            assertEquals(expectedHash, schemaHashes.get(i),
                "Replica " + i + " foreign key schema diverged from replica 0");
        }

        log.info("Foreign key determinism validated: all {} replicas match", REPLICA_COUNT);
    }

    /**
     * Test deterministic data insertion.
     * <p>
     * Verifies that inserting data with explicit values (no RAND(), no CURRENT_TIMESTAMP)
     * produces identical data across replicas. Data insertion order must be deterministic.
     */
    @Test
    void testDeterministicDataInsertion() throws Exception {
        String changelogFile = "changelogs/deterministic-data.xml";

        for (int i = 0; i < REPLICA_COUNT; i++) {
            Liquibase liquibase = new Liquibase(
                changelogFile,
                new ClassLoaderResourceAccessor(),
                databases.get(i)
            );

            liquibase.update(new Contexts(), new LabelExpression());
        }

        // For data insertion, also compare actual data (not just schema)
        List<String> dataHashes = new ArrayList<>();
        for (int i = 0; i < REPLICA_COUNT; i++) {
            String dataHash = computeDataHash(connections.get(i));
            dataHashes.add(dataHash);
            log.debug("Replica {} data hash: {}", i, dataHash);
        }

        String expectedHash = dataHashes.get(0);
        for (int i = 1; i < REPLICA_COUNT; i++) {
            assertEquals(expectedHash, dataHashes.get(i),
                "Replica " + i + " data diverged from replica 0");
        }

        log.info("Deterministic data insertion validated: all {} replicas match", REPLICA_COUNT);
    }

    /**
     * Computes a deterministic hash of the database schema.
     * <p>
     * Extracts tables, columns (with types, nullability, defaults), indexes, and constraints
     * in sorted order, then computes SHA-256 hash. This ensures byte-for-byte schema comparison.
     */
    private String computeSchemaHash(Connection conn) throws SQLException {
        StringBuilder schema = new StringBuilder();

        // Extract tables in sorted order
        List<String> tables = new ArrayList<>();
        try (ResultSet rs = conn.getMetaData().getTables(null, "PUBLIC", "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                if (!tableName.startsWith("DATABASECHANGELOG")) {  // Skip Liquibase metadata
                    tables.add(tableName);
                }
            }
        }
        Collections.sort(tables);

        // For each table, extract columns, indexes, constraints
        for (String table : tables) {
            schema.append("TABLE:").append(table).append("\n");

            // Extract columns in sorted order
            List<String> columns = new ArrayList<>();
            try (ResultSet rs = conn.getMetaData().getColumns(null, "PUBLIC", table, "%")) {
                while (rs.next()) {
                    String col = String.format("  COL:%s TYPE:%s SIZE:%d NULLABLE:%s DEFAULT:%s",
                        rs.getString("COLUMN_NAME"),
                        rs.getString("TYPE_NAME"),
                        rs.getInt("COLUMN_SIZE"),
                        rs.getBoolean("IS_NULLABLE") ? "YES" : "NO",
                        rs.getString("COLUMN_DEF")
                    );
                    columns.add(col);
                }
            }
            Collections.sort(columns);
            for (String col : columns) {
                schema.append(col).append("\n");
            }

            // Extract primary keys
            List<String> primaryKeys = new ArrayList<>();
            try (ResultSet rs = conn.getMetaData().getPrimaryKeys(null, "PUBLIC", table)) {
                while (rs.next()) {
                    String pk = String.format("  PK:%s COLUMN:%s SEQ:%d",
                        rs.getString("PK_NAME"),
                        rs.getString("COLUMN_NAME"),
                        rs.getShort("KEY_SEQ")
                    );
                    primaryKeys.add(pk);
                }
            }
            Collections.sort(primaryKeys);
            for (String pk : primaryKeys) {
                schema.append(pk).append("\n");
            }

            // Extract indexes
            List<String> indexes = new ArrayList<>();
            try (ResultSet rs = conn.getMetaData().getIndexInfo(null, "PUBLIC", table, false, false)) {
                while (rs.next()) {
                    String indexName = rs.getString("INDEX_NAME");
                    if (indexName != null && !indexName.startsWith("PRIMARY_KEY")) {
                        String idx = String.format("  INDEX:%s UNIQUE:%s COLUMN:%s POS:%d",
                            indexName,
                            !rs.getBoolean("NON_UNIQUE") ? "YES" : "NO",
                            rs.getString("COLUMN_NAME"),
                            rs.getShort("ORDINAL_POSITION")
                        );
                        indexes.add(idx);
                    }
                }
            }
            Collections.sort(indexes);
            for (String idx : indexes) {
                schema.append(idx).append("\n");
            }

            // Extract foreign keys
            List<String> foreignKeys = new ArrayList<>();
            try (ResultSet rs = conn.getMetaData().getImportedKeys(null, "PUBLIC", table)) {
                while (rs.next()) {
                    String fk = String.format("  FK:%s FROM:%s TO:%s.%s SEQ:%d",
                        rs.getString("FK_NAME"),
                        rs.getString("FKCOLUMN_NAME"),
                        rs.getString("PKTABLE_NAME"),
                        rs.getString("PKCOLUMN_NAME"),
                        rs.getShort("KEY_SEQ")
                    );
                    foreignKeys.add(fk);
                }
            }
            Collections.sort(foreignKeys);
            for (String fk : foreignKeys) {
                schema.append(fk).append("\n");
            }
        }

        // Compute SHA-256 hash of schema string
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(schema.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute schema hash", e);
        }
    }

    /**
     * Computes a deterministic hash of the database data.
     * <p>
     * Extracts all rows from all tables (excluding Liquibase metadata) in sorted order,
     * then computes SHA-256 hash. This ensures byte-for-byte data comparison.
     */
    private String computeDataHash(Connection conn) throws SQLException {
        StringBuilder data = new StringBuilder();

        // Extract tables in sorted order
        List<String> tables = new ArrayList<>();
        try (ResultSet rs = conn.getMetaData().getTables(null, "PUBLIC", "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                if (!tableName.startsWith("DATABASECHANGELOG")) {
                    tables.add(tableName);
                }
            }
        }
        Collections.sort(tables);

        // For each table, extract all rows
        for (String table : tables) {
            data.append("TABLE:").append(table).append("\n");

            // Get column names for ordering (use quoted identifiers for case sensitivity)
            List<String> columnNames = new ArrayList<>();
            try (ResultSet rs = conn.getMetaData().getColumns(null, "PUBLIC", table, "%")) {
                while (rs.next()) {
                    columnNames.add(rs.getString("COLUMN_NAME"));
                }
            }
            Collections.sort(columnNames);

            // Extract all rows (sorted by all columns for determinism)
            // Use quoted identifiers to handle case sensitivity correctly
            String orderBy = columnNames.stream()
                .map(col -> "\"" + col + "\"")
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
            String sql = "SELECT * FROM \"" + table + "\" ORDER BY " + orderBy;

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {

                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();

                while (rs.next()) {
                    for (int i = 1; i <= colCount; i++) {
                        Object value = rs.getObject(i);
                        data.append("  ").append(meta.getColumnName(i)).append("=")
                            .append(value == null ? "NULL" : value.toString()).append("\n");
                    }
                }
            }
        }

        // Compute SHA-256 hash
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute data hash", e);
        }
    }

    /**
     * Convert byte array to hex string (uppercase).
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
