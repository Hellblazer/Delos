/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.delphinius;

import com.hellblazer.delos.delphinius.Oracle.*;
import liquibase.Liquibase;
import liquibase.database.core.H2Database;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.h2.jdbc.JdbcConnection;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for content-change checks - Zanzibar-style coordination between
 * read and write authorization to prevent stale-read attacks.
 * Per Delos-868.13.
 *
 * @author hal.hildebrand
 */
public class ContentChangeCheckTest {

    private DirectOracle oracle;
    private Namespace docNs;
    private Namespace userNs;
    private AtomicLong blockHeight;

    /**
     * Creates a deterministic clock for testing.
     */
    private Supplier<ULong> createTestClock() {
        blockHeight = new AtomicLong(1);
        return () -> ULong.valueOf(blockHeight.getAndIncrement());
    }

    @BeforeEach
    void setUp() throws Exception {
        var url = String.format("jdbc:h2:mem:content-change-test-%s;DB_CLOSE_DELAY=3", new Random().nextLong());
        var connection = new JdbcConnection(url, new Properties(), "", "", false);

        var database = new H2Database();
        database.setConnection(new liquibase.database.jvm.JdbcConnection(connection));
        try (Liquibase liquibase = new Liquibase("/delphinius/initialize.xml", new ClassLoaderResourceAccessor(),
                                                 database)) {
            liquibase.update((String) null);
        }
        connection = new JdbcConnection(url, new Properties(), "", "", false);
        oracle = new DirectOracle(connection, createTestClock());

        docNs = Oracle.namespace("Document");
        userNs = Oracle.namespace("User");
    }

    /**
     * Basic case: user has both read and write access throughout.
     */
    @Test
    void authorizedWhenBothAccessesValid() throws Exception {
        var viewer = docNs.relation("viewer");
        var editor = docNs.relation("editor");
        var alice = userNs.subject("alice");
        var doc = docNs.object("doc1", viewer);

        // Grant read and write access
        var readAssertion = alice.assertion(doc);
        var writeDoc = docNs.object("doc1", editor);
        var writeAssertion = alice.assertion(writeDoc);

        var readTs = oracle.add(readAssertion).get().ts();
        oracle.add(writeAssertion).get();

        // Content change should be authorized
        var result = oracle.checkContentChange(readAssertion, writeAssertion, readTs);

        assertTrue(result.authorized(), "Should be authorized when both read and write access valid");
        assertEquals(ContentChangeDenialReason.NONE, result.reason());
        assertNotNull(result.currentTimestamp());
    }

    /**
     * Simplified API: same assertion for read and write.
     */
    @Test
    void simplifiedApiSameAssertion() throws Exception {
        var editor = docNs.relation("editor");
        var alice = userNs.subject("alice");
        var doc = docNs.object("doc1", editor);
        var assertion = alice.assertion(doc);

        var zookie = oracle.add(assertion).get().ts();

        // Use simplified single-assertion API
        var result = oracle.checkContentChange(assertion, zookie);

        assertTrue(result.authorized());
        assertEquals(ContentChangeDenialReason.NONE, result.reason());
    }

    /**
     * Content change allowed even if read access revoked AFTER zookie time.
     * This is correct: the user HAD valid read access when they read the content.
     * The key protection is checking write access at current time.
     */
    @Test
    void authorizedWhenReadAccessRevokedAfterZookie() throws Exception {
        var viewer = docNs.relation("viewer");
        var editor = docNs.relation("editor");
        var alice = userNs.subject("alice");
        var doc = docNs.object("doc1", viewer);
        var writeDoc = docNs.object("doc1", editor);

        // Grant both read and write access
        var readAssertion = alice.assertion(doc);
        var writeAssertion = alice.assertion(writeDoc);

        var readTs = oracle.add(readAssertion).get().ts();
        oracle.add(writeAssertion).get();

        // Revoke read access AFTER zookie time
        oracle.delete(readAssertion).get();

        // Content change should SUCCEED - user had valid read at zookie time
        // and still has write access
        var result = oracle.checkContentChange(readAssertion, writeAssertion, readTs);

        assertTrue(result.authorized(),
                   "Should authorize - had read access at zookie time, has write access now");
        assertEquals(ContentChangeDenialReason.NONE, result.reason());
    }

