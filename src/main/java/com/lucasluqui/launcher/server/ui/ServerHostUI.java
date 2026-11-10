package com.lucasluqui.launcher.server.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;

import com.formdev.flatlaf.FlatClientProperties;
import com.google.inject.Inject;
import com.lucasluqui.launcher.CustomColors;
import com.lucasluqui.launcher.Fonts;
import com.lucasluqui.launcher.LocaleManager;
import com.lucasluqui.launcher.server.ServerHostManager;
import com.lucasluqui.launcher.server.ui.handler.ServerEventHandler;
import com.lucasluqui.launcher.ui.BaseUI;
import com.lucasluqui.swing.FontIcon;
import com.lucasluqui.swing.Scale;
import jiconfont.icons.font_awesome.FontAwesome;

/**
 * Modern hosting and management panel for the built-in Spiral Knights server emulator.
 * Rendered directly in the main launcher pane when selected.
 */
public class ServerHostUI extends BaseUI {
    private static final Color COLOR_RUNNING = new Color(0x4C, 0xAF, 0x50);
    private static final Color COLOR_STOPPED = new Color(0xE5, 0x39, 0x35);
    private static final Color COLOR_ACCENT = CustomColors.ACCENT;

    @Inject private ServerEventHandler eventHandler;
    @Inject private ServerHostManager hostManager;
    @Inject private LocaleManager _localeManager;

    private JLabel statusBadge;
    private JLabel uptimeText;
    private JLabel playersText;
    private JLabel lanAddressText;
    private JButton startButton;
    private JButton stopButton;
    private JButton restartButton;
    private JButton connectButton;
    private JButton settingsButton;
    private JButton copyLanButton;
    private ServerConsolePanel consolePanel;

    @Inject
    public ServerHostUI() {
        super(800, 470, false); // what fits beside the launcher's sidebar (see LauncherApp.showUI)
    }

    @Override
    public void init() {
        super.init();
        compose();
        initFinished();
    }

    @Override
    public void initFinished() {
        super.initFinished();
        eventHandler.init(this);
        eventHandler.updateStatus();
    }

    @Override
    public void loadOnline() {
        // empty
    }

