package com.lucasluqui.launcher.server;

import com.threerings.projectx.data.ProjectXAuthResponseData;
import com.threerings.tudey.data.TudeySceneModel;
import com.threerings.crowd.chat.data.UserMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Starting a dungeon the way a player does: type !dungeon in chat, get sent to the level, and load it. */
public class DungeonProtocolTest {
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

    private TestClient signedIn(String user) throws Exception {
        TestClient client = TestClient.connect(port);
        ProjectXAuthResponseData answer = client.login(key, user, "pw");
        assertEquals("success", answer.code);
        client.awaitBootstrap();
        return client;
    }

    private static final int MISSION = 3;
    private static final int DEBUG = 20;
    private static final int DEPTH = 39;

    /** The game's /mission command: calls the admin service, which starts the dungeon. */
    private static void mission(TestClient client, String arguments) throws Exception {
        assertFalse(client.invoke(ClientSession.ADMIN_SERVICE_CODE, MISSION, arguments).failed);
    }

    /** The game's /debug command: the server answers with a line of text. */
    private static String debug(TestClient client, String line) throws Exception {
        return (String) client.invoke(ClientSession.ADMIN_SERVICE_CODE, DEBUG, line).values[0];
    }

    @Test
    public void typingTheCommandSendsThePlayerToAGeneratedLevelTheGameCanLoad() throws Exception {
        try (TestClient client = signedIn("Delver" + run)) {
            mission(client, "2 easy");
            int sceneId = client.awaitZoneMove(10);
            assertTrue(sceneId >= DungeonManager.FIRST_SCENE_ID, "sent to a generated scene, got " + sceneId);

            // The game then asks to move there and is answered with the level's map.
            TestClient.Reply moved = client.invoke(ClientSession.ZONE_SERVICE_CODE, 2, 1, sceneId, 0);
            assertFalse(moved.failed, moved.message);
            Object model = moved.values[moved.values.length - 1];
            assertTrue(model instanceof TudeySceneModel, "the reply carries the level map");
            assertEquals(sceneId, ((TudeySceneModel) model).sceneId);
            assertTrue(((TudeySceneModel) model).adK().size() > 100);
        }
    }

    @Test
    public void onlyThePartyLeaderStartsAndAPartyGoesInTogether() throws Exception {
        String a = "Lead" + run, b = "Memb" + run;
        try (TestClient one = signedIn(a); TestClient two = signedIn(b)) {
            String bName = server.services().knights.list(server.services().accounts.find(b).get().id).get(0).name;
            assertFalse(one.invoke(ClientSession.PARTY_SERVICE_CODE, 2, new com.threerings.util.Name(bName)).failed);
            com.threerings.presents.dobj.EntryAddedEvent<?> invite =
                (com.threerings.presents.dobj.EntryAddedEvent<?>) two.awaitSetEvent("notifications", 5);
            String inviteKey = ((com.threerings.projectx.social.data.Notification) invite.xZ()).getKey();
            assertFalse(two.invoke(ClientSession.SOCIAL_SERVICE_CODE, 5, inviteKey, Integer.valueOf(1)).failed);

            // The member cannot start one.
            mission(two, "");
            UserMessage refusal = two.awaitChat(5);
            assertTrue(refusal.message.contains("leader"), refusal.message);

            mission(one, "1");
            int forLeader = one.awaitZoneMove(10);
            int forMember = two.awaitZoneMove(10);
            assertEquals(forLeader, forMember, "both are sent to the same level");
        }
    }

    @Test
    public void clearingALevelAndWalkingToItsEndTakesTheKnightOneLevelDeeper() throws Exception {
        try (TestClient client = signedIn("Descend" + run)) {
            mission(client, "1 easy");
            int first = client.awaitZoneMove(10);
            assertFalse(client.invoke(ClientSession.ZONE_SERVICE_CODE, 2, 1, first, 0).failed);
            client.call(SceneManager.TUDEY_SCENE_SERVICE_CODE, 1); // the game asks for the scene's first state: the knight is in
            Dungeon level = server.sceneManager().dungeons().find(first);
            assertEquals(1, level.depth);

            // With monsters alive the way on stays shut: walking to the end does nothing yet.
            ClientSession session = server.sessionOfClient(client.clientOid());
            float[] exit = level.layout.exitBounds();
            Thread.sleep(300);
            session.teleport((exit[0] + exit[2]) / 2, (exit[1] + exit[3]) / 2);
            Thread.sleep(500);
            assertFalse(level.cleared());

            long knight = server.services().knights.list(server.services().accounts.find("Descend" + run).get().id).get(0).id;
            int itemsBefore = server.services().knightItems.count(knight);
            server.executeCommand("dungeon clear");
            assertTrue(level.cleared());
            for (int i = 0; i < 50 && server.services().knightItems.count(knight) == itemsBefore; i++) Thread.sleep(100);
            assertEquals(itemsBefore + 1, server.services().knightItems.count(knight), "clearing the level gives each knight a piece of gear");
            int second = client.awaitZoneMove(10);
            assertTrue(second != first);
            Dungeon deeper = server.sceneManager().dungeons().find(second);
            assertEquals(2, deeper.depth);
        }
    }

    @Test
    public void theLeaderCanBringServerPlayedKnightsUpToTheFullPartySize() throws Exception {
        try (TestClient client = signedIn("Squad" + run)) {
            assertTrue(debug(client, "companions 20").contains("Companions: " + (server.configuredPartySize() - 1)));
            mission(client, "2");
            int sceneId = client.awaitZoneMove(10);
            Dungeon level = server.sceneManager().dungeons().find(sceneId);
            assertEquals(server.configuredPartySize() - 1, level.companionsAlive());
        }
    }

    @Test
    public void aSecondStartWhileTheFirstIsStillOnItsWayIsRefused() throws Exception {
        try (TestClient client = signedIn("Twice" + run)) {
            mission(client, "");
            int first = client.awaitZoneMove(10);
            mission(client, "");
            assertTrue(client.awaitChat(5).message.contains("on its way"));
            assertEquals(1, server.sceneManager().dungeons().open(), "only one level was made");
            assertTrue(server.sceneManager().dungeons().isDungeon(first));
        }
    }

    @Test
    public void theDebugCommandListsTheServersCommandsAndDepthSetsWhereDungeonsStart() throws Exception {
        try (TestClient client = signedIn("Helper" + run)) {
            String help = debug(client, "help");
            assertTrue(help.contains("dungeon") && help.contains("companions") && help.contains("/mission") && help.contains("/depth"), help);
            assertTrue(debug(client, "nonsense").contains("/debug"), "an unknown command shows the help");

            assertFalse(client.invoke(ClientSession.ADMIN_SERVICE_CODE, DEPTH, 7).failed);
            mission(client, ""); // no depth given: the party's default
            int sceneId = client.awaitZoneMove(10);
            assertEquals(7, server.sceneManager().dungeons().find(sceneId).depth);
        }
    }

    @Test
    public void otherAdminCallsAreRefusedBecauseThereIsNoAdministrator() throws Exception {
        try (TestClient client = signedIn("Plain" + run)) {
            TestClient.Reply refused = client.invoke(ClientSession.ADMIN_SERVICE_CODE, 7, "x");
            assertTrue(refused.failed);
            assertEquals("m.access_denied", refused.message);
        }
    }
}
