package com.lucasluqui.launcher.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.lucasluqui.launcher.LauncherGlobals;
import com.lucasluqui.launcher.flamingo.FlamingoManager;
import com.lucasluqui.launcher.flamingo.data.Server;
import com.lucasluqui.launcher.setting.SettingsManager;
import com.lucasluqui.launcher.setting.ui.handler.SettingsEventHandler;
import com.lucasluqui.util.JavaUtil;

import static com.lucasluqui.launcher.Log.log;

/**
 * Starts, stops and talks to the built-in Spiral Knights server emulator.
 * <p>
 * The emulator needs the game's own jars and is built for the launcher's Java, so it runs in a
 * separate JVM ({@link SpiralServer#main}) with the game classpath, never inside the launcher.
 * The launcher keeps only a pipe to it: lines on stdout in, commands on stdin out.
 */
@Singleton
public class ServerHostManager {
    public static final String LOCAL_SERVER_NAME = "Local Server";
    private static final String SERVER_MAIN = "com.lucasluqui.launcher.server.SpiralServer";
    private static final String ALLOW_LAN_KEY = "server.allowLan";
    private static final int MAX_LOG_HISTORY = 500;
    private static final long START_TIMEOUT_SECONDS = 30;

    @Inject private SettingsManager _settingsManager;
    @Inject private FlamingoManager _flamingoManager;

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
    private final List<Consumer<String>> _logListeners = new CopyOnWriteArrayList<>();
    private final LinkedList<String> _logHistory = new LinkedList<>();
    private Writer _logFile; // guarded by _logHistory

    /**
     * Starts the server and waits until it listens or fails.
     *
     * @return Whether it is listening. On false, {@link #getLastError()} says why.
     */
    public synchronized boolean startServer(int port) {
        if (isServerRunning()) {
            return true;
        }
        _lastError = null;
        openLogFile();
        if (!ServerNet.validPort(port)) {
            return fail("Invalid port " + port + " (use 1-65535)");
        }
        if (!new File(LauncherGlobals.USER_DIR, "code/projectx-pcode.jar").isFile()) {
            return fail("Spiral Knights game files not found in " + LauncherGlobals.USER_DIR
                + " (code/projectx-pcode.jar). The server runs on your game install.");
        }

        // The game encrypts the login with this key; the same file is the server's private key.
        try {
            _publicKey = ServerKeys.loadOrCreate(ServerKeys.keyFile(new File(LauncherGlobals.USER_DIR))).publicKeyProperty();
        } catch (IOException e) {
            log.error("Could not prepare the server key", e);
            return fail("Could not prepare the server key: " + e.getMessage());
        }

        List<String> command = new ArrayList<>();
        command.add(JavaUtil.getLauncherJavaExePath(false));
        command.add("-Djava.awt.headless=true");
        command.add("--enable-native-access=ALL-UNNAMED"); // the SQLite driver loads a native library
        command.add("-Dstdout.encoding=UTF-8"); // the output is read as UTF-8
        command.add("-Dstderr.encoding=UTF-8");
        command.add("-cp");
        // pcode first: it carries patched copies of libraries (e.g. Guava) that must win over the launcher's.
        // Only this jar, not config.jar: that one gets patched for private launches and would be locked on Windows.
        command.add(String.join(File.pathSeparator, new File(LauncherGlobals.USER_DIR, "code/projectx-pcode.jar").getAbsolutePath(), launcherJar()));
        command.add(SERVER_MAIN);
        command.add("--port");
        command.add(String.valueOf(port));
        if (isLanAllowed()) {
            command.add("--lan");
        }

        _ready = false;
        _stopRequested = false;
        _players = 0;
        _starting = true;
        CountDownLatch started = new CountDownLatch(1);
        String[] failure = new String[1];
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(new File(LauncherGlobals.USER_DIR));
            builder.redirectErrorStream(true);
            builder.environment().remove("_JAVA_OPTIONS");
            _process = builder.start();
            _commands = new OutputStreamWriter(_process.getOutputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to start server process", e);
            return fail("Could not start the server process: " + e.getMessage());
        }

        Process process = _process;
        Thread reader = new Thread(() -> readOutput(process, started, failure), "BuiltinServerOutput");
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
        ensureLocalServerRegistered(port);
        return true;
    }

    private boolean fail(String message) {
        _lastError = message;
        dispatchLog("[ERROR] " + message);
        closeLogFile();
        return false;
    }

