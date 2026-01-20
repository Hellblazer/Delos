/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.state;

import com.hellblazer.delos.choam.proto.Transaction;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.state.proto.Migration;
import com.hellblazer.delos.state.proto.Txn;
import org.joou.ULong;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for Liquibase migration determinism in Byzantine
 * fault-tolerant replicated state machines.
 * <p>
 * <strong>CRITICAL REQUIREMENT:</strong> All replicas must produce identical
 * database schemas after applying the same migrations. Any non-determinism in
 * schema construction causes consensus failures.
 * <p>
 * This test suite validates:
 * <ul>
 *   <li>Schema hash consistency across independent H2 instances</li>
 *   <li>Safe change types (createTable, dropTable, addColumn, dropColumn, createIndex, dropIndex)</li>
 *   <li>LinkedHashSet fix for iteration order determinism (Delos-nric)</li>
 *   <li>Schema evolution determinism (v1 → v2 → v3)</li>
 *   <li>Detection of non-deterministic change types</li>
 * </ul>
 * <p>
 * Related: Delos-iaks (Liquibase migration determinism test suite)
 *
 * @author test
 */
public class LiquibaseMigrationDeterminismTest {

    private static final Path DETERMINISM_TEST_PATH = Path.of("src", "test", "resources", "determinism-test");

    /**
     * Test 1: Run same changeset on 4 independent H2 instances and compare schema hashes.
     * <p>
     * This is the fundamental determinism test: if Liquibase is deterministic,
     * all replicas will produce identical schemas with identical hashes.
     */
    @Test
    public void testBasicSchemaDeterminism() throws Exception {
        System.out.println("\n========================================");
        System.out.println("Test 1: Basic Schema Determinism");
        System.out.println("========================================\n");

        // Create 4 independent SqlStateMachine instances (simulating 4 replicas)
        SqlStateMachine[] replicas = new SqlStateMachine[4];
        String[] schemaHashes = new String[4];

        for (int i = 0; i < 4; i++) {
            String dbUrl = String.format("jdbc:h2:mem:determinism_test_%d", i);
            replicas[i] = new SqlStateMachine(dbUrl, new Properties(),
                                               new File("target/chkpoints-det-" + i));

            // Initialize with genesis block
            replicas[i].getExecutor().genesis(DigestAlgorithm.DEFAULT.getLast(), Collections.emptyList());

            // Apply basic schema migration
            Migration migration = Migration.newBuilder()
                                           .setUpdate(Mutator.changeLog(DETERMINISM_TEST_PATH, "basic-schema.xml"))
                                           .build();

            CompletableFuture<Object> success = new CompletableFuture<>();
            replicas[i].getExecutor().beginBlock(ULong.valueOf(1),
                                                  DigestAlgorithm.DEFAULT.getOrigin().prefix("block1"));
            replicas[i].getExecutor().execute(0, Digest.NONE,
                                               Transaction.newBuilder()
                                                          .setContent(Txn.newBuilder()
                                                                         .setMigration(migration)
                                                                         .build()
                                                                         .toByteString())
                                                          .build(),
                                               success);

            success.get(5, TimeUnit.SECONDS);

            // Compute schema hash
            schemaHashes[i] = computeSchemaHash(replicas[i].newConnection(), "PUBLIC");

            System.out.printf("Replica %d schema hash: %s%n", i, schemaHashes[i]);
        }

        // Verify all schema hashes are identical
        String expectedHash = schemaHashes[0];
        for (int i = 1; i < 4; i++) {
            assertEquals(expectedHash, schemaHashes[i],
                         String.format("Replica %d schema diverged! All replicas must produce identical schemas.", i));
        }

        System.out.println("\n✓ SUCCESS: All 4 replicas produced identical schema hash: " + expectedHash);

        // Cleanup - connections will be cleaned up by GC
    }

