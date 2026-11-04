package com.lucasluqui.launcher.server;

import com.threerings.crowd.chat.data.UserMessage;
import com.threerings.presents.dobj.AttributeChangedEvent;
import com.threerings.presents.dobj.DEvent;
import com.threerings.presents.dobj.EntryAddedEvent;
import com.threerings.presents.dobj.EntryRemovedEvent;
import com.threerings.projectx.data.ProjectXAuthResponseData;
import com.threerings.projectx.social.data.Notification;
import com.threerings.util.Name;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parties over the wire: invite, accept, party chat, leave, boot and the size limit. */
public class PartyProtocolTest {
    private static final int BOOT = 1;
    private static final int INVITE = 2;
    private static final int LEAVE = 3;
    private static final int ANSWER = 5;

    private SpiralServer server;
    private int port;
    private String key;
    private final String run = letters(5);

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

    private final List<TestClient> open = new ArrayList<>();

    private TestClient signedIn(String user) throws Exception {
        TestClient client = TestClient.connect(port);
        open.add(client);
        ProjectXAuthResponseData answer = client.login(key, user, "pw");
        assertEquals("success", answer.code);
        client.awaitBootstrap();
        return client;
    }

    @AfterEach
    public void closeAll() {
        for (TestClient client : open) client.close();
    }

    private String knightOf(String user) throws Exception {
        return server.services().knights.list(server.services().accounts.find(user).get().id).get(0).name;
    }

    /** Invites {@code to} into {@code from}'s party and has them accept. */
    private void join(TestClient from, TestClient to, String toUser) throws Exception {
        TestClient.Reply invited = from.invoke(ClientSession.PARTY_SERVICE_CODE, INVITE, new Name(knightOf(toUser)));
        assertFalse(invited.failed, invited.message);
        EntryAddedEvent<?> entry = (EntryAddedEvent<?>) to.awaitSetEvent("notifications", 5);
        String inviteKey = ((Notification) entry.xZ()).getKey();
        assertTrue(inviteKey.startsWith("PARTY_INV:"));
        TestClient.Reply answered = to.invoke(ClientSession.SOCIAL_SERVICE_CODE, ANSWER, inviteKey, Integer.valueOf(1));
        assertFalse(answered.failed, answered.message);
    }

    private static DEvent awaitAttribute(TestClient client, String attribute) throws Exception {
        while (true) {
            Object message = client.receive(5);
            if (!(message instanceof com.threerings.presents.net.EventNotification)) continue;
            Object event = ((com.threerings.presents.net.EventNotification) message).yj();
            if (event instanceof AttributeChangedEvent && attribute.equals(((AttributeChangedEvent) event).getName())) {
                return (DEvent) event;
            }
        }
    }

    @Test
    public void anAcceptedInvitationPutsBothInOnePartyAndPartyChatReachesBoth() throws Exception {
        String a = "Lead" + run, b = "Join" + run;
        TestClient one = signedIn(a), two = signedIn(b);
        join(one, two, b);
        // The joiner is told to switch to the leader's party; the leader sees the new member.
        AttributeChangedEvent moved = (AttributeChangedEvent) awaitAttribute(two, "partyOid");
        assertEquals(Integer.valueOf(one.partyOidOf(server)), moved.getValue());
        assertTrue(one.awaitSetEvent("members", 5) instanceof EntryAddedEvent);

        one.call(ClientSession.PARTY_SPEAK_SERVICE_CODE, 1, "party time", Byte.valueOf((byte) 0));
        UserMessage heard = two.awaitChat(5);
        assertEquals("party time", heard.message);
        assertEquals("party time", one.awaitChat(5).message);
    }

    @Test
    public void leavingTellsTheOthersAndOnlyTheLeaderCanBoot() throws Exception {
        String a = "Boss" + run, b = "Memb" + run, c = "Thrd" + run;
        TestClient one = signedIn(a), two = signedIn(b), three = signedIn(c);
        join(one, two, b);
        one.awaitSetEvent("members", 5);
        join(one, three, c);
        one.awaitSetEvent("members", 5);
        two.awaitSetEvent("members", 5); // the third member joining, seen by the second

        assertEquals("m.access_denied", two.invoke(ClientSession.PARTY_SERVICE_CODE, BOOT, new Name(knightOf(c))).message);
        assertFalse(one.invoke(ClientSession.PARTY_SERVICE_CODE, BOOT, new Name(knightOf(c))).failed);
        assertTrue(one.awaitSetEvent("members", 5) instanceof EntryRemovedEvent);

        assertFalse(two.invoke(ClientSession.PARTY_SERVICE_CODE, LEAVE).failed);
        assertTrue(one.awaitSetEvent("members", 5) instanceof EntryRemovedEvent);
    }

    @Test
    public void invitationsThatCannotWorkFailWithTheGamesMessages() throws Exception {
        String a = "Inva" + run, b = "Invb" + run;
        TestClient one = signedIn(a);
        assertEquals("m.user_not_online", one.invoke(ClientSession.PARTY_SERVICE_CODE, INVITE, new Name("Nobody Here")).message);
        TestClient two = signedIn(b);
        assertFalse(one.invoke(ClientSession.PARTY_SERVICE_CODE, INVITE, new Name(knightOf(b))).failed);
        assertEquals("m.already_invited", one.invoke(ClientSession.PARTY_SERVICE_CODE, INVITE, new Name(knightOf(b))).message);
        assertEquals("m.user_not_online", one.invoke(ClientSession.PARTY_SERVICE_CODE, INVITE, new Name(knightOf(a))).message);
    }

    @Test
    public void aPartyFillsUpAtTheConfiguredSize() throws Exception {
        int size = server.configuredPartySize();
        String leaderUser = "Full" + run;
        TestClient leader = signedIn(leaderUser);
        for (int i = 1; i < size; i++) {
            String user = "Fm" + (char) ('a' + i) + run;
            join(leader, signedIn(user), user);
        }
        String extra = "Fx" + run;
        signedIn(extra);
        assertEquals("m.party_full", leader.invoke(ClientSession.PARTY_SERVICE_CODE, INVITE, new Name(knightOf(extra))).message);
    }
}
