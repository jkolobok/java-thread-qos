package com.clericode.threadqos.internal;

import com.clericode.threadqos.Architecture;
import com.clericode.threadqos.OperatingSystem;
import com.clericode.threadqos.QosCapabilities;
import com.clericode.threadqos.internal.noop.NoOpThreadQosProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadQosProviderFactoryTest {

    @ParameterizedTest
    @CsvSource({
            "Mac OS X,   MACOS",
            "mac os x,   MACOS",
            "Darwin,     MACOS",
            "Windows 10, WINDOWS",
            "Windows 11, WINDOWS",
            "Windows Server 2022, WINDOWS",
            "Linux,      LINUX",
            "linux,      LINUX",
            "FreeBSD,    OTHER",
            "SunOS,      OTHER",
            "AIX,        OTHER",
            "OpenBSD,    OTHER",
            "'',         OTHER",
    })
    void detectsOperatingSystem(String osName, OperatingSystem expected) {
        assertEquals(expected, ThreadQosProviderFactory.detectOperatingSystem(osName));
    }

    @ParameterizedTest
    @CsvSource({
            "amd64,   X86_64",
            "x86_64,  X86_64",
            "x64,     X86_64",
            "aarch64, AARCH64",
            "arm64,   AARCH64",
            "x86,     OTHER",
            "i386,    OTHER",
            "arm,     OTHER",
            "riscv64, OTHER",
            "ppc64le, OTHER",
            "s390x,   OTHER",
            "'',      OTHER",
    })
    void detectsArchitecture(String osArch, Architecture expected) {
        assertEquals(expected, ThreadQosProviderFactory.detectArchitecture(osArch));
    }

    @ParameterizedTest
    @CsvSource({"FreeBSD, amd64", "SunOS, sparcv9", "AIX, ppc64", "Unknown, unknown"})
    void unknownOsGetsNoOpProviderWithReason(String osName, String osArch) {
        ThreadQosProvider provider = ThreadQosProviderFactory.create(osName, osArch);
        assertInstanceOf(NoOpThreadQosProvider.class, provider);
        assertFalse(provider.isSupported());
        QosCapabilities caps = provider.capabilities();
        assertEquals(OperatingSystem.OTHER, caps.operatingSystem());
        assertNotNull(caps.unsupportedReason());
        assertTrue(caps.unsupportedReason().contains(osName), caps.unsupportedReason());
        assertFalse(provider.set(com.clericode.threadqos.QosClass.USER_INTERACTIVE));
    }

    @Test
    void capabilitiesCarryDetectedOsAndArchitecture() {
        assertEquals(OperatingSystem.MACOS, ThreadQosProviderFactory.create("Mac OS X", "aarch64").capabilities().operatingSystem());
        assertEquals(Architecture.AARCH64, ThreadQosProviderFactory.create("Mac OS X", "aarch64").capabilities().architecture());
        assertEquals(OperatingSystem.WINDOWS, ThreadQosProviderFactory.create("Windows 11", "amd64").capabilities().operatingSystem());
        assertEquals(Architecture.X86_64, ThreadQosProviderFactory.create("Windows 11", "amd64").capabilities().architecture());
        assertEquals(OperatingSystem.LINUX, ThreadQosProviderFactory.create("Linux", "riscv64").capabilities().operatingSystem());
        assertEquals(Architecture.OTHER, ThreadQosProviderFactory.create("Linux", "riscv64").capabilities().architecture());
    }

    @Test
    void creatingAProviderForAnotherOsDoesNotTouchNativeCode() {
        // Constructing providers for every OS on any host must be side-effect free: no symbol lookup,
        // no library load, no syscall. Otherwise the factory could not be unit-tested cross-platform.
        for (String os : new String[] {"Mac OS X", "Windows 11", "Linux", "FreeBSD"}) {
            for (String arch : new String[] {"amd64", "aarch64", "riscv64"}) {
                ThreadQosProvider provider = ThreadQosProviderFactory.create(os, arch);
                assertNotNull(provider.capabilities().operatingSystem());
            }
        }
    }

    @Test
    void nullArgumentsRejected() {
        assertThrows(NullPointerException.class, () -> ThreadQosProviderFactory.create(null, "amd64"));
        assertThrows(NullPointerException.class, () -> ThreadQosProviderFactory.create("Linux", null));
    }

    @Test
    void defaultCreateMatchesSystemProperties() {
        ThreadQosProvider provider = ThreadQosProviderFactory.create();
        assertEquals(ThreadQosProviderFactory.detectOperatingSystem(System.getProperty("os.name")),
                provider.capabilities().operatingSystem());
        assertEquals(ThreadQosProviderFactory.detectArchitecture(System.getProperty("os.arch")),
                provider.capabilities().architecture());
    }
}
