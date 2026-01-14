package com.hellblazer.delos.state;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.regex.Pattern;

/**
 * Normalizes exception messages and stack traces for deterministic error handling in Byzantine
 * fault-tolerant replicated state machines.
 * <p>
 * <strong>CRITICAL BYZANTINE REQUIREMENT:</strong> Exception messages that are part of
 * transaction results MUST be deterministic across all replicas. Non-deterministic content
 * in exceptions causes state divergence and consensus failure.
 * <p>
 * <strong>Sources of Non-Determinism in Exceptions:</strong>
 * <ul>
 *   <li><strong>Thread IDs:</strong> Stack traces include thread names like "Thread-123"</li>
 *   <li><strong>Memory Addresses:</strong> Object.toString() includes "@3e25a5"</li>
 *   <li><strong>Timestamps:</strong> Logged timestamps vary by wall-clock time</li>
 *   <li><strong>File Paths:</strong> Absolute paths differ per replica (/home/user1 vs /home/user2)</li>
 *   <li><strong>Object Identity:</strong> System.identityHashCode() varies per JVM instance</li>
 * </ul>
 * <p>
 * <strong>Normalization Strategy:</strong>
 * <ol>
 *   <li>Strip thread IDs from stack trace elements</li>
 *   <li>Strip memory addresses from Object.toString() output</li>
 *   <li>Strip timestamps from log messages embedded in exceptions</li>
 *   <li>Normalize file paths to relative paths</li>
 *   <li>Preserve exception class names, messages (if deterministic), and line numbers</li>
 * </ol>
 * <p>
 * <strong>Byzantine Failure Scenario (Without Normalization):</strong>
 * <pre>
 * Replica A (Thread-12, clock skew +10ms):
 *   SQLException: Constraint violation at 2024-01-01T10:00:00.123
 *   Stack trace: Thread-12
 *
 * Replica B (Thread-45, clock skew -5ms):
 *   SQLException: Constraint violation at 2024-01-01T09:59:59.995
 *   Stack trace: Thread-45
 *
 * Result: Different exception strings, state divergence, consensus failure
 * </pre>
 * <p>
 * <strong>After Normalization:</strong>
 * <pre>
 * Both replicas return:
 *   SQLException: Constraint violation
 *   Stack trace: [normalized, no thread IDs or timestamps]
 *
 * Result: Identical exception strings, consensus maintained
 * </pre>
 * <p>
 * Related: SqlStateMachine.execute() (error handling), Delos-jbsj (normalization task)
 */
public class ExceptionNormalizer {

    /**
     * Pattern to match thread IDs in stack traces.
     * Examples: "Thread-12", "pool-3-thread-5", "ForkJoinPool-1-worker-3"
     */
    private static final Pattern THREAD_ID_PATTERN = Pattern.compile(
        "\\b(Thread-|pool-\\d+-thread-|ForkJoinPool-\\d+-worker-|main-|http-)\\d+\\b"
    );

    /**
     * Pattern to match memory addresses from Object.toString().
     * Examples: "@3e25a5", "@1a2b3c4d"
     */
    private static final Pattern MEMORY_ADDRESS_PATTERN = Pattern.compile("@[0-9a-fA-F]+\\b");

    /**
     * Pattern to match ISO 8601 timestamps.
     * Examples: "2024-01-01T10:00:00.123Z", "2024-01-01T10:00:00+00:00"
     */
    private static final Pattern ISO_TIMESTAMP_PATTERN = Pattern.compile(
        "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{3})?(Z|[+-]\\d{2}:\\d{2})?"
    );

    /**
     * Pattern to match epoch millisecond timestamps (13-digit numbers).
     * Examples: "1704067200000" in messages
     */
    private static final Pattern EPOCH_MILLIS_PATTERN = Pattern.compile("\\b1[0-9]{12}\\b");