    /**
     * Real New Enemy Problem: user never had read access at zookie time.
     * This catches the case where someone forges or reuses an old zookie.
     */
    @Test
    void deniedWhenNoReadAccessAtZookieTime() throws Exception {
        var viewer = docNs.relation("viewer");
        var editor = docNs.relation("editor");
        var alice = userNs.subject("alice");
        var doc = docNs.object("doc1", viewer);
        var writeDoc = docNs.object("doc1", editor);

        var readAssertion = alice.assertion(doc);
        var writeAssertion = alice.assertion(writeDoc);

        // Use timestamp 0 - strictly before the first add() which uses timestamp 1
        var beforeAccess = ULong.valueOf(0);

        // Grant access after the fake zookie time
        oracle.add(readAssertion).get();
        oracle.add(writeAssertion).get();

        // Try to use a zookie from BEFORE access was granted
        var result = oracle.checkContentChange(readAssertion, writeAssertion, beforeAccess);

        assertFalse(result.authorized(),
                    "Should deny - no read access at zookie time (New Enemy Problem)");
        assertEquals(ContentChangeDenialReason.READ_ACCESS_REVOKED, result.reason());
    }

    /**
     * Write access denied (never had write permission).
     */
    @Test
    void deniedWhenWriteAccessMissing() throws Exception {
        var viewer = docNs.relation("viewer");
        var editor = docNs.relation("editor");
        var alice = userNs.subject("alice");
        var doc = docNs.object("doc1", viewer);
        var writeDoc = docNs.object("doc1", editor);

        // Grant only read access, not write
        var readAssertion = alice.assertion(doc);
        var writeAssertion = alice.assertion(writeDoc);

        var readTs = oracle.add(readAssertion).get().ts();
        // Note: writeAssertion never added

        var result = oracle.checkContentChange(readAssertion, writeAssertion, readTs);

        assertFalse(result.authorized(), "Should deny when write access missing");
        assertEquals(ContentChangeDenialReason.WRITE_ACCESS_DENIED, result.reason());
    }

    /**
     * Neither read nor write access.
     */
    @Test
    void deniedWhenBothAccessesMissing() throws Exception {
        var viewer = docNs.relation("viewer");
        var editor = docNs.relation("editor");
        var alice = userNs.subject("alice");
        var doc = docNs.object("doc1", viewer);
        var writeDoc = docNs.object("doc1", editor);

        var readAssertion = alice.assertion(doc);
        var writeAssertion = alice.assertion(writeDoc);

        // No access granted at all
        var fakeZookie = ULong.valueOf(1);

        var result = oracle.checkContentChange(readAssertion, writeAssertion, fakeZookie);

        assertFalse(result.authorized(), "Should deny when both accesses missing");
        assertEquals(ContentChangeDenialReason.BOTH_DENIED, result.reason());
    }

    /**
     * Invalid zookie (timestamp in future).
     */
    @Test
    void deniedWhenZookieInFuture() throws Exception {
        var editor = docNs.relation("editor");
        var alice = userNs.subject("alice");
        var doc = docNs.object("doc1", editor);
        var assertion = alice.assertion(doc);

        oracle.add(assertion).get();

        // Create a future zookie
        var futureZookie = ULong.valueOf(blockHeight.get() + 1000);

        var result = oracle.checkContentChange(assertion, futureZookie);

        assertFalse(result.authorized(), "Should deny when zookie is in future");
        assertEquals(ContentChangeDenialReason.INVALID_ZOOKIE, result.reason());
    }

