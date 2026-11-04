package com.lucasluqui.launcher.server;

import java.io.IOException;
import java.util.List;

import com.lucasluqui.launcher.server.db.AccountStore;
import com.lucasluqui.launcher.server.db.KnightStore;
import com.threerings.presents.data.InvocationMarshaller;
import com.threerings.projectx.data.PlayerObject;

/**
 * What a service handler may use of the session it serves: who is signed in, the player object the game mirrors, and
 * ways to answer. The session implements it; the handlers hold no connection details of their own.
 */
interface SessionContext {
    /** Accounts, knights, mail and the key; null for a session without accounts (tests, tools). */
    ServerServices services();

    int connectionId();

    /** The object id of the player object (the "client oid"). */
    int clientOid();

    /** The player object; null until the player has signed in. */
    PlayerObject player();

    SceneManager scenes();

    AccountStore.Account account();

    void setAccount(AccountStore.Account account);

    List<KnightStore.Knight> knights();

    void setKnights(List<KnightStore.Knight> knights);

    /** Sends a message to the game. */
    void send(Object message) throws IOException;

    /** Answers a request with the failure the game shows as {@code messageKey}. */
    void failRequest(short requestId, String messageKey) throws IOException;

    /** Answers a request with a plain confirmation (or an empty result, for a result listener). */
    void confirm(short requestId, InvocationMarshaller.ListenerMarshaller listener) throws IOException;

    /** Answers a request successfully with these result values. */
    void respond(short requestId, Object... values) throws IOException;

    /** The object id of the place (scene) the player is in. */
    int currentPlaceOid();

    /** The object id of the player's party. */
    int partyOid();

    /** Every signed-in session. */
    java.util.Collection<ClientSession> onlineSessions();

    /** The player's party (alone in it at first). */
    Party party();

    /** Moves the player to another party (leaving the one they are in) and tells the game. */
    void joinParty(Party party) throws IOException;

    /** Party invitations and leaving. */
    PartyHandler partyHandler();

    /** Crowns and items of the knight in play. */
    KnightInventory inventory();

    /** Friends of the knight in play. */
    SocialHandler social();

    /** This session itself, for the parts of the server that need more than the handlers do. */
    ClientSession self();

    /** Ends the session. */
    void close();

    /** The session of a signed-in player who plays this knight, or null. */
    ClientSession onlineKnight(long knightId);
}
