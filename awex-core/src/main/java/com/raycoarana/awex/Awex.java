package com.raycoarana.awex;

import com.raycoarana.awex.callbacks.CancelCallback;
import com.raycoarana.awex.callbacks.DoneCallback;
import com.raycoarana.awex.callbacks.FailCallback;
import com.raycoarana.awex.exceptions.AbsentValueException;
import com.raycoarana.awex.exceptions.EmptyTasksException;
import com.raycoarana.awex.state.PoolStateImpl;
import com.raycoarana.awex.state.QueueStateImpl;
import com.raycoarana.awex.util.Map;

import java.util.Arrays;
import java.util.Collection;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class Awex {

    private final ThreadHelper mThreadHelper;
    private final Logger mLogger;
    private final AtomicLong mWorkIdProvider = new AtomicLong();
    private final Map<Integer, AwexTaskQueue> mTaskQueueMap;
    private final Map<Integer, Map<Integer, Worker>> mWorkers;
    private final PoolPolicy mPoolPolicy;
    private final AtomicInteger mThreadIdProvider = new AtomicInteger();
    private final ExecutorService mCallbackExecutor = Executors.newSingleThreadExecutor();
    private final Timer mTimer;
    private final Map<Task, Task> mTasks = Map.Provider.getSync();

    private AwexPromise mAbsentPromise;

    public Awex(ThreadHelper threadHelper, Logger logger, PoolPolicy poolPolicy) {
        mThreadHelper = threadHelper;
        mLogger = logger;
        mTaskQueueMap = Map.Provider.getSync();
        mWorkers = Map.Provider.getSync();
        mPoolPolicy = poolPolicy;
        mTimer = new Timer();

        initializeAbsentPromise();

        mPoolPolicy.initialize(new PoolManagerImpl());
    }

    private void initializeAbsentPromise() {
        mAbsentPromise = new AwexPromise<>(this);
        mAbsentPromise.reject(new AbsentValueException());
    }

    ThreadHelper provideUIThread() {
        return mThreadHelper;
    }

    Logger provideLogger() {
        return mLogger;
    }

    long provideWorkId() {
        return mWorkIdProvider.incrementAndGet();
    }

    public <Result, Progress> Promise<Result, Progress> submit(final Task<Result, Progress> task) {
        task.initialize(this);
        PoolStateImpl poolState = extractPoolState();
        mPoolPolicy.onTaskAdded(poolState, task);
        poolState.recycle();
        return task.getPromise();
    }

    private PoolStateImpl extractPoolState() {
        PoolStateImpl poolState = PoolStateImpl.get();
        extractQueueState(poolState);
        poolState.setTasks(mTasks);
        return poolState;
    }

    @SuppressWarnings("unchecked")
    private void extractQueueState(PoolStateImpl poolState) {
        Map<Integer, AwexTaskQueue> queues = mTaskQueueMap.clone();
        for (AwexTaskQueue queue : queues.values()) {
            QueueStateImpl queueState = QueueStateImpl.get(queue.getId(),
                    queue.size(),
                    queue.waiters());
            extractWorkersInfo(queueState);
            poolState.addQueue(queue.getId(), queueState);
        }
    }

    @SuppressWarnings("unchecked")
    private void extractWorkersInfo(QueueStateImpl queueState) {
        Map<Integer, Worker> workers = mWorkers.get(queueState.getId()).clone();
        for (Worker worker : workers.values()) {
            queueState.addWorker(worker.getId(), worker.takeState());
        }
    }

    void submit(Runnable runnable) {
        mCallbackExecutor.submit(runnable);
    }

    public <Result, Progress> void cancel(Task<Result, Progress> task, boolean mayInterrupt) {
        synchronized (this) {
            task.softCancel();
            AwexTaskQueue taskQueue = task.getQueue();
            if (taskQueue != null) {
                if (!taskQueue.remove(task) && mayInterrupt) {
                    Worker worker = task.getWorker();
                    if (worker != null) {
                        worker.interrupt();
                        mWorkers.get(taskQueue.getId()).remove(worker.getId());
                    }
                }
            }
        }
    }

	/**
     * Creates a new promise that will be resolved with the result of the first task which completes correctly.
     * Task will execute sequentially until the first one is resolved.
     * If all the promises fail promise will be rejected with the last exception thrown.
     * In case the collection of tasks is empty the promise will be rejected with an EmptyTasksException.
     *
     * @param tasks source tasks to execute sequentially
     * @param <Result> type of result of the promises
     * @param <Progress> type of progress of the promises
     * @returna new promise that will be resolved with the first task completed correctly
     */
    @SafeVarargs
    public final <Result, Progress> Promise<Result, Progress> sequentiallyUntilFirstDone(final
    Task<Result, Progress>... tasks) {
        return sequentiallyUntilFirstDone(Arrays.asList(tasks));
    }

    /**
     * Creates a new promise that will be resolved with the result of the first task which completes correctly.
     * Task will execute sequentially until the first one is resolved.
     * If all the promises fail promise will be rejected with the last exception thrown.
     * In case the collection of tasks is empty the promise will be rejected with an EmptyTasksException.
     *
     * @param tasks source tasks to execute sequentially
     * @param <Result> type of result of the promises
     * @param <Progress> type of progress of the promises
     * @returna new promise that will be resolved with the first task completed correctly
     */
    public <Result, Progress> Promise<Result, Progress> sequentiallyUntilFirstDone(final
    Collection<Task<Result, Progress>> tasks) {
        ResolvablePromise<Result, Progress> promise = newAwexPromise();
        int count = 0;
        for (final Task<Result, Progress> task : tasks) {
            if (count == 0) {
                submit(task).pipe(promise);
            } else {
                final ResolvablePromise<Result, Progress> nextPromise = newAwexPromise();
                promise.fail(new FailCallback() {
                    @Override
                    public void onFail(Exception exception) {
                        submit(task).pipe(nextPromise);
                    }
                }).done(new DoneCallback<Result>() {
                    @Override
                    public void onDone(Result result) {
                        nextPromise.resolve(result);
                    }
                }).cancel(new CancelCallback() {
                    @Override
                    public void onCancel() {
                        nextPromise.cancelTask();
                    }
                });
                promise = nextPromise;
            }
            count++;
        }

        if (count == 0) {
            promise.reject(new EmptyTasksException());
        }

        return promise;
    }


    /**
     * Creates a new promise that will be resolved only if all promises get resolved. If any of the
     * promises is rejected the created promise will be rejected.
     *
     * @param promises   source promises
     * @param <Result>   type of result of the promises
     * @param <Progress> type of progress of the promises
     * @return a new promise that only will be resolve if all promises get resolved, otherwise it
     * will fail.
     */
    @SafeVarargs
    public final <Result, Progress> Promise<Collection<Result>, Progress> allOf(Promise<Result, Progress>... promises) {
        return allOf(Arrays.asList(promises));
    }

    /**
     * Creates a new promise that will be resolved only if all promises get resolved. If any of the
     * promises is rejected the created promise will be rejected.
     *
     * @param promises   source promises
     * @param <Result>   type of result of the promises
     * @param <Progress> type of progress of the promises
     * @return a new promise that only will be resolve if all promises get resolved, otherwise it
     * will fail.
     */
    public <Result, Progress> Promise<Collection<Result>, Progress> allOf(Collection<Promise<Result, Progress>> promises) {
        return new AllOfPromise<>(this, promises);
    }

    /**
     * Creates a new promise that will be resolved if any promise get resolved.
     *
     * @param promises   source promises
     * @param <Result>   type of result of the promises
     * @param <Progress> type of progress of the promises
     * @return a new promise that will be resolve if any promise get resolved, otherwise it
     * will fail.
     */
    @SafeVarargs
    public final <Result, Progress> Promise<Result, Progress> anyOf(Promise<Result, Progress>... promises) {
        return anyOf(Arrays.asList(promises));
    }

    /**
     * Creates a new promise that will be resolved if any promise get resolved.
     *
     * @param promises   source promises
     * @param <Result>   type of result of the promises
     * @param <Progress> type of progress of the promises
     * @return a new promise that will be resolve if any promise get resolved, otherwise it
     * will fail.
     */
    public <Result, Progress> Promise<Result, Progress> anyOf(Collection<Promise<Result, Progress>> promises) {
        return new AnyOfPromise<>(this, promises);
    }

    /**
     * Creates a new promise that will be resolved when all promises finishes its execution, that
     * is, get resolved or rejected.
     *
     * @param promises   source promises
     * @param <Result>   type of result of the promises
     * @param <Progress> type of progress of the promises
     * @return a new promise that will be resolved when all promises finishes its execution.
     */
    @SafeVarargs
    public final <Result, Progress> Promise<MultipleResult<Result, Progress>, Progress> afterAll(Promise<Result, Progress>... promises) {
        return afterAll(Arrays.asList(promises));
    }

    /**
     * Creates a new promise that will be resolved when all promises finishes its execution, that
     * is, get resolved or rejected.
     *
     * @param promises   source promises
     * @param <Result>   type of result of the promises
     * @param <Progress> type of progress of the promises
     * @return a new promise that will be resolved when all promises finishes its execution.
     */
    public <Result, Progress> Promise<MultipleResult<Result, Progress>, Progress> afterAll(Collection<Promise<Result, Progress>> promises) {
        return new AfterAllPromise<>(this, promises);
    }

    /**
     * Creates an already resolved promise with the value passed as parameter
     *
     * @param value    value to use to resolve the promise, in case that the value is null a rejected promise is returned
     * @param <Result> type of the result
     * @param <Progress> type of the progress
     * @return a promise already resolved
     */
    @SuppressWarnings("unchecked")
    public <Result, Progress> Promise<Result, Progress> of(Result value) {
        if (value == null) {
            return (Promise<Result, Progress>) mAbsentPromise;
        } else {
            AwexPromise<Result, Progress> promise = new AwexPromise<>(this);
            promise.resolve(value);
            return promise;
        }
    }

    public <Result, Progress> ResolvablePromise<Result, Progress> newAwexPromise() {
        return new AwexPromise<>(this);
    }

    /**
     * Returns an already rejected promise
     *
     * @param <Result>   type of result
     * @param <Progress> type of progress
     * @return a promise already rejected
     */
    @SuppressWarnings("unchecked")
    public <Result, Progress> Promise<Result, Progress> absent() {
        return (Promise<Result, Progress>) mAbsentPromise;
    }

    int getNumberOfThreads() {
        return Runtime.getRuntime().availableProcessors();
    }

    void schedule(TimerTask timerTask, int timeout) {
        if (timeout > 0) {
            mTimer.schedule(timerTask, timeout);
        }
    }

    <Result, Progress> void onTaskQueueTimeout(Task<Result, Progress> task) {
        PoolStateImpl poolState = extractPoolState();
        mPoolPolicy.onTaskQueueTimeout(poolState, task);
        poolState.recycle();
    }

    <Result, Progress> void onTaskExecutionTimeout(Task<Result, Progress> task) {
        PoolStateImpl poolState = extractPoolState();
        mPoolPolicy.onTaskExecutionTimeout(poolState, task);
        poolState.recycle();
    }

    @Override
    public String toString() {
        return extractPoolState().toString();
    }

    private final WorkerListener mWorkerListener = new WorkerListener() {

        @Override
        public void onTaskFinished(Task task) {
            PoolStateImpl poolState = extractPoolState();
            mPoolPolicy.onTaskFinished(poolState, task);
            poolState.recycle();
            mTasks.remove(task);
        }

    };

    private class PoolManagerImpl implements PoolManager {

        @Override
        public synchronized void createQueue(int queueId) {
            if (mTaskQueueMap.containsKey(queueId)) {
                throw new IllegalStateException("Trying to create a queue with an id that already exists");
            }

            mTaskQueueMap.put(queueId, new AwexTaskQueue(queueId));
        }

        @Override
        public synchronized void removeQueue(int queueId) {
            AwexTaskQueue awexTaskQueue = mTaskQueueMap.remove(queueId);
            Map<Integer, Worker> workersOfQueue = mWorkers.remove(queueId);
            for (Worker worker : workersOfQueue.values()) {
                worker.die();
            }
            awexTaskQueue.destroy();
            for (Worker worker : workersOfQueue.values()) {
                worker.interrupt();
            }
        }

        @Override
        public void executeImmediately(Task task) {
            task.markQueue(null);
            new RealTimeWorker(mThreadIdProvider.incrementAndGet(), task, mThreadHelper, mLogger);
        }

        @Override
        public void queueTask(int queueId, Task task) {
            AwexTaskQueue taskQueue = mTaskQueueMap.get(queueId);
            task.markQueue(taskQueue);
            taskQueue.insert(task);
            mTasks.put(task, task);
        }

        @SuppressWarnings("unchecked")
        @Override
        public void mergeTask(Task taskInQueue, final Task taskToMerge) {
            if (taskInQueue.getState() <= Task.STATE_NOT_QUEUE) {
                throw new IllegalStateException("Task not queued");
            }
            if (taskToMerge.getState() != Task.STATE_NOT_QUEUE) {
                throw new IllegalStateException("Task already queue");
            }

            taskToMerge.markQueue(null);
            taskInQueue.getPromise().pipe(taskToMerge.getPromise());
        }

        @Override
        public synchronized int createWorker(int queueId, int priority) {
            AwexTaskQueue taskQueue = mTaskQueueMap.get(queueId);
            Map<Integer, Worker> workersOfQueue = mWorkers.get(queueId);
            if (workersOfQueue == null) {
                workersOfQueue = Map.Provider.get();
                mWorkers.put(queueId, workersOfQueue);
            }

            int id = mThreadIdProvider.incrementAndGet();
            workersOfQueue.put(id, new Worker(id, priority, taskQueue, mThreadHelper, mLogger, mWorkerListener));
            return id;
        }

        @Override
        public synchronized void removeWorker(int queueId, int workerId, boolean shouldInterrupt) {
            Worker worker = mWorkers.get(queueId).remove(workerId);
            if (worker != null) {
                if (shouldInterrupt) {
                    worker.interrupt();
                } else {
                    worker.die();
                }
            }
        }

    }

}