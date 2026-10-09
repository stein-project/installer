/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.installer;

/** Every address the installer downloads from, in one place. */
final class Urls {

    private Urls() {}

    /**
     * Published mod list (stein.json); the system property {@code steinloader.index} overrides it.
     */
    static final String INDEX =
            System.getProperty(
                    "steinloader.index",
                    "https://raw.githubusercontent.com/x4vieer/stein-loader/main/stein.json");

    /**
     * Base of the release downloads that hold the Java runtimes ({@code
     * v<version>/runtime-<os>.zip}); the system property {@code steinloader.runtimes} overrides it.
     */
    static final String RUNTIME_BASE =
            System.getProperty(
                    "steinloader.runtimes",
                    "https://github.com/x4vieer/stein-loader/releases/download/");

    /** Maven Central, the source of the LWJGL libraries declared in the version JSON. */
    static final String MAVEN = "https://repo1.maven.org/maven2/";

    /** Mojang's 1.8 asset index, used when the player's 1.8.9.json has none. */
    static final String ASSET_INDEX =
            "https://launchermeta.mojang.com/v1/packages/"
                    + "f6ad102bcaa53b1a58358f16e376d548d44933ec/1.8.json";
}
