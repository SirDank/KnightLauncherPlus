package com.lucasluqui.launcher.server;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import com.google.common.collect.Sets;
import com.google.common.collect.Maps;
import com.threerings.config.ConfigReference;
import com.threerings.crowd.chat.data.ChatMarshaller;
import com.threerings.crowd.data.LocationMarshaller;
import com.threerings.crowd.data.PlaceConfig;
import com.threerings.io.r;
import com.threerings.io.s;
import com.threerings.io.w;
import com.threerings.opengl.model.config.ModelConfig;
import com.threerings.opengl.renderer.config.ColorizationConfig;
import com.threerings.presents.data.InvocationMarshaller;
import com.threerings.presents.client.InvocationReceiver;
import com.threerings.presents.dobj.*;
import com.threerings.presents.net.*;
import com.threerings.projectx.admin.data.AdminObject;
import com.threerings.projectx.data.*;
import com.threerings.projectx.dungeon.data.GateSummary;
import com.threerings.projectx.dungeon.data.DungeonInputFrame;
import com.threerings.projectx.dungeon.data.Health;
import com.threerings.projectx.dungeon.data.PartyMarshaller;
import com.threerings.projectx.dungeon.data.PartyMember;
import com.threerings.projectx.dungeon.data.PartyObject;
import com.threerings.projectx.dungeon.data.RotatingGateSummary;
import com.threerings.projectx.dungeon.data.Status;
import com.threerings.projectx.dungeon.data.Theme;
import com.threerings.projectx.dungeon.data.actor.Dungeoneer;
import com.threerings.projectx.item.data.LevelItem;
import com.threerings.projectx.mission.data.RankPair;
import com.threerings.projectx.town.data.TownSummary;
import com.threerings.projectx.town.data.TownSceneObject;
import com.threerings.projectx.data.ProjectXSceneObject;
import com.threerings.stats.data.StatSet;
import com.threerings.tudey.data.TudeySceneModel;
import com.threerings.tudey.data.InputEnqueueEvent;
import com.threerings.tudey.data.InputFrame;
import com.threerings.tudey.data.actor.Actor;
import com.threerings.tudey.data.actor.Active;
import com.threerings.projectx.data.actor.Knight;
import com.threerings.tudey.dobj.ActorDelta;
import com.threerings.tudey.dobj.SceneDeltaEvent;
import com.threerings.util.Name;
import com.threerings.whirled.data.SceneMarshaller;
import com.threerings.whirled.zone.data.SceneSummary;
import com.threerings.whirled.zone.data.ZoneMarshaller;
import com.threerings.whirled.zone.data.ZoneSummary;

public class ClientSession implements Runnable {
    public static final int LOCATION_SERVICE_CODE = 1;
    public static final int KNIGHT_SERVICE_CODE = 2;
    public static final int PROJECTX_ZONE_SERVICE_CODE = 3;
    public static final int CHAT_SERVICE_CODE = 4;
    public static final int SCENE_SERVICE_CODE = 5;
    public static final int ZONE_SERVICE_CODE = 6;
    public static final int ITEM_SERVICE_CODE = 7;
    public static final int PARTY_SERVICE_CODE = 30;
    public static final int PARTY_SPEAK_SERVICE_CODE = 31;

    // Current client's ZoneDirector receiver registration code. The numeric
    // receiver id is discovered from PlayerObject.receivers at runtime.
    private static final String ZONE_RECEIVER_CODE = "2d900cf54355111b4bb4befcdff42b82";
    private static final short LEGACY_ZONE_RECEIVER_ID = 2;

    private final SocketChannel channel;
    private final DObjectManager dobjMgr;
    private final SceneManager sceneMgr;
    private final SceneNetworkManager sceneNetwork;
    private final MovementConfig movementConfig;
    private final int connectionId;
    private final int clientOid;
    private final String serverVersion;
    private final int serverPort;
    private final Consumer<ClientSession> onClosed;
    private final AtomicBoolean cleanedUp = new AtomicBoolean();
    private final BlockingQueue<Object> outbound = new ArrayBlockingQueue<>(512);
    private PlayerObject playerObject;
    private ServerObject serverObject;
    private AdminObject adminObject;
    private PartyObject partyObject;

    private PlaceConfig currentPlaceConfig;
    private int currentSceneId = SceneManager.DEFAULT_SCENE_ID;
    private int currentPlaceOid = SceneManager.SCENE_OID;
    private float currentSpawnX = SceneManager.HAVEN_SPAWN_X;
    private float currentSpawnY = SceneManager.HAVEN_SPAWN_Y;
    private int sceneTimestamp;
    private int lastClientSceneAcknowledge;
    private long lastAckLogNanos;
    private long previousAckLagMs;
    private boolean inputStreamLogged;
    private Knight authoritativeActor;
    private final Map<Integer, Actor> lastSentActors = new LinkedHashMap<>();
    private VanillaCombatController vanillaCombat;
    private int lastInputTimestamp = Integer.MIN_VALUE;
    private int lastAcknowledgedInputTimestamp;
    private int lastActorStateTimestamp;
    private int inputPacketCount;
    private int rejectedInputFrames;
    private int staleInputFrames;
    private int pendingInputPackets;
    private int deltaTicks;
    private int actorStateTicks;
    private int immediateActorStateTicks;
    private int timingOnlyTicks;
    private boolean inputTimelineSynchronized;
    private boolean portalTransitionPending;
    private volatile boolean sceneReady;
    private Thread writerThread;

    private r source;
    private com.threerings.presents.client.s inStream;
    private s sink;
    private w outStream;
    private volatile boolean running = true;

    private static Field argsField;
    private static Field reqIdField;
    private static Field authVersionField;
    private static Field actorCreatedField;

    static {
        try {
            argsField = InvocationRequestEvent.class.getDeclaredField("_args");
            argsField.setAccessible(true);
        } catch (Exception e) {
            System.err.println("[ClientSession] Cannot inspect invocation arguments: " + e.getMessage());
        }
        try {
            reqIdField = InvocationMarshaller.ListenerMarshaller.class.getField("requestId");
            reqIdField.setAccessible(true);
        } catch (Exception e) {
            System.err.println("[ClientSession] Cannot inspect invocation request ids: " + e.getMessage());
        }
        try {
            authVersionField = AuthRequest.class.getDeclaredField("_version");
            authVersionField.setAccessible(true);
        } catch (Exception e) {
            System.err.println("[ClientSession] Cannot inspect requested client version: " + e.getMessage());
        }
        try {
            actorCreatedField = Actor.class.getDeclaredField("_created");
            actorCreatedField.setAccessible(true);
        } catch (Exception e) {
            System.err.println("[ClientSession] Cannot rebase actor creation time: "
                + e.getMessage());
        }
    }

    public ClientSession(SocketChannel channel, DObjectManager dobjMgr, SceneManager sceneMgr,
            SceneNetworkManager sceneNetwork, MovementConfig movementConfig,
            int connectionId, String serverVersion, int serverPort,
            Consumer<ClientSession> onClosed) {
        this.channel = channel;
        this.dobjMgr = dobjMgr;
        this.sceneMgr = sceneMgr;
        this.sceneNetwork = sceneNetwork;
        this.movementConfig = movementConfig;
        this.connectionId = connectionId;
        this.clientOid = 100 + connectionId;
        this.serverVersion = serverVersion;
        this.serverPort = serverPort;
        this.onClosed = onClosed;
        this.currentPlaceConfig = sceneMgr.getHavenConfig();
    }

    public int getConnectionId() {
        return this.connectionId;
    }

    public int getClientOid() {
        return this.clientOid;
    }

    @Override
    public void run() {
        try {
            System.out.println("[ClientSession-" + connectionId + "] New connection from " + channel.getRemoteAddress());
            channel.configureBlocking(true);

            this.source = new r();
            this.inStream = new com.threerings.presents.client.s(null, this.source);

            this.sink = new s();
            this.outStream = new w(this.sink);
            startWriter();

            // Initialize Objects
            initServerObject();
            initAdminObject();
            initPlayer();
            initParty();

            // Message Loop
            while (running && channel.isOpen()) {
                while (running && !source.a(channel)) {
                }
                if (!running) break;

                Object msg = inStream.qe();
                if (msg == null) {
                    continue;
                }
                handleMessage(msg);
            }
        } catch (EOFException e) {
            System.out.println("[ClientSession-" + connectionId + "] Client disconnected normally.");
        } catch (Exception e) {
            if (running) {
                System.out.println("[ClientSession-" + connectionId + "] Connection ended: " + e.getMessage());
                e.printStackTrace(System.out);
            }
        } finally {
            close();
        }
    }

