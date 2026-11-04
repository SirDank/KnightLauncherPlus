package com.lucasluqui.launcher.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.threerings.projectx.dungeon.data.DungeonCodes;

/**
 * Starts dungeons for parties and keeps them: each level is a scene of its own, made when a party enters it and
 * dropped when the last player has left. The scene ids come from a range the game's own scenes never use, and every
 * level gets a version newer than all before it, because the game keeps the scenes it has seen and would otherwise
 * show an old level for a new one.
 */
final class DungeonManager {
    static final int FIRST_SCENE_ID = 900_000;
    static final int ID_POOL = 4_096;
    static final int FIRST_PLACE_OID = 2_000_000;
    static final int MAX_DEPTH = 99;

    private final SceneManager scenes;
    private final MovementConfig config;
    private SceneNetworkManager network;
    private final Map<Integer, Dungeon> dungeons = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger();
    private final AtomicInteger versions = new AtomicInteger((int) (System.currentTimeMillis() / 1000L));
    private final Random seeds = new Random();
    private final java.util.concurrent.ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Dungeon-Worker");
        thread.setDaemon(true);
        return thread;
    });
    /** How long a level waits for its players before it is dropped. */
    static final long ARRIVAL_GRACE_MS = 60_000;
    /** How long a party is "on its way" after a dungeon was made for it (no second start meanwhile). */
    static final long TRAVEL_MS = 15_000;

    DungeonManager(SceneManager scenes, MovementConfig config) {
        this.scenes = scenes;
        this.config = config;
        this.worker.scheduleWithFixedDelay(this::dropAbandoned, 30, 30, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Drops levels nobody ever entered. */
    void dropAbandoned() {
        long now = System.currentTimeMillis();
        for (Dungeon dungeon : this.dungeons.values()) {
            if (dungeon.abandoned(now, ARRIVAL_GRACE_MS) && this.dungeons.remove(dungeon.sceneId, dungeon)) {
                if (this.network != null) this.network.removeScene(dungeon.sceneId);
                this.scenes.unregisterScene(dungeon.sceneId);
                System.out.println("[DUNGEON] scene=" + dungeon.sceneId + " dropped (nobody arrived)");
            }
        }
    }

    /** Runs something later on the worker thread. */
    void after(long delayMs, Runnable work) {
        this.worker.schedule(work, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** The scene ticker is made after the scene manager; it is handed over here. */
    void attach(SceneNetworkManager network) {
        this.network = network;
    }

    boolean isDungeon(int sceneId) {
        return this.dungeons.containsKey(sceneId);
    }

    Dungeon find(int sceneId) {
        return this.dungeons.get(sceneId);
    }

    /**
     * Starts a dungeon for the player's party at {@code depth}. Everyone in the party who is in town goes in.
     *
     * @return Null on success, otherwise what to tell the player.
     */
    String start(ClientSession leader, int depth, DungeonCodes.Difficulty difficulty) {
        if (this.network == null) return "Dungeons are not available here.";
        if (depth == 0) depth = leader.party().defaultDepth();
        if (depth < 1 || depth > MAX_DEPTH) return "Pick a depth from 1 to " + MAX_DEPTH + ".";
        if (isDungeon(leader.currentSceneId())) return "You are in a dungeon already; walk to its end to go deeper.";
        Party party = leader.party();
        if (party.leader() != leader) return "Only the party leader can start a dungeon.";
        if (System.currentTimeMillis() < party.travelUntil()) return "Your party is already on its way into a dungeon.";
        List<ClientSession> members = new ArrayList<>();
        for (ClientSession member : party.members()) {
            if (!isDungeon(member.currentSceneId())) members.add(member);
        }
        try {
            int companions = Math.max(0, Math.min(party.companions(), party.maxSize() - members.size()));
            party.setTravelUntil(System.currentTimeMillis() + TRAVEL_MS);
            enter(members, create(members.size(), companions, depth, difficulty));
        } catch (RuntimeException e) {
            System.out.println("[DUNGEON][ERROR] could not start: " + e);
            e.printStackTrace(System.out);
            return "The dungeon could not be made: " + e.getMessage();
        }
        return null;
    }

    private final AtomicInteger companionIndex = new AtomicInteger();

    int nextCompanionIndex() {
        return this.companionIndex.getAndIncrement();
    }

    void broadcastOccupants(int sceneId) {
        if (this.network != null) this.network.broadcastOccupants(sceneId);
    }

    /** A level for this many players and server-played knights. */
    Dungeon create(int humans, int depth, DungeonCodes.Difficulty difficulty) {
        return create(humans, 0, depth, difficulty);
    }

    Dungeon create(int humans, int companions, int depth, DungeonCodes.Difficulty difficulty) {
        int partySize = humans + companions;
        int sceneId = -1;
        for (int tries = 0; tries < ID_POOL && sceneId < 0; tries++) {
            int candidate = FIRST_SCENE_ID + Math.floorMod(this.nextId.getAndIncrement(), ID_POOL);
            if (!this.dungeons.containsKey(candidate)) sceneId = candidate;
        }
        if (sceneId < 0) throw new IllegalStateException("too many dungeons are open");
        Dungeon dungeon = new Dungeon(this, this.scenes, sceneId, FIRST_PLACE_OID + (sceneId - FIRST_SCENE_ID),
            this.versions.incrementAndGet(), this.seeds.nextLong(), depth, difficulty, partySize,
            this.config.combatBurstIntervalMs);
        this.dungeons.put(sceneId, dungeon);
        this.network.addScene(sceneId, this.config.trainingDeltaIntervalMs);
        if (companions > 0) dungeon.addCompanions(companions, this.network.currentTimestamp(sceneId));
        return dungeon;
    }

    private void enter(List<ClientSession> members, Dungeon dungeon) {
        for (ClientSession member : members) {
            try {
                member.travelTo(dungeon.sceneId);
            } catch (IOException e) {
                System.out.println("[DUNGEON][WARNING] could not send " + member.player().knight + " in: " + e);
            }
        }
    }

    /** The party reached the end of a cleared level: the next one is made and everyone still here goes in. */
    void advance(Dungeon from) {
        this.worker.execute(() -> {
            try {
                List<ClientSession> members = from.sessions();
                if (members.isEmpty()) return;
                Dungeon next = create(members.size(), from.companionCount(), from.nextDepth(), from.difficulty);
                ChatHandler.notice(members, "Dungeon", "Going down to depth " + next.depth + "...");
                enter(members, next);
            } catch (RuntimeException e) {
                System.out.println("[DUNGEON][ERROR] could not make the next level: " + e);
                e.printStackTrace(System.out);
            }
        });
    }

    /** Runs something on the worker thread (never while the combat is locked). */
    void later(Runnable work) {
        this.worker.execute(work);
    }

    /** A player is now in this scene. */
    void playerEntered(int sceneId, ClientSession session) {
        Dungeon dungeon = this.dungeons.get(sceneId);
        if (dungeon != null) dungeon.playerEntered(session);
    }

    /** A player left this scene; a dungeon nobody is in any more is dropped. */
    void playerLeft(int sceneId, ClientSession session) {
        Dungeon dungeon = this.dungeons.get(sceneId);
        if (dungeon == null) return;
        dungeon.playerLeft(session);
        if (dungeon.isEmpty()) {
            this.dungeons.remove(sceneId, dungeon);
            this.network.removeScene(sceneId);
            this.scenes.unregisterScene(sceneId);
            System.out.println("[DUNGEON] scene=" + sceneId + " dropped (empty)");
        }
    }

    /** Kills every monster of every open dungeon; returns how many. */
    int clearAll(SceneNetworkManager network) {
        int killed = 0;
        for (Dungeon dungeon : this.dungeons.values()) {
            killed += dungeon.combat.killMonsters(network.currentTimestamp(dungeon.sceneId));
        }
        return killed;
    }

    int open() {
        return this.dungeons.size();
    }

    void close() {
        this.worker.shutdownNow();
    }
}
