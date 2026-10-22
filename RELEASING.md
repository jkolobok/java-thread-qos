# Releasing

Releases are cut by maintainers only. Stable consumers use Maven Central; GitHub Releases mirror
the artifacts and carry the changelog. Nothing is published from the regular CI workflow.

## One-time setup (spec §40)

1. **Sonatype Central Portal account.** Register at https://central.sonatype.com/ (sign in with
   GitHub).
2. **Namespace.** Claim `com.clericode`. A reverse-DNS namespace requires DNS verification: the
   Portal shows a verification key, add it as a TXT record on `clericode.com`, then click *Verify*.
   See https://central.sonatype.org/register/namespace/. (A GitHub-based `io.github.jkolobok`
   namespace would verify automatically, but the project deliberately uses the owner's domain.)
3. **Portal user token.** In the Portal: *View Account → Generate User Token*. It yields a
   username/password pair used as the `central` server credentials. Do not use the account
   password.
4. **GPG signing key.**
   ```bash
   gpg --full-generate-key            # RSA 4096 or Ed25519, e-mail = the Maven developer e-mail
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>
   gpg --armor --export-secret-keys <KEYID> > private.asc   # for the GitHub secret; delete afterwards
   ```
   Central verifies signatures against public key servers (keyserver.ubuntu.com, keys.openpgp.org,
   pgp.mit.edu); publish the public key to at least one of them.
5. **GitHub Actions secrets** (repository *Settings → Secrets and variables → Actions*, ideally
   inside an environment named `maven-central` that is restricted to tags `v*`):

   | Secret | Content |
   |---|---|
   | `CENTRAL_USERNAME` | Portal user-token username |
   | `CENTRAL_PASSWORD` | Portal user-token password |
   | `GPG_PRIVATE_KEY` | ASCII-armored private key (`private.asc`) |
   | `GPG_PASSPHRASE` | passphrase of that key |

   Pull requests from forks never receive these secrets; the release workflow runs only on tags
   pushed to this repository.
6. **Local `~/.m2/settings.xml`** for manual releases or dry runs:
   ```xml
   <settings>
     <servers>
       <server>
         <id>central</id>
         <username>${env.CENTRAL_USERNAME}</username>
         <password>${env.CENTRAL_PASSWORD}</password>
       </server>
     </servers>
   </settings>
   ```
7. **Terms and tiers (spec §41).** Before the first production release re-read the current Central
   Publisher terms: Sonatype announced publishing limits / tier changes effective 1 October 2026,
   and commercial artifacts may require Publisher Pro. This project is a community open-source
   library and is expected to stay on the free tier, but verify.
8. **First test release.** Push an RC tag (see below) to exercise every step except the Central
   upload, then cut the corresponding final tag.

## Cutting a release (spec §70)

1. Merge everything into `main`; CI must be green on all four platforms including the
   `uclamp required` and `native-access` jobs.
2. Update `CHANGELOG.md`: move `Unreleased` into a new `## [X.Y.Z] - YYYY-MM-DD` section with
   `Added / Changed / Fixed / Platform changes`. Any change of the per-OS mapping goes under
   *Platform changes* and is at least a MINOR bump (it changes scheduling behavior).
3. Set the version:
   ```bash
   mvn versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false
   ```
   and bump `project.build.outputTimestamp` in `pom.xml` (reproducible build, spec §68).
4. Commit `Release X.Y.Z`, tag and push:
   ```bash
   git tag -a vX.Y.Z -m "thread-qos X.Y.Z"
   git push origin main vX.Y.Z
   ```
5. The `release` workflow (`.github/workflows/release.yml`) then: verifies that the tag matches the
   POM version, checks that the version does not already exist on Central (releases are immutable,
   spec §71), runs `mvn -Prelease deploy` (tests, sources, javadoc, GPG signatures, Central upload
   with `autoPublish`), and creates the GitHub Release with the changelog section, Maven
   coordinates, Java version, `--enable-native-access` notice, supported platforms, Central link,
   the three jars and `SHA256SUMS`.
6. Verify the artifact at https://central.sonatype.com/artifact/com.clericode/thread-qos and,
   after sync, at https://repo1.maven.org/maven2/com/clericode/thread-qos/X.Y.Z/.
7. Set `main` back to the next `-SNAPSHOT` version.

### Release candidates / dry run

A tag like `v0.1.1-rc1` runs the same workflow in **dry-run mode**: builds, tests, signs and
assembles the Central bundle (`mvn -Prelease deploy -DskipUpload=true`), plus a GitHub *pre-release*
with the artifacts, but no Central upload. Use it to validate the bundle and the secrets (GPG)
before the real tag. The publishing plugin must be 0.12.0 or newer with Maven 3.10; older versions
include local repository metadata in the bundle and Central rejects it.

Local dry run without publishing:

```bash
export MAVEN_GPG_PASSPHRASE=...
mvn -Prelease verify                       # signs; leaves *.asc next to the jars in target/
mvn -Prelease -Dgpg.skip verify            # without a key: only sources/javadoc jars
mvn -Prelease deploy -DskipUpload=true    # signs and bundles, without uploading
mvn -Prelease deploy -Dcentral.autoPublish=false   # uploads, stops at "validated" in the Portal
```

## Versioning (spec §44)

Semantic Versioning. `0.x.y` until the API is stable; `1.0.0` is the first stable API. PATCH = bug
fixes without API change, MINOR = backward-compatible features, MAJOR = breaking API or behavior.
A change to the portable QoS mapping that materially alters scheduling behavior is a behavioral
change: document it under *Platform changes* and do not ship it in a PATCH release.

## Never

- Re-publish an existing version (Central releases are immutable): ship `X.Y.Z+1` instead.
- Publish from a pull-request or the regular CI workflow.
- Commit credentials or keys. `*.asc`/`*.gpg` are git-ignored.
