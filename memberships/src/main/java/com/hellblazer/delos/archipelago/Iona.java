package com.hellblazer.delos.archipelago;

import com.hellblazer.delos.archipelago.server.FernetServerInterceptor;
import com.netflix.concurrency.limits.Limit;
import com.netflix.concurrency.limits.MetricRegistry;
import io.grpc.ServerInterceptor;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A secure network server using GRPC over the Noise protocol, instead of MTLS
 * <p>
 * NOTE: Noise protocol implementation is not currently available. This class
 * exists for future use when Noise protocol support is added.
 *
 * @author hal.hildebrand
 * @deprecated Noise protocol not implemented. Use {@link MtlsServer} instead.
 **/
@Deprecated(since = "0.5.1", forRemoval = true)
public class Iona implements RouterSupplier {

    @Override
    public Router router(ServerConnectionCache.Builder cacheBuilder, Supplier<Limit> serverLimit,
                         MetricRegistry limitsRegistry, List<ServerInterceptor> interceptors,
                         Predicate<FernetServerInterceptor.HashedToken> validator, ExecutorService executor) {
        throw new UnsupportedOperationException("Noise protocol not implemented. Use MtlsServer instead.");
    }
}
