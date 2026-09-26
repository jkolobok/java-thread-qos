package com.clericode.threadqos.internal;

import com.clericode.threadqos.OperatingSystem;
import com.clericode.threadqos.ThreadQosException;
import org.junit.jupiter.api.Test;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmSupportTest {

    @Test
    void missingSymbolIsWrappedWithCause() {
        ThreadQosException e = assertThrows(ThreadQosException.class, () -> FfmSupport.downcall(
                OperatingSystem.OTHER, FfmSupport.defaultLookup(), "thread_qos_no_such_symbol_xyz",
                FunctionDescriptor.of(ValueLayout.JAVA_INT)));
        assertInstanceOf(NoSuchElementException.class, e.getCause());
        assertEquals("thread_qos_no_such_symbol_xyz", e.getNativeOperation());
        assertNull(e.getNativeErrorCode());
        assertTrue(e.getMessage().contains("thread_qos_no_such_symbol_xyz"), e.getMessage());
    }

    @Test
    void existingLibcSymbolLinks() throws Throwable {
        // strlen exists in every default lookup (libc / libSystem / ucrt).
        MethodHandle strlen = FfmSupport.downcall(OperatingSystem.OTHER, FfmSupport.defaultLookup(), "strlen",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        assertNotNull(strlen);
        try (var arena = java.lang.foreign.Arena.ofConfined()) {
            long len = (long) strlen.invokeExact(arena.allocateFrom("hello"));
            assertEquals(5L, len);
        }
    }

    @Test
    void nativeAccessIsEnabledInTheRegularTestRun() {
        // Surefire runs with --enable-native-access=com.clericode.threadqos (pom.xml).
        assertTrue(FfmSupport.isNativeAccessEnabled());
        assertNull(FfmSupport.nativeAccessHint());
    }

    @Test
    void hintTextNamesTheModuleOrAllUnnamed() {
        String named = FfmSupport.nativeAccessHintText(FfmSupport.class.getModule());
        String expectedTarget = FfmSupport.class.getModule().isNamed() ? FfmSupport.MODULE_NAME : "ALL-UNNAMED";
        assertTrue(named.contains("--enable-native-access=" + expectedTarget), named);
        assertTrue(named.contains("--enable-native-access=ALL-UNNAMED"), named);
        assertTrue(named.contains("--illegal-native-access=deny"), named);
        assertEquals("com.clericode.threadqos", FfmSupport.MODULE_NAME);
    }

    @Test
    void captureStateLayoutHasPlatformErrorField() {
        StructLayout layout = FfmSupport.captureStateLayout();
        assertNotNull(layout);
        String field = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")
                ? "GetLastError" : "errno";
        assertNotNull(FfmSupport.captureStateHandle(field));
        assertTrue(layout.byteSize() >= 4);
    }

    @Test
    void invocationFailureRethrowsThreadQosExceptionAsIs() {
        ThreadQosException original = ThreadQosException.nativeFailure(OperatingSystem.MACOS,
                com.clericode.threadqos.QosClass.DEFAULT, "f", 1);
        assertEquals(original, FfmSupport.invocationFailure(OperatingSystem.MACOS, "f", original));
        RuntimeException wrapped = FfmSupport.invocationFailure(OperatingSystem.MACOS, "f", new IllegalStateException("x"));
        assertInstanceOf(ThreadQosException.class, wrapped);
        assertInstanceOf(IllegalStateException.class, wrapped.getCause());
        assertThrows(OutOfMemoryError.class, () -> FfmSupport.invocationFailure(OperatingSystem.MACOS, "f", new OutOfMemoryError()));
    }

    @Test
    void lazyLinksOnceAndCachesFailures() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        FfmSupport.Lazy<String> ok = new FfmSupport.Lazy<>(() -> "v" + calls.incrementAndGet());
        assertTrue(!ok.isLinked());
        assertEquals("v1", ok.get());
        assertEquals("v1", ok.get());
        assertTrue(ok.isLinked());
        assertEquals(1, calls.get());

        java.util.concurrent.atomic.AtomicInteger failing = new java.util.concurrent.atomic.AtomicInteger();
        FfmSupport.Lazy<String> bad = new FfmSupport.Lazy<>(() -> {
            failing.incrementAndGet();
            throw ThreadQosException.linkageFailure(OperatingSystem.OTHER, "sym", new NoSuchElementException("x"), null);
        });
        ThreadQosException first = assertThrows(ThreadQosException.class, bad::get);
        ThreadQosException second = assertThrows(ThreadQosException.class, bad::get);
        assertEquals(first, second, "the same readable exception must be rethrown, never NoClassDefFoundError");
        assertEquals(1, failing.get(), "linkage must not be retried");
    }
}
