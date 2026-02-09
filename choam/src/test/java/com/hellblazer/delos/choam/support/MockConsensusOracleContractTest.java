/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract tests for MockConsensusOracle to ensure it satisfies the ConsensusOracle interface contract.
 *
 * @author hal.hildebrand
 */
@DisplayName("MockConsensusOracle Contract Tests")
class MockConsensusOracleContractTest {
    private MockConsensusOracle oracle;

    @BeforeEach
    void setUp() {
        oracle = new MockConsensusOracle();
    }

    @Test
    @DisplayName("initial state is INITIAL")
    void initialState_IsInitial() {
        assertThat(oracle.getState()).isEqualTo(MockConsensusOracle.State.INITIAL);
        assertThat(oracle.isStarted()).isFalse();
        assertThat(oracle.isStopped()).isFalse();
    }

    @Test
    @DisplayName("start transitions to STARTED state")
    void start_TransitionsToStarted() {
        var duration = Duration.ofMillis(100);

        oracle.start(duration);

        assertThat(oracle.getState()).isEqualTo(MockConsensusOracle.State.STARTED);
        assertThat(oracle.isStarted()).isTrue();
        assertThat(oracle.getLastGossipDuration()).isEqualTo(duration);
    }

    @Test
    @DisplayName("start is idempotent when already started")
    void start_IsIdempotent_WhenAlreadyStarted() {
        var duration1 = Duration.ofMillis(100);
        var duration2 = Duration.ofMillis(200);

        oracle.start(duration1);
        oracle.start(duration2);  // Second call should be no-op

        assertThat(oracle.getState()).isEqualTo(MockConsensusOracle.State.STARTED);
        assertThat(oracle.getLastGossipDuration()).isEqualTo(duration1);  // Unchanged
    }

    @Test
    @DisplayName("start throws IllegalStateException when stopped")
    void start_Throws_WhenStopped() {
        oracle.start(Duration.ofMillis(100));
        oracle.stop();

        assertThatThrownBy(() -> oracle.start(Duration.ofMillis(100)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot restart a stopped oracle");
    }

    @Test
    @DisplayName("stop transitions to STOPPED state")
    void stop_TransitionsToStopped() {
        oracle.start(Duration.ofMillis(100));
        oracle.stop();

        assertThat(oracle.getState()).isEqualTo(MockConsensusOracle.State.STOPPED);
        assertThat(oracle.isStarted()).isFalse();
        assertThat(oracle.isStopped()).isTrue();
    }

    @Test
    @DisplayName("stop is idempotent")
    void stop_IsIdempotent() {
        oracle.start(Duration.ofMillis(100));
        oracle.stop();
        oracle.stop();  // Second call should be no-op

        assertThat(oracle.getState()).isEqualTo(MockConsensusOracle.State.STOPPED);
    }

    @Test
    @DisplayName("stop is no-op when not started")
    void stop_IsNoOp_WhenNotStarted() {
        oracle.stop();

        assertThat(oracle.getState()).isEqualTo(MockConsensusOracle.State.INITIAL);
    }

    @Test
    @DisplayName("completeIt sets completed flag")
    void completeIt_SetsCompletedFlag() {
        oracle.start(Duration.ofMillis(100));
        oracle.completeIt();

        assertThat(oracle.isCompleted()).isTrue();
        assertThat(oracle.getState()).isEqualTo(MockConsensusOracle.State.COMPLETED);
    }

    @Test
    @DisplayName("completeIt can be called before start")
    void completeIt_CanBeCalledBeforeStart() {
        oracle.completeIt();

        assertThat(oracle.isCompleted()).isTrue();
        // State may remain INITIAL since we weren't STARTED
    }

    @Test
    @DisplayName("processor returns configured stub")
    void processor_ReturnsConfiguredStub() {
        var defaultProcessor = oracle.processor();
        assertThat(defaultProcessor).isNotNull();

        var customProcessor = new Object();
        oracle.setStubProcessor(customProcessor);

        assertThat(oracle.processor()).isSameAs(customProcessor);
    }

    @Test
    @DisplayName("reset allows reuse (test-only feature)")
    void reset_AllowsReuse() {
        oracle.start(Duration.ofMillis(100));
        oracle.completeIt();

        oracle.reset();

        assertThat(oracle.getState()).isEqualTo(MockConsensusOracle.State.INITIAL);
        assertThat(oracle.isCompleted()).isFalse();
        assertThat(oracle.getLastGossipDuration()).isNull();
    }

    @Test
    @DisplayName("thread safety: concurrent start and stop")
    void threadSafety_ConcurrentStartStop() throws InterruptedException {
        var threads = new Thread[10];

        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                oracle.start(Duration.ofMillis(100));
                oracle.stop();
            });
            threads[i].start();
        }

        for (var thread : threads) {
            thread.join();
        }

        // Should end in a consistent terminal state
        assertThat(oracle.isStopped()).isTrue();
    }

    @Test
    @DisplayName("lifecycle sequence: initial -> started -> completed")
    void lifecycle_InitialToStartedToCompleted() {
        assertThat(oracle.getState()).isEqualTo(MockConsensusOracle.State.INITIAL);

        oracle.start(Duration.ofMillis(100));
        assertThat(oracle.getState()).isEqualTo(MockConsensusOracle.State.STARTED);

        oracle.completeIt();
        assertThat(oracle.getState()).isEqualTo(MockConsensusOracle.State.COMPLETED);
        assertThat(oracle.isStopped()).isTrue();
    }

    @Test
    @DisplayName("lifecycle sequence: initial -> started -> stopped")
    void lifecycle_InitialToStartedToStopped() {
        assertThat(oracle.getState()).isEqualTo(MockConsensusOracle.State.INITIAL);

        oracle.start(Duration.ofMillis(100));
        assertThat(oracle.getState()).isEqualTo(MockConsensusOracle.State.STARTED);

        oracle.stop();
        assertThat(oracle.getState()).isEqualTo(MockConsensusOracle.State.STOPPED);
        assertThat(oracle.isStopped()).isTrue();
    }
}
