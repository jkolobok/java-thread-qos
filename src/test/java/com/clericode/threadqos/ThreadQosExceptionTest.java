package com.clericode.threadqos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadQosExceptionTest {

    @ParameterizedTest
    @CsvSource({
            "MACOS,   macOS,   pthread_set_qos_class_self_np, 1",
            "WINDOWS, Windows, SetThreadInformation,          87",
            "LINUX,   Linux,   sched_setattr,                 95",
    })
    void nativeFailureMessageFormat(OperatingSystem os, String displayName, String function, int code) {
        ThreadQosException e = ThreadQosException.nativeFailure(os, QosClass.USER_INTERACTIVE, function, code);
        assertEquals("Failed to set thread QoS: os=" + displayName + ", qos=USER_INTERACTIVE, nativeFunction="
                + function + ", errorCode=" + code, e.getMessage());
        assertEquals(os, e.getOperatingSystem());
        assertEquals(function, e.getNativeOperation());
        assertEquals(code, e.getNativeErrorCode());
        assertNull(e.getCause());
    }

    @Test
    void nativeFailureWithDetail() {
        ThreadQosException e = ThreadQosException.nativeFailure(OperatingSystem.LINUX, QosClass.BACKGROUND,
                "sched_setattr", 95, "EOPNOTSUPP: kernel built without CONFIG_UCLAMP_TASK");
        assertEquals("Failed to set thread QoS: os=Linux, qos=BACKGROUND, nativeFunction=sched_setattr, errorCode=95"
                + " (EOPNOTSUPP: kernel built without CONFIG_UCLAMP_TASK)", e.getMessage());
    }

    @Test
    void policyFailureHasNoErrorCode() {
        ThreadQosException e = ThreadQosException.policyFailure(OperatingSystem.LINUX, QosClass.DEFAULT,
                "sched_setattr", "thread uses SCHED_FIFO");
        assertNull(e.getNativeErrorCode());
        assertEquals("sched_setattr", e.getNativeOperation());
        assertTrue(e.getMessage().contains("reason=thread uses SCHED_FIFO"), e.getMessage());
    }

    @Test
    void linkageFailureKeepsCauseAndAppendsHint() {
        NoSuchElementException cause = new NoSuchElementException("Symbol not found: SetThreadInformation");
        ThreadQosException e = ThreadQosException.linkageFailure(OperatingSystem.WINDOWS, "SetThreadInformation",
                cause, "Run the JVM with --enable-native-access=com.clericode.threadqos");
        assertSame(cause, e.getCause());
        assertEquals(OperatingSystem.WINDOWS, e.getOperatingSystem());
        assertEquals("SetThreadInformation", e.getNativeOperation());
        assertNull(e.getNativeErrorCode());
        assertTrue(e.getMessage().startsWith(
                "Failed to link native function for thread QoS: os=Windows, nativeFunction=SetThreadInformation, "
                        + "cause=java.util.NoSuchElementException: Symbol not found: SetThreadInformation"), e.getMessage());
        assertTrue(e.getMessage().endsWith("--enable-native-access=com.clericode.threadqos"), e.getMessage());
    }

    @Test
    void linkageFailureWithoutHint() {
        IllegalCallerException cause = new IllegalCallerException();
        ThreadQosException e = ThreadQosException.linkageFailure(OperatingSystem.MACOS, "pthread_set_qos_class_self_np", cause, null);
        assertSame(cause, e.getCause());
        assertEquals("Failed to link native function for thread QoS: os=macOS, "
                + "nativeFunction=pthread_set_qos_class_self_np, cause=java.lang.IllegalCallerException", e.getMessage());
    }
}
