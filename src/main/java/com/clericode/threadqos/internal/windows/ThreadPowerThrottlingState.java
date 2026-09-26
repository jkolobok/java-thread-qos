package com.clericode.threadqos.internal.windows;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;

/**
 * Explicit FFM layout of {@code THREAD_POWER_THROTTLING_STATE}.
 *
 * <p>ABI source: Windows SDK {@code processthreadsapi.h}:</p>
 * <pre>
 * typedef struct _THREAD_POWER_THROTTLING_STATE {
 *     ULONG Version;      // offset 0
 *     ULONG ControlMask;  // offset 4
 *     ULONG StateMask;    // offset 8
 * } THREAD_POWER_THROTTLING_STATE;  // sizeof == 12, alignment 4
 * </pre>
 * <p>{@code ULONG} is a 32-bit unsigned integer on every Windows ABI, hence {@code JAVA_INT}.</p>
 */
public final class ThreadPowerThrottlingState {

    /** The struct layout. */
    public static final StructLayout LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("Version"),
            ValueLayout.JAVA_INT.withName("ControlMask"),
            ValueLayout.JAVA_INT.withName("StateMask"));

    /** {@code sizeof(THREAD_POWER_THROTTLING_STATE)} passed as {@code ThreadInformationSize}. */
    public static final int SIZE = (int) LAYOUT.byteSize();

    // Windows SDK processthreadsapi.h
    /** {@code THREAD_POWER_THROTTLING_CURRENT_VERSION}. */
    public static final int THREAD_POWER_THROTTLING_CURRENT_VERSION = 1;
    /** {@code THREAD_POWER_THROTTLING_EXECUTION_SPEED}. */
    public static final int THREAD_POWER_THROTTLING_EXECUTION_SPEED = 0x1;

    private static final VarHandle VERSION = handle("Version");
    private static final VarHandle CONTROL_MASK = handle("ControlMask");
    private static final VarHandle STATE_MASK = handle("StateMask");

    private ThreadPowerThrottlingState() {
    }

    private static VarHandle handle(String field) {
        return LAYOUT.varHandle(MemoryLayout.PathElement.groupElement(field));
    }

    /**
     * Fills a segment allocated with {@link #LAYOUT}; {@code Version} is always the current version.
     *
     * @param segment     target segment
     * @param controlMask {@code ControlMask}
     * @param stateMask   {@code StateMask}
     */
    public static void write(MemorySegment segment, int controlMask, int stateMask) {
        VERSION.set(segment, 0L, THREAD_POWER_THROTTLING_CURRENT_VERSION);
        CONTROL_MASK.set(segment, 0L, controlMask);
        STATE_MASK.set(segment, 0L, stateMask);
    }

    /**
     * Reads {@code Version}.
     *
     * @param segment segment allocated with {@link #LAYOUT}
     * @return the field value
     */
    public static int version(MemorySegment segment) {
        return (int) VERSION.get(segment, 0L);
    }

    /**
     * Reads {@code ControlMask}.
     *
     * @param segment segment allocated with {@link #LAYOUT}
     * @return the field value
     */
    public static int controlMask(MemorySegment segment) {
        return (int) CONTROL_MASK.get(segment, 0L);
    }

    /**
     * Reads {@code StateMask}.
     *
     * @param segment segment allocated with {@link #LAYOUT}
     * @return the field value
     */
    public static int stateMask(MemorySegment segment) {
        return (int) STATE_MASK.get(segment, 0L);
    }
}
