package com.lucasluqui.launcher.server;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starts the server the way the launcher does (its own JVM, {@code SpiralServer.main}) and checks the
 * stdout/stdin protocol that {@code ServerHostManager} depends on.
 */
public class SpiralServerProcessTest {

    private static Process start(String... args) throws IOException {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<String> command = new ArrayList<>(List.of(
            new File(System.getProperty("java.home"), "bin/java").getPath(),
            "-Djava.awt.headless=true", "-Dsk.gameDir=" + ServerNet.GAME_DIR,
            "-cp", classpath, SpiralServer.class.getName()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(new File(ServerNet.GAME_DIR));
        builder.redirectErrorStream(true);
        return builder.start();
    }

    /** Reads lines until one starts with the prefix; null at the end of the output. */
    private static String awaitLine(BufferedReader out, String prefix) throws IOException {
        String line;
        while ((line = out.readLine()) != null) {
            if (line.startsWith(prefix)) return line;
        }
        return null;
    }

    @Test
    public void failsWithFatalLineAndExitCodeOnInvalidPort() throws Exception {
        // Fails the same way with or without game data: either the port or the missing files are reported.
        Process process = start("--port", "99999");
        try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String fatal = awaitLine(out, ServerNet.FATAL);
            assertTrue(fatal != null && fatal.length() > ServerNet.FATAL.length(), "expected a [FATAL] line with a message");
        }
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(2, process.exitValue());
    }

    @Test
    public void readyPlayersAndStopProtocol() throws Exception {
        SpiralServerTest.assumeGameData();
        int port = SpiralServerTest.freePort();
        Process process = start("--port", String.valueOf(port));
        try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             Writer in = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
            assertEquals(ServerNet.READY + "port=" + port, awaitLine(out, ServerNet.READY));
            assertEquals(ServerNet.PLAYERS + "0", awaitLine(out, ServerNet.PLAYERS));

            try (Socket client = new Socket()) {
                client.connect(new InetSocketAddress(ServerNet.LOOPBACK, port), 2000);
                assertEquals(ServerNet.PLAYERS + "1", awaitLine(out, ServerNet.PLAYERS));
            }
            assertEquals(ServerNet.PLAYERS + "0", awaitLine(out, ServerNet.PLAYERS));

            in.write("status\n");
            in.flush();
            assertTrue(awaitLine(out, "[Status]") != null, "console commands are answered");

            in.write("stop\n");
            in.flush();
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "'stop' ends the process");
            assertEquals(0, process.exitValue());
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    public void exitsWhenTheLauncherGoesAway() throws Exception {
        SpiralServerTest.assumeGameData();
        Process process = start("--port", String.valueOf(SpiralServerTest.freePort()));
        try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            assertTrue(awaitLine(out, ServerNet.READY) != null);
            process.getOutputStream().close(); // what a crashed launcher looks like to the child
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "the server must not outlive the launcher");
            assertFalse(process.isAlive());
        } finally {
            process.destroyForcibly();
        }
    }
}
