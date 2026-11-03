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
import javax.swing.JTextField;
import javax.swing.SwingConstants;

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
    private static final Color COLOR_ACCENT = new Color(0x3B, 0x82, 0xF6);

    @Inject private ServerEventHandler eventHandler;
    @Inject private ServerHostManager hostManager;
    @Inject private LocaleManager _localeManager;

    private JLabel titleLabel;
    private JLabel statusBadge;
    private JLabel statusText;
    private JLabel uptimeText;
    private JLabel playersText;
    private JTextField portField;
    private JLabel lanAddressText;
    private JButton startButton;
    private JButton stopButton;
    private JButton restartButton;
    private JButton connectButton;
    private JButton copyLanButton;
    private ServerConsolePanel consolePanel;

    @Inject
    public ServerHostUI() {
        super(800, 550, false);
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
        guiFrame.setTitle("Server Hosting");
        guiFrame.setBounds(Scale.rect(100, 100, this.width, this.height));
        guiFrame.setResizable(false);
        guiFrame.setUndecorated(true);
        guiFrame.getContentPane().setBackground(CustomColors.INTERFACE_MAINPANE_BACKGROUND);
        guiFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        guiFrame.getContentPane().setLayout(new BorderLayout(0, Scale.px(8)));
        panel = (JPanel) guiFrame.getContentPane();
        panel.setBorder(BorderFactory.createEmptyBorder(0, Scale.px(20), Scale.px(16), Scale.px(20)));

        // Top Section: Title + Status Card + Control Toolbar
        JPanel topContainer = new JPanel(new BorderLayout(0, Scale.px(8)));
        topContainer.setBackground(CustomColors.INTERFACE_MAINPANE_BACKGROUND);

        // Header Title
        titleLabel = new JLabel("Server Hosting");
        titleLabel.setFont(Fonts.getFont("default", 26.0f, Font.BOLD));
        titleLabel.setForeground(Color.WHITE);
        titleLabel.putClientProperty(FlatClientProperties.STYLE, "margin: 0,0,4,0");
        topContainer.add(titleLabel, BorderLayout.NORTH);

        // Status Card
        JPanel statusCard = new JPanel(new BorderLayout(Scale.px(10), 0));
        statusCard.setBackground(new Color(0x18, 0x18, 0x1C));
        statusCard.putClientProperty(FlatClientProperties.STYLE, "arc: 8; margin: 8,12,8,12");

        JPanel leftStatus = new JPanel(new FlowLayout(FlowLayout.LEFT, Scale.px(10), 0));
        leftStatus.setBackground(new Color(0x18, 0x18, 0x1C));

        statusBadge = new JLabel("\u25CF");
        statusBadge.setFont(Fonts.getFont("default", 16.0f, Font.BOLD));
        statusBadge.setForeground(COLOR_STOPPED);
        leftStatus.add(statusBadge);

        statusText = new JLabel("Server Stopped");
        statusText.setFont(Fonts.getFont("default", 12.0f, Font.BOLD));
        statusText.setForeground(Color.WHITE);
        leftStatus.add(statusText);

        playersText = new JLabel("0 Players Online");
        playersText.setFont(Fonts.getFont("default", 11.0f, Font.PLAIN));
        playersText.setForeground(new Color(0xAA, 0xAA, 0xAA));
        leftStatus.add(playersText);

        uptimeText = new JLabel("Uptime: --");
        uptimeText.setFont(Fonts.getFont("default", 11.0f, Font.PLAIN));
        uptimeText.setForeground(new Color(0x88, 0x88, 0x88));
        leftStatus.add(uptimeText);

        statusCard.add(leftStatus, BorderLayout.WEST);

        // LAN IP Display
        JPanel rightStatus = new JPanel(new FlowLayout(FlowLayout.RIGHT, Scale.px(6), 0));
        rightStatus.setBackground(new Color(0x18, 0x18, 0x1C));

        lanAddressText = new JLabel("LAN: " + hostManager.getLanAddress());
        lanAddressText.setFont(Fonts.getFont("code", 11.0f, Font.PLAIN));
        lanAddressText.setForeground(new Color(0x9E, 0x9E, 0x9E));
        rightStatus.add(lanAddressText);

        copyLanButton = new JButton("Copy");
        copyLanButton.setFont(Fonts.getFont("default", 10.5f, Font.PLAIN));
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

        JPanel leftControls = new JPanel(new FlowLayout(FlowLayout.LEFT, Scale.px(8), 0));
        leftControls.setBackground(CustomColors.INTERFACE_MAINPANE_BACKGROUND);

        JLabel portLabel = new JLabel("Port:");
        portLabel.setFont(Fonts.getFont("default", 11.0f, Font.BOLD));
        portLabel.setForeground(Color.WHITE);
        leftControls.add(portLabel);

        portField = new JTextField(String.valueOf(hostManager.getServerPort()), 5);
        portField.setFont(Fonts.getFont("code", 11.0f, Font.PLAIN));
        portField.setBackground(new Color(0x1E, 0x1E, 0x22));
        portField.setForeground(Color.WHITE);
        portField.putClientProperty(FlatClientProperties.STYLE, "arc: 6; margin: 3,6,3,6");
        leftControls.add(portField);

        startButton = new JButton("Start Server");
        startButton.setIcon(FontIcon.of(FontAwesome.PLAY, Scale.px(12), Color.WHITE));
        startButton.setFont(Fonts.getFont("default", 11.0f, Font.BOLD));
        startButton.setBackground(COLOR_RUNNING);
        startButton.setForeground(Color.WHITE);
        startButton.setFocusPainted(false);
        startButton.putClientProperty(FlatClientProperties.STYLE, "arc: 6; borderWidth: 0; margin: 4,10,4,10");
        startButton.addActionListener(e -> eventHandler.startServer());
        leftControls.add(startButton);

        stopButton = new JButton("Stop Server");
        stopButton.setIcon(FontIcon.of(FontAwesome.STOP, Scale.px(12), Color.WHITE));
        stopButton.setFont(Fonts.getFont("default", 11.0f, Font.BOLD));
        stopButton.setBackground(COLOR_STOPPED);
        stopButton.setForeground(Color.WHITE);
        stopButton.setFocusPainted(false);
        stopButton.putClientProperty(FlatClientProperties.STYLE, "arc: 6; borderWidth: 0; margin: 4,10,4,10");
        stopButton.addActionListener(e -> eventHandler.stopServer());
        leftControls.add(stopButton);

        restartButton = new JButton("Restart");
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

        connectButton = new JButton("Connect & Play");
        connectButton.setIcon(FontIcon.of(FontAwesome.GAMEPAD, Scale.px(13), Color.WHITE));
        connectButton.setFont(Fonts.getFont("default", 11.0f, Font.BOLD));
        connectButton.setBackground(COLOR_ACCENT);
        connectButton.setForeground(Color.WHITE);
        connectButton.setFocusPainted(false);
        connectButton.putClientProperty(FlatClientProperties.STYLE, "arc: 6; borderWidth: 0; margin: 4,12,4,12");
        connectButton.setToolTipText("Switch launcher to Local Server and launch game");
        connectButton.addActionListener(e -> eventHandler.connectAndLaunch());
        rightControls.add(connectButton);

        controlToolbar.add(rightControls, BorderLayout.EAST);
        topContainer.add(controlToolbar, BorderLayout.SOUTH);

        panel.add(topContainer, BorderLayout.NORTH);

        // Console Panel in Center
        consolePanel = new ServerConsolePanel(hostManager);
        panel.add(consolePanel, BorderLayout.CENTER);
    }

    public void setStatus(boolean running, int port, int players, long uptimeMs) {
        if (statusBadge == null) return;

        if (running) {
            statusBadge.setForeground(COLOR_RUNNING);
            statusText.setText("Server Running (Port " + port + ")");
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            restartButton.setEnabled(true);
            connectButton.setEnabled(true);
            portField.setEditable(false);
        } else {
            statusBadge.setForeground(COLOR_STOPPED);
            statusText.setText("Server Stopped");
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
            restartButton.setEnabled(false);
            connectButton.setEnabled(false);
            portField.setEditable(true);
        }

        playersText.setText(players + (players == 1 ? " Player Online" : " Players Online"));
        lanAddressText.setText("LAN: " + hostManager.getLanAddress() + ":" + port);

        if (uptimeMs > 0) {
            long seconds = (uptimeMs / 1000) % 60;
            long minutes = (uptimeMs / (1000 * 60)) % 60;
            long hours = (uptimeMs / (1000 * 60 * 60));
            uptimeText.setText(String.format("Uptime: %02d:%02d:%02d", hours, minutes, seconds));
        } else {
            uptimeText.setText("Uptime: --");
        }
    }

    public String getPortText() {
        return portField != null ? portField.getText() : "";
    }
}
