package com.netflix.concurrency.limits.grpc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Uninterruptibles;
import com.netflix.concurrency.limits.Limit;
import com.netflix.concurrency.limits.Limiter;
import com.netflix.concurrency.limits.grpc.StringMarshaller;
import com.netflix.concurrency.limits.grpc.mockito.OptionalResultCaptor;
import com.netflix.concurrency.limits.limit.FixedLimit;
import com.netflix.concurrency.limits.limiter.SimpleLimiter;

import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;

public class ConcurrencyLimitServerInterceptorTest {

    private static final MethodDescriptor<String, String> METHOD_DESCRIPTOR = MethodDescriptor.<String, String>newBuilder()
                                                                                              .setType(MethodType.UNARY)
                                                                                              .setFullMethodName("service/method")
                                                                                              .setRequestMarshaller(StringMarshaller.INSTANCE)
                                                                                              .setResponseMarshaller(StringMarshaller.INSTANCE)
                                                                                              .build();

    private static final MethodDescriptor<String, String> STREAMING_METHOD_DESCRIPTOR = MethodDescriptor.<String, String>newBuilder()
                                                                                                         .setType(MethodType.SERVER_STREAMING)
                                                                                                         .setFullMethodName("service/streaming")
                                                                                                         .setRequestMarshaller(StringMarshaller.INSTANCE)
                                                                                                         .setResponseMarshaller(StringMarshaller.INSTANCE)
                                                                                                         .build();

    Limiter<GrpcServerRequestContext>      limiter;
    OptionalResultCaptor<Limiter.Listener> listener;

    private ManagedChannel channel;
    private Server         server;

    @AfterEach
    public void afterEachTest() {
        if (server != null) {
            server.shutdown();
            server = null;
        }
        if (channel != null) {
            channel.shutdown();
            channel = null;
        }
    }

    @BeforeEach
    public void beforeEachTest() {
        limiter = Mockito.spy(SimpleLimiter.newBuilder().named("foo").build());

        listener = OptionalResultCaptor.forClass(Limiter.Listener.class);

        Mockito.doAnswer(listener).when(limiter).acquire(Mockito.any());
    }

    @Test
    public void releaseOnCancellation() {
        // Setup server
        startServer((req, observer) -> {
            Uninterruptibles.sleepUninterruptibly(2, TimeUnit.SECONDS);
            observer.onNext("delayed_response");
            observer.onCompleted();
        });

        ListenableFuture<String> future = ClientCalls.futureUnaryCall(channel.newCall(METHOD_DESCRIPTOR,
                                                                                      CallOptions.DEFAULT),
                                                                      "foo");
        Uninterruptibles.sleepUninterruptibly(1, TimeUnit.SECONDS);
        future.cancel(true);

        // Verify
        Mockito.verify(limiter, Mockito.times(1)).acquire(Mockito.isA(GrpcServerRequestContext.class));
        Mockito.verify(listener.getResult().get(), Mockito.times(0)).onIgnore();

        Mockito.verify(listener.getResult().get(), Mockito.timeout(2000).times(1)).onSuccess();

        verifyCounts(0, 0, 1, 0);
    }

    @Test
    public void releaseOnDeadlineExceeded() {
        // Setup server
        startServer((req, observer) -> {
            Uninterruptibles.sleepUninterruptibly(2, TimeUnit.SECONDS);
            observer.onNext("delayed_response");
            observer.onCompleted();
        });

        try {
            ClientCalls.blockingUnaryCall(channel.newCall(METHOD_DESCRIPTOR,
                                                          CallOptions.DEFAULT.withDeadlineAfter(1, TimeUnit.SECONDS)),
                                          "foo");
        } catch (StatusRuntimeException e) {
            assertEquals(Status.Code.DEADLINE_EXCEEDED, e.getStatus().getCode());
        }
        // Verify
        Mockito.verify(limiter, Mockito.times(1)).acquire(Mockito.isA(GrpcServerRequestContext.class));
        Mockito.verify(listener.getResult().get(), Mockito.times(0)).onIgnore();

        Mockito.verify(listener.getResult().get(), Mockito.timeout(2000).times(1)).onSuccess();

        verifyCounts(0, 0, 1, 0);
    }

    @Test
    public void releaseOnError() {
        // Setup server
        startServer((req, observer) -> {
            observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
        });

        try {
            ClientCalls.blockingUnaryCall(channel, METHOD_DESCRIPTOR, CallOptions.DEFAULT, "foo");
            fail("Should have failed with INVALID_ARGUMENT error");
        } catch (StatusRuntimeException e) {
            assertEquals(Status.Code.INVALID_ARGUMENT, e.getStatus().getCode());
        }
        // Verify
        Mockito.verify(limiter, Mockito.times(1)).acquire(Mockito.isA(GrpcServerRequestContext.class));

        verifyCounts(0, 0, 1, 0);
    }

