package com.lucasluqui.launcher.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

import com.threerings.config.ConfigReference;
import com.threerings.math.Vector2f;
import com.threerings.projectx.dungeon.config.SpawnTableConfig;
import com.threerings.projectx.dungeon.data.DungeonCodes;
import com.threerings.tudey.config.ActorConfig;
import com.threerings.tudey.data.TudeySceneModel;
import com.threerings.tudey.data.actor.Actor;

/**
 * One generated dungeon level and everything that happens in it: the monsters of each room, the damage players take,
 * the crowns kills are worth, and the way on to the next level once every monster is dead. A party moves from level to
 * level, each one deeper and harder, and each one a new {@code Dungeon}.
 */
final class Dungeon implements CombatWorld.Listener {
    /** The families of monsters a room can hold (spawn tables of the game; each gives monsters scaled to the depth). */
    static final String[] FAMILIES = {
        "Monster/Single Family/Beast/Scaled - Wolver",
        "Monster/Single Family/Slime/Scaled - Rock Jelly",
        "Monster/Single Family/Slime/Scaled - Jelly Cube",
        "Monster/Single Family/Undead/Scaled - Dust Zombie",
    };
    static final int PLAYER_HEALTH = 100;
    /** Milliseconds a knocked-out knight waits before standing up at the entrance. */
    static final int RESPAWN_DELAY_MS = 3_000;
    static final int CROWNS_PER_KILL_PER_DEPTH = 6;
    /**
     * Stands in for the armor knights wear: monsters' listed damage is for armored knights, and armor is not modeled
     * yet, so every blow is cut to this share.
     */
    static final float ARMOR_FACTOR = 0.4f;

    /** How many monsters a room holds for a party of this size at this depth (rooms deeper in the level hold more). */
    static int monstersInRoom(int depth, int roomIndex, int partySize) {
        double base = 2 + depth / 2.0 + roomIndex / 2.0;
        return (int) Math.min(24, Math.max(1, Math.round(base * (1 + 0.45 * (partySize - 1)))));
    }

    /** What monsters' health is multiplied by for a party of this size. */
    static float healthFactor(int partySize) {
        return 1f + 0.35f * (partySize - 1);
    }

    /** What monsters' damage is multiplied by for a party of this size. */
    static float damageFactor(int partySize) {
        return 1f + 0.10f * (partySize - 1);
    }

    final int sceneId;
    final int depth;
    final DungeonCodes.Difficulty difficulty;
    final DungeonLayout layout;
    final CombatWorld combat;
    private final DungeonManager manager;
    private final SceneManager scenes;
    /** The players of this level, by the id of their actor. */
    private final Map<Integer, ClientSession> players = new HashMap<>();
    private final Map<Integer, Float> health = new HashMap<>();
    private final Map<Integer, Integer> respawnAt = new HashMap<>();
    /** The knights played by the server, by the id of their actor. */
    private final Map<Integer, Companion> companions = new HashMap<>();
    private int companionCount;
    private volatile int nextDepth;
    private final long createdAt = System.currentTimeMillis();
    private volatile boolean entered;
    private volatile boolean cleared;
    private volatile boolean advancing;
    private final int partySize;

    Dungeon(DungeonManager manager, SceneManager scenes, int sceneId, int placeOid, int version, long seed, int depth,
            DungeonCodes.Difficulty difficulty, int partySize, int burstIntervalMs) {
        this.manager = manager;
        this.scenes = scenes;
        this.sceneId = sceneId;
        this.depth = depth;
        this.difficulty = difficulty;
        this.partySize = partySize;
        this.layout = DungeonLayout.generate(seed, depth);
        TudeySceneModel model = DungeonSceneBuilder.build(scenes.trainingTemplate(), scenes.getConfigManager(),
            this.layout, sceneId, version);
        this.combat = new CombatWorld(scenes.getConfigManager(), model, burstIntervalMs, true);
        this.combat.setLevel(depth, damageFactor(partySize) * ARMOR_FACTOR);
        populate(seed);
        this.combat.setListener(this);
        scenes.registerCombatScene(sceneId, placeOid, model, scenes.getSceneConfig(SceneManager.TRAINING_SCENE_ID),
            this.combat, this.layout.startX, this.layout.startY);
    }

    private void populate(long seed) {
        Random random = new Random(seed ^ 0x5DEECE66DL);
        for (int index = 1; index < this.layout.rooms.size(); index++) {
            DungeonLayout.Room room = this.layout.rooms.get(index);
            String family = FAMILIES[random.nextInt(FAMILIES.length)];
            List<ConfigReference<ActorConfig>> monsters = evaluate(family, monstersInRoom(this.depth, index, this.partySize));
            List<DungeonLayout.Spot> spots = this.layout.spots(room, monsters.size(), random);
            float[] wake = {room.x0() - 0.5f, room.y0() - 0.5f, room.x1() + 1.5f, room.y1() + 1.5f};
            float health = healthFactor(this.partySize) * this.difficulty.monsterHealthScale;
            for (int i = 0; i < spots.size(); i++) {
                DungeonLayout.Spot spot = spots.get(i);
                this.combat.addMonster(monsters.get(i), new Vector2f(spot.x(), spot.y()), random.nextFloat() * 6.28f,
                    index, wake, health);
            }
        }
        System.out.println("[DUNGEON] scene=" + this.sceneId + " depth=" + this.depth + " rooms=" + this.layout.rooms.size()
            + " monsters=" + this.combat.monstersAlive(-1) + " party=" + this.partySize);
    }

