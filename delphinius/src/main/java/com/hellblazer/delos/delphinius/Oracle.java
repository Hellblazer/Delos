/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.delphinius;

import org.joou.ULong;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * @author hal.hildebrand
 */
public interface Oracle {

    // Types for DAG
    String OBJECT_TYPE   = "o";
    String RELATION_TYPE = "r";
    String SUBJECT_TYPE  = "s";

    static Namespace namespace(String name) {
        return new Namespace(name);
    }

    /**
     * Add an Assertion. The subject and object of the assertion will also be added if they do not exist
     *
     * @return the future returning the Asserted value, containing the timestamp when this assertion was attempted, and
     * whether the assertion was newly added or already existed.
     */
    CompletableFuture<Asserted> add(Assertion assertion);

    /**
     * Add a Namespace.
     *
     * @return the future returning the time value when this namespace is committed
     */
    CompletableFuture<ULong> add(Namespace namespace);

    /**
     * Add an Object.
     *
     * @return the future returning the time value when this object is committed
     */
    CompletableFuture<ULong> add(Object object);

    /**
     * Add a Relation
     *
     * @return the future returning the time value when this relation is committed
     */
    CompletableFuture<ULong> add(Relation relation);

    /**
     * Add a Subject
     *
     * @return the future returning the time value when this subject is committed
     */
    CompletableFuture<ULong> add(Subject subject);

    // ==================== Batch Operations API ====================

    /**
     * Add multiple Assertions in a single batch operation.
     * The subjects and objects of the assertions will also be added if they do not exist.
     * This is more efficient than calling add() multiple times.
     *
     * @param assertions the list of assertions to add
     * @return the future returning a list of Asserted results for each assertion
     */
    default CompletableFuture<List<Asserted>> batchAdd(List<Assertion> assertions) {
        throw new UnsupportedOperationException("Batch add assertions not implemented");
    }

    /**
     * Add multiple Subjects in a single batch operation.
     *
     * @param subjects the list of subjects to add
     * @return the future returning the time value when all subjects are committed
     */
    default CompletableFuture<ULong> batchAddSubjects(List<Subject> subjects) {
        throw new UnsupportedOperationException("Batch add subjects not implemented");
    }

    /**
     * Add multiple Objects in a single batch operation.
     *
     * @param objects the list of objects to add
     * @return the future returning the time value when all objects are committed
     */
    default CompletableFuture<ULong> batchAddObjects(List<Object> objects) {
        throw new UnsupportedOperationException("Batch add objects not implemented");
    }

    /**
     * Add multiple Relations in a single batch operation.
     *
     * @param relations the list of relations to add
     * @return the future returning the time value when all relations are committed
     */
    default CompletableFuture<ULong> batchAddRelations(List<Relation> relations) {
        throw new UnsupportedOperationException("Batch add relations not implemented");
    }

    /**
     * Delete multiple Assertions in a single batch operation.
     * Only the assertions are deleted, not the subjects nor objects.
     *
     * @param assertions the list of assertions to delete
     * @return the future returning the time value when all deletions are committed
     */
    default CompletableFuture<ULong> batchDelete(List<Assertion> assertions) {
        throw new UnsupportedOperationException("Batch delete assertions not implemented");
    }

    /**
     * Delete multiple Assertions in a single batch operation (alias for batchDelete).
     *
     * @param assertions the list of assertions to delete
     * @return the future returning the time value when all deletions are committed
     */
    default CompletableFuture<ULong> batchDeleteAssertions(List<Assertion> assertions) {
        return batchDelete(assertions);
    }

    /**
     * Delete multiple Subjects in a single batch operation.
     * All dependent uses of the subjects (mappings and Assertions) are removed as well.
     *
     * @param subjects the list of subjects to delete
     * @return the future returning the time value when all deletions are committed
     */
    default CompletableFuture<ULong> batchDeleteSubjects(List<Subject> subjects) {
        throw new UnsupportedOperationException("Batch delete subjects not implemented");
    }

    /**
     * Delete multiple Objects in a single batch operation.
     * All dependent uses of the objects (mappings and Assertions) are removed as well.
     *
     * @param objects the list of objects to delete
     * @return the future returning the time value when all deletions are committed
     */
    default CompletableFuture<ULong> batchDeleteObjects(List<Object> objects) {
        throw new UnsupportedOperationException("Batch delete objects not implemented");
    }

