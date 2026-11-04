package com.lucasluqui.launcher.server;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.lucasluqui.launcher.server.db.KnightItemStore;
import com.lucasluqui.launcher.server.db.KnightStateStore;
import com.threerings.presents.dobj.DSet;
import com.threerings.presents.dobj.EntryAddedEvent;
import com.threerings.projectx.data.PlayerObject;
import com.threerings.projectx.item.data.Item;
import com.threerings.projectx.item.data.LevelItem;

/**
 * What the knight in play owns: crowns and items. They live in the database and are put on the player object (the game
 * shows them from it) when a knight is chosen; changes while playing are saved as they happen.
 */
final class KnightInventory {
    static final int DEFAULT_CROWNS = 999_999;

    private final SessionContext session;

    KnightInventory(SessionContext session) {
        this.session = session;
    }

    private boolean saved() {
        return session.services() != null && session.account() != null && session.player() != null;
    }

    /** Puts the crowns and items of the knight in play on the player object; a knight's first time gets the starter kit. */
    void load() {
        if (session.services() == null || session.account() == null) return;
        PlayerObject player = session.player();
        ServerServices services = session.services();
        try {
            player.crowns = services.knightState.load(player.knightId).map(state -> state.crowns).orElse(DEFAULT_CROWNS);
            List<KnightItemStore.Item> rows = services.knightItems.list(player.knightId);
            if (rows.isEmpty()) {
                List<String> configs = new ArrayList<>();
                List<Integer> slots = new ArrayList<>();
                for (ItemCatalog.Starter starter : ItemCatalog.starterKit(session.scenes())) {
                    configs.add(starter.config());
                    slots.add(starter.slot());
                }
                services.knightItems.addAll(player.knightId, configs, ItemCatalog.STARTER_LEVEL, slots);
                rows = services.knightItems.list(player.knightId);
            }
            List<Item> items = new ArrayList<>();
            long[] equipment = new long[player.equipment.length];
            long[] quickbar = new long[player.quickbar.length];
            for (KnightItemStore.Item row : rows) {
                LevelItem item = ItemCatalog.createItem(row.id, row.config, row.level, session.scenes().getConfigManager());
                if (item == null) continue; // an item the game no longer has
                items.add(item);
                if (row.equipSlot >= 0 && row.equipSlot < equipment.length) equipment[row.equipSlot] = row.id;
                if (row.quickSlot >= 0 && row.quickSlot < quickbar.length) quickbar[row.quickSlot] = row.id;
            }
            player.items = new DSet<>(items);
            player.equipment = equipment;
            player.quickbar = quickbar;
        } catch (SQLException e) {
            System.out.println("[ClientSession-" + session.connectionId() + "][ITEMS][WARNING] could not load items: " + e.getMessage());
        }
    }

    /** Saves the crowns and where each item is worn. */
    void save() {
        if (!saved()) return;
        PlayerObject player = session.player();
        try {
            session.services().knightState.save(player.knightId,
                new KnightStateStore.State(player.crowns, Collections.emptyMap(), Collections.emptyMap()));
            session.services().knightItems.saveSlots(player.knightId, player.equipment, player.quickbar);
        } catch (SQLException e) {
            System.out.println("[ClientSession-" + session.connectionId() + "][ITEMS][WARNING] could not save items: " + e.getMessage());
        }
    }

    /**
     * Gives the knight an item and tells the game.
     *
     * @return The item, or null if the knight carries too much or the game has no such item.
     */
    LevelItem grant(String config, int level) {
        if (!saved()) return null;
        PlayerObject player = session.player();
        try {
            long id = session.services().knightItems.add(player.knightId, config, level, -1);
            if (id < 0) return null;
            LevelItem item = ItemCatalog.createItem(id, config, level, session.scenes().getConfigManager());
            if (item == null) {
                session.services().knightItems.remove(player.knightId, id);
                return null;
            }
            SocialHandler.push(session, new EntryAddedEvent<>(session.clientOid(), "items", item));
            return item;
        } catch (SQLException e) {
            System.out.println("[ClientSession-" + session.connectionId() + "][ITEMS][WARNING] could not give an item: " + e.getMessage());
            return null;
        }
    }

    /** Changes the knight's crowns by this much (not below 0) and tells the game; false if they would not cover a cost. */
    boolean addCrowns(int amount) {
        PlayerObject player = session.player();
        if (player == null) return false;
        long result = (long) player.crowns + amount;
        if (result < 0) return false;
        player.crowns = (int) Math.min(Integer.MAX_VALUE, result);
        try {
            session.send(new com.threerings.presents.net.EventNotification(new com.threerings.presents.dobj.AttributeChangedEvent(
                session.clientOid(), "crowns", Integer.valueOf(player.crowns))));
        } catch (IOException ignored) {
            // The session is closing; the value is saved with the rest.
        }
        return true;
    }
}
