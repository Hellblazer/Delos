/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.chiralbehaviors.tron.examples.task;

import com.chiralbehaviors.tron.FsmExecutor;

/**
 * 
 * @author hhildebrand
 * 
 */
public interface TaskFsm extends FsmExecutor<TaskModel, TaskFsm> {
    default TaskFsm block() {
        return null; // loopback transition
    }

    default TaskFsm delete() {
        return null; // loopback transition
    }

    default TaskFsm done() {
        return null; // loopback transition
    }

    default TaskFsm start(long timeslice) {
        return null; // loopback transition
    }

    default TaskFsm stop() {
        return null; // loopback transition
    }

    default TaskFsm stopped() {
        return null; // loopback transition
    }

    default TaskFsm suspended() {
        return null; // loopback transition
    }

    default TaskFsm unblock() {
        return null; // loopback transition
    }
}