    /**
     * Delete multiple Relations in a single batch operation.
     * All dependent uses of the relations (mappings, Subjects, Objects and Assertions) are removed as well.
     *
     * @param relations the list of relations to delete
     * @return the future returning the time value when all deletions are committed
     */
    default CompletableFuture<ULong> batchDeleteRelations(List<Relation> relations) {
        throw new UnsupportedOperationException("Batch delete relations not implemented");
    }

    /**
     * Check multiple assertions at the current time in a single batch operation.
     * This is more efficient than calling check() multiple times.
     *
     * @param assertions the list of assertions to check
     * @return a list of boolean results, one for each assertion
     */
    default List<Boolean> batchCheck(List<Assertion> assertions) throws SQLException {
        throw new UnsupportedOperationException("Batch check not implemented");
    }

    /**
     * Check multiple assertions at a specific timestamp in a single batch operation.
     *
     * @param assertions the list of assertions to check
     * @param valid the timestamp at which to check validity
     * @return a list of boolean results, one for each assertion
     */
    default List<Boolean> batchCheck(List<Assertion> assertions, ULong valid) throws SQLException {
        throw new UnsupportedOperationException("Batch check with timestamp not implemented");
    }

    /**
     * Check the assertion is true at the valid time
     *
     * @param valid - the time stamp at which the assertion is valid
     * @return true if the assertion is made, false if not
     */
    boolean check(Assertion assertion, ULong valid) throws SQLException;

    /**
     * Check the assertion is true at the current time
     *
     * @return true if the assertion is made, false if not
     */
    boolean check(Assertion assertion) throws SQLException;

    /**
     * Check if a content change operation is authorized. This implements Zanzibar
     * content-change check semantics to prevent stale-read attacks (New Enemy Problem).
     * <p>
     * Verifies both:
     * <ol>
     *   <li>User had read access at readZookie time (when content was loaded)</li>
     *   <li>User has write access at current time</li>
     * </ol>
     * <p>
     * This prevents the attack where:
     * <ol>
     *   <li>User reads document at T1 (gets zookie)</li>
     *   <li>User's read access is revoked at T2</li>
     *   <li>User tries to write at T3 using content from T1</li>
     * </ol>
     * Without content-change checks, the write might succeed because write access
     * wasn't revoked - but the user shouldn't be able to write content they could
     * no longer read.
     *
     * @param readAssertion the read permission that was checked when content was loaded
     * @param writeAssertion the write permission required for the modification
     * @param readZookie timestamp when content was read (the "zookie" from read operation)
     * @return result containing authorization decision and reason
     * @throws SQLException on database error
     */
    default ContentChangeResult checkContentChange(Assertion readAssertion, Assertion writeAssertion,
                                                   ULong readZookie) throws SQLException {
        throw new UnsupportedOperationException("Content-change checks not implemented");
    }

    /**
     * Simplified content-change check when read and write use the same assertion.
     * Common case: user needs "editor" permission for both read and write.
     *
     * @param assertion the permission assertion (same for read and write)
     * @param readZookie timestamp when content was read
     * @return result containing authorization decision and reason
     * @throws SQLException on database error
     */
    default ContentChangeResult checkContentChange(Assertion assertion, ULong readZookie) throws SQLException {
        return checkContentChange(assertion, assertion, readZookie);
    }

    /** Reasons why a content-change check may be denied */
    enum ContentChangeDenialReason {
        /** Content change is authorized */
        NONE,
        /** User had read access at zookie time but it was revoked */
        READ_ACCESS_REVOKED,
        /** User does not have current write permission */
        WRITE_ACCESS_DENIED,
        /** Zookie timestamp is in the future (invalid token) */
        INVALID_ZOOKIE,
        /** Both read and write access were denied */
        BOTH_DENIED
    }

    /**
     * Result of a content-change authorization check.
     *
     * @param authorized true if the content change is permitted
     * @param reason if not authorized, the reason for denial
     * @param currentTimestamp the current timestamp used for write check
     */
    record ContentChangeResult(boolean authorized, ContentChangeDenialReason reason, ULong currentTimestamp) {

        /** Create an authorized result */
        public static ContentChangeResult authorized(ULong timestamp) {
            return new ContentChangeResult(true, ContentChangeDenialReason.NONE, timestamp);
        }

