# java-thread-qos

> **Status: pre-release (0.x).** The API and the platform mappings may change before 1.0.0; see the
> [CHANGELOG](CHANGELOG.md).

Cross-platform Java thread QoS and performance hints for macOS, Windows and Linux using the
Foreign Function & Memory API (`java.lang.foreign`). No JNI, no JNA, no native binaries.

```java
Thread.ofPlatform()
        .name("market-data")
        .start(ThreadQos.wrap(QosClass.USER_INTERACTIVE, this::marketDataLoop));
```

## Contents

1. [What the library does](#what-the-library-does)
2. [QoS vs thread priority](#qos-vs-thread-priority)
3. [QoS vs CPU affinity](#qos-vs-cpu-affinity)
4. [Supported operating systems](#supported-operating-systems)
5. [Supported architectures](#supported-architectures)
6. [Maven dependency](#maven-dependency)
7. [Enabling native access](#enabling-native-access)
8. [Usage with `Thread.ofPlatform()`](#usage-with-threadofplatform)
9. [Why virtual threads are rejected](#why-virtual-threads-are-rejected)
10. [What each QoS level does on each OS](#what-each-qos-level-does-on-each-os)
11. [What the library does NOT guarantee](#what-the-library-does-not-guarantee)
12. [Checking runtime support](#checking-runtime-support)
13. [Measured effect](#measured-effect)

## What the library does

`ThreadQos.set(QosClass)` tells the operating system how latency-sensitive the **current Java
platform thread** is, using the OS's own scheduler hint API:

| OS | Native mechanism |
|---|---|
| macOS | `pthread_set_qos_class_self_np` (pthread QoS classes) |
| Windows | `SetThreadInformation(ThreadPowerThrottling)` (HighQoS / EcoQoS) + `SetThreadPriority` |
| Linux | `sched_setattr(2)` utilization clamping (`uclamp`) |
| anything else | safe no-op, `isSupported() == false` |

Everything goes through the JDK's Foreign Function & Memory API (Java 25, no preview flags). The
JAR contains no native code and has zero runtime dependencies. The intended users are low-latency
applications, trading and market-data systems, media/realtime-ish workloads and servers that want
to keep background threads from competing with latency-critical ones.

Only the current thread can be changed; there is deliberately no `set(otherThread, …)`.

## QoS vs thread priority

A **priority** answers "who runs first when two runnable threads compete for the same CPU".
A **QoS / performance intent** answers "how much performance does this thread need at all": which
CPU frequency to run at, whether a performance or an efficiency core is appropriate, whether power
throttling is acceptable, and (on macOS) how aggressively to throttle its I/O.

The two are related but not the same, and the operating systems differ:

- **macOS** keeps them separate. QoS is a first-class scheduler concept; the library never touches
  the pthread/Mach priority.
- **Windows** has both. The library's mapping combines a power-throttling state (HighQoS /
  system-managed / EcoQoS) with a `SetThreadPriority` level, because power throttling alone offers
  only three states.
- **Linux** `uclamp` is a pure performance hint; the library does not change `nice` or the
  scheduling policy.

The portable classes are therefore equivalent in *intent*, not in exact scheduler semantics.

## QoS vs CPU affinity

Affinity **pins** a thread to specific CPUs. QoS does not: it influences frequency, core *type*
preference and power management, but the scheduler stays free to move the thread. The library never
changes affinity, CPU sets, cgroups, `isolcpus`, `nohz_full`, interrupt affinity or NUMA placement,
and it does not give real-time guarantees. If you need pinning, use a dedicated tool (for example
Chronicle Thread Affinity on Linux); it composes with this library:

```java
try (AffinityLock ignored = AffinityLock.acquireLock(cpu)) {
    ThreadQos.set(QosClass.USER_INTERACTIVE);
    runLoop();
}
```

There is no compile-time or runtime dependency on Chronicle. On macOS, do not treat any affinity
library as hard pinning; the kernel does not offer it.

## Supported operating systems

| Platform | Mechanism | Status |
|---|---|---|
| macOS 10.10+ (x86_64, arm64) | `pthread_set_qos_class_self_np` | Supported |
| Windows (x86_64, arm64) | `SetThreadInformation` + `SetThreadPriority` | Supported; power throttling needs Windows 10 1709+ ² |
| Linux x86_64 | `sched_setattr` + `uclamp` | Supported if the kernel supports uclamp ¹ |
| Linux aarch64 | `sched_setattr` + `uclamp` | Supported if the kernel supports uclamp ¹ |
| Other OS / other Linux architectures | No-op | Unsupported (`isSupported() == false`, reason in `capabilities()`) |

¹ Requires Linux 5.3+ built with `CONFIG_UCLAMP_TASK` and the `sched_getattr`/`sched_setattr`
syscalls not blocked by seccomp. Even then, uclamp changes CPU frequency only under the `schedutil`
governor and task placement only on CPUs with asymmetric capacity; `util_max` does not reduce a
thread's share of CPU time. See [Linux](#linux).

² On Windows builds without `ThreadPowerThrottling` the priority half still works and
`capabilities().powerThrottlingSupported()` is `false`. See [Windows](#windows).

Minimum runtime: JDK 25. GraalVM native-image is out of scope for 1.x.

## Supported architectures

| OS | Architectures |
|---|---|
| macOS | Apple Silicon (arm64) and Intel (x86_64); the API is the same on both |
| Windows | x86_64 and arm64; only `kernel32.dll` symbols are used |
| Linux | x86_64 and aarch64 only: `sched_setattr`/`sched_getattr` have no glibc wrapper, so the syscall numbers (314/315 and 274/275) are selected per architecture. On riscv64, ppc64le, s390x, 32-bit ARM and x86 the library is a no-op with an explanatory `unsupportedReason`; numbers are never guessed. |

`capabilities().architecture()` reports what was detected (`X86_64`, `AARCH64`, `OTHER`).

## Maven dependency

```xml
<dependency>
    <groupId>com.clericode</groupId>
    <artifactId>thread-qos</artifactId>
    <version>0.1.1</version>
</dependency>
```

## Enabling native access

The library calls *restricted* `java.lang.foreign` methods, so the JVM must be told that this module
may do so. It is a modular JAR, so on the module path you can grant native access to this library
alone:

```text
java --enable-native-access=com.clericode.threadqos ...
```

On the classpath, use:

```text
java --enable-native-access=ALL-UNNAMED ...
```

What happens without the flag depends on `--illegal-native-access` (JEP 472):

| Mode | JDK 25 default? | Behavior |
|---|---|---|
| `warn` | yes | the JVM prints **one** warning per module to `stderr`; the call succeeds |
| `deny` | no (`--illegal-native-access=deny`) | the first call throws; the library wraps it into a `ThreadQosException` whose message shows the exact flag to add |
| `allow` | no | silent |

The library never prints anything itself. It checks `Module.isNativeAccessEnabled()` when it links
its bindings and appends the hint to every linkage error and to `capabilities().unsupportedReason()`
where relevant. Loading the classes does not require native access; nothing is linked until the
first call that needs it.

## Usage with `Thread.ofPlatform()`

The intended pattern is a **dedicated, long-lived platform thread** whose QoS is set once, when it
starts:

```java
Thread marketData = Thread.ofPlatform()
        .name("market-data")
        .start(ThreadQos.wrap(QosClass.USER_INTERACTIVE, this::marketDataLoop));

Thread writer = Thread.ofPlatform()
        .name("csv-writer")
        .start(ThreadQos.wrap(QosClass.UTILITY, this::writerLoop));
```

`wrap()` returns a new `Runnable` (or `Callable`) that calls `ThreadQos.set(qos)` and then the task.
It is strict: if `set()` throws, the task is **not** run and the thread ends with the exception.
That is deliberate: silently running a latency-critical loop without the requested QoS is worse
than failing at startup. If you would rather start the thread anyway, use `trySet()`:

```java
Thread t = Thread.ofPlatform().start(() -> {
    if (!ThreadQos.trySet(QosClass.USER_INTERACTIVE)) {
        // log once, keep going
    }
    loop();
});
```

`trySet()` returns `false` on unsupported platforms and on native errors, but still throws for
programming errors (`null`, virtual thread).

**No implicit restore.** `wrap()` sets the QoS once and never resets it. Do not use it for tasks
submitted to a shared pool: QoS is a property of the native worker thread, not of the task, so a
change made inside one task leaks into the next task on the same worker. For pools, set the QoS in
the thread factory instead:

```java
ThreadFactory factory = task -> Thread.ofPlatform()
        .name("execution-", 0)
        .unstarted(ThreadQos.wrap(QosClass.USER_INTERACTIVE, task));
ExecutorService executor = Executors.newFixedThreadPool(2, factory);
```

**`Thread.setPriority()` interaction.** The library never calls `Thread.setPriority()`. On Windows
HotSpot maps the Java priority to `SetThreadPriority` on start and on every `Thread.setPriority()`
call, so changing the Java priority *after* `ThreadQos.set()` overrides the native priority the
library chose. On Linux and macOS the Java priority is ignored by default and does not interfere.

A runnable example with two threads lives in [`examples/basic`](examples/basic):

```bash
mvn -q -DskipTests install                 # repository root
java --enable-native-access=com.clericode.threadqos -p target/classes \
     examples/basic/src/main/java/com/clericode/threadqos/examples/basic/Example.java
# or: cd examples/basic && mvn compile exec:exec
```

## Why virtual threads are rejected

Every method that changes QoS throws `IllegalStateException` when called on a virtual thread, on
every operating system including unsupported ones:

```text
Thread QoS can only be changed from a Java platform thread
```

A virtual thread runs on a shared **carrier** platform thread. Changing the native QoS from a
virtual thread would actually change the carrier, and the carrier will later run other, unrelated
virtual threads with that QoS. The check happens before any native code and before `wrap()`'s task
runs, so the carrier is never touched.

## What each QoS level does on each OS

| QosClass | macOS | Windows | Linux |
|---|---|---|---|
| `USER_INTERACTIVE` | `QOS_CLASS_USER_INTERACTIVE` | HighQoS + `THREAD_PRIORITY_HIGHEST` | uclamp 1024..1024 |
| `USER_INITIATED` | `QOS_CLASS_USER_INITIATED` | HighQoS + `THREAD_PRIORITY_ABOVE_NORMAL` | uclamp 768..1024 |
| `DEFAULT` | `QOS_CLASS_DEFAULT` | system-managed + `THREAD_PRIORITY_NORMAL` | uclamp 0..1024 |
| `UTILITY` | `QOS_CLASS_UTILITY` | system-managed + `THREAD_PRIORITY_BELOW_NORMAL` | uclamp 0..768 |
| `BACKGROUND` | `QOS_CLASS_BACKGROUND` | EcoQoS + `THREAD_PRIORITY_LOWEST` | uclamp 0..256 |

This is the library's **semantic mapping**; it is not a claim that the three operating systems'
APIs are equivalent 1:1. In particular, the Linux column is a performance hint for frequency
scaling and placement, not a CPU share: on x86 servers with `intel_pstate` or the `performance`
governor the classes may be indistinguishable in practice. Changing this mapping in a future
version is treated as a behavioral change and recorded under "Platform changes" in the
[CHANGELOG](CHANGELOG.md).

`DEFAULT` means "the library's normal intent", not "whatever the thread had before the first call":
the OSes do not offer a reliable round-trip, so the library does not promise one.

### macOS

Mechanism: `pthread_set_qos_class_self_np(QOS_CLASS_*, 0)` from libSystem (macOS 10.10+).
Relative priority is always 0 in this version.

| QosClass | Darwin class |
|---|---|
| `USER_INTERACTIVE` | `QOS_CLASS_USER_INTERACTIVE` |
| `USER_INITIATED` | `QOS_CLASS_USER_INITIATED` |
| `DEFAULT` | `QOS_CLASS_DEFAULT` |
| `UTILITY` | `QOS_CLASS_UTILITY` |
| `BACKGROUND` | `QOS_CLASS_BACKGROUND` |

Things to know:

- **`DEFAULT` is a reset, not a restore.** It sets `QOS_CLASS_DEFAULT`; it does not return the
  thread to `QOS_CLASS_UNSPECIFIED` or to whatever class the thread inherited from its creator.
  In practice a fresh HotSpot platform thread already reports `QOS_CLASS_DEFAULT` via
  `qos_class_self()`, so `DEFAULT` usually is the pristine state, but the library does not
  promise that.
- **`EPERM` after `pthread_setschedparam`.** A thread on which `pthread_setschedparam()` was called
  is permanently opted out of the QoS system (Apple `pthread/qos.h`); every later
  `pthread_set_qos_class_self_np` call fails with `EPERM` and the library throws
  `ThreadQosException` with `errorCode=1`. HotSpot does this only when started with
  `-XX:ThreadPriorityPolicy=1`; the default policy leaves QoS untouched.
- **`BACKGROUND` throttles more than CPU.** Darwin additionally throttles disk I/O and networking
  of background-class threads. Do not use it for a thread that must keep up with a network feed.
- **No hard P-core/E-core affinity.** QoS is a scheduler hint; `USER_INTERACTIVE` makes the
  scheduler prefer performance cores on Apple Silicon but does not pin the thread to them, and
  `BACKGROUND` does not guarantee efficiency cores.
- QoS on macOS is a separate scheduler concept from thread priority; the library never touches
  the Mach/pthread priority.

### Windows

Mechanism: `SetThreadInformation(GetCurrentThread(), ThreadPowerThrottling, …)` followed by
`SetThreadPriority(GetCurrentThread(), …)`, both from `kernel32.dll`. Failures are read through
`GetLastError` captured by the FFM linker right after each call.

| QosClass | Power throttling | `SetThreadPriority` |
|---|---|---|
| `USER_INTERACTIVE` | HighQoS (`ControlMask=EXECUTION_SPEED`, `StateMask=0`) | `THREAD_PRIORITY_HIGHEST` |
| `USER_INITIATED` | HighQoS | `THREAD_PRIORITY_ABOVE_NORMAL` |
| `DEFAULT` | system-managed (`ControlMask=0`) | `THREAD_PRIORITY_NORMAL` |
| `UTILITY` | system-managed | `THREAD_PRIORITY_BELOW_NORMAL` |
| `BACKGROUND` | EcoQoS (`ControlMask=StateMask=EXECUTION_SPEED`) | `THREAD_PRIORITY_LOWEST` |

Never used: `THREAD_PRIORITY_TIME_CRITICAL`, `REALTIME_PRIORITY_CLASS`, `THREAD_MODE_BACKGROUND_BEGIN/END`.

Things to know:

- **Minimum versions.** `SetThreadPriority` works everywhere; `SetThreadInformation` exists since
  Windows 8; the `ThreadPowerThrottling` class needs Windows 10 1709 (build 16299) or later, and the
  EcoQoS scheduling semantics are documented by Microsoft for Windows 11 / Windows 10 21H2+. On a
  build without power throttling the probe reports `powerThrottlingSupported=false` with a reason in
  `unsupportedReason`, and only the priority half is applied; Windows is never a no-op platform.
- **HotSpot already sets the native priority.** On Windows, HotSpot maps the Java priority to
  `SetThreadPriority` when a thread starts and whenever `Thread.setPriority()` is called. So
  `DEFAULT` resets to `THREAD_PRIORITY_NORMAL`, not to whatever the thread had before, and a later
  `Thread.setPriority()` on the same thread overrides what this library set.
- **`HIGHEST` can starve the JVM's own threads.** A busy-spinning thread at
  `THREAD_PRIORITY_HIGHEST` on a saturated CPU can delay GC and JIT compiler threads, which run
  at normal priority. Use `USER_INTERACTIVE` only for a small number of threads on a machine with
  spare cores, or prefer `USER_INITIATED`.
- Power throttling is a hint to the power manager (frequency, efficient cores); it is not CPU
  reservation and not affinity. Use `SetThreadSelectedCpuSets` (out of scope) for placement.

### Linux

Mechanism: utilization clamping (`uclamp`) through `sched_setattr(2)`, invoked as
`syscall(SYS_sched_setattr, …)` because glibc has no wrapper. The library first reads the thread's
`sched_attr` with `sched_getattr`, changes only `sched_util_min` / `sched_util_max`, and writes it
back with `SCHED_FLAG_KEEP_POLICY | SCHED_FLAG_KEEP_PARAMS | SCHED_FLAG_UTIL_CLAMP_MIN |
SCHED_FLAG_UTIL_CLAMP_MAX`. Policy, `nice`, RT priority, deadline parameters, affinity and cgroups
are never touched.

| QosClass | `sched_util_min` | `sched_util_max` |
|---|---|---|
| `USER_INTERACTIVE` | 1024 | 1024 |
| `USER_INITIATED` | 768 | 1024 |
| `DEFAULT` | 0 | 1024 |
| `UTILITY` | 0 | 768 |
| `BACKGROUND` | 0 | 256 |

Syscall numbers are selected once per architecture: x86_64 (314/315) and aarch64 (274/275). On any
other architecture the library is a no-op with `unsupportedReason` set; numbers are never guessed.

Things to know:

- **Requirements.** Linux 5.3+ built with `CONFIG_UCLAMP_TASK`, and `sched_getattr`/`sched_setattr`
  not blocked by seccomp. The provider probes this once (read-only `sched_getattr`) and reports the
  outcome in `capabilities().unsupportedReason()`: `ENOSYS`, `EPERM` (seccomp/security policy), a
  kernel older than 5.3, or `EOPNOTSUPP` on the first `set()` when the kernel lacks
  `CONFIG_UCLAMP_TASK`. Docker Desktop's linuxkit kernel and many cloud/minimal kernels fall into
  the last category; Ubuntu's generic kernels have uclamp enabled.
- **What uclamp actually influences.** It changes the scheduler's *estimate* of a task's
  utilization. That estimate drives CPU frequency selection only under the `schedutil` cpufreq
  governor, and task placement only on CPUs with asymmetric capacity (big.LITTLE, hybrid cores).
  On an x86 server with `intel_pstate` in active mode or the `performance` governor there is
  practically no observable effect, and `BACKGROUND` threads keep competing with
  `USER_INTERACTIVE` threads for CPU time.
- **`util_max` is not a CPU-time cap.** Clamping utilization to 256 does not limit a thread to
  25 % of a CPU or lower its share in CFS/EEVDF; lowering `nice` would, but the library
  deliberately does not do that in the portable API because it cannot be undone without
  `CAP_SYS_NICE` (see the spec's decision D-05; an explicit expert API is a v2 candidate).
- **RT and deadline threads are outside the contract.** If the current thread runs under
  `SCHED_FIFO`, `SCHED_RR`, `SCHED_DEADLINE` or `SCHED_EXT`, `set()` throws `ThreadQosException`
  (no native error code) instead of overriding the kernel's own RT clamp defaults.
- **Containers.** Docker's default seccomp profile allows both syscalls, but hardened profiles or
  gVisor may not; the probe then reports `EPERM` or `ENOSYS`.

## What the library does NOT guarantee

The library:

- calls native operating-system APIs and **changes scheduler behavior** of the calling thread;
- requires `--enable-native-access` (see above);
- must not be used from virtual threads;
- gives **no hard real-time guarantee**: none of the classes is `SCHED_FIFO`, `SCHED_DEADLINE`,
  `REALTIME_PRIORITY_CLASS` or `THREAD_PRIORITY_TIME_CRITICAL`;
- does **not** isolate CPUs, pin threads, change affinity or CPU sets, or manage NUMA placement;
- does **not** block or steer interrupts;
- does **not** manage cgroups or the process priority/class;
- on Linux does **not** change `nice` or the scheduling policy, refuses RT/deadline threads, and
  cannot promise an observable effect of uclamp on every CPU/governor combination;
- on Windows does **not** use background processing mode, and `HIGHEST` can starve GC/JIT threads
  on a saturated machine;
- on macOS does **not** restore `QOS_CLASS_UNSPECIFIED`, and `BACKGROUND` also throttles I/O;
- does not tune the JVM's own GC/compiler threads and does not touch other threads at all;
- does not restore the previous state when a task finishes (`wrap()` sets once).

Out of scope for 1.x: CPU affinity, CPU isolation, NUMA, `isolcpus`/`nohz_full`, RT policies,
Windows CPU Sets, Mach thread affinity, process-wide QoS, a Java agent, JVMTI, GraalVM native-image.

## Checking runtime support

```java
if (!ThreadQos.isSupported()) {
    System.err.println("thread QoS is a no-op here: " + ThreadQos.capabilities().unsupportedReason());
}
```

`QosCapabilities` is an immutable record:

| Field | Meaning |
|---|---|
| `operatingSystem` | `MACOS`, `WINDOWS`, `LINUX`, `OTHER` |
| `architecture` | `X86_64`, `AARCH64`, `OTHER` |
| `qosSupported` | `set()` performs a real native operation |
| `nativeReadbackSupported` | the provider can read the state back (used by the library's own tests) |
| `performanceHintSupported` | the mechanism carries a performance/energy hint (pthread QoS, power throttling, uclamp) |
| `powerThrottlingSupported` | Windows `ThreadPowerThrottling` is available |
| `priorityAdjustmentSupported` | the provider changes the native thread priority (Windows) |
| `unsupportedReason` | `null`, or why something is unavailable: unsupported OS/architecture, kernel without uclamp, seccomp `EPERM`, Windows build without power throttling, native access denied, … |

On Linux and Windows the first call to `isSupported()` / `capabilities()` performs a one-time,
read-only probe (`sched_getattr` / `GetThreadInformation`) and caches the result. Nothing is
logged; this record is the only diagnostic channel.

Errors on supported platforms are never hidden: `set()` throws `ThreadQosException` with the OS,
the requested class, the native function and its error code, for example:

```text
Failed to set thread QoS: os=Linux, qos=USER_INTERACTIVE, nativeFunction=sched_setattr, errorCode=1 (EPERM: ...)
```

## Measured effect

Setting a QoS class costs one native call per thread and changes nothing in Java. What it changes
in the operating system is measurable. [`examples/comparison`](examples/comparison) runs two
scenarios and prints the numbers for the machine it runs on:

1. **Throughput.** The same CPU-bound loop runs on a fresh platform thread once per `QosClass`,
   on an idle machine.
2. **Tail latency under load.** `2 × availableProcessors` "noise" threads spin while one
   "critical" thread times ~0.4 ms chunks of work. Four configurations: no QoS anywhere, only the
   critical thread promoted, only the noise demoted, both.

```bash
mvn -q -DskipTests install                 # repository root
java --enable-native-access=com.clericode.threadqos -p target/classes \
     examples/comparison/src/main/java/com/clericode/threadqos/examples/comparison/Comparison.java
# or: cd examples/comparison && mvn compile exec:exec
```

Scenario 2 on an Apple M-series laptop (8 performance + 4 efficiency cores, macOS 26,
Temurin 25); your numbers will differ, the pattern should not:

```text
2. Duration of a ~0.4 ms work chunk on a critical thread while 24 noise threads spin (lower is better)
configuration                                        p50       p90       p99       max
no QoS at all                                     467 us   1189 us  36776 us  94121 us
critical=USER_INTERACTIVE, noise untouched        489 us   1889 us  64374 us 169598 us
critical untouched, noise=BACKGROUND              507 us    805 us   3857 us  37971 us
critical=USER_INTERACTIVE, noise=BACKGROUND       501 us    569 us    877 us   1959 us
```

What to read from it:

- **On hybrid CPUs the class decides the core type.** In scenario 1 `UTILITY` and `BACKGROUND`
  threads run on efficiency cores and finish the same work about 1.5× later than `DEFAULT` on the
  same idle machine. `DEFAULT`, `USER_INITIATED` and `USER_INTERACTIVE` share the performance
  cores; the difference between them shows up only under contention.
- **Promoting the critical thread alone does not help.** With 24 `DEFAULT` noise threads on
  12 cores, the critical thread's p99 is tens of milliseconds whether or not it is
  `USER_INTERACTIVE`: the scheduler has nothing to preempt in its favour.
- **Demoting the noise is what protects the tail.** Once the noise threads are `BACKGROUND`, the
  critical thread's p99 drops to within a couple of times its p50 and the maximum falls from around
  100 ms to a few milliseconds. Its p50 does not move: QoS is not about the common case.
- **Linux behaves differently.** uclamp changes frequency selection and placement, not the CFS
  share (see [Linux](#linux)); on a homogeneous x86 server with the `performance` governor the
  numbers above come out equal. That is the platform difference the README warns about, now with
  numbers.

The same two effects are asserted by `QosEffectTest` (macOS only, thresholds well below the
values above). It is excluded from `mvn verify` because it takes about a minute and needs an
idle machine:

```bash
mvn -Peffect test                          # prints the measured values to the surefire report
```

## Building and contributing

```bash
mvn verify                       # unit + platform integration tests for this OS, javadoc, jar
mvn -Pbenchmarks verify exec:exec  # JMH benchmarks (not part of the published artifact)
mvn -Peffect test                  # opt-in demonstration that QoS changes scheduling (macOS)
```

See [CONTRIBUTING.md](CONTRIBUTING.md); maintainers see [RELEASING.md](RELEASING.md).

## License

Apache License 2.0. See [LICENSE](LICENSE).
