package com.clericode.threadqos.internal.linux;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;

/**
 * Explicit FFM layout of {@code struct sched_attr} plus the scheduler constants used with it.
 *
 * <p>ABI source: Linux UAPI {@code include/uapi/linux/sched/types.h} (struct) and
 * {@code include/uapi/linux/sched.h} (policies and {@code SCHED_FLAG_*}):</p>
 * <pre>
 * struct sched_attr {
 *     __u32 size;             // offset  0
 *     __u32 sched_policy;     // offset  4
 *     __u64 sched_flags;      // offset  8
 *     __s32 sched_nice;       // offset 16
 *     __u32 sched_priority;   // offset 20
 *     __u64 sched_runtime;    // offset 24
 *     __u64 sched_deadline;   // offset 32
 *     __u64 sched_period;     // offset 40
 *     __u32 sched_util_min;   // offset 48   (since 5.3, SCHED_ATTR_SIZE_VER1)
 *     __u32 sched_util_max;   // offset 52
 * };                          // sizeof == 56, alignment 8
 * </pre>
 * <p>The layout is identical on x86_64 and aarch64 (LP64, natural alignment, no packing).</p>
 */
public final class LinuxSchedAttr {

    /** The struct layout. */
    public static final StructLayout LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("size"),
            ValueLayout.JAVA_INT.withName("sched_policy"),
            ValueLayout.JAVA_LONG.withName("sched_flags"),
            ValueLayout.JAVA_INT.withName("sched_nice"),
            ValueLayout.JAVA_INT.withName("sched_priority"),
            ValueLayout.JAVA_LONG.withName("sched_runtime"),
            ValueLayout.JAVA_LONG.withName("sched_deadline"),
            ValueLayout.JAVA_LONG.withName("sched_period"),
            ValueLayout.JAVA_INT.withName("sched_util_min"),
            ValueLayout.JAVA_INT.withName("sched_util_max"));

    /** {@code SCHED_ATTR_SIZE_VER0}: size of the first published struct (no uclamp fields). */
    public static final int SIZE_VER0 = 48;
    /** {@code SCHED_ATTR_SIZE_VER1}: adds {@code sched_util_min}/{@code sched_util_max} (Linux 5.3). */
    public static final int SIZE_VER1 = 56;
    /** {@code sizeof(struct sched_attr)} as laid out here; equals {@link #SIZE_VER1}. */
    public static final int SIZE = (int) LAYOUT.byteSize();

    // Linux UAPI include/uapi/linux/sched.h, scheduling policies:
    /** {@code SCHED_NORMAL} (a.k.a. {@code SCHED_OTHER}). */
    public static final int SCHED_NORMAL = 0;
    /** {@code SCHED_FIFO}. */
    public static final int SCHED_FIFO = 1;
    /** {@code SCHED_RR}. */
    public static final int SCHED_RR = 2;
    /** {@code SCHED_BATCH}. */
    public static final int SCHED_BATCH = 3;
    /** {@code SCHED_IDLE}. */
    public static final int SCHED_IDLE = 5;
    /** {@code SCHED_DEADLINE}. */
    public static final int SCHED_DEADLINE = 6;
    /** {@code SCHED_EXT} (sched_ext, Linux 6.12+). */
    public static final int SCHED_EXT = 7;

    // Linux UAPI include/uapi/linux/sched.h, sched_attr.sched_flags:
    /** {@code SCHED_FLAG_RESET_ON_FORK}. */
    public static final long SCHED_FLAG_RESET_ON_FORK = 0x01;
    /** {@code SCHED_FLAG_RECLAIM}. */
    public static final long SCHED_FLAG_RECLAIM = 0x02;
    /** {@code SCHED_FLAG_DL_OVERRUN}. */
    public static final long SCHED_FLAG_DL_OVERRUN = 0x04;
    /** {@code SCHED_FLAG_KEEP_POLICY}: keep the current policy (Linux 5.3). */
    public static final long SCHED_FLAG_KEEP_POLICY = 0x08;
    /** {@code SCHED_FLAG_KEEP_PARAMS}: keep nice / RT priority / deadline params (Linux 5.3). */
    public static final long SCHED_FLAG_KEEP_PARAMS = 0x10;
    /** {@code SCHED_FLAG_UTIL_CLAMP_MIN}. */
    public static final long SCHED_FLAG_UTIL_CLAMP_MIN = 0x20;
    /** {@code SCHED_FLAG_UTIL_CLAMP_MAX}. */
    public static final long SCHED_FLAG_UTIL_CLAMP_MAX = 0x40;
    /** {@code SCHED_FLAG_KEEP_ALL}. */
    public static final long SCHED_FLAG_KEEP_ALL = SCHED_FLAG_KEEP_POLICY | SCHED_FLAG_KEEP_PARAMS;
    /** {@code SCHED_FLAG_UTIL_CLAMP}. */
    public static final long SCHED_FLAG_UTIL_CLAMP = SCHED_FLAG_UTIL_CLAMP_MIN | SCHED_FLAG_UTIL_CLAMP_MAX;

    /** {@code SCHED_CAPACITY_SCALE}: upper bound of the uclamp scale (0..1024). */
    public static final int UCLAMP_MAX_VALUE = 1024;

    // Linux UAPI include/uapi/asm-generic/errno-base.h, errno.h (identical on x86_64 and aarch64):
    /** {@code EPERM}. */
    public static final int EPERM = 1;
    /** {@code ESRCH}. */
    public static final int ESRCH = 3;
    /** {@code E2BIG}. */
    public static final int E2BIG = 7;
    /** {@code EFAULT}. */
    public static final int EFAULT = 14;
    /** {@code EINVAL}. */
    public static final int EINVAL = 22;
    /** {@code ENOSYS}. */
    public static final int ENOSYS = 38;
    /** {@code EOPNOTSUPP}: what {@code uclamp_validate()} returns without {@code CONFIG_UCLAMP_TASK}. */
    public static final int EOPNOTSUPP = 95;

    private static final VarHandle SIZE_H = handle("size");
    private static final VarHandle POLICY_H = handle("sched_policy");
    private static final VarHandle FLAGS_H = handle("sched_flags");
    private static final VarHandle NICE_H = handle("sched_nice");
    private static final VarHandle PRIORITY_H = handle("sched_priority");
    private static final VarHandle RUNTIME_H = handle("sched_runtime");
    private static final VarHandle DEADLINE_H = handle("sched_deadline");
    private static final VarHandle PERIOD_H = handle("sched_period");
    private static final VarHandle UTIL_MIN_H = handle("sched_util_min");
    private static final VarHandle UTIL_MAX_H = handle("sched_util_max");

    private LinuxSchedAttr() {
    }

    private static VarHandle handle(String field) {
        return LAYOUT.varHandle(MemoryLayout.PathElement.groupElement(field));
    }

    /** @param attr segment allocated with {@link #LAYOUT} @return {@code size} */
    public static int size(MemorySegment attr) {
        return (int) SIZE_H.get(attr, 0L);
    }

    /** @param attr segment @param value {@code size} */
    public static void size(MemorySegment attr, int value) {
        SIZE_H.set(attr, 0L, value);
    }

    /** @param attr segment @return {@code sched_policy} */
    public static int policy(MemorySegment attr) {
        return (int) POLICY_H.get(attr, 0L);
    }

    /** @param attr segment @param value {@code sched_policy} */
    public static void policy(MemorySegment attr, int value) {
        POLICY_H.set(attr, 0L, value);
    }

    /** @param attr segment @return {@code sched_flags} */
    public static long flags(MemorySegment attr) {
        return (long) FLAGS_H.get(attr, 0L);
    }

    /** @param attr segment @param value {@code sched_flags} */
    public static void flags(MemorySegment attr, long value) {
        FLAGS_H.set(attr, 0L, value);
    }

    /** @param attr segment @return {@code sched_nice} */
    public static int nice(MemorySegment attr) {
        return (int) NICE_H.get(attr, 0L);
    }

    /** @param attr segment @param value {@code sched_nice} */
    public static void nice(MemorySegment attr, int value) {
        NICE_H.set(attr, 0L, value);
    }

    /** @param attr segment @return {@code sched_priority} */
    public static int priority(MemorySegment attr) {
        return (int) PRIORITY_H.get(attr, 0L);
    }

    /** @param attr segment @param value {@code sched_priority} */
    public static void priority(MemorySegment attr, int value) {
        PRIORITY_H.set(attr, 0L, value);
    }

    /** @param attr segment @return {@code sched_runtime} */
    public static long runtime(MemorySegment attr) {
        return (long) RUNTIME_H.get(attr, 0L);
    }

    /** @param attr segment @return {@code sched_deadline} */
    public static long deadline(MemorySegment attr) {
        return (long) DEADLINE_H.get(attr, 0L);
    }

    /** @param attr segment @return {@code sched_period} */
    public static long period(MemorySegment attr) {
        return (long) PERIOD_H.get(attr, 0L);
    }

    /** @param attr segment @return {@code sched_util_min} */
    public static int utilMin(MemorySegment attr) {
        return (int) UTIL_MIN_H.get(attr, 0L);
    }

    /** @param attr segment @param value {@code sched_util_min} */
    public static void utilMin(MemorySegment attr, int value) {
        UTIL_MIN_H.set(attr, 0L, value);
    }

    /** @param attr segment @return {@code sched_util_max} */
    public static int utilMax(MemorySegment attr) {
        return (int) UTIL_MAX_H.get(attr, 0L);
    }

    /** @param attr segment @param value {@code sched_util_max} */
    public static void utilMax(MemorySegment attr, int value) {
        UTIL_MAX_H.set(attr, 0L, value);
    }

    /**
     * Whether the policy is one the portable API operates on (spec §16.1, D-04).
     *
     * @param policy {@code sched_policy}
     * @return {@code true} for {@code SCHED_NORMAL}, {@code SCHED_BATCH}, {@code SCHED_IDLE}
     */
    public static boolean isFairPolicy(int policy) {
        return policy == SCHED_NORMAL || policy == SCHED_BATCH || policy == SCHED_IDLE;
    }

    /**
     * Symbolic name of a policy for messages.
     *
     * @param policy {@code sched_policy}
     * @return name such as {@code "SCHED_FIFO"} or {@code "policy 42"}
     */
    public static String policyName(int policy) {
        return switch (policy) {
            case SCHED_NORMAL -> "SCHED_OTHER";
            case SCHED_FIFO -> "SCHED_FIFO";
            case SCHED_RR -> "SCHED_RR";
            case SCHED_BATCH -> "SCHED_BATCH";
            case SCHED_IDLE -> "SCHED_IDLE";
            case SCHED_DEADLINE -> "SCHED_DEADLINE";
            case SCHED_EXT -> "SCHED_EXT";
            default -> "policy " + policy;
        };
    }

    /**
     * Symbolic errno with a short meaning in the context of {@code sched_setattr}/{@code sched_getattr}.
     *
     * @param errno the errno value
     * @return description or {@code null}
     */
    public static String describeErrno(int errno) {
        return switch (errno) {
            case EPERM -> "EPERM: operation not permitted (seccomp/security policy, or insufficient privileges)";
            case ESRCH -> "ESRCH: no such thread";
            case E2BIG -> "E2BIG: sched_attr larger than the kernel's";
            case EFAULT -> "EFAULT: bad sched_attr address";
            case EINVAL -> "EINVAL: invalid sched_attr size/flags/values, or kernel too old for uclamp";
            case ENOSYS -> "ENOSYS: syscall not available";
            case EOPNOTSUPP -> "EOPNOTSUPP: kernel built without CONFIG_UCLAMP_TASK";
            default -> null;
        };
    }
}
