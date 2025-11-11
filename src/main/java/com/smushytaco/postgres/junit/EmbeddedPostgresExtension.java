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

import com.smushytaco.postgres.embedded.DatabasePreparer;

/**
 * Utility class providing JUnit 6 extensions for managing embedded PostgreSQL instances.
 * <p>
 * It offers helpers to create either a single shared PostgreSQL instance or
 * a prepared instance initialized with a specific {@link DatabasePreparer}.
 */
public final class EmbeddedPostgresExtension {
    private EmbeddedPostgresExtension() {}

    /**
     * Creates a standard embedded PostgreSQL cluster without any schema or data initialization.
     *
     * @return a new {@link SingleInstancePostgresExtension} that manages a single PostgreSQL instance
     *         shared across all tests in the current JVM
     */
    public static SingleInstancePostgresExtension singleInstance() {
        return new SingleInstancePostgresExtension();
    }

    /**
     * Creates an extension that starts a shared PostgreSQL cluster and prepares it
     * using the specified {@link DatabasePreparer}.
     * <p>
     * Each test can access a clean database initialized according to the given preparer logic.
     *
     * @param preparer the {@link DatabasePreparer} that applies schema and data initialization
     * @return a new {@link PreparedDbExtension} configured with the given preparer
     */
    public static PreparedDbExtension preparedDatabase(final DatabasePreparer preparer) {
        return new PreparedDbExtension(preparer);
    }
}
