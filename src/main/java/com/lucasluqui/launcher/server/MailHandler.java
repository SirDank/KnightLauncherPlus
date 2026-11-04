package com.lucasluqui.launcher.server;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.lucasluqui.launcher.server.db.MailStore;
import com.threerings.presents.data.InvocationMarshaller;
import com.threerings.presents.dobj.DSet;
import com.threerings.presents.dobj.EntryAddedEvent;
import com.threerings.presents.dobj.EntryRemovedEvent;
import com.threerings.presents.dobj.InvocationResponseEvent;
import com.threerings.presents.net.EventNotification;
import com.threerings.projectx.uplink.data.MailType;
import com.threerings.projectx.uplink.data.NewMailInfo;
import com.threerings.util.Name;

/** The game's mail window, backed by the database (see {@link UplinkMailService} for what each method does). */
final class MailHandler implements ServiceHandler {
    private final SessionContext session;
    /** Ids of the unread mail the game has been told about ("new mail" entries on the player object). */
    private final Set<Long> newMailIds = new HashSet<>();

    MailHandler(SessionContext session) {
        this.session = session;
    }

    @Override
    public boolean handle(int methodId, Object[] args, short reqId, InvocationMarshaller.ListenerMarshaller listener)
            throws IOException {
        ServerServices services = session.services();
        if (listener == null || services == null || session.account() == null) return false;
        try {
            UplinkMailService.Outcome outcome = services.uplinkMail.handle(
                session.player().knightId, session.player().knight.toString(), methodId, args);
            if (outcome == null) return false;
            if (outcome.failure != null) {
                session.failRequest(reqId, outcome.failure);
                return true;
            }
            for (long id : outcome.cleared) {
                if (this.newMailIds.remove(id)) {
                    session.send(new EventNotification(new EntryRemovedEvent<>(session.clientOid(), "newMailInfo", Long.valueOf(id))));
                }
            }
            if (outcome.result != null) {
                session.send(new EventNotification(new InvocationResponseEvent(session.clientOid(), reqId, (byte)1, new Object[]{outcome.result})));
            } else {
                session.confirm(reqId, listener);
            }
            if (outcome.befriended != 0) session.social().friendAdded(outcome.befriended);
            if (outcome.delivered != null) {
                ClientSession recipient = session.onlineKnight(outcome.delivered.recipientKnightId);
                if (recipient != null) recipient.mail().pushNewMail(outcome.delivered);
            }
            return true;
        } catch (SQLException e) {
            System.out.println("[ClientSession-" + session.connectionId() + "][MAIL][ERROR] database: " + e);
            session.failRequest(reqId, "m.internal_error");
            return true;
        }
    }

    /** Tells the game about mail that just arrived for the knight in play. */
    void pushNewMail(MailStore.Mail delivered) {
        try {
            if (session.player() == null || session.player().knightId != delivered.recipientKnightId) return;
            synchronized (this) {
                if (!this.newMailIds.add(delivered.id)) return;
                session.send(new EventNotification(new EntryAddedEvent<>(session.clientOid(), "newMailInfo",
                    new NewMailInfo(delivered.id, new Name(delivered.senderName), MailType.valueOf(delivered.type)))));
            }
        } catch (IOException e) {
            System.out.println("[ClientSession-" + session.connectionId() + "][MAIL][WARNING] could not announce new mail: " + e.getMessage());
        }
    }

    /** The unread mail of the knight in play, as the "new mail" entries the game shows its mail icon from. */
    DSet<NewMailInfo> newMailEntries() throws SQLException {
        this.newMailIds.clear();
        List<NewMailInfo> entries = new ArrayList<>();
        ServerServices services = session.services();
        if (services != null && session.account() != null) {
            for (MailStore.Mail stored : services.mail.inbox(session.player().knightId, null)) {
                if (!stored.unread) continue;
                entries.add(new NewMailInfo(stored.id, new Name(stored.senderName), MailType.valueOf(stored.type)));
                this.newMailIds.add(stored.id);
            }
        }
        return new DSet<>(entries);
    }
}
