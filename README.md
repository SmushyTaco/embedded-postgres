# Embedded Postgres
[![Maven Central](https://img.shields.io/maven-central/v/com.smushytaco/embedded-postgres.svg?label=maven%20central)](https://central.sonatype.com/artifact/com.smushytaco/embedded-postgres)
[![Dokka Docs](https://img.shields.io/badge/docs-dokka-brightgreen.svg)](https://smushytaco.github.io/embedded-postgres)
[![Javadocs](https://javadoc.io/badge2/com.smushytaco/embedded-postgres/javadoc.svg)](https://javadoc.io/doc/com.smushytaco/embedded-postgres)

## Introduction

This project is a fork of [Zonkyio Embedded PostgreSQL](https://github.com/zonkyio/embedded-postgres) which is a fork of [OpenTable Embedded PostgreSQL Component](https://github.com/opentable/otj-pg-embedded) created back in 2018. The original
project continues, but with a very different philosophy - wrapping the postgres instance in a docker container.
Whereas this project follows the original approach of using native postgres binaries running directly on the target platform without the overhead of virtualization.

This fork also differs from the Zonkyio fork in the sense that it depends on the latest LTS version of Java, migrates from the legacy `File` to the modern `Path`, has full javadoc coverage, and drops legacy JUnit 4 support. Think of this as a comprehensive modernization.

The library allows embedding PostgreSQL into Java application code with no external dependencies.
Excellent for allowing you to unit test with a "real" Postgres without requiring end users to install and set up a database cluster.

## Features

* All features of `com.opentable:otj-pg-embedded:0.13.3`
* Configurable version of [PostgreSQL binaries](https://github.com/zonkyio/embedded-postgres-binaries)
* PostgreSQL 11+ support even for Linux platform
* Support for running inside Docker, including Alpine Linux

## Gradle Configuration


To use this with Gradle, add the following to your `build.gradle.kts`:
```kotlin
val embeddedPostgresVersion = providers.gradleProperty("embedded_postgres_version")
dependencies {
    testImplementation("com.smushytaco:embedded-postgres:${embeddedPostgresVersion.get()}")
}
```
And the following to your `gradle.properties`:
```properties
# Check this on https://central.sonatype.com/artifact/com.smushytaco/embedded-postgres/
embedded_postgres_version = 3.0.0
```

The default version of the embedded postgres is `PostgreSQL 18.0.0`, but you can change it by following the instructions described in [Postgres version](#postgres-version).

## Basic Usage

In your JUnit test just add:

```java
@RegisterExtension
SingleInstancePostgresExtension pg = EmbeddedPostgresExtension.singleInstance();
```

This simply has JUnit manage an instance of EmbeddedPostgres (start, stop). You can then use this to get a DataSource with: `pg.getEmbeddedPostgres().getPostgresDatabase();`  

Additionally, you may use the [`EmbeddedPostgres`](src/main/java/com/smushytaco/postgres/embedded/EmbeddedPostgres.java) class directly by manually starting and stopping the instance; see [`EmbeddedPostgresTest`](src/test/java/com/smushytaco/postgres/embedded/EmbeddedPostgresTest.java) for an example.

Default username/password is: postgres/postgres and the default database is 'postgres'

## Migrators (Flyway or Liquibase)

You can easily integrate Flyway or Liquibase database schema migration:
##### Flyway
```java
@RegisterExtension
PreparedDbExtension db = EmbeddedPostgresExtension.preparedDatabase(FlywayPreparer.forClasspathLocation("db/my-db-schema"));
```

##### Liquibase
```java
@RegisterExtension
PreparedDbExtension db = EmbeddedPostgresExtension.preparedDatabase(LiquibasePreparer.forClasspathLocation("liqui/master.xml"));
```

This will create an independent database for every test with the given schema loaded from the classpath.
Database templates are used so the time cost is relatively small, given the superior isolation truly
independent databases gives you.

## Postgres version

The default version of the embedded postgres is `PostgreSQL 18.0.0`, but it can be changed by importing `embedded-postgres-binaries-bom`.

Add the following to your `build.gradle.kts`;
```kotlin
val postgresqlVersion = providers.gradleProperty("postgresql_version")
dependencies {
    testImplementation(enforcedPlatform("io.zonky.test.postgres:embedded-postgres-binaries-bom:${postgresqlVersion.get()}"))
}
```

And the following to your `gradle.properties`:
```properties
# Check this on https://central.sonatype.com/artifact/io.zonky.test.postgres/embedded-postgres-binaries-bom/
postgresql_version = 18.0.0
```

A list of all available versions of postgres binaries can be found [here](https://central.sonatype.com/artifact/io.zonky.test.postgres/embedded-postgres-binaries-bom/).

Note that the release cycle of the postgres binaries is independent of the release cycle of this library, so you can upgrade to a new version of postgres binaries immediately after it is released.

## Additional architectures

By default, only the support for `amd64` architecture is enabled.
Support for other architectures can be enabled by adding the corresponding Maven dependencies as shown in the example below:

```kotlin
dependencies {
    testImplementation("io.zonky.test.postgres:embedded-postgres-binaries-linux-i386")
}
```

**Supported platforms:** `Darwin`, `Windows`, `Linux`, `Alpine Linux`  
**Supported architectures:** `amd64`, `i386`, `arm32v6`, `arm32v7`, `arm64v8`, `ppc64le`

Note that not all architectures are supported by all platforms, look [here](https://central.sonatype.com/namespace/io.zonky.test.postgres/) for an exhaustive list of all available artifacts.
  
Since `PostgreSQL 10.0`, there are additional artifacts with `alpine-lite` suffix. These artifacts contain postgres binaries for Alpine Linux with disabled [ICU support](https://blog.2ndquadrant.com/icu-support-postgresql-10/) for further size reduction.

## Troubleshooting

### Process [/tmp/embedded-pg/PG-XYZ/bin/initdb, ...] failed

Check the console output for an `initdb: cannot be run as root` message. If the error is present, try to upgrade to a newer version of the library (1.2.8+), or ensure the build process to be running as a non-root user.

If the error is not present, try to clean up the `/tmp/embedded-pg/PG-XYZ` directory containing temporary binaries of the embedded database. 

### Running tests on Windows does not work

You probably need to install [Microsoft Visual C++ 2013 Redistributable Package](https://support.microsoft.com/en-us/help/3179560/update-for-visual-c-2013-and-visual-c-redistributable-package). The version 2013 is important, installation of other versions will not help. More detailed is the problem discussed [here](https://github.com/opentable/otj-pg-embedded/issues/65).

### Running tests in Docker does not work

Running builds inside a Docker container is fully supported, including Alpine Linux. However, PostgreSQL has a restriction the database process must run under a non-root user. Otherwise, the database does not start and fails with an error.  

So be sure to use a docker image that uses a non-root user. Or, since version `1.2.8` you can run the docker container with `--privileged` option, which allows taking advantage of `unshare` command to run the database process in a separate namespace.

Below are some examples of how to prepare a docker image running with a non-root user:

<details>
  <summary>Standard Dockerfile</summary>
  
  ```dockerfile
  FROM openjdk:8-jdk
  
  RUN groupadd --system --gid 1000 test
  RUN useradd --system --gid test --uid 1000 --shell /bin/bash --create-home test
  
  USER test
  WORKDIR /home/test
  ```

</details>

<details>
  <summary>Alpine Dockerfile</summary>
  
  ```dockerfile
  FROM openjdk:8-jdk-alpine
  
  RUN addgroup -S -g 1000 test
  RUN adduser -D -S -G test -u 1000 -s /bin/ash test
  
  USER test
  WORKDIR /home/test
  ```

</details>

<details>
  <summary>Gitlab runner Docker executor</summary>

  Configure Docker container to run in privileged mode as described [here](https://docs.gitlab.com/runner/executors/docker.html#use-docker-in-docker-with-privileged-mode).

  ```
  [[runners]]
    executor = "docker"
    [runners.docker]
      privileged = true
  ```

</details>


If the above do not resolve your error, verify that the correct locales are available in your container. For example, many variants of AlmaLinux:9 do not come with `glibc-langpack-en`. This will lead to misleading errors during `initdb`. Additionally, you can optionally set your locale with `setLocaleConfig()` when building your EmbeddedPostgres instance.

## License
The project is released under version 2.0 of the [Apache License](https://www.apache.org/licenses/LICENSE-2.0).