    /**
     * Write access revoked after read (but read was valid at zookie time).
     * This should still work since user had valid read at zookie time
     * and we're checking write at current time.
     */
    @Test
    void deniedWhenWriteAccessRevoked() throws Exception {
        var viewer = docNs.relation("viewer");
        var editor = docNs.relation("editor");
        var alice = userNs.subject("alice");
        var doc = docNs.object("doc1", viewer);
        var writeDoc = docNs.object("doc1", editor);

        var readAssertion = alice.assertion(doc);
        var writeAssertion = alice.assertion(writeDoc);

        // Grant both
        var readTs = oracle.add(readAssertion).get().ts();
        oracle.add(writeAssertion).get();

        // Revoke write access
        oracle.delete(writeAssertion).get();

        var result = oracle.checkContentChange(readAssertion, writeAssertion, readTs);

        assertFalse(result.authorized(), "Should deny when write access revoked");
        assertEquals(ContentChangeDenialReason.WRITE_ACCESS_DENIED, result.reason());
    }

    /**
     * Full scenario: Document editing workflow.
     */
    @Test
    void documentEditingWorkflow() throws Exception {
        var viewer = docNs.relation("viewer");
        var editor = docNs.relation("editor");
        var alice = userNs.subject("alice");
        var bob = userNs.subject("bob");

        var docView = docNs.object("contract.pdf", viewer);
        var docEdit = docNs.object("contract.pdf", editor);

        var aliceView = alice.assertion(docView);
        var aliceEdit = alice.assertion(docEdit);
        var bobView = bob.assertion(docView);
        var bobEdit = bob.assertion(docEdit);

        // Alice is an editor, Bob is just a viewer
        oracle.add(aliceView).get();
        var aliceReadTs = oracle.add(aliceEdit).get().ts();
        var bobReadTs = oracle.add(bobView).get().ts();

        // Alice reads document (gets zookie aliceReadTs)
        // Bob reads document (gets zookie bobReadTs)

        // Alice tries to edit - should succeed
        var aliceResult = oracle.checkContentChange(aliceView, aliceEdit, aliceReadTs);
        assertTrue(aliceResult.authorized(), "Alice (editor) should be able to edit");

        // Bob tries to edit - should fail (no write permission)
        var bobResult = oracle.checkContentChange(bobView, bobEdit, bobReadTs);
        assertFalse(bobResult.authorized(), "Bob (viewer) should not be able to edit");
        assertEquals(ContentChangeDenialReason.WRITE_ACCESS_DENIED, bobResult.reason());
    }

    /**
     * Race condition scenario: access revoked between read and write attempt.
     * User had valid read at zookie time, but write access is now revoked.
     */
    @Test
    void raceConditionAccessRevoked() throws Exception {
        var editor = docNs.relation("editor");
        var alice = userNs.subject("alice");
        var doc = docNs.object("doc1", editor);
        var assertion = alice.assertion(doc);

        // Alice gets access and reads document
        var readTs = oracle.add(assertion).get().ts();

        // Simulate: Alice's access is revoked while she's editing
        oracle.delete(assertion).get();

        // Alice tries to save her changes
        var result = oracle.checkContentChange(assertion, readTs);

        assertFalse(result.authorized(),
                    "Should deny - access was revoked between read and write");
        // Result is WRITE_ACCESS_DENIED because:
        // - Read at zookie time: VALID (assertion existed at readTs)
        // - Write at current time: DENIED (assertion is now deleted)
        assertEquals(ContentChangeDenialReason.WRITE_ACCESS_DENIED, result.reason());
    }

    /**
     * Transitive access scenario: user gains access through group membership.
     */
    @Test
    void transitiveAccessThroughGroup() throws Exception {
        var viewer = docNs.relation("viewer");
        var member = userNs.relation("member");

        var editors = userNs.subject("editors", member);
        var alice = userNs.subject("alice", member);
        var doc = docNs.object("doc1", viewer);

        // Editors group has view access
        var editorsViewDoc = editors.assertion(doc);
        oracle.add(editorsViewDoc).get();

        // Alice is member of editors
        oracle.map(alice, editors).get();

        // Alice's assertion for the doc
        var aliceViewDoc = alice.assertion(doc);

        // Get zookie (this is the current time after setup)
        var zookie = oracle.add(userNs.subject("dummy")).get();  // Just to get current time

        // Alice should have access through group membership
        var result = oracle.checkContentChange(aliceViewDoc, aliceViewDoc, zookie);

        assertTrue(result.authorized(), "Alice should have access through group membership");
    }
}
