package com.lucasluqui.launcher.server;

import java.io.File;
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
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.lucasluqui.launcher.LauncherGlobals;
import com.lucasluqui.util.BuildConfig;

import static com.lucasluqui.launcher.Log.log;

/**
 * Installs the Tortodrone server engine into {@code KnightLauncher/server}.
 * <p>
 * The engine jar is bundled with the launcher, unmodified, together with its license and notices (PolyForm
 * Noncommercial 1.0.0, which asks that they travel with every copy). It runs as a separate program in its own process.
 * The content pack (maps, items) is derived from the game and its author does not license it for redistribution, so it
 * is downloaded once from the engine's own release page instead, pinned to a SHA-256 in build.properties.
 */
public final class TortodroneInstaller {
    private static final String STAMP = ".tortodrone-version";
    private static final String BUNDLE = "/rsrc/modules/tortodrone/";
    /** The license and notices that go with every copy of the engine. */
    private static final String[] TERMS = {
        "LICENSE", "NOTICE", "THIRD-PARTY.md", "licenses/README.md", "licenses/apache-2.0.txt", "licenses/jline.txt",
    };

    private TortodroneInstaller() {}

    public static File serverDir() {
        return new File(LauncherGlobals.USER_DIR, "KnightLauncher/server");
    }

    /**
     * Makes sure the pinned engine is installed in {@code KnightLauncher/server}: the bundled jar always, the content
     * pack (downloaded) when it is missing or from another version.
     *
     * @param progress Receives lines for the server console.
     * @param text Turns a message key and its arguments into text in the user's language.
     * @return null when it is in place, otherwise what to tell the user.
     */
    public static synchronized String ensureInstalled(Consumer<String> progress, BiFunction<String, String[], String> text) {
        File dir = serverDir();
        String version = BuildConfig.getTortodroneVersion();
        try {
            Files.createDirectories(dir.toPath());
            String problem = installEngine(dir, BuildConfig.getTortodroneJarSha256(), text);
            if (problem != null) return problem;
            if (contentInstalled(dir, version)) return null;

            String url = BuildConfig.getTortodroneUrl();
            String sha256 = BuildConfig.getTortodroneSha256();
            if (url.isEmpty() || sha256.isEmpty()) return text.apply("m.engine_no_content_url", new String[0]);

            Path zip = new File(dir, ".download.zip").toPath();
            try {
                progress.accept("[INFO] " + text.apply("m.engine_downloading", new String[] {version}));
                download(url, zip, progress);
                if (!sha256.equalsIgnoreCase(sha256(zip))) {
                    return text.apply("m.engine_integrity", new String[0]);
                }
                progress.accept("[INFO] " + text.apply("m.engine_installing", new String[0]));
                extract(zip, dir.toPath());
            } finally {
                Files.deleteIfExists(zip);
            }
            Files.writeString(new File(dir, STAMP).toPath(), version, StandardCharsets.UTF_8);
            progress.accept("[INFO] " + text.apply("m.engine_installed", new String[] {version}));
            return contentInstalled(dir, version) ? null : text.apply("m.engine_incomplete", new String[0]);
        } catch (IOException e) {
            log.error("Could not install the Tortodrone server engine", e);
            return text.apply("m.engine_download_failed", new String[] {String.valueOf(e.getMessage())});
        }
    }

    /**
     * Puts the bundled engine jar and its license files in {@code dir}, replacing a jar that is not the bundled one
     * (an older launcher's, or a damaged copy). Works offline.
     *
     * @param expectedSha256 The SHA-256 the bundled jar must have.
     * @param text Turns a message key and its arguments into text in the user's language.
     * @return null on success, otherwise what to tell the user.
     */
    static String installEngine(File dir, String expectedSha256, BiFunction<String, String[], String> text) throws IOException {
        if (expectedSha256.isEmpty()) return text.apply("m.engine_no_jar_hash", new String[0]);
        Path jar = dir.toPath().resolve("tortodrone.jar");
        if (!(Files.isRegularFile(jar) && expectedSha256.equalsIgnoreCase(sha256(jar)))) {
            Path temp = dir.toPath().resolve("tortodrone.jar.tmp");
            copyBundled("tortodrone.jar", temp);
            if (!expectedSha256.equalsIgnoreCase(sha256(temp))) {
                Files.deleteIfExists(temp);
                return text.apply("m.engine_damaged", new String[0]);
            }
            Files.move(temp, jar, StandardCopyOption.REPLACE_EXISTING);
        }
        for (String term : TERMS) {
            Path target = dir.toPath().resolve(term);
            Files.createDirectories(target.getParent());
            copyBundled(term, target);
        }
        return null;
    }

    private static void copyBundled(String name, Path target) throws IOException {
        try (InputStream in = TortodroneInstaller.class.getResourceAsStream(BUNDLE + name)) {
            if (in == null) throw new IOException("this launcher does not contain " + name);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static boolean contentInstalled(File dir, String version) throws IOException {
        File stamp = new File(dir, STAMP);
        return new File(dir, "content").isDirectory() && new File(dir, "config/server.yml").isFile()
            && stamp.isFile() && version.equals(Files.readString(stamp.toPath(), StandardCharsets.UTF_8).trim());
    }

    private static void download(String address, Path target, Consumer<String> progress) throws IOException {
        URL url = new URL(address);
        if (!"https".equals(url.getProtocol())) throw new IOException("the download address is not https");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        if (connection.getResponseCode() != 200) throw new IOException("HTTP " + connection.getResponseCode());
        long total = connection.getContentLengthLong();
        try (InputStream in = connection.getInputStream(); OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long done = 0;
            int lastTenth = 0;
            for (int read; (read = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, read);
                done += read;
                int tenth = total > 0 ? (int) (done * 10 / total) : 0;
                if (tenth > lastTenth) {
                    lastTenth = tenth;
                    progress.accept("[INFO] ... " + tenth * 10 + "%");
                }
            }
        }
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = in.read(buffer)) > 0; ) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /**
     * Unpacks the release's content pack, which replaces what is there, and its configuration, which is only added
     * (never overwritten) and written without comments, so the launcher's later edits do not change its look. The jar
     * (the launcher carries its own), the web administration pages and everything else in the archive are skipped; the
     * launcher turns the web UI off.
     */
    static void extract(Path zip, Path dir) throws IOException {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                String name = entry.getName();
                boolean engine = name.startsWith("content/");
                boolean config = name.equals("config/server.yml") || name.equals("config/combat.yml");
                if (entry.isDirectory() || !(engine || config)) continue;
                Path target = dir.resolve(name).normalize();
                if (!target.startsWith(dir)) throw new IOException("unsafe path in the download: " + name);
                if (config && Files.exists(target)) continue;
                Files.createDirectories(target.getParent());
                Path temp = target.resolveSibling(target.getFileName() + ".tmp");
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                if (config) ServerPropertiesFile.stripComments(target.toFile());
            }
        }
        Files.createDirectories(dir.resolve("plugins"));
    }

    /** The engine's version this launcher is pinned to, for display. */
    public static String pinnedVersion() {
        return BuildConfig.getTortodroneVersion().toLowerCase(Locale.ROOT);
    }
}
