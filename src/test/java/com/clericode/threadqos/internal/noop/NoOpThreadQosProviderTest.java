package com.clericode.threadqos.internal.noop;

import com.clericode.threadqos.Architecture;
import com.clericode.threadqos.OperatingSystem;
import com.clericode.threadqos.QosClass;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoOpThreadQosProviderTest {

    @Test
    void isNeverSupportedAndNeverThrows() {
        NoOpThreadQosProvider p = new NoOpThreadQosProvider(OperatingSystem.OTHER, Architecture.OTHER, "FreeBSD");
        assertFalse(p.isSupported());
        for (QosClass q : QosClass.values()) {
            assertFalse(p.set(q));
        }
        assertEquals(OperatingSystem.OTHER, p.capabilities().operatingSystem());
        assertEquals("FreeBSD", p.capabilities().unsupportedReason());
        assertTrue(p.toString().contains("FreeBSD"));
    }

    @Test
    void reasonIsMandatory() {
        assertThrows(NullPointerException.class, () -> new NoOpThreadQosProvider(OperatingSystem.OTHER, Architecture.OTHER, null));
    }
}
