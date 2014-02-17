package org.squirrelframework.foundation.fsm.impl;

import static com.google.common.base.Preconditions.checkState;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.squirrelframework.foundation.component.Observable;
import org.squirrelframework.foundation.component.SquirrelProvider;
import org.squirrelframework.foundation.component.impl.AbstractSubject;
import org.squirrelframework.foundation.event.AsyncEventListener;
import org.squirrelframework.foundation.event.ListenerMethod;
import org.squirrelframework.foundation.exception.ErrorCodes;
import org.squirrelframework.foundation.exception.TransitionException;
import org.squirrelframework.foundation.fsm.Action;
import org.squirrelframework.foundation.fsm.ActionExecutionService;
import org.squirrelframework.foundation.fsm.ActionExecutionService.ActionEvent;
import org.squirrelframework.foundation.fsm.ActionExecutionService.AfterExecActionEvent;
import org.squirrelframework.foundation.fsm.ActionExecutionService.AfterExecActionListener;
import org.squirrelframework.foundation.fsm.ActionExecutionService.BeforeExecActionEvent;
import org.squirrelframework.foundation.fsm.ActionExecutionService.BeforeExecActionListener;
import org.squirrelframework.foundation.fsm.ActionExecutionService.ExecActionExceptionEvent;
import org.squirrelframework.foundation.fsm.ActionExecutionService.ExecActionExceptionListener;
import org.squirrelframework.foundation.fsm.Converter;
import org.squirrelframework.foundation.fsm.ConverterProvider;
import org.squirrelframework.foundation.fsm.ImmutableLinkedState;
import org.squirrelframework.foundation.fsm.ImmutableState;
import org.squirrelframework.foundation.fsm.MvelScriptManager;
import org.squirrelframework.foundation.fsm.SCXMLVisitor;
import org.squirrelframework.foundation.fsm.StateContext;
import org.squirrelframework.foundation.fsm.StateMachine;
import org.squirrelframework.foundation.fsm.StateMachineContext;
import org.squirrelframework.foundation.fsm.StateMachineData;
import org.squirrelframework.foundation.fsm.StateMachineStatus;
import org.squirrelframework.foundation.fsm.TransitionResult;
import org.squirrelframework.foundation.fsm.Visitor;
import org.squirrelframework.foundation.fsm.annotation.AsyncExecute;
import org.squirrelframework.foundation.fsm.annotation.OnActionExecException;
import org.squirrelframework.foundation.fsm.annotation.OnAfterActionExecuted;
import org.squirrelframework.foundation.fsm.annotation.OnBeforeActionExecuted;
import org.squirrelframework.foundation.fsm.annotation.OnStateMachineStart;
import org.squirrelframework.foundation.fsm.annotation.OnStateMachineTerminate;
import org.squirrelframework.foundation.fsm.annotation.OnTransitionBegin;
import org.squirrelframework.foundation.fsm.annotation.OnTransitionComplete;
import org.squirrelframework.foundation.fsm.annotation.OnTransitionDecline;
import org.squirrelframework.foundation.fsm.annotation.OnTransitionEnd;
import org.squirrelframework.foundation.fsm.annotation.OnTransitionException;
import org.squirrelframework.foundation.util.Pair;
import org.squirrelframework.foundation.util.ReflectUtils;
import org.squirrelframework.foundation.util.TypeReference;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * The Abstract state machine provide several extension ability to cover different extension granularity. 
 * <ol>
 * <li>Method <b>beforeStateExit</b>/<b>afterStateEntry</b> is used to add custom logic on all kinds of state exit/entry.</li>
 * <li>Method <b>exit[stateName]</b>/<b>entry[stateName]</b> is extension method which is used to add custom logic on specific state.</li>
 * <li>Method <b>beforeTransitionBegin</b>/<b>afterTransitionComplete</b> is used to add custom logic on all kinds of transition 
 * accepted all conditions.</li>
 * <li>Method <b>transitFrom[fromStateName]To[toStateName]On[eventName]</b> is used to add custom logic on specific transition 
 * accepted all conditions.</li>
 * <li>Method <b>transitFromAnyTo[toStateName]On[eventName]</b> is used to add custom logic on any state transfer to specific target 
 * state on specific event happens, so as the <b>transitFrom[fromStateName]ToAnyOn[eventName]</b>, <b>transitFrom[fromState]To[ToStateName]</b>, 
 * and <b>on[EventName]</b>.</li>
 * </ol>
 * @author Henry.He
 *
 * @param <T> state machine type
 * @param <S> state type
 * @param <E> event type
 * @param <C> context type
 */
public abstract class AbstractStateMachine<T extends StateMachine<T, S, E, C>, S, E, C> extends AbstractSubject implements StateMachine<T, S, E, C> {
    
    private static final Logger logger = LoggerFactory.getLogger(AbstractStateMachine.class);
    
    private final ActionExecutionService<T, S, E, C> executor = SquirrelProvider.getInstance().newInstance(
    		new TypeReference<ActionExecutionService<T, S, E, C>>(){});
    
    private final StateMachineData<T, S, E, C> data;
    
    private volatile StateMachineStatus status = StateMachineStatus.INITIALIZED;
    
    private LinkedBlockingQueue<Pair<E, C>> queuedEvents = new LinkedBlockingQueue<Pair<E, C>>();
    
    private LinkedBlockingQueue<Pair<E, C>> queuedTestEvents = new LinkedBlockingQueue<Pair<E, C>>();
    
    private volatile boolean isProcessingTestEvent = false;
    
    private E startEvent, finishEvent, terminateEvent;
    
    private final Lock processingLock = new ReentrantLock();
    
    private MvelScriptManager scriptManager;
    
    // state machine options
    private boolean isAutoStartEnabled = true;
    
    private boolean isAutoTerminateEnabled = true;
    
    @SuppressWarnings("unused")
    private long transitionTimeout = -1;
    
    // TODO-hhe: temporary hard-coded disable data isolation 
    private boolean isDataIsolateEnabled = false;
    
    private static final int ID_LENGTH = 10;
    
    private Class<?>[] extraParamTypes;
    
    private TransitionException lastException = null;
    
    protected AbstractStateMachine(ImmutableState<T, S, E, C> initialState, Map<S, ? extends ImmutableState<T, S, E, C>> states) {
        S intialStateId = initialState.getStateId();
        data = FSM.newStateMachineData(states);
        data.write().initalState(intialStateId);
        data.write().currentState(intialStateId);
        data.write().identifier(RandomStringUtils.randomAlphanumeric(ID_LENGTH));
    }
    
