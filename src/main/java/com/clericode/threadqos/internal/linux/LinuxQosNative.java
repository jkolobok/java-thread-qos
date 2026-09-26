package com.clericode.threadqos.internal.linux;

import com.clericode.threadqos.OperatingSystem;
import com.clericode.threadqos.internal.FfmSupport;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.util.Objects;

/**
 * FFM bindings to {@code long syscall(long number, ...)} from libc for {@code sched_setattr(2)} and
 * {@code sched_getattr(2)}.
 *
 * <p>ABI notes (spec §12, §19, §89):</p>
 * <ul>
 *   <li>{@code syscall} is variadic, so the downcall is created with
 *       {@link Linker.Option#firstVariadicArg(int) firstVariadicArg(1)}; without it the arguments would be
 *       passed with the non-variadic convention, which differs on some ABIs.</li>
 *   <li>Kernel prototypes: {@code int sched_setattr(pid_t pid, struct sched_attr *attr, unsigned int flags)}
 *       and {@code int sched_getattr(pid_t pid, struct sched_attr *attr, unsigned int size, unsigned int flags)}.
 *       {@code pid_t} and {@code unsigned int} are 32-bit; passed as {@code JAVA_INT} (already of
 *       default-promoted size). {@code pid == 0} means the calling thread.</li>
 *   <li>{@code syscall} returns {@code long}: {@code -1} on failure with {@code errno} set. {@code errno}
 *       is read via {@link Linker.Option#captureCallState(String...) captureCallState("errno")} into a
 *       per-call segment (never a shared static one).</li>
 * </ul>
 */
public final class LinuxQosNative implements LinuxNativeCalls {

    /** libc symbol. */
    public static final String SYSCALL = "syscall";
    /** Logical operation name used in exceptions. */
    public static final String SCHED_SETATTR = "sched_setattr";
    /** Logical operation name used in exceptions. */
    public static final String SCHED_GETATTR = "sched_getattr";

    private static final String ERRNO = "errno";
    private static final int CURRENT_THREAD_PID = 0;
    private static final int NO_FLAGS = 0;

    /**
     * Downcall handles and captured-state plumbing, linked once.
     *
     * @param setattr       {@code long syscall(long nr, int pid, struct sched_attr *attr, unsigned flags)}
     * @param getattr       {@code long syscall(long nr, int pid, struct sched_attr *attr, unsigned size, unsigned flags)}
     * @param captureLayout layout of the captured call state
     * @param errno         var handle reading {@code errno} from the captured state
     */
    private record Handles(MethodHandle setattr, MethodHandle getattr, StructLayout captureLayout, VarHandle errno) {
    }

    private final LinuxSyscalls numbers;
    private final FfmSupport.Lazy<Handles> handles = new FfmSupport.Lazy<>(LinuxQosNative::linkHandles);

    /**
     * Creates the bindings for the given syscall numbers. Performs no native call.
     *
     * @param numbers architecture-specific syscall numbers
     */
    public LinuxQosNative(LinuxSyscalls numbers) {
        this.numbers = Objects.requireNonNull(numbers, "numbers");
    }

    private static Handles linkHandles() {
        Linker.Option[] options = {Linker.Option.firstVariadicArg(1), Linker.Option.captureCallState(ERRNO)};
        MethodHandle setattr = FfmSupport.downcall(OperatingSystem.LINUX, FfmSupport.defaultLookup(), SYSCALL,
                FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                options);
        MethodHandle getattr = FfmSupport.downcall(OperatingSystem.LINUX, FfmSupport.defaultLookup(), SYSCALL,
                FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                options);
        return new Handles(setattr, getattr, FfmSupport.captureStateLayout(), FfmSupport.captureStateHandle(ERRNO));
    }

    /**
     * Forces linkage. Used by tests and to surface linkage errors early.
     *
     * @throws com.clericode.threadqos.ThreadQosException if {@code syscall} cannot be linked
     */
    public void link() {
        handles.get();
    }

    /**
     * The syscall numbers in use.
     *
     * @return numbers
     */
    public LinuxSyscalls numbers() {
        return numbers;
    }

    @Override
    public Result schedGetAttr(MemorySegment attr, int size) {
        Handles h = handles.get();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = arena.allocate(h.captureLayout());
            long r = (long) h.getattr().invokeExact(capture, numbers.getattr(), CURRENT_THREAD_PID, attr, size, NO_FLAGS);
            return new Result(r, r == -1 ? errno(h, capture) : 0);
        } catch (Throwable t) {
            throw FfmSupport.invocationFailure(OperatingSystem.LINUX, SCHED_GETATTR, t);
        }
    }

    @Override
    public Result schedSetAttr(MemorySegment attr) {
        Handles h = handles.get();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capture = arena.allocate(h.captureLayout());
            long r = (long) h.setattr().invokeExact(capture, numbers.setattr(), CURRENT_THREAD_PID, attr, NO_FLAGS);
            return new Result(r, r == -1 ? errno(h, capture) : 0);
        } catch (Throwable t) {
            throw FfmSupport.invocationFailure(OperatingSystem.LINUX, SCHED_SETATTR, t);
        }
    }

    private static int errno(Handles h, MemorySegment capture) {
        return (int) h.errno().get(capture, 0L);
    }
}
