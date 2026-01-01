/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.delphinius;

import com.hellblazer.delos.delphinius.Oracle.Namespace;
import com.hellblazer.delos.delphinius.Oracle.Relation;
import com.hellblazer.delos.delphinius.Oracle.Subject;
import liquibase.Liquibase;
import liquibase.database.core.H2Database;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.h2.jdbc.JdbcConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that soft-deleted assertions are properly filtered out of read methods.
 * Verifies that read(), expand(), subjects(), and other query methods do not
 * return data associated with deleted assertions.
 *
 * @author hal.hildebrand
 */
public class SoftDeleteFilterTest {

    private Oracle oracle;
    private Namespace ns;
    private Subject alice;
    private Subject bob;
    private Oracle.Object doc1;
    private Oracle.Object doc2;
    private Relation viewer;
    private Relation editor;

    @BeforeEach
    public void setup() throws Exception {
        var url = String.format("jdbc:h2:mem:test_soft_delete-%s;DB_CLOSE_DELAY=3", new Random().nextLong());
        var connection = new JdbcConnection(url, new Properties(), "", "", false);

        var database = new H2Database();
        database.setConnection(new liquibase.database.jvm.JdbcConnection(connection));
        try (var liquibase = new Liquibase("/delphinius/initialize.xml", new ClassLoaderResourceAccessor(),
                                           database)) {
            liquibase.update((String) null);
        }
        connection = new JdbcConnection(url, new Properties(), "", "", false);
        oracle = new DirectOracle(connection);

        // Setup test data
        ns = Oracle.namespace("test");
        viewer = ns.relation("viewer");
        editor = ns.relation("editor");

        alice = ns.subject("alice");
        bob = ns.subject("bob");
        doc1 = ns.object("doc1", viewer);
        doc2 = ns.object("doc2", viewer);

        // Add entities
        oracle.add(ns).get();
        oracle.add(viewer).get();
        oracle.add(editor).get();
        oracle.add(alice).get();
        oracle.add(bob).get();
        oracle.add(doc1).get();
        oracle.add(doc2).get();
    }

    @Test
    public void testReadObjectsFiltersDeletedAssertions() throws Exception {
        // Given: Alice has access to doc1
        var assertion = alice.assertion(doc1);
        oracle.add(assertion).get();

        // When: We read objects for alice
        var objectsBefore = oracle.read(alice);
        assertEquals(1, objectsBefore.size(), "Alice should have access to doc1");
        assertTrue(objectsBefore.contains(doc1), "doc1 should be in results");

        // And: We delete the assertion
        oracle.delete(assertion).get();

        // Then: Read should not return doc1 (soft-deleted)
        var objectsAfter = oracle.read(alice);
        assertEquals(0, objectsAfter.size(), "Alice should have no objects after deletion");
        assertFalse(objectsAfter.contains(doc1), "doc1 should not be in results after deletion");
    }

    @Test
    public void testReadSubjectsFiltersDeletedAssertions() throws Exception {
        // Given: Alice and Bob have access to doc1
        oracle.add(alice.assertion(doc1)).get();
        oracle.add(bob.assertion(doc1)).get();

        // When: We read subjects for doc1
        var subjectsBefore = oracle.read(doc1);
        assertEquals(2, subjectsBefore.size(), "doc1 should have 2 subjects");
        assertTrue(subjectsBefore.contains(alice), "alice should be in results");
        assertTrue(subjectsBefore.contains(bob), "bob should be in results");

        // And: We delete Alice's assertion
        oracle.delete(alice.assertion(doc1)).get();

        // Then: Read should only return Bob
        var subjectsAfter = oracle.read(doc1);
        assertEquals(1, subjectsAfter.size(), "doc1 should have 1 subject after deletion");
        assertFalse(subjectsAfter.contains(alice), "alice should not be in results after deletion");
        assertTrue(subjectsAfter.contains(bob), "bob should still be in results");
    }

