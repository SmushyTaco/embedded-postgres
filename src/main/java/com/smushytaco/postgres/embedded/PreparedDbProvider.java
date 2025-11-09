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
import org.apache.commons.lang3.RandomStringUtils;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.SynchronousQueue;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableMap;

/**
 * Provider for prepared PostgreSQL databases backed by an {@link EmbeddedPostgres}
 * instance and a {@link DatabasePreparer}.
 * <p>
 * Databases are created from a shared template cluster, allowing fast creation
 * of isolated schemas for tests or other ephemeral use-cases.
 */
public class PreparedDbProvider {
    private static final String JDBC_FORMAT = "jdbc:postgresql://localhost:%d/%s?user=%s";

    /**
     * Each database cluster's <code>template1</code> database has a unique set of schema
     * loaded so that the databases may be cloned.
     */
    private static final Map<ClusterKey, PrepPipeline> CLUSTERS = new HashMap<>();

    private final PrepPipeline dbPreparer;

    /**
     * Creates a {@link PreparedDbProvider} for the given {@link DatabasePreparer}
     * with no additional customizations of the underlying {@link EmbeddedPostgres.Builder}.
     *
     * @param preparer the database preparer used to initialize the template cluster
     * @return a new {@link PreparedDbProvider} instance
     */
    @SuppressWarnings("unused")
    public static PreparedDbProvider forPreparer(DatabasePreparer preparer) {
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
    public static PreparedDbProvider forPreparer(DatabasePreparer preparer, Iterable<Consumer<Builder>> customizers) {
        return new PreparedDbProvider(preparer, customizers);
    }

    private PreparedDbProvider(DatabasePreparer preparer, Iterable<Consumer<Builder>> customizers) {
        try {
            dbPreparer = createOrFindPreparer(preparer, customizers);
        } catch (final IOException | SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Each schema set has its own database cluster.  The template1 database has the schema preloaded so that
     * each test case need only create a new database and not re-invoke your preparer.
     */
    private static synchronized PrepPipeline createOrFindPreparer(DatabasePreparer preparer, Iterable<Consumer<Builder>> customizers) throws IOException, SQLException {
        final ClusterKey key = new ClusterKey(preparer, customizers);
        PrepPipeline result = CLUSTERS.get(key);
        if (result != null) {
            return result;
        }

        final Builder builder = EmbeddedPostgres.builder();
        customizers.forEach(c -> c.accept(builder));
        final EmbeddedPostgres pg = builder.start();
        preparer.prepare(pg.getTemplateDatabase());

        result = new PrepPipeline(pg).start();
        CLUSTERS.put(key, result);
        return result;
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
        return dbPreparer.getNextDb();
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

        Set<Entry<String, String>> properties = connectionInfo.properties().entrySet();
        for (Entry<String, String> property : properties) {
            ds.setProperty(property.getKey(), property.getValue());
        }

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

    private String getJdbcUri(DbInfo db) {
        String additionalParameters = db.getProperties().entrySet().stream()
                .map(e -> String.format("&%s=%s", e.getKey(), e.getValue()))
                .collect(Collectors.joining());
        return String.format(JDBC_FORMAT, db.port, db.dbName, db.user) + additionalParameters;
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
    public Map<String, String> getConfigurationTweak(String dbModuleName) throws SQLException {
        final DbInfo db = dbPreparer.getNextDb();
        final Map<String, String> result = new HashMap<>();
        result.put("ot.db." + dbModuleName + ".uri", getJdbcUri(db));
        result.put("ot.db." + dbModuleName + ".ds.user", db.user);
        return result;
    }

    /**
     * Spawns a background thread that prepares databases ahead of time for speed, and then uses a
     * synchronous queue to hand the prepared databases off to test cases.
     */
    private static class PrepPipeline implements Runnable {
        private final EmbeddedPostgres pg;
        private final SynchronousQueue<DbInfo> nextDatabase = new SynchronousQueue<>();

        PrepPipeline(EmbeddedPostgres pg) {
            this.pg = pg;
        }

        PrepPipeline start() {
            Thread t = new Thread(this);
            t.setDaemon(true); // so it doesn't block JVM shutdown
            t.setName("cluster-" + pg + "-preparer");
            t.start();
            return this;
        }


        DbInfo getNextDb() throws SQLException {
            try {
                final DbInfo next = nextDatabase.take();
                if (next.ex != null) {
                    throw next.ex;
                }
                return next;
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        @SuppressWarnings("SameParameterValue")
        private static void create(final DataSource connectDb, final String dbName, final String userName) throws SQLException {
            if (dbName == null) {
                throw new IllegalStateException("the database name must not be null!");
            }
            if (userName == null) {
                throw new IllegalStateException("the user name must not be null!");
            }

            try (Connection c = connectDb.getConnection();
                    PreparedStatement stmt = c.prepareStatement(String.format("CREATE DATABASE %s OWNER %s ENCODING = 'utf8'", dbName, userName))) {
                stmt.execute();
            }
        }

        @Override
        public void run() {
            while (true) {
                final String newDbName = RandomStringUtils.secure().nextAlphabetic(12).toLowerCase(Locale.ENGLISH);
                SQLException failure = null;
                try {
                    create(pg.getPostgresDatabase(), newDbName, "postgres");
                } catch (SQLException e) {
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
    }

    private static class ClusterKey {

        private final DatabasePreparer preparer;
        private final Builder builder;

        ClusterKey(DatabasePreparer preparer, Iterable<Consumer<Builder>> customizers) {
            this.preparer = preparer;
            this.builder = EmbeddedPostgres.builder();
            customizers.forEach(c -> c.accept(this.builder));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ClusterKey that = (ClusterKey) o;
            return Objects.equals(preparer, that.preparer) &&
                    Objects.equals(builder, that.builder);
        }

        @Override
        public int hashCode() {
            return Objects.hash(preparer, builder);
        }
    }

    /**
     * Holds metadata about a created database, including its name, port, user,
     * optional connection properties, and any failure that occurred during
     * creation.
     */
    @SuppressWarnings("ClassCanBeRecord")
    public static class DbInfo {
        /**
         * Creates a successful {@link DbInfo} instance without additional
         * connection properties.
         *
         * @param dbName the name of the created database
         * @param port   the port on which PostgreSQL is listening
         * @param user   the database user that owns the database
         * @return a {@link DbInfo} representing a successful creation
         */
        @SuppressWarnings("unused")
        public static DbInfo ok(final String dbName, final int port, final String user) {
            return ok(dbName, port, user, emptyMap());
        }

        private static DbInfo ok(final String dbName, final int port, final String user, final Map<String, String> properties) {
            return new DbInfo(dbName, port, user, properties, null);
        }

        /**
         * Creates a {@link DbInfo} instance representing a failed attempt
         * to create a database.
         *
         * @param e the {@link SQLException} describing the failure
         * @return a {@link DbInfo} containing the failure information
         */
        public static DbInfo error(SQLException e) {
            return new DbInfo(null, -1, null, emptyMap(), e);
        }

        private final String dbName;
        private final int port;
        private final String user;
        private final Map<String, String> properties;
        private final SQLException ex;

        private DbInfo(final String dbName, final int port, final String user, final Map<String, String> properties, final SQLException e) {
            this.dbName = dbName;
            this.port = port;
            this.user = user;
            this.properties = properties;
            this.ex = e;
        }

        /**
         * Returns the port of the PostgreSQL instance backing this database.
         *
         * @return the PostgreSQL port
         */
        public int getPort() {
            return port;
        }

        /**
         * Returns the name of the database.
         *
         * @return the database name, or {@code null} if creation failed
         */
        public String getDbName() {
            return dbName;
        }

        /**
         * Returns the user associated with this database.
         *
         * @return the database user, or {@code null} if creation failed
         */
        public String getUser() {
            return user;
        }

        /**
         * Returns an unmodifiable view of the connection properties
         * associated with this database.
         *
         * @return connection properties, never {@code null}
         */
        public Map<String, String> getProperties() {
            return unmodifiableMap(properties);
        }

        /**
         * Returns the {@link SQLException} that occurred during database
         * creation, if any.
         *
         * @return the exception that caused creation to fail, or {@code null} on success
         */
        public SQLException getException() {
            return ex;
        }

        /**
         * Indicates whether the database was created successfully.
         *
         * @return {@code true} if creation succeeded, {@code false} otherwise
         */
        public boolean isSuccess() {
            return ex == null;
        }
    }
}
