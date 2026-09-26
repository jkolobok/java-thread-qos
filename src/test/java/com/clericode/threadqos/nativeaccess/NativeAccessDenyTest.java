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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs only from the {@code native-access-deny} Maven profile: JVM started with
 * {@code --illegal-native-access=deny} and without {@code --enable-native-access} (spec §47).
 */
class NativeAccessDenyTest {

    private static final String FLAG = "--enable-native-access=" + FfmSupport.MODULE_NAME;

    @Test
    void nativeAccessIsNotEnabled() {
        assertFalse(FfmSupport.isNativeAccessEnabled(), "this test must run without --enable-native-access");
        String hint = FfmSupport.nativeAccessHint();
        assertNotNull(hint);
        assertTrue(hint.contains(FLAG), hint);
    }

    @Test
    void restrictedCallIsDeniedAndWrappedWithHint() {
        ThreadQosException e = assertThrows(ThreadQosException.class, () -> FfmSupport.downcall(
                OperatingSystem.OTHER, FfmSupport.defaultLookup(), "strlen",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)));
        assertTrue(hasCause(e, IllegalCallerException.class), () -> "cause chain: " + e);
        assertTrue(e.getMessage().contains(FLAG), e.getMessage());
    }

    @Test
    void setThrowsReadableExceptionOnSupportedPlatforms() {
        QosCapabilities caps = ThreadQos.capabilities();
        System.out.println("[native-access-deny] capabilities: " + caps);
        assumeTrue(caps.operatingSystem() != OperatingSystem.OTHER, "no native provider on this OS");
        if (caps.unsupportedReason() != null) {
            // Providers that probe at init (Linux, Windows) report the denial through capabilities…
            assertTrue(caps.unsupportedReason().contains(FLAG), caps.unsupportedReason());
        }
        // …and every provider must still refuse set() loudly instead of silently doing nothing.
        ThreadQosException e = assertThrows(ThreadQosException.class, () -> ThreadQos.set(QosClass.DEFAULT));
        assertTrue(hasCause(e, IllegalCallerException.class), () -> "cause chain: " + e);
        assertTrue(e.getMessage().contains(FLAG), e.getMessage());
    }

    static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }
}
