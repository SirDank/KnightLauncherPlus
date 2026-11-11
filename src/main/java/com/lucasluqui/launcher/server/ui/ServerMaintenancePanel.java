package com.lucasluqui.launcher.server.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.Window;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;

import com.lucasluqui.launcher.CustomColors;
import com.lucasluqui.launcher.Fonts;
import com.lucasluqui.launcher.LocaleManager;
import com.lucasluqui.launcher.server.ServerBackups;
import com.lucasluqui.launcher.server.ServerHostManager;
import com.lucasluqui.swing.Scale;
import jiconfont.icons.font_awesome.FontAwesome;

import static com.lucasluqui.launcher.Log.log;

/** What friends need to join, backups of the server's data, starting over, and the server's folders. */
final class ServerMaintenancePanel extends JPanel {
    private final ServerHostManager host;
    private final ServerUi ui;
    private final LocaleManager locale;
    private final Window owner;
    private final Runnable onDataChanged;

    private final JTextArea joinText = new JTextArea();
    private final DefaultTableModel backupModel;
    private final JTable backupTable;
    private final List<ServerBackups.Backup> backups = new ArrayList<>();
    private final JLabel status = new JLabel(" ");
    private final JCheckBox keepKey = new JCheckBox();
    private final List<JComponent> busyLocked = new ArrayList<>();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
    private final Timer timer;

