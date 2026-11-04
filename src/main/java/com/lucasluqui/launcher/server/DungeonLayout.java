package com.lucasluqui.launcher.server;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The shape of one dungeon level: a chain of rooms joined by corridors, from the entrance room to the exit room.
 * Pure geometry (no game classes), made from a seed so the same seed gives the same level. Cells are one tile; a
 * cell {@code (x, y)} covers the world square from {@code (x, y)} to {@code (x + 1, y + 1)}.
 */
final class DungeonLayout {
    /** A rectangle of floor cells, both corners included. */
    record Room(int id, int x0, int y0, int x1, int y1) {
        float centerX() { return (x0 + x1 + 1) / 2f; }
        float centerY() { return (y0 + y1 + 1) / 2f; }
        int width() { return x1 - x0 + 1; }
        int height() { return y1 - y0 + 1; }
        boolean containsWorld(float x, float y) { return x >= x0 && x < x1 + 1 && y >= y0 && y < y1 + 1; }
    }

    /** A place on the floor where a monster may stand (world coordinates). */
    record Spot(float x, float y) {}

    static final int MIN_ROOMS = 3;
    static final int MAX_ROOMS = 7;
    static final int MIN_SIDE = 7;
    static final int MAX_SIDE = 12;
    static final int CORRIDOR_WIDTH = 3;
    static final int CORRIDOR_LENGTH = 5;
    /** Monsters stand at least this far apart (world units). */
    static final double MIN_SPOT_DISTANCE = 1.3;

    final long seed;
    final int depth;
    final List<Room> rooms = new ArrayList<>();
    /** Every floor cell, rooms and corridors, as {@link #key}. */
    final Set<Long> floor = new HashSet<>();
    /** Corridor cells only (no monsters spawn there). */
    final Set<Long> corridors = new HashSet<>();
    /** Where players appear (the middle of the first room). */
    float startX, startY;

    private DungeonLayout(long seed, int depth) {
        this.seed = seed;
        this.depth = depth;
    }

    /** Rooms on a level: more of them deeper down. */
    static int roomCount(int depth) {
        return Math.max(MIN_ROOMS, Math.min(MAX_ROOMS, MIN_ROOMS + depth / 3));
    }

    static DungeonLayout generate(long seed, int depth) {
        DungeonLayout layout = new DungeonLayout(seed, depth);
        Random random = new Random(seed);
        int count = roomCount(depth);
        int x = 0;
        int previousCenterY = 0;
        for (int id = 0; id < count; id++) {
            int width = MIN_SIDE + random.nextInt(MAX_SIDE - MIN_SIDE + 1);
            int height = MIN_SIDE + random.nextInt(MAX_SIDE - MIN_SIDE + 1);
            // Rooms are centred on the corridor line of the one before, shifted a little so the level is not a straight line.
            int centerY = id == 0 ? 0 : previousCenterY + random.nextInt(7) - 3;
            Room room = new Room(id, x, centerY - height / 2, x + width - 1, centerY - height / 2 + height - 1);
            layout.rooms.add(room);
            layout.fill(room.x0(), room.y0(), room.x1(), room.y1(), false);
            if (id > 0) {
                Room before = layout.rooms.get(id - 1);
                // The corridor runs along the row through the middle of the overlap of both rooms' rows, so it is straight.
                int low = Math.max(before.y0(), room.y0()) + 1;
                int high = Math.min(before.y1(), room.y1()) - CORRIDOR_WIDTH;
                int row = high >= low ? low + random.nextInt(high - low + 1) : Math.max(before.y0(), room.y0());
                layout.fill(before.x1() + 1, row, room.x0() - 1, row + CORRIDOR_WIDTH - 1, true);
            }
            previousCenterY = centerY;
            x += width + CORRIDOR_LENGTH;
        }
        Room first = layout.rooms.get(0);
        layout.startX = first.centerX();
        layout.startY = first.centerY();
        return layout;
    }

    private void fill(int x0, int y0, int x1, int y1, boolean corridor) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                this.floor.add(key(x, y));
                if (corridor) this.corridors.add(key(x, y));
            }
        }
    }

    Room first() { return this.rooms.get(0); }
    Room last() { return this.rooms.get(this.rooms.size() - 1); }

    /** The room that contains the world position, or null (corridor or outside). */
    Room roomAt(float x, float y) {
        for (Room room : this.rooms) if (room.containsWorld(x, y)) return room;
        return null;
    }

    boolean isFloor(int x, int y) {
        return this.floor.contains(key(x, y));
    }

    /** Wall cells: every cell next to a floor cell (diagonals too) that is not floor itself. */
    Set<Long> wallCells() {
        Set<Long> walls = new HashSet<>();
        for (long cell : this.floor) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    long neighbour = key(x(cell) + dx, y(cell) + dy);
                    if (!this.floor.contains(neighbour)) walls.add(neighbour);
                }
            }
        }
        return walls;
    }

    /**
     * Places for {@code count} monsters in a room: spread over its inner area (one cell from the walls) and not on top
     * of each other. Fewer come back if the room is too small to hold that many.
     */
    List<Spot> spots(Room room, int count, Random random) {
        List<Spot> spots = new ArrayList<>();
        int attempts = 0;
        while (spots.size() < count && attempts++ < count * 40) {
            float x = room.x0() + 1.5f + random.nextFloat() * Math.max(0.5f, room.width() - 3f);
            float y = room.y0() + 1.5f + random.nextFloat() * Math.max(0.5f, room.height() - 3f);
            boolean crowded = false;
            for (Spot other : spots) {
                if (Math.hypot(other.x() - x, other.y() - y) < MIN_SPOT_DISTANCE) { crowded = true; break; }
            }
            if (!crowded) spots.add(new Spot(x, y));
        }
        return spots;
    }

    /** The area that takes players to the next level: the last room's far end, three cells deep. */
    float[] exitBounds() {
        Room last = last();
        return new float[]{last.x1() - 2f, last.y0() + 1f, last.x1() + 1f - 0.5f, last.y1() + 1f - 1f};
    }

    static long key(int x, int y) { return ((long) x << 32) | (y & 0xffffffffL); }
    static int x(long key) { return (int) (key >> 32); }
    static int y(long key) { return (int) key; }
}
