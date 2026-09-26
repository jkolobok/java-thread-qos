package com.clericode.threadqos;

import java.util.Objects;

/**
 * Thrown when the platform is supported but a native QoS operation failed, or when the native
 * bindings could not be linked (for example because native access is disabled).
 *
 * <p>The message always states what was attempted, on which operating system, through which native
 * function and with which native error code, for example:</p>
 *
 * <pre>{@code
 * Failed to set thread QoS: os=macOS, qos=USER_INTERACTIVE, nativeFunction=pthread_set_qos_class_self_np, errorCode=1
 * }</pre>
 *
 * <p>Unsupported platforms never throw this exception; they are a silent no-op and report
 * themselves through {@link QosCapabilities}.</p>
 */
public final class ThreadQosException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Operating system on which the failure happened. */
    private final OperatingSystem operatingSystem;
    /** Native function or syscall name, or {@code null}. */
    private final String nativeOperation;
    /** Native error code, or {@code null} when not reported by the native call. */
    private final Integer nativeErrorCode;

    /**
     * Creates an exception without a cause.
     *
     * @param message         full message
     * @param operatingSystem operating system on which the failure happened
     * @param nativeOperation native function or syscall name, or {@code null} if not applicable
     * @param nativeErrorCode errno / {@code GetLastError} / return code, or {@code null} if the
     *                        failure was not reported by the native call itself
     */
    public ThreadQosException(String message, OperatingSystem operatingSystem, String nativeOperation,
                              Integer nativeErrorCode) {
        this(message, operatingSystem, nativeOperation, nativeErrorCode, null);
    }

    /**
     * Creates an exception with a cause.
     *
     * @param message         full message
     * @param operatingSystem operating system on which the failure happened
     * @param nativeOperation native function or syscall name, or {@code null} if not applicable
     * @param nativeErrorCode errno / {@code GetLastError} / return code, or {@code null} if the
     *                        failure was not reported by the native call itself
     * @param cause           underlying exception (kept, never swallowed), or {@code null}
     */
    public ThreadQosException(String message, OperatingSystem operatingSystem, String nativeOperation,
                              Integer nativeErrorCode, Throwable cause) {
        super(message, cause);
        this.operatingSystem = Objects.requireNonNull(operatingSystem, "operatingSystem");
        this.nativeOperation = nativeOperation;
        this.nativeErrorCode = nativeErrorCode;
    }

    /**
     * Builds the standard message for a native call that returned an error code.
     *
     * @param operatingSystem operating system
     * @param qosClass        requested class
     * @param nativeOperation native function or syscall name
     * @param errorCode       errno-style or {@code GetLastError} code
     * @return the exception
     */
    public static ThreadQosException nativeFailure(OperatingSystem operatingSystem, QosClass qosClass,
                                                   String nativeOperation, int errorCode) {
        return nativeFailure(operatingSystem, qosClass, nativeOperation, errorCode, null);
    }

    /**
     * Builds the standard message for a native call that returned an error code, with an extra
     * human-readable detail (for example the errno symbol and its meaning).
     *
     * @param operatingSystem operating system
     * @param qosClass        requested class
     * @param nativeOperation native function or syscall name
     * @param errorCode       errno-style or {@code GetLastError} code
     * @param detail          optional detail appended to the message, or {@code null}
     * @return the exception
     */
    public static ThreadQosException nativeFailure(OperatingSystem operatingSystem, QosClass qosClass,
                                                   String nativeOperation, int errorCode, String detail) {
        StringBuilder sb = new StringBuilder(160)
                .append("Failed to set thread QoS: os=").append(operatingSystem.displayName())
                .append(", qos=").append(qosClass)
                .append(", nativeFunction=").append(nativeOperation)
                .append(", errorCode=").append(errorCode);
        if (detail != null) {
            sb.append(" (").append(detail).append(')');
        }
        return new ThreadQosException(sb.toString(), operatingSystem, nativeOperation, errorCode);
    }

    /**
     * Builds an exception for a request the library refuses to perform on policy grounds, without
     * a native error code (for example a Linux thread with a real-time scheduling policy).
     *
     * @param operatingSystem operating system
     * @param qosClass        requested class
     * @param nativeOperation native function that would have been used
     * @param reason          why the request is refused
     * @return the exception
     */
    public static ThreadQosException policyFailure(OperatingSystem operatingSystem, QosClass qosClass,
                                                   String nativeOperation, String reason) {
        String message = "Failed to set thread QoS: os=" + operatingSystem.displayName()
                + ", qos=" + qosClass
                + ", nativeFunction=" + nativeOperation
                + ", reason=" + reason;
        return new ThreadQosException(message, operatingSystem, nativeOperation, null);
    }

    /**
     * Wraps a linkage or configuration failure ({@code NoSuchElementException} from a symbol
     * lookup, {@code WrongMethodTypeException}, {@code IllegalCallerException} when native access
     * is denied, and similar) into a readable exception, preserving the cause.
     *
     * @param operatingSystem operating system
     * @param nativeOperation symbol or library that failed to link
     * @param cause           underlying exception
     * @param hint            optional extra guidance appended on a new line (for example how to
     *                        enable native access), or {@code null}
     * @return the exception
     */
    public static ThreadQosException linkageFailure(OperatingSystem operatingSystem, String nativeOperation,
                                                    Throwable cause, String hint) {
        Objects.requireNonNull(cause, "cause");
        StringBuilder sb = new StringBuilder(256)
                .append("Failed to link native function for thread QoS: os=").append(operatingSystem.displayName())
                .append(", nativeFunction=").append(nativeOperation)
                .append(", cause=").append(cause.getClass().getName());
        if (cause.getMessage() != null) {
            sb.append(": ").append(cause.getMessage());
        }
        if (hint != null) {
            sb.append(System.lineSeparator()).append(hint);
        }
        return new ThreadQosException(sb.toString(), operatingSystem, nativeOperation, null, cause);
    }

    /**
     * Returns the operating system on which the failure happened.
     *
     * @return the operating system, never {@code null}
     */
    public OperatingSystem getOperatingSystem() {
        return operatingSystem;
    }

    /**
     * Returns the native function or syscall involved.
     *
     * @return the native operation name, or {@code null} if not applicable
     */
    public String getNativeOperation() {
        return nativeOperation;
    }

    /**
     * Returns the native error code reported by the failed call.
     *
     * @return errno / {@code GetLastError} / return code, or {@code null} when the failure was not
     *         reported by the native call itself (linkage error, policy refusal)
     */
    public Integer getNativeErrorCode() {
        return nativeErrorCode;
    }
}