        /** Create a denied result */
        public static ContentChangeResult denied(ContentChangeDenialReason reason, ULong timestamp) {
            return new ContentChangeResult(false, reason, timestamp);
        }
    }

    /**
     * Delete an assertion. Only the assertion is deleted, not the subject nor object of the assertion.
     *
     * @return the future returning the time value when this assertion delete is committed
     */
    CompletableFuture<ULong> delete(Assertion assertion);

    /**
     * Delete a Namespace. All objects, subjects, relations and assertions that reference this namespace will also be
     * deleted.
     *
     * @return the future returning the time value when this namespace delete is committed
     */
    CompletableFuture<ULong> delete(Namespace namespace);

    /**
     * Delete an Object. All dependent uses of the object (mappings, Assertions) are removed as well.
     *
     * @return the future returning the time value when this object delete is committed
     */
    CompletableFuture<ULong> delete(Object object);

    /**
     * Delete a Relation. All dependent uses of the relation (mappings, Subject, Object and Assertions) are removed as
     * well.
     *
     * @return the future returning the time value when this relation delete is committed
     */
    CompletableFuture<ULong> delete(Relation relation);

    /**
     * Delete an Subject. All dependent uses of the subject (mappings and Assertions) are removed as well.
     *
     * @return the future returning the time value when this subject delete is committed
     */
    CompletableFuture<ULong> delete(Subject subject);

    /**
     * Answer the list of Subjects, both direct and transitive Subjects, that map to the supplied object. The query only
     * considers subjects with assertions that match the object completely - i.e. {namespace, name, relation}
     *
     * @throws SQLException
     */
    List<Subject> expand(Object object) throws SQLException;

    /**
     * Answer the list of Subjects, both direct and transitive, that map to the object from subjects that have the
     * supplied predicate as their relation. The query only considers assertions that match the object completely - i.e.
     * {namespace, name, relation}
     *
     * @throws SQLException
     */
    List<Subject> expand(Relation predicate, Object object) throws SQLException;

    /**
     * Answer the list of direct and transitive Objects that map to the subject from objects that have the supplied
     * predicate as their relation. The query only considers assertions that match the subject completely - i.e.
     * {namespace, name, relation}
     *
     * @throws SQLException
     */
    List<Object> expand(Relation predicate, Subject subject) throws SQLException;

    /**
     * Answer the list of direct and transitive Objects that map to the supplied subject. The query only considers
     * objects with assertions that match the subject completely - i.e. {namespace, name, relation}
     *
     * @throws SQLException
     */
    List<Object> expand(Subject subject) throws SQLException;

    /**
     * Map the parent object to the child
     *
     * @return the future returning the time value when this object mapping is committed
     */
    CompletableFuture<ULong> map(Object parent, Object child);

    /**
     * Map the parent relation to the child
     *
     * @return the future returning the time value when this relation mapping is committed
     */
    CompletableFuture<ULong> map(Relation parent, Relation child);

    /**
     * Map the parent subject to the child
     *
     * @return the future returning the time value when this subject mapping is committed
     */
    CompletableFuture<ULong> map(Subject parent, Subject child);

    /**
     * Answer the list of direct Subjects that map to the supplied objects. The query only considers subjects with
     * assertions that match the objects completely - i.e. {namespace, name, relation}
     *
     * @throws SQLException
     */
    List<Subject> read(Object... objects) throws SQLException;

    /**
     * Answer the list of direct Subjects that map to the supplied objects. The query only considers subjects with
     * assertions that match the objects completely - i.e. {namespace, name, relation} and only the subjects that have
     * the matching predicate
     *
     * @throws SQLException
     */
    List<Subject> read(Relation predicate, Object... objects) throws SQLException;

    /**
     * Answer the list of direct Objects that map to the supplied subjects. The query only considers objects with
     * assertions that match the subjects completely - i.e. {namespace, name, relation} and only the objects that have
     * the matching predicate
     *
     * @throws SQLException
     */
    List<Object> read(Relation predicate, Subject... subjects) throws SQLException;

    /**
     * Answer the list of direct Objects that map to the supplied subjects. The query only considers objects with
     * assertions that match the subjects completely - i.e. {namespace, name, relation}
     *
     * @throws SQLException
     */
    List<Object> read(Subject... subjects) throws SQLException;

