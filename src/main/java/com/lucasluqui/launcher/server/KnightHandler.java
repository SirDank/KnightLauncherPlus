package com.lucasluqui.launcher.server;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.common.collect.Sets;
import com.lucasluqui.launcher.server.db.KnightStore;
import com.threerings.presents.data.InvocationMarshaller;
import com.threerings.presents.dobj.AttributeChangedEvent;
import com.threerings.presents.net.EventNotification;
import com.threerings.projectx.data.AvailableKnight;
import com.threerings.projectx.data.PlayerObject;
import com.threerings.util.Name;

/** The knight service: choosing, creating and deleting knights, and checking a name before creating one. */
final class KnightHandler implements ServiceHandler {
    private static final int CHOOSE = 4;
    private static final int CREATE = 5;
    private static final int DELETE = 6;
    private static final int CHECK_NAME = 7;

    private final SessionContext session;
    private final MailHandler mail;

    KnightHandler(SessionContext session, MailHandler mail) {
        this.session = session;
        this.mail = mail;
    }

    /** @return Whether the request was handled here (otherwise the generic reply, or for choosing the usual flow, applies). */
    @Override
    public boolean handle(int methodId, Object[] args, short reqId, InvocationMarshaller.ListenerMarshaller listener)
            throws IOException {
        if (listener == null || session.services() == null || session.account() == null) return false;
        try {
            switch (methodId) {
                case CHOOSE:
                    return choose(args, reqId);
                case CREATE:
                    create(args, reqId, listener);
                    return true;
                case DELETE:
                    delete(args, reqId, listener);
                    return true;
                case CHECK_NAME:
                    checkName(args, reqId, listener);
                    return true;
                default:
                    return false;
            }
        } catch (SQLException e) {
            System.out.println("[ClientSession-" + session.connectionId() + "][KNIGHT][ERROR] database: " + e);
            session.failRequest(reqId, "m.internal_error");
            return true;
        }
    }

    private KnightStore.Knight own(Name name) {
        for (KnightStore.Knight knight : session.knights()) {
            if (name != null && knight.name.equalsIgnoreCase(name.toString())) return knight;
        }
        return null;
    }

    /** Picks the knight to play. Returns false to let the usual flow (entering Haven) carry on with the new knight. */
    private boolean choose(Object[] args, short reqId) throws IOException {
        Name wanted = ClientSession.findNameArgument(args);
        KnightStore.Knight knight = own(wanted);
        if (knight == null) {
            System.out.println("[ClientSession-" + session.connectionId() + "][KNIGHT] " + session.account().username
                + " asked for a knight that is not theirs: " + wanted);
            session.failRequest(reqId, "m.unknown_knight");
            return true;
        }
        select(knight);
        return false;
    }

    /** Makes this the knight in play: names, gear, mailbox. */
    private void select(KnightStore.Knight knight) throws IOException {
        PlayerObject player = session.player();
        int oid = session.clientOid();
        saveState(); // the knight being left
        session.social().announce(true);
        player.knight = new Name(knight.name);
        player.knightId = (int)knight.id;
        session.send(new EventNotification(new AttributeChangedEvent(oid, "knight", player.knight)));
        session.send(new EventNotification(new AttributeChangedEvent(oid, "knightId", Integer.valueOf(player.knightId))));
        loadState();
        session.social().load();
        session.send(new EventNotification(new AttributeChangedEvent(oid, "friends", player.friends)));
        session.social().announce(false);
        session.send(new EventNotification(new AttributeChangedEvent(oid, "crowns", Integer.valueOf(player.crowns))));
        session.send(new EventNotification(new AttributeChangedEvent(oid, "items", player.items)));
        session.send(new EventNotification(new AttributeChangedEvent(oid, "equipment", player.equipment.clone())));
        session.send(new EventNotification(new AttributeChangedEvent(oid, "quickbar", player.quickbar.clone())));
        try { // another knight, another mailbox
            player.newMailInfo = mail.newMailEntries();
            session.send(new EventNotification(new AttributeChangedEvent(oid, "newMailInfo", player.newMailInfo)));
        } catch (SQLException e) {
            System.out.println("[ClientSession-" + session.connectionId() + "][MAIL][WARNING] could not load new mail: " + e.getMessage());
        }
    }