    /** At least {@code count} monsters (fewer if the table gives none) from a family's spawn table, at this depth. */
    private List<ConfigReference<ActorConfig>> evaluate(String family, int count) {
        List<ConfigReference<ActorConfig>> result = new ArrayList<>();
        SpawnTableConfig table = this.scenes.getConfigManager().a(SpawnTableConfig.class, new ConfigReference<SpawnTableConfig>(family));
        if (table == null) {
            System.out.println("[DUNGEON][WARNING] missing spawn table " + family);
            return result;
        }
        Consumer<ConfigReference<ActorConfig>> collect = result::add;
        for (int attempt = 0; attempt < count * 3 && result.size() < count; attempt++) {
            SpawnTableConfig.a context = new SpawnTableConfig.a();
            context.depth = this.depth;
            context.difficulty = this.difficulty;
            context.floors = 10;
            SpawnTableConfig.c criteria = new SpawnTableConfig.c(new ArrayList<String>(), this.depth,
                com.threerings.projectx.dungeon.config.DungeonActorConfig.Monster.Size.UNKNOWN, new HashMap<String, Object>());
            table.a(this.scenes.getConfigManager(), context, criteria, null, collect);
        }
        return result.size() > count ? new ArrayList<>(result.subList(0, count)) : result;
    }

    /** Puts server-played knights at the entrance; they count as party members for the difficulty. */
    synchronized void addCompanions(int count, int timestamp) {
        Random random = new Random(this.layout.seed ^ 0xC0FFEEL);
        this.companionCount = count;
        for (int i = 0; i < count; i++) {
            float angle = (float) (i * 2 * Math.PI / Math.max(1, count));
            float x = this.layout.startX + (float) Math.cos(angle) * 1.5f;
            float y = this.layout.startY + (float) Math.sin(angle) * 1.5f;
            try {
                Companion companion = Companion.create(this.scenes, this.sceneId, this.manager.nextCompanionIndex(), this.depth,
                    timestamp, x, y, random);
                this.companions.put(companion.actor.vw(), companion);
                this.combat.addCompanion(companion.actor);
                this.scenes.movePlayerToScene(companion.player, this.sceneId);
                System.out.println("[DUNGEON] scene=" + this.sceneId + " companion " + companion.player.knight + " with " + companion.weaponName);
            } catch (RuntimeException e) {
                System.out.println("[DUNGEON][WARNING] could not make a companion: " + e);
                e.printStackTrace(System.out);
            }
        }
    }

    /** Overrides the depth of the level after this one (the /depth command). */
    void setNextDepth(int depth) {
        this.nextDepth = depth;
    }

    /** The depth of the level after this one. */
    int nextDepth() {
        return this.nextDepth > 0 ? this.nextDepth : Math.min(DungeonManager.MAX_DEPTH, this.depth + 1);
    }

    synchronized List<Companion> companions() {
        return new ArrayList<>(this.companions.values());
    }

    int companionCount() {
        return this.companionCount;
    }

    /** How many companions are still standing. */
    synchronized int companionsAlive() {
        int alive = 0;
        for (Companion companion : this.companions.values()) if (companion.alive) alive++;
        return alive;
    }

    /** Called when a player is put in this level. */
    synchronized void playerEntered(ClientSession session) {
        this.entered = true;
        int actor = SceneManager.getPlayerPawnId(session.player());
        this.players.put(actor, session);
        this.health.put(actor, (float) PLAYER_HEALTH);
        this.respawnAt.remove(actor);
        session.party().setHealth(session, PLAYER_HEALTH, PLAYER_HEALTH);
    }

    synchronized void playerLeft(ClientSession session) {
        int actor = SceneManager.getPlayerPawnId(session.player());
        this.players.remove(actor);
        this.health.remove(actor);
        this.respawnAt.remove(actor);
    }

    synchronized boolean isEmpty() {
        return this.players.isEmpty();
    }

    synchronized List<ClientSession> sessions() {
        return new ArrayList<>(this.players.values());
    }

    /** A level made for players who never arrived (they left, or the game could not load it) for this long is dropped. */
    boolean abandoned(long now, long graceMs) {
        return !this.entered && now - this.createdAt > graceMs;
    }

    boolean cleared() {
        return this.cleared;
    }

    // ---- CombatWorld.Listener

