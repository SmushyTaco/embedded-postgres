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

import java.sql.SQLException;
import java.util.Map;

/**
 * Holds metadata about a created database, including its name, port, user,
 * optional connection properties, and any failure that occurred during
 * creation.
 * <p>
 * This public interface exposes the database creation result without exposing
 * the concrete implementation type.
 */
public interface DbInfo {
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
    static DbInfo ok(final String dbName, final int port, final String user) {
        return ok(dbName, port, user, Map.of());
    }

    /**
     * Creates a successful {@link DbInfo} instance with additional
     * connection properties.
     *
     * @param dbName     the name of the created database
     * @param port       the port on which PostgreSQL is listening
     * @param user       the database user that owns the database
     * @param properties the connection properties associated with the database
     * @return a {@link DbInfo} representing a successful creation
     */
    static DbInfo ok(final String dbName, final int port, final String user, final Map<String, String> properties) {
        return new DefaultDbInfo(dbName, port, user, properties, null);
    }

    /**
     * Creates a {@link DbInfo} instance representing a failed attempt
     * to create a database.
     *
     * @param e the {@link SQLException} describing the failure
     * @return a {@link DbInfo} containing the failure information
     */
    static DbInfo error(final SQLException e) {
        return new DefaultDbInfo(null, -1, null, Map.of(), e);
    }

    /**
     * Returns the port of the PostgreSQL instance backing this database.
     *
     * @return the PostgreSQL port
     */
    int getPort();

    /**
     * Returns the name of the database.
     *
     * @return the database name, or {@code null} if creation failed
     */
    String getDbName();

    /**
     * Returns the user associated with this database.
     *
     * @return the database user, or {@code null} if creation failed
     */
    String getUser();

    /**
     * Returns an unmodifiable view of the connection properties associated
     * with this database.
     *
     * @return connection properties, never {@code null}
     */
    Map<String, String> getProperties();

    /**
     * Returns the {@link SQLException} that occurred during database
     * creation, if any.
     *
     * @return the exception that caused creation to fail, or {@code null} on success
     */
    @SuppressWarnings("unused")
    SQLException getException();

    /**
     * Indicates whether the database was created successfully.
     *
     * @return {@code true} if creation succeeded, {@code false} otherwise
     */
    default boolean isSuccess() {
        return getException() == null;
    }
}
