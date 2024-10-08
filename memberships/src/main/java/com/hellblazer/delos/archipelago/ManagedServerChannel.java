/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.archipelago;

import com.google.common.base.MoreObjects;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.membership.Member;
import io.grpc.*;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.stub.AbstractStub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static com.hellblazer.delos.cryptography.QualifiedBase64.qb64;

public class ManagedServerChannel extends ManagedChannel {
    private final static Logger log = LoggerFactory.getLogger(ManagedServerChannel.class);

    private final Digest          context;
    private final Releasable      delegate;
    private final CallCredentials credentials;

    ManagedServerChannel(Digest context, Releasable delegate, CallCredentials credentials) {
        this.context = context;
        this.delegate = delegate;
        this.credentials = credentials;
    }

    @Override
    public String authority() {
        return delegate.getChannel().authority();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.getChannel().awaitTermination(timeout, unit);
    }

    @Override
    public void enterIdle() {
        delegate.getChannel().enterIdle();
    }

    public CallCredentials getCredentials() {
        return credentials;
    }

    public Member getMember() {
        return delegate.getMember();
    }

    @Override
    public ConnectivityState getState(boolean requestConnection) {
        return delegate.getChannel().getState(requestConnection);
    }

    @Override
    public boolean isShutdown() {
        return delegate.getChannel().isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.getChannel().isTerminated();
    }

    @Override
    public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(
    MethodDescriptor<RequestT, ResponseT> methodDescriptor, CallOptions callOptions) {
        return new SimpleForwardingClientCall<RequestT, ResponseT>(
        delegate.getChannel().newCall(methodDescriptor, callOptions)) {
            @Override
            public void start(Listener<ResponseT> responseListener, Metadata headers) {
                headers.put(Constants.METADATA_CONTEXT_KEY, qb64(context));
                headers.put(Constants.METADATA_TARGET_KEY, qb64(delegate.getMember().getId()));
                super.start(responseListener, headers);
            }
        };
    }

    @Override
    public void notifyWhenStateChanged(ConnectivityState source, Runnable callback) {
        delegate.getChannel().notifyWhenStateChanged(source, callback);
    }

    public void release() {
        delegate.release();
    }

    @Override
    public void resetConnectBackoff() {
        delegate.getChannel().resetConnectBackoff();
    }

    @Override
    public ManagedChannel shutdown() {
        if (log.isTraceEnabled()) {
            log.trace("Shutting down connection to: {} on: {}", delegate.getMember().getId(), delegate.getFrom(),
                      new Exception("Shutdown stacktrace"));
        } else if (log.isDebugEnabled()) {
            log.debug("Shutting down connection to: {} on: {}", delegate.getMember().getId(), delegate.getFrom());
        }
        return delegate.shutdown();
    }

    @Override
    public ManagedChannel shutdownNow() {
        log.trace("Shutting down connection (now) to: {} on: {}", delegate.getMember().getId(), delegate.getFrom());
        return delegate.shutdownNow();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("delegate", delegate).toString();
    }

    public <S extends AbstractStub<S>> S wrap(S stub) {
        stub = stub.withCompression("gzip");
        return credentials == null ? stub : stub.withCallCredentials(credentials);
    }
}
