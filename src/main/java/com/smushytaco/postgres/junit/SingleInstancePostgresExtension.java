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
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * JUnit 6 extension that starts a single, reusable instance of {@link EmbeddedPostgres}
 * for the duration of a test case.
 * <p>
 * The same PostgreSQL process is reused for all tests within the same test execution,
 * and is automatically stopped after each test completes.
 */
public class SingleInstancePostgresExtension implements AfterTestExecutionCallback, BeforeTestExecutionCallback {
    private volatile EmbeddedPostgres epg;
    private volatile Connection postgresConnection;
    private final List<Consumer<EmbeddedPostgres.Builder>> builderCustomizers = new CopyOnWriteArrayList<>();

    SingleInstancePostgresExtension() {}

    @Override
    public void beforeTestExecution(@NonNull final ExtensionContext extensionContext) throws SQLException, IOException {
        epg = pg();
        postgresConnection = epg.getPostgresDatabase().getConnection();
    }

    private EmbeddedPostgres pg() throws IOException {
        final EmbeddedPostgres.Builder builder = EmbeddedPostgres.builder();
        builderCustomizers.forEach(c -> c.accept(builder));
        return builder.start();
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
        if (epg != null) throw new AssertionError("already started");
        builderCustomizers.add(customizer);
        return this;
    }

    /**
     * Returns the active {@link EmbeddedPostgres} instance started by this extension.
     * <p>
     * The instance is created lazily before the first test execution and remains
     * available for the entire lifetime of the test.
     *
     * @return the active {@link EmbeddedPostgres} instance
     * @throws AssertionError if the extension has not been started yet
     */
    public EmbeddedPostgres getEmbeddedPostgres() {
        final EmbeddedPostgres theEpg = this.epg;
        if (theEpg == null) throw new AssertionError("JUnit test not started yet!");
        return theEpg;
    }

    @Override
    public void afterTestExecution(@NonNull final ExtensionContext extensionContext) {
        try {
            postgresConnection.close();
        } catch (final SQLException e) {
            throw new AssertionError(e);
        }
        try {
            epg.close();
        } catch (final IOException e) {
            throw new AssertionError(e);
        }
    }
}
