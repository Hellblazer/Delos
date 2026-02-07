/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.CHOAM;
import com.hellblazer.delos.choam.Committee;
import com.hellblazer.delos.choam.Parameters;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.membership.Member;
import org.slf4j.Logger;

import java.util.Map;

/**
 * Synchronizer committee implementation for CHOAM synchronization protocol
 *
 * @author hal.hildebrand
 */
public class CommitteeSynchronizer implements Committee {
    private final CHOAM                   choam;
    private final Logger                  log;
    private final Map<Member, Verifier>   validators;

    public CommitteeSynchronizer(CHOAM choam, Map<Member, Verifier> validators, Logger log) {
        this.choam = choam;
        this.validators = validators;
        this.log = log;
    }

    @Override
    public void accept(HashedCertifiedBlock next) {
        choam.process();
    }

    @Override
    public void complete() {
    }

    @Override
    public boolean isMember() {
        return false;
    }

    @Override
    public Logger log() {
        return log;
    }

    @Override
    public void nextView(Digest diadem, Context<Member> pendingView) {
        log.info("Acquiring new view, size: {} on: {}", pendingView.size(), params().member().getId());
        params().context().setContext(pendingView);
        choam.setPendingViews(choam.getPendingViews().add(diadem, pendingView));
    }

    @Override
    public Parameters params() {
        return choam.params();
    }

    @Override
    public boolean validate(HashedCertifiedBlock hb) {
        return Committee.super.validate(hb, validators);
    }
}
