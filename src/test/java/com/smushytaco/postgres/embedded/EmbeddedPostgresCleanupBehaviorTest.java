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
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(value = "ot.epg.working-dir", mode = ResourceAccessMode.READ_WRITE)
class EmbeddedPostgresCleanupBehaviorTest {
    private static final String WORKING_DIR_PROPERTY = "ot.epg.working-dir";
    private static final String OWNERSHIP_MARKER_FILE_NAME = ".embedded-postgres-owned";
    private static final String LOCK_FILE_NAME = "epg-lock";

    @TempDir
    Path tempDir;

    @SuppressWarnings("SqlNoDataSourceInspection")
    @Test
    void testDefaultDataDirectoryIsCreatedUnderManagedParentAndMarkedAsOwned() throws Exception {
        final AtomicReference<Path> createdDataDirectory = new AtomicReference<>();
        final AtomicReference<Path> ownershipMarker = new AtomicReference<>();

        withWorkingDirectory(tempDir, () -> {
            try (final EmbeddedPostgres pg = EmbeddedPostgres.builder()
                    .setDataDirectoryCustomizer(createdDataDirectory::set)
                    .start();
                    final Connection connection = pg.getPostgresDatabase().getConnection();
                    final Statement statement = connection.createStatement();
                    final ResultSet resultSet = statement.executeQuery("SELECT 1")) {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt(1));

                final Path dataDirectory = createdDataDirectory.get();
                assertTrue(dataDirectory.startsWith(tempDir));

                final Path marker = ownershipMarkerPathFor(dataDirectory);
                ownershipMarker.set(marker);
                assertTrue(Files.exists(marker));
                assertEquals(dataDirectory.toAbsolutePath().normalize().toString(), Files.readString(marker).trim());
            }
        });

        assertFalse(Files.exists(createdDataDirectory.get()));
        assertFalse(Files.exists(ownershipMarker.get()));
    }

    @Test
    void testStaleOwnedDirectoryWithoutLockFileIsDeletedOnStartup() throws Exception {
        final Path staleDirectory = tempDir.resolve("stale-without-lock");
        final Path ownershipMarker = createOwnershipMarker(staleDirectory);
        Files.createDirectories(staleDirectory);
        ageFile(ownershipMarker);

        withWorkingDirectory(tempDir, () -> {
            try (final var _ = EmbeddedPostgres.builder().start()) {
                assertFalse(Files.exists(staleDirectory));
                assertFalse(Files.exists(ownershipMarker));
            }
        });
    }

    @Test
    void testCleanupUsesOwnershipMarkerInsteadOfLockFile() throws Exception {
        final Path ownedStaleDirectory = tempDir.resolve("owned-stale");
        Files.createDirectories(ownedStaleDirectory);
        final Path ownedMarker = createOwnershipMarker(ownedStaleDirectory);
        Files.writeString(ownedStaleDirectory.resolve(LOCK_FILE_NAME), "");
        ageFile(ownedMarker);
        ageFile(ownedStaleDirectory.resolve(LOCK_FILE_NAME));

        final Path unownedStaleDirectory = tempDir.resolve("unowned-stale");
        Files.createDirectories(unownedStaleDirectory);
        Files.writeString(unownedStaleDirectory.resolve(LOCK_FILE_NAME), "");
        ageFile(unownedStaleDirectory.resolve(LOCK_FILE_NAME));

        withWorkingDirectory(tempDir, () -> {
            try (final var _ = EmbeddedPostgres.builder().start()) {
                assertFalse(Files.exists(ownedStaleDirectory));
                assertFalse(Files.exists(ownedMarker));
                assertTrue(Files.exists(unownedStaleDirectory));
                assertTrue(Files.exists(unownedStaleDirectory.resolve(LOCK_FILE_NAME)));
            }
        });
    }

    private static void withWorkingDirectory(final Path workingDirectory, final ThrowingRunnable action) throws Exception {
        final String previousValue = System.getProperty(WORKING_DIR_PROPERTY);
        System.setProperty(WORKING_DIR_PROPERTY, workingDirectory.toString());
        try {
            action.run();
        } finally {
            if (previousValue == null) {
                System.clearProperty(WORKING_DIR_PROPERTY);
            } else {
                System.setProperty(WORKING_DIR_PROPERTY, previousValue);
            }
        }
    }

    private static Path createOwnershipMarker(final Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory.getParent());
        final Path ownershipMarker = ownershipMarkerPathFor(dataDirectory);
        Files.writeString(ownershipMarker, dataDirectory.toAbsolutePath().normalize().toString());
        return ownershipMarker;
    }

    private static Path ownershipMarkerPathFor(final Path dataDirectory) {
        final Path normalizedPath = dataDirectory.toAbsolutePath().normalize();
        final Path parentDirectory = normalizedPath.getParent();
        if (parentDirectory == null) throw new IllegalStateException("could not determine parent directory for " + normalizedPath);
        return parentDirectory.resolve(ownershipMarkerNameFor(normalizedPath));
    }

    private static String ownershipMarkerNameFor(final Path dataDirectory) {
        final Path normalizedPath = dataDirectory.toAbsolutePath().normalize();
        try {
            final MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = messageDigest.digest(normalizedPath.toString().getBytes(StandardCharsets.UTF_8));
            return OWNERSHIP_MARKER_FILE_NAME + "-" + HexFormat.of().formatHex(hash);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is not available", e);
        }
    }

    private static void ageFile(final Path file) throws IOException {
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofMinutes(11))));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
