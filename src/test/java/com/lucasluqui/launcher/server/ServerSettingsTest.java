package com.lucasluqui.launcher.server;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
            if (def.type == ServerSettings.Type.BOOLEAN) continue;
            double value = Double.parseDouble(def.defaultValue);
            assertTrue(value >= def.min && value <= def.max, def.key + " default " + value + " outside its range");
        }
    }

    @Test
    public void rejectsBadTypesAndRanges() {
        assertFalse(ServerSettings.validate(Map.of("server.maxSessions", "0")).isEmpty());
        assertFalse(ServerSettings.validate(Map.of("server.maxSessions", "many")).isEmpty());
        assertFalse(ServerSettings.validate(Map.of("movement.enabled", "maybe")).isEmpty());
        assertFalse(ServerSettings.validate(Map.of("movement.baseSpeed", "NaN")).isEmpty());
        assertFalse(ServerSettings.validate(Map.of("combat.burstIntervalMs", "19")).isEmpty());
    }

    @Test
    public void enforcesTheRulesBetweenSettings() {
        Map<String, String> values = new HashMap<>();
        values.put("movement.deltaIntervalMs", "30");
        values.put("movement.trainingDeltaIntervalMs", "50");
        assertTrue(ServerSettings.validate(values).get(0).contains("multiple"));

        values.clear();
        values.put("movement.bounds.minX", "100");
        assertTrue(ServerSettings.validate(values).get(0).contains("bounds"));
    }

    @Test
    public void blankValuesFallBackToTheDefault() {
        assertEquals("32", ServerSettings.raw(Map.of("server.maxSessions", "  "), "server.maxSessions"));
        assertEquals(20, ServerSettings.getInt(new HashMap<>(), "movement.deltaIntervalMs"));
    }

    @Test
    public void unknownKeysAreRejectedByLookup() {
        assertThrows(IllegalArgumentException.class, () -> ServerSettings.get("nope"));
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
