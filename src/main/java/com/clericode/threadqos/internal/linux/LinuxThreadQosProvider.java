package com.clericode.threadqos.internal.linux;

import com.clericode.threadqos.Architecture;
import com.clericode.threadqos.OperatingSystem;
import com.clericode.threadqos.QosCapabilities;
import com.clericode.threadqos.QosClass;
import com.clericode.threadqos.ThreadQosException;
import com.clericode.threadqos.internal.ThreadQosProvider;
import com.clericode.threadqos.internal.noop.NoOpThreadQosProvider;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * Linux provider: utilization clamping through {@code sched_setattr(2)} (spec §10–§19).
 *
 * <h2>Capability probe (spec §18, T-5.4)</h2>
 * <p>Lazy and read-only: {@code sched_getattr(0, attr, 56, 0)}.</p>
 * <ul>
 *   <li>{@code ENOSYS} → unsupported (syscall unavailable);</li>
 *   <li>{@code EPERM} → unsupported (seccomp / security policy);</li>
 *   <li>success with returned {@code size < 56} → unsupported (kernel older than 5.3, no uclamp fields);</li>
 *   <li>success with {@code size >= 56} → candidate; the first {@code sched_setattr} returning
 *       {@code EOPNOTSUPP} (kernel built without {@code CONFIG_UCLAMP_TASK}) flips the provider to
 *       unsupported without throwing.</li>
 * </ul>
 *
 * <h2>{@code set()} (spec §15–§17, D-04)</h2>
 * <ol>
 *   <li>{@code sched_getattr} into a per-call struct;</li>
 *   <li>policy not in {@code {SCHED_OTHER, SCHED_BATCH, SCHED_IDLE}} → {@link ThreadQosException} (RT/deadline
 *       threads are outside the portable contract);</li>
 *   <li>write {@code sched_util_min}/{@code sched_util_max} for the class;</li>
 *   <li>{@code sched_flags |= KEEP_POLICY | KEEP_PARAMS | UTIL_CLAMP_MIN | UTIL_CLAMP_MAX};</li>
 *   <li>{@code sched_setattr}. Policy, nice, RT priority, deadline parameters, affinity and cgroups are
 *       never changed.</li>
 * </ol>
 */
public final class LinuxThreadQosProvider implements ThreadQosProvider {

    /**
     * Cached provider state.
     *
     * @param capabilities capabilities to report
     * @param linkFailure  non-null if the bindings could not be linked; then {@code set()} rethrows it
     */
    private record State(QosCapabilities capabilities, ThreadQosException linkFailure) {
        boolean supported() {
            return capabilities.qosSupported();
        }
    }

    private final Architecture architecture;
    private final LinuxNativeCalls calls;
    private volatile State state;

    /**
     * Creates the provider for the running architecture: a real provider for x86_64/aarch64, a
     * {@link NoOpThreadQosProvider} with a reason for anything else. Performs no native call.
     *
     * @param architecture detected architecture
     * @return the provider
     */
    public static ThreadQosProvider create(Architecture architecture) {
        Objects.requireNonNull(architecture, "architecture");
        LinuxSyscalls numbers = LinuxSyscalls.forArchitecture(architecture);
        if (numbers == null) {
            return new NoOpThreadQosProvider(OperatingSystem.LINUX, architecture,
                    "Linux architecture '" + System.getProperty("os.arch") + "' has no verified sched_setattr/"
                            + "sched_getattr syscall numbers in this version; supported: x86_64, aarch64");
        }
        return new LinuxThreadQosProvider(architecture, new LinuxQosNative(numbers));
    }

    /**
     * Creates a provider over an explicit native layer (tests).
     *
     * @param architecture detected architecture
     * @param calls        native layer
     */
    public LinuxThreadQosProvider(Architecture architecture, LinuxNativeCalls calls) {
        this.architecture = Objects.requireNonNull(architecture, "architecture");
        this.calls = Objects.requireNonNull(calls, "calls");
    }

    private State state() {
        State s = state;
        if (s != null) {
            return s;
        }
        return probe();
    }

    private synchronized State probe() {
        State s = state;
        if (s != null) {
            return s;
        }
        s = doProbe();
        state = s;
        return s;
    }

