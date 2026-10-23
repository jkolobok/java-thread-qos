# API candidate for 1.0

The compatibility baseline is the published `com.clericode:thread-qos:0.1.1`. Run
`mvn -Papi-compat verify` to compare the current JAR with that artifact using
[japicmp](https://siom79.github.io/japicmp/MavenPlugin.html). Source or binary incompatibility
in the exported package fails the build. Internal packages are excluded; protected members
of public types are included. CI and release validation run this profile. Override
`-Dthreadqos.api.baseline=X.Y.Z` only when deliberately reviewing a new published baseline.
Reports are in `target/japicmp/`. This check covers Java compatibility, not the QoS mapping
or the behavioral contract below.

To audit another candidate JAR, set `-Dthreadqos.api.currentJar=/absolute/path/candidate.jar`.
The negative check with an isolated JAR missing `ThreadQos.class` fails with class/method
removals. Keep the development version different from the baseline: Maven may otherwise
resolve the baseline coordinates to the current project artifact instead of the published JAR.

The candidate retains the 0.1.1 public API with no breaking changes:

- Keep `set`, `isSupported`, `capabilities`, both `wrap` overloads and `trySet`.
  `set` is a no-op when unsupported and throws on native failures; `trySet` returns false
  for unsupported/native failures while retaining null/virtual-thread programming errors.
  A wrapper sets once before execution, propagates failure, and does not restore QoS.
- Defer portable `current()`. Native state need not map uniquely to a class: Linux clamps
  can be arbitrary, Windows exposes two independent mechanisms, and external code can
  modify state. The existing readback capability advertises native support, not a portable
  getter. A future structured native-state API needs its own contract.
- Retain all eight `QosCapabilities` components and their existing meaning. Changing a
  record component changes the canonical constructor and can break consumers. Diagnostics
  are descriptive strings; applications should branch on capability flags, not message text.
- Continue exporting only `com.clericode.threadqos`. Current platform-thread-only and
  current-thread-only restrictions, unsupported behavior, native-access diagnostics and
  platform mappings remain as documented in the public Javadoc and README.

This is a candidate, not a declaration that 1.0 is stable. Final freeze depends on the
real-application reports in T-9.1, review of their feedback, and a CHANGELOG entry for 1.0.
