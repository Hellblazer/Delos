/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.delphinius;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.joou.ULong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * @author hal.hildebrand
 */
public class DirectOracle extends AbstractOracle {

    private static final Logger log = LoggerFactory.getLogger(DirectOracle.class);
    private static final int DEFAULT_ASYNC_POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);

    final DSLContext                                dslCtx;  // Package-private for test access
    final Supplier<ULong>                           clock;   // Package-private for test access
    private final Map<UUID, WatchListener>          watchers = new ConcurrentHashMap<>();
    private final Map<UUID, AsyncWatchRegistration> asyncWatchers = new ConcurrentHashMap<>();
    private final ExecutorService                   defaultAsyncExecutor;

    public DirectOracle(Connection connection) {
        this(connection, () -> ULong.valueOf(System.currentTimeMillis()));
    }

    public DirectOracle(Connection connection, Supplier<ULong> clock) {
        this(DSL.using(connection, SQLDialect.H2), clock);
    }

    public DirectOracle(DSLContext dslCtx, Supplier<ULong> clock) {
        this(dslCtx, clock, DEFAULT_ASYNC_POOL_SIZE);
    }

    public DirectOracle(DSLContext dslCtx, Supplier<ULong> clock, int asyncPoolSize) {
        super(dslCtx);
        this.dslCtx = dslCtx;
        this.clock = clock;
        this.defaultAsyncExecutor = Executors.newFixedThreadPool(asyncPoolSize, r -> {
            var t = new Thread(r);
            t.setName("DirectOracle-async-watch-" + t.getId());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Add an Assertion. The subject and object of the assertion will also be added if they do not exist.
     *
     * @return the time stamp of the addition attempt, and whether the assertion was added or previously existed
     */
    public CompletableFuture<Asserted> add(Assertion assertion) {
        var timestamp = clock.get();
        var added = dslCtx.transactionResult(ctx -> add(DSL.using(ctx), assertion, timestamp.longValue()));
        if (added) {
            fireEvent(WatchEvent.assertionAdd(timestamp, assertion));
        }
        return CompletableFuture.completedFuture(new Asserted(timestamp, added));
    }

    /**
     * Add a Namespace.
     */
    public CompletableFuture<ULong> add(Namespace namespace) {
        dslCtx.transaction(ctx -> {
            add(DSL.using(ctx), namespace);
        });
        return CompletableFuture.completedFuture(clock.get());
    }

    /**
     * Add an Object.
     */
    public CompletableFuture<ULong> add(Object object) {
        dslCtx.transaction(ctx -> {
            add(DSL.using(ctx), object);
        });
        return CompletableFuture.completedFuture(clock.get());
    }

    /**
     * Add a Relation
     */
    public CompletableFuture<ULong> add(Relation relation) {
        dslCtx.transaction(ctx -> {
            add(DSL.using(ctx), relation);
        });
        return CompletableFuture.completedFuture(clock.get());
    }

    /**
     * Add a Subject
     */
    public CompletableFuture<ULong> add(Subject subject) {
        dslCtx.transaction(ctx -> {
            add(DSL.using(ctx), subject);
        });
        return CompletableFuture.completedFuture(clock.get());
    }

    @Override
    public boolean check(Assertion assertion, ULong valid) throws SQLException {
        // Cannot query the future
        if (valid.compareTo(clock.get()) > 0) {
            return false;
        }
        // Use temporal check at the specified timestamp
        return checkAt(assertion, valid.longValue());
    }

    /**
     * Check if a content change operation is authorized.
     * Implements Zanzibar content-change check semantics.
     * <p>
     * Verifies:
     * 1. Read zookie is not in the future (invalid token)
     * 2. User had read access at zookie time
     * 3. User has write access at current time
     */
    @Override
    public ContentChangeResult checkContentChange(Assertion readAssertion, Assertion writeAssertion,
                                                  ULong readZookie) throws SQLException {
        var now = clock.get();

        // Validate zookie is not in the future
        if (readZookie.compareTo(now) > 0) {
            return ContentChangeResult.denied(ContentChangeDenialReason.INVALID_ZOOKIE, now);
        }

        // Check read access at zookie time
        boolean hadReadAccess = checkAt(readAssertion, readZookie.longValue());

        // Check write access at current time
        boolean hasWriteAccess = check(writeAssertion);

        // Determine result
        if (hadReadAccess && hasWriteAccess) {
            return ContentChangeResult.authorized(now);
        } else if (!hadReadAccess && !hasWriteAccess) {
            return ContentChangeResult.denied(ContentChangeDenialReason.BOTH_DENIED, now);
        } else if (!hadReadAccess) {
            return ContentChangeResult.denied(ContentChangeDenialReason.READ_ACCESS_REVOKED, now);
        } else {
            return ContentChangeResult.denied(ContentChangeDenialReason.WRITE_ACCESS_DENIED, now);
        }
    }

    /**
     * Delete an assertion. Only the assertion is deleted, not the subject nor object of the assertion.
     */
    public CompletableFuture<ULong> delete(Assertion assertion) {
        var timestamp = clock.get();
        dslCtx.transaction(ctx -> {
            delete(DSL.using(ctx), assertion, timestamp.longValue());
        });
        fireEvent(WatchEvent.assertionDelete(timestamp, assertion));
        return CompletableFuture.completedFuture(timestamp);
    }

    /**
     * Delete a Namespace. All objects, subjects, relations and assertions that reference this namespace will also be
     * deleted.
     */
    @Override
    public CompletableFuture<ULong> delete(Namespace namespace) {
        var timestamp = clock.get();
        dslCtx.transaction(ctx -> {
            delete(DSL.using(ctx), namespace);
        });
        fireEvent(WatchEvent.namespaceDelete(timestamp, namespace));
        return CompletableFuture.completedFuture(timestamp);
    }

    /**
     * Delete an Object. All dependent uses of the object (mappings, Assertions) are removed as well.
     */
    public CompletableFuture<ULong> delete(Object object) {
        var timestamp = clock.get();
        dslCtx.transaction(ctx -> {
            delete(DSL.using(ctx), object);
        });
        fireEvent(WatchEvent.objectDelete(timestamp, object));
        return CompletableFuture.completedFuture(timestamp);
    }

    /**
     * Delete an Relation. All dependent uses of the relation (mappings, Subject, Object and Assertions) are removed as
     * well.
     */
    public CompletableFuture<ULong> delete(Relation relation) {
        var timestamp = clock.get();
        dslCtx.transaction(ctx -> {
            delete(DSL.using(ctx), relation);
        });
        fireEvent(WatchEvent.relationDelete(timestamp, relation));
        return CompletableFuture.completedFuture(timestamp);
    }

    /**
     * Delete an Subject. All dependant uses of the subject (mappings and Assertions) are removed as well.
     */
    public CompletableFuture<ULong> delete(Subject subject) {
        var timestamp = clock.get();
        dslCtx.transaction(ctx -> {
            delete(DSL.using(ctx), subject);
        });
        fireEvent(WatchEvent.subjectDelete(timestamp, subject));
        return CompletableFuture.completedFuture(timestamp);
    }

    /**
     * Map the parent object to the child
     */
    public CompletableFuture<ULong> map(Object parent, Object child) {
        var timestamp = clock.get();
        dslCtx.transaction(ctx -> {
            map(parent, DSL.using(ctx), child);
        });
        fireEvent(WatchEvent.objectMap(timestamp, parent, child));
        return CompletableFuture.completedFuture(timestamp);
    }

    /**
     * Map the parent relation to the child
     */
    public CompletableFuture<ULong> map(Relation parent, Relation child) {
        var timestamp = clock.get();
        dslCtx.transaction(ctx -> {
            map(parent, DSL.using(ctx), child);
        });
        fireEvent(WatchEvent.relationMap(timestamp, parent, child));
        return CompletableFuture.completedFuture(timestamp);
    }

    /**
     * Map the parent subject to the child
     */
    public CompletableFuture<ULong> map(Subject parent, Subject child) {
        var timestamp = clock.get();
        dslCtx.transaction(ctx -> {
            map(parent, DSL.using(ctx), child);
        });
        fireEvent(WatchEvent.subjectMap(timestamp, parent, child));
        return CompletableFuture.completedFuture(timestamp);
    }

    /**
     * Remove the mapping between the parent and the child objects
     */
    public CompletableFuture<ULong> remove(Object parent, Object child) {
        var timestamp = clock.get();
        dslCtx.transaction(ctx -> {
            remove(parent, DSL.using(ctx), child);
        });
        fireEvent(WatchEvent.objectUnmap(timestamp, parent, child));
        return CompletableFuture.completedFuture(timestamp);
    }

    /**
     * Remove the mapping between the parent and the child relations
     */
    public CompletableFuture<ULong> remove(Relation parent, Relation child) {
        var timestamp = clock.get();
        dslCtx.transaction(ctx -> {
            remove(parent, DSL.using(ctx), child);
        });
        fireEvent(WatchEvent.relationUnmap(timestamp, parent, child));
        return CompletableFuture.completedFuture(timestamp);
    }

    /**
     * Remove the mapping between the parent and the child subects
     */
    public CompletableFuture<ULong> remove(Subject parent, Subject child) {
        var timestamp = clock.get();
        dslCtx.transaction(ctx -> {
            remove(parent, DSL.using(ctx), child);
        });
        fireEvent(WatchEvent.subjectUnmap(timestamp, parent, child));
        return CompletableFuture.completedFuture(timestamp);
    }

    // ==================== Watch API Implementation ====================

    /**
     * Register a listener for authorization change events.
     * Events are delivered synchronously on the calling thread.
     *
     * @param listener the callback to invoke on changes
     * @return UUID identifying the registration
     */
    @Override
    public UUID watch(WatchListener listener) {
        var id = UUID.randomUUID();
        watchers.put(id, listener);
        return id;
    }

    /**
     * Register a listener for authorization change events with asynchronous delivery.
     * Events are delivered on the specified executor, ensuring that slow listeners
     * do not block Oracle operations.
     *
     * @param listener the callback to invoke on changes
     * @param executor the executor for async delivery; if null, uses default internal executor
     * @return UUID identifying the registration
     */
    public UUID watchAsync(WatchListener listener, ExecutorService executor) {
        var id = UUID.randomUUID();
        var effectiveExecutor = executor != null ? executor : defaultAsyncExecutor;
        asyncWatchers.put(id, new AsyncWatchRegistration(listener, effectiveExecutor));
        return id;
    }

    /**
     * Deregister a watch listener (synchronous or asynchronous).
     *
     * @param id the UUID returned from watch() or watchAsync()
     * @return true if a listener was removed
     */
    @Override
    public boolean unwatch(UUID id) {
        var removedSync = watchers.remove(id) != null;
        var removedAsync = asyncWatchers.remove(id) != null;
        return removedSync || removedAsync;
    }

    /**
     * Fire an event to all registered watchers.
     * Synchronous watchers are notified on the calling thread.
     * Asynchronous watchers are notified on their configured executors.
     */
    private void fireEvent(WatchEvent event) {
        // Deliver to synchronous watchers (on calling thread)
        for (var listener : watchers.values()) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                log.warn("Synchronous watch listener threw exception for event: {}", event.type(), e);
            }
        }

        // Deliver to asynchronous watchers (on executor threads)
        for (var registration : asyncWatchers.values()) {
            try {
                registration.executor.execute(() -> {
                    try {
                        registration.listener.onEvent(event);
                    } catch (Exception e) {
                        log.warn("Asynchronous watch listener threw exception for event: {}", event.type(), e);
                    }
                });
            } catch (RejectedExecutionException e) {
                log.debug("Async watch event rejected (executor shutdown?): {}", event.type(), e);
            }
        }
    }

    /**
     * Internal record to track async watch registrations with their executors.
     */
    private record AsyncWatchRegistration(WatchListener listener, ExecutorService executor) {
    }

    /**
     * Shutdown the async watch executor and clean up resources.
     * Should be called when the Oracle is no longer needed.
     * This is a best-effort shutdown; ongoing async deliveries may be interrupted.
     */
    public void close() {
        defaultAsyncExecutor.shutdown();
        try {
            if (!defaultAsyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                defaultAsyncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            defaultAsyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ==================== Batch Operations Implementation ====================

    /**
     * Add multiple Assertions in a single batch operation.
     */
    @Override
    public CompletableFuture<java.util.List<Asserted>> batchAdd(java.util.List<Assertion> assertions) {
        if (assertions.isEmpty()) {
            return CompletableFuture.completedFuture(java.util.Collections.emptyList());
        }

        var timestamp = clock.get();
        var results = dslCtx.transactionResult(ctx -> {
            var context = DSL.using(ctx);
            var assertedResults = new java.util.ArrayList<Asserted>(assertions.size());

            for (var assertion : assertions) {
                boolean added;
                try {
                    added = add(context, assertion, timestamp.longValue());
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to add assertion: " + assertion, e);
                }
                assertedResults.add(new Asserted(timestamp, added));

                if (added) {
                    fireEvent(WatchEvent.assertionAdd(timestamp, assertion));
                }
            }

            return assertedResults;
        });

        return CompletableFuture.completedFuture(results);
    }

    /**
     * Add multiple Subjects in a single batch operation.
     */
    @Override
    public CompletableFuture<ULong> batchAddSubjects(java.util.List<Subject> subjects) {
        if (subjects.isEmpty()) {
            return CompletableFuture.completedFuture(clock.get());
        }

        dslCtx.transaction(ctx -> {
            var context = DSL.using(ctx);
            for (var subject : subjects) {
                try {
                    add(context, subject);
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to add subject: " + subject, e);
                }
            }
        });

        return CompletableFuture.completedFuture(clock.get());
    }

    /**
     * Add multiple Objects in a single batch operation.
     */
    @Override
    public CompletableFuture<ULong> batchAddObjects(java.util.List<Object> objects) {
        if (objects.isEmpty()) {
            return CompletableFuture.completedFuture(clock.get());
        }

        dslCtx.transaction(ctx -> {
            var context = DSL.using(ctx);
            for (var object : objects) {
                try {
                    add(context, object);
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to add object: " + object, e);
                }
            }
        });

        return CompletableFuture.completedFuture(clock.get());
    }

    /**
     * Add multiple Relations in a single batch operation.
     */
    @Override
    public CompletableFuture<ULong> batchAddRelations(java.util.List<Relation> relations) {
        if (relations.isEmpty()) {
            return CompletableFuture.completedFuture(clock.get());
        }

        dslCtx.transaction(ctx -> {
            var context = DSL.using(ctx);
            for (var relation : relations) {
                try {
                    add(context, relation);
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to add relation: " + relation, e);
                }
            }
        });

        return CompletableFuture.completedFuture(clock.get());
    }

    /**
     * Delete multiple Assertions in a single batch operation.
     */
    @Override
    public CompletableFuture<ULong> batchDelete(java.util.List<Assertion> assertions) {
        if (assertions.isEmpty()) {
            return CompletableFuture.completedFuture(clock.get());
        }

        var timestamp = clock.get();
        dslCtx.transaction(ctx -> {
            var context = DSL.using(ctx);
            for (var assertion : assertions) {
                try {
                    delete(context, assertion, timestamp.longValue());
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to delete assertion: " + assertion, e);
                }
                fireEvent(WatchEvent.assertionDelete(timestamp, assertion));
            }
        });

        return CompletableFuture.completedFuture(timestamp);
    }

    /**
     * Delete multiple Subjects in a single batch operation.
     */
    @Override
    public CompletableFuture<ULong> batchDeleteSubjects(java.util.List<Subject> subjects) {
        if (subjects.isEmpty()) {
            return CompletableFuture.completedFuture(clock.get());
        }

        var timestamp = clock.get();
        dslCtx.transaction(ctx -> {
            var context = DSL.using(ctx);
            for (var subject : subjects) {
                try {
                    delete(context, subject);
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to delete subject: " + subject, e);
                }
                fireEvent(WatchEvent.subjectDelete(timestamp, subject));
            }
        });

        return CompletableFuture.completedFuture(timestamp);
    }

    /**
     * Delete multiple Objects in a single batch operation.
     */
    @Override
    public CompletableFuture<ULong> batchDeleteObjects(java.util.List<Object> objects) {
        if (objects.isEmpty()) {
            return CompletableFuture.completedFuture(clock.get());
        }

        var timestamp = clock.get();
        dslCtx.transaction(ctx -> {
            var context = DSL.using(ctx);
            for (var object : objects) {
                try {
                    delete(context, object);
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to delete object: " + object, e);
                }
                fireEvent(WatchEvent.objectDelete(timestamp, object));
            }
        });

        return CompletableFuture.completedFuture(timestamp);
    }

    /**
     * Delete multiple Relations in a single batch operation.
     */
    @Override
    public CompletableFuture<ULong> batchDeleteRelations(java.util.List<Relation> relations) {
        if (relations.isEmpty()) {
            return CompletableFuture.completedFuture(clock.get());
        }

        var timestamp = clock.get();
        dslCtx.transaction(ctx -> {
            var context = DSL.using(ctx);
            for (var relation : relations) {
                try {
                    delete(context, relation);
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to delete relation: " + relation, e);
                }
                fireEvent(WatchEvent.relationDelete(timestamp, relation));
            }
        });

        return CompletableFuture.completedFuture(timestamp);
    }

    /**
     * Check multiple assertions at the current time in a single batch operation.
     * Uses optimized bulk query for better performance.
     */
    @Override
    public java.util.List<Boolean> batchCheck(java.util.List<Assertion> assertions) throws SQLException {
        if (assertions.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        var results = new java.util.ArrayList<Boolean>(assertions.size());

        // Build a map of resolved IDs for efficient lookup
        var resolvedAssertions = new java.util.HashMap<Assertion, NamespacedId[]>();
        for (var assertion : assertions) {
            var s = resolve(dslCtx, assertion.subject());
            var o = resolve(dslCtx, assertion.object());
            resolvedAssertions.put(assertion, new NamespacedId[]{s, o});
        }

        // Check each assertion using grants query
        for (var assertion : assertions) {
            var resolved = resolvedAssertions.get(assertion);
            var s = resolved[0];
            var o = resolved[1];

            if (s == null || o == null) {
                results.add(false);
            } else {
                var exists = dslCtx.fetchExists(dslCtx.selectOne().from(grants(s.id(), dslCtx, o.id())));
                results.add(exists);
            }
        }

        return results;
    }

    /**
     * Check multiple assertions at a specific timestamp in a single batch operation.
     */
    @Override
    public java.util.List<Boolean> batchCheck(java.util.List<Assertion> assertions, ULong valid) throws SQLException {
        if (assertions.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        // Cannot query the future
        var now = clock.get();
        if (valid.compareTo(now) > 0) {
            return java.util.Collections.nCopies(assertions.size(), false);
        }

        var results = new java.util.ArrayList<Boolean>(assertions.size());
        var timestamp = valid.longValue();

        // Build a map of resolved IDs for efficient lookup
        var resolvedAssertions = new java.util.HashMap<Assertion, NamespacedId[]>();
        for (var assertion : assertions) {
            var s = resolve(dslCtx, assertion.subject());
            var o = resolve(dslCtx, assertion.object());
            resolvedAssertions.put(assertion, new NamespacedId[]{s, o});
        }

        // Check each assertion using temporal grants query
        for (var assertion : assertions) {
            var resolved = resolvedAssertions.get(assertion);
            var s = resolved[0];
            var o = resolved[1];

            if (s == null || o == null) {
                results.add(false);
            } else {
                var exists = dslCtx.fetchExists(dslCtx.selectOne().from(grants(s.id(), dslCtx, o.id(), timestamp)));
                results.add(exists);
            }
        }

        return results;
    }
}
