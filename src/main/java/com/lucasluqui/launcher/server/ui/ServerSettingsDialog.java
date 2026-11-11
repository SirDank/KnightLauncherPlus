package com.lucasluqui.launcher.server.ui;

import java.awt.BorderLayout;
import java.awt.Color;
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
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
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
 * Window for the Tortodrone server: its configuration, its accounts and knights, who is online, and maintenance
 * (join information, backups, starting over).
 */
public class ServerSettingsDialog extends JDialog {
    private static final int WIDTH = 760;
    private static final int HEIGHT = 780;

    private final ServerHostManager hostManager;
    private final LocaleManager locale;
    private final ServerUi ui;
    private final Runnable onSaved;
    private final Runnable restart;
    private final File file = ServerPropertiesFile.resolve(new File(LauncherGlobals.USER_DIR));

    private final Map<String, Field> fields = new LinkedHashMap<>();
    private final Map<String, String> initialValues = new LinkedHashMap<>();
    private Field portField;
    private Field lanField;
    private JButton saveButton;
    private JButton resetAllButton;

    private final class Field {
        final String defaultValue;
        final JComponent editor;
        Field(String defaultValue, JComponent editor) {
            this.defaultValue = defaultValue;
            this.editor = editor;
        }
        String get() {
            if (editor instanceof JCheckBox) return String.valueOf(((JCheckBox) editor).isSelected());
            if (editor instanceof JComboBox) return String.valueOf(((JComboBox<?>) editor).getSelectedItem());
            if (editor instanceof JTextField) return ((JTextField) editor).getText().trim();
            return String.valueOf(((Number) ((JSpinner) editor).getValue()).longValue());
        }
        void set(String value) {
            if (editor instanceof JCheckBox) {
                ((JCheckBox) editor).setSelected(Boolean.parseBoolean(value));
            } else if (editor instanceof JComboBox) {
                @SuppressWarnings("unchecked")
                JComboBox<String> box = (JComboBox<String>) editor;
                boolean listed = false;
                for (int i = 0; i < box.getItemCount() && !listed; i++) listed = box.getItemAt(i).equals(value);
                if (!listed) box.addItem(value); // a value set by hand in server.yml is kept, not replaced by the first item
                box.setSelectedItem(value);
            } else if (editor instanceof JTextField) {
                ((JTextField) editor).setText(value);
                ((JTextField) editor).setCaretPosition(0); // show the start of a long message, not its tail
            } else {
                ((JSpinner) editor).setValue(Integer.parseInt(value));
            }
        }
    }

    public ServerSettingsDialog(Window owner, ServerHostManager hostManager, LocaleManager locale,
                                Runnable onSaved, Runnable restart) {
        super(owner, locale.getValue("ss.title"), ModalityType.MODELESS);
        this.hostManager = hostManager;
        this.locale = locale;
        this.ui = new ServerUi(locale);
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
        JLabel subtitle = new JLabel("<html>" + locale.getValue("ss.subtitle") + "</html>");
        subtitle.setFont(Fonts.getFont("default", 11.0f, Font.PLAIN));
        subtitle.setForeground(CustomColors.INTERFACE_DEFAULT);
        subtitle.setAlignmentX(LEFT_ALIGNMENT);
        header.add(Box.createVerticalStrut(Scale.px(4)));
        header.add(subtitle);
        root.add(header, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(Fonts.getFont("default", 12.0f, Font.BOLD));
        tabs.addTab(locale.getValue("ss.tab.config"), buildConfigTab());
        tabs.addTab(locale.getValue("ss.tab.accounts"), new ServerAccountsPanel(this, hostManager, ui));
        tabs.addTab(locale.getValue("ss.tab.players"), new ServerPlayersPanel(hostManager, ui));
        tabs.addTab(locale.getValue("ss.tab.maintenance"), new ServerMaintenancePanel(this, hostManager, ui, () -> {
            loadValues();
            if (onSaved != null) onSaved.run();
        }));
        // Saving only concerns the configuration; the other tabs act at once.
        tabs.addChangeListener(e -> {
            boolean config = tabs.getSelectedIndex() == 0;
            saveButton.setEnabled(config);
            resetAllButton.setEnabled(config);
        });
        root.add(tabs, BorderLayout.CENTER);

        root.add(buttons(), BorderLayout.SOUTH);
    }

    private JPanel buildConfigTab() {
        JPanel container = new JPanel(new BorderLayout());
        container.setOpaque(false);

        JPanel form = new ServerUi.ScrollColumn();
        for (ServerSettings.Group group : ServerSettings.Group.values()) {
            form.add(section(group));
        }

        JScrollPane scroll = new JScrollPane(form);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(Scale.px(16));
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        // Focus landing in a field further down would otherwise open the tab scrolled past the first heading.
        javax.swing.SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(0));
        container.add(scroll, BorderLayout.CENTER);
        return container;
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
            portField = new Field(String.valueOf(ServerNet.DEFAULT_PORT), spinner(ServerNet.DEFAULT_PORT, 1, 65535, 1));
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
        if ("server.startupMap".equals(def.key)) {
            // Only towns can be entered; the choices are the ones in the installed content pack.
            List<String> towns = ServerPropertiesFile.townMaps(ServerHostManager.serverDir());
            if (towns.isEmpty()) towns.add(def.defaultValue);
            return combo(towns.toArray(new String[0]));
        }
        if ("server.logMode".equals(def.key)) {
            return combo(ServerSettings.LOG_MODES.toArray(new String[0]));
        }
        switch (def.type) {
            case BOOLEAN:
                return new JCheckBox();
            case INT:
                return spinner(Integer.parseInt(def.defaultValue), (int) def.min, (int) def.max, 1);
            default:
                JTextField text = new JTextField(16);
                text.setFont(Fonts.getFont("default", 11.5f, Font.PLAIN));
                text.setForeground(Color.WHITE);
                text.setBackground(CustomColors.INTERFACE_TEXTFIELD_BACKGROUND);
                text.setCaretColor(Color.WHITE);
                text.setPreferredSize(new Dimension(Scale.px(260), Scale.px(26)));
                text.setMinimumSize(new Dimension(Scale.px(120), Scale.px(26)));
                return text;
        }
    }

