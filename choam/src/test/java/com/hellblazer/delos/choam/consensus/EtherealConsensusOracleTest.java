/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.consensus;

import com.hellblazer.delos.ethereal.Ethereal;
import com.hellblazer.delos.ethereal.memberships.ChRbcGossip;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EtherealConsensusOracle adapter.
 * Validates lifecycle management, delegation, and thread safety.
 *
 * @author hal.hildebrand
 */
class EtherealConsensusOracleTest {

    @Mock
    private Ethereal ethereal;

    @Mock
    private ChRbcGossip gossip;

    private EtherealConsensusOracle oracle;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        oracle = new EtherealConsensusOracle(ethereal, gossip);
    }

    @Test
    void testStart() {
        var duration = Duration.ofSeconds(1);
        oracle.start(duration);

        verify(ethereal).start();
        verify(gossip).start(duration);
    }

    @Test
    void testStartIdempotent() {
        var duration = Duration.ofSeconds(1);
        oracle.start(duration);
        oracle.start(duration); // Second call should be no-op

        // Verify start called exactly once on each component
        verify(ethereal, times(1)).start();
        verify(gossip, times(1)).start(duration);
    }

    @Test
    void testStop() {
        var duration = Duration.ofSeconds(1);
        oracle.start(duration);  // Must start before stopping
        oracle.stop();

        // Verify stopped in reverse order (gossip before ethereal)
        var inOrder = inOrder(gossip, ethereal);
        inOrder.verify(gossip).stop();
        inOrder.verify(ethereal).stop();
    }

    @Test
    void testStopIdempotent() {
        var duration = Duration.ofSeconds(1);
        oracle.start(duration);  // Must start before stopping
        oracle.stop();
        oracle.stop(); // Second call should be no-op

        // Verify stop called exactly once on each component
        verify(gossip, times(1)).stop();
        verify(ethereal, times(1)).stop();
    }

    @Test
    void testCompleteIt() {
        oracle.completeIt();

        verify(ethereal).completeIt();
        verifyNoInteractions(gossip); // completeIt only affects ethereal
    }

    @Test
    void testProcessor() {
        oracle.processor();

        // Verify delegation to ethereal.processor()
        verify(ethereal).processor();
    }

    @Test
    void testStartStopSequence() {
        var duration = Duration.ofSeconds(1);

        // Start
        oracle.start(duration);
        verify(ethereal).start();
        verify(gossip).start(duration);

        // Stop
        oracle.stop();
        verify(ethereal).stop();
        verify(gossip).stop();

        // Verify order: start ethereal, start gossip, stop gossip, stop ethereal
        var inOrder = inOrder(ethereal, gossip);
        inOrder.verify(ethereal).start();
        inOrder.verify(gossip).start(duration);
        inOrder.verify(gossip).stop();
        inOrder.verify(ethereal).stop();
    }

    @Test
    void testThreadSafety() throws InterruptedException {
        var duration = Duration.ofSeconds(1);

        // Simulate concurrent start calls from multiple threads
        var thread1 = new Thread(() -> oracle.start(duration));
        var thread2 = new Thread(() -> oracle.start(duration));

        thread1.start();
        thread2.start();

        thread1.join();
        thread2.join();

        // Despite concurrent calls, start should only be called once
        verify(ethereal, times(1)).start();
        verify(gossip, times(1)).start(duration);
    }

    @Test
    void testNullEthereal() {
        assertThatThrownBy(() -> new EtherealConsensusOracle(null, gossip))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("ethereal cannot be null");
    }

    @Test
    void testNullGossip() {
        assertThatThrownBy(() -> new EtherealConsensusOracle(ethereal, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("gossip cannot be null");
    }

    @Test
    void testRestartAfterStop() {
        var duration = Duration.ofSeconds(1);

        // Start then stop
        oracle.start(duration);
        oracle.stop();

        // Try to start again - should be no-op (can't restart)
        oracle.start(duration);

        // Verify start and stop called exactly once
        verify(ethereal, times(1)).start();
        verify(gossip, times(1)).start(duration);
        verify(ethereal, times(1)).stop();
        verify(gossip, times(1)).stop();
    }

    @Test
    void testExceptionDuringStart() {
        var duration = Duration.ofSeconds(1);

        // Simulate gossip.start() throwing exception
        doThrow(new RuntimeException("Gossip start failed")).when(gossip).start(duration);

        assertThatThrownBy(() -> oracle.start(duration))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Gossip start failed");

        // Verify ethereal.stop() was called for cleanup
        verify(ethereal).start();
        verify(ethereal).stop();
        verify(gossip).start(duration);
    }
}
