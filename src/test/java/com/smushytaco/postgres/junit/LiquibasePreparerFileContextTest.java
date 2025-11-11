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

import com.smushytaco.postgres.embedded.LiquibasePreparer;
import liquibase.Contexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiquibasePreparerFileContextTest {
    @SuppressWarnings("JUnitMalformedDeclaration")
    @RegisterExtension
    final PreparedDbExtension db = EmbeddedPostgresExtension.preparedDatabase(LiquibasePreparer.forFile(Path.of("src")
            .resolve("test")
            .resolve("resources")
            .resolve("liqui")
            .resolve("master-test.xml"), new Contexts("test")));

    @SuppressWarnings("SqlNoDataSourceInspection")
    @Test
    void testEmptyTables() throws SQLException {
        try (final Connection c = db.getTestDatabase().getConnection();
                final Statement s = c.createStatement();
                final ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM foo")) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        }
    }
}
