/*
 * Copyright (c) 2013 ChiralBehaviors LLC, all rights reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.chiralbehaviors.tron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.chiralbehaviors.tron.examples.simpleProtocol.BufferHandler;
import com.chiralbehaviors.tron.examples.simpleProtocol.SimpleFsm;
import com.chiralbehaviors.tron.examples.simpleProtocol.SimpleProtocol;
import com.chiralbehaviors.tron.examples.simpleProtocol.impl.SimpleProtocolImpl;
import com.chiralbehaviors.tron.examples.simpleProtocol.stateMaps.Simple;
import com.chiralbehaviors.tron.examples.simpleProtocol.stateMaps.SimpleClient;

/**
 * 
 * @author hhildebrand
 * 
 */
public class TestSimple {
    @Test
    public void testIt() {
        SimpleProtocol protocol = new SimpleProtocolImpl();
        Fsm<SimpleProtocol, SimpleFsm> fsm = Fsm.construct(protocol, SimpleFsm.class, Simple.INITIAL, true);
        verifyFsmStates(fsm, protocol);
    }

    @Test
    public void testItWithCustomClassLoader() {
        SimpleProtocol protocol = new SimpleProtocolImpl();
        Fsm<SimpleProtocol, SimpleFsm> fsm = Fsm.construct(protocol, SimpleFsm.class, SimpleFsm.class.getClassLoader(),
                                                           Simple.INITIAL, true);
        verifyFsmStates(fsm, protocol);
    }

    @Test
    public void testGetCurrentStateThreadSafety() throws Exception {
        var protocol = new SimpleProtocolImpl();
        var fsm = Fsm.construct(protocol, SimpleFsm.class, Simple.INITIAL, true);
        var handler = new BufferHandler();

        var iterations = 10_000;
        var barrier = new CyclicBarrier(2);
        var failed = new AtomicBoolean(false);
        var error = new AtomicReference<Throwable>();
        var completionLatch = new CountDownLatch(2);

        // Reader thread - continuously calls getCurrentState()
        var reader = new Thread(() -> {
            try {
                barrier.await();
                for (int i = 0; i < iterations && !failed.get(); i++) {
                    var state = fsm.getCurrentState();
                    if (state == null) {
                        failed.set(true);
                        error.set(new AssertionError("getCurrentState() returned null at iteration " + i));
                        break;
                    }
                    // Verify it's a valid state from our state machine
                    if (!(state instanceof SimpleFsm)) {
                        failed.set(true);
                        error.set(new AssertionError("getCurrentState() returned invalid state: " + state));
                        break;
                    }
                }
            } catch (Throwable t) {
                failed.set(true);
                error.set(t);
            } finally {
                completionLatch.countDown();
            }
        });

        // Writer thread - continuously cycles through a valid state sequence
        var writer = new Thread(() -> {
            try {
                barrier.await();
                for (int i = 0; i < iterations && !failed.get(); i++) {
                    // Execute a complete valid state cycle
                    fsm.getTransitions().connected(handler);
                    fsm.getTransitions().writeReady();
                    fsm.getTransitions().readReady();
                    fsm.getTransitions().transmitMessage("test-" + i);
                    fsm.getTransitions().writeReady();
                    fsm.getTransitions().readReady();
                    // Small yield to increase chance of interleaving with reader
                    if (i % 100 == 0) {
                        Thread.yield();
                    }
                }
            } catch (Throwable t) {
                failed.set(true);
                error.set(t);
            } finally {
                completionLatch.countDown();
            }
        });

        reader.start();
        writer.start();

        var completed = completionLatch.await(30, TimeUnit.SECONDS);
        assertTrue(completed, "Test threads did not complete in time");

        if (failed.get()) {
            var err = error.get();
            if (err != null) {
                fail("Thread safety test failed: " + err.getMessage(), err);
            } else {
                fail("Thread safety test failed with unknown error");
            }
        }
    }

    private void verifyFsmStates(Fsm<SimpleProtocol, SimpleFsm> fsm, SimpleProtocol protocol) {
        assertNotNull(fsm);
        BufferHandler handler = new BufferHandler();
        fsm.getTransitions().connected(handler);
        assertEquals(handler, ((SimpleProtocolImpl) protocol).getHandler());
        assertEquals(SimpleClient.CONNECTED, fsm.getCurrentState());
        fsm.getTransitions().writeReady();
        assertEquals(SimpleClient.ESTABLISH_SESSION, fsm.getCurrentState());
        fsm.getTransitions().readReady();
        assertEquals(SimpleClient.SEND_MESSAGE, fsm.getCurrentState());
        fsm.getTransitions().sendGoodbye();
        assertEquals(SimpleClient.SEND_GOODBYE, fsm.getCurrentState());
        fsm.getTransitions().readReady();
        assertEquals(Simple.CLOSED, fsm.getCurrentState());
    }
}
