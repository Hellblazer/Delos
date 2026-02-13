/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth.exception;

import com.hellblazer.delos.membership.Member;

import java.util.Set;

/**
 * Thrown when the returned state does not match the expected chain consistency for a quorum response.
 *
 * @author hal.hildebrand
 */
public final class DhtStateConsistencyException extends DhtValidationException {

    public DhtStateConsistencyException(String operation, String validationDetail, Set<Member> suspectedMembers) {
        super(operation, validationDetail, suspectedMembers);
    }
}
