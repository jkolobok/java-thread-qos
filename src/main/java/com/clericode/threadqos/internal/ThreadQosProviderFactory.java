package com.clericode.threadqos.internal;

import com.clericode.threadqos.Architecture;
import com.clericode.threadqos.OperatingSystem;
import com.clericode.threadqos.internal.linux.LinuxThreadQosProvider;
import com.clericode.threadqos.internal.macos.MacOsThreadQosProvider;
import com.clericode.threadqos.internal.noop.NoOpThreadQosProvider;
import com.clericode.threadqos.internal.windows.WindowsThreadQosProvider;

import java.util.Locale;
import java.util.Objects;

/**
 * Detects the operating system and architecture once and instantiates the matching provider.
 *
 * <p>Only the provider for the current OS is ever instantiated, so no cross-platform symbol lookup
 * can happen (spec §79). Providers link their native bindings lazily, so creating one performs no
 * native call.</p>
 */
public final class ThreadQosProviderFactory {

    private ThreadQosProviderFactory() {
    }

    /**
     * Creates the provider for the running JVM from {@code os.name} and {@code os.arch}.
     *
     * @return the provider
     */
    public static ThreadQosProvider create() {
        return create(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /**
     * Creates the provider for the given system property values. Exposed for tests.
     *
     * @param osName value of {@code os.name}
     * @param osArch value of {@code os.arch}
     * @return the provider
     */
    public static ThreadQosProvider create(String osName, String osArch) {
        Objects.requireNonNull(osName, "osName");
        Objects.requireNonNull(osArch, "osArch");
        OperatingSystem os = detectOperatingSystem(osName);
        Architecture arch = detectArchitecture(osArch);
        return switch (os) {
            case MACOS -> new MacOsThreadQosProvider(arch);
            case WINDOWS -> new WindowsThreadQosProvider(arch);
            case LINUX -> LinuxThreadQosProvider.create(arch);
            case OTHER -> new NoOpThreadQosProvider(os, arch,
                    "Operating system '" + osName + "' is not supported by java-thread-qos; "
                            + "supported: macOS, Windows, Linux (x86_64, aarch64)");
        };
    }

    /**
     * Maps an {@code os.name} value to an {@link OperatingSystem}.
     *
     * @param osName value of {@code os.name}
     * @return detected family, {@link OperatingSystem#OTHER} if unknown
     */
    public static OperatingSystem detectOperatingSystem(String osName) {
        String name = osName.toLowerCase(Locale.ROOT).trim();
        if (name.startsWith("mac") || name.startsWith("darwin") || name.contains("os x")) {
            return OperatingSystem.MACOS;
        }
        if (name.startsWith("windows")) {
            return OperatingSystem.WINDOWS;
        }
        if (name.equals("linux")) {
            return OperatingSystem.LINUX;
        }
        return OperatingSystem.OTHER;
    }

    /**
     * Maps an {@code os.arch} value to an {@link Architecture}, normalizing the JDK's aliases
     * ({@code amd64}/{@code x86_64}/{@code x64}, {@code aarch64}/{@code arm64}).
     *
     * @param osArch value of {@code os.arch}
     * @return detected architecture, {@link Architecture#OTHER} if unknown
     */
    public static Architecture detectArchitecture(String osArch) {
        String arch = osArch.toLowerCase(Locale.ROOT).trim();
        return switch (arch) {
            case "amd64", "x86_64", "x86-64", "x64" -> Architecture.X86_64;
            case "aarch64", "arm64" -> Architecture.AARCH64;
            default -> Architecture.OTHER;
        };
    }
}
