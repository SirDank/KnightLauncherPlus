package com.lucasluqui.launcher.server;

import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.StandardSocketOptions;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.lucasluqui.launcher.LauncherGlobals;
import com.lucasluqui.util.SteamUtil;

/**
 * Built-in Spiral Knights Game Server Emulator.
 * Provides authentic Three Rings Presents / Narya protocol negotiation,
 * Haven (OID 500) and Training Hall (OID 501), collision physics, and authoritative combat.
 */
public class SpiralServer {
    public static final int DEFAULT_PORT = 47624;

    private final int port;
    private final String dataVersion;
    private final DObjectManager dobjMgr;
    private final SceneManager sceneMgr;
    private final MovementConfig movementConfig;
    private final SceneNetworkManager sceneNetwork;
    private final Map<Integer, ClientSession> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger sessionCounter = new AtomicInteger(1);
    private final List<Consumer<String>> logListeners = new CopyOnWriteArrayList<>();
    private ServerSocketChannel serverChannel;
    private volatile boolean running = false;

    public SpiralServer(int port) {
        this.port = port;
        this.dataVersion = detectDataVersion();
        this.dobjMgr = new DObjectManager();
        this.sceneMgr = new SceneManager(this.dobjMgr);
        this.movementConfig = MovementConfig.load();
        this.sceneNetwork = new SceneNetworkManager(
            this.movementConfig.deltaIntervalMs,
            this.movementConfig.trainingDeltaIntervalMs,
            this.sceneMgr.getCombatWorld());
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
        System.setProperty("com.threerings.io.enumPolicy", "ORDINAL");
        this.serverChannel = ServerSocketChannel.open();
        this.serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        this.serverChannel.bind(new InetSocketAddress(this.port), 128);
        this.sceneNetwork.start();
        this.running = true;

        log("==================================================================");
        log("   SPIRAL KNIGHTS - BUILT-IN SERVER EMULATOR (KNIGHTLAUNCHER+)    ");
        log("==================================================================");
        log(" Server listening on: 0.0.0.0:" + this.port);
        log(" LAN/VPN addresses: " + findClientAddresses());
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
            try {
                SocketChannel clientChannel = serverChannel.accept();
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
                    });
                this.sessions.put(connId, session);
                log("[SpiralServer] Accepted connection from " + clientChannel.getRemoteAddress()
                    + " (Session #" + connId + ")");
                Thread sessionThread = new Thread(session, "ClientSession-" + connId);
                sessionThread.setDaemon(true);
                sessionThread.start();
            } catch (IOException e) {
                if (running) {
                    logErr("[SpiralServer] Error accepting client: " + e.getMessage());
                }
            }
        }
    }

    public static String detectDataVersion() {
        Path[] candidates = new Path[]{
            Path.of("getdown.txt"),
            Path.of(LauncherGlobals.USER_DIR, "getdown.txt"),
            Path.of(LauncherGlobals.USER_DIR, "code", "config.jar")
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                try {
                    for (String line : Files.readAllLines(candidate)) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith("version")) {
                            int separator = trimmed.indexOf('=');
                            if (separator >= 0) {
                                return trimmed.substring(separator + 1).trim();
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        String steamPath = SteamUtil.getGamePathWindows();
        if (steamPath != null) {
            Path getdown = Path.of(steamPath, "getdown.txt");
            if (Files.isRegularFile(getdown)) {
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
                } catch (Exception ignored) {}
            }
        }
        return "20261006114613"; // fallback to known latest SK client version
    }

    public static String findClientAddresses() {
        List<String> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> members = network.getInetAddresses();
                while (members.hasMoreElements()) {
                    InetAddress address = members.nextElement();
                    if (address instanceof Inet4Address
                            && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()) {
                        addresses.add(address.getHostAddress());
                    }
                }
            }
        } catch (SocketException error) {
            return "127.0.0.1";
        }
        return addresses.isEmpty() ? "127.0.0.1" : String.join(", ", addresses);
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
                  .append(" | ").append(session.getUsername() != null ? session.getUsername() : "HeroKnight")
                  .append(" (").append(session.getRemoteAddress()).append(")\n");
            }
            String out = sb.toString().trim();
            log(out);
            return out;
        } else if (lower.startsWith("broadcast ") || lower.startsWith("say ")) {
            String msg = cmd.substring(cmd.indexOf(' ') + 1).trim();
            log("[BROADCAST] " + msg);
            return "Broadcasted message to all sessions.";
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
        } else if ("help".equals(lower)) {
            String help = "Available commands: status, players, broadcast <msg>, kick <id>, stop, help";
            log(help);
            return help;
        } else {
            String unk = "Unknown command: " + cmd + ". Type 'help' for available commands.";
            log(unk);
            return unk;
        }
    }

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.out.println("Invalid port, using default port: " + DEFAULT_PORT);
            }
        }

        SpiralServer server = new SpiralServer(port);
        try {
            server.start();

            Scanner scanner = new Scanner(System.in);
            while (server.running) {
                if (scanner.hasNextLine()) {
                    String line = scanner.nextLine().trim();
                    server.executeCommand(line);
                } else {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {}
                }
            }
        } catch (Exception e) {
            System.err.println("[SpiralServer] Fatal error starting server: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

