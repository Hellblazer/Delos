/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.gorgoneion;

import com.codahale.metrics.Timer;
import com.google.protobuf.Any;
import com.google.protobuf.Empty;
import com.google.protobuf.Timestamp;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.RouterImpl.CommonCommunications;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.Signer;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.cryptography.Verifier.DefaultVerifier;
import com.hellblazer.delos.cryptography.proto.Digeste;
import com.hellblazer.delos.gorgoneion.comm.GorgoneionMetrics;
import com.hellblazer.delos.gorgoneion.comm.admissions.AdmissionsServer;
import com.hellblazer.delos.gorgoneion.comm.admissions.AdmissionsService;
import com.hellblazer.delos.gorgoneion.comm.endorsement.Endorsement;
import com.hellblazer.delos.gorgoneion.comm.endorsement.EndorsementClient;
import com.hellblazer.delos.gorgoneion.comm.endorsement.EndorsementServer;
import com.hellblazer.delos.gorgoneion.comm.endorsement.EndorsementService;
import com.hellblazer.delos.gorgoneion.proto.*;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.ring.SliceIterator;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.KeyState;
import com.hellblazer.delos.stereotomy.event.EstablishmentEvent;
import com.hellblazer.delos.stereotomy.event.InceptionEvent;
import com.hellblazer.delos.stereotomy.event.KeyEvent;
import com.hellblazer.delos.stereotomy.event.proto.Ident;
import com.hellblazer.delos.stereotomy.event.proto.KERL_;
import com.hellblazer.delos.stereotomy.event.proto.KeyEventWithAttachments;
import com.hellblazer.delos.stereotomy.event.proto.Validation_;
import com.hellblazer.delos.stereotomy.event.proto.Validations;
import com.hellblazer.delos.stereotomy.event.protobuf.ProtobufEventFactory;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.processing.KerlValidationException;
import com.hellblazer.delos.stereotomy.processing.KerlValidator;
import com.hellblazer.delos.stereotomy.services.proto.ProtoEventObserver;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.joou.ULong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import static com.hellblazer.delos.stereotomy.event.protobuf.ProtobufEventFactory.digestOf;

/**
 * Gorgoneion - Byzantine fault-tolerant identity admission service for Delos.
 * <p>
 * Gorgoneion orchestrates the decentralized bootstrapping of process identities using
 * KERI (Key Event Receipt Infrastructure) and Byzantine consensus. It provides a federation
 * framework for transforming trusted attestations into trusted KERI identifiers, enabling
 * secure identity establishment across trust boundaries.
 * </p>
 *
 * <h2>Architecture</h2>
 * <p>
 * The service consists of two primary communication channels:
 * <ul>
 *   <li><b>Admissions</b> - Handles initial applications and final registration (apply/register)</li>
 *   <li><b>Endorsement</b> - Coordinates BFT consensus among members (endorse/validate/enroll)</li>
 * </ul>
 * </p>
 *
 * <h2>Protocol Flow</h2>
 * <ol>
 *   <li><b>Apply Phase</b>: Client submits KERL → Server generates nonce with BFT endorsements</li>
 *   <li><b>Attestation Phase</b>: Client gets external attestation using nonce (AWS, GCP, Azure, etc.)</li>
 *   <li><b>Register Phase</b>: Client submits credentials → Server validates with BFT subset</li>
 *   <li><b>Notarization Phase</b>: Server distributes validated KERL to BFT subset for publication</li>
 * </ol>
 *
 * <h2>Thread Safety and Lifecycle</h2>
 * <ul>
 *   <li>Thread-safe: All public methods are safe for concurrent access</li>
 *   <li>Resources: Creates a scheduled executor service for BFT communication rounds</li>
 *   <li>Cleanup: Call {@link #close()} to shutdown executor and release resources</li>
 *   <li>Shutdown: Waits up to 30 seconds for graceful termination before forcing shutdown</li>
 * </ul>
 *
 * <h2>Byzantine Fault Tolerance</h2>
 * <ul>
 *   <li>BFT subset: Deterministically selected based on identifier digest</li>
 *   <li>Majority requirement: 3f+1 model (tolerates f Byzantine failures)</li>
 *   <li>Signature verification: All nonces, credentials, and validations cryptographically verified</li>
 *   <li>Replay prevention: Nonce-based with timestamp freshness and cache-based deduplication</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * var parameters = Parameters.newBuilder()
 *     .setKerl(kerl)
 *     .setMaxDuration(Duration.ofSeconds(30))
 *     .setClockSkewTolerance(Duration.ofSeconds(5))
 *     .build();
 *
 * var gorgoneion = new Gorgoneion(
 *     attestationVerifier,
 *     provisioner,
 *     parameters,
 *     member,
 *     context,
 *     observer,
 *     router,
 *     metrics
 * );
 *
 * try {
 *     // Service is now running and handling admission requests
 * } finally {
 *     gorgoneion.close();
 * }
 * }</pre>
 *
 * @author hal.hildebrand
 * @see Parameters
 * @see CredentialValidator
 * @see ReplayCache
 */
