package com.lucasluqui.launcher.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.lucasluqui.launcher.LauncherGlobals;
import com.lucasluqui.launcher.LocaleManager;
import com.lucasluqui.launcher.flamingo.FlamingoManager;
import com.lucasluqui.launcher.flamingo.data.Server;
import com.lucasluqui.launcher.setting.SettingsManager;
import com.lucasluqui.launcher.setting.ui.handler.SettingsEventHandler;
import com.lucasluqui.util.JavaUtil;

import static com.lucasluqui.launcher.Log.log;

/**
 * Starts, stops and manages the built-in Tortodrone server engine.
 */
@Singleton
public class ServerHostManager {
    public static final String LOCAL_SERVER_NAME = "Local Server";
    private static final String ALLOW_LAN_KEY = "server.allowLan";
    private static final int MAX_LOG_HISTORY = 500;
    private static final long START_TIMEOUT_SECONDS = 60;

    @Inject private SettingsManager _settingsManager;
    @Inject private FlamingoManager _flamingoManager;
    @Inject private LocaleManager _localeManager;

    private volatile Process _process;
    private volatile Writer _commands;
    private volatile boolean _starting = false;
    private volatile boolean _ready = false;
    private volatile boolean _stopRequested = false;
    private volatile String _lastError = null;
    private volatile String _lanAddress = null;
    private volatile long _lanAddressAt = 0;
    private volatile String _publicKey = null;
    private volatile int _port = ServerNet.DEFAULT_PORT;
    private volatile long _startedAt = 0;
    /** Serializes start, stop, restart and reset. Never held by anything the UI calls on its own thread. */
    private final Object _lifecycle = new Object();
    private final Object _commandLock = new Object();
    /** Talks to the console of the running process; a new one per start, so counters never outlive a process. */
    private volatile ServerConsoleClient _console = new ServerConsoleClient(line -> false);
    /** Runs everything typed into the console, one thing at a time: a password prompt must not be interleaved. */
    private final ExecutorService _consoleExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ServerConsole");
        t.setDaemon(true);
        return t;
    });
    private volatile ScheduledExecutorService _poller;
    private volatile String _databaseProblem = null;
    private volatile int _maxClients = -1;
    private final List<Consumer<String>> _logListeners = new CopyOnWriteArrayList<>();
    private final LinkedList<String> _logHistory = new LinkedList<>();
    private Writer _logFile;

    public boolean startServer(int port) {
        synchronized (_lifecycle) {
            return startLocked(port);
        }
    }

    private boolean startLocked(int port) {
        if (isServerRunning()) {
            return true;
        }
        _lastError = null;
        openLogFile();
        if (!ServerNet.validPort(port)) {
            return fail(_localeManager.getValue("m.server_invalid_port", String.valueOf(port)));
        }

        File serverDir = new File(LauncherGlobals.USER_DIR, "KnightLauncher/server");
        File configFile = new File(serverDir, "config/server.yml");

        String problem = TortodroneInstaller.ensureInstalled(this::dispatchLog, _localeManager::getValue); // quick when the engine is already current
        if (problem != null) {
            return fail(problem);
        }


        syncServerConfig(configFile, port, isLanAllowed());

        File pubKeyFile = new File(serverDir, "data/keys/public.txt");
        if (pubKeyFile.isFile()) {
            try {
                _publicKey = Files.readString(pubKeyFile.toPath(), StandardCharsets.UTF_8).trim();
            } catch (IOException ignored) {}
        }

        List<String> command = new ArrayList<>();
        command.add(JavaUtil.getLauncherJavaExePath(false));
        command.add("-Djava.awt.headless=true");
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-Dstdout.encoding=UTF-8");
        command.add("-Dstderr.encoding=UTF-8");
        command.add("-jar");
        command.add("tortodrone.jar");
        command.add("--config");
        command.add("config/server.yml");

        _ready = false;
        _stopRequested = false;
        _console = new ServerConsoleClient(this::writeLine);
        _starting = true;
        CountDownLatch started = new CountDownLatch(1);
        String[] failure = new String[1];

        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(serverDir);
            builder.redirectErrorStream(true);
            builder.environment().remove("_JAVA_OPTIONS");
            _process = builder.start();
            _commands = new OutputStreamWriter(_process.getOutputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to start Tortodrone server process", e);
            return fail(_localeManager.getValue("m.server_process_failed", String.valueOf(e.getMessage())));
        }

        Process process = _process;
        Thread reader = new Thread(() -> readOutput(process, started, failure), "TortodroneOutput");
        reader.setDaemon(true);
        reader.start();

        boolean answered;
        try {
            answered = started.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            answered = false;
        } finally {
            _starting = false;
        }

        if (!_ready) {
            process.destroyForcibly();
            _process = null;
            _commands = null;
            return fail(failure[0] != null ? failure[0]
                : answered ? _localeManager.getValue("m.server_exited_early")
                : _localeManager.getValue("m.server_start_timeout", String.valueOf(START_TIMEOUT_SECONDS)));
        }

        _port = port;
        _startedAt = System.currentTimeMillis();
        startStatusPolling();
        _settingsManager.setValue("server.port", String.valueOf(port));

        if (pubKeyFile.isFile()) {
            try {
                _publicKey = Files.readString(pubKeyFile.toPath(), StandardCharsets.UTF_8).trim();
            } catch (IOException ignored) {}
        }

        ensureLocalServerRegistered(port);
        return true;
    }

    private void syncServerConfig(File configFile, int port, boolean allowLan) {
        if (!configFile.isFile()) return;
        try {
            ServerPropertiesFile.applyRuntime(configFile, port, allowLan);
        } catch (IOException e) {
            log.warning("Could not sync server config: " + e.getMessage());
        }
    }

    private boolean fail(String message) {
        _lastError = message;
        dispatchLog("[ERROR] " + message);
        closeLogFile();
        return false;
    }

    public static File getLogFile() {
        return new File(LauncherGlobals.USER_DIR, "KnightLauncher/server/server.log");
    }

    private void openLogFile() {
        synchronized (_logHistory) {
            closeLogFile();
            File file = getLogFile();
            try {
                Files.createDirectories(file.getParentFile().toPath());
                if (file.isFile()) {
                    Files.move(file.toPath(), new File(file.getPath() + ".old").toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                _logFile = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warning("Could not open the server log " + file + ": " + e.getMessage());
                _logFile = null;
            }
        }
    }

    private void closeLogFile() {
        synchronized (_logHistory) {
            if (_logFile != null) {
                try { _logFile.close(); } catch (IOException ignored) {}
                _logFile = null;
            }
        }
    }

    private void readOutput(Process process, CountDownLatch started, String[] failure) {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.contains("Listening on") && line.contains("(TCP)")) {
                    _ready = true;
                    started.countDown();
                } else if (line.contains("Server ready")) {
                    _ready = true;
                    started.countDown();
                } else if (line.contains("Server startup failed:")) {
                    failure[0] = line;
                }
                // The status checks the launcher runs behind its own commands are not for the console view.
                if (_console.onLine(line)) continue;
                dispatchLog(line);
            }
        } catch (IOException e) {
            log.warning("Lost the server output: " + e.getMessage());
        } finally {
            started.countDown();
            onProcessEnded(process);
        }
    }

    private void onProcessEnded(Process process) {
        int code = -1;
        try {
            code = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            if (_process != process) return;
            if (_ready && !_stopRequested) {
                dispatchLog("[ERROR] " + _localeManager.getValue("m.server_ended_unexpectedly", String.valueOf(code)));
            }
            _process = null;
            _commands = null;
            _ready = false;
            _startedAt = 0;
        }
        stopStatusPolling();
        closeLogFile();
    }

    public void stopServer() {
        Process starting = _process;
        if (_starting && starting != null) {
            _stopRequested = true;
            starting.destroyForcibly();
        }
        synchronized (_lifecycle) {
            stopLocked();
        }
    }

    private void stopLocked() {
        Process process = _process;
        if (process == null) return;
        _stopRequested = true;
        sendRaw("stop");
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            if (_process == process) {
                _process = null;
                _commands = null;
                _ready = false;
                _startedAt = 0;
            }
        }
        stopStatusPolling();
        dispatchLog("[INFO] " + _localeManager.getValue("m.server_stopped"));
        closeLogFile();
    }

    public boolean restartServer(int port) {
        synchronized (_lifecycle) {
            stopServer();
            return startServer(port);
        }
    }

    /**
     * Retires the server's data into a backup and starts it from nothing next time (stops the server first). With
     * {@code keepKeys} the server key pair stays, so players do not need a new public key. Call off the UI thread.
     */
    public boolean resetServerData(boolean keepKeys) {
        synchronized (_lifecycle) {
            stopServer();
            try {
                ServerBackups.Backup backup = ServerBackups.reset(serverDir().toPath(), keepKeys);
                synchronized (_logHistory) {
                    _logHistory.clear();
                }
                dispatchLog("[INFO] " + _localeManager.getValue("m.server_data_archived", backup.name()));
                return true;
            } catch (IOException e) {
                log.error("Could not reset the server data", e);
                return false;
            }
        }
    }

    /** Backs up the server's data and settings (stops the server first, a running database cannot be copied safely). */
    public ServerBackups.Backup backupServerData() throws IOException {
        synchronized (_lifecycle) {
            stopServer();
            ServerBackups.Backup backup = ServerBackups.create(serverDir().toPath(), null);
            dispatchLog("[INFO] " + _localeManager.getValue("m.server_data_archived", backup.name()));
            return backup;
        }
    }

    /** Puts a backup back (stops the server first). The state before is kept as a "before-restore" backup. */
    public void restoreServerData(String backupName) throws IOException {
        synchronized (_lifecycle) {
            stopServer();
            ServerBackups.restore(serverDir().toPath(), backupName);
            invalidateConfigCache();
        }
    }

    public List<ServerBackups.Backup> listBackups() throws IOException {
        return ServerBackups.list(serverDir().toPath());
    }

    public void deleteBackup(String backupName) throws IOException {
        ServerBackups.delete(serverDir().toPath(), backupName);
    }

    public static File serverDir() {
        return new File(LauncherGlobals.USER_DIR, "KnightLauncher/server");
    }

    public boolean isServerRunning() {
        Process process = _process;
        return _ready && process != null && process.isAlive();
    }

    /** The port the server listens on now, or the configured one when it is not running. */
    public int getServerPort() {
        return isServerRunning() ? _port : getConfiguredPort();
    }

    /** The port the next start will use (changed in the settings window; a running server keeps its own until restarted). */
    public int getConfiguredPort() {
        try {
            return Integer.parseInt(_settingsManager.getValue("server.port", String.valueOf(ServerNet.DEFAULT_PORT)));
        } catch (Exception e) {
            return ServerNet.DEFAULT_PORT;
        }
    }

    /** True from the moment the process is launched until it is ready (or has failed). */
    public boolean isStarting() {
        return _starting;
    }

    /** Knights signed in right now, from the server's own status (polled every few seconds; 0 when stopped). */
    public int getActivePlayers() {
        return isServerRunning() ? _console.status().players() : 0;
    }

    /** Connections open right now, including ones that have not signed in yet. */
    public int getOpenConnections() {
        return isServerRunning() ? _console.status().sessions() : 0;
    }

    /** The player limit from server.yml, read once (the status line asks every second); see {@link #invalidateConfigCache()}. */
    public int getMaxClients() {
        int cached = _maxClients;
        if (cached > 0) return cached;
        int value = 16;
        try {
            String text = ServerPropertiesFile.read(ServerPropertiesFile.resolve(new File(LauncherGlobals.USER_DIR))).get("server.maxClients");
            if (text != null) value = Integer.parseInt(text.trim());
        } catch (IOException | NumberFormatException ignored) {
            // The default stands.
        }
        _maxClients = value;
        return value;
    }

    /** Forget what was read from the server configuration (it was just saved). */
    public void invalidateConfigCache() {
        _maxClients = -1;
    }

    private ServerDatabase database() {
        return new ServerDatabase(new File(serverDir(), "data/tortodrone.db"));
    }

    /** Why the database could not be read the last time (an unsupported schema, say), or null. */
    public String getDatabaseProblem() {
        return _databaseProblem;
    }

    /** Every account (read-only; empty before the server has made its database). Call off the UI thread. */
    public List<ServerDatabase.Account> getAccounts() {
        try {
            List<ServerDatabase.Account> accounts = database().accounts();
            _databaseProblem = null;
            return accounts;
        } catch (SQLException e) {
            _databaseProblem = e.getMessage();
            log.warning("Could not read accounts from tortodrone.db: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Every knight with balances and progress (read-only). Call off the UI thread. */
    public List<ServerDatabase.Knight> getKnights() {
        try {
            List<ServerDatabase.Knight> knights = database().knights();
            _databaseProblem = null;
            return knights;
        } catch (SQLException e) {
            _databaseProblem = e.getMessage();
            log.warning("Could not read knights from tortodrone.db: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // ---- Changes: always made by the running server itself, through its console ------------------------------------

    /** The result of a console command; {@code ok} is false with the server's own reason when it refused. */
    public ServerConsoleClient.Result execute(String command, List<String> answers) {
        if (!isServerRunning()) return new ServerConsoleClient.Result(false, "not-running", List.of());
        ServerConsoleClient console = _console;
        Future<ServerConsoleClient.Result> future = _consoleExec.submit(() -> console.run(command, answers));
        try {
            return future.get(45, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ServerConsoleClient.Result(false, "interrupted", List.of());
        } catch (Exception e) {
            return new ServerConsoleClient.Result(false, "timeout", List.of());
        }
    }

    public ServerConsoleClient.Result createAccount(String username, String password, boolean gameMaster) {
        if (!ServerInput.validUsername(username)) return invalid("username");
        if (!ServerInput.validPassword(password)) return invalid("password");
        return execute("account create " + username + (gameMaster ? " gm" : ""), List.of(password, password));
    }

    public ServerConsoleClient.Result resetPassword(String username, String password) {
        if (!ServerInput.validUsername(username)) return invalid("username");
        if (!ServerInput.validPassword(password)) return invalid("password");
        return execute("account password " + username, List.of(password, password));
    }

    public ServerConsoleClient.Result setGameMaster(String username, boolean gameMaster) {
        if (!ServerInput.validUsername(username)) return invalid("username");
        return execute("gm " + username + " " + gameMaster, List.of());
    }

    /** Deletes the account with its knights (the server asks for its name typed back). */
    public ServerConsoleClient.Result deleteAccount(String username) {
        if (!ServerInput.validUsername(username)) return invalid("username");
        return execute("account delete " + username, List.of(username));
    }

    /** Deletes one knight with its items and balances (the server asks for its name typed back). */
    public ServerConsoleClient.Result deleteKnight(String username, String knight) {
        if (!ServerInput.validUsername(username) || !ServerInput.validKnight(knight)) return invalid("knight");
        return execute("knight delete " + username + " " + knight, List.of(knight));
    }

    /** Sets a balance ({@code crowns} or {@code energy}) to an exact amount. */
    public ServerConsoleClient.Result setBalance(String knight, String currency, long amount) {
        if (!ServerInput.validKnight(knight) || !isCurrency(currency) || amount < 0 || amount > Integer.MAX_VALUE) return invalid("knight");
        return execute("set " + knight + " " + currency + " " + amount, List.of());
    }

    /** Adds to (or, negative, takes from) a balance. */
    public ServerConsoleClient.Result grantBalance(String knight, String currency, long amount) {
        if (!ServerInput.validKnight(knight) || !isCurrency(currency) || Math.abs(amount) > Integer.MAX_VALUE) return invalid("knight");
        return execute("grant " + knight + " " + currency + " " + amount, List.of());
    }

    public ServerConsoleClient.Result setRank(String knight, int rank, int subrank) {
        if (!ServerInput.validKnight(knight) || rank < 1 || rank > 10 || subrank < 1 || subrank > 4) return invalid("knight");
        return execute("rank " + knight + " " + rank + " " + subrank, List.of());
    }

    public ServerConsoleClient.Result setPrestige(String knight, int prestige) {
        if (!ServerInput.validKnight(knight) || prestige < 0) return invalid("knight");
        return execute("prestige " + knight + " " + prestige, List.of());
    }

    public ServerConsoleClient.Result giveItem(String knight, int count, String item) {
        if (!ServerInput.validKnight(knight) || count < 1 || count > 20 || !ServerInput.validItem(item)) return invalid("item");
        return execute("giveitem " + knight + " " + count + " " + item, List.of());
    }

    private static boolean isCurrency(String currency) {
        return "crowns".equals(currency) || "energy".equals(currency);
    }

    private static ServerConsoleClient.Result invalid(String what) {
        return new ServerConsoleClient.Result(false, "invalid-" + what, List.of());
    }

    /** Someone signed in or connected, as the console's {@code players} lists them. */
    public record OnlinePlayer(int session, String account, String knight, String scene, int seconds, String state) {}

    private static final Pattern PLAYER_ROW = Pattern.compile("^(\\d+) \\| (.+?) \\| (.+?) \\| (.+?) \\| (\\d+)s \\| (.+)$");
    private static final Pattern PLAYER_NAME = Pattern.compile("^(.+?) \\(#(\\d+|null)\\)$");

    /** Who is connected right now (empty when the server is not running). Call off the UI thread. */
    public List<OnlinePlayer> getOnlinePlayers() {
        List<OnlinePlayer> players = new ArrayList<>();
        if (!isServerRunning()) return players;
        ServerConsoleClient console = _console;
        Future<ServerConsoleClient.Result> future = _consoleExec.submit(() -> console.query("players"));
        try {
            for (String line : future.get(15, TimeUnit.SECONDS).lines()) {
                OnlinePlayer player = parsePlayerRow(line);
                if (player != null) players.add(player);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // The list stays as far as it got.
        }
        return players;
    }

    static OnlinePlayer parsePlayerRow(String line) {
        Matcher m = PLAYER_ROW.matcher(line);
        if (!m.matches()) return null;
        return new OnlinePlayer(Integer.parseInt(m.group(1)), nameOf(m.group(2)), nameOf(m.group(3)),
            m.group(4).startsWith("null") ? "" : m.group(4), Integer.parseInt(m.group(5)), m.group(6));
    }

    /** "Bob (#4)" is Bob; "null (#null)" is nobody yet. */
    private static String nameOf(String text) {
        Matcher m = PLAYER_NAME.matcher(text);
        return m.matches() && !m.group(2).equals("null") ? m.group(1) : "";
    }

    private void startStatusPolling() {
        stopStatusPolling();
        ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ServerStatusPoll");
            t.setDaemon(true);
            return t;
        });
        _poller = poller;
        ServerConsoleClient console = _console;
        poller.scheduleWithFixedDelay(() -> {
            if (isServerRunning()) {
                try {
                    _consoleExec.submit(() -> console.query("status")).get(15, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception ignored) {
                    // The next round tries again.
                }
            }
        }, 2, 5, TimeUnit.SECONDS);
    }

    private void stopStatusPolling() {
        ScheduledExecutorService poller = _poller;
        _poller = null;
        if (poller != null) poller.shutdownNow();
    }

    /** What a friend needs to join: the addresses and the server's public key (null before the first start made it). */
    public String getPublicKey() {
        File key = new File(serverDir(), "data/keys/public.txt");
        if (!key.isFile()) return null;
        try {
            return Files.readString(key.toPath(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    /** The addresses players can use, each with the port ("192.168.1.5:47624, 10.0.0.2:47624"). */
    public String getJoinAddresses() {
        int port = getServerPort();
        List<String> addresses = new ArrayList<>();
        for (String host : getLanAddress().split(",\\s*")) addresses.add(host + ":" + port);
        return String.join(", ", addresses);
    }

    public long getUptimeMs() {
        return isServerRunning() ? (System.currentTimeMillis() - _startedAt) : 0;
    }

    public void setConfiguredPort(int port) {
        if (ServerNet.validPort(port)) {
            _settingsManager.setValue("server.port", String.valueOf(port));
        }
    }

    public boolean isLanAllowed() {
        return "true".equalsIgnoreCase(_settingsManager.getValue(ALLOW_LAN_KEY, "false"));
    }

    public void setLanAllowed(boolean allowed) {
        _settingsManager.setValue(ALLOW_LAN_KEY, String.valueOf(allowed));
    }

    public String getLanAddress() {
        if (!isLanAllowed()) {
            return ServerNet.LOOPBACK;
        }
        long now = System.currentTimeMillis();
        if (_lanAddress == null || now - _lanAddressAt > 10_000) {
            _lanAddress = ServerNet.findClientAddresses();
            _lanAddressAt = now;
        }
        return _lanAddress;
    }

    public String getLastError() {
        return _lastError;
    }

    /**
     * A line typed into the console view. It is queued behind anything the launcher itself is doing with the console
     * (a password exchange must not be interleaved) and its replies show up as normal output.
     *
     * @return "" when queued, otherwise why it was not.
     */
    public String sendCommand(String command) {
        if (!isServerRunning()) {
            return _localeManager.getValue("m.server_cmd_not_running");
        }
        String first = command.trim().split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
        // Typing stop (with the web admin off) or shutdown ends the process on purpose, which is not a crash.
        if (first.equals("stop") || first.equals("shutdown") || command.trim().equals("SHUT DOWN PROCESS")) {
            _stopRequested = true;
        }
        _consoleExec.execute(() -> writeLine(command));
        return "";
    }

    /** Writes one line to the process. Used by the console client and for text typed in the console view. */
    private boolean writeLine(String line) {
        Writer commands = _commands;
        if (commands == null) {
            return false;
        }
        synchronized (_commandLock) {
            try {
                commands.write(line.replace('\n', ' ').replace('\r', ' ') + "\n");
                commands.flush();
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }

    /** Used by stop: the process is told to end. */
    private boolean sendRaw(String command) {
        return writeLine(command);
    }

    public void addLogListener(Consumer<String> listener) {
        _logListeners.add(listener);
    }

    public void removeLogListener(Consumer<String> listener) {
        _logListeners.remove(listener);
    }

    public List<String> getLogHistory() {
        synchronized (_logHistory) {
            return new ArrayList<>(_logHistory);
        }
    }

    private void dispatchLog(String line) {
        synchronized (_logHistory) {
            if (_logHistory.size() >= MAX_LOG_HISTORY) {
                _logHistory.removeFirst();
            }
            _logHistory.addLast(line);
            if (_logFile != null) {
                try {
                    _logFile.write(line);
                    _logFile.write('\n');
                    _logFile.flush();
                } catch (IOException e) {
                    _logFile = null;
                }
            }
        }

        for (Consumer<String> listener : _logListeners) {
            try {
                listener.accept(line);
            } catch (Exception ignored) {}
        }
    }

    private void ensureLocalServerRegistered(int port) {
        try {
            Server localServer = null;
            for (Server s : _flamingoManager.getServerList()) {
                if (LOCAL_SERVER_NAME.equalsIgnoreCase(s.name)) {
                    localServer = s;
                    break;
                }
            }
            if (localServer == null) {
                localServer = _flamingoManager.newCustomServer(LOCAL_SERVER_NAME);
                _flamingoManager.addCustomServer(localServer, false);
            }
            _settingsManager.setConnectionSettings(
                localServer,
                ServerNet.LOOPBACK,
                String.valueOf(port),
                _publicKey != null ? _publicKey : SettingsEventHandler.DEFAULT_PUBLIC_KEY,
                SettingsEventHandler.DEFAULT_GETDOWN_URL,
                SettingsEventHandler.DEFAULT_CONTENT_URL
            );
            if (_flamingoManager.getSelectedServer() == localServer) {
                _flamingoManager.setSelectedServer(localServer);
            }
        } catch (Exception e) {
            log.error("Could not register local server in FlamingoManager", e);
        }
    }
}
