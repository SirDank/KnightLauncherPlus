package com.lucasluqui.launcher.server;

import com.threerings.math.Transform2D;
import com.threerings.math.Vector2f;
import com.threerings.projectx.data.PlayerObject;
import com.threerings.projectx.dungeon.data.DungeonCodes;
import com.threerings.projectx.dungeon.data.actor.Dungeoneer;
import com.threerings.tudey.data.actor.Actor;
import com.threerings.tudey.shape.config.ShapeConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The dungeon itself, without a network: the level, its monsters, and what they do to a knight. */
public class DungeonTest {
    private static SceneManager scenes;
    private static MovementConfig movement;
    private static int sceneCounter;

    @BeforeAll
    public static void load() throws Exception {
        SpiralServerTest.assumeGameData();
        movement = MovementConfig.load();
        scenes = new SceneManager(new DObjectManager(), movement);
        scenes.dungeons().attach(new SceneNetworkManager(20, 20, scenes::getCombatWorld));
    }

    private static PlayerObject newPlayer() throws Exception {
        ClientSession session = new ClientSession(SocketChannel.open(), new DObjectManager(), scenes, null, movement,
            ++sceneCounter, "test", 1, s -> {});
        for (String name : new String[]{"initServerObject", "initAdminObject", "initPlayer"}) {
            Method init = ClientSession.class.getDeclaredMethod(name);
            init.setAccessible(true);
            init.invoke(session);
        }
        java.lang.reflect.Field player = ClientSession.class.getDeclaredField("playerObject");
        player.setAccessible(true);
        return (PlayerObject) player.get(session);
    }

    @Test
    public void aLevelIsAPlayableSceneWithTheEntranceFreeAndWallsAround() throws Exception {
        Dungeon dungeon = scenes.dungeons().create(1, 1, DungeonCodes.Difficulty.MEDIUM);
        try {
            assertNotNull(scenes.getSceneModel(dungeon.sceneId));
            assertTrue(scenes.isGenerated(dungeon.sceneId));
            assertTrue(scenes.isCombatScene(dungeon.sceneId));
            assertTrue(dungeon.sceneId >= DungeonManager.FIRST_SCENE_ID);
            // The map survives the trip to the game: export, import.
            byte[] bytes = scenes.getSceneModel(dungeon.sceneId).adN();
            assertTrue(bytes.length > 1000);

            ShapeConfig.Circle circle = new ShapeConfig.Circle();
            circle.radius = 0.4f;
            SceneCollision collision = scenes.getCollision(dungeon.sceneId);
            assertFalse(collision.intersects(1, circle.acS().d(new Transform2D(new Vector2f(dungeon.layout.startX, dungeon.layout.startY), 0f))),
                "the entrance is free");
            assertTrue(collision.intersects(1, circle.acS().d(new Transform2D(new Vector2f(dungeon.layout.first().x0() - 0.5f, dungeon.layout.startY), 0f))),
                "the wall outside the entrance room blocks");
            assertTrue(dungeon.combat.monstersAlive(-1) > 0);
            assertEquals(0, dungeon.combat.monstersAlive(0), "the entrance room is empty");
        } finally {
            scenes.unregisterScene(dungeon.sceneId);
        }
    }

    @Test
    public void biggerPartiesFaceMoreAndTougherMonsters() throws Exception {
        assertTrue(Dungeon.monstersInRoom(3, 2, 8) > Dungeon.monstersInRoom(3, 2, 4));
        assertTrue(Dungeon.monstersInRoom(3, 2, 4) > Dungeon.monstersInRoom(3, 2, 1));
        assertTrue(Dungeon.monstersInRoom(10, 2, 1) > Dungeon.monstersInRoom(1, 2, 1));
        assertTrue(Dungeon.healthFactor(8) > Dungeon.healthFactor(2));
        assertTrue(Dungeon.damageFactor(8) > Dungeon.damageFactor(1));
        assertEquals(1f, Dungeon.healthFactor(1));

        Dungeon solo = scenes.dungeons().create(1, 2, DungeonCodes.Difficulty.MEDIUM);
        Dungeon eight = scenes.dungeons().create(8, 2, DungeonCodes.Difficulty.MEDIUM);
        try {
            assertTrue(eight.combat.monstersAlive(-1) > solo.combat.monstersAlive(-1) * 3 / 2,
                "eight knights meet clearly more monsters than one: " + eight.combat.monstersAlive(-1) + " vs " + solo.combat.monstersAlive(-1));
        } finally {
            scenes.unregisterScene(solo.sceneId);
            scenes.unregisterScene(eight.sceneId);
        }
    }

