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

import java.util.HashMap;
import java.util.Map;

import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableMap;

/**
 * Represents immutable connection details for an embedded PostgreSQL instance.
 * <p>
 * Contains the database name, port, user, and optional connection properties.
 * This record provides a lightweight way to pass around connection parameters
 * when interacting with embedded PostgreSQL components.
 *
 * @param dbName     the name of the database
 * @param port       the port number used to connect to the database
 * @param user       the username for the connection
 * @param properties additional connection properties (maybe empty)
 */
public record ConnectionInfo(String dbName, int port, String user, Map<String, String> properties) {
    /**
     * Creates a {@link ConnectionInfo} without any additional connection properties.
     *
     * @param dbName the name of the database
     * @param port   the port number used to connect to the database
     * @param user   the username for the connection
     */
    @SuppressWarnings("unused")
    public ConnectionInfo(final String dbName, final int port, final String user) {
        this(dbName, port, user, emptyMap());
    }

    /**
     * Creates a {@link ConnectionInfo} with optional connection properties.
     * <p>
     * The provided map of properties is defensively copied to preserve immutability.
     *
     * @param dbName     the name of the database
     * @param port       the port number used to connect to the database
     * @param user       the username for the connection
     * @param properties a map of connection properties (will be copied)
     */
    public ConnectionInfo(final String dbName, final int port, final String user, final Map<String, String> properties) {
        this.dbName = dbName;
        this.port = port;
        this.user = user;
        this.properties = new HashMap<>(properties);
    }

    /**
     * Returns an unmodifiable view of the connection properties.
     *
     * @return an unmodifiable map of connection properties
     */
    @Override
    public Map<String, String> properties() {
        return unmodifiableMap(properties);
    }
}
