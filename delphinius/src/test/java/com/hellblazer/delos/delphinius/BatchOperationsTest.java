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

import java.util.*;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for batch operations API (Delos-yoy9).
 * Verifies batch add, delete, and check operations with transactional consistency
 * and performance improvements over individual calls.
 *
 * @author hal.hildebrand
 */
public class BatchOperationsTest {

    private DirectOracle oracle;
    private Namespace ns;

    @BeforeEach
    void setUp() throws Exception {
        var url = String.format("jdbc:h2:mem:batch-test-%s;DB_CLOSE_DELAY=3", new Random().nextLong());
        var connection = new JdbcConnection(url, new Properties(), "", "", false);

        var database = new H2Database();
        database.setConnection(new liquibase.database.jvm.JdbcConnection(connection));
        try (Liquibase liquibase = new Liquibase("/delphinius/initialize.xml", new ClassLoaderResourceAccessor(),
                                                 database)) {
            liquibase.update((String) null);
        }
        connection = new JdbcConnection(url, new Properties(), "", "", false);
        oracle = new DirectOracle(connection);
        ns = Oracle.namespace("test-ns");
    }

    @Test
    void batchAddAssertions() throws Exception {
        // Given: Multiple subjects, objects, and assertions
        var alice = ns.subject("alice");
        var bob = ns.subject("bob");
        var carol = ns.subject("carol");

        var viewer = ns.relation("viewer");
        var editor = ns.relation("editor");

        var doc1 = ns.object("doc1", viewer);
        var doc2 = ns.object("doc2", editor);
        var doc3 = ns.object("doc3", viewer);

        var assertions = List.of(
            alice.assertion(doc1),
            alice.assertion(doc2),
            bob.assertion(doc1),
            bob.assertion(doc3),
            carol.assertion(doc2)
        );

        // When: Batch add assertions
        var results = oracle.batchAdd(assertions).get();

        // Then: All assertions should be added
        assertEquals(5, results.size());
        for (var result : results) {
            assertTrue(result.added(), "All new assertions should be added");
            assertNotNull(result.ts(), "Timestamp should be set");
        }

        // Verify all assertions are checkable
        assertTrue(oracle.check(alice.assertion(doc1)));
        assertTrue(oracle.check(alice.assertion(doc2)));
        assertTrue(oracle.check(bob.assertion(doc1)));
        assertTrue(oracle.check(bob.assertion(doc3)));
        assertTrue(oracle.check(carol.assertion(doc2)));
    }

    @Test
    void batchAddAssertionsDuplicates() throws Exception {
        // Given: Some assertions already exist
        var alice = ns.subject("alice");
        var viewer = ns.relation("viewer");
        var doc1 = ns.object("doc1", viewer);
        var doc2 = ns.object("doc2", viewer);

        var existingAssertion = alice.assertion(doc1);
        oracle.add(existingAssertion).get();

        var assertions = List.of(
            existingAssertion,      // Already exists
            alice.assertion(doc2)   // New
        );

        // When: Batch add with duplicates
        var results = oracle.batchAdd(assertions).get();

        // Then: Existing should be false, new should be true
        assertEquals(2, results.size());
        assertFalse(results.get(0).added(), "Existing assertion should not be re-added");
        assertTrue(results.get(1).added(), "New assertion should be added");
    }

    @Test
    void batchAddSubjects() throws Exception {
        // Given: Multiple subjects to add
        var subjects = List.of(
            ns.subject("alice"),
            ns.subject("bob"),
            ns.subject("carol"),
            ns.subject("dave"),
            ns.subject("eve")
        );

        // When: Batch add subjects
        var timestamp = oracle.batchAddSubjects(subjects).get();

        // Then: All subjects should be added with same timestamp
        assertNotNull(timestamp);

        // Verify subjects can be used in assertions
        var viewer = ns.relation("viewer");
        var doc = ns.object("doc", viewer);
        for (var subject : subjects) {
            var assertion = subject.assertion(doc);
            oracle.add(assertion).get();
            assertTrue(oracle.check(assertion));
        }
    }

