package com.lucasluqui.launcher.server;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerSettingsTest {

    @Test
    public void keysAreUniqueAndDefaultsAreValid() {
        Set<String> seen = new HashSet<>();
        for (ServerSettings.Def def : ServerSettings.all()) {
            assertTrue(seen.add(def.key), "duplicate key " + def.key);
        }
        assertTrue(ServerSettings.validate(new HashMap<>()).isEmpty());
    }

    @Test
    public void everyDefaultIsInsideItsOwnRange() {
        for (ServerSettings.Def def : ServerSettings.all()) {
            if (def.type == ServerSettings.Type.BOOLEAN || def.type == ServerSettings.Type.STRING) continue;
            double value = Double.parseDouble(def.defaultValue);
            assertTrue(value >= def.min && value <= def.max, def.key + " default " + value + " outside its range");
        }
    }

    @Test
    public void rejectsBadTypesAndRanges() {
        assertFalse(ServerSettings.validate(Map.of("server.maxClients", "0")).isEmpty());
        assertFalse(ServerSettings.validate(Map.of("server.maxClients", "many")).isEmpty());
        assertFalse(ServerSettings.validate(Map.of("server.allowLan", "maybe")).isEmpty());
        assertFalse(ServerSettings.validate(Map.of("combat.dodgeCooldownMs", "-1")).isEmpty());
    }

    @Test
    public void blankValuesFallBackToTheDefault() {
        assertEquals("16", ServerSettings.raw(Map.of("server.maxClients", "  "), "server.maxClients"));
    }

    @Test
    public void unknownKeysAreIgnoredOrFalse() {
        assertFalse(ServerSettings.isKnown("nope"));
    }

    @Test
    public void everySettingHasLabelKeys() {
        for (ServerSettings.Def def : ServerSettings.all()) {
            assertEquals("ss." + def.key, def.labelKey());
            assertEquals("ss." + def.key + ".desc", def.descriptionKey());
        }
    }
}