    private void initPlayer() {
        TownSummary haven = this.serverObject.towns.iterator().next();

        this.playerObject = new PlayerObject();
        this.playerObject.cH(this.clientOid);
        this.playerObject.playerId = connectionId;
        this.playerObject.knightId = connectionId;
        String knightName = this.connectionId == 1
            ? "HeroKnight" : "HeroKnight" + this.connectionId;
        this.playerObject.username = new Name(knightName);
        this.playerObject.knight = new Name(knightName);
        // Packed ColorPository id: class 1 (player), color 1 (magenta).
        // Zero has no ColorRecord and crashes the combat KnightSprite while
        // it creates the player's label during Training Hall loading.
        this.playerObject.color = SceneManager.DEFAULT_PLAYER_COLOR;
        this.playerObject.eye = ProjectXCodes.EyeType.DEFAULT;
        this.playerObject.height = ProjectXCodes.KnightHeight.BASE;
        this.playerObject.rank = com.threerings.projectx.mission.data.MissionCodes.bAS;
        // Native dN(rarity) checks clearance progress, not the displayed rank.
        // Unlock 2/3/4/5-star equipment without granting unrelated progress bits.
        this.playerObject.progressFlags |= 64 | 128 | 256 | 512;
        this.playerObject.prestige = 50000;
        this.playerObject.crowns = 999999;
        this.playerObject.energy = 10000;
        this.playerObject.showEnergy = true;
        this.playerObject.queuePosition = 0;
        this.playerObject.weaponSlots = 4;
        this.playerObject.trinketSlots = 2;
        this.playerObject.tokens = new ProjectXTokenRing(0);
        this.playerObject.stats = new StatSet();

        this.playerObject.achievements = new DSet<>();
        this.playerObject.gifts = new DSet<>();
        this.playerObject.friends = new DSet<>();
        this.playerObject.newMailInfo = new DSet<>();
        this.playerObject.notifications = new DSet<>();
        this.playerObject.sprites = new DSet<>();
        this.playerObject.harnesses = new DSet<>();
        this.playerObject.recipes = new DSet<>();
        this.playerObject.loadouts = new DSet<>();
        this.playerObject.objectives = new DSet<>();
        this.playerObject.memories = new DSet<>();
        this.playerObject.completedMissions = new DSet<>();
        // OptionsDialog in the client reads per-player preference values.
        // An empty set lets PlayerObject apply the client's built-in defaults.
        this.playerObject.prefs = new DSet<>();
        this.playerObject.expansions = Sets.newHashSet();
        this.playerObject.rewardIds = Sets.newHashSet();
        this.playerObject.missionTests = Sets.newHashSet();

        this.playerObject.equipment = new long[16];
        this.playerObject.quickbar = new long[8];

        // Town and Zone Config
        this.playerObject.homeTown = haven;
        this.playerObject.lastTownSceneId = SceneManager.DEFAULT_SCENE_ID;
        this.playerObject.zoneId = 1;
        this.playerObject.partyOid = 1_000 + this.connectionId;

        // Populate gear before composing the character model. The model
        // reference includes the currently equipped armor, helm and shield.
        ItemCatalog.populateKnightInventory(this.playerObject, this.sceneMgr);

        // Character Select Card Setup
        AvailableKnight knight = new AvailableKnight(
            1,
            this.playerObject.knight,
            10,
            "Developers",
            3600,
            this.sceneMgr.createPlayerModel(this.playerObject),
            Sets.newHashSet()
        );
        this.playerObject.availableKnights = new AvailableKnight[]{ knight };

        // Register with DObjectManager
        this.dobjMgr.register(this.playerObject);

        // Add to Scene
        this.sceneMgr.addPlayer(this.playerObject);
    }

    @SuppressWarnings("unchecked")
    private void initParty() {
        this.partyObject = new PartyObject();
        this.partyObject.cH(this.playerObject.partyOid);

        PartyMarshaller partyService = new PartyMarshaller();
        partyService.a(this.partyObject.xL(), PARTY_SERVICE_CODE, null);
        this.partyObject.partyService = partyService;
        com.threerings.crowd.chat.data.SpeakMarshaller speakService =
            new com.threerings.crowd.chat.data.SpeakMarshaller();
        speakService.a(this.partyObject.xL(), PARTY_SPEAK_SERVICE_CODE, null);
        this.partyObject.speakService = speakService;

        PartyMember member = new PartyMember(
            this.sceneMgr.getConfigManager(), this.playerObject, 0, false);
        this.partyObject.members = new DSet<>(Collections.singletonList(member));
        this.partyObject.health = new DSet<>(Collections.singletonList(
            new Health(this.clientOid, 100, 0)));
        Status status = new Status(this.clientOid, false);
        status.conditions = new ConfigReference[0];
        status.expires = new int[0];
        this.partyObject.status = new DSet<>(Collections.singletonList(status));
        this.partyObject.visited = Sets.newHashSet(SceneManager.TRAINING_SCENE_ID);
        this.partyObject.spawn = new com.threerings.math.Vector2f(
            SceneManager.TRAINING_SPAWN_X, SceneManager.TRAINING_SPAWN_Y);
        this.partyObject.depth = 0;
        this.partyObject.hasPickups = true;
        this.dobjMgr.register(this.partyObject);
        System.out.println("[ClientSession-" + connectionId + "][PARTY] initialized"
            + " oid=" + this.partyObject.xL()
            + " members=1 health=100 weapons=" + member.weapons.length);
    }

    private void initServerObject() {
        this.serverObject = new ServerObject();
        this.serverObject.cH(10);
        this.serverObject.nodeName = "SpiralServer-Local";

        // Add Haven Town
        TownSummary haven = new TownSummary(
            1,
            "Haven",
            "town_haven",
            "Haven Town Square",
            new ColorizationConfig[0],
            1,
            0
        );
        this.serverObject.towns = new DSet<>(Collections.singletonList(haven));

        // Add Arcade Gate
        RotatingGateSummary arcade = new RotatingGateSummary(
            1,
            "Arcade",
            "gate_tier1",
            new ColorizationConfig[0],
            "The Clockworks",
            1,
            2,
            1,
            0,
            0,
            0, // flags: this local arcade gate has no special flags
            new GateSummary.Wheel[0],
            new Theme[0]
        );
        this.serverObject.gates = new DSet<>(Collections.singletonList(arcade));

        this.serverObject.disabledAchievements = Sets.newHashSet();
        this.serverObject.disabledMissionPrefixes = Sets.newHashSet();
        this.serverObject.scopeVariables = Sets.newHashSet();
        this.serverObject.events = Maps.newHashMap();
        this.serverObject.spamwords = new String[0];
        this.dobjMgr.registerIfAbsent(this.serverObject);
    }

    private void initAdminObject() {
        this.adminObject = new AdminObject();
        this.adminObject.cH(60);
        this.adminObject.nodeInfo = new DSet<>();
        this.dobjMgr.registerIfAbsent(this.adminObject);
    }

    private void handleMessage(Object msg) throws Exception {
        if (msg instanceof AuthRequest) {
            handleAuth((AuthRequest)msg);
        } else if (msg instanceof SubscribeRequest) {
            handleSubscribe((SubscribeRequest)msg);
        } else if (msg instanceof UnsubscribeRequest) {
            handleUnsubscribe((UnsubscribeRequest)msg);
        } else if (msg instanceof ForwardEventRequest) {
            handleForwardEvent((ForwardEventRequest)msg);
        } else if (msg instanceof PingRequest) {
            handlePing((PingRequest)msg);
        } else if (msg instanceof LogoffRequest) {
            System.out.println("[ClientSession-" + connectionId + "] LogoffRequest received.");
            running = false;
        } else {
            System.out.println("[ClientSession-" + connectionId + "] Received message: " + msg.getClass().getSimpleName());
        }
    }

