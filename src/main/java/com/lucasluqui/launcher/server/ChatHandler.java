package com.lucasluqui.launcher.server;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.lucasluqui.launcher.server.db.KnightStore;
import com.threerings.crowd.chat.data.UserMessage;
import com.threerings.presents.data.InvocationMarshaller;
import com.threerings.presents.dobj.AttributeChangedEvent;
import com.threerings.presents.dobj.MessageEvent;
import com.threerings.presents.net.EventNotification;
import com.threerings.util.Name;

/**
 * Chat: speaking in the scene (service 20) and the party (31), whispers, away messages and the admin broadcast (the
 * chat service, 4). The game delivers a line from a {@code "crowd.chat"} message on the object it listens to: the scene
 * or party object for public lines, the player's own object for whispers. Muting and slash commands are the game's own.
 */
final class ChatHandler implements ServiceHandler {
    static final String MESSAGE_NAME = "crowd.chat";
    /** Longer lines are cut; the game itself limits what its chat box accepts. */
    static final int MAX_TEXT = 256;

    private static final int AWAY = 1;
    private static final int BROADCAST = 2;
    private static final int TELL = 3;
    private static final int SPEAK = 1;

    private final SessionContext session;

    ChatHandler(SessionContext session) {
        this.session = session;
    }

    /** Speaking in the scene the player is in. */
    ServiceHandler scene() {
        return (method, args, reqId, listener) -> method == SPEAK && speak(args, false);
    }

    /** Speaking to the party. */
    ServiceHandler party() {
        return (method, args, reqId, listener) -> method == SPEAK && speak(args, true);
    }

    /** The chat service: away, broadcast, tell. */
    @Override
    public boolean handle(int methodId, Object[] args, short reqId, InvocationMarshaller.ListenerMarshaller listener)
            throws IOException {
        if (session.services() == null || session.account() == null) return false;
        switch (methodId) {
            case AWAY:
                away(args.length > 0 && args[0] instanceof String ? (String)args[0] : null);
                return true;
            case BROADCAST:
                session.failRequest(reqId, "m.access_denied"); // there are no admins; the server console can broadcast
                return true;
            case TELL:
                return listener != null && tell(args, reqId);
            default:
                return false;
        }
    }

    private static String clean(String text) {
        if (text == null) return null;
        text = text.trim();
        if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT);
        return text.isEmpty() ? null : text;
    }

    private boolean speak(Object[] args, boolean toParty) throws IOException {
        if (session.services() == null || session.player() == null || args.length == 0 || !(args[0] instanceof String)) return false;
        String text = clean((String)args[0]);
        if (text == null) return true;
        byte mode = args.length > 1 && args[1] instanceof Number ? ((Number)args[1]).byteValue() : 0;
        if (mode < 0 || mode > 3) mode = 0; // default, think, emote, shout
        UserMessage line = new UserMessage(new Name(session.player().knight.toString()), null, text, mode);
        if (toParty) {
            session.party().send(new EventNotification(new MessageEvent(session.partyOid(), MESSAGE_NAME, new Object[]{line})));
            return true;
        }
        int place = session.currentPlaceOid();
        for (ClientSession other : session.onlineSessions()) {
            if (other.currentPlaceOid() == place) {
                other.send(new EventNotification(new MessageEvent(place, MESSAGE_NAME, new Object[]{line})));
            }
        }
        return true;
    }

    private void away(String message) throws IOException {
        String away = clean(message);
        session.player().awayMessage = away;
        session.send(new EventNotification(new AttributeChangedEvent(session.clientOid(), "awayMessage", away)));
    }

    /** A whisper to a knight by name; answered with how long they have been idle and their away message. */
    private boolean tell(Object[] args, short reqId) throws IOException {
        Name target = null;
        String text = null;
        for (Object arg : args) {
            if (arg instanceof Name && target == null) target = (Name)arg;
            else if (arg instanceof String && text == null) text = (String)arg;
        }
        text = clean(text);
        if (target == null || text == null) return false;
        try {
            KnightStore.Knight knight = session.services().knights.findByName(target.toString()).orElse(null);
            ClientSession recipient = knight == null ? null : session.onlineKnight(knight.id);
            if (recipient == null || recipient == session) {
                session.failRequest(reqId, "m.user_not_online");
                return true;
            }
            UserMessage line = new UserMessage(new Name(session.player().knight.toString()), null, text, (byte)0);
            recipient.send(new EventNotification(new MessageEvent(recipient.clientOid(), MESSAGE_NAME, new Object[]{line})));
            session.respond(reqId, Long.valueOf(0L), recipient.player().awayMessage);
        } catch (SQLException e) {
            System.out.println("[ClientSession-" + session.connectionId() + "][CHAT][ERROR] database: " + e);
            session.failRequest(reqId, "m.internal_error");
        }
        return true;
    }

    /** A line from a named speaker (the dungeon, ...) to these players only. */
    static void notice(java.util.Collection<ClientSession> sessions, String speaker, String text) {
        UserMessage line = new UserMessage(new Name(speaker), null, text, (byte)4);
        for (ClientSession each : new ArrayList<>(sessions)) {
            try {
                each.send(new EventNotification(new MessageEvent(each.clientOid(), MESSAGE_NAME, new Object[]{line})));
            } catch (IOException ignored) {
                // That player is going away.
            }
        }
    }

    /** A message from the server (console broadcast) to every player. */
    static void broadcast(java.util.Collection<ClientSession> sessions, String text) {
        UserMessage line = new UserMessage(new Name("Server"), null, text, (byte)4);
        List<ClientSession> everyone = new ArrayList<>(sessions);
        for (ClientSession each : everyone) {
            try {
                each.send(new EventNotification(new MessageEvent(each.clientOid(), MESSAGE_NAME, new Object[]{line})));
            } catch (IOException ignored) {
                // That player is going away.
            }
        }
    }
}
