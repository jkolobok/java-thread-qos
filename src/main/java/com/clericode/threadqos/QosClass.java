package com.clericode.threadqos;

/**
 * Portable Quality of Service (performance intent) classes for a Java platform thread.
 *
 * <p>Each constant expresses <em>how latency-sensitive</em> the work running on the current thread
 * is. The library maps the constant to the native mechanism of the operating system. The mapping
 * is a semantic one chosen by this library; it is not a claim that the native APIs of different
 * operating systems are equivalent.</p>
 *
 * <table class="striped">
 *   <caption>Per-OS mapping (library policy, version 0.x)</caption>
 *   <thead>
 *     <tr><th scope="col">QosClass</th><th scope="col">macOS (pthread QoS)</th>
 *         <th scope="col">Windows (power throttling + priority)</th><th scope="col">Linux (uclamp)</th></tr>
 *   </thead>
 *   <tbody>
 *     <tr><th scope="row">{@link #USER_INTERACTIVE}</th><td>{@code QOS_CLASS_USER_INTERACTIVE}</td>
 *         <td>HighQoS + {@code THREAD_PRIORITY_HIGHEST}</td><td>{@code util_min=1024, util_max=1024}</td></tr>
 *     <tr><th scope="row">{@link #USER_INITIATED}</th><td>{@code QOS_CLASS_USER_INITIATED}</td>
 *         <td>HighQoS + {@code THREAD_PRIORITY_ABOVE_NORMAL}</td><td>{@code util_min=768, util_max=1024}</td></tr>
 *     <tr><th scope="row">{@link #DEFAULT}</th><td>{@code QOS_CLASS_DEFAULT}</td>
 *         <td>system-managed QoS + {@code THREAD_PRIORITY_NORMAL}</td><td>{@code util_min=0, util_max=1024}</td></tr>
 *     <tr><th scope="row">{@link #UTILITY}</th><td>{@code QOS_CLASS_UTILITY}</td>
 *         <td>system-managed QoS + {@code THREAD_PRIORITY_BELOW_NORMAL}</td><td>{@code util_min=0, util_max=768}</td></tr>
 *     <tr><th scope="row">{@link #BACKGROUND}</th><td>{@code QOS_CLASS_BACKGROUND}</td>
 *         <td>EcoQoS + {@code THREAD_PRIORITY_LOWEST}</td><td>{@code util_min=0, util_max=256}</td></tr>
 *   </tbody>
 * </table>
 *
 * <p>None of the classes is a real-time scheduling policy, none reserves CPU time, and none pins
 * the thread to a CPU.</p>
 */
public enum QosClass {

    /**
     * Maximally latency-sensitive foreground or critical work: order execution, the critical path
     * of market-data processing, event dispatch with hard latency expectations.
     *
     * <p>This is not a real-time scheduling policy.</p>
     */
    USER_INTERACTIVE,

    /**
     * High performance intent, less aggressive than {@link #USER_INTERACTIVE}: trading strategy
     * calculation, synchronous user-triggered computation, time-sensitive data transformation.
     */
    USER_INITIATED,

    /**
     * Return the thread to the library's notion of a normal performance intent.
     *
     * <p>This is a <em>reset to the library default</em>, not a restore of the state that existed
     * before the first call. Where the operating system does not offer a reliable round-trip
     * (for example macOS threads start in {@code QOS_CLASS_UNSPECIFIED}, and HotSpot on Windows
     * sets the native priority itself), the resulting state can differ from the pristine one.</p>
     */
    DEFAULT,

    /**
     * Useful background work that must not compete with latency-sensitive threads: batching,
     * CSV persistence, periodic statistics, report generation.
     */
    UTILITY,

    /**
     * Low-priority background work: housekeeping, cache cleanup, non-urgent maintenance,
     * secondary telemetry.
     *
     * <p>On macOS this class additionally throttles I/O and networking of the thread.</p>
     */
    BACKGROUND
}
