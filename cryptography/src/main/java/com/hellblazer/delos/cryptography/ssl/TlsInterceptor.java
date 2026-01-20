/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.cryptography.ssl;

import javax.net.ssl.SSLSession;

import io.grpc.Context;
import io.grpc.Context.Key;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

public class TlsInterceptor implements ServerInterceptor {
    private final Key<SSLSession> sslSessionContext;

    public TlsInterceptor(Key<SSLSession> sslSessionContext) {
        this.sslSessionContext = sslSessionContext;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
                                                                 ServerCallHandler<ReqT, RespT> next) {
        SSLSession sslSession = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
        if (sslSession == null) {
            return next.startCall(call, headers);
        }
        return Contexts.interceptCall(Context.current().withValue(sslSessionContext, sslSession), call, headers, next);
    }
}
