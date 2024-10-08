package com.hellblazer.delos.ring;

import com.google.protobuf.Any;
import com.hellblazer.delos.test.proto.TestItGrpc;
import com.hellblazer.delos.archipelago.RoutableService;
import io.grpc.stub.StreamObserver;

public class ServerImpl extends TestItGrpc.TestItImplBase {
    private final RoutableService<TestIt> router;

    public ServerImpl(RoutableService<TestIt> router) {
        this.router = router;
    }

    @Override
    public void ping(Any request, StreamObserver<Any> responseObserver) {
        router.evaluate(responseObserver, t -> t.ping(request, responseObserver));
    }
}
