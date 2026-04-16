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

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddedPostgresShutdownHookTest {
    @Test
    void testExplicitCloseUnregistersShutdownHookAndRemainsIdempotent() throws IOException, NoSuchFieldException, IllegalAccessException {
        try (final EmbeddedPostgres pg = EmbeddedPostgres.builder()
                .setRegisterShutdownHook(true)
                .start()) {
            final AtomicReference<?> shutdownHook = getShutdownHook(pg);
            assertNotNull(shutdownHook.get());

            pg.close();
            assertNull(shutdownHook.get());
            assertDoesNotThrow(pg::close);
        }
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<Thread> getShutdownHook(final EmbeddedPostgres pg) throws NoSuchFieldException, IllegalAccessException {
        final Field shutdownHookField = EmbeddedPostgres.class.getDeclaredField("shutdownHook");
        shutdownHookField.setAccessible(true);
        return (AtomicReference<Thread>) shutdownHookField.get(pg);
    }
}