    /**
     * Pattern to match absolute file paths (Unix and Windows).
     * Examples: "/home/user/data", "C:\\Users\\user\\data"
     */
    private static final Pattern ABS_PATH_PATTERN = Pattern.compile(
        "(/[a-zA-Z0-9_./\\-]+/|[A-Z]:\\\\[a-zA-Z0-9_\\\\./\\-]+\\\\)"
    );

    /**
     * Normalizes an exception by stripping non-deterministic content from its message and stack trace.
     * <p>
     * <strong>What is normalized:</strong>
     * <ul>
     *   <li>Thread IDs → replaced with "Thread-*"</li>
     *   <li>Memory addresses → replaced with "@*"</li>
     *   <li>Timestamps (ISO 8601 and epoch) → replaced with "[timestamp]"</li>
     *   <li>Absolute file paths → replaced with "[path]"</li>
     * </ul>
     * <p>
     * <strong>What is preserved:</strong>
     * <ul>
     *   <li>Exception class name</li>
     *   <li>Core message text (after normalization)</li>
     *   <li>Stack trace structure (class names, method names, line numbers)</li>
     * </ul>
     *
     * @param t Original throwable
     * @return Normalized throwable with deterministic message and stack trace
     */
    public static Throwable normalize(Throwable t) {
        if (t == null) {
            return null;
        }

        // Normalize the message
        String normalizedMessage = normalizeMessage(t.getMessage());

        // Create new exception with normalized message
        Throwable normalized;
        try {
            // Try to create instance of same exception class with normalized message
            normalized = t.getClass()
                          .getConstructor(String.class)
                          .newInstance(normalizedMessage);
        } catch (Exception e) {
            // If we can't create instance, wrap in RuntimeException
            normalized = new RuntimeException(normalizedMessage);
        }

        // Normalize and set stack trace
        StackTraceElement[] originalTrace = t.getStackTrace();
        StackTraceElement[] normalizedTrace = new StackTraceElement[originalTrace.length];
        for (int i = 0; i < originalTrace.length; i++) {
            StackTraceElement elem = originalTrace[i];
            // Keep class name, method name, file name, line number
            // These are deterministic (same code on all replicas)
            normalizedTrace[i] = new StackTraceElement(
                elem.getClassName(),
                elem.getMethodName(),
                elem.getFileName(),
                elem.getLineNumber()
            );
        }
        normalized.setStackTrace(normalizedTrace);

        // Recursively normalize cause
        if (t.getCause() != null) {
            normalized.initCause(normalize(t.getCause()));
        }

        // Recursively normalize suppressed exceptions
        for (Throwable suppressed : t.getSuppressed()) {
            normalized.addSuppressed(normalize(suppressed));
        }

        return normalized;
    }

    /**
     * Normalizes an exception message by stripping non-deterministic content.
     * <p>
     * Applies all normalization patterns to the message string.
     *
     * @param message Original exception message
     * @return Normalized message with non-deterministic content replaced
     */
    public static String normalizeMessage(String message) {
        if (message == null) {
            return null;
        }

        String normalized = message;

        // Strip thread IDs
        normalized = THREAD_ID_PATTERN.matcher(normalized).replaceAll("Thread-*");

        // Strip memory addresses
        normalized = MEMORY_ADDRESS_PATTERN.matcher(normalized).replaceAll("@*");

        // Strip timestamps
        normalized = ISO_TIMESTAMP_PATTERN.matcher(normalized).replaceAll("[timestamp]");
        normalized = EPOCH_MILLIS_PATTERN.matcher(normalized).replaceAll("[timestamp]");

        // Strip absolute paths
        normalized = ABS_PATH_PATTERN.matcher(normalized).replaceAll("[path]");

        return normalized;
    }

    /**
     * Converts a throwable to a normalized stack trace string.
     * <p>
     * Useful for logging or comparing exception output across replicas.
     *
     * @param t Throwable to convert
     * @return Normalized stack trace as string
     */
    public static String toNormalizedStackTrace(Throwable t) {
        if (t == null) {
            return null;
        }

        Throwable normalized = normalize(t);
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        normalized.printStackTrace(pw);
        return sw.toString();
    }
}
