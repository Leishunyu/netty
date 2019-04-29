/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util;

import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;

import static io.netty.util.internal.StringUtil.simpleClassName;

/**
 * A {@link Timer} optimized for approximated I/O timeout scheduling.
 *
 * <h3>Tick Duration</h3>
 *
 * As described with 'approximated', this timer does not execute the scheduled
 * {@link TimerTask} on time.  {@link HashedWheelTimer}, on every tick, will
 * check if there are any {@link TimerTask}s behind the schedule and execute
 * them.
 * <p>
 * You can increase or decrease the accuracy of the execution timing by
 * specifying smaller or larger tick duration in the constructor.  In most
 * network applications, I/O timeout does not need to be accurate.  Therefore,
 * the default tick duration is 100 milliseconds and you will not need to try
 * different configurations in most cases.
 *
 * <h3>Ticks per Wheel (Wheel Size)</h3>
 *
 * {@link HashedWheelTimer} maintains a data structure called 'wheel'.
 * To put simply, a wheel is a hash table of {@link TimerTask}s whose hash
 * function is 'dead line of the task'.  The default number of ticks per wheel
 * (i.e. the size of the wheel) is 512.  You could specify a larger value
 * if you are going to schedule a lot of timeouts.
 *
 * <h3>Do not create many instances.</h3>
 *
 * {@link HashedWheelTimer} creates a new thread whenever it is instantiated and
 * started.  Therefore, you should make sure to create only one instance and
 * share it across your application.  One of the common mistakes, that makes
 * your application unresponsive, is to create a new instance for every connection.
 *
 * <h3>Implementation Details</h3>
 *
 * {@link HashedWheelTimer} is based on
 * <a href="http://cseweb.ucsd.edu/users/varghese/">George Varghese</a> and
 * Tony Lauck's paper,
 * <a href="http://cseweb.ucsd.edu/users/varghese/PAPERS/twheel.ps.Z">'Hashed
 * and Hierarchical Timing Wheels: data structures to efficiently implement a
 * timer facility'</a>.  More comprehensive slides are located
 * <a href="http://www.cse.wustl.edu/~cdgill/courses/cs6874/TimingWheels.ppt">here</a>.
 */
public class HashedWheelTimer implements Timer {

    static final InternalLogger logger =
            InternalLoggerFactory.getInstance(HashedWheelTimer.class);

