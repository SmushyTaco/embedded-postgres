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

import com.smushytaco.postgres.embedded.EmbeddedPostgres;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.extension.*;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * JUnit 6 extension that starts an {@link EmbeddedPostgres} for tests.
 *
 * <p>Behavior by registration style:
 * <ul>
 *   <li><b>static @RegisterExtension</b>: one Embedded Postgres per test class (created once, closed after the class)</li>
 *   <li><b>non-static @RegisterExtension</b>: fresh Embedded Postgres per test method (created {@literal &} closed per test)</li>
 * </ul>
 */
public class SingleInstancePostgresExtension implements BeforeAllCallback, AfterAllCallback, BeforeTestExecutionCallback, AfterTestExecutionCallback {
    private final Object stateKey = new Object();

    private final List<Consumer<EmbeddedPostgres.Builder>> builderCustomizers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean started = new AtomicBoolean(false);

    private final ThreadLocal<State> current = new ThreadLocal<>();

    SingleInstancePostgresExtension() {}

    @SuppressWarnings("DuplicatedCode")
    @Override
    public void beforeAll(@NonNull final ExtensionContext ctx) throws SQLException, IOException {
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
    public void beforeTestExecution(@NonNull final ExtensionContext ctx) throws SQLException, IOException {
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
    public void afterTestExecution(@NonNull final ExtensionContext ctx) {
        current.remove();
    }

    /**
     * Registers a customization callback for the {@link EmbeddedPostgres.Builder}
     * before the embedded PostgreSQL instance is started.
     * <p>
     * This allows fine-grained control over the configuration of the embedded
     * database, such as port, data directory, or startup parameters.
     *
     * @param customizer a consumer that modifies the {@link EmbeddedPostgres.Builder}
     * @return this {@link SingleInstancePostgresExtension} instance for chaining
     * @throws AssertionError if the embedded PostgreSQL instance has already been started
     */
    @SuppressWarnings("unused")
    public SingleInstancePostgresExtension customize(final Consumer<EmbeddedPostgres.Builder> customizer) {
        if (started.get()) throw new AssertionError("already started");
        builderCustomizers.add(customizer);
        return this;
    }

    /**
     * Returns the active {@link EmbeddedPostgres} for the current test scope.
     *
     * @return the active {@link EmbeddedPostgres} instance
     * @throws AssertionError if the extension has not been started yet
     */
    public EmbeddedPostgres getEmbeddedPostgres() {
        final State s = current.get();
        if (s == null) throw new AssertionError("not initialized");
        return s.epg;
    }

    @SuppressWarnings("java:S2095")
    private State createState() throws IOException, SQLException {
        final EmbeddedPostgres.Builder builder = EmbeddedPostgres.builder();
        builderCustomizers.forEach(c -> c.accept(builder));
        final EmbeddedPostgres epg = builder.start();
        return new State(epg, epg.getPostgresDatabase().getConnection());
    }

    private static ExtensionContext.Store classStore(final ExtensionContext ctx) {
        return ctx.getStore(ExtensionContext.Namespace.create(SingleInstancePostgresExtension.class, ctx.getRequiredTestClass()));
    }

    private static ExtensionContext.Store methodStore(final ExtensionContext ctx) {
        return ctx.getStore(ExtensionContext.Namespace.create(SingleInstancePostgresExtension.class, ctx.getRequiredTestMethod()));
    }

    private record State(EmbeddedPostgres epg, Connection postgresConnection) implements AutoCloseable {
        @Override
        public void close() {
            try {
                if (postgresConnection != null && !postgresConnection.isClosed()) postgresConnection.close();
            } catch (final Exception _) { /* noop */ }
            try {
                if (epg != null) epg.close();
            } catch (final Exception _) { /* noop */ }
        }
    }
}