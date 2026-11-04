package com.lucasluqui.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@code updatePropertiesInsideZip} is how a private launch points the game's config.jar at another server. */
public class ZipUtilTest {

    private static Path zipWith(Path dir, String properties) throws IOException {
        Path zip = dir.resolve("config.jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("deployment.properties"));
            out.write(properties.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("other/keep.txt"));
            out.write("unchanged".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return zip;
    }

    private static String read(Path zip, String entry) throws IOException {
        try (ZipFile file = new ZipFile(zip.toFile()); InputStream in = file.getInputStream(file.getEntry(entry))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void updatesOnlyTheGivenKeysAndKeepsOtherEntries(@TempDir Path dir) throws IOException {
        // Same shape as the game's file: a key with a '#', a continued value, and an untouched setting.
        Path zip = zipWith(dir, "server_host = game.spiralknights.com\n"
            + "server_ports = 47624\n"
            + "key.public = abc\\\ndef#10001\n"
            + "default_locale = en\n");

        Properties updates = new Properties();
        updates.setProperty("server_host", "127.0.0.1");
        updates.setProperty("server_ports", "47999");
        ZipUtil.updatePropertiesInsideZip(zip, "deployment.properties", updates);

        Properties result = new Properties();
        result.load(new ByteArrayInputStream(read(zip, "deployment.properties").getBytes(StandardCharsets.UTF_8)));
        assertEquals("127.0.0.1", result.getProperty("server_host"));
        assertEquals("47999", result.getProperty("server_ports"));
        assertEquals("abcdef#10001", result.getProperty("key.public"), "the key must survive the round trip");
        assertEquals("en", result.getProperty("default_locale"));
        assertEquals("unchanged", read(zip, "other/keep.txt"));
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "no temporary file may be left behind");
        }
    }

    @Test
    public void missingEntryFailsAndLeavesTheJarAlone(@TempDir Path dir) throws IOException {
        Path zip = zipWith(dir, "a = b\n");
        byte[] before = Files.readAllBytes(zip);
        assertThrows(IOException.class, () -> ZipUtil.updatePropertiesInsideZip(zip, "nope.properties", new Properties()));
        assertEquals(before.length, Files.readAllBytes(zip).length);
    }
}
