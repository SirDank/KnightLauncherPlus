package com.lucasluqui.launcher.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerPropertiesFileTest {

    @Test
    public void resolveIsAlwaysTheServerFoldersFile(@TempDir Path game) throws IOException {
        File preferred = new File(game.toFile(), "KnightLauncher/server/config/server.yml");
        // A config.yml in the game folder is not what the server reads, whatever else exists.
        File stray = new File(game.toFile(), "config/server.yml");
        stray.getParentFile().mkdirs();
        Files.writeString(stray.toPath(), "server-port: 47624\n");
        assertEquals(preferred, ServerPropertiesFile.resolve(game.toFile()));
    }

    @Test
    public void onlyTownsAreOfferedAsTheStartupMap(@TempDir Path server) throws IOException {
        String[][] maps = {{"haven", "town"}, {"arcade", "town"}, {"crash_site", "level"}, {"advanced_training", "training"}};
        for (String[] map : maps) {
            Path dir = server.resolve("content/maps/" + map[0]);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("map.yml"), "name: " + map[0] + "\ntype: " + map[1] + "\nwidth: 4\n");
        }
        Files.createDirectories(server.resolve("content/maps/broken")); // no map.yml
        Files.createDirectories(server.resolve("content/maps/Not A Name"));
        Files.writeString(server.resolve("content/maps/Not A Name/map.yml"), "type: town\n");
        assertEquals(java.util.List.of("arcade", "haven"), ServerPropertiesFile.townMaps(server.toFile()));
        assertTrue(ServerPropertiesFile.townMaps(server.resolve("nothing").toFile()).isEmpty());
    }

    @Test
    public void logRotationSettingsRoundTrip(@TempDir Path game) throws IOException {
        File file = ServerPropertiesFile.resolve(game.toFile());
        Map<String, String> values = new HashMap<>();
        values.put("server.logMaxMib", "25");
        values.put("server.logRetention", "7");
        ServerPropertiesFile.write(file, values);
        Map<String, String> read = ServerPropertiesFile.read(file);
        assertEquals("25", read.get("server.logMaxMib"));
        assertEquals("7", read.get("server.logRetention"));
    }

    @Test
    public void writesAndReadsConfiguration(@TempDir Path game) throws IOException {
        File file = ServerPropertiesFile.resolve(game.toFile());
        Map<String, String> values = new HashMap<>();
        values.put("server.maxClients", "32");
        values.put("server.welcomeMessage", "Hello Haven!");
        values.put("combat.dodgeCooldownMs", "5000");
        ServerPropertiesFile.write(file, values);

        Map<String, String> read = ServerPropertiesFile.read(file);
        assertEquals("32", read.get("server.maxClients"));
        assertEquals("Hello Haven!", read.get("server.welcomeMessage"));
        assertEquals("5000", read.get("combat.dodgeCooldownMs"));
        assertTrue(ServerSettings.validate(read).isEmpty());
    }

    @Test
    public void aMissingFileReadsAsEmpty(@TempDir Path game) throws IOException {
        File file = ServerPropertiesFile.resolve(game.toFile());
        assertTrue(ServerPropertiesFile.read(file).isEmpty());
    }
}
