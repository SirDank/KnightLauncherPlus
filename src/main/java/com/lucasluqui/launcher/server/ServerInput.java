package com.lucasluqui.launcher.server;

import java.util.regex.Pattern;

/**
 * What Tortodrone's console accepts for a name or password, checked before anything is typed into it (a console line
 * is split on spaces, so an unchecked name is an unchecked command).
 */
public final class ServerInput {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{2,31}");
    private static final Pattern KNIGHT = Pattern.compile("[A-Za-z]+(-[A-Za-z]+)*");

    private ServerInput() {}

    /** An account name: a letter, then letters, digits, "_" or "-", 3 to 32 characters. */
    public static boolean validUsername(String name) {
        return name != null && USERNAME.matcher(name).matches();
    }

    /** A knight name as the console takes it: letters, with single hyphens between parts, 3 to 16 characters. */
    public static boolean validKnight(String name) {
        return name != null && name.length() >= 3 && name.length() <= 16 && KNIGHT.matcher(name).matches();
    }

    /** A password: 8 to 128 characters, no control characters. */
    public static boolean validPassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 128) return false;
        for (int i = 0; i < password.length(); i++) {
            if (Character.isISOControl(password.charAt(i))) return false;
        }
        return true;
    }

    /** A catalogue id or item config reference: one word, nothing that would split or end the console line. */
    public static boolean validItem(String item) {
        if (item == null || item.isEmpty() || item.length() > 200) return false;
        for (int i = 0; i < item.length(); i++) {
            char c = item.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) return false;
        }
        return true;
    }
}
