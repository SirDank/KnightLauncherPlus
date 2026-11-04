package com.lucasluqui.launcher.server.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * In-game mail. Every operation is scoped to the knight whose mailbox it is, so one knight can never read, mark or
 * delete another knight's mail by guessing an id.
 */
public final class MailStore {

    public enum Status { DELIVERED, EMPTY, TOO_LONG, MAILBOX_FULL }

    public static final class Mail {
        public final long id;
        public final long recipientKnightId;
        public final Long senderKnightId;
        public final String senderName;
        public final String type;
        public final long sent;
        public final String subject;
        public final String body;
        public final boolean unread;
        /** The knight a friend request is about (the one who sent it); null for other mail. */
        public final Long attachKnightId;

        Mail(long id, long recipientKnightId, Long senderKnightId, String senderName, String type, long sent,
             String subject, String body, boolean unread, Long attachKnightId) {
            this.attachKnightId = attachKnightId;
            this.id = id;
            this.recipientKnightId = recipientKnightId;
            this.senderKnightId = senderKnightId;
            this.senderName = senderName;
            this.type = type;
            this.sent = sent;
            this.subject = subject;
            this.body = body;
            this.unread = unread;
        }
    }

    public static final class Delivery {
        public final Status status;
        /** The stored mail, for {@link Status#DELIVERED}; null otherwise. */
        public final Mail mail;

        Delivery(Status status, Mail mail) {
            this.status = status;
            this.mail = mail;
        }
    }

    public static final int MAX_SUBJECT = 100;
    public static final int MAX_BODY = 4_000;
    public static final int MAX_INBOX = 200;
    /** The game tells players that mail is deleted 30 days after it was sent. */
    public static final long LIFETIME_MS = 30L * 24 * 60 * 60 * 1000;
    /** Mail of this type never expires. */
    public static final String NON_EXPIRING = "NON_EXPIRING";

    private final Database database;
    private final LongSupplier clock;

    public MailStore(Database database) {
        this(database, System::currentTimeMillis);
    }

    MailStore(Database database, LongSupplier clock) {
        this.database = database;
        this.clock = clock;
    }

    /**
     * Puts a mail in a knight's mailbox.
     *
     * @param senderKnightId The sending knight, or null for system mail.
     * @param type A name of the game's MailType (USER, SYSTEM, ...).
     */
    public Delivery deliver(long recipientKnightId, Long senderKnightId, String senderName, String type,
                            String subject, String body) throws SQLException {
        return deliver(recipientKnightId, senderKnightId, senderName, type, subject, body, null);
    }