    @Test
    public void monstersWakeChaseAndHurtAKnightWhoEntersTheirRoom() throws Exception {
        Dungeon dungeon = scenes.dungeons().create(1, 1, DungeonCodes.Difficulty.MEDIUM);
        try {
            PlayerObject player = newPlayer();
            DungeonLayout.Room room = dungeon.layout.rooms.get(1);
            Dungeoneer knight = (Dungeoneer) scenes.createPlayerKnight(player, 1, dungeon.sceneId, room.centerX(), room.centerY());
            List<Float> hits = new ArrayList<>();
            dungeon.combat.setListener(new CombatWorld.Listener() {
                @Override public void playerHit(int actorId, float amount, int timestamp) {
                    assertEquals(knight.vw(), actorId);
                    hits.add(amount);
                }
            });
            Map<Integer, Actor> players = new LinkedHashMap<>();
            players.put(knight.vw(), knight);
            Vector2f firstSeen = null;
            for (int timestamp = 20; timestamp <= 20 * 600 && hits.isEmpty(); timestamp += 20) {
                Map<Integer, Actor> actors = dungeon.combat.step(timestamp, players);
                if (firstSeen == null && !actors.isEmpty()) firstSeen = new Vector2f(actors.values().iterator().next().tz());
            }
            assertNotNull(firstSeen, "monsters appeared");
            assertFalse(hits.isEmpty(), "within twelve seconds a monster should have reached the knight and hit them");
            assertTrue(hits.get(0) > 0 && Float.isFinite(hits.get(0)));
        } finally {
            scenes.unregisterScene(dungeon.sceneId);
        }
    }

    @Test
    public void monstersInAnotherRoomStayAsleepUntilTheKnightComes() throws Exception {
        Dungeon dungeon = scenes.dungeons().create(1, 1, DungeonCodes.Difficulty.MEDIUM);
        try {
            PlayerObject player = newPlayer();
            Dungeoneer knight = (Dungeoneer) scenes.createPlayerKnight(player, 1, dungeon.sceneId,
                dungeon.layout.startX, dungeon.layout.startY);
            List<Float> hits = new ArrayList<>();
            dungeon.combat.setListener(new CombatWorld.Listener() {
                @Override public void playerHit(int actorId, float amount, int timestamp) { hits.add(amount); }
            });
            Map<Integer, Actor> players = Map.of(knight.vw(), knight);
            for (int timestamp = 20; timestamp <= 20 * 400; timestamp += 20) dungeon.combat.step(timestamp, new LinkedHashMap<>(players));
            assertTrue(hits.isEmpty(), "the entrance room has no monsters and the others are behind walls");
        } finally {
            scenes.unregisterScene(dungeon.sceneId);
        }
    }

    @Test
    public void killingEveryMonsterClearsTheLevelAndPaysCrowns() throws Exception {
        Dungeon dungeon = scenes.dungeons().create(1, 2, DungeonCodes.Difficulty.EASY);
        try {
            dungeon.combat.snapshot(1000); // monsters appear
            int alive = dungeon.combat.monstersAlive(-1);
            assertTrue(alive > 0);
            assertFalse(dungeon.cleared());
            assertEquals(alive, dungeon.combat.killMonsters(1000));
            assertEquals(0, dungeon.combat.monstersAlive(-1));
            assertTrue(dungeon.cleared());
        } finally {
            scenes.unregisterScene(dungeon.sceneId);
        }
    }

    @Test
    public void everyLevelGetsItsOwnSceneAndNewerVersion() throws Exception {
        Dungeon one = scenes.dungeons().create(1, 1, DungeonCodes.Difficulty.MEDIUM);
        Dungeon two = scenes.dungeons().create(1, 1, DungeonCodes.Difficulty.MEDIUM);
        try {
            assertTrue(one.sceneId != two.sceneId);
            assertTrue(scenes.getSceneModel(two.sceneId).version > scenes.getSceneModel(one.sceneId).version);
            assertTrue(scenes.getPlaceOid(one.sceneId) != scenes.getPlaceOid(two.sceneId));
        } finally {
            scenes.unregisterScene(one.sceneId);
            scenes.unregisterScene(two.sceneId);
        }
    }

    @Test
    public void aKnightWalksTheLevelAndIsStoppedByItsWallsNotTheTrainingHalls() throws Exception {
        Dungeon dungeon = scenes.dungeons().create(1, 1, DungeonCodes.Difficulty.MEDIUM);
        try {
            PlayerObject player = newPlayer();
            Dungeoneer pawn = (Dungeoneer) scenes.createPlayerKnight(player, 1, dungeon.sceneId,
                dungeon.layout.startX, dungeon.layout.startY);
            VanillaCombatController controller = new VanillaCombatController(scenes, player, pawn, 0, dungeon.sceneId);
            float startX = pawn.tz().x;
            int timestamp = 0;
            for (int frame = 0; frame < 250; frame++) { // five seconds west, far more than the room is wide
                timestamp += 20;
                if (pawn.adX() == 1) pawn.aU(0, timestamp);
                controller.input(new com.threerings.projectx.dungeon.data.DungeonInputFrame(timestamp, (float) Math.PI, (float) Math.PI, 1, timestamp));
                controller.tick(timestamp);
            }
            assertTrue(pawn.tz().x < startX - 1.5f, "the knight walked west: " + startX + " -> " + pawn.tz().x);
            assertTrue(pawn.tz().x > dungeon.layout.first().x0() - 0.5f, "but the wall stopped them at " + pawn.tz().x);
        } finally {
            scenes.unregisterScene(dungeon.sceneId);
        }
    }

