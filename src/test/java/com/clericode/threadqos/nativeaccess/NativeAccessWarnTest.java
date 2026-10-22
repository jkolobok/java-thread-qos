package com.clericode.threadqos.nativeaccess;

import com.clericode.threadqos.OperatingSystem;
import com.clericode.threadqos.QosCapabilities;
import com.clericode.threadqos.QosClass;
import com.clericode.threadqos.ThreadQos;
import com.clericode.threadqos.ThreadQosException;
import com.clericode.threadqos.internal.FfmSupport;
import org.junit.jupiter.api.Test;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.ValueLayout;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs only from the {@code native-access-warn} Maven profile: JVM started without
 * {@code --enable-native-access} and without {@code --illegal-native-access} (JDK 25 default =
 * {@code warn}). The JDK prints a one-time warning, the call succeeds, and the library must still
 * surface the hint in its diagnostics (spec §47, D-08).
 */
class NativeAccessWarnTest {

    private static final String FLAG = "--enable-native-access=" + FfmSupport.MODULE_NAME;

    @Test
    void hintIsProducedWhenNativeAccessIsMissing() {
        assertFalse(FfmSupport.isNativeAccessEnabled(), "this test must run without --enable-native-access");
        String hint = FfmSupport.nativeAccessHint();
        assertNotNull(hint);
        assertTrue(hint.contains(FLAG), hint);
        assertTrue(hint.contains("one-time JVM warning"), hint);
    }

    @Test
    void linkageErrorsCarryTheHint() {
        ThreadQosException e = FfmSupport.linkageFailure(OperatingSystem.OTHER, "SetThreadInformation",
                new NoSuchElementException("Symbol not found"));
        assertTrue(e.getMessage().contains(FLAG), e.getMessage());
    }

    @Test
    void hintSurvivesASuccessfulRestrictedCall() {
        FfmSupport.downcall(OperatingSystem.OTHER, FfmSupport.defaultLookup(), "strlen",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        assertFalse(FfmSupport.isNativeAccessEnabled(),
                "warn-mode access must not replace the initial native-access setting");
        String hint = FfmSupport.nativeAccessHint();
        assertNotNull(hint);
        assertTrue(hint.contains(FLAG), hint);
        ThreadQosException e = FfmSupport.linkageFailure(OperatingSystem.OTHER, "missing-symbol",
                new NoSuchElementException("Symbol not found"));
        assertTrue(e.getMessage().contains(FLAG), e.getMessage());
    }

    @Test
    void setStillWorksInWarnMode() throws Exception {
        QosCapabilities caps = ThreadQos.capabilities();
        System.out.println("[native-access-warn] capabilities: " + caps);
        assumeTrue(caps.qosSupported(), "platform unsupported: " + caps.unsupportedReason());
        Thread t = Thread.ofPlatform().start(ThreadQos.wrap(QosClass.DEFAULT, () -> { }));
        t.join();
        assertTrue(ThreadQos.isSupported());
    }
}
