package com.clericode.threadqos.internal.linux;

import com.clericode.threadqos.ThreadQosException;

import java.lang.foreign.MemorySegment;

/**
 * Seam between {@link LinuxThreadQosProvider} and the raw syscalls, so probe and error handling can
 * be unit-tested with a fake on any OS. The only production implementation is {@link LinuxQosNative}.
 *
 * <p>All operations target the calling thread ({@code pid == 0}).</p>
 */
public interface LinuxNativeCalls {

    /**
     * Outcome of a raw {@code syscall(2)}.
     *
     * @param value return value ({@code -1} on failure)
     * @param errno {@code errno} captured right after the call (meaningful when {@code value == -1})
     */
    record Result(long value, int errno) {
        /**
         * Whether the syscall failed.
         *
         * @return {@code true} if {@code value == -1}
         */
        public boolean failed() {
            return value == -1;
        }
    }

    /**
     * {@code sched_getattr(0, attr, size, 0)}.
     *
     * @param attr segment allocated with {@link LinuxSchedAttr#LAYOUT}
     * @param size the {@code size} argument (bytes available in {@code attr})
     * @return result
     * @throws ThreadQosException if the bindings cannot be linked
     */
    Result schedGetAttr(MemorySegment attr, int size);

    /**
     * {@code sched_setattr(0, attr, 0)}.
     *
     * @param attr segment allocated with {@link LinuxSchedAttr#LAYOUT}, {@code size} field filled
     * @return result
     * @throws ThreadQosException if the bindings cannot be linked
     */
    Result schedSetAttr(MemorySegment attr);
}
