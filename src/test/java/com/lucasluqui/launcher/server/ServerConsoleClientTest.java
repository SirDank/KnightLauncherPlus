package com.lucasluqui.launcher.server;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerConsoleClientTest {
    private static String info(String text) { return "[01:00:00] [ServerConsole/INFO]: " + text; }
    private static String warn(String text) { return "[01:00:00] [ServerConsole/WARN]: " + text; }

    private static final List<String> STATUS = List.of(
        info("Tortodrone 0.40.8 | RUNNING | 127.0.0.1:47624 | uptime=7s"),
        info("Sessions: 3 | Players: 2 | Maps: 9 | Items: 9355 | Instances: 5"),
        info("Heap: 800 / 6144 MiB | Accounts: 4"));

    /** A console that answers every written line, like the real one, and remembers what was shown to the user. */
    private static final class FakeConsole implements ServerConsoleClient.Sink {
        final List<String> written = Collections.synchronizedList(new ArrayList<>());
        final List<String> shown = Collections.synchronizedList(new ArrayList<>());
        final Function<String, List<String>> script;
        ServerConsoleClient client;

        FakeConsole(Function<String, List<String>> script) { this.script = script; }

        @Override public boolean write(String line) {
            written.add(line);
            List<String> replies = line.equals("status") ? STATUS : script.apply(line);
            new Thread(() -> {
                for (String reply : replies) {
                    if (!client.onLine(reply)) shown.add(reply);
                }
            }).start();
            return true;
        }
    }

    private static ServerConsoleClient with(FakeConsole console) {
        ServerConsoleClient client = new ServerConsoleClient(console);
        console.client = client;
        return client;
    }

    @Test
    public void aSuccessfulCommandReturnsWhatTheServerSaidAndHidesTheStatusCheck() {
        FakeConsole console = new FakeConsole(l -> List.of(info("Game Master status changed: bob = true")));
        ServerConsoleClient.Result result = with(console).run("gm bob true");
        assertTrue(result.ok());
        assertEquals("Game Master status changed: bob = true", result.message());
        assertEquals(List.of("gm bob true", "status"), console.written);
        assertEquals(List.of(info("Game Master status changed: bob = true")), console.shown);
    }

    @Test
    public void aWarningIsARefusalAndItsTextIsTheMessage() {
        FakeConsole console = new FakeConsole(l -> List.of(warn("No such knight")));
        ServerConsoleClient.Result result = with(console).run("grant nobody crowns 5");
        assertFalse(result.ok());
        assertEquals("No such knight", result.message());
    }

    @Test
    public void passwordsAreSentOnlyAfterEachPromptAppears() {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        int[] stage = {0};
        FakeConsole console = new FakeConsole(l -> {
            order.add(l);
            if (l.equals("account create bob")) return List.of(info("Password:"));
            if (l.equals("secret-one") && stage[0]++ == 0) return List.of(info("Confirm password:"));
            if (l.equals("secret-two")) return List.of(info("Account created: bob"));
            return List.of(warn("Unknown command. Type help to see commands, or press Up to correct your input."));
        });
        ServerConsoleClient.Result result = with(console).run("account create bob", List.of("secret-one", "secret-two"));
        assertTrue(result.ok(), result.message());
        assertEquals("Account created: bob", result.message());
        assertEquals(List.of("account create bob", "secret-one", "secret-two"), order);
        assertEquals(List.of("account create bob", "secret-one", "secret-two", "status"), console.written);
    }

    @Test
    public void aCommandRefusedBeforeItPromptsNeverReceivesTheAnswers() {
        FakeConsole console = new FakeConsole(l -> List.of(warn("Username must be 3 to 32 characters")));
        ServerConsoleClient.Result result = with(console).run("account create 1", List.of("secret", "secret"));
        assertFalse(result.ok());
        assertEquals("Username must be 3 to 32 characters", result.message());
        assertFalse(console.written.contains("secret"), "an answer reached the console as a command");
    }

    @Test
    public void aQuietServerTimesOut() {
        ServerConsoleClient client = new ServerConsoleClient(line -> true);
        ServerConsoleClient.Result result = client.run("gm bob true", List.of(), false, 200);
        assertFalse(result.ok());
        assertEquals("timeout", result.message());
    }

    @Test
    public void anUnreachableProcessIsReportedAtOnce() {
        ServerConsoleClient.Result result = new ServerConsoleClient(line -> false).run("players");
        assertFalse(result.ok());
        assertEquals("unreachable", result.message());
    }

    @Test
    public void aSilentQueryShowsNothingAndStatusCountersAreRemembered() {
        FakeConsole console = new FakeConsole(l -> List.of());
        ServerConsoleClient client = with(console);
        ServerConsoleClient.Result result = client.query("status");
        assertTrue(result.ok());
        assertTrue(console.shown.isEmpty(), "a silent query leaked: " + console.shown);
        assertEquals(new ServerConsoleClient.Status(3, 2), client.status());
        assertEquals(3, result.lines().size());
    }

    @Test
    public void linesFromOtherThreadsAreNotTheClientsBusiness() {
        ServerConsoleClient client = new ServerConsoleClient(line -> true);
        assertFalse(client.onLine("[01:00:00] [Client-1/INFO]: Connected | session=1 | endpoint=/127.0.0.1:50000"));
        assertFalse(client.onLine("something that is not a log line"));
    }
}
