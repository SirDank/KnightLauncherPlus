package com.lucasluqui.launcher.server;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.lucasluqui.launcher.server.db.FriendStore;
import com.lucasluqui.launcher.server.db.KnightStore;
import com.lucasluqui.launcher.server.db.MailStore;
import com.threerings.projectx.uplink.client.UplinkService;
import com.threerings.projectx.uplink.data.FriendRequest;
import com.threerings.projectx.uplink.data.MailAttachment;
import com.threerings.projectx.uplink.data.MailType;
import com.threerings.projectx.uplink.data.ReceivedMail;
import com.threerings.projectx.uplink.data.SentMail;
import com.threerings.util.Name;

/**
 * The game's mail window, answered from the database: read the inbox, mark mail read, delete it and send it to
 * another knight. Method ids and reply shapes are the game's own (see the {@code UplinkMarshaller}). Mail without
 * attachments only; asking for an attachment is refused. It only decides what to answer; the session sends it.
 */
final class UplinkMailService {
    static final int CLAIM_AND_DELETE = 1;
    static final int CLAIM = 2;
    static final int DELETE = 4;
    static final int SEND = 5;
    static final int GET = 6;
    static final int EMAIL_DATA = 12;
    static final int MARK_READ = 11;

    /** What to do about a request. */
    static final class Outcome {
        /** The game's message key when the request failed; null otherwise. */
        final String failure;
        /** What a result listener receives (the inbox, ...); null for a plain confirmation. */
        final Object result;
        /** Mail ids that are read now (or gone): the session removes their "new mail" entries. */
        final long[] cleared;
        /** The mail just sent to another knight, to be pushed to them if they are online. */
        final MailStore.Mail delivered;
        /** The knight who just became a friend (a friend request was accepted); 0 for none. */
        final long befriended;

        private Outcome(String failure, Object result, long[] cleared, MailStore.Mail delivered) {
            this(failure, result, cleared, delivered, 0);
        }

        private Outcome(String failure, Object result, long[] cleared, MailStore.Mail delivered, long befriended) {
            this.befriended = befriended;
            this.failure = failure;
            this.result = result;
            this.cleared = cleared;
            this.delivered = delivered;
        }

        static Outcome failed(String key) { return new Outcome(key, null, new long[0], null); }
        static Outcome confirmed() { return new Outcome(null, null, new long[0], null); }
        static Outcome confirmed(long[] cleared, MailStore.Mail delivered) { return new Outcome(null, null, cleared, delivered); }
        static Outcome befriended(long mailId, long knightId) {
            return new Outcome(null, null, new long[]{mailId}, null, knightId);
        }
        static Outcome answered(Object result) { return new Outcome(null, result, new long[0], null); }
    }

    private final MailStore mail;
    private final KnightStore knights;
    private final FriendStore friends;

    UplinkMailService(ServerServices services) {
        this.friends = services.friends;
        this.mail = services.mail;
        this.knights = services.knights;
    }

    /** @return The outcome, or null for a method this service leaves to the generic reply. */
    Outcome handle(long knightId, String knightName, int method, Object[] args) throws SQLException {
        switch (method) {
            case GET:
                return Outcome.answered(inbox(knightId, args.length > 0 && args[0] instanceof long[] ? (long[]) args[0] : null));
            case MARK_READ: {
                long[] ids = args.length > 0 && args[0] instanceof long[] ? (long[]) args[0] : new long[0];
                mail.markRead(knightId, ids);
                return Outcome.confirmed(ids, null);
            }
            case DELETE: {
                long id = args.length > 0 && args[0] instanceof Number ? ((Number) args[0]).longValue() : 0;
                return mail.delete(knightId, id) ? Outcome.confirmed(new long[]{id}, null) : Outcome.failed("m.access_denied");
            }
            case SEND:
                return args.length > 0 && args[0] instanceof SentMail ? send(knightId, knightName, (SentMail) args[0])
                    : Outcome.failed("m.internal_error");
            case CLAIM:
            case CLAIM_AND_DELETE:
                return claim(knightId, args.length > 0 && args[0] instanceof Number ? ((Number) args[0]).longValue() : 0);
            case EMAIL_DATA: {
                UplinkService.AccountEmailData data = new UplinkService.AccountEmailData();
                data.email = "";
                data.validated = true;
                data.subscribed = true;
                return Outcome.answered(data);
            }
            default:
                return null;
        }
    }

    /** Claiming is accepting a friend request; there are no other attachments yet. */
    private Outcome claim(long knightId, long mailId) throws SQLException {
        MailStore.Mail stored = mail.inbox(knightId, new long[]{mailId}).stream().findFirst().orElse(null);
        if (stored == null || !MailType.FRIEND.name().equals(stored.type) || stored.attachKnightId == null) {
            return Outcome.failed("m.access_denied");
        }
        long requester = stored.attachKnightId;
        if (friends.areFriends(knightId, requester)) {
            mail.delete(knightId, mailId);
            return Outcome.confirmed(new long[]{mailId}, null);
        }
        if (!friends.add(knightId, requester, System.currentTimeMillis())) return Outcome.failed("e.maxfriends_invitee");
        mail.delete(knightId, mailId);
        return Outcome.befriended(mailId, requester);
    }

    private List<ReceivedMail> inbox(long knightId, long[] ids) throws SQLException {
        List<ReceivedMail> received = new ArrayList<>();
        for (MailStore.Mail stored : mail.inbox(knightId, ids)) received.add(toReceived(stored));
        return received;
    }

    static ReceivedMail toReceived(MailStore.Mail stored) {
        MailAttachment attachment = stored.attachKnightId == null ? null : new FriendRequest(stored.attachKnightId.intValue());
        return new ReceivedMail(stored.id, new Name(stored.senderName), stored.unread, false, stored.sent, stored.subject,
            stored.body, attachment, MailType.valueOf(stored.type));
    }

    private Outcome send(long knightId, String knightName, SentMail request) throws SQLException {
        boolean hasAttachment = (request.itemIds != null && !request.itemIds.isEmpty()) || request.crowns > 0 || request.energy > 0;
        if (hasAttachment) return Outcome.failed("m.access_denied");
        String recipientName = request.recipient == null ? "" : request.recipient.toString();
        KnightStore.Knight recipient = knights.findByName(recipientName).orElse(null);
        if (recipient == null) return Outcome.failed("m.unknown_knight");

        MailStore.Delivery delivery = mail.deliver(recipient.id, knightId, knightName, MailType.USER.name(), request.subject, request.body);
        switch (delivery.status) {
            case DELIVERED:
                return Outcome.confirmed(new long[0], delivery.mail);
            case TOO_LONG:
                return Outcome.failed("e.message_too_long");
            case MAILBOX_FULL:
                return Outcome.failed("m.inventory_full");
            default:
                return Outcome.failed("m.access_denied");
        }
    }
}
