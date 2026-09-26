package com.clericode.threadqos;

/**
 * CPU architecture detected once at provider initialization.
 *
 * <p>On Linux the architecture selects the {@code sched_setattr}/{@code sched_getattr} syscall
 * numbers; an unknown architecture makes the Linux provider report itself as unsupported instead
 * of guessing.</p>
 */
public enum Architecture {

    /** 64-bit x86 ({@code os.arch} {@code amd64}, {@code x86_64} or {@code x64}). */
    X86_64,

    /** 64-bit ARM ({@code os.arch} {@code aarch64} or {@code arm64}). */
    AARCH64,

    /** Any other architecture. */
    OTHER
}
