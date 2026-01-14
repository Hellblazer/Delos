package liquibase.changelog;

import liquibase.Scope;
import liquibase.database.Database;
import liquibase.exception.UnexpectedLiquibaseException;
import liquibase.servicelocator.ServiceLocator;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Factory for retrieving ChangeLogHistoryService implementations.
 * <p>
 * <strong>ThreadLocal Singleton Pattern:</strong> This class uses ThreadLocal for per-thread isolation,
 * allowing each thread to have its own independent factory instance. This is necessary for Liquibase's
 * internal architecture but has implications for deterministic execution.
 * <p>
 * <strong>CRITICAL DETERMINISM REQUIREMENT:</strong> Schema migrations MUST execute on a single thread
 * to ensure deterministic behavior across Byzantine replicas:
 * <ul>
 *   <li><strong>Single-Threaded Execution:</strong> All Liquibase operations MUST occur on the same thread
 *       within a transaction to ensure this factory returns the same instance</li>
 *   <li><strong>No Concurrent Migrations:</strong> Multiple threads executing migrations concurrently would
 *       get different factory instances, potentially leading to service selection divergence</li>
 *   <li><strong>Service Registration Order:</strong> Services are registered at front of CopyOnWriteArrayList
 *       (line 49). Concurrent registration could produce non-deterministic order</li>
 * </ul>
 * <p>
 * <strong>Byzantine Fault Tolerance Considerations:</strong>
 * <ul>
 *   <li><strong>Single-Threaded Guarantee:</strong> SqlStateMachine executes migrations via single-threaded
 *       executor. This ensures all replicas use the same thread and thus the same factory instance</li>
 *   <li><strong>Service Selection Determinism:</strong> Service selection uses TreeSet with priority comparator
 *       (line 56-61). This ensures all replicas select services in identical order by priority</li>
 *   <li><strong>Service Cache:</strong> The services map (line 18) uses ConcurrentHashMap for thread-safe
 *       caching, but is thread-local so each thread has independent cache</li>
 * </ul>
 * <p>
 * <strong>Thread-Safety Assumptions:</strong>
 * <ul>
 *   <li>Factory instance uses CopyOnWriteArrayList (thread-safe reads, synchronized writes)</li>
 *   <li>Service cache uses ConcurrentHashMap (thread-safe)</li>
 *   <li>Multiple threads CAN safely call getInstance() (each gets own instance via ThreadLocal)</li>
 *   <li>Service registration (register) is thread-safe within same factory instance</li>
 *   <li>Service retrieval (getChangeLogService) is thread-safe once services registered</li>
 * </ul>
 * <p>
 * <strong>Service Instantiation:</strong> getChangeLogService() attempts to instantiate a new service
 * instance per database (line 80). If no default constructor exists, falls back to the registered
 * prototype instance (line 84). This can cause issues if service maintains mutable state.
 * <p>
 * <strong>Testing:</strong> Multi-replica tests must verify:
 * <ul>
 *   <li>All replicas execute migrations on same logical thread (per-replica)</li>
 *   <li>Service selection order is identical across replicas</li>
 *   <li>Change log history entries are byte-for-byte identical for same input</li>
 * </ul>
 * <p>
 * Related: Delos-c3b9 (Liquibase ThreadLocal factory usage), Delos-iaks (Migration determinism tests)
 *
 * @see SqlGeneratorFactory Similar ThreadLocal pattern for SQL generators
 * @see SqlStateMachine#acceptMigration Single-threaded migration execution
 */
public class ChangeLogHistoryServiceFactory {

    /**
     * Thread-local singleton instance.
     * <p>
     * CRITICAL: Each thread gets its own factory instance. Schema migrations MUST execute
     * on a single thread to ensure deterministic behavior (same factory instance used throughout).
     */
    private final static ThreadLocal<ChangeLogHistoryServiceFactory> instance = new ThreadLocal<>();

    private List<ChangeLogHistoryService> registry = new CopyOnWriteArrayList<>();

    private Map<Database, ChangeLogHistoryService> services = new ConcurrentHashMap<>();

    public static synchronized ChangeLogHistoryServiceFactory getInstance() {
        if (instance.get() == null) {
            instance.set(new ChangeLogHistoryServiceFactory());
        }
        return instance.get();
    }

    /**
     * Set the instance used by this singleton. Used primarily for testing.
     */
    public static synchronized void setInstance(ChangeLogHistoryServiceFactory changeLogHistoryServiceFactory) {
        ChangeLogHistoryServiceFactory.instance.set(changeLogHistoryServiceFactory);
    }

    public static synchronized void reset() {
        instance.set(null);
    }

    private ChangeLogHistoryServiceFactory() {
        try {
            for (ChangeLogHistoryService service : Scope.getCurrentScope().getServiceLocator().findInstances(ChangeLogHistoryService.class)) {
                register(service);
            }
        } catch (Exception e) {
            throw new UnexpectedLiquibaseException(e);
        }
    }

    public void register(ChangeLogHistoryService changeLogHistoryService) {
        registry.add(0, changeLogHistoryService);
    }

    public ChangeLogHistoryService getChangeLogService(Database database) {
            if (services.containsKey(database)) {
                return services.get(database);
            }
            SortedSet<ChangeLogHistoryService> foundServices = new TreeSet<>(new Comparator<ChangeLogHistoryService>() {
                @Override
                public int compare(ChangeLogHistoryService o1, ChangeLogHistoryService o2) {
                    return -1 * Integer.valueOf(o1.getPriority()).compareTo(o2.getPriority());
                }
            });

            for (ChangeLogHistoryService service : registry) {
                if (service.supports(database)) {
                    foundServices.add(service);
                }
            }

            if (foundServices.isEmpty()) {
                throw new UnexpectedLiquibaseException("Cannot find ChangeLogHistoryService for " +
                    database.getShortName());
            }

            try {
                ChangeLogHistoryService exampleService = foundServices.iterator().next();
                Class<? extends ChangeLogHistoryService> aClass = exampleService.getClass();
                ChangeLogHistoryService service;
                try {
                    aClass.getConstructor();
                    service = aClass.getConstructor().newInstance();
                    service.setDatabase(database);
                } catch (NoSuchMethodException e) {
                    // must have been manually added to the registry and so already configured.
                    service = exampleService;
                }

                services.put(database, service);
                return service;
            } catch (Exception e) {
                throw new UnexpectedLiquibaseException(e);
            }
    }

    public void resetAll() {
        synchronized (ChangeLogHistoryServiceFactory.class) {
            for (ChangeLogHistoryService changeLogHistoryService : registry) {
                changeLogHistoryService.reset();
            }
            instance.set(null);
        }
    }
}

