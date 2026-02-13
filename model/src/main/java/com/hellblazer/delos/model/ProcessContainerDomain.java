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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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
    private final        Map<Digest, SubDomainHandleImpl>                          spawnedHandles        = new ConcurrentHashMap<>();
    private final        Thread                                                     shutdownHook;
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
        this.shutdownHook = new Thread(this::cleanupSocketFiles, "ProcessContainer-shutdown-" + member.getId());
    }

    /**
     * Spawn a new subdomain with the given parameters.
     * <p>
     * <b>Route Registration Timing:</b> The subdomain is created and added to {@code hostedDomains}
     * immediately, but route registration happens asynchronously during the KERI ceremony in
     * {@code demesne.commit()}. There is a brief window where the subdomain exists but is not yet
     * routable via Portal. This is expected behavior - Portal routing becomes available after
     * the subdomain's KERI identity is established.
     * <p>
     * The returned handle starts in {@link SubDomainStatus#STARTING} status and transitions to
     * {@link SubDomainStatus#RUNNING} when route registration completes.
     *
     * @param prototype DemesneParameters builder with subdomain configuration
     * @return SubDomainHandle for managing the spawned subdomain's lifecycle
     */
    public SubDomainHandle spawn(DemesneParameters.Builder prototype) {
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

            // Create handle before commit/start - starts in STARTING status
            SelfAddressingIdentifier subdomainId = (SelfAddressingIdentifier) incp.getIdentifier();
            SubDomainHandleImpl handle = new SubDomainHandleImpl(subdomainId, demesne);
            spawnedHandles.put(ctxId, handle);

            demesne.commit(coords.toEventCoords());
            demesne.start();
            // Handle remains in STARTING status until register() callback is invoked
            return handle;
        }
        return spawnedHandles.get(ctxId);
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

        // Clean up stale socket files from previous crashed instances
        cleanupStaleSocketFiles();

        // Register shutdown hook for cleanup on JVM termination (SIGTERM, SIGINT)
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Shutdown hook already registered or JVM already shutting down - this is fine
            log.debug("Shutdown hook registration skipped: {}", e.getMessage());
        }

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

        // Remove shutdown hook since we're doing orderly shutdown
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // JVM already shutting down - hook will still run, which is fine
            log.debug("Shutdown hook removal skipped: {}", e.getMessage());
        }

        portal.close(Duration.ofSeconds(30));
        hostedDomains.values().forEach(d -> d.stop());
        spawnedHandles.clear();
        var portalELG = portalEventLoopGroup.shutdownGracefully();
        var serverELG = contextEventLoopGroup.shutdownGracefully();
        var clientELG = clientEventLoopGroup.shutdownGracefully();
        try {
            if (!clientELG.await(30, TimeUnit.SECONDS)) {
                log.warn("Client event loop group did not shutdown within 30 seconds for process: {}", member.getId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            if (!serverELG.await(30, TimeUnit.SECONDS)) {
                log.warn("Server event loop group did not shutdown within 30 seconds for process: {}", member.getId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            if (!portalELG.await(30, TimeUnit.SECONDS)) {
                log.warn("Portal event loop group did not shutdown within 30 seconds for process: {}", member.getId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Clean up Unix domain socket files
        cleanupSocketFiles();
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
                Digest ctxId = Digest.from(context.getContext());
                UnixDomainSocketAddress address = UnixDomainSocketAddress.of(context.getPortalAddress());
                long registrationTime = System.currentTimeMillis();
                UnixDomainSocketAddress previous = routes.put(routingKey, address);

                if (previous != null) {
                    log.warn("Route registration replaced existing route for context: {} old: {} new: {} at: {}",
                             routingKey, previous.getPath(), address.getPath(), registrationTime);
                } else {
                    log.info("Registered route for context: {} path: {} at: {}", routingKey, address.getPath(),
                             registrationTime);
                }

                // Mark subdomain as running now that route is registered
                SubDomainHandleImpl handle = spawnedHandles.get(ctxId);
                if (handle != null) {
                    handle.markRunning();
                    log.debug("Marked subdomain {} as RUNNING after route registration", routingKey);
                }
            }
        }, null);
    }

    /**
     * Clean up stale Unix domain socket files from previous crashed instances.
     * <p>
     * Called during startup to remove socket files that may have been left behind by abnormal termination
     * (crash, SIGKILL, power loss). Uses file age to determine staleness - files older than 5 minutes
     * are considered stale and safe to delete.
     */
    private void cleanupStaleSocketFiles() {
        if (!Files.exists(communicationsDirectory)) {
            log.debug("Communications directory does not exist, skipping stale socket cleanup: {}",
                      communicationsDirectory);
            return;
        }

        try (var files = Files.list(communicationsDirectory)) {
            var staleThreshold = Instant.now().minus(Duration.ofMinutes(5));

            files.filter(Files::isRegularFile)
                 .filter(path -> {
                     try {
                         var lastModified = Files.getLastModifiedTime(path).toInstant();
                         return lastModified.isBefore(staleThreshold);
                     } catch (IOException e) {
                         log.warn("Unable to check modification time for socket file: {}", path, e);
                         return false;
                     }
                 })
                 .forEach(path -> {
                     try {
                         Files.deleteIfExists(path);
                         log.info("Cleaned up stale socket file: {}", path);
                     } catch (IOException e) {
                         log.warn("Failed to delete stale socket file: {}", path, e);
                     }
                 });
        } catch (IOException e) {
            log.warn("Failed to list communications directory for stale socket cleanup: {}", communicationsDirectory,
                     e);
        }
    }

    /**
     * Clean up Unix domain socket files created by this ProcessContainerDomain.
     * <p>
     * Called during:
     * <ul>
     *   <li>Normal shutdown via {@link #stopServices()}</li>
     *   <li>Abnormal shutdown via JVM shutdown hook (SIGTERM, SIGINT)</li>
     * </ul>
     * <p>
     * Deletes the bridge and portal endpoint socket files. Subdomain socket files are cleaned up
     * by their respective Demesne instances.
     */
    private void cleanupSocketFiles() {
        deleteSocketFile(bridge.getPath());
        deleteSocketFile(portalEndpoint.getPath());
    }

    /**
     * Delete a Unix domain socket file.
     *
     * @param path socket file path
     */
    private void deleteSocketFile(Path path) {
        try {
            if (Files.deleteIfExists(path)) {
                log.debug("Deleted socket file: {}", path);
            }
        } catch (IOException e) {
            log.warn("Failed to delete socket file: {}", path, e);
        }
    }
}
