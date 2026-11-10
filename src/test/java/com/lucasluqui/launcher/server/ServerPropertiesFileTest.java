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
    public void resolvePrefersTheServerFolderThenTheGameFolder(@TempDir Path game) throws IOException {
        File preferred = new File(game.toFile(), "KnightLauncher/server/config/server.yml");
        File fallback = new File(game.toFile(), "config/server.yml");
        assertEquals(preferred, ServerPropertiesFile.resolve(game.toFile()));
        fallback.getParentFile().mkdirs();
        Files.writeString(fallback.toPath(), "server-port: 47624\n");
        assertEquals(fallback, ServerPropertiesFile.resolve(game.toFile()));
        preferred.getParentFile().mkdirs();
        Files.writeString(preferred.toPath(), "server-port: 47625\n");
        assertEquals(preferred, ServerPropertiesFile.resolve(game.toFile()));
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