    /** Where everything the server prints is also written, so it can be read after the console is gone. */
    public static File getLogFile() {
        return new File(LauncherGlobals.USER_DIR, "KnightLauncher/server/server.log");
    }

    /** Starts a fresh log for this run, keeping the previous one as server.log.old. */
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

    /** Parses what the server process prints; everything that is not protocol goes to the console. */
    private void readOutput(Process process, CountDownLatch started, String[] failure) {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith(ServerNet.READY)) {
                    _ready = true;
                    started.countDown();
                } else if (line.startsWith(ServerNet.FATAL)) {
                    failure[0] = line.substring(ServerNet.FATAL.length());
                } else if (line.startsWith(ServerNet.PLAYERS)) {
                    try {
                        _players = Integer.parseInt(line.substring(ServerNet.PLAYERS.length()).trim());
                    } catch (NumberFormatException ignored) {}
                } else {
                    dispatchLog(line);
                }
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
            if (_process != process) {
                return; // an older process, or startServer already handled the failure
            }
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
        // Quitting while a start is waiting for the server must not wait out the start timeout:
        // killing the process releases that wait (and the lock) at once.
        Process starting = _process;
        if (_starting && starting != null) {
            _stopRequested = true;
            starting.destroyForcibly();
        }
        synchronized (this) {
            stopLocked();
        }
    }

    private void stopLocked() {
        Process process = _process;
        if (process == null) {
            return;
        }
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
        _process = null;
        _commands = null;
        _ready = false;
        _players = 0;
        _startedAt = 0;
        dispatchLog("[INFO] Server stopped.");
        closeLogFile();
    }

    public synchronized boolean restartServer(int port) {
        stopServer();
        return startServer(port);
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

    public int getActivePlayers() {
        return isServerRunning() ? _players : 0;
    }

    public long getUptimeMs() {
        return isServerRunning() && _startedAt > 0 ? (System.currentTimeMillis() - _startedAt) : 0;
    }

    /** The port to host on when the server is started; the server settings window sets it. */
    public void setConfiguredPort(int port) {
        if (ServerNet.validPort(port)) {
            _settingsManager.setValue("server.port", String.valueOf(port));
        }
    }

    /** Whether other machines may connect (otherwise only this computer can). Takes effect on the next start. */
    public boolean isLanAllowed() {
        return "true".equalsIgnoreCase(_settingsManager.getValue(ALLOW_LAN_KEY, "false"));
    }

    public void setLanAllowed(boolean allowed) {
        _settingsManager.setValue(ALLOW_LAN_KEY, String.valueOf(allowed));
    }

    /** The address(es) others should use to reach this server. */
    public String getLanAddress() {
        if (!isLanAllowed()) {
            return ServerNet.LOOPBACK;
        }
        long now = System.currentTimeMillis();
        if (_lanAddress == null || now - _lanAddressAt > 10_000) { // the UI asks every second
            _lanAddress = ServerNet.findClientAddresses();
            _lanAddressAt = now;
        }
        return _lanAddress;
    }

    public String getLastError() {
        return _lastError;
    }

    /** Sends a console command; the answer shows up in the console like any other server output. */
    public synchronized String sendCommand(String command) {
        if (!isServerRunning()) {
            return "Server is not running.";
        }
        return sendRaw(command) ? "" : "Could not reach the server process.";
    }

    private boolean sendRaw(String command) {
        Writer commands = _commands;
        if (commands == null) {
            return false;
        }
        try {
            commands.write(command.replace('\n', ' ').replace('\r', ' ') + "\n");
            commands.flush();
            return true;
        } catch (IOException e) {
            return false;
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
                    _logFile = null; // stop trying; the console still has everything
                }
            }
        }

        for (Consumer<String> listener : _logListeners) {
            try {
                listener.accept(line);
            } catch (Exception ignored) {}
        }
    }

    private static String launcherJar() {
        try {
            return new File(ServerHostManager.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .getAbsolutePath();
        } catch (Exception e) {
            return new File(LauncherGlobals.USER_DIR, "KnightLauncher.jar").getAbsolutePath();
        }
    }

    /** Makes sure a "Local Server" entry points at this server, without switching the user's selection. */
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
            // The Settings screen shows these values in text fields and a launch saves them back: if the Local Server
            // is the selected one, make it reload, or an old public key in a field could overwrite the new one.
            if (_flamingoManager.getSelectedServer() == localServer) {
                _flamingoManager.setSelectedServer(localServer);
            }
        } catch (Exception e) {
            log.error("Could not register local server in FlamingoManager", e);
        }
    }
}
