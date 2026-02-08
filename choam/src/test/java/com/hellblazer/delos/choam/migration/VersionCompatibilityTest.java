/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.migration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for version compatibility and state migration.
 * <p>
 * Validates the compatibility matrix:
 * - Version N-1 ↔ Version N (backward/forward compatible)
 * - Version N ↔ Version N+1 (backward/forward compatible)
 * - Version N-1 ✗ Version N+1 (incompatible)
 *
 * @author hal.hildebrand
 */
public class VersionCompatibilityTest {

    private MigrationRegistry registry;

    @BeforeEach
    public void setup() {
        registry = new MigrationRegistry();
    }

    @Test
    public void testDirectMigration() throws Exception {
        // Arrange: V1 → V2 migrator
        var migrator = new TestMigrator("0.0.6", "0.0.7");
        registry.register(migrator);

        var input = new ByteArrayInputStream("state-v1".getBytes());
        var output = new ByteArrayOutputStream();

        // Act: Migrate
        registry.migrate(input, "0.0.6", "0.0.7", output);

        // Assert: Output is migrated state
        assertThat(output.toString()).isEqualTo("state-v2-migrated-from-state-v1");
    }

    @Test
    public void testMultiHopMigration() throws Exception {
        // Arrange: V1 → V2 → V3 migration path
        registry.register(new TestMigrator("0.0.6", "0.0.7"));
        registry.register(new TestMigrator("0.0.7", "0.0.8"));

        var input = new ByteArrayInputStream("state-v1".getBytes());
        var output = new ByteArrayOutputStream();

        // Act: Migrate V1 → V3 (automatic path finding)
        registry.migrate(input, "0.0.6", "0.0.8", output);

        // Assert: Path found and migration succeeded
        assertThat(output.toString()).contains("migrated");
    }

    @Test
    public void testNoMigrationNeeded() throws Exception {
        // Arrange: Same version
        var input = new ByteArrayInputStream("state-v1".getBytes());
        var output = new ByteArrayOutputStream();

        // Act: Migrate same version
        registry.migrate(input, "0.0.6", "0.0.6", output);

        // Assert: State copied without modification
        assertThat(output.toString()).isEqualTo("state-v1");
    }

    @Test
    public void testNoPathExists() {
        // Arrange: No migrator registered
        var input = new ByteArrayInputStream("state-v1".getBytes());
        var output = new ByteArrayOutputStream();

        // Act & Assert: Migration fails
        assertThatThrownBy(() -> registry.migrate(input, "0.0.6", "0.0.9", output))
            .isInstanceOf(MigrationException.class)
            .hasMessageContaining("No migration path");
    }

    @Test
    public void testBackwardCompatibility() throws Exception {
        // Arrange: V2 → V1 rollback migrator
        registry.register(new TestMigrator("0.0.7", "0.0.6"));

        var input = new ByteArrayInputStream("state-v2".getBytes());
        var output = new ByteArrayOutputStream();

        // Act: Rollback
        registry.migrate(input, "0.0.7", "0.0.6", output);

        // Assert: Rollback succeeded
        assertThat(output.toString()).contains("migrated");
    }

    @Test
    public void testCanMigrate() {
        // Arrange: V1 → V2 path
        registry.register(new TestMigrator("0.0.6", "0.0.7"));
        registry.register(new TestMigrator("0.0.7", "0.0.8"));

        // Assert: Can migrate
        assertThat(registry.canMigrate("0.0.6", "0.0.7")).isTrue();
        assertThat(registry.canMigrate("0.0.6", "0.0.8")).isTrue();  // Multi-hop
        assertThat(registry.canMigrate("0.0.6", "0.0.9")).isFalse(); // No path
    }

    @Test
    public void testFindPath() {
        // Arrange: V1 → V2 → V3
        var v1v2 = new TestMigrator("0.0.6", "0.0.7");
        var v2v3 = new TestMigrator("0.0.7", "0.0.8");
        registry.register(v1v2);
        registry.register(v2v3);

        // Act: Find path
        var path = registry.findPath("0.0.6", "0.0.8");

        // Assert: Path is V1→V2→V3
        assertThat(path).hasSize(2);
        assertThat(path.get(0)).isSameAs(v1v2);
        assertThat(path.get(1)).isSameAs(v2v3);
    }

    @Test
    public void testDuplicateRegistrationRejected() {
        // Arrange: Register migrator
        registry.register(new TestMigrator("0.0.6", "0.0.7"));

        // Act & Assert: Duplicate rejected
        assertThatThrownBy(() -> registry.register(new TestMigrator("0.0.6", "0.0.7")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already registered");
    }

    @Test
    public void testGetSourceVersions() {
        // Arrange: Register migrators
        registry.register(new TestMigrator("0.0.6", "0.0.7"));
        registry.register(new TestMigrator("0.0.7", "0.0.8"));

        // Act: Get source versions
        var sources = registry.getSourceVersions();

        // Assert: Both sources present
        assertThat(sources).containsExactlyInAnyOrder("0.0.6", "0.0.7");
    }

    @Test
    public void testGetTargetVersions() {
        // Arrange: Register migrators
        registry.register(new TestMigrator("0.0.6", "0.0.7"));
        registry.register(new TestMigrator("0.0.6", "0.0.8"));

        // Act: Get targets for V1
        var targets = registry.getTargetVersions("0.0.6");

        // Assert: Both targets present
        assertThat(targets).containsExactlyInAnyOrder("0.0.7", "0.0.8");
    }

    /**
     * Test migrator that appends "-migrated-from-{input}" to demonstrate migration.
     */
    private static class TestMigrator implements StateMigrator {
        private final String sourceVersion;
        private final String targetVersion;

        public TestMigrator(String sourceVersion, String targetVersion) {
            this.sourceVersion = sourceVersion;
            this.targetVersion = targetVersion;
        }

        @Override
        public String getSourceVersion() {
            return sourceVersion;
        }

        @Override
        public String getTargetVersion() {
            return targetVersion;
        }

        @Override
        public void migrate(InputStream source, OutputStream target) throws MigrationException {
            try {
                var input = new String(source.readAllBytes());
                var output = String.format("state-v%s-migrated-from-%s",
                                           targetVersion.replace("0.0.", ""),
                                           input);
                target.write(output.getBytes());
            } catch (IOException e) {
                throw new MigrationException("Test migration failed", e);
            }
        }

        @Override
        public String getDescription() {
            return String.format("Test migrator %s → %s", sourceVersion, targetVersion);
        }
    }
}