    @Test
    public void testReadWithPredicateFiltersDeletedAssertions() throws Exception {
        // Given: Alice has viewer access to doc1 and editor access to doc2
        var doc1Viewer = ns.object("doc1", viewer);
        var doc2Editor = ns.object("doc2", editor);
        oracle.add(doc1Viewer).get();
        oracle.add(doc2Editor).get();
        oracle.add(alice.assertion(doc1Viewer)).get();
        oracle.add(alice.assertion(doc2Editor)).get();

        // When: We read viewer objects for alice
        var viewerObjectsBefore = oracle.read(viewer, alice);
        assertEquals(1, viewerObjectsBefore.size(), "Alice should have 1 viewer object");

        // And: We delete the viewer assertion
        oracle.delete(alice.assertion(doc1Viewer)).get();

        // Then: Read should not return any viewer objects
        var viewerObjectsAfter = oracle.read(viewer, alice);
        assertEquals(0, viewerObjectsAfter.size(), "Alice should have no viewer objects after deletion");
    }

    @Test
    public void testExpandObjectsFiltersDeletedAssertions() throws Exception {
        // Given: Alice has access to doc1 and doc2
        oracle.add(alice.assertion(doc1)).get();
        oracle.add(alice.assertion(doc2)).get();

        // When: We expand objects for alice
        var objectsBefore = oracle.expand(alice);
        assertEquals(2, objectsBefore.size(), "Alice should have 2 objects");

        // And: We delete one assertion
        oracle.delete(alice.assertion(doc1)).get();

        // Then: Expand should only return doc2
        var objectsAfter = oracle.expand(alice);
        assertEquals(1, objectsAfter.size(), "Alice should have 1 object after deletion");
        assertFalse(objectsAfter.contains(doc1), "doc1 should not be in results after deletion");
        assertTrue(objectsAfter.contains(doc2), "doc2 should still be in results");
    }

    @Test
    public void testExpandSubjectsFiltersDeletedAssertions() throws Exception {
        // Given: Alice and Bob have access to doc1
        oracle.add(alice.assertion(doc1)).get();
        oracle.add(bob.assertion(doc1)).get();

        // When: We expand subjects for doc1
        var subjectsBefore = oracle.expand(doc1);
        assertEquals(2, subjectsBefore.size(), "doc1 should have 2 subjects");

        // And: We delete Alice's assertion
        oracle.delete(alice.assertion(doc1)).get();

        // Then: Expand should only return Bob
        var subjectsAfter = oracle.expand(doc1);
        assertEquals(1, subjectsAfter.size(), "doc1 should have 1 subject after deletion");
        assertFalse(subjectsAfter.contains(alice), "alice should not be in results after deletion");
        assertTrue(subjectsAfter.contains(bob), "bob should still be in results");
    }

    @Test
    public void testExpandWithPredicateFiltersDeletedAssertions() throws Exception {
        // Given: Alice has viewer access to doc1
        var aliceViewer = ns.subject("alice", viewer);
        oracle.add(aliceViewer).get();
        oracle.add(aliceViewer.assertion(doc1)).get();

        // When: We expand with viewer predicate
        var subjectsBefore = oracle.expand(viewer, doc1);
        assertEquals(1, subjectsBefore.size(), "doc1 should have 1 viewer subject");

        // And: We delete the assertion
        oracle.delete(aliceViewer.assertion(doc1)).get();

        // Then: Expand should return no subjects
        var subjectsAfter = oracle.expand(viewer, doc1);
        assertEquals(0, subjectsAfter.size(), "doc1 should have no viewer subjects after deletion");
    }

