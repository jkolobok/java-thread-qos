package com.clericode.threadqos.internal.windows;

import com.clericode.threadqos.Architecture;
import com.clericode.threadqos.OperatingSystem;
import com.clericode.threadqos.QosCapabilities;
import com.clericode.threadqos.QosClass;
import com.clericode.threadqos.ThreadQosException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provider logic over a fake native layer; runs on every OS (T-4.3, T-4.4, T-4.5).
 */
class WindowsThreadQosProviderTest {

    /** Scripted fake of the Win32 layer. */
    static final class FakeCalls implements WindowsNativeCalls {
        int probeError;
        int setThrottlingError;
        int setPriorityError;
        ThreadQosException linkFailure;
        int probes;
        int controlMask;
        int stateMask;
        int priority;
        final List<String> log = new ArrayList<>();

        @Override
        public int setPowerThrottling(int controlMask, int stateMask) {
            log.add("SetThreadInformation(" + controlMask + "," + stateMask + ")");
            if (setThrottlingError != 0) {
                return setThrottlingError;
            }
            this.controlMask = controlMask;
            this.stateMask = stateMask;
            return 0;
        }

        @Override
        public PowerThrottlingRead getPowerThrottling() {
            probes++;
            if (linkFailure != null) {
                throw linkFailure;
            }
            return probeError != 0 ? new PowerThrottlingRead(probeError, 0, 0)
                    : new PowerThrottlingRead(0, controlMask, stateMask);
        }

        @Override
        public int setPriority(int priority) {
            log.add("SetThreadPriority(" + priority + ")");
            if (setPriorityError != 0) {
                return setPriorityError;
            }
            this.priority = priority;
            return 0;
        }

        @Override
        public int getPriority() {
            return priority;
        }
    }

    @Test
    void probeSuccessMeansFullSupport() {
        FakeCalls fake = new FakeCalls();
        WindowsThreadQosProvider p = new WindowsThreadQosProvider(Architecture.X86_64, fake);
        assertEquals(0, fake.probes, "constructor must not probe");
        assertTrue(p.isSupported());
        QosCapabilities caps = p.capabilities();
        assertEquals(OperatingSystem.WINDOWS, caps.operatingSystem());
        assertEquals(Architecture.X86_64, caps.architecture());
        assertTrue(caps.qosSupported());
        assertTrue(caps.powerThrottlingSupported());
        assertTrue(caps.priorityAdjustmentSupported());
        assertTrue(caps.performanceHintSupported());
        assertTrue(caps.nativeReadbackSupported());
        assertNull(caps.unsupportedReason());
        p.capabilities();
        p.isSupported();
        assertEquals(1, fake.probes, "probe result must be cached");
    }

    @ParameterizedTest
    @ValueSource(ints = {WindowsQosNative.ERROR_INVALID_PARAMETER, WindowsQosNative.ERROR_NOT_SUPPORTED, 1234})
    void probeFailureDisablesThrottlingOnly(int error) {
        FakeCalls fake = new FakeCalls();
        fake.probeError = error;
        WindowsThreadQosProvider p = new WindowsThreadQosProvider(Architecture.X86_64, fake);
        QosCapabilities caps = p.capabilities();
        assertTrue(caps.qosSupported(), "priority half keeps Windows supported");
        assertTrue(p.isSupported());
        assertFalse(caps.powerThrottlingSupported());
        assertFalse(caps.performanceHintSupported());
        assertTrue(caps.priorityAdjustmentSupported());
        assertNotNull(caps.unsupportedReason());
        assertTrue(caps.unsupportedReason().contains("error " + error), caps.unsupportedReason());

        assertTrue(p.set(QosClass.BACKGROUND));
        assertEquals(List.of("SetThreadPriority(-2)"), fake.log, "no SetThreadInformation without throttling support");
    }

    @Test
    void setAppliesThrottlingThenPriority() {
        FakeCalls fake = new FakeCalls();
        WindowsThreadQosProvider p = new WindowsThreadQosProvider(Architecture.AARCH64, fake);
        assertTrue(p.set(QosClass.USER_INTERACTIVE));
        assertEquals(List.of("SetThreadInformation(1,0)", "SetThreadPriority(2)"), fake.log);
        assertEquals(1, fake.controlMask);
        assertEquals(0, fake.stateMask);
        assertEquals(2, fake.priority);

        fake.log.clear();
        assertTrue(p.set(QosClass.BACKGROUND));
        assertEquals(List.of("SetThreadInformation(1,1)", "SetThreadPriority(-2)"), fake.log);
    }

    @Test
    void throttlingErrorIsReportedWithCode() {
        FakeCalls fake = new FakeCalls();
        fake.setThrottlingError = WindowsQosNative.ERROR_ACCESS_DENIED;
        WindowsThreadQosProvider p = new WindowsThreadQosProvider(Architecture.X86_64, fake);
        ThreadQosException e = assertThrows(ThreadQosException.class, () -> p.set(QosClass.USER_INITIATED));
        assertEquals(OperatingSystem.WINDOWS, e.getOperatingSystem());
        assertEquals("SetThreadInformation", e.getNativeOperation());
        assertEquals(5, e.getNativeErrorCode());
        assertEquals("Failed to set thread QoS: os=Windows, qos=USER_INITIATED, nativeFunction=SetThreadInformation, "
                + "errorCode=5 (ERROR_ACCESS_DENIED)", e.getMessage());
        assertEquals(List.of("SetThreadInformation(1,0)"), fake.log, "priority must not be applied after a failure");
    }

    @Test
    void priorityErrorIsReportedWithCode() {
        FakeCalls fake = new FakeCalls();
        fake.setPriorityError = WindowsQosNative.ERROR_INVALID_HANDLE;
        WindowsThreadQosProvider p = new WindowsThreadQosProvider(Architecture.X86_64, fake);
        ThreadQosException e = assertThrows(ThreadQosException.class, () -> p.set(QosClass.DEFAULT));
        assertEquals("SetThreadPriority", e.getNativeOperation());
        assertEquals(6, e.getNativeErrorCode());
        assertTrue(e.getMessage().contains("ERROR_INVALID_HANDLE"), e.getMessage());
    }

    @Test
    void linkageFailureIsReportedNotHidden() {
        FakeCalls fake = new FakeCalls();
        fake.linkFailure = ThreadQosException.linkageFailure(OperatingSystem.WINDOWS, "SetThreadInformation",
                new NoSuchElementException("Symbol not found"), "--enable-native-access=x");
        WindowsThreadQosProvider p = new WindowsThreadQosProvider(Architecture.X86_64, fake);
        assertFalse(p.isSupported());
        QosCapabilities caps = p.capabilities();
        assertFalse(caps.qosSupported());
        assertTrue(caps.unsupportedReason().contains("--enable-native-access=x"), caps.unsupportedReason());
        ThreadQosException e = assertThrows(ThreadQosException.class, () -> p.set(QosClass.DEFAULT));
        assertSame(fake.linkFailure, e);
        assertEquals(1, fake.probes);
    }
}
