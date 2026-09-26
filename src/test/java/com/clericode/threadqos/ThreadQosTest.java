package com.clericode.threadqos;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Facade contract that must hold on every platform, supported or not.
 */
class ThreadQosTest {

    @Test
    void nullArgumentsThrowNpeBeforeAnythingElse() {
        assertThrows(NullPointerException.class, () -> ThreadQos.set(null));
        assertThrows(NullPointerException.class, () -> ThreadQos.trySet(null));
        assertThrows(NullPointerException.class, () -> ThreadQos.wrap(null, () -> { }));
        assertThrows(NullPointerException.class, () -> ThreadQos.wrap(QosClass.DEFAULT, (Runnable) null));
        assertThrows(NullPointerException.class, () -> ThreadQos.wrap(null, () -> 1));
        assertThrows(NullPointerException.class, () -> ThreadQos.wrap(QosClass.DEFAULT, (Callable<Integer>) null));
    }

    @Test
    void wrapNullChecksHappenEagerlyEvenOnVirtualThread() throws Exception {
        Throwable t = runOnVirtualThread(() -> ThreadQos.wrap(QosClass.DEFAULT, (Runnable) null));
        assertInstanceOf(NullPointerException.class, t);
    }

    @Test
    void setOnVirtualThreadIsRejected() throws Exception {
        Throwable t = runOnVirtualThread(() -> ThreadQos.set(QosClass.USER_INTERACTIVE));
        assertInstanceOf(IllegalStateException.class, t);
        assertEquals("Thread QoS can only be changed from a Java platform thread", t.getMessage());
    }

    @Test
    void trySetOnVirtualThreadIsRejected() throws Exception {
        Throwable t = runOnVirtualThread(() -> ThreadQos.trySet(QosClass.USER_INTERACTIVE));
        assertInstanceOf(IllegalStateException.class, t);
    }

    @Test
    void wrappedRunnableOnVirtualThreadIsRejectedBeforeTaskRuns() throws Exception {
        AtomicBoolean ran = new AtomicBoolean();
        Runnable wrapped = ThreadQos.wrap(QosClass.BACKGROUND, () -> ran.set(true));
        Throwable t = runOnVirtualThread(wrapped);
        assertInstanceOf(IllegalStateException.class, t);
        assertFalse(ran.get(), "task must not run when QoS cannot be set");
    }

    @Test
    void wrappedCallableOnVirtualThreadIsRejectedBeforeTaskRuns() throws Exception {
        AtomicBoolean ran = new AtomicBoolean();
        Callable<String> wrapped = ThreadQos.wrap(QosClass.BACKGROUND, () -> {
            ran.set(true);
            return "x";
        });
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            ExecutionException ee = assertThrows(ExecutionException.class, () -> vt.submit(wrapped).get());
            assertInstanceOf(IllegalStateException.class, ee.getCause());
        }
        assertFalse(ran.get());
    }

    @Test
    void wrapAlwaysReturnsANewWrapper() {
        Runnable task = () -> { };
        Callable<Integer> callable = () -> 1;
        Runnable wrappedTask = ThreadQos.wrap(QosClass.DEFAULT, task);
        Callable<Integer> wrappedCallable = ThreadQos.wrap(QosClass.DEFAULT, callable);
        assertNotSame(task, wrappedTask);
        assertNotSame(callable, wrappedCallable);
    }

    @Test
    void wrappedRunnableRunsTaskOnPlatformThread() throws Exception {
        AtomicBoolean ran = new AtomicBoolean();
        Thread t = Thread.ofPlatform().start(ThreadQos.wrap(QosClass.DEFAULT, () -> ran.set(true)));
        t.join();
        assertTrue(ran.get());
    }

    @Test
    void wrappedCallableReturnsTaskResult() throws Exception {
        Callable<String> wrapped = ThreadQos.wrap(QosClass.DEFAULT, () -> "result");
        assertEquals("result", wrapped.call());
    }

    @Test
    void taskExceptionPropagatesUnchanged() {
        IllegalArgumentException boom = new IllegalArgumentException("boom");
        Runnable throwing = () -> {
            throw boom;
        };
        Runnable wrapped = ThreadQos.wrap(QosClass.DEFAULT, throwing);
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, wrapped::run);
        assertSame(boom, thrown);

        Exception checked = new Exception("checked");
        Callable<Object> throwingCallable = () -> {
            throw checked;
        };
        Callable<Object> wrappedCallable = ThreadQos.wrap(QosClass.DEFAULT, throwingCallable);
        Exception thrownChecked = assertThrows(Exception.class, wrappedCallable::call);
        assertSame(checked, thrownChecked);
    }

    @Test
    void interruptionStatusIsPreserved() throws Exception {
        AtomicReference<Boolean> stillInterrupted = new AtomicReference<>();
        Thread t = Thread.ofPlatform().start(() -> {
            Thread.currentThread().interrupt();
            ThreadQos.trySet(QosClass.DEFAULT);
            ThreadQos.wrap(QosClass.DEFAULT, () -> { }).run();
            stillInterrupted.set(Thread.currentThread().isInterrupted());
            // clear the flag before exiting
            Thread.interrupted();
        });
        t.join();
        assertEquals(Boolean.TRUE, stillInterrupted.get());
    }

    @Test
    void interruptionStatusIsNotSet() throws Exception {
        AtomicReference<Boolean> interrupted = new AtomicReference<>();
        Thread t = Thread.ofPlatform().start(() -> {
            ThreadQos.trySet(QosClass.DEFAULT);
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        t.join();
        assertEquals(Boolean.FALSE, interrupted.get());
    }

    @Test
    void isSupportedMatchesCapabilities() {
        QosCapabilities caps = ThreadQos.capabilities();
        assertNotNull(caps);
        assertEquals(caps.qosSupported(), ThreadQos.isSupported());
        if (!caps.qosSupported()) {
            assertNotNull(caps.unsupportedReason(), "unsupported platforms must explain why");
        }
    }

    @Test
    void trySetReturnsTrueExactlyWhenSupported() throws Exception {
        AtomicReference<Boolean> result = new AtomicReference<>();
        Thread t = Thread.ofPlatform().start(() -> result.set(ThreadQos.trySet(QosClass.DEFAULT)));
        t.join();
        assertEquals(ThreadQos.isSupported(), result.get());
    }

    private static Throwable runOnVirtualThread(Runnable r) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread vt = Thread.ofVirtual().unstarted(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        vt.start();
        vt.join();
        return failure.get();
    }
}
