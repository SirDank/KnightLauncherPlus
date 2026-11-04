package com.lucasluqui.launcher.server;

import java.io.InputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import com.threerings.crowd.chat.data.SpeakMarshaller;
import com.threerings.crowd.data.OccupantInfo;
import com.threerings.crowd.data.PlaceConfig;
import com.threerings.config.ConfigReference;
import com.threerings.export.BinaryImporter;
import com.threerings.math.Vector2f;
import com.threerings.opengl.model.config.ModelConfig;
import com.threerings.presents.dobj.DSet;
import com.threerings.presents.dobj.OidList;
import com.threerings.projectx.data.IconMapEntry;
import com.threerings.projectx.data.PlayerObject;
import com.threerings.projectx.data.ProjectXCodes;
import com.threerings.projectx.data.ProjectXSceneObject;
import com.threerings.projectx.data.ProjectXSceneMarshaller;
import com.threerings.projectx.data.ProjectXOccupantInfo;
import com.threerings.projectx.data.actor.Knight;
import com.threerings.projectx.dungeon.config.AttackConfig;
import com.threerings.projectx.dungeon.config.FireActionConfig;
import com.threerings.projectx.dungeon.config.DepthScaleConfig;
import com.threerings.projectx.item.config.LevelTableConfig;
import com.threerings.projectx.item.config.ItemPropertyConfig;
import com.threerings.projectx.dungeon.data.TrainingSceneConfig;
import com.threerings.projectx.dungeon.data.actor.Dungeoneer;
import com.threerings.projectx.item.config.ItemConfig;
import com.threerings.projectx.item.data.Item;
import com.threerings.projectx.item.data.LevelItem;
import com.threerings.projectx.town.data.TownSceneMarshaller;
import com.threerings.projectx.town.data.TownSceneConfig;
import com.threerings.projectx.town.data.TownSceneObject;
import com.threerings.tudey.data.TudeySceneMarshaller;
import com.threerings.tudey.data.TudeySceneModel;
import com.threerings.tudey.config.ActorConfig;
import com.threerings.util.Name;

public class SceneManager {
    /** First starter color in the installed "player" ColorPository class. */
    public static final int DEFAULT_PLAYER_COLOR = 257;
    /** Kept as an alias because the first implementation exposed this name. */
    public static final int SCENE_OID = 500;
    public static final int DEFAULT_SCENE_ID = 1; // Haven Town Square
    public static final int TRAINING_PLACE_OID = 501;
    public static final int TRAINING_SCENE_ID = 294;
    /** Where the user puts scene files that the game install does not have (relative to the game folder). */
    public static final String SCENES_DIR = "KnightLauncher/server/scenes";
    /** Legacy single-player pawn id; live sessions use their unique body OID. */
    public static final int PLAYER_PAWN_ID = 1;
    public static final float HAVEN_SPAWN_X = 1.0f;
    public static final float HAVEN_SPAWN_Y = -30.5f;
    public static final float HAVEN_TRAINING_RETURN_X = -23.0f;
    public static final float HAVEN_TRAINING_RETURN_Y = 5.75f;
    public static final float TRAINING_SPAWN_X = 5.5f;
    public static final float TRAINING_SPAWN_Y = -12.5f;
    public static final String PLAYER_ACTOR_CONFIG = "Character/PC/Default";
    public static final String COMBAT_PLAYER_ACTOR_CONFIG = "Character/PC/Level";

    // Invocation codes are scoped to the scene object and must remain distinct.
    public static final int SPEAK_SERVICE_CODE = 20;
    public static final int TUDEY_SCENE_SERVICE_CODE = 21;
    public static final int PROJECTX_SCENE_SERVICE_CODE = 22;
    public static final int TOWN_SCENE_SERVICE_CODE = 23;

