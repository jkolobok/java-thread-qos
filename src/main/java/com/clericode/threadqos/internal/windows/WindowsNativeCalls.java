package com.clericode.threadqos.internal.windows;

import com.clericode.threadqos.ThreadQosException;

/**
 * Seam between {@link WindowsThreadQosProvider} and the Win32 API, so the provider's probe and
 * error handling can be unit-tested with a fake on any OS. The only production implementation is
 * {@link WindowsQosNative}.
 *
 * <p>All operations target the calling thread ({@code GetCurrentThread()}).</p>
 */
public interface WindowsNativeCalls {

    /**
     * Result of {@code GetThreadInformation(ThreadPowerThrottling)}.
     *
     * @param error       0 on success, otherwise {@code GetLastError()}
     * @param controlMask {@code ControlMask} read back (valid when {@code error == 0})
     * @param stateMask   {@code StateMask} read back (valid when {@code error == 0})
     */
    record PowerThrottlingRead(int error, int controlMask, int stateMask) {
    }

    /**
     * {@code SetThreadInformation(GetCurrentThread(), ThreadPowerThrottling, &state, sizeof state)}.
     *
     * @param controlMask {@code ControlMask}
     * @param stateMask   {@code StateMask}
     * @return 0 on success, otherwise {@code GetLastError()} captured right after the call
     * @throws ThreadQosException if the bindings cannot be linked
     */
    int setPowerThrottling(int controlMask, int stateMask);

    /**
     * {@code GetThreadInformation(GetCurrentThread(), ThreadPowerThrottling, &state, sizeof state)}.
     *
     * @return the read result
     * @throws ThreadQosException if the bindings cannot be linked
     */
    PowerThrottlingRead getPowerThrottling();

    /**
     * {@code SetThreadPriority(GetCurrentThread(), priority)}.
     *
     * @param priority {@code THREAD_PRIORITY_*}
     * @return 0 on success, otherwise {@code GetLastError()} captured right after the call
     * @throws ThreadQosException if the bindings cannot be linked
     */
    int setPriority(int priority);

    /**
     * {@code GetThreadPriority(GetCurrentThread())}.
     *
     * @return the priority, or {@link WindowsQosState#THREAD_PRIORITY_ERROR_RETURN} on failure
     * @throws ThreadQosException if the bindings cannot be linked
     */
    int getPriority();
}
