/*
 * Copyright 2025 Nikan Radan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.smushytaco.postgres.embedded;

import com.smushytaco.postgres.util.LinuxUtils;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jspecify.annotations.NonNull;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tukaani.xz.XZInputStream;

import javax.sql.DataSource;
import java.io.*;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.Collections.unmodifiableMap;

/**
 * Manages the lifecycle of an embedded PostgreSQL server instance.
 * <p>
 * This class is responsible for extracting the PostgreSQL binaries,
 * initializing a data directory, starting the server, and shutting it
 * down when no longer needed.
 * <p>
 * Instances are usually created via {@link EmbeddedPostgres#builder()}
 * or {@link EmbeddedPostgres#start()}.
 */
public class EmbeddedPostgres implements Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(EmbeddedPostgres.class);
    private static final String JDBC_FORMAT = "jdbc:postgresql://localhost:%s/%s?user=%s";

    private static final String PG_STOP_MODE = "fast";
    private static final String PG_STOP_WAIT_S = "5";
    private static final String PG_SUPERUSER = "postgres";
    private static final Duration DEFAULT_PG_STARTUP_WAIT = Duration.ofSeconds(10);
    private static final String LOCK_FILE_NAME = "epg-lock";

    private final Path pgDir;

    private final Duration pgStartupWait;
    private final Path dataDirectory;
    private final Path lockFile;
    private final UUID instanceId = UUID.randomUUID();
    private final int port;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private final Map<String, String> postgresConfig;
    private final Map<String, String> localeConfig;
    private final Map<String, String> connectConfig;

    private volatile FileChannel lockChannel;
    private volatile FileLock lock;
    private final boolean cleanDataDirectory;

    private final ProcessBuilder.Redirect errorRedirector;
    private final ProcessBuilder.Redirect outputRedirector;

    @SuppressWarnings("unused")
    EmbeddedPostgres(Path parentDirectory, Path dataDirectory, boolean cleanDataDirectory,
                        Map<String, String> postgresConfig, Map<String, String> localeConfig, int port, Map<String, String> connectConfig,
                        PgBinaryResolver pgBinaryResolver, ProcessBuilder.Redirect errorRedirector, ProcessBuilder.Redirect outputRedirector) throws IOException {
        this(parentDirectory, dataDirectory, cleanDataDirectory, postgresConfig, localeConfig, port, connectConfig,
                pgBinaryResolver, errorRedirector, outputRedirector, DEFAULT_PG_STARTUP_WAIT, null, null);
    }

    EmbeddedPostgres(Path parentDirectory, Path dataDirectory, boolean cleanDataDirectory,
                        Map<String, String> postgresConfig, Map<String, String> localeConfig, int port, Map<String, String> connectConfig,
                        PgBinaryResolver pgBinaryResolver, ProcessBuilder.Redirect errorRedirector,
                        ProcessBuilder.Redirect outputRedirector, Duration pgStartupWait,
                        Path overrideWorkingDirectory, Consumer<Path> dataDirectoryCustomizer) throws IOException {
        this.cleanDataDirectory = cleanDataDirectory;
        this.postgresConfig = new HashMap<>(postgresConfig);
        this.localeConfig = new HashMap<>(localeConfig);
        this.connectConfig = new HashMap<>(connectConfig);
        this.port = port;
        this.pgDir = prepareBinaries(pgBinaryResolver, overrideWorkingDirectory);
        this.errorRedirector = errorRedirector;
        this.outputRedirector = outputRedirector;
        this.pgStartupWait = pgStartupWait;
        Objects.requireNonNull(this.pgStartupWait, "Wait time cannot be null");

        if (parentDirectory != null) {
            mkdirs(parentDirectory);
            cleanOldDataDirectories(parentDirectory);
            this.dataDirectory = Objects.requireNonNullElseGet(dataDirectory, () -> parentDirectory.resolve(instanceId.toString()));
        } else {
            this.dataDirectory = dataDirectory;
        }
        if (this.dataDirectory == null) {
            throw new IllegalArgumentException("no data directory");
        }
        LOG.trace("{} postgres data directory is {}", instanceId, this.dataDirectory);
        mkdirs(this.dataDirectory);

        lockFile = this.dataDirectory.resolve(LOCK_FILE_NAME);

        if (cleanDataDirectory || Files.notExists(this.dataDirectory.resolve("postgresql.conf"))
        ) {
            initdb();
        }

        lock();

        if (dataDirectoryCustomizer != null) {
            dataDirectoryCustomizer.accept(dataDirectory);
        }

        startPostmaster();
    }

    /**
     * Returns a {@link DataSource} connected to the {@code template1} database
     * using the default superuser.
     *
     * @return a data source for the {@code template1} database
     */
    public DataSource getTemplateDatabase() {
        return getDatabase(PG_SUPERUSER, "template1");
    }

    /**
     * Returns a {@link DataSource} connected to the {@code template1} database
     * using the default superuser and the given connection properties.
     *
     * @param properties additional connection properties to apply to the data source
     * @return a data source for the {@code template1} database
     */
    @SuppressWarnings("unused")
    public DataSource getTemplateDatabase(Map<String, String> properties) {
        return getDatabase(PG_SUPERUSER, "template1", properties);
    }

    /**
     * Returns a {@link DataSource} connected to the {@code postgres} database
     * using the default superuser.
     *
     * @return a data source for the {@code postgres} database
     */
    public DataSource getPostgresDatabase() {
        return getDatabase(PG_SUPERUSER, PG_SUPERUSER);
    }

    /**
     * Returns a {@link DataSource} connected to the {@code postgres} database
     * using the default superuser and the given connection properties.
     *
     * @param properties additional connection properties to apply to the data source
     * @return a data source for the {@code postgres} database
     */
    @SuppressWarnings("unused")
    public DataSource getPostgresDatabase(Map<String, String> properties) {
        return getDatabase(PG_SUPERUSER, PG_SUPERUSER, properties);
    }

    /**
     * Creates a {@link DataSource} for the given database and user, using the
     * default connection configuration.
     *
     * @param userName the database username
     * @param dbName   the database name
     * @return a data source pointing at the requested database
     */
    public DataSource getDatabase(String userName, String dbName) {
        return getDatabase(userName, dbName, connectConfig);
    }

    /**
     * Creates a {@link DataSource} for the given database and user, applying the
     * supplied connection properties.
     *
     * @param userName   the database username
     * @param dbName     the database name
     * @param properties additional connection properties to apply to the data source
     * @return a data source pointing at the requested database
     */
    public DataSource getDatabase(String userName, String dbName, Map<String, String> properties) {
        final PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setServerNames(new String[]{"localhost"});
        ds.setPortNumbers(new int[]{port});
        ds.setDatabaseName(dbName);
        ds.setUser(userName);

        properties.forEach((propertyKey, propertyValue) -> {
            try {
                ds.setProperty(propertyKey, propertyValue);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
        return ds;
    }

    /**
     * Builds a JDBC URL for the given user and database name, using the
     * port of this embedded PostgreSQL instance.
     *
     * @param userName the database username
     * @param dbName   the database name
     * @return a JDBC connection URL for the given database
     */
    @SuppressWarnings("unused")
    public String getJdbcUrl(String userName, String dbName) {
        return String.format(JDBC_FORMAT, port, dbName, userName);
    }

    /**
     * Returns the TCP port on which this embedded PostgreSQL instance is listening.
     *
     * @return the PostgreSQL server port
     */
    public int getPort() {
        return port;
    }

    Map<String, String> getConnectConfig() {
        return unmodifiableMap(connectConfig);
    }

    private void lock() throws IOException {
        lockChannel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lock = lockChannel.tryLock();
        if (lock == null) {
            throw new IllegalStateException("could not lock " + lockFile);
        }
    }

    private static String formatElapsedSince(Instant instant) {
        Duration duration = Duration.between(instant, Instant.now());
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();
        long millis = duration.toMillisPart();
        return String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, millis);
    }

    private void initdb() {
        Instant start = Instant.now();
        List<String> args = new ArrayList<>();
        args.addAll(Arrays.asList(
                "-A", "trust", "-U", PG_SUPERUSER,
                "-D", dataDirectory.toString(), "-E", "UTF-8"));
        args.addAll(createLocaleOptions());
        system(initDb, args, null);
        if (LOG.isInfoEnabled()) {
            LOG.info("{} initdb completed in {}", instanceId, formatElapsedSince(start));
        }
    }

    private void startPostmaster() {
        Instant start = Instant.now();
        if (started.getAndSet(true)) {
            throw new IllegalStateException("Postmaster already started");
        }

        final List<String> args = new ArrayList<>(Arrays.asList(
                "-D", dataDirectory.toString(),
                "-o", String.join(" ", createInitOptions()),
                "-w", "start"
        ));

        Runtime.getRuntime().addShutdownHook(newCloserThread());

        system(pgCtl, args, pgStartupWait);

        if (LOG.isInfoEnabled()) {
            LOG.info("{} postmaster startup finished in {}", instanceId, formatElapsedSince(start));
        }
    }

    private List<String> createInitOptions() {
        final List<String> initOptions = new ArrayList<>(Arrays.asList(
                "-p", Integer.toString(port),
                "-F"));

        for (final Entry<String, String> config : postgresConfig.entrySet()) {
            initOptions.add("-c");
            initOptions.add(config.getKey() + "=" + config.getValue());
        }

        return initOptions;
    }

    private List<String> createLocaleOptions() {
        final List<String> localeOptions = new ArrayList<>();
        for (final Entry<String, String> config : localeConfig.entrySet()) {
            localeOptions.add(String.format("--%s=%s", config.getKey(), config.getValue()));
        }
        return localeOptions;
    }

    private Thread newCloserThread() {
        final Thread closeThread = new Thread(() -> {
            try {
                EmbeddedPostgres.this.close();
            } catch (IOException ex) {
                LOG.error("Unexpected IOException from Closeables.close", ex);
            }
        });
        closeThread.setName("postgres-" + instanceId + "-closer");
        return closeThread;
    }

    private static void deleteDirectoryRecursively(Path dir) throws IOException {
        if (Files.notExists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public @NonNull FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public @NonNull FileVisitResult postVisitDirectory(@NonNull Path directory, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }


    @Override
    public void close() throws IOException {
        if (closed.getAndSet(true)) {
            return;
        }
        Instant start = Instant.now();
        try {
            pgCtl(dataDirectory, "stop");
            if (LOG.isInfoEnabled()) {
                LOG.info("{} shut down postmaster in {}", instanceId, formatElapsedSince(start));
            }
        } catch (final Exception e) {
            LOG.error("Could not stop postmaster {}", instanceId, e);
        }
        if (lock != null) {
            lock.release();
        }
        try {
            if (lockChannel != null) {
                lockChannel.close();
            }
        } catch (IOException e) {
            LOG.error("while closing lockStream", e);
        }

        if (cleanDataDirectory && System.getProperty("ot.epg.no-cleanup") == null) {
            try {
                deleteDirectoryRecursively(dataDirectory);
            } catch (IOException _) {
                LOG.error("Could not clean up directory {}", dataDirectory.toAbsolutePath());
            }
        } else {
            LOG.info("Did not clean up directory {}", dataDirectory.toAbsolutePath());
        }
    }

    @SuppressWarnings("SameParameterValue")
    private void pgCtl(Path dir, String action) {
        final List<String> args = new ArrayList<>(Arrays.asList(
                "-D", dir.toString(), action,
                "-m", PG_STOP_MODE, "-t",
                PG_STOP_WAIT_S, "-w"
        ));
        system(pgCtl, args, null);
    }

    private void cleanOldDataDirectories(Path parentDirectory) {
        try (Stream<Path> children = Files.list(parentDirectory)) {
            for (Path dir : children.toList()) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }

                Path theLockFile = dir.resolve(LOCK_FILE_NAME);
                if (Files.notExists(theLockFile)) {
                    continue;
                }

                boolean isTooNew = System.currentTimeMillis() -
                        Files.getLastModifiedTime(theLockFile).toMillis() < 10 * 60 * 1000;
                if (isTooNew) {
                    continue;
                }

                try (FileChannel fileChannel = FileChannel.open(theLockFile,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                        FileLock theLock = fileChannel.tryLock()) {
                    if (theLock != null) {
                        LOG.info("Found stale data directory {}", dir);
                        if (Files.exists(dir.resolve("postmaster.pid"))) {
                            try {
                                pgCtl(dir, "stop");
                                LOG.info("Shut down orphaned postmaster!");
                            } catch (Exception e) {
                                if (LOG.isDebugEnabled()) {
                                    LOG.warn("Failed to stop postmaster {}", dir, e);
                                } else {
                                    LOG.warn("Failed to stop postmaster {}: {}", dir, e.getMessage());
                                }
                            }
                        }
                        deleteDirectoryRecursively(dir);
                    }
                } catch (OverlappingFileLockException e) {
                    LOG.trace("While cleaning old data directories", e);
                } catch (Exception e) {
                    LOG.warn("While cleaning old data directories", e);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    private static Path getWorkingDirectory() {
        final Path tempWorkingDirectory = Path.of(System.getProperty("java.io.tmpdir")).resolve("embedded-pg");
        return Path.of(System.getProperty("ot.epg.working-dir", tempWorkingDirectory.toString()));
    }

    /**
     * Creates and starts an embedded PostgreSQL instance with default settings.
     *
     * @return a started {@link EmbeddedPostgres} instance
     * @throws IOException if the PostgreSQL binaries cannot be prepared
     *                     or the server cannot be started
     */
    public static EmbeddedPostgres start() throws IOException {
        return builder().start();
    }

    /**
     * Returns a new {@link Builder} for configuring and starting an
     * {@link EmbeddedPostgres} instance.
     *
     * @return a new builder for embedded PostgreSQL
     */
    public static EmbeddedPostgres.Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link EmbeddedPostgres} instances.
     * <p>
     * Allows customization of data directory, port, server configuration,
     * binary resolver, startup wait time and more before starting the server.
     */
    public static class Builder {
        private final Path parentDirectory = getWorkingDirectory();
        private Path overrideWorkingDirectory;
        private Path builderDataDirectory;
        private final Map<String, String> config = new HashMap<>();
        private final Map<String, String> localeConfig = new HashMap<>();
        private boolean builderCleanDataDirectory = true;
        private int builderPort = 0;
        private final Map<String, String> connectConfig = new HashMap<>();
        private PgBinaryResolver pgBinaryResolver = DefaultPostgresBinaryResolver.INSTANCE;
        private Duration pgStartupWait = DEFAULT_PG_STARTUP_WAIT;
        private Consumer<Path> dataDirectoryCustomizer;

        private ProcessBuilder.Redirect errRedirector = ProcessBuilder.Redirect.PIPE;
        private ProcessBuilder.Redirect outRedirector = ProcessBuilder.Redirect.PIPE;

        Builder() {
            config.put("timezone", "UTC");
            config.put("synchronous_commit", "off");
            config.put("max_connections", "300");
        }

        /**
         * Configures the maximum amount of time to wait for the PostgreSQL
         * server to start and become ready to accept connections.
         *
         * @param pgStartupWait the maximum startup wait duration (must be non-negative)
         * @return this builder instance for chaining
         * @throws IllegalArgumentException if the duration is negative
         */
        @SuppressWarnings("UnusedReturnValue")
        public Builder setPGStartupWait(Duration pgStartupWait) {
            Objects.requireNonNull(pgStartupWait);
            if (pgStartupWait.isNegative()) {
                throw new IllegalArgumentException("Negative durations are not permitted.");
            }

            this.pgStartupWait = pgStartupWait;
            return this;
        }

        /**
         * Configures whether the data directory should be cleaned up (deleted)
         * when the embedded PostgreSQL instance is closed.
         *
         * @param cleanDataDirectory {@code true} to delete the data directory on close,
         *                           {@code false} to leave it on disk
         * @return this builder instance for chaining
         */
        @SuppressWarnings("unused")
        public Builder setCleanDataDirectory(boolean cleanDataDirectory) {
            builderCleanDataDirectory = cleanDataDirectory;
            return this;
        }

        /**
         * Sets a fixed data directory for PostgreSQL to use instead of a
         * randomly generated temporary directory.
         *
         * @param directory the data directory to use
         * @return this builder instance for chaining
         */
        public Builder setDataDirectory(Path directory) {
            builderDataDirectory = directory;
            return this;
        }

        /**
         * Adds or overrides a PostgreSQL server configuration parameter.
         * <p>
         * These values are passed as {@code -c key=value} options when the
         * server is started.
         *
         * @param key   the configuration parameter name
         * @param value the configuration parameter value
         * @return this builder instance for chaining
         */
        @SuppressWarnings("unused")
        public Builder setServerConfig(String key, String value) {
            config.put(key, value);
            return this;
        }

        /**
         * Adds or overrides a locale-related initialization option used when
         * running {@code initdb}.
         *
         * @param key   the locale option name (for example {@code "locale"})
         * @param value the locale option value
         * @return this builder instance for chaining
         */
        @SuppressWarnings("unused")
        public Builder setLocaleConfig(String key, String value) {
            localeConfig.put(key, value);
            return this;
        }

        /**
         * Adds a connection configuration property that will be applied to
         * {@link PGSimpleDataSource} instances created by this embedded server.
         *
         * @param key   the connection property name
         * @param value the connection property value
         * @return this builder instance for chaining
         */
        @SuppressWarnings("UnusedReturnValue")
        public Builder setConnectConfig(String key, String value) {
            connectConfig.put(key, value);
            return this;
        }

        /**
         * Overrides the working directory used to store extracted PostgreSQL
         * binaries. If not set, a directory under the system temp directory
         * is used.
         *
         * @param workingDirectory the directory in which binaries should be stored
         * @return this builder instance for chaining
         */
        @SuppressWarnings("unused")
        public Builder setOverrideWorkingDirectory(Path workingDirectory) {
            overrideWorkingDirectory = workingDirectory;
            return this;
        }

        /**
         * Sets the TCP port for the embedded PostgreSQL instance.
         * <p>
         * If not specified, a free port is detected automatically.
         *
         * @param port the port to use
         * @return this builder instance for chaining
         */
        @SuppressWarnings("unused")
        public Builder setPort(int port) {
            builderPort = port;
            return this;
        }

        /**
         * Configures how stderr from PostgreSQL subprocesses should be redirected.
         *
         * @param errRedirector the redirect configuration for error output
         * @return this builder instance for chaining
         */
        @SuppressWarnings("unused")
        public Builder setErrorRedirector(ProcessBuilder.Redirect errRedirector) {
            this.errRedirector = errRedirector;
            return this;
        }

        /**
         * Configures how stdout from PostgreSQL subprocesses should be redirected.
         *
         * @param outRedirector the redirect configuration for standard output
         * @return this builder instance for chaining
         */
        @SuppressWarnings("unused")
        public Builder setOutputRedirector(ProcessBuilder.Redirect outRedirector) {
            this.outRedirector = outRedirector;
            return this;
        }

        /**
         * Sets the strategy used to resolve PostgreSQL binaries for the
         * current operating system and architecture.
         *
         * @param pgBinaryResolver the resolver to use
         * @return this builder instance for chaining
         */
        @SuppressWarnings("unused")
        public Builder setPgBinaryResolver(PgBinaryResolver pgBinaryResolver) {
            this.pgBinaryResolver = pgBinaryResolver;
            return this;
        }

        /**
         * Registers a callback that can customize the data directory after it
         * is created but before the PostgreSQL server is started.
         *
         * @param dataDirectoryCustomizer a consumer that receives the data directory path
         * @return this builder instance for chaining
         */
        public Builder setDataDirectoryCustomizer(final Consumer<Path> dataDirectoryCustomizer) {
            this.dataDirectoryCustomizer = dataDirectoryCustomizer;
            return this;
        }

        /**
         * Creates and starts a new embedded PostgreSQL instance with the current
         * builder configuration.
         *
         * @return a started {@link EmbeddedPostgres} instance
         * @throws IOException if the PostgreSQL binaries cannot be prepared
         *                     or the server cannot be started
         */
        public EmbeddedPostgres start() throws IOException {
            if (builderPort == 0) {
                builderPort = detectFreePort();
            }
            if (builderDataDirectory == null) {
                builderDataDirectory = Files.createTempDirectory("epg");
            }
            return new EmbeddedPostgres(parentDirectory, builderDataDirectory, builderCleanDataDirectory, config,
                    localeConfig, builderPort, connectConfig, pgBinaryResolver, errRedirector, outRedirector,
                    pgStartupWait, overrideWorkingDirectory, dataDirectoryCustomizer);
        }

        private static int detectFreePort() {
            try (ServerSocket socket = new ServerSocket(0)) {
                socket.setReuseAddress(true);
                return socket.getLocalPort();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to detect a free port", e);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Builder builder = (Builder) o;
            return builderCleanDataDirectory == builder.builderCleanDataDirectory &&
                    builderPort == builder.builderPort &&
                    Objects.equals(parentDirectory, builder.parentDirectory) &&
                    Objects.equals(builderDataDirectory, builder.builderDataDirectory) &&
                    Objects.equals(config, builder.config) &&
                    Objects.equals(localeConfig, builder.localeConfig) &&
                    Objects.equals(connectConfig, builder.connectConfig) &&
                    Objects.equals(pgBinaryResolver, builder.pgBinaryResolver) &&
                    Objects.equals(pgStartupWait, builder.pgStartupWait) &&
                    Objects.equals(errRedirector, builder.errRedirector) &&
                    Objects.equals(outRedirector, builder.outRedirector) &&
                    Objects.equals(dataDirectoryCustomizer != null ? dataDirectoryCustomizer.getClass() : null,
                            builder.dataDirectoryCustomizer != null ? builder.dataDirectoryCustomizer.getClass() : null);
        }

        @Override
        public int hashCode() {
            return Objects.hash(parentDirectory, builderDataDirectory, config, localeConfig, builderCleanDataDirectory, builderPort, connectConfig, pgBinaryResolver, pgStartupWait, errRedirector, outRedirector);
        }
    }

    private void system(Command command, List<String> args, Duration waitDuration) {
        try {
            final ProcessBuilder builder = new ProcessBuilder();

            command.applyTo(builder, args);
            builder.redirectErrorStream(true);
            builder.redirectError(errorRedirector);
            builder.redirectOutput(outputRedirector);

            final Process theProcess = builder.start();

            if (outputRedirector.type() == ProcessBuilder.Redirect.Type.PIPE) {
                ProcessOutputLogger.logOutput(LOG, theProcess, command.processName());
            }
            if (waitDuration == null) {
                if (theProcess.waitFor() != 0) {
                    throw new IllegalStateException(String.format("Process %s failed with exit code %d", builder.command(), theProcess.exitValue()));
                }
            } else {
                if (!theProcess.waitFor(waitDuration)) {
                    theProcess.destroyForcibly();
                    throw new IllegalStateException(String.format("Process %s timed out after %s", builder.command(), waitDuration));
                }
                if (theProcess.exitValue() != 0) {
                    throw new IllegalStateException(String.format("Process %s failed with exit code %d", builder.command(), theProcess.exitValue()));
                }
            }
        } catch (final IOException e) {
            throw new RuntimeException(e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static void mkdirs(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("could not create " + dir, e);
        }
    }


    private static final Lock PREPARE_BINARIES_LOCK = new ReentrantLock();
    private static final Map<PgBinaryResolver, Path> PREPARE_BINARIES = new HashMap<>();

    /**
     * Get current operating system string. The string is used in the appropriate postgres binary name.
     *
     * @return Current operating system string.
     */
    private static String getOS() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) return "Windows";
        if (osName.contains("mac")) return "Darwin";
        if (osName.contains("linux")) return "Linux";
        throw new UnsupportedOperationException("Unknown OS: " + osName);
    }

    /**
     * Get the machine architecture string. The string is used in the appropriate postgres binary name.
     *
     * @return Current machine architecture string.
     */
    private static String getArchitecture() {
        String arch = System.getProperty("os.arch", "");
        return arch.equalsIgnoreCase("amd64") ? "x86_64" : arch;
    }

    /**
     * Unpack archive compressed by tar with xz compression. By default, system tar is used (faster). If not found, then the
     * java implementation takes place.
     *
     * @param stream    A stream with the postgres binaries.
     * @param targetDir The directory to extract the content to.
     */
    private static void extractTxz(InputStream stream, Path targetDir) throws IOException {
        try (
                XZInputStream xzIn = new XZInputStream(stream);
                TarArchiveInputStream tarIn = new TarArchiveInputStream(xzIn)
        ) {
            final Set<Path> dirsToUpdate = new HashSet<>();
            final Phaser phaser = new Phaser(1);
            TarArchiveEntry entry;

            while ((entry = tarIn.getNextEntry()) != null) {
                final String individualFile = entry.getName();
                final Path fsObject = targetDir.resolve(individualFile);

                if (Files.exists(fsObject)) {
                    Files.setLastModifiedTime(fsObject, FileTime.fromMillis(System.currentTimeMillis()));

                    Path parentDir = fsObject.getParent();
                    while (parentDir != null) {
                        dirsToUpdate.add(parentDir);
                        if (targetDir.equals(parentDir)) {
                            break;
                        }
                        parentDir = parentDir.getParent();
                    }
                } else if (entry.isSymbolicLink() || entry.isLink()) {
                    Path target = FileSystems.getDefault().getPath(entry.getLinkName());
                    Files.createSymbolicLink(fsObject, target);
                } else if (entry.isFile()) {
                    byte[] content = new byte[(int) entry.getSize()];
                    int read = tarIn.read(content, 0, content.length);
                    if (read == -1) {
                        throw new IllegalStateException("could not read " + individualFile);
                    }
                    mkdirs(fsObject.getParent());

                    final AsynchronousFileChannel fileChannel = AsynchronousFileChannel.open(fsObject, CREATE, WRITE);
                    final ByteBuffer buffer = ByteBuffer.wrap(content);

                    phaser.register();
                    fileChannel.write(buffer, 0, fileChannel, new CompletionHandler<Integer, Channel>() {
                        @Override
                        public void completed(Integer written, Channel channel) {
                            closeChannel(channel);
                        }

                        @Override
                        public void failed(Throwable error, Channel channel) {
                            LOG.error("Could not write file {}", fsObject.toAbsolutePath(), error);
                            closeChannel(channel);
                        }

                        private void closeChannel(Channel channel) {
                            try {
                                channel.close();
                            } catch (IOException e) {
                                LOG.error("Unexpected error while closing the channel", e);
                            } finally {
                                phaser.arriveAndDeregister();
                            }
                        }
                    });
                } else if (entry.isDirectory()) {
                    mkdirs(fsObject);
                } else {
                    throw new UnsupportedOperationException(
                            String.format("Unsupported entry found: %s", individualFile)
                    );
                }

                if (individualFile.startsWith("bin/") || individualFile.startsWith("./bin/")) {
                    if (Files.getFileStore(fsObject).supportsFileAttributeView(PosixFileAttributeView.class)) {
                        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(fsObject);
                        perms.add(PosixFilePermission.OWNER_EXECUTE);
                        perms.add(PosixFilePermission.GROUP_EXECUTE);
                        perms.add(PosixFilePermission.OTHERS_EXECUTE);
                        Files.setPosixFilePermissions(fsObject, perms);
                    }
                }
            }

            for (Path updatedDir : dirsToUpdate) {
                Files.setLastModifiedTime(updatedDir, FileTime.fromMillis(System.currentTimeMillis()));
            }

            phaser.arriveAndAwaitAdvance();
        }
    }

    private static Path prepareBinaries(PgBinaryResolver pgBinaryResolver, Path overrideWorkingDirectory) {
        PREPARE_BINARIES_LOCK.lock();
        try {
            if (PREPARE_BINARIES.containsKey(pgBinaryResolver) && Files.exists(PREPARE_BINARIES.get(pgBinaryResolver))) {
                return PREPARE_BINARIES.get(pgBinaryResolver);
            }

            final String system = getOS();
            final String machineHardware = getArchitecture();

            LOG.info("Detected a {} {} system", system, machineHardware);
            Path pgDir;
            final InputStream pgBinary;
            try {
                pgBinary = pgBinaryResolver.getPgBinary(system, machineHardware);
            } catch (final IOException e) {
                throw new ExceptionInInitializerError(e);
            }

            if (pgBinary == null) {
                throw new IllegalStateException("No Postgres binary found for " + system + " / " + machineHardware);
            }

            try (DigestInputStream pgArchiveData = new DigestInputStream(pgBinary, MessageDigest.getInstance("MD5"));
                    ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                pgArchiveData.transferTo(baos);

                String pgDigest = HexFormat.of().formatHex(pgArchiveData.getMessageDigest().digest());
                Path workingDirectory = Optional.ofNullable(overrideWorkingDirectory).orElse(getWorkingDirectory());
                pgDir = workingDirectory.resolve(String.format("PG-%s", pgDigest));

                mkdirs(pgDir);

                FileStore store = Files.getFileStore(workingDirectory);
                if (store.supportsFileAttributeView(PosixFileAttributeView.class)) {
                    Set<PosixFilePermission> perms = Files.getPosixFilePermissions(workingDirectory);
                    perms.add(PosixFilePermission.OWNER_WRITE);
                    perms.add(PosixFilePermission.GROUP_WRITE);
                    perms.add(PosixFilePermission.OTHERS_WRITE);
                    Files.setPosixFilePermissions(workingDirectory, perms);
                } else if (store.supportsFileAttributeView(DosFileAttributeView.class)) {
                    Files.setAttribute(workingDirectory, "dos:readonly", false);
                }

                final Path pgDirExists = pgDir.resolve(".exists");

                if (!isPgBinReady(pgDirExists)) {
                    Path unpackLockFile = pgDir.resolve(LOCK_FILE_NAME);
                    try (FileChannel fileChannel = FileChannel.open(unpackLockFile,
                            StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                            FileLock unpackLock = fileChannel.tryLock()) {
                        if (unpackLock != null) {
                            LOG.info("Extracting Postgres...");
                            try (ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray())) {
                                extractTxz(bais, pgDir);
                            }
                            if (Files.notExists(pgDirExists)) {
                                Files.createFile(pgDirExists);
                            } else {
                                Files.setLastModifiedTime(pgDirExists, FileTime.fromMillis(System.currentTimeMillis()));
                            }
                        } else {
                            // the other guy is unpacking for us.
                            int maxAttempts = 60;
                            while (!isPgBinReady(pgDirExists) && --maxAttempts > 0) {
                                Thread.sleep(1000L);
                            }
                            if (!isPgBinReady(pgDirExists)) {
                                throw new IllegalStateException("Waited 60 seconds for postgres to be unpacked but it never finished!");
                            }
                        }
                    } finally {
                        try {
                            if (Files.exists(unpackLockFile)) {
                                Files.delete(unpackLockFile);
                            }
                        } catch (IOException e) {
                            LOG.error("could not remove lock file {}", unpackLockFile.toAbsolutePath(), e);
                        }
                    }

                }
            } catch (final IOException | NoSuchAlgorithmException e) {
                throw new ExceptionInInitializerError(e);
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ExceptionInInitializerError(ie);
            }
            PREPARE_BINARIES.put(pgBinaryResolver, pgDir);
            LOG.info("Postgres binaries at {}", pgDir);
            return pgDir;
        } finally {
            PREPARE_BINARIES_LOCK.unlock();
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean isPgBinReady(Path pgDirExists) {
        if (Files.notExists(pgDirExists)) {
            return false;
        }

        Path parentDir = pgDirExists.getParent();
        Path[] otherFiles;
        try (Stream<Path> stream = Files.list(parentDir)) {
            otherFiles = stream
                    .filter(path -> !path.equals(pgDirExists))
                    .toArray(Path[]::new);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }


        long contentLastModified = Stream.of(otherFiles).mapToLong(path -> {
                    try {
                        return Files.getLastModifiedTime(path).toMillis();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .max().orElse(Long.MAX_VALUE);
        try {
            long parentLastModified = Files.getLastModifiedTime(parentDir).toMillis();
            long pgDirLastModified = Files.getLastModifiedTime(pgDirExists).toMillis();
            return parentLastModified - 100 <= pgDirLastModified && contentLastModified <= pgDirLastModified;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read last modified times", e);
        }
    }

    @Override
    public String toString() {
        return "EmbeddedPG-" + instanceId;
    }

    private final Command initDb = new Command("initdb");
    private final Command pgCtl = new Command("pg_ctl");

    private class Command {

        private final String commandName;

        private Command(String commandName) {
            this.commandName = commandName;
        }

        public String processName() {
            return commandName;
        }

        public void applyTo(ProcessBuilder builder, List<String> arguments) {
            List<String> command = new ArrayList<>();

            if (LinuxUtils.isUnshareAvailable()) {
                command.addAll(Arrays.asList("unshare", "-U"));
            }

            String extension = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? ".exe" : "";
            command.add(pgDir.resolve("bin").resolve(commandName + extension).toString());
            command.addAll(arguments);

            builder.command(command);
        }
    }
}
