package com.clericode.threadqos.internal.linux;

import com.clericode.threadqos.Architecture;
import com.clericode.threadqos.OperatingSystem;
import com.clericode.threadqos.QosCapabilities;
import com.clericode.threadqos.QosClass;
import com.clericode.threadqos.ThreadQosException;
import com.clericode.threadqos.internal.ThreadQosProvider;
import com.clericode.threadqos.internal.noop.NoOpThreadQosProvider;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provider logic over a fake kernel; runs on every OS (T-5.4, T-5.5).
 */
class LinuxThreadQosProviderTest {

    /** A fake kernel holding one thread's sched_attr. */
    static final class FakeKernel implements LinuxNativeCalls {
        int getattrErrno;          // if != 0, sched_getattr fails
        int kernelAttrSize = 56;   // what the kernel reports as struct size
        int setattrErrno;          // if != 0, sched_setattr fails
        ThreadQosException linkFailure;
        int getCalls;
        int setCalls;
        // "kernel" state of the thread
        int policy = LinuxSchedAttr.SCHED_NORMAL;
        long flags = 0;
        int nice = 3;
        int priority = 0;
        int utilMin = 0;
        int utilMax = 1024;
        long lastSetFlags;
        int lastSetSize;

        @Override
        public Result schedGetAttr(MemorySegment attr, int size) {
            getCalls++;
            if (linkFailure != null) {
                throw linkFailure;
            }
            if (getattrErrno != 0) {
                return new Result(-1, getattrErrno);
            }
            LinuxSchedAttr.size(attr, Math.min(size, kernelAttrSize));
            LinuxSchedAttr.policy(attr, policy);
            LinuxSchedAttr.flags(attr, flags);
            LinuxSchedAttr.nice(attr, nice);
            LinuxSchedAttr.priority(attr, priority);
            if (kernelAttrSize >= 56) {
                LinuxSchedAttr.utilMin(attr, utilMin);
                LinuxSchedAttr.utilMax(attr, utilMax);
            }
            return new Result(0, 0);
        }

        @Override
        public Result schedSetAttr(MemorySegment attr) {
            setCalls++;
            lastSetFlags = LinuxSchedAttr.flags(attr);
            lastSetSize = LinuxSchedAttr.size(attr);
            if (setattrErrno != 0) {
                return new Result(-1, setattrErrno);
            }
            // Emulate KEEP_POLICY | KEEP_PARAMS: only clamps change.
            assertEquals(policy, LinuxSchedAttr.policy(attr), "policy must be copied unchanged");
            assertEquals(nice, LinuxSchedAttr.nice(attr), "nice must be copied unchanged");
            assertEquals(priority, LinuxSchedAttr.priority(attr), "priority must be copied unchanged");
            utilMin = LinuxSchedAttr.utilMin(attr);
            utilMax = LinuxSchedAttr.utilMax(attr);
            return new Result(0, 0);
        }
    }

    private static LinuxThreadQosProvider provider(FakeKernel k) {
        return new LinuxThreadQosProvider(Architecture.X86_64, k);
    }

    @Test
    void probeSuccess() {
        FakeKernel k = new FakeKernel();
        LinuxThreadQosProvider p = provider(k);
        assertEquals(0, k.getCalls, "constructor must not probe");
        assertTrue(p.isSupported());
        QosCapabilities caps = p.capabilities();
        assertEquals(OperatingSystem.LINUX, caps.operatingSystem());
        assertTrue(caps.qosSupported());
        assertTrue(caps.nativeReadbackSupported());
        assertTrue(caps.performanceHintSupported());
        assertFalse(caps.powerThrottlingSupported());
        assertFalse(caps.priorityAdjustmentSupported());
        assertNull(caps.unsupportedReason());
        p.isSupported();
        assertEquals(1, k.getCalls, "probe must be cached");
    }

