/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.choam.proto.Block;
import com.hellblazer.delos.choam.proto.CertifiedBlock;
import com.hellblazer.delos.choam.proto.Header;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.context.ViewChange;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.fireflies.View;
import com.hellblazer.delos.membership.MockMember;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * TDD tests for WitnessCHOAMViewChangeListener Fireflies integration.
 *
 * Tests view change subscription and coordination between Fireflies (membership)
 * and WitnessCHOAM (receipt collection state machine).
 */
class WitnessCHOAMViewChangeListenerTest {

    private static final int COMMITTEE_SIZE = 7;
    private static final int WITNESS_POOL_SIZE = 21;
    private static final DigestAlgorithm ALGORITHM = DigestAlgorithm.DEFAULT;

    private Context<MockMember> firefliesContext;
    private WitnessContext witnessContext;
    private WitnessParameters parameters;
    private WitnessReceiptManager receiptManager;
    private WitnessStateMachine stateMachine;
    private WitnessCHOAM witnessCHOAM;
    private WitnessCHOAMViewChangeListener listener;

    @Mock
    private View mockView;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        var witnessPool = createWitnessPool(WITNESS_POOL_SIZE);

        var contextId = ALGORITHM.digest("view-change-listener-test".getBytes());
        firefliesContext = new StaticContext<>(
            contextId,
            0.1,
            witnessPool,
            COMMITTEE_SIZE
        );

        var threshold = (2 * COMMITTEE_SIZE) / 3 + 1;
        parameters = WitnessParameters.newBuilder()
            .k(COMMITTEE_SIZE)
            .threshold(threshold)
            .epoch(0)
            .drainPeriod(Duration.ofMillis(500))
            .build();

        witnessContext = new WitnessContext(firefliesContext, parameters, ALGORITHM);

        receiptManager = new WitnessReceiptManager(parameters);
        stateMachine = new WitnessStateMachine(receiptManager, parameters, ALGORITHM);

        // Create genesis block (height 0)
        var genesisBlock = new HashedCertifiedBlock(ALGORITHM, CertifiedBlock.newBuilder()
            .setBlock(Block.newBuilder()
                .setHeader(Header.newBuilder()
                    .setHeight(0)
                    .build())
                .build())
            .build());

        // Initialize CHOAM replica
        witnessCHOAM = new WitnessCHOAM(null, null, stateMachine, parameters);
        witnessCHOAM.onViewChange(genesisBlock);

