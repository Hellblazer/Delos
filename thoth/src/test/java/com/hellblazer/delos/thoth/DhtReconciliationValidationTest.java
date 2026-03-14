/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

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
import com.hellblazer.delos.stereotomy.event.proto.*;
import com.hellblazer.delos.thoth.metrics.KerlDhtMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for DhtValidationPipeline.validateReconciliationBatch() — reconciliation event validation.
 *
 * @author hal.hildebrand
 */
public class DhtReconciliationValidationTest {

    @Mock
    private Ani                         ani;
    @Mock
    private KERL                        kerl;
    @Mock
    private ThothByzantineStateProvider byzantineProvider;
    @Mock
    private KerlDhtMetrics              metrics;
    @Mock
    private EventValidation             validation;
    @Mock
    private EstablishmentEvent          establishmentEvent;

    private DhtValidationPipeline    pipeline;
    private Digest                   peerId;
    private ScheduledExecutorService scheduler;
    private Duration                 validationTimeout;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        validationTimeout = Duration.ofSeconds(5);
        scheduler = Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
        pipeline = new DhtValidationPipeline(ani, kerl, validationTimeout, byzantineProvider, metrics, scheduler);
        peerId = DigestAlgorithm.DEFAULT.digest("peer-1".getBytes());
    }

    // -----------------------------------------------------------------------
    // Empty and null-safe input
    // -----------------------------------------------------------------------

    @Test
    void testEmptyBatchReturnsEmpty() {
        var result = pipeline.validateReconciliationBatch(List.of(), peerId);
        assertThat(result).isEmpty();
        verifyNoInteractions(ani, kerl, byzantineProvider);
    }

    @Test
    void testNullBatchReturnsEmpty() {
        var result = pipeline.validateReconciliationBatch(null, peerId);
        assertThat(result).isEmpty();
        verifyNoInteractions(ani, kerl, byzantineProvider);
    }

    // -----------------------------------------------------------------------
    // Circuit-breaker fail-open: unfiltered list returned
    // -----------------------------------------------------------------------

    @Test
    void testCircuitBreakerOpenReturnsUnfilteredList() {
        // Force circuit breaker open by injecting 10 consecutive infrastructure failures
        var state = createKeyStateWithEstablishmentEvent();
        var ioException = new RuntimeException("Database connection lost",
                                               new RuntimeException("SQLException: Connection refused"));
        when(kerl.getKeyEvent(any(EventCoordinates.class))).thenThrow(ioException);
        // Trigger 10 failures to open the circuit breaker via validateKeyState
        for (int i = 0; i < 10; i++) {
            pipeline.validateKeyState(state, Set.of());
        }

        // Now the circuit breaker should be open — validateReconciliationBatch must fail-open
        var events = List.of(makeInceptionEvent(0), makeRotationEvent(1), makeInteractionEvent(2));
        var result = pipeline.validateReconciliationBatch(events, peerId);

        // Fail-open: all events returned unfiltered
        assertThat(result).hasSize(3);
        assertThat(result).containsExactlyElementsOf(events);
    }

    // -----------------------------------------------------------------------
    // All-valid batch
    // -----------------------------------------------------------------------

    @Test
    void testAllValidEventsAccepted() {
        // Ani validates everything
        when(ani.eventValidation(validationTimeout)).thenReturn(validation);
        when(kerl.getKeyEvent(any(EventCoordinates.class))).thenReturn(establishmentEvent);
        when(validation.validate(establishmentEvent)).thenReturn(true);

        var events = List.of(makeInceptionEvent(0), makeRotationEvent(1));
        var result = pipeline.validateReconciliationBatch(events, peerId);

        assertThat(result).hasSize(2);
        assertThat(result).containsExactlyElementsOf(events);
        verifyNoInteractions(byzantineProvider);
    }

    // -----------------------------------------------------------------------
    // Invalid events rejected; Byzantine signal recorded
    // -----------------------------------------------------------------------

    @Test
    void testInvalidEventRejectedAndByzantineSignalRecorded() {
        // Ani rejects the event
        when(ani.eventValidation(validationTimeout)).thenReturn(validation);
        when(kerl.getKeyEvent(any(EventCoordinates.class))).thenReturn(establishmentEvent);
        when(validation.validate(establishmentEvent)).thenReturn(false);

        var events = List.of(makeInceptionEvent(0));
        var result = pipeline.validateReconciliationBatch(events, peerId);

        assertThat(result).isEmpty();
        // Byzantine signal must be recorded for the peer
        verify(byzantineProvider, atLeastOnce()).recordValidationFailure(eq(peerId), any(String.class));
    }

    @Test
    void testEventNotFoundInKerlRejectedAndByzantineSignalRecorded() {
        // Event not in local KERL — Byzantine injection attempt
        when(ani.eventValidation(validationTimeout)).thenReturn(validation);
        when(kerl.getKeyEvent(any(EventCoordinates.class))).thenReturn(null);

        var events = List.of(makeInceptionEvent(0));
        var result = pipeline.validateReconciliationBatch(events, peerId);

        assertThat(result).isEmpty();
        verify(byzantineProvider, atLeastOnce()).recordValidationFailure(eq(peerId), any(String.class));
    }

    // -----------------------------------------------------------------------
    // Mixed batch: valid + invalid — only valid events pass through
    // -----------------------------------------------------------------------

    @Test
    void testMixedBatchOnlyValidEventsPassThrough() {
        when(ani.eventValidation(validationTimeout)).thenReturn(validation);
        when(kerl.getKeyEvent(any(EventCoordinates.class))).thenReturn(establishmentEvent);
        // First call (inception) validates OK; second call (rotation) fails
        when(validation.validate(establishmentEvent)).thenReturn(true).thenReturn(false);

        var inception = makeInceptionEvent(0);
        var rotation = makeRotationEvent(1);
        var result = pipeline.validateReconciliationBatch(List.of(inception, rotation), peerId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isSameAs(inception);
        // One failure → one Byzantine signal
        verify(byzantineProvider, times(1)).recordValidationFailure(eq(peerId), any(String.class));
    }

    // -----------------------------------------------------------------------
    // Two-pass ordering: inception-first allows dependent events to validate
    // -----------------------------------------------------------------------

    @Test
    void testInceptionFirstOrderingEnablesDependentEventValidation() {
        // A batch that arrives with rotation before inception — inception must be
        // processed first so the subsequent rotation can find it in the KERL.
        when(ani.eventValidation(validationTimeout)).thenReturn(validation);
        when(kerl.getKeyEvent(any(EventCoordinates.class))).thenReturn(establishmentEvent);
        when(validation.validate(any(EstablishmentEvent.class))).thenReturn(true);

        var rotation = makeRotationEvent(1);
        var inception = makeInceptionEvent(0);
        // Deliberately pass rotation before inception
        var result = pipeline.validateReconciliationBatch(List.of(rotation, inception), peerId);

        // Both events should be returned — inception processed first bootstrapped trust
        assertThat(result).hasSize(2);
        verifyNoInteractions(byzantineProvider);
    }

    // -----------------------------------------------------------------------
    // Structural validity: events with no event type are rejected
    // -----------------------------------------------------------------------

    @Test
    void testEventWithNoTypeRejected() {
        // A KeyEventWithAttachmentAndValidations_ where the inner KeyEvent_ has no type set
        var emptyEvent = KeyEventWithAttachmentAndValidations_.newBuilder()
                                                              .setEvent(KeyEvent_.getDefaultInstance())
                                                              .build();

        var result = pipeline.validateReconciliationBatch(List.of(emptyEvent), peerId);

        assertThat(result).isEmpty();
        verify(byzantineProvider, atLeastOnce()).recordValidationFailure(eq(peerId), any(String.class));
    }

    // -----------------------------------------------------------------------
    // Infrastructure exception: fail-open for individual events
    // -----------------------------------------------------------------------

    @Test
    void testInfrastructureExceptionFailsOpenForEvent() {
        // KERL throws SQL exception during validation — individual event accepted
        when(ani.eventValidation(validationTimeout)).thenReturn(validation);
        var dbException = new RuntimeException("Database connection lost",
                                               new RuntimeException("SQLException: disk full"));
        when(kerl.getKeyEvent(any(EventCoordinates.class))).thenThrow(dbException);

        var events = List.of(makeInceptionEvent(0));
        var result = pipeline.validateReconciliationBatch(events, peerId);

        // Fail-open: event is accepted when infrastructure is failing
        assertThat(result).hasSize(1);
        verifyNoInteractions(byzantineProvider);
    }

    // -----------------------------------------------------------------------
    // All three event types handled
    // -----------------------------------------------------------------------

    @Test
    void testInteractionEventHandled() {
        when(ani.eventValidation(validationTimeout)).thenReturn(validation);
        when(kerl.getKeyEvent(any(EventCoordinates.class))).thenReturn(establishmentEvent);
        when(validation.validate(establishmentEvent)).thenReturn(true);

        var result = pipeline.validateReconciliationBatch(List.of(makeInteractionEvent(2)), peerId);

        assertThat(result).hasSize(1);
        verifyNoInteractions(byzantineProvider);
    }

    @Test
    void testRotationEventHandled() {
        when(ani.eventValidation(validationTimeout)).thenReturn(validation);
        when(kerl.getKeyEvent(any(EventCoordinates.class))).thenReturn(establishmentEvent);
        when(validation.validate(establishmentEvent)).thenReturn(true);

        var result = pipeline.validateReconciliationBatch(List.of(makeRotationEvent(1)), peerId);

        assertThat(result).hasSize(1);
        verifyNoInteractions(byzantineProvider);
    }

    // -----------------------------------------------------------------------
    // Null peer ID (robustness)
    // -----------------------------------------------------------------------

    @Test
    void testNullPeerIdHandledGracefully() {
        when(ani.eventValidation(validationTimeout)).thenReturn(validation);
        when(kerl.getKeyEvent(any(EventCoordinates.class))).thenReturn(establishmentEvent);
        when(validation.validate(establishmentEvent)).thenReturn(true);

        var events = List.of(makeInceptionEvent(0));
        // Must not NPE even with null peer ID
        var result = pipeline.validateReconciliationBatch(events, null);

        assertThat(result).hasSize(1);
    }

    // -----------------------------------------------------------------------
    // Helper builders
    // -----------------------------------------------------------------------

    private static final Ident IDENT = Ident.newBuilder()
                                            .setSelfAddressing(DigestAlgorithm.DEFAULT.getOrigin().toDigeste())
                                            .build();

    private static KeyEventWithAttachmentAndValidations_ makeInceptionEvent(long seqNum) {
        var header = Header.newBuilder().setSequenceNumber(seqNum).setIdentifier(IDENT).setIlk("icp").build();
        var spec = IdentifierSpec.newBuilder().setHeader(header).build();
        var common = EventCommon.newBuilder().build();
        var icp = InceptionEvent.newBuilder().setIdentifier(IDENT).setSpecification(spec).setCommon(common).build();
        var ke = KeyEvent_.newBuilder().setInception(icp).build();
        return KeyEventWithAttachmentAndValidations_.newBuilder().setEvent(ke).build();
    }

    private static KeyEventWithAttachmentAndValidations_ makeRotationEvent(long seqNum) {
        var header = Header.newBuilder().setSequenceNumber(seqNum).setIdentifier(IDENT).setIlk("rot").build();
        var spec = RotationSpec.newBuilder().setHeader(header).build();
        var common = EventCommon.newBuilder().build();
        var rot = RotationEvent.newBuilder().setSpecification(spec).setCommon(common).build();
        var ke = KeyEvent_.newBuilder().setRotation(rot).build();
        return KeyEventWithAttachmentAndValidations_.newBuilder().setEvent(ke).build();
    }

    private static KeyEventWithAttachmentAndValidations_ makeInteractionEvent(long seqNum) {
        var header = Header.newBuilder().setSequenceNumber(seqNum).setIdentifier(IDENT).setIlk("ixn").build();
        var spec = InteractionSpec.newBuilder().setHeader(header).build();
        var common = EventCommon.newBuilder().build();
        var ixn = InteractionEvent.newBuilder().setSpecification(spec).setCommon(common).build();
        var ke = KeyEvent_.newBuilder().setInteraction(ixn).build();
        return KeyEventWithAttachmentAndValidations_.newBuilder().setEvent(ke).build();
    }

    private KeyState_ createKeyStateWithEstablishmentEvent() {
        var coords = EventCoords.newBuilder()
                                .setIdentifier(IDENT)
                                .setSequenceNumber(0)
                                .setDigest(DigestAlgorithm.DEFAULT.getOrigin().toDigeste())
                                .build();
        return KeyState_.newBuilder().setLastEstablishmentEvent(coords).build();
    }
}
