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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PreparedDbProviderFailureCleanupTest {
    @AfterEach
    void tearDown() throws IOException {
        PreparedDbProvider.closeAll();
    }

    @SuppressWarnings({"SqlNoDataSourceInspection", "java:S5778"})
    @Test
    void testFailedPreparationDoesNotLeakStartedPostgresInstance() throws IOException, SQLException {
        final int port = detectFreePort();

        @SuppressWarnings("resource") final RuntimeException exception = assertThrows(RuntimeException.class, () -> PreparedDbProvider.forPreparer(_ -> {
            throw new SQLException("boom");
        }, List.of(builder -> {
            builder.setPort(port);
            builder.setRegisterShutdownHook(false);
        }), ClusterRetentionPolicy.CLOSE_ON_LAST_RELEASE));
        assertInstanceOf(SQLException.class, exception.getCause());

        try (final EmbeddedPostgres pg = EmbeddedPostgres.builder()
                .setPort(port)
                .setRegisterShutdownHook(false)
                .start();
                final Connection connection = pg.getPostgresDatabase().getConnection();
                final Statement statement = connection.createStatement();
                final ResultSet resultSet = statement.executeQuery("SELECT 1")) {
            assertTrue(resultSet.next());
            assertEquals(1, resultSet.getInt(1));
        }
    }

    private static int detectFreePort() throws IOException {
        try (final ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
