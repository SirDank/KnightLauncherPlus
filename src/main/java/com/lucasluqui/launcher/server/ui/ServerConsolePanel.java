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
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.text.DefaultCaret;

import com.formdev.flatlaf.FlatClientProperties;
import com.lucasluqui.launcher.CustomColors;
import com.lucasluqui.launcher.Fonts;
import com.lucasluqui.launcher.LocaleManager;
import com.lucasluqui.launcher.server.ServerHostManager;
import com.lucasluqui.swing.Scale;

/**
 * Embedded console panel displaying live server logs and an interactive command prompt.
 */
public class ServerConsolePanel extends JPanel {
    private static final int MAX_DOCUMENT_LINES = 1500;
    private static final int MAX_HISTORY = 200;

    private final JTextArea logArea;
    private final JScrollPane scrollPane;
    private final JTextField commandField;
    private final JButton sendButton;
    private final JButton clearButton;

    private final List<String> commandHistory = new ArrayList<>();
    private int historyIndex = -1;

    public ServerConsolePanel(ServerHostManager hostManager, LocaleManager localeManager) {
        setLayout(new BorderLayout(0, Scale.px(6)));
        setBackground(CustomColors.INTERFACE_MAINPANE_BACKGROUND);

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(Fonts.getFont("code", 11.0f, Font.PLAIN));
        logArea.setBackground(CustomColors.INTERFACE_MAINPANE_SUBBACKGROUND);
        logArea.setForeground(new Color(0xDD, 0xDD, 0xDD));
        logArea.setCaretColor(Color.WHITE);
        logArea.setLineWrap(false);
        // The default caret scrolls to every appended line; scrolling is done by hand so a user who
        // scrolled up to read is left alone.
        ((DefaultCaret) logArea.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
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
        commandField.setBackground(CustomColors.INTERFACE_TEXTFIELD_BACKGROUND);
        commandField.setForeground(Color.WHITE);
        commandField.setCaretColor(Color.WHITE);
        commandField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, localeManager.getValue("m.server_console_placeholder"));
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
        // Typing should work as soon as the screen is shown, without a click first.
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                SwingUtilities.invokeLater(commandField::requestFocusInWindow);
            }
        });

        JPanel buttonBar = new JPanel(new BorderLayout(Scale.px(4), 0));
        buttonBar.setBackground(CustomColors.INTERFACE_MAINPANE_BACKGROUND);

        sendButton = new JButton(localeManager.getValue("b.send_command"));
        sendButton.setFont(Fonts.getFont("default", 11.0f, Font.BOLD));
        sendButton.setBackground(CustomColors.INTERFACE_BUTTON_BACKGROUND);
        sendButton.setForeground(Color.WHITE);
        sendButton.setFocusPainted(false);
        sendButton.putClientProperty(FlatClientProperties.STYLE, "arc: 6; borderWidth: 0");
        sendButton.addActionListener(e -> submitCommand(hostManager));
        buttonBar.add(sendButton, BorderLayout.WEST);

        clearButton = new JButton(localeManager.getValue("b.clear_console"));
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

    public void setBlocked(boolean blocked) {
        commandField.setEnabled(!blocked);
        sendButton.setEnabled(!blocked);
        clearButton.setEnabled(!blocked);
    }

    public void appendLine(String line) {
        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = scrollPane.getVerticalScrollBar();
            boolean atBottom = bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 40;
            logArea.append(line + "\n");
            // Trim lines if exceeded buffer
            if (logArea.getLineCount() > MAX_DOCUMENT_LINES) {
                try {
                    int endOffset = logArea.getLineEndOffset(100);
                    logArea.replaceRange("", 0, endOffset);
                } catch (Exception ignored) {}
            }
            if (atBottom) { // don't yank the view away while the user is reading older lines
                SwingUtilities.invokeLater(() -> bar.setValue(bar.getMaximum()));
            }
        });
    }

    public void clearConsole() {
        SwingUtilities.invokeLater(() -> logArea.setText(""));
    }

    private void submitCommand(ServerHostManager hostManager) {
        String text = commandField.getText().trim();
        if (text.isEmpty()) return;

        commandHistory.add(text);
        if (commandHistory.size() > MAX_HISTORY) commandHistory.remove(0);
        historyIndex = commandHistory.size();
        commandField.setText("");

        new Thread(() -> {
            String answer = hostManager.sendCommand(text);
            if (!answer.isEmpty()) appendLine(answer); // the server's own replies arrive as normal output
        }, "ServerCommandExec").start();
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

