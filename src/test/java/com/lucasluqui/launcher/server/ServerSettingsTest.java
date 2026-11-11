package com.lucasluqui.launcher.server;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerSettingsTest {

    private static List<ServerSettings.Problem> check(String key, String value) {
        return ServerSettings.validate(Map.of(key, value));
    }

    @Test
    public void keysAreUniqueAndDefaultsAreValid() {
        Set<String> seen = new HashSet<>();
        Map<String, String> defaults = new HashMap<>();
        for (ServerSettings.Def def : ServerSettings.all()) {
            assertTrue(seen.add(def.key), "duplicate key " + def.key);
            defaults.put(def.key, def.defaultValue);
        }
        assertEquals(List.of(), ServerSettings.validate(defaults));
        assertTrue(ServerSettings.validate(new HashMap<>()).isEmpty());
    }

    @Test
    public void rejectsBadTypesAndRangesWithTheReasonAndTheLimits() {
        assertEquals(new ServerSettings.Problem("server.maxClients", "range", 1, 1024), check("server.maxClients", "0").get(0));
        assertTrue(check("server.maxClients", "1024").isEmpty(), "Tortodrone allows up to 1024 connections");
        assertEquals("range", check("server.maxClients", "1025").get(0).code());
        assertEquals("number", check("server.maxClients", "many").get(0).code());
        assertEquals("bool", check("server.allowLan", "maybe").get(0).code());
        assertEquals("range", check("combat.dodgeCooldownMs", "-1").get(0).code());
        assertTrue(check("combat.dodgeCooldownMs", "600000").isEmpty(), "Tortodrone allows up to 600000 ms");
        assertEquals("range", check("server.logMaxMib", "0").get(0).code());
        assertEquals("range", check("server.logRetention", "101").get(0).code());
    }

    @Test
    public void theWelcomeMessageFollowsTortodronesRules() {
        assertTrue(check("server.welcomeMessage", "Hello, Haven! \u00e9\ud83d\ude00").isEmpty());
        assertTrue(check("server.welcomeMessage", "x".repeat(256)).isEmpty());
        assertEquals("length", check("server.welcomeMessage", "x".repeat(257)).get(0).code());
        // 256 emoji are 256 code points but 512 chars: counted in code points, as the server does.
        assertTrue(check("server.welcomeMessage", "\ud83d\ude00".repeat(256)).isEmpty());
        assertEquals("control", check("server.welcomeMessage", "line one\nline two").get(0).code());
        assertEquals("control", check("server.welcomeMessage", "bell\u0007").get(0).code());
        assertEquals("control", check("server.welcomeMessage", "lone \ud83d surrogate").get(0).code());
    }

    @Test
    public void theStartupMapAndLogModeMustBeAcceptedByTheServer() {
        assertTrue(check("server.startupMap", "rescue_camp").isEmpty());
        assertEquals("pattern", check("server.startupMap", "Haven").get(0).code());
        assertEquals("pattern", check("server.startupMap", "../haven").get(0).code());
        assertEquals("pattern", check("server.startupMap", "9lives").get(0).code());
        assertTrue(check("server.logMode", "debug").isEmpty());
        assertEquals("pattern", check("server.logMode", "verbose").get(0).code());
        assertEquals("pattern", check("server.logMode", "Normal").get(0).code());
    }

    @Test
    public void everySettingHasLabelKeys() {
        for (ServerSettings.Def def : ServerSettings.all()) {
            assertEquals("ss." + def.key, def.labelKey());
            assertEquals("ss." + def.key + ".desc", def.descriptionKey());
        }
    }
}
