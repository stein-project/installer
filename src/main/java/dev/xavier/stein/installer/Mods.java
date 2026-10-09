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
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The mods, downloaded and updated by the installer.
 *
 * <p>The installer reads the list published as {@code stein.json} (the one CI writes on every
 * release) and puts each chosen mod in the mods folder, already at its latest version. A new mod
 * shows up for everyone without a new installer.
 *
 * <p>Three rules that matter more than convenience:
 *
 * <ul>
 *   <li><b>Only what is on the list is touched.</b> A {@code .steinmod} the installer does not know
 *       about, from someone else or for personal use, is never changed or removed.
 *   <li><b>The file is verified before it goes in</b> (sha256 from the list). A partial download,
 *       or one swapped on the way, never becomes a file in the mods folder.
 *   <li><b>No internet is not an error.</b> If the list does not arrive, the installer says so in
 *       one line and carries on: the game installs the same, just without touching the mods.
 * </ul>
 */
final class Mods {

    private static final int TIMEOUT_MS = 15_000;

    /** A mod in the published list. */
    static final class Mod {
        final String id;

        /** The id inside steinmod.json ("steincombat"), which recognises the installed file. */
        final String modId;

        final String name;
        final String file;
        final String version;
        final String url;
        final String sha256;
        final String loader;
        final String description;

        Mod(Map<String, Object> m) {
            id = str(m, "id");
            modId = str(m, "modId");
            name = str(m, "name");
            file = str(m, "file");
            version = str(m, "version");
            url = str(m, "url");
            sha256 = str(m, "sha256");
            loader = str(m, "loader");
            description = str(m, "description");
        }

        boolean isValid() {
            return !id.isEmpty()
                    && !file.isEmpty()
                    && !version.isEmpty()
                    && url.startsWith("https://")
                    && sha256.matches("[0-9a-fA-F]{64}")
                    && file.endsWith(".steinmod")
                    && file.indexOf('/') < 0
                    && file.indexOf('\\') < 0;
        }

        /** Does this mod need a newer Loader than the one this installer carries? */
        boolean needsNewerLoader() {
            return !loader.isEmpty() && compare(loader, Install.LOADER_VERSION) > 0;
        }

        private static String str(Map<String, Object> m, String k) {
            Object v = m.get(k);
            return v == null ? "" : String.valueOf(v);
        }
    }

    private Mods() {}

