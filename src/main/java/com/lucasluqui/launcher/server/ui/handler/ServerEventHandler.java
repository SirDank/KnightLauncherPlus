package com.lucasluqui.launcher.server.ui.handler;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.lucasluqui.dialog.Dialog;
import com.lucasluqui.launcher.LauncherContext;
import com.lucasluqui.launcher.LocaleManager;
import com.lucasluqui.launcher.flamingo.FlamingoManager;
import com.lucasluqui.launcher.flamingo.data.Server;
import com.lucasluqui.launcher.server.ServerHostManager;
import com.lucasluqui.launcher.server.ui.ServerHostUI;
import com.lucasluqui.launcher.server.ui.ServerSettingsDialog;
import com.lucasluqui.launcher.ui.LauncherUI;

/**
 * Handles UI events and actions for ServerHostUI.
 */
@Singleton
public class ServerEventHandler {
    @Inject private LauncherContext _ctx;
    @Inject private ServerHostManager _hostManager;
    @Inject private FlamingoManager _flamingoManager;
    @Inject private LocaleManager _localeManager;

    /** Start, stop and restart actions in progress. */
    private final java.util.concurrent.atomic.AtomicInteger _busy = new java.util.concurrent.atomic.AtomicInteger();
    private ServerHostUI _ui;
    private Timer _statusTimer;
    private ServerSettingsDialog _settings;

    public void init(ServerHostUI ui) {
        this._ui = ui;
        startStatusTimer();
    }

    public void startServer() {
        int port = parsePort();
        if (port < 0) return;
        runServerAction(() -> _hostManager.startServer(port));
    }

    public void stopServer() {
        _busy.incrementAndGet();
        updateStatus();
        new Thread(() -> {
            try {
                _hostManager.stopServer();
            } finally {
                _busy.decrementAndGet();
                SwingUtilities.invokeLater(this::updateStatus);
            }
        }, "ServerStopThread").start();
    }

    public void restartServer() {
        int port = parsePort();
        if (port < 0) return;
        runServerAction(() -> _hostManager.restartServer(port));
    }

    /** Opens the window with every server setting (one at a time). */
    public void openSettings() {
        if (_settings != null && _settings.isDisplayable()) {
            _settings.toFront();
            return;
        }
        LauncherUI launcherUI = _ctx.getApp().getUI(LauncherUI.class);
        _settings = new ServerSettingsDialog(launcherUI == null ? null : launcherUI.guiFrame, _hostManager, _localeManager,
            () -> {
                _hostManager.invalidateConfigCache();
                if (_ui != null) _ui.refreshFromSettings();
                updateStatus();
            }, this::restartServer);
        _settings.setVisible(true);
    }

    /** Runs a start/restart off the EDT and tells the user if the server did not come up. */
    private void runServerAction(java.util.function.BooleanSupplier action) {
        _busy.incrementAndGet();
        updateStatus(); // the buttons go off now, not when the thread gets going
        new Thread(() -> {
            boolean success;
            try {
                success = action.getAsBoolean();
            } finally {
                _busy.decrementAndGet();
            }
            SwingUtilities.invokeLater(() -> {
                updateStatus();
                if (!success) {
                    Dialog.push(_localeManager.getValue("m.server_start_failed", String.valueOf(_hostManager.getLastError())),
                        JOptionPane.ERROR_MESSAGE);
                }
            });
        }, "ServerStartThread").start();
    }

    public void connectAndLaunch() {
        if (!_hostManager.isServerRunning()) {
            Dialog.push(_localeManager.getValue("m.server_not_running"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        Server target = null;
        for (Server s : _flamingoManager.getServerList()) {
            if (ServerHostManager.LOCAL_SERVER_NAME.equalsIgnoreCase(s.name)) {
                target = s;
                break;
            }
        }
        if (target == null) {
            Dialog.push(_localeManager.getValue("m.server_not_running"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        _flamingoManager.setSelectedServer(target); // also refreshes the launcher UI
        LauncherUI launcherUI = _ctx.getApp().getUI(LauncherUI.class);
        if (launcherUI != null) {
            launcherUI.eventHandler.launchGameEvent(false);
        }
    }

    public void copyAddress() {
        int port = _hostManager.getServerPort();
        List<String> addresses = new ArrayList<>();
        for (String host : _hostManager.getLanAddress().split(",\\s*")) {
            addresses.add(host + ":" + port);
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(String.join(", ", addresses)), null);
        Dialog.push(_localeManager.getValue("m.address_copied"), JOptionPane.INFORMATION_MESSAGE);
    }

    public void updateStatus() {
        if (_ui == null) return;
        _ui.setStatus(_hostManager.isServerRunning(), _hostManager.getServerPort(),
            _hostManager.getActivePlayers(), _hostManager.getUptimeMs(), _busy.get() > 0 || _hostManager.isStarting());
    }

    /** The port from the server settings (the Server Settings window is where it is changed). */
    private int parsePort() {
        return _hostManager.getServerPort();
    }

    private void startStatusTimer() {
        if (_statusTimer != null) return;
        _statusTimer = new Timer(1000, e -> updateStatus());
        _statusTimer.start();
    }
}
