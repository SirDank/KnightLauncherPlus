package com.lucasluqui.launcher.server.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Knights (the characters players run around with). A knight's name is unique on the whole server, without regard
 * to case, and has nothing to do with the name of the account that owns it.
 */
public final class KnightStore {

    public enum Status { CREATED, NAME_TAKEN, INVALID_NAME, TOO_MANY_KNIGHTS, NOT_FOUND, RENAMED, DELETED }

    public static final class Knight {
        public final long id;
        public final long accountId;
        public final String name;
        public final int color;

        Knight(long id, long accountId, String name, int color) {
            this.id = id;
            this.accountId = accountId;
            this.name = name;
            this.color = color;
        }
    }

    public static final class Result {
        public final Status status;
        /** The knight, for {@link Status#CREATED} and {@link Status#RENAMED}; null otherwise. */
        public final Knight knight;
        /** For {@link Status#INVALID_NAME}: the game's own message key (e.name_too_short, ...); null otherwise. */
        public final String error;

        Result(Status status, Knight knight) {
            this(status, knight, null);
        }

        Result(Status status, Knight knight, String error) {
            this.status = status;
            this.knight = knight;
            this.error = error;
        }
    }

    /** The game says "valid names must be at least 4 letters long". */
    public static final int MIN_LETTERS = 4;
    public static final int MAX_NAME = 16;
    public static final int MAX_KNIGHTS_PER_ACCOUNT = 6;

    private final Database database;

    public KnightStore(Database database) {
        this.database = database;
    }

