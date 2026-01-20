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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * @author hal.hildebrand
 */
public class DirectOracle extends AbstractOracle {

    private static final Logger log = LoggerFactory.getLogger(DirectOracle.class);

    private final DSLContext                  dslCtx;
    private final Supplier<ULong>             clock;
    private final Map<UUID, WatchListener>    watchers = new ConcurrentHashMap<>();

    public DirectOracle(Connection connection) {
        this(connection, () -> ULong.valueOf(System.currentTimeMillis()));
    }

    public DirectOracle(Connection connection, Supplier<ULong> clock) {
        this(DSL.using(connection, SQLDialect.H2), clock);
    }

    public DirectOracle(DSLContext dslCtx, Supplier<ULong> clock) {
        super(dslCtx);
        this.dslCtx = dslCtx;
        this.clock = clock;
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
        var fs = new CompletableFuture<Asserted>();
        fs.complete(new Asserted(timestamp, added));
        return fs;
    }

    /**
     * Add a Namespace.
     */
    public CompletableFuture<ULong> add(Namespace namespace) {
        dslCtx.transaction(ctx -> {
            add(DSL.using(ctx), namespace);
        });
        var fs = new CompletableFuture<ULong>();
        fs.complete(clock.get());
        return fs;
    }

    /**
     * Add an Object.
     */
    public CompletableFuture<ULong> add(Object object) {
        dslCtx.transaction(ctx -> {
            add(DSL.using(ctx), object);
        });
        var fs = new CompletableFuture<ULong>();
        fs.complete(clock.get());
        return fs;
    }

    /**
     * Add a Relation
     */
    public CompletableFuture<ULong> add(Relation relation) {
        dslCtx.transaction(ctx -> {
            add(DSL.using(ctx), relation);
        });
        var fs = new CompletableFuture<ULong>();
        fs.complete(clock.get());
        return fs;
    }

    /**
     * Add a Subject
     */
    public CompletableFuture<ULong> add(Subject subject) {
        dslCtx.transaction(ctx -> {
            add(DSL.using(ctx), subject);
        });
        var fs = new CompletableFuture<ULong>();
        fs.complete(clock.get());
        return fs;
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
        var fs = new CompletableFuture<ULong>();
        fs.complete(timestamp);
        return fs;
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
        var fs = new CompletableFuture<ULong>();
        fs.complete(timestamp);
        return fs;
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
        var fs = new CompletableFuture<ULong>();
        fs.complete(timestamp);
        return fs;
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
        var fs = new CompletableFuture<ULong>();
        fs.complete(timestamp);
        return fs;
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
        var fs = new CompletableFuture<ULong>();
        fs.complete(timestamp);
        return fs;
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
        var fs = new CompletableFuture<ULong>();
        fs.complete(timestamp);
        return fs;
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
        var fs = new CompletableFuture<ULong>();
        fs.complete(timestamp);
        return fs;
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
        var fs = new CompletableFuture<ULong>();
        fs.complete(timestamp);
        return fs;
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
        var fs = new CompletableFuture<ULong>();
        fs.complete(timestamp);
        return fs;
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
        var fs = new CompletableFuture<ULong>();
        fs.complete(timestamp);
        return fs;
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
        var fs = new CompletableFuture<ULong>();
        fs.complete(timestamp);
        return fs;
    }

    // ==================== Watch API Implementation ====================

    /**
     * Register a listener for authorization change events.
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
     * Deregister a watch listener.
     *
     * @param id the UUID returned from watch()
     * @return true if a listener was removed
     */
    @Override
    public boolean unwatch(UUID id) {
        return watchers.remove(id) != null;
    }

    /**
     * Fire an event to all registered watchers.
     * Events are delivered synchronously; listeners should not block.
     */
    private void fireEvent(WatchEvent event) {
        for (var listener : watchers.values()) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                log.warn("Watch listener threw exception for event: {}", event.type(), e);
            }
        }
    }
}
