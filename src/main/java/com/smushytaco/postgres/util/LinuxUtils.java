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

package com.smushytaco.postgres.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * Linux-specific utility methods used by the embedded Postgres implementation.
 * <p>
 * This class provides helpers for detecting the Linux distribution and for
 * checking whether the {@code unshare} command is available and usable in
 * the current environment.
 */
public final class LinuxUtils {
    private static final Logger logger = LoggerFactory.getLogger(LinuxUtils.class);

    private static final boolean IS_LINUX = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");

    private static final String DISTRIBUTION_NAME = resolveDistributionName();
    private static final boolean UNSHARE_AVAILABLE = unshareAvailable();

    private LinuxUtils() {}

    /**
     * Returns the detected Linux distribution name, or {@code null} if the
     * current operating system is not Linux or the distribution cannot be
     * determined.
     *
     * @return the Linux distribution name (e.g. {@code "Ubuntu"}, {@code "Debian"}),
     *         or {@code null} if detection is not possible
     */
    public static String getDistributionName() {
        return DISTRIBUTION_NAME;
    }

    /**
     * Indicates whether the {@code unshare} command is available and usable
     * on this system.
     * <p>
     * On non-Linux systems this method always returns {@code false}. On Linux,
     * it checks both that the current user has the required privileges and that
     * {@code unshare} can be executed successfully.
     *
     * @return {@code true} if {@code unshare} is available and usable,
     *         {@code false} otherwise
     */
    public static boolean isUnshareAvailable() {
        return UNSHARE_AVAILABLE;
    }

    private static String resolveDistributionName() {
        if (!IS_LINUX) return null;

        try {
            final Path target;
            try (final InputStream source = LinuxUtils.class.getResourceAsStream("/sh/detect_linux_distribution.sh")) {
                target = Files.createTempFile("detect_linux_distribution_", ".sh");
                Files.copy(Objects.requireNonNull(source), target, REPLACE_EXISTING);
                target.toFile().deleteOnExit();
            }

            final Process process =  new ProcessBuilder("sh", target.toAbsolutePath().toString()).start();
            process.waitFor();

            if (process.exitValue() != 0) {
                throw new IOException("Execution of the script to detect the Linux distribution failed with error code: '" + process.exitValue() + "'");
            }

            String distributionName;
            try (final BufferedReader outputReader = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF_8))) {
                distributionName = outputReader.readLine();
            }

            if (distributionName == null || distributionName.isBlank()) {
                logger.warn("It's not possible to detect the name of the Linux distribution, the detection script returned empty output");
                return null;
            }

            if (distributionName.startsWith("Debian")) distributionName = "Debian";
            if (distributionName.equals("openSUSE project")) distributionName = "openSUSE";

            return distributionName;
        } catch (final IOException e) {
            logger.error("It's not possible to detect the name of the Linux distribution", e);
            return null;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("It's not possible to detect the name of the Linux distribution", e);
            return null;
        }
    }

    private static boolean isRootUser() {
        try {
            final Process process = new ProcessBuilder("id", "-u").start();
            int exitCode = process.waitFor();
            if (exitCode != 0) return false;

            try (final BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF_8))) {
                final String line = reader.readLine();
                return line != null && line.trim().equals("0");
            }
        } catch (final IOException _) {
            return false;
        } catch (final InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        }
    }


    private static boolean unshareAvailable() {
        if (!IS_LINUX) return false;

        try {
            if (!isRootUser()) return false;

            final ProcessBuilder builder = new ProcessBuilder("unshare", "-U", "id", "-u");

            final Process process = builder.start();
            process.waitFor();

            try (final BufferedReader outputReader = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF_8))) {
                if (process.exitValue() == 0 && !"0".equals(outputReader.readLine())) {
                    builder.command("unshare", "-U", "id", "-un");
                    final Process nameProcess = builder.start();
                    nameProcess.waitFor();
                    if (nameProcess.exitValue() == 0) return true;
                }
            }
            return false;
        } catch (final IOException _) {
            return false;
        }  catch (final InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
