package com.hellblazer.delos.leyden.comm.reconcile;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.leyden.proto.Intervals;
import com.hellblazer.delos.leyden.proto.Update;
import com.hellblazer.delos.leyden.proto.Updating;

/**
 * @author hal.hildebrand
 **/
public interface ReconciliationService {
    Update reconcile(Intervals request, Digest from);

    void update(Updating request, Digest from);
}
