/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.state;

import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.utils.Utils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author hal.hildebrand
 */
public class GenesisBootstrapTest extends AbstractLifecycleTest {

    static {
        //        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(CHOAM.class)).setLevel(Level.TRACE);
        //        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(GenesisAssembly.class)).setLevel(Level.TRACE);
        //        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ViewAssembly.class)).setLevel(Level.TRACE);
        //        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Producer.class)).setLevel(Level.TRACE);
        //        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Committee.class)).setLevel(Level.TRACE);
        //        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Fsm.class)).setLevel(Level.TRACE);
    }

    @Test
    public void genesisBootstrap() throws Exception {
        pre();
        System.out.println("Starting late joining node");
        var choam = choams.get(testSubject.getId());
        ((DynamicContext) choam.context().delegate()).activate(testSubject);
        choam.start();
        routers.get(testSubject.getId()).start();

        assertTrue(Utils.waitForCondition(30_000, 1_000, () -> choam.active()),
                   "Test subject did not become active: " + choam.logState());
        members.add(testSubject);
        post();
    }

    @Override
    protected int checkpointBlockSize() {
        return 1000;
    }

    @Override
    protected byte disc() {
        return 2;
    }
}