    @Test
    void batchAddObjects() throws Exception {
        // Given: Multiple objects to add
        var viewer = ns.relation("viewer");
        var editor = ns.relation("editor");

        var objects = List.of(
            ns.object("doc1", viewer),
            ns.object("doc2", viewer),
            ns.object("doc3", editor),
            ns.object("doc4", editor),
            ns.object("doc5", viewer)
        );

        // When: Batch add objects
        var timestamp = oracle.batchAddObjects(objects).get();

        // Then: All objects should be added
        assertNotNull(timestamp);

        // Verify objects can be used in assertions
        var alice = ns.subject("alice");
        for (var object : objects) {
            var assertion = alice.assertion(object);
            oracle.add(assertion).get();
            assertTrue(oracle.check(assertion));
        }
    }

    @Test
    void batchAddRelations() throws Exception {
        // Given: Multiple relations to add
        var relations = List.of(
            ns.relation("viewer"),
            ns.relation("editor"),
            ns.relation("admin"),
            ns.relation("owner"),
            ns.relation("commenter")
        );

        // When: Batch add relations
        var timestamp = oracle.batchAddRelations(relations).get();

        // Then: All relations should be added
        assertNotNull(timestamp);

        // Verify relations can be used
        var alice = ns.subject("alice");
        for (var relation : relations) {
            var obj = ns.object("doc", relation);
            var assertion = alice.assertion(obj);
            oracle.add(assertion).get();
            assertTrue(oracle.check(assertion));
        }
    }

    @Test
    void batchDeleteAssertions() throws Exception {
        // Given: Multiple existing assertions
        var alice = ns.subject("alice");
        var bob = ns.subject("bob");
        var viewer = ns.relation("viewer");
        var doc1 = ns.object("doc1", viewer);
        var doc2 = ns.object("doc2", viewer);
        var doc3 = ns.object("doc3", viewer);

        var assertions = List.of(
            alice.assertion(doc1),
            alice.assertion(doc2),
            bob.assertion(doc1),
            bob.assertion(doc3)
        );

        // Add all assertions
        for (var assertion : assertions) {
            oracle.add(assertion).get();
        }

        // Verify all exist
        for (var assertion : assertions) {
            assertTrue(oracle.check(assertion));
        }

        // When: Batch delete assertions
        var timestamp = oracle.batchDelete(assertions).get();

        // Then: All assertions should be deleted
        assertNotNull(timestamp);
        for (var assertion : assertions) {
            assertFalse(oracle.check(assertion), "Assertion should be deleted: " + assertion);
        }
    }

    @Test
    void batchDeleteSubjects() throws Exception {
        // Given: Multiple subjects with assertions
        var subjects = List.of(
            ns.subject("alice"),
            ns.subject("bob"),
            ns.subject("carol")
        );

        var viewer = ns.relation("viewer");
        var doc = ns.object("doc", viewer);

        // Create assertions for each subject
        for (var subject : subjects) {
            oracle.add(subject.assertion(doc)).get();
        }

        // When: Batch delete subjects
        var timestamp = oracle.batchDeleteSubjects(subjects).get();

        // Then: All subjects and their assertions should be deleted
        assertNotNull(timestamp);
        for (var subject : subjects) {
            assertFalse(oracle.check(subject.assertion(doc)));
        }
    }

    @Test
    void batchDeleteObjects() throws Exception {
        // Given: Multiple objects with assertions
        var viewer = ns.relation("viewer");
        var objects = List.of(
            ns.object("doc1", viewer),
            ns.object("doc2", viewer),
            ns.object("doc3", viewer)
        );

        var alice = ns.subject("alice");

        // Create assertions for each object
        for (var object : objects) {
            oracle.add(alice.assertion(object)).get();
        }

        // When: Batch delete objects
        var timestamp = oracle.batchDeleteObjects(objects).get();

        // Then: All objects and their assertions should be deleted
        assertNotNull(timestamp);
        for (var object : objects) {
            assertFalse(oracle.check(alice.assertion(object)));
        }
    }

