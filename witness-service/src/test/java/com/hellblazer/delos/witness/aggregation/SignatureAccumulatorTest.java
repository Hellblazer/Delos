/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation;

import org.joou.ULong;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.cryptography.bls.BLSTestFixtures;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * TDD tests for SignatureAccumulator (Phase 1B-2-A RED phase).
 * <p>
 * Test Coverage:
 * - Construction validation
 * - Single-threaded accumulation
 * - Thread-safe concurrent accumulation
 * - Snapshot immutability
 * - Threshold detection
 *
 * @author hal.hildebrand
 */
class SignatureAccumulatorTest {
    private static final DigestAlgorithm ALGORITHM = DigestAlgorithm.DEFAULT;
    private EventCoordinates testEvent;
    private List<Identifier> testMembers;
    private List<BLSSignature> testSignatures;

    @BeforeEach
    void setUp() {
        // Create test event
        var identifier = new SelfAddressingIdentifier(ALGORITHM.digest("test-identifier".getBytes()));
        var digest = ALGORITHM.digest("test-event".getBytes());
        var ilk = "icp";
        testEvent = new EventCoordinates(identifier, ULong.valueOf(1), digest, ilk);

        // Create test members and signatures
        testMembers = new ArrayList<>();
        testSignatures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            var memberDigest = ALGORITHM.digest(("member-" + i).getBytes());
            testMembers.add(new SelfAddressingIdentifier(memberDigest));
            testSignatures.add(new BLSSignature(BLSTestFixtures.randomMessage(96)));
        }
    }

    // ========== Construction Tests ==========

    @Test
    void constructorRejectsNullEvent() {
        assertThatThrownBy(() -> new SignatureAccumulator(null, 3, 0))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("event cannot be null");
    }

    @Test
    void constructorRejectsInvalidThreshold() {
        assertThatThrownBy(() -> new SignatureAccumulator(testEvent, 0, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("threshold must be >= 1");

        assertThatThrownBy(() -> new SignatureAccumulator(testEvent, -1, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("threshold must be >= 1");
    }

    @Test
    void constructorRejectsNegativeEpoch() {
        assertThatThrownBy(() -> new SignatureAccumulator(testEvent, 3, -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("epoch must be >= 0");
    }

    @Test
    void constructorAcceptsValidParameters() {
        var accumulator = new SignatureAccumulator(testEvent, 3, 0);

        assertThat(accumulator.getEvent()).isEqualTo(testEvent);
        assertThat(accumulator.getThreshold()).isEqualTo(3);
        assertThat(accumulator.getEpoch()).isEqualTo(0);
        assertThat(accumulator.signerCount()).isEqualTo(0);
        assertThat(accumulator.isThresholdMet()).isFalse();
    }

    // ========== Single Accumulation Tests ==========

    @Test
    void accumulateRejectsNullMember() {
        var accumulator = new SignatureAccumulator(testEvent, 3, 0);

        assertThatThrownBy(() -> accumulator.accumulate(null, 0, testSignatures.get(0)))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("member cannot be null");
    }

    @Test
    void accumulateRejectsNullSignature() {
        var accumulator = new SignatureAccumulator(testEvent, 3, 0);

        assertThatThrownBy(() -> accumulator.accumulate(testMembers.get(0), 0, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("signature cannot be null");
    }

    @Test
    void accumulateRejectsNegativeCommitteeIndex() {
        var accumulator = new SignatureAccumulator(testEvent, 3, 0);

        assertThatThrownBy(() -> accumulator.accumulate(testMembers.get(0), -1, testSignatures.get(0)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("committeeIndex must be >= 0");
    }

    @Test
    void accumulateFirstSignatureReturnsAccumulated() {
        var accumulator = new SignatureAccumulator(testEvent, 3, 0);

        var result = accumulator.accumulate(testMembers.get(0), 0, testSignatures.get(0));

        assertThat(result).isInstanceOf(AccumulationResult.Accumulated.class);
        var accumulated = (AccumulationResult.Accumulated) result;
        assertThat(accumulated.currentCount()).isEqualTo(1);
        assertThat(accumulated.threshold()).isEqualTo(3);
        assertThat(accumulated.progress()).isCloseTo(1.0 / 3.0, within(0.001));
        assertThat(accumulated.remaining()).isEqualTo(2);

        assertThat(accumulator.signerCount()).isEqualTo(1);
        assertThat(accumulator.isThresholdMet()).isFalse();
    }

    @Test
    void accumulateDuplicateMemberReturnsAlreadyPresent() {
        var accumulator = new SignatureAccumulator(testEvent, 3, 0);

        // Add first signature
        accumulator.accumulate(testMembers.get(0), 0, testSignatures.get(0));

        // Try to add from same member again
        var result = accumulator.accumulate(testMembers.get(0), 0, testSignatures.get(1));

        assertThat(result).isInstanceOf(AccumulationResult.AlreadyPresent.class);
        var alreadyPresent = (AccumulationResult.AlreadyPresent) result;
        assertThat(alreadyPresent.member()).isEqualTo(testMembers.get(0));

        // Count should remain 1
        assertThat(accumulator.signerCount()).isEqualTo(1);
    }

    @Test
    void accumulateThresholdSignatureReturnsThresholdMet() {
        var accumulator = new SignatureAccumulator(testEvent, 3, 0);

        // Add signatures 1 and 2
        accumulator.accumulate(testMembers.get(0), 0, testSignatures.get(0));
        accumulator.accumulate(testMembers.get(1), 1, testSignatures.get(1));

        // Add third signature - should trigger threshold
        var result = accumulator.accumulate(testMembers.get(2), 2, testSignatures.get(2));

        assertThat(result).isInstanceOf(AccumulationResult.ThresholdMet.class);
        var thresholdMet = (AccumulationResult.ThresholdMet) result;
        assertThat(thresholdMet.count()).isEqualTo(3);
        assertThat(thresholdMet.snapshot()).isNotNull();
        assertThat(thresholdMet.snapshot().signerCount()).isEqualTo(3);

        assertThat(accumulator.isThresholdMet()).isTrue();
        assertThat(accumulator.getThresholdSnapshot()).isPresent();
    }

    @Test
    void accumulateBeyondThresholdStillReturnsAccumulated() {
        var accumulator = new SignatureAccumulator(testEvent, 3, 0);

        // Reach threshold
        accumulator.accumulate(testMembers.get(0), 0, testSignatures.get(0));
        accumulator.accumulate(testMembers.get(1), 1, testSignatures.get(1));
        accumulator.accumulate(testMembers.get(2), 2, testSignatures.get(2));

        // Add fourth signature beyond threshold
        var result = accumulator.accumulate(testMembers.get(3), 3, testSignatures.get(3));

        assertThat(result).isInstanceOf(AccumulationResult.Accumulated.class);
        assertThat(accumulator.signerCount()).isEqualTo(4);
        assertThat(accumulator.isThresholdMet()).isTrue();
    }

    // ========== Snapshot Tests ==========

    @Test
    void snapshotReturnsImmutableState() {
        var accumulator = new SignatureAccumulator(testEvent, 3, 0);

        accumulator.accumulate(testMembers.get(0), 0, testSignatures.get(0));
        accumulator.accumulate(testMembers.get(1), 1, testSignatures.get(1));

        var snapshot = accumulator.snapshot();

        assertThat(snapshot.event()).isEqualTo(testEvent);
        assertThat(snapshot.signerCount()).isEqualTo(2);
        assertThat(snapshot.threshold()).isEqualTo(3);
        assertThat(snapshot.thresholdMet()).isFalse();
        assertThat(snapshot.signerIndices()).containsExactly(0, 1);
    }

    @Test
    void snapshotIsImmutableAfterAccumulatorChanges() {
        var accumulator = new SignatureAccumulator(testEvent, 3, 0);

        accumulator.accumulate(testMembers.get(0), 0, testSignatures.get(0));
        var snapshot1 = accumulator.snapshot();

        // Add more signatures
        accumulator.accumulate(testMembers.get(1), 1, testSignatures.get(1));
        accumulator.accumulate(testMembers.get(2), 2, testSignatures.get(2));

        // Snapshot1 should still reflect old state
        assertThat(snapshot1.signerCount()).isEqualTo(1);
        assertThat(accumulator.signerCount()).isEqualTo(3);
    }

    @Test
    void snapshotSignerIndicesAreSorted() {
        var accumulator = new SignatureAccumulator(testEvent, 5, 0);

        // Add in non-sequential order
        accumulator.accumulate(testMembers.get(5), 5, testSignatures.get(5));
        accumulator.accumulate(testMembers.get(1), 1, testSignatures.get(1));
        accumulator.accumulate(testMembers.get(3), 3, testSignatures.get(3));

        var snapshot = accumulator.snapshot();

        assertThat(snapshot.signerIndices()).containsExactly(1, 3, 5); // Sorted
    }

    @Test
    void snapshotSignatureListMatchesIndicesOrder() {
        var accumulator = new SignatureAccumulator(testEvent, 5, 0);

        // Add in non-sequential order
        accumulator.accumulate(testMembers.get(5), 5, testSignatures.get(5));
        accumulator.accumulate(testMembers.get(1), 1, testSignatures.get(1));
        accumulator.accumulate(testMembers.get(3), 3, testSignatures.get(3));

        var snapshot = accumulator.snapshot();

        assertThat(snapshot.signatureList()).containsExactly(
            testSignatures.get(1),
            testSignatures.get(3),
            testSignatures.get(5)
        );
    }

    @Test
    void getThresholdSnapshotIsEmptyBeforeThreshold() {
        var accumulator = new SignatureAccumulator(testEvent, 3, 0);

        accumulator.accumulate(testMembers.get(0), 0, testSignatures.get(0));

        assertThat(accumulator.getThresholdSnapshot()).isEmpty();
    }

    @Test
    void getThresholdSnapshotIsPresentAfterThreshold() {
        var accumulator = new SignatureAccumulator(testEvent, 3, 0);

        accumulator.accumulate(testMembers.get(0), 0, testSignatures.get(0));
        accumulator.accumulate(testMembers.get(1), 1, testSignatures.get(1));
        accumulator.accumulate(testMembers.get(2), 2, testSignatures.get(2));

        var snapshot = accumulator.getThresholdSnapshot();
        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().signerCount()).isEqualTo(3);
        assertThat(snapshot.get().thresholdMet()).isTrue();
    }

    // ========== Concurrency Tests ==========

    @Test
    void concurrentAccumulationIsSafe() throws Exception {
        var accumulator = new SignatureAccumulator(testEvent, 5, 0);
        var executor = Executors.newFixedThreadPool(10);
        var latch = new CountDownLatch(10);

        // Submit 10 concurrent accumulations (indices 0-9)
        for (int i = 0; i < 10; i++) {
            final var index = i;
            executor.submit(() -> {
                try {
                    accumulator.accumulate(
                        testMembers.get(index),
                        index,
                        testSignatures.get(index)
                    );
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(accumulator.signerCount()).isEqualTo(10);
        assertThat(accumulator.isThresholdMet()).isTrue();
    }

    @Test
    void onlyOneThreadReceivesThresholdMet() throws Exception {
        var accumulator = new SignatureAccumulator(testEvent, 3, 0);
        var executor = Executors.newFixedThreadPool(5);
        var latch = new CountDownLatch(5);
        var thresholdMetCount = new AtomicInteger(0);

        // Submit 5 concurrent accumulations, threshold is 3
        for (int i = 0; i < 5; i++) {
            final var index = i;
            executor.submit(() -> {
                try {
                    var result = accumulator.accumulate(
                        testMembers.get(index),
                        index,
                        testSignatures.get(index)
                    );
                    if (result.isThresholdMet()) {
                        thresholdMetCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Exactly one thread should have received ThresholdMet
        assertThat(thresholdMetCount.get()).isEqualTo(1);
        assertThat(accumulator.getThresholdSnapshot()).isPresent();
    }

    @Test
    void concurrentDuplicatesAreRejectedCorrectly() throws Exception {
        var accumulator = new SignatureAccumulator(testEvent, 5, 0);
        var executor = Executors.newFixedThreadPool(10);
        var latch = new CountDownLatch(10);
        var duplicateCount = new AtomicInteger(0);

        // Submit 10 threads trying to add the same member
        for (int i = 0; i < 10; i++) {
            executor.submit(() -> {
                try {
                    var result = accumulator.accumulate(
                        testMembers.get(0), // Same member
                        0,
                        testSignatures.get(0)
                    );
                    if (result instanceof AccumulationResult.AlreadyPresent) {
                        duplicateCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Exactly one thread succeeds, 9 get duplicates
        assertThat(accumulator.signerCount()).isEqualTo(1);
        assertThat(duplicateCount.get()).isEqualTo(9);
    }

    @Test
    void concurrentSnapshotsAreConsistent() throws Exception {
        var accumulator = new SignatureAccumulator(testEvent, 5, 0);
        var executor = Executors.newFixedThreadPool(10);
        var latch = new CountDownLatch(10);
        var snapshots = new ArrayList<SignatureAccumulator.Snapshot>();

        // Add some signatures
        for (int i = 0; i < 3; i++) {
            accumulator.accumulate(testMembers.get(i), i, testSignatures.get(i));
        }

        // Concurrent snapshot reads
        for (int i = 0; i < 10; i++) {
            executor.submit(() -> {
                try {
                    synchronized (snapshots) {
                        snapshots.add(accumulator.snapshot());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // All snapshots should have consistent state
        assertThat(snapshots).hasSize(10);
        assertThat(snapshots).allMatch(s -> s.signerCount() == 3);
    }

    // ========== Edge Cases ==========

    @Test
    void thresholdOfOneIsMetImmediately() {
        var accumulator = new SignatureAccumulator(testEvent, 1, 0);

        var result = accumulator.accumulate(testMembers.get(0), 0, testSignatures.get(0));

        assertThat(result).isInstanceOf(AccumulationResult.ThresholdMet.class);
        assertThat(accumulator.isThresholdMet()).isTrue();
        assertThat(accumulator.signerCount()).isEqualTo(1);
    }

    @Test
    void largeCommitteeIndexIsAccepted() {
        var accumulator = new SignatureAccumulator(testEvent, 3, 0);

        var result = accumulator.accumulate(testMembers.get(0), 255, testSignatures.get(0));

        assertThat(result).isInstanceOf(AccumulationResult.Accumulated.class);
        var snapshot = accumulator.snapshot();
        assertThat(snapshot.signerIndices()).containsExactly(255);
    }

    @Test
    void accumulationResultHelpersWork() {
        var accumulated = new AccumulationResult.Accumulated(2, 5);
        var thresholdMet = new AccumulationResult.ThresholdMet(
            5,
            new SignatureAccumulator(testEvent, 5, 0).snapshot()
        );
        var alreadyPresent = new AccumulationResult.AlreadyPresent(testMembers.get(0));
        var invalid = new AccumulationResult.InvalidSignature(testMembers.get(0), "test");

        assertThat(accumulated.isSuccess()).isTrue();
        assertThat(accumulated.isThresholdMet()).isFalse();

        assertThat(thresholdMet.isSuccess()).isTrue();
        assertThat(thresholdMet.isThresholdMet()).isTrue();

        assertThat(alreadyPresent.isSuccess()).isFalse();
        assertThat(alreadyPresent.isThresholdMet()).isFalse();

        assertThat(invalid.isSuccess()).isFalse();
        assertThat(invalid.isThresholdMet()).isFalse();
    }
}
