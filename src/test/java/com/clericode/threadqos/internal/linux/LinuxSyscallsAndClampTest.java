package com.clericode.threadqos.internal.linux;

import com.clericode.threadqos.Architecture;
import com.clericode.threadqos.QosClass;
import com.clericode.threadqos.internal.ThreadQosProviderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinuxSyscallsAndClampTest {

    @ParameterizedTest
    @CsvSource({
            "amd64,   314, 315",
            "x86_64,  314, 315",
            "aarch64, 274, 275",
            "arm64,   274, 275",
    })
    void syscallNumbersByOsArch(String osArch, long setattr, long getattr) {
        Architecture arch = ThreadQosProviderFactory.detectArchitecture(osArch);
        LinuxSyscalls n = LinuxSyscalls.forArchitecture(arch);
        assertEquals(setattr, n.setattr());
        assertEquals(getattr, n.getattr());
    }

    @Test
    void unknownArchitectureHasNoNumbers() {
        assertNull(LinuxSyscalls.forArchitecture(Architecture.OTHER));
        for (String arch : new String[] {"riscv64", "ppc64le", "s390x", "arm", "x86", "loongarch64"}) {
            assertNull(LinuxSyscalls.forArchitecture(ThreadQosProviderFactory.detectArchitecture(arch)), arch);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "USER_INTERACTIVE, 1024, 1024",
            "USER_INITIATED,   768,  1024",
            "DEFAULT,          0,    1024",
            "UTILITY,          0,    768",
            "BACKGROUND,       0,    256",
    })
    void uclampMapping(QosClass qos, int min, int max) {
        LinuxUtilClamp c = LinuxUtilClamp.of(qos);
        assertEquals(min, c.min());
        assertEquals(max, c.max());
        assertTrue(c.min() <= c.max());
    }

    @Test
    void clampRangeIsValidated() {
        assertThrows(IllegalArgumentException.class, () -> new LinuxUtilClamp(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> new LinuxUtilClamp(0, 1025));
        assertThrows(IllegalArgumentException.class, () -> new LinuxUtilClamp(800, 700));
    }
}
