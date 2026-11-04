package com.lucasluqui.launcher.server;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.lucasluqui.launcher.server.db.FriendStore;
import com.lucasluqui.launcher.server.db.KnightStore;
import com.lucasluqui.launcher.server.db.MailStore;
import com.threerings.presents.data.InvocationMarshaller;
import com.threerings.presents.dobj.DEvent;
import com.threerings.presents.dobj.DSet;
import com.threerings.presents.dobj.EntryAddedEvent;
import com.threerings.presents.dobj.EntryRemovedEvent;
import com.threerings.presents.dobj.EntryUpdatedEvent;
import com.threerings.presents.net.EventNotification;
import com.threerings.projectx.data.PlayerObject;
import com.threerings.projectx.social.data.FriendEntry;
import com.threerings.projectx.social.data.Whereabouts;
import com.threerings.projectx.uplink.data.MailType;
import com.threerings.util.Name;

/**
 * Friends: friend requests (sent as mail, accepted by claiming it, see {@link UplinkMailService}), removing friends,
 * and showing each friend where the others are. A friendship is mutual and stored in {@link FriendStore}.
 */
final class SocialHandler implements ServiceHandler {
    private static final int BEFRIEND = 1;
    private static final int JOIN = 2;
    private static final int UNFRIEND = 6;
    /** The game sends these too (Steam/Discord join, looking for group, notification answers); nothing to do. */
    private static final int ANSWER_NOTIFICATION = 5;
    private static final int IGNORED_LOW = 3;
    private static final int IGNORED_HIGH = 5;

    private final SessionContext session;

    SocialHandler(SessionContext session) {
        this.session = session;
    }

    @Override
    public boolean handle(int methodId, Object[] args, short reqId, InvocationMarshaller.ListenerMarshaller listener)
            throws IOException {
        if (listener == null || session.services() == null || session.account() == null) return false;
        try {
            if (methodId == BEFRIEND) {
                befriend(args, reqId);
            } else if (methodId == UNFRIEND) {
                unfriend(args, reqId);
            } else if (methodId == JOIN) {
                session.failRequest(reqId, "m.friend_unjoinable|" + firstName(args));
            } else if (methodId == ANSWER_NOTIFICATION) {
                session.partyHandler().answerInvite(args, reqId, listener);
            } else if (methodId >= IGNORED_LOW && methodId <= IGNORED_HIGH) {
                session.confirm(reqId, listener);
            } else {
                return false;
            }
        } catch (SQLException e) {
            System.out.println("[ClientSession-" + session.connectionId() + "][SOCIAL][ERROR] database: " + e);
            session.failRequest(reqId, "m.internal_error");
        }
        return true;
    }

    private static String firstName(Object[] args) {
        for (Object arg : args) if (arg instanceof Name) return arg.toString();
        return "";
    }

    private void befriend(Object[] args, short reqId) throws IOException, SQLException {
        ServerServices services = session.services();
        String wanted = firstName(args);
        String subject = null, message = null;
        for (Object arg : args) {
            if (arg instanceof String) {
                if (subject == null) subject = (String)arg; else if (message == null) message = (String)arg;
            }
        }
        KnightStore.Knight target = services.knights.findByName(wanted).orElse(null);
        long me = session.player().knightId;
        String myName = session.player().knight.toString();
        if (target == null) {
            session.failRequest(reqId, "m.unknown_knight|" + wanted);
        } else if (target.id == me) {
            session.failRequest(reqId, "m.friend_self");
        } else if (services.friends.areFriends(me, target.id)) {
            session.failRequest(reqId, "m.already_friend|" + target.name);
        } else if (services.friends.count(me) >= FriendStore.MAX_FRIENDS) {
            session.failRequest(reqId, "e.maxfriends_inviter|" + FriendStore.MAX_FRIENDS);
        } else if (services.friends.count(target.id) >= FriendStore.MAX_FRIENDS) {
            session.failRequest(reqId, "e.maxfriends_invitee");
        } else if (services.mail.friendRequestPending(target.id, me)) {
            session.failRequest(reqId, "m.friend_request_pending|" + target.name);
        } else {
            if (subject == null || subject.trim().isEmpty()) subject = "Friend request";
            if (message == null || message.trim().isEmpty()) message = myName + " would like to be your friend.";
            MailStore.Delivery delivery = services.mail.deliver(target.id, me, myName, MailType.FRIEND.name(),
                subject, message, me);
            if (delivery.status != MailStore.Status.DELIVERED) {
                session.failRequest(reqId, delivery.status == MailStore.Status.TOO_LONG ? "e.message_too_long" : "m.inventory_full");
                return;
            }
            ClientSession recipient = session.onlineKnight(target.id);
            if (recipient != null) recipient.mail().pushNewMail(delivery.mail);
            session.respond(reqId);
        }
    }

