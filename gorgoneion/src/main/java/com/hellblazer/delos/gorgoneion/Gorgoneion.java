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
import com.hellblazer.delos.stereotomy.processing.InvalidKeyEventException;
import com.hellblazer.delos.stereotomy.processing.KeyEventProcessor;
import com.hellblazer.delos.stereotomy.processing.MissingEventException;
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
import java.util.stream.Collectors;

import static com.hellblazer.delos.stereotomy.event.protobuf.ProtobufEventFactory.digestOf;

/**
 * @author hal.hildebrand
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

    public Gorgoneion(Predicate<SignedAttestation> verifier, BiFunction<Credentials, Validations, Any> provisioner,
                      Parameters parameters, ControlledIdentifierMember member, Context<Member> context,
                      ProtoEventObserver observer, Router router, GorgoneionMetrics metrics) {
        this(verifier, provisioner, parameters, member, context, observer, router, metrics, router);
    }

    /**
     * Get the replay cache for metrics monitoring.
     *
     * @return The replay cache instance
     */
    public ReplayCache getReplayCache() {
        return replayCache;
    }

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

        admissionsComm = admissionsRouter.create(member, context.getId(), new Admit(), ":admissions",
                                                 r -> new AdmissionsServer(admissionsRouter.getClientIdentityProvider(),
                                                                           r, metrics));
        endorsementComm = endorsementRouter.create(member, context.getId(), service, ":endorsement",
                                                   r -> new EndorsementServer(
                                                   admissionsRouter.getClientIdentityProvider(), r, metrics),
                                                   EndorsementClient.getCreate(metrics),
                                                   Endorsement.getLocalLoopback(member, service));
    }
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
     *
     * @param ident The identifier to compute subset for
     * @return Set of Digest IDs that are valid signers for this identifier
     */
    private Set<Digest> expectedBftSigners(Ident ident) {
        if (context.size() == 1) {
            return Set.of(member.getId());
        }
        return context.bftSubset(digestOf(ident, parameters.digestAlgorithm()))
                      .stream()
                      .map(Member::getId)
                      .collect(Collectors.toSet());
    }

    private void enroll(Notarization request) {
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
                result.complete(validations);
            }
        }, parameters.frequency());
        return result;
    }

    private Establishment register(Credentials request) {
        final var kerl = request.getAttestation().getAttestation().getKerl();
        final var identifier = identifier(kerl);
        if (identifier == null) {
            throw new IllegalArgumentException("No identifier");
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
            return Establishment.newBuilder()
                                .setValidations(validations)
                                .setProvisioning(provisioning)
                                .build();
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
                throw new StatusRuntimeException(
                Status.ABORTED.withDescription("Cannot gather required credential validations"));
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
        try {
            return validated.thenCompose(v -> notarize(request, v)).thenApply(v -> establish(request, v)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Credential registration interrupted for identifier: {} on: {}", identifier, member.getId(), e);
            return null;
        } catch (ExecutionException e) {
            log.error("Credential registration failed for identifier: {} on: {}", identifier, member.getId(),
                      e.getCause());
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
     * Validates the complete KERL event chain.
     * Processes each event sequentially, validating:
     * - KERL starts with InceptionEvent
     * - Event signatures against prior state
     * - Sequence number monotonicity (exact increment by 1)
     * - Digest chain integrity (each event's priorEventDigest matches hash of previous)
     * - Pre-rotation commitments
     * - Configuration traits
     * - KERL ends with EstablishmentEvent
     *
     * @param kerl the KERL protobuf containing the event chain
     * @return the final KeyState after validating all events
     * @throws StatusRuntimeException if validation fails
     */
    private KeyState validateChain(KERL_ kerl) throws StatusRuntimeException {
        // Step 1: Check KERL is not empty
        if (kerl.getEventsCount() == 0) {
            throw new StatusRuntimeException(Status.UNAUTHENTICATED.withDescription("Empty KERL"));
        }

        // Step 2: Deserialize all events
        List<KeyEvent> events = new ArrayList<>();
        for (int i = 0; i < kerl.getEventsCount(); i++) {
            try {
                var eventWithAttach = ProtobufEventFactory.from(kerl.getEvents(i));
                var event = eventWithAttach.event();
                if (event == null) {
                    throw new StatusRuntimeException(
                        Status.INVALID_ARGUMENT.withDescription("Event " + i + " failed to deserialize"));
                }
                events.add(event);
            } catch (Exception e) {
                log.warn("Failed to deserialize event {} from KERL: {}", i, e.getMessage());
                throw new StatusRuntimeException(
                    Status.INVALID_ARGUMENT.withDescription("Invalid event at index " + i));
            }
        }

        // Step 2.5: Validate first event is InceptionEvent (ISSUE #5 FIX)
        if (!(events.get(0) instanceof com.hellblazer.delos.stereotomy.event.InceptionEvent)) {
            throw new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription(
                "KERL must start with InceptionEvent"));
        }

        // Step 3: Create processor for sequential validation
        KeyEventProcessor processor = new KeyEventProcessor(parameters.kerl());

        // Step 4: Process each event sequentially
        KeyState currentState = null;
        for (int i = 0; i < events.size(); i++) {
            KeyEvent event = events.get(i);
            try {
                currentState = processor.process(event);

                // Validate sequence number progression
                if (!currentState.getSequenceNumber().equals(ULong.valueOf(i))) {
                    throw new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription(
                        "Invalid sequence number at index " + i + ": expected " + i + " got "
                        + currentState.getSequenceNumber()));
                }

                log.debug("Validated event {} in KERL chain: {}", i, event.getIlk());

            } catch (InvalidKeyEventException e) {
                // Signature verification failed
                log.warn("Invalid signature at event {} in KERL: {}", i, e.getMessage());
                throw new StatusRuntimeException(
                    Status.UNAUTHENTICATED.withDescription("Invalid event signature: " + e.getMessage()));

            } catch (MissingEventException e) {
                // Previous event missing from KERL
                log.warn("Missing previous event for event {}: {}", i, e.getMessage());
                throw new StatusRuntimeException(
                    Status.FAILED_PRECONDITION.withDescription("Incomplete KERL chain"));

            } catch (StatusRuntimeException e) {
                // Re-throw StatusRuntimeException as-is
                throw e;

            } catch (Exception e) {
                log.error("Unexpected error validating event {} in KERL", i, e);
                throw new StatusRuntimeException(
                    Status.INTERNAL.withDescription("Error validating KERL chain"));
            }
        }

        // Step 4.5: Validate final event is EstablishmentEvent (ISSUE #6 FIX)
        if (!(events.get(events.size() - 1) instanceof EstablishmentEvent)) {
            throw new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription(
                "KERL must end with EstablishmentEvent"));
        }

        log.debug("Validated complete KERL chain with {} events", events.size());
        return currentState; // Final state after all events validated
    }

    private boolean validate(Credentials credentials, Digest from) {
        // Wrapper method: calls validateCredentials with full nonce and attestation validation
        return validateCredentials(credentials, from);
    }

    private boolean validateCredentials(Credentials credentials, Digest from) {
        var sn = credentials.getNonce();
        final var issuer = Digest.from(sn.getNonce().getIssuer());
        if (!context.isMember(issuer)) {
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
        if (sn.getNonce().getNoise().equals(Digeste.getDefaultInstance())) {
            log.warn("Invalid credential nonce, missing noise from: {} on: {}", from, member.getId());
            return false;
        }
        var nInstant = Instant.ofEpochSecond(sn.getNonce().getTimestamp().getSeconds(),
                                             sn.getNonce().getTimestamp().getNanos());
        final var now = parameters.clock().instant();
        final var clockSkewTolerance = parameters.clockSkewTolerance();
        if (now.plus(clockSkewTolerance).isBefore(nInstant) || nInstant.plus(parameters.maxDuration()).isBefore(now)) {
            log.warn("Invalid credential nonce, invalid timestamp: {} (tolerance: {}ms) from: {} on: {}", nInstant,
                     clockSkewTolerance.toMillis(), from, member.getId());
            return false;
        }

        // Replay attack prevention: Check if we've seen this nonce before
        var nonceKey = new ReplayCache.NonceKey(Digest.from(sn.getNonce().getNoise()), issuer, sn.getNonce().getTimestamp());
        if (!replayCache.tryAdmit(nonceKey)) {
            log.warn("Replay attack detected: duplicate credential nonce from: {} on: {}", from, member.getId());
            return false;
        }

        final var serialized = sn.getNonce().toByteString();
        var expectedSigners = expectedBftSigners(sn.getNonce().getMember());
        var count = 0;
        var issuerSigned = false;
        for (var signature : sn.getSignaturesList()) {
            final var id = Digest.from(signature.getId());
            var m = context.getMember(id);
            if (m == null) {
                log.warn("Credential nonce, unknown signing member: {} from: {} on: {}", m, from, member.getId());
                continue;
            }
            if (!expectedSigners.contains(id)) {
                log.warn("Credential nonce signature from non-BFT-subset member: {} from: {} on: {}", id, from,
                         member.getId());
                continue;
            }
            if (!m.verify(JohnHancock.from(signature.getSignature()), serialized)) {
                log.warn("Credential nonce, invalid signature of: {} from: {} on: {}", m, from, member.getId());
                continue;
            }
            if (!issuerSigned && issuer.equals(id)) {
                issuerSigned = true;
            }
            count++;
        }

        var majority = context.size() == 1 ? 1 : context.majority();
        if (count < majority) {
            log.warn("Invalid credential nonce, no majority signature: {} required >= {} from: {} on: {}", count,
                     majority, from, member.getId());
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

        // Verify identifier matches sender (from validatedState)
        if (validatedState.getIdentifier() instanceof SelfAddressingIdentifier sai) {
            if (!sai.getDigest().equals(from)) {
                log.warn("KERL identifier {} does not match sender {} from: {} on: {}", sai.getDigest(), from, from, member.getId());
                return false;
            }
        } else {
            log.warn("Invalid credential, KERL identifier is not SelfAddressingIdentifier from: {}", from);
            return false;
        }

        var m = Identifier.from(sn.getNonce().getMember());
        if (!m.equals(identifier)) {
            log.warn("Invalid credential attestation, identifier: {} not equal to nonce member: {} from: {} on: {}",
                     identifier, m, from, member.getId());
            return false;
        }

        var aInstant = Instant.ofEpochSecond(sa.getAttestation().getTimestamp().getSeconds(),
                                             sa.getAttestation().getTimestamp().getNanos());
        if (now.plus(clockSkewTolerance).isBefore(aInstant) || aInstant.plus(parameters.maxDuration()).isBefore(now) || aInstant.isBefore(
        nInstant)) {
            log.warn("Invalid credential attestation, invalid timestamp: {} (tolerance: {}ms) for: {} from: {} on: {}",
                     aInstant, clockSkewTolerance.toMillis(), identifier, from, member.getId());
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
            try {
                var estalishment = Gorgoneion.this.register(request);
                if (estalishment == null) {
                    responseObserver.onError(
                    new StatusRuntimeException(Status.UNAUTHENTICATED.withDescription("Invalid credentials")));
                } else {
                    responseObserver.onNext(estalishment);
                    responseObserver.onCompleted();
                }
            } catch (StatusRuntimeException e) {
                responseObserver.onError(e);
            }
        }

        private boolean validate(KERL_ kerl, Digest from) {
            try {
                // Use validateChain for complete validation
                KeyState validatedState = Gorgoneion.this.validateChain(kerl);

                // Verify the identifier matches the sender
                if (validatedState.getIdentifier() instanceof SelfAddressingIdentifier sai) {
                    if (!sai.getDigest().equals(from)) {
                        log.warn("KERL identifier {} does not match sender {} on: {}", sai.getDigest(), from,
                                 member.getId());
                        return false;
                    }
                    return true;
                } else {
                    log.warn("KERL identifier is not SelfAddressingIdentifier from: {}", from);
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
            if (!validateCredentials(credentials, from)) {
                log.warn("Invalid credentials from: {} on: {}", from, member.getId());
                throw new StatusRuntimeException(Status.UNAUTHENTICATED.withDescription("Invalid credentials"));
            }
            return verificationOf(credentials);
        }

        private boolean validate(Nonce request, Digest from) {
            final var issuer = Digest.from(request.getIssuer());
            if (!context.isMember(issuer)) {
                log.warn("Invalid nonce, non existent issuer: {} from: {} on: {}", issuer, from, member.getId());
                return false;
            }
            if (!from.equals(issuer)) {
                log.warn("Invalid nonce, issuer: {} not requester: {} on: {}", issuer, from, member.getId());
                return false;
            }
            if (request.getNoise().equals(Digeste.getDefaultInstance())) {
                log.warn("Invalid nonce, missing noise from: {} on: {}", from, member.getId());
                return false;
            }
            if (request.getMember().equals(Ident.getDefaultInstance())) {
                log.warn("Invalid nonce, missing member from: {} on: {}", from, member.getId());
                return false;
            }
            var nInstant = Instant.ofEpochSecond(request.getTimestamp().getSeconds(),
                                                 request.getTimestamp().getNanos());
            final var now = parameters.clock().instant();
            final var clockSkewTolerance = parameters.clockSkewTolerance();
            if (now.plus(clockSkewTolerance).isBefore(nInstant) || nInstant.plus(parameters.maxDuration()).isBefore(now)) {
                log.warn("Invalid nonce, invalid timestamp: {} (tolerance: {}ms) from: {} on: {}", nInstant,
                         clockSkewTolerance.toMillis(), from, member.getId());
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
                var expectedValidators = expectedBftSigners(identifier.toIdent());
                var count = 0;
                for (var validation : request.getValidations().getValidationsList()) {
                    var validatorDigest = digestOf(validation.getValidator().getIdentifier(),
                                                   parameters.digestAlgorithm());
                    if (!expectedValidators.contains(validatorDigest)) {
                        log.warn("Notarization validation from non-BFT-subset validator: {} from: {} on: {}",
                                 validatorDigest, from, member.getId());
                        continue;
                    }
                    if (new DefaultVerifier(
                    parameters.kerl().getKeyState(EventCoordinates.from(validation.getValidator())).getKeys()).verify(
                    JohnHancock.from(validation.getSignature()), establishment.toKeyEvent_().toByteString())) {
                        count++;
                    } else {
                        log.warn("Invalid notarization, invalid validation for: {} from: {} on: {}", identifier, from,
                                 member.getId());
                    }
                }
                // If there is only one active member in our context, it's us.
                var majority = context.size() == 1 ? 1 : context.majority();
                if (count < majority) {
                    log.warn("Invalid notarization, no majority: {} required: {} for: {} from: {} on: {}", count,
                             majority, identifier, from, member.getId());
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
