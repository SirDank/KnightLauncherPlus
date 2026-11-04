package com.lucasluqui.launcher.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the real server on loopback. Needs the game's data: set {@code -Dsk.gameDir=<dir>} to a folder
 * holding {@code rsrc/config}, {@code getdown.txt} and the two scene files (see the README), otherwise
 * these tests skip.
 */
public class SpiralServerTest {

    private SpiralServer server;

    @AfterEach
    public void tearDown() {
        if (server != null) server.stop();
        System.clearProperty("sk.server.authTimeout");
    }

    static void assumeGameData() {
        assumeTrue(SceneManager.findConfigFile("actor.dat").exists() && SceneManager.openSceneStream(1) != null
            && SceneManager.openSceneStream(SceneManager.TRAINING_SCENE_ID) != null,
            "Spiral Knights game data (configs and scenes) not installed");
    }

    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private SpiralServer start(int port) throws IOException {
        server = new SpiralServer(port, false);
        server.start(); // listening once this returns
        return server;
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(ServerNet.LOOPBACK, port), 2000);
        socket.setSoTimeout(6000);
        return socket;
    }

    /** Waits for the server to close the connection; false if it is still open after the socket timeout. */
    private static boolean closedByServer(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        try {
            while (true) {
                if (in.read() == -1) return true;
            }
        } catch (SocketTimeoutException e) {
            return false;
        } catch (IOException reset) {
            return true; // connection reset
        }
    }

    @Test
    public void testServerLifecycle() throws IOException {
        assumeGameData();
        int port = freePort();
        server = new SpiralServer(port, false);
        assertFalse(server.isRunning());

        server.start();
        assertTrue(server.isRunning());
        assertEquals(0, server.getSessionCount());

        try (Socket socket = connect(port)) {
            assertTrue(socket.isConnected(), "Socket should connect to running SpiralServer");
        }

        server.stop();
        assertFalse(server.isRunning(), "Server should report not running after stop()");
    }

    @Test
    public void testRejectsInvalidPort() {
        assumeGameData();
        assertThrows(IOException.class, () -> new SpiralServer(70000, false).start());
    }

    @Test
    public void testPortInUseFailsToStart() throws IOException {
        assumeGameData();
        try (ServerSocket taken = new ServerSocket(0, 1, java.net.InetAddress.getByName(ServerNet.LOOPBACK))) {
            SpiralServer second = new SpiralServer(taken.getLocalPort(), false);
            assertThrows(IOException.class, second::start);
            assertFalse(second.isRunning());
        }
    }

    @Test
    public void testUnauthenticatedConnectionIsDropped() throws IOException {
        assumeGameData();
        System.setProperty("sk.server.authTimeout", "1");
        int port = freePort();
        start(port);

        try (Socket idle = connect(port)) {
            assertTrue(closedByServer(idle), "a connection that never signs in must be dropped");
        }
    }

    @Test
    public void testSessionCap() throws IOException {
        assumeGameData();
        System.setProperty("sk.server.authTimeout", "60");
        int port = freePort();
        start(port);

        List<Socket> open = new ArrayList<>();
        try {
            for (int i = 0; i < SpiralServer.MAX_SESSIONS; i++) {
                open.add(connect(port));
            }
            long deadline = System.currentTimeMillis() + 5000;
            while (server.getSessionCount() < SpiralServer.MAX_SESSIONS && System.currentTimeMillis() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(SpiralServer.MAX_SESSIONS, server.getSessionCount());

            try (Socket extra = connect(port)) {
                assertTrue(closedByServer(extra), "connection number " + (SpiralServer.MAX_SESSIONS + 1) + " must be refused");
            }
            assertEquals(SpiralServer.MAX_SESSIONS, server.getSessionCount(), "refused connections must not count");
        } finally {
            for (Socket socket : open) socket.close();
        }
    }

    @Test
    public void testGarbageDoesNotKillServer() throws IOException {
        assumeGameData();
        System.setProperty("sk.server.authTimeout", "2");
        int port = freePort();
        start(port);

        byte[] noise = new byte[4096];
        new Random(42).nextBytes(noise);
        try (Socket bad = connect(port)) {
            bad.getOutputStream().write(noise);
            bad.getOutputStream().flush();
            assertTrue(closedByServer(bad), "garbage input must end the connection");
        }

        assertTrue(server.isRunning());
        try (Socket good = connect(port)) {
            assertTrue(good.isConnected(), "the server must still accept connections after garbage");
        }
    }
}