    /**
     * Remove the mapping between the parent and the child objects
     *
     * @return the future returning the time value when this object mapping removal is committed
     */
    CompletableFuture<ULong> remove(Object parent, Object child);

    /**
     * Remove the mapping between the parent and the child relations
     *
     * @return the future returning the time value when this relation mapping removal is committed
     */
    CompletableFuture<ULong> remove(Relation parent, Relation child);

    /**
     * Remove the mapping between the parent and the child subjects
     *
     * @return the future returning the time value when this subject mapping removal is committed
     */
    CompletableFuture<ULong> remove(Subject parent, Subject child);

    /**
     * Answer the list of direct and transitive subjects that map to the object. These subjects may be further filtered
     * by the predicate Relation, if not null. The query only considers assertions that match the object completely -
     * i.e. {namespace, name, relation}
     *
     * @throws SQLException
     */
    Stream<Subject> subjects(Relation predicate, Object object) throws SQLException;

    record Asserted(ULong ts, boolean added) {
    }

    /** A Namespace **/
    record Namespace(String name) {

        /** Grounding for all the domains */
        public static final Namespace NO_NAMESPACE = new Namespace("");

        public Object object(String name, Relation relation) {
            return new Object(this, name, relation);
        }

        public Relation relation(String name) {
            return new Relation(this, name);
        }

        public Subject subject(String name) {
            return new Subject(this, name, Relation.NO_RELATION);
        }

        public Subject subject(String name, Relation relation) {
            return new Subject(this, name, relation);
        }
    }

    record NamespacedId(Long namespace, Long id, Long relation) {
    }

    /** A Subject **/
    record Subject(Namespace namespace, String name, Relation relation) {

        public static final Subject NO_SUBJECT = new Subject(Namespace.NO_NAMESPACE, "", Relation.NO_RELATION);

        public Assertion assertion(Object object) {
            return new Assertion(this, object);
        }

        @Override
        public String toString() {
            return namespace.name + ":" + name + (relation.equals(Relation.NO_RELATION) ? "" : "#" + relation);
        }
    }

    /** An Object **/
    record Object(Namespace namespace, String name, Relation relation) {

        public static final Object NO_OBJECT = new Object(Namespace.NO_NAMESPACE, "", Relation.NO_RELATION);

        public Assertion assertion(Subject subject) {
            return new Assertion(subject, this);
        }

        @Override
        public String toString() {
            return namespace.name + ":" + name + (relation.equals(Relation.NO_RELATION) ? "" : "#" + relation);
        }
    }

    /** A Relation **/
    record Relation(Namespace namespace, String name) {
        public static final Relation NO_RELATION = new Relation(Namespace.NO_NAMESPACE, "");

        @Override
        public String toString() {
            return namespace.name + ":" + name;
        }
    }

    /** An Assertion **/
    record Assertion(Subject subject, Object object) {
        public static final Assertion NO_ASSERTION = new Assertion(Subject.NO_SUBJECT, Object.NO_OBJECT);

        @Override
        public String toString() {
            return subject + "@" + object;
        }
    }

    // ==================== Watch API ====================

    /**
     * Register a listener for authorization change events. The listener will be called
     * for all mutations (assertions, mappings, deletions) that occur after registration.
     * <p>
     * This implements the Zanzibar Watch API pattern for cache invalidation and
     * secondary index maintenance.
     *
     * @param listener the callback to invoke on changes
     * @return UUID identifying the registration for later deregistration
     */
    default UUID watch(WatchListener listener) {
        throw new UnsupportedOperationException("Watch API not implemented");
    }

    /**
     * Deregister a previously registered watch listener.
     *
     * @param id the UUID returned from watch()
     * @return true if a listener was removed, false if not found
     */
    default boolean unwatch(UUID id) {
        throw new UnsupportedOperationException("Watch API not implemented");
    }

    /**
     * Callback interface for receiving authorization change events.
     * <p>
     * <b>IMPORTANT:</b> Implementations MUST NOT block or perform long-running operations.
     * Synchronous watch listeners are invoked on the mutation thread, so blocking
     * will delay all subsequent authorization changes. For potentially blocking
     * operations, use {@link #watchAsync(WatchListener, ExecutorService)} instead.
     * <p>
     * Exceptions thrown by implementations are caught and logged but do not
     * affect mutation processing.
     */
    @FunctionalInterface
    interface WatchListener {
        /**
         * Called when an authorization change occurs.
         *
         * @param event the change event
         */
        void onEvent(WatchEvent event);
    }

