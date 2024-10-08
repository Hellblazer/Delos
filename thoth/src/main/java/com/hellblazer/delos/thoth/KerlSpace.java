/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.hellblazer.delos.thoth;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hellblazer.delos.bloomFilters.BloomFilter;
import com.hellblazer.delos.bloomFilters.BloomFilter.DigestBloomFilter;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.proto.Biff;
import com.hellblazer.delos.cryptography.proto.Digeste;
import com.hellblazer.delos.stereotomy.DigestKERL;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.KERL;
import com.hellblazer.delos.stereotomy.event.KeyEvent;
import com.hellblazer.delos.stereotomy.event.proto.*;
import com.hellblazer.delos.stereotomy.event.protobuf.AttachmentEventImpl;
import com.hellblazer.delos.stereotomy.event.protobuf.ProtobufEventFactory;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.thoth.proto.Intervals;
import com.hellblazer.delos.thoth.proto.Update;
import org.h2.jdbcx.JdbcConnectionPool;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.jooq.impl.DSL;
import org.joou.ULong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.hellblazer.delos.stereotomy.db.UniKERL.compress;
import static com.hellblazer.delos.stereotomy.db.UniKERL.decompress;
import static com.hellblazer.delos.stereotomy.schema.tables.Coordinates.COORDINATES;
import static com.hellblazer.delos.stereotomy.schema.tables.Event.EVENT;
import static com.hellblazer.delos.stereotomy.schema.tables.Identifier.IDENTIFIER;
import static com.hellblazer.delos.thoth.schema.tables.IdentifierLocationHash.IDENTIFIER_LOCATION_HASH;
import static com.hellblazer.delos.thoth.schema.tables.PendingAttachment.PENDING_ATTACHMENT;
import static com.hellblazer.delos.thoth.schema.tables.PendingCoordinates.PENDING_COORDINATES;
import static com.hellblazer.delos.thoth.schema.tables.PendingEvent.PENDING_EVENT;
import static com.hellblazer.delos.thoth.schema.tables.PendingValidations.PENDING_VALIDATIONS;
import static com.hellblazer.delos.utils.Utils.b64;

/**
 * Represents the replicated KERL logic
 *
 * @author hal.hildebrand
 */
public class KerlSpace {
    private static final Logger             log = LoggerFactory.getLogger(KerlSpace.class);
    private final        JdbcConnectionPool connectionPool;
    private final        Digest             member;
    private final        DigestAlgorithm    algorithm;

    public KerlSpace(JdbcConnectionPool connectionPool, Digest member, DigestAlgorithm algorithm) {
        this.connectionPool = connectionPool;
        this.member = member;
        this.algorithm = algorithm;
    }

    public static void upsert(DSLContext dsl, EventCoords coordinates, Attachment attachment, Digest member) {
        final var identBytes = b64(coordinates.getIdentifier());

        var ident = dsl.newRecord(IDENTIFIER);
        ident.setPrefix(identBytes);
        ident.merge();

        Record1<Long> id;
        var coords = b64(Digest.from(coordinates.getDigest()).getBytes());
        try {
            id = dsl.insertInto(PENDING_COORDINATES)
                    .set(PENDING_COORDINATES.DIGEST, coords)
                    .set(PENDING_COORDINATES.IDENTIFIER,
                         dsl.select(IDENTIFIER.ID).from(IDENTIFIER).where(IDENTIFIER.PREFIX.eq(identBytes)))
                    .set(PENDING_COORDINATES.ILK, coordinates.getIlk())
                    .set(PENDING_COORDINATES.SEQUENCE_NUMBER,
                         ULong.valueOf(coordinates.getSequenceNumber()).toBigInteger())
                    .returningResult(PENDING_COORDINATES.ID)
                    .fetchOne();
        } catch (IntegrityConstraintViolationException e) {
            // Already exists
            id = dsl.select(PENDING_COORDINATES.ID)
                    .from(PENDING_COORDINATES)
                    .join(IDENTIFIER)
                    .on(IDENTIFIER.PREFIX.eq(identBytes))
                    .where(PENDING_COORDINATES.IDENTIFIER.eq(IDENTIFIER.ID))
                    .and(PENDING_COORDINATES.DIGEST.eq(coords))
                    .and(PENDING_COORDINATES.SEQUENCE_NUMBER.eq(
                    ULong.valueOf(coordinates.getSequenceNumber()).toBigInteger()))
                    .and(PENDING_COORDINATES.ILK.eq(coordinates.getIlk()))
                    .fetchOne();
        }
        var vRec = dsl.newRecord(PENDING_ATTACHMENT);
        vRec.setCoordinates(id.value1());
        vRec.setAttachment(b64(attachment.toByteArray()));
        vRec.insert();
    }

