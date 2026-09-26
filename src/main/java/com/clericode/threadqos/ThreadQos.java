package com.clericode.threadqos;

import com.clericode.threadqos.internal.ThreadQosProvider;
import com.clericode.threadqos.internal.ThreadQosProviderFactory;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Sets a Quality of Service / performance-intent class on the <em>current Java platform thread</em>.
 *
 * <p>Typical use is a long-lived dedicated thread:</p>
 *
 * <pre>{@code
 * Thread.ofPlatform()
 *         .name("market-data")
 *         .start(ThreadQos.wrap(QosClass.USER_INTERACTIVE, this::marketDataLoop));
 * }</pre>
 *
 * <p>or, from inside the thread itself:</p>
 *
 * <pre>{@code
 * ThreadQos.set(QosClass.USER_INTERACTIVE);
 * }</pre>
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Only the current thread is affected. There is no API for other threads.</li>
 *   <li>Only platform threads: every method that changes QoS throws {@link IllegalStateException}
 *       when called on a virtual thread, on every operating system including unsupported ones.
 *       A virtual thread runs on a shared carrier thread whose scheduling parameters would
 *       otherwise leak to unrelated virtual threads.</li>
 *   <li>{@code null} arguments throw {@link NullPointerException} before any native code runs.</li>
 *   <li>On unsupported platforms {@link #set(QosClass)} is a silent no-op and
 *       {@link #isSupported()} is {@code false}. On supported platforms a failing native call
 *       throws {@link ThreadQosException}; failures are never hidden.</li>
 *   <li>Nothing is logged. Diagnostics are available through {@link #capabilities()}.</li>
 *   <li>The thread's interruption status is neither read, set nor cleared.</li>
 *   <li>{@link Thread#setPriority(int)} is never called; on Windows the library controls the native
 *       priority itself.</li>
 * </ul>
 *
 * <h2>Native access</h2>
 *
 * <p>The library uses restricted {@code java.lang.foreign} methods. Run the JVM with
 * {@code --enable-native-access=com.clericode.threadqos} (module path) or
 * {@code --enable-native-access=ALL-UNNAMED} (classpath). On JDK 25 a missing flag produces a
 * one-time JVM warning; with {@code --illegal-native-access=deny} the first call throws a
 * {@link ThreadQosException} whose message explains the flag.</p>
 *
 * <p>Loading this class does not link any native function; native bindings are initialized on the
 * first call that needs them.</p>
 */
public final class ThreadQos {

    private ThreadQos() {
    }

    /**
     * Class-initialization holder: the provider is created exactly once, lazily, on the first API
     * call, and read afterwards without any lock (spec §5, §27, §78).
     */
    private static final class Holder {
        static final ThreadQosProvider PROVIDER = ThreadQosProviderFactory.create();

        private Holder() {
        }
    }

    /**
     * Whether {@link #set(QosClass)} performs a real native operation on this JVM.
     *
     * <p>On Linux this triggers a one-time, read-only probe of {@code sched_getattr}; on Windows a
     * one-time, read-only {@code GetThreadInformation} probe. The result is cached.</p>
     *
     * @return {@code true} if native QoS is available
     */
    public static boolean isSupported() {
        return Holder.PROVIDER.isSupported();
    }

    /**
     * Detailed capabilities of the active provider, including the reason when something is
     * unavailable.
     *
     * @return capabilities, never {@code null}
     */
    public static QosCapabilities capabilities() {
        return Holder.PROVIDER.capabilities();
    }

    /**
     * Sets the QoS class of the current platform thread.
     *
     * @param qosClass requested class
     * @throws NullPointerException  if {@code qosClass} is {@code null}
     * @throws IllegalStateException if called on a virtual thread
     * @throws ThreadQosException    if the platform is supported but the native call failed, or the
     *                               native bindings could not be linked
     */
    public static void set(QosClass qosClass) {
        Objects.requireNonNull(qosClass, "qosClass");
        requirePlatformThread();
        Holder.PROVIDER.set(qosClass);
    }

    /**
     * Sets the QoS class of the current platform thread, reporting failure instead of throwing.
     *
     * <p>Programming errors are still thrown: {@code null} and virtual-thread calls behave exactly
     * like {@link #set(QosClass)}. Environmental problems (unsupported platform, native error,
     * linkage failure) yield {@code false}. Use this when starting the thread matters more than
     * getting the requested QoS; use {@link #set(QosClass)} or {@link #wrap(QosClass, Runnable)}
     * when running without the requested QoS would be a silent bug.</p>
     *
     * @param qosClass requested class
     * @return {@code true} if a native operation was performed successfully
     * @throws NullPointerException  if {@code qosClass} is {@code null}
     * @throws IllegalStateException if called on a virtual thread
     */
    public static boolean trySet(QosClass qosClass) {
        Objects.requireNonNull(qosClass, "qosClass");
        requirePlatformThread();
        try {
            return Holder.PROVIDER.set(qosClass);
        } catch (ThreadQosException e) {
            return false;
        }
    }

    /**
     * Wraps a task so that the QoS class is applied on the executing thread right before the task
     * runs.
     *
     * <p>The wrapper is strict: if {@link #set(QosClass)} fails, its exception propagates and the
     * task is <em>not</em> run. The QoS is set once and never restored afterwards, which matches
     * the intended use (a dedicated long-lived platform thread). Do not use the wrapper for tasks
     * submitted to a shared pool: QoS is a property of the native worker thread, not of the
     * submitted task, so it would leak to the next task on the same worker.</p>
     *
     * <p>A wrapper is always returned, also on unsupported platforms, so that the {@code null} and
     * virtual-thread checks behave identically everywhere.</p>
     *
     * @param qosClass requested class
     * @param task     the task to run after setting the QoS
     * @return a new {@link Runnable}
     * @throws NullPointerException if either argument is {@code null}
     */
    public static Runnable wrap(QosClass qosClass, Runnable task) {
        Objects.requireNonNull(qosClass, "qosClass");
        Objects.requireNonNull(task, "task");
        return () -> {
            set(qosClass);
            task.run();
        };
    }

    /**
     * Wraps a {@link Callable} so that the QoS class is applied on the executing thread right
     * before the task runs. Same contract as {@link #wrap(QosClass, Runnable)}.
     *
     * @param qosClass requested class
     * @param task     the task to call after setting the QoS
     * @param <V>      result type
     * @return a new {@link Callable}
     * @throws NullPointerException if either argument is {@code null}
     */
    public static <V> Callable<V> wrap(QosClass qosClass, Callable<V> task) {
        Objects.requireNonNull(qosClass, "qosClass");
        Objects.requireNonNull(task, "task");
        return () -> {
            set(qosClass);
            return task.call();
        };
    }

    private static void requirePlatformThread() {
        if (Thread.currentThread().isVirtual()) {
            throw new IllegalStateException("Thread QoS can only be changed from a Java platform thread");
        }
    }
}
