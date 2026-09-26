package com.clericode.threadqos.internal.linux;

import com.clericode.threadqos.Architecture;

/**
 * Architecture-specific syscall numbers for {@code sched_setattr} / {@code sched_getattr}, which have
 * no glibc wrapper (spec §12, §14). Selected once at provider initialization; never guessed.
 *
 * <p>Sources: {@code arch/x86/entry/syscalls/syscall_64.tbl} (x86_64) and
 * {@code include/uapi/asm-generic/unistd.h} (aarch64 and every other asm-generic architecture).</p>
 *
 * @param setattr {@code __NR_sched_setattr}
 * @param getattr {@code __NR_sched_getattr}
 */
public record LinuxSyscalls(long setattr, long getattr) {

    /** x86_64: {@code 314 common sched_setattr}, {@code 315 common sched_getattr}. */
    public static final LinuxSyscalls X86_64 = new LinuxSyscalls(314, 315);
    /** aarch64 (asm-generic): {@code __NR_sched_setattr 274}, {@code __NR_sched_getattr 275}. */
    public static final LinuxSyscalls AARCH64 = new LinuxSyscalls(274, 275);

    /**
     * Returns the syscall numbers for an architecture, or {@code null} if the architecture is not
     * supported (riscv64, loongarch64, ppc64le, s390x, 32-bit ARM/x86 are deliberately excluded
     * until they are tested on real hardware).
     *
     * @param architecture detected architecture
     * @return numbers or {@code null}
     */
    public static LinuxSyscalls forArchitecture(Architecture architecture) {
        return switch (architecture) {
            case X86_64 -> X86_64;
            case AARCH64 -> AARCH64;
            case OTHER -> null;
        };
    }
}
