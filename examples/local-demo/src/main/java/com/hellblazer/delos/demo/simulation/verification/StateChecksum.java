/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.verification;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;

/**
 * Verification check for SQL state consistency across replicas.
 * <p>
 * Queries state tables from CHOAM SQL state machines and computes checksums
 * to verify that all nodes have identical replicated state. This follows the
 * checkpoint pattern from SmokeTest.java using script export and hash comparison.
 * <p>
 * The verification process:
 * <ol>
 *   <li>Export SQL state script from each node</li>
 *   <li>Compress the script for deterministic comparison</li>
 *   <li>Compute digest hash of compressed script</li>
 *   <li>Compare hashes across all nodes</li>
 * </ol>
 *
 * @author hal.hildebrand
 */
public class StateChecksum implements VerificationCheck {
    private static final Logger log = LoggerFactory.getLogger(StateChecksum.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private final List<NodeStateProvider> nodeProviders;
    private final Duration timeout;

    /**
     * Create a state checksum verifier.
     *
     * @param nodeProviders providers for accessing SQL state from each node
     */
    public StateChecksum(List<NodeStateProvider> nodeProviders) {
        this(nodeProviders, DEFAULT_TIMEOUT);
    }

    /**
     * Create a state checksum verifier with custom timeout.
     *
     * @param nodeProviders providers for accessing SQL state from each node
     * @param timeout       timeout for verification
     */
    public StateChecksum(List<NodeStateProvider> nodeProviders, Duration timeout) {
        this.nodeProviders = nodeProviders;
        this.timeout = timeout;
    }

    @Override
    public String getName() {
        return "StateChecksum";
    }

    @Override
    public VerificationResult execute() {
        var start = Instant.now();
        log.debug("Executing state checksum verification across {} nodes", nodeProviders.size());

        try {
            var checksums = new HashMap<String, Digest>();
            var details = new ArrayList<String>();

            // Compute checksum for each node
            for (var provider : nodeProviders) {
                try {
                    var checksum = computeStateChecksum(provider);
                    checksums.put(provider.getNodeId(), checksum);
                    details.add(String.format("Node %s: %s", provider.getNodeId(), checksum));
                } catch (Exception e) {
                    log.error("Failed to compute checksum for node {}", provider.getNodeId(), e);
                    var elapsed = Duration.between(start, Instant.now());
                    return VerificationResult.error(getName(), elapsed, e);
                }
            }

            // Verify all checksums match
            var uniqueChecksums = checksums.values().stream().distinct().count();
            var elapsed = Duration.between(start, Instant.now());

            if (uniqueChecksums == 1) {
                log.debug("State checksum verification passed: all {} nodes consistent", nodeProviders.size());
                return VerificationResult.success(getName(), elapsed, details);
            } else {
                var message = String.format("State divergence detected: %d different checksums across %d nodes",
                                          uniqueChecksums, nodeProviders.size());
                log.error(message);
                return VerificationResult.failure(getName(), elapsed, message, details);
            }

        } catch (Exception e) {
            log.error("State checksum verification failed", e);
            var elapsed = Duration.between(start, Instant.now());
            return VerificationResult.error(getName(), elapsed, e);
        }
    }

    @Override
    public Duration getTimeout() {
        return timeout;
    }

    /**
     * Compute state checksum for a node.
     * <p>
     * This follows the pattern from SmokeTest.checkpoint():
     * 1. Export SQL state to script
     * 2. Compress script for deterministic comparison
     * 3. Compute digest hash
     */
    private Digest computeStateChecksum(NodeStateProvider provider) throws SQLException, java.io.IOException {
        try (var conn = provider.getConnection()) {
            // Export state to in-memory script
            var scriptBytes = exportStateScript(conn);

            // Compress script for deterministic comparison
            var compressedBytes = compressScript(scriptBytes);

            // Compute digest hash
            return DigestAlgorithm.DEFAULT.digest(new ByteArrayInputStream(compressedBytes));
        }
    }

    /**
     * Export SQL state to script bytes.
     */
    private byte[] exportStateScript(Connection conn) throws SQLException, java.io.IOException {
        var output = new ByteArrayOutputStream();

        try (var stmt = conn.createStatement()) {
            // Execute SCRIPT command to export state
            try (var rs = stmt.executeQuery("SCRIPT")) {
                while (rs.next()) {
                    var scriptLine = rs.getString(1);
                    output.write(scriptLine.getBytes());
                    output.write('\n');
                }
            }
        }

        return output.toByteArray();
    }

    /**
     * Compress script bytes using Deflater for deterministic comparison.
     */
    private byte[] compressScript(byte[] scriptBytes) {
        try {
            var output = new ByteArrayOutputStream();
            try (var deflater = new DeflaterOutputStream(output)) {
                deflater.write(scriptBytes);
                deflater.finish();
                deflater.flush();
            }
            return output.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to compress script", e);
        }
    }

    /**
     * Provider interface for accessing SQL state from a node.
     * <p>
     * Implementations provide JDBC connections to CHOAM SQL state machines.
     */
    public interface NodeStateProvider {
        /**
         * Get the node identifier.
         */
        String getNodeId();

        /**
         * Get a JDBC connection to the node's SQL state machine.
         * <p>
         * Connection should be closed by caller.
         */
        Connection getConnection() throws SQLException;
    }
}