public class Gorgoneion implements Closeable {
    public static final Logger log = LoggerFactory.getLogger(Gorgoneion.class);

    @SuppressWarnings("unused")
    private final CommonCommunications<?, AdmissionsService>            admissionsComm;
    private final Context<Member>                                       context;
    private final CommonCommunications<Endorsement, EndorsementService> endorsementComm;
    private final ControlledIdentifierMember                            member;
    private final ProtoEventObserver                                    observer;
    private final Parameters                                            parameters;
    private final Predicate<SignedAttestation>                          verifier;
    private final ScheduledExecutorService                              scheduler;
    /**
     * Provisioner contract: Generates provisioning data for successfully validated credentials.
     *
     * <p>Contract:
     * <ul>
     *   <li>Called after credential validation succeeds and validations are collected from BFT quorum.
     *   <li>Input credentials have been validated as authentic and match expected KERL chain.
     *   <li>Validations parameter contains consensus signatures from BFT members confirming the credentials.
     *   <li>Must return an Any message containing provisioning details for the newly admitted member.
     *   <li>Should be idempotent: applying same valid (credentials, validations) pair should yield same result.
     *   <li>Should not throw checked exceptions; if provisioning fails, return an error message wrapped in Any or empty Any.
     *   <li>Null return value is treated as provisioning failure and results in no provisioning data being returned.
     * </ul>
     *
     * <p>Usage:
     * Invoked in two paths:
     * <ul>
     *   <li>Single-member context (line 326): Direct provision after local validation.
     *   <li>Multi-member context (line 355): After gathering BFT quorum validations via notarization.
     * </ul>
     */
    private final BiFunction<Credentials, Validations, Any>             provisioner;
    private final Endorse                                               service = new Endorse();
    private final ReplayCache                                           replayCache;
    private final CredentialValidator                                   credentialValidator;

    /**
     * Creates a Gorgoneion service with shared router for both admissions and endorsement.
     * <p>
     * This constructor delegates to the dual-router constructor, using the same router
     * for both communication channels. Suitable for simple deployments where a single
     * network router handles all traffic.
     * </p>
     *
     * @param verifier    predicate to verify external attestations (e.g., AWS, GCP signatures)
     * @param provisioner function to generate provisioning data after successful admission
     * @param parameters  configuration parameters (timeouts, clock, KERL)
     * @param member      the local member identity (must be controlled identifier with signing capability)
     * @param context     the membership context for BFT operations
     * @param observer    event observer for publishing validated KERLs to unified log
     * @param router      the GRPC router for both admissions and endorsement channels
     * @param metrics     metrics collector for monitoring admission operations
     * @throws NullPointerException if any parameter is null
     */
    public Gorgoneion(Predicate<SignedAttestation> verifier, BiFunction<Credentials, Validations, Any> provisioner,
                      Parameters parameters, ControlledIdentifierMember member, Context<Member> context,
                      ProtoEventObserver observer, Router router, GorgoneionMetrics metrics) {
        this(verifier, provisioner, parameters, member, context, observer, router, metrics, router);
    }

    /**
     * Get the replay cache for metrics monitoring.
     * <p>
     * Provides access to the internal replay cache for observing cache statistics
     * such as hit rate, eviction rate, and current size. Useful for operational
     * monitoring and capacity planning.
     * </p>
     *
     * @return the replay cache instance (never null)
     */
    public ReplayCache getReplayCache() {
        return replayCache;
    }

    /**
     * Creates a Gorgoneion service with separate routers for admissions and endorsement.
     * <p>
     * This is the primary constructor that initializes the full service infrastructure:
     * <ul>
     *   <li>Creates replay cache for nonce deduplication</li>
     *   <li>Initializes credential validator for timestamp and signature verification</li>
     *   <li>Establishes admissions communication channel for client-facing operations</li>
     *   <li>Establishes endorsement communication channel for BFT consensus</li>
     *   <li>Starts scheduled executor for BFT communication rounds</li>
     * </ul>
     * </p>
     *
     * <h3>Preconditions</h3>
     * <ul>
     *   <li>Member must be a controlled identifier with valid signing keys</li>
     *   <li>Context must contain at least 1 member</li>
     *   <li>KERL must be initialized in parameters</li>
     *   <li>Routers must be properly configured with MTLS</li>
     * </ul>
     *
     * <h3>Resources Created</h3>
     * <ul>
     *   <li>ScheduledExecutorService (virtual thread pool) - shutdown via {@link #close()}</li>
     *   <li>ReplayCache (Caffeine cache) - automatic TTL-based cleanup</li>
     *   <li>GRPC server channels - managed by router lifecycle</li>
     * </ul>
     *
     * @param verifier          predicate to verify external attestations (e.g., AWS, GCP signatures)
     * @param provisioner       function to generate provisioning data after successful admission
     * @param parameters        configuration parameters (timeouts, clock, KERL, digest algorithm)
     * @param member            the local member identity (must be controlled identifier with signing capability)
     * @param context           the membership context for BFT subset calculation and member lookup
     * @param observer          event observer for publishing validated KERLs to unified log
     * @param admissionsRouter  the GRPC router for client-facing admissions operations (apply/register)
     * @param metrics           metrics collector for monitoring admission operations
     * @param endorsementRouter the GRPC router for BFT consensus operations (endorse/validate/enroll)
     * @throws NullPointerException if any parameter is null
     */
    public Gorgoneion(Predicate<SignedAttestation> verifier, BiFunction<Credentials, Validations, Any> provisioner,
                      Parameters parameters, ControlledIdentifierMember member, Context<Member> context,
                      ProtoEventObserver observer, Router admissionsRouter, GorgoneionMetrics metrics,
                      Router endorsementRouter) {
        this.verifier = verifier;
        this.member = member;
        this.context = context;
        this.parameters = parameters;
        this.observer = observer;
        this.provisioner = provisioner;
        this.scheduler = Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
        this.replayCache = new ReplayCache(10000, parameters.maxDuration(), Duration.ofSeconds(5));
        this.credentialValidator = new CredentialValidator(context, parameters, member.getId());

        admissionsComm = admissionsRouter.create(member, context.getId(), new Admit(), ":admissions",
                                                 r -> new AdmissionsServer(admissionsRouter.getClientIdentityProvider(),
                                                                           r, metrics));
        endorsementComm = endorsementRouter.create(member, context.getId(), service, ":endorsement",
                                                   r -> new EndorsementServer(
                                                   admissionsRouter.getClientIdentityProvider(), r, metrics),
                                                   EndorsementClient.getCreate(metrics),
                                                   Endorsement.getLocalLoopback(member, service));
    }

