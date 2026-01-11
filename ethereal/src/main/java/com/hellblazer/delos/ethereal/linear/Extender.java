/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.ethereal.linear;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.ethereal.Config;
import com.hellblazer.delos.ethereal.Dag;
import com.hellblazer.delos.ethereal.Unit;
import com.hellblazer.delos.ethereal.linear.UnanimousVoter.SuperMajorityDecider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Extender is a type that implements an algorithm that extends order of units provided by an instance of a Dag to a
 * linear order.
 *
 * @author hal.hildebrand
 */
public class Extender {
    private static final int    FIRST_DECIDED_ROUND = 3;
    private static final Logger log                 = LoggerFactory.getLogger(Extender.class);

    private final Config                                conf;
    private final Dag                                   dag;
    private final Map<Digest, SuperMajorityDecider> deciders = new ConcurrentHashMap<>();
    private final String                                logLabel;

    public Extender(Dag dag, Config conf) {
        this.dag = dag;
        this.conf = conf;
        logLabel = conf.logLabel();
    }

    /**
     * roundSorter picks information about newly picked timing unit from the timingRounds channel, finds all units
     * belonging to their timing round and establishes linear order on them. Sends slices of ordered units to output.
     */
    public TimingRound chooseNextTimingUnits(TimingRound lastTU, Consumer<List<Unit>> output) {
        TimingRound next;
        TimingRound last = lastTU;

        do {
            log.trace("Choose TR, last: {} on: {}", lastTU, conf.logLabel());
            next = nextRound(last);
            if (next != null && !next.equals(last)) {
                var units = next.orderedUnits(conf.digestAlgorithm(), conf.logLabel());
                log.trace("Output of: {} preBlock: {} on: {}", next, units, conf.logLabel());
                output.accept(units);
                last = next;
            } else {
                log.trace("Exit choose TR, last: {} on: {}", next, conf.logLabel());
                return next;
            }
        } while (next != null && !next.equals(last));
        log.trace("Exit choose TR, last: {} on: {}", next, conf.logLabel());
        return next;
    }

    public TimingRound nextRound(TimingRound lastTU) {
        var dagMaxLevel = dag.maxLevel();
        log.trace("Begin round, {} dag mxLvl: {} on: {}", lastTU, dagMaxLevel, FIRST_DECIDED_ROUND, logLabel);
        var level = 0;
        final Unit previousTU = lastTU == null ? null : lastTU.currentTU();
        if (previousTU != null) {
            level = lastTU.level() + 1;
        }
        if (dagMaxLevel < level + FIRST_DECIDED_ROUND) {
            log.trace("No round, dag mxLvl: {} is < ({} + {}) on: {}", dagMaxLevel, level, FIRST_DECIDED_ROUND,
                      logLabel);
            return lastTU;
        }

        var units = dag.unitsOnLevel(level);

        var decided = false;
        Unit currentTU = null;

        for (Unit uc : permutation(level, units, previousTU)) {
            if (uc == null) {
                continue;
            }
            var decision = getDecider(uc, deciders).decideUnitIsPopular(dagMaxLevel);
            if (decision.decision() == Vote.POPULAR) {
                currentTU = uc;
                decided = true;
                deciders.clear();
                log.trace("Popular: {} decided on: {} level: {} max: {} on: {}", uc, decision.decisionLevel(), level,
                          dagMaxLevel, logLabel);
                break;
            }
            if (decision.decision() == Vote.UNDECIDED) {
                log.trace("Undecided: {} decided on: {} level: {} max: {} on: {}", uc, decision.decisionLevel(), level,
                          dagMaxLevel, logLabel);
                break;
            }
            log.trace("Unpopular: {} decided on: {} level: {} max: {} on: {}", uc, decision.decisionLevel(), level,
                      dagMaxLevel, logLabel);

        }
        if (!decided) {
            log.trace("No round decided, dag mxLvl: {} level: {} max: {} on: {}", dagMaxLevel, level, logLabel);
            return lastTU;
        }
        final var current = new TimingRound(currentTU, level, lastTU == null ? null : lastTU.currentTU());
        log.trace("{} dag mxLvl: {} on: {}", current, dagMaxLevel, logLabel);
        return current;
    }

    private SuperMajorityDecider getDecider(Unit uc, Map<Digest, SuperMajorityDecider> deciders) {
        return deciders.computeIfAbsent(uc.hash(), h -> new SuperMajorityDecider(
        new UnanimousVoter(dag, uc, new ConcurrentHashMap<>(), logLabel)));
    }

    private List<Unit> permutation(int level, List<Unit> unitsOnLevel, Unit previousTU) {
        final var pidOrder = pidOrder(level, previousTU);
        List<Unit> permutation = pidOrder.stream().map(s -> unitsOnLevel.get(s)).toList();
        if (log.isTraceEnabled()) {
            log.trace("CRP level: {} permutation: {} pidOrder: {} previous: {} on: {}", level,
                      permutation.stream().map(e -> e == null ? null : e.shortString()).toList(), pidOrder,
                      previousTU == null ? null : previousTU.shortString(), logLabel);
        }
        return permutation;
    }

    /**
     * Deterministic linear extension per Aleph-BFT §4, Algorithm 2.
     * Uses cryptographic hash-based ordering for process ID permutation to ensure determinism
     * across all JVM versions, vendors, and architectures.
     *
     * Previous implementation used Collections.shuffle(Random), which is not provably
     * deterministic across JVM versions. This implementation uses explicit hash-based ordering
     * via cryptographic digest, providing mathematical guarantee of determinism.
     */
    private List<Short> pidOrder(int level, Unit tu) {
        var pids = new ArrayList<Short>();
        for (int pid = 0; pid < conf.nProc(); pid++) {
            pids.add((short) pid);
        }
        if (tu == null) {
            return pids;
        }
        // Deterministic ordering via cryptographic hash-based comparison
        // For each process ID, compute H(unit_hash || pid) and sort by hash value
        // This ensures all honest nodes compute identical permutation (Aleph §4 requirement)
        pids.sort((pid1, pid2) -> {
            var hash1 = computeProcessHash(tu, pid1);
            var hash2 = computeProcessHash(tu, pid2);
            return hash1.compareTo(hash2);
        });
        return pids;
    }

    /**
     * Compute deterministic hash for process ID in timing unit context.
     * Hash(unit_hash || pid) provides cryptographically-bound ordering.
     * Result is deterministic across all JVM implementations.
     */
    private Digest computeProcessHash(Unit tu, short pid) {
        var buffer = ByteBuffer.allocate(Short.BYTES + Long.BYTES);
        buffer.putLong(tu.hash().fold());
        buffer.putShort(pid);
        return conf.digestAlgorithm().digest(buffer.array());
    }
}