    private boolean processEvent(E event, C context, StateMachineData<T, S, E, C> orignalData, 
            ActionExecutionService<T, S, E, C> executionService, boolean isDataIsolateEnabled) {
        StateMachineData<T, S, E, C> localData = orignalData;
        ImmutableState<T, S, E, C> fromState = localData.read().currentRawState();
        S fromStateId = fromState.getStateId(), toStateId = null;
        try {
            beforeTransitionBegin(fromStateId, event, context);
            fireEvent(new TransitionBeginEventImpl<T, S, E, C>(fromStateId, event, context, getThis()));
            
            if(isDataIsolateEnabled) {
                // use local data to isolation transition data write
                localData = FSM.newStateMachineData(orignalData.read().orginalStates());
                localData.dump(orignalData.read());
            }
            
            TransitionResult<T, S, E, C> result = FSM.newResult(false, fromState, null);
            StateContext<T, S, E, C> stateContext = FSM.newStateContext(this, localData, 
                    fromState, event, context, result, executionService);
            fromState.internalFire(stateContext);
            toStateId = result.getTargetState().getStateId();
            executionService.executeAll();
            
            if(result.isAccepted()) {
                localData.write().lastState(fromStateId);
                localData.write().currentState(toStateId);
                if(isDataIsolateEnabled) { 
                    // import local data after transition accepted
                    orignalData.dump(localData.read());
                }
                fireEvent(new TransitionCompleteEventImpl<T, S, E, C>(fromStateId, toStateId, 
                        event, context, getThis()));
                afterTransitionCompleted(fromStateId, getCurrentState(), event, context);
                return true;
            } else {
                fireEvent(new TransitionDeclinedEventImpl<T, S, E, C>(fromStateId, event, context, getThis()));
                afterTransitionDeclined(fromStateId, event, context);
            }
        } catch (Exception e) {
            // set state machine in error status first which means state machine cannot process event anymore 
            // unless this exception has been resolved and state machine status set back to normal again.
            setStatus(StateMachineStatus.ERROR);
            // wrap any exception into transition exception    
            lastException = (e instanceof TransitionException) ? (TransitionException) e :
                new TransitionException(e, ErrorCodes.FSM_TRANSITION_ERROR, 
                        new Object[]{fromStateId, toStateId, event, context, "UNKNOWN", e.getMessage()});
            fireEvent(new TransitionExceptionEventImpl<T, S, E, C>(lastException, fromStateId, 
                    localData.read().currentState(), event, context, getThis()));
            afterTransitionCausedException(fromStateId, toStateId, event, context);
        } finally {
            fireEvent(new TransitionEndEventImpl<T, S, E, C>(fromStateId, toStateId, event, context, getThis()));
            afterTransitionEnd(fromStateId, getCurrentState(), event, context);
        }
        return false;
    }
    
    private void processEvents() {
        if (isIdle()) {
            setStatus(StateMachineStatus.BUSY);
            processingLock.lock();
            try {
                Pair<E, C> eventInfo = null;
                while ((eventInfo=queuedEvents.poll())!=null) {
                    // response to cancel operation
                    if(Thread.interrupted()) {
                        queuedEvents.clear();
                        break;
                    }
                    E event = eventInfo.first();
                    C context = eventInfo.second();
                    processEvent(event, context, data, executor, isDataIsolateEnabled);
                }
            } finally {
            	if(getStatus()==StateMachineStatus.BUSY)
            	    setStatus(StateMachineStatus.IDLE);
            	processingLock.unlock();
            }
        }
    }
    
    private void internalFire(E event, C context) {
        if(getStatus()==StateMachineStatus.INITIALIZED) {
            if(isAutoStartEnabled) {
                start(context);
            } else {
                throw new IllegalStateException("The state machine is not running.");
            }
        }
        if(getStatus()==StateMachineStatus.TERMINATED) {
            throw new IllegalStateException("The state machine is already terminated.");
        }
        if(getStatus()==StateMachineStatus.ERROR) {
            throw new IllegalStateException("The state machine is corruptted.");
        }
        queuedEvents.add(new Pair<E, C>(event, context));
        processEvents();
        
        ImmutableState<T, S, E, C> rawState = data.read().currentRawState();
        if(isAutoTerminateEnabled && rawState.isRootState() && rawState.isFinalState()) {
            terminate(context);
        }
    }
    
    private boolean isEntryPoint() {
        return StateMachineContext.currentInstance()==null;
    }
    
    @Override
    public void fire(E event, C context) {
        boolean isEntryPoint = isEntryPoint();
        if(isEntryPoint) {
            StateMachineContext.set(getThis());
        } else if(StateMachineContext.currentInstance()!=this) {
            T currentInstance = StateMachineContext.currentInstance();
            currentInstance.fire(event, context);
            return;
        } 
        try {
            if(StateMachineContext.isTestEvent()) {
                internalTest(event, context);
            } else {
                internalFire(event, context);
            }
        } finally {
            if(isEntryPoint) {
                StateMachineContext.set(null);
            }
        }
    }
    
    @Override
    public void fire(E event) {
        fire(event, null);
    }
    
    private ActionExecutionService<T, S, E, C> getDummyExecutor() {
        ActionExecutionService<T, S, E, C> dummyExecutor = SquirrelProvider.getInstance().newInstance(
                new TypeReference<ActionExecutionService<T, S, E, C>>(){});
        dummyExecutor.setDummyExecution(true);
        return dummyExecutor;
    }
    
    private S internalTest(E event, C context) {
        checkState(status!=StateMachineStatus.ERROR && status!=StateMachineStatus.TERMINATED,
                "Cannot test state machine under "+status+" status.");
        
        S testResult = null;
        queuedTestEvents.add(new Pair<E, C>(event, context));
        if(!isProcessingTestEvent) {
            isProcessingTestEvent = true;
            @SuppressWarnings("unchecked")
            StateMachineData<T, S, E, C> cloneData = (StateMachineData<T, S, E, C>)dumpSavedData();
            ActionExecutionService<T, S, E, C> dummyExecutor = getDummyExecutor();
            
            if(getStatus()==StateMachineStatus.INITIALIZED) {
                if(isAutoStartEnabled) {
                    internalStart(context, cloneData, dummyExecutor);
                } else {
                    throw new IllegalStateException("The state machine is not running.");
                }
            }
            try {
                Pair<E, C> eventInfo = null;
                while ((eventInfo=queuedTestEvents.poll())!=null) {
                    E testEvent = eventInfo.first();
                    C testContext = eventInfo.second();
                    processEvent(testEvent, testContext, cloneData, dummyExecutor, false);
                }
                testResult = resolveState(cloneData.read().currentState(), cloneData);
            } finally {
                isProcessingTestEvent = false;
            }
        }
        return testResult;
    }
    