    private final TownSceneObject sceneObject;
    private final ProjectXSceneObject trainingSceneObject;
    private final TownSceneConfig havenConfig;
    private final TrainingSceneConfig trainingConfig;
    private final InstalledConfigManager configManager;
    private final List<String> weaponCatalog;
    private TudeySceneModel havenSceneModel;
    private TudeySceneModel trainingSceneModel;
    private final CombatWorld combatWorld;
    private final PartyManager parties;
    private final DungeonManager dungeons;
    private final DObjectManager dobjMgr;
    private final SceneCollision havenCollision, trainingCollision;
    /** Every scene the server hosts, by scene id and by the object id of its place object. */
    private final java.util.Map<Integer, SceneInstance> instancesBySceneId = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Integer, SceneInstance> instancesByPlaceOid = new java.util.concurrent.ConcurrentHashMap<>();
    private static Field bodyOidField;

    static {
        try {
            bodyOidField = OccupantInfo.class.getDeclaredField("bodyOid");
            bodyOidField.setAccessible(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public SceneManager(DObjectManager dobjMgr, MovementConfig movementConfig) {
        this.dobjMgr = dobjMgr;
        this.configManager = loadInstalledConfigs();
        this.parties = new PartyManager(dobjMgr, this, movementConfig.maxPartySize);
        this.dungeons = new DungeonManager(this, movementConfig);
        this.weaponCatalog = ItemCatalog.discoverWeapons(this.configManager);
        this.sceneObject = new TownSceneObject();
        this.sceneObject.cH(SCENE_OID);
        this.sceneObject.occupants = new OidList();
        this.sceneObject.occupantInfo = new DSet<>();
        this.sceneObject.mapIcons = new DSet<>();
        initializeSceneServices(this.sceneObject, SCENE_OID);

        this.trainingSceneObject = new ProjectXSceneObject();
        this.trainingSceneObject.cH(TRAINING_PLACE_OID);
        this.trainingSceneObject.occupants = new OidList();
        this.trainingSceneObject.occupantInfo = new DSet<>();
        this.trainingSceneObject.mapIcons = new DSet<>();
        initializeSceneServices(this.trainingSceneObject, TRAINING_PLACE_OID);

        importClientScenes(new File(ServerNet.GAME_DIR));

        TownSceneConfig resolvedConfig = new TownSceneConfig();

        // Load TudeySceneModel from scenes/1 (Haven Town Square) - disk or classpath
        try (InputStream input = openSceneStream(DEFAULT_SCENE_ID)) {
            if (input != null) {
                BinaryImporter importer = new BinaryImporter(input);
                this.havenSceneModel = (TudeySceneModel)importer.qe();
                if (this.havenSceneModel.adG() instanceof TownSceneConfig) {
                    resolvedConfig = (TownSceneConfig)this.havenSceneModel.adG();
                }
                System.out.println("[SceneManager] Loaded Haven map (Scene 1):"
                    + " name=" + this.havenSceneModel.name
                    + " entries=" + this.havenSceneModel.adK().size()
                    + " config=" + resolvedConfig.getClass().getSimpleName());
            } else {
                System.err.println("[SceneManager][WARN] Haven scene 1 not found on disk or classpath");
            }
        } catch (Exception e) {
            System.err.println("[SceneManager] Failed to load Haven scene 1: " + e.getMessage());
        }
        this.havenConfig = resolvedConfig;

        TrainingSceneConfig resolvedTrainingConfig = new TrainingSceneConfig();
        try (InputStream input = openSceneStream(TRAINING_SCENE_ID)) {
            if (input != null) {
                BinaryImporter importer = new BinaryImporter(input);
                this.trainingSceneModel = (TudeySceneModel)importer.qe();
                if (this.trainingSceneModel.adG() instanceof TrainingSceneConfig) {
                    resolvedTrainingConfig = (TrainingSceneConfig)this.trainingSceneModel.adG();
                }
                System.out.println("[SceneManager] Loaded Training Hall (Scene "
                    + TRAINING_SCENE_ID + "): name=" + this.trainingSceneModel.name
                    + " entries=" + this.trainingSceneModel.adK().size()
                    + " config=" + resolvedTrainingConfig.getClass().getSimpleName());
            } else {
                System.err.println("[SceneManager][WARN] Training Hall scene " + TRAINING_SCENE_ID + " not found");
            }
        } catch (Exception e) {
            System.err.println("[SceneManager] Failed to load Training Hall: " + e.getMessage());
        }
        this.trainingConfig = resolvedTrainingConfig;
        if (this.havenSceneModel == null || this.trainingSceneModel == null) {
            throw new IllegalStateException("Spiral Knights scene files " + DEFAULT_SCENE_ID + " and "
                + TRAINING_SCENE_ID + " were not found. Start Spiral Knights, log in "
                + "and visit Haven and the Training Hall once (the game saves the scenes it receives), then start the server "
                + "again: it copies them from the game automatically. Or put files named " + DEFAULT_SCENE_ID + " and "
                + TRAINING_SCENE_ID + " in " + ServerNet.GAME_DIR + java.io.File.separator + SCENES_DIR + ".");
        }
        this.havenSceneModel.init(this.configManager);
        this.trainingSceneModel.init(this.configManager);
        this.combatWorld = new CombatWorld(this.configManager, this.trainingSceneModel, movementConfig.combatBurstIntervalMs);
        this.havenCollision=new SceneCollision(this.havenSceneModel,null);
        this.trainingCollision=new SceneCollision(this.trainingSceneModel,this.combatWorld);

        register(new SceneInstance(DEFAULT_SCENE_ID, SCENE_OID, this.sceneObject, this.havenSceneModel,
            this.havenConfig, this.havenCollision, null));
        register(new SceneInstance(TRAINING_SCENE_ID, TRAINING_PLACE_OID, this.trainingSceneObject,
            this.trainingSceneModel, this.trainingConfig, this.trainingCollision, this.combatWorld));
        dobjMgr.register(this.sceneObject);
        dobjMgr.register(this.trainingSceneObject);
        System.out.println("[SceneManager] Initialized Haven Town Scene Object (OID=" + SCENE_OID + ", SceneID=" + DEFAULT_SCENE_ID + ")");
        System.out.println("[SceneManager] Initialized Training Hall Scene Object (OID="
            + TRAINING_PLACE_OID + ", SceneID=" + TRAINING_SCENE_ID + ")");
    }

    private void initializeSceneServices(ProjectXSceneObject scene, int placeOid) {
        SpeakMarshaller speakService = new SpeakMarshaller();
        speakService.a(placeOid, SPEAK_SERVICE_CODE, null);
        scene.speakService = speakService;

        TudeySceneMarshaller tudeySceneService = new TudeySceneMarshaller();
        tudeySceneService.a(placeOid, TUDEY_SCENE_SERVICE_CODE, null);
        scene.tudeySceneService = tudeySceneService;

        ProjectXSceneMarshaller projectXSceneService = new ProjectXSceneMarshaller();
        projectXSceneService.a(placeOid, PROJECTX_SCENE_SERVICE_CODE, null);
        scene.projectXSceneService = projectXSceneService;

        if (scene instanceof TownSceneObject) {
            TownSceneMarshaller townSceneService = new TownSceneMarshaller();
            townSceneService.a(placeOid, TOWN_SCENE_SERVICE_CODE, null);
            ((TownSceneObject)scene).townSceneService = townSceneService;
        }

        System.out.println("[SceneManager] Bound scene services oid=" + placeOid
            + ": speak=" + SPEAK_SERVICE_CODE
            + ", tudey=" + TUDEY_SCENE_SERVICE_CODE
            + ", projectx=" + PROJECTX_SCENE_SERVICE_CODE
            + (scene instanceof TownSceneObject ? ", town=" + TOWN_SCENE_SERVICE_CODE : ""));
    }

    public TownSceneObject getSceneObject() {
        return this.sceneObject;
    }

    public TownSceneConfig getHavenConfig() {
        return this.havenConfig;
    }

    public PlaceConfig getSceneConfig() {
        return this.havenConfig;
    }

    public TudeySceneModel getHavenSceneModel() {
        return this.havenSceneModel;
    }

    /** The scene with this id, or null. */
    SceneInstance sceneInstance(int sceneId) {
        return this.instancesBySceneId.get(sceneId);
    }

    /** The Training Hall's map: the template the generated dungeon levels are made from. */
    TudeySceneModel trainingTemplate() {
        return this.trainingSceneModel;
    }

    /** Adds a scene made while the server runs (a dungeon level) and makes its place object available to the game. */
    SceneInstance registerCombatScene(int sceneId, int placeOid, TudeySceneModel model, PlaceConfig config,
                                      CombatWorld combat, float spawnX, float spawnY) {
        ProjectXSceneObject object = new ProjectXSceneObject();
        object.cH(placeOid);
        object.occupants = new OidList();
        object.occupantInfo = new DSet<>();
        object.mapIcons = new DSet<>();
        initializeSceneServices(object, placeOid);
        SceneInstance instance = new SceneInstance(sceneId, placeOid, object, model, config,
            new SceneCollision(model, combat), combat, spawnX, spawnY);
        this.dobjMgr.register(object);
        register(instance);
        return instance;
    }

    /** Drops a scene made while the server runs. */
    void unregisterScene(int sceneId) {
        SceneInstance instance = this.instancesBySceneId.remove(sceneId);
        if (instance == null) return;
        this.instancesByPlaceOid.remove(instance.placeOid);
        this.dobjMgr.unregister(instance.placeOid);
    }

    private void register(SceneInstance instance) {
        this.instancesBySceneId.put(instance.sceneId, instance);
        this.instancesByPlaceOid.put(instance.placeOid, instance);
    }

    public ProjectXSceneObject getSceneObjectByPlaceOid(int placeOid) {
        SceneInstance instance = this.instancesByPlaceOid.get(placeOid);
        return instance == null ? null : instance.object;
    }

    public TudeySceneModel getSceneModel(int sceneId) {
        SceneInstance instance = this.instancesBySceneId.get(sceneId);
        return instance == null ? null : instance.model;
    }

    public PlaceConfig getSceneConfig(int sceneId) {
        SceneInstance instance = this.instancesBySceneId.get(sceneId);
        return instance == null ? null : instance.config;
    }

    public int getPlaceOid(int sceneId) {
        SceneInstance instance = this.instancesBySceneId.get(sceneId);
        return instance == null ? -1 : instance.placeOid;
    }

    public int getSceneIdForPlaceOid(int placeOid) {
        SceneInstance instance = this.instancesByPlaceOid.get(placeOid);
        return instance == null ? -1 : instance.sceneId;
    }

    /** Whether the scene is one with fighting (the Training Hall, later the dungeons). */
    public boolean isCombatScene(int sceneId) {
        SceneInstance instance = this.instancesBySceneId.get(sceneId);
        return instance != null && instance.combat != null;
    }

    /** The fighting of a scene; null for a scene without combat. */
    public CombatWorld getCombatWorld(int sceneId) {
        SceneInstance instance = this.instancesBySceneId.get(sceneId);
        return instance == null ? null : instance.combat;
    }

    public static int getPortalDestination(int sceneId, float x, float y) {
        return getPortalDestination(sceneId, x, y, x, y);
    }

    public static int getPortalDestination(
            int sceneId, float fromX, float fromY, float toX, float toY) {
        // Bounds come from the AreaEntry vertices embedded in scenes/1 and
        // scenes/294. Slight padding prevents a fast frame from skipping the
        // thin trigger volume.
        if (sceneId == DEFAULT_SCENE_ID
                && segmentIntersectsBounds(
                    fromX, fromY, toX, toY, -24.5f, -21.5f, 10.5f, 13.5f)) {
            return TRAINING_SCENE_ID;
        }
        if (sceneId == TRAINING_SCENE_ID
                && segmentIntersectsBounds(
                    fromX, fromY, toX, toY, 2.0f, 9.0f, -23.5f, -20.0f)) {
            return DEFAULT_SCENE_ID;
        }
        return -1;
    }

    private static boolean segmentIntersectsBounds(float fromX, float fromY,
            float toX, float toY, float minX, float maxX, float minY, float maxY) {
        float deltaX = toX - fromX;
        float deltaY = toY - fromY;
        float entry = 0.0f;
        float exit = 1.0f;
        float[] p = new float[]{ -deltaX, deltaX, -deltaY, deltaY };
        float[] q = new float[]{
            fromX - minX, maxX - fromX, fromY - minY, maxY - fromY };
        for (int index = 0; index < p.length; index++) {
            if (Math.abs(p[index]) < 0.000001f) {
                if (q[index] < 0.0f) {
                    return false;
                }
                continue;
            }
            float ratio = q[index] / p[index];
            if (p[index] < 0.0f) {
                entry = Math.max(entry, ratio);
            } else {
                exit = Math.min(exit, ratio);
            }
            if (entry > exit) {
                return false;
            }
        }
        return true;
    }

    public ConfigReference<ModelConfig> createPlayerModel(PlayerObject player) {
        return createPlayerModel(player, -1);
    }

    public ConfigReference<ModelConfig> createPlayerModel(
            PlayerObject player, int weaponIndex) {
        // Reproduce the original PlayerObject model composition without a
        // full game service container: animated base + face + height + the
        // visible equipped armor, helm and shield.
        ConfigReference<ModelConfig> modelConfig = new ConfigReference<ModelConfig>(
            "character/pc/model_level.dat",
            "Colorization", Integer.valueOf(player.color),
            new Object[]{ "Height", player.height.value });
        player.eye.updatePlayerModelArgs(modelConfig.kW());
        for (int slot : new int[]{ 0, 1, 2 }) {
            // DungeoneerSprite owns the live shield attachment. Sending a
            // second shield through the base PC model duplicates it.
            if (slot==2 && weaponIndex>=0) continue;
            long itemId = player.equipment[slot];
            Item equipped = findItem(player, itemId);
            if (equipped == null) {
                continue;
            }
            ItemConfig.Original itemConfig = resolveItemConfig(equipped);
            if (itemConfig != null) {
                itemConfig.a(player.color, modelConfig.kW());
            } else {
                System.out.println("[SceneManager][CHARACTER][WARNING] Missing item config for "
                    + equipped.Mx().getName());
            }
        }
        // The combat sprite also owns the weapon drawn from Dungeoneer.Ri().
        modelConfig.kW().f("Shield Away", weaponIndex < 0 ? "True" : "False");
        return modelConfig;
    }

    public Knight createPlayerKnight(PlayerObject player, int createdTimestamp,
            int sceneId, float spawnX, float spawnY) {
        boolean combatScene = isCombatScene(sceneId);
        int pawnId = getPlayerPawnId(player);
        ConfigReference<ModelConfig> modelConfig = createPlayerModel(
            player, combatScene ? 0 : -1);
        ConfigReference<ActorConfig> actorConfig =
            player.a(combatScene ? COMBAT_PLAYER_ACTOR_CONFIG : PLAYER_ACTOR_CONFIG,
                modelConfig, new Object[0]);
        if (!combatScene) {
            Knight knight=new Knight(actorConfig, pawnId, createdTimestamp,
                new Vector2f(spawnX, spawnY), 0.0f);
            knight.init(this.configManager);
            return knight;
        }

        Dungeoneer dungeoneer = new Dungeoneer(
            actorConfig, pawnId, createdTimestamp,
            new Vector2f(spawnX, spawnY), 0.0f);
        LevelItem weapon = getEquippedWeapon(player, 0);
        if (weapon != null) {
            dungeoneer.c(weapon.VQ());
        }
        Item shield = findItem(player, player.equipment[2]);
        if (shield != null) {
            dungeoneer.fe(shield.Mx().getName());
        }
        dungeoneer.init(this.configManager);
        return dungeoneer;
    }

    public static int getPlayerPawnId(PlayerObject player) {
        return player == null ? PLAYER_PAWN_ID : player.xL();
    }

    /**
     * Copies the scenes the game client has cached ({@code <game>/scenes/<id>}, written when you visit
     * them online) into {@link #SCENES_DIR}, so the server keeps its own copy of the exact scenes the
     * official servers sent. Scenes already there are left alone.
     *
     * @return How many scenes were copied.
     */
    public static int importClientScenes(File gameDir) {
        int copied = 0;
        for (int sceneId : new int[]{DEFAULT_SCENE_ID, TRAINING_SCENE_ID}) {
            File target = new File(gameDir, SCENES_DIR + "/" + sceneId);
            if (target.isFile()) continue;
            for (String dir : new String[]{"scenes/", "rsrc/scenes/"}) {
                File source = new File(gameDir, dir + sceneId);
                if (!source.isFile()) continue;
                try {
                    java.nio.file.Files.createDirectories(target.getParentFile().toPath());
                    java.nio.file.Files.copy(source.toPath(), target.toPath());
                    System.out.println("[SceneManager] Copied scene " + sceneId + " from the game's cache (" + source.getPath() + ")");
                    copied++;
                } catch (java.io.IOException e) {
                    System.err.println("[SceneManager] Could not copy scene " + sceneId + ": " + e.getMessage());
                }
                break;
            }
        }
        return copied;
    }

    /**
     * A scene file (never bundled with the launcher), or null if there is none. The server process
     * runs in the game directory, so paths are relative to that. The client downloads most scenes
     * on demand, so a normal install may not have these: put them in {@link #SCENES_DIR}.
     */
    public static InputStream openSceneStream(int sceneId) {
        for (String dir : new String[]{SCENES_DIR + "/", "rsrc/scenes/", "scenes/"}) {
            File sceneFile = new File(ServerNet.GAME_DIR, dir + sceneId);
            if (sceneFile.isFile()) {
                try { return new java.io.FileInputStream(sceneFile); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    public static File findConfigFile(String configName) {
        return new File(ServerNet.GAME_DIR, "rsrc/config/" + configName);
    }

    private static InstalledConfigManager loadInstalledConfigs() {
        InstalledConfigManager manager = new InstalledConfigManager();
        try {
            int actors = manager.loadGroup(ActorConfig.class, findConfigFile("actor.dat"));
            int items = manager.loadGroup(ItemConfig.class, findConfigFile("item.dat"));
            int attacks = manager.loadGroup(AttackConfig.class, findConfigFile("attack.dat"));
            manager.loadGroup(FireActionConfig.class, findConfigFile("fire_action.dat"));
            manager.loadGroup(DepthScaleConfig.class, findConfigFile("depth_scale.dat"));
            manager.loadGroup(LevelTableConfig.class, findConfigFile("level_table.dat"));
            manager.loadGroup(ItemPropertyConfig.class, findConfigFile("item_property.dat"));
            manager.loadGroup(com.threerings.projectx.config.EmoteConfig.class, findConfigFile("emote.dat"));
            manager.loadGroup(com.threerings.tudey.config.TileConfig.class, findConfigFile("tile.dat"));
            manager.loadGroup(com.threerings.tudey.config.PlaceableConfig.class, findConfigFile("placeable.dat"));
            manager.loadGroup(com.threerings.tudey.config.PathConfig.class, findConfigFile("path.dat"));
            manager.loadGroup(com.threerings.projectx.dungeon.config.SpawnTableConfig.class, findConfigFile("spawn_table.dat"));
            System.out.println("[SceneManager] Loaded installed combat configs: actors="
                + actors + " items=" + items + " attacks=" + attacks);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load installed combat configs: " + e.getMessage(), e);
        }
        return manager;
    }

    DungeonManager dungeons() {
        return this.dungeons;
    }

    /** Whether the scene was made while the server runs (a dungeon level) rather than loaded from a file. */
    boolean isGenerated(int sceneId) {
        return this.dungeons.isDungeon(sceneId);
    }

    PartyManager parties() {
        return this.parties;
    }

    public InstalledConfigManager getConfigManager() {
        return this.configManager;
    }

    public List<String> getWeaponCatalog() { return this.weaponCatalog; }

    public CombatWorld getCombatWorld() {
        return this.combatWorld;
    }

    public SceneCollision getCollision(int sceneId) {
        SceneInstance instance = this.instancesBySceneId.get(sceneId);
        return instance == null ? this.havenCollision : instance.collision;
    }

    public LevelItem getEquippedWeapon(PlayerObject player, int weaponIndex) {
        if (player == null || player.equipment == null || weaponIndex < 0
                || weaponIndex >= player.weaponSlots) {
            return null;
        }
        Item item = findItem(player, player.equipment[6 + weaponIndex]);
        return item instanceof LevelItem ? (LevelItem)item : null;
    }

    public ItemConfig.Weapon resolveWeaponConfig(LevelItem weapon) {
        ItemConfig.Original original = resolveItemConfig(weapon);
        return original instanceof ItemConfig.Weapon ? (ItemConfig.Weapon)original : null;
    }

    public ItemConfig.Original resolveItemConfig(Item item) {
        if (item == null || item.Mx() == null) {
            return null;
        }
        ItemConfig config = this.configManager.a(ItemConfig.class, item.Mx());
        return config == null ? null : config.w(this.configManager);
    }

    public AttackConfig.Original resolveAttack(ItemConfig.Weapon weapon, int chainIndex) {
        if (weapon == null || weapon.attacks == null || weapon.attacks.length == 0) {
            return null;
        }
        int index = Math.max(0, Math.min(chainIndex, weapon.attacks.length - 1));
        return AttackConfig.b(this.configManager, weapon.attacks[index].ref);
    }

    public void equipCombatWeapon(Dungeoneer actor, PlayerObject player, int weaponIndex) {
        LevelItem weapon = getEquippedWeapon(player, weaponIndex);
        actor.c(weapon == null ? null : weapon.VQ());
        actor.setConfig(player.a(COMBAT_PLAYER_ACTOR_CONFIG,
            createPlayerModel(player, weaponIndex), new Object[0]));
        actor.init(this.configManager);
    }

    public void refreshCombatEquipment(Dungeoneer actor, PlayerObject player, int weaponIndex,int timestamp) {
        Item shield=findItem(player,player.equipment[2]);
        actor.fe(shield==null?null:shield.Mx().getName());
        equipCombatWeapon(actor,player,weaponIndex);
        // Cancel an activity whose weapon/shield has just been removed.
        actor.aU(0,Math.max(timestamp,actor.adY()+1));
    }

    public void moveEquipment(PlayerObject player,long itemId,
            com.threerings.projectx.item.data.ItemCodes.Location location,int slot) {
        Item item=findItem(player,itemId);
        if (item==null) throw new IllegalArgumentException("Unknown inventory item");
        if (location==com.threerings.projectx.item.data.ItemCodes.Location.EQUIPMENT) {
            ItemConfig.Original config=resolveItemConfig(item);
            int[] slots=config==null?null:config.getGroup().getSlots();
            boolean valid=false;
            if(slots!=null) for(int allowed:slots) if(allowed==slot) valid=true;
            if(!valid || slot<0 || slot>=player.equipment.length
                    || (slot>=6 && slot<=9 && slot-6>=player.weaponSlots)
                    || (slot>=10 && slot<=11 && slot-10>=player.trinketSlots))
                throw new IllegalArgumentException("Invalid equipment slot");
        } else if (location!=com.threerings.projectx.item.data.ItemCodes.Location.ARSENAL
                && location!=com.threerings.projectx.item.data.ItemCodes.Location.QUICKBAR)
            throw new IllegalArgumentException("Unsupported inventory location");
        if(location==com.threerings.projectx.item.data.ItemCodes.Location.QUICKBAR
                && (slot<0 || slot>=player.quickbar.length))
            throw new IllegalArgumentException("Invalid quickbar slot");
        long[] equipment=player.equipment.clone(),quickbar=player.quickbar.clone();
        for(int i=0;i<equipment.length;i++) if(equipment[i]==itemId) equipment[i]=0;
        for(int i=0;i<quickbar.length;i++) if(quickbar[i]==itemId) quickbar[i]=0;
        if(location==com.threerings.projectx.item.data.ItemCodes.Location.EQUIPMENT) equipment[slot]=itemId;
        if(location==com.threerings.projectx.item.data.ItemCodes.Location.QUICKBAR) quickbar[slot]=itemId;
        player.equipment=equipment; player.quickbar=quickbar;
    }

    private static Item findItem(PlayerObject player, long itemId) {
        if (itemId == 0L || player.items == null) {
            return null;
        }
        for (Item item : player.items) {
            if (item != null && item.VD() == itemId) {
                return item;
            }
        }
        return null;
    }

    public synchronized void addPlayer(PlayerObject po) {
        movePlayerToScene(po, DEFAULT_SCENE_ID);
    }

    public synchronized void movePlayerToScene(PlayerObject po, int sceneId) {
        for (SceneInstance instance : this.instancesBySceneId.values()) removePlayerFrom(instance.object, po);
        ProjectXSceneObject target = getSceneObjectByPlaceOid(getPlaceOid(sceneId));
        if (target == null) {
            throw new IllegalArgumentException("Unknown sceneId=" + sceneId);
        }
        addPlayerTo(target, po);
        System.out.println("[SceneManager] Player moved to sceneId=" + sceneId
            + " placeOid=" + target.xL() + ": " + po.knight
            + " (bodyOid=" + po.xL() + ")");
    }

    private static void addPlayerTo(ProjectXSceneObject scene, PlayerObject po) {
        int bodyOid = po.xL();
        if (!scene.occupants.aH(bodyOid)) {
            scene.occupants.aG(bodyOid);
        }

        ProjectXOccupantInfo occ = new ProjectXOccupantInfo(po);
        occ.pawnId = getPlayerPawnId(po);
        if (bodyOidField != null) {
            try {
                bodyOidField.set(occ, Integer.valueOf(bodyOid));
            } catch (Exception ignored) {}
        }

        List<OccupantInfo> list = new ArrayList<>();
        for (OccupantInfo existing : scene.occupantInfo) {
            if (!existing.username.equals(occ.username)) {
                list.add(existing);
            }
        }
        list.add(occ);
        scene.occupantInfo = new DSet<>(list);
    }

    public synchronized void removePlayer(PlayerObject po) {
        if (po == null) {
            return;
        }

        for (SceneInstance instance : this.instancesBySceneId.values()) removePlayerFrom(instance.object, po);
        System.out.println("[SceneManager] Player removed from all local scenes: bodyOid="
            + po.xL());
    }

    public synchronized OidList snapshotOccupants(int sceneId) {
        ProjectXSceneObject scene = getSceneObjectByPlaceOid(getPlaceOid(sceneId));
        OidList copy = new OidList();
        if (scene != null && scene.occupants != null) {
            for (Integer oid : scene.occupants) {
                copy.aG(oid.intValue());
            }
        }
        return copy;
    }

    public synchronized DSet<OccupantInfo> snapshotOccupantInfo(int sceneId) {
        ProjectXSceneObject scene = getSceneObjectByPlaceOid(getPlaceOid(sceneId));
        return scene == null || scene.occupantInfo == null
            ? new DSet<>() : new DSet<>(scene.occupantInfo);
    }

    private static void removePlayerFrom(ProjectXSceneObject scene, PlayerObject po) {
        int bodyOid = po.xL();
        OidList occupants = new OidList();
        for (Integer existingOid : scene.occupants) {
            if (existingOid.intValue() != bodyOid) {
                occupants.aG(existingOid.intValue());
            }
        }
        scene.occupants = occupants;

        List<OccupantInfo> occupantInfo = new ArrayList<>();
        for (OccupantInfo existing : scene.occupantInfo) {
            if (existing.bodyOid == null || existing.bodyOid.intValue() != bodyOid) {
                occupantInfo.add(existing);
            }
        }
        scene.occupantInfo = new DSet<>(occupantInfo);
    }
}
