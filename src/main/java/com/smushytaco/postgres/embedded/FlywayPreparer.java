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

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;

import javax.sql.DataSource;
import java.util.*;

/**
 * A {@link DatabasePreparer} implementation that applies database schema migrations
 * using <a href="https://flywaydb.org/">Flyway</a>.
 * <p>
 * This preparer can be created either from classpath-based migration scripts or
 * a custom Flyway configuration map. It is typically used to initialize an
 * embedded PostgreSQL instance before running tests.
 */
@SuppressWarnings("ClassCanBeRecord")
public final class FlywayPreparer implements DatabasePreparer {
    private final FluentConfiguration configuration;
    private final List<String> locations;
    private final Map<String, String> properties;

    /**
     * Creates a {@link FlywayPreparer} configured to apply migrations from one or more
     * classpath locations.
     *
     * @param locations one or more classpath locations containing Flyway migration scripts
     *                  (e.g., {@code "db/migration"})
     * @return a new {@link FlywayPreparer} configured to run migrations from the specified locations
     */
    public static FlywayPreparer forClasspathLocation(final String... locations) {
        return new FlywayPreparer(Flyway.configure().locations(locations), Arrays.asList(locations), null);
    }

    /**
     * Creates a {@link FlywayPreparer} using a map of Flyway configuration properties.
     * <p>
     * This method allows advanced customization of Flyway’s behavior, such as migration
     * locations, schema management, and transactional behavior.
     *
     * @param configuration a map of Flyway configuration properties (e.g., {@code "flyway.locations"},
     *                      {@code "flyway.sqlMigrationPrefix"})
     * @return a new {@link FlywayPreparer} configured according to the specified properties
     */
    @SuppressWarnings("unused")
    public static FlywayPreparer fromConfiguration(final Map<String, String> configuration) {
        return new FlywayPreparer(Flyway.configure().configuration(configuration), null, new HashMap<>(configuration));
    }

    private FlywayPreparer(final FluentConfiguration configuration, final List<String> locations, final Map<String, String> properties) {
        this.configuration = configuration;
        this.locations = locations;
        this.properties = properties;
    }

    @Override
    public void prepare(final DataSource ds) {
        configuration.dataSource(ds).load().migrate();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final FlywayPreparer that = (FlywayPreparer) o;
        return Objects.equals(locations, that.locations) && Objects.equals(properties, that.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(locations, properties);
    }
}