    @Override
    public S test(E event, C context) {
        boolean isEntryPoint = isEntryPoint();
        if(isEntryPoint) {
            StateMachineContext.set(getThis(), true);
        }
        try {
            return internalTest(event, context);
        } finally {
            if(isEntryPoint) {
                StateMachineContext.set(null);
            }
        }
    }
    
    @Override
    public S test(E event) {
        return test(event, null);
    }
    
    protected boolean isIdle() {
    	return getStatus()!=StateMachineStatus.BUSY;
    }
    
    /**
     * Replaced by <code>afterTransitionCausedException(S fromState, S toState, E event, C context)</code>. 
     * Exception can be retrieved from <code>StateMachine.getLastException()</code>.
     * @param e exception during transition
     * @param fromState transition source state
     * @param toState transition target state
     * @param event transition event
     * @param context transition context
     */
    @Deprecated
    protected void afterTransitionCausedException(
            TransitionException e, S fromState, S toState, E event, C context) {
        throw e;
    }
    
    protected void afterTransitionCausedException(S fromState, S toState, E event, C context) {
        afterTransitionCausedException(getLastException(), fromState, toState, event, context);
        // throw getLastException()
    }
    
    protected void beforeTransitionBegin(S fromState, E event, C context) {
    }
    
    protected void afterTransitionCompleted(S fromState, S toState, E event, C context) {
    }
    
    protected void afterTransitionEnd(S fromState, S toState, E event, C context) {
    }
    
    protected void afterTransitionDeclined(S fromState, E event, C context) {
    }
    
    protected void beforeActionInvoked(S fromState, S toState, E event, C context) {
    }
    
    protected void afterActionInvoked(S fromState, S toState, E event, C context) {
    }
    
    private ImmutableState<T, S, E, C> resolveRawState(ImmutableState<T, S, E, C> rawState) {
        ImmutableState<T, S, E, C> resolvedRawState = rawState;
        if(resolvedRawState instanceof ImmutableLinkedState) {
            @SuppressWarnings("unchecked")
            T linkedStateMachine = (T) ((ImmutableLinkedState<T, S, E, C>)rawState).
                getLinkedStateMachine(getThis());
            resolvedRawState = linkedStateMachine.getCurrentRawState();
        }
        return resolvedRawState;
    }
    
    @Override
    public ImmutableState<T, S, E, C> getCurrentRawState() {
        ImmutableState<T, S, E, C> rawState = data.read().currentRawState();
        return resolveRawState(rawState);
    }
    
    @Override
    public ImmutableState<T, S, E, C> getLastRawState() {
        ImmutableState<T, S, E, C> lastRawState = data.read().lastRawState();
        return resolveRawState(lastRawState);
    }
    
    @Override
    public ImmutableState<T, S, E, C> getInitialRawState() {
        return getRawStateFrom(getInitialState());
    }
    
    @Override
    public ImmutableState<T, S, E, C> getRawStateFrom(S stateId) {
        return data.read().rawStateFrom(stateId);
    }
    
    @Override
    public Collection<ImmutableState<T, S, E, C>> getAllRawStates() {
        return data.read().rawStates();
    }
    
    @Override
    public Collection<S> getAllStates() {
        return data.read().states();
    }
    
    private S resolveState(S state, StateMachineData<T, S, E, C> localData) {
        S resolvedState = state;
        ImmutableState<T, S, E, C> rawState = localData.read().rawStateFrom(resolvedState);
        if(rawState instanceof ImmutableLinkedState) {
            ImmutableLinkedState<T, S, E, C> linkedRawState = (ImmutableLinkedState<T, S, E, C>)rawState;
            resolvedState = linkedRawState.getLinkedStateMachine(getThis()).getCurrentState();
        }
        return resolvedState;
    }
    
    @Override
    public S getCurrentState() {
        processingLock.lock();
        try {
            return resolveState(data.read().currentState(), data);
        } finally {
            processingLock.unlock();
        }
    }
    
    @Override
    public S getLastState() {
        processingLock.lock();
        try {
            return resolveState(data.read().lastState(), data);
        } finally {
            processingLock.unlock();
        }
    }
    
    @Override
    public S getInitialState() {
        processingLock.lock();
        try {
            return data.read().initialState();
        } finally {
            processingLock.unlock();
        }
    }

    private void entryAll(ImmutableState<T, S, E, C> origin, StateContext<T, S, E, C> stateContext) {
    	Stack<ImmutableState<T, S, E, C>> stack = new Stack<ImmutableState<T, S, E, C>>();

    	ImmutableState<T, S, E, C> state = origin;
		while (state != null) {
			stack.push(state);
			state = state.getParentState();
		}
		while (stack.size() > 0) {
			state = stack.pop();
			state.entry(stateContext);
		}
	}
    
    @Override
    public synchronized void start(C context) {
    	if(isStarted()) {
            return;
        }
    	setStatus(StateMachineStatus.IDLE);
    	internalStart(context, data, executor);
        processEvents();
    }
    
    private void internalStart(C context, StateMachineData<T, S, E, C> localData,
            ActionExecutionService<T, S, E, C> executionService) {
        StateContext<T, S, E, C> stateContext = FSM.newStateContext(
                this, localData, localData.read().currentRawState(), getStartEvent(), 
                context, null, executionService);
        
        entryAll(localData.read().initialRawState(), stateContext);
        ImmutableState<T, S, E, C> currentState = localData.read().currentRawState();
        ImmutableState<T, S, E, C> historyState = currentState.enterByHistory(stateContext);
        executionService.executeAll();
        localData.write().currentState(historyState.getStateId());
        fireEvent(new StartEventImpl<T, S, E, C>(getThis()));
    }
    
    @Override
    public void start() {
        start(null);
    }
    
