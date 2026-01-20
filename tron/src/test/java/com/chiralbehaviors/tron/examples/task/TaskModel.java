/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.chiralbehaviors.tron.examples.task;

/**
 * 
 * @author hhildebrand
 * 
 */
public interface TaskModel {
    void blockTask();

    void continueTask();

    void releaseResources();

    void startSliceTimer(long timeslice);

    void stopSliceTimer();

    void stopTask();

    void suspendTask();
}
