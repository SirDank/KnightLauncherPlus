package com.lucasluqui.launcher.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerYamlRoundTripTest {
    @TempDir File dir;

    private File yml(String text) throws IOException {
        File file = new File(dir, "config/server.yml");
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), text, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    public void savedSettingsComeBackAndNonAsciiTextSurvives() throws IOException {
        File file = yml("max-clients: 16\n");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("server.maxClients", "24");
        values.put("server.welcomeMessage", "Willkommen, Ritter! ⚔ ü");
        values.put("server.allowLan", "true");
        values.put("combat.dodgeCooldownMs", "5000");
        ServerPropertiesFile.write(file, values);

        Map<String, String> read = ServerPropertiesFile.read(file);
        assertEquals("24", read.get("server.maxClients"));
        assertEquals("Willkommen, Ritter! ⚔ ü", read.get("server.welcomeMessage"));
        assertEquals("true", read.get("server.allowLan"));
        assertEquals("5000", read.get("combat.dodgeCooldownMs"));
        assertFalse(new File(file.getParentFile(), "server.yml.tmp").exists(), "no temporary file is left behind");
    }

    @Test
    public void settingsTheLauncherDoesNotKnowAreKept() throws IOException {
        File file = yml("some-engine-option: 7\nstartup-map: some_other_map\n");
        ServerPropertiesFile.write(file, Map.of("server.maxClients", "8"));
        String text = Files.readString(file.toPath());
        assertTrue(text.contains("some-engine-option: 7"));
        assertTrue(text.contains("some_other_map"));
    }

    @Test
    public void everyStartSetsPortAndAddressAndKeepsTheNewsListenerOffSteamsPort() throws IOException {
        File file = yml("server-port: 1\nwebui/content_url-port: 8080\n");
        ServerPropertiesFile.applyRuntime(file, 47624, false);
        String text = Files.readString(file.toPath());
        assertTrue(text.contains("server-port: 47624"));
        assertTrue(text.contains("127.0.0.1"));
        assertTrue(text.contains("webui/content_url-port: 0"));
        assertTrue(text.contains("webui-enabled: false"));

        ServerPropertiesFile.applyRuntime(file, 47624, true);
        assertEquals("true", ServerPropertiesFile.read(file).get("server.allowLan"));
    }

    @Test
    public void aMalformedFileIsReportedAndLeftAlone() throws IOException {
        File file = yml("max-clients: [unclosed\n");
        assertThrows(IOException.class, () -> ServerPropertiesFile.write(file, Map.of("server.maxClients", "8")));
        assertEquals("max-clients: [unclosed\n", Files.readString(file.toPath()));
    }

    @Test
    public void missingFilesReadAsEmpty() throws IOException {
        assertTrue(ServerPropertiesFile.read(new File(dir, "nope/server.yml")).isEmpty());
    }
}
