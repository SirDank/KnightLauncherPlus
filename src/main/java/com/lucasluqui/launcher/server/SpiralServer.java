package com.lucasluqui.launcher.server;

import com.lucasluqui.launcher.server.db.AccountStore;
import com.lucasluqui.launcher.server.db.KnightStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Built-in Spiral Knights Game Server Emulator.
 * Provides authentic Three Rings Presents / Narya protocol negotiation,
 * Haven (OID 500) and Training Hall (OID 501), collision physics, and authoritative combat.
 *
 * It needs the game's own jars, so the launcher runs it in a separate JVM ({@link #main}) and talks
 * to it over stdin/stdout (see {@link ServerNet}). It listens on loopback only unless asked to open up.
 */
public class SpiralServer {
    public static final int DEFAULT_PORT = ServerNet.DEFAULT_PORT;
    /** Default for {@code server.maxSessions}: concurrent connections accepted, more are dropped at once. */
    public static final int MAX_SESSIONS = 32;

    // A connection that has not signed in by then is dropped: server.authTimeoutSeconds
    // ({@code -Dsk.server.authTimeout} overrides it, for tests).
    private final long authTimeoutSeconds;

    private final int port;
    private final boolean allowLan;
    private final String dataVersion;
    private final DObjectManager dobjMgr;
    private final SceneManager sceneMgr;
    private final MovementConfig movementConfig;
    private final SceneNetworkManager sceneNetwork;
    private final Map<Integer, ClientSession> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger sessionCounter = new AtomicInteger(1);
    private final List<Consumer<String>> logListeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "SpiralServer-AuthWatchdog");
        thread.setDaemon(true);
        return thread;
    });
    private ServerSocketChannel serverChannel;
    private volatile boolean running = false;
    /** Accounts, knights, mail and the server key: opened in {@link #start}. */
    private ServerServices services;

    /** How many knights fit in a party here; for tests. */
    int configuredPartySize() {
        return this.movementConfig.maxPartySize;
    }

    /** The scenes of this server; for tests. */
    SceneManager sceneManager() {
        return this.sceneMgr;
    }

    /** The session of the player with this client object id, or null; for tests. */
    ClientSession sessionOfClient(int clientOid) {
        for (ClientSession session : this.sessions.values()) {
            if (session.clientOid() == clientOid) return session;
        }
        return null;
    }

    /** The party object id of the player with this client object id, or -1; for tests. */
    int partyOidOfClient(int clientOid) {
        for (ClientSession session : this.sessions.values()) {
            if (session.clientOid() == clientOid) return session.partyOid();
        }
        return -1;
    }

    /** The accounts, knights and mail of this server; for tests. */
    ServerServices services() {
        return this.services;
    }


    public SpiralServer(int port, boolean allowLan) {
        this.port = port;
        this.allowLan = allowLan;
        this.dataVersion = detectDataVersion();
        this.dobjMgr = new DObjectManager();
        // First, so a bad server.properties fails before the slow scene and config loading.
        this.movementConfig = MovementConfig.load();
        this.authTimeoutSeconds = Long.getLong("sk.server.authTimeout", this.movementConfig.authTimeoutSeconds);
        this.sceneMgr = new SceneManager(this.dobjMgr, this.movementConfig);
        this.sceneNetwork = new SceneNetworkManager(
            this.movementConfig.deltaIntervalMs,
            this.movementConfig.trainingDeltaIntervalMs,
            this.sceneMgr::getCombatWorld);
        this.sceneMgr.dungeons().attach(this.sceneNetwork);
    }

    public void addLogListener(Consumer<String> listener) {
        logListeners.add(listener);
    }

    public void removeLogListener(Consumer<String> listener) {
        logListeners.remove(listener);
    }

    public void log(String line) {
        System.out.println(line);
        for (Consumer<String> listener : logListeners) {
            try {
                listener.accept(line);
            } catch (Exception ignored) {}
        }
    }

    public void logErr(String line) {
        System.err.println(line);
        for (Consumer<String> listener : logListeners) {
            try {
                listener.accept("[ERR] " + line);
            } catch (Exception ignored) {}
        }
    }

    public synchronized void start() throws IOException {
        if (this.running) {
            return;
        }
        if (!ServerNet.validPort(this.port)) {
            throw new IOException("Invalid port " + this.port + " (use 1-65535)");
        }
        // Before the port opens: a database or key problem must fail the start, with a clear message.
        try {
            this.services = ServerServices.open(new java.io.File(ServerNet.GAME_DIR));
        } catch (java.sql.SQLException | IOException e) {
            throw new IOException("Could not open the server's accounts database or key: " + e.getMessage(), e);
        }
        String bindHost = this.allowLan ? "0.0.0.0" : ServerNet.LOOPBACK;
        ServerSocketChannel channel = ServerSocketChannel.open();
        try {
            channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            channel.bind(new InetSocketAddress(bindHost, this.port), 128);
        } catch (IOException e) {
            channel.close();
            throw e;
        }
        this.serverChannel = channel;
        this.sceneNetwork.start();
        this.running = true;

        log("==================================================================");
        log("   SPIRAL KNIGHTS - BUILT-IN SERVER EMULATOR (KNIGHTLAUNCHER+)    ");
        log("==================================================================");
        log(" Server listening on: " + bindHost + ":" + this.port
            + (this.allowLan ? " (open to LAN/VPN)" : " (this computer only)"));
        if (this.allowLan) {
            log(" LAN/VPN addresses: " + ServerNet.findClientAddresses());
        }
        log(" Transport: TCP NoDelay/KeepAlive, Haven ticks: "
            + this.movementConfig.deltaIntervalMs + "ms, Training ticks: "
            + this.movementConfig.trainingDeltaIntervalMs + "ms");
        log(" Effective data version: " + this.dataVersion);
        log(" Protocol: Three Rings Presents / Narya / DObject (Framed)");
        log(" Town Scene: OID " + SceneManager.SCENE_OID + " (Haven / Level Scene)");
        log(" Training Hall: OID " + SceneManager.TRAINING_PLACE_OID
            + " (Scene " + SceneManager.TRAINING_SCENE_ID + " / Advanced Training)");
        log(" Combat: Authoritative simulation, weapons, bullets, bombs, training cubes");
        log(" Movement: " + this.movementConfig.describe());
        log(" Inventory: Full rank 10 equipment & weapons catalog");
        log(" Status: READY FOR CLIENT CONNECTIONS");
        log("==================================================================");

        Thread acceptThread = new Thread(this::acceptLoop, "SpiralServer-Acceptor");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        while (running && serverChannel != null && serverChannel.isOpen()) {
            SocketChannel clientChannel = null;
            try {
                clientChannel = serverChannel.accept();
                if (sessions.size() >= this.movementConfig.maxSessions) {
                    logErr("[SpiralServer] Refused connection from " + clientChannel.getRemoteAddress()
                        + ": " + this.movementConfig.maxSessions + " sessions already open");
                    clientChannel.close();
                    continue;
                }
                clientChannel.configureBlocking(true);
                clientChannel.socket().setTcpNoDelay(true);
                clientChannel.socket().setKeepAlive(true);
                clientChannel.socket().setReceiveBufferSize(256 * 1024);
                clientChannel.socket().setSendBufferSize(256 * 1024);
                int connId = sessionCounter.getAndIncrement();
                ClientSession session = new ClientSession(
                    clientChannel, dobjMgr, sceneMgr, sceneNetwork, movementConfig,
                    connId, dataVersion, this.port,
                    closed -> {
                        this.sessions.remove(closed.getConnectionId(), closed);
                        log("[SpiralServer] Session disconnected: id=" + closed.getConnectionId());
                        reportPlayers();
                    }, this.services);
                this.sessions.put(connId, session);
                log("[SpiralServer] Accepted connection from " + clientChannel.getRemoteAddress()
                    + " (Session #" + connId + ")");
                reportPlayers();
                Thread sessionThread = new Thread(session, "ClientSession-" + connId);
                sessionThread.setDaemon(true);
                sessionThread.start();
                watchdog.schedule(() -> {
                    if (!session.isAuthenticated()) {
                        log("[SpiralServer] Session #" + connId + " did not authenticate in "
                            + authTimeoutSeconds + "s, dropping it");
                        session.close();
                    }
                }, authTimeoutSeconds, TimeUnit.SECONDS);
            } catch (IOException e) {
                if (clientChannel != null) {
                    try { clientChannel.close(); } catch (IOException ignored) {}
                }
                if (running) {
                    logErr("[SpiralServer] Error accepting client: " + e.getMessage());
                    try {
                        Thread.sleep(100); // don't spin on a persistent failure
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                }
            }
        }
    }

    /** Tells the launcher the session count, in the line format {@link ServerNet#PLAYERS}. */
    private void reportPlayers() {
        System.out.println(ServerNet.PLAYERS + sessions.size());
    }

    /** The game version the clients should be on, from the game install's getdown.txt. */
    public static String detectDataVersion() {
        String version = readVersion(Path.of(ServerNet.GAME_DIR, "getdown.txt"));
        return version != null ? version : "20261006114613"; // fallback to known latest SK client version
    }

    private static String readVersion(Path getdown) {
        if (!Files.isRegularFile(getdown)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(getdown)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("version")) {
                    int separator = trimmed.indexOf('=');
                    if (separator >= 0) {
                        return trimmed.substring(separator + 1).trim();
                    }
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    public synchronized void stop() {
        if (!this.running) return;
        this.running = false;
        try {
            if (this.serverChannel != null && this.serverChannel.isOpen()) {
                this.serverChannel.close();
            }
        } catch (IOException ignored) {}
        for (ClientSession session : this.sessions.values()) {
            session.close();
        }
        this.sessions.clear();
        this.sceneNetwork.close();
        this.watchdog.shutdownNow();
        if (this.services != null) {
            try {
                this.services.close();
            } catch (java.sql.SQLException e) {
                logErr("[SpiralServer] Could not close the database: " + e.getMessage());
            }
        }
        log("[SpiralServer] Server stopped.");
    }

    public boolean isRunning() {
        return this.running;
    }

    public int getPort() {
        return this.port;
    }

    public int getSessionCount() {
        return this.sessions.size();
    }

    public String getDataVersion() {
        return this.dataVersion;
    }

    public String executeCommand(String command) {
        if (command == null || command.trim().isEmpty()) return "";
        String cmd = command.trim();
        log("> " + cmd);
        String lower = cmd.toLowerCase();
        if ("stop".equals(lower) || "exit".equals(lower)) {
            stop();
            return "Server stopping...";
        } else if ("status".equals(lower)) {
            String status = "[Status] Running: " + running
                + ", Active sessions: " + sessions.size()
                + ", Total accepted: " + (sessionCounter.get() - 1)
                + ", Haven players: " + sceneNetwork.participantCount(SceneManager.DEFAULT_SCENE_ID)
                + ", Training players: " + sceneNetwork.participantCount(SceneManager.TRAINING_SCENE_ID);
            log(status);
            return status;
        } else if ("players".equals(lower)) {
            if (sessions.isEmpty()) {
                log("[Players] No players currently connected.");
                return "No players online.";
            }
            StringBuilder sb = new StringBuilder("[Players] Connected (" + sessions.size() + "):\n");
            for (ClientSession session : sessions.values()) {
                sb.append(" - Session #").append(session.getSessionId())
                  .append(" | ").append(session.getUsername() != null ? session.getUsername() : "(authenticating)")
                  .append(" (").append(session.getRemoteAddress()).append(")\n");
            }
            String out = sb.toString().trim();
            log(out);
            return out;
        } else if (lower.startsWith("broadcast ") || lower.startsWith("say ")) {
            String msg = cmd.substring(cmd.indexOf(' ') + 1).trim();
            int players = this.services == null ? 0 : this.services.online.size();
            if (this.services != null) ChatHandler.broadcast(this.services.online.values(), msg);
            log("[BROADCAST] " + msg + " (" + players + " players)");
            return "Sent to " + players + " players.";
        } else if (lower.startsWith("kick ")) {
            try {
                int targetId = Integer.parseInt(cmd.substring(5).trim());
                ClientSession s = sessions.get(targetId);
                if (s != null) {
                    s.close();
                    log("[KICK] Kicked session #" + targetId);
                    return "Kicked session #" + targetId;
                } else {
                    log("[KICK] Session #" + targetId + " not found.");
                    return "Session not found.";
                }
            } catch (Exception ex) {
                log("[KICK] Invalid syntax. Usage: kick <sessionId>");
                return "Invalid syntax.";
            }
        } else if (lower.startsWith("account ") || lower.startsWith("knight ")) {
            String out = accountCommand(cmd);
            log(out);
            return out;
        } else if ("dungeon clear".equals(lower)) {
            int killed = this.sceneMgr.dungeons().clearAll(this.sceneNetwork);
            return "Killed " + killed + " monsters in " + this.sceneMgr.dungeons().open() + " open dungeons.";
        } else if ("help".equals(lower)) {
            String help = "Available commands: status, players, broadcast <msg>, kick <id>, dungeon clear, account list|reset <name> <password>|delete <name>, knight list <account>, stop, help";
            log(help);
            return help;
        } else {
            String unk = "Unknown command: " + cmd + ". Type 'help' for available commands.";
            log(unk);
            return unk;
        }
    }

    /** The console's {@code account ...} and {@code knight ...} commands. */
    private String accountCommand(String command) {
        if (this.services == null) return "Accounts are not available.";
        String[] words = command.trim().split("\\s+", 4);
        try {
            if ("account".equalsIgnoreCase(words[0]) && words.length >= 2) {
                switch (words[1].toLowerCase()) {
                    case "list": {
                        StringBuilder out = new StringBuilder("[Accounts]");
                        for (AccountStore.Account account : this.services.accounts.list()) {
                            out.append("\n - ").append(account.username).append(" (")
                                .append(this.services.knights.list(account.id).size()).append(" knights)");
                        }
                        return out.toString();
                    }
                    case "reset":
                        if (words.length < 4) return "Usage: account reset <name> <password>";
                        // The game sends the MD5 hex of what is typed, and that is what is stored (hashed).
                        return this.services.accounts.resetPassword(words[2], md5Hex(words[3]))
                            ? "Password of " + words[2] + " changed." : "No such account (or invalid password).";
                    case "delete": {
                        if (words.length < 3) return "Usage: account delete <name>";
                        AccountStore.Account account = this.services.accounts.find(words[2]).orElse(null);
                        if (account == null) return "No such account.";
                        ClientSession online = this.services.online.get(account.id);
                        this.services.accounts.delete(words[2]);
                        if (online != null) online.close();
                        return "Account " + account.username + " and its knights deleted.";
                    }
                    default:
                }
            } else if ("knight".equalsIgnoreCase(words[0]) && words.length >= 3 && "list".equalsIgnoreCase(words[1])) {
                AccountStore.Account account = this.services.accounts.find(command.trim().substring(command.trim().indexOf(words[2]))).orElse(null);
                if (account == null) return "No such account.";
                StringBuilder out = new StringBuilder("[Knights of " + account.username + "]");
                for (KnightStore.Knight knight : this.services.knights.list(account.id)) out.append("\n - ").append(knight.name);
                return out.toString();
            }
        } catch (java.sql.SQLException e) {
            return "Database error: " + e.getMessage();
        }
        return "Usage: account list|reset <name> <password>|delete <name>, knight list <account>";
    }

    private static String md5Hex(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("MD5").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Entry point of the server process: {@code SpiralServer [--port N] [--lan]}.
     * Prints {@link ServerNet#READY} once listening or {@link ServerNet#FATAL} and exits non-zero.
     * Reads commands from stdin and stops when it ends, so it never outlives the launcher.
     */
    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        boolean lan = false;
        for (int i = 0; i < args.length; i++) {
            if ("--lan".equals(args[i])) {
                lan = true;
            } else if ("--port".equals(args[i]) && i + 1 < args.length) {
                try {
                    port = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    fatal("Invalid port: " + args[i]);
                }
            }
        }
        // Read when the game's Streamer classes load, so it has to be set first. Scoped to this process.
        System.setProperty("com.threerings.io.enumPolicy", "ORDINAL");

        SpiralServer server;
        try {
            server = new SpiralServer(port, lan);
            server.start();
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            fatal(t instanceof IOException || t instanceof IllegalStateException ? t.getMessage() : t.toString());
            return;
        }
        System.out.println(ServerNet.READY + "port=" + port);
        server.reportPlayers();

        Scanner scanner = new Scanner(System.in);
        while (server.running && scanner.hasNextLine()) {
            server.executeCommand(scanner.nextLine());
        }
        server.stop(); // 'stop' command, or the launcher went away (stdin closed)
        System.exit(0);
    }

    private static void fatal(String message) {
        System.out.println(ServerNet.FATAL + message);
        System.exit(2);
    }
}
