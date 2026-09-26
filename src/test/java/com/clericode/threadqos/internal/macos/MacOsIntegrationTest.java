package com.clericode.threadqos.internal.macos;

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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real native calls on macOS (spec §49, §53). Tagged {@code abi}: the round-trip through
 * {@code qos_class_self()} verifies the {@code QOS_CLASS_*} constants against the running OS without
 * any C compilation (spec §76).
 */
@EnabledOnOs(OS.MAC)
@Tag("integration")
class MacOsIntegrationTest {

    @Test
    void factorySelectsMacOsProvider() {
        ThreadQosProvider provider = ThreadQosProviderFactory.create();
        assertInstanceOf(MacOsThreadQosProvider.class, provider);
        QosCapabilities caps = provider.capabilities();
        assertEquals(OperatingSystem.MACOS, caps.operatingSystem());
        assertTrue(caps.qosSupported());
        assertTrue(caps.nativeReadbackSupported());
        assertTrue(caps.performanceHintSupported());
        assertNull(caps.unsupportedReason());
        assertTrue(ThreadQos.isSupported());
    }

    @Test
    void requiredSymbolsResolve() {
        assertTrue(FfmSupport.defaultLookup().find(MacOsQosNative.SET_QOS_CLASS_SELF).isPresent());
        assertTrue(FfmSupport.defaultLookup().find(MacOsQosNative.QOS_CLASS_SELF).isPresent());
        MacOsQosNative.link();
    }

    @Test
    void freshThreadReportsAKnownClass() throws Exception {
        // Observed on macOS 15/26 with Temurin 25: a new HotSpot platform thread inherits the creator's
        // requested class and reports QOS_CLASS_DEFAULT (0x15), not QOS_CLASS_UNSPECIFIED. The library
        // documents DEFAULT as "reset to the library default", not "restore the pristine state".
        AtomicInteger initial = new AtomicInteger(-1);
        Thread t = Thread.ofPlatform().start(() -> initial.set(MacOsQosNative.qosClassSelf()));
        t.join();
        System.out.println("[macos] qos_class_self() on a fresh platform thread = 0x" + Integer.toHexString(initial.get()));
        Set<Integer> known = new HashSet<>(List.of(MacOsQosNative.QOS_CLASS_UNSPECIFIED, MacOsQosNative.QOS_CLASS_BACKGROUND,
                MacOsQosNative.QOS_CLASS_UTILITY, MacOsQosNative.QOS_CLASS_DEFAULT,
                MacOsQosNative.QOS_CLASS_USER_INITIATED, MacOsQosNative.QOS_CLASS_USER_INTERACTIVE));
        assertTrue(known.contains(initial.get()), "unknown qos_class_t value " + initial.get());
    }

    @ParameterizedTest
    @EnumSource(QosClass.class)
    @Tag("abi")
    void setThenReadBackRoundTrip(QosClass qosClass) throws Exception {
        AtomicInteger readBack = new AtomicInteger(-1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread t = Thread.ofPlatform().name("qos-" + qosClass).start(() -> {
            try {
                ThreadQos.set(qosClass);
                readBack.set(MacOsQosNative.qosClassSelf());
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        t.join();
        assertNull(failure.get(), () -> "native error: " + failure.get());
        assertEquals(MacOsQosNative.toNativeQosClass(qosClass), readBack.get(),
                "qos_class_self() must return the constant we set");
    }

    @Test
    void allClassesInSequenceOnOneThread() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread t = Thread.ofPlatform().start(() -> {
            try {
                for (QosClass q : QosClass.values()) {
                    ThreadQos.set(q);
                    assertEquals(MacOsQosNative.toNativeQosClass(q), MacOsQosNative.qosClassSelf());
                    assertTrue(ThreadQos.trySet(q));
                }
                ThreadQos.set(QosClass.DEFAULT);
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        t.join();
        assertNull(failure.get(), () -> "failure: " + failure.get());
    }

    @Test
    void wrappedThreadStartsWithoutError() throws Exception {
        AtomicInteger seen = new AtomicInteger(-1);
        Thread t = Thread.ofPlatform().name("wrapped").start(
                ThreadQos.wrap(QosClass.USER_INTERACTIVE, () -> seen.set(MacOsQosNative.qosClassSelf())));
        t.join();
        assertEquals(MacOsQosNative.QOS_CLASS_USER_INTERACTIVE, seen.get());
    }

    @Test
    void virtualThreadIsRejectedBeforeAnyNativeCall() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger carrierQos = new AtomicInteger(-1);
        Thread vt = Thread.ofVirtual().start(() -> {
            try {
                ThreadQos.set(QosClass.BACKGROUND);
            } catch (Throwable e) {
                failure.set(e);
            }
            carrierQos.set(MacOsQosNative.qosClassSelf());
        });
        vt.join();
        assertInstanceOf(IllegalStateException.class, failure.get());
        assertTrue(carrierQos.get() != MacOsQosNative.QOS_CLASS_BACKGROUND, "carrier thread must be untouched");
    }
}
