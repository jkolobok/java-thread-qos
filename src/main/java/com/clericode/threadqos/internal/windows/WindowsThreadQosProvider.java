package com.clericode.threadqos.internal.windows;

import com.clericode.threadqos.Architecture;
import com.clericode.threadqos.OperatingSystem;
import com.clericode.threadqos.QosCapabilities;
import com.clericode.threadqos.QosClass;
import com.clericode.threadqos.ThreadQosException;
import com.clericode.threadqos.internal.FfmSupport;
import com.clericode.threadqos.internal.ThreadQosProvider;

import java.util.Objects;

/**
 * Windows provider: {@code SetThreadInformation(ThreadPowerThrottling)} followed by
 * {@code SetThreadPriority} (spec §8, §9).
 *
 * <p>Capability probe (T-4.3): on first use a read-only
 * {@code GetThreadInformation(ThreadPowerThrottling)} decides whether power throttling exists on
 * this Windows build. {@code ERROR_INVALID_PARAMETER} / {@code ERROR_NOT_SUPPORTED} (pre-1709
 * Windows 10, some server SKUs) make {@code powerThrottlingSupported == false} with a reason;
 * the priority half keeps working, so Windows is never a no-op platform. The probe result is
 * cached; a linkage failure (native access denied, missing symbol) is cached too and rethrown from
 * {@link #set(QosClass)}.</p>
 */
public final class WindowsThreadQosProvider implements ThreadQosProvider {

    /**
     * Cached probe outcome.
     *
     * @param capabilities        capabilities to report
     * @param throttlingSupported whether to call {@code SetThreadInformation} in {@link #set(QosClass)}
     * @param linkFailure         non-null if the bindings could not be linked; then {@code set()} rethrows it
     */
    private record State(QosCapabilities capabilities, boolean throttlingSupported, ThreadQosException linkFailure) {
    }

    private final Architecture architecture;
    private final WindowsNativeCalls calls;
    private final FfmSupport.Lazy<State> state = new FfmSupport.Lazy<>(this::probe);

    /**
     * Creates the production provider. Performs no native call.
     *
     * @param architecture detected architecture
     */
    public WindowsThreadQosProvider(Architecture architecture) {
        this(architecture, WindowsQosNative.INSTANCE);
    }

    /**
     * Creates a provider over an explicit native layer (tests).
     *
     * @param architecture detected architecture
     * @param calls        native layer
     */
    public WindowsThreadQosProvider(Architecture architecture, WindowsNativeCalls calls) {
        this.architecture = Objects.requireNonNull(architecture, "architecture");
        this.calls = Objects.requireNonNull(calls, "calls");
    }

    private State probe() {
        WindowsNativeCalls.PowerThrottlingRead read;
        try {
            read = calls.getPowerThrottling();
        } catch (ThreadQosException e) {
            String reason = "Windows native bindings could not be linked: " + e.getMessage();
            return new State(QosCapabilities.unsupported(OperatingSystem.WINDOWS, architecture, reason), false, e);
        }
        boolean throttling = read.error() == 0;
        String reason = null;
        if (!throttling) {
            String detail = WindowsQosNative.describeError(read.error());
            reason = "ThreadPowerThrottling is unavailable: GetThreadInformation failed with error "
                    + read.error() + (detail != null ? " (" + detail + ")" : "")
                    + "; only SetThreadPriority is applied";
        }
        QosCapabilities caps = new QosCapabilities(OperatingSystem.WINDOWS, architecture,
                /* qosSupported */ true,
                /* nativeReadbackSupported */ true,
                /* performanceHintSupported */ throttling,
                /* powerThrottlingSupported */ throttling,
                /* priorityAdjustmentSupported */ true,
                reason);
        return new State(caps, throttling, null);
    }

    @Override
    public boolean isSupported() {
        return state.get().capabilities().qosSupported();
    }

    @Override
    public QosCapabilities capabilities() {
        return state.get().capabilities();
    }

    @Override
    public boolean set(QosClass qosClass) {
        State s = state.get();
        if (s.linkFailure() != null) {
            throw s.linkFailure();
        }
        WindowsQosState target = WindowsQosState.of(qosClass);
        if (s.throttlingSupported()) {
            int error = calls.setPowerThrottling(target.controlMask(), target.stateMask());
            if (error != 0) {
                throw ThreadQosException.nativeFailure(OperatingSystem.WINDOWS, qosClass,
                        WindowsQosNative.SET_THREAD_INFORMATION, error, WindowsQosNative.describeError(error));
            }
        }
        int error = calls.setPriority(target.priority());
        if (error != 0) {
            throw ThreadQosException.nativeFailure(OperatingSystem.WINDOWS, qosClass,
                    WindowsQosNative.SET_THREAD_PRIORITY, error, WindowsQosNative.describeError(error));
        }
        return true;
    }

    @Override
    public String toString() {
        return "WindowsThreadQosProvider";
    }
}
