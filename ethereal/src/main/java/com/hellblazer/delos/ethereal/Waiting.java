/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.ethereal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.hellblazer.delos.ethereal.proto.PreUnit_s;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.ethereal.Adder.State;

/**
 * @author hal.hildebrand
 */
public class Waiting implements Comparable<Waiting> {

    private final    List<Waiting>          children       = new ArrayList<>();
    private final    PreUnit                pu;
    private final    PreUnit_s              serialized;
    private volatile Unit                   decoded;
    private volatile int                    missingParents = 0;
    private          AtomicReference<State> state          = new AtomicReference<>(State.PROPOSED);
    private volatile int                    waitingParents = 0;

    /**
     * LIVENESS (Delos-vyai): Timestamp when unit arrived and entered waiting state.
     * Used to detect Byzantine withholding - if unit waits too long for parents,
     * timeout triggers recovery mechanism (broadcast request to peers).
     */
    private final    long                   arrivedAt      = System.currentTimeMillis();

    public Waiting(PreUnit pu) {
        this(pu, pu.toPreUnit_s());
    }

    public Waiting(PreUnit pu, PreUnit_s serialized) {
        this.pu = pu;
        this.serialized = serialized;
    }

    public void addChild(Waiting wp) {
        children.add(wp);
    }

    public List<Waiting> children() {
        return children;
    }

    public void clearAndAdd(List<Waiting> c) {
        children.clear();
        children.addAll(c);
    }

    public void clearChildren() {
        children.clear();
    }

    @Override
    public int compareTo(Waiting o) {
        var comp = Short.compare(creator(), o.creator());
        if (comp < 0 || comp > 0) {
            return comp;
        }
        return Integer.compare(height(), o.height());

    }

    public short creator() {
        return pu.creator();
    }

    public synchronized void decMissing() {
        missingParents--;
    }

    public synchronized void decWaiting() {
        waitingParents--;
    }

    public Unit decoded() {
        return decoded;
    }

    public int epoch() {
        return pu.epoch();
    }

    public Digest hash() {
        return pu.hash();
    }

    public int height() {
        return pu.height();
    }

    public Long id() {
        return pu.id();
    }

    public synchronized void incMissing() {
        missingParents++;
    }

    public synchronized void incWaiting() {
        waitingParents++;
    }

    public synchronized int missingParents() {
        return missingParents;
    }

    /**
     * Composite atomicity check for parent output readiness.
     * CRITICAL: Synchronization ensures both missing and waiting counters are checked atomically.
     * Prevents TOCTOU (Time-of-Check-Time-of-Use) race where another thread could modify counters
     * between the check and use, potentially causing incorrect consensus decisions.
     */
    public synchronized boolean parentsOutput() {
        return waitingParents == 0 && missingParents == 0;
    }

    public PreUnit pu() {
        return pu;
    }

    public PreUnit_s serialized() {
        return serialized;
    }

    public void setDecoded(Unit decoded) {
        this.decoded = decoded;
    }

    public void setState(State state) {
        this.state.set(state);
    }

    public State state() {
        return state.get();
    }

    @Override
    public String toString() {
        return hash() + ":" + state() + ":[" + pu.shortString() + "]" + "(" + missingParents + "," + waitingParents
        + ")";
    }

    public synchronized int waitingParents() {
        return waitingParents;
    }

    /**
     * LIVENESS (Delos-vyai): Get arrival timestamp of this waiting unit.
     *
     * @return milliseconds since epoch when unit entered waiting state
     */
    public long getArrivalTime() {
        return arrivedAt;
    }

    /**
     * LIVENESS (Delos-vyai): Check if unit is stale (waiting beyond timeout threshold).
     *
     * Stale units indicate Byzantine withholding - parent units needed for RBC progression
     * are not being delivered. This is used to trigger recovery mechanisms.
     *
     * @param timeoutMs timeout threshold in milliseconds
     * @return true if unit has been waiting longer than timeoutMs
     */
    public boolean isStaleAfterMillis(long timeoutMs) {
        return System.currentTimeMillis() - arrivedAt > timeoutMs;
    }
}
