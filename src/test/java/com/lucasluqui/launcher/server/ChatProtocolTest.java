package com.lucasluqui.launcher.server;

import com.threerings.crowd.chat.data.UserMessage;
import com.threerings.projectx.data.ProjectXAuthResponseData;
import com.threerings.util.Name;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chat over the wire: scene speech reaches everyone in the scene, whispers reach one knight. */
public class ChatProtocolTest {
    private static final int TELL = 3;

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

    /** The knight a fresh account starts with is named after the account. */
    private String knightOf(String user) throws Exception {
        return server.services().knights.list(server.services().accounts.find(user).get().id).get(0).name;
    }

    @Test
    public void sceneSpeechReachesEveryoneInTheSceneIncludingTheSpeaker() throws Exception {
        String a = "Talk" + run, b = "Hear" + run;
        try (TestClient one = signedIn(a); TestClient two = signedIn(b)) {
            one.call(SceneManager.SPEAK_SERVICE_CODE, 1, "hello knights", Byte.valueOf((byte) 0));
            UserMessage heard = two.awaitChat(5);
            assertEquals("hello knights", heard.message);
            assertEquals(knightOf(a), heard.speaker.toString());
            assertEquals("hello knights", one.awaitChat(5).message);
        }
    }

    @Test
    public void aWhisperReachesOnlyTheNamedKnightAndTheSenderGetsTheReplyTheGameExpects() throws Exception {
        String a = "Whisp" + run, b = "Targt" + run;
        try (TestClient one = signedIn(a); TestClient two = signedIn(b)) {
            TestClient.Reply reply = one.invoke(ClientSession.CHAT_SERVICE_CODE, TELL, new Name(knightOf(b)), "psst");
            assertFalse(reply.failed, "tell failed: " + reply.message);
            assertEquals(2, reply.values.length); // idle time and away message: the game casts both
            assertTrue(reply.values[0] instanceof Long);
            UserMessage heard = two.awaitChat(5);
            assertEquals("psst", heard.message);
            assertEquals(knightOf(a), heard.speaker.toString());
        }
    }

    @Test
    public void aWhisperToSomeoneNotOnlineFailsWithTheGamesMessage() throws Exception {
        try (TestClient one = signedIn("Lone" + run)) {
            TestClient.Reply reply = one.invoke(ClientSession.CHAT_SERVICE_CODE, TELL, new Name("Nobody Here"), "anyone?");
            assertTrue(reply.failed);
            assertEquals("m.user_not_online", reply.message);
        }
    }

    @Test
    public void theConsoleCanBroadcastToEveryPlayer() throws Exception {
        try (TestClient one = signedIn("Bcast" + run)) {
            server.executeCommand("say maintenance soon");
            assertEquals("maintenance soon", one.awaitChat(5).message);
        }
    }
}
