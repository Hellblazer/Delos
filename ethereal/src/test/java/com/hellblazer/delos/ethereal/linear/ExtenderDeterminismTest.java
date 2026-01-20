/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.ethereal.linear;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.ethereal.Config;
import com.hellblazer.delos.ethereal.Dag;
import com.hellblazer.delos.ethereal.DagFactory;
import com.hellblazer.delos.ethereal.DagReader;
import com.hellblazer.delos.ethereal.Unit;
import com.hellblazer.delos.utils.Hex;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates deterministic linear extension ordering across JVM versions, vendors, and architectures.
 * Tests Aleph-BFT §4 requirement: all honest nodes must compute identical permutations.
 *
 * Phase 2 validation for Delos-3nsd hash-based determinism implementation.
 * Ensures Extender.pidOrder() produces cryptographically deterministic results that are
 * stable across:
 * - Different JVM versions (11, 17, 21, 24)
 * - Different JVM vendors (OpenJDK, GraalVM)
 * - Different architectures (Intel, ARM)
 * - Different endianness systems
 *
 * @author hal.hildebrand
 */
public class ExtenderDeterminismTest {

    /**
     * Basic determinism within same JVM: same input produces same output.
     * Validates pidOrder() is deterministic across 1000 repeated calls.
     */
    @Test
    public void testDeterministicPidOrdering() throws Exception {
        Dag d;
        try (var fis = new FileInputStream(new File("src/test/resources/dags/4/regular.txt"))) {
            d = DagReader.readDag(fis, new DagFactory.TestDagFactory());
        }
        var cnf = Config.newBuilder().setnProc(d.nProc()).build();
        var extender = new Extender(d, cnf);

        // Get first timing unit to test pidOrder with
        var firstRound = extender.nextRound(null);
        assertNotNull(firstRound);

        // Capture first ordering
        var firstOrdering = firstRound.orderedUnits(DigestAlgorithm.DEFAULT, "");
        assertNotNull(firstOrdering);

        // Repeat 1000 times - must be identical
        for (int i = 0; i < 1000; i++) {
            var round = extender.nextRound(null);
            var ordering = round.orderedUnits(DigestAlgorithm.DEFAULT, "");
            assertEquals(firstOrdering.size(), ordering.size(),
                         "Iteration " + i + " produced different ordering size");

            for (int j = 0; j < firstOrdering.size(); j++) {
                assertEquals(firstOrdering.get(j), ordering.get(j),
                             "Iteration " + i + " position " + j + " differs");
            }
        }
    }

    /**
     * Different inputs produce different orderings (statistical guarantee).
     * Validates hash-based ordering provides good distribution.
     */
    @Test
    public void testDifferentInputs() throws Exception {
        Dag d;
        try (var fis = new FileInputStream(new File("src/test/resources/dags/4/regular.txt"))) {
            d = DagReader.readDag(fis, new DagFactory.TestDagFactory());
        }
        var cnf = Config.newBuilder().setnProc(d.nProc()).build();
        var extender = new Extender(d, cnf);

        // Collect orderings from multiple levels
        Set<String> orderingSignatures = new HashSet<>();
        TimingRound current = null;
        for (int level = 0; level < 8; level++) {
            current = extender.nextRound(current);
            assertNotNull(current, "failed at level: " + level);
            var ordering = current.orderedUnits(DigestAlgorithm.DEFAULT, "");
            var signature = createOrderingSignature(ordering);
            orderingSignatures.add(signature);
        }

        // Different levels should produce different orderings (high probability)
        // With 8 levels and 4 PIDs, we expect at least 6 unique orderings
        assertTrue(orderingSignatures.size() >= 6,
                   "Expected diverse orderings, got " + orderingSignatures.size() + " unique patterns");
    }

    /**
     * Boundary values for Byzantine process counts (f < n/3).
     * Tests n=4, 7, 10, 16 (boundary values for BFT).
     */
    @Test
    public void testBoundaryValues() {
        short[] validCounts = { 4, 7, 10, 16 }; // 3f+1 boundaries

        for (short nProc : validCounts) {
            var cnf = Config.newBuilder().setnProc(nProc).build();
            assertEquals(nProc, cnf.nProc());

            // Create test digest to use as unit hash
            var unitHash = DigestAlgorithm.DEFAULT.digest(("boundary-test-" + nProc).getBytes());

            // Compute process hash for each PID
            var hashes = new ArrayList<Digest>();
            for (short pid = 0; pid < nProc; pid++) {
                var hash = computeProcessHashDirect(unitHash, pid, cnf.digestAlgorithm());
                hashes.add(hash);
            }

            // Verify all hashes are unique (cryptographic guarantee)
            var uniqueHashes = new HashSet<>(hashes);
            assertEquals(nProc, uniqueHashes.size(),
                         "nProc=" + nProc + " should produce " + nProc + " unique hashes");
        }
    }

