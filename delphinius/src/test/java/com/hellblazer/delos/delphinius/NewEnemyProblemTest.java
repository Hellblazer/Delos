/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.delphinius;

import com.hellblazer.delos.delphinius.Oracle.Assertion;
import com.hellblazer.delos.delphinius.Oracle.Namespace;
import com.hellblazer.delos.delphinius.Oracle.Object;
import com.hellblazer.delos.delphinius.Oracle.Relation;
import com.hellblazer.delos.delphinius.Oracle.Subject;
import liquibase.Liquibase;
import liquibase.database.core.H2Database;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.h2.jdbc.JdbcConnection;
import org.joou.ULong;
import org.junit.jupiter.api.Test;

import java.util.Properties;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for the "New Enemy Problem" from the Zanzibar paper.
 *
 * The vulnerability: When checking permissions with a stale timestamp,
 * the system should NOT grant access to resources created AFTER that timestamp.
 * This prevents revoked users from accessing new content by using old tokens.
 *
 * @author hal.hildebrand
 */
public class NewEnemyProblemTest {

    /**
     * Creates a deterministic clock (simulating block height) for testing.
     * Each call to get() returns a unique, monotonically increasing value.
     */
    private Supplier<ULong> createTestClock() {
        var blockHeight = new AtomicLong(1);
        return () -> ULong.valueOf(blockHeight.getAndIncrement());
    }

    @Test
    public void shouldNotAccessNewContentWithOldTimestamp() throws Exception {
        final var url = String.format("jdbc:h2:mem:test_new_enemy-%s;DB_CLOSE_DELAY=3",
                                      new Random().nextLong());
        var connection = new JdbcConnection(url, new Properties(), "", "", false);

        var database = new H2Database();
        database.setConnection(new liquibase.database.jvm.JdbcConnection(connection));
        try (Liquibase liquibase = new Liquibase("/delphinius/initialize.xml", new ClassLoaderResourceAccessor(),
                                                 database)) {
            liquibase.update((String) null);
        }
        connection = new JdbcConnection(url, new Properties(), "", "", false);
        // Use deterministic block height clock instead of wall clock
        Oracle oracle = new DirectOracle(connection, createTestClock());

        // Setup namespaces and relations
        var docNs = new Namespace("Document");
        var userNs = new Namespace("User");
        var viewRelation = docNs.relation("view");
        var memberRelation = userNs.relation("member");

        // Create user and group
        var user = userNs.subject("alice", memberRelation);
        var oldDoc = docNs.object("doc-1", viewRelation);

        // T1: Grant user access to existing document
        Assertion userCanViewOldDoc = user.assertion(oldDoc);
        var t1 = oracle.add(userCanViewOldDoc).get().ts();

        // Verify user can access old document at T1
        assertTrue(oracle.check(userCanViewOldDoc, t1),
                   "User should have access to doc-1 at time T1");

        // T2: Revoke user's access
        var t2 = oracle.delete(userCanViewOldDoc).get();

        // Verify revocation worked at current time
        assertFalse(oracle.check(userCanViewOldDoc),
                    "User should not have access to doc-1 after revocation");

        // T3: Create NEW document after revocation
        var newDoc = docNs.object("doc-new", viewRelation);
        var userCanViewNewDoc = user.assertion(newDoc);
        var t3 = oracle.add(userCanViewNewDoc).get().ts();

        // The vulnerability test: Query with OLD timestamp T1
        // At time T1, doc-new did NOT exist, so it should NOT be accessible
        // This is the "New Enemy Problem" - revoked user should not see new content
        assertFalse(oracle.check(userCanViewNewDoc, t1),
                    "SECURITY BUG: User should NOT have access to doc-new at old timestamp T1 " +
                    "(doc-new was created at T3 which is after T1)");

        // Verify that at T3, the user DOES have access (sanity check)
        assertTrue(oracle.check(userCanViewNewDoc, t3),
                   "User should have access to doc-new at time T3");

        // Additional check: User should not have access to old doc at T2 or later
        assertFalse(oracle.check(userCanViewOldDoc, t2),
                    "User should not have access to doc-1 at time T2 (after revocation)");
    }

    @Test
    public void shouldRespectDeletionTimestamps() throws Exception {
        final var url = String.format("jdbc:h2:mem:test_deletion_time-%s;DB_CLOSE_DELAY=3",
                                      new Random().nextLong());
        var connection = new JdbcConnection(url, new Properties(), "", "", false);

        var database = new H2Database();
        database.setConnection(new liquibase.database.jvm.JdbcConnection(connection));
        try (Liquibase liquibase = new Liquibase("/delphinius/initialize.xml", new ClassLoaderResourceAccessor(),
                                                 database)) {
            liquibase.update((String) null);
        }
        connection = new JdbcConnection(url, new Properties(), "", "", false);
        // Use deterministic block height clock instead of wall clock
        Oracle oracle = new DirectOracle(connection, createTestClock());

        var ns = new Namespace("Test");
        var rel = ns.relation("access");
        var user = ns.subject("bob", rel);
        var resource = ns.object("resource-1", rel);

        // Test 1: Grant and check at grant time
        var assertion = user.assertion(resource);
        var t1 = oracle.add(assertion).get().ts();
        assertTrue(oracle.check(assertion, t1),
                   "Should have access at grant time");

        // Test 2: After deletion, should not have access at current time
        var t2 = oracle.delete(assertion).get();
        assertFalse(oracle.check(assertion),
                    "Should NOT have access after deletion (current time)");

        // Test 3: Before deletion time, should still have access (original grant visible)
        assertTrue(oracle.check(assertion, t1),
                   "Should still have access when checking at T1 (before deletion)");

        // Test 4: At or after deletion time, should not have access
        assertFalse(oracle.check(assertion, t2),
                    "Should NOT have access at deletion time");

        // Note: Single-row temporal model has limitations with re-grant cycles.
        // After re-grant, the assertion becomes visible from original created_at onward,
        // which may not match full temporal semantics. The critical security fix
        // (New Enemy Problem) is tested in shouldNotAccessNewContentWithOldTimestamp.
    }
}
