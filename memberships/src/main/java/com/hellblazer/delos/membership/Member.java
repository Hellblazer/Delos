/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.membership;

import java.io.InputStream;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.Verifier;

/**
 * Core member interface providing identity-agnostic membership functionality.
 * Members are uniquely identified by a Digest and can verify signatures.
 * <p>
 * For X509 certificate-based members, see {@link CertificateMember}.
 *
 * @author hal.hildebrand
 */
public interface Member extends Comparable<Member>, Verifier {

    @Override
    int compareTo(Member o);

    // The id of a member uniquely identifies it
    @Override
    boolean equals(Object obj);

    /**
     * @return the unique id of this member
     */
    Digest getId();

    @Override
    int hashCode();

    boolean verify(JohnHancock signature, InputStream message);

}
