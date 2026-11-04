package com.lucasluqui.launcher.server;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DungeonLayoutTest {
    @Test
    public void theSameSeedMakesTheSameLevelAndOtherSeedsDiffer() {
        assertEquals(DungeonLayout.generate(7, 3).floor, DungeonLayout.generate(7, 3).floor);
        assertTrue(!DungeonLayout.generate(7, 3).floor.equals(DungeonLayout.generate(8, 3).floor));
    }

    @Test
    public void deeperLevelsHaveMoreRoomsWithinTheLimits() {
        assertEquals(DungeonLayout.MIN_ROOMS, DungeonLayout.roomCount(1));
        assertTrue(DungeonLayout.roomCount(9) > DungeonLayout.roomCount(1));
        assertEquals(DungeonLayout.MAX_ROOMS, DungeonLayout.roomCount(500));
        assertEquals(DungeonLayout.roomCount(9), DungeonLayout.generate(1, 9).rooms.size());
    }

    @Test
    public void everyFloorCellCanBeReachedFromTheStartWithoutLeavingTheFloor() {
        for (long seed = 0; seed < 200; seed++) {
            for (int depth : new int[]{1, 6, 15}) {
                DungeonLayout level = DungeonLayout.generate(seed, depth);
                Set<Long> seen = new HashSet<>();
                ArrayDeque<Long> queue = new ArrayDeque<>();
                long start = DungeonLayout.key((int) Math.floor(level.startX), (int) Math.floor(level.startY));
                assertTrue(level.floor.contains(start), "the start is on the floor, seed " + seed);
                queue.add(start);
                seen.add(start);
                while (!queue.isEmpty()) {
                    long cell = queue.poll();
                    for (int[] step : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        long next = DungeonLayout.key(DungeonLayout.x(cell) + step[0], DungeonLayout.y(cell) + step[1]);
                        if (level.floor.contains(next) && seen.add(next)) queue.add(next);
                    }
                }
                assertEquals(level.floor.size(), seen.size(), "seed " + seed + " depth " + depth + " is connected");
            }
        }
    }

    @Test
    public void wallsSurroundTheFloorAndNeverTouchIt() {
        DungeonLayout level = DungeonLayout.generate(3, 4);
        Set<Long> walls = level.wallCells();
        assertTrue(!walls.isEmpty());
        for (long wall : walls) assertTrue(!level.floor.contains(wall));
        // Every floor cell's neighbours are floor or wall.
        for (long cell : level.floor) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    long next = DungeonLayout.key(DungeonLayout.x(cell) + dx, DungeonLayout.y(cell) + dy);
                    assertTrue(level.floor.contains(next) || walls.contains(next));
                }
            }
        }
    }

    @Test
    public void monsterSpotsStayInsideTheRoomApart() {
        DungeonLayout level = DungeonLayout.generate(5, 2);
        DungeonLayout.Room room = level.rooms.get(1);
        var spots = level.spots(room, 6, new Random(1));
        assertTrue(spots.size() >= 4);
        for (var spot : spots) {
            assertTrue(room.containsWorld(spot.x(), spot.y()));
            assertTrue(level.isFloor((int) Math.floor(spot.x()), (int) Math.floor(spot.y())));
        }
        for (int a = 0; a < spots.size(); a++) {
            for (int b = a + 1; b < spots.size(); b++) {
                assertTrue(Math.hypot(spots.get(a).x() - spots.get(b).x(), spots.get(a).y() - spots.get(b).y()) >= DungeonLayout.MIN_SPOT_DISTANCE);
            }
        }
    }

    @Test
    public void theExitIsInsideTheLastRoom() {
        DungeonLayout level = DungeonLayout.generate(9, 5);
        float[] exit = level.exitBounds();
        assertTrue(level.last().containsWorld(exit[0], exit[1]));
        assertTrue(level.last().containsWorld(exit[2], exit[3]));
    }
}
