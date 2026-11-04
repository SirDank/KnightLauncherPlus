package com.lucasluqui.launcher.server.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** What a knight has: crowns and what is equipped or on the quickbar (item names by slot). */
public final class KnightStateStore {

    public static final class State {
        public final int crowns;
        public final Map<Integer, String> equipment;
        public final Map<Integer, String> quickbar;

        public State(int crowns, Map<Integer, String> equipment, Map<Integer, String> quickbar) {
            this.crowns = crowns;
            this.equipment = equipment;
            this.quickbar = quickbar;
        }
    }

    private final Database database;

    public KnightStateStore(Database database) {
        this.database = database;
    }

    public Optional<State> load(long knightId) throws SQLException {
        return database.read(c -> {
            try (PreparedStatement statement = c.prepareStatement(
                    "SELECT crowns, equipment, quickbar FROM knight_state WHERE knight_id = ?")) {
                statement.setLong(1, knightId);
                try (ResultSet row = statement.executeQuery()) {
                    return row.next()
                        ? Optional.of(new State(row.getInt(1), decode(row.getString(2)), decode(row.getString(3))))
                        : Optional.<State>empty();
                }
            }
        });
    }

    public void save(long knightId, State state) throws SQLException {
        database.write(c -> {
            try (PreparedStatement statement = c.prepareStatement(
                    "INSERT INTO knight_state(knight_id, crowns, equipment, quickbar) VALUES (?, ?, ?, ?)"
                    + " ON CONFLICT(knight_id) DO UPDATE SET crowns = excluded.crowns,"
                    + " equipment = excluded.equipment, quickbar = excluded.quickbar")) {
                statement.setLong(1, knightId);
                statement.setInt(2, state.crowns);
                statement.setString(3, encode(state.equipment));
                statement.setString(4, encode(state.quickbar));
                statement.executeUpdate();
            }
            return null;
        });
    }

    /** One "slot, tab, item name" line per slot. */
    static String encode(Map<Integer, String> slots) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<Integer, String> slot : slots.entrySet()) {
            text.append(slot.getKey()).append('\t').append(slot.getValue()).append('\n');
        }
        return text.toString();
    }

    static Map<Integer, String> decode(String text) {
        Map<Integer, String> slots = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            try {
                slots.put(Integer.parseInt(line.substring(0, tab)), line.substring(tab + 1));
            } catch (NumberFormatException ignored) {
                // A damaged line: that slot is empty.
            }
        }
        return slots;
    }
}
