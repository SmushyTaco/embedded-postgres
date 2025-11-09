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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * A specialized {@link DatabasePreparer} that prepares a database by working directly
 * with an active JDBC {@link Connection}.
 * <p>
 * Implementations of this interface can perform initialization, schema setup,
 * or data population using the provided connection, instead of working
 * at the {@link javax.sql.DataSource} level.
 */
public interface DatabaseConnectionPreparer extends DatabasePreparer {

    @Override
    default void prepare(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection()) {
            prepare(c);
        }
    }

    /**
     * Prepares the database using a provided JDBC {@link Connection}.
     * <p>
     * This method is invoked with an open connection, allowing implementors
     * to execute SQL statements or perform other setup tasks directly.
     * The connection will be automatically closed by the caller after execution.
     *
     * @param conn an active JDBC connection to the target database
     * @throws SQLException if an error occurs while preparing the database
     */
    void prepare(Connection conn) throws SQLException;
}
