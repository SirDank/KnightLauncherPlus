package com.lucasluqui.launcher.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import com.threerings.projectx.data.actor.Knight;
import com.threerings.tudey.data.actor.Actor;

/**
 * Fixed-rate authoritative scene clocks and participant broadcaster.
 *
 * Input frequency never drives output frequency: each scene advances in small,
 * shared steps and every connected client receives the same actor snapshot.
 */
public final class SceneNetworkManager implements AutoCloseable {
    private final int schedulerIntervalMs;
    private final Map<Integer, SceneChannel> scenes = new java.util.concurrent.ConcurrentHashMap<>();
    private final ScheduledExecutorService ticker;
    private volatile boolean running;
    /** The fighting of a scene (null for a scene without combat). */
    private final java.util.function.IntFunction<CombatWorld> combatOf;
    private final java.util.Set<String> reportedErrors = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public SceneNetworkManager(int havenIntervalMs, int trainingIntervalMs) {
        this(havenIntervalMs, trainingIntervalMs, (CombatWorld) null);
    }

    public SceneNetworkManager(int havenIntervalMs, int trainingIntervalMs, CombatWorld combatWorld) {
        this(havenIntervalMs, trainingIntervalMs,
            sceneId -> sceneId == SceneManager.TRAINING_SCENE_ID ? combatWorld : null);
    }

