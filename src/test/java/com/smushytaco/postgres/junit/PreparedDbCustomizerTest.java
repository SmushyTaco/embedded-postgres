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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PreparedDbCustomizerTest {
    private static final DatabasePreparer EMPTY_PREPARER = _ -> {};

    @SuppressWarnings("JUnitMalformedDeclaration")
    @RegisterExtension
    final PreparedDbExtension dbA1 = EmbeddedPostgresExtension.preparedDatabase(EMPTY_PREPARER);
    @SuppressWarnings("JUnitMalformedDeclaration")
    @RegisterExtension
    final PreparedDbExtension dbA2 = EmbeddedPostgresExtension.preparedDatabase(EMPTY_PREPARER).customize(_ -> {});
    @SuppressWarnings("JUnitMalformedDeclaration")
    @RegisterExtension
    final PreparedDbExtension dbA3 = EmbeddedPostgresExtension.preparedDatabase(EMPTY_PREPARER).customize(builder -> builder.setPGStartupWait(Duration.ofSeconds(10)));
    @SuppressWarnings("JUnitMalformedDeclaration")
    @RegisterExtension
    final PreparedDbExtension dbB1 = EmbeddedPostgresExtension.preparedDatabase(EMPTY_PREPARER).customize(builder -> builder.setPGStartupWait(Duration.ofSeconds(11)));
    @SuppressWarnings("JUnitMalformedDeclaration")
    @RegisterExtension
    final PreparedDbExtension dbB2 = EmbeddedPostgresExtension.preparedDatabase(EMPTY_PREPARER).customize(builder -> builder.setPGStartupWait(Duration.ofSeconds(11)));

    @Test
    void testCustomizers() {
        final int dbA1Port = dbA1.getConnectionInfo().port();
        final int dbA2Port = dbA2.getConnectionInfo().port();
        final int dbA3Port = dbA3.getConnectionInfo().port();

        assertEquals(dbA1Port, dbA2Port);
        assertEquals(dbA1Port, dbA3Port);

        final int dbB1Port = dbB1.getConnectionInfo().port();
        final int dbB2Port = dbB2.getConnectionInfo().port();

        assertEquals(dbB1Port, dbB2Port);

        assertNotEquals(dbA1Port, dbB2Port);
    }
}
