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
        this(scene,player,pawn,timestamp,scene.getCombatWorld());
    }
    VanillaCombatController(SceneManager scene,PlayerObject player,Dungeoneer pawn,int timestamp,CombatWorld world) {
        super(new Context(scene,player,pawn,world),pawn,timestamp);
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
        // This client-only client Mobile has no assignment to _maxStep. The
        // native controller clamps every integration step to zero otherwise.
        // The native integrator subdivides at 60 Hz; its context now resolves
        // map/enemy penetration after every integration step.
        if (pawn.aeb() != 0) return;
        try {
            java.lang.reflect.Field step=Mobile.class.getDeclaredField("_maxStep");
            step.setAccessible(true); step.setFloat(pawn,Float.POSITIVE_INFINITY);
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
        Context(SceneManager scene,PlayerObject player,Dungeoneer pawn,CombatWorld world) {
            this.scene=scene; this.player=player; this.pawn=pawn;
            this.collision=new SceneCollision(scene.getSceneModel(SceneManager.TRAINING_SCENE_ID),world);
        }
        public ConfigManager getConfigManager() { return scene.getConfigManager(); }
        public PlayerObject CT() { return player; }
        public TudeySceneModel Sq() { return scene.getSceneModel(SceneManager.TRAINING_SCENE_ID); }
        public Actor cY(int id) { return id==pawn.vw()?pawn:scene.getCombatWorld().snapshot(0).get(id); }
        public Actor a(Dungeoneer actor,float distance,float angle,int mask) { return null; }
        public boolean a(Actor actor,Shape shape,Vector2f result) { return collision.penetration(actor,shape,result); }
        public boolean a(Actor actor,Shape shape) { return collision.intersects(actor,shape); }
        public boolean a(int mask,Shape shape) { return collision.intersects(mask,shape); }
        public int b(Actor actor,Shape shape) { return collision.directions(actor,shape); }
    }
}
