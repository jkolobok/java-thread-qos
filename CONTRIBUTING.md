# Contributing

Thanks for your interest in java-thread-qos.

## Requirements

- JDK 25 or newer (the library uses the stable `java.lang.foreign` API; no preview flags).
- Maven 3.9+.
- No native toolchain is needed: the project is pure Java and must stay that way (no JNI, JNA,
  JNR, JavaCPP or bundled `.so`/`.dylib`/`.dll`).

## Build

```bash
mvn verify
```

This compiles, runs unit tests and platform integration tests for the current OS, and builds
Javadoc. Tests run with `--enable-native-access=com.clericode.threadqos`.

## Platform integration tests

Tests that call native APIs are enabled per OS with JUnit `@EnabledOnOs`. On Linux they are
skipped with an explicit reason when the kernel has no `uclamp` support; pass
`-Dthreadqos.requireSupported=true` to turn such skips into failures (used by CI).

## ABI-drift tests

The native constants (`QOS_CLASS_*`, `THREAD_POWER_THROTTLING_*`, `THREAD_PRIORITY_*`,
`SCHED_FLAG_*`, syscall numbers, struct layouts) are transcribed from the authoritative headers and
must never be "fixed" from memory or a blog post. Two layers guard against drift without adding a
C toolchain (spec §76):

- **Layout tests** (run everywhere): `ThreadPowerThrottlingStateTest`, `LinuxSchedAttrTest` check
  size, alignment, offsets and integer widths of every struct; `*QosNativeTest` /
  `WindowsQosStateTest` / `LinuxSyscallsAndClampTest` pin the constant values.
- **Read-back tests** (tagged `@Tag("abi")`, run on the matching OS): after `set()` the state is read
  back through the OS (`qos_class_self()`, `GetThreadInformation` + `GetThreadPriority`,
  `sched_getattr`) and compared with the constants we wrote. If a constant were wrong, the OS would
  either reject the call or hand back a different value.

If you change a constant, cite the header/document and the version in the code comment next to it.

## Style

- Plain Java style; `final` where useful, no wildcard imports.
- Every native constant is named and carries a comment pointing to the authoritative header or
  documentation (Apple `pthread/qos.h`, Windows SDK `processthreadsapi.h`, Linux UAPI
  `include/uapi/linux/sched.h`).
- No magic offsets: struct layouts are explicit `MemoryLayout`s with tests on size and offsets.
- Public API is documented; Javadoc must build without warnings.

## Releases

Releases are cut by maintainers only. See [RELEASING.md](RELEASING.md).
