package com.clericode.threadqos.internal.linux;

import com.clericode.threadqos.Architecture;
import com.clericode.threadqos.OperatingSystem;
import com.clericode.threadqos.QosCapabilities;
import com.clericode.threadqos.QosClass;
import com.clericode.threadqos.ThreadQos;
import com.clericode.threadqos.ThreadQosException;
import com.clericode.threadqos.internal.FfmSupport;
import com.clericode.threadqos.internal.ThreadQosProvider;
import com.clericode.threadqos.internal.ThreadQosProviderFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real syscalls on Linux (spec §51, §53). The read-back through {@code sched_getattr} doubles as the
 * ABI-drift check for the struct layout and flag constants (spec §76).
 *
 * <p>When the kernel has no uclamp, the uclamp tests are skipped with the provider's
 * {@code unsupportedReason}; with {@code -Dthreadqos.requireSupported=true} they fail instead
 * (T-5.7), so a regression cannot hide behind a skip.</p>
 */
@EnabledOnOs(OS.LINUX)
@Tag("integration")
class LinuxIntegrationTest {

    private static final boolean REQUIRE_SUPPORTED = Boolean.getBoolean("threadqos.requireSupported");
    private static LinuxQosNative rawNative;

    @BeforeAll
    static void report() {
        QosCapabilities caps = ThreadQos.capabilities();
        System.out.println("[linux] os.arch=" + System.getProperty("os.arch") + " kernel=" + kernelRelease()
                + " requireSupported=" + REQUIRE_SUPPORTED);
        System.out.println("[linux] capabilities=" + caps);
        LinuxSyscalls numbers = LinuxSyscalls.forArchitecture(caps.architecture());
        if (numbers != null) {
            rawNative = new LinuxQosNative(numbers);
        }
    }

