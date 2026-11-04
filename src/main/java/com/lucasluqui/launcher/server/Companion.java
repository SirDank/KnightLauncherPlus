package com.lucasluqui.launcher.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.threerings.presents.dobj.DSet;
import com.threerings.projectx.data.PlayerObject;
import com.threerings.projectx.data.ProjectXCodes;
import com.threerings.projectx.dungeon.data.DungeonInputFrame;
import com.threerings.projectx.dungeon.data.actor.Dungeoneer;
import com.threerings.projectx.item.config.ItemConfig;
import com.threerings.projectx.item.data.Item;
import com.threerings.projectx.item.data.LevelItem;
import com.threerings.tudey.data.actor.Actor;
import com.threerings.util.Name;

/**
 * A knight played by the server, fighting beside the party. It is a knight like the players': the same actor, the same
 * combat state machine (it is driven by the same kind of input a player's game sends: where to walk, when to swing), the
 * same health and no way around the damage monsters do. Its gear is drawn at random from what exists in the game for
 * its depth: only items the game itself can create, of a rarity that fits the level, never a stat cheat.
 */
final class Companion {
    static final int OID_BASE = 3_000_000;
    private static final int MOVING = 1;
    private static final int ACTION = 4;
    private static final float FOLLOW_DISTANCE = 3.5f;
    private static final float ENGAGE_DISTANCE = 10f;
    private static final int SWING_INTERVAL_MS = 700;

    private static final String[] NAMES = {"Clanker", "Rivet", "Gizmo", "Sprocket", "Widget", "Cogsworth", "Brasso", "Piston",
        "Flange", "Bolter", "Gasket", "Ratchet"};

    final PlayerObject player;
    final Dungeoneer actor;
    final String weaponName;
    private final VanillaCombatController controller;
    private final float range;
    private int nextSwing;
    float health = Dungeon.PLAYER_HEALTH;
    boolean alive = true;

    private Companion(PlayerObject player, Dungeoneer actor, VanillaCombatController controller, float range, String weaponName) {
        this.player = player;
        this.actor = actor;
        this.controller = controller;
        this.range = range;
        this.weaponName = weaponName;
    }

    static String nameFor(int index) {
        String base = NAMES[index % NAMES.length];
        return index < NAMES.length ? base : base + " " + (char) ('A' + index / NAMES.length);
    }

    static Companion create(SceneManager scenes, int sceneId, int index, int depth, int timestamp, float x, float y, Random random) {
        InstalledConfigManager configs = scenes.getConfigManager();
        PlayerObject player = new PlayerObject();
        player.cH(OID_BASE + index);
        String name = nameFor(index);
        player.knight = new Name(name);
        player.username = new Name(name);
        player.color = SceneManager.DEFAULT_PLAYER_COLOR;
        player.eye = ProjectXCodes.EyeType.DEFAULT;
        player.height = ProjectXCodes.KnightHeight.BASE;
        player.rank = com.threerings.projectx.mission.data.MissionCodes.bAS;
        // As for the players: native item checks look at clearance progress, which unlocks gear of every rarity.
        player.progressFlags |= 64 | 128 | 256 | 512;
        player.prestige = 50000;
        player.weaponSlots = 4;
        player.trinketSlots = 2;
        player.equipment = new long[16];
        player.quickbar = new long[8];

        List<Item> items = new ArrayList<>();
        long id = 1000;
        int level = Math.min(10, 1 + depth);
        String weaponName = null;
        int[] slots = {0, 1, 2, 6, 7};
        String[] kinds = {GearCatalog.HELM, GearCatalog.ARMOR, GearCatalog.SHIELD, GearCatalog.SWORD, GearCatalog.SWORD};
        for (int i = 0; i < slots.length; i++) {
            String choice = GearCatalog.pick(configs, kinds[i], depth, random);
            if (choice == null) continue;
            LevelItem item = ItemCatalog.createItem(id++, choice, level, configs);
            if (item == null) continue;
            items.add(item);
            player.equipment[slots[i]] = item.VD();
            if (weaponName == null && slots[i] == 6) weaponName = choice;
        }
        player.items = new DSet<>(items);

        Dungeoneer actor = (Dungeoneer) scenes.createPlayerKnight(player, timestamp, sceneId, x, y);
        VanillaCombatController controller = new VanillaCombatController(scenes, player, actor, Math.max(0, timestamp - 1), sceneId);
        float range = 1.5f;
        return new Companion(player, actor, controller, range, weaponName);
    }

    /**
     * One tick of thinking: fight the nearest monster in reach, otherwise stay with the nearest knight.
     *
     * @param humans The players' actors, to follow.
     */
    void tick(int timestamp, Map<Integer, ? extends Actor> humans, CombatWorld combat) {
        if (!this.alive) return;
        Actor monster = combat.nearestMonster(this.actor.tz().x, this.actor.tz().y, ENGAGE_DISTANCE);
        float rotation = this.actor.tA();
        float direction = this.actor.adC();
        int flags = 0;
        if (monster != null) {
            double distance = Math.hypot(monster.tz().x - this.actor.tz().x, monster.tz().y - this.actor.tz().y);
            rotation = (float) Math.atan2(monster.tz().y - this.actor.tz().y, monster.tz().x - this.actor.tz().x);
            if (distance > this.range) {
                flags |= MOVING;
                direction = rotation;
            } else if (timestamp >= this.nextSwing) {
                flags |= ACTION;
                this.nextSwing = timestamp + SWING_INTERVAL_MS;
            }
        } else {
            Actor nearest = null;
            double best = Double.MAX_VALUE;
            for (Actor human : humans.values()) {
                double distance = Math.hypot(human.tz().x - this.actor.tz().x, human.tz().y - this.actor.tz().y);
                if (distance < best) {
                    best = distance;
                    nearest = human;
                }
            }
            if (nearest != null && best > FOLLOW_DISTANCE) {
                flags |= MOVING;
                rotation = direction = (float) Math.atan2(nearest.tz().y - this.actor.tz().y, nearest.tz().x - this.actor.tz().x);
            }
        }
        if (this.actor.adX() == 1) this.actor.aU(0, timestamp); // the creation animation is over
        this.controller.input(new DungeonInputFrame(timestamp, rotation, direction, flags, timestamp));
        this.controller.tick(timestamp);
    }
}