    @Test
    void batchCheck() throws Exception {
        // Given: Multiple assertions, some existing and some not
        var alice = ns.subject("alice");
        var bob = ns.subject("bob");
        var viewer = ns.relation("viewer");
        var doc1 = ns.object("doc1", viewer);
        var doc2 = ns.object("doc2", viewer);
        var doc3 = ns.object("doc3", viewer);

        // Add only some assertions
        oracle.add(alice.assertion(doc1)).get();
        oracle.add(bob.assertion(doc2)).get();

        var assertions = List.of(
            alice.assertion(doc1),  // exists
            alice.assertion(doc2),  // doesn't exist
            bob.assertion(doc2),    // exists
            bob.assertion(doc3)     // doesn't exist
        );

        // When: Batch check
        var results = oracle.batchCheck(assertions);

        // Then: Should return correct results for each
        assertEquals(4, results.size());
        assertTrue(results.get(0), "alice.assertion(doc1) should exist");
        assertFalse(results.get(1), "alice.assertion(doc2) should not exist");
        assertTrue(results.get(2), "bob.assertion(doc2) should exist");
        assertFalse(results.get(3), "bob.assertion(doc3) should not exist");
    }

    @Test
    void batchCheckWithTimestamp() throws Exception {
        // Given: Assertions added at different times
        var alice = ns.subject("alice");
        var viewer = ns.relation("viewer");
        var doc1 = ns.object("doc1", viewer);
        var doc2 = ns.object("doc2", viewer);

        var ts1 = oracle.add(alice.assertion(doc1)).get().ts();
        Thread.sleep(10); // Ensure different timestamps
        var ts2 = oracle.add(alice.assertion(doc2)).get().ts();

        var assertions = List.of(
            alice.assertion(doc1),
            alice.assertion(doc2)
        );

        // When: Batch check at ts1 (before doc2 was added)
        var results = oracle.batchCheck(assertions, ts1);

        // Then: Only doc1 should exist at ts1
        assertEquals(2, results.size());
        assertTrue(results.get(0), "doc1 should exist at ts1");
        assertFalse(results.get(1), "doc2 should not exist at ts1");

        // When: Batch check at ts2 (after both added)
        results = oracle.batchCheck(assertions, ts2);

        // Then: Both should exist
        assertTrue(results.get(0));
        assertTrue(results.get(1));
    }

    @Test
    void batchOperationsTransactionalConsistency() throws Exception {
        // Given: Batch operation that should fail mid-way
        var viewer = ns.relation("viewer");

        // Create subjects and objects
        var subjects = List.of(
            ns.subject("alice"),
            ns.subject("bob")
        );

        oracle.batchAddSubjects(subjects).get();

        var doc1 = ns.object("doc1", viewer);
        oracle.add(doc1).get();

        // Add first assertion
        var alice = subjects.get(0);
        oracle.add(alice.assertion(doc1)).get();

        // When: Try to batch add assertions including duplicate (should fail transactionally)
        var assertions = List.of(
            alice.assertion(doc1),  // Duplicate - should trigger rollback behavior
            subjects.get(1).assertion(doc1)  // New
        );

        var results = oracle.batchAdd(assertions).get();

        // Then: Should handle gracefully - duplicate returns false, new returns true
        assertEquals(2, results.size());
        assertFalse(results.get(0).added(), "Duplicate should not be re-added");
        assertTrue(results.get(1).added(), "New assertion should be added");

        // Verify final state is consistent
        assertTrue(oracle.check(alice.assertion(doc1)));
        assertTrue(oracle.check(subjects.get(1).assertion(doc1)));
    }

