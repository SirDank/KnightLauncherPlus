package com.lucasluqui.launcher.server.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** The weapons and gear each knight owns, and where they are worn. Item ids are unique across all knights. */
public final class KnightItemStore {

    public static final class Item {
        public final long id;
        public final String config;
        public final int level;
        /** The equipment slot the item is worn in, or -1. */
        public final int equipSlot;
        /** The quickbar slot, or -1. */
        public final int quickSlot;

        Item(long id, String config, int level, int equipSlot, int quickSlot) {
            this.id = id;
            this.config = config;
            this.level = level;
            this.equipSlot = equipSlot;
            this.quickSlot = quickSlot;
        }
    }

    /** A knight cannot carry more (the shop and loot refuse past this). */
    public static final int MAX_ITEMS = 500;

    private final Database database;

    public KnightItemStore(Database database) {
        this.database = database;
    }

    public List<Item> list(long knightId) throws SQLException {
        return database.read(c -> {
            List<Item> items = new ArrayList<>();
            try (PreparedStatement select = c.prepareStatement(
                    "SELECT id, config, level, equip_slot, quick_slot FROM knight_items WHERE knight_id = ? ORDER BY id")) {
                select.setLong(1, knightId);
                try (ResultSet row = select.executeQuery()) {
                    while (row.next()) {
                        int equip = row.getInt(4);
                        if (row.wasNull()) equip = -1;
                        int quick = row.getInt(5);
                        if (row.wasNull()) quick = -1;
                        items.add(new Item(row.getLong(1), row.getString(2), row.getInt(3), equip, quick));
                    }
                }
            }
            return items;
        });
    }

    public int count(long knightId) throws SQLException {
        return database.read(c -> {
            try (PreparedStatement select = c.prepareStatement("SELECT COUNT(*) FROM knight_items WHERE knight_id = ?")) {
                select.setLong(1, knightId);
                try (ResultSet row = select.executeQuery()) {
                    row.next();
                    return row.getInt(1);
                }
            }
        });
    }

    /** Gives the knight a new item; returns its id, or -1 if they carry too much already. */
    public long add(long knightId, String config, int level, int equipSlot) throws SQLException {
        return database.write(c -> addOn(c, knightId, config, level, equipSlot));
    }

    /** Gives the knight many items at once (the starter kit); {@code slots} says where each is worn (-1: bag). */
    public void addAll(long knightId, List<String> configs, int level, List<Integer> slots) throws SQLException {
        database.write(c -> {
            for (int i = 0; i < configs.size(); i++) addOn(c, knightId, configs.get(i), level, slots.get(i));
            return null;
        });
    }

    private long addOn(java.sql.Connection c, long knightId, String config, int level, int equipSlot) throws SQLException {
        try (PreparedStatement count = c.prepareStatement("SELECT COUNT(*) FROM knight_items WHERE knight_id = ?")) {
            count.setLong(1, knightId);
            try (ResultSet row = count.executeQuery()) {
                row.next();
                if (row.getInt(1) >= MAX_ITEMS) return -1;
            }
        }
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO knight_items (knight_id, config, level, equip_slot) VALUES (?, ?, ?, ?)",
                java.sql.Statement.RETURN_GENERATED_KEYS)) {
            insert.setLong(1, knightId);
            insert.setString(2, config);
            insert.setInt(3, level);
            if (equipSlot < 0) insert.setNull(4, java.sql.Types.INTEGER); else insert.setInt(4, equipSlot);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    /** Takes an item away (sold, destroyed); true if the knight had it. */
    public boolean remove(long knightId, long itemId) throws SQLException {
        return database.write(c -> {
            try (PreparedStatement delete = c.prepareStatement("DELETE FROM knight_items WHERE knight_id = ? AND id = ?")) {
                delete.setLong(1, knightId);
                delete.setLong(2, itemId);
                return delete.executeUpdate() == 1;
            }
        });
    }

    /** Records where the knight wears what: {@code equipment[slot]} and {@code quickbar[slot]} are item ids (0: nothing). */
    public void saveSlots(long knightId, long[] equipment, long[] quickbar) throws SQLException {
        database.write(c -> {
            try (PreparedStatement clear = c.prepareStatement(
                    "UPDATE knight_items SET equip_slot = NULL, quick_slot = NULL WHERE knight_id = ?")) {
                clear.setLong(1, knightId);
                clear.executeUpdate();
            }
            try (PreparedStatement worn = c.prepareStatement("UPDATE knight_items SET equip_slot = ? WHERE knight_id = ? AND id = ?");
                 PreparedStatement quick = c.prepareStatement("UPDATE knight_items SET quick_slot = ? WHERE knight_id = ? AND id = ?")) {
                for (int slot = 0; slot < equipment.length; slot++) {
                    if (equipment[slot] == 0) continue;
                    worn.setInt(1, slot);
                    worn.setLong(2, knightId);
                    worn.setLong(3, equipment[slot]);
                    worn.executeUpdate();
                }
                for (int slot = 0; slot < quickbar.length; slot++) {
                    if (quickbar[slot] == 0) continue;
                    quick.setInt(1, slot);
                    quick.setLong(2, knightId);
                    quick.setLong(3, quickbar[slot]);
                    quick.executeUpdate();
                }
            }
            return null;
        });
    }
}
