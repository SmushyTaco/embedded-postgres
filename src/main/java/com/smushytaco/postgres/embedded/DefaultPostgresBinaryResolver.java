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

import com.smushytaco.postgres.util.ArchUtils;
import com.smushytaco.postgres.util.LinuxUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static java.lang.String.format;

/**
 * The default implementation of {@link PgBinaryResolver} that locates PostgreSQL
 * binaries based on the current operating system, CPU architecture, and (for Linux)
 * distribution.
 * <p>
 * This resolver searches the classpath for matching binary resources
 * (e.g., {@code postgres-Linux-x86_64.txz}) and provides them as an {@link InputStream}.
 * It also includes fallback behavior for unsupported or emulated architectures.
 */
public class DefaultPostgresBinaryResolver implements PgBinaryResolver {
    private static final Logger logger = LoggerFactory.getLogger(DefaultPostgresBinaryResolver.class);

    /**
     * A singleton instance of {@link DefaultPostgresBinaryResolver} for convenient reuse.
     * <p>
     * This instance can be used directly when no custom binary resolution strategy
     * is needed.
     */
    public static final DefaultPostgresBinaryResolver INSTANCE = new DefaultPostgresBinaryResolver();

    private DefaultPostgresBinaryResolver() {}

    @Override
    public InputStream getPgBinary(final String system, final String machineHardware) throws IOException {
        final String architecture = ArchUtils.normalize(machineHardware);
        final String distribution = LinuxUtils.getDistributionName();

        if (logger.isInfoEnabled()) logger.info("Detected distribution: '{}'", Optional.ofNullable(distribution).orElse("Unknown"));

        if (distribution != null) {
            final Resource resource = findPgBinary(normalize(format("postgres-%s-%s-%s.txz", system, architecture, distribution)));
            if (resource != null) {
                logger.info("Distribution specific postgres binaries found: '{}'", resource.getFilename());
                return resource.getInputStream();
            } else {
                logger.debug("Distribution specific postgres binaries not found");
            }
        }

        Resource resource = findPgBinary(normalize(format("postgres-%s-%s.txz", system, architecture)));
        if (resource != null) {
            logger.info("System specific postgres binaries found: '{}'", resource.getFilename());
            return resource.getInputStream();
        }

        if ((Objects.equals(system, "Darwin") && Objects.equals(machineHardware, "aarch64"))
                || (Objects.equals(system, "Windows") && Objects.equals(architecture, "arm_64"))) {
            resource = findPgBinary(normalize(format("postgres-%s-%s.txz", system, "x86_64")));
            if (resource != null) {
                logger.warn("No native binaries supporting ARM architecture found. " +
                        "Trying to use binaries for x64 architecture instead: '{}'. " +
                        "Make sure you have enabled emulation for this purpose. " +
                        "Note that performance may be degraded.", resource.getFilename());
                return resource.getInputStream();
            }
        }

        logger.error("No postgres binaries found, you need to add an appropriate maven dependency " +
                "that meets the following parameters - system: '{}', architecture: '{}' " +
                "[https://github.com/zonkyio/embedded-postgres#additional-architectures]", system, architecture);
        throw new IllegalStateException("Missing embedded postgres binaries");
    }

    private static Resource findPgBinary(final String resourceLocation) throws IOException {
        logger.trace("Searching for postgres binaries - location: '{}'", resourceLocation);
        final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        final List<URL> urls = Collections.list(classLoader.getResources(resourceLocation));

        if (urls.size() > 1) {
            logger.error("Detected multiple binaries of the same architecture: '{}'", urls);
            throw new IllegalStateException("Duplicate embedded postgres binaries");
        }
        if (urls.size() == 1) return new Resource(urls.getFirst());

        return null;
    }

    private static String normalize(final String input) {
        if (input == null || input.isBlank()) return input;
        return input.replace(' ', '_').toLowerCase(Locale.ROOT);
    }

    private record Resource(URL url) {
        public String getFilename() {
            final String path = URLDecoder.decode(url.getPath(), StandardCharsets.UTF_8);
            final int slash = path.lastIndexOf('/');
            return (slash != -1) ? path.substring(slash + 1) : path;
        }

        public InputStream getInputStream() throws IOException {
            final URLConnection con = this.url.openConnection();
            try {
                return con.getInputStream();
            } catch (final IOException ex) {
                if (con instanceof final HttpURLConnection httpURLConnection) httpURLConnection.disconnect();
                throw ex;
            }
        }
    }
}
