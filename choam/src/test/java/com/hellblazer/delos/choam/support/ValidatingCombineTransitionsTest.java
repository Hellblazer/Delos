/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import com.chiralbehaviors.tron.Fsm;
import com.hellblazer.delos.choam.FeatureFlags;
import com.hellblazer.delos.choam.fsm.Combine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.function.Supplier;

import static com.hellblazer.delos.choam.fsm.Combine.Mercantile.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for ValidatingCombineTransitions decorator.
 * Verifies that decorator correctly wraps transition methods with validation.
 *
 * @author hal.hildebrand
 */
public class ValidatingCombineTransitionsTest {

    private Combine.Transitions mockDelegate;
    private StateTransitionValidator validator;
    private SimpleMeterRegistry metrics;
    private CHOAMStateSnapshot currentSnapshot;
    private java.util.concurrent.atomic.AtomicInteger snapshotCallCount;
    private Supplier<CHOAMStateSnapshot> snapshotSupplier;
    private ValidatingCombineTransitions validatingTransitions;

    private static final String VALIDATION_MODE_PROPERTY = "feature.state.validation.mode";

    @AfterEach
    public void tearDown() {
        // Clean up the validation mode system property after each test
        System.clearProperty(VALIDATION_MODE_PROPERTY);
    }

    @BeforeEach
    public void setup() {
        mockDelegate = mock(Combine.Transitions.class);
        var matrix = StateTransitionMatrix.getInstance();
        metrics = new SimpleMeterRegistry();
        validator = new StateTransitionValidator(matrix, metrics);
        snapshotCallCount = new java.util.concurrent.atomic.AtomicInteger(0);

        // Create initial snapshot in INITIAL state
        currentSnapshot = new CHOAMStateSnapshot(
            false, false,  // not started
            false, null,   // no committee
            false, false, -1,  // no genesis
            false, -1, 0,  // no view
            0, false, false,
            "INITIAL"
        );

        // Supplier that returns currentSnapshot
        snapshotSupplier = () -> {
            snapshotCallCount.incrementAndGet();
            return currentSnapshot;
        };

        validatingTransitions = new ValidatingCombineTransitions(
            mockDelegate,
            validator,
            snapshotSupplier
        );
    }

    @Test
    public void testStartTransition_ValidPrecondition() {
        // Setup snapshot queue: pre-snapshot (INITIAL) then post-snapshot (RECOVERING)
        var preSnapshot = new CHOAMStateSnapshot(
            false, false,  // not started
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );

        var postSnapshot = new CHOAMStateSnapshot(
            true, false,  // started after transition
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "RECOVERING"
        );

        var snapshots = new java.util.ArrayDeque<>(java.util.List.of(preSnapshot, postSnapshot));
        var queuedSupplier = (Supplier<CHOAMStateSnapshot>) snapshots::poll;

        var validating = new ValidatingCombineTransitions(mockDelegate, validator, queuedSupplier);

        // Mock delegate to return RECOVERING
        when(mockDelegate.start()).thenReturn(RECOVERING);

        // Execute transition
        validating.start();

        // Verify delegate was called
        verify(mockDelegate, times(1)).start();

        // Verify no violations recorded
        assertEquals(0, metrics.counter("validation.violations").count());
    }

    @Test
    public void testStartTransition_InvalidPrecondition() {
        // Set snapshot to already started (invalid for INITIAL→start)
        currentSnapshot = new CHOAMStateSnapshot(
            true, false,  // already started - VIOLATES PRECONDITION
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );

        when(mockDelegate.start()).thenReturn(RECOVERING);

        // Execute transition
        validatingTransitions.start();

        // Verify delegate was still called (log-only mode)
        verify(mockDelegate, times(1)).start();

        // Verify violation was recorded
        assertEquals(1, metrics.counter("validation.violations").count());
        assertEquals(1, metrics.counter("validation.precondition.violations").count());
    }

    @Test
    public void testCombineTransition_Loopback() {
        // Set snapshot to OPERATIONAL (valid for combine)
        currentSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            true, true, 100,
            true, 95, 2,
            5, false, false,
            "OPERATIONAL"
        );

        // Mock delegate to return null (loopback)
        when(mockDelegate.combine()).thenReturn(null);

        var result = validatingTransitions.combine();

        assertNull(result);  // Loopback returns null
        verify(mockDelegate, times(1)).combine();

