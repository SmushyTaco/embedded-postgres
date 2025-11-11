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

package com.smushytaco.postgres.junit;

import com.smushytaco.postgres.embedded.ConnectionInfo;
import com.smushytaco.postgres.embedded.DatabasePreparer;
import com.smushytaco.postgres.embedded.EmbeddedPostgres;
import com.smushytaco.postgres.embedded.PreparedDbProvider;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.extension.*;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * JUnit 6 extension that provisions PostgreSQL test databases using
 * {@link PreparedDbProvider} and a {@link DatabasePreparer}.
 * <p>
 * Depending on how it is used, the same prepared cluster can be shared
 * per test class or each test can get its own fresh database instance.
 */
public class PreparedDbExtension implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {
    private final DatabasePreparer preparer;
    private boolean perClass = false;
    private volatile DataSource dataSource;
    private volatile PreparedDbProvider provider;
    private volatile ConnectionInfo connectionInfo;

    private final List<Consumer<EmbeddedPostgres.Builder>> builderCustomizers = new CopyOnWriteArrayList<>();

    PreparedDbExtension(final DatabasePreparer preparer) {
        if (preparer == null) throw new IllegalStateException("null preparer");
        this.preparer = preparer;
    }

    /**
     * Registers a customization callback for the underlying
     * {@link EmbeddedPostgres.Builder} before the embedded cluster is started.
     * <p>
     * This can be used to tweak server configuration, ports, or other
     * low-level settings prior to database preparation.
     *
     * @param customizer a consumer that mutates the {@link EmbeddedPostgres.Builder}
     * @return this {@link PreparedDbExtension} instance for method chaining
     * @throws AssertionError if the extension has already been started
     */
    public PreparedDbExtension customize(final Consumer<EmbeddedPostgres.Builder> customizer) {
        if (dataSource != null) throw new AssertionError("already started");
        builderCustomizers.add(customizer);
        return this;
    }

    @Override
    public void beforeAll(@NonNull final ExtensionContext extensionContext) throws SQLException {
        provider = PreparedDbProvider.forPreparer(preparer, builderCustomizers);
        connectionInfo = provider.createNewDatabase();
        dataSource = provider.createDataSourceFromConnectionInfo(connectionInfo);
        perClass = true;
    }

    @Override
    public void afterAll(@NonNull final ExtensionContext extensionContext) {
        dataSource = null;
        connectionInfo = null;
        provider = null;
        perClass = false;
    }

    @Override
    public void beforeEach(@NonNull final ExtensionContext extensionContext) throws SQLException {
        if (perClass) return;
        provider = PreparedDbProvider.forPreparer(preparer, builderCustomizers);
        connectionInfo = provider.createNewDatabase();
        dataSource = provider.createDataSourceFromConnectionInfo(connectionInfo);
    }

    @Override
    public void afterEach(@NonNull final ExtensionContext extensionContext) {
        if (perClass) return;
        dataSource = null;
        connectionInfo = null;
        provider = null;
    }

    /**
     * Returns the {@link DataSource} for the database associated with the
     * current test class or test method, depending on how the extension
     * is configured.
     *
     * @return the prepared test database {@link DataSource}
     * @throws AssertionError if the extension has not been initialized yet
     */
    public DataSource getTestDatabase() {
        if (dataSource == null) throw new AssertionError("not initialized");
        return dataSource;
    }

    /**
     * Returns connection details for the currently prepared test database,
     * including database name, port, user, and connection properties.
     *
     * @return the {@link ConnectionInfo} for the active test database
     * @throws AssertionError if the extension has not been initialized yet
     */
    public ConnectionInfo getConnectionInfo() {
        if (connectionInfo == null) throw new AssertionError("not initialized");
        return connectionInfo;
    }

    /**
     * Returns the underlying {@link PreparedDbProvider} used by this
     * extension to create and prepare databases.
     *
     * @return the backing {@link PreparedDbProvider}
     * @throws AssertionError if the extension has not been initialized yet
     */
    public PreparedDbProvider getDbProvider() {
        if (provider == null) throw new AssertionError("not initialized");
        return provider;
    }
}
