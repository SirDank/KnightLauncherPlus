package com.lucasluqui.launcher.server.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;

import com.formdev.flatlaf.FlatClientProperties;
import com.lucasluqui.launcher.CustomColors;
import com.lucasluqui.launcher.Fonts;
import com.lucasluqui.launcher.LauncherGlobals;
import com.lucasluqui.launcher.LocaleManager;
import com.lucasluqui.launcher.server.ServerHostManager;
import com.lucasluqui.launcher.server.ServerNet;
import com.lucasluqui.launcher.server.ServerPropertiesFile;
import com.lucasluqui.launcher.server.ServerSettings;
import com.lucasluqui.swing.FontIcon;
import com.lucasluqui.swing.Scale;
import jiconfont.icons.font_awesome.FontAwesome;

import static com.lucasluqui.launcher.Log.log;

/**
 * The window with every setting of the built-in server. It is built from {@link ServerSettings}, so a new setting
 * shows up here by itself. The port and "allow LAN/VPN" are the launcher's own; everything else is written to
 * {@code server.properties}. Changes apply the next time the server starts.
 */
public class ServerSettingsDialog extends JDialog {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 720;

    private final ServerHostManager hostManager;
    private final LocaleManager locale;
    private final Runnable onSaved;
    private final Runnable restart;
    private final File file = ServerPropertiesFile.resolve(new File(LauncherGlobals.USER_DIR));

    private final Map<String, Field> fields = new LinkedHashMap<>();
    private Field portField;
    private Field lanField;

    /** One row: the editor, how to read and set it, and the default it resets to. */
    private final class Field {
        final String defaultValue;
        final JComponent editor;
        Field(String defaultValue, JComponent editor) {
            this.defaultValue = defaultValue;
            this.editor = editor;
        }
        String get() {
            if (editor instanceof JCheckBox) return String.valueOf(((JCheckBox) editor).isSelected());
            Number value = (Number) ((JSpinner) editor).getValue();
            return editor.getClientProperty("float") != null ? String.valueOf(value.floatValue()) : String.valueOf(value.longValue());
        }
        void set(String value) {
            if (editor instanceof JCheckBox) {
                ((JCheckBox) editor).setSelected(Boolean.parseBoolean(value));
            } else if (editor.getClientProperty("float") != null) {
                ((JSpinner) editor).setValue(Double.parseDouble(value));
            } else {
                ((JSpinner) editor).setValue(Integer.parseInt(value));
            }
        }
    }

    /**
     * @param onSaved Called after the settings were saved (to refresh the hosting screen).
     * @param restart Restarts the server; offered after saving while it is running.
     */
    public ServerSettingsDialog(Window owner, ServerHostManager hostManager, LocaleManager locale,
                                Runnable onSaved, Runnable restart) {
        super(owner, locale.getValue("ss.title"), ModalityType.MODELESS);
        this.hostManager = hostManager;
        this.locale = locale;
        this.onSaved = onSaved;
        this.restart = restart;
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        compose();
        loadValues();
        pack();
        setSize(Scale.px(WIDTH), Scale.px(HEIGHT));
        setLocationRelativeTo(owner);
    }

    private void compose() {
        JPanel root = new JPanel(new BorderLayout(0, Scale.px(10)));
        root.setBackground(CustomColors.INTERFACE_MAINPANE_BACKGROUND);
        root.setBorder(BorderFactory.createEmptyBorder(Scale.px(16), Scale.px(20), Scale.px(14), Scale.px(20)));
        setContentPane(root);

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(locale.getValue("ss.title"));
        title.setFont(Fonts.getFont("default", 22.0f, Font.BOLD));
        title.setForeground(Color.WHITE);
        title.setAlignmentX(LEFT_ALIGNMENT);
        header.add(title);
        JLabel subtitle = new JLabel(locale.getValue("ss.subtitle"));
        subtitle.setFont(Fonts.getFont("default", 11.0f, Font.PLAIN));
        subtitle.setForeground(CustomColors.INTERFACE_DEFAULT);
        subtitle.setAlignmentX(LEFT_ALIGNMENT);
        header.add(Box.createVerticalStrut(Scale.px(4)));
        header.add(subtitle);
        root.add(header, BorderLayout.NORTH);

        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        for (ServerSettings.Group group : ServerSettings.Group.values()) {
            form.add(section(group));
        }
        JScrollPane scroll = new JScrollPane(form);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(Scale.px(16));
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        root.add(scroll, BorderLayout.CENTER);

        root.add(buttons(), BorderLayout.SOUTH);
    }