    public static void upsert(DSLContext context, KeyEvent event, DigestAlgorithm digestAlgorithm, Digest member) {
        final EventCoordinates prevCoords = event.getPrevious();

        final var identBytes = b64(event.getIdentifier().toIdent());

        context.mergeInto(IDENTIFIER)
               .using(context.selectOne())
               .on(IDENTIFIER.PREFIX.eq(identBytes))
               .whenNotMatchedThenInsert(IDENTIFIER.PREFIX)
               .values(identBytes)
               .execute();
        long id;
        var prev = b64(prevCoords.getDigest().getBytes());
        try {
            id = context.insertInto(PENDING_COORDINATES)
                        .set(PENDING_COORDINATES.DIGEST, prev)
                        .set(PENDING_COORDINATES.IDENTIFIER,
                             context.select(IDENTIFIER.ID).from(IDENTIFIER).where(IDENTIFIER.PREFIX.eq(identBytes)))
                        .set(PENDING_COORDINATES.ILK, event.getIlk())
                        .set(PENDING_COORDINATES.SEQUENCE_NUMBER, event.getSequenceNumber().toBigInteger())
                        .returningResult(PENDING_COORDINATES.ID)
                        .fetchOne()
                        .value1();
        } catch (IntegrityConstraintViolationException e) {
            // Already exists
            var coordinates = event.getCoordinates();
            var result = context.select(PENDING_COORDINATES.ID)
                                .from(PENDING_COORDINATES)
                                .join(IDENTIFIER)
                                .on(IDENTIFIER.PREFIX.eq(identBytes))
                                .where(PENDING_COORDINATES.IDENTIFIER.eq(IDENTIFIER.ID))
                                .and(PENDING_COORDINATES.DIGEST.eq(prev))
                                .and(
                                PENDING_COORDINATES.SEQUENCE_NUMBER.eq(coordinates.getSequenceNumber().toBigInteger()))
                                .and(PENDING_COORDINATES.ILK.eq(coordinates.getIlk()))
                                .fetchOne();
            if (result == null) {
                throw new IllegalStateException("upsert failed", e);
            }
            id = result.value1();
        }

        final var digest = event.hash(digestAlgorithm);
        try {
            context.insertInto(PENDING_EVENT)
                   .set(PENDING_EVENT.COORDINATES, id)
                   .set(PENDING_EVENT.DIGEST, b64(digest.getBytes()))
                   .set(PENDING_EVENT.EVENT, compress(event.getBytes()))
                   .execute();
        } catch (DataAccessException e) {
        }
    }