    private void unfriend(Object[] args, short reqId) throws IOException, SQLException {
        ServerServices services = session.services();
        KnightStore.Knight other = services.knights.findByName(firstName(args)).orElse(null);
        long me = session.player().knightId;
        if (other == null || !services.friends.remove(me, other.id)) {
            session.failRequest(reqId, "m.unknown_knight|" + firstName(args));
            return;
        }
        session.respond(reqId);
        removeEntry(session, other.name);
        ClientSession them = session.onlineKnight(other.id);
        if (them != null) removeEntry(them, session.player().knight.toString());
    }

    // ---- What friends see of each other.

    /** Where the knight in play is, as their friends see it. */
    Whereabouts whereabouts() {
        Whereabouts.Online here = new Whereabouts.Online();
        here.location = placeName(session.currentPlaceOid());
        here.zoneId = 1;
        here.instanceId = 1; // 0 would show the "in transit" icon
        return here;
    }

    private static String placeName(int placeOid) {
        return placeOid == SceneManager.TRAINING_PLACE_OID ? "Advanced Training" : "Haven";
    }

    private FriendEntry entryFor(FriendStore.Friend friend) {
        ClientSession online = session.onlineKnight(friend.knightId);
        Whereabouts where = online != null && online.player() != null
            ? online.social().whereabouts() : new Whereabouts.Offline(friend.lastSeen);
        return new FriendEntry(new Name(friend.name), (int)friend.knightId, friend.color, where);
    }

    /** The friends of the knight in play, for the player object. */
    DSet<FriendEntry> entries() throws SQLException {
        List<FriendEntry> entries = new ArrayList<>();
        for (FriendStore.Friend friend : session.services().friends.list(session.player().knightId)) {
            entries.add(entryFor(friend));
        }
        return new DSet<>(entries);
    }

    /** Puts the friends of the knight in play on the player object (after signing in or switching knights). */
    void load() {
        if (session.services() == null || session.account() == null || session.player() == null) return;
        try {
            session.player().friends = entries();
            session.player().whereabouts = whereabouts();
        } catch (SQLException e) {
            System.out.println("[ClientSession-" + session.connectionId() + "][SOCIAL][WARNING] could not load friends: " + e.getMessage());
            session.player().friends = new DSet<>();
        }
    }

    /** Tells the online friends of the knight in play where they are now (or that they are gone, when {@code gone}). */
    void announce(boolean gone) {
        if (session.services() == null || session.account() == null || session.player() == null) return;
        try {
            PlayerObject me = session.player();
            FriendStore.Friend self = new FriendStore.Friend(me.knightId, me.knight.toString(), me.color,
                System.currentTimeMillis());
            if (gone) session.services().knights.setLastSeen(me.knightId, self.lastSeen);
            else me.whereabouts = whereabouts();
            for (FriendStore.Friend friend : session.services().friends.list(me.knightId)) {
                ClientSession them = session.onlineKnight(friend.knightId);
                if (them == null || them == session || them.player() == null) continue;
                FriendEntry entry = new FriendEntry(new Name(self.name), (int)self.knightId, self.color,
                    gone ? new Whereabouts.Offline(self.lastSeen) : whereabouts());
                push(them, new EntryUpdatedEvent<>(them.clientOid(), "friends", entry));
            }
        } catch (SQLException e) {
            System.out.println("[ClientSession-" + session.connectionId() + "][SOCIAL][WARNING] presence: " + e.getMessage());
        }
    }

    /** A friendship was just made with this knight: both sides get the entry. */
    void friendAdded(long otherKnightId) {
        try {
            KnightStore.Knight other = session.services().knights.findById(otherKnightId).orElse(null);
            if (other == null) return;
            FriendStore.Friend friend = new FriendStore.Friend(other.id, other.name, other.color, System.currentTimeMillis());
            push(session, new EntryAddedEvent<>(session.clientOid(), "friends", entryFor(friend)));
            ClientSession them = session.onlineKnight(otherKnightId);
            if (them != null && them.player() != null) {
                PlayerObject me = session.player();
                FriendEntry mine = new FriendEntry(new Name(me.knight.toString()), me.knightId, me.color, whereabouts());
                push(them, new EntryAddedEvent<>(them.clientOid(), "friends", mine));
            }
        } catch (SQLException e) {
            System.out.println("[ClientSession-" + session.connectionId() + "][SOCIAL][WARNING] friend entry: " + e.getMessage());
        }
    }

    private static void removeEntry(SessionContext target, String friendName) throws IOException {
        push(target, new EntryRemovedEvent<>(target.clientOid(), "friends", new Name(friendName)));
    }

    /** Applies the event to the server's copy of the player object, then tells the game. */
    static void push(SessionContext target, DEvent event) {
        try {
            event.b(target.player());
            target.send(new EventNotification(event));
        } catch (IOException | RuntimeException e) {
            System.out.println("[SOCIAL][WARNING] could not update a friends list: " + e);
        }
    }
}
