package com.lucasluqui.launcher.server.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class KnightStateStoreTest {
    private Database database;
    private KnightStore knights;
    private KnightStateStore state;
    private long knight;

    @BeforeEach
    public void open() throws SQLException {
        database = Database.inMemory();
        AccountStore accounts = new AccountStore(database, new PasswordHasher(1_000));
        knights = new KnightStore(database);
        state = new KnightStateStore(database);
        knight = knights.create(accounts.signIn("a", "pw").account.id, "Alice", 0).knight.id;
    }

    @AfterEach
    public void close() throws SQLException {
        database.close();
    }

    @Test
    public void aKnightWithoutASaveHasNoState() throws SQLException {
        assertFalse(state.load(knight).isPresent());
    }

    @Test
    public void crownsAndGearComeBackAsSaved() throws SQLException {
        Map<Integer, String> worn = new LinkedHashMap<>();
        worn.put(0, "Gear/Helm/Chaos Cowl");
        worn.put(6, "Weapon/Sword/Leviathan Blade");
        Map<Integer, String> quick = new LinkedHashMap<>();
        quick.put(2, "Consumable/Elixir");
        state.save(knight, new KnightStateStore.State(1234, worn, quick));
        KnightStateStore.State loaded = state.load(knight).get();
        assertEquals(1234, loaded.crowns);
        assertEquals(worn, loaded.equipment);
        assertEquals(quick, loaded.quickbar);
    }

    @Test
    public void savingAgainReplacesTheEarlierSave() throws SQLException {
        state.save(knight, new KnightStateStore.State(1, Map.of(0, "A"), Map.of()));
        state.save(knight, new KnightStateStore.State(2, Map.of(), Map.of()));
        KnightStateStore.State loaded = state.load(knight).get();
        assertEquals(2, loaded.crowns);
        assertTrue(loaded.equipment.isEmpty());
    }

    @Test
    public void aDamagedLineLeavesOnlyThatSlotEmpty() {
        Map<Integer, String> slots = KnightStateStore.decode("0\tHelm\nnonsense\nx\tBad\n6\tSword\n");
        assertEquals(Map.of(0, "Helm", 6, "Sword"), slots);
    }

    @Test
    public void deletingTheKnightDeletesItsState() throws SQLException {
        state.save(knight, new KnightStateStore.State(5, Map.of(), Map.of()));
        long account = knights.list(knights.findByName("Alice").get().accountId).get(0).accountId;
        knights.delete(account, knight);
        assertFalse(state.load(knight).isPresent());
    }
}