    private boolean isStarted() {
        return getStatus()==StateMachineStatus.IDLE || getStatus()==StateMachineStatus.BUSY;
    }
    
    private boolean isTerminiated() {
    	return getStatus()==StateMachineStatus.TERMINATED;
    }
    
    @Override
    public StateMachineStatus getStatus() {
        return status;
    }
    
    protected void setStatus(StateMachineStatus status) {
        this.status = status;
    }
    
    @Override
    public S getLastActiveChildStateOf(S parentStateId) {
        processingLock.lock();
        try {
            return data.read().lastActiveChildStateOf(parentStateId);
        } finally {
            processingLock.unlock();
        }
    }
    
    @Override
    public List<S> getSubStatesOn(S parentStateId) {
        processingLock.lock();
        try {
            return data.read().subStatesOn(parentStateId);
        } finally {
            processingLock.unlock();
        }
    }
    
    @Override
    public synchronized void terminate(C context) {
    	if(isTerminiated()) {
            return;
        }
        
        StateContext<T, S, E, C> stateContext = FSM.newStateContext(
                this, data, data.read().currentRawState(), getTerminateEvent(), 
                context, null, executor);
        exitAll(data.read().currentRawState(), stateContext);
        executor.executeAll();
        
        setStatus(StateMachineStatus.TERMINATED);
        fireEvent(new TerminateEventImpl<T, S, E, C>(getThis()));
    }
    
    @Override
    public void terminate() {
        terminate(null);
    }
    
    private void exitAll(ImmutableState<T, S, E, C> current, StateContext<T, S, E, C> stateContext) {
    	ImmutableState<T, S, E, C> state = current;
        while (state != null) {
        	state.exit(stateContext);
        	state = state.getParentState();
		}
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public T getThis() {
    	return (T)this;
    }
    
    @Override
    public void accept(Visitor visitor) {
        visitor.visitOnEntry(this);
        for(ImmutableState<T, S, E, C> state : data.read().rawStates()) {
            if(state.getParentState()==null)
                state.accept(visitor);
        }
        visitor.visitOnExit(this);
    }
    
    void setTypeOfStateMachine(Class<? extends T> stateMachineType) {
        data.write().typeOfStateMachine(stateMachineType);
    }
    
    void setTypeOfState(Class<S> stateType) {
        data.write().typeOfState(stateType);
    }
    
    void setTypeOfEvent(Class<E> eventType) {
        data.write().typeOfEvent(eventType);
    }
    
    void setTypeOfContext(Class<C> contextType) {
        data.write().typeOfContext(contextType);
    }
    
    void setScriptManager(MvelScriptManager scriptManager) {
        this.scriptManager = scriptManager;
    }
    
    void setStartEvent(E startEvent) {
    	this.startEvent=startEvent;
    }
    
    E getStartEvent() {
    	return startEvent;
    }
    
    void setTerminateEvent(E terminateEvent) {
    	this.terminateEvent=terminateEvent;
    }
    
    E getTerminateEvent() {
    	return terminateEvent;
    }
    
    void setFinishEvent(E finishEvent) {
    	this.finishEvent=finishEvent;
    }
    
    E getFinishEvent() {
    	return finishEvent;
    }
    
    void setExtraParamTypes(Class<?>[] extraParamTypes) {
        this.extraParamTypes = extraParamTypes;
    }
    
    @Override
    public StateMachineData.Reader<T, S, E, C> dumpSavedData() {
        processingLock.lock();
        try {
            StateMachineData<T, S, E, C> savedData = 
                    FSM.newStateMachineData(data.read().orginalStates());
            savedData.dump(data.read());
            
            // process linked state if any
            saveLinkedStateData(data.read(), savedData.write());
            return savedData.read();
        } finally {
            processingLock.unlock();
        }
    }
    
    private void saveLinkedStateData(StateMachineData.Reader<T, S, E, C> src, StateMachineData.Writer<T, S, E, C> target) {
        dumpLinkedStateFor(src.currentRawState(), target);
//        dumpLinkedStateFor(src.lastRawState(), target);
        // TODO-hhe: dump linked state info for last active child state
        // TODO-hhe: dump linked state info for parallel state
    }
    
    private void dumpLinkedStateFor(ImmutableState<T, S, E, C> rawState, StateMachineData.Writer<T, S, E, C> target) {
        if(rawState!=null && rawState instanceof ImmutableLinkedState) {
            ImmutableLinkedState<T, S, E, C> linkedRawState = (ImmutableLinkedState<T, S, E, C>)rawState;
            StateMachineData.Reader<? extends StateMachine<?, S, E, C>, S, E, C> linkStateData = 
                    linkedRawState.getLinkedStateMachine(getThis()).dumpSavedData();
            target.linkedStateDataOn(rawState.getStateId(), linkStateData);
        }
    }
    
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public boolean loadSavedData(StateMachineData.Reader<T, S, E, C> savedData) {
        Preconditions.checkNotNull(savedData, "Saved data cannot be null");
        if(processingLock.tryLock()) {
            try {
                data.dump(savedData);
                // process linked state if any
                for(S linkedState : savedData.linkedStates()) {
                    StateMachineData.Reader linkedStateData = savedData.linkedStateDataOf(linkedState);
                    ImmutableState<T, S, E, C> rawState = data.read().rawStateFrom(linkedState);
                    if(linkedStateData!=null && rawState instanceof ImmutableLinkedState) {
                        ImmutableLinkedState<T, S, E, C> linkedRawState = (ImmutableLinkedState<T, S, E, C>)rawState;
                        linkedRawState.getLinkedStateMachine(getThis()).loadSavedData(linkedStateData);
                    }
                }
                setStatus(StateMachineStatus.IDLE);
                return true;
            } finally {
                processingLock.unlock();
            }
        }
        return false;
    }
    
    @Override
    public boolean isContextSensitive() {
        return true;
    }
    
    @Override
    public Class<C> typeOfContext() {
        return data.read().typeOfContext();
    }
    
    @Override
    public Class<E> typeOfEvent() {
        return data.read().typeOfEvent();
    }
    
    @Override
    public Class<S> typeOfState() {
        return data.read().typeOfState();
    }
    
    @Override
    public TransitionException getLastException() {
        return lastException;
    }
    
    protected void setLastException(TransitionException lastException) {
        this.lastException = lastException;
    }
    
    private interface DeclarativeLisener {
        Object getListenTarget();
    }
    
    private Object newListenerMethodProxy(final Object listenTarget, 
            final Method listenerMethod, final Class<?> listenerInterface, final String condition) {
        final String listenerMethodName = ReflectUtils.getStatic(
                ReflectUtils.getField(listenerInterface, "METHOD_NAME")).toString();
        
        AsyncExecute asyncAnnotation = ReflectUtils.getAnnotation(listenTarget.getClass(), AsyncExecute.class);
        if(asyncAnnotation==null) {
            asyncAnnotation = listenerMethod.getAnnotation(AsyncExecute.class);
        }
        final boolean isAsync = asyncAnnotation!=null;
        final long timeout = asyncAnnotation!=null ? asyncAnnotation.timeout() : -1;
        InvocationHandler invokationHandler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if(method.getName().equals("getListenTarget")) {
                    return listenTarget;
                } else if(method.getName().equals(listenerMethodName)) {
                    if(args[0] instanceof TransitionEvent) {
                        @SuppressWarnings("unchecked")
                        TransitionEvent<T, S, E, C> event = (TransitionEvent<T, S, E, C>)args[0];
                        return invokeTransitionListenerMethod(listenTarget, listenerMethod, condition, event);
                    } else if(args[0] instanceof ActionEvent) {
                        @SuppressWarnings("unchecked")
                        ActionEvent<T, S, E, C> event = (ActionEvent<T, S, E, C>)args[0];
                        return invokeActionListenerMethod(listenTarget, listenerMethod, condition, event);
                    } else if(args[0] instanceof StartEvent || args[0] instanceof TerminateEvent) {
                        @SuppressWarnings("unchecked")
                        StateMachineEvent<T, S, E, C> event = (StateMachineEvent<T, S, E, C>)args[0];
                        return invokeStateMachineListenerMethod(listenTarget, listenerMethod, condition, event);
                    } else {
                        throw new IllegalArgumentException("Unable to recognize argument type "+args[0].getClass().getName()+".");
                    }
                } else if(method.getName().equals("equals")) {
                    return super.equals(args[0]);
                } else if(method.getName().equals("hashCode")) {
                    return super.hashCode();
                } else if(method.getName().equals("toString")) {
                    return super.toString();
                } else if(isAsync && method.getName().equals("timeout")) {
                    return timeout;
                }
                throw new UnsupportedOperationException("Cannot invoke method "+method.getName()+".");
            }
        };
        
