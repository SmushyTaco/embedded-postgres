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
import com.smushytaco.postgres.embedded.DatabaseConnectionPreparer;
import com.smushytaco.postgres.embedded.DatabasePreparer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.sql.DataSource;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PreparedDbTest {
    private final DatabasePreparer prepA = new SimplePreparer("a");
    private final DatabasePreparer prepB = new SimplePreparer("b");

    @SuppressWarnings("JUnitMalformedDeclaration")
    @RegisterExtension
    final PreparedDbExtension dbA1 = EmbeddedPostgresExtension.preparedDatabase(prepA);
    @SuppressWarnings("JUnitMalformedDeclaration")
    @RegisterExtension
    final PreparedDbExtension dbA2 = EmbeddedPostgresExtension.preparedDatabase(prepA);
    @SuppressWarnings("JUnitMalformedDeclaration")
    @RegisterExtension
    final PreparedDbExtension dbB1 = EmbeddedPostgresExtension.preparedDatabase(prepB);

    @SuppressWarnings("SqlNoDataSourceInspection")
    @Test
    void testDbs() throws SQLException {
        try (final Connection c = dbA1.getTestDatabase().getConnection();
                final Statement stmt = c.createStatement()) {
            commonAssertion(stmt);
        }
        try (final Connection c = dbA2.getTestDatabase().getConnection();
                final PreparedStatement stmt = c.prepareStatement("SELECT count(1) FROM a");
                final ResultSet rs = stmt.executeQuery()) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        }
        try (final Connection c = dbB1.getTestDatabase().getConnection();
                final PreparedStatement stmt = c.prepareStatement("SELECT * FROM b")) {
            stmt.execute();
        }
    }

    @SuppressWarnings("SqlNoDataSourceInspection")
    private void commonAssertion(final Statement stmt) throws SQLException {
        stmt.execute("INSERT INTO a VALUES(1)");
        try (final ResultSet rs = stmt.executeQuery("SELECT COUNT(1) FROM a")) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void testEquivalentAccess() throws SQLException {
        final ConnectionInfo dbInfo = dbA1.getConnectionInfo();
        final DataSource dataSource = dbA1.getTestDatabase();
        try (final Connection c = dataSource.getConnection(); Statement stmt = c.createStatement()) {
            commonAssertion(stmt);
            assertEquals(dbInfo.user(), c.getMetaData().getUserName());
        }
    }

    @Test
    void testDbUri() throws SQLException {
        try (final Connection c = DriverManager.getConnection(dbA1.getDbProvider().createDatabase());
                final Statement stmt = c.createStatement()) {
            commonAssertion(stmt);
        }
    }

    @SuppressWarnings("ClassCanBeRecord")
    static class SimplePreparer implements DatabaseConnectionPreparer {
        private final String name;

        public SimplePreparer(final String name) {
            this.name = name;
        }

        @Override
        public void prepare(final Connection conn) throws SQLException {
            try (final PreparedStatement stmt = conn.prepareStatement(String.format("CREATE TABLE %s (foo int)", name))) {
                stmt.execute();
            }
        }
    }
}
