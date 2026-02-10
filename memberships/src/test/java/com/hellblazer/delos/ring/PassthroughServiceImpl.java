/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.ring;

import com.google.protobuf.Any;
import io.grpc.stub.StreamObserver;

/**
 * Test service implementation that passes through the actual response from the local service.
 * Unlike ServiceImpl which always returns a ByteMessage, this returns whatever local.ping() returns.
 *
 * @author hal.hildebrand
 */
public class PassthroughServiceImpl implements TestIt {
    private final TestItService local;

    public PassthroughServiceImpl(TestItService local) {
        this.local = local;
    }

    @Override
    public void ping(Any request, StreamObserver<Any> responseObserver) {
        var result = local.ping(request);
        responseObserver.onNext(result);
        responseObserver.onCompleted();
    }
}
