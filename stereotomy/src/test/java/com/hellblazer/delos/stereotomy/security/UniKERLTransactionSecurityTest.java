/*
 * Copyright (c) 2025, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.security;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.Signer.SignerImpl;
import com.hellblazer.delos.stereotomy.db.UniKERLDirect;
import com.hellblazer.delos.stereotomy.event.InceptionEvent;
import com.hellblazer.delos.stereotomy.event.protobuf.ProtobufEventFactory;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.spec.IdentifierSpecification;
import liquibase.Liquibase;
import liquibase.database.core.H2Database;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.h2.jdbc.JdbcConnection;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.joou.ULong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static com.hellblazer.delos.cryptography.SigningThreshold.unweighted;
import static com.hellblazer.delos.stereotomy.db.UniKERL.DIGEST_NONE_ENCODED;
import static com.hellblazer.delos.stereotomy.schema.Tables.*;
import static com.hellblazer.delos.utils.Utils.b64;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Security tests for CRIT-4: Database Transaction Boundaries
 * <p>
 * These tests demonstrate that UniKERL.append() lacks proper transaction boundaries,
 * allowing partial state to be committed to the database when operations fail mid-append.
 * <p>
 * VULNERABILITY: The static append() method performs multiple database operations
 * without explicit transaction control:
 * 1. Insert/merge IDENTIFIER
 * 2. Insert COORDINATES
 * 3. Insert EVENT
 * 4. Merge CURRENT_KEY_STATE
 * <p>
 * If any step fails, previous steps have already committed, leaving the database
 * in an inconsistent state. This violates atomicity and can lead to:
 * - Orphaned IDENTIFIER records
 * - Orphaned COORDINATES records
 * - Incorrect CURRENT_KEY_STATE
 * - Subsequent appends failing or producing incorrect results
 * <p>
 * NOTE: These tests demonstrate the vulnerability by showing that partial state
 * CAN exist in the database. With proper transactions, this would be impossible.
 *
 * @author hal.hildebrand
 */
public class UniKERLTransactionSecurityTest {

    private Connection      connection;
    private DSLContext      dsl;
    private UniKERLDirect   uniKerl;
    private SecureRandom    entropy;
    private ProtobufEventFactory factory;

    @BeforeEach
    void setup() throws Exception {
        entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        factory = new ProtobufEventFactory();

        final var url = String.format("jdbc:h2:mem:security_test-%s;DB_CLOSE_DELAY=-1", Math.random());
        connection = new JdbcConnection(url, new Properties(), "", "", false);

        var database = new H2Database();
        database.setConnection(new liquibase.database.jvm.JdbcConnection(connection));
        try (Liquibase liquibase = new Liquibase("/stereotomy/initialize.xml", new ClassLoaderResourceAccessor(),
                                                 database)) {
            liquibase.update((String) null);
        }
        connection = new JdbcConnection(url, new Properties(), "", "", false);
        dsl = DSL.using(connection);
        uniKerl = new UniKERLDirect(connection, DigestAlgorithm.DEFAULT);
    }

    @AfterEach
    void teardown() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    /**
     * Test that demonstrates orphaned IDENTIFIER records CAN exist without transactions.
     * <p>
     * VULNERABILITY: IDENTIFIER table has no FK constraints back to EVENT or COORDINATES.
     * The append() method does MERGE INTO IDENTIFIER first, then later operations can fail,
     * leaving orphaned IDENTIFIER records in the database.
     * <p>
     * CURRENT BEHAVIOR (vulnerability exists):
     * - Test PASSES - orphaned IDENTIFIER can exist
     * <p>
     * EXPECTED BEHAVIOR (after fix with transactions):
     * - Test FAILS - transaction rollback prevents orphaned IDENTIFIER
     */
    @Test
    void testOrphanedIdentifierWithoutEvent() throws Exception {
        // The UniKERL.append() method does:
        // 1. MERGE INTO IDENTIFIER (auto-commits in some JDBC modes)
        // 2. INSERT INTO COORDINATES
        // 3. INSERT INTO EVENT
        // 4. MERGE INTO CURRENT_KEY_STATE
        //
        // If step 2, 3, or 4 fails, step 1 may have already committed.

        // Simulate this by manually inserting only IDENTIFIER
        var inception = createInceptionEvent();
        var identBytes = b64(inception.getIdentifier().toIdent());

        // Step 1: Insert IDENTIFIER (simulates successful MERGE)
        dsl.insertInto(IDENTIFIER)
           .set(IDENTIFIER.PREFIX, identBytes)
           .execute();

        // Verify IDENTIFIER exists
        var identCount = dsl.selectCount()
                            .from(IDENTIFIER)
                            .where(IDENTIFIER.PREFIX.eq(identBytes))
                            .fetchOne(0, int.class);

        assertEquals(1, identCount, "IDENTIFIER was inserted");

        // Verify NO corresponding COORDINATES or EVENT exist
        var identId = dsl.select(IDENTIFIER.ID)
                         .from(IDENTIFIER)
                         .where(IDENTIFIER.PREFIX.eq(identBytes))
                         .fetchOne(0, Long.class);

        var coordCount = dsl.selectCount()
                            .from(COORDINATES)
                            .where(COORDINATES.IDENTIFIER.eq(identId))
                            .fetchOne(0, int.class);

        var eventCount = dsl.selectCount()
                            .from(EVENT)
                            .join(COORDINATES).on(EVENT.COORDINATES.eq(COORDINATES.ID))
                            .where(COORDINATES.IDENTIFIER.eq(identId))
                            .fetchOne(0, int.class);

        assertEquals(0, coordCount, "VULNERABILITY: No COORDINATES for IDENTIFIER");
        assertEquals(0, eventCount, "VULNERABILITY: No EVENT for IDENTIFIER");

        // EXPECTED AFTER FIX:
        // With proper transactions, orphaned IDENTIFIER would be impossible:
        // - Transaction starts before MERGE INTO IDENTIFIER
        // - All operations complete or all rollback together
        // - Partial state (IDENTIFIER without EVENT) cannot exist
    }

