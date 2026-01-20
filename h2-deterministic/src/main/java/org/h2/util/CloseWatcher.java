/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package org.h2.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * A phantom reference to watch for unclosed objects.
 */
public class CloseWatcher extends PhantomReference<Object> {

    /**
     * The queue (might be set to null at any time).
     */
    private static final ReferenceQueue<Object> queue = new ReferenceQueue<>();

    /**
     * The reference set. Must keep it, otherwise the references are garbage
     * collected first and thus never enqueued.
     */
    private static final Set<CloseWatcher> refs = Collections.synchronizedSet(new HashSet<>());

    /**
     * The stack trace of when the object was created. It is converted to a
     * string early on to avoid classloader problems (a classloader can't be
     * garbage collected if there is a static reference to one of its classes).
     */
    private String openStackTrace;

    /**
     * The closeable object.
     */
    private AutoCloseable closeable;

    public CloseWatcher(Object referent, ReferenceQueue<Object> q,
            AutoCloseable closeable) {
        super(referent, q);
        this.closeable = closeable;
    }

    /**
     * Check for an collected object.
     * <p>
     * Polls the reference queue for CloseWatcher instances whose referents have been
     * garbage collected. If a watcher is found and its closeable is still non-null,
     * it indicates a resource leak (object was garbage collected without being closed).
     *
     * @return the first watcher with an unclosed resource, or null if none found
     */
    public static CloseWatcher pollUnclosed() {
        while (true) {
            CloseWatcher cw = (CloseWatcher) queue.poll();
            if (cw == null) {
                return null;
            }
            // Remove from tracking set. refs is final and non-null, so no null check needed.
            refs.remove(cw);
            if (cw.closeable != null) {
                return cw;
            }
        }
    }

    /**
     * Register an object. Before calling this method, pollUnclosed() should be
     * called in a loop to remove old references.
     *
     * @param o the object
     * @param closeable the object to close
     * @param stackTrace whether the stack trace should be registered (this is
     *            relatively slow)
     * @return the close watcher
     */
    public static CloseWatcher register(Object o, AutoCloseable closeable, boolean stackTrace) {
        CloseWatcher cw = new CloseWatcher(o, queue, closeable);
        if (stackTrace) {
            Exception e = new Exception("Open Stack Trace");
            StringWriter s = new StringWriter();
            e.printStackTrace(new PrintWriter(s));
            cw.openStackTrace = s.toString();
        }
        refs.add(cw);
        return cw;
    }

    /**
     * Unregister an object, so it is no longer tracked.
     *
     * @param w the reference
     */
    public static void unregister(CloseWatcher w) {
        if (w == null) {
            return;
        }
        w.closeable = null;
        refs.remove(w);
    }

    /**
     * Get the open stack trace or null if none.
     *
     * @return the open stack trace
     */
    public String getOpenStackTrace() {
        return openStackTrace;
    }

    public AutoCloseable getCloseable() {
        return closeable;
    }

}