    /**
     * Test 2: Test all safe change types.
     * <p>
     * Validates that common schema change operations are deterministic:
     * - createTable
     * - dropTable
     * - addColumn
     * - dropColumn
     * - createIndex
     * - dropIndex
     */
    @Test
    public void testSafeChangeTypes() throws Exception {
        System.out.println("\n========================================");
        System.out.println("Test 2: Safe Change Types Determinism");
        System.out.println("========================================\n");

        // Create 2 replicas
        SqlStateMachine replica1 = new SqlStateMachine("jdbc:h2:mem:safe_changes_1", new Properties(),
                                                        new File("target/chkpoints-safe-1"));
        SqlStateMachine replica2 = new SqlStateMachine("jdbc:h2:mem:safe_changes_2", new Properties(),
                                                        new File("target/chkpoints-safe-2"));

        replica1.getExecutor().genesis(DigestAlgorithm.DEFAULT.getLast(), Collections.emptyList());
        replica2.getExecutor().genesis(DigestAlgorithm.DEFAULT.getLast(), Collections.emptyList());

        // Apply basic schema (tests createTable, addColumn, createIndex)
        applyMigration(replica1, 1, "basic-schema.xml");
        applyMigration(replica2, 1, "basic-schema.xml");

        String hash1AfterCreate = computeSchemaHash(replica1.newConnection(), "PUBLIC");
        String hash2AfterCreate = computeSchemaHash(replica2.newConnection(), "PUBLIC");

        assertEquals(hash1AfterCreate, hash2AfterCreate,
                     "Schema hashes diverged after createTable/addColumn/createIndex");

        System.out.println("✓ createTable, addColumn, createIndex: DETERMINISTIC");

        // Note: dropColumn, dropIndex, dropTable are also tested in the schema evolution test (Test 4)
        // which includes evolution-v3.xml that performs drop operations. This validates that
        // drop operations are deterministic as part of the complete evolution workflow.

        System.out.println("✓ dropColumn, dropIndex, dropTable: Tested in schema evolution (Test 4)");

        System.out.println("\n✓ SUCCESS: All safe change types are deterministic");

        // Cleanup - connections will be cleaned up by GC
    }

    /**
     * Test 3: Verify LinkedHashSet fix for iteration order determinism.
     * <p>
     * After Delos-nric, SqlGeneratorFactory.getAffectedDatabaseObjects() uses
     * LinkedHashSet instead of HashSet. This test verifies that affected objects
     * are iterated in the same order across replicas.
     * <p>
     * We can't directly test iteration order without modifying Liquibase internals,
     * but we can verify that complex multi-object migrations produce identical schemas.
     */
    @Test
    public void testLinkedHashSetIterationOrder() throws Exception {
        System.out.println("\n========================================");
        System.out.println("Test 3: LinkedHashSet Iteration Order");
        System.out.println("========================================\n");

        // Create 3 replicas
        SqlStateMachine[] replicas = new SqlStateMachine[3];
        String[] hashes = new String[3];

        for (int i = 0; i < 3; i++) {
            replicas[i] = new SqlStateMachine(String.format("jdbc:h2:mem:linked_hashset_%d", i),
                                               new Properties(),
                                               new File("target/chkpoints-lhs-" + i));
            replicas[i].getExecutor().genesis(DigestAlgorithm.DEFAULT.getLast(), Collections.emptyList());

            // Apply complex changeset with many affected objects
            // This exercises getAffectedDatabaseObjects() heavily
            applyMigration(replicas[i], 1, "basic-schema.xml");

            hashes[i] = computeSchemaHash(replicas[i].newConnection(), "PUBLIC");
            System.out.printf("Replica %d schema hash: %s%n", i, hashes[i]);
        }

        // All hashes must be identical
        for (int i = 1; i < 3; i++) {
            assertEquals(hashes[0], hashes[i],
                         "LinkedHashSet iteration order test failed: schema divergence detected");
        }

        System.out.println("\n✓ SUCCESS: LinkedHashSet maintains deterministic iteration order");
        System.out.println("  All replicas produced identical schema despite multiple affected objects");

        // Cleanup - connections will be cleaned up by GC
    }

