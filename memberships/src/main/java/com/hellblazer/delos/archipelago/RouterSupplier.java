/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.archipelago;

import com.netflix.concurrency.limits.Limit;
import com.hellblazer.delos.archipelago.server.FernetServerInterceptor;
import com.hellblazer.delos.protocols.LimitsRegistry;
import io.grpc.ServerInterceptor;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * @author hal.hildebrand
 */
public interface RouterSupplier {
    default Router router() {
        return router(ServerConnectionCache.newBuilder(), RouterImpl::defaultServerLimit, null);
    }

    default Router router(ServerConnectionCache.Builder cacheBuilder) {
        return router(cacheBuilder, RouterImpl::defaultServerLimit, null);
    }

    default Router router(ServerConnectionCache.Builder cacheBuilder, ExecutorService executor) {
        return router(cacheBuilder, RouterImpl::defaultServerLimit, null, Collections.emptyList(), null, executor);
    }

    default Router router(ServerConnectionCache.Builder cacheBuilder, Supplier<Limit> serverLimit,
                          LimitsRegistry limitsRegistry) {
        return router(cacheBuilder, serverLimit, limitsRegistry, Collections.emptyList());
    }

    default Router router(ServerConnectionCache.Builder cacheBuilder, Supplier<Limit> serverLimit,
                          LimitsRegistry limitsRegistry, List<ServerInterceptor> interceptors) {
        return router(cacheBuilder, serverLimit, limitsRegistry, interceptors, null);
    }

    default Router router(ServerConnectionCache.Builder cacheBuilder, Supplier<Limit> serverLimit,
                          LimitsRegistry limitsRegistry, List<ServerInterceptor> interceptors,
                          Predicate<FernetServerInterceptor.HashedToken> validator) {
        return router(cacheBuilder, serverLimit, limitsRegistry, interceptors, validator, null);

    }

    Router router(ServerConnectionCache.Builder cacheBuilder, Supplier<Limit> serverLimit,
                  LimitsRegistry limitsRegistry, List<ServerInterceptor> interceptors,
                  Predicate<FernetServerInterceptor.HashedToken> validator, ExecutorService executor);
}