    /**
     * Gracefully shuts down the Gorgoneion service and releases all resources.
     * <p>
     * This method performs an orderly shutdown:
     * <ol>
     *   <li>Checks if already shutdown (idempotent)</li>
     *   <li>Initiates executor service shutdown</li>
     *   <li>Waits up to 30 seconds for running tasks to complete</li>
     *   <li>Forces shutdown if timeout exceeded</li>
     *   <li>Interrupts current thread if interrupted during wait</li>
     * </ol>
     * </p>
     *
     * <h3>Thread Safety</h3>
     * <p>Safe to call from multiple threads. Only the first call initiates shutdown.</p>
     *
     * <h3>Blocking Behavior</h3>
     * <p>This method blocks for up to 30 seconds waiting for graceful shutdown.
     * If tasks are still running after 30 seconds, forces immediate termination.</p>
     *
     * <h3>Exception Handling</h3>
     * <p>If interrupted during shutdown wait, this method:
     * <ul>
     *   <li>Forces immediate shutdown via shutdownNow()</li>
     *   <li>Restores the thread's interrupt status</li>
     *   <li>Does not throw InterruptedException (swallows it after cleanup)</li>
     * </ul>
     * </p>
     *
     * @see java.io.Closeable#close()
     */
    @Override
    public void close() {
        if (!scheduler.isShutdown()) {
            log.debug("Shutting down scheduler on: {}", member.getId());
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("Scheduler did not terminate within timeout, forcing shutdown on: {}", member.getId());
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                log.warn("Interrupted while waiting for scheduler termination on: {}", member.getId());
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean completeEndorsement(Optional<MemberSignature> futureSailor, Set<MemberSignature> validations) {
        if (futureSailor.isEmpty()) {
            return true;
        }
        validations.add(futureSailor.get());
        return true;
    }

    private boolean completeEnrollment(Optional<Empty> futureSailor, Member m, HashSet<Member> completed) {
        if (futureSailor.isEmpty()) {
            return true;
        }
        completed.add(m);
        return true;
    }

    private boolean completeVerification(Optional<Validation_> futureSailor, Member m,
                                         HashSet<Validation_> verifications) {
        if (futureSailor.isEmpty()) {
            return false;
        }
        var v = futureSailor.get();
        verifications.add(v);
        return true;
    }

    private MemberSignature endorse(Nonce request) {
        return MemberSignature.newBuilder()
                              .setId(member.getId().toDigeste())
                              .setSignature(member.sign(request.toByteString()).toSig())
                              .build();
    }

    /**
     * Compute the expected BFT subset for an identifier and return their member digests.
     * Delegates to CredentialValidator for consistent BFT subset calculation.
     *
     * @param ident The identifier to compute subset for
     * @return Set of Digest IDs that are valid signers for this identifier
     */
    private Set<Digest> expectedBftSigners(Ident ident) {
        return credentialValidator.expectedBftSigners(ident);
    }

    private void enroll(Notarization request) {
        if (observer == null) {
            log.error("KERL observer is null, cannot publish notarization on: {}", member.getId());
            return;
        }
        log.trace("Enrolling notarization with KERL events: {} on: {}", request.getKerl().getEventsCount(), member.getId());
        observer.publish(request.getKerl(), Collections.singletonList(request.getValidations()));
    }

    private Establishment establish(Credentials credentials, Validations validations) {
        Any provisioning = null;
        try {
            provisioning = provisioner.apply(credentials, validations);
            if (provisioning == null) {
                log.warn("Provisioner returned null for credentials with {} validations", validations.getValidationsCount());
                provisioning = Any.getDefaultInstance();
            }
        } catch (Exception e) {
            log.error("Provisioner failed to generate provisioning data: {}", e.getMessage(), e);
            provisioning = Any.getDefaultInstance();
        }
        return Establishment.newBuilder()
                            .setValidations(validations)
                            .setProvisioning(provisioning)
                            .buildPartial();
    }

    private SignedNonce generateNonce(KERL_ application) {
        final var identifier = identifier(application);
        if (identifier == null) {
            throw new IllegalArgumentException("No identifier");
        }
        log.info("Generating nonce for: {} contacting: {} on: {}", identifier, identifier, member.getId());
        var now = parameters.clock().instant();
        final var ident = identifier.toIdent();
        var nonce = Nonce.newBuilder()
                         .setMember(ident)
                         .setIssuer(member.getId().toDigeste())
                         .setNoise(parameters.digestAlgorithm().random().toDigeste())
                         .setTimestamp(Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()))
                         .build();

        var successors = context.size() == 1 ? Collections.singletonList(member)
                                             : context.bftSubset(digestOf(ident, parameters.digestAlgorithm()));
        final var majority = context.size() == 1 ? 1 : context.majority();
        final var redirecting = new SliceIterator<>("Nonce Endorsement", member, successors, endorsementComm,
                                                    scheduler);
        Set<MemberSignature> endorsements = Collections.newSetFromMap(new ConcurrentHashMap<>());
        var generated = new CompletableFuture<SignedNonce>();
        redirecting.iterate((link) -> {
            log.info("Request signing nonce for: {} contacting: {} on: {}", identifier, link.getMember().getId(),
                     member.getId());
            return link.endorse(nonce, parameters.registrationTimeout());
        }, (futureSailor, _, _, _) -> completeEndorsement(futureSailor, endorsements), () -> {
            if (endorsements.size() < majority) {
                generated.completeExceptionally(new StatusRuntimeException(Status.ABORTED.withDescription(
                "Cannot gather required nonce endorsements: %s required: %s on: %s".formatted(endorsements.size(),
                                                                                              majority,
                                                                                              member.getId()))));
            } else {
                generated.complete(SignedNonce.newBuilder()
                                              .addSignatures(MemberSignature.newBuilder()
                                                                            .setId(member.getId().toDigeste())
                                                                            .setSignature(
                                                                            member.sign(nonce.toByteString()).toSig())
                                                                            .build())
                                              .setNonce(nonce)
                                              .addAllSignatures(endorsements)
                                              .build());
                log.info("Generated nonce for: {} signatures: {} on: {}", identifier, endorsements.size(),
                         member.getId());
            }
        }, parameters.frequency());
        try {
            return generated.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Nonce generation interrupted for identifier: {} on: {}", identifier, member.getId(), e);
            return null;
        } catch (ExecutionException e) {
            log.error("Nonce generation failed for identifier: {} on: {}", identifier, member.getId(), e.getCause());
            if (e.getCause() instanceof StatusRuntimeException sre) {
                throw sre;
            }
            throw new RuntimeException(e.getCause());
        }
    }

    private Identifier identifier(KERL_ kerl) {
        if (ProtobufEventFactory.from(kerl.getEvents(kerl.getEventsCount() - 1))
                                .event() instanceof EstablishmentEvent establishment) {
            return establishment.getIdentifier();
        }
        return null;
    }

    private CompletableFuture<Validations> notarize(Credentials credentials, Validations validations) {
        final var kerl = credentials.getAttestation().getAttestation().getKerl();
        final var identifier = identifier(kerl);
        if (identifier == null) {
            throw new IllegalArgumentException("No identifier");
        }

        var notarization = Notarization.newBuilder()
                                       .setKerl(credentials.getAttestation().getAttestation().getKerl())
                                       .setValidations(validations)
                                       .build();

        var successors = context.bftSubset(digestOf(identifier.toIdent(), parameters.digestAlgorithm()));
        final var majority = context.size() == 1 ? 1 : context.majority();
        SliceIterator<Endorsement> redirecting = new SliceIterator<>("Enrollment", member, successors, endorsementComm,
                                                                     scheduler);
        var completed = new HashSet<Member>();
        var result = new CompletableFuture<Validations>();
        redirecting.iterate((link) -> {
            log.info("Enrolling: {} contacting: {} on: {}", identifier, link.getMember().getId(), member.getId());
            link.enroll(notarization, parameters.registrationTimeout());
            return Empty.getDefaultInstance();
        }, (futureSailor, _, _, member) -> completeEnrollment(futureSailor, member, completed), () -> {
            if (completed.size() < majority) {
                // Complete the future exceptionally and return normally
                // Exception will be propagated when caller invokes .get() on the future
                result.completeExceptionally(new StatusRuntimeException(Status.ABORTED.withDescription("Cannot complete enrollment")));
            } else {
                // Also enroll locally on the coordinating member to publish to its own KERL
                try {
                    enroll(notarization);
                } catch (Exception e) {
                    log.error("Failed to enroll notarization locally for: {} on: {}", identifier, member.getId(), e);
                }
                result.complete(validations);
            }
        }, parameters.frequency());
        return result;
    }

    private CompletableFuture<Establishment> registerAsync(Credentials request) {
        final var kerl = request.getAttestation().getAttestation().getKerl();
        final var identifier = identifier(kerl);
        if (identifier == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("No identifier"));
        }
        log.debug("Validating credentials for: {} nonce signatures: {} on: {}", identifier,
                  request.getNonce().getSignaturesCount(), member.getId());

        var validated = new CompletableFuture<Validations>();

        var successors = context.bftSubset(digestOf(identifier.toIdent(), parameters.digestAlgorithm()));
        if (context.size() == 1) {
            var validations = Validations.newBuilder().addValidations(validate(request)).build();
            Any provisioning = null;
            try {
                provisioning = provisioner.apply(request, validations);
                if (provisioning == null) {
                    log.warn("Provisioner returned null for credentials with {} validations", validations.getValidationsCount());
                    provisioning = Any.getDefaultInstance();
                }
            } catch (Exception e) {
                log.error("Provisioner failed to generate provisioning data: {}", e.getMessage(), e);
                provisioning = Any.getDefaultInstance();
            }
            var establishment = Establishment.newBuilder()
                                             .setValidations(validations)
                                             .setProvisioning(provisioning)
                                             .build();
            return CompletableFuture.completedFuture(establishment);
        }
        final var majority = context.size() == 1 ? 1 : context.majority();
        final var redirecting = new SliceIterator<>("Credential verification", member, successors, endorsementComm,
                                                    scheduler);
        var verifications = new HashSet<Validation_>();
        redirecting.iterate((link) -> {
            log.debug("Validating  credentials for: {} contacting: {} on: {}", identifier, link.getMember().getId(),
                      member.getId());
            return link.validate(request, parameters.registrationTimeout());
        }, (futureSailor, _, _, member) -> completeVerification(futureSailor, member, verifications), () -> {
            if (verifications.size() < majority) {
                validated.completeExceptionally(new StatusRuntimeException(
                Status.ABORTED.withDescription("Cannot gather required credential validations")));
            } else {
                validated.complete(Validations.newBuilder()
                                              .setCoordinates(
                                              ProtobufEventFactory.from(kerl.getEvents(kerl.getEventsCount() - 1))
                                                                  .event()
                                                                  .getCoordinates()
                                                                  .toEventCoords())
                                              .addAllValidations(verifications)
                                              .build());
                log.debug("Validated credentials for: {} verifications: {} on: {}", identifier, verifications.size(),
                          member.getId());
            }
        }, parameters.frequency());
        // Return the chained future without blocking
        return validated.thenCompose(v -> notarize(request, v))
                       .thenApply(v -> establish(request, v));
    }