    @Test
    public void testSubjectsStreamFiltersDeletedAssertions() throws Exception {
        // Given: Alice and Bob have access to doc1
        oracle.add(alice.assertion(doc1)).get();
        oracle.add(bob.assertion(doc1)).get();

        // When: We get subjects stream for doc1
        var subjectsBefore = oracle.subjects(null, doc1).toList();
        assertEquals(2, subjectsBefore.size(), "doc1 should have 2 subjects");

        // And: We delete Alice's assertion
        oracle.delete(alice.assertion(doc1)).get();

        // Then: Subjects stream should only return Bob
        var subjectsAfter = oracle.subjects(null, doc1).toList();
        assertEquals(1, subjectsAfter.size(), "doc1 should have 1 subject after deletion");
        assertFalse(subjectsAfter.contains(alice), "alice should not be in results after deletion");
        assertTrue(subjectsAfter.contains(bob), "bob should still be in results");
    }

    @Test
    public void testTransitiveAccessWithDeletedAssertions() throws Exception {
        // Given: Group hierarchy - alice is member of editors group
        var editors = ns.subject("editors", viewer);
        oracle.add(editors).get();
        oracle.map(alice, editors).get();

        // And: editors group has access to doc1
        oracle.add(editors.assertion(doc1)).get();

        // When: We expand subjects for doc1 (should include alice via group)
        var subjectsBefore = oracle.expand(doc1);
        assertTrue(subjectsBefore.contains(alice), "alice should have access via editors group");
        assertTrue(subjectsBefore.contains(editors), "editors group should have access");

        // And: We delete the group's assertion
        oracle.delete(editors.assertion(doc1)).get();

        // Then: Neither alice nor editors should have access
        var subjectsAfter = oracle.expand(doc1);
        assertFalse(subjectsAfter.contains(alice), "alice should not have access after group deletion");
        assertFalse(subjectsAfter.contains(editors), "editors should not have access after deletion");
    }

    @Test
    public void testReAddingDeletedAssertionRestoresAccess() throws Exception {
        // Given: Alice has access to doc1
        var assertion = alice.assertion(doc1);
        oracle.add(assertion).get();

        // When: We delete the assertion
        oracle.delete(assertion).get();
        var objectsAfterDelete = oracle.read(alice);
        assertEquals(0, objectsAfterDelete.size(), "Alice should have no access after deletion");

        // And: We re-add the same assertion
        oracle.add(assertion).get();

        // Then: Alice should have access again
        var objectsAfterReAdd = oracle.read(alice);
        assertEquals(1, objectsAfterReAdd.size(), "Alice should have access after re-adding");
        assertTrue(objectsAfterReAdd.contains(doc1), "doc1 should be accessible again");

        // And: Check should succeed
        assertTrue(oracle.check(assertion), "check should return true after re-adding");
    }

    @Test
    public void testMultipleObjectsWithMixedDeletedAssertions() throws Exception {
        // Given: Alice has access to doc1 and doc2, Bob has access to doc1
        oracle.add(alice.assertion(doc1)).get();
        oracle.add(alice.assertion(doc2)).get();
        oracle.add(bob.assertion(doc1)).get();

        // When: We delete Alice's access to doc1
        oracle.delete(alice.assertion(doc1)).get();

        // Then: Alice should only see doc2
        var aliceObjects = oracle.read(alice);
        assertEquals(1, aliceObjects.size(), "Alice should have access to 1 object");
        assertTrue(aliceObjects.contains(doc2), "Alice should still have access to doc2");
        assertFalse(aliceObjects.contains(doc1), "Alice should not have access to doc1");

        // And: Bob should still see doc1
        var bobObjects = oracle.read(bob);
        assertEquals(1, bobObjects.size(), "Bob should have access to 1 object");
        assertTrue(bobObjects.contains(doc1), "Bob should still have access to doc1");

        // And: doc1 should only show Bob as subject
        var doc1Subjects = oracle.read(doc1);
        assertEquals(1, doc1Subjects.size(), "doc1 should have 1 subject");
        assertTrue(doc1Subjects.contains(bob), "Bob should be a subject of doc1");
        assertFalse(doc1Subjects.contains(alice), "Alice should not be a subject of doc1");
    }
}
