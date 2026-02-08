/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for state migrators enabling version compatibility across CHOAM upgrades.
 * <p>
 * The registry maintains a graph of version transitions and can automatically
 * find migration paths from any source version to any target version using
 * Dijkstra's shortest path algorithm.
 * <p>
 * <b>Example Usage</b>:
 * <pre>
 * var registry = new MigrationRegistry();
 * registry.register(new V1ToV2Migrator());
 * registry.register(new V2ToV3Migrator());
 *
 * // Automatic path finding: V1 → V2 → V3
 * registry.migrate(checkpointV1, "0.0.6", "0.0.8", output);
 * </pre>
 *
 * @author hal.hildebrand
 */
public class MigrationRegistry {
    private static final Logger log = LoggerFactory.getLogger(MigrationRegistry.class);

    private final Map<String, Map<String, StateMigrator>> migrators = new ConcurrentHashMap<>();

    /**
     * Register a state migrator.
     *
     * @param migrator the migrator to register
     * @throws IllegalArgumentException if migrator with same versions already registered
     */
    public void register(StateMigrator migrator) {
        var source = migrator.getSourceVersion();
        var target = migrator.getTargetVersion();

        migrators.computeIfAbsent(source, k -> new ConcurrentHashMap<>())
                 .compute(target, (k, existing) -> {
                     if (existing != null) {
                         throw new IllegalArgumentException(
                             String.format("Migrator already registered for %s → %s", source, target)
                         );
                     }
                     log.info("Registered migrator: {} → {} ({})",
                              source, target, migrator.getDescription());
                     return migrator;
                 });
    }

    /**
     * Find a migrator for the given version transition.
     *
     * @param sourceVersion version to migrate from
     * @param targetVersion version to migrate to
     * @return optional migrator if found
     */
    public Optional<StateMigrator> find(String sourceVersion, String targetVersion) {
        return Optional.ofNullable(migrators.getOrDefault(sourceVersion, Map.of())
                                            .get(targetVersion));
    }

    /**
     * Find migration path from source to target version using Dijkstra's algorithm.
     * <p>
     * Supports multi-hop migrations: V1 → V2 → V3
     *
     * @param sourceVersion version to migrate from
     * @param targetVersion version to migrate to
     * @return list of migrators forming the path, empty if no path exists
     */
    public List<StateMigrator> findPath(String sourceVersion, String targetVersion) {
        if (sourceVersion.equals(targetVersion)) {
            return List.of();  // No migration needed
        }

        // Dijkstra's shortest path
        var visited = new HashSet<String>();
        var distances = new HashMap<String, Integer>();
        var previous = new HashMap<String, String>();
        var queue = new PriorityQueue<>(Comparator.comparingInt(distances::get));

        distances.put(sourceVersion, 0);
        queue.add(sourceVersion);

        while (!queue.isEmpty()) {
            var current = queue.poll();
            if (visited.contains(current)) continue;
            visited.add(current);

            if (current.equals(targetVersion)) {
                // Found target, reconstruct path
                return reconstructPath(sourceVersion, targetVersion, previous);
            }

            // Explore neighbors
            var neighbors = migrators.getOrDefault(current, Map.of());
            for (var next : neighbors.keySet()) {
                if (visited.contains(next)) continue;

                var distance = distances.get(current) + 1;
                if (distance < distances.getOrDefault(next, Integer.MAX_VALUE)) {
                    distances.put(next, distance);
                    previous.put(next, current);
                    queue.add(next);
                }
            }
        }

        return List.of();  // No path found
    }

    /**
     * Migrate state from source version to target version.
     * <p>
     * Automatically finds migration path and applies all migrations in sequence.
     *
     * @param source input stream with source version checkpoint
     * @param sourceVersion version of source checkpoint
     * @param targetVersion desired target version
     * @param target output stream for migrated checkpoint
     * @throws MigrationException if migration fails or no path exists
     */
    public void migrate(InputStream source, String sourceVersion, String targetVersion,
                        OutputStream target) throws MigrationException {
        var path = findPath(sourceVersion, targetVersion);
        if (path.isEmpty() && !sourceVersion.equals(targetVersion)) {
            throw new MigrationException(
                String.format("No migration path from %s to %s", sourceVersion, targetVersion)
            );
        }

        if (path.isEmpty()) {
            // No migration needed, copy directly
            try {
                source.transferTo(target);
            } catch (Exception e) {
                throw new MigrationException("Failed to copy checkpoint", e);
            }
            return;
        }

        log.info("Migrating {} → {} via {} hops", sourceVersion, targetVersion, path.size());

        // Apply migrations in sequence
        try {
            var current = source;
            for (int i = 0; i < path.size(); i++) {
                var migrator = path.get(i);
                log.debug("Applying migration {}/{}: {}",
                          i + 1, path.size(), migrator.getDescription());

                if (i == path.size() - 1) {
                    // Last migration, write to target
                    migrator.migrate(current, target);
                } else {
                    // Intermediate migration, use temporary buffer
                    var temp = new java.io.ByteArrayOutputStream();
                    migrator.migrate(current, temp);
                    current = new java.io.ByteArrayInputStream(temp.toByteArray());
                }
            }

            log.info("Migration completed: {} → {}", sourceVersion, targetVersion);
        } catch (MigrationException e) {
            throw e;
        } catch (Exception e) {
            throw new MigrationException("Migration failed", e);
        }
    }

    /**
     * Check if a migration path exists from source to target version.
     *
     * @param sourceVersion version to migrate from
     * @param targetVersion version to migrate to
     * @return true if migration path exists
     */
    public boolean canMigrate(String sourceVersion, String targetVersion) {
        return !findPath(sourceVersion, targetVersion).isEmpty()
               || sourceVersion.equals(targetVersion);
    }

    /**
     * Get all registered source versions.
     *
     * @return set of source versions
     */
    public Set<String> getSourceVersions() {
        return new HashSet<>(migrators.keySet());
    }

    /**
     * Get all registered target versions for a given source version.
     *
     * @param sourceVersion source version
     * @return set of target versions
     */
    public Set<String> getTargetVersions(String sourceVersion) {
        return new HashSet<>(migrators.getOrDefault(sourceVersion, Map.of()).keySet());
    }

    private List<StateMigrator> reconstructPath(String source, String target,
                                                 Map<String, String> previous) {
        var path = new ArrayList<StateMigrator>();
        var current = target;

        while (!current.equals(source)) {
            var prev = previous.get(current);
            if (prev == null) {
                return List.of();  // Path broken
            }

            var migrator = migrators.get(prev).get(current);
            path.add(0, migrator);  // Prepend (reverse order)
            current = prev;
        }

        return path;
    }
}
