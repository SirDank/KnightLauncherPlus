package com.lucasluqui.launcher.server.db;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * The server's SQLite file. One connection, used by one thread at a time (the server process is the only writer), so
 * every access goes through {@link #write} or {@link #read}. The schema is versioned with {@code PRAGMA user_version}
 * and upgraded by {@link #MIGRATIONS} when the file is opened.
 */
public final class Database implements AutoCloseable {

    /** Work done on the connection; its result is returned by {@link #write}/{@link #read}. */
    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    /**
     * Schema upgrades, in order. Entry {@code i} takes a file from version {@code i} to {@code i + 1}. Never edit an
     * entry that has been released: add a new one.
     */
    static final List<String> MIGRATIONS = List.of(
        // 1: accounts and knights. Names are unique without regard to case, across the whole server.
        "CREATE TABLE accounts ("
            + " id INTEGER PRIMARY KEY,"
            + " username TEXT NOT NULL UNIQUE COLLATE NOCASE,"
            + " pw_hash BLOB NOT NULL,"
            + " pw_salt BLOB NOT NULL,"
            + " pw_iterations INTEGER NOT NULL,"
            + " created_at INTEGER NOT NULL,"
            + " last_login INTEGER);"
            + "CREATE TABLE knights ("
            + " id INTEGER PRIMARY KEY,"
            + " account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,"
            + " name TEXT NOT NULL UNIQUE COLLATE NOCASE,"
            + " color INTEGER NOT NULL DEFAULT 0,"
            + " created_at INTEGER NOT NULL);"
            + "CREATE INDEX knights_by_account ON knights(account_id);",
        // 2: in-game mail between knights (text only for now; attachments come later with their own migration).
        // Ids start at 1: the client reserves id 0 for a fake mail of its own.
        "CREATE TABLE mail ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " recipient_knight_id INTEGER NOT NULL REFERENCES knights(id) ON DELETE CASCADE,"
            + " sender_knight_id INTEGER,"
            + " sender_name TEXT NOT NULL,"
            + " type TEXT NOT NULL DEFAULT 'USER',"
            + " sent INTEGER NOT NULL,"
            + " subject TEXT NOT NULL,"
            + " body TEXT NOT NULL,"
            + " unread INTEGER NOT NULL DEFAULT 1);"
            + "CREATE INDEX mail_inbox ON mail(recipient_knight_id, sent DESC);",
        // 3: what a knight owns and wears. Equipment is stored by item name, not id: ids are handed out at login and
        // change when the game's item list does. A knight without a row has the defaults.
        "CREATE TABLE knight_state ("
            + " knight_id INTEGER PRIMARY KEY REFERENCES knights(id) ON DELETE CASCADE,"
            + " crowns INTEGER NOT NULL,"
            + " equipment TEXT NOT NULL,"
            + " quickbar TEXT NOT NULL);",
        // 4: friends (one row per direction), the knight a friend request mail came from, and when a knight was last on.
        "CREATE TABLE friends ("
            + " knight_id INTEGER NOT NULL REFERENCES knights(id) ON DELETE CASCADE,"
            + " friend_id INTEGER NOT NULL REFERENCES knights(id) ON DELETE CASCADE,"
            + " created_at INTEGER NOT NULL,"
            + " PRIMARY KEY (knight_id, friend_id));"
            + "ALTER TABLE mail ADD COLUMN attach_knight_id INTEGER;"
            + "ALTER TABLE knights ADD COLUMN last_seen INTEGER;",
        // 5: the items a knight owns (weapons and gear). The id is what the game sees; equip_slot/quick_slot say where
        // an item is worn (null: in the bag). This replaces the by-name gear of knight_state.
        "CREATE TABLE knight_items ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " knight_id INTEGER NOT NULL REFERENCES knights(id) ON DELETE CASCADE,"
            + " config TEXT NOT NULL,"
            + " level INTEGER NOT NULL DEFAULT 1,"
            + " equip_slot INTEGER,"
            + " quick_slot INTEGER);"
            + "CREATE INDEX knight_items_by_knight ON knight_items(knight_id);"
    );

    private final Connection connection;

    private Database(Connection connection) {
        this.connection = connection;
    }

    /** Opens (and creates or upgrades) the database file. */
    public static Database open(File file) throws SQLException {
        try {
            File directory = file.getAbsoluteFile().getParentFile();
            Files.createDirectories(directory.toPath());
            // The driver unpacks its native library into this folder; the system temp folder is not always writable.
            if (System.getProperty("org.sqlite.tmpdir") == null) {
                File temporary = new File(directory, "tmp");
                Files.createDirectories(temporary.toPath());
                System.setProperty("org.sqlite.tmpdir", temporary.getPath());
            }
        } catch (IOException e) {
            throw new SQLException("Cannot create the folder for " + file + ": " + e.getMessage(), e);
        }
        return connect("jdbc:sqlite:" + file.getAbsolutePath());
    }

    /** A throwaway database in memory, for tests. */
    public static Database inMemory() throws SQLException {
        return connect("jdbc:sqlite::memory:");
    }

    private static Database connect(String url) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("The SQLite driver is missing from the launcher", e);
        }
        Connection connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
            if (!url.contains(":memory:")) statement.execute("PRAGMA journal_mode = WAL");
        }
        Database database = new Database(connection);
        database.migrate();
        return database;
    }

    private void migrate() throws SQLException {
        int version;
        try (Statement statement = connection.createStatement();
             java.sql.ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            version = result.next() ? result.getInt(1) : 0;
        }
        if (version > MIGRATIONS.size()) {
            throw new SQLException("This database was made by a newer launcher (schema version " + version
                + ", this one knows " + MIGRATIONS.size() + ")");
        }
        for (int next = version; next < MIGRATIONS.size(); next++) {
            final int target = next + 1;
            write(c -> {
                try (Statement statement = c.createStatement()) {
                    for (String sql : MIGRATIONS.get(target - 1).split(";")) {
                        if (!sql.trim().isEmpty()) statement.execute(sql);
                    }
                    statement.execute("PRAGMA user_version = " + target);
                }
                return null;
            });
        }
    }

    public int schemaVersion() throws SQLException {
        return read(c -> {
            try (Statement statement = c.createStatement(); java.sql.ResultSet result = statement.executeQuery("PRAGMA user_version")) {
                return result.next() ? result.getInt(1) : 0;
            }
        });
    }

    /** Runs {@code work} in a transaction: committed when it returns, rolled back when it throws. */
    public synchronized <T> T write(Work<T> work) throws SQLException {
        boolean auto = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = work.run(connection);
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(auto);
        }
    }

    /** Runs a query. */
    public synchronized <T> T read(Work<T> work) throws SQLException {
        return work.run(connection);
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
