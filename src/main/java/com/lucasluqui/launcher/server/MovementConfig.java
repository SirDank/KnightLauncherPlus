package com.lucasluqui.launcher.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Server-side movement simulation and tick configuration.
 */
public final class MovementConfig {
    public static final Path DEFAULT_PATH = Path.of("KnightLauncher", "server", "server.properties");
    public static final Path FALLBACK_PATH = Path.of("server.properties");

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
    public final float minX;
    public final float maxX;
    public final float minY;
    public final float maxY;

    public MovementConfig(Properties values) {
        this.enabled = booleanValue(values, "movement.enabled", true);
        this.sendAuthoritativeUpdates = booleanValue(
            values, "movement.sendAuthoritativeUpdates", true);
        this.clampToMapBounds = booleanValue(values, "movement.clampToMapBounds", true);
        this.baseSpeed = floatValue(values, "movement.baseSpeed", 5.0f, 0.1f, 50.0f);
        this.speedMultiplier = floatValue(
            values, "movement.speedMultiplier", 1.0f, 0.05f, 10.0f);
        this.maxFrameDeltaMs = intValue(
            values, "movement.maxFrameDeltaMs", 250, 1, 2_000);
        this.maxFramesPerPacket = intValue(
            values, "movement.maxFramesPerPacket", 64, 1, 512);
        this.deltaIntervalMs = intValue(
            values, "movement.deltaIntervalMs", 100, 10, 1_000);
        this.trainingDeltaIntervalMs = intValue(
            values, "movement.trainingDeltaIntervalMs", 200,
            this.deltaIntervalMs, 2_000);
        if (this.trainingDeltaIntervalMs % this.deltaIntervalMs != 0) {
            throw new IllegalArgumentException(
                "movement.trainingDeltaIntervalMs must be a multiple of movement.deltaIntervalMs");
        }
        this.actorStateIntervalMs = intValue(
            values, "movement.actorStateIntervalMs", 200,
            this.deltaIntervalMs, 2_000);
        if (this.actorStateIntervalMs % this.deltaIntervalMs != 0) {
            throw new IllegalArgumentException(
                "movement.actorStateIntervalMs must be a multiple of movement.deltaIntervalMs");
        }
        this.logEveryPackets = intValue(
            values, "movement.logEveryPackets", 20, 0, 100_000);
        this.minX = floatValue(values, "movement.bounds.minX", -81.0f, -10_000.0f, 10_000.0f);
        this.maxX = floatValue(values, "movement.bounds.maxX", 86.0f, -10_000.0f, 10_000.0f);
        this.minY = floatValue(values, "movement.bounds.minY", -82.0f, -10_000.0f, 10_000.0f);
        this.maxY = floatValue(values, "movement.bounds.maxY", 70.0f, -10_000.0f, 10_000.0f);
        if (this.minX >= this.maxX || this.minY >= this.maxY) {
            throw new IllegalArgumentException("movement.bounds min values must be lower than max values");
        }
    }

    public static MovementConfig load() {
        Properties values = new Properties();
        Path path = Files.isRegularFile(DEFAULT_PATH) ? DEFAULT_PATH : (Files.isRegularFile(FALLBACK_PATH) ? FALLBACK_PATH : null);
        if (path != null) {
            try (InputStream input = Files.newInputStream(path)) {
                values.load(input);
            } catch (IOException e) {
                System.err.println("[MovementConfig][WARN] Failed to read " + path + ": " + e.getMessage());
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
            + " logEveryPackets=" + this.logEveryPackets;
    }

    private static boolean booleanValue(Properties values, String key, boolean fallback) {
        String value = values.getProperty(key);
        if (value == null) {
            return fallback;
        }
        if ("true".equalsIgnoreCase(value.trim()) || "false".equalsIgnoreCase(value.trim())) {
            return Boolean.parseBoolean(value.trim());
        }
        throw new IllegalArgumentException(key + " must be true or false, got: " + value);
    }

    private static int intValue(Properties values, String key, int fallback, int min, int max) {
        String value = values.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < min || parsed > max) {
                throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer, got: " + value, e);
        }
    }

    private static float floatValue(
            Properties values, String key, float fallback, float min, float max) {
        String value = values.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            float parsed = Float.parseFloat(value.trim());
            if (!Float.isFinite(parsed) || parsed < min || parsed > max) {
                throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be numeric, got: " + value, e);
        }
    }
}

