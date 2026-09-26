package com.clericode.threadqos.internal.windows;

import com.clericode.threadqos.OperatingSystem;
import com.clericode.threadqos.internal.FfmSupport;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

/**
 * FFM bindings to {@code kernel32.dll}.
 *
 * <p>ABI source: Windows SDK {@code processthreadsapi.h}, {@code winbase.h}, {@code winerror.h};
 * Microsoft Learn pages for {@code SetThreadInformation}, {@code GetThreadInformation},
 * {@code SetThreadPriority}, {@code GetThreadPriority}, {@code GetCurrentThread}.</p>
 *
 * <p>Types: {@code HANDLE} is a pointer, {@code BOOL} and {@code THREAD_INFORMATION_CLASS} (an enum)
 * are 32-bit, {@code DWORD} is 32-bit unsigned. The x64 and ARM64 calling conventions are handled
 * by the native linker.</p>
 *
 * <p>{@code GetLastError} is read through {@link Linker.Option#captureCallState(String...)} into a
 * segment allocated per call, never a shared static one (spec §9.4; see {@link FfmSupport}).</p>
 *
 * <p>Symbols are taken from an explicit {@code kernel32.dll} lookup: on Windows the linker's default
 * lookup exposes only the C runtime, not {@code kernel32} (spec deviation, T-4.1). The pseudo
 * handle returned by {@code GetCurrentThread()} must not be closed.</p>
 */
public final class WindowsQosNative implements WindowsNativeCalls {

    /** Library that exports every symbol used here. */
    public static final String KERNEL32 = "kernel32.dll";
    /** Native function names (spec §9.3). */
    public static final String GET_CURRENT_THREAD = "GetCurrentThread";
    /** Native function name. */
    public static final String SET_THREAD_INFORMATION = "SetThreadInformation";
    /** Native function name. */
    public static final String GET_THREAD_INFORMATION = "GetThreadInformation";
    /** Native function name. */
    public static final String SET_THREAD_PRIORITY = "SetThreadPriority";
    /** Native function name. */
    public static final String GET_THREAD_PRIORITY = "GetThreadPriority";

    // Windows SDK winbase.h, enum THREAD_INFORMATION_CLASS:
    //   ThreadMemoryPriority = 0, ThreadAbsoluteCpuPriority = 1, ThreadDynamicCodePolicy = 2, ThreadPowerThrottling = 3
    /** {@code THREAD_INFORMATION_CLASS.ThreadPowerThrottling}. */
    public static final int THREAD_POWER_THROTTLING = 3;

    // Windows SDK winerror.h:
    /** {@code ERROR_ACCESS_DENIED}. */
    public static final int ERROR_ACCESS_DENIED = 5;
    /** {@code ERROR_INVALID_HANDLE}. */
    public static final int ERROR_INVALID_HANDLE = 6;
    /** {@code ERROR_NOT_SUPPORTED}. */
    public static final int ERROR_NOT_SUPPORTED = 50;
    /** {@code ERROR_INVALID_PARAMETER}: also what pre-1709 Windows 10 returns for {@code ThreadPowerThrottling}. */
    public static final int ERROR_INVALID_PARAMETER = 87;

    /** Name of the captured call-state field on Windows. */
    private static final String GET_LAST_ERROR = "GetLastError";

    /**
     * Downcall handles and the captured-state plumbing, linked once.
     *
     * @param getCurrentThread     {@code HANDLE GetCurrentThread(void)}
     * @param setThreadInformation {@code BOOL SetThreadInformation(HANDLE, THREAD_INFORMATION_CLASS, LPVOID, DWORD)}
     * @param getThreadInformation {@code BOOL GetThreadInformation(HANDLE, THREAD_INFORMATION_CLASS, LPVOID, DWORD)}
     * @param setThreadPriority    {@code BOOL SetThreadPriority(HANDLE, int)}
     * @param getThreadPriority    {@code int GetThreadPriority(HANDLE)}
     * @param captureLayout        layout of the captured call state
     * @param lastError            var handle reading {@code GetLastError} from the captured state
     */
    private record Handles(MethodHandle getCurrentThread, MethodHandle setThreadInformation,
                           MethodHandle getThreadInformation, MethodHandle setThreadPriority,
                           MethodHandle getThreadPriority, StructLayout captureLayout, VarHandle lastError) {
    }

    private static final FfmSupport.Lazy<Handles> HANDLES = new FfmSupport.Lazy<>(WindowsQosNative::linkHandles);

    /** Shared stateless instance. */
    public static final WindowsQosNative INSTANCE = new WindowsQosNative();

    private WindowsQosNative() {
    }

    private static Handles linkHandles() {
        SymbolLookup kernel32 = FfmSupport.libraryLookup(OperatingSystem.WINDOWS, KERNEL32);
        Linker.Option capture = Linker.Option.captureCallState(GET_LAST_ERROR);
        MethodHandle getCurrentThread = FfmSupport.downcall(OperatingSystem.WINDOWS, kernel32, GET_CURRENT_THREAD,
                FunctionDescriptor.of(ValueLayout.ADDRESS));
        MethodHandle setThreadInformation = FfmSupport.downcall(OperatingSystem.WINDOWS, kernel32, SET_THREAD_INFORMATION,
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                capture);
        MethodHandle getThreadInformation = FfmSupport.downcall(OperatingSystem.WINDOWS, kernel32, GET_THREAD_INFORMATION,
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                capture);
        MethodHandle setThreadPriority = FfmSupport.downcall(OperatingSystem.WINDOWS, kernel32, SET_THREAD_PRIORITY,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                capture);
        MethodHandle getThreadPriority = FfmSupport.downcall(OperatingSystem.WINDOWS, kernel32, GET_THREAD_PRIORITY,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                capture);
        StructLayout captureLayout = FfmSupport.captureStateLayout();
        VarHandle lastError = FfmSupport.captureStateHandle(GET_LAST_ERROR);
        return new Handles(getCurrentThread, setThreadInformation, getThreadInformation, setThreadPriority,
                getThreadPriority, captureLayout, lastError);
    }

