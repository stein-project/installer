/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.installer;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Installs Stein Loader into Prism Launcher: an instance of its own, "Stein Loader 1.8.9", next to
 * the player's others, which are never touched. Prism keeps each instance in {@code
 * instances/<folder>/} (the game in {@code minecraft/}) and describes it by components ({@code
 * mmc-pack.json}).
 *
 * <ul>
 *   <li>{@code patches/net.minecraft.json}: a customized Minecraft component, complete, as the
 *       version JSON is for the official launcher — the 1.8.9 libraries minus LWJGL 2, ours, LWJGL
 *       3 and the patched game as its main jar. Prism cannot take a library out of another
 *       component, so it replaces the whole one, and nothing asks for the LWJGL 2 component;
 *   <li>{@code libraries/}: the instance's local libraries (Prism looks them up by file name): the
 *       Bridge, the Loader, the patched paulscode and the patched game, made here from the player's
 *       own 1.8.9, downloaded from Mojang (nothing from Mojang ships in the installer). The other
 *       libraries keep their addresses, and Prism downloads them;
 *   <li>{@code stein/runtime}: the Java 25, set in {@code instance.cfg} with the memory, Java's
 *       compatibility check off (Prism only knows Java 8 for 1.8.9) and the SL icon.
 * </ul>
 *
 * <p>Prism's folder on each system: {@code %APPDATA%\PrismLauncher} on Windows, {@code
 * ~/.local/share/PrismLauncher} (or the Flatpak's) on Linux, {@code ~/Library/Application
 * Support/PrismLauncher} on macOS; its instances folder can be another one ({@code InstanceDir} in
 * prismlauncher.cfg).
 */
final class PrismInstall {

    /** Prism's settings file: a folder with it is a Prism data folder. */
    static final String CONFIG = "prismlauncher.cfg";

    /** The instance's folder name and its name in Prism. */
    static final String INSTANCE_DIR = "SteinLoader";

    static final String INSTANCE_NAME = Install.PROFILE_NAME;

    /** The icon's key in Prism ({@code icons/<key>.png}). */
    static final String ICON_KEY = "steinloader";

    /** The patched game, the instance's main jar. */
    static final String GAME = "dev.xavier.stein:minecraft-steinloader:1.8.9";

    /** Mojang's version list: where the 1.8.9 JSON (and from it the jar) comes from. */
    static final String MANIFEST =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private static final String PAULSCODE = "com.paulscode:librarylwjglopenal:20100824";

    private final Path prism;
    private final Path payload;
    private final Install.Options opt;
    private final Messages m;
    private final Install.Log log;
    private final Path inst;
    private final Path stein;

    PrismInstall(Path prism, Path payload, Install.Options opt, Messages m, Install.Log log) {
        this.prism = prism.toAbsolutePath().normalize();
        this.payload = payload;
        this.opt = opt;
        this.m = m;
        this.log = log;
        inst = instances(this.prism).resolve(INSTANCE_DIR);
        stein = inst.resolve("stein");
    }

    /** Is it a Prism Launcher data folder? */
    static boolean isPrism(Path dir) {
        return dir != null && Files.isRegularFile(dir.resolve(CONFIG));
    }

    /** Prism's data folders on this machine (the default one and, on Linux, the Flatpak's). */
    static List<Path> find() {
        Path home = Paths.get(System.getProperty("user.home"));
        List<Path> candidates = new ArrayList<>();
        if (Install.WINDOWS) {
            String appdata = System.getenv("APPDATA");
            if (appdata != null) {
                candidates.add(Paths.get(appdata, "PrismLauncher"));
            }
        } else if (Install.MACOS) {
            candidates.add(home.resolve("Library/Application Support/PrismLauncher"));
        } else {
            String xdg = System.getenv("XDG_DATA_HOME");
            Path data =
                    xdg != null && !xdg.isEmpty() ? Paths.get(xdg) : home.resolve(".local/share");
            candidates.add(data.resolve("PrismLauncher"));
            candidates.add(
                    home.resolve(".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher"));
        }
        List<Path> out = new ArrayList<>();
        for (Path p : candidates) {
            if (isPrism(p)) {
                out.add(p.toAbsolutePath().normalize());
            }
        }
        return out;
    }

