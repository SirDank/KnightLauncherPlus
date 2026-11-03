package com.lucasluqui.launcher.server.ui.handler;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.lucasluqui.launcher.LauncherContext;
import com.lucasluqui.launcher.flamingo.FlamingoManager;
import com.lucasluqui.launcher.flamingo.data.Server;
import com.lucasluqui.launcher.server.ServerHostManager;
import com.lucasluqui.launcher.server.ui.ServerHostUI;
import com.lucasluqui.launcher.ui.LauncherUI;

/**
 * Handles UI events and actions for ServerHostUI.
 */
@Singleton
public class ServerEventHandler {
    @Inject private LauncherContext _ctx;
    @Inject private ServerHostManager _hostManager;
    @Inject private FlamingoManager _flamingoManager;

    private ServerHostUI _ui;
    private Timer _statusTimer;

    public void init(ServerHostUI ui) {
        this._ui = ui;
        startStatusTimer();
    }

    public void startServer() {
        int port = parsePort();
        new Thread(() -> {
            boolean success = _hostManager.startServer(port);
            SwingUtilities.invokeLater(() -> updateStatus());
        }, "ServerStartThread").start();
    }

    public void stopServer() {
        new Thread(() -> {
            _hostManager.stopServer();
            SwingUtilities.invokeLater(() -> updateStatus());
        }, "ServerStopThread").start();
    }

    public void restartServer() {
        int port = parsePort();
        new Thread(() -> {
            _hostManager.restartServer(port);
            SwingUtilities.invokeLater(() -> updateStatus());
        }, "ServerRestartThread").start();
    }

    public void connectAndLaunch() {
        // Find Local Server in FlamingoManager
        Server target = null;
        for (Server s : _flamingoManager.getServerList()) {
            if (ServerHostManager.LOCAL_SERVER_NAME.equalsIgnoreCase(s.name)) {
                target = s;
                break;
            }
        }
        if (target != null) {
            _flamingoManager.setSelectedServer(target);
            LauncherUI launcherUI = _ctx.getApp().getUI(LauncherUI.class);
            if (launcherUI != null) {
                launcherUI.selectedServerChanged();
                launcherUI.eventHandler.launchGameEvent(false);
            }
        }
    }

    public void copyAddress() {
        String addr = _hostManager.getLanAddress();
        int port = _hostManager.getServerPort();
        String text = addr + ":" + port;
        StringSelection selection = new StringSelection(text);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
    }

    public void updateStatus() {
        if (_ui == null) return;
        boolean running = _hostManager.isServerRunning();
        int players = _hostManager.getActivePlayers();
        long uptimeMs = _hostManager.getUptimeMs();
        int port = _hostManager.getServerPort();

        _ui.setStatus(running, port, players, uptimeMs);
    }

    private int parsePort() {
        if (_ui == null) return _hostManager.getServerPort();
        try {
            return Integer.parseInt(_ui.getPortText().trim());
        } catch (Exception e) {
            return _hostManager.getServerPort();
        }
    }

    private void startStatusTimer() {
        if (_statusTimer != null) return;
        _statusTimer = new Timer(1000, e -> updateStatus());
        _statusTimer.start();
    }
}