        // Should have no violations for valid loopback
        assertEquals(0, metrics.counter("validation.violations").count());
    }

    @Test
    public void testBootstrapTransition_WithParameter() {
        // Setup snapshot queue: pre (RECOVERING) then post (BOOTSTRAPPING)
        var preSnapshot = new CHOAMStateSnapshot(
            true, false,
            false, null,  // no committee yet
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "RECOVERING"
        );

        var postSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "GenesisFormation",  // committee created
            false, false, -1,
            false, -1, 0,
            0, true, false,
            "BOOTSTRAPPING"
        );

        var snapshots = new java.util.ArrayDeque<>(java.util.List.of(preSnapshot, postSnapshot));
        var queuedSupplier = (Supplier<CHOAMStateSnapshot>) snapshots::poll;

        var validating = new ValidatingCombineTransitions(mockDelegate, validator, queuedSupplier);

        var mockAnchor = mock(HashedCertifiedBlock.class);
        when(mockDelegate.bootstrap(mockAnchor)).thenReturn(BOOTSTRAPPING);

        // Execute transition
        var result = validating.bootstrap(mockAnchor);

        assertEquals(BOOTSTRAPPING, result);
        verify(mockDelegate, times(1)).bootstrap(mockAnchor);

        // Should validate successfully
        assertEquals(0, metrics.counter("validation.violations").count());
    }

    @Test
    public void testFailTransition() {
        // fail() can be called from any state
        currentSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            true, true, 100,
            true, 95, 2,
            5, false, false,
            "OPERATIONAL"
        );

        when(mockDelegate.fail()).thenReturn(PROTOCOL_FAILURE);

        var result = validatingTransitions.fail();

        assertEquals(PROTOCOL_FAILURE, result);
        verify(mockDelegate, times(1)).fail();
    }

    @Test
    public void testNextViewTransition_DefaultLoopback() {
        // OPERATIONAL has default nextView (returns null, loopback)
        currentSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            true, true, 100,
            true, 95, 2,
            5, false, false,
            "OPERATIONAL"
        );

        when(mockDelegate.nextView()).thenReturn(null);

        var result = validatingTransitions.nextView();

        assertNull(result);
        verify(mockDelegate, times(1)).nextView();
        assertEquals(0, metrics.counter("validation.violations").count());
    }

    @Test
    public void testRotateViewKeysTransition() {
        // OPERATIONAL→rotateViewKeys is loopback
        currentSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            true, true, 100,
            true, 95, 2,
            5, false, false,
            "OPERATIONAL"
        );

        when(mockDelegate.rotateViewKeys()).thenReturn(null);

        var result = validatingTransitions.rotateViewKeys();

        assertNull(result);
        verify(mockDelegate, times(1)).rotateViewKeys();
    }

    @Test
    public void testContextDelegation() {
        var mockContext = mock(Combine.class);
        when(mockDelegate.context()).thenReturn(mockContext);

        var result = validatingTransitions.context();

        assertEquals(mockContext, result);
        verify(mockDelegate, times(1)).context();
    }

    @Test
    public void testFsmDelegation() {
        // fsm() returns a Tron Fsm which is final and cannot be mocked
        // Just verify delegation happens without exception
        validatingTransitions.fsm();
        verify(mockDelegate, times(1)).fsm();
    }

    @Test
    public void testAllTransitionMethods() {
        // Verify all 13 transition methods are wrapped
        // Setup snapshot for valid state
        currentSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            true, true, 100,
            true, 95, 2,
            5, false, false,
            "OPERATIONAL"
        );

        // Mock all transitions
        when(mockDelegate.beginCheckpoint()).thenReturn(null);
        when(mockDelegate.combine()).thenReturn(null);
        when(mockDelegate.fail()).thenReturn(PROTOCOL_FAILURE);
        when(mockDelegate.finishCheckpoint()).thenReturn(null);
        when(mockDelegate.nextView()).thenReturn(null);
        when(mockDelegate.regenerate()).thenReturn(REGENERATING);
        when(mockDelegate.regenerated()).thenReturn(OPERATIONAL);
        when(mockDelegate.rotateViewKeys()).thenReturn(null);
        when(mockDelegate.synchd()).thenReturn(OPERATIONAL);
        when(mockDelegate.synchronizationFailed()).thenReturn(AWAITING_REGENERATION);
        when(mockDelegate.synchronizing()).thenReturn(SYNCHRONIZING);

        // Call all methods
        validatingTransitions.beginCheckpoint();
        validatingTransitions.combine();
        validatingTransitions.fail();
        validatingTransitions.finishCheckpoint();
        validatingTransitions.nextView();
        validatingTransitions.regenerate();
        validatingTransitions.regenerated();
        validatingTransitions.rotateViewKeys();
        validatingTransitions.synchd();
        validatingTransitions.synchronizationFailed();
        validatingTransitions.synchronizing();

        // Verify all were delegated
        verify(mockDelegate, times(1)).beginCheckpoint();
        verify(mockDelegate, times(1)).combine();
        verify(mockDelegate, times(1)).fail();
        verify(mockDelegate, times(1)).finishCheckpoint();
        verify(mockDelegate, times(1)).nextView();
        verify(mockDelegate, times(1)).regenerate();
        verify(mockDelegate, times(1)).regenerated();
        verify(mockDelegate, times(1)).rotateViewKeys();
        verify(mockDelegate, times(1)).synchd();
        verify(mockDelegate, times(1)).synchronizationFailed();
        verify(mockDelegate, times(1)).synchronizing();
    }

    @Test
    public void testConstructorValidation() {
        assertThrows(IllegalArgumentException.class,
                     () -> new ValidatingCombineTransitions(null, validator, snapshotSupplier));

        assertThrows(IllegalArgumentException.class,
                     () -> new ValidatingCombineTransitions(mockDelegate, null, snapshotSupplier));

        assertThrows(IllegalArgumentException.class,
                     () -> new ValidatingCombineTransitions(mockDelegate, validator, null));
    }

    @Test
    public void testGetters() {
        assertEquals(mockDelegate, validatingTransitions.getDelegate());
        assertEquals(validator, validatingTransitions.getValidator());
    }

    // -----------------------------------------------------------------------
    // Enforcement mode tests (Delos-izm.2.2)
    // -----------------------------------------------------------------------

    @Test
    public void testEnforceMode_ViolationThrowsIllegalStateException() {
        // Set ENFORCE mode via system property
        System.setProperty(VALIDATION_MODE_PROPERTY, "ENFORCE");

        // Create an invalid precondition: start() when already started
        currentSnapshot = new CHOAMStateSnapshot(
            true, false,  // already started - violates INITIAL→start precondition
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );

        when(mockDelegate.start()).thenReturn(RECOVERING);

        // In ENFORCE mode a violation must throw IllegalStateException
        assertThrows(IllegalStateException.class, () -> validatingTransitions.start());

        // Delegate should NOT have been called (exception before execution)
        // Actually: precondition is checked before delegation, so delegate never called
        verify(mockDelegate, never()).start();
    }

    @Test
    public void testEnforceMode_ValidTransitionDoesNotThrow() {
        // Set ENFORCE mode
        System.setProperty(VALIDATION_MODE_PROPERTY, "ENFORCE");

        // Valid precondition: start() when NOT started in INITIAL
        var preSnapshot = new CHOAMStateSnapshot(
            false, false,  // not started
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );
        var postSnapshot = new CHOAMStateSnapshot(
            true, false,
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "RECOVERING"
        );

        var snapshots = new java.util.ArrayDeque<>(java.util.List.of(preSnapshot, postSnapshot));
        var validating = new ValidatingCombineTransitions(
            mockDelegate, validator, snapshots::poll);

        when(mockDelegate.start()).thenReturn(RECOVERING);

        // Should not throw — no violations
        assertDoesNotThrow(() -> validating.start());
        verify(mockDelegate, times(1)).start();
    }

    @Test
    public void testLogOnlyMode_ViolationDoesNotThrow() {
        // LOG_ONLY is the default; set it explicitly for clarity
        System.setProperty(VALIDATION_MODE_PROPERTY, "LOG_ONLY");

        // Invalid precondition: already started
        currentSnapshot = new CHOAMStateSnapshot(
            true, false,
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );

        when(mockDelegate.start()).thenReturn(RECOVERING);

        // LOG_ONLY must not throw
        assertDoesNotThrow(() -> validatingTransitions.start());

        // Delegate was still called
        verify(mockDelegate, times(1)).start();

        // Violation counter was incremented by the validator
        assertEquals(1, metrics.counter("validation.violations").count());
    }

    @Test
    public void testMetricsOnlyMode_ViolationDoesNotLogButCounterIncremented() {
        // Set METRICS_ONLY mode
        System.setProperty(VALIDATION_MODE_PROPERTY, "METRICS_ONLY");

        // Invalid precondition: already started
        currentSnapshot = new CHOAMStateSnapshot(
            true, false,
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );

        when(mockDelegate.start()).thenReturn(RECOVERING);

        // METRICS_ONLY must not throw
        assertDoesNotThrow(() -> validatingTransitions.start());

        // Delegate was still called
        verify(mockDelegate, times(1)).start();

        // Counter still incremented (by the validator before handleViolation is called)
        assertEquals(1, metrics.counter("validation.violations").count());
    }

    @Test
    public void testDefaultMode_IsLogOnly() {
        // No system property set — should behave as LOG_ONLY
        assertEquals(FeatureFlags.ValidationMode.LOG_ONLY,
                     FeatureFlags.ValidationMode.current(),
                     "Default validation mode should be LOG_ONLY");
    }

    @Test
    public void testValidationMode_ParsesFromSystemProperty() {
        System.setProperty(VALIDATION_MODE_PROPERTY, "ENFORCE");
        assertEquals(FeatureFlags.ValidationMode.ENFORCE, FeatureFlags.ValidationMode.current());

        System.setProperty(VALIDATION_MODE_PROPERTY, "METRICS_ONLY");
        assertEquals(FeatureFlags.ValidationMode.METRICS_ONLY, FeatureFlags.ValidationMode.current());

        System.setProperty(VALIDATION_MODE_PROPERTY, "LOG_ONLY");
        assertEquals(FeatureFlags.ValidationMode.LOG_ONLY, FeatureFlags.ValidationMode.current());
    }

    @Test
    public void testValidationMode_InvalidProperty_DefaultsToLogOnly() {
        System.setProperty(VALIDATION_MODE_PROPERTY, "TOTALLY_INVALID");
        assertEquals(FeatureFlags.ValidationMode.LOG_ONLY,
                     FeatureFlags.ValidationMode.current(),
                     "Invalid property value should fall back to LOG_ONLY");
    }

    @Test
    public void testEnforceMode_PostconditionViolationAlsoThrows() {
        // Set ENFORCE mode
        System.setProperty(VALIDATION_MODE_PROPERTY, "ENFORCE");

        // Valid precondition snapshot
        var preSnapshot = new CHOAMStateSnapshot(
            false, false,
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );
        // Post-snapshot that violates postcondition: start() should set started=true
        // but postSnapshot still shows started=false
        var postSnapshot = new CHOAMStateSnapshot(
            false, false,  // started still false — violates postcondition for start()
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "RECOVERING"
        );

        var snapshots = new java.util.ArrayDeque<>(java.util.List.of(preSnapshot, postSnapshot));
        var validating = new ValidatingCombineTransitions(
            mockDelegate, validator, snapshots::poll);

        when(mockDelegate.start()).thenReturn(RECOVERING);

        // Postcondition violation must also throw in ENFORCE mode
        assertThrows(IllegalStateException.class, () -> validating.start());
    }

    @Test
    public void testInvalidStateName_FallbackToProtocolFailure() {
        // Snapshot with invalid state name
        currentSnapshot = new CHOAMStateSnapshot(
            false, false,
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INVALID_STATE_NAME"  // Not a valid Mercantile state
        );

        when(mockDelegate.start()).thenReturn(RECOVERING);

        // Should not throw, should fall back to PROTOCOL_FAILURE for parsing
        // and record violation
        validatingTransitions.start();

        verify(mockDelegate, times(1)).start();

        // Should record violations due to invalid state
        assertTrue(metrics.counter("validation.violations").count() > 0);
    }

    @Test
    public void testMetricsRecordedForViolations() {
        // Setup invalid precondition
        currentSnapshot = new CHOAMStateSnapshot(
            true, false,  // already started - invalid for INITIAL→start
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );

        when(mockDelegate.start()).thenReturn(RECOVERING);

        // Execute transition
        validatingTransitions.start();

        // Check metrics
        assertEquals(1, metrics.counter("validation.violations").count());
        assertEquals(1, metrics.counter("validation.precondition.violations").count());

        // Timer should have recorded duration
        assertTrue(metrics.timer("validation.precondition.timer").count() > 0);
    }
}
