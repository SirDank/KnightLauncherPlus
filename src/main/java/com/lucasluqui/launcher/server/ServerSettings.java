package com.lucasluqui.launcher.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Every setting of the built-in server in one place: key, type, default and allowed range. The server, the
 * {@code server.properties} file and the launcher's server settings window all read this list, so they cannot
 * disagree. No game or launcher classes in here: the launcher loads it too.
 */
public final class ServerSettings {

    public enum Group { NETWORK, UPDATES, MOVEMENT, COMBAT, ADVANCED }

    public enum Type { BOOLEAN, INT, FLOAT }

    /** One setting. The window shows it with the locale keys {@code ss.<key>} and {@code ss.<key>.desc}. */
    public static final class Def {
        public final String key;
        public final Group group;
        public final Type type;
        public final String defaultValue;
        public final double min;
        public final double max;

        private Def(String key, Group group, Type type, String defaultValue, double min, double max) {
            this.key = key;
            this.group = group;
            this.type = type;
            this.defaultValue = defaultValue;
            this.min = min;
            this.max = max;
        }

        public String labelKey() {
            return "ss." + this.key;
        }

        public String descriptionKey() {
            return "ss." + this.key + ".desc";
        }
    }

    /** The tick interval the client expects, see {@link MovementConfig#DEFAULT_TICK_MS}. */
    static final int TICK = 20;

    private static final List<Def> ALL;
    private static final Map<String, Def> BY_KEY = new LinkedHashMap<>();

    static {
        List<Def> list = new ArrayList<>();
        list.add(bool("server.allowLan", Group.NETWORK, false)); // stored by the launcher, not in server.properties
        list.add(integer("server.maxSessions", Group.NETWORK, 32, 1, 256));
        list.add(integer("server.authTimeoutSeconds", Group.NETWORK, 10, 1, 300));
        list.add(integer("server.maxPartySize", Group.NETWORK, 8, 1, 8));

        list.add(integer("movement.deltaIntervalMs", Group.UPDATES, TICK, 10, 1_000));
        list.add(integer("movement.trainingDeltaIntervalMs", Group.UPDATES, TICK, 10, 2_000));
        list.add(integer("movement.actorStateIntervalMs", Group.UPDATES, TICK, 10, 2_000));

        list.add(bool("movement.enabled", Group.MOVEMENT, true));
        list.add(bool("movement.sendAuthoritativeUpdates", Group.MOVEMENT, true));
        list.add(bool("movement.clampToMapBounds", Group.MOVEMENT, true));
        list.add(real("movement.baseSpeed", Group.MOVEMENT, 5.0f, 0.1f, 50.0f));
        list.add(real("movement.speedMultiplier", Group.MOVEMENT, 1.0f, 0.05f, 10.0f));
        list.add(integer("movement.maxFrameDeltaMs", Group.MOVEMENT, 250, 1, 2_000));
        list.add(integer("movement.maxFramesPerPacket", Group.MOVEMENT, 64, 1, 512));
        list.add(real("movement.bounds.minX", Group.MOVEMENT, -81.0f, -10_000f, 10_000f));
        list.add(real("movement.bounds.maxX", Group.MOVEMENT, 86.0f, -10_000f, 10_000f));
        list.add(real("movement.bounds.minY", Group.MOVEMENT, -82.0f, -10_000f, 10_000f));
        list.add(real("movement.bounds.maxY", Group.MOVEMENT, 70.0f, -10_000f, 10_000f));

        list.add(integer("combat.burstIntervalMs", Group.COMBAT, 100, 20, 1_000));

        list.add(integer("movement.logEveryPackets", Group.ADVANCED, 20, 0, 100_000));

        ALL = Collections.unmodifiableList(list);
        for (Def def : ALL) BY_KEY.put(def.key, def);
    }

    private ServerSettings() {}

    private static Def bool(String key, Group group, boolean def) {
        return new Def(key, group, Type.BOOLEAN, String.valueOf(def), 0, 1);
    }

    private static Def integer(String key, Group group, int def, int min, int max) {
        return new Def(key, group, Type.INT, String.valueOf(def), min, max);
    }

    private static Def real(String key, Group group, float def, float min, float max) {
        return new Def(key, group, Type.FLOAT, String.valueOf(def), min, max);
    }

    public static List<Def> all() {
        return ALL;
    }

    public static Def get(String key) {
        Def def = BY_KEY.get(key);
        if (def == null) throw new IllegalArgumentException("Unknown server setting: " + key);
        return def;
    }

    public static boolean isKnown(String key) {
        return BY_KEY.containsKey(key);
    }

    /** The value in {@code values}, or the default; trimmed. */
    public static String raw(Map<String, String> values, String key) {
        String value = values.get(key);
        return value == null || value.trim().isEmpty() ? get(key).defaultValue : value.trim();
    }

    public static Map<String, String> toMap(Properties properties) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) map.put(name, properties.getProperty(name));
        return map;
    }

    /**
     * Checks every known value in {@code values} (missing ones count as defaults) against its type and range and the
     * rules between settings.
     *
     * @return The problems, in plain English; empty when everything is fine.
     */
    public static List<String> validate(Map<String, String> values) {
        List<String> problems = new ArrayList<>();
        for (Def def : ALL) {
            String value = raw(values, def.key);
            switch (def.type) {
                case BOOLEAN:
                    if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                        problems.add(def.key + " must be true or false, got: " + value);
                    }
                    break;
                case INT:
                    try {
                        long parsed = Long.parseLong(value);
                        if (parsed < def.min || parsed > def.max) {
                            problems.add(def.key + " must be between " + (long) def.min + " and " + (long) def.max);
                        }
                    } catch (NumberFormatException e) {
                        problems.add(def.key + " must be an integer, got: " + value);
                    }
                    break;
                case FLOAT:
                    try {
                        float parsed = Float.parseFloat(value);
                        if (!Float.isFinite(parsed) || parsed < def.min || parsed > def.max) {
                            problems.add(def.key + " must be between " + (float) def.min + " and " + (float) def.max);
                        }
                    } catch (NumberFormatException e) {
                        problems.add(def.key + " must be numeric, got: " + value);
                    }
                    break;
            }
        }
        if (!problems.isEmpty()) return problems; // the rules below need numbers that parse

        int delta = getInt(values, "movement.deltaIntervalMs");
        int training = getInt(values, "movement.trainingDeltaIntervalMs");
        int actorState = getInt(values, "movement.actorStateIntervalMs");
        if (training < delta || training % delta != 0) {
            problems.add("movement.trainingDeltaIntervalMs must be a multiple of movement.deltaIntervalMs (" + delta + ")");
        }
        if (actorState < delta || actorState % delta != 0) {
            problems.add("movement.actorStateIntervalMs must be a multiple of movement.deltaIntervalMs (" + delta + ")");
        }
        if (getFloat(values, "movement.bounds.minX") >= getFloat(values, "movement.bounds.maxX")
                || getFloat(values, "movement.bounds.minY") >= getFloat(values, "movement.bounds.maxY")) {
            problems.add("movement.bounds min values must be lower than max values");
        }
        return problems;
    }

    // Typed reads. Call validate() first; these assume valid numbers.

    public static boolean getBoolean(Map<String, String> values, String key) {
        return Boolean.parseBoolean(raw(values, key));
    }

    public static int getInt(Map<String, String> values, String key) {
        return Integer.parseInt(raw(values, key));
    }

    public static float getFloat(Map<String, String> values, String key) {
        return Float.parseFloat(raw(values, key));
    }
}
