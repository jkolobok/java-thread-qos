package com.clericode.threadqos.internal.noop;

import com.clericode.threadqos.Architecture;
import com.clericode.threadqos.OperatingSystem;
import com.clericode.threadqos.QosCapabilities;
import com.clericode.threadqos.QosClass;
import com.clericode.threadqos.internal.ThreadQosProvider;

import java.util.Objects;

/**
 * Provider for unsupported platforms: {@code isSupported() == false}, {@code set()} does nothing,
 * nothing is logged. The reason is available through {@link #capabilities()}.
 */
public final class NoOpThreadQosProvider implements ThreadQosProvider {

    private final QosCapabilities capabilities;

    /**
     * Creates a no-op provider.
     *
     * @param operatingSystem detected operating system
     * @param architecture    detected architecture
     * @param reason          why the platform is unsupported
     */
    public NoOpThreadQosProvider(OperatingSystem operatingSystem, Architecture architecture, String reason) {
        this.capabilities = QosCapabilities.unsupported(operatingSystem, architecture, Objects.requireNonNull(reason, "reason"));
    }

    @Override
    public boolean isSupported() {
        return false;
    }

    @Override
    public QosCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public boolean set(QosClass qosClass) {
        return false;
    }

    @Override
    public String toString() {
        return "NoOpThreadQosProvider[" + capabilities.unsupportedReason() + "]";
    }
}
