package com.clericode.threadqos.internal.macos;

import com.clericode.threadqos.Architecture;
import com.clericode.threadqos.OperatingSystem;
import com.clericode.threadqos.QosCapabilities;
import com.clericode.threadqos.QosClass;
import com.clericode.threadqos.ThreadQosException;
import com.clericode.threadqos.internal.ThreadQosProvider;

import java.util.Objects;

/**
 * macOS provider: {@code pthread_set_qos_class_self_np(QOS_CLASS_*, 0)}.
 *
 * <p>Every supported macOS (10.10+) has the API, so capabilities are static and no probe is
 * needed. Linkage happens on the first {@link #set(QosClass)}.</p>
 */
public final class MacOsThreadQosProvider implements ThreadQosProvider {

    private final QosCapabilities capabilities;

    /**
     * Creates the provider. Performs no native call.
     *
     * @param architecture detected architecture
     */
    public MacOsThreadQosProvider(Architecture architecture) {
        Objects.requireNonNull(architecture, "architecture");
        this.capabilities = QosCapabilities.supported(OperatingSystem.MACOS, architecture,
                /* nativeReadbackSupported */ true,
                /* performanceHintSupported */ true,
                /* powerThrottlingSupported */ false,
                /* priorityAdjustmentSupported */ false);
    }

    @Override
    public boolean isSupported() {
        return true;
    }

    @Override
    public QosCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public boolean set(QosClass qosClass) {
        int result = MacOsQosNative.setQosClassSelf(MacOsQosNative.toNativeQosClass(qosClass),
                MacOsQosNative.RELATIVE_PRIORITY_DEFAULT);
        if (result != 0) {
            throw ThreadQosException.nativeFailure(OperatingSystem.MACOS, qosClass,
                    MacOsQosNative.SET_QOS_CLASS_SELF, result, describe(result));
        }
        return true;
    }

    private static String describe(int errno) {
        if (errno == MacOsQosNative.EPERM) {
            return "EPERM: the thread has opted out of the QoS class system, typically because "
                    + "pthread_setschedparam() was called on it (e.g. HotSpot -XX:ThreadPriorityPolicy=1)";
        }
        return null;
    }

    @Override
    public String toString() {
        return "MacOsThreadQosProvider";
    }
}