    public static void upsert(DSLContext dsl, Validations validations, Digest member) {
        final var coordinates = validations.getCoordinates();
        final var logCoords = EventCoordinates.from(coordinates);
        final var logIdentifier = Identifier.from(coordinates.getIdentifier());
        log.trace("Upserting validations for: {} on: {}", logCoords, member);
        final var identBytes = b64(coordinates.getIdentifier());

        try {
            dsl.mergeInto(IDENTIFIER)
               .using(dsl.selectOne())
               .on(IDENTIFIER.PREFIX.eq(identBytes))
               .whenNotMatchedThenInsert(IDENTIFIER.PREFIX)
               .values(identBytes)
               .execute();
        } catch (DataAccessException e) {
            log.trace("Duplicate inserting identifier: {}", logIdentifier);
        }

        Record1<Long> id;
        var d = b64(Digest.from(coordinates.getDigest()).getBytes());
        try {
            id = dsl.insertInto(PENDING_COORDINATES)
                    .set(PENDING_COORDINATES.DIGEST, d)
                    .set(PENDING_COORDINATES.IDENTIFIER,
                         dsl.select(IDENTIFIER.ID).from(IDENTIFIER).where(IDENTIFIER.PREFIX.eq(identBytes)))
                    .set(PENDING_COORDINATES.ILK, coordinates.getIlk())
                    .set(PENDING_COORDINATES.SEQUENCE_NUMBER,
                         ULong.valueOf(coordinates.getSequenceNumber()).toBigInteger())
                    .returningResult(PENDING_COORDINATES.ID)
                    .fetchOne();
            log.trace("Id: {} for: {} on: {}", id.value1(), logCoords, member);
        } catch (DataAccessException e) {
            log.trace("access exception for: {} on: {}", logCoords, e, member);
            // Already exists
            id = dsl.select(PENDING_COORDINATES.ID)
                    .from(PENDING_COORDINATES)
                    .join(IDENTIFIER)
                    .on(IDENTIFIER.PREFIX.eq(identBytes))
                    .where(PENDING_COORDINATES.IDENTIFIER.eq(IDENTIFIER.ID))
                    .and(PENDING_COORDINATES.DIGEST.eq(d))
                    .and(PENDING_COORDINATES.SEQUENCE_NUMBER.eq(
                    ULong.valueOf(coordinates.getSequenceNumber()).toBigInteger()))
                    .and(PENDING_COORDINATES.ILK.eq(coordinates.getIlk()))
                    .fetchOne();
        }
        if (id == null) {
            log.trace("Null coordinates ID for: {} on: {}", coordinates, member);
            return;
        }
        var vRec = dsl.newRecord(PENDING_VALIDATIONS);
        vRec.setCoordinates(id.value1());
        vRec.setValidations(b64(validations));
        vRec.insert();
    }

    /**
     * Answer the bloom filter encoding the key events contained within the combined intervals
     *
     * @param seed      - the seed for the bloom filter's hash generator
     * @param intervals - the combined intervals containing the identifier location hashes.
     * @param fpr       - the false positive rate for the bloom filter
     * @return the bloom filter of Digests bounded by the identifier location hash intervals
     */
    public Biff populate(long seed, CombinedIntervals intervals, double fpr) {
        DigestBloomFilter bff = new DigestBloomFilter(seed, Math.max(cardinality(), 100), fpr);
        try (var connection = connectionPool.getConnection()) {
            var dsl = DSL.using(connection, SQLDialect.H2);
            eventDigestsIn(intervals, dsl).forEach(d -> {
                log.trace("Adding reconcile digest: {} on: {}", d, member);
                bff.add(d);
            });
        } catch (SQLException e) {
            log.error("Unable populate bloom filter, cannot acquire JDBC connection on: {}", member, e);
        }
        return bff.toBff();
    }

    /**
     * Reconcile the intervals for our partner
     *
     * @param intervals - the relevant intervals of identifiers and the event digests of these identifiers the partner
     *                  already have
     * @param kerl
     * @return the Update.Builder of missing key events, based on the supplied intervals
     */
    public Update.Builder reconcile(Intervals intervals, DigestKERL kerl) {
        var biff = BloomFilter.from(intervals.getHave());
        var update = Update.newBuilder();
        try (var connection = connectionPool.getConnection()) {
            var dsl = DSL.using(connection, SQLDialect.H2);
            intervals.getIntervalsList()
                     .stream()
                     .map(KeyInterval::new)
                     .flatMap(i -> eventDigestsIn(i, dsl))
                     .peek(d -> log.trace("reconcile digest: {} on: {}", d, member))
                     .filter(d -> !biff.contains(d))
                     .peek(d -> log.trace("filtered reconcile digest: {} on: {}", d, member))
                     .map(d -> event(d, dsl, kerl))
                     .filter(Objects::nonNull)
                     .forEach(update::addEvents);
        } catch (SQLException e) {
            log.error("Unable to provide estimated cardinality, cannot acquire JDBC connection on: {}", member, e);
            throw new IllegalStateException(
            "Unable to provide estimated cardinality, cannot acquire JDBC connection on:" + member, e);
        }
        return update;
    }

