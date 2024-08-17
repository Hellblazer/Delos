/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.model;

import com.google.protobuf.Message;
import com.hellblazer.delos.choam.CHOAM;
import com.hellblazer.delos.choam.Parameters;
import com.hellblazer.delos.choam.Parameters.RuntimeParameters;
import com.hellblazer.delos.choam.proto.Join;
import com.hellblazer.delos.choam.proto.Transaction;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.Signer;
import com.hellblazer.delos.delphinius.Oracle;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.model.delphinius.ShardedOracle;
import com.hellblazer.delos.state.Mutator;
import com.hellblazer.delos.state.SqlStateMachine;
import com.hellblazer.delos.state.proto.Migration;
import com.hellblazer.delos.state.proto.Txn;
import com.hellblazer.delos.stereotomy.ControlledIdentifier;
import com.hellblazer.delos.stereotomy.KERL.EventWithAttachments;
import com.hellblazer.delos.stereotomy.event.proto.Attachment;
import com.hellblazer.delos.stereotomy.event.proto.AttachmentEvent;
import com.hellblazer.delos.stereotomy.event.proto.KERL_;
import com.hellblazer.delos.stereotomy.event.proto.KeyEventWithAttachments;
import com.hellblazer.delos.stereotomy.event.protobuf.InteractionEventImpl;
import com.hellblazer.delos.stereotomy.event.protobuf.ProtobufEventFactory;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.joou.ULong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.JDBCType;
import java.util.*;

import static com.hellblazer.delos.cryptography.QualifiedBase64.qb64;
import static java.nio.file.Path.of;

/**
 * An abstract sharded domain, top level, or subdomain. A domain minimally consists of a managed KERL, ReBAC Oracle and
 * the defined membership
 *
 * @author hal.hildebrand
 */
abstract public class Domain {

    private static final Logger log = LoggerFactory.getLogger(Domain.class);

    protected final CHOAM                      choam;
    protected final ControlledIdentifierMember member;
    protected final Mutator                    mutator;
    protected final Oracle                     oracle;
    protected final Parameters                 params;
    protected final SqlStateMachine            sqlStateMachine;
    protected final Connection                 stateConnection;

