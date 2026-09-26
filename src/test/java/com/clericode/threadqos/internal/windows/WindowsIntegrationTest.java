package com.clericode.threadqos.internal.windows;

import com.clericode.threadqos.OperatingSystem;
import com.clericode.threadqos.QosCapabilities;
import com.clericode.threadqos.QosClass;
import com.clericode.threadqos.ThreadQos;
import com.clericode.threadqos.internal.FfmSupport;
import com.clericode.threadqos.internal.ThreadQosProvider;
import com.clericode.threadqos.internal.ThreadQosProviderFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real Win32 calls (spec §50, §53). The read-back of {@code GetThreadInformation} and
 * {@code GetThreadPriority} doubles as the ABI-drift check for the constants (spec §76).
 */
@EnabledOnOs(OS.WINDOWS)
@Tag("integration")
class WindowsIntegrationTest {

    @Test
    void factorySelectsWindowsProviderAndProbeSucceeds() {
        ThreadQosProvider provider = ThreadQosProviderFactory.create();
        assertInstanceOf(WindowsThreadQosProvider.class, provider);
        QosCapabilities caps = provider.capabilities();
        System.out.println("[windows] os.name=" + System.getProperty("os.name") + " os.version="
                + System.getProperty("os.version") + " capabilities=" + caps);
        assertEquals(OperatingSystem.WINDOWS, caps.operatingSystem());
        assertTrue(caps.qosSupported());
        assertTrue(caps.priorityAdjustmentSupported());
        assertTrue(caps.nativeReadbackSupported());
        if (Boolean.getBoolean("threadqos.requireSupported")) {
            assertTrue(caps.powerThrottlingSupported(), "ThreadPowerThrottling expected on this runner: " + caps);
        }
        assertTrue(ThreadQos.isSupported());
    }

    @Test
    void requiredSymbolsResolveFromKernel32() {
        SymbolLookup kernel32 = FfmSupport.libraryLookup(OperatingSystem.WINDOWS, WindowsQosNative.KERNEL32);
        for (String symbol : new String[] {WindowsQosNative.GET_CURRENT_THREAD, WindowsQosNative.SET_THREAD_INFORMATION,
                WindowsQosNative.GET_THREAD_INFORMATION, WindowsQosNative.SET_THREAD_PRIORITY, WindowsQosNative.GET_THREAD_PRIORITY}) {
            assertTrue(kernel32.find(symbol).isPresent(), symbol);
        }
        WindowsQosNative.link();
        assertNotEquals(MemorySegment.NULL, WindowsQosNative.currentThread());
    }

    @ParameterizedTest
    @EnumSource(QosClass.class)
    @Tag("abi")
    void setThenReadBack(QosClass qosClass) throws Exception {
        WindowsQosState expected = WindowsQosState.of(qosClass);
        boolean throttling = ThreadQos.capabilities().powerThrottlingSupported();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Integer> priority = new AtomicReference<>();
        AtomicReference<WindowsNativeCalls.PowerThrottlingRead> read = new AtomicReference<>();
        Thread t = Thread.ofPlatform().name("qos-" + qosClass).start(() -> {
            try {
                ThreadQos.set(qosClass);
                priority.set(WindowsQosNative.INSTANCE.getPriority());
                if (throttling) {
                    read.set(WindowsQosNative.INSTANCE.getPowerThrottling());
                }
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        t.join();
        assertNull(failure.get(), () -> "native error: " + failure.get());
        assertEquals(expected.priority(), priority.get(), "GetThreadPriority must return the mapped priority");
        if (throttling) {
            assertEquals(0, read.get().error());
            assertEquals(expected.controlMask(), read.get().controlMask(), "ControlMask read back");
            assertEquals(expected.stateMask(), read.get().stateMask(), "StateMask read back");
        } else {
            System.out.println("[windows] power throttling unsupported on this build, priority-only check for " + qosClass);
        }
    }

    @Test
    void wrappedThreadStartsWithoutError() throws Exception {
        AtomicReference<Integer> prio = new AtomicReference<>();
        Thread t = Thread.ofPlatform().start(ThreadQos.wrap(QosClass.UTILITY, () -> prio.set(WindowsQosNative.INSTANCE.getPriority())));
        t.join();
        assertEquals(WindowsQosState.THREAD_PRIORITY_BELOW_NORMAL, prio.get());
    }

    @Test
    void invalidHandleYieldsCapturedLastError() {
        // T-4.5: GetLastError must come from the captured call state of the failing call.
        WindowsQosNative.link();
        int error = WindowsQosNative.setPriority(MemorySegment.NULL, WindowsQosState.THREAD_PRIORITY_NORMAL);
        assertEquals(WindowsQosNative.ERROR_INVALID_HANDLE, error);
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
