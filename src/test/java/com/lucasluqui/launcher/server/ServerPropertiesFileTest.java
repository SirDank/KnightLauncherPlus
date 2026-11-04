package com.lucasluqui.launcher.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerPropertiesFileTest {

    @Test
    public void resolvePrefersTheServerFolderThenTheGameFolder(@TempDir Path game) throws IOException {
        File preferred = new File(game.toFile(), "KnightLauncher/server/server.properties");
        File fallback = new File(game.toFile(), "server.properties");
        assertEquals(preferred, ServerPropertiesFile.resolve(game.toFile())); // neither exists: where a new one goes
        Files.writeString(fallback.toPath(), "server.maxSessions=5\n");
        assertEquals(fallback, ServerPropertiesFile.resolve(game.toFile()));
        preferred.getParentFile().mkdirs();
        Files.writeString(preferred.toPath(), "server.maxSessions=6\n");
        assertEquals(preferred, ServerPropertiesFile.resolve(game.toFile()));
    }

    @Test
    public void writesOnlyNonDefaultsAndReadsThemBack(@TempDir Path game) throws IOException {
        File file = ServerPropertiesFile.resolve(game.toFile());
        Map<String, String> values = new HashMap<>();
        values.put("server.maxSessions", "8");
        values.put("movement.baseSpeed", "5.0");        // the default, so it must not be written
        values.put("movement.enabled", "false");
        ServerPropertiesFile.write(file, values);

        Map<String, String> read = ServerPropertiesFile.read(file);
        assertEquals(Map.of("server.maxSessions", "8", "movement.enabled", "false"), read);
        assertFalse(Files.readString(file.toPath()).contains("baseSpeed"));
        assertTrue(ServerSettings.validate(read).isEmpty());
    }

    @Test
    public void keepsKeysItDoesNotKnow(@TempDir Path game) throws IOException {
        File file = ServerPropertiesFile.resolve(game.toFile());
        file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), "future.setting=on\nserver.maxSessions=9\n");
        ServerPropertiesFile.write(file, Map.of("server.maxSessions", "10"));

        Properties all = ServerPropertiesFile.readAll(file);
        assertEquals("on", all.getProperty("future.setting"));
        assertEquals("10", all.getProperty("server.maxSessions"));
    }

    @Test
    public void resetToDefaultsRemovesTheKnownKeys(@TempDir Path game) throws IOException {
        File file = ServerPropertiesFile.resolve(game.toFile());
        ServerPropertiesFile.write(file, Map.of("server.maxSessions", "8"));
        ServerPropertiesFile.write(file, new HashMap<>());
        assertTrue(ServerPropertiesFile.read(file).isEmpty());
    }

    @Test
    public void aMissingFileReadsAsEmptyAndLeavesNoTemporaryFiles(@TempDir Path game) throws IOException {
        File file = ServerPropertiesFile.resolve(game.toFile());
        assertTrue(ServerPropertiesFile.read(file).isEmpty());
        ServerPropertiesFile.write(file, Map.of("combat.burstIntervalMs", "250"));
        try (var files = Files.list(file.getParentFile().toPath())) {
            assertEquals(1, files.count());
        }
    }

    @Test
    public void whatTheWindowWritesIsWhatTheServerLoads(@TempDir Path game) throws IOException {
        File file = ServerPropertiesFile.resolve(game.toFile());
        ServerPropertiesFile.write(file, Map.of("movement.deltaIntervalMs", "40",
            "movement.trainingDeltaIntervalMs", "80", "movement.actorStateIntervalMs", "40", "server.maxSessions", "4"));
        MovementConfig config = new MovementConfig(ServerPropertiesFile.readAll(file));
        assertEquals(40, config.deltaIntervalMs);
        assertEquals(80, config.trainingDeltaIntervalMs);
        assertEquals(4, config.maxSessions);
        assertEquals(10, config.authTimeoutSeconds);
    }
}
