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

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.DirectoryResourceAccessor;
import liquibase.resource.ResourceAccessor;

import javax.sql.DataSource;
import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

import static liquibase.database.DatabaseFactory.getInstance;

/**
 * A {@link DatabasePreparer} implementation that applies Liquibase migrations
 * to an embedded PostgreSQL database.
 * <p>
 * This preparer supports loading changelogs either from the classpath or from
 * a file system directory, and optionally runs only for specific {@link Contexts}.
 */
@SuppressWarnings("ClassCanBeRecord")
public final class LiquibasePreparer implements DatabasePreparer {
    private final String location;
    private final ResourceAccessor accessor;
    private final Contexts contexts;

    /**
     * Creates a {@link LiquibasePreparer} that loads a Liquibase changelog
     * from the specified classpath location.
     *
     * @param location the classpath path to the changelog file
     *                 (e.g., {@code "db/changelog/db.changelog-master.xml"})
     * @return a new {@link LiquibasePreparer} configured to use the changelog at the given location
     */
    public static LiquibasePreparer forClasspathLocation(final String location) {
        return forClasspathLocation(location, null);
    }

    /**
     * Creates a {@link LiquibasePreparer} that loads a Liquibase changelog
     * from the specified classpath location and applies only the provided contexts.
     *
     * @param location the classpath path to the changelog file
     * @param contexts the {@link Contexts} defining which Liquibase changesets should be applied
     * @return a new {@link LiquibasePreparer} configured with the given changelog and contexts
     */
    public static LiquibasePreparer forClasspathLocation(final String location, final Contexts contexts) {
        return new LiquibasePreparer(location, new ClassLoaderResourceAccessor(), contexts);
    }

    /**
     * Creates a {@link LiquibasePreparer} that loads a Liquibase changelog
     * from a changelog file on the file system.
     *
     * @param file the path to the Liquibase changelog file
     * @return a new {@link LiquibasePreparer} configured to use the changelog at the given file path
     */
    public static LiquibasePreparer forFile(final Path file) {
        return forFile(file, null);
    }

    /**
     * Creates a {@link LiquibasePreparer} that loads a Liquibase changelog
     * from a file on the file system and applies only the specified contexts.
     *
     * @param file the path to the Liquibase changelog file
     * @param contexts the {@link Contexts} defining which Liquibase changesets should be applied
     * @return a new {@link LiquibasePreparer} configured with the given changelog and contexts
     */
    public static LiquibasePreparer forFile(final Path file, final Contexts contexts) {
        if (file == null) throw new IllegalArgumentException("Missing file");
        final Path dir = file.getParent();
        if (dir == null) throw new IllegalArgumentException("Cannot get parent dir from file");

        try {
            return new LiquibasePreparer(file.getFileName().toString(), new DirectoryResourceAccessor(dir), contexts);
        } catch (final FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private LiquibasePreparer(final String location, final ResourceAccessor accessor, final Contexts contexts) {
        this.location = location;
        this.accessor = accessor;
        this.contexts = contexts != null ? contexts : new Contexts();
    }

    @Override
    public void prepare(final DataSource ds) throws SQLException {
        try (final Connection connection = ds.getConnection();
                final Database database = getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection))) {
            new Liquibase(location, accessor, database).update(contexts);
        } catch (final LiquibaseException e) {
            throw new SQLException(e);
        }
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final LiquibasePreparer that = (LiquibasePreparer) o;
        return Objects.equals(location, that.location)
                && Objects.equals(accessor, that.accessor)
                && Objects.equals(contexts.getContexts(), that.contexts.getContexts());
    }

    @Override
    public int hashCode() {
        return Objects.hash(location, accessor, contexts.getContexts());
    }
}
