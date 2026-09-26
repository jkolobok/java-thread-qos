package com.clericode.threadqos.bench;

import com.clericode.threadqos.QosCapabilities;
import com.clericode.threadqos.QosClass;
import com.clericode.threadqos.ThreadQos;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks (spec §54). Run with {@code mvn -Pbenchmarks verify exec:exec}.
 *
 * <p>The point is to show that there is no Java-side overhead in an application's hot path: QoS is
 * set once per worker thread, and {@code isSupported()} / {@code capabilities()} are cached reads.
 * The {@code set*} benchmarks measure the native call itself and are expected to cost microseconds
 * (a syscall / pthread call); that number is platform-dependent and should not be advertised as a
 * library property.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = "--enable-native-access=ALL-UNNAMED")
@State(Scope.Thread)
public class ThreadQosBenchmark {

    private final Runnable noop = () -> { };
    private final Runnable wrapped = ThreadQos.wrap(QosClass.DEFAULT, noop);

    /** Cached provider read; expected to be a couple of nanoseconds. */
    @Benchmark
    public boolean isSupported() {
        return ThreadQos.isSupported();
    }

    /** Cached capabilities record; expected to be a couple of nanoseconds. */
    @Benchmark
    public QosCapabilities capabilities() {
        return ThreadQos.capabilities();
    }

    /** Native call: pthread_set_qos_class_self_np / SetThreadInformation+SetThreadPriority / sched_setattr. */
    @Benchmark
    public void setDefault() {
        ThreadQos.set(QosClass.DEFAULT);
    }

    /** Native call with the "highest" mapping, to spot asymmetric costs. */
    @Benchmark
    public void setUserInteractive() {
        ThreadQos.set(QosClass.USER_INTERACTIVE);
    }

    /** trySet on the current platform (no exception path expected). */
    @Benchmark
    public boolean trySetDefault() {
        return ThreadQos.trySet(QosClass.DEFAULT);
    }

    /** Baseline for the wrapper benchmarks: running the task without any wrapper. */
    @Benchmark
    public void baselineRunTask(Blackhole bh) {
        noop.run();
        bh.consume(noop);
    }

    /** Wrapper creation cost (one lambda allocation). */
    @Benchmark
    public Runnable wrapCreate() {
        return ThreadQos.wrap(QosClass.DEFAULT, noop);
    }

    /** Wrapper startup overhead: one native set() plus the task. This is paid once per thread start. */
    @Benchmark
    public void wrapRun(Blackhole bh) {
        wrapped.run();
        bh.consume(wrapped);
    }
}
