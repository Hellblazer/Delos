/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.model;

import com.hellblazer.delos.archipelago.Portal;
import com.hellblazer.delos.choam.Parameters;
import com.hellblazer.delos.comm.grpc.DomainSocketServerInterceptor;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.Signer;
import com.hellblazer.delos.cryptography.proto.Digeste;
import com.hellblazer.delos.demesne.proto.DemesneParameters;
import com.hellblazer.delos.demesne.proto.SubContext;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.model.demesnes.Demesne;
import com.hellblazer.delos.model.demesnes.JniBridge;
import com.hellblazer.delos.model.demesnes.comm.OuterContextServer;
import com.hellblazer.delos.model.demesnes.comm.OuterContextService;
import com.hellblazer.delos.stereotomy.event.Seal;
import com.hellblazer.delos.stereotomy.event.proto.AttachmentEvent;
import com.hellblazer.delos.stereotomy.event.proto.KeyState_;
import com.hellblazer.delos.stereotomy.identifier.BasicIdentifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.identifier.spec.IdentifierSpecification;
import com.hellblazer.delos.stereotomy.identifier.spec.InteractionSpecification;
import com.hellblazer.delos.stereotomy.services.grpc.StereotomyMetrics;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.netty.DomainSocketNegotiatorHandler;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDomainSocketChannel;
import io.netty.channel.socket.nio.NioServerDomainSocketChannel;
import org.joou.ULong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.hellblazer.delos.cryptography.QualifiedBase64.qb64;

/**
 * @author hal.hildebrand
 **/
public class ProcessContainerDomain extends ProcessDomain {

    private final static Logger                                                    log                   = LoggerFactory.getLogger(
    ProcessContainerDomain.class);
    private final static Class<? extends io.netty.channel.Channel>                 channelType           = NioDomainSocketChannel.class;
    protected final      Executor                                                  executor              = Executors.newVirtualThreadPerTaskExecutor();
    private final        UnixDomainSocketAddress                                   bridge;
    private final        EventLoopGroup                                            clientEventLoopGroup  = new NioEventLoopGroup();
    private final        Path                                                      communicationsDirectory;
    private final        EventLoopGroup                                            contextEventLoopGroup = new NioEventLoopGroup();
    private final        Map<Digest, Demesne>                                      hostedDomains         = new ConcurrentHashMap<>();
    private final        Portal<Member>                                            portal;
    private final        UnixDomainSocketAddress                                   portalEndpoint;
    private final        EventLoopGroup                                            portalEventLoopGroup  = new NioEventLoopGroup();
    private final        Map<String, UnixDomainSocketAddress>                      routes                = new ConcurrentHashMap<>();
    private final        IdentifierSpecification.Builder<SelfAddressingIdentifier> subDomainSpecification;

    public ProcessContainerDomain(Digest group, ControlledIdentifierMember member, ProcessDomainParameters parameters,
                                  Parameters.Builder builder, Parameters.RuntimeParameters.Builder runtime,
                                  String endpoint, Path commDirectory,
                                  com.hellblazer.delos.fireflies.Parameters.Builder ff,
                                  IdentifierSpecification.Builder<SelfAddressingIdentifier> subDomainSpecification,
                                  StereotomyMetrics stereotomyMetrics) {
        super(group, member, parameters, builder, runtime, endpoint, ff, stereotomyMetrics);
        communicationsDirectory = commDirectory;
        bridge = UnixDomainSocketAddress.of(communicationsDirectory.resolve(UUID.randomUUID().toString()));
        portalEndpoint = UnixDomainSocketAddress.of(communicationsDirectory.resolve(UUID.randomUUID().toString()));
        portal = new Portal<>(member.getId(), NettyServerBuilder.forAddress(portalEndpoint)
                                                                .protocolNegotiator(
                                                                new DomainSocketNegotiatorHandler.DomainSocketNegotiator())
                                                                .executor(Executors.newVirtualThreadPerTaskExecutor())
                                                                .withChildOption(ChannelOption.TCP_NODELAY, true)
                                                                .channelType(NioServerDomainSocketChannel.class)
                                                                .workerEventLoopGroup(portalEventLoopGroup)
                                                                .bossEventLoopGroup(portalEventLoopGroup)
                                                                .intercept(new DomainSocketServerInterceptor()),
                              s -> handler(portalEndpoint), bridge, Duration.ofMillis(1), s -> routes.get(s));
        this.subDomainSpecification = subDomainSpecification;
    }