    /** As above; {@code attachKnightId} is the knight a friend request is about. */
    public Delivery deliver(long recipientKnightId, Long senderKnightId, String senderName, String type,
                            String subject, String body, Long attachKnightId) throws SQLException {
        String cleanSubject = subject == null ? "" : subject.trim();
        String cleanBody = body == null ? "" : body.trim();
        if (cleanSubject.isEmpty() || cleanBody.isEmpty()) return new Delivery(Status.EMPTY, null);
        if (cleanSubject.length() > MAX_SUBJECT || cleanBody.length() > MAX_BODY) return new Delivery(Status.TOO_LONG, null);

        return database.write(c -> {
            expire(c, recipientKnightId);
            try (PreparedStatement count = c.prepareStatement("SELECT COUNT(*) FROM mail WHERE recipient_knight_id = ?")) {
                count.setLong(1, recipientKnightId);
                try (ResultSet row = count.executeQuery()) {
                    row.next();
                    if (row.getInt(1) >= MAX_INBOX) return new Delivery(Status.MAILBOX_FULL, null);
                }
            }
            long sent = clock.getAsLong();
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO mail (recipient_knight_id, sender_knight_id, sender_name, type, sent, subject, body, attach_knight_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)", java.sql.Statement.RETURN_GENERATED_KEYS)) {
                insert.setLong(1, recipientKnightId);
                if (senderKnightId == null) insert.setNull(2, java.sql.Types.INTEGER); else insert.setLong(2, senderKnightId);
                insert.setString(3, senderName);
                insert.setString(4, type);
                insert.setLong(5, sent);
                insert.setString(6, cleanSubject);
                insert.setString(7, cleanBody);
                if (attachKnightId == null) insert.setNull(8, java.sql.Types.INTEGER); else insert.setLong(8, attachKnightId);
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    keys.next();
                    return new Delivery(Status.DELIVERED, new Mail(keys.getLong(1), recipientKnightId, senderKnightId,
                        senderName, type, sent, cleanSubject, cleanBody, true, attachKnightId));
                }
            }
        });
    }

    /**
     * The knight's mail, newest first.
     *
     * @param ids Only these (ones that are not the knight's are ignored), or null for the whole mailbox.
     */
    public List<Mail> inbox(long knightId, long[] ids) throws SQLException {
        return database.write(c -> {
            expire(c, knightId);
            StringBuilder sql = new StringBuilder(
                "SELECT id, recipient_knight_id, sender_knight_id, sender_name, type, sent, subject, body, unread, attach_knight_id"
                    + " FROM mail WHERE recipient_knight_id = ?");
            if (ids != null) {
                if (ids.length == 0) return new ArrayList<Mail>();
                sql.append(" AND id IN (").append("?,".repeat(ids.length), 0, ids.length * 2 - 1).append(")");
            }
            sql.append(" ORDER BY sent DESC, id DESC");
            List<Mail> mail = new ArrayList<>();
            try (PreparedStatement select = c.prepareStatement(sql.toString())) {
                select.setLong(1, knightId);
                if (ids != null) for (int i = 0; i < ids.length; i++) select.setLong(2 + i, ids[i]);
                try (ResultSet row = select.executeQuery()) {
                    while (row.next()) {
                        long id = row.getLong(1);
                        long recipient = row.getLong(2);
                        long sender = row.getLong(3);
                        Long senderKnightId = row.wasNull() ? null : sender; // wasNull() refers to the column read last
                        boolean unread = row.getInt(9) != 0;
                        long attach = row.getLong(10);
                        Long attachKnightId = row.wasNull() ? null : attach;
                        mail.add(new Mail(id, recipient, senderKnightId, row.getString(4),
                            row.getString(5), row.getLong(6), row.getString(7), row.getString(8), unread, attachKnightId));
                    }
                }
            }
            return mail;
        });
    }

    /** Whether the sender already has a friend request waiting in the recipient's mailbox. */
    public boolean friendRequestPending(long recipientKnightId, long senderKnightId) throws SQLException {
        return database.read(c -> {
            try (PreparedStatement select = c.prepareStatement(
                    "SELECT 1 FROM mail WHERE recipient_knight_id = ? AND sender_knight_id = ? AND type = 'FRIEND'")) {
                select.setLong(1, recipientKnightId);
                select.setLong(2, senderKnightId);
                try (ResultSet row = select.executeQuery()) {
                    return row.next();
                }
            }
        });
    }

    public int unreadCount(long knightId) throws SQLException {
        return database.read(c -> {
            try (PreparedStatement count = c.prepareStatement(
                    "SELECT COUNT(*) FROM mail WHERE recipient_knight_id = ? AND unread = 1")) {
                count.setLong(1, knightId);
                try (ResultSet row = count.executeQuery()) {
                    row.next();
                    return row.getInt(1);
                }
            }
        });
    }

    /** @return How many of the given mails were the knight's and unread. */
    public int markRead(long knightId, long[] ids) throws SQLException {
        if (ids == null || ids.length == 0) return 0;
        return database.write(c -> {
            try (PreparedStatement update = c.prepareStatement(
                    "UPDATE mail SET unread = 0 WHERE recipient_knight_id = ? AND unread = 1 AND id = ?")) {
                int changed = 0;
                for (long id : ids) {
                    update.setLong(1, knightId);
                    update.setLong(2, id);
                    changed += update.executeUpdate();
                }
                return changed;
            }
        });
    }

    /** @return Whether the mail was the knight's (and is now gone). */
    public boolean delete(long knightId, long id) throws SQLException {
        return database.write(c -> {
            try (PreparedStatement delete = c.prepareStatement("DELETE FROM mail WHERE recipient_knight_id = ? AND id = ?")) {
                delete.setLong(1, knightId);
                delete.setLong(2, id);
                return delete.executeUpdate() == 1;
            }
        });
    }

    private void expire(Connection c, long knightId) throws SQLException {
        try (PreparedStatement delete = c.prepareStatement(
                "DELETE FROM mail WHERE recipient_knight_id = ? AND type <> ? AND sent < ?")) {
            delete.setLong(1, knightId);
            delete.setString(2, NON_EXPIRING);
            delete.setLong(3, clock.getAsLong() - LIFETIME_MS);
            delete.executeUpdate();
        }
    }
}
