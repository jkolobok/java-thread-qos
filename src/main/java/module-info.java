/**
 * Cross-platform thread Quality of Service controls for the current Java platform thread.
 *
 * <p>The module exports a single public package, {@code com.clericode.threadqos}. All
 * {@code internal} packages are implementation details and are not exported.</p>
 *
 * <p>The library performs restricted {@code java.lang.foreign} operations. Grant native access
 * to this module only:</p>
 *
 * <pre>{@code
 * --enable-native-access=com.clericode.threadqos
 * }</pre>
 */
module com.clericode.threadqos {
    exports com.clericode.threadqos;
}
