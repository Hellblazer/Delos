/*
 * Copyright (c) 2013 ChiralBehaviors LLC, all rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.chiralbehaviors.tron;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A Finite State Machine implementation.
 *
 * @param <Transitions> the transition interface
 * @param <Context>     the fsm context interface
 * @author hhildebrand
 */
public final class Fsm<Context, Transitions> {
    private static final Logger                             DEFAULT_LOG = LoggerFactory.getLogger(Fsm.class);
    private static final ThreadLocal<Fsm<?, ?>>             thisFsm     = new ThreadLocal<>();

    // Method reflection caches - keyed by state class for O(1) lookup
    private static final ConcurrentHashMap<Class<?>, Method> ENTRY_ACTION_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Method> EXIT_ACTION_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Method> DEFAULT_TRANSITION_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Method> TRANSITION_METHOD_CACHE = new ConcurrentHashMap<>();

    // Maximum hierarchical state stack depth to prevent unbounded growth
    private static final int                                 MAX_STACK_DEPTH = 16;

    private final        Transitions                        proxy;
    private final        Deque<State<Context, Transitions>> stack       = new ArrayDeque<>();
    private final        Lock                               sync;
    private final        Class<Transitions>                 transitionsType;
    private volatile     Context                            context;
    private              Transitions                        current;
    private              Logger                             log;
    private              String                             name        = "";
    private              boolean                            pendingPop  = false;
    private              State<Context, Transitions>        pendingPush;
    private              PendingTransition                  popTransition;
    private              Transitions                        previous;
    private              PendingTransition                  pushTransition;
    private              String                             transition;

    Fsm(Context context, boolean sync, Class<Transitions> transitionsType, ClassLoader transitionsCL) {
        this.setContext(context);
        this.sync = sync ? new ReentrantLock() : null;
        this.transitionsType = transitionsType;
        this.log = DEFAULT_LOG;
        @SuppressWarnings("unchecked")
        Transitions facade = (Transitions) Proxy.newProxyInstance(transitionsCL, new Class<?>[] { transitionsType },
                                                                  transitionsHandler());
        proxy = facade;
    }

    /**
     * Construct a new instance of a finite state machine.
     *
     * @param fsmContext    - the object used as the action context for this FSM
     * @param transitions   - the interface class used to define the transitions for this FSM
     * @param transitionsCL - the class loader to be used to load the transitions interface class
     * @param initialState  - the initial state of the FSM
     * @param sync          - true if this FSM is to synchronize state transitions. This is required for multi-threaded
     *                      use of the FSM
     * @return the Fsm instance
     */
    public static <Context, Transitions> Fsm<Context, Transitions> construct(Context fsmContext,
                                                                             Class<Transitions> transitions,
                                                                             ClassLoader transitionsCL,
                                                                             Enum<?> initialState, boolean sync) {
        if (!transitions.isAssignableFrom(initialState.getClass())) {
            throw new IllegalArgumentException(
            String.format("Supplied initial state '%s' does not implement the transitions interface '%s'", initialState,
                          transitions));
        }
        Fsm<Context, Transitions> fsm = new Fsm<>(fsmContext, sync, transitions, transitionsCL);
        @SuppressWarnings("unchecked")
        Transitions initial = (Transitions) initialState;
        fsm.current = initial;
        return fsm;
    }

    /**
     * Construct a new instance of a finite state machine with a default ClassLoader.
     */
    public static <Context, Transitions> Fsm<Context, Transitions> construct(Context fsmContext,
                                                                             Class<Transitions> transitions,
                                                                             Enum<?> initialState, boolean sync) {
        return construct(fsmContext, transitions, fsmContext.getClass().getClassLoader(), initialState, sync);
    }

    /**
     * @return the Context of the currently executing Fsm
     */
    public static <Context> Context thisContext() {
        @SuppressWarnings("unchecked")
        Fsm<Context, ?> fsm = (Fsm<Context, ?>) thisFsm.get();
        return fsm.getContext();
    }

    /**
     * @return the currrently executing Fsm
     */
    public static <Context, Transitions> Fsm<Context, Transitions> thisFsm() {
        @SuppressWarnings("unchecked")
        Fsm<Context, Transitions> fsm = (Fsm<Context, Transitions>) thisFsm.get();
        return fsm;
    }

