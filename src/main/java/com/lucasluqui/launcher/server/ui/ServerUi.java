package com.lucasluqui.launcher.server.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import com.formdev.flatlaf.FlatClientProperties;
import com.lucasluqui.launcher.CustomColors;
import com.lucasluqui.launcher.Fonts;
import com.lucasluqui.launcher.LocaleManager;
import com.lucasluqui.launcher.server.ServerConsoleClient;
import com.lucasluqui.swing.FontIcon;
import com.lucasluqui.swing.Scale;
import jiconfont.icons.font_awesome.FontAwesome;

/** Styling, background work and message helpers shared by the server settings panels. */
final class ServerUi {
    static final Color DANGER = new Color(180, 40, 40);
    static final Color OK_TEXT = new Color(0x7F, 0xD1, 0x8B);
    static final Color ERROR_TEXT = new Color(0xFF, 0x8A, 0x80);

    /** A panel that follows the width of its scroll pane, so wrapped text wraps and no horizontal bar appears. */
    static final class ScrollColumn extends javax.swing.JPanel implements javax.swing.Scrollable {
        ScrollColumn() {
            setOpaque(false);
            setLayout(new javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS));
        }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction) { return Scale.px(16); }
        @Override public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction) { return visible.height; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    final LocaleManager locale;

    ServerUi(LocaleManager locale) {
        this.locale = locale;
    }

    JButton button(String key, FontAwesome icon, Color background, java.awt.event.ActionListener action) {
        JButton button = new JButton(locale.getValue(key));
        if (icon != null) button.setIcon(FontIcon.of(icon, Scale.px(12), Color.WHITE));
        button.setFont(Fonts.getFont("default", 11.0f, Font.BOLD));
        button.setBackground(background);
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
        button.setHorizontalAlignment(SwingConstants.CENTER);
        button.putClientProperty(FlatClientProperties.STYLE,
            "arc: 6; borderWidth: 0; margin: 4,12,4,12; disabledBackground: #1E1E1E; disabledText: #777777");
        button.addActionListener(action);
        return button;
    }

    JLabel subheading(String key) {
        JLabel label = new JLabel(locale.getValue(key));
        label.setFont(Fonts.getFont("default", 14.0f, Font.BOLD));
        label.setForeground(CustomColors.ACCENT_LIGHT);
        label.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        return label;
    }

    void styleField(JTextField field) {
        field.setFont(Fonts.getFont("default", 11.5f, Font.PLAIN)); // the monospace font has no glyphs for every language
        field.setForeground(Color.WHITE);
        field.setBackground(CustomColors.INTERFACE_TEXTFIELD_BACKGROUND);
        field.setCaretColor(Color.WHITE);
        field.setPreferredSize(new Dimension(Scale.px(140), Scale.px(26)));
    }

    void styleTable(JTable table) {
        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        table.setBackground(CustomColors.INTERFACE_TEXTFIELD_BACKGROUND);
        table.setForeground(Color.WHITE);
        table.setFont(Fonts.getFont("default", 11.0f, Font.PLAIN));
        table.setRowHeight(Scale.px(22));
        table.setFillsViewportHeight(true);
        table.getTableHeader().setBackground(CustomColors.INTERFACE_BUTTON_BACKGROUND);
        table.getTableHeader().setForeground(Color.WHITE);
        table.getTableHeader().setFont(Fonts.getFont("default", 11.0f, Font.BOLD));
    }

    JScrollPane scroll(JTable table, int height) {
        JScrollPane pane = new JScrollPane(table);
        pane.setPreferredSize(new Dimension(Scale.px(640), Scale.px(height)));
        pane.setMaximumSize(new Dimension(Integer.MAX_VALUE, Scale.px(height)));
        pane.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        pane.setBorder(BorderFactory.createLineBorder(new Color(0x33, 0x33, 0x33)));
        return pane;
    }

    /** Runs {@code work} on a background thread and hands its result to {@code done} on the UI thread. */
    <T> void async(String name, Supplier<T> work, Consumer<T> done) {
        Thread thread = new Thread(() -> {
            T result = work.get();
            SwingUtilities.invokeLater(() -> done.accept(result));
        }, name);
        thread.setDaemon(true);
        thread.start();
    }

    /** What to tell the user about a console result: the server's own words, or our message for the cases it cannot give. */
    String describe(ServerConsoleClient.Result result) {
        switch (result.message()) {
            case "not-running": return locale.getValue("m.ss_result_not_running");
            case "timeout": return locale.getValue("m.ss_result_timeout");
            case "unreachable": return locale.getValue("m.ss_result_unreachable");
            default:
                if (result.message().startsWith("invalid-") || result.message().equals("interrupted")) {
                    return locale.getValue("m.ss_result_invalid");
                }
                return result.message();
        }
    }

    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes / 1024.0;
        String[] units = {"KiB", "MiB", "GiB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
