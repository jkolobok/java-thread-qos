package com.clericode.threadqos.examples.comparison;

import com.clericode.threadqos.QosClass;
import com.clericode.threadqos.ThreadQos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shows what a QoS class actually changes, with numbers, on the machine it runs on.
 *
 * <p>Two scenarios:</p>
 * <ol>
 *   <li><b>Throughput.</b> The same CPU-bound work runs on a fresh platform thread once per
 *       {@link QosClass}, on an otherwise idle machine. On hybrid CPUs (Apple silicon, Intel
 *       P/E cores, ARM big.LITTLE) {@code UTILITY} and {@code BACKGROUND} land on efficiency cores
 *       and finish later; on a homogeneous CPU with a fixed frequency the numbers are equal.</li>
 *   <li><b>Tail latency under load.</b> {@code 2 × availableProcessors} "noise" threads spin
 *       while one "critical" thread times short chunks of work. The critical thread's p50 barely
 *       moves, but its p99 and max depend on whether the noise threads were demoted to
 *       {@code BACKGROUND}: that is the case the library is for.</li>
 * </ol>
 *
 * <p>Run from the repository root after {@code mvn -q -DskipTests install}:</p>
 * <pre>{@code
 * java --enable-native-access=com.clericode.threadqos -p target/classes \
 *      examples/comparison/src/main/java/com/clericode/threadqos/examples/comparison/Comparison.java
 * }</pre>
 * <p>or {@code cd examples/comparison && mvn compile exec:exec}. Pass {@code quick} as the only
 * argument to run a shorter version.</p>
 *
 * <p>This is a demonstration, not a benchmark: no JMH, no pinning, no statistics beyond
 * percentiles. Close other CPU-heavy programs before reading the numbers.</p>
 */
public final class Comparison {

    private static final int CPUS = Runtime.getRuntime().availableProcessors();

    private Comparison() {
    }

    /**
     * Entry point.
     *
     * @param args {@code quick} for a shorter run, otherwise ignored
     * @throws InterruptedException if interrupted while joining
     */
    public static void main(String[] args) throws InterruptedException {
        boolean quick = args.length > 0 && "quick".equals(args[0]);
        long throughputIterations = quick ? 100_000_000L : 400_000_000L;
        int latencySamples = quick ? 1_000 : 2_000;

        System.out.println("os=" + System.getProperty("os.name") + " arch=" + System.getProperty("os.arch")
                + " cpus=" + CPUS + " supported=" + ThreadQos.isSupported());
        System.out.println("capabilities=" + ThreadQos.capabilities());
        if (!ThreadQos.isSupported()) {
            System.out.println("QoS is a no-op here; the numbers below show the platform's baseline only.");
        }

        warmUp();
        throughput(throughputIterations);
        tailLatency(latencySamples);
    }

    // ---------------------------------------------------------------------------------------------
    // Scenario 1: throughput per class
    // ---------------------------------------------------------------------------------------------

    private static void throughput(long iterations) throws InterruptedException {
        System.out.println();
        System.out.println("1. Identical CPU-bound work per QoS class, idle machine (lower is faster)");
        System.out.printf(Locale.ROOT, "   %-17s %10s%n", "QosClass", "elapsed");
        for (QosClass qos : QosClass.values()) {
            long[] elapsedNanos = new long[1];
            Thread thread = Thread.ofPlatform()
                    .name("throughput-" + qos)
                    .start(ThreadQos.wrap(qos, (Runnable) () -> elapsedNanos[0] = timed(() -> work(iterations))));
            thread.join();
            System.out.printf(Locale.ROOT, "   %-17s %7d ms%n", qos, elapsedNanos[0] / 1_000_000);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Scenario 2: tail latency of a critical thread while noise threads saturate the CPUs
    // ---------------------------------------------------------------------------------------------

    private static void tailLatency(int samples) throws InterruptedException {
        int noiseThreads = CPUS * 2;
        System.out.println();
        System.out.println("2. Duration of a ~0.4 ms work chunk on a critical thread while "
                + noiseThreads + " noise threads spin (lower is better)");
        System.out.printf(Locale.ROOT, "   %-46s %9s %9s %9s %9s%n", "configuration", "p50", "p90", "p99", "max");
        for (int round = 0; round < 2; round++) {
            report("no QoS at all", null, null, noiseThreads, samples);
            report("critical=USER_INTERACTIVE, noise untouched", QosClass.USER_INTERACTIVE, null, noiseThreads, samples);
            report("critical untouched, noise=BACKGROUND", null, QosClass.BACKGROUND, noiseThreads, samples);
            report("critical=USER_INTERACTIVE, noise=BACKGROUND", QosClass.USER_INTERACTIVE, QosClass.BACKGROUND,
                    noiseThreads, samples);
        }
    }

    private static void report(String label, QosClass critical, QosClass noise, int noiseThreads, int samples)
            throws InterruptedException {
        long[] nanos = measureUnderLoad(critical, noise, noiseThreads, samples);
        System.out.printf(Locale.ROOT, "   %-46s %6d us %6d us %6d us %6d us%n", label,
                percentile(nanos, 50) / 1_000, percentile(nanos, 90) / 1_000,
                percentile(nanos, 99) / 1_000, nanos[nanos.length - 1] / 1_000);
    }

    /**
     * Starts {@code noiseThreads} spinning threads (optionally wrapped in {@code noise}), then runs
     * {@code samples} timed work chunks on one critical thread (optionally wrapped in
     * {@code critical}). Returns the sorted chunk durations in nanoseconds.
     */
    static long[] measureUnderLoad(QosClass critical, QosClass noise, int noiseThreads, int samples)
            throws InterruptedException {
        AtomicBoolean running = new AtomicBoolean(true);
        Runnable spin = () -> {
            long sink = 0;
            while (running.get()) {
                sink += work(1_000_000);
            }
            if (sink == 42) {
                System.out.println(); // keep the result alive; never true in practice
            }
        };
        List<Thread> noisy = new ArrayList<>(noiseThreads);
        for (int i = 0; i < noiseThreads; i++) {
            noisy.add(Thread.ofPlatform().name("noise-" + i).start(noise == null ? spin : ThreadQos.wrap(noise, spin)));
        }
        Thread.sleep(300); // let the noise settle before measuring

        long[] nanos = new long[samples];
        Runnable measure = () -> {
            for (int i = 0; i < nanos.length; i++) {
                nanos[i] = timed(() -> work(200_000));
            }
        };
        Thread criticalThread = Thread.ofPlatform().name("critical")
                .start(critical == null ? measure : ThreadQos.wrap(critical, measure));
        criticalThread.join();

        running.set(false);
        for (Thread t : noisy) {
            t.join();
        }
        Arrays.sort(nanos);
        return nanos;
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static void warmUp() throws InterruptedException {
        Thread t = Thread.ofPlatform().start(() -> work(50_000_000L));
        t.join();
    }

    /** Pure CPU work with no memory traffic: xorshift over {@code iterations} steps. */
    static long work(long iterations) {
        long x = 0x9E3779B97F4A7C15L;
        for (long i = 0; i < iterations; i++) {
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
        }
        return x;
    }

    static long timed(Runnable r) {
        long start = System.nanoTime();
        r.run();
        return System.nanoTime() - start;
    }

    static long percentile(long[] sorted, int percent) {
        int index = (int) Math.min(sorted.length - 1, Math.round(sorted.length * (percent / 100.0)));
        return sorted[index];
    }
}
