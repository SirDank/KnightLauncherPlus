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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    private volatile int _players = 0;
    private volatile String _lastError = null;
    private volatile String _lanAddress = null;
    private volatile long _lanAddressAt = 0;
    private volatile String _publicKey = null;
    private volatile int _port = ServerNet.DEFAULT_PORT;
    private volatile long _startedAt = 0;
    /** Serializes start, stop, restart and reset. Never held by anything the UI calls on its own thread. */
    private final Object _lifecycle = new Object();
    private final Object _commandLock = new Object();
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
            return fail("Invalid port " + port + " (use 1-65535)");
        }

        File serverDir = new File(LauncherGlobals.USER_DIR, "KnightLauncher/server");
        File configFile = new File(serverDir, "config/server.yml");

        String problem = TortodroneInstaller.ensureInstalled(this::dispatchLog); // quick when the engine is already current
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
        _players = 0;
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
            return fail("Could not start server process: " + e.getMessage());
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
                : answered ? "The server process exited before it was ready"
                : "The server did not start within " + START_TIMEOUT_SECONDS + "s");
        }

        _port = port;
        _startedAt = System.currentTimeMillis();
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
                } else if (line.contains("Server startup failed:") || line.startsWith(ServerNet.FATAL)) {
                    failure[0] = line;
                } else if (line.startsWith(ServerNet.PLAYERS)) {
                    try {
                        _players = Integer.parseInt(line.substring(ServerNet.PLAYERS.length()).trim());
                    } catch (NumberFormatException ignored) {}
                }
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
                dispatchLog("[ERROR] The server process ended unexpectedly (exit code " + code + ").");
            }
            _process = null;
            _commands = null;
            _ready = false;
            _players = 0;
            _startedAt = 0;
        }
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
                _players = 0;
                _startedAt = 0;
            }
        }
        dispatchLog("[INFO] Server stopped.");
        closeLogFile();
    }

    public boolean restartServer(int port) {
        synchronized (_lifecycle) {
            stopServer();
            return startServer(port);
        }
    }

    public boolean resetServerData() {
        synchronized (_lifecycle) {
            return resetLocked();
        }
    }

    private boolean resetLocked() {
        stopServer();
        File serverDir = new File(LauncherGlobals.USER_DIR, "KnightLauncher/server");
        File dataDir = new File(serverDir, "data");

        if (dataDir.exists()) {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            File backupDir = new File(serverDir, "data_backup_" + ts);
            try {
                Files.move(dataDir.toPath(), backupDir.toPath(), StandardCopyOption.REPLACE_EXISTING);
                dispatchLog("[INFO] Existing server data archived to " + backupDir.getName());
            } catch (IOException e) {
                log.error("Could not move data to backup folder", e);
                return false;
            }
        }

        File logFile = new File(serverDir, "server.log");
        File oldLogFile = new File(serverDir, "server.log.old");
        if (logFile.exists()) logFile.delete();
        if (oldLogFile.exists()) oldLogFile.delete();

        synchronized (_logHistory) {
            _logHistory.clear();
        }

        dispatchLog("[INFO] Server data has been reset to a clean state.");
        return true;
    }

    public boolean isServerRunning() {
        Process process = _process;
        return _ready && process != null && process.isAlive();
    }

    public int getServerPort() {
        if (isServerRunning()) {
            return _port;
        }
        try {
            return Integer.parseInt(_settingsManager.getValue("server.port", String.valueOf(ServerNet.DEFAULT_PORT)));
        } catch (Exception e) {
            return _port;
        }
    }

    /** True from the moment the process is launched until it is ready (or has failed). */
    public boolean isStarting() {
        return _starting;
    }

    public int getActivePlayers() {
        return _players;
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

    public static record ServerAccount(int id, String username, boolean gameMaster, long createdAt, int knightCount, String knights) {}

    private static File databaseFile() {
        return new File(LauncherGlobals.USER_DIR, "KnightLauncher/server/data/tortodrone.db");
    }

    /** Opens the server's database, waiting a moment (not failing at once) if the running server holds it. */
    private static Connection connect(boolean readOnly) throws java.sql.SQLException {
        Properties properties = new Properties();
        if (readOnly) properties.setProperty("open_mode", "1"); // SQLITE_OPEN_READONLY
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile().getAbsolutePath(), properties);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 3000");
            if (!readOnly) statement.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    public List<ServerAccount> getAccounts() {
        List<ServerAccount> list = new ArrayList<>();
        if (!databaseFile().isFile()) return list;
        try (Connection conn = connect(true);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT a.id, a.username, a.game_master, a.created_at, " +
                 "COUNT(k.id) AS k_count, GROUP_CONCAT(k.name, ', ') AS k_names " +
                 "FROM accounts a LEFT JOIN knights k ON a.id = k.account_id " +
                 "GROUP BY a.id ORDER BY a.id ASC")) {
            while (rs.next()) {
                list.add(new ServerAccount(
                    rs.getInt("id"),
                    rs.getString("username"),
                    rs.getInt("game_master") != 0,
                    rs.getLong("created_at"),
                    rs.getInt("k_count"),
                    rs.getString("k_names")
                ));
            }
        } catch (Exception e) {
            log.warning("Could not read accounts from tortodrone.db: " + e.getMessage());
        }
        return list;
    }

    /**
     * Creates an account. A running server does it itself (it asks for the password twice on its console; the server
     * is the only writer of its database while it runs). Without a running server there is nobody to hash the
     * password, so this needs the server.
     *
     * @return Whether the account exists afterwards (waits a moment for the server to finish).
     */
    public boolean createAccount(String username, String password, boolean gameMaster) {
        if (!isServerRunning()) return false;
        if (accountExists(username)) return false; // the password lines would reach the console as commands
        if (!sendRaw("account create " + username + (gameMaster ? " gm" : ""))) return false;
        sendRaw(password);
        sendRaw(password);
        for (int i = 0; i < 20; i++) {
            if (accountExists(username)) return true;
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean accountExists(String username) {
        for (ServerAccount account : getAccounts()) {
            if (account.username().equalsIgnoreCase(username)) return true;
        }
        return false;
    }

    /**
     * Flips the account's game master flag. A running server does it itself (it keeps caches and revisions that
     * writing the database behind its back would leave stale); a stopped one is edited directly.
     */
    public boolean toggleGameMaster(int accountId, String username, boolean currentGm) {
        boolean newGm = !currentGm;
        if (isServerRunning()) {
            return sendRaw("gm " + username + " " + newGm);
        }
        if (!databaseFile().isFile()) return false;
        try (Connection conn = connect(false);
             PreparedStatement ps = conn.prepareStatement("UPDATE accounts SET game_master = ? WHERE id = ?")) {
            ps.setInt(1, newGm ? 1 : 0);
            ps.setInt(2, accountId);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            log.warning("Could not update game_master in tortodrone.db: " + e.getMessage());
            return false;
        }
    }

    /** Deletes the account with its knights: by the running server itself (it asks to confirm by typing the name), else directly. */
    public boolean deleteAccount(int accountId, String username) {
        if (isServerRunning()) {
            return sendRaw("account delete " + username) && sendRaw(username);
        }
        if (!databaseFile().isFile()) return false;
        try (Connection conn = connect(false);
             PreparedStatement ps = conn.prepareStatement("DELETE FROM accounts WHERE id = ?")) {
            ps.setInt(1, accountId);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            log.warning("Could not delete account in tortodrone.db: " + e.getMessage());
            return false;
        }
    }

    public List<String> getAllKnights() {
        List<String> list = new ArrayList<>();
        if (!databaseFile().isFile()) return list;
        try (Connection conn = connect(true);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT DISTINCT name FROM knights ORDER BY name ASC")) {
            while (rs.next()) {
                list.add(rs.getString("name"));
            }
        } catch (Exception e) {
            log.warning("Could not read knights from tortodrone.db: " + e.getMessage());
        }
        return list;
    }

    public long getUptimeSeconds() {
        return isServerRunning() ? (System.currentTimeMillis() - _startedAt) / 1000 : 0;
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

    public String sendCommand(String command) {
        if (!isServerRunning()) {
            return _localeManager.getValue("m.server_cmd_not_running");
        }
        return sendRaw(command) ? "" : _localeManager.getValue("m.server_cmd_unreachable");
    }

    public boolean sendRaw(String command) {
        Writer commands = _commands;
        if (commands == null) {
            return false;
        }
        synchronized (_commandLock) { // lines of one multi-line exchange (a password prompt) must not interleave
            try {
                commands.write(command.replace('\n', ' ').replace('\r', ' ') + "\n");
                commands.flush();
                return true;
            } catch (IOException e) {
                return false;
            }
        }
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