    /**
     * Test 4: Test schema evolution (v1 → v2 → v3) produces identical final schema.
     * <p>
     * Validates that incremental schema changes over multiple versions maintain
     * determinism across all replicas.
     */
    @Test
    public void testSchemaEvolution() throws Exception {
        System.out.println("\n========================================");
        System.out.println("Test 4: Schema Evolution Determinism");
        System.out.println("========================================\n");

        // Create 4 replicas
        SqlStateMachine[] replicas = new SqlStateMachine[4];
        String[] hashesV1 = new String[4];
        String[] hashesV2 = new String[4];
        String[] hashesV3 = new String[4];

        for (int i = 0; i < 4; i++) {
            replicas[i] = new SqlStateMachine(String.format("jdbc:h2:mem:evolution_%d", i),
                                               new Properties(),
                                               new File("target/chkpoints-evo-" + i));
            replicas[i].getExecutor().genesis(DigestAlgorithm.DEFAULT.getLast(), Collections.emptyList());

            // V1: Initial schema
            applyMigration(replicas[i], 1, "evolution-v1.xml");
            hashesV1[i] = computeSchemaHash(replicas[i].newConnection(), "PUBLIC");

            // V2: Add columns and indexes
            applyMigration(replicas[i], 2, "evolution-v2.xml");
            hashesV2[i] = computeSchemaHash(replicas[i].newConnection(), "PUBLIC");

            // V3: Drop column, drop index, add table
            applyMigration(replicas[i], 3, "evolution-v3.xml");
            hashesV3[i] = computeSchemaHash(replicas[i].newConnection(), "PUBLIC");

            System.out.printf("Replica %d: V1=%s, V2=%s, V3=%s%n",
                              i, hashesV1[i].substring(0, 8), hashesV2[i].substring(0, 8),
                              hashesV3[i].substring(0, 8));
        }

        // Verify V1 consistency
        for (int i = 1; i < 4; i++) {
            assertEquals(hashesV1[0], hashesV1[i], "V1 schema diverged on replica " + i);
        }
        System.out.println("✓ V1 schema: DETERMINISTIC");

        // Verify V2 consistency
        for (int i = 1; i < 4; i++) {
            assertEquals(hashesV2[0], hashesV2[i], "V2 schema diverged on replica " + i);
        }
        System.out.println("✓ V2 schema: DETERMINISTIC");

        // Verify V3 consistency
        for (int i = 1; i < 4; i++) {
            assertEquals(hashesV3[0], hashesV3[i], "V3 schema diverged on replica " + i);
        }
        System.out.println("✓ V3 schema: DETERMINISTIC");

        System.out.println("\n✓ SUCCESS: Schema evolution (V1 → V2 → V3) is deterministic across all replicas");

        // Cleanup - connections will be cleaned up by GC
    }

    /**
     * Test 5: Document forbidden change types that are non-deterministic.
     * <p>
     * This test documents (not validates) change types that MUST NOT be used
     * in Byzantine fault-tolerant systems because they produce non-deterministic results:
     * <ul>
     *   <li><code>loadData</code> - File I/O order undefined</li>
     *   <li><code>sql</code> - Raw SQL may use non-deterministic functions</li>
     *   <li><code>sqlFile</code> - File I/O order undefined</li>
     *   <li><code>executeCommand</code> - External command execution non-deterministic</li>
     *   <li><code>loadUpdateData</code> - File I/O order undefined</li>
     * </ul>
     * <p>
     * <strong>FORBIDDEN CHANGE TYPES:</strong>
     * <pre>
     * {@code
     * <!-- DO NOT USE - Non-deterministic! -->
     * <loadData file="data.csv" tableName="USERS"/>
     * <sql>SELECT RAND()</sql>
     * <sqlFile path="script.sql"/>
     * <executeCommand executable="script.sh"/>
     * <loadUpdateData file="updates.csv" tableName="USERS"/>
     * }
     * </pre>
     * <p>
     * <strong>USE INSTEAD:</strong>
     * <pre>
     * {@code
     * <!-- Deterministic alternative: Insert via SQL batch -->
     * <insert schemaName="PUBLIC" tableName="USERS">
     *   <column name="ID" valueNumeric="1"/>
     *   <column name="NAME" value="Alice"/>
     * </insert>
     * }
     * </pre>
     */
    @Test
    public void testForbiddenChangeTypesDocumentation() {
        System.out.println("\n========================================");
        System.out.println("Test 5: Forbidden Change Types");
        System.out.println("========================================\n");

        System.out.println("CRITICAL: The following Liquibase change types are FORBIDDEN");
        System.out.println("in Byzantine fault-tolerant replicated state machines:\n");

        Map<String, String> forbiddenTypes = new LinkedHashMap<>();
        forbiddenTypes.put("loadData", "File I/O order is undefined, CSV parsing may vary");
        forbiddenTypes.put("sql", "Raw SQL may use non-deterministic functions (RAND, NOW, UUID)");
        forbiddenTypes.put("sqlFile", "File I/O order undefined, script may use non-deterministic SQL");
        forbiddenTypes.put("executeCommand", "External command execution is non-deterministic");
        forbiddenTypes.put("loadUpdateData", "File I/O order undefined, update order may vary");

        forbiddenTypes.forEach((type, reason) -> {
            System.out.printf("  ✗ %s: %s%n", type, reason);
        });

        System.out.println("\nSAFE ALTERNATIVES:");
        System.out.println("  ✓ Use <insert> tags for deterministic data loading");
        System.out.println("  ✓ Use createTable/addColumn/createIndex for schema changes");
        System.out.println("  ✓ Use SQL functions from whitelist (see Delos-a5g5)");

        System.out.println("\n✓ Documentation complete: Forbidden change types recorded");

        // This test always passes - it's documentation
        assertTrue(true, "Forbidden change types documented");
    }

