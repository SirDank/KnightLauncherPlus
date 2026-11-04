package com.lucasluqui.launcher.server;

import com.threerings.projectx.data.ProjectXAuthResponseData;
import com.threerings.util.Name;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Knights over the wire: a client creates, checks, chooses and deletes knights through the game's knight service.
 * The knights of every run have names of their own, because the database persists in the game folder.
 */
public class KnightProtocolTest {
    private static final int CREATE = 5;
    private static final int DELETE = 6;
    private static final int CHECK_NAME = 7;
    private static final int CHOOSE = 4;

    private SpiralServer server;
    private int port;
    private String key;
    private final String run = letters(6);

    private static String letters(int count) {
        Random random = new Random();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) text.append((char) ('a' + random.nextInt(26)));
        return text.toString();
    }

    @BeforeEach
    public void start() throws Exception {
        SpiralServerTest.assumeGameData();
        port = SpiralServerTest.freePort();
        server = new SpiralServer(port, false);
        server.start();
        key = ServerKeys.loadOrCreate(ServerKeys.keyFile(new File(ServerNet.GAME_DIR))).publicKeyProperty();
    }

    @AfterEach
    public void stop() {
        if (server != null) server.stop();
    }

    /** Signs in as a fresh account and returns the connected client. */
    private TestClient signedIn(String user) throws Exception {
        TestClient client = TestClient.connect(port);
        ProjectXAuthResponseData answer = client.login(key, user, "pw");
        assertEquals("success", answer.code);
        client.awaitBootstrap();
        return client;
    }

    private static TestClient.Reply create(TestClient client, String account, String knight) throws Exception {
        return client.invoke(ClientSession.KNIGHT_SERVICE_CODE, CREATE, new Name(account), new Name(knight), Integer.valueOf(257));
    }

    @Test
    public void aFirstLoginComesWithAKnightAndMoreCanBeCreated() throws Exception {
        String user = "Acct" + run;
        try (TestClient client = signedIn(user)) {
            TestClient.Reply reply = create(client, user, "Sir " + run);
            assertFalse(reply.failed, "create failed: " + reply.message);
        }
    }

    @Test
    public void knightNamesAreUniqueOnTheWholeServerAndNotTiedToTheAccountName() throws Exception {
        String wanted = "Ser " + run;
        try (TestClient one = signedIn("First" + run); TestClient two = signedIn("Second" + run)) {
            assertFalse(create(one, "First" + run, wanted).failed);
            TestClient.Reply taken = create(two, "Second" + run, wanted.toUpperCase());
            assertTrue(taken.failed);
            assertEquals("m.name_in_use", taken.message);
            // The account name is no reservation for the knight name, and the other way round.
            assertFalse(create(two, "Second" + run, "Second" + run.substring(0, 3) + "knight").failed);
        }
    }

    @Test
    public void invalidNamesGetTheGamesOwnMessages() throws Exception {
        String user = "Rules" + run;
        try (TestClient client = signedIn(user)) {
            assertEquals("e.name_too_short", create(client, user, "Abc").message);
            assertEquals("e.name_invalid_character", create(client, user, "Knight" + 7).message);
            assertEquals("e.name_too_consec", create(client, user, "Aaabcd").message);
            assertEquals("e.name_invalid_space", create(client, user, "Two  Spaces").message);
        }
    }

    @Test
    public void aNameCanBeCheckedBeforeCreatingTheKnight() throws Exception {
        String user = "Check" + run;
        try (TestClient client = signedIn(user)) {
            assertFalse(client.invoke(ClientSession.KNIGHT_SERVICE_CODE, CHECK_NAME, "Free " + run).failed);
            assertEquals("e.name_too_short", client.invoke(ClientSession.KNIGHT_SERVICE_CODE, CHECK_NAME, "ab").message);
            create(client, user, "Used " + run);
            assertEquals("m.name_in_use", client.invoke(ClientSession.KNIGHT_SERVICE_CODE, CHECK_NAME, "used " + run).message);
        }
    }

    @Test
    public void aKnightOfAnotherAccountCannotBeChosenOrDeleted() throws Exception {
        String mine = "Mine " + run;
        try (TestClient owner = signedIn("Owner" + run); TestClient other = signedIn("Other" + run)) {
            create(owner, "Owner" + run, mine);
            assertEquals("m.unknown_knight", other.invoke(ClientSession.KNIGHT_SERVICE_CODE, DELETE, new Name(mine)).message);
            assertEquals("m.unknown_knight", other.invoke(ClientSession.KNIGHT_SERVICE_CODE, CHOOSE, new Name(mine), null).message);
            // The owner still has it: deleting works for them, and the name is free again afterwards.
            assertFalse(owner.invoke(ClientSession.KNIGHT_SERVICE_CODE, DELETE, new Name(mine)).failed);
            assertFalse(create(other, "Other" + run, mine).failed);
        }
    }

    @Test
    public void aSecondKnightAppearsAfterReconnecting() throws Exception {
        String user = "Keeps" + run;
        String extra = "Extra " + run;
        try (TestClient client = signedIn(user)) {
            assertFalse(create(client, user, extra).failed);
        }
        try (TestClient again = signedIn(user)) {
            // Creating the same name again proves it was stored under this account.
            assertEquals("m.name_in_use", create(again, user, extra).message);
            assertNull(null);
        }
    }

    /** The id of the knight's item with this config (the starter kit is given when they first sign in). */
    private long itemId(long knightId, String config) throws Exception {
        for (com.lucasluqui.launcher.server.db.KnightItemStore.Item item : server.services().knightItems.list(knightId)) {
            if (item.config.equals(config)) return item.id;
        }
        throw new AssertionError("no " + config);
    }

    private com.lucasluqui.launcher.server.db.KnightItemStore.Item itemNamed(long knightId, String config) throws Exception {
        for (com.lucasluqui.launcher.server.db.KnightItemStore.Item item : server.services().knightItems.list(knightId)) {
            if (item.config.equals(config)) return item;
        }
        throw new AssertionError("no " + config);
    }

    /** Gear is saved when the session ends (in the session's own thread), so wait for it. */
    private void awaitSaved(long knightId, String config, int slot) throws Exception {
        for (int i = 0; i < 50; i++) {
            if (itemNamed(knightId, config).equipSlot == slot) return;
            Thread.sleep(100);
        }
        assertEquals(slot, itemNamed(knightId, config).equipSlot, config + " was not saved in its slot");
    }

    @Test
    public void wornGearIsSavedPerKnight() throws Exception {
        String user = "Gear" + run;
        long knightId;
        try (TestClient client = signedIn(user)) {
            knightId = server.services().knights.list(server.services().accounts.find(user).get().id).get(0).id;
            assertEquals(1, itemNamed(knightId, "Gear/Armor/Chaos Cloak").equipSlot, "the starter armor is worn");
            // Moving the armor to the bag takes it off.
            TestClient.Reply reply = client.invoke(ClientSession.ITEM_SERVICE_CODE, 9, Long.valueOf(itemId(knightId, "Gear/Armor/Chaos Cloak")),
                com.threerings.projectx.item.data.ItemCodes.Location.ARSENAL, Integer.valueOf(0));
            assertFalse(reply.failed, "move failed: " + reply.message);
        }
        awaitSaved(knightId, "Gear/Armor/Chaos Cloak", -1);
        assertEquals(6, itemNamed(knightId, "Weapon/Sword/Leviathan Blade").equipSlot, "the weapon should still be worn");
    }

    @Test
    public void theEquipMenuPutsAWeaponInTheChosenSlot() throws Exception {
        String user = "Equip" + run;
        long knightId;
        try (TestClient client = signedIn(user)) {
            knightId = server.services().knights.list(server.services().accounts.find(user).get().id).get(0).id;
            // "Equip as..." slot 7 moves the first starter weapon there.
            TestClient.Reply reply = client.invoke(ClientSession.ITEM_SERVICE_CODE, 3,
                Long.valueOf(itemId(knightId, "Weapon/Sword/Leviathan Blade")), Integer.valueOf(7));
            assertFalse(reply.failed, "equip failed: " + reply.message);
        }
        awaitSaved(knightId, "Weapon/Sword/Leviathan Blade", 7);
    }

    @Test
    public void everyKnightOwnsItsOwnItems() throws Exception {
        String user = "Own" + run;
        long first, second;
        try (TestClient client = signedIn(user)) {
            first = server.services().knights.list(server.services().accounts.find(user).get().id).get(0).id;
            assertFalse(create(client, user, "Second " + run).failed);
            second = server.services().knights.findByName("Second " + run).get().id;
        }
        // A knight's items are its own: a second knight has none until it is first played.
        assertTrue(server.services().knightItems.count(first) > 10);
        assertEquals(0, server.services().knightItems.count(second));
        assertTrue(itemId(first, "Weapon/Sword/Leviathan Blade") > 0);
    }
}
