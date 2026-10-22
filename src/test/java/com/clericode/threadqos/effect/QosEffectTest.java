package com.clericode.threadqos.effect;

import com.clericode.threadqos.QosClass;
import com.clericode.threadqos.ThreadQos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Demonstrates, as a test, that the QoS class changes something observable. Excluded from the
 * default {@code mvn verify} because it takes tens of seconds and depends on the machine being
 * otherwise idle; run it with {@code mvn -Peffect test}.
 *
 * <p>The assertions are deliberately loose (thresholds far below what an idle Apple silicon Mac
 * shows) so that the test documents the direction of the effect, not a performance number.
 * Measured values are printed so they end up in {@code target/surefire-reports}.</p>
 *
 * <p>Only macOS is asserted: on Linux, uclamp does not change the CFS/EEVDF share of a thread
 * (see README, "util_max is not a CPU-time cap"), so demoting noise threads does not protect a
 * critical thread's tail latency there; Windows has not been measured by the authors.</p>
 */
@Tag("effect")
@EnabledOnOs(OS.MAC)
class QosEffectTest {

    private static final int CPUS = Runtime.getRuntime().availableProcessors();

    @BeforeAll
    static void requireSupport() throws InterruptedException {
        assumeTrue(ThreadQos.isSupported(), "QoS unsupported: " + ThreadQos.capabilities().unsupportedReason());
        Thread warmUp = Thread.ofPlatform().start(() -> work(50_000_000L));
        warmUp.join();
    }

    /**
     * On Apple silicon {@code BACKGROUND} threads are scheduled on efficiency cores, so identical
     * work takes longer than on a {@code USER_INTERACTIVE} thread. Five interleaved runs per class,
     * ratio of the medians (a single run that spilled onto the other core type is ignored); an
     * idle M-series Mac shows about 1.5, the test requires 1.15.
     */
    @Test
    @EnabledOnOs(value = OS.MAC, architectures = "aarch64")
    void backgroundThreadRunsSlowerOnEfficiencyCores() throws InterruptedException {
        int runs = 5;
        long[] interactive = new long[runs];
        long[] background = new long[runs];
        for (int i = 0; i < runs; i++) {
            interactive[i] = elapsedOn(QosClass.USER_INTERACTIVE, 200_000_000L);
            background[i] = elapsedOn(QosClass.BACKGROUND, 200_000_000L);
        }
        Arrays.sort(interactive);
        Arrays.sort(background);
        double ratio = (double) background[runs / 2] / interactive[runs / 2];
        System.out.printf(Locale.ROOT, "[effect] USER_INTERACTIVE median=%d ms %s | BACKGROUND median=%d ms %s | ratio=%.2f%n",
                interactive[runs / 2] / 1_000_000, millis(interactive), background[runs / 2] / 1_000_000, millis(background), ratio);
        assertTrue(ratio >= 1.15, "expected BACKGROUND to be at least 15% slower, ratio=" + ratio);
    }

    /**
     * With {@code 2 × CPUs} spinning noise threads, a critical thread's p99 chunk duration is tens
     * of milliseconds when everything shares {@code DEFAULT}, and stays within a couple of times
     * its p50 when the noise is {@code BACKGROUND}. Requires a 3× improvement in the better of two
     * attempts; an idle M-series Mac shows 40× to 80×.
     */
    @Test
    void demotingNoiseToBackgroundProtectsCriticalTail() throws InterruptedException {
        double bestRatio = 0;
        for (int attempt = 0; attempt < 2 && bestRatio < 3.0; attempt++) {
            long[] untouched = measureUnderLoad(null, null, CPUS * 2, 2_000);
            long[] demoted = measureUnderLoad(QosClass.USER_INTERACTIVE, QosClass.BACKGROUND, CPUS * 2, 2_000);
            long p99Untouched = percentile(untouched, 99);
            long p99Demoted = percentile(demoted, 99);
            double ratio = (double) p99Untouched / p99Demoted;
            System.out.printf(Locale.ROOT,
                    "[effect] attempt %d: no QoS p50=%d us p99=%d us max=%d us | noise=BACKGROUND p50=%d us p99=%d us max=%d us | p99 ratio=%.1f%n",
                    attempt + 1, percentile(untouched, 50) / 1_000, p99Untouched / 1_000, untouched[untouched.length - 1] / 1_000,
                    percentile(demoted, 50) / 1_000, p99Demoted / 1_000, demoted[demoted.length - 1] / 1_000, ratio);
            bestRatio = Math.max(bestRatio, ratio);
        }
        assertTrue(bestRatio >= 3.0, "expected p99 to improve at least 3x with BACKGROUND noise, best ratio=" + bestRatio);
    }

    // --- measurement helpers (same workload as examples/comparison) -----------------------------

    private static long elapsedOn(QosClass qos, long iterations) throws InterruptedException {
        long[] elapsed = new long[1];
        Thread t = Thread.ofPlatform().start(ThreadQos.wrap(qos, (Runnable) () -> elapsed[0] = timed(() -> work(iterations))));
        t.join();
        return elapsed[0];
    }

    private static long[] measureUnderLoad(QosClass critical, QosClass noise, int noiseThreads, int samples)
            throws InterruptedException {
        AtomicBoolean running = new AtomicBoolean(true);
        Runnable spin = () -> {
            long sink = 0;
            while (running.get()) {
                sink += work(1_000_000);
            }
            if (sink == 42) {
                System.out.println();
            }
        };
        List<Thread> noisy = new ArrayList<>(noiseThreads);
        for (int i = 0; i < noiseThreads; i++) {
            noisy.add(Thread.ofPlatform().start(noise == null ? spin : ThreadQos.wrap(noise, spin)));
        }
        Thread.sleep(300);

        long[] nanos = new long[samples];
        Runnable measure = () -> {
            for (int i = 0; i < nanos.length; i++) {
                nanos[i] = timed(() -> work(200_000));
            }
        };
        Thread criticalThread = Thread.ofPlatform().start(critical == null ? measure : ThreadQos.wrap(critical, measure));
        criticalThread.join();

        running.set(false);
        for (Thread t : noisy) {
            t.join();
        }
        Arrays.sort(nanos);
        return nanos;
    }

    private static String millis(long[] nanos) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < nanos.length; i++) {
            sb.append(i == 0 ? "" : " ").append(nanos[i] / 1_000_000);
        }
        return sb.append("]").toString();
    }

    private static long work(long iterations) {
        long x = 0x9E3779B97F4A7C15L;
        for (long i = 0; i < iterations; i++) {
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
        }
        return x;
    }

    private static long timed(Runnable r) {
        long start = System.nanoTime();
        r.run();
        return System.nanoTime() - start;
    }

    private static long percentile(long[] sorted, int percent) {
        int index = (int) Math.min(sorted.length - 1, Math.round(sorted.length * (percent / 100.0)));
        return sorted[index];
    }
}
