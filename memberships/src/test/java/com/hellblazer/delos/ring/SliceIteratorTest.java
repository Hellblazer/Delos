package com.hellblazer.delos.ring;

import com.google.protobuf.Any;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.RouterImpl;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.impl.SigningMemberImpl;
import com.hellblazer.delos.utils.Utils;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.joou.ULong;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author hal.hildebrand
 **/
public class SliceIteratorTest {
    @Test
    public void smokin() throws Exception {
        var serverMember1 = new SigningMemberImpl(Utils.getMember(0), ULong.MIN);
        var serverMember2 = new SigningMemberImpl(Utils.getMember(1), ULong.MIN);
        var pinged1 = new AtomicBoolean();
        var pinged2 = new AtomicBoolean();

        var local1 = new TestItService() {

            @Override
            public void close() throws IOException {
            }

            @Override
            public Member getMember() {
                return serverMember1;
            }

            @Override
            public Any ping(Any request) {
                pinged1.set(true);
                return Any.getDefaultInstance();
            }
        };
        var local2 = new TestItService() {

            @Override
            public void close() throws IOException {
            }

            @Override
            public Member getMember() {
                return serverMember2;
            }

            @Override
            public Any ping(Any request) {
                pinged2.set(true);
                return Any.getDefaultInstance();
            }
        };
        final var name = UUID.randomUUID().toString();
        DynamicContext<Member> context = DynamicContext.newBuilder().build();
        context.activate(serverMember1);
        context.activate(serverMember2);

        var serverBuilder = InProcessServerBuilder.forName(name);
        var cacheBuilder = ServerConnectionCache.newBuilder()
                                                .setFactory(to -> InProcessChannelBuilder.forName(name).build());
        Router router = new RouterImpl(serverMember1, serverBuilder, cacheBuilder, null);
        try {
            RouterImpl.CommonCommunications<TestItService, TestIt> commsA = router.create(serverMember1,
                                                                                          context.getId(),
                                                                                          new ServiceImpl(local1, "A"),
                                                                                          "A", ServerImpl::new,
                                                                                          TestItClient::new, local1);

            RouterImpl.CommonCommunications<TestItService, TestIt> commsB = router.create(serverMember2,
                                                                                          context.getId(),
                                                                                          new ServiceImpl(local2, "B"),
                                                                                          "B", ServerImpl::new,
                                                                                          TestItClient::new, local2);

            router.start();
            var slice = new SliceIterator<TestItService>("Test Me", serverMember1,
                                                         Arrays.asList(serverMember1, serverMember2), commsA);
            var countdown = new CountDownLatch(1);
            slice.iterate((link) -> link.ping(Any.getDefaultInstance()), (_, _, _, _) -> true, () -> {
                countdown.countDown();
            }, Duration.ofMillis(1));
            boolean finished = countdown.await(3, TimeUnit.SECONDS);
            assertTrue(finished, "completed: " + countdown.getCount());
            assertTrue(pinged1.get());
            assertTrue(pinged2.get());
        } finally {
            router.close(Duration.ofSeconds(0));
        }
    }
}
