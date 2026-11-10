package com.lucasluqui.launcher.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings definitions for the Tortodrone server.
 */
public final class ServerSettings {

    public enum Group { NETWORK, COMBAT, ADVANCED }

    public enum Type { BOOLEAN, INT, FLOAT, STRING }

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

    private static final List<Def> ALL;
    private static final Map<String, Def> BY_KEY = new LinkedHashMap<>();

    static {
        List<Def> list = new ArrayList<>();
        list.add(bool("server.allowLan", Group.NETWORK, false));
        list.add(integer("server.maxClients", Group.NETWORK, 16, 1, 256));

        list.add(integer("combat.dodgeCooldownMs", Group.COMBAT, 8000, 0, 60_000));

        list.add(text("server.welcomeMessage", Group.ADVANCED, "Welcome to Tortodrone!"));
        list.add(text("server.startupMap", Group.ADVANCED, "haven"));
        list.add(text("server.logMode", Group.ADVANCED, "normal"));

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

    private static Def text(String key, Group group, String def) {
        return new Def(key, group, Type.STRING, def, 0, 0);
    }

    public static List<Def> all() {
        return ALL;
    }

    public static Def find(String key) {
        return BY_KEY.get(key);
    }

    public static boolean isKnown(String key) {
        return BY_KEY.containsKey(key);
    }

    public static String raw(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value != null && !value.trim().isEmpty()) return value.trim();
        Def def = BY_KEY.get(key);
        return def != null ? def.defaultValue : null;
    }

    public static List<String> validate(Map<String, String> values) {
        List<String> errors = new ArrayList<>();
        for (Def def : ALL) {
            String value = values.get(def.key);
            if (value == null || value.isEmpty()) continue;
            try {
                switch (def.type) {
                    case BOOLEAN:
                        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                            errors.add(def.key + ": must be true or false");
                        }
                        break;
                    case INT:
                        long intVal = Long.parseLong(value);
                        if (intVal < (long) def.min || intVal > (long) def.max) {
                            errors.add(def.key + ": must be between " + (long) def.min + " and " + (long) def.max);
                        }
                        break;
                    case FLOAT:
                        double realVal = Double.parseDouble(value);
                        if (realVal < def.min || realVal > def.max) {
                            errors.add(def.key + ": must be between " + def.min + " and " + def.max);
                        }
                        break;
                    case STRING:
                        break;
                }
            } catch (NumberFormatException e) {
                errors.add(def.key + ": invalid number format");
            }
        }
        return errors;
    }
}
