package com.clericode.threadqos.internal.windows;

import com.clericode.threadqos.QosClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsQosStateTest {

    @ParameterizedTest
    @CsvSource({
            // qos,            controlMask, stateMask, priority
            "USER_INTERACTIVE, 1,           0,         2",
            "USER_INITIATED,   1,           0,         1",
            "DEFAULT,          0,           0,         0",
            "UTILITY,          0,           0,        -1",
            "BACKGROUND,       1,           1,        -2",
    })
    void mapping(QosClass qos, int controlMask, int stateMask, int priority) {
        WindowsQosState s = WindowsQosState.of(qos);
        assertEquals(controlMask, s.controlMask());
        assertEquals(stateMask, s.stateMask());
        assertEquals(priority, s.priority());
    }

    @Test
    void neverUsesForbiddenPriorities() {
        for (QosClass q : QosClass.values()) {
            int p = WindowsQosState.of(q).priority();
            assertNotEquals(WindowsQosState.THREAD_PRIORITY_TIME_CRITICAL, p);
            assertNotEquals(WindowsQosState.THREAD_PRIORITY_IDLE, p);
            assertTrue(p >= WindowsQosState.THREAD_PRIORITY_LOWEST && p <= WindowsQosState.THREAD_PRIORITY_HIGHEST);
        }
    }

    @Test
    void sdkConstants() {
        assertEquals(-2, WindowsQosState.THREAD_PRIORITY_LOWEST);
        assertEquals(-1, WindowsQosState.THREAD_PRIORITY_BELOW_NORMAL);
        assertEquals(0, WindowsQosState.THREAD_PRIORITY_NORMAL);
        assertEquals(1, WindowsQosState.THREAD_PRIORITY_ABOVE_NORMAL);
        assertEquals(2, WindowsQosState.THREAD_PRIORITY_HIGHEST);
        assertEquals(15, WindowsQosState.THREAD_PRIORITY_TIME_CRITICAL);
        assertEquals(-15, WindowsQosState.THREAD_PRIORITY_IDLE);
        assertEquals(0x7fffffff, WindowsQosState.THREAD_PRIORITY_ERROR_RETURN);
    }
}