    private void compose() {
        guiFrame.setVisible(false);
        guiFrame.setTitle(_localeManager.getValue("t.server_hosting"));
        guiFrame.setBounds(Scale.rect(100, 100, this.width, this.height));
        guiFrame.setResizable(false);
        guiFrame.setUndecorated(true);
        guiFrame.getContentPane().setBackground(CustomColors.INTERFACE_MAINPANE_BACKGROUND);
        guiFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        guiFrame.getContentPane().setLayout(new BorderLayout(0, Scale.px(8)));
        panel = (JPanel) guiFrame.getContentPane();
        panel.setBorder(BorderFactory.createEmptyBorder(0, Scale.px(20), Scale.px(16), Scale.px(20)));

        // Top Section: Single Consolidated Control & Status Toolbar
        JPanel topContainer = new JPanel(new BorderLayout(Scale.px(10), 0));
        topContainer.setBackground(CustomColors.INTERFACE_MAINPANE_BACKGROUND);

        // Left Controls: Start, Stop, Restart
        JPanel leftControls = new JPanel();
        leftControls.setLayout(new javax.swing.BoxLayout(leftControls, javax.swing.BoxLayout.X_AXIS));
        leftControls.setBackground(CustomColors.INTERFACE_MAINPANE_BACKGROUND);

        startButton = new JButton(_localeManager.getValue("b.start_server"));
        startButton.setIcon(FontIcon.of(FontAwesome.PLAY, Scale.px(12), Color.WHITE));
        startButton.setFont(Fonts.getFont("default", 11.0f, Font.BOLD));
        startButton.setBackground(COLOR_RUNNING);
        startButton.setForeground(Color.WHITE);
        startButton.setFocusPainted(false);
        startButton.putClientProperty(FlatClientProperties.STYLE, "arc: 6; borderWidth: 0; margin: 4,10,4,10; disabledBackground: #1E1E1E; disabledText: #777777");
        startButton.addActionListener(e -> eventHandler.startServer());
        leftControls.add(startButton);
        leftControls.add(javax.swing.Box.createHorizontalStrut(Scale.px(8)));

        stopButton = new JButton(_localeManager.getValue("b.stop_server"));
        stopButton.setIcon(FontIcon.of(FontAwesome.STOP, Scale.px(12), Color.WHITE));
        stopButton.setFont(Fonts.getFont("default", 11.0f, Font.BOLD));
        stopButton.setBackground(COLOR_STOPPED);
        stopButton.setForeground(Color.WHITE);
        stopButton.setFocusPainted(false);
        stopButton.putClientProperty(FlatClientProperties.STYLE, "arc: 6; borderWidth: 0; margin: 4,10,4,10; disabledBackground: #1E1E1E; disabledText: #777777");
        stopButton.addActionListener(e -> eventHandler.stopServer());
        leftControls.add(stopButton);
        leftControls.add(javax.swing.Box.createHorizontalStrut(Scale.px(8)));

        restartButton = new JButton(_localeManager.getValue("b.restart_server"));
        restartButton.setIcon(FontIcon.of(FontAwesome.REFRESH, Scale.px(12), Color.WHITE));
        restartButton.setFont(Fonts.getFont("default", 11.0f, Font.BOLD));
        restartButton.setBackground(CustomColors.INTERFACE_BUTTON_BACKGROUND);
        restartButton.setForeground(Color.WHITE);
        restartButton.setFocusPainted(false);
        restartButton.putClientProperty(FlatClientProperties.STYLE, "arc: 6; borderWidth: 0; margin: 4,10,4,10; disabledBackground: #1E1E1E; disabledText: #777777");
        restartButton.addActionListener(e -> eventHandler.restartServer());
        leftControls.add(restartButton);

        topContainer.add(leftControls, BorderLayout.WEST);

        // Center Status: Dot, 0/max Online, Uptime, LAN address, Copy button
        JPanel centerStatus = new JPanel(new FlowLayout(FlowLayout.CENTER, Scale.px(8), Scale.px(4)));
        centerStatus.setBackground(CustomColors.INTERFACE_MAINPANE_BACKGROUND);

        statusBadge = new JLabel();
        setStatusDot(COLOR_STOPPED);
        centerStatus.add(statusBadge);

        int maxClients = hostManager.getMaxClients();
        playersText = new JLabel("0/" + maxClients + " Online");
        playersText.setFont(Fonts.getFont("default", 11.0f, Font.PLAIN));
        playersText.setForeground(new Color(0xAA, 0xAA, 0xAA));
        centerStatus.add(playersText);

        uptimeText = new JLabel(_localeManager.getValue("m.server_uptime_none"));
        uptimeText.setFont(Fonts.getFont("default", 11.0f, Font.PLAIN));
        uptimeText.setForeground(new Color(0x88, 0x88, 0x88));
        centerStatus.add(uptimeText);

        lanAddressText = new JLabel(_localeManager.getValue("m.server_lan_address", hostManager.getLanAddress()));
        lanAddressText.setFont(Fonts.getFont("code", 11.0f, Font.PLAIN));
        lanAddressText.setForeground(new Color(0x9E, 0x9E, 0x9E));
        centerStatus.add(lanAddressText);

        copyLanButton = new JButton(FontIcon.of(FontAwesome.CLIPBOARD, Scale.px(13), Color.WHITE));
        copyLanButton.setToolTipText(_localeManager.getValue("b.copy_lan_ip"));
        copyLanButton.setBackground(CustomColors.INTERFACE_BUTTON_BACKGROUND);
        copyLanButton.setForeground(Color.WHITE);
        copyLanButton.setFocusPainted(false);
        copyLanButton.putClientProperty(FlatClientProperties.STYLE, "arc: 6; borderWidth: 0; margin: 2,6,2,6; disabledBackground: #1E1E1E; disabledText: #777777");
        copyLanButton.addActionListener(e -> eventHandler.copyAddress());
        centerStatus.add(copyLanButton);

        topContainer.add(centerStatus, BorderLayout.CENTER);

        // Right Controls: Icon-only Settings and Connect & Play buttons
        JPanel rightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, Scale.px(6), 0));
        rightControls.setBackground(CustomColors.INTERFACE_MAINPANE_BACKGROUND);

        settingsButton = new JButton(FontIcon.of(FontAwesome.COGS, Scale.px(14), Color.WHITE));
        settingsButton.setToolTipText(_localeManager.getValue("b.server_settings"));
        settingsButton.setFont(Fonts.getFont("default", 11.0f, Font.BOLD));
        settingsButton.setBackground(CustomColors.INTERFACE_BUTTON_BACKGROUND);
        settingsButton.setForeground(Color.WHITE);
        settingsButton.setFocusPainted(false);
        settingsButton.putClientProperty(FlatClientProperties.STYLE, "arc: 6; borderWidth: 0; margin: 4,8,4,8; disabledBackground: #1E1E1E; disabledText: #777777");
        settingsButton.addActionListener(e -> eventHandler.openSettings());
        rightControls.add(settingsButton);

        connectButton = new JButton(FontIcon.of(FontAwesome.GAMEPAD, Scale.px(14), Color.WHITE));
        connectButton.setToolTipText(_localeManager.getValue("b.connect_server"));
        connectButton.setFont(Fonts.getFont("default", 11.0f, Font.BOLD));
        connectButton.setBackground(COLOR_ACCENT);
        connectButton.setForeground(Color.WHITE);
        connectButton.setFocusPainted(false);
        connectButton.putClientProperty(FlatClientProperties.STYLE, "arc: 6; borderWidth: 0; margin: 4,8,4,8; disabledBackground: #1E1E1E; disabledText: #777777");
        connectButton.addActionListener(e -> eventHandler.connectAndLaunch());
        rightControls.add(connectButton);

        topContainer.add(rightControls, BorderLayout.EAST);
        panel.add(topContainer, BorderLayout.NORTH);

        // Console Panel in Center
        consolePanel = new ServerConsolePanel(hostManager, _localeManager);
        panel.add(consolePanel, BorderLayout.CENTER);
    }

    private void setStatusDot(Color color) {
        statusBadge.setIcon(FontIcon.of(FontAwesome.CIRCLE, Scale.px(14), color));
    }

    private boolean _blocked = false;

    @Override
    public void toggleElementsBlock(boolean block) {
        super.toggleElementsBlock(block);
        this._blocked = block;
        if (returnButton != null) {
            returnButton.setEnabled(!block);
        }
        if (block) {
            startButton.setEnabled(false);
            stopButton.setEnabled(false);
            restartButton.setEnabled(false);
            connectButton.setEnabled(false);
            settingsButton.setEnabled(false);
            copyLanButton.setEnabled(false);
        } else {
            settingsButton.setEnabled(true);
            copyLanButton.setEnabled(true);
            eventHandler.updateStatus();
        }
        if (consolePanel != null) {
            consolePanel.setBlocked(block);
        }
    }

    /** @param busy A start, stop or restart is under way: the three server buttons stay off until it is done. */
    public void setStatus(boolean running, int port, int players, long uptimeMs, boolean busy) {
        if (statusBadge == null) return;

        if (_blocked) {
            startButton.setEnabled(false);
            stopButton.setEnabled(false);
            restartButton.setEnabled(false);
            connectButton.setEnabled(false);
            settingsButton.setEnabled(false);
            copyLanButton.setEnabled(false);
        } else if (busy) {
            startButton.setEnabled(false);
            stopButton.setEnabled(false);
            restartButton.setEnabled(false);
            connectButton.setEnabled(false);
            settingsButton.setEnabled(true);
            copyLanButton.setEnabled(true);
        } else if (running) {
            setStatusDot(COLOR_RUNNING);
            statusBadge.setToolTipText(_localeManager.getValue("m.server_running"));
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            restartButton.setEnabled(true);
            connectButton.setEnabled(true);
            settingsButton.setEnabled(true);
            copyLanButton.setEnabled(true);
        } else {
            setStatusDot(COLOR_STOPPED);
            statusBadge.setToolTipText(_localeManager.getValue("m.server_stopped"));
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
            restartButton.setEnabled(false);
            connectButton.setEnabled(false);
            settingsButton.setEnabled(true);
            copyLanButton.setEnabled(true);
        }

        int max = hostManager.getMaxClients();
        playersText.setText(players + "/" + max + " Online");
        lanAddressText.setText(_localeManager.getValue("m.server_lan_address", hostManager.getLanAddress() + ":" + port));

        if (uptimeMs > 0) {
            long seconds = (uptimeMs / 1000) % 60;
            long minutes = (uptimeMs / (1000 * 60)) % 60;
            long hours = (uptimeMs / (1000 * 60 * 60));
            uptimeText.setText(_localeManager.getValue("m.server_uptime", String.format("%02d:%02d:%02d", hours, minutes, seconds)));
        } else {
            uptimeText.setText(_localeManager.getValue("m.server_uptime_none"));
        }
    }

    /** The server settings window saved something (the port or LAN access): shows the address again. */
    public void refreshFromSettings() {
        if (lanAddressText == null) return;
        lanAddressText.setText(_localeManager.getValue("m.server_lan_address",
            hostManager.getLanAddress() + ":" + hostManager.getServerPort()));
    }
}