    @Test
    void probeEnosys() {
        FakeKernel k = new FakeKernel();
        k.getattrErrno = LinuxSchedAttr.ENOSYS;
        LinuxThreadQosProvider p = provider(k);
        assertFalse(p.isSupported());
        assertTrue(p.capabilities().unsupportedReason().contains("ENOSYS"), p.capabilities().unsupportedReason());
        assertFalse(p.set(QosClass.USER_INTERACTIVE), "unsupported must be a silent no-op");
        assertEquals(0, k.setCalls);
    }

    @Test
    void probeEperm() {
        FakeKernel k = new FakeKernel();
        k.getattrErrno = LinuxSchedAttr.EPERM;
        LinuxThreadQosProvider p = provider(k);
        assertFalse(p.isSupported());
        assertTrue(p.capabilities().unsupportedReason().contains("seccomp"), p.capabilities().unsupportedReason());
    }

    @Test
    void probeOtherErrno() {
        FakeKernel k = new FakeKernel();
        k.getattrErrno = LinuxSchedAttr.EINVAL;
        LinuxThreadQosProvider p = provider(k);
        assertFalse(p.isSupported());
        assertTrue(p.capabilities().unsupportedReason().contains("errno 22"), p.capabilities().unsupportedReason());
    }

    @Test
    void probeOldKernelSize48() {
        FakeKernel k = new FakeKernel();
        k.kernelAttrSize = 48;
        LinuxThreadQosProvider p = provider(k);
        assertFalse(p.isSupported());
        assertTrue(p.capabilities().unsupportedReason().contains("older than 5.3"), p.capabilities().unsupportedReason());
        assertFalse(p.set(QosClass.DEFAULT));
    }

    @Test
    void setWritesClampsAndKeepFlagsOnly() {
        FakeKernel k = new FakeKernel();
        k.flags = LinuxSchedAttr.SCHED_FLAG_RESET_ON_FORK;
        k.nice = -7;
        k.policy = LinuxSchedAttr.SCHED_BATCH;
        LinuxThreadQosProvider p = provider(k);
        assertTrue(p.set(QosClass.USER_INITIATED));
        assertEquals(768, k.utilMin);
        assertEquals(1024, k.utilMax);
        assertEquals(56, k.lastSetSize);
        long expectedFlags = LinuxSchedAttr.SCHED_FLAG_RESET_ON_FORK | LinuxSchedAttr.SCHED_FLAG_KEEP_POLICY
                | LinuxSchedAttr.SCHED_FLAG_KEEP_PARAMS | LinuxSchedAttr.SCHED_FLAG_UTIL_CLAMP_MIN
                | LinuxSchedAttr.SCHED_FLAG_UTIL_CLAMP_MAX;
        assertEquals(expectedFlags, k.lastSetFlags, "existing flags preserved, keep+clamp flags added");
        assertEquals(LinuxSchedAttr.SCHED_BATCH, k.policy);
        assertEquals(-7, k.nice);

        assertTrue(p.set(QosClass.BACKGROUND));
        assertEquals(0, k.utilMin);
        assertEquals(256, k.utilMax);
        assertEquals(1 + 2, k.getCalls, "probe + one getattr per set");
        assertEquals(2, k.setCalls);
    }

    @Test
    void rtPolicyIsRefusedWithoutTouchingTheKernel() {
        for (int policy : new int[] {LinuxSchedAttr.SCHED_FIFO, LinuxSchedAttr.SCHED_RR, LinuxSchedAttr.SCHED_DEADLINE, LinuxSchedAttr.SCHED_EXT, 42}) {
            FakeKernel k = new FakeKernel();
            k.policy = policy;
            k.priority = policy == LinuxSchedAttr.SCHED_FIFO || policy == LinuxSchedAttr.SCHED_RR ? 10 : 0;
            LinuxThreadQosProvider p = provider(k);
            ThreadQosException e = assertThrows(ThreadQosException.class, () -> p.set(QosClass.USER_INTERACTIVE));
            assertEquals(OperatingSystem.LINUX, e.getOperatingSystem());
            assertEquals("sched_setattr", e.getNativeOperation());
            assertNull(e.getNativeErrorCode());
            assertTrue(e.getMessage().contains(LinuxSchedAttr.policyName(policy)), e.getMessage());
            assertEquals(0, k.setCalls, "sched_setattr must not be attempted for " + policy);
            assertTrue(p.isSupported(), "a refused thread does not make the platform unsupported");
        }
    }

