package com.clericode.threadqos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QosCapabilitiesTest {

    @Test
    void unsupportedHasAllFlagsFalseAndReason() {
        QosCapabilities caps = QosCapabilities.unsupported(OperatingSystem.OTHER, Architecture.OTHER, "FreeBSD is not supported");
        assertEquals(OperatingSystem.OTHER, caps.operatingSystem());
        assertEquals(Architecture.OTHER, caps.architecture());
        assertFalse(caps.qosSupported());
        assertFalse(caps.nativeReadbackSupported());
        assertFalse(caps.performanceHintSupported());
        assertFalse(caps.powerThrottlingSupported());
        assertFalse(caps.priorityAdjustmentSupported());
        assertEquals("FreeBSD is not supported", caps.unsupportedReason());
    }

    @Test
    void supportedHasNoReason() {
        QosCapabilities caps = QosCapabilities.supported(OperatingSystem.MACOS, Architecture.AARCH64, true, true, false, false);
        assertTrue(caps.qosSupported());
        assertTrue(caps.nativeReadbackSupported());
        assertTrue(caps.performanceHintSupported());
        assertFalse(caps.powerThrottlingSupported());
        assertFalse(caps.priorityAdjustmentSupported());
        assertNull(caps.unsupportedReason());
    }

    @Test
    void toStringContainsEveryComponent() {
        QosCapabilities caps = QosCapabilities.unsupported(OperatingSystem.LINUX, Architecture.X86_64, "kernel too old");
        String s = caps.toString();
        assertTrue(s.contains("operatingSystem=LINUX"), s);
        assertTrue(s.contains("architecture=X86_64"), s);
        assertTrue(s.contains("qosSupported=false"), s);
        assertTrue(s.contains("nativeReadbackSupported=false"), s);
        assertTrue(s.contains("performanceHintSupported=false"), s);
        assertTrue(s.contains("powerThrottlingSupported=false"), s);
        assertTrue(s.contains("priorityAdjustmentSupported=false"), s);
        assertTrue(s.contains("unsupportedReason=kernel too old"), s);
    }

    @Test
    void nullComponentsAreRejected() {
        assertThrows(NullPointerException.class,
                () -> new QosCapabilities(null, Architecture.OTHER, false, false, false, false, false, null));
        assertThrows(NullPointerException.class,
                () -> new QosCapabilities(OperatingSystem.OTHER, null, false, false, false, false, false, null));
        assertThrows(NullPointerException.class,
                () -> QosCapabilities.unsupported(OperatingSystem.OTHER, Architecture.OTHER, null));
    }
}