    /** Types of authorization changes */
    enum WatchEventType {
        /** An assertion was added */
        ASSERTION_ADD,
        /** An assertion was deleted (soft delete) */
        ASSERTION_DELETE,
        /** A subject mapping was added (group membership) */
        SUBJECT_MAP,
        /** A subject mapping was removed */
        SUBJECT_UNMAP,
        /** An object mapping was added (hierarchy) */
        OBJECT_MAP,
        /** An object mapping was removed */
        OBJECT_UNMAP,
        /** A relation mapping was added */
        RELATION_MAP,
        /** A relation mapping was removed */
        RELATION_UNMAP,
        /** A namespace was deleted */
        NAMESPACE_DELETE,
        /** An object was deleted */
        OBJECT_DELETE,
        /** A relation was deleted */
        RELATION_DELETE,
        /** A subject was deleted */
        SUBJECT_DELETE
    }

    /**
     * Event representing an authorization change. Used for cache invalidation
     * and secondary index maintenance per Zanzibar Watch API.
     *
     * @param type the type of change
     * @param timestamp when the change occurred
     * @param subject the subject involved (may be null depending on type)
     * @param object the object involved (may be null depending on type)
     * @param relation the relation involved (may be null depending on type)
     * @param namespace the namespace involved (may be null depending on type)
     */
    record WatchEvent(WatchEventType type, ULong timestamp, Subject subject, Object object, Relation relation,
                      Namespace namespace) {

        /** Create an assertion add event */
        public static WatchEvent assertionAdd(ULong ts, Assertion assertion) {
            return new WatchEvent(WatchEventType.ASSERTION_ADD, ts, assertion.subject(), assertion.object(), null,
                                  null);
        }

        /** Create an assertion delete event */
        public static WatchEvent assertionDelete(ULong ts, Assertion assertion) {
            return new WatchEvent(WatchEventType.ASSERTION_DELETE, ts, assertion.subject(), assertion.object(), null,
                                  null);
        }

        /** Create a subject mapping event */
        public static WatchEvent subjectMap(ULong ts, Subject parent, Subject child) {
            return new WatchEvent(WatchEventType.SUBJECT_MAP, ts, parent, null, null, null);
        }

        /** Create a subject unmap event */
        public static WatchEvent subjectUnmap(ULong ts, Subject parent, Subject child) {
            return new WatchEvent(WatchEventType.SUBJECT_UNMAP, ts, parent, null, null, null);
        }

        /** Create an object mapping event */
        public static WatchEvent objectMap(ULong ts, Object parent, Object child) {
            return new WatchEvent(WatchEventType.OBJECT_MAP, ts, null, parent, null, null);
        }

        /** Create an object unmap event */
        public static WatchEvent objectUnmap(ULong ts, Object parent, Object child) {
            return new WatchEvent(WatchEventType.OBJECT_UNMAP, ts, null, parent, null, null);
        }

        /** Create a relation mapping event */
        public static WatchEvent relationMap(ULong ts, Relation parent, Relation child) {
            return new WatchEvent(WatchEventType.RELATION_MAP, ts, null, null, parent, null);
        }

        /** Create a relation unmap event */
        public static WatchEvent relationUnmap(ULong ts, Relation parent, Relation child) {
            return new WatchEvent(WatchEventType.RELATION_UNMAP, ts, null, null, parent, null);
        }

        /** Create a namespace delete event */
        public static WatchEvent namespaceDelete(ULong ts, Namespace namespace) {
            return new WatchEvent(WatchEventType.NAMESPACE_DELETE, ts, null, null, null, namespace);
        }

        /** Create an object delete event */
        public static WatchEvent objectDelete(ULong ts, Object object) {
            return new WatchEvent(WatchEventType.OBJECT_DELETE, ts, null, object, null, null);
        }

        /** Create a relation delete event */
        public static WatchEvent relationDelete(ULong ts, Relation relation) {
            return new WatchEvent(WatchEventType.RELATION_DELETE, ts, null, null, relation, null);
        }

        /** Create a subject delete event */
        public static WatchEvent subjectDelete(ULong ts, Subject subject) {
            return new WatchEvent(WatchEventType.SUBJECT_DELETE, ts, subject, null, null, null);
        }
    }

}