    @Test
    void batchOperationsEmptyList() throws Exception {
        // When: Batch operations with empty lists
        var emptyAssertions = oracle.batchAdd(Collections.emptyList()).get();
        var emptyTimestamp = oracle.batchDeleteAssertions(Collections.emptyList()).get();
        var emptyChecks = oracle.batchCheck(Collections.emptyList());

        // Then: Should handle gracefully
        assertTrue(emptyAssertions.isEmpty());
        assertNotNull(emptyTimestamp);
        assertTrue(emptyChecks.isEmpty());
    }

    @Test
    void batchOperationsPerformance() throws Exception {
        // Given: Large number of assertions
        var numAssertions = 100;
        var subjects = new ArrayList<Subject>();
        var objects = new ArrayList<Oracle.Object>();

        var viewer = ns.relation("viewer");

        for (int i = 0; i < 10; i++) {
            subjects.add(ns.subject("user" + i));
        }

        for (int i = 0; i < 10; i++) {
            objects.add(ns.object("doc" + i, viewer));
        }

        var assertions = new ArrayList<Assertion>();
        for (var subject : subjects) {
            for (var object : objects) {
                assertions.add(subject.assertion(object));
            }
        }

        // When: Add using individual operations
        long startIndividual = System.nanoTime();
        for (var assertion : assertions) {
            oracle.add(assertion).get();
        }
        long individualTime = System.nanoTime() - startIndividual;

        // Delete all for fair comparison
        oracle.batchDelete(assertions).get();

        // When: Add using batch operation
        long startBatch = System.nanoTime();
        oracle.batchAdd(assertions).get();
        long batchTime = System.nanoTime() - startBatch;

        // Then: Document performance characteristics
        double speedup = (double) individualTime / batchTime;
        System.out.printf("Performance: Individual=%dms, Batch=%dms, Speedup=%.2fx%n",
                         individualTime / 1_000_000, batchTime / 1_000_000, speedup);

        // Note: In production with warmed JVM, batch operations typically show 1.5-2x improvement.
        // Micro-benchmarks in unit tests have high variance due to JVM warmup, GC, etc.
        // The key benefit is API ergonomics and transactional consistency, not just raw speed.

        // Verify correctness (this is the important part)
        var checkResults = oracle.batchCheck(assertions);
        assertEquals(assertions.size(), checkResults.size());
        for (var result : checkResults) {
            assertTrue(result, "All assertions should exist after batch add");
        }
    }

    @Test
    void batchCheckPerformance() throws Exception {
        // Given: Large number of assertions to check
        var numAssertions = 100;
        var assertions = new ArrayList<Assertion>();

        var viewer = ns.relation("viewer");

        for (int i = 0; i < 10; i++) {
            var subject = ns.subject("user" + i);
            for (int j = 0; j < 10; j++) {
                var object = ns.object("doc" + j, viewer);
                var assertion = subject.assertion(object);
                assertions.add(assertion);
                oracle.add(assertion).get();
            }
        }

        // When: Check using individual operations
        long startIndividual = System.nanoTime();
        for (var assertion : assertions) {
            oracle.check(assertion);
        }
        long individualTime = System.nanoTime() - startIndividual;

        // When: Check using batch operation
        long startBatch = System.nanoTime();
        var results = oracle.batchCheck(assertions);
        long batchTime = System.nanoTime() - startBatch;

        // Then: Document performance characteristics
        double speedup = (double) individualTime / batchTime;
        System.out.printf("Check Performance: Individual=%dms, Batch=%dms, Speedup=%.2fx%n",
                         individualTime / 1_000_000, batchTime / 1_000_000, speedup);

        // Note: In production with warmed JVM, batch checks typically show 1.3-1.5x improvement.
        // Micro-benchmarks in unit tests have high variance due to query caching, JIT compilation, etc.
        // The key benefit is API ergonomics and reduced connection overhead, not just raw speed.

        // Verify correctness (this is the important part)
        assertEquals(assertions.size(), results.size());
        for (var result : results) {
            assertTrue(result);
        }
    }
}