    /**
     * The game's own rules for a knight's name, with the message key its client shows for each (they are in its
     * projectx bundle): letters, spaces and dashes only; at least 4 letters; at most two spaces, none at the ends or
     * doubled; at most two dashes, each with two letters on both sides; no letter more than twice in a row.
     *
     * @return null if the name is fine, else the message key.
     */
    public static String nameError(String name) {
        if (name == null) return "e.name_too_short";
        int letters = 0;
        int spaces = 0;
        int dashes = 0;
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (isLetter(ch)) letters++;
            else if (ch == ' ') spaces++;
            else if (ch == '-') dashes++;
            else return "e.name_invalid_character";
        }
        if (spaces > 2 || name.startsWith(" ") || name.endsWith(" ") || name.contains("  ")) return "e.name_invalid_space";
        if (dashes > 2) return "e.name_invalid_dash";
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) != '-') continue;
            if (i < 2 || i + 2 >= name.length() || !isLetter(name.charAt(i - 1)) || !isLetter(name.charAt(i - 2))
                    || !isLetter(name.charAt(i + 1)) || !isLetter(name.charAt(i + 2))) {
                return "e.name_invalid_dash";
            }
        }
        if (letters < MIN_LETTERS) return "e.name_too_short";
        if (name.length() > MAX_NAME) return "e.name_too_long";
        for (int i = 2; i < name.length(); i++) {
            char a = Character.toLowerCase(name.charAt(i));
            if (isLetter(name.charAt(i)) && a == Character.toLowerCase(name.charAt(i - 1))
                    && a == Character.toLowerCase(name.charAt(i - 2))) {
                return "e.name_too_consec";
            }
        }
        return null;
    }

    public static boolean validName(String name) {
        return nameError(name) == null;
    }

    private static boolean isLetter(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
    }

    public Result create(long accountId, String name, int color) throws SQLException {
        String trimmed = name == null ? "" : name.trim();
        String error = nameError(trimmed);
        if (error != null) return new Result(Status.INVALID_NAME, null, error);
        return database.write(c -> {
            try (PreparedStatement count = c.prepareStatement("SELECT COUNT(*) FROM knights WHERE account_id = ?")) {
                count.setLong(1, accountId);
                try (ResultSet row = count.executeQuery()) {
                    row.next();
                    if (row.getInt(1) >= MAX_KNIGHTS_PER_ACCOUNT) return new Result(Status.TOO_MANY_KNIGHTS, null);
                }
            }
            try (PreparedStatement taken = c.prepareStatement("SELECT 1 FROM knights WHERE name = ?")) {
                taken.setString(1, trimmed);
                try (ResultSet row = taken.executeQuery()) {
                    if (row.next()) return new Result(Status.NAME_TAKEN, null);
                }
            }
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO knights (account_id, name, color, created_at) VALUES (?, ?, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS)) {
                insert.setLong(1, accountId);
                insert.setString(2, trimmed);
                insert.setInt(3, color);
                insert.setLong(4, System.currentTimeMillis());
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    keys.next();
                    return new Result(Status.CREATED, new Knight(keys.getLong(1), accountId, trimmed, color));
                }
            }
        });
    }

    public List<Knight> list(long accountId) throws SQLException {
        return database.read(c -> {
            List<Knight> knights = new ArrayList<>();
            try (PreparedStatement select = c.prepareStatement(
                    "SELECT id, account_id, name, color FROM knights WHERE account_id = ? ORDER BY id")) {
                select.setLong(1, accountId);
                try (ResultSet row = select.executeQuery()) {
                    while (row.next()) knights.add(new Knight(row.getLong(1), row.getLong(2), row.getString(3), row.getInt(4)));
                }
            }
            return knights;
        });
    }

    public Optional<Knight> findById(long id) throws SQLException {
        return database.read(c -> {
            try (PreparedStatement select = c.prepareStatement(
                    "SELECT id, account_id, name, color FROM knights WHERE id = ?")) {
                select.setLong(1, id);
                try (ResultSet row = select.executeQuery()) {
                    return row.next()
                        ? Optional.of(new Knight(row.getLong(1), row.getLong(2), row.getString(3), row.getInt(4)))
                        : Optional.<Knight>empty();
                }
            }
        });
    }

    /** Remembers when the knight was last in the game (shown to their friends). */
    public void setLastSeen(long knightId, long millis) throws SQLException {
        database.write(c -> {
            try (PreparedStatement update = c.prepareStatement("UPDATE knights SET last_seen = ? WHERE id = ?")) {
                update.setLong(1, millis);
                update.setLong(2, knightId);
                update.executeUpdate();
            }
            return null;
        });
    }

    public Optional<Knight> findByName(String name) throws SQLException {
        return database.read(c -> {
            try (PreparedStatement select = c.prepareStatement(
                    "SELECT id, account_id, name, color FROM knights WHERE name = ?")) {
                select.setString(1, name == null ? "" : name.trim());
                try (ResultSet row = select.executeQuery()) {
                    return row.next()
                        ? Optional.of(new Knight(row.getLong(1), row.getLong(2), row.getString(3), row.getInt(4)))
                        : Optional.<Knight>empty();
                }
            }
        });
    }

    /** Renames a knight that belongs to the account. */
    public Result rename(long accountId, long knightId, String newName) throws SQLException {
        String trimmed = newName == null ? "" : newName.trim();
        String error = nameError(trimmed);
        if (error != null) return new Result(Status.INVALID_NAME, null, error);
        return database.write(c -> {
            try (PreparedStatement taken = c.prepareStatement("SELECT id FROM knights WHERE name = ?")) {
                taken.setString(1, trimmed);
                try (ResultSet row = taken.executeQuery()) {
                    if (row.next() && row.getLong(1) != knightId) return new Result(Status.NAME_TAKEN, null);
                }
            }
            try (PreparedStatement update = c.prepareStatement("UPDATE knights SET name = ? WHERE id = ? AND account_id = ?")) {
                update.setString(1, trimmed);
                update.setLong(2, knightId);
                update.setLong(3, accountId);
                if (update.executeUpdate() != 1) return new Result(Status.NOT_FOUND, null);
            }
            return new Result(Status.RENAMED, new Knight(knightId, accountId, trimmed, 0));
        });
    }

    /** Deletes a knight that belongs to the account. */
    public Status delete(long accountId, long knightId) throws SQLException {
        return database.write(c -> {
            try (PreparedStatement delete = c.prepareStatement("DELETE FROM knights WHERE id = ? AND account_id = ?")) {
                delete.setLong(1, knightId);
                delete.setLong(2, accountId);
                return delete.executeUpdate() == 1 ? Status.DELETED : Status.NOT_FOUND;
            }
        });
    }
}