    @Override
    public void beforeStep(int timestamp, Map<Integer, ? extends Actor> humans) {
        List<Companion> acting;
        synchronized (this) {
            acting = new ArrayList<>(this.companions.values());
        }
        for (Companion companion : acting) {
            try {
                companion.tick(timestamp, humans, this.combat);
            } catch (RuntimeException e) {
                System.out.println("[DUNGEON][WARNING] companion " + companion.player.knight + " failed: " + e);
            }
        }
    }

    @Override
    public void playerHit(int actorId, float amount, int timestamp) {
        Companion companion;
        synchronized (this) {
            companion = this.companions.get(actorId);
        }
        if (companion != null) {
            companionHit(companion, amount);
            return;
        }
        ClientSession session;
        float left;
        synchronized (this) {
            session = this.players.get(actorId);
            Float current = this.health.get(actorId);
            if (session == null || current == null || current <= 0) return;
            left = Math.max(0, current - amount);
            this.health.put(actorId, left);
            if (left <= 0) this.respawnAt.put(actorId, timestamp + RESPAWN_DELAY_MS);
        }
        session.party().setHealth(session, Math.round(left), PLAYER_HEALTH);
        if (left <= 0) {
            System.out.println("[DUNGEON] scene=" + this.sceneId + " " + session.player().knight + " was knocked out");
        }
    }

    /** Companions have health like anyone and, once it is gone, they are gone for this level. */
    private void companionHit(Companion companion, float amount) {
        boolean fell;
        synchronized (this) {
            if (!companion.alive) return;
            companion.health = Math.max(0, companion.health - amount);
            fell = companion.health <= 0;
            if (fell) companion.alive = false;
        }
        if (fell) {
            this.combat.removeCompanion(companion.actor.vw());
            // Broadcasting occupants takes the sessions' locks, which must not happen while the combat is locked.
            this.manager.later(() -> {
                this.scenes.removePlayer(companion.player);
                this.manager.broadcastOccupants(this.sceneId);
                ChatHandler.notice(sessions(), "Dungeon", companion.player.knight + " has fallen.");
            });
        }
    }

    @Override
    public void monsterKilled(int actorId, int room, int timestamp) {
        int reward = CROWNS_PER_KILL_PER_DEPTH * this.depth;
        boolean last = !this.cleared && this.combat.monstersAlive(-1) == 0;
        if (last) this.cleared = true;
        // Crowns, loot and messages change the knights' objects and take their locks: done off the combat thread.
        this.manager.later(() -> {
            for (ClientSession session : sessions()) session.addCrowns(reward);
            if (last) {
                ChatHandler.notice(sessions(), "Dungeon", "Level cleared! Walk to the end of the last room to go deeper.");
                giveLoot();
            }
        });
    }

    /** Every knight finds a piece of gear for clearing the level: one the game has, of a rarity that fits the depth. */
    private void giveLoot() {
        Random random = new Random(this.layout.seed ^ 0x100DL);
        for (ClientSession session : sessions()) {
            String kind = GearCatalog.WEARABLE[random.nextInt(GearCatalog.WEARABLE.length)];
            String config = GearCatalog.pick(this.scenes.getConfigManager(), kind, this.depth, random);
            if (config == null) continue;
            if (session.inventory().grant(config, Math.min(10, this.depth + 1)) != null) {
                ChatHandler.notice(java.util.List.of(session), "Dungeon", "Loot: " + GearCatalog.shortName(config) + "!");
            }
        }
    }

    @Override
    public void stepped(int timestamp, Map<Integer, ? extends Actor> actors) {
        List<ClientSession> standUp = new ArrayList<>();
        java.util.Set<Integer> here;
        synchronized (this) {
            here = new java.util.HashSet<>(this.players.keySet());
            for (Map.Entry<Integer, Integer> entry : new ArrayList<>(this.respawnAt.entrySet())) {
                if (timestamp >= entry.getValue()) {
                    this.respawnAt.remove(entry.getKey());
                    this.health.put(entry.getKey(), (float) PLAYER_HEALTH);
                    ClientSession session = this.players.get(entry.getKey());
                    if (session != null) standUp.add(session);
                }
            }
        }
        // This runs while the combat is locked; a session's own lock must never be taken from here (a session
        // holding its own lock asks the combat for things), so the knights are moved by the worker thread.
        if (!standUp.isEmpty()) {
            this.manager.later(() -> {
                for (ClientSession session : standUp) {
                    session.teleport(this.layout.startX, this.layout.startY);
                    session.party().setHealth(session, PLAYER_HEALTH, PLAYER_HEALTH);
                }
            });
        }
        if (this.cleared && !this.advancing) {
            float[] exit = this.layout.exitBounds();
            for (Actor actor : actors.values()) {
                if (here.contains(actor.vw()) && actor.tz().x >= exit[0] && actor.tz().x <= exit[2]
                        && actor.tz().y >= exit[1] && actor.tz().y <= exit[3]) {
                    this.advancing = true;
                    this.manager.advance(this);
                    return;
                }
            }
        }
    }
}
