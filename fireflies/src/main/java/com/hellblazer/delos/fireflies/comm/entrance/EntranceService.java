/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.fireflies.comm.entrance;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.fireflies.proto.JoinResponse;
import com.hellblazer.delos.fireflies.proto.Join;
import com.hellblazer.delos.fireflies.proto.Redirect;
import com.hellblazer.delos.fireflies.proto.Registration;
import io.grpc.stub.StreamObserver;

/**
 * @author hal.hildebrand
 */
public interface EntranceService {

    void join(Join request, Digest from, StreamObserver<JoinResponse> responseObserver, long startNanos);

    Redirect seed(Registration request, Digest from);
}
