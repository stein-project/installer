/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

import dev.xavier.stein.installer.Json;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Launches a version from a .minecraft folder the way the official launcher would: reads the
 * version JSON (per-OS rules, libraries, natives to extract, arguments with ${...}) and the profile
 * that points to it (Java, JVM arguments, game directory). It tests what the installer writes
 * without the launcher (which requires a login). Offline account; assets from another folder
 * (read-only).
 *
 * <p>--legacy: like the old launcher (Mojang's until 2017 and its derivatives, such as Shiginima):
 * reads minecraftArguments instead of arguments, uses only its own JVM arguments (natives and
 * classpath) plus the profile's, checks the dates with its date reader and requires the profile key
 * to equal the name (it selects by name).
 *
 * <p>Usage: java -cp <classes dir> test/LaunchVersion.java &lt;.minecraft&gt; &lt;id&gt;
 * &lt;assetsDir&gt; [--legacy]
 */
public class LaunchVersion {

    public static void main(String[] a) throws Exception {
        Path mc = Paths.get(a[0]).toAbsolutePath();
        String id = a[1];
        Path assets = Paths.get(a[2]).toAbsolutePath();
        boolean legacy = a.length > 3 && a[3].equals("--legacy");
        Map<String, Object> v =
                Json.object(
                        Json.parse(
                                Files.readString(
                                        mc.resolve("versions/" + id + "/" + id + ".json"))));
        if (legacy) {
            legacyDate(String.valueOf(v.get("time")));
            legacyDate(String.valueOf(v.get("releaseTime")));
        }

        Map<String, Object> profile = null;
        Object profiles =
                Json.get(
                        Json.parse(Files.readString(mc.resolve("launcher_profiles.json"))),
                        "profiles");
        for (Map.Entry<?, ?> e : ((Map<?, ?>) profiles).entrySet()) {
            if (id.equals(Json.get(e.getValue(), "lastVersionId"))) {
                profile = Json.object(e.getValue());
                if (legacy && !e.getKey().equals(profile.get("name"))) {
                    throw new IllegalStateException(
                            "profile with key \""
                                    + e.getKey()
                                    + "\" differs from its name: the old launcher does not select"
                                    + " it");
                }
            }
        }
        if (profile == null) {
            throw new IllegalStateException("no profile points to " + id);
        }

        Path natives = Files.createTempDirectory("natives-" + id);
        List<String> cp = new ArrayList<>();
        for (Object lib : (List<?>) v.get("libraries")) {
            if (!allowed(Json.get(lib, "rules"))) {
                continue;
            }
            Object nat = Json.get(lib, "natives", "windows");
            if (nat != null) {
                String classifier = nat.toString().replace("${arch}", "64");
                Object path = Json.get(lib, "downloads", "classifiers", classifier, "path");
                if (path != null) {
                    extract(mc.resolve("libraries").resolve(path.toString()), natives);
                }
                continue;
            }
            // The old launcher builds the path from the name; the official one uses the JSON path
            // and,
            // without downloads (a library by name only), the same Maven layout.
            Object path = legacy ? null : Json.get(lib, "downloads", "artifact", "path");
            Path jar =
                    mc.resolve("libraries")
                            .resolve(
                                    path != null
                                            ? path.toString()
                                            : legacyPath(String.valueOf(Json.get(lib, "name"))));
            if (!Files.isRegularFile(jar)) {
                throw new IllegalStateException("library missing: " + jar);
            }
            cp.add(jar.toString());
        }
        cp.add(mc.resolve("versions/" + id + "/" + id + ".jar").toString());

        // Without a gameDir in the profile, the launcher uses the regular .minecraft.
        Object gd = profile.get("gameDir");
        Path gameDir = gd == null ? mc : Paths.get(gd.toString());
        Files.createDirectories(gameDir);
        List<String> cmd = new ArrayList<>();
        String javaw = String.valueOf(profile.get("javaDir"));
        cmd.add(
                javaw.replace(
                        "javaw.exe", "java.exe")); // java.exe: the output goes to the terminal
        for (String s : String.valueOf(profile.get("javaArgs")).trim().split("\\s+")) {
            cmd.add(s);
        }
        List<?> jvm =
                legacy
                        ? List.of("-Djava.library.path=${natives_directory}", "-cp", "${classpath}")
                        : (List<?>) Json.get(v, "arguments", "jvm");
        for (Object o : jvm) {
            cmd.add(
                    o.toString()
                            .replace("${natives_directory}", natives.toString())
                            .replace("${launcher_name}", "stein-test")
                            .replace("${launcher_version}", "1")
                            .replace("${classpath}", String.join(File.pathSeparator, cp)));
        }
        cmd.add(String.valueOf(v.get("mainClass")));
        List<?> game =
                legacy
                        ? List.of(String.valueOf(v.get("minecraftArguments")).split(" "))
                        : (List<?>) Json.get(v, "arguments", "game");
        for (Object o : game) {
            cmd.add(
                    o.toString()
                            .replace("${auth_player_name}", "SteinBridge")
                            .replace("${version_name}", id)
                            .replace("${game_directory}", gameDir.toString())
                            .replace("${assets_root}", assets.toString())
                            .replace("${assets_index_name}", String.valueOf(v.get("assets")))
                            .replace("${auth_uuid}", "00000000000000000000000000000000")
                            .replace("${auth_access_token}", "0")
                            .replace("${user_properties}", "{}")
                            .replace("${user_type}", "legacy"));
        }
        System.out.println("[launch] " + cp.size() + " classpath entries, natives in " + natives);
        System.out.println("[launch] java: " + cmd.get(0));
        System.out.println(
                "[launch] mode: "
                        + (legacy
                                ? "old launcher (minecraftArguments)"
                                : "official launcher (arguments)"));
        Process p = new ProcessBuilder(cmd).directory(gameDir.toFile()).inheritIO().start();
        System.exit(p.waitFor());
    }

    /**
     * The old launcher's date reader (DateTypeAdapter): replaces "Z" with "+00:00", drops the ":"
     * of the zone and reads "yyyy-MM-dd'T'HH:mm:ssZ"; fractional seconds do not pass, and with an
     * invalid date it discards the whole version.
     */
    static void legacyDate(String s) throws Exception {
        String cleaned = s.replace("Z", "+00:00");
        cleaned = cleaned.substring(0, 22) + cleaned.substring(23);
        new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US)
                .parse(cleaned);
    }

    /**
     * The old launcher's Library.getArtifactPath: the name cut in 3 (split(":", 3)) and
     * "artifact-version.jar"; a classifier becomes ":" in the path.
     */
    static String legacyPath(String name) {
        String[] p = name.split(":", 3);
        return p[0].replace('.', '/') + "/" + p[1] + "/" + p[2] + "/" + p[1] + "-" + p[2] + ".jar";
    }

    /** Launcher rules: the last matching one wins; with no rules, allowed. */
    static boolean allowed(Object rules) {
        if (rules == null) {
            return true;
        }
        boolean ok = false;
        for (Object r : (List<?>) rules) {
            Object os = Json.get(r, "os", "name");
            if (os == null || os.equals("windows")) {
                ok = "allow".equals(Json.get(r, "action"));
            }
        }
        return ok;
    }

    static void extract(Path jar, Path dir) throws Exception {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (ZipEntry e : java.util.Collections.list(z.entries())) {
                if (e.isDirectory() || e.getName().startsWith("META-INF/")) {
                    continue;
                }
                Path out = dir.resolve(Paths.get(e.getName()).getFileName().toString());
                try (InputStream in = z.getInputStream(e)) {
                    Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