    /** The published list, or empty when it cannot be fetched (offline, server down). */
    static List<Mod> index() {
        List<Mod> out = new ArrayList<>();
        try {
            // Old Java 8 tries TLS 1.0 by default and GitHub refuses the connection; asking for 1.2
            // fixes it. Only on Java 8, and only "TLSv1.2": Java 8 does not know the name TLSv1.3
            // and
            // rejects the whole list if it appears, and on a newer Java the property would only
            // drop
            // 1.3.
            if (System.getProperty("https.protocols") == null
                    && "1.8".equals(System.getProperty("java.specification.version"))) {
                System.setProperty("https.protocols", "TLSv1.2");
            }
            byte[] bytes = download(Urls.INDEX, null);
            Object root = Json.parse(new String(bytes, StandardCharsets.UTF_8));
            Object mods = Json.object(root).get("mods");
            if (mods instanceof List) {
                for (Object o : (List<?>) mods) {
                    Mod m = new Mod(Json.object(o));
                    if (m.isValid()) {
                        out.add(m);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            return new ArrayList<>();
        }
        return out;
    }

    /**
     * What is already in the folder: mod id -> file and version read from the {@code .steinmod}.
     */
    static Map<String, String[]> installedMods(Path modsDir) {
        Map<String, String[]> out = new LinkedHashMap<>();
        if (!Files.isDirectory(modsDir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(modsDir)) {
            for (Path p :
                    (Iterable<Path>)
                            s.filter(f -> f.getFileName().toString().endsWith(".steinmod"))
                                    ::iterator) {
                String[] info = readManifest(p);
                if (info != null) {
                    out.put(info[0], new String[] {p.getFileName().toString(), info[1]});
                }
            }
        } catch (IOException e) {
            return out;
        }
        return out;
    }

    /** {id, version} from the steinmod.json inside the file, or null if it is not one of ours. */
    private static String[] readManifest(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry e = z.getEntry("steinmod.json");
            if (e == null) {
                return null;
            }
            try (InputStream in = z.getInputStream(e)) {
                Map<String, Object> m =
                        Json.object(
                                Json.parse(
                                        new String(Install.readAll(in), StandardCharsets.UTF_8)));
                Object id = m.get("id");
                Object version = m.get("version");
                return id == null
                        ? null
                        : new String[] {String.valueOf(id), String.valueOf(version)};
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Puts the chosen mods in the folder at the list's version and removes the ones that were
     * unticked. Whatever is not on the list stays where it is.
     *
     * @return how many files changed
     */
    static int apply(Path modsDir, List<Mod> list, Set<String> chosen, Install.Log log, Messages m)
            throws IOException {
        Files.createDirectories(modsDir);
        Map<String, String[]> have = installedMods(modsDir);
        int changed = 0;
        for (Mod mod : list) {
            String[] current = have.get(mod.modId.isEmpty() ? mod.id : mod.modId);
            if (!isChosen(mod, chosen)) {
                if (current != null) {
                    Files.deleteIfExists(modsDir.resolve(current[0]));
                    log.line(m.t("modRemoved", mod.name));
                    changed++;
                }
                continue;
            }
            if (mod.needsNewerLoader()) {
                log.line(m.t("modTooNew", mod.name, mod.loader));
                continue;
            }
            if (current != null
                    && mod.version.equals(current[1])
                    && Files.isRegularFile(modsDir.resolve(mod.file))) {
                log.line(m.t("modOk", mod.name, mod.version));
                continue;
            }
            log.line(m.t("modDownload", mod.name, mod.version));
            Path tmp = Files.createTempFile("stein-mod", ".part");
            try {
                byte[] sha = download(mod.url, tmp);
                if (!Install.hex(sha).equalsIgnoreCase(mod.sha256)) {
                    log.line(m.t("modBadSum", mod.name));
                    continue;
                }
                // The old file may have another name (the mod changed its file name).
                if (current != null && !current[0].equals(mod.file)) {
                    Files.deleteIfExists(modsDir.resolve(current[0]));
                }
                Files.move(tmp, modsDir.resolve(mod.file), StandardCopyOption.REPLACE_EXISTING);
                changed++;
            } catch (IOException e) {
                log.line(m.t("modFailed", mod.name, String.valueOf(e)));
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
        return changed;
    }

    /**
     * Was the mod chosen? Both the component's short id ({@code combat}, which the window and
     * {@code --mods} use) and the id inside the mod ({@code steincombat}, which is what is read
     * from the folder to know what is installed) count. Accepting both avoids one side talking
     * about one id and the other about another, which in the first version deleted an installed mod
     * because it looked like it had not been chosen.
     */
    private static boolean isChosen(Mod mod, Set<String> chosen) {
        return chosen.contains(mod.id) || !mod.modId.isEmpty() && chosen.contains(mod.modId);
    }

    /**
     * Downloads the address. With {@code target}, writes to it and returns the sha256 of what was
     * written; without a target, returns the content (the list, which is small).
     */
    static byte[] download(String url, Path target) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "SteinLoader-Installer/" + Install.VERSION);
        c.setRequestProperty("Accept", "*/*");
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code + " at " + url);
            }
            try (InputStream in = c.getInputStream()) {
                if (target == null) {
                    return Install.readAll(in);
                }
                MessageDigest md;
                try {
                    md = MessageDigest.getInstance("SHA-256");
                } catch (java.security.NoSuchAlgorithmException e) {
                    throw new IOException(e);
                }
                try (OutputStream out = Files.newOutputStream(target)) {
                    byte[] buf = new byte[1 << 16];
                    for (int n; (n = in.read(buf)) > 0; ) {
                        md.update(buf, 0, n);
                        out.write(buf, 0, n);
                    }
                }
                return md.digest();
            }
        } finally {
            c.disconnect();
        }
    }

    /** "0.1.10" is newer than "0.1.9": compares number by number, not as text. */
    static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int va = i < x.length ? number(x[i]) : 0;
            int vb = i < y.length ? number(y[i]) : 0;
            if (va != vb) {
                return va < vb ? -1 : 1;
            }
        }
        return 0;
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
