package com.hellblazer.delos.leyden.comm.reconcile;

import com.hellblazer.delos.archipelago.ManagedServerChannel;
import com.hellblazer.delos.leyden.proto.Intervals;
import com.hellblazer.delos.leyden.proto.ReconciliationGrpc;
import com.hellblazer.delos.leyden.proto.Update;
import com.hellblazer.delos.leyden.proto.Updating;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;

import java.io.IOException;

/**
 * @author hal.hildebrand
 **/
public class Reckoning implements ReconciliationClient {
    private final ManagedServerChannel                          channel;
    private final ReconciliationGrpc.ReconciliationBlockingStub client;
    private final Member                                        member;

    public Reckoning(ManagedServerChannel channel, Member member, ReconciliationMetrics metrics) {
        this.channel = channel;
        this.client = channel.wrap(ReconciliationGrpc.newBlockingStub(channel));
        this.member = member;
    }

    public static ReconciliationClient getCreate(ManagedServerChannel channel, ReconciliationMetrics metrics) {
        return new Reckoning(channel, channel.getMember(), metrics);
    }

    public static ReconciliationClient getLocalLoopback(ReconciliationService service, SigningMember member) {
        return new ReconciliationClient() {
            @Override
            public void close() throws IOException {

            }

            @Override
            public Member getMember() {
                return member;
            }

            @Override
            public Update reconcile(Intervals intervals) {
                return Update.getDefaultInstance();
            }

            @Override
            public void update(Updating updating) {
                // noop
            }
        };
    }

    @Override
    public void close() throws IOException {
        channel.release();
    }

    @Override
    public Member getMember() {
        return member;
    }

    @Override
    public Update reconcile(Intervals intervals) {
        return client.reconcile(intervals);
    }

    @Override
    public void update(Updating updating) {
        client.update(updating);
    }
}
