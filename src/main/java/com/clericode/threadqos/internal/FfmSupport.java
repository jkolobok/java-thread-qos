package com.clericode.threadqos.internal;

import com.clericode.threadqos.OperatingSystem;
import com.clericode.threadqos.ThreadQosException;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.lang.invoke.WrongMethodTypeException;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Shared FFM plumbing: the process-wide {@link Linker}, downcall creation with readable error
 * wrapping, native-access diagnostics and the captured-call-state layout.
 *
 * <h2>Captured call state</h2>
 *
 * <p>{@code errno} / {@code GetLastError} are read through {@link Linker.Option#captureCallState}.
 * The segment that receives the captured state <strong>must be allocated per call</strong> in a
 * confined arena (see {@link #captureStateLayout()}). A static shared segment is forbidden:
 * concurrent {@code set()} calls from different threads would overwrite each other's error
 * codes.</p>
 */
public final class FfmSupport {

    /** The JPMS module name used in the native-access hint. */
    public static final String MODULE_NAME = "com.clericode.threadqos";

    // Capture before any restricted call: JDK 25's warn mode enables native access on the
    // first such call to suppress subsequent warnings. Reading the live flag afterwards
    // would lose the diagnostic explaining the missing --enable-native-access option.
    private static final boolean NATIVE_ACCESS_ENABLED_AT_INITIALIZATION =
            FfmSupport.class.getModule().isNativeAccessEnabled();

    /** The single native linker for the process. */
    public static final Linker LINKER = Linker.nativeLinker();

    private FfmSupport() {
    }

    /**
     * Returns the linker's default lookup (libc / libSystem on POSIX, the C runtime on Windows).
     *
     * @return the default symbol lookup
     */
    public static SymbolLookup defaultLookup() {
        return LINKER.defaultLookup();
    }

    /**
     * Loads a system library with the global arena, wrapping failures.
     *
     * @param os          operating system, for the error message
     * @param libraryName library name as understood by the platform loader
     * @return the lookup
     * @throws ThreadQosException if the library cannot be loaded or native access is denied
     */
    @SuppressWarnings("restricted") // the whole point of this library; guarded by native-access diagnostics
    public static SymbolLookup libraryLookup(OperatingSystem os, String libraryName) {
        try {
            return SymbolLookup.libraryLookup(libraryName, Arena.global());
        } catch (IllegalCallerException | IllegalArgumentException e) {
            throw linkageFailure(os, libraryName, e);
        }
    }

    /**
     * Creates a downcall handle for {@code symbol}, wrapping every expected linkage failure into a
     * {@link ThreadQosException} (spec §77) and appending the native-access hint when relevant.
     *
     * @param os         operating system, for the error message
     * @param lookup     where to find the symbol
     * @param symbol     symbol name
     * @param descriptor function descriptor
     * @param options    linker options
     * @return the method handle
     * @throws ThreadQosException if the symbol is missing, the descriptor is rejected, or native
     *                            access is denied
     */
    @SuppressWarnings("restricted") // the whole point of this library; guarded by native-access diagnostics
    public static MethodHandle downcall(OperatingSystem os, SymbolLookup lookup, String symbol,
                                        FunctionDescriptor descriptor, Linker.Option... options) {
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(symbol, "symbol");
        try {
            MemorySegment address = lookup.find(symbol)
                    .orElseThrow(() -> new NoSuchElementException("Symbol not found: " + symbol));
            return LINKER.downcallHandle(address, descriptor, options);
        } catch (IllegalCallerException | NoSuchElementException | IllegalArgumentException
                 | UnsupportedOperationException | WrongMethodTypeException e) {
            throw linkageFailure(os, symbol, e);
        }
    }

    /**
     * Wraps a linkage failure with the native-access hint (if native access is not enabled).
     *
     * @param os              operating system
     * @param nativeOperation symbol or library name
     * @param cause           underlying exception
     * @return the exception to throw
     */
    public static ThreadQosException linkageFailure(OperatingSystem os, String nativeOperation, Throwable cause) {
        return ThreadQosException.linkageFailure(os, nativeOperation, cause, nativeAccessHint());
    }

    /**
     * Converts an arbitrary throwable escaping {@code MethodHandle.invokeExact} into a
     * {@link ThreadQosException}; a {@link ThreadQosException} or an {@link Error} is rethrown as is.
     *
     * @param os              operating system
     * @param nativeOperation native function name
     * @param t               the throwable
     * @return the exception to throw
     */
    public static RuntimeException invocationFailure(OperatingSystem os, String nativeOperation, Throwable t) {
        if (t instanceof ThreadQosException tqe) {
            return tqe;
        }
        if (t instanceof Error error) {
            throw error;
        }
        return linkageFailure(os, nativeOperation, t);
    }

    /**
     * Whether the library's module had native access when this class was initialized
     * (spec §31, D-08). The value is captured before linking: JDK 25's warn mode enables
     * access after the first restricted call, which must not erase the missing-flag diagnostic.
     *
     * @return {@code true} if native access was enabled at initialization
     */
    public static boolean isNativeAccessEnabled() {
        return NATIVE_ACCESS_ENABLED_AT_INITIALIZATION;
    }

    /**
     * Returns the guidance text to append to error messages when native access was not enabled
     * at initialization, or {@code null} when it was enabled.
     *
     * @return the hint or {@code null}
     */
    public static String nativeAccessHint() {
        if (isNativeAccessEnabled()) {
            return null;
        }
        return nativeAccessHintText(FfmSupport.class.getModule());
    }

    /**
     * Formats the native-access hint for the given module (named or unnamed).
     *
     * @param module the module that needs native access
     * @return the hint text
     */
    public static String nativeAccessHintText(Module module) {
        String target = module.isNamed() ? module.getName() : "ALL-UNNAMED";
        String nl = System.lineSeparator();
        return "Native access is not enabled for module " + target + " (java-thread-qos)." + nl
                + "On JDK 25 this produces a one-time JVM warning; with --illegal-native-access=deny it fails." + nl
                + "Run the JVM with:" + nl
                + "  --enable-native-access=" + target + nl
                + "For classpath usage:" + nl
                + "  --enable-native-access=ALL-UNNAMED";
    }

    /**
     * Lazily linked, immutable native bindings.
     *
     * <p>Why not the class-initialization holder idiom: if linking fails (native access denied,
     * symbol missing) a static initializer surfaces as {@code ExceptionInInitializerError} and every
     * later access as a bare {@code NoClassDefFoundError}, which violates the error-message
     * requirement (spec §77). This holder instead caches the {@link ThreadQosException} and rethrows
     * the same readable exception on every call. The fast path is a single volatile read; no lock is
     * taken after successful linkage (spec §27).</p>
     *
     * @param <T> the immutable bindings type (typically a record of {@link MethodHandle}s)
     */
    public static final class Lazy<T> {

        private final Supplier<T> linker;
        private volatile T value;
        private volatile ThreadQosException failure;

        /**
         * Creates the holder; nothing is linked until {@link #get()}.
         *
         * @param linker performs the linkage; may throw {@link ThreadQosException}
         */
        public Lazy(Supplier<T> linker) {
            this.linker = Objects.requireNonNull(linker, "linker");
        }

        /**
         * Returns the bindings, linking them on first use.
         *
         * @return the bindings
         * @throws ThreadQosException if linkage failed (now or on an earlier attempt)
         */
        public T get() {
            T v = value;
            if (v != null) {
                return v;
            }
            return linkSlowPath();
        }

        /**
         * Whether linkage has already been attempted successfully.
         *
         * @return {@code true} if {@link #get()} will not link
         */
        public boolean isLinked() {
            return value != null;
        }

        private synchronized T linkSlowPath() {
            T v = value;
            if (v != null) {
                return v;
            }
            ThreadQosException f = failure;
            if (f != null) {
                throw f;
            }
            try {
                v = Objects.requireNonNull(linker.get(), "linker returned null");
                value = v;
                return v;
            } catch (ThreadQosException e) {
                failure = e;
                throw e;
            }
        }
    }

    /**
     * The layout of the captured call state for this platform. Allocate one instance of it per
     * native call in a confined arena; never share a static segment between threads.
     *
     * @return the layout
     */
    public static StructLayout captureStateLayout() {
        return Linker.Option.captureStateLayout();
    }

    /**
     * A {@link VarHandle} reading one captured state field ({@code "errno"} on POSIX,
     * {@code "GetLastError"} on Windows) from a segment allocated with {@link #captureStateLayout()}.
     *
     * @param name field name
     * @return the var handle taking {@code (MemorySegment, long baseOffset)} and returning {@code int}
     */
    public static VarHandle captureStateHandle(String name) {
        return captureStateLayout().varHandle(MemoryLayout.PathElement.groupElement(name));
    }
}
