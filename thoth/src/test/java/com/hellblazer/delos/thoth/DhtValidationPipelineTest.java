/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import com.google.protobuf.Empty;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.SigningThreshold;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.EventValidation;
import com.hellblazer.delos.stereotomy.KERL;
import com.hellblazer.delos.stereotomy.event.EstablishmentEvent;
import com.hellblazer.delos.stereotomy.event.KeyEvent;
import com.hellblazer.delos.stereotomy.event.RotationEvent;
import com.hellblazer.delos.stereotomy.event.proto.EventCoords;
import com.hellblazer.delos.stereotomy.event.proto.Ident;
import com.hellblazer.delos.stereotomy.event.proto.KeyState_;
import com.hellblazer.delos.stereotomy.services.grpc.proto.KeyStates;
import com.hellblazer.delos.thoth.metrics.KerlDhtMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.InputStream;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for DhtValidationPipeline validation logic.
 *
 * @author hal.hildebrand
 */
public class DhtValidationPipelineTest {

    @Mock
    private Ani                             ani;
    @Mock
    private KERL                            kerl;
    @Mock
    private ThothByzantineStateProvider     byzantineProvider;
    @Mock
    private KerlDhtMetrics                  metrics;
    @Mock
    private EventValidation                 validation;
    @Mock
    private EstablishmentEvent              establishmentEvent;
    @Mock
    private RotationEvent                   rotationEvent;

