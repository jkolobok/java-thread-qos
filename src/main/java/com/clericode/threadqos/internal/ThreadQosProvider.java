package com.clericode.threadqos.internal;

import com.clericode.threadqos.QosCapabilities;
import com.clericode.threadqos.QosClass;
import com.clericode.threadqos.ThreadQosException;

/**
 * Strategy interface implemented once per operating system.
 *
 * <p>Implementations are selected exactly once by {@link ThreadQosProviderFactory}. They must not
 * detect the OS, look up symbols, take monitor locks or allocate avoidable objects in
 * {@link #set(QosClass)} after initialization (spec §27).</p>
 */
public interface ThreadQosProvider {

    /**
     * Whether {@link #set(QosClass)} performs a real native operation on this JVM. May trigger a
     * lazy, read-only capability probe.
     *
     * @return {@code true} if native QoS is available
     */
    boolean isSupported();

    /**
     * Detailed capabilities. May trigger a lazy, read-only capability probe.
     *
     * @return capabilities, never {@code null}
     */
    QosCapabilities capabilities();

    /**
     * Applies the QoS class to the current native thread. The caller has already validated that
     * the argument is non-null and that the current thread is a platform thread.
     *
     * @param qosClass requested class
     * @return {@code true} if a native operation was performed, {@code false} if the platform turned
     *         out to be unsupported and the call was a no-op
     * @throws ThreadQosException if the platform is supported but the native operation failed, or if
     *                            the native bindings could not be linked
     */
    boolean set(QosClass qosClass);
}
