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

import java.util.Locale;

/**
 * Utility class for normalizing CPU architecture names across platforms.
 * <p>
 * Converts various architecture identifiers (e.g., "amd64", "x86_64", "arm64")
 * into a consistent normalized form used for selecting compatible binaries.
 * This helps ensure predictable handling of architecture strings
 * reported by different systems and JVMs.
 */
public class ArchUtils {
    private ArchUtils() {}

    /**
     * Normalizes a raw architecture name into a standardized architecture identifier.
     * <p>
     * For example, inputs such as {@code "x86_64"}, {@code "amd64"}, or {@code "ia32e"}
     * will all normalize to {@code "x86_64"}.
     *
     * @param archName the raw architecture name to normalize (must not be blank)
     * @return the normalized architecture string (e.g., {@code "x86_64"}, {@code "arm_64"})
     * @throws IllegalStateException if the input is blank or the architecture is unsupported
     */
    public static String normalize(final String archName) {
        if (archName == null || archName.isBlank()) throw new IllegalStateException("No architecture detected");

        final String arch = archName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");

        if (arch.matches("^(x8664|amd64|ia32e|em64t|x64)$")) return "x86_64";
        if (arch.matches("^(x8632|x86|i[3-6]86|ia32|x32)$")) return "x86_32";
        if (arch.matches("^(ia64w?|itanium64)$")) return "itanium_64";
        if (arch.matches("^(sparcv9|sparc64)$")) return "sparc_64";
        if (arch.matches("^(sparc|sparc32)$")) return "sparc_32";
        if (arch.matches("^(aarch64|armv8|arm64).*$")) return "arm_64";
        if (arch.matches("^(arm|arm32).*$")) return "arm_32";
        if (arch.matches("^(mips|mips32)$")) return "mips_32";
        if (arch.matches("^(mipsel|mips32el)$")) return "mipsel_32";
        if (arch.matches("^(ppc|ppc32)$")) return "ppc_32";
        if (arch.matches("^(ppcle|ppc32le)$")) return "ppcle_32";

        return switch (arch) {
            case "ia64n" -> "itanium_32";
            case "mips64" -> "mips_64";
            case "mips64el" -> "mipsel_64";
            case "ppc64" -> "ppc_64";
            case "ppc64le" -> "ppcle_64";
            case "s390" -> "s390_32";
            case "s390x" -> "s390_64";
            default -> throw new IllegalStateException("Unsupported architecture: " + archName);
        };
    }
}
