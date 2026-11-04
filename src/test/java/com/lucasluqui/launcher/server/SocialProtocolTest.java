package com.lucasluqui.launcher.server;

import com.threerings.presents.dobj.DEvent;
import com.threerings.presents.dobj.EntryAddedEvent;
import com.threerings.presents.dobj.EntryRemovedEvent;
import com.threerings.projectx.data.ProjectXAuthResponseData;
import com.threerings.projectx.social.data.FriendEntry;
import com.threerings.projectx.uplink.data.FriendRequest;
import com.threerings.projectx.uplink.data.ReceivedMail;
import com.threerings.util.Name;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Friends over the wire: a request travels as mail, claiming it makes both knights friends. */
public class SocialProtocolTest {
    private static final int BEFRIEND = 1;
    private static final int UNFRIEND = 6;
    private static final int GET_MAIL = 6;
    private static final int CLAIM = 2;

    private SpiralServer server;
    private int port;
    private String key;
    private final String run = letters(6);

    private static String letters(int count) {
        Random random = new Random();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) text.append((char) ('a' + random.nextInt(26)));
        return text.toString();
    }

    @BeforeEach
    public void start() throws Exception {
        SpiralServerTest.assumeGameData();
        port = SpiralServerTest.freePort();
        server = new SpiralServer(port, false);
        server.start();
        key = ServerKeys.loadOrCreate(ServerKeys.keyFile(new File(ServerNet.GAME_DIR))).publicKeyProperty();
    }

    @AfterEach
    public void stop() {
        if (server != null) server.stop();
    }

    private TestClient signedIn(String user) throws Exception {
        TestClient client = TestClient.connect(port);
        ProjectXAuthResponseData answer = client.login(key, user, "pw");
        assertEquals("success", answer.code);
        client.awaitBootstrap();
        return client;
    }

    /** An account that already has a knight (named like the account), as the game would create it. */
    private long knightWithAccount(String user) throws Exception {
        long account = server.services().accounts.signIn(user, com.samskivert.util.ay.aH("pw")).account.id;
        return server.services().knights.create(account, user, 0).knight.id;
    }

    private String knightOf(String user) throws Exception {
        return server.services().knights.list(server.services().accounts.find(user).get().id).get(0).name;
    }

    @SuppressWarnings("unchecked")
    private ReceivedMail firstMail(TestClient client) throws Exception {
        TestClient.Reply reply = client.invoke(ClientSession.UPLINK_SERVICE_CODE, GET_MAIL, (Object) null);
        assertFalse(reply.failed, reply.message);
        return ((List<ReceivedMail>) reply.values[0]).get(0);
    }

    @Test
    public void aRequestIsAcceptedByClaimingItsMailAndBothSidesGetTheFriend() throws Exception {
        String a = "Askr" + run, b = "Targ" + run;
        try (TestClient one = signedIn(a); TestClient two = signedIn(b)) {
            TestClient.Reply sent = one.invoke(ClientSession.SOCIAL_SERVICE_CODE, BEFRIEND, new Name(knightOf(b)), "Hello", "Let us be friends");
            assertFalse(sent.failed, sent.message);
            two.awaitNewMail(5);
            ReceivedMail mail = firstMail(two);
            assertTrue(mail.attachment instanceof FriendRequest);
            assertEquals(knightOf(a), mail.sender.toString());

            TestClient.Reply claimed = two.invoke(ClientSession.UPLINK_SERVICE_CODE, CLAIM, Long.valueOf(mail.id));
            assertFalse(claimed.failed, claimed.message);
            DEvent mine = two.awaitSetEvent("friends", 5);
            assertTrue(mine instanceof EntryAddedEvent);
            assertEquals(knightOf(a), ((FriendEntry) ((EntryAddedEvent<?>) mine).xZ()).name.toString());
            DEvent theirs = one.awaitSetEvent("friends", 5);
            assertEquals(knightOf(b), ((FriendEntry) ((EntryAddedEvent<?>) theirs).xZ()).name.toString());
        }
    }

    @Test
    public void unfriendingRemovesTheEntryFromBothSides() throws Exception {
        String a = "Ufra" + run, b = "Ufrb" + run;
        long knightA = knightWithAccount(a);
        long knightB = knightWithAccount(b);
        server.services().friends.add(knightA, knightB, 1);
        try (TestClient one = signedIn(a); TestClient two = signedIn(b)) {
            TestClient.Reply reply = one.invoke(ClientSession.SOCIAL_SERVICE_CODE, UNFRIEND, new Name(knightOf(b)));
            assertFalse(reply.failed, reply.message);
            assertTrue(one.awaitSetEvent("friends", 5) instanceof EntryRemovedEvent);
            assertTrue(two.awaitSetEvent("friends", 5) instanceof EntryRemovedEvent);
            assertFalse(server.services().friends.areFriends(knightA, knightB));
        }
    }

    @Test
    public void requestsThatCannotWorkFailWithTheGamesMessages() throws Exception {
        String a = "Errs" + run, b = "Errt" + run;
        try (TestClient one = signedIn(a); TestClient two = signedIn(b)) {
            assertEquals("m.friend_self", one.invoke(ClientSession.SOCIAL_SERVICE_CODE, BEFRIEND, new Name(knightOf(a)), "s", "m").message);
            assertTrue(one.invoke(ClientSession.SOCIAL_SERVICE_CODE, BEFRIEND, new Name("Nobody Known"), "s", "m").message
                .startsWith("m.unknown_knight"));
            assertFalse(one.invoke(ClientSession.SOCIAL_SERVICE_CODE, BEFRIEND, new Name(knightOf(b)), "s", "m").failed);
            assertTrue(one.invoke(ClientSession.SOCIAL_SERVICE_CODE, BEFRIEND, new Name(knightOf(b)), "s", "m").message
                .startsWith("m.friend_request_pending"));
        }
    }

    @Test
    public void friendsAreSeenComingOnlineAndLeaving() throws Exception {
        String a = "Prsa" + run, b = "Prsb" + run;
        long knightA = knightWithAccount(a);
        long knightB = knightWithAccount(b);
        server.services().friends.add(knightA, knightB, 1);
        try (TestClient one = signedIn(a)) {
            try (TestClient two = signedIn(b)) {
                DEvent online = one.awaitSetEvent("friends", 5);
                FriendEntry entry = (FriendEntry) ((com.threerings.presents.dobj.EntryEvent<?>) online).xZ();
                assertEquals(knightOf(b), entry.name.toString());
                assertTrue(entry.whereabouts.YO(), "should show as online");
            }
            // The friend leaving shows as offline.
            DEvent gone = one.awaitSetEvent("friends", 5);
            while (((FriendEntry) ((com.threerings.presents.dobj.EntryEvent<?>) gone).xZ()).whereabouts.YO()) {
                gone = one.awaitSetEvent("friends", 5);
            }
        }
    }
}
