/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Test coverage for AccumulationResult.Buffered sealed subclass.
 * Tests construction, validation, and integration with AccumulationResult interface.
 *
 * @author hal.hildebrand
 */
class AccumulationResultBufferedTest {

    private static final Identifier TEST_MEMBER = Identifier.NONE;
    private static final int TEST_POSITION = 5;
    private static final long TEST_EPOCH = 100L;

    @Test
    @DisplayName("Valid construction with all fields")
    void validConstruction() {
        var buffered = new AccumulationResult.Buffered(TEST_MEMBER, TEST_POSITION, TEST_EPOCH);

        assertThat(buffered.member()).isEqualTo(TEST_MEMBER);
        assertThat(buffered.bufferPosition()).isEqualTo(TEST_POSITION);
        assertThat(buffered.expectedReplayEpoch()).isEqualTo(TEST_EPOCH);
    }

    @Test
    @DisplayName("Valid construction with zero position")
    void validConstructionZeroPosition() {
        var buffered = new AccumulationResult.Buffered(TEST_MEMBER, 0, TEST_EPOCH);

        assertThat(buffered.bufferPosition()).isZero();
    }

    @Test
    @DisplayName("Valid construction with zero epoch")
    void validConstructionZeroEpoch() {
        var buffered = new AccumulationResult.Buffered(TEST_MEMBER, TEST_POSITION, 0L);

        assertThat(buffered.expectedReplayEpoch()).isZero();
    }

    @Test
    @DisplayName("Null member throws IllegalArgumentException")
    void nullMemberThrows() {
        assertThatThrownBy(() -> new AccumulationResult.Buffered(null, TEST_POSITION, TEST_EPOCH))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("member cannot be null");
    }

    @Test
    @DisplayName("Negative bufferPosition throws IllegalArgumentException")
    void negativePositionThrows() {
        assertThatThrownBy(() -> new AccumulationResult.Buffered(TEST_MEMBER, -1, TEST_EPOCH))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bufferPosition must be >= 0");
    }

