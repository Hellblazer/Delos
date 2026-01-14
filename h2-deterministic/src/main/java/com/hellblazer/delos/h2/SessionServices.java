package com.hellblazer.delos.h2;

/**
 * Service registry for custom operations callable from SQL stored procedures and triggers.
 * <p>
 * <strong>CRITICAL BYZANTINE REQUIREMENT:</strong> All service implementations MUST be deterministic
 * pure functions to prevent state machine divergence in replicated consensus systems.
 * <p>
 * <strong>Determinism Requirements:</strong>
 * <ul>
 *   <li><strong>Pure Functions:</strong> Same inputs MUST produce identical outputs across all replicas</li>
 *   <li><strong>No Side Effects:</strong> Services MUST NOT modify external state (files, network, databases)</li>
 *   <li><strong>No Non-Deterministic APIs:</strong> See blacklist below</li>
 *   <li><strong>Thread-Safe:</strong> Services may be called concurrently by multiple sessions</li>
 * </ul>
 * <p>
 * <strong>Blacklisted APIs (Non-Deterministic):</strong>
 * <ul>
 *   <li><strong>Wall-Clock Time:</strong> System.currentTimeMillis(), System.nanoTime(), Instant.now(), etc.
 *       <br>→ Use BlockClock via application layer (block height + transaction index)</li>
 *   <li><strong>Random:</strong> Math.random(), new Random(), UUID.randomUUID()
 *       <br>→ Use SecureRandom with deterministic seed (via application layer)</li>
 *   <li><strong>Thread/Process IDs:</strong> Thread.currentThread().getId(), ProcessHandle.current().pid()
 *       <br>→ Replicas have different thread/process IDs</li>
 *   <li><strong>Network I/O:</strong> Socket, HttpClient, URL.openConnection()
 *       <br>→ Network latency and failures differ per replica</li>
 *   <li><strong>File I/O:</strong> File reads/writes, Path operations
 *       <br>→ File system state differs per replica</li>
 *   <li><strong>Environment:</strong> System.getenv(), System.getProperty() (except allowed JVM properties)
 *       <br>→ Environment variables differ per replica</li>
 *   <li><strong>Object Identity:</strong> Object.hashCode(), System.identityHashCode()
 *       <br>→ Memory layout differs per JVM instance</li>
 *   <li><strong>Floating Point Non-Associativity:</strong> Avoid parallel reduction unless order guaranteed
 *       <br>→ FP arithmetic is not associative: (a+b)+c ≠ a+(b+c) in general</li>
 * </ul>
 * <p>
 * <strong>Safe Patterns:</strong>
 * <ul>
 *   <li>Deterministic computations (math, string operations, data transformations)</li>
 *   <li>Accessing SQL session state (connection metadata, transaction isolation level)</li>
 *   <li>Cryptographic operations with fixed seeds</li>
 *   <li>Deterministic data structure operations (sorted maps/sets, ordered iteration)</li>
 * </ul>
 * <p>
 * <strong>Future Enforcement:</strong> Implementations should be annotated with {@code @DeterministicService}
 * (not yet implemented). Runtime validation will prevent blacklisted API calls using bytecode analysis
 * similar to UDF sandboxing (see h2-deterministic/RISKS.md RISK-004).
 * <p>
 * <strong>Validation Strategy:</strong>
 * <ol>
 *   <li><strong>Static Analysis:</strong> Bytecode inspection for blacklisted API calls</li>
 *   <li><strong>Runtime Monitoring:</strong> SecurityManager-like hooks (when available post-Java 21)</li>
 *   <li><strong>Testing:</strong> Multi-replica consensus tests with service calls (see MultiReplicaSqlConsensusTest)</li>
 * </ol>
 * <p>
 * <strong>Failure Mode:</strong> If a service is non-deterministic:
 * <ul>
 *   <li>Replicas compute different results for same SQL</li>
 *   <li>State machine divergence occurs (different database states)</li>
 *   <li>Consensus fails (replicas cannot agree on next block)</li>
 *   <li>Byzantine fault tolerance violated (minority can cause split-brain)</li>
 * </ul>
 * <p>
 * <strong>Example (FORBIDDEN - Non-Deterministic):</strong>
 * <pre>
 * public class TimestampService implements SessionServices {
 *     public &lt;T&gt; T call(String serviceName, Object... parameters) {
 *         if ("getCurrentTimestamp".equals(serviceName)) {
 *             return (T) Instant.now();  // ❌ FORBIDDEN: Wall-clock time
 *         }
 *         throw new ServiceNotFoundException(serviceName);
 *     }
 * }
 * // Result: Each replica sees different timestamp, state diverges
 * </pre>
 * <p>
 * <strong>Example (CORRECT - Deterministic):</strong>
 * <pre>
 * public class CryptoService implements SessionServices {
 *     public &lt;T&gt; T call(String serviceName, Object... parameters) {
 *         if ("sha256".equals(serviceName)) {
 *             byte[] data = (byte[]) parameters[0];
 *             return (T) MessageDigest.getInstance("SHA-256").digest(data);  // ✅ Deterministic
 *         }
 *         throw new ServiceNotFoundException(serviceName);
 *     }
 * }
 * // Result: All replicas compute identical hash for same input
 * </pre>
 * <p>
 * Related: Delos-fnb3 (SessionServices determinism requirements), RISK-004 (UDF sandboxing)
 *
 * @see SqlStateMachine#begin(ULong, Digest) BlockClock seeding for deterministic time
 * @see FunctionWhitelist Function enforcement for SQL-level determinism
 */
public interface SessionServices {
    SessionServices NO_SERVICES = new SessionServices() {
        public <T> T call(String serviceName, Object... parameters) throws ServiceNotFoundException {
            throw new ServiceNotFoundException(serviceName);
        }

        @Override
        public void run(String serviceName, Object... parameters) throws ServiceNotFoundException {
            throw new ServiceNotFoundException(serviceName);
        }
    };

    /**
     * Invoke a service and return its result.
     * <p>
     * <strong>CRITICAL:</strong> Implementation MUST be deterministic (same inputs → same output).
     * See class-level javadoc for blacklisted APIs and requirements.
     *
     * @param serviceName Name of the service to invoke
     * @param parameters Service parameters (must be deterministic types)
     * @param <T> Return type
     * @return Service result (must be deterministic)
     * @throws ServiceNotFoundException If service not registered
     */
    <T> T call(String serviceName, Object... parameters) throws ServiceNotFoundException;

    /**
     * Execute a service without returning a result (side-effect-free operation).
     * <p>
     * <strong>CRITICAL:</strong> Implementation MUST be deterministic and have NO side effects.
     * This includes no file I/O, network I/O, or external state modification.
     * See class-level javadoc for blacklisted APIs and requirements.
     *
     * @param serviceName Name of the service to execute
     * @param parameters Service parameters (must be deterministic types)
     * @throws ServiceNotFoundException If service not registered
     */
    void run(String serviceName, Object... parameters) throws ServiceNotFoundException;

    class ServiceNotFoundException extends Exception {
        public ServiceNotFoundException(String serviceName) {
            super(serviceName);
        }
    }
}
