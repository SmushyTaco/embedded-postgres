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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * JUnit 6 extension that provisions PostgreSQL test databases using
 * {@link PreparedDbProvider} and a {@link DatabasePreparer}.
 * <p>
 * Depending on how it is used, the same prepared cluster can be shared
 * per test class or each test can get its own fresh database instance.
 */
public class PreparedDbExtension implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {
    private final Object stateKey = new Object();

    private final DatabasePreparer preparer;
    private final List<Consumer<EmbeddedPostgres.Builder>> builderCustomizers = new CopyOnWriteArrayList<>();

    private final AtomicBoolean started = new AtomicBoolean(false);

    private final ThreadLocal<State> current = new ThreadLocal<>();

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
        if (started.get()) throw new AssertionError("already started");
        builderCustomizers.add(customizer);
        return this;
    }

    @SuppressWarnings("DuplicatedCode")
    @Override
    public void beforeAll(@NonNull final ExtensionContext ctx) throws SQLException {
        final ExtensionContext.Store classStore = classStore(ctx);
        State state = classStore.get(stateKey, State.class);
        if (state == null) {
            state = createState();
            classStore.put(stateKey, state);
            started.set(true);
        }
        current.set(state);
    }

    @Override
    public void afterAll(@NonNull final ExtensionContext ctx) {
        current.remove();
    }

    @SuppressWarnings("DuplicatedCode")
    @Override
    public void beforeEach(@NonNull final ExtensionContext ctx) throws SQLException {
        final ExtensionContext.Store classStore = classStore(ctx);
        State state = classStore.get(stateKey, State.class);
        if (state == null) {
            final ExtensionContext.Store methodStore = methodStore(ctx);
            state = methodStore.get(stateKey, State.class);
            if (state == null) {
                state = createState();
                methodStore.put(stateKey, state);
                started.set(true);
            }
        }
        current.set(state);
    }

    @Override
    public void afterEach(@NonNull final ExtensionContext ctx) {
        current.remove();
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
        final State s = current.get();
        if (s == null) throw new AssertionError("not initialized");
        return s.dataSource;
    }

    /**
     * Returns connection details for the currently prepared test database,
     * including database name, port, user, and connection properties.
     *
     * @return the {@link ConnectionInfo} for the active test database
     * @throws AssertionError if the extension has not been initialized yet
     */
    public ConnectionInfo getConnectionInfo() {
        final State s = current.get();
        if (s == null) throw new AssertionError("not initialized");
        return s.connectionInfo;
    }

    /**
     * Returns the underlying {@link PreparedDbProvider} used by this
     * extension to create and prepare databases.
     *
     * @return the backing {@link PreparedDbProvider}
     * @throws AssertionError if the extension has not been initialized yet
     */
    public PreparedDbProvider getDbProvider() {
        final State s = current.get();
        if (s == null) throw new AssertionError("not initialized");
        return s.provider;
    }

    private State createState() throws SQLException {
        final PreparedDbProvider provider = PreparedDbProvider.forPreparer(preparer, builderCustomizers);
        final ConnectionInfo connectionInfo = provider.createNewDatabase();
        return new State(provider, connectionInfo, provider.createDataSourceFromConnectionInfo(connectionInfo));
    }

    private static ExtensionContext.Store classStore(final ExtensionContext ctx) {
        return ctx.getStore(ExtensionContext.Namespace.create(PreparedDbExtension.class, ctx.getRequiredTestClass()));
    }

    private static ExtensionContext.Store methodStore(final ExtensionContext ctx) {
        return ctx.getStore(ExtensionContext.Namespace.create(PreparedDbExtension.class, ctx.getRequiredTestMethod()));
    }

    private record State(PreparedDbProvider provider, ConnectionInfo connectionInfo, DataSource dataSource) implements AutoCloseable {
        @Override
        public void close() {
            try {
                if (provider instanceof final AutoCloseable autoCloseable) autoCloseable.close();
            } catch (final Exception _) { /* noop */ }
            try {
                if (dataSource instanceof final AutoCloseable autoCloseable) autoCloseable.close();
            } catch (final Exception _) { /* noop */ }
        }
    }
}
