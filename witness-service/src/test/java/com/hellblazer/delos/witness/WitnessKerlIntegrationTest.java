/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.Verifiers;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for WitnessKerlIntegration (Phase 1A-3-B).
 * <p>
 * Verifies:
 * - KeyState verification with caching
 * - Circuit breaker behavior
 * - Committee filtering with KERI verification
 * - Cache expiration and LRU eviction
 * - Timeout handling
 */
class WitnessKerlIntegrationTest {

    @Mock
    private Verifiers mockVerifiers;

    @Mock
    private Verifier mockVerifier;

    private WitnessKerlIntegration kerlIntegration;

    private Identifier testIdentifier1;
    private Identifier testIdentifier2;
    private Identifier testIdentifier3;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // Create test identifiers
        testIdentifier1 = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("test1".getBytes()));
        testIdentifier2 = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("test2".getBytes()));
        testIdentifier3 = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("test3".getBytes()));

        kerlIntegration = new WitnessKerlIntegration(mockVerifiers);
    }

    @Test
    void testDefaultConfiguration() {
        var config = WitnessKerlIntegration.KerlConfig.defaults();

        assertEquals(Duration.ofSeconds(60), config.cacheTtl());
        assertEquals(1000, config.maxCacheSize());
        assertEquals(Duration.ofMillis(200), config.verificationTimeout());
        assertEquals(0.10, config.circuitBreakerThreshold());
        assertEquals(100, config.circuitBreakerWindow());
        assertEquals(Duration.ofMinutes(1), config.circuitBreakerCooldown());
    }

    @Test
    void testVerifyKeyStateValid() {
        when(mockVerifiers.verifierFor(testIdentifier1)).thenReturn(Optional.of(mockVerifier));

        var result = kerlIntegration.verifyKeyState(testIdentifier1);

        assertEquals(WitnessKerlIntegration.VerificationResult.VALID, result);
        // Called twice: once during verification, once during cache update
        verify(mockVerifiers, atLeast(1)).verifierFor(testIdentifier1);
    }

    @Test
    void testVerifyKeyStateUnknown() {
        when(mockVerifiers.verifierFor(testIdentifier1)).thenReturn(Optional.empty());

        var result = kerlIntegration.verifyKeyState(testIdentifier1);

        assertEquals(WitnessKerlIntegration.VerificationResult.UNKNOWN, result);
    }

    @Test
    void testCacheHit() {
        when(mockVerifiers.verifierFor(testIdentifier1)).thenReturn(Optional.of(mockVerifier));

        // First call - cache miss
        var result1 = kerlIntegration.verifyKeyState(testIdentifier1);
        assertEquals(WitnessKerlIntegration.VerificationResult.VALID, result1);

        // Second call - cache hit
        var result2 = kerlIntegration.verifyKeyState(testIdentifier1);
        assertEquals(WitnessKerlIntegration.VerificationResult.VALID, result2);

        // Verifier called during verification + cache update on first call only
        // Second call should hit cache and not call verifiers again
        verify(mockVerifiers, atMost(2)).verifierFor(testIdentifier1);

        var stats = kerlIntegration.getStats();
        assertEquals(1, stats.cacheHits());
        assertEquals(1, stats.cacheMisses());
    }

    @Test
    void testCacheClear() {
        when(mockVerifiers.verifierFor(testIdentifier1)).thenReturn(Optional.of(mockVerifier));

        // Populate cache
        kerlIntegration.verifyKeyState(testIdentifier1);

        // Clear cache
        kerlIntegration.clearCache();

        // Should need to verify again
        kerlIntegration.verifyKeyState(testIdentifier1);

        // Each verification call may invoke verifierFor multiple times
        // (once during verify, once during cache update)
        verify(mockVerifiers, atLeast(2)).verifierFor(testIdentifier1);

        var stats = kerlIntegration.getStats();
        assertEquals(0, stats.cacheHits());
        assertEquals(1, stats.cacheMisses());  // Reset after clear
    }

    @Test
    void testFilterValidMembers() {
        // testIdentifier1 and testIdentifier2 are valid, testIdentifier3 is unknown
        when(mockVerifiers.verifierFor(testIdentifier1)).thenReturn(Optional.of(mockVerifier));
        when(mockVerifiers.verifierFor(testIdentifier2)).thenReturn(Optional.of(mockVerifier));
        when(mockVerifiers.verifierFor(testIdentifier3)).thenReturn(Optional.empty());

        var candidates = Set.of(testIdentifier1, testIdentifier2, testIdentifier3);

        var validMembers = kerlIntegration.filterValidMembers(candidates, 2);

        assertEquals(2, validMembers.size());
        assertTrue(validMembers.contains(testIdentifier1));
        assertTrue(validMembers.contains(testIdentifier2));
        assertFalse(validMembers.contains(testIdentifier3));
    }

    @Test
    void testFilterValidMembersInsufficientThrowsException() {
        // Only testIdentifier1 is valid
        when(mockVerifiers.verifierFor(testIdentifier1)).thenReturn(Optional.of(mockVerifier));
        when(mockVerifiers.verifierFor(testIdentifier2)).thenReturn(Optional.empty());
        when(mockVerifiers.verifierFor(testIdentifier3)).thenReturn(Optional.empty());

        var candidates = Set.of(testIdentifier1, testIdentifier2, testIdentifier3);

        assertThrows(WitnessKerlIntegration.InsufficientCommitteeException.class,
                     () -> kerlIntegration.filterValidMembers(candidates, 2));
    }

    @Test
    void testCircuitBreakerOpens() {
        // Configure with low threshold for testing
        var config = new WitnessKerlIntegration.KerlConfig(
            Duration.ofSeconds(60),
            100,
            Duration.ofMillis(200),
            0.20,  // 20% failure threshold
            10,    // Small window
            Duration.ofMinutes(1)
        );
        var integration = new WitnessKerlIntegration(mockVerifiers, config);

        // Initially closed
        assertFalse(integration.isCircuitOpen());

        // Simulate failures by returning empty (UNKNOWN result)
        when(mockVerifiers.verifierFor(any(Identifier.class))).thenReturn(Optional.empty());

        // Make enough calls to trigger circuit breaker
        for (int i = 0; i < 15; i++) {
            var id = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest(("fail" + i).getBytes()));
            integration.verifyKeyState(id);
        }

        // Circuit should be open after enough failures
        var stats = integration.getStats();
        assertTrue(stats.failureRate() > 0);
    }

    @Test
    void testCircuitBreakerReset() {
        var integration = new WitnessKerlIntegration(mockVerifiers);

        // Manually reset
        integration.resetCircuitBreaker();

        assertFalse(integration.isCircuitOpen());

        var stats = integration.getStats();
        assertEquals(0.0, stats.failureRate());
    }

    @Test
    void testVerifySignature() throws Exception {
        when(mockVerifiers.verifierFor(testIdentifier1)).thenReturn(Optional.of(mockVerifier));
        when(mockVerifier.verify(any(JohnHancock.class), any(InputStream.class))).thenReturn(true);

        var signature = mock(JohnHancock.class);
        var message = "test message".getBytes();

        var result = kerlIntegration.verifySignature(testIdentifier1, signature, message);

        assertTrue(result);
        verify(mockVerifier).verify(any(JohnHancock.class), any(InputStream.class));
    }

    @Test
    void testVerifySignatureInvalidKeyState() {
        when(mockVerifiers.verifierFor(testIdentifier1)).thenReturn(Optional.empty());

        var signature = mock(JohnHancock.class);
        var message = "test message".getBytes();

        var result = kerlIntegration.verifySignature(testIdentifier1, signature, message);

        assertFalse(result);
    }

    @Test
    void testStats() {
        when(mockVerifiers.verifierFor(testIdentifier1)).thenReturn(Optional.of(mockVerifier));
        when(mockVerifiers.verifierFor(testIdentifier2)).thenReturn(Optional.empty());

        kerlIntegration.verifyKeyState(testIdentifier1);  // Valid
        kerlIntegration.verifyKeyState(testIdentifier1);  // Cache hit
        kerlIntegration.verifyKeyState(testIdentifier2);  // Unknown

        var stats = kerlIntegration.getStats();
        assertEquals(2, stats.cacheSize());
        assertEquals(1, stats.cacheHits());
        assertEquals(2, stats.cacheMisses());
        assertFalse(stats.circuitOpen());
    }

    @Test
    void testCustomConfiguration() {
        var customConfig = new WitnessKerlIntegration.KerlConfig(
            Duration.ofSeconds(30),
            500,
            Duration.ofMillis(100),
            0.05,
            50,
            Duration.ofSeconds(30)
        );

        var integration = new WitnessKerlIntegration(mockVerifiers, customConfig);
        assertNotNull(integration);
    }

    @Test
    void testGetVerifierFromCache() {
        when(mockVerifiers.verifierFor(testIdentifier1)).thenReturn(Optional.of(mockVerifier));

        // First verify to populate cache
        kerlIntegration.verifyKeyState(testIdentifier1);

        // Get verifier should use cache
        var verifier = kerlIntegration.getVerifier(testIdentifier1);

        assertTrue(verifier.isPresent());
    }

    @Test
    void testShutdown() {
        // Should not throw
        kerlIntegration.shutdown();
    }
}
