package com.clericode.threadqos.internal.macos;

import com.clericode.threadqos.QosClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure mapping tests; run on every OS (no native call).
 */
class MacOsQosNativeTest {

    @ParameterizedTest
    @CsvSource({
            "USER_INTERACTIVE, 0x21",
            "USER_INITIATED,   0x19",
            "DEFAULT,          0x15",
            "UTILITY,          0x11",
            "BACKGROUND,       0x09",
    })
    void mapsToDarwinConstants(QosClass qosClass, String expectedHex) {
        assertEquals(Integer.decode(expectedHex), MacOsQosNative.toNativeQosClass(qosClass));
    }

    @Test
    void everyClassMapsToADistinctNonUnspecifiedValue() {
        Set<Integer> seen = new HashSet<>();
        for (QosClass q : QosClass.values()) {
            int v = MacOsQosNative.toNativeQosClass(q);
            assertTrue(v != MacOsQosNative.QOS_CLASS_UNSPECIFIED);
            assertTrue(seen.add(v), "duplicate native value for " + q);
        }
        assertEquals(0, MacOsQosNative.RELATIVE_PRIORITY_DEFAULT);
    }
}
