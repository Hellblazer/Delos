/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.archipelago;

import com.hellblazer.delos.archipelago.server.FernetServerInterceptor;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static com.hellblazer.delos.archipelago.Constants.SERVER_CONTEXT_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test RoutableService exception handling security - verifies that internal exceptions
 * are never leaked to gRPC clients, preventing Byzantine attackers from gaining
 * information about internal implementation details.
 *
 * @author hal.hildebrand
 */
class RoutableServiceTest {

    private RoutableService<TestService> routableService;
    private Digest                       context;
    private TestService                  service;
    private StreamObserver<Object>       responseObserver;

    @BeforeEach
    void setUp() {
        routableService = new RoutableService<>();
        context = DigestAlgorithm.DEFAULT.digest("test-context".getBytes());
        service = new TestService();
        responseObserver = mock(StreamObserver.class);
    }

    /**
     * Test that RuntimeExceptions are converted to generic StatusRuntimeException
     * with INTERNAL status and generic message (Consumer overload)
     */
    @Test
    void shouldConvertRuntimeExceptionToGenericStatus_Consumer() {
        routableService.bind(context, service, null);

        Consumer<TestService> throwing = s -> {
            throw new RuntimeException("Sensitive internal error with package.ClassName");
        };

        runInContext(() -> routableService.evaluate(responseObserver, throwing));

        ArgumentCaptor<StatusRuntimeException> captor = ArgumentCaptor.forClass(StatusRuntimeException.class);
        verify(responseObserver).onError(captor.capture());

        var exception = captor.getValue();
        assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(exception.getStatus().getDescription()).isEqualTo("Internal error");
        // Verify no internal details are exposed
        assertThat(exception.getMessage()).doesNotContain("package.ClassName");
        assertThat(exception.getMessage()).doesNotContain("Sensitive");
    }

    /**
     * Test that RuntimeExceptions are converted to generic StatusRuntimeException
     * with INTERNAL status and generic message (BiConsumer overload)
     */
    @Test
    void shouldConvertRuntimeExceptionToGenericStatus_BiConsumer() {
        routableService.bind(context, service, null);

        BiConsumer<TestService, FernetServerInterceptor.HashedToken> throwing = (s, t) -> {
            throw new RuntimeException("Sensitive internal error with package.ClassName");
        };

        runInContext(() -> routableService.evaluate(responseObserver, throwing));

        ArgumentCaptor<StatusRuntimeException> captor = ArgumentCaptor.forClass(StatusRuntimeException.class);
        verify(responseObserver).onError(captor.capture());

        var exception = captor.getValue();
        assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(exception.getStatus().getDescription()).isEqualTo("Internal error");
        // Verify no internal details are exposed
        assertThat(exception.getMessage()).doesNotContain("package.ClassName");
        assertThat(exception.getMessage()).doesNotContain("Sensitive");
    }

    /**
     * Test that Errors (e.g., AssertionError) are also converted to generic status
     */
    @Test
    void shouldConvertErrorToGenericStatus_Consumer() {
        routableService.bind(context, service, null);

        Consumer<TestService> throwing = s -> {
            throw new AssertionError("Internal assertion failed at com.internal.ClassName:123");
        };

        runInContext(() -> routableService.evaluate(responseObserver, throwing));

        ArgumentCaptor<StatusRuntimeException> captor = ArgumentCaptor.forClass(StatusRuntimeException.class);
        verify(responseObserver).onError(captor.capture());

        var exception = captor.getValue();
        assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(exception.getStatus().getDescription()).isEqualTo("Internal error");
        // Verify no stack trace or class names are exposed
        assertThat(exception.getMessage()).doesNotContain("com.internal");
        assertThat(exception.getMessage()).doesNotContain(":123");
    }

    /**
     * Test that custom exceptions are sanitized
     */
    @Test
    void shouldSanitizeCustomExceptions_BiConsumer() {
        routableService.bind(context, service, null);

        BiConsumer<TestService, FernetServerInterceptor.HashedToken> throwing = (s, t) -> {
            throw new CustomBusinessException("Customer ID 12345 not found in database table CUSTOMERS");
        };

        runInContext(() -> routableService.evaluate(responseObserver, throwing));

        ArgumentCaptor<StatusRuntimeException> captor = ArgumentCaptor.forClass(StatusRuntimeException.class);
        verify(responseObserver).onError(captor.capture());

        var exception = captor.getValue();
        assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(exception.getStatus().getDescription()).isEqualTo("Internal error");
        // Verify no business logic details are exposed
        assertThat(exception.getMessage()).doesNotContain("Customer ID");
        assertThat(exception.getMessage()).doesNotContain("CUSTOMERS");
        assertThat(exception.getMessage()).doesNotContain("12345");
    }