    @Test
    void eopnotsuppOnFirstSetFlipsToUnsupportedSilently() {
        FakeKernel k = new FakeKernel();
        k.setattrErrno = LinuxSchedAttr.EOPNOTSUPP;
        LinuxThreadQosProvider p = provider(k);
        assertTrue(p.isSupported(), "candidate after probe");
        assertFalse(p.set(QosClass.USER_INTERACTIVE), "must not throw");
        assertFalse(p.isSupported());
        assertTrue(p.capabilities().unsupportedReason().contains("CONFIG_UCLAMP_TASK"), p.capabilities().unsupportedReason());
        assertFalse(p.set(QosClass.USER_INTERACTIVE));
        assertEquals(1, k.setCalls, "no further syscalls once unsupported");
    }

    @Test
    void otherSetattrErrnoIsReported() {
        FakeKernel k = new FakeKernel();
        k.setattrErrno = LinuxSchedAttr.EPERM;
        LinuxThreadQosProvider p = provider(k);
        ThreadQosException e = assertThrows(ThreadQosException.class, () -> p.set(QosClass.UTILITY));
        assertEquals("sched_setattr", e.getNativeOperation());
        assertEquals(1, e.getNativeErrorCode());
        assertEquals("Failed to set thread QoS: os=Linux, qos=UTILITY, nativeFunction=sched_setattr, errorCode=1 "
                + "(EPERM: operation not permitted (seccomp/security policy, or insufficient privileges))", e.getMessage());
        assertTrue(p.isSupported(), "a failed operation keeps the platform supported (spec §21)");
    }

    @Test
    void getattrFailureDuringSetIsReported() {
        FakeKernel k = new FakeKernel();
        LinuxThreadQosProvider p = provider(k);
        assertTrue(p.isSupported());
        k.getattrErrno = LinuxSchedAttr.ESRCH;
        ThreadQosException e = assertThrows(ThreadQosException.class, () -> p.set(QosClass.DEFAULT));
        assertEquals("sched_getattr", e.getNativeOperation());
        assertEquals(3, e.getNativeErrorCode());
    }

    @Test
    void linkageFailureIsReportedNotHidden() {
        FakeKernel k = new FakeKernel();
        k.linkFailure = ThreadQosException.linkageFailure(OperatingSystem.LINUX, "syscall",
                new NoSuchElementException("Symbol not found"), "--enable-native-access=x");
        LinuxThreadQosProvider p = provider(k);
        assertFalse(p.isSupported());
        assertTrue(p.capabilities().unsupportedReason().contains("--enable-native-access=x"));
        ThreadQosException e = assertThrows(ThreadQosException.class, () -> p.set(QosClass.DEFAULT));
        assertSame(k.linkFailure, e);
    }

    @Test
    void unknownArchitectureGetsNoOpWithReason() {
        ThreadQosProvider p = LinuxThreadQosProvider.create(Architecture.OTHER);
        assertInstanceOf(NoOpThreadQosProvider.class, p);
        assertFalse(p.isSupported());
        assertNotNull(p.capabilities().unsupportedReason());
        assertTrue(p.capabilities().unsupportedReason().contains("x86_64, aarch64"));
        assertEquals(OperatingSystem.LINUX, p.capabilities().operatingSystem());
    }

    @Test
    void knownArchitecturesGetRealProviderWithoutNativeCalls() {
        assertInstanceOf(LinuxThreadQosProvider.class, LinuxThreadQosProvider.create(Architecture.X86_64));
        assertInstanceOf(LinuxThreadQosProvider.class, LinuxThreadQosProvider.create(Architecture.AARCH64));
    }
}
