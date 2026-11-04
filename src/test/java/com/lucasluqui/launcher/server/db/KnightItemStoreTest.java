package com.lucasluqui.launcher.server.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class KnightItemStoreTest {
    private Database database;
    private KnightStore knights;
    private KnightItemStore items;
    private long alice, bob;

    @BeforeEach
    public void open() throws SQLException {
        database = Database.inMemory();
        AccountStore accounts = new AccountStore(database, new PasswordHasher(1_000));
        knights = new KnightStore(database);
        items = new KnightItemStore(database);
        alice = knights.create(accounts.signIn("a", "pw").account.id, "Alice", 0).knight.id;
        bob = knights.create(accounts.signIn("b", "pw").account.id, "Bobby", 0).knight.id;
    }

    @AfterEach
    public void close() throws SQLException {
        database.close();
    }

    @Test
    public void itemsBelongToOneKnightAndIdsNeverRepeat() throws SQLException {
        long a = items.add(alice, "Weapon/Sword/Blade", 3, -1);
        long b = items.add(bob, "Weapon/Sword/Blade", 3, -1);
        assertTrue(a > 0 && b > 0 && a != b);
        assertEquals(1, items.list(alice).size());
        assertEquals(a, items.list(alice).get(0).id);
        assertFalse(items.remove(bob, a), "a knight cannot take another's item");
        assertTrue(items.remove(alice, a));
        long c = items.add(alice, "Weapon/Sword/Blade", 3, -1);
        assertTrue(c > a, "an id is not reused");
    }

    @Test
    public void slotsAreSavedAndReplacedAsAWhole() throws SQLException {
        long sword = items.add(alice, "Weapon/Sword/Blade", 1, 6);
        long helm = items.add(alice, "Gear/Helm/Cap", 1, 0);
        assertEquals(6, items.list(alice).get(0).equipSlot);

        long[] equipment = new long[16];
        long[] quickbar = new long[8];
        equipment[7] = sword; // moved to another slot; the helm comes off
        quickbar[2] = helm;
        items.saveSlots(alice, equipment, quickbar);
        List<KnightItemStore.Item> now = items.list(alice);
        assertEquals(7, now.get(0).equipSlot);
        assertEquals(-1, now.get(0).quickSlot);
        assertEquals(-1, now.get(1).equipSlot);
        assertEquals(2, now.get(1).quickSlot);
    }

    @Test
    public void aKnightCannotCarryMoreThanTheLimit() throws SQLException {
        java.util.List<String> configs = java.util.Collections.nCopies(KnightItemStore.MAX_ITEMS, "Weapon/Sword/Blade");
        java.util.List<Integer> slots = java.util.Collections.nCopies(KnightItemStore.MAX_ITEMS, -1);
        items.addAll(alice, configs, 1, slots);
        assertEquals(KnightItemStore.MAX_ITEMS, items.count(alice));
        assertEquals(-1, items.add(alice, "Weapon/Sword/Blade", 1, -1));
    }

    @Test
    public void deletingTheKnightDeletesItsItems() throws SQLException {
        items.add(alice, "Weapon/Sword/Blade", 1, -1);
        long account = knights.findById(alice).get().accountId;
        knights.delete(account, alice);
        assertEquals(0, items.count(alice));
    }
}
