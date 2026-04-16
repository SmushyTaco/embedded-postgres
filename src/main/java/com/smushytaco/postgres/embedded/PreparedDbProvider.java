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

import com.smushytaco.postgres.embedded.EmbeddedPostgres.Builder;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Provider for prepared PostgreSQL databases backed by an {@link EmbeddedPostgres}
 * instance and a {@link DatabasePreparer}.
 * <p>
 * Databases are created from a shared template cluster, allowing fast creation
 * of isolated schemas for tests or other ephemeral use-cases.
 * <p>
 * Each provider instance acts as a handle to a managed shared cluster rather than
 * directly owning the lifecycle of the underlying preparer pipeline.
 */
public class PreparedDbProvider implements AutoCloseable {
    private static final String JDBC_FORMAT = "jdbc:postgresql://localhost:%d/%s?user=%s";
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final ClusterManager CLUSTER_MANAGER = new ClusterManager();

    private final SharedCluster sharedCluster;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a {@link PreparedDbProvider} for the given {@link DatabasePreparer}
     * with no additional customizations of the underlying {@link EmbeddedPostgres.Builder}.
     *
     * @param preparer the database preparer used to initialize the template cluster
     * @return a new {@link PreparedDbProvider} instance
     */
    @SuppressWarnings("unused")
    public static PreparedDbProvider forPreparer(final DatabasePreparer preparer) {
        return forPreparer(preparer, Collections.emptyList());
    }

    /**
     * Creates a {@link PreparedDbProvider} for the given {@link DatabasePreparer}
     * and a set of customizers that can adjust the {@link EmbeddedPostgres.Builder}
     * before the cluster is started.
     *
     * @param preparer    the database preparer used to initialize the template cluster
     * @param customizers customizations to apply to the {@link EmbeddedPostgres.Builder}
     * @return a new {@link PreparedDbProvider} instance
     */
    public static PreparedDbProvider forPreparer(final DatabasePreparer preparer, final Iterable<Consumer<Builder>> customizers) {
        return new PreparedDbProvider(preparer, customizers);
    }