    /**
     * Execute the initial state's entry action. Note that we do not guard against multiple invocations.
     */
    public void enterStartState() {
        if (log.isTraceEnabled()) {
            log.trace(String.format("[%s] Entering start state %s", name, prettyPrint(current)));
        }
        executeEntryAction();
    }

    /**
     * @return the action context object of this Fsm
     */
    public Context getContext() {
        return locked(() -> context);
    }

    /**
     * Set the Context of the FSM
     */
    public void setContext(Context context) {
        locked(() -> {
            this.context = context;
            return null;
        });
    }

    /**
     * @return the current state of the Fsm
     */
    public Transitions getCurrentState() {
        return locked(() -> {
            Transitions transitions = current;
            return transitions;
        });
    }

    /**
     * @return the logger used by this Fsm
     */
    public Logger getLog() {
        return locked(() -> {
            Logger c = log;
            return c;
        });
    }

    /**
     * Set the Logger for this Fsm.
     *
     * @param log - the Logger of this Fsm
     */
    public void setLog(Logger log) {
        this.log = log;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * @return the previous state of the Fsm, or null if no previous state
     */
    public Transitions getPreviousState() {
        return locked(() -> {
            Transitions transitions = previous;
            return transitions;
        });
    }

    /**
     * @return the String representation of the current transition
     */
    public String getTransition() {
        return locked(() -> {
            return transition;
        });
    }

    /**
     * @return the Transitions object that drives this Fsm through its transitions
     */
    public Transitions getTransitions() {
        return proxy;
    }

    /**
     * @return the invalid transition excepiton based current transition attempt
     */
    public InvalidTransition invalidTransitionOn() {
        return new InvalidTransition(String.format("[%s] %s.%s", name, prettyPrint(current), transition));
    }

    /**
     * Pop the current state and restore the previously pushed state from the stack.
     *
     * <h2>Execution Semantics</h2>
     * <ol>
     *   <li>The current state's exit action executes (if defined)
     *   <li>The state is removed from the stack and becomes current again with its saved context
     *   <li>The restored state's entry action does NOT execute (it was never exited, only suspended by push)
     *   <li>Optional pending transition can be executed on the restored state
     * </ol>
     *
     * <h2>Stack Restoration</h2>
     * Pop restores the complete state that was saved by the most recent push():
     * <ul>
     *   <li>Restores both the state and its associated context
     *   <li>Complements the push() operation to implement hierarchical state entry/exit
     *   <li>Throws IllegalStateException if stack is empty (nothing to pop)
     * </ul>
     *
     * <h2>Entry/Exit Action Semantics</h2>
     * The restoration pattern maintains symmetry with suspension:
     * <ul>
     *   <li><strong>On push</strong>: Current state is suspended (no exit action), new state entered
     *   <li><strong>On pop</strong>: Current state is exited, previous state restored (no entry action)
     * </ul>
     * This ensures that push/pop pairs maintain consistent semantics: a state that was suspended without
     * exiting will be restored without re-entering, preserving its internal state exactly.
     *
     * <h2>Pending Transitions</h2>
     * After calling pop(), you can optionally fire a transition on the restored state:
     * <pre>
     *   // Pop from subprocess back to parent
     *   var restored = fsm.pop();
     *
     *   // Optionally fire a transition on the restored state
     *   restored.continueProcessing();
     *
     *   // Or just return control
     *   return null;
     * </pre>
     *
     * <h2>Constraints</h2>
     * <ul>
     *   <li>Stack must not be empty (throws IllegalStateException if empty)
     *   <li>Cannot pop if another pop is already pending (throws IllegalStateException)
     *   <li>Cannot pop if a push is already pending (throws IllegalStateException)
     *   <li>Do NOT call pop() from within entry/exit actions (causes pending transitions to elide,
     *       meaning they will be skipped/cancelled rather than executed)
     * </ul>
     *
     * @return the Transitions proxy for firing an optional pending transition on the popped state
     * @throws IllegalStateException if stack is empty, push is pending, or pop already pending
     */
    public Transitions pop() {
        if (pendingPop) {
            throw new IllegalStateException(String.format("[%s] State has already been popped", name));
        }
        if (pendingPush != null) {
            throw new IllegalStateException(String.format("[%s] Cannot pop after pushing", name));
        }
        if (stack.size() == 0) {
            throw new IllegalStateException(
            String.format("[%s] State stack is empty, current state: %s, transition: %s", name, prettyPrint(current),
                          transition));
        }
        pendingPop = true;
        popTransition = new PendingTransition();
        @SuppressWarnings("unchecked")
        Transitions pendingTransition = (Transitions) Proxy.newProxyInstance(getContext().getClass().getClassLoader(),
                                                                             new Class<?>[] { transitionsType },
                                                                             popTransition);
        return pendingTransition;
    }

    public String prettyPrint(Transitions state) {
        if (state == null) {
            return "null";
        }
        Class<?> enclosingClass = state.getClass().getEnclosingClass();
        return String.format("%s.%s", (enclosingClass != null ? enclosingClass : state.getClass()).getSimpleName(),
                             ((Enum<?>) state).name());
    }

    /**
     * Push the current state onto the stack and transition to a new state.
     *
     * This is a convenience method that uses the current context. Use {@link #push(Transitions, Object)} to provide
     * a new context for the pushed state.
     *
     * @param state the new current state of the Fsm
     * @return the Transitions proxy for firing an optional pending transition on the new state
     * @throws IllegalStateException if stack depth would exceed limit or push already pending
     * @see #push(Transitions, Object)
     */
    public Transitions push(Transitions state) {
        return push(state, context);
    }

    /**
     * Push the current state onto the stack and transition to a new state with a new context.
     *
     * <h2>Execution Semantics</h2>
     * <ol>
     *   <li>The current state is <strong>saved to the stack unchanged</strong> (current exit action NOT executed,
     *       because we're not exiting the state - we're saving it for later restoration)
     *   <li>The supplied state becomes the current state with the new context
     *   <li>The new state's entry action executes (if defined)
     *   <li>Optional pending transition can be executed on the new state
     * </ol>
     *
     * <h2>Why Exit Action is NOT Executed on Push</h2>
     * Push represents a hierarchical state entry (like a subroutine call), not a state exit.
     * The current state is suspended and saved, but not exited. Compare with transitions:
     * <ul>
     *   <li><strong>Normal transition</strong>: StateA.exit() → state changes → StateB.entry()
     *   <li><strong>Push</strong>: StateA → stack, StateB.entry() (no StateA.exit())
     *   <li><strong>Pop</strong>: StateB.exit() → StateA restored from stack (no StateA.entry())
     * </ul>
     *
     * <h2>Stack Semantics</h2>
     * The FSM maintains a stack of saved states for hierarchical (nested) state machines. When you push a state:
     * <ul>
     *   <li>The previous current state is saved on the stack (as a complete State object with context)
     *   <li>The new state becomes the current state with the supplied context
     *   <li>Later calling pop() will restore the previous state and context, executing its exit action
     *   <li>Stack depth is limited to {@value #MAX_STACK_DEPTH} (= 16) to prevent unbounded growth
     *       during deeply nested state hierarchies. This limit accommodates most practical hierarchical
     *       FSM designs while preventing stack overflow from programming errors or infinite recursion.
     * </ul>
     *
     * <h2>Pending Transitions</h2>
     * After calling push(), you can optionally fire a transition on the new state:
     * <pre>
     *   // Push to subprocess state with new context
     *   var subprocess = fsm.push(States.SUBPROCESS, subprocContext);
     *
     *   // Optionally fire a transition on the new state
     *   subprocess.start();
     *
     *   // Or just exit without further transition
     *   return null;
     * </pre>
     *
     * <h2>Constraints</h2>
     * <ul>
     *   <li>Cannot push a null state (throws IllegalStateException)
     *   <li>Cannot push while another push is already pending (throws IllegalStateException)
     *   <li>Cannot push after a pop is already pending (throws IllegalStateException)
     *   <li>Stack depth must not exceed {@value #MAX_STACK_DEPTH} (throws IllegalStateException)
     *   <li>Do NOT call push() from within entry/exit actions (causes pending transitions to elide,
     *       meaning they will be skipped/cancelled rather than executed). This prevents transition
     *       execution loops and maintains predictable execution order.
     * </ul>
     *
     * @param state   the new current state of the Fsm
     * @param context the new current context of the FSM (for the pushed state)
     * @return the Transitions proxy for firing an optional pending transition on the new state
     * @throws IllegalStateException if state is null, another push is pending, or stack depth exceeded
     */
    public Transitions push(Transitions state, Context context) {
        if (state == null) {
            throw new IllegalStateException(String.format("[%s] Cannot push a null state", name));
        }
        if (pendingPush != null) {
            throw new IllegalStateException(String.format("[%s] Cannot push state twice", name));
        }
        if (pendingPop) {
            throw new IllegalStateException(String.format("[%s] Cannot push after pop", name));
        }
        if (stack.size() >= MAX_STACK_DEPTH) {
            throw new IllegalStateException(
            String.format("[%s] Stack overflow: depth %d exceeds maximum %d", name, stack.size(), MAX_STACK_DEPTH));
        }
        pushTransition = new PendingTransition();
        pendingPush = new State<>(context, state);
        @SuppressWarnings("unchecked")
        Transitions pendingTransition = (Transitions) Proxy.newProxyInstance(getContext().getClass().getClassLoader(),
                                                                             new Class<?>[] { transitionsType },
                                                                             pushTransition);
        return pendingTransition;
    }

    public <R> R synchonizeOnState(Callable<R> call) throws Exception {
        return locked(call);
    }

    public void synchonizeOnState(Runnable call) {
        locked(() -> {
            call.run();
            return null;
        });
    }

    @Override
    public String toString() {
        return String.format("Fsm [name = %s, current=%s, previous=%s, transition=%s]", name, prettyPrint(current),
                             prettyPrint(previous), getTransition());
    }

    private void executeEntryAction() {
        Method action = ENTRY_ACTION_CACHE.computeIfAbsent(current.getClass(), cls -> {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Entry.class)) {
                    m.setAccessible(true);
                    return m;
                }
            }
            return null;  // No entry action defined for this state
        });

        if (action != null) {
            if (log.isTraceEnabled()) {
                log.trace(
                String.format("[%s] Entry action: %s.%s", name, prettyPrint(current), prettyPrint(action)));
            }
            try {
                // For entry actions with parameters, inject the context
                if (action.getParameterTypes().length > 0)
                    action.invoke(current, getContext());
                else
                    action.invoke(current);
            } catch (IllegalAccessException | IllegalArgumentException e) {
                throw new IllegalStateException(e);
            } catch (InvocationTargetException e) {
                Throwable targetException = e.getTargetException();
                if (targetException instanceof RuntimeException) {
                    throw (RuntimeException) targetException;
                }
                throw new IllegalStateException(targetException);
            }
        }
    }

    private void executeExitAction() {
        Method action = EXIT_ACTION_CACHE.computeIfAbsent(current.getClass(), cls -> {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Exit.class)) {
                    m.setAccessible(true);
                    return m;
                }
            }
            return null;  // No exit action defined for this state
        });

        if (action != null) {
            if (log.isTraceEnabled()) {
                log.trace(
                String.format("[%s] Exit action: %s.%s", name, prettyPrint(current), prettyPrint(action)));
            }
            try {
                // For exit action with parameters, inject the context
                if (action.getParameterTypes().length > 0)
                    action.invoke(current, getContext());
                else
                    action.invoke(current);
            } catch (IllegalAccessException | IllegalArgumentException e) {
                throw new IllegalStateException(e);
            } catch (InvocationTargetException e) {
                Throwable targetException = e.getTargetException();
                if (targetException instanceof RuntimeException) {
                    throw (RuntimeException) targetException;
                }
                throw new IllegalStateException(targetException);
            }
        }
    }

    /**
     * The Jesus Nut
     *
     * @param t         - the transition to fire
     * @param arguments - the transition arguments
     * @return
     */
    private Object fire(Method t, Object[] arguments) {
        if (t == null) {
            return null;
        }
        Fsm<?, ?> previousFsm = thisFsm.get();
        thisFsm.set(this);
        previous = current;
        if (!transitionsType.isAssignableFrom(t.getReturnType())) {
            try {
                return t.invoke(current, arguments);
            } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                throw new IllegalStateException(e);
            }
        }

        try {
            transition = prettyPrint(t);
            Transitions nextState;
            Transitions pinned = current;
            try {
                nextState = fireTransition(lookupTransition(t), arguments);
            } catch (InvalidTransition e) {
                nextState = fireTransition(lookupDefaultTransition(e, t), arguments);
            }
            if (pinned == current) {
                transitionTo(nextState);
            } else {
                if (nextState != null && log.isTraceEnabled()) {
                    log.trace(
                    String.format("[%s] Eliding Transition %s -> %s, pinned state: %s", name, prettyPrint(current),
                                  prettyPrint(nextState), prettyPrint(pinned)));
                }
            }
            return null;
        } finally {
            thisFsm.set(previousFsm);
        }
    }

    /**
     * Fire the concrete transition of the current state
     *
     * @param stateTransition - the transition method to execute
     * @param arguments       - the arguments of the method
     * @return the next state
     */
    @SuppressWarnings("unchecked")
    private Transitions fireTransition(Method stateTransition, Object[] arguments) {
        if (stateTransition.isAnnotationPresent(Default.class)) {
            if (log.isTraceEnabled()) {
                log.trace(String.format("[%s] Default transition: %s.%s", prettyPrint(current)), getTransition(), name);
            }
            try {
                return (Transitions) stateTransition.invoke(current, (Object[]) null);
            } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                throw new IllegalStateException(
                String.format("Unable to invoke transition %s,%s", prettyPrint(current), prettyPrint(stateTransition)),
                e);
            }
        }
        if (log.isTraceEnabled()) {
            log.trace(String.format("[%s] Transition: %s.%s", name, prettyPrint(current), getTransition()));
        }
        try {
            return (Transitions) stateTransition.invoke(current, arguments);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            throw new IllegalStateException(
            String.format("Unable to invoke transition %s.%s", prettyPrint(current), prettyPrint(stateTransition)),
            e.getCause());
        } catch (InvocationTargetException e) {
            if (e.getTargetException() instanceof InvalidTransition) {
                if (log.isTraceEnabled()) {
                    log.trace(
                    String.format("[%s] Invalid transition %s.%s", name, prettyPrint(current), getTransition()));
                }
                throw (InvalidTransition) e.getTargetException();
            }
            if (e.getTargetException() instanceof RuntimeException) {
                throw (RuntimeException) e.getTargetException();
            }
            throw new IllegalStateException(
            String.format("[%s] Unable to invoke transition %s.%s", name, prettyPrint(current),
                          prettyPrint(stateTransition)), e.getTargetException());
        }
    }

    private <T> T locked(Callable<T> call) {
        final Lock lock = sync;
        if (lock != null) {
            lock.lock();
        }
        try {
            return call.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            if (lock != null) {
                lock.unlock();
            }
        }
    }

    private Method lookupDefaultTransition(InvalidTransition previousException, Method t) {
        Method defaultMethod = DEFAULT_TRANSITION_CACHE.computeIfAbsent(current.getClass(), cls -> {
            // look for a @Default transition for the state singleton
            for (Method defaultTransition : cls.getDeclaredMethods()) {
                if (defaultTransition.isAnnotationPresent(Default.class)) {
                    defaultTransition.setAccessible(true);
                    return defaultTransition;
                }
            }
            // look for a @Default transition for the state on the enclosing enum class
            for (Method defaultTransition : cls.getMethods()) {
                if (defaultTransition.isAnnotationPresent(Default.class)) {
                    defaultTransition.setAccessible(true);
                    return defaultTransition;
                }
            }
            return null;  // No default transition found
        });

        if (defaultMethod != null) {
            return defaultMethod;
        }

        if (previousException == null) {
            throw new InvalidTransition(String.format(prettyPrint(t)));
        } else {
            throw previousException;
        }
    }

    /**
     * Lookup the transition.
     *
     * @param t - the transition defined in the interface
     * @return the transition Method for the current state matching the interface definition
     */
    private Method lookupTransition(Method t) {
        // Create cache key: "className.methodName(param1,param2,...)
        String cacheKey = current.getClass().getName() + "." + t.getName()
                        + Arrays.toString(t.getParameterTypes());

        Method stateTransition = TRANSITION_METHOD_CACHE.computeIfAbsent(cacheKey, key -> {
            try {
                // First we try declared methods on the state
                Method m = current.getClass().getMethod(t.getName(), t.getParameterTypes());
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException | SecurityException e1) {
                throw new IllegalStateException(
                String.format("Inconcievable!  The state %s does not implement the transition %s",
                              prettyPrint(current), prettyPrint(t)));
            }
        });

        return stateTransition;
    }

    /**
     * Ye olde tyme state transition
     *
     * @param nextState - the next state of the Fsm
     */
    private void normalTransition(Transitions nextState) {
        if (nextState == null) { // internal loopback transition
            if (log.isTraceEnabled()) {
                log.trace(String.format("[%s] Internal loopback: %s", name, prettyPrint(current)));
            }
            return;
        }
        executeExitAction();
        if (log.isTraceEnabled()) {
            log.trace(
            String.format("[%s] State transition:  %s -> %s", name, prettyPrint(current), prettyPrint(nextState)));
        }
        current = nextState;
        executeEntryAction();
    }

    /**
     * Execute the exit action of the current state. Set current state to popped state of the stack. Execute any pending
     * transition on the current state.
     */
    private void popTransition() {
        pendingPop = false;
        previous = current;
        State<Context, Transitions> pop = stack.pop();
        PendingTransition pendingTransition = popTransition;
        popTransition = null;

        executeExitAction();
        if (log.isTraceEnabled()) {
            log.trace(String.format("[%s] State transition:  %s -> %s - Popping(%s)", name, prettyPrint(previous),
                                    prettyPrint(pop), stack.size() + 1));
        }
        current = pop.transitions;
        if (pop.context != null) {
            setContext(pop.context);
        }
        if (pendingTransition != null) {
            if (log.isTraceEnabled()) {
                log.trace(String.format("[%s] Pop transition: %s.%s", name, prettyPrint(current),
                                        prettyPrint(pendingTransition.method)));
            }
            fire(pendingTransition.method, pendingTransition.args);
        }
    }

    private String prettyPrint(State<Context, Transitions> state) {
        return prettyPrint(state.transitions) + " [" +
               (state.context == null ? "<>: " + getContext() : state.context) + "]";
    }

    private String prettyPrint(Method transition) {
        StringBuilder builder = new StringBuilder();
        if (transition != null) {
            builder.append(transition.getName());
            builder.append('(');
            Class<?>[] parameters = transition.getParameterTypes();
            for (int i = 0; i < parameters.length; i++) {
                builder.append(parameters[i].getSimpleName());
                if (i != parameters.length - 1) {
                    builder.append(", ");
                }
            }
            builder.append(')');
        } else {
            builder.append("loopback");
        }
        return builder.toString();
    }

    /**
     * Push the current state of the Fsm to the stack, with the supplied context as the new current context of the FSM,
     * if non null. Transition the Fsm to the nextState, execute the entry action of that state. Set the current state
     * of the Fsm to the pending push state, executing the entry action on that state
     *
     * @param nextState
     */
    private void pushTransition(Transitions nextState) {
        State<Context, Transitions> pushed = pendingPush;
        pendingPush = null;
        normalTransition(nextState);
        stack.push(new State<>(context, current));
        if (log.isTraceEnabled()) {
            log.trace(String.format("[%s] State transition: %s -> %s - Pushing(%s)", name, prettyPrint(current),
                                    prettyPrint(pushed), stack.size()));
        }
        current = pushed.transitions;
        if (pushed.context != null) {
            setContext(pushed.context);
        }
        Transitions pinned = current;
        PendingTransition pushTrns = pushTransition;
        pushTransition = null;
        executeEntryAction();
        if (pushTrns != null) {
            if (current != pinned) {
                log.trace(String.format("[%s] Eliding push transition %s.%s pinned: %s", name, prettyPrint(current),
                                        prettyPrint(pushTrns.method), prettyPrint(pinned)));
            } else {
                if (log.isTraceEnabled()) {
                    log.trace(String.format("[%s] Push transition: %s.%s", name, prettyPrint(current),
                                            prettyPrint(pushTrns.method)));
                }
                fire(pushTrns.method, pushTrns.args);
            }
        }
    }

    /**
     * Transition to the next state
     *
     * @param nextState
     */
    private void transitionTo(Transitions nextState) {
        if (pendingPush != null) {
            pushTransition(nextState);
        } else if (pendingPop) {
            popTransition();
        } else {
            normalTransition(nextState);
        }
    }

    private InvocationHandler transitionsHandler() {
        return new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                return locked(() -> fire(method, args));
            }
        };
    }

    private static class State<Context, Transitions> {
        private final Context     context;
        private final Transitions transitions;

        public State(Context context, Transitions transitions) {
            this.context = context;
            this.transitions = transitions;
        }

    }

    private static class PendingTransition implements InvocationHandler {
        private volatile Object[] args;
        private volatile Method   method;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (this.method != null) {
                throw new IllegalStateException(
                String.format("Pop transition '%s' has already been established", method.toGenericString()));
            }
            this.method = method;
            this.args = args;
            return null;
        }
    }
}
