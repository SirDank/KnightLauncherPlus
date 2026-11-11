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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;

import com.formdev.flatlaf.FlatClientProperties;
import com.lucasluqui.launcher.CustomColors;
import com.lucasluqui.launcher.Fonts;
import com.lucasluqui.launcher.LocaleManager;
import com.lucasluqui.launcher.server.ServerConsoleClient;
import com.lucasluqui.launcher.server.ServerDatabase;
import com.lucasluqui.launcher.server.ServerHostManager;
import com.lucasluqui.launcher.server.ServerInput;
import com.lucasluqui.swing.Scale;
import jiconfont.icons.font_awesome.FontAwesome;

/**
 * Accounts and knights of the server: what its database holds (read-only, refreshed while the tab is showing) and the
 * changes that can be made, each of which is done by the running server itself and reported with its own answer.
 */
final class ServerAccountsPanel extends JPanel {
    private final ServerHostManager host;
    private final ServerUi ui;
    private final LocaleManager locale;
    private final Window owner;

    private final DefaultTableModel accountModel;
    private final DefaultTableModel knightModel;
    private final JTable accountTable;
    private final JTable knightTable;
    private final List<ServerDatabase.Account> accounts = new ArrayList<>();
    private final List<ServerDatabase.Knight> allKnights = new ArrayList<>();
    private final List<ServerDatabase.Knight> shownKnights = new ArrayList<>();
    private final JLabel notice = new JLabel();
    private final JLabel status = new JLabel(" ");
    private final JComboBox<String> currencyKnight = new JComboBox<>();
    private final JComboBox<String> itemKnight = new JComboBox<>();
    /** Everything that changes something: only usable while the server runs. */
    private final List<JComponent> live = new ArrayList<>();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
    private final Timer timer;
    private int ticks = 0;
    private boolean loading = false;

