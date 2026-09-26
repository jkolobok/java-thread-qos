package com.clericode.threadqos.internal.windows;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Layout test (spec §52): runs on every OS, no native call.
 */
class ThreadPowerThrottlingStateTest {

    @Test
    void sizeAndAlignment() {
        assertEquals(12, ThreadPowerThrottlingState.LAYOUT.byteSize());
        assertEquals(12, ThreadPowerThrottlingState.SIZE);
        assertEquals(4, ThreadPowerThrottlingState.LAYOUT.byteAlignment());
    }

    @Test
    void offsetsAndWidths() {
        assertEquals(0, offset("Version"));
        assertEquals(4, offset("ControlMask"));
        assertEquals(8, offset("StateMask"));
        for (MemoryLayout member : ThreadPowerThrottlingState.LAYOUT.memberLayouts()) {
            assertEquals(ValueLayout.JAVA_INT.withName(member.name().orElseThrow()), member);
        }
    }

    @Test
    void writeSetsCurrentVersionAndMasks() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment s = arena.allocate(ThreadPowerThrottlingState.LAYOUT);
            ThreadPowerThrottlingState.write(s, 0x1, 0x0);
            assertEquals(1, ThreadPowerThrottlingState.version(s));
            assertEquals(1, ThreadPowerThrottlingState.THREAD_POWER_THROTTLING_CURRENT_VERSION);
            assertEquals(0x1, ThreadPowerThrottlingState.controlMask(s));
            assertEquals(0x0, ThreadPowerThrottlingState.stateMask(s));
            // raw bytes, little-endian on every Windows ABI
            assertEquals(1, s.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), 0));
            assertEquals(1, s.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), 4));
        }
    }

    @Test
    void constantsMatchSdk() {
        assertEquals(0x1, ThreadPowerThrottlingState.THREAD_POWER_THROTTLING_EXECUTION_SPEED);
        assertEquals(3, WindowsQosNative.THREAD_POWER_THROTTLING);
    }

    private static long offset(String field) {
        return ThreadPowerThrottlingState.LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement(field));
    }
}
