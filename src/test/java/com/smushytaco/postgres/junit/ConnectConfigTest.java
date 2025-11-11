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
import com.smushytaco.postgres.embedded.DatabasePreparer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.postgresql.ds.common.BaseDataSource;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConnectConfigTest {
    private final CapturingDatabasePreparer preparer = new CapturingDatabasePreparer();

    @SuppressWarnings("JUnitMalformedDeclaration")
    @RegisterExtension
    final PreparedDbExtension db = EmbeddedPostgresExtension.preparedDatabase(preparer)
            .customize(builder -> builder.setConnectConfig("connectTimeout", "20"));

    @Test
    void test() throws SQLException {
        final ConnectionInfo connectionInfo = db.getConnectionInfo();

        final Map<String, String> properties = connectionInfo.properties();
        assertEquals(1, properties.size());
        assertEquals("20", properties.get("connectTimeout"));

        final BaseDataSource testDatabase = (BaseDataSource) db.getTestDatabase();
        assertEquals("20", testDatabase.getProperty("connectTimeout"));

        final BaseDataSource preparerDataSource = (BaseDataSource) preparer.getDataSource();
        assertEquals("20", preparerDataSource.getProperty("connectTimeout"));
    }

    private static class CapturingDatabasePreparer implements DatabasePreparer {
        private DataSource dataSource;

        @Override
        public void prepare(final DataSource ds) {
            if (dataSource != null) throw new IllegalStateException("database preparer has been called multiple times");
            dataSource = ds;
        }

        public DataSource getDataSource() {
            return dataSource;
        }
    }
}