    private State doProbe() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment attr = arena.allocate(LinuxSchedAttr.LAYOUT);
            LinuxNativeCalls.Result r = calls.schedGetAttr(attr, LinuxSchedAttr.SIZE);
            if (r.failed()) {
                return unsupported(switch (r.errno()) {
                    case LinuxSchedAttr.ENOSYS -> "sched_getattr syscall is unavailable (ENOSYS)";
                    case LinuxSchedAttr.EPERM -> "sched_getattr is denied by seccomp or a security policy (EPERM)";
                    default -> "sched_getattr failed with errno " + r.errno()
                            + describe(r.errno());
                });
            }
            int size = LinuxSchedAttr.size(attr);
            if (size < LinuxSchedAttr.SIZE_VER1) {
                return unsupported("kernel returned sched_attr size " + size + " < " + LinuxSchedAttr.SIZE_VER1
                        + ": kernel older than 5.3, utilization clamping is not available");
            }
            return new State(QosCapabilities.supported(OperatingSystem.LINUX, architecture,
                    /* nativeReadbackSupported */ true,
                    /* performanceHintSupported */ true,
                    /* powerThrottlingSupported */ false,
                    /* priorityAdjustmentSupported */ false), null);
        } catch (ThreadQosException e) {
            String reason = "Linux native bindings could not be linked: " + e.getMessage();
            return new State(QosCapabilities.unsupported(OperatingSystem.LINUX, architecture, reason), e);
        }
    }

    private State unsupported(String reason) {
        return new State(QosCapabilities.unsupported(OperatingSystem.LINUX, architecture, reason), null);
    }

    private static String describe(int errno) {
        String d = LinuxSchedAttr.describeErrno(errno);
        return d == null ? "" : " (" + d + ")";
    }

    @Override
    public boolean isSupported() {
        return state().supported();
    }

    @Override
    public QosCapabilities capabilities() {
        return state().capabilities();
    }

    @Override
    public boolean set(QosClass qosClass) {
        State s = state();
        if (s.linkFailure() != null) {
            throw s.linkFailure();
        }
        if (!s.supported()) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment attr = arena.allocate(LinuxSchedAttr.LAYOUT);
            LinuxNativeCalls.Result get = calls.schedGetAttr(attr, LinuxSchedAttr.SIZE);
            if (get.failed()) {
                throw ThreadQosException.nativeFailure(OperatingSystem.LINUX, qosClass, LinuxQosNative.SCHED_GETATTR,
                        get.errno(), LinuxSchedAttr.describeErrno(get.errno()));
            }
            int policy = LinuxSchedAttr.policy(attr);
            if (!LinuxSchedAttr.isFairPolicy(policy)) {
                throw ThreadQosException.policyFailure(OperatingSystem.LINUX, qosClass, LinuxQosNative.SCHED_SETATTR,
                        "the thread uses " + LinuxSchedAttr.policyName(policy)
                                + "; real-time and deadline threads are outside the portable QoS contract "
                                + "(the kernel manages their utilization clamps)");
            }
            LinuxUtilClamp clamp = LinuxUtilClamp.of(qosClass);
            LinuxSchedAttr.size(attr, LinuxSchedAttr.SIZE_VER1);
            LinuxSchedAttr.utilMin(attr, clamp.min());
            LinuxSchedAttr.utilMax(attr, clamp.max());
            LinuxSchedAttr.flags(attr, LinuxSchedAttr.flags(attr)
                    | LinuxSchedAttr.SCHED_FLAG_KEEP_POLICY | LinuxSchedAttr.SCHED_FLAG_KEEP_PARAMS
                    | LinuxSchedAttr.SCHED_FLAG_UTIL_CLAMP_MIN | LinuxSchedAttr.SCHED_FLAG_UTIL_CLAMP_MAX);
            LinuxNativeCalls.Result set = calls.schedSetAttr(attr);
            if (set.failed()) {
                if (set.errno() == LinuxSchedAttr.EOPNOTSUPP) {
                    markUnsupported("sched_setattr with utilization clamps returned EOPNOTSUPP: "
                            + "kernel built without CONFIG_UCLAMP_TASK");
                    return false;
                }
                throw ThreadQosException.nativeFailure(OperatingSystem.LINUX, qosClass, LinuxQosNative.SCHED_SETATTR,
                        set.errno(), LinuxSchedAttr.describeErrno(set.errno()));
            }
            return true;
        }
    }

    private synchronized void markUnsupported(String reason) {
        state = unsupported(reason);
    }

    @Override
    public String toString() {
        return "LinuxThreadQosProvider[" + architecture + "]";
    }
}