    ServerMaintenancePanel(Window owner, ServerHostManager host, ServerUi ui, Runnable onDataChanged) {
        super(new BorderLayout());
        this.owner = owner;
        this.host = host;
        this.ui = ui;
        this.locale = ui.locale;
        this.onDataChanged = onDataChanged;
        setOpaque(false);

        String[] columns = {locale.getValue("ss.col.backup"), locale.getValue("ss.col.created"), locale.getValue("ss.col.size")};
        backupModel = new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int col) {
                return false;
            }
        };
        backupTable = new JTable(backupModel);
        ui.styleTable(backupTable);
        backupTable.getColumnModel().getColumn(1).setPreferredWidth(Scale.px(150));
        backupTable.getColumnModel().getColumn(1).setMinWidth(Scale.px(150));
        backupTable.getColumnModel().getColumn(2).setPreferredWidth(Scale.px(90));
        backupTable.getColumnModel().getColumn(2).setMinWidth(Scale.px(90));

        JPanel content = new ServerUi.ScrollColumn();
        content.setBorder(BorderFactory.createEmptyBorder(Scale.px(10), Scale.px(10), Scale.px(10), Scale.px(10)));
        content.add(joinSection());
        content.add(Box.createVerticalStrut(Scale.px(14)));
        content.add(backupSection());
        content.add(Box.createVerticalStrut(Scale.px(14)));
        content.add(resetSection());
        content.add(Box.createVerticalStrut(Scale.px(14)));
        content.add(foldersSection());

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(Scale.px(16));
        add(scroll, BorderLayout.CENTER);

        status.setFont(Fonts.getFont("default", 11.5f, Font.BOLD));
        status.setBorder(BorderFactory.createEmptyBorder(Scale.px(4), Scale.px(12), Scale.px(2), Scale.px(12)));
        add(status, BorderLayout.SOUTH);

        timer = new Timer(3000, e -> {
            if (isShowing()) refreshJoin();
        });
        timer.setInitialDelay(3000);

        // Fresh data the moment the tab is shown, not on the next timer tick.
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) onShown();
        });
        reloadBackups();
    }

    private void onShown() {
        refreshJoin();
        reloadBackups();
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

    private JPanel column() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(LEFT_ALIGNMENT);
        return panel;
    }

    private JPanel row() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, Scale.px(8), Scale.px(4)));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        return row;
    }

    private JTextArea note(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(Fonts.getFont("default", 10.5f, Font.PLAIN));
        area.setForeground(CustomColors.INTERFACE_MAINPANE_FOOTNOTE.brighter());
        area.setBorder(null);
        area.setAlignmentX(LEFT_ALIGNMENT);
        return area;
    }

    private JButton lockedButton(String key, FontAwesome icon, Color background, java.awt.event.ActionListener action) {
        JButton button = ui.button(key, icon, background, action);
        busyLocked.add(button);
        return button;
    }

    // ---- Join info ------------------------------------------------------------------------------------------------

    private JPanel joinSection() {
        JPanel section = column();
        section.add(ui.subheading("ss.join.title"));
        joinText.setEditable(false);
        joinText.setLineWrap(true);
        joinText.setFont(Fonts.getFont("default", 11.0f, Font.PLAIN));
        joinText.setForeground(Color.WHITE);
        joinText.setBackground(CustomColors.INTERFACE_TEXTFIELD_BACKGROUND);
        joinText.setBorder(BorderFactory.createEmptyBorder(Scale.px(6), Scale.px(8), Scale.px(6), Scale.px(8)));
        joinText.setAlignmentX(LEFT_ALIGNMENT);
        section.add(joinText);
        section.add(note(locale.getValue("ss.join.hint")));
        JPanel row = row();
        row.add(ui.button("b.ss_copy_join", FontAwesome.CLIPBOARD, CustomColors.ACCENT, e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(joinText.getText()), null);
            setStatus(locale.getValue("m.join_info_copied"), false);
        }));
        section.add(row);
        return section;
    }

    private void refreshJoin() {
        ui.async("ServerJoinInfo", () -> new String[] {host.getJoinAddresses(), host.getPublicKey()}, info ->
            joinText.setText(locale.getValue("ss.join.text",
                new String[] {info[0], info[1] != null ? info[1] : locale.getValue("ss.join.nokey")})));
    }

    // ---- Backups --------------------------------------------------------------------------------------------------

    private JPanel backupSection() {
        JPanel section = column();
        section.add(ui.subheading("ss.backups.title"));
        section.add(note(locale.getValue("ss.backups.hint", String.valueOf(ServerBackups.KEEP))));
        section.add(ui.scroll(backupTable, 110));
        JPanel row = row();
        row.add(lockedButton("b.ss_backup_create", FontAwesome.DOWNLOAD, CustomColors.ACCENT, e -> createBackup()));
        row.add(lockedButton("b.ss_backup_restore", FontAwesome.UPLOAD, CustomColors.INTERFACE_BUTTON_BACKGROUND, e -> restoreBackup()));
        row.add(lockedButton("b.ss_backup_delete", FontAwesome.TRASH, ServerUi.DANGER, e -> deleteBackup()));
        section.add(row);
        return section;
    }

    private void reloadBackups() {
        ui.async("ServerBackupsLoad", () -> {
            try {
                return host.listBackups();
            } catch (IOException e) {
                log.warning("Could not list the server backups: " + e.getMessage());
                return new ArrayList<ServerBackups.Backup>();
            }
        }, list -> {
            backups.clear();
            backups.addAll(list);
            backupModel.setRowCount(0);
            for (ServerBackups.Backup backup : backups) {
                backupModel.addRow(new Object[] {backup.name(), dateFormat.format(new Date(backup.time())), ServerUi.size(backup.bytes())});
            }
        });
    }

    private ServerBackups.Backup selectedBackup() {
        int row = backupTable.getSelectedRow();
        if (row < 0 || row >= backups.size()) {
            setStatus(locale.getValue("m.ss_select_backup"), true);
            return null;
        }
        return backups.get(row);
    }

    /** Runs a backup action that stops the server, with the buttons off meanwhile. */
    private void locked(String thread, java.util.concurrent.Callable<String> work) {
        setBusy(true);
        setStatus(locale.getValue("m.ss_working"), false);
        ui.async(thread, () -> {
            try {
                return new String[] {work.call(), null};
            } catch (Exception e) {
                log.error("Server maintenance failed", e);
                return new String[] {null, String.valueOf(e.getMessage())};
            }
        }, outcome -> {
            setBusy(false);
            if (outcome[1] != null) {
                setStatus(locale.getValue("m.ss_backup_failed", outcome[1]), true);
            } else {
                setStatus(outcome[0], false);
            }
            reloadBackups();
            onDataChanged.run();
        });
    }

    private void setBusy(boolean busy) {
        for (JComponent component : busyLocked) component.setEnabled(!busy);
    }

    private void createBackup() {
        if (host.isServerRunning() && JOptionPane.showConfirmDialog(owner, locale.getValue("m.ss_backup_stops"),
            locale.getValue("ss.backups.title"), JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.YES_OPTION) return;
        locked("ServerBackupCreate", () -> locale.getValue("m.ss_backup_done", host.backupServerData().name()));
    }

    private void restoreBackup() {
        ServerBackups.Backup backup = selectedBackup();
        if (backup == null) return;
        if (JOptionPane.showConfirmDialog(owner, locale.getValue("m.ss_restore_confirm", backup.name()),
            locale.getValue("b.ss_backup_restore"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        locked("ServerBackupRestore", () -> {
            host.restoreServerData(backup.name());
            return locale.getValue("m.ss_restore_done", backup.name());
        });
    }

    private void deleteBackup() {
        ServerBackups.Backup backup = selectedBackup();
        if (backup == null) return;
        if (JOptionPane.showConfirmDialog(owner, locale.getValue("m.ss_backup_delete_confirm", backup.name()),
            locale.getValue("b.ss_backup_delete"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        locked("ServerBackupDelete", () -> {
            host.deleteBackup(backup.name());
            return " ";
        });
    }

    // ---- Start over -----------------------------------------------------------------------------------------------

    private JPanel resetSection() {
        JPanel section = column();
        section.add(ui.subheading("ss.reset.title"));
        keepKey.setText(locale.getValue("ss.reset.keepkey"));
        keepKey.setSelected(true);
        keepKey.setOpaque(false);
        keepKey.setForeground(Color.WHITE);
        keepKey.setAlignmentX(LEFT_ALIGNMENT);
        busyLocked.add(keepKey);
        section.add(keepKey);
        JPanel row = row();
        row.add(lockedButton("b.ss_reset_server", FontAwesome.TRASH, ServerUi.DANGER, e -> {
            boolean keep = keepKey.isSelected();
            String note = locale.getValue(keep ? "m.ss_reset_note_keep" : "m.ss_reset_note_newkey");
            if (JOptionPane.showConfirmDialog(owner, locale.getValue("m.ss_reset_server_confirm", note),
                locale.getValue("t.ss_reset_server"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
            setBusy(true);
            setStatus(locale.getValue("m.ss_working"), false);
            ui.async("ServerReset", () -> host.resetServerData(keep), ok -> {
                setBusy(false);
                setStatus(locale.getValue(ok ? "m.ss_reset_server_success" : "m.ss_reset_failed"), !ok);
                reloadBackups();
                onDataChanged.run();
            });
        }));
        section.add(row);
        return section;
    }

    // ---- Folders --------------------------------------------------------------------------------------------------

    private JPanel foldersSection() {
        JPanel row = row();
        File server = ServerHostManager.serverDir();
        row.add(ui.button("b.ss_open_folder", FontAwesome.FOLDER_OPEN, CustomColors.INTERFACE_BUTTON_BACKGROUND, e -> open(new File(server, "config"))));
        row.add(ui.button("b.ss_open_data", FontAwesome.FOLDER_OPEN, CustomColors.INTERFACE_BUTTON_BACKGROUND, e -> open(new File(server, "data"))));
        row.add(ui.button("b.ss_open_logs", FontAwesome.FILE_TEXT, CustomColors.INTERFACE_BUTTON_BACKGROUND, e -> open(new File(server, "data/logs"))));
        row.add(ui.button("b.ss_open_backups", FontAwesome.FOLDER_OPEN, CustomColors.INTERFACE_BUTTON_BACKGROUND, e -> open(ServerBackups.backupsDir(server.toPath()).toFile())));
        return row;
    }

    private void open(File folder) {
        try {
            Files.createDirectories(folder.toPath());
            Desktop.getDesktop().open(folder);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            log.warning("Could not open " + folder + ": " + e.getMessage());
        }
    }

    private void setStatus(String text, boolean error) {
        status.setForeground(error ? ServerUi.ERROR_TEXT : ServerUi.OK_TEXT);
        status.setText(text == null || text.isEmpty() ? " " : text);
    }
}
