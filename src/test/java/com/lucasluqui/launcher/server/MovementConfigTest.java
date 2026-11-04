package com.lucasluqui.launcher.server;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class MovementConfigTest {

    private static Properties props(String... keyValues) {
        Properties properties = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) properties.setProperty(keyValues[i], keyValues[i + 1]);
        return properties;
    }

    @Test
    public void defaultsMatchWhatTheClientExpects() {
        MovementConfig config = new MovementConfig(new Properties());
        // Updates every 20 ms in both scenes (the client sends input every 33 ms); 100/200 ms made movement stutter.
        assertEquals(20, config.deltaIntervalMs);
        assertEquals(20, config.trainingDeltaIntervalMs);
        assertEquals(20, config.actorStateIntervalMs);
        assertEquals(100, config.combatBurstIntervalMs);
    }

    @Test
    public void valuesCanBeOverridden() {
        MovementConfig config = new MovementConfig(props(
            "movement.deltaIntervalMs", "50", "movement.trainingDeltaIntervalMs", "100",
            "movement.actorStateIntervalMs", "100", "combat.burstIntervalMs", "250"));
        assertEquals(50, config.deltaIntervalMs);
        assertEquals(100, config.trainingDeltaIntervalMs);
        assertEquals(250, config.combatBurstIntervalMs);
    }

    @Test
    public void trainingAndActorIntervalsMustBeMultiplesOfTheBaseTick() {
        assertThrows(IllegalArgumentException.class, () -> new MovementConfig(props(
            "movement.deltaIntervalMs", "30", "movement.trainingDeltaIntervalMs", "50")));
        assertThrows(IllegalArgumentException.class, () -> new MovementConfig(props(
            "movement.deltaIntervalMs", "30", "movement.actorStateIntervalMs", "50")));
    }

    @Test
    public void burstIntervalIsRangeChecked() {
        assertThrows(IllegalArgumentException.class, () -> new MovementConfig(props("combat.burstIntervalMs", "19")));
        assertThrows(IllegalArgumentException.class, () -> new MovementConfig(props("combat.burstIntervalMs", "1001")));
        assertThrows(IllegalArgumentException.class, () -> new MovementConfig(props("combat.burstIntervalMs", "fast")));
    }

    @Test
    public void loadWorksWithoutAnyFile() {
        // The file is optional; a missing one must give the defaults, not an error.
        assertEquals(20, MovementConfig.load().deltaIntervalMs);
    }
}
