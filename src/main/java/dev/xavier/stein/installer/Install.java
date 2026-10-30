/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.installer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Installs Stein Loader into a .minecraft folder. The Stein Bridge (the engine: Java 25, LWJGL 3,
 * optimizations) always goes in; the Loader is optional. Mods are chosen separately: the installer
 * only touches the mods on the published list. Installing again over an existing install updates
 * it.
 *
 * <p>It serves the official launcher and the ones that follow the old version and profile format
 * (Mojang's until 2017 and its derivatives, such as Shiginima): the JSON carries the arguments in
 * both formats and dates without fractional seconds, and the profile key is its own name.
 *
 * <ul>
 *   <li>versions/1.8.9-SteinLoader: the player's own 1.8.9.jar, patched here on their machine
 *       (nothing from Mojang ships in the installer), a complete JSON with no inheritsFrom so it
 *       does not inherit LWJGL 2, listing the 1.8.9 libraries minus LWJGL 2, the patched paulscode,
 *       ours and LWJGL 3, the Java 25 (runtime/) and the install manifest;
 *   <li>libraries/dev/xavier/stein: the bridge, the Loader and the patched paulscode;
 *   <li>the "Stein Loader 1.8.9" profile in launcher_profiles.json (and in the Microsoft Store
 *       variant, if present), with the Java 25 and the SL icon and no gameDir: the game uses the
 *       regular .minecraft (options, servers, worlds and mods are the player's own).
 * </ul>
 */
public final class Install {

    /** Installer and installed package version (manifest, window, zip). */
    public static final String VERSION = "0.1.42";

    /**
     * Library versions: those of SteinBridge.VERSION and Loader.VERSION (the build,
     * build.gradle.kts, checks).
     */
    public static final String BRIDGE_VERSION = "0.1.27";

    public static final String LOADER_VERSION = "0.1.31";

    /** Folder and version id in the launcher, in LiteLoader style (1.8.9-LiteLoader1.8.9). */
    public static final String VERSION_ID = "1.8.9-SteinLoader";

    /**
     * Profile name and key. The old launcher selects the profile by name and looks it up by key:
     * with a key different from the name, the profile does not stay selected and it falls back to
     * the previous one.
     */
    static final String PROFILE_NAME = "Stein Loader 1.8.9";

    static final String MANIFEST = "stein-loader-install.json";

    /** Marker left by whoever copied the Java into the version, to know when to replace it. */
    static final String RUNTIME_MARK = "stein-runtime.txt";

    /** Game memory beyond the cap: starts small and ZGC aims for 1 GB (why: see writeProfiles). */
    static final String HEAP_POLICY = "-Xms256M -XX:SoftMaxHeapSize=1024M";

    /**
     * This machine's system, named the way LWJGL names native jars: {@code windows}, {@code linux},
     * {@code macos} or {@code macos-arm64}. It is the same name the installer for that system
     * carries in its payload: the build (build.gradle.kts) makes all four, each with the natives
     * and the Java of its target.
     */
    static final String SYSTEM = detectSystem();

    /**
     * The same system named the way the launcher does in library rules ({@code osx}, not "macos").
     */
    static final String LAUNCHER_OS = SYSTEM.startsWith("macos") ? "osx" : SYSTEM;

    static final boolean WINDOWS = SYSTEM.equals("windows");
    static final boolean MACOS = SYSTEM.startsWith("macos");

    private static String detectSystem() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            // The two kinds of Mac have different natives: Apple Silicon is aarch64. And this does
            // not
            // ask this JVM's os.arch, it asks the machine: if the player opens the installer with
            // an
            // Intel Java (the Java 8 many Macs still have), os.arch answers x86_64 even on Apple
            // Silicon, because what Rosetta translates is the installer. Installing by that answer
            // would make the whole game (Java 25 and the LWJGL natives) run translated on a machine
            // that runs native.
            if (macArm64()) {
                return "macos-arm64";
            }
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            return arch.contains("aarch64") || arch.contains("arm") ? "macos-arm64" : "macos";
        }
        return "linux";
    }

    /**
     * Is the machine an Apple Silicon? The kernel answers, not the JVM: {@code hw.optional.arm64}
     * is 1 on every Mac with an Apple chip, including when the asker is an Intel process translated
     * by Rosetta. An Intel Mac does not have this key and sysctl exits with an error; any slip here
     * falls back to {@code os.arch}.
     */
    private static boolean macArm64() {
        try {
            Process p =
                    new ProcessBuilder("/usr/sbin/sysctl", "-n", "hw.optional.arm64")
                            .redirectErrorStream(true)
                            .start();
            String output;
            try (BufferedReader r =
                    new BufferedReader(
                            new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                output = r.readLine();
            }
            p.waitFor();
            return output != null && output.trim().equals("1");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * The Java executable inside the runtime: on Windows it has an extension, and there is a
     * console-less one (javaw).
     */
    static String javaBin(boolean noConsole) {
        return WINDOWS ? (noConsole ? "javaw.exe" : "java.exe") : "java";
    }

    static final String LWJGL = "3.4.3";
    static final String[] LWJGL_MODULES = {
        "lwjgl", "lwjgl-glfw", "lwjgl-opengl", "lwjgl-openal", "lwjgl-stb", "lwjgl-vulkan"
    };
    static final String PAULSCODE =
            "com/paulscode/librarylwjglopenal/20100824/librarylwjglopenal-20100824.jar";
    static final String[] PROFILE_FILES = {
        "launcher_profiles.json", "launcher_profiles_microsoft_store.json"
    };

    /** JDK folders the game does not use (if the installer runs on a full JDK). */
    static final Set<String> SKIP_RUNTIME =
            new java.util.HashSet<>(
                    Arrays.asList("jmods", "include", "demo", "sample", "man", "legal"));

    /** Installer output: progress lines and the fraction done. */
    public interface Log {
        void line(String s);

        default void progress(int done, int total) {}
    }

    /** An error with a message for the player (already in the chosen language). */
    public static final class InstallException extends Exception {
        InstallException(String message) {
            super(message);
        }
    }

    public static final class Options {
        public boolean loader = true;
        public int ramGb = 3;

        /**
         * The mods the installer puts in the folder, by id ({@code hud}, {@code replay}...). null
         * means "leave the mods folder alone": the case when the published list did not arrive, or
         * when whoever is installing asked to leave it as it is.
         */
        public Set<String> mods;
    }

    private final Path mc;
    private final Path payload;
    private final Options opt;
    private final Messages m;
    private final Log log;
    private final Path versionDir;
    private final Path runtimeDir;
    private final Path libs;

    public Install(Path mc, Path payload, Options opt, Messages m, Log log) {
        this.mc = mc.toAbsolutePath().normalize();
        this.payload = payload;
        this.opt = opt;
        this.m = m;
        this.log = log;
        versionDir = this.mc.resolve("versions").resolve(VERSION_ID);
        // The Java 25 lives with the version (it goes away together with it on uninstall).
        runtimeDir = versionDir.resolve("runtime");
        libs = this.mc.resolve("libraries");
    }

    /**
     * The launcher that uses the folder, from launcher_profiles.json: the name recorded by the old
     * one (profilesFormat 1, e.g. "Shiginima Launcher v4.100") or the official one; null without
     * the file.
     */
    public static String launcherName(Path mc) {
        try {
            Object root = Json.parse(readText(mc.resolve("launcher_profiles.json")));
            if ("1".equals(String.valueOf(Json.get(root, "launcherVersion", "profilesFormat")))) {
                Object name = Json.get(root, "launcherVersion", "name");
                return name != null ? name.toString() : "?";
            }
            return "Minecraft Launcher";
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The manifest of the install that already exists in mc (version, components, memory), or null.
     * A version without a manifest counts as installed, of unknown version.
     */
    public static Map<String, Object> installed(Path mc) {
        Path dir = mc.toAbsolutePath().normalize().resolve("versions").resolve(VERSION_ID);
        try {
            if (Files.isRegularFile(dir.resolve(MANIFEST))) {
                return Json.object(Json.parse(readText(dir.resolve(MANIFEST))));
            }
            if (Files.isRegularFile(dir.resolve(VERSION_ID + ".json"))) {
                return map("version", "?");
            }
        } catch (IOException | RuntimeException e) {
            return map("version", "?");
        }
        return null;
    }

    public void install() throws Exception {
        // The game from this folder is open: its files are in use, and Windows lets some be
        // replaced
        // and not others. The install would end up half done (new game jar, old bridge) and the
        // open
        // game would start reading classes from a file swapped under it, which is how an update
        // once
        // took down a player's session. Nothing is touched before this check.
        if (InstallerApp.gameRunning(runtimeDir)) {
            throw new IllegalStateException(m.t("gameOpen"));
        }
        int total = 6;
        Map<String, Object> before = installed(mc);
        log.line(
                before == null
                        ? m.t("start", VERSION, mc)
                        : m.t("update", before.get("version"), VERSION, mc));
        log.line(
                m.t("versions", BRIDGE_VERSION)
                        + (opt.loader ? ", Stein Loader " + LOADER_VERSION : ""));
        Map<String, Object> vanilla = checkVanilla();
        log.progress(1, total);

        copyRuntime();
        log.progress(2, total);

        log.line(m.t("patching"));
        Files.createDirectories(versionDir);
        patch(
                mc.resolve("versions/1.8.9/1.8.9.jar"),
                versionDir.resolve(VERSION_ID + ".jar"),
                "name-mapping.txt",
                "redirects-mc.tsv",
                "redirects-opt.tsv",
                "redirects-batch.tsv",
                "redirects-mods.tsv");
        Path paul =
                libs.resolve(mavenPath("dev.xavier.stein:librarylwjglopenal-steinbridge:20100824"));
        Files.createDirectories(paul.getParent());
        patch(libs.resolve(PAULSCODE), paul, "redirects-al.tsv");
        log.progress(4, total);

        log.line(m.t("version", VERSION_ID));
        writeVersion(vanilla, paul);
        log.progress(5, total);

        writeProfiles();
        writeManifest();
        installMods();
        log.progress(6, total);
        log.line(m.t("done", PROFILE_NAME));
    }

    public void uninstall() throws Exception {
        log.line(m.t("removing", mc));
        deleteTree(versionDir); // the Java 25 is inside it
        deleteTree(libs.resolve("dev/xavier/stein"));
        for (String name : PROFILE_FILES) {
            Path f = mc.resolve(name);
            if (Files.isRegularFile(f)) {
                Map<String, Object> root = Json.object(Json.parse(readText(f)));
                Object profiles = root.get("profiles");
                Map<?, ?> p = profiles instanceof Map ? (Map<?, ?>) profiles : null;
                if (p != null && p.remove(PROFILE_NAME) != null) {
                    writeText(f, Json.write(root));
                    log.line(m.t("profileRemoved", name));
                }
            }
        }
        log.line(m.t("removed"));
    }

    // ---- steps ----

    private Map<String, Object> checkVanilla() throws Exception {
        Path json = mc.resolve("versions/1.8.9/1.8.9.json");
        Path jar = mc.resolve("versions/1.8.9/1.8.9.jar");
        if (!Files.isRegularFile(json) || !Files.isRegularFile(jar)) {
            throw new InstallException(m.t("notFound", mc) + m.t("howTo"));
        }
        if (!Files.isRegularFile(libs.resolve(PAULSCODE))) {
            throw new InstallException(m.t("missingLibs", libs) + m.t("howTo"));
        }
        Map<String, Object> vanilla = Json.object(Json.parse(readText(json)));
        Object sha = Json.get(vanilla, "downloads", "client", "sha1");
        if (sha != null && !sha.equals(sha1(jar))) {
            throw new InstallException(m.t("badJar", mc) + m.t("howTo"));
        }
        log.line(m.t("found"));
        return vanilla;
    }

    /**
     * The chosen mods, at the latest published version ({@link Mods}). With no choice at all
     * ({@code opt.mods == null}) the mods folder is left as it is: that is what happens when the
     * published list did not arrive, and what the person installing can ask for.
     */
    private void installMods() {
        if (opt.mods == null) {
            return;
        }
        try {
            List<Mods.Mod> list = Mods.index();
            if (list.isEmpty()) {
                log.line(m.t("modsOffline"));
                return;
            }
            int changed = Mods.apply(mc.resolve("mods"), list, opt.mods, log, m);
            log.line(changed > 0 ? m.t("modsDone", String.valueOf(changed)) : m.t("modsNothing"));
        } catch (IOException | RuntimeException e) {
            // Mods are an extra: the game is already installed and works without any.
            log.line(m.t("modsFailed", String.valueOf(e)));
        }
    }

    /**
     * The game's Java 25, copied into the version. It comes from inside the jar when the installer
     * is the single file ({@link Bundle}); in the jpackage app, it is the Java running the
     * installer.
     */
    private void copyRuntime() throws IOException, InstallException {
        Path jar = Bundle.self();
        if (isRuntimeCurrent()) {
            log.line(m.t("javaOk"));
            return;
        }
        if (jar != null && Bundle.hasRuntime(jar)) {
            log.line(m.t("javaCopy", runtimeDir));
            deleteTree(runtimeDir);
            Files.createDirectories(runtimeDir);
            Bundle.copyTree(jar, "runtime/", runtimeDir);
            allowExecution();
            markRuntime();
            return;
        }
        if (jar != null) {
            downloadRuntime();
            return;
        }
        Path src = Paths.get(System.getProperty("java.home"));
        log.line(m.t("javaCopy", runtimeDir));
        deleteTree(runtimeDir);
        Files.walkFileTree(
                src,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                            throws IOException {
                        if (!dir.equals(src)
                                && dir.getParent().equals(src)
                                && SKIP_RUNTIME.contains(dir.getFileName().toString())) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        Files.createDirectories(runtimeDir.resolve(src.relativize(dir).toString()));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        Files.copy(
                                file,
                                runtimeDir.resolve(src.relativize(file).toString()),
                                StandardCopyOption.REPLACE_EXISTING);
                        return FileVisitResult.CONTINUE;
                    }
                });
        allowExecution();
        markRuntime();
    }

    /**
     * The game's Java 25, downloaded from the release itself. This is what allows <b>one installer
     * for every system</b>: the jar is 5 MB and the Java, which is native to each system, is 50 and
     * comes afterwards.
     *
     * <p><b>This is the first thing the install does</b>, before touching any file: if the network
     * fails here, the installer says so and leaves nothing half done. Someone who already has this
     * version's Java does not download it again ({@link #isRuntimeCurrent}).
     *
     * <p>The expected sha256 does not come from the internet: it is written into the jar at build
     * time (the build, build.gradle.kts,), so the downloaded file is checked against what this
     * installer knows, not against what the network says. Without an entry for this system the
     * download is refused.
     */
    private void downloadRuntime() throws IOException, InstallException {
        String name = "runtime-" + SYSTEM + ".zip";
        String url = Urls.RUNTIME_BASE + "v" + VERSION + "/" + name;
        String expected = runtimeSha();
        if (expected == null) {
            throw new InstallException(m.t("javaNoChecksum", name));
        }
        log.line(m.t("javaDownload", name));
        Path zip = Files.createTempFile("stein-runtime", ".zip");
        try {
            String downloaded = hex(Mods.download(url, zip));
            if (!expected.equalsIgnoreCase(downloaded)) {
                throw new InstallException(m.t("javaBad", name));
            }
            log.line(m.t("javaCopy", runtimeDir));
            deleteTree(runtimeDir);
            Files.createDirectories(runtimeDir);
            unzip(zip, runtimeDir);
            allowExecution();
            markRuntime();
        } catch (IOException e) {
            // The reason matters: no network, behind a proxy and the file being down are different
            // things for whoever will try again.
            throw new InstallException(
                    m.t("javaFail", url, e.getMessage() == null ? e.toString() : e.getMessage()));
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    /**
     * The sha256 of this system's runtime, written into the jar by the build; null if the file is
     * not there or has no entry.
     */
    private String runtimeSha() {
        try (InputStream in = Install.class.getResourceAsStream("/runtimes.txt")) {
            if (in == null) {
                return null;
            }
            for (String line : new String(readAll(in), StandardCharsets.UTF_8).split("\n")) {
                String[] p = line.trim().split("\\s+");
                if (p.length == 2 && p[0].equals(SYSTEM)) {
                    return p[1];
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    /**
     * Unzips the runtime zip. Empty folders do not matter: what exists is created from the file
     * paths.
     */
    private static void unzip(Path zip, Path target) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(zip.toFile())) {
            for (java.util.Enumeration<? extends java.util.zip.ZipEntry> e = z.entries();
                    e.hasMoreElements(); ) {
                java.util.zip.ZipEntry entry = e.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                Path out = root.resolve(entry.getName()).normalize();
                if (!out.startsWith(root)) {
                    throw new IOException(
                            "path outside the target folder in the zip: " + entry.getName());
                }
                Files.createDirectories(out.getParent());
                try (InputStream in = z.getInputStream(entry)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Outside Windows, the execute bit of the Java that was just copied.
     *
     * <p>The jar does not store file permissions: {@code bin/java} comes out of it unable to run,
     * and the launcher (or the installer itself, which calls the Patcher with it) would get
     * "Permission denied" with no hint why. {@code lib/jspawnhelper} goes along: it is what the JVM
     * uses to create processes.
     */
    private void allowExecution() throws IOException {
        if (WINDOWS) {
            return;
        }
        for (Path dir : Arrays.asList(runtimeDir.resolve("bin"), runtimeDir.resolve("lib"))) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            final boolean all = "bin".equals(dir.getFileName().toString());
            Files.walkFileTree(
                    dir,
                    new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                            String name = f.getFileName().toString();
                            if (all || name.equals("jspawnhelper") || name.equals("jexec")) {
                                try {
                                    Files.setPosixFilePermissions(
                                            f,
                                            java.nio.file.attribute.PosixFilePermissions.fromString(
                                                    "rwxr-xr-x"));
                                } catch (IOException | UnsupportedOperationException e) {
                                    // A file system without POSIX: there is nothing to mark, and
                                    // the
                                    // warning is enough.
                                    log.line(m.t("execMarkFail", f, e));
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
        }
    }

    /**
     * Is the Java inside the version the one this installer carries?
     *
     * <p>Checking the jlink {@code release} file does not work: two runtimes from different
     * vendors, with the same version and the same modules, have an identical {@code release}. That
     * is how an install once kept an old runtime while the installer said everything was up to
     * date. Now the answer is a marker written by whoever copied it, with the runtime's identity:
     * when it does not match, copy again.
     */
    private boolean isRuntimeCurrent() throws IOException {
        Path mark = runtimeDir.resolve(RUNTIME_MARK);
        return Files.isRegularFile(mark)
                && currentMark().equals(readText(mark).trim())
                && Files.isRegularFile(runtimeDir.resolve("bin").resolve(javaBin(true)));
    }

    /**
     * The identity of the Java this installer delivers: the <b>sha256 of the runtime</b> when it is
     * downloaded, and the installer version when it comes from inside the jar or is the app's own.
     *
     * <p>Marking by sha avoids a 50 MB download on every new version: the installer changes every
     * week, Java 25 does not. While the Java is the same file, the marker matches and nothing is
     * downloaded.
     */
    private String currentMark() {
        String sha = runtimeSha();
        return sha != null ? sha : VERSION;
    }

    private void markRuntime() throws IOException {
        writeText(runtimeDir.resolve(RUNTIME_MARK), currentMark() + System.lineSeparator());
    }

    /** The Patcher runs in its own process (static state, and it exits with an error code). */
    private void patch(Path in, Path out, String... rules) throws Exception {
        List<String> cmd =
                new ArrayList<>(
                        Arrays.asList(
                                javaExe(),
                                "-cp",
                                // The "*" in the classpath is JVM syntax, not a file: built as
                                // text,
                                // because Windows java.nio refuses "*" inside a Path.
                                payload.resolve("patcher")
                                        + java.io.File.pathSeparator
                                        + payload.resolve("patcher").resolve("asm")
                                        + java.io.File.separator
                                        + "*",
                                "Patcher",
                                in.toString(),
                                out.toString()));
        for (String r : rules) {
            cmd.add(payload.resolve("rules").resolve(r).toString());
        }
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder all = new StringBuilder();
        try (BufferedReader r =
                new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                all.append(line).append('\n');
            }
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new InstallException(m.t("patchFail", in.getFileName(), code, all));
        }
        log.line(m.t("patched", in.getFileName()));
    }

    private void writeVersion(Map<String, Object> vanilla, Path paul) throws Exception {
        List<Object> libraries = new ArrayList<>();
        // Earlier versions of the bridge and the Loader go away: the version is part of the path in
        // libraries/.
        deleteTree(libs.resolve("dev/xavier/stein/bridge"));
        deleteTree(libs.resolve("dev/xavier/stein/loader"));
        // Ours go in by name only, as OptiFine does with its jar: there is nowhere to download them
        // from, and the old launcher reads the url as a java.net.URL, so "" took the whole version
        // down.
        libraries.add(
                localLib(
                        "dev.xavier.stein:steinbridge:" + BRIDGE_VERSION,
                        payload.resolve("steinbridge.jar")));
        if (opt.loader) {
            libraries.add(
                    localLib(
                            "dev.xavier.stein:steinloader:" + LOADER_VERSION,
                            payload.resolve("steinloader.jar")));
        }
        Object vanillaLibs = vanilla.get("libraries");
        for (Object o :
                vanillaLibs instanceof List
                        ? (List<?>) vanillaLibs
                        : java.util.Collections.emptyList()) {
            String name = String.valueOf(Json.get(o, "name"));
            if (name.startsWith("org.lwjgl.lwjgl:")) {
                continue; // LWJGL 2: LWJGL 3 and the bridge's shim take its place
            }
            if (name.startsWith("com.paulscode:librarylwjglopenal:")) {
                libraries.add(
                        map("name", "dev.xavier.stein:librarylwjglopenal-steinbridge:20100824"));
                continue;
            }
            libraries.add(o);
        }
        // LWJGL 3 last on the classpath: the shim classes (org.lwjgl.*) come first.
        for (String mod : LWJGL_MODULES) {
            String coord = "org.lwjgl:" + mod + ":" + LWJGL;
            libraries.add(
                    copyLib(
                            coord,
                            payload.resolve("lwjgl").resolve(mod + "-" + LWJGL + ".jar"),
                            Urls.MAVEN + mavenPath(coord)));
            // Natives with a 3-part coordinate: the old launcher builds the path from the name, cut
            // in 3, and "group:artifact:version:classifier" turned into a path with ":".
            // The jar is Maven's (the url downloads the same file); only its place in libraries/
            // changes. The natives are this system's, which are the ones the payload carried: the
            // install belongs to the machine where it happens. The OS rule stays written because
            // the
            // launcher reads it before building the classpath.
            String cls = "natives-" + SYSTEM;
            Path nativesJar =
                    payload.resolve("lwjgl").resolve(mod + "-" + LWJGL + "-" + cls + ".jar");
            if (mod.equals("lwjgl-vulkan") && !Files.isRegularFile(nativesJar)) {
                // LWJGL's Vulkan has natives only for macOS (MoltenVK): elsewhere the loader comes
                // with the graphics driver
                continue;
            }
            String natives = "dev.xavier.stein.lwjgl:" + mod + "-" + cls + ":" + LWJGL;
            Map<String, Object> n =
                    copyLib(natives, nativesJar, Urls.MAVEN + mavenPath(coord + ":" + cls));
            n.put("rules", Arrays.asList(map("action", "allow", "os", map("name", LAUNCHER_OS))));
            libraries.add(n);
        }

        // The game presents itself as 1.8.9 (F3 "1.8.9/SteinLoader", crash report): the folder id
        // is
        // only for the launcher.
        String args =
                String.valueOf(vanilla.get("minecraftArguments"))
                        .replace("${version_name}", "1.8.9");
        List<Object> game = new ArrayList<>(Arrays.asList(args.split(" ")));
        List<Object> jvm =
                new ArrayList<>(
                        Arrays.asList(
                                "-Djava.library.path=${natives_directory}",
                                "--enable-native-access=ALL-UNNAMED",
                                "-Dminecraft.launcher.brand=${launcher_name}",
                                "-Dminecraft.launcher.version=${launcher_version}",
                                "-cp",
                                "${classpath}"));
        if (MACOS) {
            // On macOS GLFW only works on the main thread, and the JVM occupies it: without this
            // the
            // window does not open. With the OS rule alongside, because this JSON is read by the
            // launcher, which honours rules.
            jvm.add(
                    0,
                    map(
                            "rules",
                            Arrays.asList(map("action", "allow", "os", map("name", "osx"))),
                            "value",
                            "-XstartOnFirstThread"));
        }

        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", VERSION_ID);
        v.put("type", "release");
        // Dates like Mojang's ("2015-12-03T09:24:39+00:00"): the old launcher does not read
        // fractional seconds, and with an invalid date the whole version disappears from its list.
        v.put(
                "time",
                OffsetDateTime.now(ZoneOffset.UTC)
                        .format(
                                DateTimeFormatter.ofPattern(
                                        "yyyy-MM-dd'T'HH:mm:ssxxx", Locale.ROOT)));
        v.put("releaseTime", vanilla.get("releaseTime"));
        Object min = vanilla.get("minimumLauncherVersion");
        v.put("minimumLauncherVersion", min != null ? min : new BigDecimal(14));
        v.put("mainClass", "dev.xavier.stein.bridge.launch.Main");
        // The 1.8.9.json of old launchers has no assetIndex: copied as null, the launcher did not
        // download the index and the game was left without sounds and without the language list.
        // Without one, Mojang's 1.8.9 index applies (the same as the official launcher's).
        Object assets = vanilla.get("assets");
        Object assetIndex = vanilla.get("assetIndex");
        v.put("assets", assets instanceof String ? assets : "1.8");
        v.put(
                "assetIndex",
                assetIndex instanceof Map
                        ? assetIndex
                        : map(
                                "id",
                                "1.8",
                                "sha1",
                                "f6ad102bcaa53b1a58358f16e376d548d44933ec",
                                "size",
                                78494,
                                "totalSize",
                                114885064,
                                "url",
                                Urls.ASSET_INDEX));
        // Arguments in both formats: the official launcher uses "arguments" (with the JVM ones);
        // the
        // old one only knows "minecraftArguments" and adds its own JVM arguments plus the
        // profile's.
        v.put("minecraftArguments", args);
        v.put("arguments", map("game", game, "jvm", jvm));
        // The 1.8.9 logging configuration (the one Mojang fixed for Log4Shell) stays.
        if (vanilla.get("logging") != null) {
            v.put("logging", vanilla.get("logging"));
        }
        v.put("libraries", libraries);
        writeText(versionDir.resolve(VERSION_ID + ".json"), Json.write(v));
    }

    private void writeProfiles() throws IOException {
        List<Path> files = new ArrayList<>();
        for (String name : PROFILE_FILES) {
            if (Files.isRegularFile(mc.resolve(name))) {
                files.add(mc.resolve(name));
            }
        }
        if (files.isEmpty()) {
            files.add(mc.resolve(PROFILE_FILES[0]));
        }
        String now = Instant.now().toString();
        for (Path f : files) {
            Map<String, Object> root;
            if (Files.isRegularFile(f)) {
                Path backup = f.resolveSibling(f.getFileName() + ".before-stein-loader");
                if (!Files.exists(backup)) {
                    Files.copy(f, backup);
                }
                root = Json.object(Json.parse(readText(f)));
                logLauncher(root);
            } else {
                root = new LinkedHashMap<>();
                root.put("profiles", new LinkedHashMap<>());
                root.put("settings", new LinkedHashMap<>());
                root.put("version", new BigDecimal(3));
            }
            Object ps = root.get("profiles");
            Map<String, Object> profiles =
                    ps instanceof Map ? Json.object(ps) : new LinkedHashMap<String, Object>();
            root.put("profiles", profiles);
            Object old = profiles.get(PROFILE_NAME);
            Map<String, Object> p =
                    old instanceof Map ? Json.object(old) : new LinkedHashMap<String, Object>();
            p.putIfAbsent("created", now);
            p.put("icon", "data:image/png;base64," + Base64.getEncoder().encodeToString(icon()));
            p.put("lastUsed", now);
            p.put("lastVersionId", VERSION_ID);
            p.put("name", PROFILE_NAME);
            p.put("type", "custom");
            p.remove("gameDir"); // the regular .minecraft, like the default profile
            p.put("javaDir", javaDir());
            // --enable-native-access here too: the old launcher does not read the JVM arguments
            // from
            // the JSON (in the official one it comes out repeated, with no effect).
            // -ZProactive: without the "proactive" ZGC collections, which ran every 4 s and cost
            // ~80
            // ms of concurrent work each, which was what stretched the 1% low. Measured with 400
            // players around, three runs each: worst frame 27 ms -> 18 ms, 0.1% low 54 -> 60 fps,
            // and every run matched the others. ZGC still collects when memory demands it; it just
            // does not collect on its own initiative when memory is plentiful.
            // The memory chosen is the cap (-Xmx), not the reservation: -Xms256M and a 1 GB target
            // (SoftMaxHeapSize) that ZGC tries to keep, going past it only when the game needs to.
            // With -Xms equal to -Xmx the game took all the memory up front and ZGC only collected
            // when it was full: 3.5 GB in Task Manager for ~290 MB live (400-player crowd).
            // A 768 MB target sat right above what the game uses (~680 MB in the benchmark's
            // fights), so ZGC ran hundreds of cycles per scene, over a core of its own: 1 GB leaves
            // it room for 9-50 cycles, 1% low +12..50%, worst frame lower, for ~180 MB more heap.
            p.put(
                    "javaArgs",
                    "-Xmx"
                            + opt.ramGb
                            + "G "
                            + HEAP_POLICY
                            + " -XX:+UseZGC -XX:-ZProactive"
                            + " --enable-native-access=ALL-UNNAMED");
            profiles.put(PROFILE_NAME, p);
            writeText(f, Json.write(root));
            log.line(m.t("profile", PROFILE_NAME, f.getFileName()));
        }
    }

    /**
     * The Java 25 in the profile: the <b>absolute</b> path of the executable that sits inside the
     * version (javaw.exe on Windows, java elsewhere).
     *
     * <p>It used to be relative ({@code .\data\.minecraft\versions\...}), with a modpack in mind
     * that the player copies whole to another folder: the profile would stay valid after moving
     * everything. In practice it caused trouble: whoever runs the launcher does not guarantee from
     * where, and a relative path only works if the working directory is exactly what the installer
     * assumed. Absolute always opens; whoever moves the folder runs the installer again, which is
     * one click.
     */
    private String javaDir() {
        return runtimeDir.resolve("bin").resolve(javaBin(true)).toString();
    }

    /**
     * Which launcher uses the folder, from launcher_profiles.json: the old one writes
     * launcherVersion.profilesFormat 1 and its own name. Informational only: the version and the
     * profile are written in both formats anyway.
     */
    private void logLauncher(Map<String, Object> root) {
        if ("1".equals(String.valueOf(Json.get(root, "launcherVersion", "profilesFormat")))) {
            Object name = Json.get(root, "launcherVersion", "name");
            log.line(m.t("launcherLegacy", name != null ? name : "?"));
        } else {
            log.line(m.t("launcherNew"));
        }
    }

    private void writeManifest() throws IOException {
        Map<String, Object> man =
                map(
                        "version",
                        VERSION,
                        "loader",
                        opt.loader,
                        "ramGb",
                        new BigDecimal(opt.ramGb),
                        "installed",
                        Instant.now().toString());
        writeText(versionDir.resolve(MANIFEST), Json.write(man));
    }

    // ---- utilities ----

    static byte[] icon() throws IOException {
        try (InputStream in = Install.class.getResourceAsStream("/sl-256.png")) {
            if (in == null) {
                throw new IOException("icon missing from the installer");
            }
            return readAll(in);
        }
    }

    /** One of our libraries: copied into libraries/ (Maven layout) and declared by name only. */
    private Map<String, Object> localLib(String coord, Path src) throws Exception {
        copyLib(coord, src, "");
        return map("name", coord);
    }

    private Map<String, Object> copyLib(String coord, Path src, String url) throws Exception {
        if (!Files.isRegularFile(src)) {
            throw new InstallException(m.t("incomplete", payload.relativize(src)));
        }
        Path dst = libs.resolve(mavenPath(coord));
        Files.createDirectories(dst.getParent());
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        return libEntry(coord, dst, url);
    }

    private Map<String, Object> libEntry(String coord, Path file, String url) throws Exception {
        Map<String, Object> artifact =
                map(
                        "path",
                        libs.relativize(file).toString().replace('\\', '/'),
                        "sha1",
                        sha1(file),
                        "size",
                        new BigDecimal(Files.size(file)),
                        "url",
                        url);
        return map("name", coord, "downloads", map("artifact", artifact));
    }

    /** group:artifact:version[:classifier] -> path in libraries/. */
    static String mavenPath(String coord) {
        String[] c = coord.split(":");
        String file = c[1] + "-" + c[2] + (c.length > 3 ? "-" + c[3] : "") + ".jar";
        return c[0].replace('.', '/') + "/" + c[1] + "/" + c[2] + "/" + file;
    }

    static String sha1(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                md.update(buf, 0, n);
            }
        }
        return hex(md.digest());
    }

    // --- what Java 8 does not have ready-made ---
    // The installer is compiled for Java 8 on purpose: whoever plays 1.8.9 through an alternative
    // launcher opens it by double-clicking a .jar, and the Java that does that on their machine is
    // usually 8. These are the only newer things the code used; everything else already existed in
    // 8.

    /** {@code Files.readString}, which only arrived in Java 11. */
    static String readText(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    /** {@code Files.writeString}, likewise. */
    static void writeText(Path p, String text) throws IOException {
        Files.write(p, text.getBytes(StandardCharsets.UTF_8));
    }

    /** {@code InputStream.readAllBytes}, which only arrived in Java 9. */
    static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** {@code HexFormat}, which only arrived in Java 17. */
    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * The java that runs the Patcher: <b>the Java 25 that just went into the version folder</b>,
     * not the one that opened the installer. The single jar opens with whatever Java the person has
     * (17 or newer) and the Patcher is compiled for 25: run on the outer one, it would not even
     * load ("class file version 69"). And, as a bonus, the game is patched by the same Java that
     * will run it.
     */
    private String javaExe() {
        Path bundled = runtimeDir.resolve("bin").resolve(javaBin(false));
        return Files.isRegularFile(bundled)
                ? bundled.toString()
                : Paths.get(System.getProperty("java.home"), "bin", "java").toString();
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        Files.walkFileTree(
                dir,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path d, IOException e)
                            throws IOException {
                        Files.delete(d);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }
}