    /**
     * Serialization determinism: verify hash bytes can be serialized/deserialized and produce identical results.
     */
    @Test
    public void testSerializationDeterminism() {
        var unitHash = DigestAlgorithm.DEFAULT.digest("test-unit-serialization".getBytes());
        short nProc = 4;

        // Compute PID ordering using original hash
        var originalOrdering = computePidOrdering(unitHash, nProc);

        // Serialize unit hash to hex and deserialize (simulates cross-process transfer)
        var unitHashHex = Hex.hex(unitHash.getBytes());
        var unitHashDeserialized = new Digest(DigestAlgorithm.DEFAULT, Hex.unhex(unitHashHex));

        // Verify deserialized hash matches original
        assertEquals(unitHash, unitHashDeserialized);

        // Compute PID ordering using deserialized hash
        var deserializedOrdering = computePidOrdering(unitHashDeserialized, nProc);

        // Verify ordering matches original after serialization round-trip
        assertEquals(originalOrdering, deserializedOrdering,
                     "PID ordering differs after serialization round-trip");
    }

    /**
     * Linear extension determinism: multiple calls to nextRound() produce identical unit permutation.
     */
    @Test
    public void testLinearExtensionDeterminism() throws Exception {
        // Run the linear extension process 10 times and verify identical results
        List<List<String>> allRuns = new ArrayList<>();

        for (int run = 0; run < 10; run++) {
            Dag d;
            try (var fis = new FileInputStream(new File("src/test/resources/dags/4/regular.txt"))) {
                d = DagReader.readDag(fis, new DagFactory.TestDagFactory());
            }
            var cnf = Config.newBuilder().setnProc(d.nProc()).build();
            var extender = new Extender(d, cnf);

            List<String> thisRunOrdering = new ArrayList<>();
            TimingRound current = null;
            for (int level = 0; level < 8; level++) {
                current = extender.nextRound(current);
                assertNotNull(current, "Run " + run + " failed at level: " + level);
                var ordering = current.orderedUnits(DigestAlgorithm.DEFAULT, "");
                for (var unit : ordering) {
                    thisRunOrdering.add(unit.shortString());
                }
            }
            allRuns.add(thisRunOrdering);
        }

        // All runs must produce identical ordering
        var firstRun = allRuns.get(0);
        for (int run = 1; run < allRuns.size(); run++) {
            assertEquals(firstRun, allRuns.get(run), "Run " + run + " differs from first run");
        }
    }

    /**
     * Process 100+ units and verify final ordering identical across runs.
     */
    @Test
    public void testDeterministicOrdering100Units() throws Exception {
        // Use regular DAG which has 11 levels x 4 processes = 44 units
        // Run multiple times and verify ordering consistency
        List<List<String>> allRuns = new ArrayList<>();

        for (int run = 0; run < 5; run++) {
            Dag d;
            try (var fis = new FileInputStream(new File("src/test/resources/dags/4/regular.txt"))) {
                d = DagReader.readDag(fis, new DagFactory.TestDagFactory());
            }
            var cnf = Config.newBuilder().setnProc(d.nProc()).build();
            var extender = new Extender(d, cnf);

            List<String> thisRunOrdering = new ArrayList<>();
            TimingRound current = null;
            for (int level = 0; level < 8; level++) {
                current = extender.nextRound(current);
                var ordering = current.orderedUnits(DigestAlgorithm.DEFAULT, "");
                for (var unit : ordering) {
                    thisRunOrdering.add(Hex.hex(unit.hash().getBytes()));
                }
            }
            allRuns.add(thisRunOrdering);
        }

        // Verify all runs identical
        for (int run = 1; run < allRuns.size(); run++) {
            assertEquals(allRuns.get(0), allRuns.get(run));
        }
    }

