package com.lucasluqui.launcher.server;

import com.threerings.config.ConfigManager;
import com.threerings.config.ConfigReference;
import com.threerings.projectx.item.data.LevelItem;
import com.threerings.math.Vector2f;
import com.threerings.projectx.data.PlayerObject;
import com.threerings.projectx.dungeon.config.AttackConfig;
import com.threerings.projectx.dungeon.data.DungeonInputFrame;
import com.threerings.projectx.dungeon.data.actor.Dungeoneer;
import com.threerings.projectx.dungeon.util.i;
import com.threerings.tudey.data.TudeySceneModel;
import com.threerings.tudey.data.actor.Actor;
import com.threerings.tudey.shape.Shape;
import com.threerings.tudey.data.actor.Mobile;

/** Uses the game's own combat state machine instead of imitation timers. */
public final class VanillaCombatController extends i {
    private final SceneManager scene;
    private final Dungeoneer pawn;
    private final PlayerObject player;
    private final CombatWorld world;
    private ConfigReference<?> observedWeapon;
    private int weaponIndex;
    private int observedActivity = Integer.MIN_VALUE, observedStart = Integer.MIN_VALUE;
    public VanillaCombatController(SceneManager scene,PlayerObject player,Dungeoneer pawn,int timestamp) {
        this(scene,player,pawn,timestamp,SceneManager.TRAINING_SCENE_ID);
    }
    /** @param sceneId The scene the knight fights in: its map decides where the knight can walk. */
    VanillaCombatController(SceneManager scene,PlayerObject player,Dungeoneer pawn,int timestamp,int sceneId) {
        this(scene,player,pawn,timestamp,sceneId,scene.getCombatWorld(sceneId));
    }
    private VanillaCombatController(SceneManager scene,PlayerObject player,Dungeoneer pawn,int timestamp,int sceneId,CombatWorld world) {
        super(new Context(scene,player,pawn,sceneId,world),pawn,timestamp);
        this.scene=scene; this.pawn=pawn; this.player=player;
        this.world=world;
        initializeMovement(pawn);
        observedWeapon=pawn.Rl()==null?null:pawn.Rl().Mx();
        for(int index=0;index<4;index++) {
            LevelItem equipped=scene.getEquippedWeapon(player,index);
            if(equipped!=null && java.util.Objects.equals(equipped.Mx(),observedWeapon)) {
                weaponIndex=index;break;
            }
        }
    }
    private static void initializeMovement(Mobile pawn) {
        // The client never assigns _maxStep, so it stays 0 and the game's own movement code (Mobile step clamping)
        // cuts every step to nothing: the knight could attack but never walk. Check the field itself; an accessor
        // like aeb() that looked equivalent returned something else on this game build.
        try {
            java.lang.reflect.Field step=Mobile.class.getDeclaredField("_maxStep");
            step.setAccessible(true);
            if (step.getFloat(pawn)!=0f) return;
            step.setFloat(pawn,Float.POSITIVE_INFINITY);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    public void input(DungeonInputFrame frame) {
        a(frame);
        observe();
    }
    public void tick(int timestamp) {
        gV(timestamp);
        observe();
    }
    private void observe() {
        ConfigReference<?> weapon=pawn.Rl()==null?null:pawn.Rl().Mx();
        if (!java.util.Objects.equals(observedWeapon,weapon)) {
            for (int index=0;index<4;index++) {
                LevelItem equipped=scene.getEquippedWeapon(player,index);
                if (equipped!=null && equipped.Mx().equals(weapon)) {
                    scene.equipCombatWeapon(pawn,player,index);
                    weaponIndex=index; break;
                }
            }
            observedWeapon=weapon;
        }
        int activity=pawn.adX(), started=pawn.adY();
        if (activity==observedActivity && started==observedStart) return;
        observedActivity=activity; observedStart=started;
        AttackConfig.Original attack=null;
        if (activity>=292 && activity<=299) attack=scene.resolveAttack(pawn.Rm(),activity-292);
        else if (activity==290) attack=AttackConfig.b(scene.getConfigManager(),pawn.Rm().chargedAttack);
        else if (activity==289) attack=AttackConfig.b(scene.getConfigManager(),pawn.Rm().incompleteChargedAttack);
        else if (activity==287) attack=AttackConfig.b(scene.getConfigManager(),pawn.y(scene.getConfigManager()).bash);
        if (attack!=null) this.world.attack(pawn,attack,started,biD);
        System.out.println("[COMBAT] vanilla activity="+activity+" source="+pawn.vw()+" start="+started);
    }
    public int weaponIndex() { return weaponIndex; }
    private static final class Context implements i.c {
        final SceneManager scene; final PlayerObject player; final Dungeoneer pawn;
        final SceneCollision collision;
        final int sceneId; final CombatWorld world;
        Context(SceneManager scene,PlayerObject player,Dungeoneer pawn,int sceneId,CombatWorld world) {
            this.scene=scene; this.player=player; this.pawn=pawn; this.sceneId=sceneId; this.world=world;
            this.collision=new SceneCollision(scene.getSceneModel(sceneId),world);
        }
        public ConfigManager getConfigManager() { return scene.getConfigManager(); }
        public PlayerObject CT() { return player; }
        public TudeySceneModel Sq() { return scene.getSceneModel(sceneId); }
        public Actor cY(int id) { return id==pawn.vw()?pawn:world.snapshot(0).get(id); }
        public Actor a(Dungeoneer actor,float distance,float angle,int mask) { return null; }
        public boolean a(Actor actor,Shape shape,Vector2f result) { return collision.penetration(actor,shape,result); }
        public boolean a(Actor actor,Shape shape) { return collision.intersects(actor,shape); }
        public boolean a(int mask,Shape shape) { return collision.intersects(mask,shape); }
        public int b(Actor actor,Shape shape) { return collision.directions(actor,shape); }
    }
}