    @Test
    public void releaseOnSuccess() {
        // Setup server
        startServer((req, observer) -> {
            observer.onNext("response");
            observer.onCompleted();
        });

        ClientCalls.blockingUnaryCall(channel, METHOD_DESCRIPTOR, CallOptions.DEFAULT, "foo");
        Mockito.verify(limiter, Mockito.times(1)).acquire(Mockito.isA(GrpcServerRequestContext.class));
        Mockito.verify(listener.getResult().get(), Mockito.timeout(1000).times(1)).onSuccess();

        verifyCounts(0, 0, 1, 0);
    }

    @Test
    public void releaseOnUncaughtException() throws Exception {
        Thread.sleep(500);
        // Setup server
        startServer((req, observer) -> {
            throw new RuntimeException("failure");
        });
        try {
            ClientCalls.blockingUnaryCall(channel, METHOD_DESCRIPTOR, CallOptions.DEFAULT, "foo");
            fail("Should have failed with UNKNOWN error");
        } catch (StatusRuntimeException e) {
            assertEquals(Status.Code.UNKNOWN, e.getStatus().getCode());
        }
        Thread.sleep(500);
        var builder = new StringBuilder().append('\n')
                                         .append('\n')
                                         .append("******************************************")
                                         .append('\n')
                                         .append("*** 2 stack traces above were expected ***")
                                         .append('\n')
                                         .append("******************************************")
                                         .append('\n')
                                         .append('\n');
        LoggerFactory.getLogger(getClass()).warn(builder.toString());
        // Verify
        Mockito.verify(limiter, Mockito.times(1)).acquire(Mockito.isA(GrpcServerRequestContext.class));
        Mockito.verify(listener.getResult().get(), Mockito.timeout(1000).times(1)).onIgnore();

        verifyCounts(0, 1, 0, 0);
    }