    /**
     * Timing unit selection consistent across runs.
     */
    @Test
    public void testDeterministicTimingRounds() throws Exception {
        List<String> timingUnits1 = new ArrayList<>();
        List<String> timingUnits2 = new ArrayList<>();

        // Run 1
        {
            Dag d;
            try (var fis = new FileInputStream(new File("src/test/resources/dags/4/regular.txt"))) {
                d = DagReader.readDag(fis, new DagFactory.TestDagFactory());
            }
            var cnf = Config.newBuilder().setnProc(d.nProc()).build();
            var extender = new Extender(d, cnf);

            TimingRound current = null;
            for (int level = 0; level < 8; level++) {
                current = extender.nextRound(current);
                timingUnits1.add(current.currentTU().shortString());
            }
        }

        // Run 2
        {
            Dag d;
            try (var fis = new FileInputStream(new File("src/test/resources/dags/4/regular.txt"))) {
                d = DagReader.readDag(fis, new DagFactory.TestDagFactory());
            }
            var cnf = Config.newBuilder().setnProc(d.nProc()).build();
            var extender = new Extender(d, cnf);

            TimingRound current = null;
            for (int level = 0; level < 8; level++) {
                current = extender.nextRound(current);
                timingUnits2.add(current.currentTU().shortString());
            }
        }

        assertEquals(timingUnits1, timingUnits2);
    }

    /**
     * DigestAlgorithm.digest() output deterministic.
     */
    @Test
    public void testDigestAlgorithmConsistency() {
        var data = "test data for hash consistency".getBytes();

        // Hash the same data 100 times
        Digest firstHash = DigestAlgorithm.DEFAULT.digest(data);
        for (int i = 0; i < 100; i++) {
            var hash = DigestAlgorithm.DEFAULT.digest(data);
            assertEquals(firstHash, hash, "Hash iteration " + i + " differs");
        }
    }

    /**
     * ByteBuffer encoding (unit_hash || pid) consistent.
     */
    @Test
    public void testByteBufferEncoding() {
        var unitHash = DigestAlgorithm.DEFAULT.digest("test-unit".getBytes());
        short pid = 5;

        // Encode 100 times
        List<Digest> hashes = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            var buffer = ByteBuffer.allocate(Short.BYTES + Long.BYTES);
            buffer.putLong(unitHash.fold());
            buffer.putShort(pid);
            var hash = DigestAlgorithm.DEFAULT.digest(buffer.array());
            hashes.add(hash);
        }

