/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.gorgoneion.comm.admissions;

import com.codahale.metrics.Timer.Context;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.gorgoneion.proto.Credentials;
import com.hellblazer.delos.gorgoneion.proto.Establishment;
import com.hellblazer.delos.gorgoneion.proto.SignedNonce;
import com.hellblazer.delos.stereotomy.event.proto.KERL_;
import io.grpc.stub.StreamObserver;

/**
 * @author hal.hildebrand
 */
public interface AdmissionsService {

    void apply(KERL_ application, Digest from, StreamObserver<SignedNonce> responseObserver, Context timer);

    void register(Credentials request, Digest from, StreamObserver<Establishment> responseObserver, Context timer);

}
