package com.lucasluqui.launcher.server.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FriendStoreTest {
    private Database database;
    private KnightStore knights;
    private FriendStore friends;
    private long alice, bob, carol;

    @BeforeEach
    public void open() throws SQLException {
        database = Database.inMemory();
        AccountStore accounts = new AccountStore(database, new PasswordHasher(1_000));
        knights = new KnightStore(database);
        friends = new FriendStore(database);
        alice = knights.create(accounts.signIn("a", "pw").account.id, "Alice", 0).knight.id;
        bob = knights.create(accounts.signIn("b", "pw").account.id, "Bobby", 3).knight.id;
        carol = knights.create(accounts.signIn("c", "pw").account.id, "Carol", 0).knight.id;
    }

    @AfterEach
    public void close() throws SQLException {
        database.close();
    }

    @Test
    public void aFriendshipIsMutualAndListedByName() throws SQLException {
        assertTrue(friends.add(alice, bob, 1));
        assertTrue(friends.areFriends(alice, bob));
        assertTrue(friends.areFriends(bob, alice));
        assertEquals("Bobby", friends.list(alice).get(0).name);
        assertEquals(3, friends.list(alice).get(0).color);
        assertEquals("Alice", friends.list(bob).get(0).name);
    }

    @Test
    public void befriendingTwiceOrYourselfDoesNothing() throws SQLException {
        assertTrue(friends.add(alice, bob, 1));
        assertFalse(friends.add(bob, alice, 2));
        assertFalse(friends.add(alice, alice, 3));
        assertEquals(1, friends.count(alice));
    }

    @Test
    public void removingEndsItForBoth() throws SQLException {
        friends.add(alice, bob, 1);
        friends.add(alice, carol, 1);
        assertTrue(friends.remove(bob, alice));
        assertFalse(friends.areFriends(alice, bob));
        assertFalse(friends.areFriends(bob, alice));
        assertTrue(friends.areFriends(alice, carol));
        assertFalse(friends.remove(alice, bob));
    }

    @Test
    public void deletingAKnightEndsItsFriendships() throws SQLException {
        friends.add(alice, bob, 1);
        long account = knights.findById(bob).get().accountId;
        knights.delete(account, bob);
        assertEquals(0, friends.count(alice));
    }

    @Test
    public void lastSeenIsShownToFriends() throws SQLException {
        friends.add(alice, bob, 1);
        assertEquals(0, friends.list(alice).get(0).lastSeen);
        knights.setLastSeen(bob, 12345);
        assertEquals(12345, friends.list(alice).get(0).lastSeen);
    }

    @Test
    public void aFriendRequestIsPendingUntilItIsAnswered() throws SQLException {
        MailStore mail = new MailStore(database);
        assertFalse(mail.friendRequestPending(bob, alice));
        MailStore.Mail sent = mail.deliver(bob, alice, "Alice", "FRIEND", "Hi", "Be my friend", alice).mail;
        assertTrue(mail.friendRequestPending(bob, alice));
        assertEquals(Long.valueOf(alice), sent.attachKnightId);
        mail.delete(bob, sent.id);
        assertFalse(mail.friendRequestPending(bob, alice));
    }
}
