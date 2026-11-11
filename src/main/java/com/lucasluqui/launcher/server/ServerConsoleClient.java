package com.lucasluqui.launcher.server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Talks to the Tortodrone console through the child process's stdin and stdout: sends a command, answers its prompts
 * (a password is asked for on its own line, after the prompt appears) and reports what the server said back, so the
 * launcher can tell success from refusal instead of assuming.
 * <p>
 * The console runs commands one at a time, in order. To know a command has finished, a {@code status} command is sent
 * behind it and its three output lines are swallowed; the {@code Heap:} line is the last of them. Replies arrive as
 * {@code [HH:mm:ss] [ServerConsole/INFO|WARN]: text}; a WARN is a refusal.
 */
public final class ServerConsoleClient {
    /** What the server answered. */
    public record Result(boolean ok, String message, List<String> lines) {
        static Result failed(String message) {
            return new Result(false, message, List.of());
        }
    }

    /** Where command lines go (the process's stdin). Returns false when the process cannot be reached. */
    public interface Sink {
        boolean write(String line);
    }

    /** The counters the server's own {@code status} command prints. */
    public record Status(int sessions, int players) {}

    private static final Pattern CONSOLE_LINE = Pattern.compile("^\\[\\d\\d:\\d\\d:\\d\\d] \\[ServerConsole/(INFO|WARN)]: (.*)$");
    private static final Pattern STATUS_HEAD = Pattern.compile("^Tortodrone \\S+ \\| \\S+ \\| .*");
    private static final Pattern STATUS_SESSIONS = Pattern.compile("^Sessions: (\\d+) \\| Players: (\\d+)\\b.*");
    private static final String STATUS_LAST = "Heap:";
    private static final long PROMPT_TIMEOUT_MS = 5_000;
    private static final long DEFAULT_TIMEOUT_MS = 20_000;

    private final Sink sink;
    private final ReentrantLock one = new ReentrantLock(true);
    private volatile Exchange current;
    private volatile Status status = new Status(0, 0);

    public ServerConsoleClient(Sink sink) {
        this.sink = sink;
    }

    /** The counters seen in the most recent {@code status} reply. */
    public Status status() {
        return status;
    }

    /**
     * Feed every line the server prints. Returns true when the line belongs to an exchange that wants it hidden from
     * the console view (the status check that marks the end of a command, or everything of a silent query).
     */
    public boolean onLine(String line) {
        Matcher m = CONSOLE_LINE.matcher(line);
        if (!m.matches()) return false;
        boolean warn = m.group(1).equals("WARN");
        String text = m.group(2);

        Matcher sessions = STATUS_SESSIONS.matcher(text);
        if (sessions.matches()) {
            status = new Status(Integer.parseInt(sessions.group(1)), Integer.parseInt(sessions.group(2)));
        }

        Exchange exchange = current;
        if (exchange == null) return false;
        boolean marker = exchange.markerSent && !warn
            && (STATUS_HEAD.matcher(text).matches() || sessions.matches() || text.startsWith(STATUS_LAST));
        exchange.lines.add(new Line(warn, text, marker));
        return exchange.silent || marker;
    }

    /** Sends a command that needs no answers. Call off the UI thread. */
    public Result run(String command) {
        return run(command, List.of(), false, DEFAULT_TIMEOUT_MS);
    }

    /** Sends a command and answers its prompts in order, each only once the server has asked. Call off the UI thread. */
    public Result run(String command, List<String> answers) {
        return run(command, answers, false, DEFAULT_TIMEOUT_MS);
    }

    /** As {@link #run(String, List)} but nothing of the exchange is shown in the console view. */
    public Result query(String command) {
        return run(command, List.of(), true, DEFAULT_TIMEOUT_MS);
    }

    public Result run(String command, List<String> answers, boolean silent, long timeoutMs) {
        one.lock();
        try {
            Exchange exchange = new Exchange(silent);
            current = exchange;
            try {
                return exchange(exchange, command, answers, timeoutMs);
            } finally {
                current = null;
            }
        } finally {
            one.unlock();
        }
    }

    private Result exchange(Exchange exchange, String command, List<String> answers, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        boolean isStatus = command.trim().equals("status");
        if (!sink.write(command)) return Result.failed("unreachable");

        List<String> output = new ArrayList<>();
        String refusal = null;
        for (String answer : answers) {
            Line line;
            boolean prompted = false;
            while (!prompted && (line = next(exchange, Math.min(PROMPT_TIMEOUT_MS, remaining(deadline)))) != null) {
                if (line.warn) {
                    refusal = line.text; // refused before (or while) asking: nothing is waiting for an answer
                    return finish(exchange, deadline, output, refusal, isStatus);
                }
                if (line.text.endsWith(":")) prompted = true;
                else output.add(line.text);
            }
            if (!prompted) return finish(exchange, deadline, output, "timeout", isStatus);
            if (!sink.write(answer)) return Result.failed("unreachable");
        }
        return finish(exchange, deadline, output, refusal, isStatus);
    }

    /** Waits for the console to get past the command: sends the status check behind it and reads up to its last line. */
    private Result finish(Exchange exchange, long deadline, List<String> output, String refusal, boolean isStatus) {
        if (!isStatus) {
            exchange.markerSent = true;
            if (!sink.write("status")) return Result.failed("unreachable");
        }
        boolean done = false;
        Line line;
        while (!done && (line = next(exchange, remaining(deadline))) != null) {
            if (line.marker) {
                done = line.text.startsWith(STATUS_LAST);
            } else if (isStatus && line.text.startsWith(STATUS_LAST)) {
                output.add(line.text);
                done = true;
            } else if (line.warn) {
                if (refusal == null) refusal = line.text;
                output.add(line.text);
            } else {
                output.add(line.text);
            }
        }
        if (!done) return new Result(false, refusal != null ? refusal : "timeout", output);
        if (refusal != null) return new Result(false, refusal, output);
        return new Result(true, output.isEmpty() ? "" : output.get(output.size() - 1), output);
    }

    private static long remaining(long deadline) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
    }

    private static Line next(Exchange exchange, long millis) {
        try {
            return exchange.lines.poll(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private record Line(boolean warn, String text, boolean marker) {}

    private static final class Exchange {
        final boolean silent;
        volatile boolean markerSent;
        final BlockingQueue<Line> lines = new LinkedBlockingQueue<>();

        Exchange(boolean silent) {
            this.silent = silent;
        }
    }
}
