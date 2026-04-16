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
import java.util.HashMap;
import java.util.Map;

import static java.util.Collections.unmodifiableMap;

/**
 * Package-private {@link DbInfo} record implementation.
 *
 * @param dbName     the name of the created database
 * @param port       the port on which PostgreSQL is listening
 * @param user       the database user that owns the database
 * @param properties the connection properties associated with the database
 * @param ex         the exception that caused creation to fail, if any
 */
record DefaultDbInfo(String dbName, int port, String user, Map<String, String> properties, SQLException ex) implements DbInfo {
    /**
     * Creates a {@link DefaultDbInfo} with defensively copied connection properties.
     *
     * @param dbName     the name of the created database
     * @param port       the port on which PostgreSQL is listening
     * @param user       the database user that owns the database
     * @param properties the connection properties associated with the database
     * @param ex         the exception that caused creation to fail, if any
     */
    DefaultDbInfo(final String dbName, final int port, final String user, final Map<String, String> properties, final SQLException ex) {
        this.dbName = dbName;
        this.port = port;
        this.user = user;
        this.properties = new HashMap<>(properties);
        this.ex = ex;
    }

    @Override
    public int getPort() {
        return port;
    }

    @Override
    public String getDbName() {
        return dbName;
    }

    @Override
    public String getUser() {
        return user;
    }

    /**
     * Returns an unmodifiable view of the connection properties associated
     * with this database.
     *
     * @return connection properties, never {@code null}
     */
    @Override
    public Map<String, String> getProperties() {
        return unmodifiableMap(properties);
    }

    @Override
    public SQLException getException() {
        return ex;
    }
}
