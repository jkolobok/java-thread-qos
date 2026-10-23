# Validation evidence (2026-10-22)

## Published preview

The first successful public preview is **0.1.1**, commit
`950590341a6eea73fafb1f6be1bf6bb2234b87fb`. Version 0.1.0 was rejected by Central;
the original release ticket is satisfied by its published replacement, without retagging.

- [RC dry run](https://github.com/jkolobok/java-thread-qos/actions/runs/39859919793): success.
- [Final release](https://github.com/jkolobok/java-thread-qos/actions/runs/39860135506): success.
- [GitHub release](https://github.com/jkolobok/java-thread-qos/releases/tag/v0.1.1): final,
  binary/sources/Javadoc JARs, signatures and SHA256SUMS present.
- [Central POM](https://repo.maven.apache.org/maven2/com/clericode/thread-qos/0.1.1/thread-qos-0.1.1.pom)
  and [Central JAR](https://repo.maven.apache.org/maven2/com/clericode/thread-qos/0.1.1/thread-qos-0.1.1.jar)
  downloaded successfully. The basic modular example was compiled with `-Xlint:all -Werror`
  against that downloaded JAR (not `target/classes` or a local installation) and ran on
  macOS arm64: support true, both execution/writer loops made progress, exit 0.

## Native platform coverage

[CI for the published commit](https://github.com/jkolobok/java-thread-qos/actions/runs/39859914180)
completed successfully in all 12 jobs:

| Platform | Build, tests, Javadoc, module | Native-access deny/warn | Required uclamp |
|---|---|---|---|
| macos-latest | success | success / success | n/a |
| windows-latest | success | success / success | n/a |
| ubuntu-24.04 | success | success / success | success |
| ubuntu-24.04-arm | success | not in original matrix | success |

This establishes integration coverage of the published preview, not real-application
performance or the correctness of the new unpushed workflow changes.

## Four-worker synthetic scenario

The [validation example](../examples/validation/README.md) now runs baseline and QoS with
market-data, execution, writer and maintenance threads, checks worker progress and native
errors, and prints bounded measurements. CI is configured to run it on all four platforms
and retain logs and japicmp reports. Those new remote runs have not been performed yet.

Local macOS arm64 run: JDK 25.0.4.1, macOS 26.7.1, 12 logical CPUs, 3 seconds per mode.
All four workers made progress and all four QoS assignments reported support. One observed
run (not a performance guarantee):

| Metric | Baseline | QoS |
|---|---:|---:|
| Market-data operations/s | 452160 | 467325 |
| Execution operations/s | 452055 | 467084 |
| Utility writes/s | 376 | 139 |
| Maintenance operations/s | 751 | 41 |
| Execution p50, microseconds | 544.709 | 634.666 |
| Execution p99, microseconds | 3661.291 | 1501.542 |
| Dropped events | 0 | 0 |

Latency is sampled from the first 200,000 received events. The scenario runs in fixed order,
does not isolate CPUs, and was not run on a controlled idle machine. It demonstrates progress
and an observable difference; it does not establish that a mapping is beneficial for a real
application. A negative run with `--illegal-native-access=deny` failed with the native-access
diagnostic, as intended, instead of emitting a successful QoS report.

Local Docker arm64 was also checked (JDK 25.0.4.1, Linux 7.0.14-linuxkit). The read-only
probe initially reported support, but the first uclamp write returned `EOPNOTSUPP` and
correctly changed capabilities to unsupported (`CONFIG_UCLAMP_TASK` absent). The strict
scenario exited nonzero at that point. Without `--require-supported`, both modes completed
with all four workers making progress and all QoS `applied` flags false. This is coverage of the unsupported-kernel path,
not positive Linux uclamp evidence; the latter is provided by the published CI above.

## Local checks of development changes

Local checks of the development changes passed:

- `mvn -Prelease,api-compat -Dgpg.skip clean verify`: 133 tests passed, 15 platform-specific
  skips, Javadoc/sources artifacts built and no public API changes. This is unsigned local
  packaging, not a new signed release or Central deployment.
- Native-access profiles: deny 3/3, warn 4/4. Benchmark profile compiled and its tests passed;
  no JMH performance run is claimed.
- API negative check: removing `ThreadQos.class` from an isolated candidate JAR made
  japicmp fail with `CLASS_REMOVED`/`METHOD_REMOVED`; the normal candidate passed afterwards.
  Cached baseline and freshly downloaded Central JAR have matching exported API signatures.
- `javac -Xlint:all -Werror` compiled the validation example; source-launcher smoke passed.
- `actionlint` accepted both workflows. Central guard simulation accepted 404 and rejected
  200, 403, 429, 500 and a curl DNS failure. Local Markdown links and `git diff --check` passed.

## Outstanding acceptance evidence

T-9.1 remains open: select a real application and collect its baseline/QoS reports on macOS
Apple Silicon, Windows x64, Linux x86_64 and Linux arm64. Each report needs application
revision/configuration, runtime/kernel, CPU topology, power/governor settings, workload,
duration and repeat count, observed throughput/tail latency/drops, and mapping feedback,
including an absent or negative effect. Synthetic CI and unit tests do not meet this criterion.

T-9.2 has an [API candidate and compatibility check](api-stability.md); final freeze awaits
that feedback. T-9.3 awaits both gates, a 1.0 CHANGELOG/version review, and successful
tagged-commit validation/publication. Nothing here declares 1.0 released.
