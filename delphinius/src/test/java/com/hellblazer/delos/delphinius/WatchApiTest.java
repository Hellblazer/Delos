/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.delphinius;

import com.hellblazer.delos.delphinius.Oracle.*;
import liquibase.Liquibase;
import liquibase.database.core.H2Database;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.h2.jdbc.JdbcConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Watch API - Zanzibar-style change notification mechanism.
 * Per Delos-868.11.
 *
 * @author hal.hildebrand
 */
public class WatchApiTest {

    private DirectOracle oracle;
    private Namespace ns;

    @BeforeEach
    void setUp() throws Exception {
        var url = String.format("jdbc:h2:mem:watch-test-%s;DB_CLOSE_DELAY=3", new Random().nextLong());
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
    void watchAndUnwatch() {
        List<WatchEvent> events = new CopyOnWriteArrayList<>();
        var id = oracle.watch(events::add);

        assertNotNull(id, "Watch should return a non-null UUID");

        // Unwatch should return true for valid ID
        assertTrue(oracle.unwatch(id), "Should return true when removing existing watcher");

        // Unwatch again should return false
        assertFalse(oracle.unwatch(id), "Should return false when watcher not found");
    }

    @Test
    void assertionAddEvents() throws Exception {
        List<WatchEvent> events = new CopyOnWriteArrayList<>();
        oracle.watch(events::add);

        var subject = ns.subject("alice");
        var relation = ns.relation("viewer");
        var object = ns.object("doc1", relation);
        var assertion = subject.assertion(object);

        // Add assertion
        var result = oracle.add(assertion).get();
        assertTrue(result.added(), "Assertion should be added");

        // Verify event was fired
        assertEquals(1, events.size(), "Should have received one event");
        var event = events.getFirst();
        assertEquals(WatchEventType.ASSERTION_ADD, event.type());
        assertEquals(subject, event.subject());
        assertEquals(object, event.object());
        assertNotNull(event.timestamp());
    }

    @Test
    void assertionAddDuplicateNoEvent() throws Exception {
        List<WatchEvent> events = new CopyOnWriteArrayList<>();

        var subject = ns.subject("alice");
        var relation = ns.relation("viewer");
        var object = ns.object("doc1", relation);
        var assertion = subject.assertion(object);

        // Add assertion first time (before watch)
        oracle.add(assertion).get();

        // Now watch
        oracle.watch(events::add);

        // Add same assertion again - should not fire event
        var result = oracle.add(assertion).get();
        assertFalse(result.added(), "Duplicate assertion should not be added");
        assertEquals(0, events.size(), "No event should be fired for duplicate");
    }

    @Test
    void assertionDeleteEvents() throws Exception {
        List<WatchEvent> events = new CopyOnWriteArrayList<>();

        var subject = ns.subject("bob");
        var relation = ns.relation("editor");
        var object = ns.object("doc2", relation);
        var assertion = subject.assertion(object);

        // Add assertion before watch
        oracle.add(assertion).get();

        // Now watch
        oracle.watch(events::add);

        // Delete assertion
        oracle.delete(assertion).get();

        // Verify event was fired
        assertEquals(1, events.size());
        var event = events.getFirst();
        assertEquals(WatchEventType.ASSERTION_DELETE, event.type());
        assertEquals(subject, event.subject());
        assertEquals(object, event.object());
    }

    @Test
    void subjectMappingEvents() throws Exception {
        List<WatchEvent> events = new CopyOnWriteArrayList<>();
        oracle.watch(events::add);

        var member = ns.relation("member");
        var userGroup = ns.subject("users", member);
        var adminGroup = ns.subject("admins", member);

        // Map admin group to users group (admins are members of users)
        oracle.map(adminGroup, userGroup).get();

        // Should have one event
        assertEquals(1, events.size());
        assertEquals(WatchEventType.SUBJECT_MAP, events.getFirst().type());

        // Unmap
        events.clear();
        oracle.remove(adminGroup, userGroup).get();

        assertEquals(1, events.size());
        assertEquals(WatchEventType.SUBJECT_UNMAP, events.getFirst().type());
    }

    @Test
    void objectMappingEvents() throws Exception {
        List<WatchEvent> events = new CopyOnWriteArrayList<>();
        oracle.watch(events::add);

        var read = ns.relation("read");
        var folder = ns.object("folder1", read);
        var doc = ns.object("doc1", read);

        // Map doc to folder
        oracle.map(folder, doc).get();

        assertEquals(1, events.size());
        assertEquals(WatchEventType.OBJECT_MAP, events.getFirst().type());

        // Unmap
        events.clear();
        oracle.remove(folder, doc).get();

        assertEquals(1, events.size());
        assertEquals(WatchEventType.OBJECT_UNMAP, events.getFirst().type());
    }

    @Test
    void relationMappingEvents() throws Exception {
        List<WatchEvent> events = new CopyOnWriteArrayList<>();
        oracle.watch(events::add);

        var viewer = ns.relation("viewer");
        var reader = ns.relation("reader");

        // Map viewer to reader (viewer implies reader)
        oracle.map(viewer, reader).get();

        assertEquals(1, events.size());
        assertEquals(WatchEventType.RELATION_MAP, events.getFirst().type());

        // Unmap
        events.clear();
        oracle.remove(viewer, reader).get();

        assertEquals(1, events.size());
        assertEquals(WatchEventType.RELATION_UNMAP, events.getFirst().type());
    }

    @Test
    void entityDeleteEvents() throws Exception {
        List<WatchEvent> events = new CopyOnWriteArrayList<>();

        // Create entities before watch
        var deleteNs = Oracle.namespace("delete-test");
        oracle.add(deleteNs).get();

        var relation = deleteNs.relation("test-relation");
        oracle.add(relation).get();

        var subject = deleteNs.subject("test-subject");
        oracle.add(subject).get();

        var object = deleteNs.object("test-object", relation);
        oracle.add(object).get();

        // Now watch
        oracle.watch(events::add);

        // Delete subject
        oracle.delete(subject).get();
        assertEquals(1, events.size());
        assertEquals(WatchEventType.SUBJECT_DELETE, events.getFirst().type());
        events.clear();

        // Delete object
        oracle.delete(object).get();
        assertEquals(1, events.size());
        assertEquals(WatchEventType.OBJECT_DELETE, events.getFirst().type());
        events.clear();

        // Delete relation
        oracle.delete(relation).get();
        assertEquals(1, events.size());
        assertEquals(WatchEventType.RELATION_DELETE, events.getFirst().type());
        events.clear();

        // Delete namespace
        oracle.delete(deleteNs).get();
        assertEquals(1, events.size());
        assertEquals(WatchEventType.NAMESPACE_DELETE, events.getFirst().type());
    }

    @Test
    void multipleWatchers() throws Exception {
        List<WatchEvent> events1 = new CopyOnWriteArrayList<>();
        List<WatchEvent> events2 = new CopyOnWriteArrayList<>();

        var id1 = oracle.watch(events1::add);
        var id2 = oracle.watch(events2::add);

        var subject = ns.subject("carol");
        var relation = ns.relation("owner");
        var object = ns.object("doc3", relation);

        oracle.add(subject.assertion(object)).get();

        // Both watchers should receive the event
        assertEquals(1, events1.size());
        assertEquals(1, events2.size());

        // Unwatch one
        oracle.unwatch(id1);
        oracle.add(ns.subject("dave").assertion(object)).get();

        // Only second watcher should receive new event
        assertEquals(1, events1.size(), "Unwatched listener should not receive events");
        assertEquals(2, events2.size(), "Active listener should receive event");
    }

    @Test
    void watcherExceptionIsolation() throws Exception {
        List<WatchEvent> goodEvents = new CopyOnWriteArrayList<>();

        // Register a failing watcher first
        oracle.watch(e -> {
            throw new RuntimeException("Deliberate test exception");
        });

        // Register a good watcher second
        oracle.watch(goodEvents::add);

        var subject = ns.subject("test");
        var relation = ns.relation("test");
        var object = ns.object("test", relation);

        // Add assertion - should not throw despite failing watcher
        oracle.add(subject.assertion(object)).get();

        // Good watcher should still receive the event
        assertEquals(1, goodEvents.size(), "Exception in one watcher should not affect others");
    }

    @Test
    void eventTimestampOrdering() throws Exception {
        List<WatchEvent> events = new CopyOnWriteArrayList<>();
        oracle.watch(events::add);

        var relation = ns.relation("access");

        // Perform multiple operations
        for (int i = 0; i < 5; i++) {
            var subject = ns.subject("user" + i);
            var object = ns.object("resource" + i, relation);
            oracle.add(subject.assertion(object)).get();
        }

        assertEquals(5, events.size());

        // Verify timestamps are non-decreasing
        for (int i = 1; i < events.size(); i++) {
            assertTrue(events.get(i).timestamp().compareTo(events.get(i - 1).timestamp()) >= 0,
                       "Timestamps should be non-decreasing");
        }
    }
}