    /**
     * Forces linkage of all handles. Used by tests and to surface linkage errors early.
     *
     * @throws com.clericode.threadqos.ThreadQosException if a symbol cannot be linked
     */
    public static void link() {
        HANDLES.get();
    }

    /**
     * {@code GetCurrentThread()}: a pseudo handle for the calling thread. Never close it.
     *
     * @return the pseudo handle
     */
    public static MemorySegment currentThread() {
        Handles h = HANDLES.get();
        try {
            return (MemorySegment) h.getCurrentThread().invokeExact();
        } catch (Throwable t) {
            throw FfmSupport.invocationFailure(OperatingSystem.WINDOWS, GET_CURRENT_THREAD, t);
        }
    }

    @Override
    public int setPowerThrottling(int controlMask, int stateMask) {
        Handles h = HANDLES.get();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = arena.allocate(h.captureLayout());
            MemorySegment state = arena.allocate(ThreadPowerThrottlingState.LAYOUT);
            ThreadPowerThrottlingState.write(state, controlMask, stateMask);
            int ok = (int) h.setThreadInformation().invokeExact(capture, currentThread(), THREAD_POWER_THROTTLING,
                    state, ThreadPowerThrottlingState.SIZE);
            return ok != 0 ? 0 : lastError(h, capture);
        } catch (Throwable t) {
            throw FfmSupport.invocationFailure(OperatingSystem.WINDOWS, SET_THREAD_INFORMATION, t);
        }
    }

    @Override
    public PowerThrottlingRead getPowerThrottling() {
        Handles h = HANDLES.get();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = arena.allocate(h.captureLayout());
            MemorySegment state = arena.allocate(ThreadPowerThrottlingState.LAYOUT);
            // Version must be set before the call; masks are output.
            ThreadPowerThrottlingState.write(state, 0, 0);
            int ok = (int) h.getThreadInformation().invokeExact(capture, currentThread(), THREAD_POWER_THROTTLING,
                    state, ThreadPowerThrottlingState.SIZE);
            if (ok == 0) {
                return new PowerThrottlingRead(lastError(h, capture), 0, 0);
            }
            return new PowerThrottlingRead(0, ThreadPowerThrottlingState.controlMask(state),
                    ThreadPowerThrottlingState.stateMask(state));
        } catch (Throwable t) {
            throw FfmSupport.invocationFailure(OperatingSystem.WINDOWS, GET_THREAD_INFORMATION, t);
        }
    }

    @Override
    public int setPriority(int priority) {
        return setPriority(currentThread(), priority);
    }

    /**
     * {@code SetThreadPriority(handle, priority)} on an explicit handle. Exposed for the error-path
     * integration test (an invalid handle must yield {@code ERROR_INVALID_HANDLE}).
     *
     * @param thread   thread handle
     * @param priority {@code THREAD_PRIORITY_*}
     * @return 0 on success, otherwise the captured {@code GetLastError()}
     */
    public static int setPriority(MemorySegment thread, int priority) {
        Handles h = HANDLES.get();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = arena.allocate(h.captureLayout());
            int ok = (int) h.setThreadPriority().invokeExact(capture, thread, priority);
            return ok != 0 ? 0 : lastError(h, capture);
        } catch (Throwable t) {
            throw FfmSupport.invocationFailure(OperatingSystem.WINDOWS, SET_THREAD_PRIORITY, t);
        }
    }

    @Override
    public int getPriority() {
        Handles h = HANDLES.get();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = arena.allocate(h.captureLayout());
            return (int) h.getThreadPriority().invokeExact(capture, currentThread());
        } catch (Throwable t) {
            throw FfmSupport.invocationFailure(OperatingSystem.WINDOWS, GET_THREAD_PRIORITY, t);
        }
    }

    private static int lastError(Handles h, MemorySegment capture) {
        return (int) h.lastError().get(capture, 0L);
    }

    /**
     * Human-readable meaning of a few well-known Win32 error codes.
     *
     * @param error {@code GetLastError()} value
     * @return description or {@code null}
     */
    public static String describeError(int error) {
        return switch (error) {
            case ERROR_ACCESS_DENIED -> "ERROR_ACCESS_DENIED";
            case ERROR_INVALID_HANDLE -> "ERROR_INVALID_HANDLE";
            case ERROR_NOT_SUPPORTED -> "ERROR_NOT_SUPPORTED: ThreadPowerThrottling is not available on this Windows build";
            case ERROR_INVALID_PARAMETER -> "ERROR_INVALID_PARAMETER: ThreadPowerThrottling requires Windows 10 1709 (build 16299) or later";
            default -> null;
        };
    }
}