    public SceneNetworkManager(int havenIntervalMs, int trainingIntervalMs,
                               java.util.function.IntFunction<CombatWorld> combatOf) {
        this.combatOf = combatOf;
        this.schedulerIntervalMs = havenIntervalMs;
        this.scenes.put(SceneManager.DEFAULT_SCENE_ID,
            new SceneChannel(SceneManager.DEFAULT_SCENE_ID, havenIntervalMs));
        this.scenes.put(SceneManager.TRAINING_SCENE_ID,
            new SceneChannel(SceneManager.TRAINING_SCENE_ID, trainingIntervalMs));
        ThreadFactory threads = task -> {
            Thread thread = new Thread(task, "SceneNetwork-Ticker");
            thread.setDaemon(true);
            thread.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 1));
            return thread;
        };
        this.ticker = Executors.newSingleThreadScheduledExecutor(threads);
    }

    public void start() {
        if (this.running) {
            return;
        }
        this.running = true;
        // The scene timestamp is game time in milliseconds. The scheduler runs
        // at the configured input cadence, while each channel decides when to
        // emit. The local launcher matches its input interval to this cadence.
        this.ticker.scheduleAtFixedRate(
            this::tickSafely, this.schedulerIntervalMs, this.schedulerIntervalMs,
            TimeUnit.MILLISECONDS);
        System.out.println("[NETWORK] authoritative scene ticker started baseIntervalMs="
            + this.schedulerIntervalMs
            + " havenIntervalMs=" + intervalMs(SceneManager.DEFAULT_SCENE_ID)
            + " trainingIntervalMs=" + intervalMs(SceneManager.TRAINING_SCENE_ID));
    }

    /** Starts ticking a scene made while the server runs. */
    public void addScene(int sceneId, int intervalMs) {
        this.scenes.putIfAbsent(sceneId, new SceneChannel(sceneId, intervalMs));
    }

    /** Stops ticking a scene; its players must have left. */
    public void removeScene(int sceneId) {
        this.scenes.remove(sceneId);
    }

    public int currentTimestamp(int sceneId) {
        SceneChannel scene = requireScene(sceneId);
        synchronized (scene) {
            return scene.timestamp;
        }
    }

    public int intervalMs(int sceneId) {
        return requireScene(sceneId).tickIntervalMs;
    }

    public void join(int sceneId, ClientSession session) {
        SceneChannel scene = requireScene(sceneId);
        synchronized (scene) {
            boolean wasEmpty = scene.participants.isEmpty();
            if (!scene.participants.contains(session)) {
                scene.participants.add(session);
            }
            if (wasEmpty) {
                scene.pendingElapsedMs = 0;
                scene.resetMetrics(System.nanoTime());
            }
            System.out.println("[NETWORK] join scene=" + sceneId
                + " clientOid=" + session.getClientOid()
                + " players=" + scene.participants.size()
                + " timestamp=" + scene.timestamp);
        }
    }

    public void leave(int sceneId, ClientSession session) {
        SceneChannel scene = this.scenes.get(sceneId);
        if (scene == null) {
            return;
        }
        synchronized (scene) {
            if (scene.participants.remove(session)) {
                System.out.println("[NETWORK] leave scene=" + sceneId
                    + " clientOid=" + session.getClientOid()
                    + " players=" + scene.participants.size());
            }
        }
    }

    public Map<Integer, Actor> snapshotActors(int sceneId) {
        SceneChannel scene = requireScene(sceneId);
        List<ClientSession> participants;
        int timestamp;
        synchronized (scene) {
            participants = new ArrayList<>(scene.participants);
            timestamp = scene.timestamp;
        }
        Map<Integer,Actor> actors = snapshotActors(sceneId, timestamp, participants);
        CombatWorld combat = this.combatOf.apply(sceneId);
        if (combat != null) {
            actors.putAll(combat.snapshot(timestamp));
        }
        return actors;
    }

    public int participantCount(int sceneId) {
        SceneChannel scene = requireScene(sceneId);
        synchronized (scene) {
            return scene.participants.size();
        }
    }

    public void broadcastOccupants(int sceneId) {
        SceneChannel scene = this.scenes.get(sceneId);
        if (scene == null) {
            return;
        }
        List<ClientSession> participants;
        synchronized (scene) {
            participants = new ArrayList<>(scene.participants);
        }
        for (ClientSession session : participants) {
            try {
                session.publishSceneOccupants(sceneId);
            } catch (IOException error) {
                System.out.println("[NETWORK][WARNING] failed occupant broadcast"
                    + " scene=" + sceneId + " clientOid=" + session.getClientOid()
                    + " error=" + error.getMessage());
                session.close();
            }
        }
    }

    private void tickSafely() {
        if (!this.running) {
            return;
        }
        for (SceneChannel scene : this.scenes.values()) {
            try {
                tick(scene);
            } catch (Throwable error) {
                // A failure in one scene must not skip the others. Full stack trace the first time an error shows up.
                boolean first = this.reportedErrors.add(scene.sceneId + ":" + error);
                System.out.println("[NETWORK][ERROR] scene " + scene.sceneId + " tick failed: " + error
                    + (first ? "" : " (repeated)"));
                if (first && this.reportedErrors.size() <= 50) {
                    error.printStackTrace(System.out);
                }
            }
        }
    }

    private void tick(SceneChannel scene) {
        List<ClientSession> participants;
        int timestamp;
        String metrics = null;
        synchronized (scene) {
            if (scene.participants.isEmpty()) {
                scene.pendingElapsedMs = 0;
                return;
            }
            scene.pendingElapsedMs += this.schedulerIntervalMs;
            if (scene.pendingElapsedMs < scene.tickIntervalMs) {
                return;
            }
            scene.pendingElapsedMs -= scene.tickIntervalMs;
            long now = System.nanoTime();
            if (scene.lastTickNanos != 0L) {
                scene.maxTickGapNanos = Math.max(
                    scene.maxTickGapNanos, now - scene.lastTickNanos);
            }
            scene.lastTickNanos = now;
            scene.timestamp += scene.tickIntervalMs;
            timestamp = scene.timestamp;
            participants = new ArrayList<>(scene.participants);
            scene.metricsTicks++;
            if (scene.metricsTicks >= 100) {
                long wallNanos = Math.max(1L, now - scene.metricsStartedNanos);
                double rateHz = scene.metricsTicks * 1_000_000_000.0 / wallNanos;
                long wallMs = TimeUnit.NANOSECONDS.toMillis(wallNanos);
                long maxGapMs = TimeUnit.NANOSECONDS.toMillis(scene.maxTickGapNanos);
                metrics = String.format(java.util.Locale.ROOT,
                    "[NETWORK][TICK] scene=%d players=%d timestamp=%d rateHz=%.2f"
                        + " wallWindowMs=%d maxGapMs=%d",
                    scene.sceneId, participants.size(), timestamp, rateHz,
                    wallMs, maxGapMs);
                scene.resetMetrics(now);
            }
        }

        if (metrics != null) {
            System.out.println(metrics);
        }

        Map<Integer, Actor> actors = new LinkedHashMap<>();
        Map<ClientSession, Integer> inputTimestamps = new LinkedHashMap<>();
        for (ClientSession session : participants) {
            ClientSession.TickSnapshot snapshot = session.snapshotForTick(scene.sceneId, timestamp);
            if (snapshot != null) {
                actors.put(snapshot.actor.vw(), snapshot.actor);
                inputTimestamps.put(session, snapshot.inputTimestamp);
            }
        }
        CombatWorld combat = this.combatOf.apply(scene.sceneId);
        if (combat != null) {
            actors.putAll(combat.step(timestamp,actors));
        }
        for (ClientSession session : participants) {
            try {
                session.publishSceneTick(scene.sceneId, timestamp, actors,
                    inputTimestamps.getOrDefault(session, Integer.MIN_VALUE));
            } catch (Exception error) {
                System.out.println("[NETWORK][WARNING] failed scene delta"
                    + " scene=" + scene.sceneId
                    + " clientOid=" + session.getClientOid()
                    + " error=" + error.getMessage());
                session.close();
            }
        }
    }

    private static Map<Integer, Actor> snapshotActors(
            int sceneId, int timestamp, List<ClientSession> participants) {
        Map<Integer, Actor> actors = new LinkedHashMap<>();
        for (ClientSession session : participants) {
            Knight actor = session.snapshotActorForTick(sceneId, timestamp);
            if (actor != null) {
                actors.put(actor.vw(), actor);
            }
        }
        return actors;
    }

    private SceneChannel requireScene(int sceneId) {
        SceneChannel scene = this.scenes.get(sceneId);
        if (scene == null) {
            throw new IllegalArgumentException("Unknown network sceneId=" + sceneId);
        }
        return scene;
    }

    @Override
    public void close() {
        this.running = false;
        this.ticker.shutdownNow();
        System.out.println("[NETWORK] authoritative scene ticker stopped");
    }

    private static final class SceneChannel {
        final int sceneId;
        final int tickIntervalMs;
        final List<ClientSession> participants = new ArrayList<>();
        int timestamp = 1;
        int pendingElapsedMs;
        long lastTickNanos;
        long metricsStartedNanos;
        long maxTickGapNanos;
        int metricsTicks;

        SceneChannel(int sceneId, int tickIntervalMs) {
            this.sceneId = sceneId;
            this.tickIntervalMs = tickIntervalMs;
        }

        void resetMetrics(long now) {
            this.lastTickNanos = now;
            this.metricsStartedNanos = now;
            this.maxTickGapNanos = 0L;
            this.metricsTicks = 0;
        }
    }
}
