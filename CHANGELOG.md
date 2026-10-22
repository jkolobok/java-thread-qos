# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Each release additionally
carries a **Platform changes** section: any change to the per-OS mapping of `QosClass` to native
scheduler parameters is recorded there, because such changes alter scheduling behavior even when
the Java API is unchanged.

## [Unreleased]

### Added

### Changed

### Fixed

### Platform changes

## [0.1.1] - 2026-10-22

First public preview. Scope per spec §91: macOS, Windows, Linux x86_64 and Linux aarch64 in one
JAR, portable API, Maven Central publication, CI on all four platforms.

### Added

- `ThreadQos` facade: `isSupported()`, `capabilities()`, `set(QosClass)`, `trySet(QosClass)`,
  `wrap(QosClass, Runnable)`, `wrap(QosClass, Callable)`. Current thread only; platform threads
  only (virtual threads are rejected with `IllegalStateException` on every OS).
- `QosClass` with five portable levels: `USER_INTERACTIVE`, `USER_INITIATED`, `DEFAULT`,
  `UTILITY`, `BACKGROUND`.
- `QosCapabilities` record with `operatingSystem`, `architecture`, `qosSupported`,
  `nativeReadbackSupported`, `performanceHintSupported`, `powerThrottlingSupported`,
  `priorityAdjustmentSupported`, `unsupportedReason`.
- `ThreadQosException` carrying the OS, the native operation and the native error code; linkage
  and native-access failures are wrapped with a hint showing the `--enable-native-access` flag.
- JPMS module `com.clericode.threadqos` exporting a single package; zero runtime
  dependencies; no JNI/JNA/native binaries (FFM only, JDK 25).
- JMH benchmark profile (`-Pbenchmarks`) and `examples/basic`.
- `examples/comparison`: measures throughput per `QosClass` and tail latency of a critical thread
  under load with and without QoS; README section "Measured effect" with numbers from an Apple
  M-series Mac.
- `QosEffectTest` (`mvn -Peffect test`, macOS only): asserts that `BACKGROUND` work runs slower on
  efficiency cores and that demoting noise threads to `BACKGROUND` protects a critical thread's p99.

### Fixed

- Upgrade the Central publishing plugin to 0.12.0 to remove Maven 3.10's local metadata from
  the deployment bundle; RC builds now assemble the bundle with `skipUpload=true`.
- Preserve the missing-native-access diagnostic after a successful restricted call in JDK 25's
  default warning mode.

### Platform changes

- **macOS** (10.10+): `pthread_set_qos_class_self_np(QOS_CLASS_*, 0)`. Mapping
  `USER_INTERACTIVE→0x21`, `USER_INITIATED→0x19`, `DEFAULT→0x15`, `UTILITY→0x11`,
  `BACKGROUND→0x09`. `DEFAULT` sets `QOS_CLASS_DEFAULT`, not `QOS_CLASS_UNSPECIFIED`.
- **Windows** (priority: all versions; power throttling: Windows 10 1709+):
  `SetThreadInformation(ThreadPowerThrottling)` then `SetThreadPriority`.
  `USER_INTERACTIVE` = HighQoS + HIGHEST, `USER_INITIATED` = HighQoS + ABOVE_NORMAL,
  `DEFAULT` = system-managed + NORMAL, `UTILITY` = system-managed + BELOW_NORMAL,
  `BACKGROUND` = EcoQoS + LOWEST. Builds without power throttling keep the priority half.
- **Linux x86_64 / aarch64** (kernel 5.3+ with `CONFIG_UCLAMP_TASK`): `sched_setattr` with
  `SCHED_FLAG_KEEP_POLICY | SCHED_FLAG_KEEP_PARAMS | SCHED_FLAG_UTIL_CLAMP_MIN | SCHED_FLAG_UTIL_CLAMP_MAX`.
  `USER_INTERACTIVE` = 1024..1024, `USER_INITIATED` = 768..1024, `DEFAULT` = 0..1024,
  `UTILITY` = 0..768, `BACKGROUND` = 0..256. Policy, nice, RT priority and deadline parameters are
  preserved; threads under `SCHED_FIFO`/`SCHED_RR`/`SCHED_DEADLINE`/`SCHED_EXT` are refused with
  `ThreadQosException`. Kernels without uclamp, seccomp denial and unknown architectures make the
  library a no-op with `unsupportedReason` set.
- **Other operating systems**: no-op.

## [0.1.0] - 2026-10-22 (not published)

Central rejected the deployment bundle because the publishing plugin included Maven 3.10's
local repository metadata. The original tag is retained; use 0.1.1.

[Unreleased]: https://github.com/jkolobok/java-thread-qos/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/jkolobok/java-thread-qos/releases/tag/v0.1.1
[0.1.0]: https://github.com/jkolobok/java-thread-qos/tree/v0.1.0