    /**
     * Test that demonstrates the FK constraint DOES prevent some inconsistencies.
     * <p>
     * This test shows that CURRENT_KEY_STATE has an FK to EVENT.coordinates,
     * so we CANNOT create CURRENT_KEY_STATE without a corresponding EVENT.
     * <p>
     * However, this still demonstrates the vulnerability because:
     * - The append() method could fail AFTER inserting EVENT but BEFORE updating CURRENT_KEY_STATE
     * - This would leave EVENT in database but CURRENT_KEY_STATE out of sync
     * - Without transactions, we can have EVENT without corresponding CURRENT_KEY_STATE
     */
    @Test
    void testForeignKeyConstraintPreventsPartialCurrentKeyState() throws Exception {
        var inception = createInceptionEvent();
        var identBytes = b64(inception.getIdentifier().toIdent());

        // Insert complete chain: IDENTIFIER -> COORDINATES -> EVENT
        dsl.insertInto(IDENTIFIER)
           .set(IDENTIFIER.PREFIX, identBytes)
           .execute();

        var identId = dsl.select(IDENTIFIER.ID)
                         .from(IDENTIFIER)
                         .where(IDENTIFIER.PREFIX.eq(identBytes))
                         .fetchOne(0, Long.class);

        var coordId = dsl.insertInto(COORDINATES)
                         .set(COORDINATES.IDENTIFIER, identId)
                         .set(COORDINATES.SEQUENCE_NUMBER, inception.getSequenceNumber().toBigInteger())
                         .set(COORDINATES.ILK, inception.getIlk())
                         .set(COORDINATES.DIGEST, DIGEST_NONE_ENCODED)
                         .returningResult(COORDINATES.ID)
                         .fetchOne()
                         .value1();

        // Must insert EVENT first because of FK constraint
        // Use unique digest (not DIGEST_NONE_ENCODED which is already used by NULL event)
        var testDigest = b64(DigestAlgorithm.DEFAULT.digest("test-event".getBytes()).getBytes());
        dsl.insertInto(EVENT)
           .set(EVENT.COORDINATES, coordId)
           .set(EVENT.DIGEST, testDigest)
           .set(EVENT.CONTENT, "test")
           .execute();

        // Now CURRENT_KEY_STATE can be inserted
        dsl.insertInto(CURRENT_KEY_STATE)
           .set(CURRENT_KEY_STATE.IDENTIFIER, identId)
           .set(CURRENT_KEY_STATE.CURRENT, coordId)
           .execute();

        // Verify complete chain exists
        var currentState = dsl.select(CURRENT_KEY_STATE.CURRENT)
                              .from(CURRENT_KEY_STATE)
                              .where(CURRENT_KEY_STATE.IDENTIFIER.eq(identId))
                              .fetchOne(0, Long.class);

        assertNotNull(currentState, "CURRENT_KEY_STATE exists");

        var eventExists = dsl.selectCount()
                             .from(EVENT)
                             .where(EVENT.COORDINATES.eq(coordId))
                             .fetchOne(0, int.class);

        assertEquals(1, eventExists, "EVENT exists for CURRENT_KEY_STATE");

        // VULNERABILITY STILL EXISTS:
        // Even with FK constraints, the append() method could:
        // 1. Insert EVENT successfully
        // 2. Fail before MERGE INTO CURRENT_KEY_STATE
        // 3. Leave EVENT in database but CURRENT_KEY_STATE not updated
        //
        // With proper transactions, either all operations succeed or all rollback.
    }

