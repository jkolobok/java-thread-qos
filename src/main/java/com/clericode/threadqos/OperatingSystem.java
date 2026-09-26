package com.clericode.threadqos;

/**
 * Operating system family detected once at provider initialization.
 */
public enum OperatingSystem {

    /** Apple macOS (Darwin). QoS is applied through {@code pthread_set_qos_class_self_np}. */
    MACOS("macOS"),

    /** Microsoft Windows. QoS is applied through {@code SetThreadInformation} and {@code SetThreadPriority}. */
    WINDOWS("Windows"),

    /** Linux. QoS is applied through {@code sched_setattr(2)} utilization clamping. */
    LINUX("Linux"),

    /** Any other operating system. The library is a safe no-op there. */
    OTHER("other");

    private final String displayName;

    OperatingSystem(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the human-readable name used in exception messages, for example {@code "macOS"}.
     *
     * @return the display name
     */
    public String displayName() {
        return displayName;
    }
}
