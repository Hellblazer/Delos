/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.choam.comm.Submitter;
import com.hellblazer.delos.choam.proto.SubmitResult;
import com.hellblazer.delos.choam.proto.Transaction;
import com.hellblazer.delos.cryptography.Digest;

/**
 * Transaction submission service implementation.
 * Delegates transaction submission to the CHOAM coordinator.
 * <p>
 * Extracted from CHOAM.java (Phase 3.2).
 * </p>
 *
 * @author hal.hildebrand
 */
class TransSubmission implements Submitter {
    private final CHOAM choam;

    /**
     * Construct a TransSubmission service.
     *
     * @param choam CHOAM coordinator instance
     */
    TransSubmission(CHOAM choam) {
        this.choam = choam;
    }

    @Override
    public SubmitResult submit(Transaction request, Digest from) {
        return choam.submit(request, from);
    }
}