    /** The instances folder: {@code InstanceDir} in prismlauncher.cfg, relative to Prism's. */
    static Path instances(Path prism) {
        String dir = "instances";
        try {
            for (String line : Install.readText(prism.resolve(CONFIG)).split("\\r?\\n")) {
                if (line.startsWith("InstanceDir=")) {
                    String v = line.substring("InstanceDir=".length()).trim();
                    if (!v.isEmpty()) {
                        dir = v;
                    }
                }
            }
        } catch (IOException e) {
            // the default
        }
        Path p = Paths.get(dir);
        return (p.isAbsolute() ? p : prism.resolve(p)).normalize();
    }

    /** The manifest of the Stein instance already in this Prism, or null. */
    static Map<String, Object> installed(Path prism) {
        Path man =
                instances(prism).resolve(INSTANCE_DIR).resolve("stein").resolve(Install.MANIFEST);
        try {
            return Files.isRegularFile(man) ? Json.object(Json.parse(Install.readText(man))) : null;
        } catch (IOException | RuntimeException e) {
            return Install.map("version", "?");
        }
    }

    /** The game folder of the instance (worlds, options, mods). */
    Path gameDir() {
        return inst.resolve("minecraft");
    }

    void install() throws Exception {
        Path runtime = stein.resolve("runtime");
        if (InstallerApp.gameRunning(runtime)) {
            throw new IllegalStateException(m.t("gameOpen"));
        }
        int total = 6;
        Map<String, Object> before = installed(prism);
        log.line(
                before == null
                        ? m.t("start", Install.VERSION, inst)
                        : m.t("update", before.get("version"), Install.VERSION, inst));
        log.line(
                m.t("versions", Install.BRIDGE_VERSION)
                        + (opt.loader ? ", Stein Loader " + Install.LOADER_VERSION : ""));
        Files.createDirectories(stein);
        Map<String, Object> vanilla = vanilla();
        Path game = prismLibrary(Json.get(vanilla, "downloads", "client"), clientPath());
        Path paul = prismLibrary(paulscode(vanilla), null);
        log.progress(1, total);

        Install tools = new Install(inst, runtime, payload, opt, m, log);
        tools.copyRuntime();
        log.progress(2, total);

        log.line(m.t("patching"));
        Path libs = inst.resolve("libraries");
        Files.createDirectories(libs);
        clearOurs(libs);
        tools.patch(game, libs.resolve(fileName(GAME)), Install.GAME_RULES);
        tools.patch(paul, libs.resolve(fileName(Install.PAULSCODE_STEIN)), "redirects-al.tsv");
        copy(
                payload.resolve("steinbridge.jar"),
                libs,
                "dev.xavier.stein:steinbridge:" + Install.BRIDGE_VERSION);
        if (opt.loader) {
            copy(
                    payload.resolve("steinloader.jar"),
                    libs,
                    "dev.xavier.stein:steinloader:" + Install.LOADER_VERSION);
        }
        log.progress(4, total);

        log.line(m.t("prismInstance", INSTANCE_NAME));
        Files.createDirectories(inst.resolve("patches"));
        Install.writeText(
                inst.resolve("patches/net.minecraft.json"), Json.write(component(vanilla)));
        Install.writeText(inst.resolve("mmc-pack.json"), Json.write(pack()));
        writeConfig(runtime);
        writeIcon();
        Files.createDirectories(gameDir());
        log.progress(5, total);

        Install.writeText(
                stein.resolve(Install.MANIFEST),
                Json.write(
                        Install.map(
                                "version",
                                Install.VERSION,
                                "loader",
                                opt.loader,
                                "ramGb",
                                new BigDecimal(opt.ramGb),
                                "installed",
                                Instant.now().toString())));
        installMods();
        log.progress(6, total);
        log.line(m.t("prismDone", INSTANCE_NAME));
    }

