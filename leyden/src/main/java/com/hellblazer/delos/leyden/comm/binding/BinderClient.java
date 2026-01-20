/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.leyden.comm.binding;

import com.hellblazer.delos.archipelago.Link;
import com.hellblazer.delos.leyden.proto.Binding;
import com.hellblazer.delos.leyden.proto.Bound;
import com.hellblazer.delos.leyden.proto.Key;

/**
 * @author hal.hildebrand
 **/
public interface BinderClient extends Link {

    void bind(Binding binding);

    Bound get(Key key);

    void unbind(Key key);
}
