/*
 * Copyright 2026 Glavo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.jpountz.xxhash;

import java.io.IOException;

public final class XXHCLI {

    public static final boolean IS_AVAILABLE;

    static {
        boolean available = false;
        try {
            ProcessBuilder checkBuilder = new ProcessBuilder().command("xxhsum", "-V").redirectErrorStream(true);
            Process checkProcess = checkBuilder.start();
            available = checkProcess.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            // xxhsum CLI not available or failed to execute; treat as unavailable to allow test skip
        }

        IS_AVAILABLE = available;
    }

    private XXHCLI() {
    }
}