    @Test
    public void companionsAreKnightsWithRealGearThatFitTheDepthAndCountForTheDifficulty() throws Exception {
        Dungeon alone = scenes.dungeons().create(1, 0, 4, DungeonCodes.Difficulty.MEDIUM);
        Dungeon withFriends = scenes.dungeons().create(1, 3, 4, DungeonCodes.Difficulty.MEDIUM);
        try {
            assertEquals(3, withFriends.companionsAlive());
            assertEquals(3, withFriends.companions().size());
            for (Companion companion : withFriends.companions()) {
                assertNotNull(companion.weaponName, "every companion carries a weapon");
                assertTrue(scenes.getWeaponCatalog().contains(companion.weaponName), companion.weaponName + " is a real weapon of the game");
                com.threerings.projectx.item.config.ItemConfig weapon = scenes.getConfigManager()
                    .d(com.threerings.projectx.item.config.ItemConfig.class, companion.weaponName);
                assertTrue(weapon.creatable);
                assertTrue(GearCatalog.fitsDepth(weapon.w(scenes.getConfigManager()).rarity, 4), "a weapon that fits depth 4");
                assertTrue(scenes.getEquippedWeapon(companion.player, 0) != null);
            }
            // They are in the scene as occupants, like any knight.
            assertEquals(3, scenes.getSceneObjectByPlaceOid(scenes.getPlaceOid(withFriends.sceneId)).occupants.size());
            assertTrue(withFriends.combat.monstersAlive(-1) > alone.combat.monstersAlive(-1), "companions make the level harder");
        } finally {
            scenes.unregisterScene(alone.sceneId);
            scenes.unregisterScene(withFriends.sceneId);
        }
    }

    @Test
    public void companionsFightTheMonstersAndCanFall() throws Exception {
        Dungeon dungeon = scenes.dungeons().create(1, 2, 1, DungeonCodes.Difficulty.MEDIUM);
        try {
            dungeon.combat.snapshot(1000);
            float before = dungeon.combat.monsterHealth();
            DungeonLayout.Room room = dungeon.layout.rooms.get(1);
            int occupantsBefore = scenes.getSceneObjectByPlaceOid(scenes.getPlaceOid(dungeon.sceneId)).occupants.size();
            for (Companion companion : dungeon.companions()) companion.actor.tz().s(room.centerX(), room.centerY());
            for (int timestamp = 1020; timestamp <= 1000 + 20 * 2500; timestamp += 20) {
                dungeon.combat.step(timestamp, new LinkedHashMap<>());
            }
            assertTrue(dungeon.combat.monsterHealth() < before, "the companions hurt the monsters: " + before + " -> " + dungeon.combat.monsterHealth());
            // Their health is finite: enough damage and they are gone.
            for (Companion companion : dungeon.companions()) dungeon.playerHit(companion.actor.vw(), 10_000f, 99_999);
            for (Companion companion : dungeon.companions()) assertFalse(companion.alive);
            // They leave the scene's occupants a moment later, on the dungeon's worker thread.
            for (int i = 0; i < 50 && scenes.getSceneObjectByPlaceOid(scenes.getPlaceOid(dungeon.sceneId)).occupants.size() >= occupantsBefore; i++) Thread.sleep(50);
            assertTrue(scenes.getSceneObjectByPlaceOid(scenes.getPlaceOid(dungeon.sceneId)).occupants.size() < occupantsBefore, "the fallen are no longer in the scene");
        } finally {
            scenes.unregisterScene(dungeon.sceneId);
        }
    }

    @Test
    public void aLevelNobodyEntersIsDroppedAfterTheGracePeriod() throws Exception {
        Dungeon dungeon = scenes.dungeons().create(1, 1, DungeonCodes.Difficulty.MEDIUM);
        try {
            long now = System.currentTimeMillis();
            assertFalse(dungeon.abandoned(now, DungeonManager.ARRIVAL_GRACE_MS), "just made: players are still on their way");
            assertTrue(dungeon.abandoned(now + DungeonManager.ARRIVAL_GRACE_MS + 1_000, DungeonManager.ARRIVAL_GRACE_MS));
        } finally {
            scenes.unregisterScene(dungeon.sceneId);
        }
    }
}
