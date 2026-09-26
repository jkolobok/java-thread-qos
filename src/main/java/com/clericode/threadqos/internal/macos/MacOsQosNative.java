package com.clericode.threadqos.internal.macos;

import com.clericode.threadqos.OperatingSystem;
import com.clericode.threadqos.QosClass;
import com.clericode.threadqos.internal.FfmSupport;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * FFM bindings to the Darwin pthread QoS API.
 *
 * <p>ABI source: Apple libpthread, {@code include/sys/qos.h} and {@code include/pthread/qos.h}
 * (https://github.com/apple-oss-distributions/libpthread). {@code qos_class_t} is an
 * {@code unsigned int} enum; both functions are available since macOS 10.10.</p>
 *
 * <p>Method handles are created exactly once, lazily, on the first native call; nothing native
 * happens when this class is loaded (spec §7.4, §78).</p>
 */
public final class MacOsQosNative {

    /** Native function name. */
    public static final String SET_QOS_CLASS_SELF = "pthread_set_qos_class_self_np";
    /** Native function name used for read-back. */
    public static final String QOS_CLASS_SELF = "qos_class_self";

    // Darwin sys/qos.h, enum qos_class_t (macos(10.10)):
    /** {@code QOS_CLASS_USER_INTERACTIVE}. */
    public static final int QOS_CLASS_USER_INTERACTIVE = 0x21;
    /** {@code QOS_CLASS_USER_INITIATED}. */
    public static final int QOS_CLASS_USER_INITIATED = 0x19;
    /** {@code QOS_CLASS_DEFAULT}. */
    public static final int QOS_CLASS_DEFAULT = 0x15;
    /** {@code QOS_CLASS_UTILITY}. */
    public static final int QOS_CLASS_UTILITY = 0x11;
    /** {@code QOS_CLASS_BACKGROUND}. */
    public static final int QOS_CLASS_BACKGROUND = 0x09;
    /** {@code QOS_CLASS_UNSPECIFIED}: the state of a thread that never requested a QoS class. */
    public static final int QOS_CLASS_UNSPECIFIED = 0x00;

    /** Portable API v1 never exposes the relative priority (spec §7.2). */
    public static final int RELATIVE_PRIORITY_DEFAULT = 0;

    /** Darwin errno: operation not permitted (thread opted out of QoS by {@code pthread_setschedparam}). */
    public static final int EPERM = 1;

    private MacOsQosNative() {
    }

    /**
     * Immutable set of downcall handles, linked once.
     *
     * @param setQos  {@code int pthread_set_qos_class_self_np(qos_class_t, int)}
     * @param qosSelf {@code qos_class_t qos_class_self(void)}
     */
    private record Handles(MethodHandle setQos, MethodHandle qosSelf) {
    }

    private static final FfmSupport.Lazy<Handles> HANDLES = new FfmSupport.Lazy<>(MacOsQosNative::linkHandles);

    private static Handles linkHandles() {
        MethodHandle setQos = FfmSupport.downcall(OperatingSystem.MACOS, FfmSupport.defaultLookup(),
                SET_QOS_CLASS_SELF,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        MethodHandle qosSelf = FfmSupport.downcall(OperatingSystem.MACOS, FfmSupport.defaultLookup(),
                QOS_CLASS_SELF,
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
        return new Handles(setQos, qosSelf);
    }

    /**
     * Maps a portable class to the Darwin constant (spec §7.2). The constants never leave this
     * package.
     *
     * @param qosClass portable class
     * @return {@code QOS_CLASS_*} value
     */
    public static int toNativeQosClass(QosClass qosClass) {
        return switch (qosClass) {
            case USER_INTERACTIVE -> QOS_CLASS_USER_INTERACTIVE;
            case USER_INITIATED -> QOS_CLASS_USER_INITIATED;
            case DEFAULT -> QOS_CLASS_DEFAULT;
            case UTILITY -> QOS_CLASS_UTILITY;
            case BACKGROUND -> QOS_CLASS_BACKGROUND;
        };
    }

    /**
     * Forces linkage of all handles. Used by tests and to surface linkage errors early.
     *
     * @throws com.clericode.threadqos.ThreadQosException if a symbol cannot be linked
     */
    public static void link() {
        HANDLES.get();
    }

    /**
     * Calls {@code pthread_set_qos_class_self_np}.
     *
     * @param nativeQosClass   {@code QOS_CLASS_*} value
     * @param relativePriority relative priority, 0 in v1
     * @return 0 on success, otherwise an errno value (typically {@code EPERM})
     * @throws com.clericode.threadqos.ThreadQosException if the function cannot be linked
     */
    public static int setQosClassSelf(int nativeQosClass, int relativePriority) {
        try {
            return (int) HANDLES.get().setQos().invokeExact(nativeQosClass, relativePriority);
        } catch (Throwable t) {
            throw FfmSupport.invocationFailure(OperatingSystem.MACOS, SET_QOS_CLASS_SELF, t);
        }
    }

    /**
     * Calls {@code qos_class_self()}: the QoS class currently requested for the calling thread.
     *
     * @return {@code QOS_CLASS_*} value, {@link #QOS_CLASS_UNSPECIFIED} if none was requested
     * @throws com.clericode.threadqos.ThreadQosException if the function cannot be linked
     */
    public static int qosClassSelf() {
        try {
            return (int) HANDLES.get().qosSelf().invokeExact();
        } catch (Throwable t) {
            throw FfmSupport.invocationFailure(OperatingSystem.MACOS, QOS_CLASS_SELF, t);
        }
    }
}