    //实例计数器
    private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger();
    //实例过多警告值
    private static final AtomicBoolean WARNED_TOO_MANY_INSTANCES = new AtomicBoolean();
    //实际数量限制
    private static final int INSTANCE_COUNT_LIMIT = 64;
    private static final long MILLISECOND_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    //资源泄漏检测器
    private static final ResourceLeakDetector<HashedWheelTimer> leakDetector = ResourceLeakDetectorFactory.instance()
            .newResourceLeakDetector(HashedWheelTimer.class, 1);
    //工作线程状态更新
    private static final AtomicIntegerFieldUpdater<HashedWheelTimer> WORKER_STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimer.class, "workerState");
    //泄漏值
    private final ResourceLeakTracker<HashedWheelTimer> leak;
    //工作对象
    private final Worker worker = new Worker();
    //工作线程
    private final Thread workerThread;

    public static final int WORKER_STATE_INIT = 0;
    public static final int WORKER_STATE_STARTED = 1;
    public static final int WORKER_STATE_SHUTDOWN = 2;
    @SuppressWarnings({ "unused", "FieldMayBeFinal" })
    private volatile int workerState; // 0 - init, 1 - started, 2 - shut down
    //tick的时长，也就是指针多久转一格
    private final long tickDuration;
    //时间轮数组
    private final HashedWheelBucket[] wheel;
    // 这是一个标示符，用来快速计算任务应该呆的格子。
    private final int mask;
    //开始时间已初始化
    private final CountDownLatch startTimeInitialized = new CountDownLatch(1);
    //任务队列
    private final Queue<HashedWheelTimeout> timeouts = PlatformDependent.newMpscQueue();
    //关闭的任务队列
    private final Queue<HashedWheelTimeout> cancelledTimeouts = PlatformDependent.newMpscQueue();
    //等待超时
    private final AtomicLong pendingTimeouts = new AtomicLong(0);
    //最大等待超时
    private final long maxPendingTimeouts;
    //开始时间
    private volatile long startTime;

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}), default tick duration, and
     * default number of ticks per wheel.
     */
    public HashedWheelTimer() {
        this(Executors.defaultThreadFactory());
    }

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}) and default number of ticks
     * per wheel.
     *
     * @param tickDuration   the duration between tick
     * @param unit           the time unit of the {@code tickDuration}
     * @throws NullPointerException     if {@code unit} is {@code null}
     * @throws IllegalArgumentException if {@code tickDuration} is &lt;= 0
     */
    public HashedWheelTimer(long tickDuration, TimeUnit unit) {
        this(Executors.defaultThreadFactory(), tickDuration, unit);
    }

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}).
     *
     * @param tickDuration   the duration between tick
     * @param unit           the time unit of the {@code tickDuration}
     * @param ticksPerWheel  the size of the wheel
     * @throws NullPointerException     if {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(long tickDuration, TimeUnit unit, int ticksPerWheel) {
        this(Executors.defaultThreadFactory(), tickDuration, unit, ticksPerWheel);
    }

    /**
     * Creates a new timer with the default tick duration and default number of
     * ticks per wheel.
     *
     * @param threadFactory  a {@link ThreadFactory} that creates a
     *                       background {@link Thread} which is dedicated to
     *                       {@link TimerTask} execution.
     * @throws NullPointerException if {@code threadFactory} is {@code null}
     */
    public HashedWheelTimer(ThreadFactory threadFactory) {
        this(threadFactory, 100, TimeUnit.MILLISECONDS);
    }

    /**
     * Creates a new timer with the default number of ticks per wheel.
     *
     * @param threadFactory  a {@link ThreadFactory} that creates a
     *                       background {@link Thread} which is dedicated to
     *                       {@link TimerTask} execution.
     * @param tickDuration   the duration between tick
     * @param unit           the time unit of the {@code tickDuration}
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if {@code tickDuration} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory, long tickDuration, TimeUnit unit) {
        this(threadFactory, tickDuration, unit, 512);
    }

    /**
     * Creates a new timer.
     *
     * @param threadFactory  a {@link ThreadFactory} that creates a
     *                       background {@link Thread} which is dedicated to
     *                       {@link TimerTask} execution.
     * @param tickDuration   the duration between tick
     * @param unit           the time unit of the {@code tickDuration}
     * @param ticksPerWheel  the size of the wheel
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory,
            long tickDuration, TimeUnit unit, int ticksPerWheel) {
        this(threadFactory, tickDuration, unit, ticksPerWheel, true);
    }

    /**
     * Creates a new timer.
     *
     * @param threadFactory        a {@link ThreadFactory} that creates a
     *                             background {@link Thread} which is dedicated to
     *                             {@link TimerTask} execution.
     * @param tickDuration         the duration between tick
     * @param unit                 the time unit of the {@code tickDuration}
     * @param ticksPerWheel        the size of the wheel
     * @param leakDetection        {@code true} if leak detection should be enabled always,
     *                             if false it will only be enabled if the worker thread is not
     *                             a daemon thread.
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(
        ThreadFactory threadFactory,
        long tickDuration, TimeUnit unit, int ticksPerWheel, boolean leakDetection) {
        this(threadFactory, tickDuration, unit, ticksPerWheel, leakDetection, -1);
    }

    /**
     * Creates a new timer.
     *
     * @param threadFactory        a {@link ThreadFactory} that creates a
     *                             background {@link Thread} which is dedicated to
     *                             {@link TimerTask} execution.
     * @param tickDuration         the duration between tick
     * @param unit                 the time unit of the {@code tickDuration}
     * @param ticksPerWheel        the size of the wheel
     * @param leakDetection        {@code true} if leak detection should be enabled always,
     *                             if false it will only be enabled if the worker thread is not
     *                             a daemon thread.
     * @param  maxPendingTimeouts  The maximum number of pending timeouts after which call to
     *                             {@code newTimeout} will result in
     *                             {@link java.util.concurrent.RejectedExecutionException}
     *                             being thrown. No maximum pending timeouts limit is assumed if
     *                             this value is 0 or negative.
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory, // 用来创建worker线程
            long tickDuration,// tick的时长，也就是指针多久转一格
            TimeUnit unit, // tickDuration的时间单位
            int ticksPerWheel, // 一圈有几格
            boolean leakDetection, // 是否开启内存泄露检测
            long maxPendingTimeouts  //最大挂起超时次数
    ) {

        if (threadFactory == null) {
            throw new NullPointerException("threadFactory");
        }
        if (unit == null) {
            throw new NullPointerException("unit");
        }
        if (tickDuration <= 0) {
            throw new IllegalArgumentException("tickDuration must be greater than 0: " + tickDuration);
        }
        if (ticksPerWheel <= 0) {
            throw new IllegalArgumentException("ticksPerWheel must be greater than 0: " + ticksPerWheel);
        }

        // 将ticksPerWheel标准化为2的幂并初始化轮子.
        wheel = createWheel(ticksPerWheel);
        // 这是一个标示符，用来快速计算任务应该呆的格子。
        // 我们知道，给定一个deadline的定时任务，其应该呆的格子=deadline%wheel.length.但是%操作是个相对耗时的操作，所以使用一种变通的位运算代替：
        // 因为一圈的长度为2的n次方，mask = 2^n-1后低位将全部是1，然后deadline&mast == deadline%wheel.length
        // java中的HashMap也是使用这种处理方法
        mask = wheel.length - 1;

        // 转换成纳秒处理
        long duration = unit.toNanos(tickDuration);

        // 校验是否存在溢出。即指针转动的时间间隔不能太长而导致tickDuration*wheel.length>Long.MAX_VALUE
        if (duration >= Long.MAX_VALUE / wheel.length) {
            throw new IllegalArgumentException(String.format(
                    "tickDuration: %d (expected: 0 < tickDuration in nanos < %d",
                    tickDuration, Long.MAX_VALUE / wheel.length));
        }

        if (duration < MILLISECOND_NANOS) {
            if (logger.isWarnEnabled()) {
                logger.warn("Configured tickDuration %d smaller then %d, using 1ms.",
                            tickDuration, MILLISECOND_NANOS);
            }
            this.tickDuration = MILLISECOND_NANOS;
        } else {
            this.tickDuration = duration;
        }

        // 创建worker线程
        workerThread = threadFactory.newThread(worker);

        // 这里默认是启动内存泄露检测：当HashedWheelTimer实例超过当前cpu可用核数*4的时候，将发出警告
        leak = leakDetection || !workerThread.isDaemon() ? leakDetector.track(this) : null;

        this.maxPendingTimeouts = maxPendingTimeouts;

        if (INSTANCE_COUNTER.incrementAndGet() > INSTANCE_COUNT_LIMIT &&
            WARNED_TOO_MANY_INSTANCES.compareAndSet(false, true)) {
            reportTooManyInstances();
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            // 如果这个对象将被GC
            // 我们尚未关闭然后我们想确保减少活动实例数. 通过原子减来保证实例逐个被关闭
            if (WORKER_STATE_UPDATER.getAndSet(this, WORKER_STATE_SHUTDOWN) != WORKER_STATE_SHUTDOWN) {
                INSTANCE_COUNTER.decrementAndGet();
            }
        }
    }

    private static HashedWheelBucket[] createWheel(int ticksPerWheel) {
        if (ticksPerWheel <= 0) {
            throw new IllegalArgumentException(
                    "ticksPerWheel must be greater than 0: " + ticksPerWheel);
        }
        if (ticksPerWheel > 1073741824) {
            throw new IllegalArgumentException(
                    "ticksPerWheel may not be greater than 2^30: " + ticksPerWheel);
        }
        // 初始化ticksPerWheel的值为不小于ticksPerWheel的最小2的n次方
        ticksPerWheel = normalizeTicksPerWheel(ticksPerWheel);
        // 初始化wheel数组
        HashedWheelBucket[] wheel = new HashedWheelBucket[ticksPerWheel];
        for (int i = 0; i < wheel.length; i ++) {
            wheel[i] = new HashedWheelBucket();
        }
        return wheel;
    }

    // 初始化ticksPerWheel的值为不小于ticksPerWheel的最小2的n次方
    private static int normalizeTicksPerWheel(int ticksPerWheel) {
        int normalizedTicksPerWheel = 1;
        while (normalizedTicksPerWheel < ticksPerWheel) {
            normalizedTicksPerWheel <<= 1;
        }
        return normalizedTicksPerWheel;
    }

    /**
     * Starts the background thread explicitly.  The background thread will
     * start automatically on demand even if you did not call this method.
     *
     * @throws IllegalStateException if this timer has been
     *                               {@linkplain #stop() stopped} already
     */
    // 启动时间轮。这个方法其实不需要显示的主动调用，因为在添加定时任务（newTimeout()方法）的时候会自动调用此方法。
    // 这个是合理的设计，因为如果时间轮里根本没有定时任务，启动时间轮也是空耗资源
    public void start() {
        // 判断当前时间轮的状态，如果是初始化，则启动worker线程，启动整个时间轮；如果已经启动则略过；如果是已经停止，则报错
        // 这里是一个Lock Free的设计。因为可能有多个线程调用启动方法，这里使用AtomicIntegerFieldUpdater原子的更新时间轮的状态
        switch (WORKER_STATE_UPDATER.get(this)) {
            case WORKER_STATE_INIT:
                if (WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_INIT, WORKER_STATE_STARTED)) {
                    workerThread.start();
                }
                break;
            case WORKER_STATE_STARTED:
                break;
            case WORKER_STATE_SHUTDOWN:
                throw new IllegalStateException("cannot be started once stopped");
            default:
                throw new Error("Invalid WorkerState");
        }

        // 等待worker线程初始化时间轮的启动时间
        while (startTime == 0) {
            try {
                startTimeInitialized.await();
            } catch (InterruptedException ignore) {
                // Ignore - it will be ready very soon.
            }
        }
    }

    @Override
    public Set<Timeout> stop() {
        // worker线程不能停止时间轮，也就是加入的定时任务，不能调用这个方法。
        // 不然会有恶意的定时任务调用这个方法而造成大量定时任务失效
        if (Thread.currentThread() == workerThread) {
            throw new IllegalStateException(
                    HashedWheelTimer.class.getSimpleName() +
                            ".stop() cannot be called from " +
                            TimerTask.class.getSimpleName());
        }
        // 尝试CAS替换当前状态为“停止：2”。如果失败，则当前时间轮的状态只能是“初始化：0”或者“停止：2”。直接将当前状态设置为“停止：2“
        if (!WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_STARTED, WORKER_STATE_SHUTDOWN)) {
            // workerState can be 0 or 2 at this moment - let it always be 2.
            if (WORKER_STATE_UPDATER.getAndSet(this, WORKER_STATE_SHUTDOWN) != WORKER_STATE_SHUTDOWN) {
                INSTANCE_COUNTER.decrementAndGet();
                if (leak != null) {
                    boolean closed = leak.close(this);
                    assert closed;
                }
            }

            return Collections.emptySet();
        }

        try {
            boolean interrupted = false;
            //如果工作线程存活
            while (workerThread.isAlive()) {
                //中断工作线程
                //interrupt()不能中断在运行中的线程，它只能改变中断状态而已。
                workerThread.interrupt();
                try {
                    //工作线程加入本地线程
                    workerThread.join(100);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            //如果发现线程已经被打上中断标识
            if (interrupted) {
                //改变当前线程状态
                Thread.currentThread().interrupt();
            }
        } finally {
            INSTANCE_COUNTER.decrementAndGet();
            if (leak != null) {
                boolean closed = leak.close(this);
                assert closed;
            }
        }
        return worker.unprocessedTimeouts();
    }

    @Override
    public Timeout newTimeout(TimerTask task, long delay, TimeUnit unit) {
        // 参数校验
        if (task == null) {
            throw new NullPointerException("task");
        }
        if (unit == null) {
            throw new NullPointerException("unit");
        }

        long pendingTimeoutsCount = pendingTimeouts.incrementAndGet();
        //待处理超时数 pendingTimeoutsCount 大于或等于允许的最大挂起
        if (maxPendingTimeouts > 0 && pendingTimeoutsCount > maxPendingTimeouts) {
            pendingTimeouts.decrementAndGet();
            throw new RejectedExecutionException("Number of pending timeouts ("
                + pendingTimeoutsCount + ") is greater than or equal to maximum allowed pending "
                + "timeouts (" + maxPendingTimeouts + ")");
        }
        // 如果时间轮没有启动，则启动
        start();

        //将超时添加到超时队列，该队列将在下一个时钟处理。
        //在处理过程中，所有排队的HashedWheelTimeouts都将添加到正确的HashedWheelBucket中。
        long deadline = System.nanoTime() + unit.toNanos(delay) - startTime;

        // 防止溢出。
        if (delay > 0 && deadline < 0) {
            deadline = Long.MAX_VALUE;
        }
        // 这里定时任务不是直接加到对应的格子中，而是先加入到一个队列里，然后等到下一个tick的时候，会从队列里取出最多100000个任务加入到指定的格子中
        HashedWheelTimeout timeout = new HashedWheelTimeout(this, task, deadline);
        timeouts.add(timeout);
        return timeout;
    }

    /**
     * Returns the number of pending timeouts of this {@link Timer}.
     */
    public long pendingTimeouts() {
        return pendingTimeouts.get();
    }

    private static void reportTooManyInstances() {
        if (logger.isErrorEnabled()) {
            String resourceType = simpleClassName(HashedWheelTimer.class);
            logger.error("You are creating too many " + resourceType + " instances. " +
                    resourceType + " is a shared resource that must be reused across the JVM," +
                    "so that only a few instances are created.");
        }
    }

    //Worker是时间轮的核心线程类。tick的转动，过期任务的处理都是在这个线程中处理的。
    private final class Worker implements Runnable {
        private final Set<Timeout> unprocessedTimeouts = new HashSet<Timeout>();

        private long tick;

        @Override
        public void run() {
            // 初始化startTime.只有所有任务的的deadline都是想对于这个时间点
            startTime = System.nanoTime();
            if (startTime == 0) {
                // 由于System.nanoTime()可能返回0，甚至负数。并且0是一个标示符，用来判断startTime是否被初始化，所以当startTime=0的时候，重新赋值为1
                startTime = 1;
            }

            // 唤醒阻塞在start()的线程
            startTimeInitialized.countDown();
            // 只要时间轮的状态为WORKER_STATE_STARTED，就循环的“转动”tick，循环判断响应格子中的到期任务
            do {
                // waitForNextTick方法主要是计算下次tick的时间, 然后sleep到下次tick
                // 返回值就是System.nanoTime() - startTime, 也就是Timer启动后到这次tick, 所过去的时间
                final long deadline = waitForNextTick();
                if (deadline > 0) {   // 可能溢出或者被中断的时候会返回负数, 所以小于等于0不管
                    // 获取tick对应的格子索引
                    int idx = (int) (tick & mask);
                    // 移除被取消的任务
                    processCancelledTasks();
                    HashedWheelBucket bucket =
                            wheel[idx];
                    // 从任务队列中取出任务加入到对应的格子中
                    transferTimeoutsToBuckets();
                    System.out.println("bucket"+bucket+",idx"+idx);
                    // 过期执行格子中的任务
                    bucket.expireTimeouts(deadline);
                    tick++;
                }
            } while (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_STARTED);

            // 这里应该是时间轮停止了，清除所有格子中的任务，并加入到未处理任务列表，以供stop()方法返回
            for (HashedWheelBucket bucket: wheel) {
                bucket.clearTimeouts(unprocessedTimeouts);
            }
            // 将还没有加入到格子中的待处理定时任务队列中的任务取出，如果是未取消的任务，则加入到未处理任务队列中，以供stop()方法返回
            for (;;) {
                HashedWheelTimeout timeout = timeouts.poll();
                if (timeout == null) {
                    break;
                }
                if (!timeout.isCancelled()) {
                    unprocessedTimeouts.add(timeout);
                }
            }
            // 处理取消的任务
            processCancelledTasks();
        }

        // 将newTimeout()方法中加入到待处理定时任务队列中的任务加入到指定的格子中
        private void transferTimeoutsToBuckets() {
            // 每次tick只处理10w个任务，以免阻塞worker线程
            // adds new timeouts in a loop.
            for (int i = 0; i < 100000; i++) {
                HashedWheelTimeout timeout = timeouts.poll();
                //System.out.println("当前times.size"+timeouts.size());
                // 如果没有任务了，直接跳出循环
                if (timeout == null) {
                    // all processed
                    break;
                }
                // 还没有放入到格子中就取消了，直接略过
                if (timeout.state() == HashedWheelTimeout.ST_CANCELLED) {
                    // Was cancelled in the meantime.
                    continue;
                }
                // 计算任务需要经过多少个tick
                long calculated = timeout.deadline / tickDuration;
                // 计算任务的轮数
                timeout.remainingRounds = (calculated - tick) / wheel.length;

                //如果任务在timeouts队列里面放久了, 以至于已经过了执行时间, 这个时候就使用当前tick, 也就是放到当前bucket, 此方法调用完后就会被执行.
                final long ticks = Math.max(calculated, tick); // Ensure we don't schedule for past.
                System.out.println("tick:"+ticks);
                int stopIndex = (int) (ticks & mask);
                // 将任务加入到响应的格子中
                HashedWheelBucket bucket = wheel[stopIndex];
                bucket.addTimeout(timeout);
            }
        }

        // 将取消的任务取出，并从格子中移除
        private void processCancelledTasks() {
            for (;;) {
                HashedWheelTimeout timeout = cancelledTimeouts.poll();
                if (timeout == null) {
                    // all processed
                    break;
                }
                try {
                    timeout.remove();
                } catch (Throwable t) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("An exception was thrown while process a cancellation task", t);
                    }
                }
            }
        }

        /**
         * calculate goal nanoTime from startTime and current tick number,
         * then wait until that goal has been reached.
         * @return Long.MIN_VALUE if received a shutdown request,
         * current time otherwise (with Long.MIN_VALUE changed by +1)
         */
        //sleep, 直到下次tick到来, 然后返回该次tick和启动时间之间的时长
        private long waitForNextTick() {
            //下次tick的时间点, 用于计算需要sleep的时间
            long deadline = tickDuration * (tick + 1);

            for (;;) {
                // 计算需要sleep的时间, 之所以加999999后再除10000000, 是为了保证足够的sleep时间
                // 例如：当deadline - currentTime=2000002的时候，如果不加999999，则只睡了2ms，
                // 而2ms其实是未到达deadline这个时间点的，所有为了使上述情况能sleep足够的时间，加上999999后，会多睡1ms
                final long currentTime = System.nanoTime() - startTime;
                long sleepTimeMs = (deadline - currentTime + 999999) / 1000000;

                if (sleepTimeMs <= 0) {
                    // 这里的意思应该是从时间轮启动到现在经过太长的时间(跨度大于292年...)，以至于让long装不下，都溢出了...对于netty的严谨，我服！
                    if (currentTime == Long.MIN_VALUE) {
                        return -Long.MAX_VALUE;
                    } else {
                        return currentTime;
                    }
                }

                // Check if we run on windows, as if thats the case we will need
                // to round the sleepTime as workaround for a bug that only affect
                // the JVM if it runs on windows.
                //
                // See https://github.com/netty/netty/issues/356
                if (PlatformDependent.isWindows()) { // 这里是因为windows平台的定时调度最小单位为10ms，如果不是10ms的倍数，可能会引起sleep时间不准确
                    sleepTimeMs = sleepTimeMs / 10 * 10;
                }

                try {
                    System.out.println("sleepTimeMs"+sleepTimeMs);
                    Thread.sleep(sleepTimeMs);
                } catch (InterruptedException ignored) {
                    // 调用HashedWheelTimer.stop()时优雅退出
                    if (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_SHUTDOWN) {
                        return Long.MIN_VALUE;
                    }
                }
            }
        }

        public Set<Timeout> unprocessedTimeouts() {
            return Collections.unmodifiableSet(unprocessedTimeouts);
        }
    }

    private static final class HashedWheelTimeout implements Timeout {
        // 定义定时任务的3个状态：初始化、取消、过期
        private static final int ST_INIT = 0;
        private static final int ST_CANCELLED = 1;
        private static final int ST_EXPIRED = 2;
        // 用来CAS方式更新定时任务状态
        private static final AtomicIntegerFieldUpdater<HashedWheelTimeout> STATE_UPDATER =
                AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimeout.class, "state");

        // 时间轮引用
        private final HashedWheelTimer timer;
        // 具体到期需要执行的任务
        private final TimerTask task;
        private final long deadline;

        @SuppressWarnings({"unused", "FieldMayBeFinal", "RedundantFieldInitialization" })
        private volatile int state = ST_INIT;

        // remainingRounds将由之前的Worker.transferTimeoutsToBuckets（）计算和设置
        // HashedWheelTimeout将被添加到正确的HashedWheelBucket中。
        // 离任务执行的轮数，当将次任务加入到格子中是计算该值，每过一轮，该值减一。
        long remainingRounds;

        //这将用于通过双链表在HashedWheelTimerBucket中链接超时。
        //由于只有workerThread会对其执行操作，因此不需要 synchronized/ volatile。

        HashedWheelTimeout next;
        HashedWheelTimeout prev;

        // 定时任务所在的格子
        HashedWheelBucket bucket;

        HashedWheelTimeout(HashedWheelTimer timer, TimerTask task, long deadline) {
            this.timer = timer;
            this.task = task;
            this.deadline = deadline;
        }

        @Override
        public Timer timer() {
            return timer;
        }

        @Override
        public TimerTask task() {
            return task;
        }

        @Override
        public boolean cancel() {
            // 这里只是修改状态为ST_CANCELLED，会在下次tick时，在格子中移除
            if (!compareAndSetState(ST_INIT, ST_CANCELLED)) {
                return false;
            }
            //如果要取消任务，我们将其放到另一个队列中，该队列将在每个tick上处理。
            //所以这意味着我们的GC延迟最大值。 1滴答持续时间足够好。这条路
            //我们可以再次使用我们的MpscLinkedQueue，从而尽可能地减少锁定/开销。
            // 加入到时间轮的待取消队列，并在每次tick的时候，从相应格子中移除。
            timer.cancelledTimeouts.add(this);
            return true;
        }

        // 从格子中移除自身
        void remove() {
            HashedWheelBucket bucket = this.bucket;
            if (bucket != null) {
                bucket.remove(this);
            } else {
                timer.pendingTimeouts.decrementAndGet();
            }
        }

        public boolean compareAndSetState(int expected, int state) {
            return STATE_UPDATER.compareAndSet(this, expected, state);
        }

        public int state() {
            return state;
        }

        @Override
        public boolean isCancelled() {
            return state() == ST_CANCELLED;
        }

        @Override
        public boolean isExpired() {
            return state() == ST_EXPIRED;
        }

        // 从格子中移除自身
        public void expire() {
            if (!compareAndSetState(ST_INIT, ST_EXPIRED)) {
                return;
            }

            try {
                System.err.println("过期执行"+this);
                task.run(this);
            } catch (Throwable t) {
                if (logger.isWarnEnabled()) {
                    logger.warn("An exception was thrown by " + TimerTask.class.getSimpleName() + '.', t);
                }
            }
        }

        @Override
        public String toString() {
            final long currentTime = System.nanoTime();
            long remaining = deadline - currentTime + timer.startTime;

            StringBuilder buf = new StringBuilder(192)
               .append(simpleClassName(this))
               .append('(')
               .append("deadline: ");
            if (remaining > 0) {
                buf.append(remaining)
                   .append(" ns later");
            } else if (remaining < 0) {
                buf.append(-remaining)
                   .append(" ns ago");
            } else {
                buf.append("now");
            }

            if (isCancelled()) {
                buf.append(", cancelled");
            }

            return buf.append(", task: ")
                      .append(task())
                      .append(')')
                      .toString();
        }
    }

    /**
     * Bucket that stores HashedWheelTimeouts. These are stored in a linked-list like datastructure to allow easy
     * removal of HashedWheelTimeouts in the middle. Also the HashedWheelTimeout act as nodes themself and so no
     * extra object creation is needed.
     */
    //HashedWheelBucket用来存放HashedWheelTimeout，结构类似于LinkedList。提供了expireTimeouts(long deadline)方法来过期并执行格子中的定时任务
    private static final class HashedWheelBucket {
        // 用于链表数据结构
        private HashedWheelTimeout head;
        private HashedWheelTimeout tail;

        /**
         * Add {@link HashedWheelTimeout} to this bucket.
         */
        // 基础的链表添加操作
        public void addTimeout(HashedWheelTimeout timeout) {
            assert timeout.bucket == null;
            timeout.bucket = this;
            if (head == null) {
                head = tail = timeout;
            } else {
                tail.next = timeout;
                timeout.prev = tail;
                tail = timeout;
            }
        }

        /**
         * Expire all {@link HashedWheelTimeout}s for the given {@code deadline}.
         */
        // 过期并执行格子中的到期任务，tick到该格子的时候，worker线程会调用这个方法，根据deadline和remainingRounds判断任务是否过期
        public void expireTimeouts(long deadline) {
            HashedWheelTimeout timeout = head;

            // 处理所有超时
            // 遍历格子中的所有定时任务
            while (timeout != null) {
                HashedWheelTimeout next = timeout.next;
                if (timeout.remainingRounds <= 0) {    // 定时任务到期
                    next = remove(timeout);
                    if (timeout.deadline <= deadline) {
                        if(head!=null){
                            System.out.println("到期了"+head.toString());
                        }
                        timeout.expire();
                    } else {
                        // 如果round数已经为0，deadline却>当前格子的deadline，说放错格子了，这种情况应该不会出现
                        throw new IllegalStateException(String.format(
                                "timeout.deadline (%d) > deadline (%d)", timeout.deadline, deadline));
                    }
                } else if (timeout.isCancelled()) {
                    next = remove(timeout);
                } else {  //没有到期，轮数-1
                    System.out.println("没有到期");
                    timeout.remainingRounds --;
                }
                // 先保存next，因为移除后next将被设置为null
                timeout = next;
            }
        }

        // 基础的链表移除node操作
        public HashedWheelTimeout remove(HashedWheelTimeout timeout) {
            HashedWheelTimeout next = timeout.next;
            // remove timeout that was either processed or cancelled by updating the linked-list
            if (timeout.prev != null) {
                timeout.prev.next = next;
            }
            if (timeout.next != null) {
                timeout.next.prev = timeout.prev;
            }

            if (timeout == head) {
                // if timeout is also the tail we need to adjust the entry too
                if (timeout == tail) {
                    tail = null;
                    head = null;
                } else {
                    head = next;
                }
            } else if (timeout == tail) {
                // if the timeout is the tail modify the tail to be the prev node.
                tail = timeout.prev;
            }
            // null out prev, next and bucket to allow for GC.
            timeout.prev = null;
            timeout.next = null;
            timeout.bucket = null;
            timeout.timer.pendingTimeouts.decrementAndGet();
            return next;
        }

        /**
         * 清除此存储桶并返回所有未过期/已取消 {@link Timeout}s.
         */
        public void clearTimeouts(Set<Timeout> set) {
            for (;;) {
                HashedWheelTimeout timeout = pollTimeout();
                if (timeout == null) {
                    return;
                }
                if (timeout.isExpired() || timeout.isCancelled()) {
                    continue;
                }
                set.add(timeout);
            }
        }

        // 链表的poll操作
        private HashedWheelTimeout pollTimeout() {
            HashedWheelTimeout head = this.head;
            if (head == null) {
                return null;
            }
            HashedWheelTimeout next = head.next;
            if (next == null) {
                tail = this.head =  null;
            } else {
                this.head = next;
                next.prev = null;
            }

            // null out prev and next to allow for GC.
            head.next = null;
            head.prev = null;
            head.bucket = null;
            return head;
        }
    }
}
