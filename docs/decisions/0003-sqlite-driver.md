# ADR-0003: Run on the server's bundled sqlite-jdbc, and be ready for it to disappear

* **Status:** accepted
* **Date:** 2026-09-20
* **Milestone:** M0 (exercised from M2)

## Context

The brief says JDBC drivers are fetched at runtime through Paper's plugin loader rather than shaded.
For SQLite that turns out to be impossible, and the reason is worth recording:

* `paper-server/build.gradle.kts` (main and `dev/26.3`) declares
  `runtimeOnly("org.xerial:sqlite-jdbc:3.49.1.0")` — the driver is on the **server** classpath.
* `PaperClasspathBuilder` builds the plugin library loader with the server loader as its parent, and
  `PaperPluginClassLoader.loadClass` delegates to the parent first. So `org.sqlite.*` always
  resolves to Paper's copy; a newer version fetched through `MavenLibraryResolver` is dead weight.
* Relocation is not an escape either: sqlite-jdbc binds JNI symbols to its class names.
* Paper's own build file files those lines under a comment saying they are kept for backwards
  compatibility and are "eventually to be removed".

The practical difference: SQLiteConfig in 3.49.1.0 has no `setWalAutocheckpoint`; that arrived in
3.53.0.0. Compiling against the newest release would produce `NoSuchMethodError` at runtime.

## Decision

1. Compile and unit-test `trace-storage-sqlite` against **exactly 3.49.1.0**, the version Paper
   bundles (`compileOnly` in the plugin, `testImplementation` for tests off-server).
2. Configure SQLite through **SQL pragmas**, never driver-version-specific setters:
   `PRAGMA journal_mode=WAL`, `synchronous=NORMAL`, `wal_autocheckpoint=0`, `query_only=1` for
   readers. This also keeps the code honest about what it sets.
3. At startup, read `select sqlite_version()` and the driver's version, log both, and refuse to
   start on anything below the minimum the storage format needs (`WITHOUT ROWID` needs 3.8.2,
   `VACUUM INTO` needs 3.27.0 — both far below what ships).
4. The plugin loader resolves sqlite-jdbc from Maven Central's mirror **only if the class is absent
   from the parent loader**, so the day Paper drops the bundled driver, Trace keeps working.
5. Any driver Paper does not bundle (PostgreSQL, ClickHouse in later tiers) is fetched through the
   loader using `MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR` — Paper warns when a plugin uses
   Maven Central directly, because that breaks Central's terms of service — and registered
   explicitly, since `DriverManager`'s `ServiceLoader` discovery runs under the server's context
   classloader and never sees a plugin-loaded driver.

## Consequences

* Trace's storage engine is designed against SQLite 3.49.1 features, and the supported-versions note
  in the docs says so.
* A Paper update can change the engine version underneath us. `/trace status` reports the live
  version, and the storage tests run against the pinned one.
* We inherit Paper's driver's JNI behaviour, including the single JEP 472 "restricted method"
  warning that JDK 25 prints once per JVM — usually already triggered by Paper's own JLine loader,
  and harmless: `--illegal-native-access` defaults to `warn` in JDK 25, 26 and 27.

## Alternatives considered

* **A child-first classloader so Trace can ship its own sqlite-jdbc.** Rejected for now: it buys
  newer SQLite at the cost of duplicate native libraries and a classloading trick that has to keep
  working across Paper versions. Revisit only if the storage engine needs a post-3.49 feature.
* **Shade and relocate.** Impossible: JNI symbol names follow the class names.
