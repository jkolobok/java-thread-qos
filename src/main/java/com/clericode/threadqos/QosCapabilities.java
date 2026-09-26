package com.clericode.threadqos;

import java.util.Objects;

/**
 * Immutable description of what the active provider can do on this JVM.
 *
 * <p>The library never logs (it has no logging dependency and is silent by default), so this
 * record is the only diagnostic channel that explains <em>why</em> the library is a no-op on a
 * given machine: see {@link #unsupportedReason()}.</p>
 *
 * <table class="striped">
 *   <caption>Field semantics per operating system</caption>
 *   <thead>
 *     <tr><th scope="col">Field</th><th scope="col">macOS</th><th scope="col">Windows</th>
 *         <th scope="col">Linux</th><th scope="col">Other</th></tr>
 *   </thead>
 *   <tbody>
 *     <tr><th scope="row">{@code qosSupported}</th><td>{@code true}</td>
 *         <td>{@code powerThrottlingSupported || priorityAdjustmentSupported}</td>
 *         <td>{@code true} only after a successful uclamp probe</td><td>{@code false}</td></tr>
 *     <tr><th scope="row">{@code nativeReadbackSupported}</th><td>{@code true} ({@code qos_class_self})</td>
 *         <td>{@code true} ({@code GetThreadInformation}, {@code GetThreadPriority})</td>
 *         <td>{@code true} ({@code sched_getattr}) when supported</td><td>{@code false}</td></tr>
 *     <tr><th scope="row">{@code performanceHintSupported}</th><td>{@code true}</td>
 *         <td>equals {@code powerThrottlingSupported}</td><td>equals {@code qosSupported}</td><td>{@code false}</td></tr>
 *     <tr><th scope="row">{@code powerThrottlingSupported}</th><td>{@code false}</td>
 *         <td>result of the {@code ThreadPowerThrottling} probe</td><td>{@code false}</td><td>{@code false}</td></tr>
 *     <tr><th scope="row">{@code priorityAdjustmentSupported}</th><td>{@code false}</td>
 *         <td>{@code true}</td><td>{@code false}</td><td>{@code false}</td></tr>
 *     <tr><th scope="row">{@code unsupportedReason}</th><td>{@code null}</td>
 *         <td>{@code null}, or why power throttling is unavailable</td>
 *         <td>{@code null}, or why uclamp is unavailable</td><td>why the OS is unsupported</td></tr>
 *   </tbody>
 * </table>
 *
 * @param operatingSystem             detected operating system family
 * @param architecture                detected CPU architecture
 * @param qosSupported                {@code true} if {@link ThreadQos#set(QosClass)} performs a real
 *                                    native operation on this JVM
 * @param nativeReadbackSupported     {@code true} if the provider can read the native state back
 *                                    (used by the library's own tests; not exposed as public API)
 * @param performanceHintSupported    {@code true} if the mechanism carries a performance/energy
 *                                    hint to the scheduler (pthread QoS, power throttling, uclamp)
 * @param powerThrottlingSupported    {@code true} if Windows {@code ThreadPowerThrottling} is available
 * @param priorityAdjustmentSupported {@code true} if the provider adjusts the native thread priority
 *                                    (Windows only in this version)
 * @param unsupportedReason           human-readable explanation when something is unavailable, or
 *                                    {@code null} when every mechanism advertised for this OS works
 */
public record QosCapabilities(
        OperatingSystem operatingSystem,
        Architecture architecture,
        boolean qosSupported,
        boolean nativeReadbackSupported,
        boolean performanceHintSupported,
        boolean powerThrottlingSupported,
        boolean priorityAdjustmentSupported,
        String unsupportedReason) {

    /**
     * Canonical constructor; validates the non-null components.
     *
     * @param operatingSystem             detected operating system family
     * @param architecture                detected CPU architecture
     * @param qosSupported                see {@link #qosSupported()}
     * @param nativeReadbackSupported     see {@link #nativeReadbackSupported()}
     * @param performanceHintSupported    see {@link #performanceHintSupported()}
     * @param powerThrottlingSupported    see {@link #powerThrottlingSupported()}
     * @param priorityAdjustmentSupported see {@link #priorityAdjustmentSupported()}
     * @param unsupportedReason           see {@link #unsupportedReason()}
     */
    public QosCapabilities {
        Objects.requireNonNull(operatingSystem, "operatingSystem");
        Objects.requireNonNull(architecture, "architecture");
    }

    /**
     * Creates capabilities for a platform on which the library is a no-op.
     *
     * @param operatingSystem detected operating system family
     * @param architecture    detected CPU architecture
     * @param reason          non-null explanation of why QoS is unavailable
     * @return capabilities with every flag {@code false} and the given reason
     */
    public static QosCapabilities unsupported(OperatingSystem operatingSystem, Architecture architecture, String reason) {
        Objects.requireNonNull(reason, "reason");
        return new QosCapabilities(operatingSystem, architecture, false, false, false, false, false, reason);
    }

    /**
     * Creates capabilities for a platform on which every mechanism listed for that OS works.
     *
     * @param operatingSystem             detected operating system family
     * @param architecture                detected CPU architecture
     * @param nativeReadbackSupported     see {@link #nativeReadbackSupported()}
     * @param performanceHintSupported    see {@link #performanceHintSupported()}
     * @param powerThrottlingSupported    see {@link #powerThrottlingSupported()}
     * @param priorityAdjustmentSupported see {@link #priorityAdjustmentSupported()}
     * @return capabilities with {@code qosSupported == true} and no unsupported reason
     */
    public static QosCapabilities supported(OperatingSystem operatingSystem, Architecture architecture,
                                            boolean nativeReadbackSupported, boolean performanceHintSupported,
                                            boolean powerThrottlingSupported, boolean priorityAdjustmentSupported) {
        return new QosCapabilities(operatingSystem, architecture, true, nativeReadbackSupported,
                performanceHintSupported, powerThrottlingSupported, priorityAdjustmentSupported, null);
    }
}
