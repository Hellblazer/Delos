/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.comm.grpc;

import io.grpc.*;

/**
 * Server interceptor for Unix domain socket connections.
 * Previously extracted peer credentials, but NIO domain sockets don't support this.
 * Now a simple pass-through interceptor for compatibility.
 *
 * @author hal.hildebrand
 */
public class DomainSocketServerInterceptor implements ServerInterceptor {

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                                                                 final Metadata requestHeaders,
                                                                 ServerCallHandler<ReqT, RespT> next) {
        // Pass through - NIO domain sockets don't provide peer credentials
        return next.startCall(call, requestHeaders);
    }

}
