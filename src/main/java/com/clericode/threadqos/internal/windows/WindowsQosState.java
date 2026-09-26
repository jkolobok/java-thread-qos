package com.clericode.threadqos.internal.windows;

import com.clericode.threadqos.QosClass;

import static com.clericode.threadqos.internal.windows.ThreadPowerThrottlingState.THREAD_POWER_THROTTLING_EXECUTION_SPEED;

/**
 * The native state a portable class maps to on Windows (spec §8.2, §9.1): a power-throttling
 * state (HighQoS / system-managed / EcoQoS) plus a {@code SetThreadPriority} level.
 *
 * <p>Never used, by design: {@code THREAD_PRIORITY_TIME_CRITICAL}, {@code REALTIME_PRIORITY_CLASS},
 * {@code THREAD_MODE_BACKGROUND_BEGIN/END} (spec §9.1, §9.2).</p>
 *
 * @param controlMask {@code THREAD_POWER_THROTTLING_STATE.ControlMask}
 * @param stateMask   {@code THREAD_POWER_THROTTLING_STATE.StateMask}
 * @param priority    {@code THREAD_PRIORITY_*} value for {@code SetThreadPriority}
 */
public record WindowsQosState(int controlMask, int stateMask, int priority) {

    // Windows SDK winbase.h / winnt.h (THREAD_BASE_PRIORITY_*):
    /** {@code THREAD_PRIORITY_LOWEST} = {@code THREAD_BASE_PRIORITY_MIN}. */
    public static final int THREAD_PRIORITY_LOWEST = -2;
    /** {@code THREAD_PRIORITY_BELOW_NORMAL} = {@code THREAD_PRIORITY_LOWEST + 1}. */
    public static final int THREAD_PRIORITY_BELOW_NORMAL = -1;
    /** {@code THREAD_PRIORITY_NORMAL}. */
    public static final int THREAD_PRIORITY_NORMAL = 0;
    /** {@code THREAD_PRIORITY_ABOVE_NORMAL} = {@code THREAD_PRIORITY_HIGHEST - 1}. */
    public static final int THREAD_PRIORITY_ABOVE_NORMAL = 1;
    /** {@code THREAD_PRIORITY_HIGHEST} = {@code THREAD_BASE_PRIORITY_MAX}. */
    public static final int THREAD_PRIORITY_HIGHEST = 2;
    /** {@code THREAD_PRIORITY_TIME_CRITICAL} = {@code THREAD_BASE_PRIORITY_LOWRT}; documented only, never applied. */
    public static final int THREAD_PRIORITY_TIME_CRITICAL = 15;
    /** {@code THREAD_PRIORITY_IDLE} = {@code THREAD_BASE_PRIORITY_IDLE}; documented only, never applied. */
    public static final int THREAD_PRIORITY_IDLE = -15;
    /** {@code THREAD_PRIORITY_ERROR_RETURN} = {@code MAXLONG}: returned by {@code GetThreadPriority} on failure. */
    public static final int THREAD_PRIORITY_ERROR_RETURN = Integer.MAX_VALUE;

    /** HighQoS: execution-speed throttling is controlled and switched off. */
    public static final int HIGH_QOS_CONTROL = THREAD_POWER_THROTTLING_EXECUTION_SPEED;
    /** HighQoS state mask. */
    public static final int HIGH_QOS_STATE = 0;
    /** EcoQoS: execution-speed throttling is controlled and switched on. */
    public static final int ECO_QOS_CONTROL = THREAD_POWER_THROTTLING_EXECUTION_SPEED;
    /** EcoQoS state mask. */
    public static final int ECO_QOS_STATE = THREAD_POWER_THROTTLING_EXECUTION_SPEED;
    /** System-managed: the system decides. */
    public static final int SYSTEM_MANAGED_CONTROL = 0;
    /** System-managed state mask. */
    public static final int SYSTEM_MANAGED_STATE = 0;

    /**
     * The single mapping switch (spec §9.1).
     *
     * @param qosClass portable class
     * @return native state
     */
    public static WindowsQosState of(QosClass qosClass) {
        return switch (qosClass) {
            case USER_INTERACTIVE -> new WindowsQosState(HIGH_QOS_CONTROL, HIGH_QOS_STATE, THREAD_PRIORITY_HIGHEST);
            case USER_INITIATED -> new WindowsQosState(HIGH_QOS_CONTROL, HIGH_QOS_STATE, THREAD_PRIORITY_ABOVE_NORMAL);
            case DEFAULT -> new WindowsQosState(SYSTEM_MANAGED_CONTROL, SYSTEM_MANAGED_STATE, THREAD_PRIORITY_NORMAL);
            case UTILITY -> new WindowsQosState(SYSTEM_MANAGED_CONTROL, SYSTEM_MANAGED_STATE, THREAD_PRIORITY_BELOW_NORMAL);
            case BACKGROUND -> new WindowsQosState(ECO_QOS_CONTROL, ECO_QOS_STATE, THREAD_PRIORITY_LOWEST);
        };
    }
}