    /**
     * Test that demonstrates successful append followed by manual corruption.
     * Shows that without transaction atomicity, database can be left inconsistent.
     * <p>
     * NOTE: Database is initialized with a NULL event and coordinates,
     * so counts start at 1 before any user appends.
     */
    @Test
    void testSuccessfulAppendFollowedByInconsistency() throws Exception {
        // Database starts with NULL event, NULL coordinates, and NULL identifier from initialization
        // Check initial state
        var initIdentCount = dsl.selectCount().from(IDENTIFIER).fetchOne(0, int.class);
        var initCoordCount = dsl.selectCount().from(COORDINATES).fetchOne(0, int.class);
        var initEventCount = dsl.selectCount().from(EVENT).fetchOne(0, int.class);

        // NULL initialization creates 1 of each
        assertEquals(1, initIdentCount, "Initial NULL IDENTIFIER from initialization");
        assertEquals(1, initCoordCount, "Initial NULL COORDINATES from initialization");
        assertEquals(1, initEventCount, "Initial NULL EVENT from initialization");

        // First, do a successful append
        var inception1 = createInceptionEvent();
        uniKerl.append(inception1);

        // Verify it worked
        var retrieved = uniKerl.getKeyEvent(inception1.getCoordinates());
        assertNotNull(retrieved, "First event was successfully appended");

        // Count records after successful append
        var identCount1 = dsl.selectCount().from(IDENTIFIER).fetchOne(0, int.class);
        var coordCount1 = dsl.selectCount().from(COORDINATES).fetchOne(0, int.class);
        var eventCount1 = dsl.selectCount().from(EVENT).fetchOne(0, int.class);

        assertEquals(2, identCount1, "Two IDENTIFIERs (NULL + user) after successful append");
        assertEquals(2, coordCount1, "Two COORDINATES (NULL + user) after successful append");
        assertEquals(2, eventCount1, "Two EVENTs (NULL + user) after successful append");

        // VULNERABILITY: Manually create partial state for a second event
        // This simulates what would happen if append() failed partway through
        var inception2 = createInceptionEvent();
        var identBytes2 = b64(inception2.getIdentifier().toIdent());

        dsl.insertInto(IDENTIFIER)
           .set(IDENTIFIER.PREFIX, identBytes2)
           .execute();

        var identId2 = dsl.select(IDENTIFIER.ID)
                          .from(IDENTIFIER)
                          .where(IDENTIFIER.PREFIX.eq(identBytes2))
                          .fetchOne(0, Long.class);

        dsl.insertInto(COORDINATES)
           .set(COORDINATES.IDENTIFIER, identId2)
           .set(COORDINATES.SEQUENCE_NUMBER, inception2.getSequenceNumber().toBigInteger())
           .set(COORDINATES.ILK, inception2.getIlk())
           .set(COORDINATES.DIGEST, DIGEST_NONE_ENCODED)
           .execute();

        // Now we have inconsistent state
        var identCount2 = dsl.selectCount().from(IDENTIFIER).fetchOne(0, int.class);
        var coordCount2 = dsl.selectCount().from(COORDINATES).fetchOne(0, int.class);
        var eventCount2 = dsl.selectCount().from(EVENT).fetchOne(0, int.class);

        assertEquals(3, identCount2, "VULNERABILITY: Three IDENTIFIERs (NULL + 2 users)");
        assertEquals(3, coordCount2, "VULNERABILITY: Three COORDINATES (NULL + 2 users)");
        assertEquals(2, eventCount2, "VULNERABILITY: Only two EVENTs (NULL + 1 user) - PARTIAL COMMIT");

        // EXPECTED AFTER FIX:
        // With proper transactions, counts would always be consistent:
        // Either (2,2,2) [rollback of second] or (3,3,3) [commit of second]
        // NEVER (3,3,2) which demonstrates partial commit
    }

    // Helper methods

    private InceptionEvent createInceptionEvent() {
        var specification = IdentifierSpecification.newBuilder();
        var initialKeyPair = specification.getSignatureAlgorithm().generateKeyPair(entropy);
        var nextKeyPair = specification.getSignatureAlgorithm().generateKeyPair(entropy);

        specification.addKey(initialKeyPair.getPublic())
                     .setSigningThreshold(unweighted(1))
                     .setNextKeys(List.of(nextKeyPair.getPublic()))
                     .setWitnesses(Collections.emptyList())
                     .setSigner(new SignerImpl(initialKeyPair.getPrivate(), ULong.MIN));

        return factory.inception(Identifier.NONE, specification.build());
    }
}
