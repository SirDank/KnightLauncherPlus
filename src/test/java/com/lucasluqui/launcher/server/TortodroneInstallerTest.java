package com.lucasluqui.launcher.server;

import com.lucasluqui.util.BuildConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TortodroneInstallerTest {
    @TempDir Path dir;
    /** Messages come back as their keys, which is all these tests need to tell them apart. */
    private static final java.util.function.BiFunction<String, String[], String> KEYS = (key, args) -> key;

    private Path zip(Map<String, String> entries) throws IOException {
        Path file = dir.resolve("release.zip");
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    private Map<String, String> release() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("tortodrone.jar", "jar");
        entries.put("content/town/haven.yml", "town: haven\n");
        entries.put("config/server.yml", "# a comment\nmax-clients: 16\n# another\nstartup-map: haven\n");
        entries.put("config/combat.yml", "dodgeCooldownMs: 8000\n");
        entries.put("webui/index.html", "<html>");
        entries.put("README.md", "docs");
        return entries;
    }

    @Test
    public void theContentAndConfigAreInstalledAndTheRestIsLeftOut() throws IOException {
        Path server = dir.resolve("server");
        TortodroneInstaller.extract(zip(release()), server);
        assertFalse(Files.exists(server.resolve("tortodrone.jar")), "the launcher carries its own jar");
        assertTrue(Files.isRegularFile(server.resolve("content/town/haven.yml")));
        assertFalse(Files.exists(server.resolve("webui")));
        assertFalse(Files.exists(server.resolve("README.md")));
        assertTrue(Files.isDirectory(server.resolve("plugins")));
        String config = Files.readString(server.resolve("config/server.yml"));
        assertFalse(config.contains("#"), "comments are removed: " + config);
        assertTrue(config.contains("max-clients: 16") && config.contains("startup-map: haven"));
    }

    @Test
    public void anUpdateReplacesTheContentButNeverTheUsersConfiguration() throws IOException {
        Path server = dir.resolve("server");
        TortodroneInstaller.extract(zip(release()), server);
        Files.writeString(server.resolve("config/server.yml"), "max-clients: 99\n");
        Map<String, String> newer = release();
        newer.put("content/town/haven.yml", "town: newer\n");
        TortodroneInstaller.extract(zip(newer), server);
        assertEquals("town: newer\n", Files.readString(server.resolve("content/town/haven.yml")));
        assertEquals("max-clients: 99\n", Files.readString(server.resolve("config/server.yml")));
    }

    @Test
    public void anArchiveThatClimbsOutOfTheFolderIsRefused() throws IOException {
        Map<String, String> evil = new LinkedHashMap<>();
        evil.put("content/../../escaped.txt", "x");
        assertThrows(IOException.class, () -> TortodroneInstaller.extract(zip(evil), dir.resolve("server")));
        assertFalse(Files.exists(dir.resolve("escaped.txt")));
    }

    @Test
    public void theChecksumIsTheSha256OfTheFile() throws IOException {
        Path file = dir.resolve("f");
        Files.writeString(file, "abc");
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", TortodroneInstaller.sha256(file));
    }

    @Test
    public void contentInstalledMeansContentConfigAndTheMatchingVersion() throws IOException {
        Path server = dir.resolve("server");
        TortodroneInstaller.extract(zip(release()), server);
        assertFalse(TortodroneInstaller.contentInstalled(server.toFile(), "0.40.8"), "no version stamp yet");
        Files.writeString(server.resolve(".tortodrone-version"), "0.40.8");
        assertTrue(TortodroneInstaller.contentInstalled(server.toFile(), "0.40.8"));
        assertFalse(TortodroneInstaller.contentInstalled(server.toFile(), "0.41.0"), "another version is an update");
    }

    @Test
    public void theBundledJarIsTheOneThePinnedBuildSays() throws IOException {
        Path server = dir.resolve("server");
        Files.createDirectories(server);
        String pinned = BuildConfig.getTortodroneJarSha256();
        assertFalse(pinned.isEmpty(), "build.properties pins the bundled jar");
        assertNull(TortodroneInstaller.installEngine(server.toFile(), pinned, KEYS));
        assertEquals(pinned, TortodroneInstaller.sha256(server.resolve("tortodrone.jar")));
    }

    @Test
    public void theLicenseAndNoticesTravelWithTheJar() throws IOException {
        Path server = dir.resolve("server");
        Files.createDirectories(server);
        assertNull(TortodroneInstaller.installEngine(server.toFile(), BuildConfig.getTortodroneJarSha256(), KEYS));
        assertTrue(Files.readString(server.resolve("LICENSE")).contains("PolyForm Noncommercial License 1.0.0"));
        assertTrue(Files.readString(server.resolve("NOTICE")).contains("Required Notice: Tortodrone by N0rule"));
        assertTrue(Files.isRegularFile(server.resolve("THIRD-PARTY.md")));
        assertTrue(Files.isRegularFile(server.resolve("licenses/jline.txt")));
    }

    @Test
    public void anOutdatedOrDamagedJarIsReplacedAndAMismatchedBundleIsRefused() throws IOException {
        Path server = dir.resolve("server");
        Files.createDirectories(server);
        Files.writeString(server.resolve("tortodrone.jar"), "an older launcher's jar");
        String pinned = BuildConfig.getTortodroneJarSha256();
        assertNull(TortodroneInstaller.installEngine(server.toFile(), pinned, KEYS));
        assertEquals(pinned, TortodroneInstaller.sha256(server.resolve("tortodrone.jar")));

        // A launcher whose bundled jar is not the one it pins must not install it.
        Path other = dir.resolve("other");
        Files.createDirectories(other);
        String problem = TortodroneInstaller.installEngine(other.toFile(), "0".repeat(64), KEYS);
        assertEquals("m.engine_damaged", problem);
        assertFalse(Files.exists(other.resolve("tortodrone.jar")));
        assertFalse(Files.exists(other.resolve("tortodrone.jar.tmp")));
    }
}