    private DhtValidationPipeline           pipeline;
    private Set<Member>                     providers;
    private Duration                        validationTimeout;
    private ScheduledExecutorService        scheduler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        validationTimeout = Duration.ofSeconds(5);
        scheduler = Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
        pipeline = new DhtValidationPipeline(ani, kerl, validationTimeout, byzantineProvider, metrics, scheduler);
        providers = Set.of(createMember("byzantine-1"));
    }

    @Test
    void testValidationFailsWhenKerlEventNotFound() {
        // Given: KeyState with establishment event that doesn't exist in KERL
        var state = createKeyStateWithEstablishmentEvent();
        when(kerl.getKeyEvent(any(EventCoordinates.class))).thenReturn(null);

        // When: Validate the state
        var result = pipeline.validateKeyState(state, providers);

        // Then: Validation should FAIL (not skip)
        assertThat(result.valid()).isFalse();
        assertThat(result.failureReason()).contains("not found in local KERL");
        assertThat(result.suspects()).isEqualTo(providers);
        assertThat(result.operation()).isEqualTo("keyState");
    }

    @Test
    void testValidationFailsForNonEstablishmentEvent() {
        // Given: KeyState references a rotation event, not establishment
        var state = createKeyStateWithEstablishmentEvent();
        // Return mock that is KeyEvent but NOT EstablishmentEvent
        KeyEvent nonEstablishmentEvent = mock(KeyEvent.class);
        when(kerl.getKeyEvent(any(EventCoordinates.class))).thenReturn(nonEstablishmentEvent);
        when(ani.eventValidation(validationTimeout)).thenReturn(validation);

        // When: Validate the state
        var result = pipeline.validateKeyState(state, providers);

        // Then: Validation should FAIL (not skip)
        assertThat(result.valid()).isFalse();
        assertThat(result.failureReason()).contains("Expected EstablishmentEvent but got");
        assertThat(result.suspects()).isEqualTo(providers);
        // validation.validate() should NOT be called since event type is wrong
        verifyNoInteractions(validation);
    }

    @Test
    void testValidationFailsWhenAniRejectsEvent() {
        // Given: Ani validation returns false
        var state = createKeyStateWithEstablishmentEvent();
        when(kerl.getKeyEvent(any(EventCoordinates.class))).thenReturn(establishmentEvent);
        when(ani.eventValidation(validationTimeout)).thenReturn(validation);
        when(validation.validate(establishmentEvent)).thenReturn(false);

        // When: Validate the state
        var result = pipeline.validateKeyState(state, providers);

        // Then: Validation should fail
        assertThat(result.valid()).isFalse();
        assertThat(result.failureReason()).contains("validation failed");
        assertThat(result.suspects()).isEqualTo(providers);
    }

    @Test
    void testValidationSucceedsWhenAniAcceptsEvent() {
        // Given: Ani validation returns true
        var state = createKeyStateWithEstablishmentEvent();
        when(kerl.getKeyEvent(any(EventCoordinates.class))).thenReturn(establishmentEvent);
        when(ani.eventValidation(validationTimeout)).thenReturn(validation);
        when(validation.validate(establishmentEvent)).thenReturn(true);

        // When: Validate the state
        var result = pipeline.validateKeyState(state, providers);

        // Then: Validation should succeed
        assertThat(result.valid()).isTrue();
        assertThat(result.value()).isEqualTo(state);
        verify(metrics).incrementValidationSuccess("keyState");
    }

    @Test
    void testValidationSkippedForNullState() {
        // Given: Null state
        KeyState_ state = null;

        // When: Validate
        var result = pipeline.validateKeyState(state, providers);

        // Then: Validation succeeds (null is valid)
        assertThat(result.valid()).isTrue();
        verifyNoInteractions(kerl, ani);
    }

    @Test
    void testValidationSkippedForDefaultInstance() {
        // Given: Default instance (empty state)
        var state = KeyState_.getDefaultInstance();

        // When: Validate
        var result = pipeline.validateKeyState(state, providers);

        // Then: Validation succeeds
        assertThat(result.valid()).isTrue();
        verifyNoInteractions(kerl, ani);
    }

    @Test
    void testValidationSkippedWhenNoEstablishmentEvent() {
        // Given: KeyState without establishment event (use builder that sets some field but not lastEstablishmentEvent)
        var state = KeyState_.newBuilder().setCoordinates(EventCoords.getDefaultInstance()).build();

        // When: Validate
        var result = pipeline.validateKeyState(state, providers);

        // Then: Validation succeeds (nothing to validate)
        assertThat(result.valid()).isTrue();
        verify(metrics).incrementValidationSkipped("keyState", "no_establishment_event");
        verifyNoInteractions(kerl, ani);
    }

    @Test
    void testInfrastructureExceptionFailsOpen() {
        // Given: KERL throws infrastructure exception
        var state = createKeyStateWithEstablishmentEvent();
        var dbException = new RuntimeException("Database connection lost",
                                               new RuntimeException("SQLException: Connection refused"));
        when(kerl.getKeyEvent(any(EventCoordinates.class))).thenThrow(dbException);

        // When: Validate
        var result = pipeline.validateKeyState(state, providers);

        // Then: Should fail open (accept response with warning)
        assertThat(result.valid()).isTrue();
        verify(metrics).incrementValidationSkipped("keyState", "kerl_access_failure");
    }

    @Test
    void testUnexpectedExceptionFailsClosed() {
        // Given: KERL throws unexpected exception (NPE)
        var state = createKeyStateWithEstablishmentEvent();
        var unexpectedException = new NullPointerException("Unexpected programming error");
        when(kerl.getKeyEvent(any(EventCoordinates.class))).thenThrow(unexpectedException);

        // When: Validate
        var result = pipeline.validateKeyState(state, providers);

        // Then: Should fail closed (reject response)
        assertThat(result.valid()).isFalse();
        assertThat(result.failureReason()).contains("Infrastructure error");
        assertThat(result.failureReason()).contains("NullPointerException");
        assertThat(result.suspects()).isEqualTo(providers);
    }

    @Test
    void testConcurrentValidationThreadSafety() throws Exception {
        // Given: Multiple threads validating concurrently
        var state = createKeyStateWithEstablishmentEvent();
        when(kerl.getKeyEvent(any(EventCoordinates.class))).thenReturn(establishmentEvent);
        when(ani.eventValidation(validationTimeout)).thenReturn(validation);
        when(validation.validate(establishmentEvent)).thenReturn(true);

        ExecutorService executor = Executors.newFixedThreadPool(10);
        var futures = IntStream.range(0, 100).mapToObj(i -> CompletableFuture.runAsync(() -> {
            var result = pipeline.validateKeyState(state, providers);
            assertThat(result.valid()).isTrue();
        }, executor)).toList();

        // When: All threads complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();

        // Then: All validations succeeded, metrics accurate
        verify(metrics, times(100)).incrementValidationSuccess("keyState");
        executor.shutdown();
    }

    @Test
    void testValidateKeyStatesAllValid() {
        // Given: KeyStates with multiple valid states
        var state1 = createKeyStateWithEstablishmentEvent();
        var state2 = createKeyStateWithEstablishmentEvent();
        var keyStates = KeyStates.newBuilder().addKeyStates(state1).addKeyStates(state2).build();

        when(kerl.getKeyEvent(any(EventCoordinates.class))).thenReturn(establishmentEvent);
        when(ani.eventValidation(validationTimeout)).thenReturn(validation);
        when(validation.validate(establishmentEvent)).thenReturn(true);

        // When: Validate
        var result = pipeline.validateKeyStates(keyStates, providers);

        // Then: All states valid
        assertThat(result.valid()).isTrue();
        verify(metrics).incrementValidationSuccess("keyStates");
        verify(metrics, times(2)).incrementValidationSuccess("keyState");
    }

    @Test
    void testValidateKeyStatesOneInvalid() {
        // Given: KeyStates where one state is invalid
        var state1 = createKeyStateWithEstablishmentEvent();
        var state2 = createKeyStateWithEstablishmentEvent();
        var keyStates = KeyStates.newBuilder().addKeyStates(state1).addKeyStates(state2).build();

        when(kerl.getKeyEvent(any(EventCoordinates.class))).thenReturn(establishmentEvent);
        when(ani.eventValidation(validationTimeout)).thenReturn(validation);
        when(validation.validate(establishmentEvent)).thenReturn(true).thenReturn(false);

        // When: Validate
        var result = pipeline.validateKeyStates(keyStates, providers);

        // Then: Entire batch invalid
        assertThat(result.valid()).isFalse();
        assertThat(result.failureReason()).contains("contains invalid state");
        assertThat(result.suspects()).isEqualTo(providers);
    }

    @Test
    void testValidateKeyStatesEmpty() {
        // Given: Empty KeyStates
        var keyStates = KeyStates.getDefaultInstance();

        // When: Validate
        var result = pipeline.validateKeyStates(keyStates, providers);

        // Then: Valid
        assertThat(result.valid()).isTrue();
        verifyNoInteractions(kerl, ani);
    }

    @Test
    void testValidateKeyStatesInfrastructureException() {
        // Given: KeyStates validation throws infrastructure exception
        var state1 = createKeyStateWithEstablishmentEvent();
        var keyStates = KeyStates.newBuilder().addKeyStates(state1).build();
        var ioException = new RuntimeException("I/O error reading KERL",
                                               new RuntimeException("IOException: Disk full"));
        when(kerl.getKeyEvent(any(EventCoordinates.class))).thenThrow(ioException);

        // When: Validate
        var result = pipeline.validateKeyStates(keyStates, providers);

        // Then: Fail open (exception handled at validateKeyState level)
        assertThat(result.valid()).isTrue();
        // Metrics incremented at validateKeyState level, not validateKeyStates
        verify(metrics).incrementValidationSkipped("keyState", "kerl_access_failure");
    }

    @Test
    void testValidateKeyStatesUnexpectedException() {
        // Given: KeyStates validation throws unexpected exception
        var state1 = createKeyStateWithEstablishmentEvent();
        var keyStates = KeyStates.newBuilder().addKeyStates(state1).build();
        var illegalState = new IllegalStateException("Unexpected state");
        when(kerl.getKeyEvent(any(EventCoordinates.class))).thenThrow(illegalState);

        // When: Validate
        var result = pipeline.validateKeyStates(keyStates, providers);

        // Then: Fail closed
        assertThat(result.valid()).isFalse();
        assertThat(result.failureReason()).contains("Infrastructure error");
        assertThat(result.suspects()).isEqualTo(providers);
    }

    @Test
    void testValidateEmptyAlwaysValid() {
        // Given: Empty response
        var empty = Empty.getDefaultInstance();

        // When: Validate
        var result = pipeline.validateEmpty(empty, providers);

        // Then: Always valid
        assertThat(result.valid()).isTrue();
        verifyNoInteractions(kerl, ani, metrics, byzantineProvider);
    }

    @Test
    void testReportFailureRecordsByzantine() {
        // Given: Invalid result
        var state = createKeyStateWithEstablishmentEvent();
        var result = ValidationResult.invalid(state, providers, "Forged event", "keyState");

        // When: Report failure
        pipeline.reportFailure(result);

        // Then: Byzantine provider and metrics updated
        for (var member : providers) {
            verify(byzantineProvider).recordValidationFailure(member.getId(), "Forged event");
        }
        verify(metrics).incrementValidationFailure("keyState", "Forged event");
        verify(metrics).incrementByzantineDetection("VALIDATION");
    }

    @Test
    void testReportFailureIgnoresValidResults() {
        // Given: Valid result
        var state = createKeyStateWithEstablishmentEvent();
        var result = ValidationResult.valid(state, "keyState");

        // When: Report failure
        pipeline.reportFailure(result);

        // Then: Nothing reported
        verifyNoInteractions(byzantineProvider, metrics);
    }

    @Test
    void testReportFailureWithMultipleSuspects() {
        // Given: Multiple Byzantine suspects
        var member1 = createMember("byzantine-1");
        var member2 = createMember("byzantine-2");
        var suspects = Set.of(member1, member2);
        var state = createKeyStateWithEstablishmentEvent();
        var result = ValidationResult.invalid(state, suspects, "Equivocation detected", "keyState");

        // When: Report failure
        pipeline.reportFailure(result);

        // Then: All suspects recorded
        verify(byzantineProvider).recordValidationFailure(member1.getId(), "Equivocation detected");
        verify(byzantineProvider).recordValidationFailure(member2.getId(), "Equivocation detected");
        verify(metrics).incrementValidationFailure("keyState", "Equivocation detected");
        verify(metrics).incrementByzantineDetection("VALIDATION");
    }

    @Test
    void testNullProvidersHandledGracefully() {
        // Given: Null providers
        var state = createKeyStateWithEstablishmentEvent();
        when(kerl.getKeyEvent(any(EventCoordinates.class))).thenReturn(null);

        // When: Validate with null providers
        var result = pipeline.validateKeyState(state, null);

        // Then: Should not NPE, validation still fails
        assertThat(result.valid()).isFalse();
        assertThat(result.suspects()).isEmpty();
    }

    // Helper methods

    private KeyState_ createKeyStateWithEstablishmentEvent() {
        var ident = Ident.newBuilder().setSelfAddressing(DigestAlgorithm.DEFAULT.getOrigin().toDigeste()).build();
        var coords = EventCoords.newBuilder()
                                .setIdentifier(ident)
                                .setSequenceNumber(0)
                                .setDigest(DigestAlgorithm.DEFAULT.getOrigin().toDigeste())
                                .build();
        return KeyState_.newBuilder().setLastEstablishmentEvent(coords).build();
    }

    private Member createMember(String name) {
        var digest = DigestAlgorithm.DEFAULT.digest(name.getBytes());
        return new Member() {
            @Override
            public Digest getId() {
                return digest;
            }

            @Override
            public int compareTo(Member o) {
                return digest.compareTo(o.getId());
            }

            @Override
            public boolean verify(JohnHancock signature, InputStream message) {
                return true;
            }

            @Override
            public boolean verify(SigningThreshold threshold, JohnHancock signature, InputStream message) {
                return true;
            }
        };
    }
}
