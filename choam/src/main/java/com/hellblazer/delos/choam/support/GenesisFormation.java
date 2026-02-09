/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.archipelago.RouterImpl.CommonCommunications;
import com.hellblazer.delos.choam.CHOAM;
import com.hellblazer.delos.choam.Committee;
import com.hellblazer.delos.choam.GenesisAssembly;
import com.hellblazer.delos.choam.GenesisContext;
import com.hellblazer.delos.choam.Parameters;
import com.hellblazer.delos.choam.comm.Terminal;
import com.hellblazer.delos.choam.proto.SignedViewMember;
import com.hellblazer.delos.choam.proto.ViewMember;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.Signer;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import org.joou.ULong;
import org.slf4j.Logger;

/**
 * Genesis formation committee for bootstrapping the CHOAM consensus
 *
 * @author hal.hildebrand
 */
public class GenesisFormation implements Committee {
    private final GenesisAssembly  assembly;
    private final CHOAM            choam;
    private final Context<Member>  formation;
    private final Logger           log;

    public GenesisFormation(CHOAM choam, Logger log) {
        this.choam = choam;
        this.log = log;
        var params = choam.params();
        formation = Committee.viewFor(params.genesisViewId(), params.context());
        if (formation.isMember(params.member()) && params.generateGenesis()) {
            final var c = choam.getNextView();
            log.trace("Using genesis consensus key: {} sig: {} on: {}",
                      params.digestAlgorithm().digest(c.consensusKeyPair().getPublic().getEncoded()),
                      params.digestAlgorithm().digest(c.member().getSignature().toByteString()),
                      params.member().getId());
            // During Genesis, use member identity key for signing to match GenesisContext.verifiersByPid()
            // which returns member identity verifiers. Consensus keys aren't exchanged until Join messages
            // are processed after Genesis consensus completes.
            // Cache the signer to avoid repeated KERL/keystore lookups on every sign() operation during Genesis.
            // ControlledIdentifierMember.sign() calls identifier.getSigner() which does expensive lookups each time.
            // By caching the Signer here, Genesis signatures use the cached instance avoiding the overhead.
            Signer genesisSigner = ((ControlledIdentifierMember) params.member()).getIdentifier().getSigner();
            if (genesisSigner == null) {
                throw new IllegalStateException(
                "Cannot obtain signer for Genesis from member: " + params.member().getId());
            }
            log.trace("Cached Genesis signer: {} for member: {} on: {}", genesisSigner.getClass().getSimpleName(),
                      params.member().getId(), params.member().getId());
            var supp = choam.pendingViews();
            var vc = new GenesisContext(formation, supp, params, genesisSigner, choam.constructBlock());
            var inView = ViewMember.newBuilder(c.member()).setView(params.genesisViewId().toDigeste()).build();
            var svm = SignedViewMember.newBuilder()
                                      .setVm(inView)
                                      .setSignature(params.member().sign(inView.toByteString()).toSig())
                                      .build();
            assembly = new GenesisAssembly(vc, choam.getComm(), svm, choam.getLabel(), choam.getScheduler());
            log.info("Setting next view id to genesis: {} on: {}", params.genesisViewId(), params.member().getId());
            choam.setNextViewId(params.genesisViewId());
        } else {
            log.trace("No formation on: {}", params.member().getId());
            assembly = null;
        }
    }

    @Override
    public void accept(HashedCertifiedBlock hb) {
        assert hb.height().equals(ULong.valueOf(0));
        choam.acceptGenesis(hb);
    }

    @Override
    public void complete() {
        if (assembly != null) {
            assembly.stop();
        }
    }

    @Override
    public boolean isMember() {
        return formation.isMember(choam.params().member());
    }

    @Override
    public Logger log() {
        return log;
    }

    @Override
    public void nextView(Digest diadem, Context<Member> pendingView) {
        log.info("Cancelling formation, acquiring new view, size: {} on: {}", pendingView.size(),
                 choam.params().member().getId());
        choam.params().context().setContext(pendingView);
        choam.setPendingViews(choam.getPendingViews().add(diadem, pendingView));

        choam.transitionsNextView();
    }

    @Override
    public Parameters params() {
        return choam.params();
    }

    @Override
    public void regenerate() {
        if (assembly != null) {
            assembly.start();
        }
    }

    @Override
    public boolean validate(HashedCertifiedBlock hb) {
        var block = hb.block;
        if (!block.hasGenesis()) {
            log.debug("Invalid genesis block: {} on: {}", hb.hash, choam.params().member().getId());
            return false;
        }
        return validateRegeneration(hb);
    }
}