    public void verifyCounts(int dropped, int ignored, int success, int rejected) {
        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException e) {
        }
//        assertEquals(dropped,
//                     registry.counter("unit.test.limiter.call", "id", testName.getMethodName(), "status", "dropped")
//                             .count());
//        assertEquals(ignored,
//                     registry.counter("unit.test.limiter.call", "id", testName.getMethodName(), "status", "ignored")
//                             .count());
//        assertEquals(success,
//                     registry.counter("unit.test.limiter.call", "id", testName.getMethodName(), "status", "success")
//                             .count());
//        assertEquals(rejected,
//                     registry.counter("unit.test.limiter.call", "id", testName.getMethodName(), "status", "rejected")
//                             .count());
    }

    @Test
    public void streamingCallsShouldBeLimited() throws Exception {
        // Use a fixed limit of 1 to easily test rejection
        Limiter<GrpcServerRequestContext> fixedLimiter = SimpleLimiter.<GrpcServerRequestContext>newBuilder()
                                                                      .limit(FixedLimit.of(1))
                                                                      .named("streaming-test")
                                                                      .build();

        var streamHoldLatch = new CountDownLatch(1);
        var streamStartLatch = new CountDownLatch(1);

        // Start server with streaming endpoint
        startStreamingServer((req, observer) -> {
            streamStartLatch.countDown();
            try {
                // Hold the stream open to occupy the limit
                streamHoldLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            observer.onNext("response1");
            observer.onNext("response2");
            observer.onCompleted();
        }, fixedLimiter);

        var firstCallStarted = new AtomicInteger(0);
        var secondCallRejected = new AtomicInteger(0);

        // First streaming call - should succeed and hold the limit
        var thread1 = new Thread(() -> {
            try {
                var iterator = ClientCalls.blockingServerStreamingCall(
                    channel.newCall(STREAMING_METHOD_DESCRIPTOR, CallOptions.DEFAULT),
                    "request"
                );
                firstCallStarted.incrementAndGet();
                while (iterator.hasNext()) {
                    iterator.next();
                }
            } catch (StatusRuntimeException e) {
                // Should not happen for first call
            }
        });

        thread1.start();

        // Wait for first call to start
        streamStartLatch.await(2, TimeUnit.SECONDS);
        Thread.sleep(100); // Small delay to ensure limit is held

        // Second streaming call - should be rejected with UNAVAILABLE
        try {
            var iterator = ClientCalls.blockingServerStreamingCall(
                channel.newCall(STREAMING_METHOD_DESCRIPTOR, CallOptions.DEFAULT),
                "request"
            );
            iterator.hasNext(); // Try to get first response
            fail("Second streaming call should have been rejected");
        } catch (StatusRuntimeException e) {
            assertEquals(Status.Code.UNAVAILABLE, e.getStatus().getCode());
            secondCallRejected.incrementAndGet();
        }

        // Release first call
        streamHoldLatch.countDown();
        thread1.join(2000);

        assertEquals(1, firstCallStarted.get(), "First call should have started");
        assertEquals(1, secondCallRejected.get(), "Second call should have been rejected");
    }

    @Test
    public void streamingCallShouldReleaseOnComplete() throws Exception {
        Limiter<GrpcServerRequestContext> streamingLimiter = Mockito.spy(SimpleLimiter.<GrpcServerRequestContext>newBuilder()
                                                                                       .named("streaming-release-test")
                                                                                       .build());
        var streamingListener = OptionalResultCaptor.forClass(Limiter.Listener.class);
        Mockito.doAnswer(streamingListener).when(streamingLimiter).acquire(Mockito.any());

        startStreamingServer((req, observer) -> {
            observer.onNext("response1");
            observer.onNext("response2");
            observer.onCompleted();
        }, streamingLimiter);

        var iterator = ClientCalls.blockingServerStreamingCall(
            channel.newCall(STREAMING_METHOD_DESCRIPTOR, CallOptions.DEFAULT),
            "request"
        );

        var count = 0;
        while (iterator.hasNext()) {
            iterator.next();
            count++;
        }

        assertEquals(2, count);
        Mockito.verify(streamingLimiter, Mockito.times(1)).acquire(Mockito.isA(GrpcServerRequestContext.class));
        Mockito.verify(streamingListener.getResult().get(), Mockito.timeout(1000).times(1)).onSuccess();
    }

    @Test
    public void streamingCallShouldReleaseOnError() throws Exception {
        Limiter<GrpcServerRequestContext> streamingLimiter = Mockito.spy(SimpleLimiter.<GrpcServerRequestContext>newBuilder()
                                                                                       .named("streaming-error-test")
                                                                                       .build());
        var streamingListener = OptionalResultCaptor.forClass(Limiter.Listener.class);
        Mockito.doAnswer(streamingListener).when(streamingLimiter).acquire(Mockito.any());

        startStreamingServer((req, observer) -> {
            observer.onNext("response1");
            observer.onError(Status.INTERNAL.asRuntimeException());
        }, streamingLimiter);

        try {
            var iterator = ClientCalls.blockingServerStreamingCall(
                channel.newCall(STREAMING_METHOD_DESCRIPTOR, CallOptions.DEFAULT),
                "request"
            );
            while (iterator.hasNext()) {
                iterator.next();
            }
            fail("Should have thrown INTERNAL error");
        } catch (StatusRuntimeException e) {
            assertEquals(Status.Code.INTERNAL, e.getStatus().getCode());
        }

        Mockito.verify(streamingLimiter, Mockito.times(1)).acquire(Mockito.isA(GrpcServerRequestContext.class));
        Mockito.verify(streamingListener.getResult().get(), Mockito.timeout(1000).times(1)).onSuccess();
    }

    private void startServer(ServerCalls.UnaryMethod<String, String> method) {
        try {
            server = NettyServerBuilder.forPort(0)
                                       .addService(ServerInterceptors.intercept(ServerServiceDefinition.builder("service")
                                                                                                       .addMethod(METHOD_DESCRIPTOR,
                                                                                                                  ServerCalls.asyncUnaryCall(method))
                                                                                                       .build(),
                                                                                ConcurrencyLimitServerInterceptor.newBuilder(limiter)
                                                                                                                 .build()))
                                       .build()
                                       .start();

            channel = NettyChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void startStreamingServer(ServerCalls.ServerStreamingMethod<String, String> method,
                                     Limiter<GrpcServerRequestContext> streamingLimiter) {
        try {
            server = NettyServerBuilder.forPort(0)
                                       .addService(ServerInterceptors.intercept(ServerServiceDefinition.builder("service")
                                                                                                       .addMethod(STREAMING_METHOD_DESCRIPTOR,
                                                                                                                  ServerCalls.asyncServerStreamingCall(method))
                                                                                                       .build(),
                                                                                ConcurrencyLimitServerInterceptor.newBuilder(streamingLimiter)
                                                                                                                 .build()))
                                       .build()
                                       .start();

            channel = NettyChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