    ServerAccountsPanel(Window owner, ServerHostManager host, ServerUi ui) {
        super(new BorderLayout());
        this.owner = owner;
        this.host = host;
        this.ui = ui;
        this.locale = ui.locale;
        setOpaque(false);

        accountModel = readOnlyModel("ss.col.id", "ss.col.username", "ss.col.gm", "ss.col.knights", "ss.col.created");
        accountTable = new JTable(accountModel);
        ui.styleTable(accountTable);
        accountTable.getColumnModel().getColumn(0).setMaxWidth(Scale.px(50));
        accountTable.getColumnModel().getColumn(2).setMaxWidth(Scale.px(60));
        accountTable.getColumnModel().getColumn(3).setMaxWidth(Scale.px(70));
        accountTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showKnightsOfSelection();
        });

        knightModel = readOnlyModel("ss.col.knight", "ss.col.account", "ss.col.rank", "ss.col.prestige", "ss.col.crowns", "ss.col.energy");
        knightTable = new JTable(knightModel);
        ui.styleTable(knightTable);
        knightTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) syncGrantTargets();
        });

        JPanel content = new ServerUi.ScrollColumn();
        content.setBorder(BorderFactory.createEmptyBorder(Scale.px(10), Scale.px(10), Scale.px(10), Scale.px(10)));

        notice.setFont(Fonts.getFont("default", 11.0f, Font.PLAIN));
        notice.setForeground(CustomColors.INTERFACE_DEFAULT);
        notice.setAlignmentX(LEFT_ALIGNMENT);
        content.add(notice);
        content.add(Box.createVerticalStrut(Scale.px(6)));

        content.add(createSection());
        content.add(Box.createVerticalStrut(Scale.px(12)));
        content.add(accountsSection());
        content.add(Box.createVerticalStrut(Scale.px(12)));
        content.add(knightsSection());
        content.add(Box.createVerticalStrut(Scale.px(12)));
        content.add(grantSection());

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(Scale.px(16));
        add(scroll, BorderLayout.CENTER);

        status.setFont(Fonts.getFont("default", 11.5f, Font.BOLD));
        status.setBorder(BorderFactory.createEmptyBorder(Scale.px(4), Scale.px(12), Scale.px(2), Scale.px(12)));
        add(status, BorderLayout.SOUTH);

        timer = new Timer(3000, e -> tick());
        timer.setInitialDelay(3000);
        updateLive();

        // Fresh data the moment the tab is shown, not on the next timer tick.
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) onShown();
        });
    }

    private void onShown() {
        updateLive();
        reload();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        timer.start();
    }

    @Override
    public void removeNotify() {
        timer.stop();
        super.removeNotify();
    }

    private DefaultTableModel readOnlyModel(String... columnKeys) {
        String[] names = new String[columnKeys.length];
        for (int i = 0; i < names.length; i++) names[i] = locale.getValue(columnKeys[i]);
        return new DefaultTableModel(names, 0) {
            @Override public boolean isCellEditable(int row, int col) {
                return false;
            }
        };
    }

    // ---- Sections -------------------------------------------------------------------------------------------------

    private JPanel row() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, Scale.px(8), Scale.px(4)));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        return row;
    }

    private JButton liveButton(String key, FontAwesome icon, Color background, java.awt.event.ActionListener action) {
        JButton button = ui.button(key, icon, background, action);
        live.add(button);
        return button;
    }

    private JPanel createSection() {
        JPanel section = column();
        section.add(ui.subheading("ss.account.create_title"));
        JPanel row = row();
        JTextField user = new JTextField(10);
        user.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, locale.getValue("ss.account.username"));
        ui.styleField(user);
        JPasswordField pass = new JPasswordField(10);
        pass.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, locale.getValue("ss.account.password"));
        ui.styleField(pass);
        JCheckBox gm = new JCheckBox(locale.getValue("ss.account.gm"));
        gm.setOpaque(false);
        gm.setForeground(Color.WHITE);
        live.add(user);
        live.add(pass);
        live.add(gm);
        row.add(user);
        row.add(pass);
        row.add(gm);
        row.add(liveButton("b.ss_account_create", FontAwesome.USER_PLUS, CustomColors.ACCENT, e -> {
            String name = user.getText().trim();
            String password = new String(pass.getPassword());
            if (name.isEmpty() || password.isEmpty()) {
                fail(locale.getValue("m.ss_acc_empty"));
                return;
            }
            if (!ServerInput.validUsername(name)) {
                fail(locale.getValue("m.ss_acc_invalid_user"));
                return;
            }
            if (!ServerInput.validPassword(password)) {
                fail(locale.getValue("m.ss_acc_invalid_pass"));
                return;
            }
            boolean makeGm = gm.isSelected();
            working();
            ui.async("ServerAccountCreate", () -> host.createAccount(name, password, makeGm), result -> {
                if (result.ok()) {
                    user.setText("");
                    pass.setText("");
                }
                report(result, locale.getValue("m.ss_acc_created", name),
                    locale.getValue("m.ss_acc_create_failed", name) + " " + ui.describe(result));
            });
        }));
        section.add(row);
        return section;
    }

    private JPanel accountsSection() {
        JPanel section = column();
        section.add(ui.subheading("ss.account.list_title"));
        section.add(ui.scroll(accountTable, 120));
        JPanel row = row();
        row.add(liveButton("b.ss_account_password", FontAwesome.KEY, CustomColors.INTERFACE_BUTTON_BACKGROUND, e -> resetPassword()));
        row.add(liveButton("b.ss_account_gm_toggle", FontAwesome.SHIELD, CustomColors.INTERFACE_BUTTON_BACKGROUND, e -> toggleGm()));
        row.add(liveButton("b.ss_account_delete", FontAwesome.TRASH, ServerUi.DANGER, e -> deleteAccount()));
        row.add(ui.button("b.ss_account_refresh", FontAwesome.REFRESH, CustomColors.INTERFACE_BUTTON_BACKGROUND, e -> reload()));
        section.add(row);
        return section;
    }

    private JPanel knightsSection() {
        JPanel section = column();
        section.add(ui.subheading("ss.knights.title"));
        section.add(ui.scroll(knightTable, 120));
        JPanel row = row();
        row.add(liveButton("b.ss_knight_edit", FontAwesome.PENCIL, CustomColors.INTERFACE_BUTTON_BACKGROUND, e -> editKnight()));
        row.add(liveButton("b.ss_knight_delete", FontAwesome.TRASH, ServerUi.DANGER, e -> deleteKnight()));
        section.add(row);
        return section;
    }

    private JPanel grantSection() {
        JPanel section = column();
        section.add(ui.subheading("ss.currency.grant_title"));
        JPanel currency = row();
        currencyKnight.setPreferredSize(new Dimension(Scale.px(150), Scale.px(26)));
        JComboBox<String> type = new JComboBox<>(new String[] {"crowns", "energy"});
        type.setPreferredSize(new Dimension(Scale.px(90), Scale.px(26)));
        JSpinner amount = new JSpinner(new SpinnerNumberModel(1000, 1, 2_000_000_000, 100));
        amount.setPreferredSize(new Dimension(Scale.px(120), Scale.px(26)));
        live.add(currencyKnight);
        live.add(type);
        live.add(amount);
        currency.add(currencyKnight);
        currency.add(type);
        currency.add(amount);
        currency.add(liveButton("b.ss_currency_grant", FontAwesome.PLUS_CIRCLE, CustomColors.INTERFACE_BUTTON_BACKGROUND, e -> {
            String knight = (String) currencyKnight.getSelectedItem();
            if (knight == null) {
                fail(locale.getValue("m.ss_knight_required"));
                return;
            }
            String currencyName = String.valueOf(type.getSelectedItem());
            long value = ((Number) amount.getValue()).longValue();
            working();
            ui.async("ServerGrant", () -> host.grantBalance(knight, currencyName, value), result ->
                report(result, locale.getValue("m.ss_granted", new String[] {String.valueOf(value), currencyName, knight}),
                    ui.describe(result)));
        }));
        section.add(currency);

        section.add(Box.createVerticalStrut(Scale.px(8)));
        section.add(ui.subheading("ss.item.give_title"));
        JPanel give = row();
        itemKnight.setPreferredSize(new Dimension(Scale.px(150), Scale.px(26)));
        JTextField item = new JTextField(16);
        item.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, locale.getValue("ss.item.placeholder"));
        ui.styleField(item);
        JSpinner count = new JSpinner(new SpinnerNumberModel(1, 1, 20, 1));
        count.setPreferredSize(new Dimension(Scale.px(70), Scale.px(26)));
        live.add(itemKnight);
        live.add(item);
        live.add(count);
        give.add(itemKnight);
        give.add(item);
        give.add(count);
        give.add(liveButton("b.ss_item_give", FontAwesome.GIFT, CustomColors.INTERFACE_BUTTON_BACKGROUND, e -> {
            String knight = (String) itemKnight.getSelectedItem();
            String reference = item.getText().trim();
            if (knight == null || reference.isEmpty()) {
                fail(locale.getValue("m.ss_item_required"));
                return;
            }
            int number = ((Number) count.getValue()).intValue();
            working();
            ui.async("ServerGiveItem", () -> host.giveItem(knight, number, reference), result ->
                report(result, locale.getValue("m.ss_item_given", new String[] {String.valueOf(number), reference, knight}),
                    ui.describe(result)));
        }));
        section.add(give);
        return section;
    }

    private JPanel column() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(LEFT_ALIGNMENT);
        return panel;
    }

    // ---- Actions --------------------------------------------------------------------------------------------------

    private ServerDatabase.Account selectedAccount() {
        int row = accountTable.getSelectedRow();
        if (row < 0 || row >= accounts.size()) {
            fail(locale.getValue("m.ss_select_account"));
            return null;
        }
        return accounts.get(row);
    }

    private ServerDatabase.Knight selectedKnight() {
        int row = knightTable.getSelectedRow();
        if (row < 0 || row >= shownKnights.size()) {
            fail(locale.getValue("m.ss_knight_required"));
            return null;
        }
        return shownKnights.get(row);
    }

    private void resetPassword() {
        ServerDatabase.Account account = selectedAccount();
        if (account == null) return;
        JPasswordField field = new JPasswordField(20);
        JPanel panel = new JPanel(new BorderLayout(0, Scale.px(6)));
        panel.add(new JLabel(locale.getValue("m.ss_password_prompt", account.username())), BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(owner, panel, locale.getValue("t.ss_password_title"),
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        String password = new String(field.getPassword());
        if (!ServerInput.validPassword(password)) {
            fail(locale.getValue("m.ss_acc_invalid_pass"));
            return;
        }
        working();
        ui.async("ServerPassword", () -> host.resetPassword(account.username(), password), result ->
            report(result, result.message(), ui.describe(result)));
    }

    private void toggleGm() {
        ServerDatabase.Account account = selectedAccount();
        if (account == null) return;
        working();
        ui.async("ServerGm", () -> host.setGameMaster(account.username(), !account.gameMaster()), result -> {
            report(result, result.message(), locale.getValue("m.ss_gm_failed", account.username()) + " " + ui.describe(result));
        });
    }

    private void deleteAccount() {
        ServerDatabase.Account account = selectedAccount();
        if (account == null) return;
        if (JOptionPane.showConfirmDialog(owner, locale.getValue("m.ss_delete_confirm", account.username()),
            locale.getValue("t.ss_delete_account"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        working();
        ui.async("ServerDeleteAccount", () -> host.deleteAccount(account.username()), result ->
            report(result, result.message(), locale.getValue("m.ss_delete_failed", account.username()) + " " + ui.describe(result)));
    }

    private void deleteKnight() {
        ServerDatabase.Knight knight = selectedKnight();
        if (knight == null) return;
        if (JOptionPane.showConfirmDialog(owner, locale.getValue("m.ss_knight_delete_confirm", knight.name()),
            locale.getValue("b.ss_knight_delete"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        working();
        ui.async("ServerDeleteKnight", () -> host.deleteKnight(knight.username(), knight.name()), result ->
            report(result, result.message(), ui.describe(result)));
    }

    private void editKnight() {
        ServerDatabase.Knight knight = selectedKnight();
        if (knight == null) return;
        JSpinner crowns = new JSpinner(new SpinnerNumberModel(knight.crowns(), 0L, (long) Integer.MAX_VALUE, 100L));
        JSpinner energy = new JSpinner(new SpinnerNumberModel(knight.energy(), 0L, (long) Integer.MAX_VALUE, 10L));
        JSpinner rank = new JSpinner(new SpinnerNumberModel(clamp(knight.rank(), 1, 10), 1, 10, 1));
        JSpinner subrank = new JSpinner(new SpinnerNumberModel(clamp(knight.subrank(), 1, 4), 1, 4, 1));
        JSpinner prestige = new JSpinner(new SpinnerNumberModel(knight.prestige(), 0, Integer.MAX_VALUE, 1));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(Scale.px(3), Scale.px(4), Scale.px(3), Scale.px(4));
        c.anchor = GridBagConstraints.WEST;
        String[] labels = {"ss.col.crowns", "ss.col.energy", "ss.col.rank", "ss.col.subrank", "ss.col.prestige"};
        JSpinner[] spinners = {crowns, energy, rank, subrank, prestige};
        for (int i = 0; i < labels.length; i++) {
            c.gridy = i;
            c.gridx = 0;
            form.add(new JLabel(locale.getValue(labels[i])), c);
            c.gridx = 1;
            spinners[i].setPreferredSize(new Dimension(Scale.px(130), Scale.px(26)));
            form.add(spinners[i], c);
        }
        if (JOptionPane.showConfirmDialog(owner, form, locale.getValue("t.ss_knight_edit", knight.name()),
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;

        long newCrowns = ((Number) crowns.getValue()).longValue();
        long newEnergy = ((Number) energy.getValue()).longValue();
        int newRank = ((Number) rank.getValue()).intValue();
        int newSubrank = ((Number) subrank.getValue()).intValue();
        int newPrestige = ((Number) prestige.getValue()).intValue();
        working();
        ui.async("ServerEditKnight", () -> {
            ServerConsoleClient.Result last = null;
            if (newCrowns != knight.crowns()) {
                last = host.setBalance(knight.name(), "crowns", newCrowns);
                if (!last.ok()) return last;
            }
            if (newEnergy != knight.energy()) {
                last = host.setBalance(knight.name(), "energy", newEnergy);
                if (!last.ok()) return last;
            }
            if (newRank != knight.rank() || newSubrank != knight.subrank()) {
                last = host.setRank(knight.name(), newRank, newSubrank);
                if (!last.ok()) return last;
            }
            if (newPrestige != knight.prestige()) {
                last = host.setPrestige(knight.name(), newPrestige);
            }
            return last;
        }, result -> {
            if (result == null) {
                setStatus(locale.getValue("m.ss_nothing_changed"), false);
            } else {
                report(result, result.message(), ui.describe(result));
            }
        });
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ---- Status and data ------------------------------------------------------------------------------------------

    private void working() {
        setStatus(locale.getValue("m.ss_working"), false);
    }

    private void fail(String text) {
        setStatus(text, true);
    }

    private void setStatus(String text, boolean error) {
        status.setForeground(error ? ServerUi.ERROR_TEXT : ServerUi.OK_TEXT);
        status.setText(text == null || text.isEmpty() ? " " : text);
    }

    /** Shows the outcome of a console command and refreshes the lists. */
    private void report(ServerConsoleClient.Result result, String success, String failure) {
        setStatus(result.ok() ? success : failure, !result.ok());
        reload();
    }

    private void tick() {
        updateLive();
        ticks++;
        if (ticks % 2 == 0 && isShowing() && host.isServerRunning()) reload();
    }

    private void updateLive() {
        boolean running = host.isServerRunning();
        for (JComponent component : live) component.setEnabled(running);
        String problem = host.getDatabaseProblem();
        if (problem != null) {
            notice.setForeground(ServerUi.ERROR_TEXT);
            notice.setText(locale.getValue("m.ss_db_problem", problem));
        } else {
            notice.setForeground(CustomColors.INTERFACE_DEFAULT);
            notice.setText(running ? " " : locale.getValue("m.ss_accounts_offline"));
        }
    }

    /** Reads the lists from the server's database in the background. */
    void reload() {
        if (loading) return;
        loading = true;
        ui.async("ServerAccountsLoad", () -> new Object[] {host.getAccounts(), host.getKnights()}, data -> {
            loading = false;
            @SuppressWarnings("unchecked") List<ServerDatabase.Account> newAccounts = (List<ServerDatabase.Account>) data[0];
            @SuppressWarnings("unchecked") List<ServerDatabase.Knight> newKnights = (List<ServerDatabase.Knight>) data[1];
            int selectedId = accountTable.getSelectedRow() >= 0 && accountTable.getSelectedRow() < accounts.size()
                ? accounts.get(accountTable.getSelectedRow()).id() : -1;
            accounts.clear();
            accounts.addAll(newAccounts);
            allKnights.clear();
            allKnights.addAll(newKnights);
            accountModel.setRowCount(0);
            int reselect = -1;
            for (int i = 0; i < accounts.size(); i++) {
                ServerDatabase.Account account = accounts.get(i);
                if (account.id() == selectedId) reselect = i;
                accountModel.addRow(new Object[] {
                    account.id(), account.username(), locale.getValue(account.gameMaster() ? "ss.value.yes" : "ss.value.no"),
                    account.knightCount(), account.createdAt() > 0 ? dateFormat.format(new Date(account.createdAt())) : "--"
                });
            }
            if (reselect >= 0) accountTable.setRowSelectionInterval(reselect, reselect);
            showKnightsOfSelection();
            updateLive();
        });
    }

    private void showKnightsOfSelection() {
        int row = accountTable.getSelectedRow();
        int accountId = row >= 0 && row < accounts.size() ? accounts.get(row).id() : -1;
        String selectedName = knightTable.getSelectedRow() >= 0 && knightTable.getSelectedRow() < shownKnights.size()
            ? shownKnights.get(knightTable.getSelectedRow()).name() : null;
        shownKnights.clear();
        knightModel.setRowCount(0);
        int reselect = -1;
        for (ServerDatabase.Knight knight : allKnights) {
            if (accountId >= 0 && knight.accountId() != accountId) continue;
            if (knight.name().equals(selectedName)) reselect = shownKnights.size();
            shownKnights.add(knight);
            knightModel.addRow(new Object[] {
                knight.name(), knight.username(), knight.rank() + "-" + knight.subrank(), knight.prestige(),
                knight.crowns(), knight.energy()
            });
        }
        if (reselect >= 0) knightTable.setRowSelectionInterval(reselect, reselect);
        fillKnightChoices();
        syncGrantTargets();
    }

    /** The grant rows offer every knight; the table selection picks which one is shown. */
    private void fillKnightChoices() {
        Object keepCurrency = currencyKnight.getSelectedItem();
        Object keepItem = itemKnight.getSelectedItem();
        currencyKnight.removeAllItems();
        itemKnight.removeAllItems();
        for (ServerDatabase.Knight knight : allKnights) {
            currencyKnight.addItem(knight.name());
            itemKnight.addItem(knight.name());
        }
        if (keepCurrency != null) currencyKnight.setSelectedItem(keepCurrency);
        if (keepItem != null) itemKnight.setSelectedItem(keepItem);
    }

    private void syncGrantTargets() {
        int row = knightTable.getSelectedRow();
        if (row < 0 || row >= shownKnights.size()) return;
        String name = shownKnights.get(row).name();
        currencyKnight.setSelectedItem(name);
        itemKnight.setSelectedItem(name);
    }
}
