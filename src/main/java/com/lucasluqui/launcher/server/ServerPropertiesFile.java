package com.lucasluqui.launcher.server;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Reads and writes the optional {@code server.properties} that holds the server's settings (see {@link ServerSettings}).
 * Only settings that differ from the defaults are written, so a later change of a default still reaches everyone who
 * never touched that setting. Keys it does not know are kept.
 */
public final class ServerPropertiesFile {

    private static final String HEADER = "Settings of the built-in server, edited in the launcher's Server Settings window."
        + "\nOnly values that differ from the defaults are listed. Changes apply the next time the server starts.";

    private ServerPropertiesFile() {}

    /** The file the server reads: the one in KnightLauncher/server, else a server.properties in the game folder. */
    public static File resolve(File gameDir) {
        File preferred = new File(gameDir, "KnightLauncher/server/server.properties");
        File fallback = new File(gameDir, "server.properties");
        return !preferred.isFile() && fallback.isFile() ? fallback : preferred;
    }

    /** All keys in the file (known or not); empty if there is no file. */
    public static Properties readAll(File file) throws IOException {
        Properties properties = new Properties();
        if (file.isFile()) {
            try (InputStream in = Files.newInputStream(file.toPath())) {
                properties.load(in);
            }
        }
        return properties;
    }

    /** The known settings in the file, as strings. */
    public static Map<String, String> read(File file) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        Properties properties = readAll(file);
        for (ServerSettings.Def def : ServerSettings.all()) {
            String value = properties.getProperty(def.key);
            if (value != null) values.put(def.key, value.trim());
        }
        return values;
    }

    /**
     * Replaces the known settings in the file with {@code values}; defaults are left out, unknown keys stay.
     * The values must have passed {@link ServerSettings#validate}.
     */
    public static void write(File file, Map<String, String> values) throws IOException {
        Properties existing = readAll(file);
        Path directory = file.getAbsoluteFile().getParentFile().toPath();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "server.properties.", ".tmp");
        try {
            try (BufferedWriter out = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                for (String line : HEADER.split("\n")) out.write("# " + line + "\n");
                for (ServerSettings.Def def : ServerSettings.all()) {
                    String value = ServerSettings.raw(values, def.key);
                    if (!sameAsDefault(def, value)) out.write(def.key + "=" + value + "\n");
                }
                for (String key : existing.stringPropertyNames()) {
                    if (!ServerSettings.isKnown(key)) out.write(escapeKey(key) + "=" + existing.getProperty(key) + "\n");
                }
            }
            Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean sameAsDefault(ServerSettings.Def def, String value) {
        switch (def.type) {
            case BOOLEAN:
                return Boolean.parseBoolean(value) == Boolean.parseBoolean(def.defaultValue);
            case INT:
                return Long.parseLong(value) == Long.parseLong(def.defaultValue);
            default:
                return Float.parseFloat(value) == Float.parseFloat(def.defaultValue);
        }
    }

    private static String escapeKey(String key) {
        return key.replace(" ", "\\ ").replace("=", "\\=").replace(":", "\\:");
    }
}