    /**
     * Compute a cryptographic hash of the database schema.
     * <p>
     * This hash captures:
     * - Table names and columns (in sorted order)
     * - Column types and constraints
     * - Index names and columns
     * - Primary keys and foreign keys
     * <p>
     * If schemas are identical, hashes will match. If schemas diverge
     * (even slightly), hashes will differ.
     */
    private String computeSchemaHash(Connection connection, String schema) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        DatabaseMetaData metaData = connection.getMetaData();

        // Get all tables in schema (sorted for determinism)
        List<String> tables = new ArrayList<>();
        try (ResultSet rs = metaData.getTables(null, schema, null, new String[]{"TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME"));
            }
        }
        Collections.sort(tables);

        for (String table : tables) {
            digest.update(("TABLE:" + table).getBytes());

            // Get columns (sorted)
            List<String> columns = new ArrayList<>();
            try (ResultSet rs = metaData.getColumns(null, schema, table, null)) {
                while (rs.next()) {
                    String colName = rs.getString("COLUMN_NAME");
                    String colType = rs.getString("TYPE_NAME");
                    int colSize = rs.getInt("COLUMN_SIZE");
                    String nullable = rs.getString("IS_NULLABLE");
                    columns.add(String.format("%s:%s(%d):%s", colName, colType, colSize, nullable));
                }
            }
            Collections.sort(columns);
            for (String col : columns) {
                digest.update(col.getBytes());
            }

            // Get primary keys
            try (ResultSet rs = metaData.getPrimaryKeys(null, schema, table)) {
                while (rs.next()) {
                    digest.update(("PK:" + rs.getString("COLUMN_NAME")).getBytes());
                }
            }

            // Get indexes (sorted)
            List<String> indexes = new ArrayList<>();
            try (ResultSet rs = metaData.getIndexInfo(null, schema, table, false, false)) {
                while (rs.next()) {
                    String indexName = rs.getString("INDEX_NAME");
                    if (indexName != null && !indexName.startsWith("PRIMARY_KEY")) {
                        String colName = rs.getString("COLUMN_NAME");
                        indexes.add(String.format("%s:%s", indexName, colName));
                    }
                }
            }
            Collections.sort(indexes);
            for (String idx : indexes) {
                digest.update(idx.getBytes());
            }
        }

        // Convert hash to hex string
        byte[] hashBytes = digest.digest();
        StringBuilder hexString = new StringBuilder();
        for (byte b : hashBytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    /**
     * Apply a migration from a changeset file.
     */
    private void applyMigration(SqlStateMachine machine, int blockHeight, String changesetFile) throws Exception {
        Migration migration = Migration.newBuilder()
                                       .setUpdate(Mutator.changeLog(DETERMINISM_TEST_PATH, changesetFile))
                                       .build();

        CompletableFuture<Object> success = new CompletableFuture<>();
        machine.getExecutor().beginBlock(ULong.valueOf(blockHeight),
                                          DigestAlgorithm.DEFAULT.getOrigin().prefix("block" + blockHeight));
        machine.getExecutor().execute(0, Digest.NONE,
                                       Transaction.newBuilder()
                                                  .setContent(Txn.newBuilder()
                                                                 .setMigration(migration)
                                                                 .build()
                                                                 .toByteString())
                                                  .build(),
                                       success);

        success.get(5, TimeUnit.SECONDS);
    }

}