    /**
     * Uninstalling here would mean deleting the instance, and with it the worlds and options
     * inside: that stays with the player, in Prism (right click, Delete).
     */
    void uninstall() throws Install.InstallException {
        throw new Install.InstallException(m.t("prismUninstall", INSTANCE_NAME));
    }

    // ---- the player's 1.8.9, from Mojang ----

    /**
     * Mojang's 1.8.9 JSON, checked against the sha1 in the version list; the copy kept in the
     * instance serves when the list does not answer.
     */
    private Map<String, Object> vanilla() throws Exception {
        Path kept = stein.resolve("1.8.9.json");
        try {
            Path list = Files.createTempFile("stein-manifest", ".json");
            try {
                Mods.download(MANIFEST, list);
                Object versions = Json.get(Json.parse(Install.readText(list)), "versions");
                for (Object v : versions instanceof List ? (List<?>) versions : new ArrayList<>()) {
                    if ("1.8.9".equals(Json.get(v, "id"))) {
                        fetch(
                                String.valueOf(Json.get(v, "url")),
                                String.valueOf(Json.get(v, "sha1")),
                                kept);
                    }
                }
            } finally {
                Files.deleteIfExists(list);
            }
        } catch (IOException e) {
            if (!Files.isRegularFile(kept)) {
                throw new Install.InstallException(m.t("prismNoVanilla", e.getMessage()));
            }
            log.line(m.t("prismOffline"));
        }
        if (!Files.isRegularFile(kept)) {
            throw new Install.InstallException(m.t("prismNoVanilla", "1.8.9"));
        }
        log.line(m.t("found"));
        return Json.object(Json.parse(Install.readText(kept)));
    }

    /**
     * A Mojang file (described by its url and sha1) in Prism's own libraries folder, where Prism
     * would put it too: downloaded only when it is missing or does not match.
     */
    private Path prismLibrary(Object info, String path) throws Exception {
        if (info == null) {
            throw new Install.InstallException(m.t("prismNoVanilla", "1.8.9"));
        }
        String p = path != null ? path : String.valueOf(Json.get(info, "path"));
        Path file = prism.resolve("libraries").resolve(p);
        fetch(String.valueOf(Json.get(info, "url")), String.valueOf(Json.get(info, "sha1")), file);
        return file;
    }

