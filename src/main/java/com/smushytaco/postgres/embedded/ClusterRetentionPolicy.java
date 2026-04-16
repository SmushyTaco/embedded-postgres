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

/**
 * Controls what happens to a prepared cluster when its last provider handle is released.
 */
public enum ClusterRetentionPolicy {
    /**
     * Close the cluster immediately when the last provider handle is released.
     */
    CLOSE_ON_LAST_RELEASE,

    /**
     * Keep the cluster cached until {@link PreparedDbProvider#closeAll()} is called.
     */
    KEEP_UNTIL_CLOSE_ALL
}
