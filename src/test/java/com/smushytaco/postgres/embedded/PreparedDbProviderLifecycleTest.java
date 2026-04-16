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

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreparedDbProviderLifecycleTest {
    @AfterEach
    void tearDown() throws IOException {
        PreparedDbProvider.closeAll();
    }

    @Test
    void testKeepUntilCloseAllRetainsClusterUntilExplicitGlobalClose() throws SQLException, IOException {
        final CountingPreparer preparer = new CountingPreparer();

        final PreparedDbProvider provider1 = PreparedDbProvider.forPreparer(preparer, ClusterRetentionPolicy.KEEP_UNTIL_CLOSE_ALL);
        provider1.createNewDatabase();
        assertEquals(1, preparer.getInvocationCount());

        provider1.close();
        assertThrows(IllegalStateException.class, provider1::createNewDatabase);

        final PreparedDbProvider provider2 = PreparedDbProvider.forPreparer(preparer, ClusterRetentionPolicy.KEEP_UNTIL_CLOSE_ALL);
        provider2.createNewDatabase();
        assertEquals(1, preparer.getInvocationCount());

        PreparedDbProvider.closeAll();
        assertThrows(IllegalStateException.class, provider2::createNewDatabase);

        final PreparedDbProvider provider3 = PreparedDbProvider.forPreparer(preparer, ClusterRetentionPolicy.KEEP_UNTIL_CLOSE_ALL);
        provider3.createNewDatabase();
        assertEquals(2, preparer.getInvocationCount());
        provider3.close();
    }

    @Test
    void testCloseOnLastReleaseRecreatesClusterAfterFinalClose() throws SQLException, IOException {
        final CountingPreparer preparer = new CountingPreparer();

        final PreparedDbProvider provider1 = PreparedDbProvider.forPreparer(preparer, ClusterRetentionPolicy.CLOSE_ON_LAST_RELEASE);
        final PreparedDbProvider provider2 = PreparedDbProvider.forPreparer(preparer, ClusterRetentionPolicy.CLOSE_ON_LAST_RELEASE);

        provider1.createNewDatabase();
        provider2.createNewDatabase();
        assertEquals(1, preparer.getInvocationCount());

        provider1.close();
        assertThrows(IllegalStateException.class, provider1::createNewDatabase);

        provider2.createNewDatabase();
        assertEquals(1, preparer.getInvocationCount());

        provider2.close();
        assertThrows(IllegalStateException.class, provider2::createNewDatabase);

        final PreparedDbProvider provider3 = PreparedDbProvider.forPreparer(preparer, ClusterRetentionPolicy.CLOSE_ON_LAST_RELEASE);
        provider3.createNewDatabase();
        assertEquals(2, preparer.getInvocationCount());
        provider3.close();
    }

    private static final class CountingPreparer implements DatabasePreparer {
        private final AtomicInteger invocationCount = new AtomicInteger();

        @Override
        public void prepare(final DataSource ds) {
            invocationCount.incrementAndGet();
        }

        private int getInvocationCount() {
            return invocationCount.get();
        }
    }
}