        // Create listener
        listener = new WitnessCHOAMViewChangeListener(
            witnessCHOAM, witnessContext, ALGORITHM, "test-listener"
        );
    }

    @Test
    void testRegister_SubscribesToView() {
        // When: Registering listener with view
        listener.register(mockView);

        // Then: register() called on view with correct parameters
        verify(mockView, times(1)).register(eq("test-listener"), any(Consumer.class));
    }

    @Test
    void testDeregister_UnsubscribesFromView() {
        // When: Deregistering listener
        listener.deregister(mockView);

        // Then: deregister() called on view
        verify(mockView, times(1)).deregister(any(Consumer.class));
    }

    @Test
    void testViewChangeHandler_IncrementHeight() {
        // Given: Initial view height is 0
        assertEquals(0L, listener.getViewHeight());

        // When: Creating and handling view change
        var diadem = ALGORITHM.digest("view-1".getBytes());
        var viewChange = createMockViewChange(diadem, firefliesContext);

        // Capture the listener and invoke it
        ArgumentCaptor<Consumer<ViewChange>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(mockView, times(0)).register(eq("test-listener"), captor.capture());

        listener.register(mockView);
        verify(mockView).register(eq("test-listener"), captor.capture());

        // Invoke the listener with view change
        Consumer<ViewChange> viewChangeHandler = captor.getValue();
        viewChangeHandler.accept(viewChange);

        // Then: View height incremented
        assertEquals(1L, listener.getViewHeight());
    }

    @Test
    void testViewChangeHandler_UpdatesWitnessCHOAMViewHeight() {
        // Given: WitnessCHOAM starts at height 0
        assertEquals(0L, witnessCHOAM.getViewHeight());

        // When: Handling view change
        var diadem = ALGORITHM.digest("view-100".getBytes());
        var viewChange = createMockViewChange(diadem, firefliesContext);

        ArgumentCaptor<Consumer<ViewChange>> captor = ArgumentCaptor.forClass(Consumer.class);
        listener.register(mockView);
        verify(mockView).register(eq("test-listener"), captor.capture());

        Consumer<ViewChange> viewChangeHandler = captor.getValue();
        viewChangeHandler.accept(viewChange);

        // Then: WitnessCHOAM view height updated
        assertEquals(1L, witnessCHOAM.getViewHeight());
    }

    @Test
    void testViewChangeHandler_StartsDrainPeriod() {
        // Given: Listener registered
        ArgumentCaptor<Consumer<ViewChange>> captor = ArgumentCaptor.forClass(Consumer.class);
        listener.register(mockView);
        verify(mockView).register(eq("test-listener"), captor.capture());

        // When: Handling view change
        var diadem = ALGORITHM.digest("view-drain".getBytes());
        var viewChange = createMockViewChange(diadem, firefliesContext);

        Consumer<ViewChange> viewChangeHandler = captor.getValue();
        viewChangeHandler.accept(viewChange);

        // Then: Drain period is active on WitnessCHOAM
        assertTrue(witnessCHOAM.isDraining());
    }

    @Test
    void testMultipleViewChanges_ProgressionTracking() {
        // Given: Listener registered and initialized
        ArgumentCaptor<Consumer<ViewChange>> captor = ArgumentCaptor.forClass(Consumer.class);
        listener.register(mockView);
        verify(mockView).register(eq("test-listener"), captor.capture());
        Consumer<ViewChange> viewChangeHandler = captor.getValue();

        // When: Multiple view changes occur
        for (int i = 1; i <= 3; i++) {
            var diadem = ALGORITHM.digest(("view-" + i).getBytes());
            var viewChange = createMockViewChange(diadem, firefliesContext);
            viewChangeHandler.accept(viewChange);

            // Then: Each view change increments height
            assertEquals(i, listener.getViewHeight());
            assertEquals(i, witnessCHOAM.getViewHeight());
        }
    }

    @Test
    void testViewChangeWithCollectionsInProgress() {
        // Given: Collection in progress
        ArgumentCaptor<Consumer<ViewChange>> captor = ArgumentCaptor.forClass(Consumer.class);
        listener.register(mockView);
        verify(mockView).register(eq("test-listener"), captor.capture());
        Consumer<ViewChange> viewChangeHandler = captor.getValue();

        var event = createEventCoordinates("test-event", 1L);
        var collectionId = witnessCHOAM.initiateCollection(event, 0L);

        // Verify collection is tracked
        assertNotNull(witnessCHOAM.getSequence(collectionId));
        assertEquals(1, witnessCHOAM.getStatistics().activeSequences());

        // When: View change occurs during collection
        var diadem = ALGORITHM.digest("view-with-collections".getBytes());
        var viewChange = createMockViewChange(diadem, firefliesContext);
        viewChangeHandler.accept(viewChange);

        // Then: Collection still tracked and drain period started
        assertEquals(1, witnessCHOAM.getStatistics().activeSequences());
        assertTrue(witnessCHOAM.isDraining());
    }

    @Test
    void testSetViewHeight_ForTesting() {
        // Given: Listener with initial height 0
        assertEquals(0L, listener.getViewHeight());

        // When: Setting view height for testing
        listener.setViewHeight(500L);

        // Then: Height updated
        assertEquals(500L, listener.getViewHeight());
    }

    // Helper methods

    private List<MockMember> createWitnessPool(int size) {
        return IntStream.range(0, size)
            .mapToObj(i -> {
                var digest = ALGORITHM.digest(("witness-" + i).getBytes());
                return new MockMember(digest);
            })
            .toList();
    }

    private EventCoordinates createEventCoordinates(String identifierStr, long sequenceNumber) {
        var identifier = new SelfAddressingIdentifier(
            ALGORITHM.digest(identifierStr.getBytes())
        );
        var digest = ALGORITHM.digest(
            (identifierStr + "-" + sequenceNumber).getBytes()
        );
        return new EventCoordinates(identifier, ULong.valueOf(sequenceNumber), digest, "icp");
    }

    private ViewChange createMockViewChange(Digest diadem,
                                            Context<?> context) {
        return new ViewChange(
            context,
            diadem,
            Collections.emptyList(),  // joining
            Collections.emptyList()   // leaving
        );
    }
}
