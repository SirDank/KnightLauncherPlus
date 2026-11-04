package com.lucasluqui.launcher.server.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MailStoreTest {
    private Database database;
    private AtomicLong now;
    private MailStore mail;
    private long alice;
    private long bob;

    @BeforeEach
    public void open() throws SQLException {
        database = Database.inMemory();
        now = new AtomicLong(1_000_000_000L);
        mail = new MailStore(database, now::get);
        AccountStore accounts = new AccountStore(database, new PasswordHasher(1_000));
        KnightStore knights = new KnightStore(database);
        alice = knights.create(accounts.signIn("a", "pw").account.id, "Alice", 0).knight.id;
        bob = knights.create(accounts.signIn("b", "pw").account.id, "Bobby", 0).knight.id;
    }

    @AfterEach
    public void close() throws SQLException {
        database.close();
    }

    @Test
    public void deliveredMailArrivesUnreadWithARealIdAndTheSendersName() throws SQLException {
        MailStore.Delivery delivery = mail.deliver(bob, alice, "Alice", "USER", " Hello ", " Hi Bob ");
        assertEquals(MailStore.Status.DELIVERED, delivery.status);
        assertTrue(delivery.mail.id >= 1, "id 0 is reserved by the client");
        assertEquals("Hello", delivery.mail.subject);

        List<MailStore.Mail> inbox = mail.inbox(bob, null);
        assertEquals(1, inbox.size());
        assertEquals("Alice", inbox.get(0).senderName);
        assertTrue(inbox.get(0).unread);
        assertEquals(1, mail.unreadCount(bob));
        assertEquals(0, mail.inbox(alice, null).size());
    }

    @Test
    public void inboxIsNewestFirstAndCanBeLimitedToIds() throws SQLException {
        long first = mail.deliver(bob, alice, "Alice", "USER", "one", "x").mail.id;
        now.addAndGet(1000);
        long second = mail.deliver(bob, alice, "Alice", "USER", "two", "x").mail.id;
        now.addAndGet(1000);
        long third = mail.deliver(bob, alice, "Alice", "USER", "three", "x").mail.id;

        assertEquals(List.of(third, second, first), ids(mail.inbox(bob, null)));
        assertEquals(List.of(second, first), ids(mail.inbox(bob, new long[]{first, second})));
        assertEquals(0, mail.inbox(bob, new long[0]).size());
    }

    @Test
    public void nobodyCanReadMarkOrDeleteAnotherKnightsMail() throws SQLException {
        long id = mail.deliver(bob, alice, "Alice", "USER", "private", "x").mail.id;

        assertEquals(0, mail.inbox(alice, new long[]{id}).size());
        assertEquals(0, mail.markRead(alice, new long[]{id}));
        assertFalse(mail.delete(alice, id));
        assertEquals(1, mail.unreadCount(bob));
        assertEquals(1, mail.inbox(bob, null).size());
    }

    @Test
    public void markReadClearsTheUnreadCountOnce() throws SQLException {
        long a = mail.deliver(bob, alice, "Alice", "USER", "a", "x").mail.id;
        long b = mail.deliver(bob, alice, "Alice", "USER", "b", "x").mail.id;
        assertEquals(2, mail.markRead(bob, new long[]{a, b}));
        assertEquals(0, mail.markRead(bob, new long[]{a, b}), "already read");
        assertEquals(0, mail.unreadCount(bob));
        assertFalse(mail.inbox(bob, new long[]{a}).get(0).unread);
    }

    @Test
    public void deleteRemovesOnlyThatMail() throws SQLException {
        long a = mail.deliver(bob, alice, "Alice", "USER", "a", "x").mail.id;
        long b = mail.deliver(bob, alice, "Alice", "USER", "b", "x").mail.id;
        assertTrue(mail.delete(bob, a));
        assertFalse(mail.delete(bob, a));
        assertEquals(List.of(b), ids(mail.inbox(bob, null)));
    }

    @Test
    public void systemMailHasNoSenderKnight() throws SQLException {
        mail.deliver(bob, null, "!Support", "SYSTEM", "Welcome", "Have fun");
        MailStore.Mail received = mail.inbox(bob, null).get(0);
        assertNull(received.senderKnightId);
        assertEquals("!Support", received.senderName);
    }

    @Test
    public void rejectsEmptyAndOversizedMail() throws SQLException {
        assertEquals(MailStore.Status.EMPTY, mail.deliver(bob, alice, "Alice", "USER", "  ", "body").status);
        assertEquals(MailStore.Status.EMPTY, mail.deliver(bob, alice, "Alice", "USER", "subject", "").status);
        assertEquals(MailStore.Status.EMPTY, mail.deliver(bob, alice, "Alice", "USER", null, null).status);
        assertEquals(MailStore.Status.TOO_LONG,
            mail.deliver(bob, alice, "Alice", "USER", "s".repeat(MailStore.MAX_SUBJECT + 1), "body").status);
        assertEquals(MailStore.Status.TOO_LONG,
            mail.deliver(bob, alice, "Alice", "USER", "subject", "b".repeat(MailStore.MAX_BODY + 1)).status);
        assertEquals(0, mail.inbox(bob, null).size());
    }

    @Test
    public void aMailboxHoldsAtMostTwoHundredMails() throws SQLException {
        for (int i = 0; i < MailStore.MAX_INBOX; i++) {
            assertEquals(MailStore.Status.DELIVERED, mail.deliver(bob, alice, "Alice", "USER", "m" + i, "x").status);
        }
        assertEquals(MailStore.Status.MAILBOX_FULL, mail.deliver(bob, alice, "Alice", "USER", "one too many", "x").status);
    }

    @Test
    public void mailExpiresAfterThirtyDaysExceptNonExpiringMail() throws SQLException {
        mail.deliver(bob, alice, "Alice", "USER", "old", "x");
        mail.deliver(bob, null, "!Support", MailStore.NON_EXPIRING, "keep", "x");
        now.addAndGet(MailStore.LIFETIME_MS - 1);
        assertEquals(2, mail.inbox(bob, null).size());
        now.addAndGet(2);
        List<MailStore.Mail> left = mail.inbox(bob, null);
        assertEquals(1, left.size());
        assertEquals("keep", left.get(0).subject);
    }

    @Test
    public void deletingAKnightDeletesItsMail() throws SQLException {
        mail.deliver(bob, alice, "Alice", "USER", "a", "x");
        new KnightStore(database).delete(new AccountStore(database, new PasswordHasher(1_000)).find("b").get().id, bob);
        assertEquals(0, mail.inbox(bob, null).size());
    }

    private static List<Long> ids(List<MailStore.Mail> mail) {
        return mail.stream().map(m -> m.id).toList();
    }
}
