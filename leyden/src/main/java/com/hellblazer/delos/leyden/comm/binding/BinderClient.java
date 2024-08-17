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
