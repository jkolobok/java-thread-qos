package com.clericode.threadqos.examples.basic;

import com.clericode.threadqos.QosClass;
import com.clericode.threadqos.ThreadQos;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Two long-lived platform threads: a latency-sensitive "execution" loop and a "writer" doing
 * background persistence. The QoS class is set once, when each thread starts, through
 * {@link ThreadQos#wrap(QosClass, Runnable)}.
 *
 * <p>Run from this directory after {@code mvn install} in the repository root:</p>
 * <pre>{@code
 * mvn compile exec:exec
 * }</pre>
 * <p>or directly with the source launcher, from the repository root (the example is itself a
 * module, so the library goes on the module path):</p>
 * <pre>{@code
 * java --enable-native-access=com.clericode.threadqos -p target/classes \
 *      examples/basic/src/main/java/com/clericode/threadqos/examples/basic/Example.java
 * }</pre>
 */
public final class Example {

    private static final AtomicBoolean RUNNING = new AtomicBoolean(true);

    private Example() {
    }

    /**
     * Entry point.
     *
     * @param args ignored
     * @throws InterruptedException if interrupted while joining
     */
    public static void main(String[] args) throws InterruptedException {
        System.out.println("thread QoS supported: " + ThreadQos.isSupported());
        System.out.println("capabilities: " + ThreadQos.capabilities());

        Thread execution = Thread.ofPlatform()
                .name("execution")
                .start(ThreadQos.wrap(QosClass.USER_INTERACTIVE, Example::executionLoop));

        Thread writer = Thread.ofPlatform()
                .name("writer")
                .start(ThreadQos.wrap(QosClass.UTILITY, Example::writerLoop));

        Thread.sleep(Duration.ofSeconds(2));
        RUNNING.set(false);
        execution.join();
        writer.join();
        System.out.println("done");
    }

    /** Latency-sensitive event loop: spins on a queue, never sleeps for long. */
    private static void executionLoop() {
        long events = 0;
        while (RUNNING.get()) {
            // ... poll an order/market-data queue here ...
            events++;
            Thread.onSpinWait();
        }
        System.out.println("execution: processed " + events + " loop iterations on "
                + Thread.currentThread().getName());
    }

    /** Background persistence: batches and flushes, latency does not matter. */
    private static void writerLoop() {
        long flushes = 0;
        while (RUNNING.get()) {
            // ... append a CSV batch, fsync occasionally ...
            flushes++;
            try {
                Thread.sleep(Duration.ofMillis(100));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        System.out.println("writer: flushed " + flushes + " batches on " + Thread.currentThread().getName());
    }
}