    public Domain(ControlledIdentifierMember member, Parameters.Builder params, String dbURL, Path checkpointBaseDir,
                  RuntimeParameters.Builder runtime) {
        var paramsClone = params.clone();
        var runtimeClone = runtime.clone();
        this.member = member;
        var dir = checkpointBaseDir.toFile();
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                throw new IllegalArgumentException("Cannot create checkpoint base directory: " + checkpointBaseDir);
            }
        }
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("Must be a directory: " + checkpointBaseDir);
        }
        var checkpointDir = new File(dir, qb64(member.getIdentifier().getDigest()));
        sqlStateMachine = new SqlStateMachine(member.getId(), dbURL, new Properties(), checkpointDir);

        paramsClone.getProducer().ethereal().setSigner(member);
        this.params = paramsClone.build(runtimeClone.setCheckpointer(sqlStateMachine.getCheckpointer())
                                                    .setProcessor(sqlStateMachine.getExecutor())
                                                    .setMember(member)
                                                    .setRestorer(sqlStateMachine.getBootstrapper())
                                                    .setKerl(this::kerl)
                                                    .setGenesisData(this::genesisOf)
                                                    .build());
        choam = new CHOAM(this.params);
        mutator = sqlStateMachine.getMutator(choam.getSession());
        stateConnection = sqlStateMachine.newConnection();
        this.oracle = new ShardedOracle(stateConnection, mutator, params.getSubmitTimeout(),
                                        //                              () -> ULong.valueOf(System.currentTimeMillis()));
                                        () -> sqlStateMachine.getCurrentBlock().height());
        log.info("Domain: {} member: {} db URL: {} checkpoint base dir: {}", this.params.context().getId(),
                 member.getId(), dbURL, checkpointBaseDir);
    }

    public static Txn boostrapMigration() {
        Map<Path, URL> resources = new HashMap<>();
        resources.put(of("/initialize.xml"), res("/initialize.xml"));
        resources.put(of("/stereotomy/initialize.xml"), res("/stereotomy/initialize.xml"));
        resources.put(of("/stereotomy/stereotomy.xml"), res("/stereotomy/stereotomy.xml"));
        resources.put(of("/stereotomy/uni-kerl.xml"), res("/stereotomy/uni-kerl.xml"));
        resources.put(of("/delphinius/initialize.xml"), res("/delphinius/initialize.xml"));
        resources.put(of("/delphinius/delphinius.xml"), res("/delphinius/delphinius.xml"));
        resources.put(of("/delphinius/delphinius-functions.xml"), res("/delphinius/delphinius-functions.xml"));
        resources.put(of("/model/model.xml"), res("/model/model.xml"));

        return Txn.newBuilder()
                  .setMigration(
                  Migration.newBuilder().setUpdate(Mutator.changeLog(resources, "/initialize.xml")).build())
                  .build();
    }

    public static Path tempDirOf(ControlledIdentifier<SelfAddressingIdentifier> id) {
        Path dir;
        try {
            dir = Files.createTempDirectory(qb64(id.getDigest()));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create temporary directory", e);
        }
        dir.toFile().deleteOnExit();
        return dir;
    }

    private static URL res(String resource) {
        return Domain.class.getResource(resource);
    }

    private static Transaction transactionOf(Message message, SignatureAlgorithm signatureAlgorithm,
                                             DigestAlgorithm digestAlgorithm) {
        ByteBuffer buff = ByteBuffer.allocate(4);
        buff.putInt(0);
        buff.flip();
        var signer = new Signer.MockSigner(signatureAlgorithm, ULong.MIN);
        var digeste = digestAlgorithm.getOrigin().toDigeste();
        var sig = signer.sign(digeste.toByteString().asReadOnlyByteBuffer(), buff,
                              message.toByteString().asReadOnlyByteBuffer());
        return Transaction.newBuilder()
                          .setSource(digeste)
                          .setContent(message.toByteString())
                          .setSignature(sig.toSig())
                          .build();
    }

    public boolean active() {
        return choam.active();
    }

    public Context<Member> getContext() {
        return choam.context();
    }

    /**
     * @return the RBAC Oracle
     */
    public Oracle getDelphi() {
        return oracle;
    }

    /**
     * @return the Identifier of the receiver
     */
    public Identifier getIdentifier() {
        return member.getIdentifier().getIdentifier();
    }

    public ControlledIdentifierMember getMember() {
        return member;
    }

    public String logState() {
        return choam.logState();
    }

    public void start() {
        choam.start();
    }

    public void stop() {
        choam.stop();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + getIdentifier() + "]";
    }

    protected Transaction migrations() {
        return null;
    }

    protected Transaction transactionOf(Message message) {
        var signatureAlgorithm = params.viewSigAlgorithm();
        var digestAlgorithm = params.digestAlgorithm();
        return transactionOf(message, signatureAlgorithm, digestAlgorithm);
    }

    // Provide the list of transactions establishing the unified KERL of the group
    private List<Transaction> genesisOf(Map<Member, Join> members) {
        var sorted = new ArrayList<>(members.keySet());
        sorted.sort(Comparator.naturalOrder());
        log.info("Genesis joins: {} on: {}", sorted.stream().map(m -> m.getId()).toList(), params.member().getId());
        List<Transaction> transactions = new ArrayList<>();
        // Schemas
        transactions.add(transactionOf(boostrapMigration()));
        var migrations = migrations();
        if (migrations != null) {
            // additional SQL migrations
            transactions.add(migrations);
        }
        sorted.stream()
              .map(e -> manifest(members.get(e)))
              .filter(Objects::nonNull)
              .flatMap(Collection::stream)
              .forEach(transactions::add);
        return transactions;
    }

    // Answer the KERL of this node
    private KERL_ kerl() {
        List<EventWithAttachments> kerl;
        kerl = member.getIdentifier().getKerl();
        if (kerl == null) {
            return KERL_.getDefaultInstance();
        }
        var b = KERL_.newBuilder();
        kerl.stream().map(EventWithAttachments::toKeyEvente).forEach(b::addEvents);
        return b.build();
    }

    // Manifest the transactions that instantiate the KERL for this Join. The Join
    // is validated as a side effect, and if invalid, NULL is returned.
    private List<Transaction> manifest(Join join) {
        return join.getKerl().getEventsList().stream().map(this::transactionOf).toList();
    }

    private Transaction transactionOf(KeyEventWithAttachments ke) {
        var event = switch (ke.getEventCase()) {
            case EVENT_NOT_SET -> null;
            case INCEPTION -> ProtobufEventFactory.toKeyEvent(ke.getInception());
            case INTERACTION -> new InteractionEventImpl(ke.getInteraction());
            case ROTATION -> ProtobufEventFactory.toKeyEvent(ke.getRotation());
        };
        var batch = mutator.batch();
        batch.execute(
        mutator.call("{ ? = call stereotomy.append(?, ?, ?) }", Collections.singletonList(JDBCType.BINARY),
                     event.getBytes(), event.getIlk(), DigestAlgorithm.DEFAULT.digestCode()));
        if (!ke.getAttachment().equals(Attachment.getDefaultInstance())) {
            var attach = AttachmentEvent.newBuilder()
                                        .setCoordinates(event.getCoordinates().toEventCoords())
                                        .setAttachment(ke.getAttachment())
                                        .build();
            batch.execute(
            mutator.call("{ ? = call stereotomy.appendAttachment(?) }", Collections.singletonList(JDBCType.BINARY),
                         attach.toByteArray()));
        }
        return transactionOf(Txn.newBuilder().setBatched(batch.build()).build());
    }
}
