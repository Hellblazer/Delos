/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.archipelago;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.hellblazer.delos.archipelago.RouterImpl.CommonCommunications;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.impl.SigningMemberImpl;
import com.hellblazer.delos.test.proto.ByteMessage;
import com.hellblazer.delos.test.proto.TestItGrpc;
import com.hellblazer.delos.test.proto.TestItGrpc.TestItBlockingStub;
import com.hellblazer.delos.test.proto.TestItGrpc.TestItImplBase;
import com.hellblazer.delos.utils.Utils;
import io.grpc.stub.StreamObserver;
import org.joou.ULong;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * @author hal.hildebrand
 */
public class LocalServerTest {
    private final TestItService local = new TestItService() {

        @Override
        public void close() throws IOException {
        }

        @Override
        public Member getMember() {
            return null;
        }

        @Override
        public Any ping(Any request) {
            return null;
        }
    };

    @Test
    public void smokin() throws Exception {
        final var memberA = new SigningMemberImpl(Utils.getMember(0), ULong.MIN);
        final var memberB = new SigningMemberImpl(Utils.getMember(1), ULong.MIN);
        final var ctxA = DigestAlgorithm.DEFAULT.getOrigin().prefix(0x666);
        final var prefix = UUID.randomUUID().toString();

        RouterSupplier serverA = new LocalServer(prefix, memberA);
        var routerA = serverA.router(ServerConnectionCache.newBuilder());

        CommonCommunications<TestItService, TestIt> commsA = routerA.create(memberA, ctxA, new ServerA(), "A",
                                                                            r -> new Server(r),
                                                                            c -> new TestItClient(c), local);

        RouterSupplier serverB = new LocalServer(prefix, memberB);
        var routerB = serverB.router(ServerConnectionCache.newBuilder());

        CommonCommunications<TestItService, TestIt> commsA_B = routerB.create(memberB, ctxA, new ServerB(), "B",
                                                                              r -> new Server(r),
                                                                              c -> new TestItClient(c), local);

        routerA.start();
        routerB.start();

        var clientA = commsA.connect(memberB);

        var resultA = clientA.ping(Any.getDefaultInstance());
        assertNotNull(resultA);
        assertEquals("Hello Server B", resultA.unpack(ByteMessage.class).getContents().toStringUtf8());

        var clientB = commsA_B.connect(memberA);
        var resultB = clientB.ping(Any.getDefaultInstance());
        assertNotNull(resultB);
        assertEquals("Hello Server A", resultB.unpack(ByteMessage.class).getContents().toStringUtf8());

        routerA.close(Duration.ofSeconds(0));
        routerB.close(Duration.ofSeconds(0));
    }

    public interface TestIt {
        void ping(Any request, StreamObserver<Any> responseObserver);
    }

    public interface TestItService extends Link {
        Any ping(Any request);
    }

    public static class Server extends TestItImplBase {
        private final RoutableService<TestIt> router;

        public Server(RoutableService<TestIt> router) {
            this.router = router;
        }

        @Override
        public void ping(Any request, StreamObserver<Any> responseObserver) {
            router.evaluate(responseObserver, (t, token) -> t.ping(request, responseObserver));
        }
    }

    public static class TestItClient implements TestItService {
        private final TestItBlockingStub   client;
        private final ManagedServerChannel connection;

        public TestItClient(ManagedServerChannel c) {
            this.connection = c;
            client = c.wrap(TestItGrpc.newBlockingStub(c));
        }

        @Override
        public void close() throws IOException {
            connection.release();
        }

        @Override
        public Member getMember() {
            return connection.getMember();
        }

        @Override
        public Any ping(Any request) {
            return client.ping(request);
        }
    }

    public class ServerA implements TestIt {
        @Override
        public void ping(Any request, StreamObserver<Any> responseObserver) {
            responseObserver.onNext(
            Any.pack(ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("Hello Server A")).build()));
            responseObserver.onCompleted();
        }
    }

    public class ServerB implements TestIt {
        @Override
        public void ping(Any request, StreamObserver<Any> responseObserver) {
            responseObserver.onNext(
            Any.pack(ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("Hello Server B")).build()));
            responseObserver.onCompleted();
        }
    }
}
