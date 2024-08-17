package com.hellblazer.delos.leyden.comm.binding;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.leyden.proto.Binding;
import com.hellblazer.delos.leyden.proto.Bound;
import com.hellblazer.delos.leyden.proto.Key;

/**
 * @author hal.hildebrand
 **/
public interface BinderService {
    void bind(Binding request, Digest from);

    Bound get(Key request, Digest from);

    void unbind(Key request, Digest from);
}
