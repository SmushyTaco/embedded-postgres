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

import java.io.IOException;
import java.io.InputStream;

/**
 * A strategy for resolving PostgreSQL binaries.
 *
 * @see DefaultPostgresBinaryResolver
 */
public interface PgBinaryResolver {

    /**
     * Returns an input stream containing the PostgreSQL binary for the given
     * operating system and hardware architecture.
     * <p>
     * Implementations should locate and provide the correct binary distribution
     * for the specified platform. The returned {@link InputStream} will typically
     * represent a compressed archive of the PostgreSQL executable files.
     *
     * @param system the name of the operating system (e.g., "Darwin", "Linux", "Windows")
     * @param machineHardware the hardware architecture (e.g., "x86_64", "arm64")
     * @return an {@link InputStream} of the PostgreSQL binary archive
     * @throws IOException if the binary cannot be located, read, or opened
     */
    InputStream getPgBinary(String system, String machineHardware) throws IOException;
}