    private static String kernelRelease() {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of("/proc/sys/kernel/osrelease")).trim();
        } catch (java.io.IOException e) {
            return "?";
        }
    }

    /** Skips (or fails under requireSupported) when uclamp is unavailable. */
    private static void requireUclamp() {
        QosCapabilities caps = ThreadQos.capabilities();
        if (caps.qosSupported()) {
            return;
        }
        String reason = "uclamp unavailable on this kernel: " + caps.unsupportedReason();
        if (REQUIRE_SUPPORTED) {
            throw new AssertionError("threadqos.requireSupported=true but " + reason);
        }
        System.out.println("[linux] SKIPPED: " + reason);
        abort(reason);
    }

    @Test
    void factorySelectsLinuxProviderForKnownArchitectures() {
        ThreadQosProvider provider = ThreadQosProviderFactory.create();
        QosCapabilities caps = provider.capabilities();
        assertEquals(OperatingSystem.LINUX, caps.operatingSystem());
        if (caps.architecture() == Architecture.OTHER) {
            assertFalse(caps.qosSupported());
            assertNotNull(caps.unsupportedReason());
            System.out.println("[linux] SKIPPED: unsupported architecture " + System.getProperty("os.arch"));
            return;
        }
        assertInstanceOf(LinuxThreadQosProvider.class, provider);
        if (!caps.qosSupported()) {
            assertNotNull(caps.unsupportedReason(), "unsupported must carry a reason");
        }
    }

    @Test
    void syscallSymbolResolvesAndVariadicBindingWorks() {
        assumeTrue(rawNative != null, "unsupported architecture");
        assertTrue(FfmSupport.defaultLookup().find(LinuxQosNative.SYSCALL).isPresent());
        rawNative.link();
        // sched_getattr exists on every kernel >= 3.14: a successful call proves the variadic
        // syscall() binding passes the arguments correctly on this ABI (x86_64 SysV / AAPCS64).
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment attr = arena.allocate(LinuxSchedAttr.LAYOUT);
            LinuxNativeCalls.Result r = rawNative.schedGetAttr(attr, LinuxSchedAttr.SIZE);
            System.out.println("[linux] sched_getattr -> value=" + r.value() + " errno=" + r.errno()
                    + " size=" + LinuxSchedAttr.size(attr) + " policy=" + LinuxSchedAttr.policyName(LinuxSchedAttr.policy(attr))
                    + " nice=" + LinuxSchedAttr.nice(attr) + " flags=0x" + Long.toHexString(LinuxSchedAttr.flags(attr))
                    + " util_min=" + LinuxSchedAttr.utilMin(attr) + " util_max=" + LinuxSchedAttr.utilMax(attr));
            if (r.failed()) {
                assertTrue(r.errno() == LinuxSchedAttr.EPERM || r.errno() == LinuxSchedAttr.ENOSYS,
                        "unexpected errno " + r.errno() + " (" + LinuxSchedAttr.describeErrno(r.errno()) + ")");
                return;
            }
            assertTrue(LinuxSchedAttr.size(attr) >= LinuxSchedAttr.SIZE_VER0);
            assertTrue(LinuxSchedAttr.size(attr) <= LinuxSchedAttr.SIZE);
            assertTrue(LinuxSchedAttr.isFairPolicy(LinuxSchedAttr.policy(attr)), "a plain Java thread is SCHED_OTHER");
            assertEquals(0, LinuxSchedAttr.priority(attr));
        }
    }

    @Test
    void probeIsVisibleInOutput() {
        assumeTrue(rawNative != null, "unsupported architecture");
        QosCapabilities caps = ThreadQos.capabilities();
        System.out.println("[linux] uclamp " + (caps.qosSupported() ? "SUPPORTED" : "UNSUPPORTED: " + caps.unsupportedReason()));
        if (REQUIRE_SUPPORTED) {
            assertTrue(caps.qosSupported(), "threadqos.requireSupported=true but " + caps.unsupportedReason());
        }
    }

    @Test
    void firstSetFlipsToUnsupportedOnKernelWithoutUclampConfig() throws Exception {
        assumeTrue(rawNative != null, "unsupported architecture");
        // Exercises the EOPNOTSUPP transition on kernels >= 5.3 built without CONFIG_UCLAMP_TASK
        // (e.g. Docker Desktop's linuxkit kernel); on uclamp kernels it simply verifies trySet == true.
        AtomicReference<Boolean> result = new AtomicReference<>();
        Thread t = Thread.ofPlatform().start(() -> result.set(ThreadQos.trySet(QosClass.DEFAULT)));
        t.join();
        assertEquals(ThreadQos.isSupported(), result.get(), "trySet must agree with the post-call capability");
        if (!result.get()) {
            System.out.println("[linux] first set() -> unsupported: " + ThreadQos.capabilities().unsupportedReason());
        }
    }

    @ParameterizedTest
    @EnumSource(QosClass.class)
    @Tag("abi")
    void setThenReadBackClamps(QosClass qosClass) throws Exception {
        requireUclamp();
        LinuxUtilClamp expected = LinuxUtilClamp.of(qosClass);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        int[] readBack = new int[6];
        Thread t = Thread.ofPlatform().name("qos-" + qosClass).start(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment before = arena.allocate(LinuxSchedAttr.LAYOUT);
                assertFalse(rawNative.schedGetAttr(before, LinuxSchedAttr.SIZE).failed());
                ThreadQos.set(qosClass);
                MemorySegment after = arena.allocate(LinuxSchedAttr.LAYOUT);
                assertFalse(rawNative.schedGetAttr(after, LinuxSchedAttr.SIZE).failed());
                readBack[0] = LinuxSchedAttr.utilMin(after);
                readBack[1] = LinuxSchedAttr.utilMax(after);
                readBack[2] = LinuxSchedAttr.policy(before);
                readBack[3] = LinuxSchedAttr.policy(after);
                readBack[4] = LinuxSchedAttr.nice(before);
                readBack[5] = LinuxSchedAttr.nice(after);
                // restore library default before the thread exits (spec §51 step 6)
                ThreadQos.set(QosClass.DEFAULT);
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        t.join();
        assertNull(failure.get(), () -> "native error: " + failure.get());
        assertEquals(expected.min(), readBack[0], "sched_util_min read back");
        assertEquals(expected.max(), readBack[1], "sched_util_max read back");
        assertEquals(readBack[2], readBack[3], "sched_policy must not change");
        assertEquals(readBack[4], readBack[5], "sched_nice must not change");
    }

    @Test
    void allClassesInSequenceThenDefault() throws Exception {
        requireUclamp();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread t = Thread.ofPlatform().start(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment attr = arena.allocate(LinuxSchedAttr.LAYOUT);
                for (QosClass q : QosClass.values()) {
                    ThreadQos.set(q);
                    assertFalse(rawNative.schedGetAttr(attr, LinuxSchedAttr.SIZE).failed());
                    assertEquals(LinuxUtilClamp.of(q).min(), LinuxSchedAttr.utilMin(attr), q + " min");
                    assertEquals(LinuxUtilClamp.of(q).max(), LinuxSchedAttr.utilMax(attr), q + " max");
                }
                ThreadQos.set(QosClass.DEFAULT);
                assertFalse(rawNative.schedGetAttr(attr, LinuxSchedAttr.SIZE).failed());
                assertEquals(0, LinuxSchedAttr.utilMin(attr));
                assertEquals(1024, LinuxSchedAttr.utilMax(attr));
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        t.join();
        assertNull(failure.get(), () -> "failure: " + failure.get());
    }

    @Test
    void wrappedThreadStartsWithoutError() throws Exception {
        requireUclamp();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread t = Thread.ofPlatform().start(ThreadQos.wrap(QosClass.UTILITY, () -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment attr = arena.allocate(LinuxSchedAttr.LAYOUT);
                assertFalse(rawNative.schedGetAttr(attr, LinuxSchedAttr.SIZE).failed());
                assertEquals(768, LinuxSchedAttr.utilMax(attr));
            } catch (Throwable e) {
                failure.set(e);
            }
        }));
        t.join();
        assertNull(failure.get(), () -> "failure: " + failure.get());
    }

    @Test
    void realTimeThreadIsRefused() throws Exception {
        requireUclamp();
        // D-04: needs the right to switch to SCHED_FIFO (CAP_SYS_NICE / RLIMIT_RTPRIO); otherwise skip loudly.
        AtomicReference<String> skip = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<ThreadQosException> refused = new AtomicReference<>();
        Thread t = Thread.ofPlatform().name("rt-probe").start(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment attr = arena.allocate(LinuxSchedAttr.LAYOUT);
                assertFalse(rawNative.schedGetAttr(attr, LinuxSchedAttr.SIZE).failed());
                MemorySegment fifo = arena.allocate(LinuxSchedAttr.LAYOUT);
                LinuxSchedAttr.size(fifo, LinuxSchedAttr.SIZE_VER0);
                LinuxSchedAttr.policy(fifo, LinuxSchedAttr.SCHED_FIFO);
                LinuxSchedAttr.priority(fifo, 1);
                LinuxNativeCalls.Result r = rawNative.schedSetAttr(fifo);
                if (r.failed()) {
                    skip.set("cannot switch to SCHED_FIFO (errno " + r.errno() + ", "
                            + LinuxSchedAttr.describeErrno(r.errno()) + "); run as root or with RLIMIT_RTPRIO to enable");
                    return;
                }
                try {
                    ThreadQos.set(QosClass.USER_INTERACTIVE);
                } catch (ThreadQosException e) {
                    refused.set(e);
                } finally {
                    // back to SCHED_OTHER with the original nice
                    MemorySegment other = arena.allocate(LinuxSchedAttr.LAYOUT);
                    LinuxSchedAttr.size(other, LinuxSchedAttr.SIZE_VER0);
                    LinuxSchedAttr.policy(other, LinuxSchedAttr.SCHED_NORMAL);
                    LinuxSchedAttr.nice(other, LinuxSchedAttr.nice(attr));
                    rawNative.schedSetAttr(other);
                }
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        t.join();
        assertNull(failure.get(), () -> "failure: " + failure.get());
        if (skip.get() != null) {
            System.out.println("[linux] SKIPPED RT test: " + skip.get());
            abort(skip.get());
        }
        assertNotNull(refused.get(), "set() on a SCHED_FIFO thread must throw");
        assertNull(refused.get().getNativeErrorCode());
        assertTrue(refused.get().getMessage().contains("SCHED_FIFO"), refused.get().getMessage());
    }

    @Test
    void virtualThreadIsRejectedBeforeAnyNativeCall() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread vt = Thread.ofVirtual().start(() -> {
            try {
                ThreadQos.set(QosClass.BACKGROUND);
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        vt.join();
        assertInstanceOf(IllegalStateException.class, failure.get());
    }
}
