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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
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
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import javax.swing.table.DefaultTableModel;

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
 * Multi-tab dialog for configuring the Tortodrone server and administering accounts/knights.
 */
public class ServerSettingsDialog extends JDialog {
    private static final int WIDTH = 700;
    private static final int HEIGHT = 740;

    private final ServerHostManager hostManager;
    private final LocaleManager locale;
    private final Runnable onSaved;
    private final Runnable restart;
    private final File file = ServerPropertiesFile.resolve(new File(LauncherGlobals.USER_DIR));

    private final Map<String, Field> fields = new LinkedHashMap<>();
    private final Map<String, String> initialValues = new LinkedHashMap<>();
    private Field portField;
    private Field lanField;

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
            Number value = (Number) ((JSpinner) editor).getValue();
            return editor.getClientProperty("float") != null ? String.valueOf(value.floatValue()) : String.valueOf(value.longValue());
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
            } else if (editor.getClientProperty("float") != null) {
                ((JSpinner) editor).setValue(Double.parseDouble(value));
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

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(Fonts.getFont("default", 12.0f, Font.BOLD));
        tabs.addTab(locale.getValue("ss.tab.config"), buildConfigTab());
        tabs.addTab(locale.getValue("ss.tab.accounts"), buildAccountsTab());
        root.add(tabs, BorderLayout.CENTER);

        root.add(buttons(), BorderLayout.SOUTH);
    }

    private JPanel buildConfigTab() {
        JPanel container = new JPanel(new BorderLayout());
        container.setOpaque(false);

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
        container.add(scroll, BorderLayout.CENTER);
        return container;
    }

    private JPanel buildAccountsTab() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(Scale.px(14), Scale.px(10), Scale.px(14), Scale.px(10)));

        JTextField knightField = new JTextField(10);
        JTextField itemKnightField = new JTextField(10);

        // Create Account Section
        panel.add(subheading("ss.account.create_title"));
        JPanel createRow = new JPanel(new FlowLayout(FlowLayout.LEFT, Scale.px(8), Scale.px(4)));
        createRow.setOpaque(false);
        createRow.setAlignmentX(LEFT_ALIGNMENT);

        JTextField newUserField = new JTextField(10);
        newUserField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, locale.getValue("ss.account.username"));
        styleField(newUserField);
        createRow.add(newUserField);

        JPasswordField newPassField = new JPasswordField(10);
        newPassField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, locale.getValue("ss.account.password"));
        styleField(newPassField);
        createRow.add(newPassField);

        JCheckBox gmCheck = new JCheckBox(locale.getValue("ss.account.gm"));
        gmCheck.setOpaque(false);
        gmCheck.setForeground(Color.WHITE);
        createRow.add(gmCheck);

        // Accounts Table Section
        String[] cols = new String[] { locale.getValue("ss.col.id"), locale.getValue("ss.col.username"), locale.getValue("ss.col.gm"),
            locale.getValue("ss.col.knights"), locale.getValue("ss.col.created") };
        DefaultTableModel accountModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int row, int col) {
                return false;
            }
        };

        JTable accountTable = new JTable(accountModel);
        accountTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        accountTable.setBackground(CustomColors.INTERFACE_TEXTFIELD_BACKGROUND);
        accountTable.setForeground(Color.WHITE);
        accountTable.setFont(Fonts.getFont("default", 11.0f, Font.PLAIN));
        accountTable.setRowHeight(Scale.px(22));
        accountTable.getTableHeader().setBackground(CustomColors.INTERFACE_BUTTON_BACKGROUND);
        accountTable.getTableHeader().setForeground(Color.WHITE);
        accountTable.getTableHeader().setFont(Fonts.getFont("default", 11.0f, Font.BOLD));
        accountTable.getColumnModel().getColumn(0).setMaxWidth(Scale.px(50));
        accountTable.getColumnModel().getColumn(1).setPreferredWidth(Scale.px(130));
        accountTable.getColumnModel().getColumn(2).setPreferredWidth(Scale.px(50));
        accountTable.getColumnModel().getColumn(3).setPreferredWidth(Scale.px(160));
        accountTable.getColumnModel().getColumn(4).setPreferredWidth(Scale.px(140));

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        List<ServerHostManager.ServerAccount> accountListCache = new ArrayList<>();
        Runnable reloadAccounts = () -> {
            accountModel.setRowCount(0);
            accountListCache.clear();
            List<ServerHostManager.ServerAccount> accounts = hostManager.getAccounts();
            accountListCache.addAll(accounts);
            for (ServerHostManager.ServerAccount acc : accounts) {
                String createdStr = acc.createdAt() > 0 ? dateFormat.format(new Date(acc.createdAt())) : "--";
                String knightsStr = acc.knights() != null && !acc.knights().isEmpty() ? acc.knights() : (acc.knightCount() == 0 ? locale.getValue("ss.value.none") : locale.getValue("ss.value.knights_count", String.valueOf(acc.knightCount())));
                accountModel.addRow(new Object[] {
                    acc.id(),
                    acc.username(),
                    locale.getValue(acc.gameMaster() ? "ss.value.yes" : "ss.value.no"),
                    knightsStr,
                    createdStr
                });
            }
        };

        accountTable.getSelectionModel().addListSelectionListener(e -> {
            int row = accountTable.getSelectedRow();
            if (row >= 0 && row < accountListCache.size()) {
                ServerHostManager.ServerAccount acc = accountListCache.get(row);
                if (acc.knights() != null && !acc.knights().isEmpty()) {
                    String firstKnight = acc.knights().split(",")[0].trim();
                    knightField.setText(firstKnight);
                    itemKnightField.setText(firstKnight);
                }
            }
        });

        reloadAccounts.run();

        JButton createBtn = button("b.ss_account_create", FontAwesome.USER_PLUS, CustomColors.ACCENT, e -> {
            String u = newUserField.getText().trim();
            String p = new String(newPassField.getPassword());
            if (u.isEmpty() || p.isEmpty()) {
                JOptionPane.showMessageDialog(this, locale.getValue("m.ss_acc_empty"), locale.getValue("t.ss_error"), JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (!u.matches("[A-Za-z][A-Za-z0-9_-]{2,31}")) {
                JOptionPane.showMessageDialog(this, locale.getValue("m.ss_acc_invalid_user"), locale.getValue("t.ss_acc_invalid_user"), JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (p.length() < 8 || p.length() > 128) {
                JOptionPane.showMessageDialog(this, locale.getValue("m.ss_acc_invalid_pass"), locale.getValue("t.ss_acc_invalid_pass"), JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (!hostManager.isServerRunning()) {
                JOptionPane.showMessageDialog(this, locale.getValue("m.ss_need_running_create"), locale.getValue("t.ss_offline"), JOptionPane.WARNING_MESSAGE);
                return;
            }
            boolean gm = gmCheck.isSelected();
            newUserField.setText("");
            newPassField.setText("");
            new Thread(() -> {
                boolean created = hostManager.createAccount(u, p, gm);
                javax.swing.SwingUtilities.invokeLater(() -> {
                    reloadAccounts.run();
                    JOptionPane.showMessageDialog(this, locale.getValue(created ? "m.ss_acc_created" : "m.ss_acc_create_failed", u),
                        locale.getValue(created ? "t.ss_success" : "t.ss_error"),
                        created ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE);
                });
            }, "ServerAccountCreate").start();
        });
        createRow.add(createBtn);
        panel.add(createRow);

        panel.add(Box.createVerticalStrut(Scale.px(14)));

        // Player Accounts View Section
        panel.add(subheading("ss.account.list_title"));
        JScrollPane accountScroll = new JScrollPane(accountTable);
        accountScroll.setPreferredSize(new Dimension(Scale.px(640), Scale.px(130)));
        accountScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, Scale.px(130)));
        accountScroll.setAlignmentX(LEFT_ALIGNMENT);
        accountScroll.setBorder(BorderFactory.createLineBorder(new Color(0x33, 0x33, 0x33)));
        panel.add(accountScroll);

        JPanel tableActions = new JPanel(new FlowLayout(FlowLayout.LEFT, Scale.px(8), Scale.px(4)));
        tableActions.setOpaque(false);
        tableActions.setAlignmentX(LEFT_ALIGNMENT);

        JButton toggleGmBtn = button("b.ss_account_gm_toggle", FontAwesome.SHIELD, CustomColors.INTERFACE_BUTTON_BACKGROUND, e -> {
            int row = accountTable.getSelectedRow();
            if (row < 0 || row >= accountListCache.size()) {
                JOptionPane.showMessageDialog(this, locale.getValue("m.ss_select_account"), locale.getValue("t.ss_no_selection"), JOptionPane.WARNING_MESSAGE);
                return;
            }
            ServerHostManager.ServerAccount acc = accountListCache.get(row);
            if (!hostManager.toggleGameMaster(acc.id(), acc.username(), acc.gameMaster())) {
                JOptionPane.showMessageDialog(this, locale.getValue("m.ss_gm_failed", acc.username()), locale.getValue("t.ss_error"), JOptionPane.ERROR_MESSAGE);
            }
            reloadAccountsSoon(reloadAccounts);
        });
        tableActions.add(toggleGmBtn);

        JButton deleteAccBtn = button("b.ss_account_delete", FontAwesome.TRASH, new Color(180, 40, 40), e -> {
            int row = accountTable.getSelectedRow();
            if (row < 0 || row >= accountListCache.size()) {
                JOptionPane.showMessageDialog(this, locale.getValue("m.ss_select_account"), locale.getValue("t.ss_no_selection"), JOptionPane.WARNING_MESSAGE);
                return;
            }
            ServerHostManager.ServerAccount acc = accountListCache.get(row);
            int confirm = JOptionPane.showConfirmDialog(this, locale.getValue("m.ss_delete_confirm", acc.username()),
                locale.getValue("t.ss_delete_account"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm == JOptionPane.YES_OPTION) {
                if (!hostManager.deleteAccount(acc.id(), acc.username())) {
                    JOptionPane.showMessageDialog(this, locale.getValue("m.ss_delete_failed", acc.username()), locale.getValue("t.ss_error"), JOptionPane.ERROR_MESSAGE);
                }
                reloadAccountsSoon(reloadAccounts);
            }
        });
        tableActions.add(deleteAccBtn);

        JButton refreshAccBtn = button("b.ss_account_refresh", FontAwesome.REFRESH, CustomColors.INTERFACE_BUTTON_BACKGROUND, e -> {
            reloadAccounts.run();
        });
        tableActions.add(refreshAccBtn);
        panel.add(tableActions);

        panel.add(Box.createVerticalStrut(Scale.px(14)));

        // Currency Grants Section
        panel.add(subheading("ss.currency.grant_title"));
        JPanel currRow = new JPanel(new FlowLayout(FlowLayout.LEFT, Scale.px(8), Scale.px(4)));
        currRow.setOpaque(false);
        currRow.setAlignmentX(LEFT_ALIGNMENT);

        knightField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, locale.getValue("ss.currency.knight_name"));
        styleField(knightField);
        currRow.add(knightField);

        JComboBox<String> currType = new JComboBox<>(new String[] { "crowns", "energy" });
        currType.setPreferredSize(new Dimension(Scale.px(90), Scale.px(26)));
        currRow.add(currType);

        JSpinner currAmount = spinner(1000, 1, 2_000_000_000, 100, false);
        currRow.add(currAmount);

        JButton grantBtn = button("b.ss_currency_grant", FontAwesome.PLUS_CIRCLE, CustomColors.INTERFACE_BUTTON_BACKGROUND, e -> {
            String k = knightField.getText().trim();
            if (k.isEmpty()) {
                JOptionPane.showMessageDialog(this, locale.getValue("m.ss_knight_required"), locale.getValue("t.ss_error"), JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (!hostManager.isServerRunning()) {
                JOptionPane.showMessageDialog(this, locale.getValue("m.ss_need_running_grant"), locale.getValue("t.ss_offline"), JOptionPane.WARNING_MESSAGE);
                return;
            }
            String type = String.valueOf(currType.getSelectedItem());
            long amt = ((Number) currAmount.getValue()).longValue();
            hostManager.sendCommand("grant " + k + " " + type + " " + amt);
            JOptionPane.showMessageDialog(this, locale.getValue("m.ss_granted", new String[]{String.valueOf(amt), type, k}), locale.getValue("t.ss_success"), JOptionPane.INFORMATION_MESSAGE);
        });
        currRow.add(grantBtn);
        panel.add(currRow);

        panel.add(Box.createVerticalStrut(Scale.px(16)));

        // Item Grants Section
        panel.add(subheading("ss.item.give_title"));
        JPanel itemRow = new JPanel(new FlowLayout(FlowLayout.LEFT, Scale.px(8), Scale.px(4)));
        itemRow.setOpaque(false);
        itemRow.setAlignmentX(LEFT_ALIGNMENT);

        itemKnightField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, locale.getValue("ss.currency.knight_name"));
        styleField(itemKnightField);
        itemRow.add(itemKnightField);

        JTextField itemConfigField = new JTextField(16);
        itemConfigField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, locale.getValue("ss.item.placeholder"));
        styleField(itemConfigField);
        itemRow.add(itemConfigField);

        JSpinner itemCount = spinner(1, 1, 20, 1, false);
        itemRow.add(itemCount);

        JButton giveBtn = button("b.ss_item_give", FontAwesome.GIFT, CustomColors.INTERFACE_BUTTON_BACKGROUND, e -> {
            String k = itemKnightField.getText().trim();
            String item = itemConfigField.getText().trim();
            if (k.isEmpty() || item.isEmpty()) {
                JOptionPane.showMessageDialog(this, locale.getValue("m.ss_item_required"), locale.getValue("t.ss_error"), JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (!hostManager.isServerRunning()) {
                JOptionPane.showMessageDialog(this, locale.getValue("m.ss_need_running_item"), locale.getValue("t.ss_offline"), JOptionPane.WARNING_MESSAGE);
                return;
            }
            int count = ((Number) itemCount.getValue()).intValue();
            hostManager.sendCommand("giveitem " + k + " " + count + " " + item);
            JOptionPane.showMessageDialog(this, locale.getValue("m.ss_item_given", new String[]{String.valueOf(count), item, k}), locale.getValue("t.ss_success"), JOptionPane.INFORMATION_MESSAGE);
        });
        itemRow.add(giveBtn);
        panel.add(itemRow);

        JScrollPane scroll = new JScrollPane(panel);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.add(scroll, BorderLayout.CENTER);
        return wrap;
    }

    /** A running server applies account changes a moment after the command was sent. */
    private static void reloadAccountsSoon(Runnable reload) {
        reload.run();
        javax.swing.Timer later = new javax.swing.Timer(700, e -> reload.run());
        later.setRepeats(false);
        later.start();
    }

    private JLabel subheading(String key) {
        JLabel label = new JLabel(locale.getValue(key));
        label.setFont(Fonts.getFont("default", 14.0f, Font.BOLD));
        label.setForeground(CustomColors.ACCENT_LIGHT);
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private void styleField(JTextField field) {
        field.setFont(Fonts.getFont("code", 11.5f, Font.PLAIN));
        field.setForeground(Color.WHITE);
        field.setBackground(CustomColors.INTERFACE_TEXTFIELD_BACKGROUND);
        field.setCaretColor(Color.WHITE);
        field.setPreferredSize(new Dimension(Scale.px(140), Scale.px(26)));
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
        if ("server.startupMap".equals(def.key)) {
            JComboBox<String> mapBox = new JComboBox<>(new String[] {
                "haven", "arcade", "bazaar", "crash_site", "garrison",
                "hall_of_heroes", "laboratory", "rescue_camp", "advanced_training"
            });
            mapBox.setFont(Fonts.getFont("code", 11.5f, Font.PLAIN));
            mapBox.setPreferredSize(new Dimension(Scale.px(180), Scale.px(26)));
            return mapBox;
        }
        if ("server.logMode".equals(def.key)) {
            JComboBox<String> logBox = new JComboBox<>(new String[] { "normal", "debug" });
            logBox.setFont(Fonts.getFont("code", 11.5f, Font.PLAIN));
            logBox.setPreferredSize(new Dimension(Scale.px(180), Scale.px(26)));
            return logBox;
        }
        switch (def.type) {
            case BOOLEAN:
                return new JCheckBox();
            case INT:
                return spinner(Integer.parseInt(def.defaultValue), (int) def.min, (int) def.max, 1, false);
            case STRING:
                JTextField text = new JTextField(16);
                text.setFont(Fonts.getFont("code", 11.5f, Font.PLAIN));
                text.setForeground(Color.WHITE);
                text.setBackground(CustomColors.INTERFACE_TEXTFIELD_BACKGROUND);
                text.setCaretColor(Color.WHITE);
                text.setPreferredSize(new Dimension(Scale.px(180), Scale.px(26)));
                text.setMinimumSize(new Dimension(Scale.px(120), Scale.px(26)));
                return text;
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
        left.add(button("b.ss_reset_server", FontAwesome.TRASH, new Color(180, 40, 40), e -> confirmResetServer()));
        left.add(button("b.ss_open_folder", FontAwesome.FOLDER_OPEN, CustomColors.INTERFACE_BUTTON_BACKGROUND, e -> openFolder()));
        bar.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, Scale.px(8), 0));
        right.setOpaque(false);
        right.add(button("b.ss_close", null, CustomColors.INTERFACE_BUTTON_BACKGROUND, e -> dispose()));
        right.add(button("b.ss_save", FontAwesome.CHECK, CustomColors.ACCENT, e -> save()));
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private void confirmResetServer() {
        int choice = JOptionPane.showConfirmDialog(
            this,
            locale.getValue("m.ss_reset_server_confirm"),
            locale.getValue("t.ss_reset_server"),
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        );
        if (choice == JOptionPane.YES_OPTION) {
            boolean success = hostManager.resetServerData();
            if (success) {
                JOptionPane.showMessageDialog(
                    this,
                    locale.getValue("m.ss_reset_server_success"),
                    locale.getValue("t.ss_reset_server"),
                    JOptionPane.INFORMATION_MESSAGE
                );
                loadValues();
                if (onSaved != null) onSaved.run();
            } else {
                JOptionPane.showMessageDialog(
                    this,
                    locale.getValue("m.ss_reset_failed"),
                    locale.getValue("t.ss_reset_server"),
                    JOptionPane.ERROR_MESSAGE
                );
            }
        }
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
        values.put("server.allowLan", lanField.get());
        List<String> problems = ServerSettings.validate(values);
        if (!problems.isEmpty()) {
            JOptionPane.showMessageDialog(this, locale.getValue("m.ss_invalid") + "\n\n" + String.join("\n", problems),
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

    public static List<String> requiredLocaleKeys() {
        List<String> keys = new ArrayList<>(List.of("ss.title", "ss.subtitle", "ss.server.port", "ss.server.port.desc",
            "b.ss_save", "b.ss_close", "b.ss_reset_all", "b.ss_reset_server", "b.ss_open_folder", "tt.ss_reset_field",
            "m.ss_invalid", "m.ss_save_failed", "m.ss_saved", "m.ss_saved_restart", "m.ss_reset_server_confirm",
            "m.ss_reset_server_success", "t.ss_reset_server", "b.server_settings", "ss.tab.config", "ss.tab.accounts",
            "ss.account.create_title", "ss.account.username", "ss.account.password", "ss.account.gm", "b.ss_account_create",
            "ss.account.list_title", "b.ss_account_gm_toggle", "b.ss_account_delete", "b.ss_account_refresh",
            "ss.currency.grant_title", "ss.currency.knight_name", "b.ss_currency_grant", "ss.item.give_title", "b.ss_item_give",
            "ss.col.id", "ss.col.username", "ss.col.gm", "ss.col.knights", "ss.col.created", "ss.value.yes", "ss.value.no", "ss.value.none",
            "ss.value.knights_count", "t.ss_error", "t.ss_success", "t.ss_offline", "t.ss_no_selection", "t.ss_delete_account",
            "t.ss_acc_invalid_user", "t.ss_acc_invalid_pass", "m.ss_acc_empty", "m.ss_acc_invalid_user", "m.ss_acc_invalid_pass",
            "m.ss_need_running_create", "m.ss_acc_created", "m.ss_acc_create_failed", "m.ss_select_account", "m.ss_gm_failed",
            "m.ss_delete_confirm", "m.ss_delete_failed", "m.ss_knight_required", "m.ss_need_running_grant", "m.ss_granted",
            "m.ss_item_required", "m.ss_need_running_item", "m.ss_item_given", "ss.item.placeholder", "m.ss_reset_failed",
            "m.server_cmd_not_running", "m.server_cmd_unreachable"));
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
