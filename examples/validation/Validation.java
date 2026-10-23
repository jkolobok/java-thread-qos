import com.clericode.threadqos.QosClass;
import com.clericode.threadqos.ThreadQos;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/** Bounded synthetic workload for T-9.1; does not replace real-application validation. */
public final class Validation {
    private Validation() { }

    public static void main(String[] args) throws Exception {
        int seconds = args.length == 0 ? 3 : Integer.parseInt(args[0]);
        if (seconds < 1 || seconds > 300 || args.length > 2
                || (args.length == 2 && !args[1].equals("--require-supported"))) {
            throw new IllegalArgumentException("Usage: Validation.java [seconds:1..300] [--require-supported]");
        }
        System.out.println("time=" + Instant.now());
        System.out.println("java=" + System.getProperty("java.runtime.version"));
        System.out.println("os=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " arch=" + System.getProperty("os.arch")
                + " cpus=" + Runtime.getRuntime().availableProcessors());
        System.out.println("capabilities=" + ThreadQos.capabilities());
        boolean required = args.length == 2;
        if (required && !ThreadQos.isSupported()) {
            throw new IllegalStateException("Native QoS required: " + ThreadQos.capabilities());
        }
        System.out.println("Synthetic baseline followed by QoS; no universal performance threshold.");
        run(false, seconds, required);
        run(true, seconds, required);
    }

    private static void run(boolean qos, int seconds, boolean required) throws Exception {
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch start = new CountDownLatch(1);
        ArrayBlockingQueue<Long> events = new ArrayBlockingQueue<>(4096);
        long[] latencies = new long[200_000];
        Worker market = new Worker("market-data", QosClass.USER_INTERACTIVE);
        Worker execution = new Worker("execution", QosClass.USER_INITIATED);
        Worker utility = new Worker("utility-writer", QosClass.UTILITY);
        Worker background = new Worker("background-maintenance", QosClass.BACKGROUND);
        List<Thread> threads = new ArrayList<>();
        Path file = Files.createTempFile("thread-qos-validation-", ".bin");
        try (FileChannel writer = FileChannel.open(file, StandardOpenOption.WRITE)) {
            threads.add(worker(market, qos, required, running, failure, ready, start, () -> {
                long value = 1;
                while (running.get()) {
                    value = work(value);
                    market.operations++;
                    if (!events.offer(System.nanoTime())) {
                        market.dropped++;
                    }
                }
                market.checksum = value;
            }));
            threads.add(worker(execution, qos, required, running, failure, ready, start, () -> {
                long value = 2;
                while (running.get()) {
                    Long sent = events.poll(100, TimeUnit.MILLISECONDS);
                    if (sent != null) {
                        long delay = System.nanoTime() - sent;
                        // Storage is bounded; retain the first 200k observations.
                        if (execution.operations < latencies.length) {
                            latencies[(int) execution.operations] = delay;
                        }
                        value = work(value);
                        execution.operations++;
                    }
                }
                execution.checksum = value;
            }));
            threads.add(worker(utility, qos, required, running, failure, ready, start, () -> {
                ByteBuffer buffer = ByteBuffer.allocate(4096);
                while (running.get()) {
                    buffer.clear();
                    buffer.putLong(utility.operations).clear();
                    while (buffer.hasRemaining()) {
                        writer.write(buffer);
                    }
                    utility.operations++;
                    if (utility.operations % 64 == 0) {
                        writer.force(false);
                    }
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
                }
            }));
            threads.add(worker(background, qos, required, running, failure, ready, start, () -> {
                long value = 3;
                while (running.get()) {
                    value = work(value);
                    background.operations++;
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
                background.checksum = value;
            }));
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Workers did not initialize within 10 seconds");
            }
            rethrow(failure);
            long began = System.nanoTime();
            start.countDown();
            TimeUnit.SECONDS.sleep(seconds);
            stop(threads, running, start);
            double elapsed = (System.nanoTime() - began) / 1_000_000_000.0;
            rethrow(failure);
            System.out.println("mode=" + (qos ? "qos" : "baseline"));
            for (Worker w : List.of(market, execution, utility, background)) {
                if (w.operations == 0) {
                    throw new IllegalStateException("No work completed by " + w.name);
                }
                System.out.printf(Locale.ROOT,
                        "worker=%s class=%s applied=%s operations=%d ops_per_second=%.1f checksum=%d%n",
                        w.name, qos ? w.qos : "untouched", w.applied, w.operations,
                        w.operations / elapsed, w.checksum);
            }
            int samples = (int) Math.min(execution.operations, latencies.length);
            Arrays.sort(latencies, 0, samples);
            System.out.printf(Locale.ROOT,
                    "execution_samples=%d p50_us=%.3f p99_us=%.3f dropped_events=%d writer_bytes=%d%n",
                    samples, percentile(latencies, samples, 50) / 1000.0,
                    percentile(latencies, samples, 99) / 1000.0, market.dropped, writer.size());
            System.out.println("capabilities_after=" + ThreadQos.capabilities());
        } finally {
            try {
                stop(threads, running, start);
            } finally {
                Files.deleteIfExists(file);
            }
        }
    }

    private static Thread worker(Worker w, boolean qos, boolean required, AtomicBoolean running,
                                 AtomicReference<Throwable> failure, CountDownLatch ready,
                                 CountDownLatch start, Work work) {
        return Thread.ofPlatform().daemon(true).name(w.name).start(() -> {
            try {
                if (qos) {
                    // set() propagates native failures. trySet() alone would conceal their cause.
                    ThreadQos.set(w.qos);
                    w.applied = ThreadQos.isSupported();
                    if (required && !w.applied) {
                        throw new IllegalStateException("QoS became unsupported: " + ThreadQos.capabilities());
                    }
                }
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            } finally {
                ready.countDown();
            }
            try {
                start.await();
                if (failure.get() == null) {
                    work.run();
                }
            } catch (InterruptedException e) {
                if (running.get()) {
                    failure.compareAndSet(null, e);
                }
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        });
    }

    private static void stop(List<Thread> threads, AtomicBoolean running, CountDownLatch start)
            throws InterruptedException {
        running.set(false);
        start.countDown();
        // poll() is bounded; avoid interrupting the writer's FileChannel during normal shutdown.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        for (Thread thread : threads) {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                thread.join(TimeUnit.NANOSECONDS.toMillis(remaining) + 1);
            }
            if (thread.isAlive()) {
                threads.forEach(Thread::interrupt);
                throw new IllegalStateException("Worker did not stop: " + thread.getName());
            }
        }
    }

    private static void rethrow(AtomicReference<Throwable> failure) {
        if (failure.get() != null) {
            throw new IllegalStateException("Worker failed", failure.get());
        }
    }

    private static long percentile(long[] sorted, int size, int percent) {
        return sorted[Math.max(0, (int) Math.ceil(size * (percent / 100.0)) - 1)];
    }

    private static long work(long value) {
        for (int i = 0; i < 1000; i++) {
            value ^= value << 13;
            value ^= value >>> 7;
            value ^= value << 17;
        }
        return value;
    }

    @FunctionalInterface
    private interface Work {
        void run() throws Exception;
    }

    private static final class Worker {
        final String name;
        final QosClass qos;
        boolean applied;
        long operations;
        long checksum;
        long dropped;

        Worker(String name, QosClass qos) {
            this.name = name;
            this.qos = qos;
        }
    }
}