    private Establishment register(Credentials request) {
        try {
            return registerAsync(request).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Credential registration interrupted for identifier: {} on: {}",
                      identifier(request.getAttestation().getAttestation().getKerl()), member.getId(), e);
            return null;
        } catch (ExecutionException e) {
            log.error("Credential registration failed for identifier: {} on: {}",
                      identifier(request.getAttestation().getAttestation().getKerl()), member.getId(), e.getCause());
            throw new StatusRuntimeException(Status.INTERNAL.withCause(e.getCause()));
        }
    }

    private Validation_ validate(Credentials credentials) {
        var event = (InceptionEvent) ProtobufEventFactory.from(
        credentials.getAttestation().getAttestation().getKerl().getEvents(0)).event();
        Signer signer = member.getIdentifier().getSigner();
        var johnHancock = signer.sign(event.toKeyEvent_().toByteString());
        log.info("Signed credentials for: {} on: {}", event.getIdentifier(), member.getId());
        var validation = Validation_.newBuilder()
                                    .setValidator(member.getIdentifier().getCoordinates().toEventCoords())
                                    .setSignature(johnHancock.toSig())
                                    .build();
        return validation;
    }

    private Validation_ verificationOf(Credentials credentials) {
        if (verifier.test(credentials.getAttestation())) {
            return validate(credentials);
        }
        return null;
    }

    /**
     * Validates the complete KERL event chain using KerlValidator.
     * Delegates to the unified validation service for:
     * - KERL starts with InceptionEvent
     * - Event signatures against prior state
     * - Sequence number monotonicity
     * - KERL ends with EstablishmentEvent
     *
     * @param kerl the KERL protobuf containing the event chain
     * @return the final KeyState after validating all events
     * @throws StatusRuntimeException if validation fails
     */
    private KeyState validateChain(KERL_ kerl) throws StatusRuntimeException {
        try {
            var validator = new KerlValidator(parameters.kerl());
            return validator.validateChain(kerl);
        } catch (KerlValidationException e) {
            log.warn("KERL validation failed: {}", e.getMessage());
            // Map validation exceptions to appropriate gRPC status codes
            if (e.getMessage().contains("Empty KERL")) {
                throw new StatusRuntimeException(Status.UNAUTHENTICATED.withDescription(e.getMessage()));
            } else if (e.getMessage().contains("Invalid event") || e.getMessage().contains("signature")) {
                throw new StatusRuntimeException(Status.UNAUTHENTICATED.withDescription(e.getMessage()));
            } else if (e.getMessage().contains("Incomplete")) {
                throw new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription(e.getMessage()));
            } else {
                throw new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription(e.getMessage()));
            }
        }
    }

    private boolean validate(Credentials credentials, Digest from) {
        // Wrapper method: calls validateCredentials with full nonce and attestation validation
        return validateCredentials(credentials, from);
    }

    private boolean validateCredentials(Credentials credentials, Digest from) {
        var sn = credentials.getNonce();
        final var issuer = Digest.from(sn.getNonce().getIssuer());
        if (!credentialValidator.isMember(issuer)) {
            log.warn("Invalid credential nonce, non existent issuer: {} from: {} on: {}", issuer, from,
                     member.getId());
            return false;
        }
        // NOTE: We do NOT check from.equals(issuer) here because in the client registration flow:
        // - 'from' is the CLIENT making the registration request
        // - 'issuer' is the SERVER that originally issued the nonce during apply()
        // The client legitimately received the nonce from a server and is now registering.
        // The issuer being a valid context member is sufficient; signature verification
        // ensures the nonce was properly endorsed by the BFT subset.
        if (!credentialValidator.hasValidNoise(sn.getNonce().getNoise())) {
            log.warn("Invalid credential nonce, missing noise from: {} on: {}", from, member.getId());
            return false;
        }
        var nonceTimestamp = sn.getNonce().getTimestamp();
        var nInstant = CredentialValidator.toInstant(nonceTimestamp);
        if (!credentialValidator.isTimestampValid(nonceTimestamp)) {
            log.warn("Invalid credential nonce, invalid timestamp: {} (tolerance: {}ms) from: {} on: {}", nInstant,
                     parameters.clockSkewTolerance().toMillis(), from, member.getId());
            return false;
        }

        // Replay attack prevention: Check if we've seen this nonce before
        var nonceKey = new ReplayCache.NonceKey(Digest.from(sn.getNonce().getNoise()), issuer, sn.getNonce().getTimestamp());
        if (!replayCache.tryAdmit(nonceKey)) {
            log.warn("Replay attack detected: duplicate credential nonce from: {} on: {}", from, member.getId());
            return false;
        }

        final var serialized = sn.getNonce().toByteString();
        var expectedSigners = credentialValidator.expectedBftSigners(sn.getNonce().getMember());
        // Use CredentialValidator for signature counting with BFT subset enforcement
        var count = credentialValidator.countValidSignatures(sn.getSignaturesList(), expectedSigners, serialized);

        if (!credentialValidator.hasMajority(count)) {
            log.warn("Invalid credential nonce, no majority signature: {} required >= {} from: {} on: {}", count,
                     credentialValidator.getRequiredMajority(), from, member.getId());
            return false;
        }

        log.info("Valid credential nonce for: {} from: {} on: {}", Identifier.from(sn.getNonce().getMember()), from,
                 member.getId());

        var sa = credentials.getAttestation();
        final var kerl = sa.getAttestation().getKerl();

        // ISSUE #1 FIX: Validate entire KERL chain before attestation signature checks
        KeyState validatedState;
        try {
            validatedState = validateChain(kerl);
        } catch (StatusRuntimeException e) {
            log.warn("KERL chain validation failed for credentials from: {} - {}", from, e.getStatus().getDescription());
            return false;
        }

        var identifier = identifier(kerl);
        if (identifier == null) {
            log.warn("Invalid credential attestation, invalid identifier from: {} on: {}", from, member.getId());
            return false;
        }

        // NOTE: We do NOT check that KERL identifier matches 'from' (RPC caller)
        // In direct client registration: 'from' is the client
        // In BFT endorsement: 'from' is the member validating, not the client
        // The validateChain() call above already cryptographically verifies KERL ownership
        // through signature validation of the inception and all events. The KERL identifier
        // is derived from the inception event digest, and all signatures prove control of
        // that key material. This is sufficient proof of ownership across all call paths.

        var m = Identifier.from(sn.getNonce().getMember());
        if (!m.equals(identifier)) {
            log.warn("Invalid credential attestation, identifier: {} not equal to nonce member: {} from: {} on: {}",
                     identifier, m, from, member.getId());
            return false;
        }

        var attestationTimestamp = sa.getAttestation().getTimestamp();
        var aInstant = CredentialValidator.toInstant(attestationTimestamp);
        // Validate attestation timestamp and ensure it's not before the nonce timestamp
        if (!credentialValidator.isTimestampValid(attestationTimestamp) ||
            !credentialValidator.isTimestampAtOrAfter(attestationTimestamp, nonceTimestamp)) {
            log.warn("Invalid credential attestation, invalid timestamp: {} (tolerance: {}ms) for: {} from: {} on: {}",
                     aInstant, parameters.clockSkewTolerance().toMillis(), identifier, from, member.getId());
            return false;
        }

        // Validate attestation signature using final state's keys (ISSUE #9 FIX: Handle null with logging)
        if (validatedState.getLastEstablishmentEvent() != null) {
            // Get the establishment event (inception or last rotation)
            var establishment = ProtobufEventFactory.from(
                kerl.getEvents(validatedState.getLastEstablishmentEvent().getSequenceNumber().intValue())).event();

            if (establishment instanceof EstablishmentEvent est) {
                final var verifier = new Verifier.DefaultVerifier(est.getKeys());
                if (!verifier.verify(JohnHancock.from(sa.getAttestation().getNonce()), sn.toByteString())) {
                    log.warn("Invalid credential attestation, invalid nonce signature for: {} from: {} on: {}",
                             identifier, from, member.getId());
                    return false;
                }
                if (!verifier.verify(JohnHancock.from(sa.getSignature()),
                                     sa.getAttestation().toByteString())) {
                    log.warn("Invalid credential attestation, invalid attestation signature for: {} from: {} on: {}",
                             identifier, from, member.getId());
                    return false;
                }
            } else {
                log.warn("Invalid credential attestation, final event is not EstablishmentEvent for: {} from: {} on: {}",
                         identifier, from, member.getId());
                return false;
            }
        } else {
            log.warn("Invalid credential attestation, no establishment event in validated state for: {} from: {} on: {}",
                     identifier, from, member.getId());
            return false;
        }

        log.info("Valid credential attestation for: {} from: {} on: {}", identifier, from, member.getId());
        return true;
    }

    private class Admit implements AdmissionsService {

        @Override
        public void apply(KERL_ request, Digest from, StreamObserver<SignedNonce> responseObserver,
                          Timer.Context time) {
            if (!validate(request, from)) {
                log.warn("Invalid application from: {} on: {}", from, member.getId());
                responseObserver.onError(
                new StatusRuntimeException(Status.UNAUTHENTICATED.withDescription("Invalid application")));
                return;
            }
            SignedNonce sn;
            try {
                sn = generateNonce(request);
            } catch (StatusRuntimeException sre) {
                responseObserver.onError(sre);
                return;
            }

            if (sn == null) {
                responseObserver.onError(
                new StatusRuntimeException(Status.UNAUTHENTICATED.withDescription("Invalid application")));
            } else {
                responseObserver.onNext(sn);
                responseObserver.onCompleted();
            }
        }

        @Override
        public void register(Credentials request, Digest from, StreamObserver<Establishment> responseObserver,
                             Timer.Context timer) {
            if (!Gorgoneion.this.validate(request, from)) {
                log.warn("Invalid credentials from: {} on: {}", from, member.getId());
                responseObserver.onError(
                new StatusRuntimeException(Status.UNAUTHENTICATED.withDescription("Invalid credentials")));
                return;
            }
            // Start async registration without blocking the gRPC handler thread
            // Use scheduler executor to ensure response is sent on a proper executor thread
            Gorgoneion.this.registerAsync(request)
                           .whenCompleteAsync((establishment, throwable) -> {
                               if (throwable != null) {
                                   if (throwable instanceof StatusRuntimeException sre) {
                                       responseObserver.onError(sre);
                                   } else {
                                       responseObserver.onError(
                                       new StatusRuntimeException(Status.INTERNAL.withCause(throwable)));
                                   }
                               } else if (establishment == null) {
                                   responseObserver.onError(
                                   new StatusRuntimeException(Status.UNAUTHENTICATED.withDescription("Invalid credentials")));
                               } else {
                                   responseObserver.onNext(establishment);
                                   responseObserver.onCompleted();
                               }
                           }, scheduler);
        }

        private boolean validate(KERL_ kerl, Digest from) {
            try {
                // Use validateChain for complete validation
                KeyState validatedState = Gorgoneion.this.validateChain(kerl);

                // NOTE: We do NOT check that KERL identifier matches 'from' (RPC caller)
                // In direct client application: 'from' is the client
                // In BFT endorsement: 'from' is the member validating, not the client
                // The validateChain() call above already cryptographically verifies KERL ownership
                // through signature validation of the inception and all events.

                // Verify identifier is valid (SelfAddressingIdentifier)
                if (validatedState.getIdentifier() instanceof SelfAddressingIdentifier sai) {
                    log.info("Validated KERL for {} from: {} on: {}", sai.getDigest(), from, member.getId());
                    return true;
                } else {
                    log.warn("KERL identifier is not SelfAddressingIdentifier from: {} on: {}", from,
                             member.getId());
                    return false;
                }

            } catch (StatusRuntimeException e) {
                log.warn("KERL validation failed from: {} - {} on: {}", from, e.getStatus().getDescription(),
                         member.getId());
                return false;
            } catch (Exception e) {
                log.error("Error validating KERL from: {} on: {}", from, member.getId(), e);
                return false;
            }
        }

    }

    private class Endorse implements EndorsementService {

        @Override
        public MemberSignature endorse(Nonce request, Digest from) {
            if (!validate(request, from)) {
                log.warn("Invalid endorsement nonce from: {} on: {}", from, member.getId());
                throw new StatusRuntimeException(Status.UNAUTHENTICATED.withDescription("Invalid endorsement nonce"));
            }
            log.info("Endorsing nonce for: {} from: {} on: {}", Identifier.from(request.getMember()), from,
                     member.getId());
            return Gorgoneion.this.endorse(request);
        }

        @Override
        public void enroll(Notarization request, Digest from) {
            var kerl = request.getKerl();
            var identifier = identifier(kerl);
            if (!validate(request, identifier, kerl, from)) {
                log.warn("Invalid notarization for: {} from: {} on: {}", identifier, from, member.getId());
                throw new StatusRuntimeException(Status.UNAUTHENTICATED.withDescription("Invalid notarization"));
            }
            log.info("Enrolling notarization for: {} from: {} on: {}", identifier, from, member.getId());
            Gorgoneion.this.enroll(request);
        }

        @Override
        public Validation_ validate(Credentials credentials, Digest from) {
            // During BFT endorsement (collectinging validations), we only need to:
            // 1. Validate the KERL chain (cryptographic proof)
            // 2. Sign the establishment event
            // We explicitly DO NOT check the nonce replay cache here because:
            // - The nonce was already validated in Admit.register()
            // - Multiple members validating the same credential is LEGITIMATE in BFT
            // - The replay cache only needs to prevent THE SAME CLIENT from submitting twice via Admit.register()
            try {
                KeyState validatedState = validateChain(credentials.getAttestation().getAttestation().getKerl());
                if (validatedState == null) {
                    log.warn("Invalid credentials from: {} on: {}", from, member.getId());
                    throw new StatusRuntimeException(Status.UNAUTHENTICATED.withDescription("Invalid credentials"));
                }
            } catch (StatusRuntimeException e) {
                log.warn("KERL validation failed from: {} on: {} - {}", from, member.getId(), e.getStatus().getDescription());
                throw e;
            }
            return verificationOf(credentials);
        }

        private boolean validate(Nonce request, Digest from) {
            final var issuer = Digest.from(request.getIssuer());
            if (!credentialValidator.isMember(issuer)) {
                log.warn("Invalid nonce, non existent issuer: {} from: {} on: {}", issuer, from, member.getId());
                return false;
            }
            if (!from.equals(issuer)) {
                log.warn("Invalid nonce, issuer: {} not requester: {} on: {}", issuer, from, member.getId());
                return false;
            }
            if (!credentialValidator.hasValidNoise(request.getNoise())) {
                log.warn("Invalid nonce, missing noise from: {} on: {}", from, member.getId());
                return false;
            }
            if (!credentialValidator.hasValidMember(request.getMember())) {
                log.warn("Invalid nonce, missing member from: {} on: {}", from, member.getId());
                return false;
            }
            var nInstant = CredentialValidator.toInstant(request.getTimestamp());
            if (!credentialValidator.isTimestampValid(request.getTimestamp())) {
                log.warn("Invalid nonce, invalid timestamp: {} (tolerance: {}ms) from: {} on: {}", nInstant,
                         parameters.clockSkewTolerance().toMillis(), from, member.getId());
                return false;
            }

            // NOTE: We do NOT check replay cache here during nonce endorsement. The endorsement phase is part
            // of nonce generation and involves multiple BFT members signing the same nonce. The replay cache
            // is enforced during credential registration in validateCredentials() where we check if a completed
            // credential was already submitted. This prevents replay of finished registrations while allowing
            // legitimate nonce endorsements to proceed.

            log.info("Validated nonce from: {} on: {}", from, member.getId());
            return true;
        }

        private boolean validate(Notarization request, Identifier identifier, KERL_ kerl, Digest from) {
            if (ProtobufEventFactory.from(kerl.getEvents(kerl.getEventsCount() - 1))
                                    .event() instanceof EstablishmentEvent establishment) {
                // NOTE: The validators in the notarization are from the credential validation phase
                // We do NOT check if they're in the enrollment BFT subset because:
                // 1. They came from credential validation which has its own BFT subset
                // 2. Notarization just preserves who signed the nonce and validates their signatures
                // 3. The enrollment phase will separately collect signatures from enrollment BFT members
                // 4. What matters is: do these validators have valid signatures? Do we have majority?
                var count = 0;
                for (var validation : request.getValidations().getValidationsList()) {
                    try {
                        if (new DefaultVerifier(
                        parameters.kerl().getKeyState(EventCoordinates.from(validation.getValidator())).getKeys()).verify(
                        JohnHancock.from(validation.getSignature()), establishment.toKeyEvent_().toByteString())) {
                            count++;
                        } else {
                            log.warn("Invalid notarization, invalid validation signature for: {} from: {} on: {}", identifier, from,
                                     member.getId());
                        }
                    } catch (Exception e) {
                        log.warn("Error verifying notarization signature for: {} from: {} on: {}", identifier, from,
                                 member.getId(), e);
                    }
                }
                // Use CredentialValidator for consistent majority calculation
                if (!credentialValidator.hasMajority(count)) {
                    log.warn("Invalid notarization, no majority: {} required: {} for: {} from: {} on: {}", count,
                             credentialValidator.getRequiredMajority(), identifier, from, member.getId());
                    return false;
                }
                return true;
            } else {
                log.warn("Invalid notarization, invalid kerl for: {} from: {} on: {}", identifier, from,
                         member.getId());
                return false;
            }
        }
    }
}