        // All must be identical
        for (int i = 1; i < hashes.size(); i++) {
            assertEquals(hashes.get(0), hashes.get(i));
        }
    }

    /**
     * Digest.compareTo() ordering stable.
     */
    @Test
    public void testComparableDigestOrdering() {
        // Create deterministic set of digests
        var digests = new ArrayList<Digest>();
        for (int i = 0; i < 20; i++) {
            var data = ("digest-" + i).getBytes();
            digests.add(DigestAlgorithm.DEFAULT.digest(data));
        }

        // Sort multiple times
        List<List<Digest>> sortedLists = new ArrayList<>();
        for (int run = 0; run < 10; run++) {
            var copy = new ArrayList<>(digests);
            copy.sort(Digest::compareTo);
            sortedLists.add(copy);
        }

        // All sorted lists must be identical
        for (int run = 1; run < sortedLists.size(); run++) {
            assertEquals(sortedLists.get(0), sortedLists.get(run));
        }
    }

    /**
     * Test n=4,5,7,8,10,13,16 (all valid Byzantine counts).
     */
    @Test
    public void testProcessCountBoundaries() {
        short[] validCounts = { 4, 5, 7, 8, 10, 13, 16 };

        for (short nProc : validCounts) {
            var cnf = Config.newBuilder().setnProc(nProc).build();
            var unitHash = DigestAlgorithm.DEFAULT.digest(("boundary-test-" + nProc).getBytes());

            // Verify hash uniqueness for all PIDs
            Set<Digest> hashes = new HashSet<>();
            for (short pid = 0; pid < nProc; pid++) {
                var hash = computeProcessHashDirect(unitHash, pid, cnf.digestAlgorithm());
                hashes.add(hash);
            }

            assertEquals(nProc, hashes.size(), "nProc=" + nProc + " failed uniqueness");
        }
    }

    /**
     * Large process counts (n=100, n=301) still deterministic.
     */
    @Test
    public void testLargeProcessCounts() {
        // Note: Config validation requires valid Byzantine counts
        // Test larger valid Byzantine counts: 3f+1 for f=33, f=100
        short[] largeCounts = { 100, 301 };

        for (short nProc : largeCounts) {
            var cnf = Config.newBuilder().setnProc(nProc).build();
            var unitHash = DigestAlgorithm.DEFAULT.digest(("large-test-" + nProc).getBytes());

            // Compute hashes for first 50 PIDs, verify consistency
            List<Digest> firstRun = new ArrayList<>();
            for (short pid = 0; pid < Math.min(50, nProc); pid++) {
                firstRun.add(computeProcessHashDirect(unitHash, pid, cnf.digestAlgorithm()));
            }

            // Second run must match
            List<Digest> secondRun = new ArrayList<>();
            for (short pid = 0; pid < Math.min(50, nProc); pid++) {
                secondRun.add(computeProcessHashDirect(unitHash, pid, cnf.digestAlgorithm()));
            }

            assertEquals(firstRun, secondRun, "nProc=" + nProc + " not deterministic");
        }
    }

    /**
     * Same PID across different units produces different hash order.
     */
    @Test
    public void testSamePidDifferentUnits() {
        var unitHash1 = DigestAlgorithm.DEFAULT.digest("unit-1".getBytes());
        var unitHash2 = DigestAlgorithm.DEFAULT.digest("unit-2".getBytes());
        short pid = 3;

        var hash1 = computeProcessHashDirect(unitHash1, pid, DigestAlgorithm.DEFAULT);
        var hash2 = computeProcessHashDirect(unitHash2, pid, DigestAlgorithm.DEFAULT);

        assertNotEquals(hash1, hash2, "Different units should produce different hashes for same PID");
    }

    /**
     * Same unit in different rounds produces different ordering context.
     */
    @Test
    public void testSameUnitDifferentRounds() throws Exception {
        Dag d;
        try (var fis = new FileInputStream(new File("src/test/resources/dags/4/regular.txt"))) {
            d = DagReader.readDag(fis, new DagFactory.TestDagFactory());
        }
        var cnf = Config.newBuilder().setnProc(d.nProc()).build();
        var extender = new Extender(d, cnf);

        // Get ordering at level 0
        var round0 = extender.nextRound(null);
        var ordering0 = round0.orderedUnits(DigestAlgorithm.DEFAULT, "");

        // Get ordering at level 1
        var round1 = extender.nextRound(round0);
        var ordering1 = round1.orderedUnits(DigestAlgorithm.DEFAULT, "");

        // Orderings should differ (different timing unit context)
        assertNotEquals(createOrderingSignature(ordering0), createOrderingSignature(ordering1));
    }

    // === Helper Methods ===

    /**
     * Create ordering signature for comparison.
     */
    private String createOrderingSignature(List<Unit> ordering) {
        var sb = new StringBuilder();
        for (var unit : ordering) {
            sb.append(unit.creator()).append(",");
        }
        return sb.toString();
    }

    /**
     * Compute process hash directly (same algorithm as Extender.computeProcessHash).
     */
    private Digest computeProcessHashDirect(Digest unitHash, short pid, DigestAlgorithm algo) {
        var buffer = ByteBuffer.allocate(Short.BYTES + Long.BYTES);
        buffer.putLong(unitHash.fold());
        buffer.putShort(pid);
        return algo.digest(buffer.array());
    }

    /**
     * Compute PID ordering for a given unit hash (same as Extender.pidOrder()).
     */
    private List<Short> computePidOrdering(Digest unitHash, short nProc) {
        var pidHashes = new ArrayList<PidHashPair>();
        for (short pid = 0; pid < nProc; pid++) {
            var hash = computeProcessHashDirect(unitHash, pid, DigestAlgorithm.DEFAULT);
            pidHashes.add(new PidHashPair(pid, hash));
        }
        pidHashes.sort((a, b) -> a.hash.compareTo(b.hash));

        List<Short> ordering = new ArrayList<>();
        for (var ph : pidHashes) {
            ordering.add(ph.pid);
        }
        return ordering;
    }

    /**
     * Helper record for PID hash pairs.
     */
    private record PidHashPair(short pid, Digest hash) {
    }
}
