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
    private JLabel statusText;
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

        // Top Section: Status Card + Control Toolbar
        JPanel topContainer = new JPanel(new BorderLayout(0, Scale.px(8)));
        topContainer.setBackground(CustomColors.INTERFACE_MAINPANE_BACKGROUND);

        // Status Card
        JPanel statusCard = new JPanel(new BorderLayout(Scale.px(10), 0));
        statusCard.setBackground(CustomColors.INTERFACE_BUTTON_BACKGROUND);
        statusCard.putClientProperty(FlatClientProperties.STYLE, "arc: 8");
        statusCard.setBorder(BorderFactory.createEmptyBorder(Scale.px(8), Scale.px(12), Scale.px(8), Scale.px(12)));

        JPanel leftStatus = new JPanel(new FlowLayout(FlowLayout.LEFT, Scale.px(10), 0));
        leftStatus.setBackground(CustomColors.INTERFACE_BUTTON_BACKGROUND);

        // An icon, not the "●" character: the launcher's font has no glyph for it and drew an empty box.
        statusBadge = new JLabel();
        setStatusDot(COLOR_STOPPED);
        leftStatus.add(statusBadge);

        statusText = new JLabel(_localeManager.getValue("m.server_stopped"));
        statusText.setFont(Fonts.getFont("default", 12.0f, Font.BOLD));
        statusText.setForeground(Color.WHITE);
        leftStatus.add(statusText);

        playersText = new JLabel(_localeManager.getValue("m.server_no_players"));
        playersText.setFont(Fonts.getFont("default", 11.0f, Font.PLAIN));
        playersText.setForeground(new Color(0xAA, 0xAA, 0xAA));
        leftStatus.add(playersText);

        uptimeText = new JLabel(_localeManager.getValue("m.server_uptime_none"));
        uptimeText.setFont(Fonts.getFont("default", 11.0f, Font.PLAIN));
        uptimeText.setForeground(new Color(0x88, 0x88, 0x88));
        leftStatus.add(uptimeText);

        statusCard.add(leftStatus, BorderLayout.WEST);

        // LAN IP Display
        JPanel rightStatus = new JPanel(new FlowLayout(FlowLayout.RIGHT, Scale.px(6), 0));
        rightStatus.setBackground(CustomColors.INTERFACE_BUTTON_BACKGROUND);

        lanAddressText = new JLabel(_localeManager.getValue("m.server_lan_address", hostManager.getLanAddress()));
        lanAddressText.setFont(Fonts.getFont("code", 11.0f, Font.PLAIN));
        lanAddressText.setForeground(new Color(0x9E, 0x9E, 0x9E));
        rightStatus.add(lanAddressText);

        copyLanButton = new JButton(FontIcon.of(FontAwesome.CLIPBOARD, Scale.px(14), Color.WHITE));
        copyLanButton.setToolTipText(_localeManager.getValue("b.copy_lan_ip"));
        copyLanButton.setBackground(CustomColors.INTERFACE_BUTTON_BACKGROUND);
        copyLanButton.setForeground(Color.WHITE);
        copyLanButton.setFocusPainted(false);
        copyLanButton.putClientProperty(FlatClientProperties.STYLE, "arc: 6; borderWidth: 0; margin: 2,6,2,6");
        copyLanButton.addActionListener(e -> eventHandler.copyAddress());
        rightStatus.add(copyLanButton);

        statusCard.add(rightStatus, BorderLayout.EAST);
        topContainer.add(statusCard, BorderLayout.CENTER);

        // Control Toolbar
        JPanel controlToolbar = new JPanel(new BorderLayout(Scale.px(8), 0));
        controlToolbar.setBackground(CustomColors.INTERFACE_MAINPANE_BACKGROUND);

        // The three server buttons, flush with the left edge of the status card above.
        JPanel leftControls = new JPanel();
        leftControls.setLayout(new javax.swing.BoxLayout(leftControls, javax.swing.BoxLayout.X_AXIS));
        leftControls.setBackground(CustomColors.INTERFACE_MAINPANE_BACKGROUND);

        startButton = new JButton(_localeManager.getValue("b.start_server"));
        startButton.setIcon(FontIcon.of(FontAwesome.PLAY, Scale.px(12), Color.WHITE));
        startButton.setFont(Fonts.getFont("default", 11.0f, Font.BOLD));
        startButton.setBackground(COLOR_RUNNING);
        startButton.setForeground(Color.WHITE);
        startButton.setFocusPainted(false);
        startButton.putClientProperty(FlatClientProperties.STYLE, "arc: 6; borderWidth: 0; margin: 4,10,4,10");
        startButton.addActionListener(e -> eventHandler.startServer());
        leftControls.add(startButton);
        leftControls.add(javax.swing.Box.createHorizontalStrut(Scale.px(8)));

        stopButton = new JButton(_localeManager.getValue("b.stop_server"));
        stopButton.setIcon(FontIcon.of(FontAwesome.STOP, Scale.px(12), Color.WHITE));
        stopButton.setFont(Fonts.getFont("default", 11.0f, Font.BOLD));
        stopButton.setBackground(COLOR_STOPPED);
        stopButton.setForeground(Color.WHITE);
        stopButton.setFocusPainted(false);
        stopButton.putClientProperty(FlatClientProperties.STYLE, "arc: 6; borderWidth: 0; margin: 4,10,4,10");
        stopButton.addActionListener(e -> eventHandler.stopServer());
        leftControls.add(stopButton);
        leftControls.add(javax.swing.Box.createHorizontalStrut(Scale.px(8)));

        restartButton = new JButton(_localeManager.getValue("b.restart_server"));
        restartButton.setIcon(FontIcon.of(FontAwesome.REFRESH, Scale.px(12), Color.WHITE));
        restartButton.setFont(Fonts.getFont("default", 11.0f, Font.BOLD));
        restartButton.setBackground(CustomColors.INTERFACE_BUTTON_BACKGROUND);
        restartButton.setForeground(Color.WHITE);
        restartButton.setFocusPainted(false);
        restartButton.putClientProperty(FlatClientProperties.STYLE, "arc: 6; borderWidth: 0; margin: 4,10,4,10");
        restartButton.addActionListener(e -> eventHandler.restartServer());
        leftControls.add(restartButton);

        controlToolbar.add(leftControls, BorderLayout.WEST);

        JPanel rightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, Scale.px(8), 0));
        rightControls.setBackground(CustomColors.INTERFACE_MAINPANE_BACKGROUND);

        settingsButton = new JButton(_localeManager.getValue("b.server_settings"));
        settingsButton.setIcon(FontIcon.of(FontAwesome.COGS, Scale.px(13), Color.WHITE));
        settingsButton.setFont(Fonts.getFont("default", 11.0f, Font.BOLD));
        settingsButton.setBackground(CustomColors.INTERFACE_BUTTON_BACKGROUND);
        settingsButton.setForeground(Color.WHITE);
        settingsButton.setFocusPainted(false);
        settingsButton.putClientProperty(FlatClientProperties.STYLE, "arc: 6; borderWidth: 0; margin: 4,12,4,12");
        settingsButton.addActionListener(e -> eventHandler.openSettings());
        rightControls.add(settingsButton);

        connectButton = new JButton(_localeManager.getValue("b.connect_server"));
        connectButton.setIcon(FontIcon.of(FontAwesome.GAMEPAD, Scale.px(13), Color.WHITE));
        connectButton.setFont(Fonts.getFont("default", 11.0f, Font.BOLD));
        connectButton.setBackground(COLOR_ACCENT);
        connectButton.setForeground(Color.WHITE);
        connectButton.setFocusPainted(false);
        connectButton.putClientProperty(FlatClientProperties.STYLE, "arc: 6; borderWidth: 0; margin: 4,12,4,12");
        connectButton.setToolTipText(_localeManager.getValue("tt.connect_server"));
        connectButton.addActionListener(e -> eventHandler.connectAndLaunch());
        rightControls.add(connectButton);

        controlToolbar.add(rightControls, BorderLayout.EAST);
        topContainer.add(controlToolbar, BorderLayout.SOUTH);

        panel.add(topContainer, BorderLayout.NORTH);

        // Console Panel in Center
        consolePanel = new ServerConsolePanel(hostManager, _localeManager);
        panel.add(consolePanel, BorderLayout.CENTER);
    }

    private void setStatusDot(Color color) {
        statusBadge.setIcon(FontIcon.of(FontAwesome.CIRCLE, Scale.px(14), color));
    }

    public void setStatus(boolean running, int port, int players, long uptimeMs) {
        if (statusBadge == null) return;

        if (running) {
            setStatusDot(COLOR_RUNNING);
            statusText.setText(_localeManager.getValue("m.server_running"));
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            restartButton.setEnabled(true);
            connectButton.setEnabled(true);
        } else {
            setStatusDot(COLOR_STOPPED);
            statusText.setText(_localeManager.getValue("m.server_stopped"));
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
            restartButton.setEnabled(false);
            connectButton.setEnabled(false);
        }

        if (players == 0) {
            playersText.setText(_localeManager.getValue("m.server_no_players"));
        } else if (players == 1) {
            playersText.setText(_localeManager.getValue("m.server_player_count", "1"));
        } else {
            playersText.setText(_localeManager.getValue("m.server_players_count", String.valueOf(players)));
        }
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
