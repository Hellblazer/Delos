/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.chiralbehaviors.tron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.internal.verification.Times;

import com.chiralbehaviors.tron.examples.task.Task;
import com.chiralbehaviors.tron.examples.task.TaskFsm;
import com.chiralbehaviors.tron.examples.task.TaskModel;

/**
 * 
 * @author hhildebrand
 * 
 */
public class TestTask {
    @Test
    public void testIt() {
        long timeslice = 100;
        TaskModel model = mock(TaskModel.class);
        Fsm<TaskModel, TaskFsm> fsm = Fsm.construct(model, TaskFsm.class, Task.Suspended, false);
        TaskFsm transitions = fsm.getTransitions();
        assertEquals(Task.Suspended, fsm.getCurrentState());
        transitions.start(timeslice);
        verify(model).continueTask();
        verify(model).startSliceTimer(timeslice);
        assertEquals(Task.Running, fsm.getCurrentState());
        transitions.suspended();
        assertEquals(Task.Suspended, fsm.getCurrentState());
        verify(model).stopSliceTimer();
        transitions.start(timeslice);
        verify(model, new Times(2)).startSliceTimer(timeslice);
        transitions.block();
        assertEquals(Task.Blocked, fsm.getCurrentState());
        verify(model, new Times(2)).stopSliceTimer();
        transitions.unblock();
        assertEquals(Task.Suspended, fsm.getCurrentState());
        transitions.start(timeslice);
        verify(model, new Times(3)).startSliceTimer(timeslice);
        transitions.stop();
        assertEquals(Task.Stopping, fsm.getCurrentState());
        verify(model, new Times(3)).stopSliceTimer();
        transitions.stopped();
        assertEquals(Task.Stopped, fsm.getCurrentState());
        transitions.delete();
        assertEquals(Task.Deleted, fsm.getCurrentState());
        transitions.delete();
        assertEquals(Task.Deleted, fsm.getCurrentState());
        transitions.stop();
        assertEquals(Task.Deleted, fsm.getCurrentState());
    }
}