    @Test
    @DisplayName("Large bufferPosition is valid")
    void largePositionValid() {
        var buffered = new AccumulationResult.Buffered(TEST_MEMBER, Integer.MAX_VALUE, TEST_EPOCH);

        assertThat(buffered.bufferPosition()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("isSuccess() returns false for Buffered")
    void isSuccessReturnsFalse() {
        var buffered = new AccumulationResult.Buffered(TEST_MEMBER, TEST_POSITION, TEST_EPOCH);

        assertThat(buffered.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("isThresholdMet() returns false for Buffered")
    void isThresholdMetReturnsFalse() {
        var buffered = new AccumulationResult.Buffered(TEST_MEMBER, TEST_POSITION, TEST_EPOCH);

        assertThat(buffered.isThresholdMet()).isFalse();
    }

    @Test
    @DisplayName("isBuffered() returns true for Buffered")
    void isBufferedReturnsTrue() {
        var buffered = new AccumulationResult.Buffered(TEST_MEMBER, TEST_POSITION, TEST_EPOCH);

        assertThat(buffered.isBuffered()).isTrue();
    }

    @Test
    @DisplayName("Can be used in switch expression")
    void canBeUsedInSwitchExpression() {
        AccumulationResult result = new AccumulationResult.Buffered(TEST_MEMBER, TEST_POSITION, TEST_EPOCH);

        var description = switch (result) {
            case AccumulationResult.Accumulated acc -> "accumulated";
            case AccumulationResult.ThresholdMet tm -> "threshold";
            case AccumulationResult.AlreadyPresent ap -> "duplicate";
            case AccumulationResult.InvalidSignature is -> "invalid";
            case AccumulationResult.EpochMismatch em -> "epoch";
            case AccumulationResult.ViewRefMismatch vm -> "viewref";
            case AccumulationResult.LateSigner ls -> "late";
            case AccumulationResult.Buffered buf -> "buffered";
        };

        assertThat(description).isEqualTo("buffered");
    }

    @Test
    @DisplayName("Can be pattern matched")
    void canBePatternMatched() {
        AccumulationResult result = new AccumulationResult.Buffered(TEST_MEMBER, TEST_POSITION, TEST_EPOCH);

        if (result instanceof AccumulationResult.Buffered buffered) {
            assertThat(buffered.member()).isEqualTo(TEST_MEMBER);
            assertThat(buffered.bufferPosition()).isEqualTo(TEST_POSITION);
            assertThat(buffered.expectedReplayEpoch()).isEqualTo(TEST_EPOCH);
        } else {
            fail("Expected Buffered instance");
        }
    }

    @Test
    @DisplayName("toString provides useful debug information")
    void toStringProvidesDebugInfo() {
        var buffered = new AccumulationResult.Buffered(TEST_MEMBER, TEST_POSITION, TEST_EPOCH);

        var str = buffered.toString();

        assertThat(str)
            .contains("Buffered")
            .contains("member=" + TEST_MEMBER)
            .contains("bufferPosition=" + TEST_POSITION)
            .contains("expectedReplayEpoch=" + TEST_EPOCH);
    }

    @Test
    @DisplayName("Record equality works correctly")
    void recordEqualityWorks() {
        var buffered1 = new AccumulationResult.Buffered(TEST_MEMBER, TEST_POSITION, TEST_EPOCH);
        var buffered2 = new AccumulationResult.Buffered(TEST_MEMBER, TEST_POSITION, TEST_EPOCH);
        var buffered3 = new AccumulationResult.Buffered(TEST_MEMBER, TEST_POSITION + 1, TEST_EPOCH);

        assertThat(buffered1).isEqualTo(buffered2);
        assertThat(buffered1).hasSameHashCodeAs(buffered2);
        assertThat(buffered1).isNotEqualTo(buffered3);
    }

    @Test
    @DisplayName("Different members create different instances")
    void differentMembersCreateDifferentInstances() {
        var digest1 = DigestAlgorithm.DEFAULT.digest("member1".getBytes());
        var digest2 = DigestAlgorithm.DEFAULT.digest("member2".getBytes());
        var member1 = new com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier(digest1);
        var member2 = new com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier(digest2);

        var buffered1 = new AccumulationResult.Buffered(member1, TEST_POSITION, TEST_EPOCH);
        var buffered2 = new AccumulationResult.Buffered(member2, TEST_POSITION, TEST_EPOCH);

        assertThat(buffered1).isNotEqualTo(buffered2);
    }

    @Test
    @DisplayName("Different positions create different instances")
    void differentPositionsCreateDifferentInstances() {
        var buffered1 = new AccumulationResult.Buffered(TEST_MEMBER, 1, TEST_EPOCH);
        var buffered2 = new AccumulationResult.Buffered(TEST_MEMBER, 2, TEST_EPOCH);

        assertThat(buffered1).isNotEqualTo(buffered2);
    }

    @Test
    @DisplayName("Different epochs create different instances")
    void differentEpochsCreateDifferentInstances() {
        var buffered1 = new AccumulationResult.Buffered(TEST_MEMBER, TEST_POSITION, 100L);
        var buffered2 = new AccumulationResult.Buffered(TEST_MEMBER, TEST_POSITION, 101L);

        assertThat(buffered1).isNotEqualTo(buffered2);
    }

    @Test
    @DisplayName("Implements AccumulationResult interface")
    void implementsAccumulationResultInterface() {
        AccumulationResult result = new AccumulationResult.Buffered(TEST_MEMBER, TEST_POSITION, TEST_EPOCH);

        assertThat(result).isInstanceOf(AccumulationResult.class);
    }

    @Test
    @DisplayName("Only Buffered returns true for isBuffered()")
    void onlyBufferedReturnsTrue() {
        // Buffered returns true
        AccumulationResult buffered = new AccumulationResult.Buffered(TEST_MEMBER, TEST_POSITION, TEST_EPOCH);
        assertThat(buffered.isBuffered()).isTrue();

        // All others return false
        AccumulationResult accumulated = new AccumulationResult.Accumulated(3, 5);
        assertThat(accumulated.isBuffered()).isFalse();

        AccumulationResult alreadyPresent = new AccumulationResult.AlreadyPresent(TEST_MEMBER);
        assertThat(alreadyPresent.isBuffered()).isFalse();

        AccumulationResult invalidSignature = new AccumulationResult.InvalidSignature(TEST_MEMBER, "test");
        assertThat(invalidSignature.isBuffered()).isFalse();

        AccumulationResult epochMismatch = new AccumulationResult.EpochMismatch(TEST_MEMBER, 1, 2);
        assertThat(epochMismatch.isBuffered()).isFalse();

        AccumulationResult lateSigner = new AccumulationResult.LateSigner(
            TEST_MEMBER, 5, java.time.Instant.now()
        );
        assertThat(lateSigner.isBuffered()).isFalse();
    }

    @Test
    @DisplayName("isSuccess() excludes Buffered but includes success types")
    void isSuccessExcludesBuffered() {
        // Success types
        AccumulationResult accumulated = new AccumulationResult.Accumulated(3, 5);
        assertThat(accumulated.isSuccess()).isTrue();

        // Buffered is NOT success
        AccumulationResult buffered = new AccumulationResult.Buffered(TEST_MEMBER, TEST_POSITION, TEST_EPOCH);
        assertThat(buffered.isSuccess()).isFalse();

        // Failure types
        AccumulationResult invalidSig = new AccumulationResult.InvalidSignature(TEST_MEMBER, "test");
        assertThat(invalidSig.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("Thread safety - multiple threads can create Buffered instances")
    void threadSafety() throws InterruptedException {
        var results = new java.util.concurrent.ConcurrentLinkedQueue<AccumulationResult.Buffered>();
        var threads = new java.util.ArrayList<Thread>();

        for (int i = 0; i < 10; i++) {
            final int position = i;
            var thread = new Thread(() -> {
                var buffered = new AccumulationResult.Buffered(TEST_MEMBER, position, TEST_EPOCH);
                results.add(buffered);
            });
            threads.add(thread);
            thread.start();
        }

        for (var thread : threads) {
            thread.join();
        }

        assertThat(results).hasSize(10);
        assertThat(results).allMatch(r -> r.expectedReplayEpoch() == TEST_EPOCH);
    }
}
