package com.hellblazer.delos.archipelago;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.membership.Member;
import io.grpc.ManagedChannel;

public interface Releasable {
    ManagedChannel getChannel();

    Digest getFrom();

    Member getMember();

    void release();

    ManagedChannel shutdown();

    ManagedChannel shutdownNow();
}