    private JPanel section(ServerSettings.Group group) {
        JPanel section = new JPanel();
        section.setOpaque(false);
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setAlignmentX(LEFT_ALIGNMENT);
        section.setBorder(BorderFactory.createEmptyBorder(Scale.px(14), 0, 0, Scale.px(8)));

        JLabel name = new JLabel(locale.getValue("ss.group." + group.name().toLowerCase(java.util.Locale.ROOT)));
        name.setFont(Fonts.getFont("default", 14.0f, Font.BOLD));
        name.setForeground(CustomColors.ACCENT_LIGHT);
        name.setAlignmentX(LEFT_ALIGNMENT);
        section.add(name);
        JSeparator separator = new JSeparator();
        separator.setAlignmentX(LEFT_ALIGNMENT);
        separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, Scale.px(8)));
        section.add(separator);

        if (group == ServerSettings.Group.NETWORK) {
            // The launcher's own settings: which port to host on and whether others may join.
            portField = new Field(String.valueOf(ServerNet.DEFAULT_PORT), spinner(ServerNet.DEFAULT_PORT, 1, 65535, 1, false));
            section.add(row("ss.server.port", "ss.server.port.desc", portField));
        }
        for (ServerSettings.Def def : ServerSettings.all()) {
            if (def.group != group) continue;
            Field field = new Field(def.defaultValue, editorFor(def));
            if (def.key.equals("server.allowLan")) {
                lanField = field;
            } else {
                fields.put(def.key, field);
            }
            section.add(row(def.labelKey(), def.descriptionKey(), field));
        }
        return section;
    }

    private JComponent editorFor(ServerSettings.Def def) {
        switch (def.type) {
            case BOOLEAN:
                return new JCheckBox();
            case INT:
                return spinner(Integer.parseInt(def.defaultValue), (int) def.min, (int) def.max, 1, false);
            default:
                double step = Math.max(0.1, Math.pow(10, Math.floor(Math.log10(Math.max(1.0, def.max))) - 2));
                return spinner(Double.parseDouble(def.defaultValue), def.min, def.max, Math.min(step, 5.0), true);
        }
    }

    private JSpinner spinner(Number value, Number min, Number max, Number step, boolean real) {
        SpinnerNumberModel model = real
            ? new SpinnerNumberModel(value.doubleValue(), min.doubleValue(), max.doubleValue(), step.doubleValue())
            : new SpinnerNumberModel(value.intValue(), min.intValue(), max.intValue(), step.intValue());
        JSpinner spinner = new JSpinner(model);
        spinner.setFont(Fonts.getFont("code", 11.5f, Font.PLAIN));
        // Plain numbers: no thousands separator in a port, and decimals keep their ".0".
        spinner.setEditor(new JSpinner.NumberEditor(spinner, real ? "0.0##" : "0"));
        if (real) spinner.putClientProperty("float", Boolean.TRUE);
        Dimension size = new Dimension(Scale.px(110), Scale.px(26));
        spinner.setPreferredSize(size);
        spinner.setMinimumSize(size);
        return spinner;
    }

    private JPanel row(String labelKey, String descriptionKey, Field field) {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createEmptyBorder(Scale.px(8), 0, Scale.px(2), 0));

        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(0, 0, 0, Scale.px(10));

        JLabel label = new JLabel(locale.getValue(labelKey));
        label.setFont(Fonts.getFont("default", 12.0f, Font.BOLD));
        label.setForeground(Color.WHITE);
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        row.add(label, c);

        c.gridx = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.NORTHEAST;
        row.add(field.editor, c);

        JButton reset = new JButton(FontIcon.of(FontAwesome.UNDO, Scale.px(12), CustomColors.INTERFACE_DEFAULT));
        reset.setToolTipText(locale.getValue("tt.ss_reset_field"));
        reset.setFocusPainted(false);
        reset.setBackground(CustomColors.INTERFACE_BUTTON_BACKGROUND);
        reset.putClientProperty(FlatClientProperties.STYLE, "arc: 6; borderWidth: 0; margin: 3,6,3,6");
        reset.addActionListener(e -> field.set(field.defaultValue));
        c.gridx = 2;
        c.insets = new Insets(0, 0, 0, 0);
        row.add(reset, c);

        JTextArea description = new JTextArea(locale.getValue(descriptionKey));
        description.setEditable(false);
        description.setFocusable(false);
        description.setOpaque(false);
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        description.setFont(Fonts.getFont("default", 10.5f, Font.PLAIN));
        description.setForeground(CustomColors.INTERFACE_MAINPANE_FOOTNOTE.brighter());
        description.setBorder(null);
        c.gridx = 0;
        c.gridy = 1;
        c.gridwidth = 3;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(Scale.px(2), 0, 0, 0);
        row.add(description, c);
        return row;
    }

    private JPanel buttons() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, Scale.px(8), 0));
        left.setOpaque(false);
        left.add(button("b.ss_reset_all", null, CustomColors.INTERFACE_BUTTON_BACKGROUND, e -> resetAll()));
        left.add(button("b.ss_open_folder", FontAwesome.FOLDER_OPEN, CustomColors.INTERFACE_BUTTON_BACKGROUND, e -> openFolder()));
        bar.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, Scale.px(8), 0));
        right.setOpaque(false);
        right.add(button("b.ss_close", null, CustomColors.INTERFACE_BUTTON_BACKGROUND, e -> dispose()));
        right.add(button("b.ss_save", FontAwesome.CHECK, CustomColors.ACCENT, e -> save()));
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private JButton button(String key, FontAwesome icon, Color background, java.awt.event.ActionListener action) {
        JButton button = new JButton(locale.getValue(key));
        if (icon != null) button.setIcon(FontIcon.of(icon, Scale.px(12), Color.WHITE));
        button.setFont(Fonts.getFont("default", 11.0f, Font.BOLD));
        button.setBackground(background);
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
        button.setHorizontalAlignment(SwingConstants.CENTER);
        button.putClientProperty(FlatClientProperties.STYLE, "arc: 6; borderWidth: 0; margin: 4,12,4,12");
        button.addActionListener(action);
        return button;
    }

    /** Shows what is saved: the launcher's port and LAN choice, and server.properties. */
    private void loadValues() {
        portField.set(String.valueOf(hostManager.getServerPort()));
        lanField.set(String.valueOf(hostManager.isLanAllowed()));
        Map<String, String> saved = new LinkedHashMap<>();
        try {
            saved = ServerPropertiesFile.read(file);
        } catch (IOException e) {
            log.warning("Could not read " + file + ": " + e.getMessage());
        }
        for (Map.Entry<String, Field> entry : fields.entrySet()) {
            String value = saved.get(entry.getKey());
            try {
                entry.getValue().set(value == null ? entry.getValue().defaultValue : value);
            } catch (RuntimeException invalid) {
                entry.getValue().set(entry.getValue().defaultValue); // a hand-edited value the spinner cannot show
            }
        }
    }

    private void resetAll() {
        portField.set(portField.defaultValue);
        lanField.set(lanField.defaultValue);
        for (Field field : fields.values()) field.set(field.defaultValue);
    }

    private void openFolder() {
        try {
            File folder = file.getParentFile();
            if (!folder.isDirectory()) folder.mkdirs();
            Desktop.getDesktop().open(folder);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            log.warning("Could not open " + file.getParent() + ": " + e.getMessage());
        }
    }

    private void save() {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, Field> entry : fields.entrySet()) values.put(entry.getKey(), entry.getValue().get());
        List<String> problems = ServerSettings.validate(values);
        if (!problems.isEmpty()) {
            JOptionPane.showMessageDialog(this, locale.getValue("m.ss_invalid") + "\n\n" + String.join("\n", problems),
                locale.getValue("ss.title"), JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            ServerPropertiesFile.write(file, values);
        } catch (IOException e) {
            log.error("Could not save " + file, e);
            JOptionPane.showMessageDialog(this, locale.getValue("m.ss_save_failed", e.getMessage()),
                locale.getValue("ss.title"), JOptionPane.ERROR_MESSAGE);
            return;
        }
        hostManager.setConfiguredPort(Integer.parseInt(portField.get()));
        hostManager.setLanAllowed(Boolean.parseBoolean(lanField.get()));
        onSaved.run();

        if (hostManager.isServerRunning()) {
            int answer = JOptionPane.showConfirmDialog(this, locale.getValue("m.ss_saved_restart"),
                locale.getValue("ss.title"), JOptionPane.YES_NO_OPTION);
            if (answer == JOptionPane.YES_OPTION) restart.run();
        } else {
            JOptionPane.showMessageDialog(this, locale.getValue("m.ss_saved"), locale.getValue("ss.title"),
                JOptionPane.INFORMATION_MESSAGE);
        }
        dispose();
    }

    /** Used by the tests of the window's pieces; the keys every row needs in the locale files. */
    public static List<String> requiredLocaleKeys() {
        List<String> keys = new ArrayList<>(List.of("ss.title", "ss.subtitle", "ss.server.port", "ss.server.port.desc",
            "b.ss_save", "b.ss_close", "b.ss_reset_all", "b.ss_open_folder", "tt.ss_reset_field",
            "m.ss_invalid", "m.ss_save_failed", "m.ss_saved", "m.ss_saved_restart", "b.server_settings"));
        for (ServerSettings.Group group : ServerSettings.Group.values()) {
            keys.add("ss.group." + group.name().toLowerCase(java.util.Locale.ROOT));
        }
        for (ServerSettings.Def def : ServerSettings.all()) {
            keys.add(def.labelKey());
            keys.add(def.descriptionKey());
        }
        return keys;
    }
}
