package com.lucasluqui.launcher.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerDatabaseTest {
    @TempDir Path dir;

    /** The tables as Tortodrone 0.40.8 creates them (schema 16), cut to what the launcher reads. */
    private File database(int version) throws SQLException {
        File file = dir.resolve("tortodrone.db").toFile();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath()); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE metadata(key TEXT PRIMARY KEY, value INTEGER NOT NULL)");
            s.execute("CREATE TABLE accounts(id INTEGER PRIMARY KEY CHECK(id>0), username TEXT NOT NULL, name_key TEXT NOT NULL UNIQUE, "
                + "salt BLOB NOT NULL CHECK(length(salt)=16), verifier BLOB NOT NULL CHECK(length(verifier)=32), "
                + "game_master INTEGER NOT NULL DEFAULT 0 CHECK(game_master IN(0,1)), auth_revision INTEGER NOT NULL DEFAULT 1, "
                + "revision INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL, next_knight_id INTEGER NOT NULL DEFAULT 1)");
            s.execute("CREATE TABLE knights(account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE, id INTEGER NOT NULL, "
                + "name TEXT NOT NULL, name_key TEXT NOT NULL, helm TEXT NOT NULL, armor TEXT NOT NULL, helm_accessory TEXT, "
                + "armor_accessory TEXT, costume TEXT NOT NULL, color INTEGER NOT NULL, height INTEGER NOT NULL, eye INTEGER NOT NULL, "
                + "created_at INTEGER NOT NULL, last_renamed INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(account_id,id))");
            s.execute("CREATE TABLE world_characters(world_id INTEGER PRIMARY KEY, account_id INTEGER NOT NULL, knight_id INTEGER NOT NULL, "
                + "crowns INTEGER NOT NULL DEFAULT 0, energy INTEGER NOT NULL DEFAULT 0, state_revision INTEGER NOT NULL DEFAULT 1, "
                + "UNIQUE(account_id,knight_id))");
            s.execute("INSERT INTO accounts(id,username,name_key,salt,verifier,game_master,created_at) VALUES "
                + "(1,'Alice','alice',zeroblob(16),zeroblob(32),1,1000),(2,'Bob','bob',zeroblob(16),zeroblob(32),0,2000),"
                + "(3,'Carol','carol',zeroblob(16),zeroblob(32),0,3000)");
            s.execute("INSERT INTO knights(account_id,id,name,name_key,helm,armor,costume,color,height,eye,created_at) VALUES "
                + "(1,1,'Sir-Lance','sir-lance','h','a','c',1,1,1,1500),(1,2,'Dusk','dusk','h','a','c',1,1,1,1600),"
                + "(2,1,'Rook','rook','h','a','c',1,1,1,2500)");
            s.execute("INSERT INTO world_characters(world_id,account_id,knight_id,crowns,energy) VALUES (10,1,1,5000,120),(11,1,2,0,0),(12,2,1,77,3)");
            // Sir-Lance is rank 4-2 with prestige 3; Dusk has never progressed (no rows); Rook is 10-4.
            s.execute("INSERT INTO metadata VALUES ('knight.progress.10.rank',42),('knight.progress.10.prestige',3),('knight.progress.12.rank',104)");
            s.execute("PRAGMA user_version = " + version);
        }
        return file;
    }

    @Test
    public void accountsAreListedWithTheirKnightCounts() throws SQLException {
        List<ServerDatabase.Account> accounts = new ServerDatabase(database(16)).accounts();
        assertEquals(3, accounts.size());
        assertEquals(new ServerDatabase.Account(1, "Alice", true, 1000, 2), accounts.get(0));
        assertEquals(new ServerDatabase.Account(2, "Bob", false, 2000, 1), accounts.get(1));
        assertEquals(0, accounts.get(2).knightCount());
    }

    @Test
    public void knightsCarryBalancesRankAndPrestige() throws SQLException {
        List<ServerDatabase.Knight> knights = new ServerDatabase(database(16)).knights();
        assertEquals(3, knights.size());
        ServerDatabase.Knight lance = knights.get(0);
        assertEquals("Sir-Lance", lance.name());
        assertEquals("Alice", lance.username());
        assertEquals(10, lance.worldId());
        assertEquals(5000, lance.crowns());
        assertEquals(120, lance.energy());
        assertEquals(4, lance.rank());
        assertEquals(2, lance.subrank());
        assertEquals(3, lance.prestige());
        ServerDatabase.Knight dusk = knights.get(1);
        assertEquals(1, dusk.rank(), "a knight that never progressed is rank 1-1");
        assertEquals(1, dusk.subrank());
        assertEquals(0, dusk.prestige());
        assertEquals(10, knights.get(2).rank());
        assertEquals(4, knights.get(2).subrank());
    }

    @Test
    public void aDatabaseOfAnotherSchemaVersionIsNotRead() throws SQLException {
        ServerDatabase db = new ServerDatabase(database(17));
        assertThrows(SQLException.class, db::accounts);
        assertThrows(SQLException.class, db::knights);
        assertEquals(17, db.schemaVersion());
    }

    @Test
    public void aMissingDatabaseIsJustEmpty() throws SQLException {
        ServerDatabase db = new ServerDatabase(dir.resolve("nothing.db").toFile());
        assertFalse(db.exists());
        assertTrue(db.accounts().isEmpty());
        assertTrue(db.knights().isEmpty());
        assertEquals(-1, db.schemaVersion());
    }

    @Test
    public void theLauncherCannotWriteThroughItsConnection() throws SQLException {
        File file = database(16);
        // The class only exposes reads, but the connection itself must also refuse a write.
        java.util.Properties properties = new java.util.Properties();
        properties.setProperty("open_mode", "1");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath(), properties); Statement s = c.createStatement()) {
            assertThrows(SQLException.class, () -> s.executeUpdate("UPDATE accounts SET game_master = 1"));
        }
    }
}