        Class<?>[] implementedInterfaces = isAsync ? 
                new Class<?>[]{listenerInterface, DeclarativeLisener.class, AsyncEventListener.class} : 
                new Class<?>[]{listenerInterface, DeclarativeLisener.class};
        Object proxyListener = Proxy.newProxyInstance(StateMachine.class.getClassLoader(), 
                implementedInterfaces, invokationHandler);
        return proxyListener;
    }
    
    private Object invokeStateMachineListenerMethod(final Object listenTarget, 
            final Method listenerMethod, final String condition, 
            final StateMachineEvent<T, S, E, C> event) {
        Class<?>[] parameterTypes = listenerMethod.getParameterTypes();
        final Map<String, Object> variables = Maps.newHashMap();
        variables.put(MvelScriptManager.VAR_STATE_MACHINE, event.getStateMachine());
        
        boolean isSatisfied = true;
        if(condition!=null && condition.length()>0) {
            isSatisfied = scriptManager.evalBoolean(condition, variables);
        }
        if(!isSatisfied) return null;
        
        if(parameterTypes.length == 0) {
            return ReflectUtils.invoke(listenerMethod, listenTarget);
        }
        // parameter values infer
        List<Object> parameterValues = Lists.newArrayList();
        for(Class<?> parameterType : parameterTypes) {
            if(parameterType.isAssignableFrom(AbstractStateMachine.this.getClass())) {
                parameterValues.add(event.getStateMachine());
            } else {
                parameterValues.add(null);
            }
        }
        return ReflectUtils.invoke(listenerMethod, listenTarget, parameterValues.toArray());
    }
    
    private Object invokeActionListenerMethod(final Object listenTarget, 
            final Method listenerMethod, final String condition, 
            final ActionEvent<T, S, E, C> event) {
        Class<?>[] parameterTypes = listenerMethod.getParameterTypes();
        
        final Map<String, Object> variables = Maps.newHashMap();
        variables.put(MvelScriptManager.VAR_FROM, event.getFrom());
        variables.put(MvelScriptManager.VAR_TO, event.getTo());
        variables.put(MvelScriptManager.VAR_EVENT, event.getEvent());
        variables.put(MvelScriptManager.VAR_CONTEXT, event.getContext());
        variables.put(MvelScriptManager.VAR_STATE_MACHINE, event.getStateMachine());
        if(event instanceof ExecActionExceptionEvent) {
            Exception e = ((ExecActionExceptionEvent<T, S, E, C>)event).getException();
            variables.put(MvelScriptManager.VAR_EXCEPTION, e);
        }
        
        boolean isSatisfied = true;
        if(condition!=null && condition.length()>0) {
            isSatisfied = scriptManager.evalBoolean(condition, variables);
        }
        if(!isSatisfied) return null;
        
        if(parameterTypes.length == 0) {
            return ReflectUtils.invoke(listenerMethod, listenTarget);
        }
        // parameter values infer
        List<Object> parameterValues = Lists.newArrayList();
        boolean isSourceStateSet = false, isTargetStateSet=false, isEventSet=false, isContextSet=false;
        for(Class<?> parameterType : parameterTypes) {
            if(!isSourceStateSet && parameterType.isAssignableFrom(typeOfState())) {
                parameterValues.add(event.getFrom());
                isSourceStateSet = true;
            } else if(!isTargetStateSet && parameterType.isAssignableFrom(typeOfState())) {
                parameterValues.add(event.getTo());
                isTargetStateSet = true;
            } else if(!isEventSet && parameterType.isAssignableFrom(typeOfEvent())) {
                parameterValues.add(event.getEvent());
                isEventSet = true;
            } else if(!isContextSet && parameterType.isAssignableFrom(typeOfContext())) {
                parameterValues.add(event.getContext());
                isContextSet = true;
            } else if(parameterType.isAssignableFrom(AbstractStateMachine.this.getClass())) {
                parameterValues.add(event.getStateMachine());
            } else if(parameterType.isAssignableFrom(Action.class)) {
                parameterValues.add(event.getExecutionTarget());
            } else if(parameterType==int[].class) {
                parameterValues.add(event.getMOfN());
            } else if(event instanceof ExecActionExceptionEvent && parameterType.isAssignableFrom(TransitionException.class)) {
                parameterValues.add(((ExecActionExceptionEvent<T, S, E, C>)event).getException());
            } else {
                parameterValues.add(null);
            }
        }
        return ReflectUtils.invoke(listenerMethod, listenTarget, parameterValues.toArray());
    }
    
    private Object invokeTransitionListenerMethod(final Object listenTarget, 
            final Method listenerMethod, final String condition, 
            final TransitionEvent<T, S, E, C> event) {
        Class<?>[] parameterTypes = listenerMethod.getParameterTypes();
        
        final Map<String, Object> variables = Maps.newHashMap();
        variables.put(MvelScriptManager.VAR_FROM, event.getSourceState());
        variables.put(MvelScriptManager.VAR_EVENT, event.getCause());
        variables.put(MvelScriptManager.VAR_CONTEXT, event.getContext());
        variables.put(MvelScriptManager.VAR_STATE_MACHINE, event.getStateMachine());
        if(event instanceof TransitionCompleteEvent) {
            variables.put(MvelScriptManager.VAR_TO, ((TransitionCompleteEvent<T, S, E, C>)event).getTargetState());
        } else if(event instanceof TransitionExceptionEvent) {
            variables.put(MvelScriptManager.VAR_TO, ((TransitionExceptionEvent<T, S, E, C>)event).getTargetState());
            variables.put(MvelScriptManager.VAR_EXCEPTION, ((TransitionExceptionEvent<T, S, E, C>)event).getException());
        }
        
        boolean isSatisfied = true;
        if(condition!=null && condition.length()>0) {
            isSatisfied = scriptManager.evalBoolean(condition, variables);
        }
        if(!isSatisfied) return null;
        
        if(parameterTypes.length == 0) {
            return ReflectUtils.invoke(listenerMethod, listenTarget);
        }
        // parameter values infer
        List<Object> parameterValues = Lists.newArrayList();
        boolean isSourceStateSet = false, isTargetStateSet=false, isEventSet=false, isContextSet=false;
        for(Class<?> parameterType : parameterTypes) {
            if(!isSourceStateSet && parameterType.isAssignableFrom(typeOfState())) {
                parameterValues.add(event.getSourceState());
                isSourceStateSet = true;
            } else if(!isTargetStateSet && event instanceof TransitionEndEvent && 
                    parameterType.isAssignableFrom(typeOfState())) {
                parameterValues.add(((TransitionEndEvent<T, S, E, C>)event).getTargetState());
                isTargetStateSet = true;
            } else if(!isTargetStateSet && event instanceof TransitionCompleteEvent && 
                    parameterType.isAssignableFrom(typeOfState())) {
                parameterValues.add(((TransitionCompleteEvent<T, S, E, C>)event).getTargetState());
                isTargetStateSet = true;
            } else if(!isTargetStateSet && event instanceof TransitionExceptionEvent && 
                    parameterType.isAssignableFrom(typeOfState()) && !isTargetStateSet) {
                parameterValues.add(((TransitionExceptionEvent<T, S, E, C>)event).getTargetState());
                isTargetStateSet = true;
            } else if(!isEventSet && parameterType.isAssignableFrom(typeOfEvent())) {
                parameterValues.add(event.getCause());
                isEventSet = true;
            } else if(!isContextSet && parameterType.isAssignableFrom(typeOfContext())) {
                parameterValues.add(event.getContext());
                isContextSet = true;
            } else if(parameterType.isAssignableFrom(AbstractStateMachine.this.getClass())) {
                parameterValues.add(event.getStateMachine());
            } else if(event instanceof TransitionExceptionEvent && 
                    parameterType.isAssignableFrom(TransitionException.class)) {
                parameterValues.add(((TransitionExceptionEvent<T, S, E, C>)event).getException());
            } else {
                parameterValues.add(null);
            }
        }
        return ReflectUtils.invoke(listenerMethod, listenTarget, parameterValues.toArray());
    }
    
    private void registerDeclarativeListener(final Object listenerMethodProvider, Method listenerMethod, 
            Observable listenTarget, Class<? extends Annotation> annotationClass, Class<?> listenerClass, 
            Class<?> eventClass) {
        Annotation anno = listenerMethod.getAnnotation(annotationClass);
        if(anno!=null) {
            Method whenMethod = ReflectUtils.getMethod(anno.getClass(), "when", new Class[0]);
            String whenCondition = StringUtils.EMPTY;
            if(whenMethod!=null) {
                whenCondition = (String)ReflectUtils.invoke(whenMethod, anno);
            }
            Field methodField = ReflectUtils.getField(listenerClass, "METHOD");
            if(methodField!=null && Modifier.isStatic(methodField.getModifiers())) {
                Method method = (Method)ReflectUtils.getStatic(methodField);
                Object proxyListener = newListenerMethodProxy(listenerMethodProvider, 
                        listenerMethod, listenerClass, whenCondition);
                listenTarget.addListener(eventClass, proxyListener, method);
            } else {
                logger.info("Cannot find static field named 'METHOD' on listener class '"+listenerClass+"'.");
            }
        }
    }
    
    private static final Class<?>[][] stateMachineListenerMapping = {
        {OnTransitionBegin.class,       TransitionBeginListener.class,      TransitionBeginEvent.class},
        {OnTransitionComplete.class,    TransitionCompleteListener.class,   TransitionCompleteEvent.class},
        {OnTransitionDecline.class,     TransitionDeclinedListener.class,   TransitionDeclinedEvent.class},
        {OnTransitionEnd.class,         TransitionEndListener.class,        TransitionEndEvent.class},
        {OnTransitionException.class,   TransitionExceptionListener.class,  TransitionExceptionEvent.class},
        {OnStateMachineStart.class,     StartListener.class,                StartEvent.class},
        {OnStateMachineTerminate.class, TerminateListener.class,            TerminateEvent.class}
    };
    
    private static final Class<?>[][] actionExecutorListenerMapping = {
        {OnBeforeActionExecuted.class,  BeforeExecActionListener.class,     BeforeExecActionEvent.class},
        {OnAfterActionExecuted.class,   AfterExecActionListener.class,      AfterExecActionEvent.class},
        {OnActionExecException.class,   ExecActionExceptionListener.class,  ExecActionExceptionEvent.class},
    };
    
    @Override
    @SuppressWarnings("unchecked")
    public void addDeclarativeListener(final Object listenerMethodProvider) {
        List<String> visitedMethods = Lists.newArrayList();
        for(final Method listenerMethod : listenerMethodProvider.getClass().getMethods()) {
            String methodSignature = listenerMethod.toString();
            if(visitedMethods.contains(methodSignature)) continue;
            visitedMethods.add(methodSignature);
            for(int i=0; i<stateMachineListenerMapping.length; ++i) {
                registerDeclarativeListener(listenerMethodProvider, listenerMethod, this, 
                        (Class<? extends Annotation>)stateMachineListenerMapping[i][0], 
                        stateMachineListenerMapping[i][1], stateMachineListenerMapping[i][2]);
            }
            
            for(int i=0; i<actionExecutorListenerMapping.length; ++i) {
                registerDeclarativeListener(listenerMethodProvider, listenerMethod, executor, 
                        (Class<? extends Annotation>)actionExecutorListenerMapping[i][0], 
                        actionExecutorListenerMapping[i][1], actionExecutorListenerMapping[i][2]);
            }
        }
    }
    
    @Override
    public void removeDeclarativeListener(final Object listenerMethodProvider) {
        removeDeclarativeListener(this, listenerMethodProvider);
        removeDeclarativeListener(executor, listenerMethodProvider);
    }
    
    /**
     * Internal use only
     * @return ActionExecutionService
     */
    public int getExecutorListenerSize() {
        return executor.getListenerSize();
    }
    
    @Override
    public String getIdentifier() {
        return data.read().identifier();
    }
    
    @Override
    public String getDescription() {
        StringBuilder builder = new StringBuilder();
        builder.append("id=\"").append(getIdentifier()).append("\" ");
        builder.append("fsm-type=\"").append(getClass().getName()).append("\" ");
        builder.append("state-type=\"").append(typeOfState().getName()).append("\" ");
        builder.append("event-type=\"").append(typeOfEvent().getName()).append("\" ");
        builder.append("context-type=\"").append(typeOfContext().getName()).append("\" ");
        
        Converter<E> eventConverter = ConverterProvider.INSTANCE.getConverter(typeOfEvent());
        if(getStartEvent()!=null) {
            builder.append("start-event=\"");
            builder.append(eventConverter.convertToString(getStartEvent()));
            builder.append("\" ");
        }
        if(getTerminateEvent()!=null) {
            builder.append("terminate-event=\"");
            builder.append(eventConverter.convertToString(getTerminateEvent()));
            builder.append("\" ");
        }
        if(getFinishEvent()!=null) {
            builder.append("finish-event=\"");
            builder.append(eventConverter.convertToString(getFinishEvent()));
            builder.append("\" ");
        }
        builder.append("context-insensitive=\"").append(isContextSensitive()).append("\" ");
        
        if(extraParamTypes!=null && extraParamTypes.length>0) {
            builder.append("extra-parameters=\"[");
            for(int i=0; i<extraParamTypes.length; ++i) {
                if(i>0) builder.append(",");
                builder.append(extraParamTypes[i].getName());
            }
            builder.append("]\" ");
        }
        return builder.toString();
    }
    
    @Override
    public String exportXMLDefinition(boolean beautifyXml) {
        SCXMLVisitor visitor = SquirrelProvider.getInstance().newInstance(SCXMLVisitor.class);
        accept(visitor);
        return visitor.getScxml(beautifyXml);
    }
    
    private void removeDeclarativeListener(Observable observable, final Object listenTarget) {
        observable.removeListener(new Predicate<ListenerMethod>() {
            @Override
            public boolean apply(ListenerMethod input) {
                return (input.getTarget() instanceof DeclarativeLisener) && 
                      ((DeclarativeLisener)input.getTarget()).getListenTarget()==listenTarget;
            }
        });
    }
    
    @Override
    public void addStateMachineListener(StateMachineListener<T, S, E, C> listener) {
        addListener(StateMachineEvent.class, listener, StateMachineListener.METHOD);
    }
    
    @Override
    public void removeStateMachineListener(StateMachineListener<T, S, E, C> listener) {
        removeListener(StateMachineEvent.class, listener, StateMachineListener.METHOD);
    }
    
    @Override
    public void addStartListener(StartListener<T, S, E, C> listener) {
        addListener(StartEvent.class, listener, StartListener.METHOD);
    }
    
    @Override
    public void removeStartListener(StartListener<T, S, E, C> listener) {
        removeListener(StartEvent.class, listener, StartListener.METHOD);
    }
    
    @Override
    public void addTerminateListener(TerminateListener<T, S, E, C> listener) {
        addListener(TerminateEvent.class, listener, TerminateListener.METHOD);
    }
    
    @Override
    public void removeTerminateListener(TerminateListener<T, S, E, C> listener) {
        removeListener(TerminateEvent.class, listener, TerminateListener.METHOD);
    }
    
    @Override
    public void addStateMachineExceptionListener(StateMachineExceptionListener<T, S, E, C> listener) {
        addListener(StateMachineExceptionEvent.class, listener, StateMachineExceptionListener.METHOD);
    }
    
    @Override
    public void removeStateMachineExceptionListener(StateMachineExceptionListener<T, S, E, C> listener) {
        removeListener(StateMachineExceptionEvent.class, listener, StateMachineExceptionListener.METHOD);
    }
    
    @Override
    public void addTransitionBeginListener(TransitionBeginListener<T, S, E, C> listener) {
        addListener(TransitionBeginEvent.class, listener, TransitionBeginListener.METHOD);
    }
    
    @Override
    public void removeTransitionBeginListener(TransitionBeginListener<T, S, E, C> listener) {
        removeListener(TransitionBeginEvent.class, listener, TransitionBeginListener.METHOD);
    }
    
    @Override
    public void addTransitionCompleteListener(TransitionCompleteListener<T, S, E, C> listener) {
        addListener(TransitionCompleteEvent.class, listener, TransitionCompleteListener.METHOD);
    }
    
    @Override
    public void removeTransitionCompleteListener(TransitionCompleteListener<T, S, E, C> listener) {
        removeListener(TransitionCompleteEvent.class, listener, TransitionCompleteListener.METHOD);
    }
    
    @Override
    public void addTransitionExceptionListener(TransitionExceptionListener<T, S, E, C> listener) {
        addListener(TransitionExceptionEvent.class, listener, TransitionExceptionListener.METHOD);
    }
    
    @Override
    public void removeTransitionExceptionListener(TransitionExceptionListener<T, S, E, C> listener) {
        removeListener(TransitionExceptionEvent.class, listener, TransitionExceptionListener.METHOD);
    }
    
    @Override
    public void addTransitionDeclinedListener(TransitionDeclinedListener<T, S, E, C> listener) {
        addListener(TransitionDeclinedEvent.class, listener, TransitionDeclinedListener.METHOD);
    }
    
    @Override
    public void removeTransitionDecleindListener(TransitionDeclinedListener<T, S, E, C> listener) {
        removeListener(TransitionDeclinedEvent.class, listener, TransitionDeclinedListener.METHOD);
    }
    
    @Override
    public void addTransitionEndListener(TransitionEndListener<T, S, E, C> listener) {
        addListener(TransitionEndEvent.class, listener, TransitionEndListener.METHOD);
    }
    
    @Override
    public void removeTransitionEndListener(TransitionEndListener<T, S, E, C> listener) {
        removeListener(TransitionEndListener.class, listener, TransitionEndListener.METHOD);
    }
    
    @Override
    public void addExecActionListener(BeforeExecActionListener<T, S, E, C> listener) {
    	executor.addExecActionListener(listener);
    }
	
    @Override
	public void removeExecActionListener(BeforeExecActionListener<T, S, E, C> listener) {
		executor.removeExecActionListener(listener);
	}
    
    public static abstract class AbstractStateMachineEvent<T extends StateMachine<T, S, E, C>, S, E, C> 
            implements StateMachine.StateMachineEvent<T, S, E, C> {
    	private final T stateMachine;
        public AbstractStateMachineEvent(T stateMachine) {
        	this.stateMachine = stateMachine;
        }
        
        @Override
        public T getStateMachine() {
            return stateMachine;
        }
    }
    
    public static class StartEventImpl<T extends StateMachine<T, S, E, C>, S, E, C> 
            extends AbstractStateMachineEvent<T, S, E, C> 
            implements StateMachine.StartEvent<T, S, E, C> {
        public StartEventImpl(T source) {
            super(source);
        }
    }
    
    public static class TerminateEventImpl<T extends StateMachine<T, S, E, C>, S, E, C> 
            extends AbstractStateMachineEvent<T, S, E, C> 
            implements StateMachine.TerminateEvent<T, S, E, C> {
        public TerminateEventImpl(T source) {
            super(source);
        }
    }
    
    public static class StateMachineExceptionEventImpl<T extends StateMachine<T, S, E, C>, S, E, C> 
            extends AbstractStateMachineEvent<T, S, E, C> 
            implements StateMachine.StateMachineExceptionEvent<T, S, E, C> {
        private final Exception e;
        public StateMachineExceptionEventImpl(Exception e, T source) {
            super(source);
            this.e = e;
        }
        
        @Override
        public Exception getException() {
            return e;
        }
    }
    
    public static abstract class AbstractTransitionEvent<T extends StateMachine<T, S, E, C>, S, E, C> 
            extends AbstractStateMachineEvent<T, S, E, C> 
            implements StateMachine.TransitionEvent<T, S, E, C> {
        private final S sourceState;
        private final E event;
        private final C context;
        public AbstractTransitionEvent(S sourceState, E event, C context, T stateMachine) {
            super(stateMachine);
            this.sourceState = sourceState;
            this.event = event;
            this.context = context;
        }
        
        @Override
        public S getSourceState() {
            return sourceState;
        }
        
        @Override
        public E getCause() {
            return event;
        }
        
        @Override
        public C getContext() {
            return context;
        }
    }
    
    public static class TransitionBeginEventImpl<T extends StateMachine<T, S, E, C>, S, E, C> 
            extends AbstractTransitionEvent<T, S, E, C> 
            implements StateMachine.TransitionBeginEvent<T, S, E, C> {
        public TransitionBeginEventImpl(S sourceState, E event, C context,T stateMachine) {
            super(sourceState, event, context, stateMachine);
        }
    }
    
    public static class TransitionCompleteEventImpl<T extends StateMachine<T, S, E, C>, S, E, C> 
            extends AbstractTransitionEvent<T, S, E, C> 
            implements StateMachine.TransitionCompleteEvent<T, S, E, C> {
        private final S targetState;
        public TransitionCompleteEventImpl(S sourceState, S targetState, E event, C context,T stateMachine) {
            super(sourceState, event, context, stateMachine);
            this.targetState = targetState;
        }
        
        @Override
        public S getTargetState() {
            return targetState;
        }
    }
    
    public static class TransitionExceptionEventImpl<T extends StateMachine<T, S, E, C>, S, E, C> 
            extends AbstractTransitionEvent<T, S, E, C> 
            implements StateMachine.TransitionExceptionEvent<T, S, E, C> {
        private final S targetState;
        private final TransitionException e;
        public TransitionExceptionEventImpl(TransitionException e, 
                S sourceState, S targetState, E event, C context,T stateMachine) {
            super(sourceState, event, context, stateMachine);
            this.targetState = targetState;
            this.e = e;
        }
        
        @Override
        public S getTargetState() {
            return targetState;
        }
        
        @Override
        public TransitionException getException() {
            return e;
        }
    }
    
    public static class TransitionDeclinedEventImpl<T extends StateMachine<T, S, E, C>, S, E, C> 
            extends AbstractTransitionEvent<T, S, E, C> 
            implements StateMachine.TransitionDeclinedEvent<T, S, E, C> {
        public TransitionDeclinedEventImpl(S sourceState, E event, C context,T stateMachine) {
            super(sourceState, event, context, stateMachine);
        }
    }
    
    public static class TransitionEndEventImpl<T extends StateMachine<T, S, E, C>, S, E, C> 
            extends AbstractTransitionEvent<T, S, E, C> 
            implements StateMachine.TransitionEndEvent<T, S, E, C> {
        private final S targetState;
        public TransitionEndEventImpl(S sourceState, S targetState, E event, C context,T stateMachine) {
            super(sourceState, event, context, stateMachine);
            this.targetState = targetState;
        }
        @Override
        public S getTargetState() {
            return targetState;
        }
    }
}