    /**
     * Update the key events in this space
     *
     * @param events
     * @param kerl
     */
    public void update(List<KeyEventWithAttachmentAndValidations_> events, KERL.AppendKERL kerl) {
        if (events.isEmpty()) {
            log.trace("No events to update on: {}", member);
            return;
        }

        log.trace("Events to update: {}", events.size());
        final var digestAlgorithm = kerl.getDigestAlgorithm();

        try (var connection = connectionPool.getConnection()) {
            var dsl = DSL.using(connection, SQLDialect.H2);
            dsl.transaction(ctx -> {
                var context = DSL.using(ctx);
                for (var evente_ : events) {
                    final var event = ProtobufEventFactory.from(evente_.getEvent());
                    if (!evente_.getValidations().equals(Validations.getDefaultInstance())) {
                        upsert(context, evente_.getValidations(), member);
                    }
                    if (evente_.hasAttachment()) {
                        upsert(context, event.getCoordinates().toEventCoords(), evente_.getAttachment(), member);
                    }
                    upsert(context, event, digestAlgorithm, member);
                }
            });
            commitPending(dsl, kerl);
        } catch (SQLException e) {
            log.error("Unable to update events, cannot acquire JDBC connection on: {}", member, e);
            throw new IllegalStateException("Unable to update events, cannot acquire JDBC connection on: " + member, e);
        }
    }

    // the estimated cardinality of the number of key events
    private int cardinality() {
        try (var connection = connectionPool.getConnection()) {
            var dsl = DSL.using(connection, SQLDialect.H2);
            return dsl.fetchCount(dsl.selectFrom(IDENTIFIER));
        } catch (SQLException e) {
            log.error("Unable to provide estimated cardinality, cannot acquire JDBC connection on: {}", member, e);
            return 0;
        }
    }

    private void commitPending(DSLContext context, KERL.AppendKERL kerl) {
        log.trace("Commit pending on: {}", member);
        context.select(PENDING_COORDINATES.ID, PENDING_EVENT.EVENT, PENDING_COORDINATES.ILK)
               .from(PENDING_EVENT)
               .join(PENDING_COORDINATES)
               .on(PENDING_COORDINATES.ID.eq(PENDING_EVENT.COORDINATES))
               .join(EVENT)
               .on(EVENT.DIGEST.eq(PENDING_COORDINATES.DIGEST))
               .orderBy(PENDING_COORDINATES.SEQUENCE_NUMBER)
               .fetchStream()
               .forEach(r -> {
                   KeyEvent event = ProtobufEventFactory.toKeyEvent(decompress(r.value2()), r.value3());
                   EventCoordinates coordinates = event.getCoordinates();
                   if (coordinates != null) {
                       context.select(PENDING_ATTACHMENT.ATTACHMENT)
                              .from(PENDING_ATTACHMENT)
                              .where(PENDING_ATTACHMENT.COORDINATES.eq(r.value1()))
                              .stream()
                              .forEach(bytes -> {
                                  try {
                                      Attachment attach = Attachment.parseFrom(b64(bytes.value1()));
                                      kerl.append(Collections.singletonList(new AttachmentEventImpl(
                                      AttachmentEvent.newBuilder()
                                                     .setCoordinates(coordinates.toEventCoords())
                                                     .setAttachment(attach)
                                                     .build())));
                                  } catch (InvalidProtocolBufferException e) {
                                      log.error("Cannot deserialize attachment on: {}", member, e);
                                  }
                              });
                       context.select(PENDING_VALIDATIONS.VALIDATIONS)
                              .from(PENDING_VALIDATIONS)
                              .where(PENDING_VALIDATIONS.COORDINATES.eq(r.value1()))
                              .stream()
                              .forEach(bytes -> {
                                  try {
                                      Validations attach = Validations.parseFrom(b64(bytes.value1()));
                                      kerl.appendValidations(coordinates, attach.getValidationsList()
                                                                                .stream()
                                                                                .collect(Collectors.toMap(
                                                                                v -> EventCoordinates.from(
                                                                                v.getValidator()),
                                                                                v -> JohnHancock.from(
                                                                                v.getSignature()))));
                                  } catch (InvalidProtocolBufferException e) {
                                      log.error("Cannot deserialize validation on: {}", member, e);
                                  }
                              });
                       kerl.append(event);
                   }
                   context.deleteFrom(PENDING_COORDINATES).where(PENDING_COORDINATES.ID.eq(r.value1())).execute();
               });
    }

