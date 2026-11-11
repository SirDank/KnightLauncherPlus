package com.lucasluqui.launcher.server;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Reads the Tortodrone database (schema version 16) for the settings window. Strictly read-only: while the server runs
 * it is the only writer (it keeps revisions and caches that a write behind its back would leave stale), so every
 * change goes through its console, see {@link ServerConsoleClient}. A database of another schema version is not read
 * at all, since the table layout may differ.
 */
public final class ServerDatabase {
    public static final int SCHEMA_VERSION = 16;

    public record Account(int id, String username, boolean gameMaster, long createdAt, int knightCount) {}

    public record Knight(int accountId, String username, int knightId, String name, long worldId,
                         long crowns, long energy, int rank, int subrank, int prestige, long createdAt) {}

    private final File file;

    public ServerDatabase(File file) {
        this.file = file;
    }

    /** Whether the server has created its database yet. */
    public boolean exists() {
        return file.isFile();
    }

    /** Every account, oldest first. */
    public List<Account> accounts() throws SQLException {
        List<Account> list = new ArrayList<>();
        if (!exists()) return list;
        try (Connection connection = open(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                 "SELECT a.id, a.username, a.game_master, a.created_at, COUNT(k.id) " +
                 "FROM accounts a LEFT JOIN knights k ON k.account_id = a.id GROUP BY a.id ORDER BY a.id")) {
            while (rs.next()) {
                list.add(new Account(rs.getInt(1), rs.getString(2), rs.getInt(3) != 0, rs.getLong(4), rs.getInt(5)));
            }
        }
        return list;
    }

    /** Every knight with its balances and progress, by account then creation. */
    public List<Knight> knights() throws SQLException {
        List<Knight> list = new ArrayList<>();
        if (!exists()) return list;
        // rank is stored as rank*10+subrank (1-1 until the knight has progressed); prestige defaults to 0.
        String sql =
            "SELECT k.account_id, a.username, k.id, k.name, COALESCE(w.world_id, 0), COALESCE(w.crowns, 0), " +
            "COALESCE(w.energy, 0), COALESCE(r.value, 11), COALESCE(p.value, 0), k.created_at " +
            "FROM knights k JOIN accounts a ON a.id = k.account_id " +
            "LEFT JOIN world_characters w ON w.account_id = k.account_id AND w.knight_id = k.id " +
            "LEFT JOIN metadata r ON r.key = 'knight.progress.' || w.world_id || '.rank' " +
            "LEFT JOIN metadata p ON p.key = 'knight.progress.' || w.world_id || '.prestige' " +
            "ORDER BY a.id, k.id";
        try (Connection connection = open(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                int rank = rs.getInt(8);
                list.add(new Knight(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getLong(5),
                    rs.getLong(6), rs.getLong(7), rank / 10, rank % 10, rs.getInt(9), rs.getLong(10)));
            }
        }
        return list;
    }

    /** The database's schema version, or -1 if it cannot be read. */
    public int schemaVersion() {
        if (!exists()) return -1;
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : -1;
        } catch (SQLException e) {
            return -1;
        }
    }

    private Connection connect() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("open_mode", "1"); // SQLITE_OPEN_READONLY
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath(), properties);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 3000");
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    /** Opens read-only, waiting a moment (not failing at once) if the running server holds the database; refuses another schema. */
    private Connection open() throws SQLException {
        Connection connection = connect();
        try (Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery("PRAGMA user_version")) {
                int version = rs.next() ? rs.getInt(1) : -1;
                if (version != SCHEMA_VERSION) {
                    throw new SQLException("unsupported schema version " + version + " (expected " + SCHEMA_VERSION + ")");
                }
            }
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }
}
