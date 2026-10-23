# Four-worker validation

Run from the repository root with JDK 25:

```bash
mvn -Papi-compat verify
java --enable-native-access=com.clericode.threadqos \
  --module-path target/classes --add-modules com.clericode.threadqos \
  examples/validation/Validation.java 10 --require-supported
```

The duration (1–300 seconds) applies to each of two runs: untouched baseline, then QoS.
The four dedicated platform threads model a market-data busy loop (`USER_INTERACTIVE`),
an execution event queue (`USER_INITIATED`), a file writer (`UTILITY`), and periodic
maintenance (`BACKGROUND`). Each run uses new threads; the writer uses a temporary file
that is deleted on exit. Startup and shutdown have time limits. Native errors and worker
failures cause a nonzero exit; a successful report requires progress from all four workers.
With `--require-supported`, unsupported/no-op execution is also a failure, including a
Linux kernel that rejects uclamp only on the first write.

Output records runtime, OS, architecture, capabilities, operations/second, file bytes,
event drops and execution queue latency p50/p99. Latency covers the first 200,000 received
events, not necessarily the whole run. No effect threshold is imposed: uclamp does not
promise a CPU share, and a busy shared runner is unsuitable for performance assertions.
Results show application-observed behavior, not native readback (the integration tests
check that separately). Run longer, repeat in both orders, record CPU/governor/power mode,
and compare the same machine under the same load before drawing performance conclusions.

CI runs the short scenario on all four supported runner platforms and uploads
`validation-<runner>` artifacts with the workload log and japicmp reports. These are
synthetic smoke reports. T-9.1 still requires integration and feedback from a real
application on all four platforms; see [the validation report](../../docs/validation.md).