    private KeyEventWithAttachmentAndValidations_ event(Digest d, DSLContext dsl, DigestKERL kerl) {
        final var builder = KeyEventWithAttachmentAndValidations_.newBuilder();
        KeyEvent event = kerl.getKeyEvent(d);
        if (event == null) {
            return null;
        }
        EventCoordinates coordinates = event.getCoordinates();
        com.hellblazer.delos.stereotomy.event.AttachmentEvent.Attachment a = kerl.getAttachment(coordinates);
        builder.setAttachment(a.toAttachemente());
        Map<EventCoordinates, JohnHancock> vs = kerl.getValidations(coordinates);
        var v = Validations.newBuilder()
                           .setCoordinates(coordinates.toEventCoords())
                           .addAllValidations(vs.entrySet()
                                                .stream()
                                                .map(e -> Validation_.newBuilder()
                                                                     .setValidator(e.getKey().toEventCoords())
                                                                     .setSignature(e.getValue().toSig())
                                                                     .build())
                                                .toList())
                           .build();
        builder.setValidations(v);
        builder.setEvent(event.toKeyEvent_());
        return builder.build();
    }

    private Stream<Digest> eventDigestsIn(CombinedIntervals intervals, DSLContext dsl) {
        return intervals.intervals().flatMap(interval -> eventDigestsIn(interval, dsl));
    }

    private Stream<Digest> eventDigestsIn(KeyInterval interval, DSLContext dsl) {
        var begin = b64(interval.getBegin().getBytes());
        var end = b64(interval.getEnd().getBytes());
        return Stream.concat(dsl.select(EVENT.DIGEST)
                                .from(EVENT)
                                .join(COORDINATES)
                                .on(EVENT.COORDINATES.eq(COORDINATES.ID))
                                .join(IDENTIFIER)
                                .on(COORDINATES.IDENTIFIER.eq(IDENTIFIER.ID))
                                .join(IDENTIFIER_LOCATION_HASH)
                                .on(IDENTIFIER.ID.eq(IDENTIFIER_LOCATION_HASH.IDENTIFIER))
                                .where(IDENTIFIER_LOCATION_HASH.DIGEST.ge(begin))
                                .and(IDENTIFIER_LOCATION_HASH.DIGEST.le(end))
                                .stream()
                                .map(r -> new Digest(algorithm, b64(r.value1())))
                                .filter(Objects::nonNull), dsl.select(PENDING_EVENT.DIGEST)
                                                              .from(PENDING_EVENT)
                                                              .join(PENDING_COORDINATES)
                                                              .on(PENDING_EVENT.COORDINATES.eq(PENDING_COORDINATES.ID))
                                                              .join(IDENTIFIER)
                                                              .on(PENDING_COORDINATES.IDENTIFIER.eq(IDENTIFIER.ID))
                                                              .join(IDENTIFIER_LOCATION_HASH)
                                                              .on(IDENTIFIER.ID.eq(IDENTIFIER_LOCATION_HASH.IDENTIFIER))
                                                              .where(IDENTIFIER_LOCATION_HASH.DIGEST.ge(begin))
                                                              .and(IDENTIFIER_LOCATION_HASH.DIGEST.le(end))
                                                              .stream()
                                                              .map(r -> {
                                                                  try {
                                                                      return Digest.from(
                                                                      Digeste.parseFrom(b64(r.value1())));
                                                                  } catch (InvalidProtocolBufferException e) {
                                                                      return null;
                                                                  }
                                                              })
                                                              .filter(Objects::nonNull));
    }
}
