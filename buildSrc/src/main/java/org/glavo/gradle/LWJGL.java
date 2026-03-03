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

package org.glavo.gradle;

import org.gradle.internal.os.OperatingSystem;
import org.lwjgl.system.Platform;

public final class LWJGL {
    public static final String PLAFROM;

    static {
        String os = switch (Platform.get()) {
            case WINDOWS -> "windows";
            case LINUX -> "linux";
            case FREEBSD -> "freebsd";
            case MACOSX -> "macos";
            default -> null;
        };

        String arch = switch (Platform.getArchitecture()) {
            case X64 -> "";
            case X86 -> "-x86";
            case ARM64 -> "-arm64";
            case ARM32 -> "-arm32";
            case PPC64LE -> "-ppc64le";
            case RISCV64 -> "-riscv64";
            default -> null;
        };

        PLAFROM = os != null && arch != null ? os + arch : null;
    }

    private LWJGL() {
    }

}
