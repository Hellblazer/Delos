/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.choam.proto.SignedViewMember;
import com.hellblazer.delos.choam.proto.SubmitResult;
import com.hellblazer.delos.choam.proto.Transaction;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.Signer;
import com.hellblazer.delos.cryptography.Signer.SignerImpl;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.membership.Member;
import io.grpc.StatusRuntimeException;
import org.joou.ULong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static io.grpc.Status.INVALID_ARGUMENT;

/**
 * A member of the current committee with block production capabilities.
 * Associates participate in consensus by producing and validating blocks.
 * <p>
 * Extracted from CHOAM.java (Phase 3.2).
 * </p>
 *
 * @author hal.hildebrand
 */
class Associate extends Administration {
    private static final Logger log = LoggerFactory.getLogger(Associate.class);

    private final Producer producer;

    /**
     * Construct an Associate committee member.
     *
     * @param choam      CHOAM coordinator instance
     * @param viewChange the certified block triggering this view change
     * @param validators committee member validators
     * @param nextView   the next view configuration
     */
    Associate(CHOAM choam, HashedCertifiedBlock viewChange, Map<Member, Verifier> validators, NextView nextView) {
        super(choam, validators, new Digest(
        viewChange.block.hasGenesis() ? viewChange.block.getGenesis().getInitialView().getId()
                                      : viewChange.block.getReconfigure().getId()));
        var context = new StaticContext<>(viewId, choam.params().context().getProbabilityByzantine(), 3,
                                          validators.keySet(), choam.params().context().getEpsilon(), validators.size());
        log.trace("Using consensus key: {} sig: {} for view: {} on: {}",
                  choam.params().digestAlgorithm().digest(nextView.consensusKeyPair().getPublic().getEncoded()),
                  choam.params().digestAlgorithm().digest(nextView.member().getSignature().toByteString()), viewId,
                  choam.params().member().getId());
        Signer signer = new SignerImpl(nextView.consensusKeyPair().getPrivate(), ULong.MIN);
        var pv = choam.pendingViews();
        producer = new Producer(choam.viewStateHolder().getNextViewId(),
                                new ViewContext(context, choam.params(), pv, signer, validators, choam.constructBlock()),
                                choam.blockChainState().getHead(), choam.getCheckpointManager().currentCheckpoint(), choam.getLabel(), choam.getScheduler());
        producer.start();
    }

    @Override
    public void complete() {
        producer.stop();
    }

    @Override
    public void join(SignedViewMember nextView, Digest from) {
        if (!from.equals(Digest.from(nextView.getVm().getId()))) {
            log.trace("Join from: {} does not match {} from join: {} diadem: {} on: {}", from,
                      Digest.from(nextView.getVm().getId()), Digest.from(nextView.getVm().getView()),
                      Digest.from(nextView.getVm().getDiadem()), choam.params().member().getId());
            throw new StatusRuntimeException(INVALID_ARGUMENT);
        }
        producer.join(nextView);
    }

    @Override
    public SubmitResult submit(Transaction request) {
        return producer.submit(request);
    }
}
