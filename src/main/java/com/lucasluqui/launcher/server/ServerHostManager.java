package com.lucasluqui.launcher.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.lucasluqui.launcher.LauncherContext;
import com.lucasluqui.launcher.LocaleManager;
import com.lucasluqui.launcher.flamingo.FlamingoManager;
import com.lucasluqui.launcher.flamingo.data.Server;
import com.lucasluqui.launcher.setting.SettingsManager;
import com.lucasluqui.launcher.setting.ui.handler.SettingsEventHandler;

import static com.lucasluqui.launcher.Log.log;

/**
 * Lifecycle manager and event dispatcher for the built-in Spiral Knights server emulator.
 */
@Singleton
public class ServerHostManager {
    public static final String LOCAL_SERVER_NAME = "Local Server";
    private static final int MAX_LOG_HISTORY = 500;

    @Inject private LauncherContext _ctx;
    @Inject private SettingsManager _settingsManager;
    @Inject private FlamingoManager _flamingoManager;
    @Inject private LocaleManager _localeManager;

    private SpiralServer _server;
    private Thread _serverThread;
    private int _port = SpiralServer.DEFAULT_PORT;
    private long _startedAt = 0;
    private final List<Consumer<String>> _logListeners = new CopyOnWriteArrayList<>();
    private final LinkedList<String> _logHistory = new LinkedList<>();

    public synchronized boolean startServer(int port) {
        if (isServerRunning()) {
            return true;
        }
        this._port = port > 0 ? port : SpiralServer.DEFAULT_PORT;
        try {
            _settingsManager.setValue("server.port", String.valueOf(this._port));
            _server = new SpiralServer(this._port);
            _server.addLogListener(this::dispatchLog);

            _serverThread = new Thread(() -> {
                try {
                    _server.start();
                } catch (IOException e) {
                    dispatchLog("[ERROR] Failed to start server: " + e.getMessage());
                    log.error("Server host error", e);
                }
            }, "BuiltinServerThread");
            _serverThread.setDaemon(true);
            _serverThread.start();

            _startedAt = System.currentTimeMillis();
            ensureLocalServerRegistered(this._port);
            return true;
        } catch (Exception e) {
            dispatchLog("[ERROR] Could not initialize server: " + e.getMessage());
            log.error("Failed to start server", e);
            return false;
        }
    }

    public synchronized void stopServer() {
        if (_server != null) {
            _server.stop();
            _server = null;
        }
        if (_serverThread != null) {
            _serverThread.interrupt();
            _serverThread = null;
        }
        _startedAt = 0;
        dispatchLog("[INFO] Server stopped.");
    }

    public synchronized void restartServer(int port) {
        stopServer();
        startServer(port);
    }

    public boolean isServerRunning() {
        return _server != null && _server.isRunning();
    }

    public int getServerPort() {
        if (_server != null) {
            return _server.getPort();
        }
        try {
            return Integer.parseInt(_settingsManager.getValue("server.port", String.valueOf(SpiralServer.DEFAULT_PORT)));
        } catch (Exception e) {
            return _port;
        }
    }

    public int getActivePlayers() {
        return _server != null ? _server.getSessionCount() : 0;
    }

    public long getUptimeMs() {
        return isServerRunning() && _startedAt > 0 ? (System.currentTimeMillis() - _startedAt) : 0;
    }

    public String getLanAddress() {
        return SpiralServer.findClientAddresses();
    }

    public String sendCommand(String command) {
        if (!isServerRunning()) {
            return "Server is not running.";
        }
        return _server.executeCommand(command);
    }

    public void addLogListener(Consumer<String> listener) {
        _logListeners.add(listener);
    }

    public void removeLogListener(Consumer<String> listener) {
        _logListeners.remove(listener);
    }

    public synchronized List<String> getLogHistory() {
        return new ArrayList<>(_logHistory);
    }

    private synchronized void dispatchLog(String line) {
        if (_logHistory.size() >= MAX_LOG_HISTORY) {
            _logHistory.removeFirst();
        }
        _logHistory.addLast(line);

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
                localServer.description = "Built-in local game server";
                _flamingoManager.addCustomServer(localServer);
            }
            _settingsManager.setConnectionSettings(
                localServer,
                "127.0.0.1",
                String.valueOf(port),
                SettingsEventHandler.DEFAULT_PUBLIC_KEY,
                SettingsEventHandler.DEFAULT_GETDOWN_URL,
                SettingsEventHandler.DEFAULT_CONTENT_URL
            );
        } catch (Exception e) {
            log.error("Could not register local server in FlamingoManager", e);
        }
    }
}
