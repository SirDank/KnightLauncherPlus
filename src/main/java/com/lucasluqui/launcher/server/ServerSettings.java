package com.lucasluqui.launcher.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The Tortodrone settings the launcher edits, with the same limits Tortodrone itself enforces at startup (it refuses
 * to start on anything outside them, so a value the launcher lets through but the server rejects is a server that
 * will not start).
 */
public final class ServerSettings {

    public enum Group { NETWORK, COMBAT, ADVANCED, LOGGING }

    public enum Type { BOOLEAN, INT, STRING }

    /** Why a value is refused; the window turns this into a message in the user's language. */
    public record Problem(String key, String code, long min, long max) {}

    public static final class Def {
        public final String key;
        public final Group group;
        public final Type type;
        public final String defaultValue;
        /** For an INT, its range; for a STRING, {@code max} is the most characters (code points) it may have. */
        public final long min;
        public final long max;
        /** A STRING must match this when set, or null for anything without control characters. */
        public final Pattern pattern;

        private Def(String key, Group group, Type type, String defaultValue, long min, long max, Pattern pattern) {
            this.key = key;
            this.group = group;
            this.type = type;
            this.defaultValue = defaultValue;
            this.min = min;
            this.max = max;
            this.pattern = pattern;
        }

        public String labelKey() {
            return "ss." + this.key;
        }

        public String descriptionKey() {
            return "ss." + this.key + ".desc";
        }
    }

    /** Folder names under content/maps (Tortodrone only accepts this shape, and the map must be a town). */
    public static final Pattern MAP_NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    public static final List<String> LOG_MODES = List.of("normal", "debug");

    private static final List<Def> ALL;

    static {
        List<Def> list = new ArrayList<>();
        list.add(bool("server.allowLan", Group.NETWORK, false));
        // Tortodrone: simultaneous connections, including logins still in progress (1-1024).
        list.add(integer("server.maxClients", Group.NETWORK, 16, 1, 1024));

        list.add(integer("combat.dodgeCooldownMs", Group.COMBAT, 8000, 0, 600_000));

        list.add(text("server.welcomeMessage", Group.ADVANCED, "Welcome to Tortodrone!", 256, null));
        list.add(text("server.startupMap", Group.ADVANCED, "haven", 64, MAP_NAME));

        list.add(text("server.logMode", Group.LOGGING, "normal", 16, Pattern.compile("normal|debug")));
        list.add(integer("server.logMaxMib", Group.LOGGING, 10, 1, 1024));
        list.add(integer("server.logRetention", Group.LOGGING, 5, 1, 100));

        ALL = Collections.unmodifiableList(list);
    }

    private ServerSettings() {}

    private static Def bool(String key, Group group, boolean def) {
        return new Def(key, group, Type.BOOLEAN, String.valueOf(def), 0, 1, null);
    }

    private static Def integer(String key, Group group, int def, int min, int max) {
        return new Def(key, group, Type.INT, String.valueOf(def), min, max, null);
    }

    private static Def text(String key, Group group, String def, int maxLength, Pattern pattern) {
        return new Def(key, group, Type.STRING, def, 0, maxLength, pattern);
    }

    public static List<Def> all() {
        return ALL;
    }

    /** What is wrong with the given values (keys are the ones in {@link #all()}); blank or missing values are fine. */
    public static List<Problem> validate(Map<String, String> values) {
        List<Problem> problems = new ArrayList<>();
        for (Def def : ALL) {
            String value = values.get(def.key);
            if (value == null || value.isEmpty()) continue;
            switch (def.type) {
                case BOOLEAN:
                    if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                        problems.add(new Problem(def.key, "bool", 0, 0));
                    }
                    break;
                case INT:
                    try {
                        long number = Long.parseLong(value.trim());
                        if (number < def.min || number > def.max) problems.add(new Problem(def.key, "range", def.min, def.max));
                    } catch (NumberFormatException e) {
                        problems.add(new Problem(def.key, "number", def.min, def.max));
                    }
                    break;
                case STRING:
                    if (value.codePointCount(0, value.length()) > def.max) {
                        problems.add(new Problem(def.key, "length", 0, def.max));
                    } else if (hasControlCharacter(value)) {
                        problems.add(new Problem(def.key, "control", 0, 0));
                    } else if (def.pattern != null && !def.pattern.matcher(value).matches()) {
                        problems.add(new Problem(def.key, "pattern", 0, 0));
                    }
                    break;
            }
        }
        return problems;
    }

    private static boolean hasControlCharacter(String value) {
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            if (Character.isISOControl(cp) || (cp >= 0xD800 && cp <= 0xDFFF)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }
}