    /**
     * Spawn a new subdomain with the given parameters.
     * <p>
     * <b>Route Registration Timing:</b> The subdomain is created and added to {@code hostedDomains}
     * immediately, but route registration happens asynchronously during the KERI ceremony in
     * {@code demesne.commit()}. There is a brief window where the subdomain exists but is not yet
     * routable via Portal. This is expected behavior - Portal routing becomes available after
     * the subdomain's KERI identity is established.
     *
     * @param prototype DemesneParameters builder with subdomain configuration
     * @return SelfAddressingIdentifier of the spawned subdomain
     */
    public SelfAddressingIdentifier spawn(DemesneParameters.Builder prototype) {
        final var witness = member.getIdentifier().newEphemeral().get();
        final var cloned = prototype.clone();
        var parameters = cloned.setCommDirectory(communicationsDirectory.toString())
                               .setPortal(portalEndpoint.getPath().toString())
                               .build();
        var ctxId = Digest.from(parameters.getContext());
        final AtomicBoolean added = new AtomicBoolean();
        final var demesne = new JniBridge(parameters);
        var computed = hostedDomains.computeIfAbsent(ctxId, k -> {
            added.set(true);
            return demesne;
        });
        if (added.get()) {
            var newSpec = subDomainSpecification.clone();
            // the receiver is a witness to the subdomain's delegated key
            var newWitnesses = new ArrayList<>(subDomainSpecification.getWitnesses());
            newWitnesses.add(new BasicIdentifier(witness.getPublic()));
            newSpec.setWitnesses(newWitnesses);
            var incp = demesne.inception(member.getIdentifier().getIdentifier().toIdent(), newSpec);
            var sigs = new HashMap<Integer, JohnHancock>();
            sigs.put(0, new Signer.SignerImpl(witness.getPrivate(), ULong.MIN).sign(incp.toKeyEvent_().toByteString()));
            var attached = new com.hellblazer.delos.stereotomy.event.AttachmentEvent.AttachmentImpl(sigs);
            var seal = Seal.EventSeal.construct(incp.getIdentifier(), incp.hash(dht.digestAlgorithm()),
                                                incp.getSequenceNumber().longValue());
            var builder = InteractionSpecification.newBuilder().addAllSeals(Collections.singletonList(seal));
            KeyState_ ks = dht.append(AttachmentEvent.newBuilder()
                                                     .setCoordinates(incp.getCoordinates().toEventCoords())
                                                     .setAttachment(attached.toAttachemente())
                                                     .build());
            var coords = member.getIdentifier().seal(builder);
            demesne.commit(coords.toEventCoords());
            demesne.start();
            return (SelfAddressingIdentifier) incp.getIdentifier();
        }
        return computed.getId();
    }

    /**
     * Package-private accessor for testing route registration.
     * @return unmodifiable view of routes map for testing verification
     */
    Map<String, UnixDomainSocketAddress> getRoutes() {
        return Collections.unmodifiableMap(routes);
    }

    @Override
    protected void startServices() {
        super.startServices();
        try {
            portal.start();
        } catch (IOException e) {
            throw new IllegalStateException(
            "Unable to start portal, local address: " + bridge.getPath() + " on: " + params.member().getId());
        }
    }

    @Override
    protected void stopServices() {
        super.stopServices();
        portal.close(Duration.ofSeconds(30));
        hostedDomains.values().forEach(d -> d.stop());
        var portalELG = portalEventLoopGroup.shutdownGracefully();
        var serverELG = contextEventLoopGroup.shutdownGracefully();
        var clientELG = clientEventLoopGroup.shutdownGracefully();
        try {
            if (clientELG.await(30, TimeUnit.SECONDS)) {
                log.info("Did not completely shutdown client event loop group for process: {}", member.getId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            if (!serverELG.await(30, TimeUnit.SECONDS)) {
                log.info("Did not completely shutdown server event loop group for process: {}", member.getId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            if (!portalELG.await(30, TimeUnit.SECONDS)) {
                log.info("Did not completely shutdown portal event loop group for process: {}", member.getId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ManagedChannel handler(UnixDomainSocketAddress address) {
        return NettyChannelBuilder.forAddress(address)
                                  .withOption(ChannelOption.TCP_NODELAY, true)
                                  .executor(executor)
                                  .eventLoopGroup(clientEventLoopGroup)
                                  .channelType(channelType)
                                  .keepAliveTime(1, TimeUnit.SECONDS)
                                  .usePlaintext()
                                  .build();
    }

    private BindableService outerContextService() {
        return new OuterContextServer(new OuterContextService() {

            @Override
            public void deregister(Digeste context) {
                String routingKey = qb64(Digest.from(context));
                UnixDomainSocketAddress removed = routes.remove(routingKey);
                if (removed != null) {
                    log.info("Deregistered route for context: {} path: {}", routingKey, removed.getPath());
                } else {
                    log.warn("Attempted to deregister non-existent route for context: {}", routingKey);
                }
            }

            @Override
            public void register(SubContext context) {
                String routingKey = qb64(Digest.from(context.getContext()));
                UnixDomainSocketAddress address = UnixDomainSocketAddress.of(context.getPortalAddress());
                UnixDomainSocketAddress previous = routes.put(routingKey, address);

                if (previous != null) {
                    log.warn("Route registration replaced existing route for context: {} old: {} new: {}",
                             routingKey, previous.getPath(), address.getPath());
                } else {
                    log.info("Registered route for context: {} path: {}", routingKey, address.getPath());
                }
            }
        }, null);
    }
}
