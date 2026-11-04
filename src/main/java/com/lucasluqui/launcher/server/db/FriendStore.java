package com.lucasluqui.launcher.server.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Friendships between knights. A friendship is mutual: two rows, one per direction, always written together. */
public final class FriendStore {

    public static final class Friend {
        public final long knightId;
        public final String name;
        public final int color;
        /** When they were last in the game (milliseconds), or 0 if never. */
        public final long lastSeen;

        public Friend(long knightId, String name, int color, long lastSeen) {
            this.knightId = knightId;
            this.name = name;
            this.color = color;
            this.lastSeen = lastSeen;
        }
    }

    /** The game hides its befriend button at this many friends. */
    public static final int MAX_FRIENDS = 250;

    private final Database database;

    public FriendStore(Database database) {
        this.database = database;
    }

    public List<Friend> list(long knightId) throws SQLException {
        return database.read(c -> {
            List<Friend> friends = new ArrayList<>();
            try (PreparedStatement select = c.prepareStatement(
                    "SELECT k.id, k.name, k.color, COALESCE(k.last_seen, 0) FROM friends f"
                        + " JOIN knights k ON k.id = f.friend_id WHERE f.knight_id = ? ORDER BY k.name")) {
                select.setLong(1, knightId);
                try (ResultSet row = select.executeQuery()) {
                    while (row.next()) friends.add(new Friend(row.getLong(1), row.getString(2), row.getInt(3), row.getLong(4)));
                }
            }
            return friends;
        });
    }

    public boolean areFriends(long a, long b) throws SQLException {
        return database.read(c -> {
            try (PreparedStatement select = c.prepareStatement("SELECT 1 FROM friends WHERE knight_id = ? AND friend_id = ?")) {
                select.setLong(1, a);
                select.setLong(2, b);
                try (ResultSet row = select.executeQuery()) {
                    return row.next();
                }
            }
        });
    }

    public int count(long knightId) throws SQLException {
        return database.read(c -> {
            try (PreparedStatement select = c.prepareStatement("SELECT COUNT(*) FROM friends WHERE knight_id = ?")) {
                select.setLong(1, knightId);
                try (ResultSet row = select.executeQuery()) {
                    row.next();
                    return row.getInt(1);
                }
            }
        });
    }

    /** Makes the two knights friends (both directions, one transaction); false if they already were or one is full. */
    public boolean add(long a, long b, long now) throws SQLException {
        if (a == b) return false;
        return database.write(c -> {
            for (long knight : new long[]{a, b}) {
                try (PreparedStatement count = c.prepareStatement("SELECT COUNT(*) FROM friends WHERE knight_id = ?")) {
                    count.setLong(1, knight);
                    try (ResultSet row = count.executeQuery()) {
                        row.next();
                        if (row.getInt(1) >= MAX_FRIENDS) return false;
                    }
                }
            }
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT OR IGNORE INTO friends (knight_id, friend_id, created_at) VALUES (?, ?, ?)")) {
                int added = 0;
                for (long[] pair : new long[][]{{a, b}, {b, a}}) {
                    insert.setLong(1, pair[0]);
                    insert.setLong(2, pair[1]);
                    insert.setLong(3, now);
                    added += insert.executeUpdate();
                }
                return added == 2;
            }
        });
    }

    /** Ends the friendship in both directions; false if there was none. */
    public boolean remove(long a, long b) throws SQLException {
        return database.write(c -> {
            try (PreparedStatement delete = c.prepareStatement(
                    "DELETE FROM friends WHERE (knight_id = ? AND friend_id = ?) OR (knight_id = ? AND friend_id = ?)")) {
                delete.setLong(1, a);
                delete.setLong(2, b);
                delete.setLong(3, b);
                delete.setLong(4, a);
                return delete.executeUpdate() > 0;
            }
        });
    }
}
