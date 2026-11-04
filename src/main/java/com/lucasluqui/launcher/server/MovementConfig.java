package com.lucasluqui.launcher.server;

import java.io.IOException;
import java.util.Properties;

/**
 * Server-side movement simulation and tick configuration.
 */
public final class MovementConfig {
    /**
     * Scene update interval. The client sends its input every 33 ms and smooths over about twice the update
     * interval, so slower updates (the old 100/200 ms) show up as stutter.
     */
    public static final int DEFAULT_TICK_MS = ServerSettings.TICK;

    public final boolean enabled;
    public final boolean sendAuthoritativeUpdates;
    public final boolean clampToMapBounds;
    public final float baseSpeed;
    public final float speedMultiplier;
    public final int maxFrameDeltaMs;
    public final int maxFramesPerPacket;
    public final int deltaIntervalMs;
    public final int trainingDeltaIntervalMs;
    public final int actorStateIntervalMs;
    public final int logEveryPackets;
    /** Time between the shots of a charged burst attack. */
    public final int combatBurstIntervalMs;
    /** Connections accepted at the same time, signed in or not. */
    public final int maxSessions;
    /** A connection that has not signed in by then is dropped. */
    public final int authTimeoutSeconds;
    /** Knights that fit in one party. */
    public final int maxPartySize;
    public final float minX;
    public final float maxX;
    public final float minY;
    public final float maxY;

    public MovementConfig(Properties properties) {
        java.util.Map<String, String> values = ServerSettings.toMap(properties);
        java.util.List<String> problems = ServerSettings.validate(values);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(problems.get(0));
        }
        this.enabled = ServerSettings.getBoolean(values, "movement.enabled");
        this.sendAuthoritativeUpdates = ServerSettings.getBoolean(values, "movement.sendAuthoritativeUpdates");
        this.clampToMapBounds = ServerSettings.getBoolean(values, "movement.clampToMapBounds");
        this.baseSpeed = ServerSettings.getFloat(values, "movement.baseSpeed");
        this.speedMultiplier = ServerSettings.getFloat(values, "movement.speedMultiplier");
        this.maxFrameDeltaMs = ServerSettings.getInt(values, "movement.maxFrameDeltaMs");
        this.maxFramesPerPacket = ServerSettings.getInt(values, "movement.maxFramesPerPacket");
        this.deltaIntervalMs = ServerSettings.getInt(values, "movement.deltaIntervalMs");
        this.trainingDeltaIntervalMs = ServerSettings.getInt(values, "movement.trainingDeltaIntervalMs");
        this.actorStateIntervalMs = ServerSettings.getInt(values, "movement.actorStateIntervalMs");
        this.logEveryPackets = ServerSettings.getInt(values, "movement.logEveryPackets");
        this.combatBurstIntervalMs = ServerSettings.getInt(values, "combat.burstIntervalMs");
        this.maxSessions = ServerSettings.getInt(values, "server.maxSessions");
        this.authTimeoutSeconds = ServerSettings.getInt(values, "server.authTimeoutSeconds");
        this.maxPartySize = ServerSettings.getInt(values, "server.maxPartySize");
        this.minX = ServerSettings.getFloat(values, "movement.bounds.minX");
        this.maxX = ServerSettings.getFloat(values, "movement.bounds.maxX");
        this.minY = ServerSettings.getFloat(values, "movement.bounds.minY");
        this.maxY = ServerSettings.getFloat(values, "movement.bounds.maxY");
    }

    public static MovementConfig load() {
        Properties values = new Properties();
        java.io.File file = ServerPropertiesFile.resolve(new java.io.File(ServerNet.GAME_DIR));
        if (file.isFile()) {
            try {
                values = ServerPropertiesFile.readAll(file);
            } catch (IOException e) {
                System.err.println("[MovementConfig][WARN] Failed to read " + file + ": " + e.getMessage());
            }
        }
        return new MovementConfig(values);
    }

    public float effectiveSpeed() {
        return this.baseSpeed * this.speedMultiplier;
    }

    public String describe() {
        return "enabled=" + this.enabled
            + " authoritative=" + this.sendAuthoritativeUpdates
            + " speed=" + effectiveSpeed()
            + " (base=" + this.baseSpeed + " x" + this.speedMultiplier + ")"
            + " maxFrameDeltaMs=" + this.maxFrameDeltaMs
            + " maxFramesPerPacket=" + this.maxFramesPerPacket
            + " deltaIntervalMs=" + this.deltaIntervalMs
            + " trainingDeltaIntervalMs=" + this.trainingDeltaIntervalMs
            + " actorStateIntervalMs=" + this.actorStateIntervalMs
            + " clamp=" + this.clampToMapBounds
            + " bounds=[" + this.minX + "," + this.maxX + "]x["
            + this.minY + "," + this.maxY + "]"
            + " logEveryPackets=" + this.logEveryPackets
            + " burstIntervalMs=" + this.combatBurstIntervalMs;
    }
}
