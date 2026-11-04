package com.lucasluqui.launcher.server;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

import com.lucasluqui.launcher.server.db.KnightStore;
import com.threerings.presents.data.InvocationMarshaller;
import com.threerings.presents.dobj.EntryAddedEvent;
import com.threerings.presents.dobj.EntryRemovedEvent;
import com.threerings.projectx.dungeon.data.DungeonCodes;
import com.threerings.projectx.social.data.Notification;
import com.threerings.util.Name;

/**
 * The party service: invite a knight, answer an invitation (the game sends that through the social service), leave,
 * and boot a member. The party's first member (who has been in it longest) is its leader.
 */
final class PartyHandler implements ServiceHandler {
    private static final int BOOT = 1;
    private static final int INVITE = 2;
    private static final int LEAVE = 3;
    private static final int REVIVE = 4;
    static final String INVITE_KEY_PREFIX = "PARTY_INV:";

    private final SessionContext session;

    PartyHandler(SessionContext session) {
        this.session = session;
    }

    @Override
    public boolean handle(int methodId, Object[] args, short reqId, InvocationMarshaller.ListenerMarshaller listener)
            throws IOException {
        if (listener == null || session.services() == null || session.account() == null) return false;
        try {
            switch (methodId) {
                case INVITE:
                    invite(args, reqId, listener);
                    return true;
                case LEAVE:
                    session.confirm(reqId, listener);
                    session.joinParty(session.scenes().parties().create());
                    // A dungeon belongs to the party that went in: whoever leaves it goes back to town.
                    if (session.scenes().dungeons().isDungeon(session.self().currentSceneId())) {
                        session.self().travelTo(SceneManager.DEFAULT_SCENE_ID);
                    }
                    return true;
                case BOOT:
                    boot(args, reqId, listener);
                    return true;
                case REVIVE:
                    session.confirm(reqId, listener);
                    return true;
                default:
                    return false;
            }
        } catch (SQLException e) {
            System.out.println("[ClientSession-" + session.connectionId() + "][PARTY][ERROR] database: " + e);
            session.failRequest(reqId, "m.internal_error");
            return true;
        }
    }

    private static String firstName(Object[] args) {
        for (Object arg : args) if (arg instanceof Name) return arg.toString();
        return "";
    }

    private ClientSession onlineByName(String name) throws SQLException {
        KnightStore.Knight knight = session.services().knights.findByName(name).orElse(null);
        return knight == null ? null : session.onlineKnight(knight.id);
    }

    private void invite(Object[] args, short reqId, InvocationMarshaller.ListenerMarshaller listener)
            throws IOException, SQLException {
        ClientSession them = onlineByName(firstName(args));
        Party party = session.party();
        if (them == null || them == session || them.player() == null) {
            session.failRequest(reqId, "m.user_not_online");
            return;
        }
        String key = Notification.PartyInvite.C(null, party.oid());
        if (them.party() == party) {
            session.failRequest(reqId, "m.already_in_party");
        } else if (them.party().size() > 1) {
            session.failRequest(reqId, "m.already_in_party");
        } else if (them.player().notifications.f(key) != null) {
            session.failRequest(reqId, "m.already_invited");
        } else if (party.size() >= session.scenes().parties().maxSize()) {
            session.failRequest(reqId, "m.party_full");
        } else {
            List<ClientSession> members = party.members();
            Name[] names = new Name[members.size()];
            for (int i = 0; i < names.length; i++) names[i] = new Name(members.get(i).player().knight.toString());
            Notification.PartyInvite invite = new Notification.PartyInvite(null, party.oid(),
                DungeonCodes.Difficulty.values()[0], names, null, 0, 0, null, Math.max(0, members.indexOf(session)));
            session.confirm(reqId, listener);
            SocialHandler.push(them, new EntryAddedEvent<>(them.clientOid(), "notifications", invite));
        }
    }

    /**
     * The answer to a party invitation (the game sends it as social service method 5 with the notification's key and
     * 1 for accept, 0 for decline). Other notifications are just cleared.
     */
    void answerInvite(Object[] args, short reqId, InvocationMarshaller.ListenerMarshaller listener) throws IOException {
        String key = null;
        int choice = 0;
        for (Object arg : args) {
            if (arg instanceof String && key == null) key = (String)arg;
            else if (arg instanceof Number) choice = ((Number)arg).intValue();
        }
        if (key == null || session.player().notifications.f(key) == null) {
            session.confirm(reqId, listener);
            return;
        }
        Party target = null;
        String failure = null;
        if (choice == 1 && key.startsWith(INVITE_KEY_PREFIX)) {
            try {
                target = session.scenes().parties().find(Integer.parseInt(key.substring(key.lastIndexOf(':') + 1)));
            } catch (NumberFormatException ignored) {
                // Not one of ours: handled as a decline.
            }
            if (target == null) failure = "m.party_dissolved";
            else if (target.size() >= session.scenes().parties().maxSize()) failure = "m.party_full";
        }
        if (failure != null) {
            session.failRequest(reqId, failure);
        } else {
            session.confirm(reqId, listener);
        }
        SocialHandler.push(session, new EntryRemovedEvent<>(session.clientOid(), "notifications", key));
        if (failure == null && target != null) session.joinParty(target);
    }

    private void boot(Object[] args, short reqId, InvocationMarshaller.ListenerMarshaller listener) throws IOException {
        Party party = session.party();
        String name = firstName(args);
        if (party.leader() != session) {
            session.failRequest(reqId, "m.access_denied");
            return;
        }
        for (ClientSession member : party.members()) {
            if (member != session && member.player().knight.toString().equalsIgnoreCase(name)) {
                session.confirm(reqId, listener);
                member.joinParty(session.scenes().parties().create());
                return;
            }
        }
        session.failRequest(reqId, "m.unknown_knight|" + name);
    }
}