    private PreparedDbProvider(final DatabasePreparer preparer, final Iterable<Consumer<Builder>> customizers) {
        try {
            sharedCluster = CLUSTER_MANAGER.acquireCluster(preparer, customizers);
        } catch (final IOException | SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Creates a new database and returns its JDBC connection string.
     * <p>
     * No two invocations will return the same database name.
     *
     * @return a JDBC URI for the newly created database
     * @throws SQLException if the database cannot be created or prepared
     */
    public String createDatabase() throws SQLException {
        return getJdbcUri(createNewDB());
    }

    /**
     * Create a new database, and return the backing info.
     * This allows you to access the host and port.
     * More common usage is to call createDatabase() and
     * get the JDBC connection string.
     * NB: No two invocations will return the same database.
     */
    private DbInfo createNewDB() throws SQLException {
        ensureOpen();
        return sharedCluster.getNextDb();
    }

    /**
     * Creates a new database and returns its connection metadata.
     * <p>
     * This is a convenience for callers that need access to individual fields
     * such as host, port, database name, user, and connection properties.
     *
     * @return a {@link ConnectionInfo} for the newly created database,
     *         or {@code null} if database creation failed
     * @throws SQLException if the database cannot be created or prepared
     */
    public ConnectionInfo createNewDatabase() throws SQLException {
        final DbInfo dbInfo = createNewDB();
        return !dbInfo.isSuccess() ? null : new ConnectionInfo(dbInfo.getDbName(), dbInfo.getPort(), dbInfo.getUser(), dbInfo.getProperties());
    }

    /**
     * Creates a {@link DataSource} for the given connection information.
     * <p>
     * This is typically used with {@link #createNewDatabase()} to obtain
     * a ready-to-use {@link javax.sql.DataSource} for tests.
     *
     * @param connectionInfo the connection metadata for the target database
     * @return a configured {@link DataSource} instance
     * @throws SQLException if the underlying driver rejects a configuration property
     */
    public DataSource createDataSourceFromConnectionInfo(final ConnectionInfo connectionInfo) throws SQLException {
        final PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setPortNumbers(new int[]{connectionInfo.port()});
        ds.setDatabaseName(connectionInfo.dbName());
        ds.setUser(connectionInfo.user());

        final Set<Entry<String, String>> properties = connectionInfo.properties().entrySet();
        for (final Entry<String, String> property : properties) ds.setProperty(property.getKey(), property.getValue());

        return ds;
    }

    /**
     * Creates a new database and returns it as a {@link DataSource}.
     * <p>
     * No two invocations will return the same database.
     *
     * @return a {@link DataSource} for a newly created database
     * @throws SQLException if the database cannot be created or the data source cannot be configured
     */
    @SuppressWarnings("unused")
    public DataSource createDataSource() throws SQLException {
        return createDataSourceFromConnectionInfo(createNewDatabase());
    }

    private String getJdbcUri(final DbInfo db) {
        final String additionalParameters = db.getProperties().entrySet().stream()
                .map(e -> String.format("&%s=%s", e.getKey(), e.getValue()))
                .collect(Collectors.joining());
        return String.format(JDBC_FORMAT, db.getPort(), db.getDbName(), db.getUser()) + additionalParameters;
    }

    /**
     * Returns configuration tweaks suitable for wiring into an otj-jdbc
     * {@code DatabaseModule}.
     * <p>
     * The returned map contains entries for the JDBC URI and username
     * under keys derived from the supplied module name.
     *
     * @param dbModuleName the logical name of the database module
     * @return a map of configuration properties for the given module name
     * @throws SQLException if a new database cannot be created
     */
    @SuppressWarnings("unused")
    public Map<String, String> getConfigurationTweak(final String dbModuleName) throws SQLException {
        ensureOpen();
        final DbInfo db = sharedCluster.getNextDb();
        final Map<String, String> result = new HashMap<>();
        result.put("ot.db." + dbModuleName + ".uri", getJdbcUri(db));
        result.put("ot.db." + dbModuleName + ".ds.user", db.getUser());
        return result;
    }

    /**
     * Releases this provider's handle to the shared prepared cluster.
     * <p>
     * When the last provider referencing the cluster is closed, the underlying
     * preparer pipeline and embedded PostgreSQL instance are also closed.
     *
     * @throws IOException if the underlying cluster cannot be closed cleanly
     */
    @Override
    public void close() throws IOException {
        if (closed.getAndSet(true)) return;
        CLUSTER_MANAGER.release(sharedCluster);
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("PreparedDbProvider has been closed");
    }

    /**
     * Coordinates access to shared prepared database clusters.
     */
    private static final class ClusterManager {
        private final Map<ClusterKey, SharedCluster> clusters = new HashMap<>();

        /**
         * Each schema set has its own database cluster. The template1 database has the schema preloaded so that
         * each test case need only create a new database and not re-invoke the preparer.
         *
         * @param preparer the preparer used to initialize the cluster template
         * @param customizers customizations to apply to the embedded Postgres builder
         * @return a shared cluster for the given preparer configuration
         * @throws IOException if the embedded PostgreSQL instance cannot be started or closed cleanly on failure
         * @throws SQLException if the preparer fails while initializing the template database
         */
        private synchronized SharedCluster acquireCluster(final DatabasePreparer preparer, final Iterable<Consumer<Builder>> customizers) throws IOException, SQLException {
            final ClusterKey key = new ClusterKey(preparer, customizers);
            final SharedCluster existing = clusters.get(key);
            if (existing != null) {
                existing.acquire();
                return existing;
            }

            final Builder builder = EmbeddedPostgres.builder();
            customizers.forEach(c -> c.accept(builder));
            final EmbeddedPostgres pg = builder.start();
            try {
                preparer.prepare(pg.getTemplateDatabase());
            } catch (SQLException | RuntimeException e) {
                try {
                    pg.close();
                } catch (final IOException closeException) {
                    e.addSuppressed(closeException);
                }
                throw e;
            }
            final SharedCluster created = new SharedCluster(key, new PrepPipeline(pg).start());
            clusters.put(key, created);
            return created;
        }

        /**
         * Releases a shared cluster previously acquired by a provider.
         *
         * @param sharedCluster the shared cluster to release
         * @throws IOException if closing the last remaining cluster handle fails
         */
        private void release(final SharedCluster sharedCluster) throws IOException {
            final SharedCluster clusterToClose;
            synchronized (this) {
                if (sharedCluster.release() == 0) {
                    clusters.remove(sharedCluster.getKey(), sharedCluster);
                    clusterToClose = sharedCluster;
                } else {
                    clusterToClose = null;
                }
            }

            if (clusterToClose != null) clusterToClose.close();
        }
    }

    /**
     * Represents a managed shared prepared cluster.
     */
    private static final class SharedCluster implements AutoCloseable {
        private final ClusterKey key;
        private final PrepPipeline preparerPipeline;
        private int refCount = 1;
        private final AtomicBoolean closed = new AtomicBoolean();

        private SharedCluster(final ClusterKey key, final PrepPipeline preparerPipeline) {
            this.key = key;
            this.preparerPipeline = preparerPipeline;
        }

        private void acquire() {
            if (closed.get()) throw new IllegalStateException("Shared cluster has already been closed");
            refCount++;
        }

        private int release() {
            if (refCount == 0) throw new IllegalStateException("Shared cluster reference count is already zero");
            return --refCount;
        }

        private ClusterKey getKey() {
            return key;
        }

        private DbInfo getNextDb() throws SQLException {
            if (closed.get()) throw new IllegalStateException("Shared cluster has already been closed");
            return preparerPipeline.getNextDb();
        }

        /**
         * Closes the shared cluster and its preparer pipeline.
         *
         * @throws IOException if the underlying embedded PostgreSQL instance cannot be closed cleanly
         */
        @Override
        public void close() throws IOException {
            if (closed.getAndSet(true)) return;
            preparerPipeline.close();
        }
    }

    /**
     * Spawns a background thread that prepares databases ahead of time for speed, and then uses a
     * synchronous queue to hand the prepared databases off to test cases.
     */
    private static final class PrepPipeline implements Runnable, AutoCloseable {
        private final EmbeddedPostgres pg;
        private final SynchronousQueue<DbInfo> nextDatabase = new SynchronousQueue<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private Thread runningThread;

        private PrepPipeline(final EmbeddedPostgres pg) {
            this.pg = pg;
        }

        private PrepPipeline start() {
            final Thread t = new Thread(this);
            t.setDaemon(true); // so it doesn't block JVM shutdown
            t.setName("cluster-" + pg + "-preparer");
            runningThread = t;
            t.start();
            return this;
        }

        private DbInfo getNextDb() throws SQLException {
            if (closed.get()) throw new IllegalStateException("Preparation pipeline has been closed");
            try {
                final DbInfo next = nextDatabase.take();
                if (next.getException() != null) throw next.getException();
                return next;
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        @SuppressWarnings("SameParameterValue")
        private static void create(final DataSource connectDb, final String dbName, final String userName) throws SQLException {
            if (dbName == null) throw new IllegalStateException("the database name must not be null!");
            if (userName == null) throw new IllegalStateException("the user name must not be null!");

            try (final Connection c = connectDb.getConnection();
                    final PreparedStatement stmt = c.prepareStatement(String.format("CREATE DATABASE %s OWNER %s ENCODING = 'utf8'", dbName, userName))) {
                stmt.execute();
            }
        }

        @SuppressWarnings("SameParameterValue")
        private static String randomAlphabetic(final int length) {
            return SECURE_RANDOM
                    .ints(length, 0, ALPHABET.length())
                    .mapToObj(ALPHABET::charAt)
                    .collect(StringBuilder::new, StringBuilder::append, StringBuilder::append)
                    .toString()
                    .toLowerCase(Locale.ENGLISH);
        }

        @SuppressWarnings("java:S2189")
        @Override
        public void run() {
            while (!closed.get()) {
                final String newDbName = randomAlphabetic(12);
                SQLException failure = null;
                try {
                    create(pg.getPostgresDatabase(), newDbName, "postgres");
                } catch (final SQLException e) {
                    failure = e;
                }
                try {
                    if (failure == null) {
                        nextDatabase.put(DbInfo.ok(newDbName, pg.getPort(), "postgres", pg.getConnectConfig()));
                    } else {
                        nextDatabase.put(DbInfo.error(failure));
                    }
                } catch (final InterruptedException _) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        /**
         * Stops the preparation thread and closes the underlying embedded PostgreSQL instance.
         *
         * @throws IOException if the underlying embedded PostgreSQL instance cannot be closed cleanly
         */
        @Override
        public void close() throws IOException {
            if (closed.getAndSet(true)) return;
            if (runningThread != null) runningThread.interrupt();
            pg.close();
        }
    }

    private record ClusterKey(DatabasePreparer preparer, Builder builder) {
        private ClusterKey(final DatabasePreparer preparer, final Iterable<Consumer<Builder>> customizers) {
            this(preparer, createBuilder(customizers));
        }

        private static Builder createBuilder(final Iterable<Consumer<Builder>> customizers) {
            final Builder builder = EmbeddedPostgres.builder();
            customizers.forEach(c -> c.accept(builder));
            return builder;
        }
    }
}