    /** Puts the crowns and items of the knight in play on the player object. */
    void loadState() {
        session.inventory().load();
    }

    /** Saves the crowns and gear of the knight in play. */
    void saveState() {
        session.inventory().save();
    }

    private Name nameArgument(Object[] args) {
        // createKnight(Name account, Name knight, ...): the knight's name is the second Name when there are two.
        List<Name> names = new ArrayList<>();
        for (Object arg : args) if (arg instanceof Name) names.add((Name)arg);
        return names.isEmpty() ? null : names.get(names.size() > 1 ? 1 : 0);
    }

    private void create(Object[] args, short reqId, InvocationMarshaller.ListenerMarshaller listener)
            throws IOException, SQLException {
        Name requested = nameArgument(args);
        int color = SceneManager.DEFAULT_PLAYER_COLOR;
        for (Object arg : args) {
            if (arg instanceof Integer && ((Integer)arg) != 0) {
                color = (Integer)arg;
                break;
            }
        }
        System.out.println("[ClientSession-" + session.connectionId() + "][KNIGHT] create name=" + requested + " color=" + color
            + " args=" + Arrays.toString(args));
        KnightStore.Result result = session.services().knights.create(session.account().id,
            requested == null ? "" : requested.toString(), color);
        switch (result.status) {
            case CREATED:
                refreshCards();
                session.confirm(reqId, listener);
                return;
            case NAME_TAKEN:
                session.failRequest(reqId, "m.name_in_use");
                return;
            case INVALID_NAME:
                session.failRequest(reqId, result.error);
                return;
            default:
                session.failRequest(reqId, "m.access_denied"); // too many knights
        }
    }

    private void delete(Object[] args, short reqId, InvocationMarshaller.ListenerMarshaller listener)
            throws IOException, SQLException {
        KnightStore.Knight knight = own(ClientSession.findNameArgument(args));
        if (knight == null) {
            session.failRequest(reqId, "m.unknown_knight");
            return;
        }
        session.services().knights.delete(session.account().id, knight.id);
        refreshCards();
        session.confirm(reqId, listener);
    }

    private void checkName(Object[] args, short reqId, InvocationMarshaller.ListenerMarshaller listener)
            throws IOException, SQLException {
        String name = null;
        for (Object arg : args) if (arg instanceof String) { name = (String)arg; break; }
        String error = KnightStore.nameError(name == null ? null : name.trim());
        if (error != null) {
            session.failRequest(reqId, error);
        } else if (session.services().knights.findByName(name).isPresent()) {
            session.failRequest(reqId, "m.name_in_use");
        } else {
            session.confirm(reqId, listener);
        }
    }

    /** The character selection cards for the account's knights (a single card per knight). */
    AvailableKnight[] cards() {
        List<KnightStore.Knight> knights = session.knights();
        AvailableKnight[] cards = new AvailableKnight[knights.size()];
        for (int i = 0; i < cards.length; i++) {
            KnightStore.Knight owned = knights.get(i);
            cards[i] = new AvailableKnight((int)owned.id, new Name(owned.name), 10, "Developers", 3600,
                session.scenes().createPlayerModel(session.player()), Sets.newHashSet());
        }
        return cards;
    }

    /** Reloads the account's knights and tells the game, which redraws its character selection from them. */
    private void refreshCards() throws IOException, SQLException {
        session.setKnights(session.services().knights.list(session.account().id));
        AvailableKnight[] cards = cards();
        session.player().availableKnights = cards;
        session.send(new EventNotification(new AttributeChangedEvent(session.clientOid(), "availableKnights", cards)));
    }
}
