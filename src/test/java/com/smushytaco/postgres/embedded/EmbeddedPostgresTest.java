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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddedPostgresTest {
    @TempDir
    Path tf;

    @SuppressWarnings("SqlNoDataSourceInspection")
    @Test
    void testEmbeddedPg() throws IOException, SQLException {
        try (final EmbeddedPostgres pg = EmbeddedPostgres.start();
                final Connection c = pg.getPostgresDatabase().getConnection();
                final Statement s = c.createStatement();
                final ResultSet rs = s.executeQuery("SELECT 1")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertFalse(rs.next());
        }
    }

    @SuppressWarnings("SqlNoDataSourceInspection")
    @Test
    void testEmbeddedPgCreationWithNestedDataDirectory() throws IOException, SQLException {
        final Path dataDir = Files.createDirectories(tf.resolve("data-dir-parent").resolve("data-dir"));
        try (final EmbeddedPostgres pg = EmbeddedPostgres.builder()
                .setDataDirectory(dataDir)
                .setDataDirectoryCustomizer(dd -> {
                    assertEquals(dataDir, dd);
                    final Path pgConfigFile = dd.resolve("postgresql.conf");
                    assertTrue(Files.isRegularFile(pgConfigFile));
                    try {
                        final String pgConfig = Files.readString(pgConfigFile)
                                .replaceFirst("#?listen_addresses\\s*=\\s*'localhost'", "listen_addresses = '*'");
                        Files.writeString(pgConfigFile, pgConfig);
                    } catch (final IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .start()) {
            try (final Connection connection = pg.getPostgresDatabase().getConnection();
                    final Statement statement = connection.createStatement();
                    final ResultSet rs = statement.executeQuery("SHOW listen_addresses;")) {
                rs.next();
                assertEquals("*", rs.getString(1));
            }
        }
    }
}
