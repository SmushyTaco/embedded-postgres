# Embedded Postgres

[![Maven Central](https://img.shields.io/maven-central/v/com.smushytaco/embedded-postgres.svg?label=maven%20central)](https://central.sonatype.com/artifact/com.smushytaco/embedded-postgres)
[![Dokka Docs](https://img.shields.io/badge/docs-dokka-brightgreen.svg)](https://smushytaco.github.io/embedded-postgres)
[![Javadocs](https://javadoc.io/badge2/com.smushytaco/embedded-postgres/javadoc.svg)](https://javadoc.io/doc/com.smushytaco/embedded-postgres)

`embedded-postgres` runs real PostgreSQL binaries directly on the host machine for fast, isolated tests without Docker.

This project is a modern fork of the original [OpenTable](https://github.com/opentable/otj-pg-embedded) / [Zonky](https://github.com/zonkyio/embedded-postgres) embedded PostgreSQL line. It keeps the native-binary approach, targets modern Java, uses `Path`-based APIs, provides JUnit 6 extensions, supports schema preparation via Flyway or Liquibase, and includes more explicit lifecycle and cleanup controls for prepared clusters.

## Why this fork

Compared with older forks, this project focuses on:

- modern Java-first APIs
- `Path` instead of legacy `File`
- full Javadoc coverage
- JUnit 6 support
- native PostgreSQL binaries instead of Docker
- improved prepared-cluster lifecycle management
- better stale-directory cleanup behavior on long-lived JVMs and Windows

## Features

- starts real PostgreSQL binaries on the target platform
- supports direct use through `EmbeddedPostgres`
- supports JUnit 6 extensions for both single-instance and prepared-database workflows
- supports deterministic database preparation through `DatabasePreparer`
- supports `DatabaseConnectionPreparer` for direct JDBC-connection-based setup
- built-in Flyway integration through `FlywayPreparer`
- built-in Liquibase integration through `LiquibasePreparer`
- configurable prepared-cluster retention via `ClusterRetentionPolicy`
- explicit global cleanup through `PreparedDbProvider.closeAll()`
- custom binary resolution via `PgBinaryResolver`
- configurable server settings, locale settings, connection properties, startup timeout, redirects, working directory, data directory, and shutdown hook behavior
- sidecar ownership markers for safer stale-directory cleanup

## Installation

### Gradle

Add the library to your test dependencies.

```kotlin
dependencies {
    testImplementation(libs.embeddedPostgres)
}
```

`gradle/libs.versions.toml`:

```toml
[versions]
# Check this on https://central.sonatype.com/artifact/com.smushytaco/embedded-postgres/
embeddedPostgres = "4.0.0"

[libraries]
embeddedPostgres = { group = "com.smushytaco", name = "embedded-postgres", version.ref = "embeddedPostgres" }
```

Check Maven Central for the latest version.

## PostgreSQL binaries

By default, the library uses Zonky's embedded PostgreSQL binary artifacts and resolves them through `DefaultPostgresBinaryResolver`.

The default embedded PostgreSQL binary version can be changed independently of the library version by importing the binaries BOM.

### Gradle BOM example

```kotlin
dependencies {
    testImplementation(enforcedPlatform(libs.postgresql))
}
```

`gradle/libs.versions.toml`:

```toml
[versions]
# Check this on https://central.sonatype.com/artifact/io.zonky.test.postgres/embedded-postgres-binaries-bom/
postgresql = "18.3.0"

[libraries]
postgresql = { group = "io.zonky.test.postgres", name = "embedded-postgres-binaries-bom", version.ref = "postgresql" }
```

Available binary versions:

- BOM: <https://central.sonatype.com/artifact/io.zonky.test.postgres/embedded-postgres-binaries-bom>
- Binary artifacts namespace: <https://central.sonatype.com/namespace/io.zonky.test.postgres>

## Additional architectures

By default, only the common architecture dependency is typically present in a project. Additional binary artifacts can be added explicitly as needed.

Example:

```kotlin
dependencies {
    testImplementation(libs.embeddedPostgresBinariesLinuxI386)
}
```

`gradle/libs.versions.toml`:

```toml
[libraries]
embeddedPostgresBinariesLinuxI386 = { group = "io.zonky.test.postgres", name = "embedded-postgres-binaries-linux-i386" }
```

Typical supported platforms and architectures are provided by the binary artifacts, not by this library alone. Check the binary artifact namespace for the exact list available for a given PostgreSQL version.

## Basic usage

### Direct usage

Use `EmbeddedPostgres` directly when you want full control over startup and shutdown.

```java
try (EmbeddedPostgres pg = EmbeddedPostgres.start();
     Connection connection = pg.getPostgresDatabase().getConnection();
     Statement statement = connection.createStatement();
     ResultSet resultSet = statement.executeQuery("SELECT 1")) {
    resultSet.next();
    System.out.println(resultSet.getInt(1));
}
```

Useful methods include:

- `EmbeddedPostgres.start()`
- `EmbeddedPostgres.builder()`
- `getPostgresDatabase()`
- `getTemplateDatabase()`
- `getDatabase(user, dbName)`
- `getJdbcUrl(user, dbName)`
- `getPort()`

## Builder customization

`EmbeddedPostgres.builder()` supports low-level customization before startup.

Examples include:

```java
EmbeddedPostgres pg = EmbeddedPostgres.builder()
        .setPort(5433)
        .setPGStartupWait(Duration.ofSeconds(20))
        .setServerConfig("max_connections", "100")
        .setLocaleConfig("locale", "en_US.UTF-8")
        .setConnectConfig("connectTimeout", "20")
        .setRegisterShutdownHook(true)
        .start();
```

Available builder options include:

- `setPGStartupWait(Duration)`
- `setCleanDataDirectory(boolean)`
- `setRegisterShutdownHook(boolean)`
- `setDataDirectory(Path)`
- `setServerConfig(String, String)`
- `setLocaleConfig(String, String)`
- `setConnectConfig(String, String)`
- `setOverrideWorkingDirectory(Path)`
- `setPort(int)`
- `setErrorRedirector(ProcessBuilder.Redirect)`
- `setOutputRedirector(ProcessBuilder.Redirect)`
- `setPgBinaryResolver(PgBinaryResolver)`
- `setDataDirectoryCustomizer(Consumer<Path>)`

## JUnit 6 support

The library ships with two JUnit 6 extensions.

### Single instance extension

Use this when you want an `EmbeddedPostgres` instance managed for you.

```java
@RegisterExtension
final SingleInstancePostgresExtension pg = EmbeddedPostgresExtension.singleInstance();
```

Then access the running server with:

```java
DataSource dataSource = pg.getEmbeddedPostgres().getPostgresDatabase();
```

You can also customize the underlying builder:

```java
@RegisterExtension
final SingleInstancePostgresExtension pg = EmbeddedPostgresExtension.singleInstance()
        .customize(builder -> builder.setPGStartupWait(Duration.ofSeconds(20)));
```

Behavior depends on how the extension is registered:

- `static @RegisterExtension`: one embedded PostgreSQL instance per test class
- non-static `@RegisterExtension`: one embedded PostgreSQL instance per test method

### Prepared database extension

Use this when each test should get its own fresh database cloned from a prepared template cluster.

```java
@RegisterExtension
final PreparedDbExtension db = EmbeddedPostgresExtension.preparedDatabase(_ -> {});
```

Access helpers:

- `getTestDatabase()` returns the current test `DataSource`
- `getConnectionInfo()` returns database name, port, user, and connection properties
- `getDbProvider()` returns the underlying `PreparedDbProvider`

Example:

```java
@RegisterExtension
final PreparedDbExtension db = EmbeddedPostgresExtension.preparedDatabase(connection -> {
    try (PreparedStatement statement = connection.prepareStatement("CREATE TABLE sample (id int)")) {
        statement.execute();
    }
});
```

You can customize the builder and the cluster retention policy:

```java
@RegisterExtension
final PreparedDbExtension db = EmbeddedPostgresExtension.preparedDatabase(_ -> {})
        .customize(builder -> builder.setPGStartupWait(Duration.ofSeconds(20)))
        .setClusterRetentionPolicy(ClusterRetentionPolicy.CLOSE_ON_LAST_RELEASE);
```

Behavior depends on registration style:

- `static @RegisterExtension`: one prepared state per test class
- non-static `@RegisterExtension`: one prepared state per test method

## Database preparers

### `DatabasePreparer`

Implement `DatabasePreparer` when you want to prepare a database through a `DataSource`.

```java
DatabasePreparer preparer = dataSource -> {
    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement()) {
        statement.execute("CREATE TABLE sample (id int)");
    }
};
```

Preparation must be deterministic because prepared clusters may be cached and reused based on equality semantics.

### `DatabaseConnectionPreparer`

Implement `DatabaseConnectionPreparer` when you prefer direct access to a JDBC `Connection`.

```java
DatabaseConnectionPreparer preparer = connection -> {
    try (Statement statement = connection.createStatement()) {
        statement.execute("CREATE TABLE sample (id int)");
    }
};
```

## Prepared database provider

`PreparedDbProvider` is the low-level API behind `PreparedDbExtension`.

It can be used directly when you want prepared template clusters without JUnit.

```java
PreparedDbProvider provider = PreparedDbProvider.forPreparer(connection -> {
    try (Statement statement = connection.createStatement()) {
        statement.execute("CREATE TABLE sample (id int)");
    }
});

try {
    ConnectionInfo info = provider.createNewDatabase();
    DataSource dataSource = provider.createDataSourceFromConnectionInfo(info);
    // use the database
} finally {
    provider.close();
}
```

Key methods:

- `forPreparer(preparer)`
- `forPreparer(preparer, retentionPolicy)`
- `forPreparer(preparer, customizers)`
- `forPreparer(preparer, customizers, retentionPolicy)`
- `createDatabase()`
- `createNewDatabase()`
- `createDataSource()`
- `createDataSourceFromConnectionInfo(...)`
- `getConfigurationTweak(...)`
- `close()`
- `closeAll()`

### Cluster retention policy

Prepared clusters use explicit lifecycle control.

`ClusterRetentionPolicy` supports:

- `CLOSE_ON_LAST_RELEASE` — close the prepared cluster as soon as the last provider handle is released
- `KEEP_UNTIL_CLOSE_ALL` — keep prepared clusters cached until `PreparedDbProvider.closeAll()` is called

The default retention policy is `KEEP_UNTIL_CLOSE_ALL`.

This is useful when you want to trade memory / process lifetime for faster repeated prepared-database creation.

If you are running tests in a long-lived JVM or custom runner, call `PreparedDbProvider.closeAll()` at the end of the run to explicitly tear down retained prepared clusters.

## Flyway integration

Use `FlywayPreparer` to prepare a template cluster from Flyway migrations.

### Classpath locations

```java
@RegisterExtension
final PreparedDbExtension db = EmbeddedPostgresExtension.preparedDatabase(
        FlywayPreparer.forClasspathLocation("db/migration")
);
```

### Custom Flyway configuration

```java
Map<String, String> configuration = Map.of(
        "flyway.locations", "classpath:db/migration"
);

PreparedDbProvider provider = PreparedDbProvider.forPreparer(
        FlywayPreparer.fromConfiguration(configuration)
);
```

## Liquibase integration

Use `LiquibasePreparer` to prepare a template cluster from Liquibase changelogs.

### Classpath changelog

```java
@RegisterExtension
final PreparedDbExtension db = EmbeddedPostgresExtension.preparedDatabase(
        LiquibasePreparer.forClasspathLocation("liqui/master.xml")
);
```

### Classpath changelog with contexts

```java
@RegisterExtension
final PreparedDbExtension db = EmbeddedPostgresExtension.preparedDatabase(
        LiquibasePreparer.forClasspathLocation("liqui/master-test.xml", new Contexts("test"))
);
```

### File-based changelog

```java
PreparedDbProvider provider = PreparedDbProvider.forPreparer(
        LiquibasePreparer.forFile(Path.of("src/test/resources/liqui/master.xml"))
);
```

## Binary resolution

If the default binary resolution is not enough, provide your own `PgBinaryResolver`.

```java
EmbeddedPostgres pg = EmbeddedPostgres.builder()
        .setPgBinaryResolver((system, architecture) -> {
            // return an InputStream for the correct archive
            throw new UnsupportedOperationException();
        })
        .start();
```

`DefaultPostgresBinaryResolver` resolves binaries from the classpath using operating system, architecture, and Linux distribution information.

## Cleanup and working directories

The library keeps extracted PostgreSQL binaries under a working directory and manages data directories separately.

- default working directory: `${java.io.tmpdir}/embedded-pg`
- override with system property: `ot.epg.working-dir`
- data-directory cleanup is controlled by `setCleanDataDirectory(boolean)`

The library also uses sidecar ownership marker files to safely identify stale embedded-postgres data directories and clean them up on later startups.

## Troubleshooting

### `initdb` or server startup fails

If startup fails with an `initdb` or `pg_ctl` error:

- verify that the correct binary artifacts for your platform are on the classpath
- verify that the working directory is writable
- verify that the data directory or its parent is writable
- on Linux containers, verify the user is not root unless your environment explicitly supports it
- if needed, set locale values with `setLocaleConfig(...)`

### Windows

On Windows, the PostgreSQL binaries may require the Microsoft Visual C++ 2013 Redistributable Package.

### Docker and containers

This library runs native PostgreSQL binaries and does not wrap PostgreSQL in Docker itself.

When running tests inside a container:

- prefer a non-root user
- ensure the container filesystem is writable
- ensure required locales are available
- on Linux, `unshare` support is detected automatically when applicable

### Long-lived JVMs

If your test runner keeps the JVM alive between runs, retained prepared clusters may also remain alive until explicitly closed. In those environments, call:

```java
PreparedDbProvider.closeAll();
```

at end-of-run cleanup.

## API summary

Main embedded API:

- `EmbeddedPostgres`
- `EmbeddedPostgres.Builder`
- `PgBinaryResolver`
- `DefaultPostgresBinaryResolver`
- `ConnectionInfo`

Preparation API:

- `DatabasePreparer`
- `DatabaseConnectionPreparer`
- `PreparedDbProvider`
- `ClusterRetentionPolicy`
- `DbInfo`
- `FlywayPreparer`
- `LiquibasePreparer`

JUnit 6 API:

- `EmbeddedPostgresExtension`
- `SingleInstancePostgresExtension`
- `PreparedDbExtension`

## License

Apache 2.0 — see the [LICENSE](LICENSE) file for details.
