package com.lucasluqui.launcher.server.ui;

import java.awt.BorderLayout;
import java.awt.Font;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;

import com.lucasluqui.launcher.CustomColors;
import com.lucasluqui.launcher.Fonts;
import com.lucasluqui.launcher.LocaleManager;
import com.lucasluqui.launcher.server.ServerHostManager;
import com.lucasluqui.swing.Scale;

/** Who is connected to the running server right now, as its own {@code players} command lists them. */
final class ServerPlayersPanel extends JPanel {
    private final ServerHostManager host;
    private final ServerUi ui;
    private final LocaleManager locale;
    private final DefaultTableModel model;
    private final JLabel summary = new JLabel(" ");
    private final Timer timer;
    private boolean loading = false;

    ServerPlayersPanel(ServerHostManager host, ServerUi ui) {
        super(new BorderLayout(0, Scale.px(8)));
        this.host = host;
        this.ui = ui;
        this.locale = ui.locale;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(Scale.px(12), Scale.px(12), Scale.px(12), Scale.px(12)));

        summary.setFont(Fonts.getFont("default", 12.0f, Font.BOLD));
        summary.setForeground(CustomColors.ACCENT_LIGHT);
        add(summary, BorderLayout.NORTH);

        String[] columns = {locale.getValue("ss.col.session"), locale.getValue("ss.col.account"), locale.getValue("ss.col.knight"),
            locale.getValue("ss.col.scene"), locale.getValue("ss.col.duration"), locale.getValue("ss.col.state")};
        model = new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int col) {
                return false;
            }
        };
        JTable table = new JTable(model);
        ui.styleTable(table);
        table.getColumnModel().getColumn(0).setMaxWidth(Scale.px(70));
        add(new javax.swing.JScrollPane(table), BorderLayout.CENTER);

        timer = new Timer(3000, e -> refresh());
        timer.setInitialDelay(3000);

        // Fresh data the moment the tab is shown, not on the next timer tick.
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) onShown();
        });
    }

    private void onShown() {
        refresh();
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

    private void refresh() {
        if (!isShowing() || loading) return;
        if (!host.isServerRunning()) {
            model.setRowCount(0);
            summary.setText(locale.getValue("ss.players.offline"));
            return;
        }
        loading = true;
        ui.async("ServerPlayersLoad", host::getOnlinePlayers, players -> {
            loading = false;
            show(players);
        });
    }

    private void show(List<ServerHostManager.OnlinePlayer> players) {
        model.setRowCount(0);
        for (ServerHostManager.OnlinePlayer player : players) {
            model.addRow(new Object[] {
                player.session(), player.account().isEmpty() ? "-" : player.account(),
                player.knight().isEmpty() ? "-" : player.knight(), player.scene().isEmpty() ? "-" : player.scene(),
                duration(player.seconds()), player.state()
            });
        }
        summary.setText(locale.getValue("ss.players.summary",
            new String[] {String.valueOf(host.getOpenConnections()), String.valueOf(host.getActivePlayers())}));
    }

    static String duration(int seconds) {
        if (seconds >= 3600) return (seconds / 3600) + "h " + String.format("%02d", (seconds % 3600) / 60) + "m";
        if (seconds >= 60) return (seconds / 60) + "m " + String.format("%02d", seconds % 60) + "s";
        return seconds + "s";
    }
}
