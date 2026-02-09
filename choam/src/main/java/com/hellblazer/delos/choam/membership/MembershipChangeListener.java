/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.membership;

import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.stereotomy.event.proto.KERL_;

/**
 * Listener interface for membership change events. Implementations receive notifications
 * when members join, leave, or the view changes.
 * <p>
 * Thread Safety: Listener methods may be called from membership protocol threads.
 * Implementations must be thread-safe and should avoid blocking operations.
 *
 * @author hal.hildebrand
 */
public interface MembershipChangeListener {

    /**
     * Called when a member joins the membership view.
     * <p>
     * Thread Safety: Called from membership protocol threads. Implementations should
     * avoid blocking and complete quickly.
     *
     * @param member the member that joined
     */
    void onMemberJoined(Member member);

    /**
     * Called when a member leaves the membership view (gracefully or due to failure).
     * <p>
     * Thread Safety: Called from membership protocol threads. Implementations should
     * avoid blocking and complete quickly.
     *
     * @param member the member that left
     */
    void onMemberLeft(Member member);

    /**
     * Called when the membership view changes (e.g., reconfiguration, view transition).
     * <p>
     * The view change includes the new KERL (Key Event Receipt Log) representing the
     * new membership configuration.
     * <p>
     * Thread Safety: Called from membership protocol threads. Implementations should
     * avoid blocking and complete quickly.
     *
     * @param viewChange the view change notification with new KERL
     */
    void onViewChange(KERL_ viewChange);
}
