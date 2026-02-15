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
 * Thrown when cryptographic or state validation fails for a quorum response. Carries the set of suspected Byzantine
 * members that provided the invalid response.
 * <p>
 * Note: Declared non-sealed instead of final to support testing frameworks (Mockito) that require subclassing.
 * Production code should not extend this class.
 * </p>
 *
 * @author hal.hildebrand
 */
public non-sealed class DhtValidationException extends DhtException {

    private final String      operation;
    private final String      validationDetail;
    private final Set<Member> suspectedMembers;

    public DhtValidationException(String operation, String validationDetail, Set<Member> suspectedMembers) {
        super("Validation failed for %s: %s (suspects: %d members)".formatted(operation, validationDetail,
                                                                              suspectedMembers.size()));
        this.operation = operation;
        this.validationDetail = validationDetail;
        this.suspectedMembers = Set.copyOf(suspectedMembers);
    }

    public String operation() {
        return operation;
    }

    public String validationDetail() {
        return validationDetail;
    }

    public Set<Member> suspectedMembers() {
        return suspectedMembers;
    }
}
