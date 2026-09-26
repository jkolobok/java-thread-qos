package com.clericode.threadqos.internal.linux;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layout test (spec §13, §52): runs on every OS, no native call.
 */
class LinuxSchedAttrTest {

    @Test
    void sizeAndAlignment() {
        assertEquals(56, LinuxSchedAttr.LAYOUT.byteSize());
        assertEquals(56, LinuxSchedAttr.SIZE);
        assertEquals(LinuxSchedAttr.SIZE_VER1, LinuxSchedAttr.SIZE);
        assertEquals(48, LinuxSchedAttr.SIZE_VER0);
        assertEquals(8, LinuxSchedAttr.LAYOUT.byteAlignment());
    }

    @Test
    void offsets() {
        assertEquals(0, offset("size"));
        assertEquals(4, offset("sched_policy"));
        assertEquals(8, offset("sched_flags"));
        assertEquals(16, offset("sched_nice"));
        assertEquals(20, offset("sched_priority"));
        assertEquals(24, offset("sched_runtime"));
        assertEquals(32, offset("sched_deadline"));
        assertEquals(40, offset("sched_period"));
        assertEquals(48, offset("sched_util_min"));
        assertEquals(52, offset("sched_util_max"));
    }

    @Test
    void integerWidths() {
        assertEquals(ValueLayout.JAVA_INT, member("size").withoutName());
        assertEquals(ValueLayout.JAVA_INT, member("sched_policy").withoutName());
        assertEquals(ValueLayout.JAVA_LONG, member("sched_flags").withoutName());
        assertEquals(ValueLayout.JAVA_INT, member("sched_nice").withoutName());
        assertEquals(ValueLayout.JAVA_INT, member("sched_priority").withoutName());
        assertEquals(ValueLayout.JAVA_LONG, member("sched_runtime").withoutName());
        assertEquals(ValueLayout.JAVA_LONG, member("sched_deadline").withoutName());
        assertEquals(ValueLayout.JAVA_LONG, member("sched_period").withoutName());
        assertEquals(ValueLayout.JAVA_INT, member("sched_util_min").withoutName());
        assertEquals(ValueLayout.JAVA_INT, member("sched_util_max").withoutName());
    }

    @Test
    void accessorsRoundTripAndSignedNice() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment a = arena.allocate(LinuxSchedAttr.LAYOUT);
            LinuxSchedAttr.size(a, 56);
            LinuxSchedAttr.policy(a, LinuxSchedAttr.SCHED_BATCH);
            LinuxSchedAttr.flags(a, LinuxSchedAttr.SCHED_FLAG_RESET_ON_FORK | LinuxSchedAttr.SCHED_FLAG_UTIL_CLAMP_MIN);
            LinuxSchedAttr.nice(a, -5);
            LinuxSchedAttr.priority(a, 0);
            LinuxSchedAttr.utilMin(a, 768);
            LinuxSchedAttr.utilMax(a, 1024);
            assertEquals(56, LinuxSchedAttr.size(a));
            assertEquals(LinuxSchedAttr.SCHED_BATCH, LinuxSchedAttr.policy(a));
            assertEquals(0x21L, LinuxSchedAttr.flags(a));
            assertEquals(-5, LinuxSchedAttr.nice(a), "sched_nice is __s32");
            assertEquals(768, LinuxSchedAttr.utilMin(a));
            assertEquals(1024, LinuxSchedAttr.utilMax(a));
            assertEquals(0L, LinuxSchedAttr.runtime(a));
            assertEquals(0L, LinuxSchedAttr.deadline(a));
            assertEquals(0L, LinuxSchedAttr.period(a));
            // raw byte check of a 64-bit field at its offset
            assertEquals(0x21L, a.get(ValueLayout.JAVA_LONG, 8));
        }
    }

    @Test
    void uapiConstants() {
        assertEquals(0, LinuxSchedAttr.SCHED_NORMAL);
        assertEquals(1, LinuxSchedAttr.SCHED_FIFO);
        assertEquals(2, LinuxSchedAttr.SCHED_RR);
        assertEquals(3, LinuxSchedAttr.SCHED_BATCH);
        assertEquals(5, LinuxSchedAttr.SCHED_IDLE);
        assertEquals(6, LinuxSchedAttr.SCHED_DEADLINE);
        assertEquals(7, LinuxSchedAttr.SCHED_EXT);
        assertEquals(0x08L, LinuxSchedAttr.SCHED_FLAG_KEEP_POLICY);
        assertEquals(0x10L, LinuxSchedAttr.SCHED_FLAG_KEEP_PARAMS);
        assertEquals(0x20L, LinuxSchedAttr.SCHED_FLAG_UTIL_CLAMP_MIN);
        assertEquals(0x40L, LinuxSchedAttr.SCHED_FLAG_UTIL_CLAMP_MAX);
        assertEquals(0x18L, LinuxSchedAttr.SCHED_FLAG_KEEP_ALL);
        assertEquals(0x60L, LinuxSchedAttr.SCHED_FLAG_UTIL_CLAMP);
        assertEquals(1, LinuxSchedAttr.EPERM);
        assertEquals(22, LinuxSchedAttr.EINVAL);
        assertEquals(38, LinuxSchedAttr.ENOSYS);
        assertEquals(95, LinuxSchedAttr.EOPNOTSUPP);
        assertEquals(1024, LinuxSchedAttr.UCLAMP_MAX_VALUE);
    }

    @Test
    void fairPolicies() {
        assertTrue(LinuxSchedAttr.isFairPolicy(LinuxSchedAttr.SCHED_NORMAL));
        assertTrue(LinuxSchedAttr.isFairPolicy(LinuxSchedAttr.SCHED_BATCH));
        assertTrue(LinuxSchedAttr.isFairPolicy(LinuxSchedAttr.SCHED_IDLE));
        assertFalse(LinuxSchedAttr.isFairPolicy(LinuxSchedAttr.SCHED_FIFO));
        assertFalse(LinuxSchedAttr.isFairPolicy(LinuxSchedAttr.SCHED_RR));
        assertFalse(LinuxSchedAttr.isFairPolicy(LinuxSchedAttr.SCHED_DEADLINE));
        assertFalse(LinuxSchedAttr.isFairPolicy(LinuxSchedAttr.SCHED_EXT));
        assertEquals("SCHED_FIFO", LinuxSchedAttr.policyName(1));
        assertEquals("policy 42", LinuxSchedAttr.policyName(42));
    }

    private static long offset(String field) {
        return LinuxSchedAttr.LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement(field));
    }

    private static MemoryLayout member(String field) {
        return LinuxSchedAttr.LAYOUT.select(MemoryLayout.PathElement.groupElement(field));
    }
}
