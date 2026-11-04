package com.lucasluqui.launcher.server;

import com.threerings.projectx.data.ProjectXAuthResponseData;
import com.threerings.projectx.data.ProjectXCredentials;
import com.threerings.util.Name;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The login, end to end: a game client (see {@link TestClient}) does the key exchange against the real server and
 * signs in. Needs the game's data like the other server tests (-Dsk.gameDir); skips otherwise.
 */
public class LoginProtocolTest {
    private SpiralServer server;
    private int port;
    private String key;
    /** The database persists in the game folder between runs, so every run uses names of its own. */
    private final String tag = Long.toString(System.nanoTime() % 1_000_000, 36);

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

    private ProjectXAuthResponseData signIn(String user, String password) throws Exception {
        try (TestClient client = TestClient.connect(port)) {
            return client.login(key, user, password);
        }
    }

    @Test
    public void aNewUsernameCreatesTheAccountAndSignsIn() throws Exception {
        String user = "New" + tag;
        ProjectXAuthResponseData answer = signIn(user, "secret1");
        assertEquals("success", answer.code);
        assertEquals(user, answer.authName.toString());
        assertNotNull(answer.sessionId);
        assertEquals(32, answer.sessionId.length());
    }

    @Test
    public void theSamePasswordSignsBackInAndAWrongOneIsRefusedWithTheLoginScreensMessage() throws Exception {
        String user = "Back" + tag;
        assertEquals("success", signIn(user, "right").code);
        assertEquals("success", signIn(user, "right").code);
        assertEquals("m.invalid_password", signIn(user, "wrong").code);
    }

    @Test
    public void usernamesAreNotCaseSensitiveAndKeepTheirFirstSpelling() throws Exception {
        String user = "Mixed" + tag;
        assertEquals("success", signIn(user, "pw").code);
        ProjectXAuthResponseData again = signIn(user.toLowerCase(), "pw");
        assertEquals("success", again.code);
        assertEquals(user, again.authName.toString());
        assertEquals("m.invalid_password", signIn(user.toUpperCase(), "other").code);
    }

    @Test
    public void theConsoleCanResetAPasswordAndDeleteAnAccount() throws Exception {
        String user = "Admin" + tag;
        assertEquals("success", signIn(user, "old pass").code);
        assertTrue(server.executeCommand("account list").contains(user));
        assertTrue(server.executeCommand("knight list " + user).contains(user.substring(0, 5)));
        assertTrue(server.executeCommand("account reset " + user + " new pass").contains("changed"));
        assertEquals("m.invalid_password", signIn(user, "old pass").code);
        assertEquals("success", signIn(user, "new pass").code);
        assertTrue(server.executeCommand("account delete " + user).contains("deleted"));
        assertEquals("No such account.", server.executeCommand("account delete " + user));
        assertEquals("success", signIn(user, "anything").code); // the name is free again: a new account
    }

    @Test
    public void aRefusedLoginEndsTheConnection() throws Exception {
        String user = "Refused" + tag;
        signIn(user, "good");
        try (TestClient client = TestClient.connect(port)) {
            assertEquals("m.invalid_password", client.login(key, user, "bad").code);
            assertTrue(client.closedByServer(5), "the server must close the connection after refusing");
        }
    }

    @Test
    public void aReconnectWithTheSessionIdWorks() throws Exception {
        String user = "Again" + tag;
        ProjectXAuthResponseData first = signIn(user, "pw");
        assertEquals("success", first.code);
        // What the game does after a successful login: it keeps the session id instead of the password.
        ProjectXCredentials reconnect = new ProjectXCredentials(first.sessionId, first.authName.toString());
        try (TestClient client = TestClient.connect(port)) {
            ProjectXAuthResponseData second = client.login(key, reconnect);
            assertEquals("success", second.code);
            assertNotEquals(first.sessionId, second.sessionId, "a new session id each time");
        }
        // A made-up id does not work.
        try (TestClient client = TestClient.connect(port)) {
            assertEquals("m.session_expired",
                client.login(key, new ProjectXCredentials("0".repeat(32), user)).code);
        }
    }

    @Test
    public void aSessionIdOnlyWorksForItsOwnUsername() throws Exception {
        ProjectXAuthResponseData mine = signIn("Owner" + tag, "pw");
        signIn("Other" + tag, "pw");
        try (TestClient client = TestClient.connect(port)) {
            assertEquals("m.session_expired",
                client.login(key, new ProjectXCredentials(mine.sessionId, "Other" + tag)).code);
        }
    }

    @Test
    public void tooManyWrongPasswordsLockTheNameOut() throws Exception {
        String user = "Locked" + tag;
        assertEquals("success", signIn(user, "right").code);
        for (int i = 0; i < 5; i++) assertEquals("m.invalid_password", signIn(user, "wrong" + i).code);
        assertEquals("m.too_many_attempts", signIn(user, "right").code);
    }

    @Test
    public void steamTicketLoginsAreRefusedClearly() throws Exception {
        ProjectXCredentials steam = new ProjectXCredentials(new Name("steam:76561198184778765"), "ignored");
        try (TestClient client = TestClient.connect(port)) {
            assertEquals("m.steam_error", client.login(key, steam).code);
        }
    }

    @Test
    public void aClientWithTheWrongKeyIsToldItWasNotSetUpByTheLauncher() throws Exception {
        // A game holding another key.public (say the Official one): the key exchange fails, the game then sends its
        // login unencrypted, and the server must not take it.
        String otherKey = ServerKeys.loadOrCreate(new File(System.getProperty("java.io.tmpdir"),
            "other-key-" + tag + ".key")).publicKeyProperty();
        try (TestClient client = TestClient.connect(port)) {
            assertEquals("m.failed_to_secure", client.login(otherKey, "Wrongkey" + tag, "pw").code);
        } finally {
            new File(System.getProperty("java.io.tmpdir"), "other-key-" + tag + ".key").delete();
        }
    }
}
