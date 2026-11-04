package com.lucasluqui.launcher.server;

import com.threerings.projectx.data.ProjectXAuthResponseData;
import com.threerings.projectx.uplink.data.NewMailInfo;
import com.threerings.projectx.uplink.data.ReceivedMail;
import com.threerings.projectx.uplink.data.SentMail;
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

/** The in-game mail window over the wire: two players send, receive, read and delete mail. */
public class MailProtocolTest {
    private static final int UPLINK = 40;
    private static final int DELETE = 4;
    private static final int SEND = 5;
    private static final int GET = 6;
    private static final int MARK_READ = 11;

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

    /** Signs in as a new account, creates a knight with this name and plays it. */
    private TestClient playerWithKnight(String account, String knight) throws Exception {
        TestClient client = TestClient.connect(port);
        ProjectXAuthResponseData answer = client.login(key, account, "pw");
        assertEquals("success", answer.code);
        client.awaitBootstrap();
        assertFalse(client.invoke(ClientSession.KNIGHT_SERVICE_CODE, 5, new Name(account), new Name(knight), Integer.valueOf(257)).failed);
        assertFalse(client.invoke(ClientSession.KNIGHT_SERVICE_CODE, 4, new Name(knight), null).failed);
        return client;
    }

    private static SentMail mail(String recipient, String subject, String body) {
        SentMail mail = new SentMail();
        mail.recipient = new Name(recipient);
        mail.subject = subject;
        mail.body = body;
        return mail;
    }

    @SuppressWarnings("unchecked")
    private static List<ReceivedMail> inbox(TestClient client) throws Exception {
        TestClient.Reply reply = client.invoke(UPLINK, GET, (Object) null);
        assertFalse(reply.failed, "get mail failed: " + reply.message);
        return (List<ReceivedMail>) reply.values[0];
    }

    @Test
    public void mailArrivesWithItsSenderIsAnnouncedAndCanBeReadAndDeleted() throws Exception {
        String senderKnight = "Sender " + run;
        String readerKnight = "Reader " + run;
        try (TestClient sender = playerWithKnight("S" + run, senderKnight);
             TestClient reader = playerWithKnight("R" + run, readerKnight)) {
            assertTrue(inbox(reader).isEmpty());

            assertFalse(sender.invoke(UPLINK, SEND, mail(readerKnight, "Hello", "How are you?")).failed);

            NewMailInfo announced = reader.awaitNewMail(10); // pushed to the online recipient
            assertEquals(senderKnight, announced.name.toString());

            List<ReceivedMail> received = inbox(reader);
            assertEquals(1, received.size());
            assertEquals("Hello", received.get(0).subject);
            assertEquals("How are you?", received.get(0).body);
            assertEquals(senderKnight, received.get(0).sender.toString());
            assertEquals(announced.id, received.get(0).id);
            assertTrue(received.get(0).id >= 1);

            assertFalse(reader.invoke(UPLINK, MARK_READ, (Object) new long[]{announced.id}).failed);
            assertTrue(inbox(sender).isEmpty(), "the sender's own mailbox stays empty");

            long id = received.get(0).id;
            assertFalse(reader.invoke(UPLINK, DELETE, Long.valueOf(id)).failed);
            assertTrue(inbox(reader).isEmpty());
            assertEquals("m.access_denied", reader.invoke(UPLINK, DELETE, Long.valueOf(id)).message);
        }
    }

    @Test
    public void mailIsPerKnightAndNobodyCanDeleteSomeoneElsesMail() throws Exception {
        String senderKnight = "Sender " + run;
        String readerKnight = "Reader " + run;
        try (TestClient sender = playerWithKnight("S" + run, senderKnight);
             TestClient reader = playerWithKnight("R" + run, readerKnight)) {
            sender.invoke(UPLINK, SEND, mail(readerKnight, "Private", "for the reader only"));
            long id = inbox(reader).get(0).id;
            assertEquals("m.access_denied", sender.invoke(UPLINK, DELETE, Long.valueOf(id)).message);
            assertEquals(1, inbox(reader).size());
        }
    }

    @Test
    public void mailToTheSameKnightLaterStillArrivesAfterTheyReconnect() throws Exception {
        String senderKnight = "Sender " + run;
        String readerKnight = "Reader " + run;
        try (TestClient sender = playerWithKnight("S" + run, senderKnight)) {
            try (TestClient reader = playerWithKnight("R" + run, readerKnight)) {
                assertTrue(inbox(reader).isEmpty());
            }
            // The reader is offline now.
            assertFalse(sender.invoke(UPLINK, SEND, mail(readerKnight, "While away", "see you")).failed);
            try (TestClient again = TestClient.connect(port)) {
                assertEquals("success", again.login(key, "R" + run, "pw").code);
                again.awaitBootstrap();
                assertFalse(again.invoke(ClientSession.KNIGHT_SERVICE_CODE, 4, new Name(readerKnight), null).failed);
                List<ReceivedMail> waiting = inbox(again);
                assertEquals(1, waiting.size());
                assertEquals("While away", waiting.get(0).subject);
            }
        }
    }

    @Test
    public void sendingFailsWithTheGamesMessages() throws Exception {
        String senderKnight = "Sender " + run;
        try (TestClient sender = playerWithKnight("S" + run, senderKnight)) {
            assertEquals("m.unknown_knight", sender.invoke(UPLINK, SEND, mail("Nobody " + run, "Hi", "there")).message);

            SentMail withCrowns = mail(senderKnight, "Gift", "money");
            withCrowns.crowns = 100;
            assertEquals("m.access_denied", sender.invoke(UPLINK, SEND, withCrowns).message);

            assertEquals("e.message_too_long",
                sender.invoke(UPLINK, SEND, mail(senderKnight, "Long", "x".repeat(5_000))).message);
        }
    }
}
