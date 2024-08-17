package com.hellblazer.delos.leyden.comm.reconcile;

import com.hellblazer.delos.archipelago.Link;
import com.hellblazer.delos.leyden.proto.Intervals;
import com.hellblazer.delos.leyden.proto.Update;
import com.hellblazer.delos.leyden.proto.Updating;

/**
 * @author hal.hildebrand
 **/
public interface ReconciliationClient extends Link {
    Update reconcile(Intervals intervals);

    void update(Updating updating);
}