    private void startWriter() {
        this.writerThread = new Thread(this::writerLoop,
            "ClientSession-" + this.connectionId + "-Writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    private void writerLoop() {
        try {
            while (this.running || !this.outbound.isEmpty()) {
                Object message = this.outbound.poll(250L, TimeUnit.MILLISECONDS);
                if (message != null) {
                    writeMessageNow(message);
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            if (this.running) {
                System.out.println("[ClientSession-" + connectionId
                    + "][NETWORK] writer failed: " + error.getMessage());
                close();
            }
        }
    }

    private void sendMessage(Object msg) throws IOException {
        if (!this.running) {
            throw new EOFException("session closed");
        }
        if (!this.outbound.offer(msg)) {
            System.out.println("[ClientSession-" + connectionId
                + "][NETWORK][WARNING] outbound queue overflow size="
                + this.outbound.size());
            throw new IOException("outbound queue overflow");
        }
    }

    private void writeMessageNow(Object msg) throws IOException {
        outStream.bf(msg);
        outStream.flush();
        ByteBuffer buf = sink.sH();
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
        sink.sI();
    }

    private void handleAuth(AuthRequest req) throws Exception {
        String requestedVersion = readRequestedVersion(req);
        System.out.println("[ClientSession-" + connectionId + "][AUTH] request received"
            + " requestedVersion=" + requestedVersion
            + " serverVersion=" + serverVersion);
        if (!"unknown".equals(requestedVersion) && !"unknown".equals(serverVersion)
                && !serverVersion.equals(requestedVersion)) {
            System.out.println("[ClientSession-" + connectionId + "][AUTH][WARNING] Version mismatch accepted for local development.");
        }

        // 1. Send AuthResponse with code = "success"
        ProjectXAuthResponseData authData = new ProjectXAuthResponseData();
        authData.code = "success";
        authData.ident = "spiral";
        authData.sessionId = "session-" + connectionId + "-" + System.currentTimeMillis();
        authData.authName = this.playerObject.username;
        sendMessage(new AuthResponse(authData));
        System.out.println("[ClientSession-" + connectionId + "][AUTH] success authName=" + authData.authName
            + " clientOid=" + clientOid);

        // 2. Send BootstrapNotification
        ProjectXBootstrapData bd = new ProjectXBootstrapData();
        bd.connectionId = this.connectionId;
        bd.clientOid = this.clientOid;
        bd.hostname = advertisedHost();
        bd.ports = new int[]{this.serverPort};
        bd.datagramPorts = new int[0];
        bd.serverOid = 10;
        bd.adminOid = 60;
        bd.exchangeOid = 0;
        bd.pvpOid = 0;
        bd.pvpGameInfoOid = 0;
        bd.missionOid = 0;
        bd.openHallOid = 0;
        bd.statusOid = 0;
        bd.placeBrowserOid = 0;
        bd.clientTimingsOid = 0;

        bd.services = new ArrayList<>();

        LocationMarshaller locMarsh = new LocationMarshaller();
        locMarsh.a(this.clientOid, LOCATION_SERVICE_CODE, null);
        bd.services.add(locMarsh);

        KnightMarshaller knightMarsh = new KnightMarshaller();
        knightMarsh.a(this.clientOid, KNIGHT_SERVICE_CODE, null);
        bd.services.add(knightMarsh);

        com.threerings.projectx.item.data.ItemMarshaller itemMarsh =
            new com.threerings.projectx.item.data.ItemMarshaller();
        itemMarsh.a(this.clientOid, ITEM_SERVICE_CODE, (com.threerings.presents.client.x)null);
        bd.services.add(itemMarsh);

        ProjectXZoneMarshaller zoneMarsh = new ProjectXZoneMarshaller();
        zoneMarsh.a(this.clientOid, PROJECTX_ZONE_SERVICE_CODE, null);
        bd.services.add(zoneMarsh);

        ChatMarshaller chatMarsh = new ChatMarshaller();
        chatMarsh.a(this.clientOid, CHAT_SERVICE_CODE, null);
        bd.services.add(chatMarsh);

        SceneMarshaller sceneMarsh = new SceneMarshaller();
        sceneMarsh.a(this.clientOid, SCENE_SERVICE_CODE,
            (com.threerings.presents.client.x)null);
        bd.services.add(sceneMarsh);

        ZoneMarshaller whirledZoneMarsh = new ZoneMarshaller();
        whirledZoneMarsh.a(this.clientOid, ZONE_SERVICE_CODE, null);
        bd.services.add(whirledZoneMarsh);

        sendMessage(new BootstrapNotification(bd));
        // Presents defaults to ten outgoing messages per second. At a 20 ms
        // input cadence that queues seconds of stale controls and scene ACKs.
        // Use the existing protocol negotiation, with room for RPCs/keepalive.
        int fastestInterval = Math.min(this.movementConfig.deltaIntervalMs,
            this.movementConfig.trainingDeltaIntervalMs);
        int messagesPerSecond = Math.max(100, 2 * ((1000 + fastestInterval - 1)
            / fastestInterval) + 20);
        sendMessage(new UpdateThrottleMessage(messagesPerSecond));
        System.out.println("[ClientSession-" + connectionId
            + "][NETWORK] client send budget=" + messagesPerSecond + " messages/sec");
        System.out.println("[ClientSession-" + connectionId + "][BOOTSTRAP] sent services="
            + "Location,Knight,ProjectXZone,Chat,Scene,Zone");
        System.out.println("[ClientSession-" + connectionId + "][BOOTSTRAP] waiting for client subscription to OID=" + clientOid);
    }

    private void handleSubscribe(SubscribeRequest req) throws Exception {
        int oid = req.xL();
        System.out.println("[ClientSession-" + connectionId + "][SUBSCRIBE] oid=" + oid);

        DObject obj = dobjMgr.get(oid);
        if (obj != null) {
            sendMessage(new ObjectResponse<>(obj));
            System.out.println("[ClientSession-" + connectionId + "][SUBSCRIBE] dispatched="
                + obj.getClass().getSimpleName() + " oid=" + oid);
            if (this.sceneMgr.getSceneObjectByPlaceOid(oid) != null) {
                ProjectXSceneObject scene = (ProjectXSceneObject)obj;
                int sceneId = this.sceneMgr.getSceneIdForPlaceOid(oid);
                TudeySceneModel model = this.sceneMgr.getSceneModel(sceneId);
                System.out.println("[ClientSession-" + connectionId + "][WORLD] scene subscription payload"
                    + " placeOid=" + oid
                    + " modelSceneId=" + (model == null ? "null" : model.sceneId)
                    + " occupants=" + scene.occupants
                    + " services={speak:" + (scene.speakService != null)
                    + ",tudey:" + (scene.tudeySceneService != null)
                    + ",projectx:" + (scene.projectXSceneService != null)
                    + ",town:" + (scene instanceof TownSceneObject
                        && ((TownSceneObject)scene).townSceneService != null) + "}");
                System.out.println("[ClientSession-" + connectionId + "][WORLD] scene object delivered; waiting for client willEnterPlace");
            } else if (obj == this.partyObject) {
                System.out.println("[ClientSession-" + connectionId + "][PARTY] subscription delivered"
                    + " oid=" + oid + " members=" + this.partyObject.members.size()
                    + " health=" + this.partyObject.health.size()
                    + " status=" + this.partyObject.status.size());
            }
        } else {
            System.out.println("[ClientSession-" + connectionId + "][SUBSCRIBE][WARNING] object not found oid=" + oid);
            sendMessage(new FailureResponse(oid, "m.object_not_found"));
        }
    }

    private void handleUnsubscribe(UnsubscribeRequest req) throws Exception {
        int oid = req.xL();
        System.out.println("[ClientSession-" + connectionId + "] UnsubscribeRequest for OID: " + oid);
        sendMessage(new UnsubscribeResponse(oid));
    }

    private void handleForwardEvent(ForwardEventRequest req) throws Exception {
        DEvent event = req.yj();
        if (event instanceof InvocationRequestEvent) {
            InvocationRequestEvent invReq = (InvocationRequestEvent)event;
            int invCode = invReq.ye();
            int methodId = invReq.yc();
            Object[] args = (argsField != null) ? (Object[])argsField.get(invReq) : new Object[0];
            System.out.println("[ClientSession-" + connectionId + "] InvocationRequest: code=" + invCode + ", method=" + methodId + ", args=" + Arrays.toString(args));

            // TudeySceneMarshaller.adc(): once the place object is installed,
            // the client asks for an initial SceneDeltaEvent. That first delta
            // creates its PreloadableSet; omitting it leaves the loading screen
            // permanently at exactly 0 percent.
            if (invCode == SceneManager.TUDEY_SCENE_SERVICE_CODE && methodId == 1) {
                sendInitialSceneState();
                return;
            }

            for (Object arg : args) {
                if (arg instanceof InvocationMarshaller.ListenerMarshaller) {
                    InvocationMarshaller.ListenerMarshaller lm = (InvocationMarshaller.ListenerMarshaller)arg;
                    short reqId = lm.requestId;

                    // Client ItemMarshaller method 9: move item to location/slot.
                    if (invCode == ITEM_SERVICE_CODE && methodId == 9) {
                        try {
                            moveEquipment(((Number)args[0]).longValue(),
                                (com.threerings.projectx.item.data.ItemCodes.Location)args[1],
                                ((Number)args[2]).intValue());
                            sendDefaultListenerResponse(reqId, lm);
                        } catch (IllegalArgumentException error) {
                            sendMessage(new EventNotification(new InvocationResponseEvent(
                                this.clientOid,reqId,(byte)0,new Object[]{"m.invalid_item"})));
                        }
                        return;
                    }

                    // 1. Whirled ZoneMarshaller moveTo(zone, scene, version)
                    if (invCode == ZONE_SERVICE_CODE || lm instanceof ZoneMarshaller.ZoneMoveMarshaller) {
                        sendZoneMoveResponse(reqId, args);
                        return;
                    }

                    // 2. Whirled SceneMarshaller moveTo(scene, version)
                    if (invCode == SCENE_SERVICE_CODE || lm instanceof SceneMarshaller.SceneMoveMarshaller) {
                        sendSceneMoveResponse(reqId, args);
                        return;
                    }

                    // 3. LocationMarshaller.moveToPlace
                    if (invCode == LOCATION_SERVICE_CODE && methodId == 2) {
                        int requestedPlaceOid = intArgument(args, 0, this.currentPlaceOid);
                        int requestedSceneId = this.sceneMgr.getSceneIdForPlaceOid(requestedPlaceOid);
                        if (requestedSceneId != -1) {
                            activateScene(requestedSceneId);
                        }
                        System.out.println("[ClientSession-" + connectionId
                            + "][WORLD] Location moveToPlace requested placeOid="
                            + requestedPlaceOid + " -> "
                            + this.currentPlaceConfig.getClass().getSimpleName());
                        InvocationResponseEvent resp = new InvocationResponseEvent(
                            this.clientOid,
                            reqId,
                            (byte)1,
                            new Object[]{ this.currentPlaceConfig }
                        );
                        sendMessage(new EventNotification(resp));
                        return;
                    }

                    // 4. KnightMarshaller selectKnight
                    if (invCode == KNIGHT_SERVICE_CODE && (methodId == 3 || methodId == 4)) {
                        Name selectedKnight = findNameArgument(args);
                        System.out.println("[ClientSession-" + connectionId + "][KNIGHT] selected=" + selectedKnight);
                        activateScene(SceneManager.DEFAULT_SCENE_ID);

                        // Confirm selectKnight
                        InvocationResponseEvent confirmResp = new InvocationResponseEvent(this.clientOid, reqId, (byte)1, new Object[0]);
                        sendMessage(new EventNotification(confirmResp));

                        // Trigger the registered ZoneDirector. It will call the
                        // ZoneMarshaller and receive the model in one transition.
                        short zoneReceiverId = findReceiverId(ZONE_RECEIVER_CODE, LEGACY_ZONE_RECEIVER_ID);
                        InvocationNotificationEvent zoneNotif = new InvocationNotificationEvent(
                            this.clientOid,
                            zoneReceiverId,
                            1,
                            new Object[]{ Integer.valueOf(1), Integer.valueOf(SceneManager.DEFAULT_SCENE_ID) }
                        );
                        sendMessage(new EventNotification(zoneNotif));
                        System.out.println("[ClientSession-" + connectionId + "][WORLD] move requested zone=1 scene="
                            + SceneManager.DEFAULT_SCENE_ID + " placeOid=" + SceneManager.SCENE_OID
                            + " receiverId=" + zoneReceiverId);
                        return;
                    }

                    // Default confirm response
                    sendDefaultListenerResponse(reqId, lm);
                    return;
                }
            }
        } else if (event instanceof InputEnqueueEvent) {
            InputEnqueueEvent input = (InputEnqueueEvent)event;
            if (!this.inputStreamLogged) {
                this.inputStreamLogged = true;
                System.out.println("[ClientSession-" + connectionId + "][TUDEY] player input stream active"
                    + " sceneOid=" + event.xH()
                    + " acknowledgedSceneTimestamp=" + input.acknowledge
                    + " smoothedTime=" + input.smoothedTime
                    + " frames=" + (input.frames == null ? 0 : input.frames.length));
            }
            processInput(input);
        } else {
            System.out.println("[ClientSession-" + connectionId + "] ForwardEvent: " + event);
            if (event != null && playerObject != null && event.xH() == playerObject.xL()) {
                try {
                    applyClientObjectEvent(event);
                    System.out.println("[ClientSession-" + connectionId + "][CLIENT_OBJECT] applied event="
                        + event.getClass().getSimpleName() + " receivers=" + playerObject.receivers);
                } catch (Exception e) {
                    System.out.println("[ClientSession-" + connectionId + "][CLIENT_OBJECT][WARNING] Failed event: " + e.getMessage());
                }
            }
        }
    }

    private void applyClientObjectEvent(DEvent event) {
        playerObject.e(event);
        if (event instanceof CompoundEvent) {
            for (DEvent child : ((CompoundEvent)event).xE()) {
                applySingleClientObjectEvent(child);
            }
        } else {
            applySingleClientObjectEvent(event);
        }
    }

    private synchronized void moveEquipment(long itemId,
            com.threerings.projectx.item.data.ItemCodes.Location location,int slot) throws IOException {
        this.sceneMgr.moveEquipment(this.playerObject,itemId,location,slot);
        sendMessage(new EventNotification(new AttributeChangedEvent(
            this.clientOid,"equipment",this.playerObject.equipment.clone())));
        sendMessage(new EventNotification(new AttributeChangedEvent(
            this.clientOid,"quickbar",this.playerObject.quickbar.clone())));
        if (this.authoritativeActor instanceof Dungeoneer) {
            Dungeoneer pawn=(Dungeoneer)this.authoritativeActor;
            int index=this.vanillaCombat==null?0:this.vanillaCombat.weaponIndex();
            this.sceneMgr.refreshCombatEquipment(pawn,this.playerObject,index,this.sceneTimestamp);
        } else if (this.authoritativeActor != null) {
            this.authoritativeActor.setConfig(this.playerObject.a("Character/PC/Default",
                this.sceneMgr.createPlayerModel(this.playerObject),new Object[0]));
            this.authoritativeActor.init(this.sceneMgr.getConfigManager());
        }
        PartyMember member=new PartyMember(this.sceneMgr.getConfigManager(),this.playerObject,0,false);
        this.partyObject.members=new DSet<>(Collections.singletonList(member));
        sendMessage(new EventNotification(new AttributeChangedEvent(
            this.partyObject.xL(),"members",this.partyObject.members)));
        System.out.println("[EQUIPMENT] item="+itemId+" location="+location+" slot="+slot);
    }

    private void applySingleClientObjectEvent(DEvent event) {
        boolean notifyObservers = event.b(playerObject);
        if (notifyObservers) {
            playerObject.d(event);
        }
    }

    private void sendZoneMoveResponse(short reqId, Object[] requestArgs) throws IOException {
        int requestedZoneId = intArgument(requestArgs, 0, -1);
        int requestedSceneId = intArgument(requestArgs, 1, -1);
        int requestedVersion = intArgument(requestArgs, 2, -1);
        System.out.println("[ClientSession-" + connectionId + "][WORLD] zone move RPC"
            + " zoneId=" + requestedZoneId + " sceneId=" + requestedSceneId
            + " cachedVersion=" + requestedVersion);
        if (this.sceneMgr.getSceneModel(requestedSceneId) == null) {
            System.out.println("[ClientSession-" + connectionId
                + "][WORLD][WARNING] unsupported sceneId=" + requestedSceneId
                + "; keeping sceneId=" + this.currentSceneId);
            requestedSceneId = this.currentSceneId;
        }
        activateScene(requestedSceneId);
        ZoneSummary zone = createHavenZoneSummary();
        TudeySceneModel model = this.sceneMgr.getSceneModel(this.currentSceneId);
        InvocationResponseEvent response;
        if (model != null) {
            response = new InvocationResponseEvent(this.clientOid, reqId, (byte)3,
                new Object[]{ Integer.valueOf(this.currentPlaceOid), this.currentPlaceConfig, zone, model });
            System.out.println("[ClientSession-" + connectionId + "][WORLD] zone response includes SceneModel"
                + " sceneId=" + model.sceneId + " version=" + model.version);
        } else {
            response = new InvocationResponseEvent(this.clientOid, reqId, (byte)2,
                new Object[]{ Integer.valueOf(SceneManager.SCENE_OID), this.currentPlaceConfig, zone });
            System.out.println("[ClientSession-" + connectionId + "][WORLD][WARNING] zone response without SceneModel");
        }
        sendMessage(new EventNotification(response));
    }

    private void sendSceneMoveResponse(short reqId, Object[] requestArgs) throws IOException {
        int requestedSceneId = intArgument(requestArgs, 0, this.currentSceneId);
        if (this.sceneMgr.getSceneModel(requestedSceneId) != null) {
            activateScene(requestedSceneId);
        }
        TudeySceneModel model = this.sceneMgr.getSceneModel(this.currentSceneId);
        InvocationResponseEvent response;
        if (model != null) {
            response = new InvocationResponseEvent(this.clientOid, reqId, (byte)3,
                new Object[]{ Integer.valueOf(this.currentPlaceOid), this.currentPlaceConfig, model });
        } else {
            response = new InvocationResponseEvent(this.clientOid, reqId, (byte)2,
                new Object[]{ Integer.valueOf(this.currentPlaceOid), this.currentPlaceConfig });
        }
        sendMessage(new EventNotification(response));
    }

    private synchronized void sendInitialSceneState() throws IOException {
        this.sceneReady = false;
        int reference = 0;
        int timestamp = this.sceneNetwork.currentTimestamp(this.currentSceneId);
        short elapsed = (short)Math.max(1, Math.min(timestamp, Short.MAX_VALUE));
        Knight playerActor = this.sceneMgr.createPlayerKnight(
            this.playerObject, timestamp, this.currentSceneId,
            this.currentSpawnX, this.currentSpawnY);
        this.authoritativeActor = playerActor;
        resetCombatState();
        this.sceneNetwork.join(this.currentSceneId, this);
        Map<Integer, Actor> actors = this.sceneNetwork.snapshotActors(this.currentSceneId);
        if (!actors.containsKey(playerActor.vw())) {
            actors.put(playerActor.vw(), (Knight)playerActor.copy(null));
        }
        Actor[] addedActors = actors.values().toArray(new Actor[actors.size()]);
        SceneDeltaEvent initialState = new SceneDeltaEvent(
            this.clientOid,
            this.currentPlaceOid,
            0,
            (short)0,
            reference,
            timestamp,
            elapsed,
            addedActors,
            null,
            null,
            null);
        this.sceneTimestamp = timestamp;
        this.lastClientSceneAcknowledge = timestamp;
        this.lastAckLogNanos = System.nanoTime();
        this.previousAckLagMs = 0L;
        this.lastAcknowledgedInputTimestamp = 0;
        this.lastActorStateTimestamp = timestamp;
        this.lastSentActors.clear();
        copyActors(actors, this.lastSentActors);
        sendMessage(new EventNotification(initialState));
        this.sceneReady = true;
        this.sceneNetwork.broadcastOccupants(this.currentSceneId);
        Object composedModel = playerActor.Mx().kW().get("Model");
        System.out.println("[ClientSession-" + connectionId + "][TUDEY] initial scene state sent"
            + " targetOid=" + this.clientOid
            + " sceneId=" + this.currentSceneId
            + " sceneOid=" + this.currentPlaceOid
            + " reference=" + reference
            + " timestamp=" + timestamp
            + " actors=" + addedActors.length + " pawnId=" + playerActor.vw()
            + " actorType=" + playerActor.getClass().getSimpleName()
            + " actorConfig=" + playerActor.Mx().getName()
            + " model=" + composedModel
            + " activity=" + playerActor.adX()
            + " spawn=(" + this.currentSpawnX + "," + this.currentSpawnY + ")"
            + "; client preload may begin");
    }

    private synchronized void processInput(InputEnqueueEvent input) throws IOException {
        if (this.authoritativeActor == null || this.sceneTimestamp <= 0) {
            return;
        }

        int priorClientAcknowledge = this.lastClientSceneAcknowledge;
        int boundedAcknowledge = Math.max(0,
            Math.min(input.acknowledge, this.sceneTimestamp));
        if (boundedAcknowledge > this.lastClientSceneAcknowledge) {
            this.lastClientSceneAcknowledge = boundedAcknowledge;
        }

        InputFrame[] frames = input.frames == null ? new InputFrame[0] : input.frames;
        InputFrame[] orderedFrames = frames.clone();
        // The live client sends its unacknowledged history newest-first. Process
        // it chronologically so every new sample advances the simulation once.
        Arrays.sort(orderedFrames, (left, right) -> {
            if (left == null) return right == null ? 0 : -1;
            if (right == null) return 1;
            return Integer.compare(left.Rb(), right.Rb());
        });
        int firstFrameIndex = Math.max(
            0, orderedFrames.length - this.movementConfig.maxFramesPerPacket);
        int frameCount = orderedFrames.length - firstFrameIndex;
        if (firstFrameIndex > 0) {
            this.rejectedInputFrames += firstFrameIndex;
            System.out.println("[ClientSession-" + connectionId + "][INPUT][WARNING] packet truncated"
                + " received=" + frames.length + " acceptedLimit=" + frameCount);
        }

        float packetBeforeX = this.authoritativeActor.tz().x;
        float packetBeforeY = this.authoritativeActor.tz().y;
        int accepted = 0;
        int latestAcceptedTimestamp = this.lastInputTimestamp;
        int latestInputFlags = 0;
        int referenceSceneTimestamp = this.sceneTimestamp;
        int targetSceneTimestamp = Math.max(referenceSceneTimestamp,
            this.sceneNetwork.currentTimestamp(this.currentSceneId));
        int newestPacketInputTimestamp = Integer.MIN_VALUE;

        for (int index = firstFrameIndex; index < orderedFrames.length; index++) {
            InputFrame frame = orderedFrames[index];
            if (frame != null && Float.isFinite(frame.tA()) && Float.isFinite(frame.adC())
                    && (this.lastInputTimestamp == Integer.MIN_VALUE
                        || frame.Rb() > this.lastInputTimestamp)) {
                newestPacketInputTimestamp = Math.max(newestPacketInputTimestamp, frame.Rb());
            }
        }

        if (this.currentSceneId == SceneManager.TRAINING_SCENE_ID
                && this.authoritativeActor instanceof Dungeoneer) {
            // Combat activities expire on the authoritative scene clock even
            // while the player is standing still and sends no new frames.
            if (this.vanillaCombat != null) this.vanillaCombat.tick(targetSceneTimestamp);
        }

        for (int index = firstFrameIndex; index < orderedFrames.length; index++) {
            InputFrame frame = orderedFrames[index];
            if (frame == null || !Float.isFinite(frame.tA()) || !Float.isFinite(frame.adC())) {
                this.rejectedInputFrames++;
                continue;
            }

            int frameTimestamp = frame.Rb();
            if (this.lastInputTimestamp != Integer.MIN_VALUE
                    && frameTimestamp <= this.lastInputTimestamp) {
                this.staleInputFrames++;
                continue;
            }

            if (!this.inputTimelineSynchronized) {
                this.inputTimelineSynchronized = true;
                System.out.println("[ClientSession-" + connectionId
                    + "][INPUT] timeline synchronized"
                    + " sceneTimestamp=" + this.sceneTimestamp
                    + " smoothedTime=" + input.smoothedTime
                    + " firstInputTimestamp=" + frameTimestamp
                    + " predictionLeadMs="
                    + ((long)frameTimestamp - input.smoothedTime));
            }

            int elapsedMs = 0;
            if (this.lastInputTimestamp != Integer.MIN_VALUE) {
                long rawElapsed = (long)frameTimestamp - (long)this.lastInputTimestamp;
                elapsedMs = (int)Math.min(rawElapsed, this.movementConfig.maxFrameDeltaMs);
            }

            int frameSceneTimestamp = targetSceneTimestamp;
            if (newestPacketInputTimestamp != Integer.MIN_VALUE) {
                long historyAge = (long)newestPacketInputTimestamp - frameTimestamp;
                long translated = (long)targetSceneTimestamp - Math.max(0L, historyAge);
                frameSceneTimestamp = (int)Math.max(
                    (long)referenceSceneTimestamp + 1L,
                    Math.min((long)targetSceneTimestamp, translated));
            }

            boolean combatFrame = this.currentSceneId == SceneManager.TRAINING_SCENE_ID
                && this.authoritativeActor instanceof Dungeoneer && frame instanceof DungeonInputFrame;
            if (!combatFrame) {
                this.authoritativeActor.ah(frame.tA());
                this.authoritativeActor.aP(frame.adC());
            }
            if (this.authoritativeActor.adX() == 1) {
                // Activity 1 is the creation animation. The normal state is
                // activity 0; idle/walk selection then follows actor flag 2.
                this.authoritativeActor.aU(0, frameSceneTimestamp);
            }
            if (combatFrame) {
                Dungeoneer pawn = (Dungeoneer)this.authoritativeActor;
                if (this.vanillaCombat == null) this.vanillaCombat = new VanillaCombatController(
                    this.sceneMgr,this.playerObject,pawn,frameSceneTimestamp-1);
                DungeonInputFrame dungeon = (DungeonInputFrame)frame;
                int actionTimestamp = (int)Math.max(Integer.MIN_VALUE,(long)frameSceneTimestamp
                    - Math.max(0L,(long)frameTimestamp-dungeon.Nk()));
                int flags = this.movementConfig.enabled ? frame.getFlags() : frame.getFlags() & ~1;
                this.vanillaCombat.input(new DungeonInputFrame(
                    frameSceneTimestamp,frame.tA(),frame.adC(),flags,actionTimestamp));
                clampCombatPosition();
            }

            boolean moving = this.movementConfig.enabled && frame.bR(1);
            if (!combatFrame) this.authoritativeActor.k(2, moving);
            if (!combatFrame && moving && elapsedMs > 0) {
                float distance = this.movementConfig.effectiveSpeed() * elapsedMs / 1000.0f;
                float x = this.authoritativeActor.tz().x
                    + distance * (float)Math.cos(frame.adC());
                float y = this.authoritativeActor.tz().y
                    + distance * (float)Math.sin(frame.adC());
                if (this.movementConfig.clampToMapBounds) {
                    x = clamp(x, this.movementConfig.minX, this.movementConfig.maxX);
                    y = clamp(y, this.movementConfig.minY, this.movementConfig.maxY);
                }
                this.sceneMgr.getCollision(this.currentSceneId).move(
                    this.authoritativeActor, x, y);
                this.authoritativeActor.setDirty(true);
            }

            this.lastInputTimestamp = frameTimestamp;
            latestAcceptedTimestamp = frameTimestamp;
            latestInputFlags = frame.getFlags();
            accepted++;
        }

        this.inputPacketCount++;
        this.pendingInputPackets++;
        if (accepted > 0) {
            maybeTriggerPortal(packetBeforeX, packetBeforeY);
        }

        if (shouldLogInputPacket()) {
            System.out.println("[ClientSession-" + connectionId + "][INPUT]"
                + " packet=" + this.inputPacketCount
                + " accepted=" + accepted
                + " rejectedTotal=" + this.rejectedInputFrames
                + " staleTotal=" + this.staleInputFrames
                + " clientSceneAck=" + input.acknowledge
                + " acceptedSceneAck=" + this.lastClientSceneAcknowledge
                + " ackAdvanced="
                + (this.lastClientSceneAcknowledge > priorClientAcknowledge)
                + " smoothedTime=" + input.smoothedTime
                + " serverSceneTimestamp=" + this.sceneTimestamp
                + " inputTimestamp=" + latestAcceptedTimestamp
                + " predictionLeadMs="
                + (latestAcceptedTimestamp == Integer.MIN_VALUE
                    ? 0L : (long)latestAcceptedTimestamp - this.sceneTimestamp)
                + " clientAckLagMs=" + ((long)this.sceneTimestamp - input.acknowledge)
                + " queuedForTick=true"
                + " pendingPackets=" + this.pendingInputPackets
                + " outboundQueue=" + this.outbound.size() + "/512"
                + " flags=0x" + Integer.toHexString(latestInputFlags)
                + " moving=" + ((latestInputFlags & 1) != 0)
                + " action=" + ((latestInputFlags & 4) != 0)
                + " weapon=" + (this.currentSceneId == SceneManager.TRAINING_SCENE_ID
                    ? (this.vanillaCombat==null?1:this.vanillaCombat.weaponIndex()+1) : 0)
                + " activity=" + this.authoritativeActor.adX()
                + " position=(" + this.authoritativeActor.tz().x
                + "," + this.authoritativeActor.tz().y + ")"
                + " rotation=" + this.authoritativeActor.tA()
                + " direction=" + this.authoritativeActor.adC());
        }
    }

    private static String currentWeaponName(Dungeoneer actor) {
        LevelItem weapon = actor.Rl();
        return weapon == null || weapon.Mx() == null
            ? "none" : weapon.Mx().getName();
    }

    private void logDeliveredCombatState(
            Knight previous, int reference, int acknowledgedInputTimestamp, int timestamp,
            boolean actorChanged, int batchedInputPackets) {
        if (!actorChanged || !(previous instanceof Dungeoneer)
                || !(this.authoritativeActor instanceof Dungeoneer)) {
            return;
        }
        Dungeoneer oldActor = (Dungeoneer)previous;
        Dungeoneer newActor = (Dungeoneer)this.authoritativeActor;
        String oldWeapon = currentWeaponName(oldActor);
        String newWeapon = currentWeaponName(newActor);
        if (oldActor.adX() == newActor.adX() && Objects.equals(oldWeapon, newWeapon)) {
            return;
        }
        System.out.println("[ClientSession-" + connectionId + "][COMBAT] delta delivered"
            + " reference=" + reference
            + " timestamp=" + timestamp
            + " acknowledge=" + acknowledgedInputTimestamp
            + " activity=" + newActor.adX()
            + " weapon=" + newWeapon
            + " batchedInputPackets=" + batchedInputPackets);
    }

    private void resetCombatState() {
        this.vanillaCombat = null;
    }

    private void clampCombatPosition() {
        if (!this.movementConfig.clampToMapBounds) return;
        float x=clamp(this.authoritativeActor.tz().x,this.movementConfig.minX,this.movementConfig.maxX);
        float y=clamp(this.authoritativeActor.tz().y,this.movementConfig.minY,this.movementConfig.maxY);
        if (x!=this.authoritativeActor.tz().x || y!=this.authoritativeActor.tz().y) {
            this.authoritativeActor.tz().s(x,y);
            this.authoritativeActor.setDirty(true);
        }
    }
    synchronized Knight snapshotActorForTick(int sceneId, int timestamp) {
        TickSnapshot snapshot = snapshotForTick(sceneId, timestamp);
        return snapshot == null ? null : snapshot.actor;
    }

    static final class TickSnapshot {
        final Knight actor;
        final int inputTimestamp;
        TickSnapshot(Knight actor, int inputTimestamp) {
            this.actor = actor;
            this.inputTimestamp = inputTimestamp;
        }
    }

    synchronized TickSnapshot snapshotForTick(int sceneId, int timestamp) {
        if (!this.running || this.authoritativeActor == null
                || this.currentSceneId != sceneId) {
            return null;
        }
        if (sceneId == SceneManager.TRAINING_SCENE_ID
                && this.authoritativeActor instanceof Dungeoneer) {
            if (this.vanillaCombat != null) {
                this.vanillaCombat.tick(timestamp);
                clampCombatPosition();
            }
        }
        return new TickSnapshot((Knight)this.authoritativeActor.copy(null), this.lastInputTimestamp);
    }

    synchronized void publishSceneTick(
            int sceneId, int timestamp, Map<Integer, ? extends Actor> actors, int capturedInputTimestamp)
            throws IOException {
        if (!this.running || !this.sceneReady || this.currentSceneId != sceneId
                || timestamp <= this.sceneTimestamp) {
            return;
        }

        // The installed client retains a list of scene records and resolves
        // every delta by its exact reference. Deltas therefore form an ordered
        // chain at the scene cadence; InputEnqueueEvent.acknowledge reports how
        // far the client consumed that chain, but must not gate the next tick.
        // Gating here made a 100 ms client ACK round trip slower than the clock
        // and produced an ever-growing multi-second backlog in Training Hall.
        int reference = this.sceneTimestamp;
        int deliveryTimestamp = timestamp;
        boolean immediateState = hasImmediateActorChange(actors);
        boolean periodicState = (long)deliveryTimestamp - this.lastActorStateTimestamp
            >= this.movementConfig.actorStateIntervalMs;
        boolean deliverActorState = immediateState || periodicState;
        Map<Integer, Actor> deliveryActors = new LinkedHashMap<>();
        if (deliverActorState) {
            deliveryActors.putAll(prepareActorsForDelivery(actors, deliveryTimestamp));
        } else {
            copyActors(this.lastSentActors, deliveryActors);
        }
        List<Actor> added = new ArrayList<>();
        List<ActorDelta> updated = new ArrayList<>();
        List<Integer> removed = new ArrayList<>();

        for (Map.Entry<Integer, Actor> entry : deliveryActors.entrySet()) {
            Actor current = entry.getValue();
            Actor previous = this.lastSentActors.get(entry.getKey());
            if (previous == null) {
                added.add((Actor)current.copy(null));
            } else if (!previous.equals(current)) {
                updated.add(new ActorDelta(previous, current));
            }
        }
        for (Integer actorId : this.lastSentActors.keySet()) {
            if (!deliveryActors.containsKey(actorId)) {
                removed.add(actorId);
            }
        }

        if (deliverActorState && capturedInputTimestamp != Integer.MIN_VALUE) {
            // Input can arrive between the shared snapshot and its publication.
            // Only acknowledge input represented by the delivered actor state:
            // otherwise the client discards frames needed for reconciliation.
            this.lastAcknowledgedInputTimestamp = capturedInputTimestamp;
        }
        int acknowledgedInputTimestamp = this.lastAcknowledgedInputTimestamp;
        short elapsed = (short)Math.max(1L,
            Math.min((long)deliveryTimestamp - reference, Short.MAX_VALUE));
        SceneDeltaEvent update = new SceneDeltaEvent(
            this.clientOid,
            this.currentPlaceOid,
            acknowledgedInputTimestamp,
            (short)0,
            reference,
            deliveryTimestamp,
            elapsed,
            added.isEmpty() ? null : added.toArray(new Actor[added.size()]),
            updated.isEmpty() ? null : updated.toArray(new ActorDelta[updated.size()]),
            removed.isEmpty() ? null : toIntArray(removed),
            null);
        sendMessage(new EventNotification(update));

        this.deltaTicks++;
        if (deliverActorState) {
            this.actorStateTicks++;
            if (immediateState) {
                this.immediateActorStateTicks++;
            }
        } else {
            this.timingOnlyTicks++;
        }
        if (this.deltaTicks >= 100) {
            System.out.println("[ClientSession-" + connectionId
                + "][NETWORK][STATE] scene=" + sceneId
                + " deltaTicks=" + this.deltaTicks
                + " actorStateTicks=" + this.actorStateTicks
                + " immediateTicks=" + this.immediateActorStateTicks
                + " timingOnlyTicks=" + this.timingOnlyTicks
                + " clientAckLagMs="
                + Math.max(0L,
                    (long)deliveryTimestamp - this.lastClientSceneAcknowledge)
                + " outboundQueue=" + this.outbound.size() + "/512");
            this.deltaTicks = 0;
            this.actorStateTicks = 0;
            this.immediateActorStateTicks = 0;
            this.timingOnlyTicks = 0;
        }

        long ackLagMs = Math.max(
            0L, (long)deliveryTimestamp - this.lastClientSceneAcknowledge);
        long ackNow = System.nanoTime();
        boolean recovered = this.previousAckLagMs >= 1_000L && ackLagMs < 500L;
        boolean reportLag = ackLagMs >= 1_000L
            && ackNow - this.lastAckLogNanos >= TimeUnit.SECONDS.toNanos(2L);
        if (recovered || reportLag) {
            int sceneIntervalMs = this.sceneNetwork.intervalMs(sceneId);
            System.out.println("[ClientSession-" + connectionId
                + "][NETWORK][ACK] " + (recovered ? "recovered" : "lagging")
                + " scene=" + sceneId
                + " clientAck=" + this.lastClientSceneAcknowledge
                + " lastSent=" + deliveryTimestamp
                + " ackLagMs=" + ackLagMs
                + " chainDepth="
                + ((ackLagMs + sceneIntervalMs - 1L) / sceneIntervalMs)
                + " sceneIntervalMs=" + sceneIntervalMs
                + " actorStateIntervalMs=" + this.movementConfig.actorStateIntervalMs
                + " outboundQueue=" + this.outbound.size() + "/512");
            this.lastAckLogNanos = ackNow;
        }
        this.previousAckLagMs = ackLagMs;

        Knight previousOwnActor = this.authoritativeActor == null
            ? null : (Knight)this.lastSentActors.get(this.authoritativeActor.vw());
        if (deliverActorState && previousOwnActor != null) {
            logDeliveredCombatState(
                previousOwnActor, reference, acknowledgedInputTimestamp,
                deliveryTimestamp,
                !previousOwnActor.equals(this.authoritativeActor),
                this.pendingInputPackets);
        }
        this.sceneTimestamp = deliveryTimestamp;
        this.lastSentActors.clear();
        copyActors(deliveryActors, this.lastSentActors);
        if (deliverActorState) {
            this.lastActorStateTimestamp = deliveryTimestamp;
            this.pendingInputPackets = 0;
        }
    }

    synchronized void publishSceneOccupants(int sceneId) throws IOException {
        if (!this.running || !this.sceneReady || this.currentSceneId != sceneId) {
            return;
        }
        AttributeChangedEvent occupants = new AttributeChangedEvent(
            this.currentPlaceOid, "occupants",
            this.sceneMgr.snapshotOccupants(sceneId));
        AttributeChangedEvent occupantInfo = new AttributeChangedEvent(
            this.currentPlaceOid, "occupantInfo",
            this.sceneMgr.snapshotOccupantInfo(sceneId));
        sendMessage(new EventNotification(occupants));
        sendMessage(new EventNotification(occupantInfo));
    }

    private static void copyActors(
            Map<Integer, ? extends Actor> source, Map<Integer, Actor> destination) {
        for (Map.Entry<Integer, ? extends Actor> entry : source.entrySet()) {
            destination.put(entry.getKey(), (Actor)entry.getValue().copy(null));
        }
    }

    /**
     * State that changes the controller or animation is delivered on the next
     * scene tick. Pure transform reconciliation may wait for the configured
     * snapshot boundary while the local pawn prediction stays smooth.
     */
    private boolean hasImmediateActorChange(Map<Integer, ? extends Actor> actors) {
        if (!this.lastSentActors.keySet().equals(actors.keySet())) {
            return true;
        }
        for (Map.Entry<Integer, ? extends Actor> entry : actors.entrySet()) {
            Actor previous = this.lastSentActors.get(entry.getKey());
            Actor current = entry.getValue();
            if (previous == null
                    || previous.getClass() != current.getClass()
                    || (previous instanceof Active && current instanceof Active
                        && (((Active)previous).adX() != ((Active)current).adX()
                            || ((Active)previous).adY() != ((Active)current).adY()))
                    || previous.bR(2) != current.bR(2)
                    || !Objects.equals(previous.Mx(), current.Mx())) {
                return true;
            }
            if (previous instanceof Dungeoneer && current instanceof Dungeoneer
                    && !Objects.equals(
                        currentWeaponName((Dungeoneer)previous),
                        currentWeaponName((Dungeoneer)current))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Keeps actor lifecycle timestamps inside the recipient's bounded scene
     * timeline. State is current, but a newly delivered activity must not be
     * stamped several seconds in the future merely because another client (or
     * the global scene) advanced while this recipient was loading.
     */
    private Map<Integer, Actor> prepareActorsForDelivery(
            Map<Integer, ? extends Actor> actors, int deliveryTimestamp) {
        Map<Integer, Actor> prepared = new LinkedHashMap<>();
        for (Map.Entry<Integer, ? extends Actor> entry : actors.entrySet()) {
            Actor current = entry.getValue();
            Actor adjusted = (Actor)current.copy(null);
            Actor previous = this.lastSentActors.get(entry.getKey());

            if (adjusted instanceof Active && ((Active)adjusted).adY() > deliveryTimestamp) {
                Active active = (Active)adjusted;
                int activityStarted = deliveryTimestamp;
                if (previous instanceof Active && ((Active)previous).adX() == active.adX()) {
                    activityStarted = Math.min(((Active)previous).adY(), deliveryTimestamp);
                }
                active.aU(active.adX(), activityStarted);
            }
            if (actorCreatedField != null && adjusted.aea() > deliveryTimestamp) {
                try {
                    int created = previous == null
                        ? deliveryTimestamp : Math.min(previous.aea(), deliveryTimestamp);
                    actorCreatedField.setInt(adjusted, created);
                } catch (IllegalAccessException error) {
                    throw new IllegalStateException(
                        "Cannot rebase actor creation timestamp", error);
                }
            }
            prepared.put(entry.getKey(), adjusted);
        }
        return prepared;
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int ii = 0; ii < values.size(); ii++) {
            result[ii] = values.get(ii).intValue();
        }
        return result;
    }

    private boolean shouldLogInputPacket() {
        return this.movementConfig.logEveryPackets > 0
            && (this.inputPacketCount == 1
                || this.inputPacketCount % this.movementConfig.logEveryPackets == 0);
    }

    private void maybeTriggerPortal(float previousX, float previousY) throws IOException {
        if (this.portalTransitionPending || this.authoritativeActor == null) {
            return;
        }
        int destination = SceneManager.getPortalDestination(
            this.currentSceneId,
            previousX,
            previousY,
            this.authoritativeActor.tz().x,
            this.authoritativeActor.tz().y);
        if (destination == -1) {
            return;
        }
        this.portalTransitionPending = true;
        short zoneReceiverId = findReceiverId(ZONE_RECEIVER_CODE, LEGACY_ZONE_RECEIVER_ID);
        InvocationNotificationEvent zoneNotification = new InvocationNotificationEvent(
            this.clientOid,
            zoneReceiverId,
            1,
            new Object[]{ Integer.valueOf(1), Integer.valueOf(destination) });
        sendMessage(new EventNotification(zoneNotification));
        System.out.println("[ClientSession-" + connectionId + "][PORTAL] trigger"
            + " fromScene=" + this.currentSceneId
            + " toScene=" + destination
            + " position=(" + this.authoritativeActor.tz().x
            + "," + this.authoritativeActor.tz().y + ")"
            + " receiverId=" + zoneReceiverId);
    }

    private synchronized void activateScene(int sceneId) {
        int placeOid = this.sceneMgr.getPlaceOid(sceneId);
        PlaceConfig config = this.sceneMgr.getSceneConfig(sceneId);
        if (placeOid == -1 || config == null) {
            throw new IllegalArgumentException("Unknown local sceneId=" + sceneId);
        }

        int previousSceneId = this.currentSceneId;
        if (sceneId != previousSceneId) {
            this.sceneReady = false;
            this.sceneNetwork.leave(previousSceneId, this);
        }
        this.currentSceneId = sceneId;
        this.currentPlaceOid = placeOid;
        this.currentPlaceConfig = config;
        if (sceneId == SceneManager.TRAINING_SCENE_ID) {
            this.currentSpawnX = SceneManager.TRAINING_SPAWN_X;
            this.currentSpawnY = SceneManager.TRAINING_SPAWN_Y;
        } else if (previousSceneId == SceneManager.TRAINING_SCENE_ID) {
            this.currentSpawnX = SceneManager.HAVEN_TRAINING_RETURN_X;
            this.currentSpawnY = SceneManager.HAVEN_TRAINING_RETURN_Y;
        } else {
            this.currentSpawnX = SceneManager.HAVEN_SPAWN_X;
            this.currentSpawnY = SceneManager.HAVEN_SPAWN_Y;
        }

        if (this.playerObject != null) {
            this.sceneMgr.movePlayerToScene(this.playerObject, sceneId);
            if (sceneId != previousSceneId) {
                this.sceneNetwork.broadcastOccupants(previousSceneId);
                this.sceneNetwork.broadcastOccupants(sceneId);
            }
        }
        if (sceneId != previousSceneId) {
            this.sceneTimestamp = 0;
            this.lastClientSceneAcknowledge = 0;
            this.lastAckLogNanos = 0L;
            this.previousAckLagMs = 0L;
            this.authoritativeActor = null;
            this.lastSentActors.clear();
            this.lastInputTimestamp = Integer.MIN_VALUE;
            this.lastAcknowledgedInputTimestamp = 0;
            this.lastActorStateTimestamp = 0;
            this.inputTimelineSynchronized = false;
            this.inputStreamLogged = false;
            this.inputPacketCount = 0;
            this.rejectedInputFrames = 0;
            this.staleInputFrames = 0;
            this.pendingInputPackets = 0;
            this.deltaTicks = 0;
            this.actorStateTicks = 0;
            this.immediateActorStateTicks = 0;
            this.timingOnlyTicks = 0;
            this.portalTransitionPending = false;
            resetCombatState();
            System.out.println("[ClientSession-" + connectionId + "][WORLD] activated"
                + " sceneId=" + sceneId + " placeOid=" + placeOid
                + " config=" + config.getClass().getSimpleName()
                + " spawn=(" + this.currentSpawnX + "," + this.currentSpawnY + ")");
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private ZoneSummary createHavenZoneSummary() {
        ZoneSummary zone = new ZoneSummary();
        zone.zoneId = 1;
        zone.name = new Name("Haven");

        SceneSummary scene = new SceneSummary();
        scene.sceneId = SceneManager.DEFAULT_SCENE_ID;
        scene.name = "Haven Town Square";
        scene.neighbors = new int[]{ SceneManager.TRAINING_SCENE_ID };
        scene.neighborDirs = new int[]{ 0 };

        SceneSummary training = new SceneSummary();
        training.sceneId = SceneManager.TRAINING_SCENE_ID;
        training.name = "Advanced Training Hall";
        training.neighbors = new int[]{ SceneManager.DEFAULT_SCENE_ID };
        training.neighborDirs = new int[]{ 0 };
        zone.scenes = new SceneSummary[]{ scene, training };
        return zone;
    }

    private void sendDefaultListenerResponse(short reqId, InvocationMarshaller.ListenerMarshaller listener)
            throws IOException {
        Object[] responseArgs = (listener instanceof InvocationMarshaller.ResultMarshaller)
            ? new Object[]{ null }
            : new Object[0];
        InvocationResponseEvent response = new InvocationResponseEvent(
            this.clientOid, reqId, (byte)1, responseArgs);
        sendMessage(new EventNotification(response));
    }

    private short findReceiverId(String receiverCode, short fallback) {
        if (this.playerObject.receivers != null) {
            for (InvocationReceiver.Registration registration : this.playerObject.receivers) {
                if (receiverCode.equals(registration.receiverCode)) {
                    return registration.receiverId;
                }
            }
        }
        System.out.println("[ClientSession-" + connectionId + "][WORLD][WARNING] Receiver " + receiverCode
            + " was not registered; using legacy receiverId=" + fallback);
        return fallback;
    }

    private static Name findNameArgument(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof Name) {
                return (Name)arg;
            }
        }
        return new Name("unknown");
    }

    private static int intArgument(Object[] args, int index, int fallback) {
        return index < args.length && args[index] instanceof Number
            ? ((Number)args[index]).intValue() : fallback;
    }

    private static String readRequestedVersion(AuthRequest request) {
        if (authVersionField == null) {
            return "unknown";
        }
        try {
            Object value = authVersionField.get(request);
            return value == null ? "unknown" : value.toString();
        } catch (IllegalAccessException e) {
            return "unknown";
        }
    }

    private String advertisedHost() {
        try {
            java.net.SocketAddress address = this.channel.getLocalAddress();
            if (address instanceof java.net.InetSocketAddress) {
                java.net.InetAddress host =
                    ((java.net.InetSocketAddress)address).getAddress();
                if (host != null) {
                    return host.getHostAddress();
                }
            }
        } catch (IOException error) {
            System.out.println("[ClientSession-" + connectionId
                + "][NETWORK][WARNING] cannot resolve advertised host: "
                + error.getMessage());
        }
        return "localhost";
    }

    private void handlePing(PingRequest req) throws Exception {
        // The sender's pack stamp is transient. The client's readObject
        // records the server receive time in ym(), used to measure process delay.
        PongResponse pong = new PongResponse(req.ym(), req.xK());
        sendMessage(pong);
    }

    public int getSessionId() {
        return this.connectionId;
    }

    public String getUsername() {
        return (this.playerObject != null && this.playerObject.username != null)
            ? this.playerObject.username.toString()
            : "HeroKnight";
    }

    public java.net.SocketAddress getRemoteAddress() {
        try {
            return this.channel != null ? this.channel.getRemoteAddress() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public void close() {
        running = false;
        if (cleanedUp.compareAndSet(false, true)) {
            this.sceneReady = false;
            this.sceneNetwork.leave(this.currentSceneId, this);
            sceneMgr.removePlayer(playerObject);
            this.sceneNetwork.broadcastOccupants(this.currentSceneId);
            if (playerObject != null) {
                dobjMgr.unregister(playerObject.xL());
            }
            if (partyObject != null) {
                dobjMgr.unregister(partyObject.xL());
            }
            if (this.onClosed != null) {
                this.onClosed.accept(this);
            }
        }
        if (this.writerThread != null && this.writerThread != Thread.currentThread()) {
            this.writerThread.interrupt();
        }
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        } catch (Exception e) {}
    }
}