    /**
     * Test that StatusRuntimeException is passed through unchanged
     * (this is expected behavior - service can explicitly return gRPC statuses)
     */
    @Test
    void shouldPassThroughStatusRuntimeException_Consumer() {
        routableService.bind(context, service, null);

        var expectedStatus = new StatusRuntimeException(Status.PERMISSION_DENIED.withDescription("Access denied"));
        Consumer<TestService> throwing = s -> {
            throw expectedStatus;
        };

        runInContext(() -> routableService.evaluate(responseObserver, throwing));

        ArgumentCaptor<StatusRuntimeException> captor = ArgumentCaptor.forClass(StatusRuntimeException.class);
        verify(responseObserver).onError(captor.capture());

        var exception = captor.getValue();
        assertThat(exception).isSameAs(expectedStatus);
        assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    /**
     * Test that normal execution doesn't trigger error handling
     */
    @Test
    void shouldNotCallOnErrorForSuccessfulExecution_Consumer() {
        routableService.bind(context, service, null);

        AtomicReference<TestService> receivedService = new AtomicReference<>();
        Consumer<TestService> normal = receivedService::set;

        runInContext(() -> routableService.evaluate(responseObserver, normal));

        verify(responseObserver, never()).onError(any());
        assertThat(receivedService.get()).isSameAs(service);
    }

    /**
     * Test that normal execution doesn't trigger error handling (BiConsumer)
     */
    @Test
    void shouldNotCallOnErrorForSuccessfulExecution_BiConsumer() {
        routableService.bind(context, service, null);

        AtomicReference<TestService> receivedService = new AtomicReference<>();
        BiConsumer<TestService, FernetServerInterceptor.HashedToken> normal = (s, t) -> receivedService.set(s);

        runInContext(() -> routableService.evaluate(responseObserver, normal));

        verify(responseObserver, never()).onError(any());
        assertThat(receivedService.get()).isSameAs(service);
    }

    /**
     * Test that null context returns NOT_FOUND (not INTERNAL)
     */
    @Test
    void shouldReturnNotFoundForNullContext() {
        // Don't set context
        routableService.bind(context, service, null);

        Consumer<TestService> consumer = s -> {};
        routableService.evaluate(responseObserver, consumer);

        ArgumentCaptor<StatusRuntimeException> captor = ArgumentCaptor.forClass(StatusRuntimeException.class);
        verify(responseObserver).onError(captor.capture());

        var exception = captor.getValue();
        assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
    }

    /**
     * Test that unbound context returns NOT_FOUND (not INTERNAL)
     */
    @Test
    void shouldReturnNotFoundForUnboundContext() {
        var unboundContext = DigestAlgorithm.DEFAULT.digest("unbound".getBytes());

        Consumer<TestService> consumer = s -> {};

        Context.current().withValue(SERVER_CONTEXT_KEY, unboundContext)
               .run(() -> routableService.evaluate(responseObserver, consumer));

        ArgumentCaptor<StatusRuntimeException> captor = ArgumentCaptor.forClass(StatusRuntimeException.class);
        verify(responseObserver).onError(captor.capture());

        var exception = captor.getValue();
        assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
    }

    /**
     * Test that NullPointerException is sanitized (common error type)
     */
    @Test
    void shouldSanitizeNullPointerException() {
        routableService.bind(context, service, null);

        Consumer<TestService> throwing = s -> {
            throw new NullPointerException("Cannot invoke com.internal.Customer.getId() on null object");
        };

        runInContext(() -> routableService.evaluate(responseObserver, throwing));

        ArgumentCaptor<StatusRuntimeException> captor = ArgumentCaptor.forClass(StatusRuntimeException.class);
        verify(responseObserver).onError(captor.capture());

        var exception = captor.getValue();
        assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(exception.getStatus().getDescription()).isEqualTo("Internal error");
        assertThat(exception.getMessage()).doesNotContain("com.internal");
        assertThat(exception.getMessage()).doesNotContain("Customer");
        assertThat(exception.getMessage()).doesNotContain("getId");
    }

    /**
     * Helper to run code in a gRPC context with SERVER_CONTEXT_KEY set
     */
    private void runInContext(Runnable runnable) {
        Context.current().withValue(SERVER_CONTEXT_KEY, context).run(runnable);
    }

    /**
     * Simple test service
     */
    static class TestService {
    }

    /**
     * Custom exception to test sanitization
     */
    static class CustomBusinessException extends RuntimeException {
        CustomBusinessException(String message) {
            super(message);
        }
    }
}
