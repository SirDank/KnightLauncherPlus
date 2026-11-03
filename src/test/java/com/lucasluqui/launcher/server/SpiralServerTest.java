package com.lucasluqui.launcher.server;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SpiralServerTest {

    @Test
    public void testServerLifecycle() throws IOException, InterruptedException {
        int testPort = 57624;
        SpiralServer server = new SpiralServer(testPort);
        assertFalse(server.isRunning());

        server.start();
        assertTrue(server.isRunning());

        // Wait a brief moment for the listening socket to bind
        Thread.sleep(100);

        // Verify TCP connectivity to bound port
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", testPort), 1000);
            assertTrue(socket.isConnected(), "Socket should connect to running SpiralServer");
        }

        server.stop();
        assertFalse(server.isRunning(), "Server should report not running after stop()");
    }
}

