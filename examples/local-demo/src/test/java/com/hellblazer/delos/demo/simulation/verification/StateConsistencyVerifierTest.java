/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.verification;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StateConsistencyVerifier.
 *
 * @author hal.hildebrand
 */
public class StateConsistencyVerifierTest {

    @TempDir
    Path tempDir;

    private StateConsistencyVerifier verifier;
    private Path logFile;

    @BeforeEach
    public void setUp() {
        logFile = tempDir.resolve("verification.log");
        verifier = new StateConsistencyVerifier(logFile);
    }

    @AfterEach
    public void tearDown() {
        if (verifier != null) {
            verifier.shutdown();
        }
    }

    @Test
    public void testVerificationResultCreation() {
        var result = VerificationResult.success("TestCheck", Duration.ofMillis(100));

        assertNotNull(result);
        assertEquals("TestCheck", result.checkName());
        assertTrue(result.passed());
        assertEquals(Duration.ofMillis(100), result.executionTime());
        assertTrue(result.details().isEmpty());
    }

    @Test
    public void testVerificationResultWithDetails() {
        var details = List.of("Detail 1", "Detail 2");
        var result = VerificationResult.success("TestCheck", Duration.ofMillis(100), details);

        assertNotNull(result);
        assertEquals("TestCheck", result.checkName());
        assertTrue(result.passed());
        assertEquals(2, result.details().size());
        assertEquals("Detail 1", result.details().get(0));
    }

    @Test
    public void testVerificationResultFailure() {
        var result = VerificationResult.failure("TestCheck", Duration.ofMillis(100), "Test failure");

        assertNotNull(result);
        assertEquals("TestCheck", result.checkName());
        assertFalse(result.passed());
        assertEquals("Test failure", result.message());
    }

    @Test
    public void testVerificationResultTimeout() {
        var result = VerificationResult.timeout("TestCheck", Duration.ofSeconds(5));

        assertNotNull(result);
        assertEquals("TestCheck", result.checkName());
        assertFalse(result.passed());
        assertTrue(result.message().contains("timed out"));
    }

    @Test
    public void testVerificationResultError() {
        var exception = new RuntimeException("Test error");
        var result = VerificationResult.error("TestCheck", Duration.ofMillis(100), exception);

        assertNotNull(result);
        assertEquals("TestCheck", result.checkName());
        assertFalse(result.passed());
        assertTrue(result.message().contains("Test error"));
    }

    @Test
    public void testAddVerificationCheck() {
        var check = new MockVerificationCheck("MockCheck", true);

        verifier.addVerificationCheck(check);

        var passed = verifier.verifyAll();

        assertTrue(passed);
        assertEquals(1, verifier.getVerificationHistory().size());
    }

    @Test
    public void testVerifyAllWithMultipleChecks() {
        verifier.addVerificationCheck(new MockVerificationCheck("Check1", true));
        verifier.addVerificationCheck(new MockVerificationCheck("Check2", true));
        verifier.addVerificationCheck(new MockVerificationCheck("Check3", true));

        var passed = verifier.verifyAll();

        assertTrue(passed);
        assertEquals(3, verifier.getVerificationHistory().size());
    }

    @Test
    public void testVerifyAllWithFailure() {
        verifier.addVerificationCheck(new MockVerificationCheck("PassCheck", true));
        verifier.addVerificationCheck(new MockVerificationCheck("FailCheck", false));

        var passed = verifier.verifyAll();

        assertFalse(passed);
        assertEquals(2, verifier.getVerificationHistory().size());
    }

    @Test
    public void testVerificationHistoryTracking() {
        var check = new MockVerificationCheck("TestCheck", true);
        verifier.addVerificationCheck(check);

        verifier.verifyAll();
        verifier.verifyAll();

        var history = verifier.getVerificationHistory();
        assertEquals(2, history.size());
        assertEquals("TestCheck", history.get(0).checkName());
        assertEquals("TestCheck", history.get(1).checkName());
    }

    @Test
    public void testIsClusterConsistent() {
        verifier.addVerificationCheck(new MockVerificationCheck("Check1", true));

        assertTrue(verifier.isClusterConsistent()); // No verifications yet

        verifier.verifyAll();

        assertTrue(verifier.isClusterConsistent());
    }

    @Test
    public void testIsClusterInconsistent() {
        verifier.addVerificationCheck(new MockVerificationCheck("FailCheck", false));

        verifier.verifyAll();

        assertFalse(verifier.isClusterConsistent());
    }

    @Test
    public void testVerificationLogging() throws Exception {
        verifier.addVerificationCheck(new MockVerificationCheck("TestCheck", true));

        verifier.verifyAll();

        assertTrue(Files.exists(logFile));
        var logContent = Files.readString(logFile);
        assertTrue(logContent.contains("TestCheck"));
        assertTrue(logContent.contains("PASS"));
    }

    @Test
    public void testVerificationTimeout() {
        var slowCheck = new SlowVerificationCheck("SlowCheck", Duration.ofSeconds(10), Duration.ofMillis(100));
        verifier.addVerificationCheck(slowCheck);

        var passed = verifier.verifyAll();

        assertFalse(passed);
        var history = verifier.getVerificationHistory();
        assertEquals(1, history.size());
        assertFalse(history.get(0).passed());
        assertTrue(history.get(0).message().contains("timed out"));
    }

    @Test
    public void testVerificationCheckExecution() {
        var executionCounter = new AtomicInteger(0);
        var check = new VerificationCheck() {
            @Override
            public String getName() {
                return "CounterCheck";
            }

            @Override
            public VerificationResult execute() {
                executionCounter.incrementAndGet();
                return VerificationResult.success(getName(), Duration.ofMillis(10));
            }

            @Override
            public Duration getTimeout() {
                return Duration.ofSeconds(5);
            }
        };

        verifier.addVerificationCheck(check);
        verifier.verifyAll();

        assertEquals(1, executionCounter.get());
    }

    /**
     * Mock verification check for testing.
     */
    private static class MockVerificationCheck implements VerificationCheck {
        private final String name;
        private final boolean shouldPass;

        public MockVerificationCheck(String name, boolean shouldPass) {
            this.name = name;
            this.shouldPass = shouldPass;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public VerificationResult execute() {
            if (shouldPass) {
                return VerificationResult.success(name, Duration.ofMillis(10));
            } else {
                return VerificationResult.failure(name, Duration.ofMillis(10), "Mock failure");
            }
        }

        @Override
        public Duration getTimeout() {
            return Duration.ofSeconds(5);
        }
    }

    /**
     * Slow verification check for timeout testing.
     */
    private static class SlowVerificationCheck implements VerificationCheck {
        private final String name;
        private final Duration sleepDuration;
        private final Duration timeout;

        public SlowVerificationCheck(String name, Duration sleepDuration, Duration timeout) {
            this.name = name;
            this.sleepDuration = sleepDuration;
            this.timeout = timeout;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public VerificationResult execute() {
            try {
                Thread.sleep(sleepDuration.toMillis());
                return VerificationResult.success(name, sleepDuration);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return VerificationResult.error(name, Duration.ZERO, e);
            }
        }

        @Override
        public Duration getTimeout() {
            return timeout;
        }
    }
}
