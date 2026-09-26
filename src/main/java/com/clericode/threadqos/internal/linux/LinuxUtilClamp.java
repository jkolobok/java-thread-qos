package com.clericode.threadqos.internal.linux;

import com.clericode.threadqos.QosClass;

/**
 * The utilization clamp a portable class maps to (spec §15.1, §15.2). The values are scheduler
 * performance/capacity hints on the 0..1024 scale, not CPU-time reservations.
 *
 * @param min {@code sched_util_min}
 * @param max {@code sched_util_max}
 */
public record LinuxUtilClamp(int min, int max) {

    /**
     * Canonical constructor; validates the range.
     *
     * @param min {@code sched_util_min}, 0..1024
     * @param max {@code sched_util_max}, {@code min}..1024
     */
    public LinuxUtilClamp {
        if (min < 0 || max > LinuxSchedAttr.UCLAMP_MAX_VALUE || min > max) {
            throw new IllegalArgumentException("invalid uclamp range " + min + ".." + max);
        }
    }

    /**
     * The single mapping switch.
     *
     * @param qosClass portable class
     * @return the clamp
     */
    public static LinuxUtilClamp of(QosClass qosClass) {
        return switch (qosClass) {
            case USER_INTERACTIVE -> new LinuxUtilClamp(1024, 1024);
            case USER_INITIATED -> new LinuxUtilClamp(768, 1024);
            case DEFAULT -> new LinuxUtilClamp(0, 1024);
            case UTILITY -> new LinuxUtilClamp(0, 768);
            case BACKGROUND -> new LinuxUtilClamp(0, 256);
        };
    }
}
