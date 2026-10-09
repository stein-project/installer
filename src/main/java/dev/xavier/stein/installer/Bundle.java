/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.installer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The installer in a single file: the payload (bridge, Loader, LWJGL, patcher, rules) travels
 * inside the {@code .jar} itself, not in a folder next to it.
 *
 * <p>A self-extracting {@code .exe} runs a command as soon as it is opened, which is exactly what
 * antivirus software flags as suspicious. A jar has none of that: it is opened by the Java the
 * player already has.
 *
 * <p>The jpackage app (the folder with the .exe) also works: there the payload sits next to the jar
 * and the Java is the one running the installer. These methods only apply when the jar carries the
 * payload inside.
 */
final class Bundle {

    private Bundle() {}

    /**
     * The jar this installer is running from, <b>if</b> it carries the payload inside; null in the
     * jpackage app (where payload and Java are in the folder).
     */
    static Path self() {
        return containing("payload/");
    }

    /** Does the jar also carry the game's Java 25 inside (older installers did)? */
    static boolean hasRuntime(Path jar) {
        if (jar == null) {
            return false;
        }
        try (ZipFile z = new ZipFile(jar.toFile())) {
            return z.getEntry("runtime/release") != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static Path containing(String prefix) {
        try {
            Path p =
                    Paths.get(
                            Bundle.class
                                    .getProtectionDomain()
                                    .getCodeSource()
                                    .getLocation()
                                    .toURI());
            if (!Files.isRegularFile(p)) {
                return null;
            }
            try (ZipFile z = new ZipFile(p.toFile())) {
                for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                    if (e.nextElement().getName().startsWith(prefix)) {
                        return p;
                    }
                }
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extracts a folder of the jar into a temporary directory, deleted when the installer exits.
     * This is how the payload gets to disk: the Patcher runs in another process and needs real
     * files.
     */
    static Path extract(Path jar, String prefix) throws IOException {
        Path dir = Files.createTempDirectory("stein-loader-");
        dir.toFile().deleteOnExit();
        copyEntries(jar, prefix, dir, true);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(dir)));
        return dir;
    }

    /** Writes a folder of the jar straight to the destination, with no temporary copy. */
    static void copyTree(Path jar, String prefix, Path dest) throws IOException {
        copyEntries(jar, prefix, dest, false);
    }

    private static void copyEntries(Path jar, String prefix, Path dest, boolean deleteOnExit)
            throws IOException {
        Path target = dest.toAbsolutePath().normalize();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (!entry.getName().startsWith(prefix) || entry.isDirectory()) {
                    continue;
                }
                Path out = target.resolve(entry.getName().substring(prefix.length())).normalize();
                if (!out.startsWith(target)) {
                    throw new IOException(
                            "path outside the target folder in the jar: " + entry.getName());
                }
                Files.createDirectories(out.getParent());
                try (InputStream in = z.getInputStream(entry)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
                if (deleteOnExit) {
                    out.toFile().deleteOnExit();
                }
            }
        }
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> all = Files.walk(dir)) {
            all.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // a system temp folder: the OS cleans it up later
        }
    }
}