    /** Downloads to {@code file} unless it is already there with that sha1; checks the result. */
    private void fetch(String url, String sha1, Path file) throws Exception {
        if (Files.isRegularFile(file) && sha1.equalsIgnoreCase(Install.sha1(file))) {
            return;
        }
        Files.createDirectories(file.getParent());
        Path part = file.resolveSibling(file.getFileName() + ".part");
        Mods.download(url, part);
        if (!sha1.equalsIgnoreCase(Install.sha1(part))) {
            Files.deleteIfExists(part);
            throw new Install.InstallException(m.t("prismBadFile", url));
        }
        Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Where Prism keeps the 1.8.9 client jar. */
    private static String clientPath() {
        return Install.mavenPath("com.mojang:minecraft:1.8.9:client");
    }

    /** The download of Mojang's paulscode OpenAL library, which gets patched for LWJGL 3. */
    private static Object paulscode(Map<String, Object> vanilla) {
        Object libs = vanilla.get("libraries");
        for (Object l : libs instanceof List ? (List<?>) libs : new ArrayList<>()) {
            if (String.valueOf(Json.get(l, "name")).equals(PAULSCODE)) {
                return Json.get(l, "downloads", "artifact");
            }
        }
        return null;
    }

    // ---- the instance ----

    /**
     * The customized Minecraft component: the 1.8.9 libraries minus LWJGL 2 and Mojang's paulscode,
     * ours as local libraries, LWJGL 3 from Maven Central, and the patched game as the main jar.
     */
    private Map<String, Object> component(Map<String, Object> vanilla) throws Exception {
        List<Object> libraries = new ArrayList<>();
        libraries.add(local("dev.xavier.stein:steinbridge:" + Install.BRIDGE_VERSION));
        if (opt.loader) {
            libraries.add(local("dev.xavier.stein:steinloader:" + Install.LOADER_VERSION));
        }
        Object vanillaLibs = vanilla.get("libraries");
        for (Object o : vanillaLibs instanceof List ? (List<?>) vanillaLibs : new ArrayList<>()) {
            String name = String.valueOf(Json.get(o, "name"));
            if (name.startsWith("org.lwjgl.lwjgl:")) {
                continue; // LWJGL 2: LWJGL 3 and the bridge's shim take its place
            }
            if (name.equals(PAULSCODE)) {
                libraries.add(local(Install.PAULSCODE_STEIN));
                continue;
            }
            libraries.add(o);
        }
        // LWJGL 3 last on the classpath: the shim classes (org.lwjgl.*) come first.
        for (String mod : Install.LWJGL_MODULES) {
            String coord = "org.lwjgl:" + mod + ":" + Install.LWJGL;
            Path jar = payload.resolve("lwjgl").resolve(mod + "-" + Install.LWJGL + ".jar");
            libraries.add(maven(coord, Install.mavenPath(coord), jar, null));
            String cls = "natives-" + Install.SYSTEM;
            Path natives =
                    payload.resolve("lwjgl")
                            .resolve(mod + "-" + Install.LWJGL + "-" + cls + ".jar");
            if (!Files.isRegularFile(natives)) {
                continue; // LWJGL's Vulkan has natives only for macOS
            }
            // The natives under a coordinate of their own (Maven's file, same url): the same
            // layout the version JSON uses, with this system's rule.
            String nativesCoord = "dev.xavier.stein.lwjgl:" + mod + "-" + cls + ":" + Install.LWJGL;
            libraries.add(
                    maven(
                            nativesCoord,
                            Install.mavenPath(nativesCoord),
                            natives,
                            Install.mavenPath(coord + ":" + cls)));
        }
        String args =
                String.valueOf(vanilla.get("minecraftArguments"))
                        .replace("${version_name}", "1.8.9");
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("formatVersion", new BigDecimal(1));
        c.put("uid", "net.minecraft");
        c.put("version", "1.8.9");
        c.put("name", "Minecraft");
        c.put("type", "release");
        c.put("releaseTime", vanilla.get("releaseTime"));
        c.put("mainClass", "dev.xavier.stein.bridge.launch.Main");
        c.put("minecraftArguments", args);
        c.put("assetIndex", vanilla.get("assetIndex"));
        c.put("compatibleJavaMajors", Arrays.asList(new BigDecimal(25)));
        c.put("mainJar", local(GAME));
        c.put("libraries", libraries);
        // The same JVM arguments as the official launcher's profile (Install.writeProfiles explains
        // each); the memory cap comes from the instance.
        c.put(
                "+jvmArgs",
                Arrays.asList(
                        "-XX:SoftMaxHeapSize=1024M",
                        "-XX:+UseZGC",
                        "-XX:-ZProactive",
                        "--enable-native-access=ALL-UNNAMED"));
        // On macOS GLFW needs the main thread (-XstartOnFirstThread): Prism adds it with this
        // trait.
        c.put("+traits", Arrays.asList("FirstThreadOnMacOS"));
        return c;
    }

    /** Only the Minecraft component: the customized one, in patches/. */
    private static Map<String, Object> pack() {
        Map<String, Object> mc = new LinkedHashMap<>();
        mc.put("cachedName", "Minecraft");
        mc.put("cachedVersion", "1.8.9");
        mc.put("important", true);
        mc.put("uid", "net.minecraft");
        mc.put("version", "1.8.9");
        return Install.map("components", Arrays.asList(mc), "formatVersion", new BigDecimal(1));
    }

    /**
     * instance.cfg: what Stein needs (Java 25, memory, name, icon) over what is already there
     * (Prism's playtime and the player's own settings stay).
     */
    private void writeConfig(Path runtime) throws IOException {
        Path cfg = inst.resolve("instance.cfg");
        Map<String, String> values = new LinkedHashMap<>();
        if (Files.isRegularFile(cfg)) {
            for (String line : Install.readText(cfg).split("\\r?\\n")) {
                int eq = line.indexOf('=');
                if (eq > 0 && !line.startsWith("[")) {
                    values.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
        }
        values.put("ConfigVersion", "1.3");
        values.put("InstanceType", "OneSix");
        values.put("name", INSTANCE_NAME);
        values.put("iconKey", ICON_KEY);
        values.put("OverrideJavaLocation", "true");
        values.put("AutomaticJava", "false");
        values.put(
                "JavaPath",
                runtime.resolve("bin")
                        .resolve(Install.javaBin(true))
                        .toString()
                        .replace('\\', '/'));
        values.put("IgnoreJavaCompatibility", "true");
        values.put("OverrideMemory", "true");
        values.put("MinMemAlloc", "256");
        values.put("MaxMemAlloc", String.valueOf(opt.ramGb * 1024));
        StringBuilder b = new StringBuilder("[General]\n");
        for (Map.Entry<String, String> e : values.entrySet()) {
            b.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        Install.writeText(cfg, b.toString());
    }

    private void writeIcon() throws IOException {
        Path icons = prism.resolve("icons");
        Files.createDirectories(icons);
        Files.write(icons.resolve(ICON_KEY + ".png"), Install.icon());
    }

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
            int changed = Mods.apply(gameDir().resolve("mods"), list, opt.mods, log, m);
            log.line(changed > 0 ? m.t("modsDone", String.valueOf(changed)) : m.t("modsNothing"));
        } catch (IOException | RuntimeException e) {
            log.line(m.t("modsFailed", String.valueOf(e)));
        }
    }

    // ---- libraries ----

    /**
     * A local library of the instance: Prism finds it by file name in the instance's libraries/.
     */
    private static Map<String, Object> local(String coord) {
        return Install.map("name", coord, "MMC-hint", "local");
    }

    /** A library Prism downloads from Maven Central, checked by the sha1 of the payload's copy. */
    private static Map<String, Object> maven(String coord, String path, Path jar, String urlPath)
            throws Exception {
        String url = Install.mavenPath(coord);
        Map<String, Object> artifact =
                Install.map(
                        "path",
                        path,
                        "sha1",
                        Install.sha1(jar),
                        "size",
                        new BigDecimal(Files.size(jar)),
                        "url",
                        Urls.MAVEN + (urlPath != null ? urlPath : url));
        Map<String, Object> lib =
                Install.map("name", coord, "downloads", Install.map("artifact", artifact));
        if (urlPath != null) {
            lib.put(
                    "rules",
                    Arrays.asList(
                            Install.map(
                                    "action",
                                    "allow",
                                    "os",
                                    Install.map("name", Install.LAUNCHER_OS))));
        }
        return lib;
    }

    private void copy(Path src, Path libs, String coord) throws Exception {
        if (!Files.isRegularFile(src)) {
            throw new Install.InstallException(m.t("incomplete", payload.relativize(src)));
        }
        Files.copy(src, libs.resolve(fileName(coord)), StandardCopyOption.REPLACE_EXISTING);
    }

    /** Earlier versions of ours go away (the version is part of the file name). */
    private static void clearOurs(Path libs) throws IOException {
        try (DirectoryStream<Path> s =
                Files.newDirectoryStream(libs, "stein{bridge,loader}-*.jar")) {
            for (Path p : s) {
                Files.delete(p);
            }
        }
    }

    /** artifact-version.jar, the file name Prism looks for. */
    static String fileName(String coord) {
        String p = Install.mavenPath(coord);
        return p.substring(p.lastIndexOf('/') + 1);
    }
}