    private JComboBox<String> combo(String[] items) {
        JComboBox<String> box = new JComboBox<>(items);
        box.setFont(Fonts.getFont("code", 11.5f, Font.PLAIN));
        box.setPreferredSize(new Dimension(Scale.px(180), Scale.px(26)));
        return box;
    }

    private JSpinner spinner(int value, int min, int max, int step) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, min, max, step));
        spinner.setFont(Fonts.getFont("code", 11.5f, Font.PLAIN));
        spinner.setEditor(new JSpinner.NumberEditor(spinner, "0"));
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
        resetAllButton = ui.button("b.ss_reset_all", null, CustomColors.INTERFACE_BUTTON_BACKGROUND, e -> resetAll());
        left.add(resetAllButton);
        bar.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, Scale.px(8), 0));
        right.setOpaque(false);
        right.add(ui.button("b.ss_close", null, CustomColors.INTERFACE_BUTTON_BACKGROUND, e -> dispose()));
        saveButton = ui.button("b.ss_save", FontAwesome.CHECK, CustomColors.ACCENT, e -> save());
        right.add(saveButton);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private void loadValues() {
        portField.set(String.valueOf(hostManager.getConfiguredPort()));
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
                entry.getValue().set(entry.getValue().defaultValue);
            }
        }
        initialValues.clear();
        for (Map.Entry<String, Field> entry : fields.entrySet()) {
            initialValues.put(entry.getKey(), entry.getValue().get());
        }
        initialValues.put("server.port", portField.get());
        initialValues.put("server.allowLan", lanField.get());
    }

    private void resetAll() {
        portField.set(portField.defaultValue);
        lanField.set(lanField.defaultValue);
        for (Field field : fields.values()) field.set(field.defaultValue);
    }

    /** The reasons a value was refused, in the user's language. */
    private List<String> describe(List<ServerSettings.Problem> problems) {
        List<String> lines = new ArrayList<>();
        for (ServerSettings.Problem problem : problems) {
            String label = locale.getValue("ss." + problem.key());
            lines.add(locale.getValue("ss.problem." + problem.code(),
                new String[] {label, String.valueOf(problem.min()), String.valueOf(problem.max())}));
        }
        return lines;
    }

    private void save() {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, Field> entry : fields.entrySet()) values.put(entry.getKey(), entry.getValue().get());
        values.put("server.allowLan", lanField.get());
        List<ServerSettings.Problem> problems = ServerSettings.validate(values);
        if (!problems.isEmpty()) {
            JOptionPane.showMessageDialog(this, locale.getValue("m.ss_invalid") + "\n\n" + String.join("\n", describe(problems)),
                locale.getValue("ss.title"), JOptionPane.ERROR_MESSAGE);
            return;
        }

        Map<String, String> currentValues = new LinkedHashMap<>(values);
        currentValues.put("server.port", portField.get());
        boolean changed = !currentValues.equals(initialValues);
        if (!changed) {
            dispose();
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

    /** Every string the window and its panels use, so the tests can check each language has them. */
    public static List<String> requiredLocaleKeys() {
        List<String> keys = new ArrayList<>(List.of(
            "ss.title", "ss.subtitle", "ss.server.port", "ss.server.port.desc",
            "b.ss_save", "b.ss_close", "b.ss_reset_all", "tt.ss_reset_field",
            "m.ss_invalid", "m.ss_save_failed", "m.ss_saved", "m.ss_saved_restart",
            "b.server_settings", "ss.tab.config", "ss.tab.accounts", "ss.tab.players",
            "ss.tab.maintenance", "ss.account.create_title", "ss.account.username", "ss.account.password",
            "ss.account.gm", "b.ss_account_create", "ss.account.list_title", "b.ss_account_gm_toggle",
            "b.ss_account_delete", "b.ss_account_refresh", "b.ss_account_password", "t.ss_password_title",
            "m.ss_password_prompt", "ss.currency.grant_title", "b.ss_currency_grant", "ss.item.give_title",
            "b.ss_item_give", "ss.item.placeholder", "ss.col.id", "ss.col.username",
            "ss.col.gm", "ss.col.knights", "ss.col.created", "ss.col.knight",
            "ss.col.account", "ss.col.rank", "ss.col.subrank", "ss.col.prestige",
            "ss.col.crowns", "ss.col.energy", "ss.col.session", "ss.col.scene",
            "ss.col.duration", "ss.col.state", "ss.col.backup", "ss.col.size",
            "ss.value.yes", "ss.value.no", "ss.knights.title", "b.ss_knight_edit",
            "t.ss_knight_edit", "b.ss_knight_delete", "m.ss_knight_delete_confirm", "t.ss_delete_account",
            "m.ss_acc_empty", "m.ss_acc_invalid_user", "m.ss_acc_invalid_pass", "m.ss_acc_created",
            "m.ss_acc_create_failed", "m.ss_select_account", "m.ss_gm_failed", "m.ss_delete_confirm",
            "m.ss_delete_failed", "m.ss_knight_required", "m.ss_granted", "m.ss_item_given",
            "m.ss_item_required", "m.ss_result_not_running", "m.ss_result_timeout", "m.ss_result_unreachable",
            "m.ss_result_invalid", "m.ss_accounts_offline", "m.ss_working", "m.ss_nothing_changed",
            "m.ss_db_problem", "ss.players.summary", "ss.players.offline", "ss.join.title",
            "ss.join.text", "ss.join.nokey", "ss.join.hint", "b.ss_copy_join",
            "m.join_info_copied", "ss.backups.title", "ss.backups.hint", "b.ss_backup_create",
            "b.ss_backup_restore", "b.ss_backup_delete", "m.ss_backup_stops", "m.ss_backup_done",
            "m.ss_restore_confirm", "m.ss_restore_done", "m.ss_backup_failed", "m.ss_backup_delete_confirm",
            "m.ss_select_backup", "ss.reset.title", "ss.reset.keepkey", "b.ss_reset_server",
            "t.ss_reset_server", "m.ss_reset_server_confirm", "m.ss_reset_note_keep", "m.ss_reset_note_newkey",
            "m.ss_reset_server_success", "m.ss_reset_failed", "b.ss_open_folder", "b.ss_open_data",
            "b.ss_open_logs", "b.ss_open_backups", "m.server_ended_unexpectedly", "m.server_stopped",
            "m.server_data_archived", "ss.host.online", "m.server_cmd_not_running"
        ));
        for (ServerSettings.Group group : ServerSettings.Group.values()) {
            keys.add("ss.group." + group.name().toLowerCase(java.util.Locale.ROOT));
        }
        for (ServerSettings.Def def : ServerSettings.all()) {
            keys.add(def.labelKey());
            keys.add(def.descriptionKey());
        }
        for (String code : new String[] {"bool", "number", "range", "length", "control", "pattern"}) {
            keys.add("ss.problem." + code);
        }
        return keys;
    }
}
