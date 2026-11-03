package com.lucasluqui.launcher.server.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import com.formdev.flatlaf.FlatClientProperties;
import com.lucasluqui.launcher.CustomColors;
import com.lucasluqui.launcher.Fonts;
import com.lucasluqui.launcher.server.ServerHostManager;
import com.lucasluqui.swing.Scale;

/**
 * Embedded console panel displaying live server logs and an interactive command prompt.
 */
public class ServerConsolePanel extends JPanel {
    private static final int MAX_DOCUMENT_LINES = 1500;

    private final JTextArea logArea;
    private final JScrollPane scrollPane;
    private final JTextField commandField;
    private final JButton sendButton;
    private final JButton clearButton;

    private final List<String> commandHistory = new ArrayList<>();
    private int historyIndex = -1;

    public ServerConsolePanel(ServerHostManager hostManager) {
        setLayout(new BorderLayout(0, Scale.px(6)));
        setBackground(CustomColors.INTERFACE_MAINPANE_BACKGROUND);

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(Fonts.getFont("code", 11.0f, Font.PLAIN));
        logArea.setBackground(new Color(0x13, 0x13, 0x15));
        logArea.setForeground(new Color(0xDD, 0xDD, 0xDD));
        logArea.setCaretColor(Color.WHITE);
        logArea.setLineWrap(false);
        logArea.putClientProperty(FlatClientProperties.STYLE, "margin: 8,8,8,8");

        scrollPane = new JScrollPane(logArea);
        scrollPane.setBorder(null);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        add(scrollPane, BorderLayout.CENTER);

        // Command Bar
        JPanel commandBar = new JPanel(new BorderLayout(Scale.px(6), 0));
        commandBar.setBackground(CustomColors.INTERFACE_MAINPANE_BACKGROUND);

        commandField = new JTextField();
        commandField.setFont(Fonts.getFont("code", 11.5f, Font.PLAIN));
        commandField.setBackground(new Color(0x1E, 0x1E, 0x22));
        commandField.setForeground(Color.WHITE);
        commandField.setCaretColor(Color.WHITE);
        commandField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Enter command (e.g. status, help, stop)...");
        commandField.putClientProperty(FlatClientProperties.STYLE, "arc: 6; margin: 4,8,4,8");

        commandField.addActionListener(e -> submitCommand(hostManager));
        commandField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_UP) {
                    navigateHistory(-1);
                } else if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                    navigateHistory(1);
                }
            }
        });
        commandBar.add(commandField, BorderLayout.CENTER);

        JPanel buttonBar = new JPanel(new BorderLayout(Scale.px(4), 0));
        buttonBar.setBackground(CustomColors.INTERFACE_MAINPANE_BACKGROUND);

        sendButton = new JButton("Send");
        sendButton.setFont(Fonts.getFont("default", 11.0f, Font.BOLD));
        sendButton.setBackground(CustomColors.INTERFACE_BUTTON_BACKGROUND);
        sendButton.setForeground(Color.WHITE);
        sendButton.setFocusPainted(false);
        sendButton.putClientProperty(FlatClientProperties.STYLE, "arc: 6; borderWidth: 0");
        sendButton.addActionListener(e -> submitCommand(hostManager));
        buttonBar.add(sendButton, BorderLayout.WEST);

        clearButton = new JButton("Clear");
        clearButton.setFont(Fonts.getFont("default", 11.0f, Font.BOLD));
        clearButton.setBackground(CustomColors.INTERFACE_BUTTON_BACKGROUND);
        clearButton.setForeground(Color.WHITE);
        clearButton.setFocusPainted(false);
        clearButton.putClientProperty(FlatClientProperties.STYLE, "arc: 6; borderWidth: 0");
        clearButton.addActionListener(e -> clearConsole());
        buttonBar.add(clearButton, BorderLayout.EAST);

        commandBar.add(buttonBar, BorderLayout.EAST);
        add(commandBar, BorderLayout.SOUTH);

        // Populate history
        for (String line : hostManager.getLogHistory()) {
            appendLine(line);
        }

        // Listen for live lines
        hostManager.addLogListener(this::appendLine);
    }

    public void appendLine(String line) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(line + "\n");
            // Trim lines if exceeded buffer
            if (logArea.getLineCount() > MAX_DOCUMENT_LINES) {
                try {
                    int endOffset = logArea.getLineEndOffset(100);
                    logArea.replaceRange("", 0, endOffset);
                } catch (Exception ignored) {}
            }
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    public void clearConsole() {
        SwingUtilities.invokeLater(() -> logArea.setText(""));
    }

    private void submitCommand(ServerHostManager hostManager) {
        String text = commandField.getText().trim();
        if (text.isEmpty()) return;

        commandHistory.add(text);
        historyIndex = commandHistory.size();
        commandField.setText("");

        new Thread(() -> hostManager.sendCommand(text), "ServerCommandExec").start();
    }

    private void navigateHistory(int delta) {
        if (commandHistory.isEmpty()) return;
        historyIndex += delta;
        if (historyIndex < 0) historyIndex = 0;
        if (historyIndex > commandHistory.size()) historyIndex = commandHistory.size();

        if (historyIndex == commandHistory.size()) {
            commandField.setText("");
        } else {
            commandField.setText(commandHistory.get(historyIndex));
        }
    }
}

