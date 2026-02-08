/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.choam.proto.ViewMember;

import java.security.KeyPair;

/**
 * Represents the next view to be activated, including the view member and consensus key pair.
 *
 * @param member            the view member descriptor
 * @param consensusKeyPair  the key pair for BFT consensus in this view
 * @author hal.hildebrand
 */
public record NextView(ViewMember member, KeyPair consensusKeyPair) {
}
