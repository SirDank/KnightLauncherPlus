package com.lucasluqui.launcher.server;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import com.threerings.config.ConfigReference;
import com.threerings.math.*;
import com.threerings.projectx.dungeon.config.*;
import com.threerings.projectx.dungeon.data.actor.*;
import com.threerings.projectx.dungeon.util.DamageSummary;
import com.threerings.projectx.dungeon.util.DamageSummary.DamageType;
import com.threerings.projectx.item.config.ItemConfig;
import com.threerings.projectx.item.data.LevelItem;
import com.threerings.tudey.config.*;
import com.threerings.tudey.data.TudeySceneModel;
import com.threerings.tudey.data.actor.Actor;
import com.threerings.tudey.data.actor.Active;
import com.threerings.tudey.data.actor.Mobile;
import com.threerings.tudey.shape.Shape;
import com.threerings.tudey.shape.config.ShapeConfig;

/** Scene-owned combat, driven by client attack/fire/actor/depth catalogs. */
public final class CombatWorld {
    private final InstalledConfigManager configs;
    private final TudeySceneModel model;
    private final List<TrainingTarget> targets = new ArrayList<>();
    private final ConcurrentLinkedQueue<AttackRequest> incoming = new ConcurrentLinkedQueue<>();
    private final PriorityQueue<Fire> pending = new PriorityQueue<>(
        Comparator.comparingInt((Fire f) -> f.time).thenComparingLong(f -> f.order));
    private final Set<String> unsupported = new HashSet<>();
    private final Map<Integer,SpawnedActor> spawned = new LinkedHashMap<>();
    private int nextActorId;
    private long sequence;
    private static final Field HEALTH = field(Monster.class,"_healthPct");
    private static final Field CONDITIONS = field(Monster.class,"_conditions");

    public CombatWorld(InstalledConfigManager configs, TudeySceneModel model) {
        this.configs = configs;
        if (model == null) throw new IllegalStateException("Training map unavailable for combat");
        this.model=model;
        for (TudeySceneModel.Entry entry : model.adK()) {
            if (entry.getKey() instanceof Integer) nextActorId = Math.max(nextActorId,(Integer)entry.getKey());
        }
        for (TudeySceneModel.Entry entry : model.adK()) {
            if (!(entry instanceof TudeySceneModel.PlaceableEntry)) continue;
            TudeySceneModel.PlaceableEntry pad = (TudeySceneModel.PlaceableEntry)entry;
            if (!"Dynamic/Misc/Respawn Pad".equals(pad.placeable.getName())) continue;
            Object ref = pad.placeable.kW().get("Actor");
            if (!(ref instanceof ConfigReference)) continue;
            @SuppressWarnings("unchecked") ConfigReference<ActorConfig> actorRef = (ConfigReference<ActorConfig>)ref;
            if (!actorRef.getName().equals("Character/NPC/Advanced Training Gym/Training Cube")) continue;
            ActorConfig.Original original = configs.a(ActorConfig.class,actorRef).ai(configs);
            if (!(original instanceof DungeonActorConfig.Monster)) throw new IllegalStateException("Training cube type changed");
            DungeonActorConfig.Monster monster = (DungeonActorConfig.Monster)original;
            Vector3f position = pad.transform.tJ();
            Quaternion rotation = pad.transform.tK();
            float angle = (float)Math.atan2(2*(rotation.w*rotation.z+rotation.x*rotation.y),
                1-2*(rotation.y*rotation.y+rotation.z*rotation.z));
            Object delay = pad.placeable.kW().get("Delay");
            if (!(delay instanceof Number)) throw new IllegalStateException("Training respawn delay unavailable");
            int health = monster.health;
            if (monster.healthScale != null) health = Math.round(configs.a(DepthScaleConfig.class,monster.healthScale).b(configs,0));
            if (health <= 0) throw new IllegalStateException("Training cube health unavailable");
            targets.add(new TrainingTarget(actorRef,monster,new Vector2f(position.x,position.y),angle,health,((Number)delay).intValue()));
        }
        if (targets.isEmpty()) throw new IllegalStateException("Client map contains no training cubes");
        System.out.println("[COMBAT] loaded vanilla training targets="+targets.size()+" depth=0");
    }

    public void attack(Dungeoneer actor, AttackConfig.Original attack, int timestamp) {
        attack(actor,attack,timestamp,1f);
    }

    public void attack(Dungeoneer actor, AttackConfig.Original attack, int timestamp,float speed) {
        if (attack == null) throw new IllegalStateException("Missing installed attack");
        if (!(speed>0) || !Float.isFinite(speed)) throw new IllegalStateException("Invalid vanilla attack speed "+speed);
        incoming.add(new AttackRequest(actor.vw(),actor.Rl()==null?null:actor.Rl().VQ(),attack,timestamp,speed));
    }

    /** Called once per shared scene tick, before any recipient's snapshot. */
    public synchronized Map<Integer,Actor> step(int timestamp, Map<Integer,? extends Actor> players) {
        for (TrainingTarget target : targets) {
            if (target.actor == null && timestamp >= target.respawnAt) target.spawn(timestamp,++nextActorId);
        }
        AttackRequest request;
        while ((request=incoming.poll())!=null) {
            if (!players.containsKey(request.source)) continue;
            for (FireActionConfig.FireActionReference reference : request.attack.fireRefs) {
                if (reference == null || reference.ref == null) continue;
                FireActionConfig fire = configs.a(FireActionConfig.class,reference.ref);
                if (fire == null) throw new IllegalStateException("Missing fire action "+reference.ref);
                int time = request.time + request.attack.aC(request.speed)
                    + (int)(fire.delay/(request.attack.fireSpeed*request.speed));
                pending.add(new Fire(time,++sequence,request,fire,reference.ref));
            }
        }
        while (!pending.isEmpty() && pending.peek().time <= timestamp) {
            Fire fire = pending.poll();
            Actor source = players.get(fire.request.source);
            if (source instanceof Active && ((Active)source).adY()==fire.request.time)
                execute(fire.fire.action,fire.reference,source,fire.request.weapon,timestamp);
        }
        advanceSpawned(timestamp);
        Map<Integer,Actor> result = new LinkedHashMap<>();
        for (TrainingTarget target : targets) {
            if (target.actor == null) continue;
            if (target.health <= 0 && timestamp >= target.destroyAt) {
                target.actor = null;
                target.respawnAt = timestamp + target.delay;
            } else result.put(target.actor.vw(),(Actor)target.actor.copy(null));
        }
        for (SpawnedActor actor : spawned.values()) result.put(actor.actor.vw(),(Actor)actor.actor.copy(null));
        return result;
    }

    public synchronized Map<Integer,Actor> snapshot(int timestamp) {
        // Initial join has no inputs to consume and does not advance combat.
        Map<Integer,Actor> result = new LinkedHashMap<>();
        for (TrainingTarget target : targets) {
            if (target.actor == null && target.respawnAt == 0) target.spawn(timestamp,++nextActorId);
            if (target.actor != null) result.put(target.actor.vw(),(Actor)target.actor.copy(null));
        }
        for (SpawnedActor actor : spawned.values()) result.put(actor.actor.vw(),(Actor)actor.actor.copy(null));
        return result;
    }

    private void execute(ActionConfig action, ConfigReference<FireActionConfig> ref,
            Actor source, LevelItem weapon, int timestamp) {
        if (action == null) return;
        if (action instanceof ActionConfig.None) return;
        if (action instanceof ActionConfig.Compound) {
            for (ActionConfig child : ((ActionConfig.Compound)action).actions) execute(child,ref,source,weapon,timestamp);
        } else if (action instanceof DungeonActionConfig.DamageRegion) {
            DungeonActionConfig.DamageRegion damage = (DungeonActionConfig.DamageRegion)action;
            Object explicit = ref == null ? null : ref.kW().get("Shape");
            Shape shape;
            if (explicit instanceof ShapeConfig.TransformedShape || explicit instanceof ShapeConfig) {
                // Client derived fire references explicitly bind this shape.
                // Their legacy template parameter path may point to a Default
                // region after import; retain the actual reference geometry.
                Shape local = explicit instanceof ShapeConfig.TransformedShape
                    ? ((ShapeConfig.TransformedShape)explicit).acS() : ((ShapeConfig)explicit).acS();
                shape = local.d(new Transform2D(source.tz(),source.tA()));
            } else if (damage.region instanceof RegionConfig.Transformed) {
                shape = ((RegionConfig.Transformed)damage.region).shape.acS().d(new Transform2D(source.tz(),source.tA()));
            } else if (damage.region instanceof RegionConfig.Fixed) {
                shape = ((RegionConfig.Fixed)damage.region).shape.acS();
            } else if (damage.region instanceof RegionConfig.Default) {
                RegionConfig.Default region=(RegionConfig.Default)damage.region;
                if (!(region.location instanceof TargetConfig.Source)) {
                    unsupported("non-source default damage region"); return;
                }
                ShapeConfig geometry=source.adZ().shape;
                if (region.expansion!=0) {
                    if (!(geometry instanceof ShapeConfig.Circle) && !(geometry instanceof ShapeConfig.Point)) {
                        unsupported("non-circular damage expansion"); return;
                    }
                    ShapeConfig.Circle circle=new ShapeConfig.Circle();
                    circle.radius=region.expansion+(geometry instanceof ShapeConfig.Circle?((ShapeConfig.Circle)geometry).radius:0);
                    geometry=circle;
                }
                shape=geometry.acS().d(new Transform2D(source.tz(),source.tA()));
            } else {
                unsupported("damage region without resolved shape: "+(ref==null?action.getClass().getName():ref.getName()));
                return;
            }
            for (TrainingTarget target : targets) {
                if (target.actor == null || target.health<=0 || target.config.blockDamage) continue;
                Shape victim = target.config.shape.acS().d(new Transform2D(target.actor.tz(),target.actor.tA()));
                if (!shape.b(victim)) continue;
                float amount = damage(damage.damage,target,weapon);
                if (!(amount>0) || !Float.isFinite(amount)) continue;
                target.health = Math.max(0,target.health-amount);
                try { HEALTH.setFloat(target.actor,target.health/target.maximum); }
                catch (IllegalAccessException error) { throw new IllegalStateException(error); }
                target.actor.setDirty(true);
                if (target.health<=0) {
                    target.actor.gI(timestamp);
                    target.destroyAt = timestamp + Math.max(1,target.config.deathTime);
                }
                System.out.printf(Locale.ROOT,"[COMBAT] hit source=%d target=%d time=%d damage=%.3f health=%.3f/%d%n",
                    source.vw(),target.actor.vw(),timestamp,amount,target.health,target.maximum);
            }
        } else if (action instanceof DungeonActionConfig.SpawnSnapped) {
            DungeonActionConfig.SpawnSnapped spawn=(DungeonActionConfig.SpawnSnapped)action;
            if (!(spawn.location instanceof TargetConfig.Source) || spawn.rotationVariance!=0) {
                unsupported("non-source or random actor spawn"); return;
            }
            spawn(spawn.ej(source.vw()),spawn.e(new Vector2f(source.tz()),source.tA()),
                (spawn.relative?source.tA():0)+spawn.rotation,source,weapon,timestamp);
        } else if (action instanceof ActionConfig.SpawnActor) {
            ActionConfig.SpawnActor spawn=(ActionConfig.SpawnActor)action;
            if (!(spawn.location instanceof TargetConfig.Source)) {
                unsupported("non-source actor spawn"); return;
            }
            spawn(spawn.actor,new Vector2f(source.tz()),source.tA(),source,weapon,timestamp);
        } else if (action instanceof ActionConfig.DestroyActor) {
            if (((ActionConfig.DestroyActor)action).target instanceof TargetConfig.Source) {
                SpawnedActor actor=spawned.get(source.vw());
                if (actor!=null) actor.destroy(timestamp);
            } else unsupported("non-source destruction");
        } else unsupported("action "+action.getClass().getName());
    }

    private void spawn(ConfigReference<ActorConfig> ref,Vector2f position,float rotation,
            Actor source,LevelItem weapon,int timestamp) {
        if (ref==null) { unsupported("unresolved spawn actor reference"); return; }
        if (("Bullet/Sword/Burst Bullet".equals(ref.getName())
                || "Bullet/Sword/Parts/Burst Trail".equals(ref.getName())) && weapon!=null) {
            ItemConfig.Weapon item=(ItemConfig.Weapon)weapon.w(configs);
            Object color=item.chargedAttack==null?null:item.chargedAttack.kW().get("Burst Color");
            if(color!=null) {ref=ref.kX();ref.kW().f("Color",color);}
        }
        ActorConfig actorConfig=configs.a(ActorConfig.class,ref);
        if (actorConfig==null) throw new IllegalStateException("Missing spawned actor "+ref);
        ActorConfig.Original config=actorConfig.ai(configs);
        if (config instanceof DungeonActorConfig.Monster) {
            unsupported("summoned monster AI"); return;
        }
        Actor actor=config.a(ref,++nextActorId,timestamp,position,rotation);
        actor.init(configs);
        int expiry=Integer.MAX_VALUE;
        ActionConfig detonation=null;
        if (actor instanceof Bullet) {
            DungeonActorConfig.Bullet bullet=(DungeonActorConfig.Bullet)config;
            ((Bullet)actor).i(source);
            ((Bullet)actor).aF(bullet.height);
            actor.tz().x+=Math.cos(rotation)*bullet.offset.x-Math.sin(rotation)*bullet.offset.y;
            actor.tz().y+=Math.sin(rotation)*bullet.offset.x+Math.cos(rotation)*bullet.offset.y;
            ((Mobile)actor).aP(rotation);
            actor.k(Mobile.MOVING,bullet.startMoving);
            expiry=timestamp+bullet.fuse;
            detonation=bullet.detonationAction;
        } else if (actor instanceof Bomb) {
            DungeonActorConfig.Bomb bomb=(DungeonActorConfig.Bomb)config;
            ((Bomb)actor).i(source);
            expiry=timestamp+bomb.fuse;
            detonation=bomb.detonationAction;
        }
        spawned.put(actor.vw(),new SpawnedActor(actor,weapon,timestamp,expiry,detonation));
        SpawnedActor state=spawned.get(actor.vw());
        if ("Bullet/Sword/Burst Bullet".equals(ref.getName())) {
            state.burstRemaining=number(ref.kW().get("Burst Count"),0);
            for (Timer timer:state.timers) {
                if (!(timer.config.action instanceof ActionConfig.Conditional)) continue;
                ActionConfig branch=((ActionConfig.Conditional)timer.config.action).action;
                if (!(branch instanceof ActionConfig.Compound)) continue;
                for (ActionConfig child:((ActionConfig.Compound)branch).actions)
                    if(child instanceof ActionConfig.SpawnActor) state.burstAction=child;
            }
            // Client-only templates omit the server variable/tick handler. The
            // local compatibility cadence is explicit, not claimed as vanilla.
            state.burstInterval=burstInterval();
            state.nextBurst=timestamp;
            state.timers.clear();
            System.out.println("[COMBAT] charged burst count="+state.burstRemaining+" intervalMs="+state.burstInterval);
        }
        System.out.println("[COMBAT] spawn actor="+actor.vw()+" config="+ref.getName()+" time="+timestamp);
    }

    private void advanceSpawned(int timestamp) {
        // Child spawns join the next tick; never mutate an active iteration.
        for (SpawnedActor state : new ArrayList<>(spawned.values())) {
            if (state.destroyedAt!=Integer.MAX_VALUE) {
                if (timestamp>state.destroyedAt) spawned.remove(state.actor.vw());
                continue;
            }
            Actor actor=state.actor;
            if (actor instanceof Bullet && timestamp>state.lastTick) {
                int until=Math.min(timestamp,state.expiresAt);
                Vector2f before=new Vector2f(actor.tz());
                ((Mobile)actor).a((until-state.lastTick)/1000f,state.lastTick,until);
                Vector2f motion=new Vector2f(actor.tz().x-before.x,actor.tz().y-before.y);
                Shape base=actor.adZ().shape.acS().d(new Transform2D(before,actor.tA()));
                Shape swept=base.A(motion);
                float earliest=Float.POSITIVE_INFINITY;
                synchronized(model) {
                    if(model.a(actor,swept)) {
                        float lower=0,upper=1;
                        for(int i=0;i<18;i++) {
                            float middle=(lower+upper)/2;
                            if(model.a(actor,base.A(new Vector2f(motion.x*middle,motion.y*middle))))upper=middle;
                            else lower=middle;
                        }
                        earliest=upper;
                    }
                }
                for (TrainingTarget target : targets) {
                    if (target.actor==null || target.health<=0
                            || (actor.adZ().collisionMask&target.config.collisionFlags)==0) continue;
                    Shape victim=target.config.shape.acS().d(new Transform2D(target.actor.tz(),target.actor.tA()));
                    if (!swept.b(victim)) continue;
                    float lower=0,upper=1;
                    for (int i=0;i<18;i++) {
                        float middle=(lower+upper)/2;
                        if (base.A(new Vector2f(motion.x*middle,motion.y*middle)).b(victim)) upper=middle;
                        else lower=middle;
                    }
                    earliest=Math.min(earliest,upper);
                }
                if (Float.isFinite(earliest)) {
                    float fraction=Math.min(1,earliest+0.0001f);
                    actor.tz().x=before.x+motion.x*fraction;
                    actor.tz().y=before.y+motion.y*fraction;
                    execute(state.detonation,null,actor,state.weapon,timestamp);
                    state.destroy(timestamp);
                }
            }
            state.lastTick=timestamp;
            if (state.destroyedAt!=Integer.MAX_VALUE) continue;
            if (state.burstAction!=null && timestamp>=state.nextBurst && state.burstRemaining>0) {
                execute(state.burstAction,null,actor,state.weapon,timestamp);
                state.burstRemaining--; state.nextBurst=timestamp+state.burstInterval;
                if(state.burstRemaining==0) state.destroy(timestamp);
            }
            if (timestamp>=state.expiresAt) {
                if (actor instanceof Bullet) ((Bullet)actor).cj(true);
                else execute(state.detonation,null,actor,state.weapon,timestamp);
                state.destroy(timestamp);
                continue;
            }
            for (Timer timer : state.timers) {
                if (timestamp>=timer.next && (timer.config.limit==0 || timer.count<timer.config.limit)) {
                    execute(timer.config.action,null,actor,state.weapon,timestamp);
                    timer.count++;
                    timer.next+=timer.interval;
                }
            }
        }
    }

    private static int number(Object value,int fallback) {
        if(value instanceof Number)return Math.max(0,((Number)value).intValue());
        if(value instanceof String)try{return Math.max(0,Integer.parseInt((String)value));}catch(NumberFormatException ignored){}
        return fallback;
    }
    private static int burstInterval() {
        java.util.Properties properties=new java.util.Properties();
        try(java.io.InputStream input=new java.io.FileInputStream("server/server.properties")) {
            properties.load(input);
            int value=Integer.parseInt(properties.getProperty("combat.burstIntervalMs","100"));
            if(value<20 || value>1000) throw new IllegalArgumentException("combat.burstIntervalMs must be 20..1000");
            return value;
        } catch(java.io.IOException error) { throw new IllegalStateException(error); }
    }

    private float damage(DamageConfig damage, TrainingTarget target, LevelItem weapon) {
        if (damage instanceof DamageConfig.Compound) {
            float total=0;
            for (DamageConfig part : ((DamageConfig.Compound)damage).damages) total+=damage(part,target,weapon);
            return total;
        }
        if (damage instanceof DamageConfig.Base && !(damage instanceof DamageConfig.DepthBase)) {
            DamageConfig.Base fixed=(DamageConfig.Base)damage;
            if ((fixed.flags&target.config.damageMask)==0) return 0;
            DamageType type;
            if (fixed instanceof DamageConfig.Normal) type=DamageType.NORMAL;
            else if (fixed instanceof DamageConfig.Piercing) type=DamageType.PIERCING;
            else if (fixed instanceof DamageConfig.Elemental) type=DamageType.ELEMENTAL;
            else if (fixed instanceof DamageConfig.Shadow) type=DamageType.SHADOW;
            else if (fixed instanceof DamageConfig.Unblockable) type=DamageType.UNBLOCKABLE;
            else { unsupported("fixed damage "+fixed.getClass().getName()); return 0; }
            return DamageSummary.u(fixed.amount,target.config.defense.a(configs,type,0,0));
        }
        if (!(damage instanceof DamageConfig.DepthScale)) {
            unsupported("damage "+damage.getClass().getName()); return 0;
        }
        DamageConfig.DepthScale scaled = (DamageConfig.DepthScale)damage;
        if ((scaled.flags & target.config.damageMask)==0) return 0;
        if (weapon == null) throw new IllegalStateException("Player damage missing weapon");
        ItemConfig.Weapon item = (ItemConfig.Weapon)weapon.w(configs);
        float attack = configs.a(DepthScaleConfig.class,scaled.depthScale).a(configs,0,item.rarity);
        if (scaled instanceof DamageConfig.DepthScaleMulti) {
            DamageConfig.DepthScaleMulti multi=(DamageConfig.DepthScaleMulti)scaled;
            float split;
            if (multi.splitDepth != null) split=configs.a(DepthScaleConfig.class,multi.splitDepth).a(configs,0,item.rarity);
            else {
                float primary=0,secondary=0;
                for(ItemConfig.AttackValue rating:item.attackValues) {
                    if(rating.damageType==multi.type)primary=rating.a(configs,item,0);
                    if(rating.damageType==multi.type2)secondary=rating.a(configs,item,0);
                }
                split=DamageSummary.t(primary,secondary);
            }
            if (!Float.isFinite(split) || split<0 || split>1) throw new IllegalStateException("Invalid native damage split "+split);
            return defended(attack*(1-split),scaled.type,target,weapon,item)
                +defended(attack*split,multi.type2,target,weapon,item);
        }
        return defended(attack,scaled.type,target,weapon,item);
    }

    private float defended(float attack,DamageType type,TrainingTarget target,LevelItem weapon,ItemConfig.Weapon item) {
        attack = weapon.P(configs).a(attack,type,configs,0,item.rarity);
        int defense = target.config.defense.a(configs,type,0,0);
        // The original client utility supplies the nonlinear defense curve.
        return DamageSummary.u(attack,defense);
    }

    private void unsupported(String kind) {
        if (unsupported.add(kind)) System.out.println("[COMBAT][UNIMPLEMENTED] "+kind);
    }

    private static Field field(Class<?> type,String name) {
        try { Field field=type.getDeclaredField(name); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
    }

    private final class TrainingTarget {
        final ConfigReference<ActorConfig> ref;
        final DungeonActorConfig.Monster config;
        final Vector2f spawn;
        final float rotation;
        final int maximum,delay;
        Monster actor;
        float health;
        int destroyAt,respawnAt;
        TrainingTarget(ConfigReference<ActorConfig> ref,DungeonActorConfig.Monster config,Vector2f spawn,float rotation,int maximum,int delay) {
            this.ref=ref; this.config=config; this.spawn=spawn; this.rotation=rotation; this.maximum=maximum; this.delay=delay;
        }
        void spawn(int timestamp,int actorId) {
            actor=(Monster)config.a(ref,actorId,timestamp,new Vector2f(spawn),rotation);
            actor.init(configs);
            actor.aU(0,timestamp);
            health=maximum;
            try { HEALTH.setFloat(actor,1f); CONDITIONS.set(actor,new ConfigReference<?>[0]); }
            catch (IllegalAccessException error) { throw new IllegalStateException(error); }
            System.out.println("[COMBAT] spawn target="+actorId+" position="+spawn+" health="+maximum);
        }
    }
    private static final class AttackRequest {
        final int source,time; final LevelItem weapon; final AttackConfig.Original attack; final float speed;
        AttackRequest(int source,LevelItem weapon,AttackConfig.Original attack,int time,float speed) {
            this.source=source; this.weapon=weapon; this.attack=attack; this.time=time; this.speed=speed;
        }
    }
    private static final class Fire {
        final int time; final long order; final AttackRequest request;
        final FireActionConfig fire; final ConfigReference<FireActionConfig> reference;
        Fire(int time,long order,AttackRequest request,FireActionConfig fire,ConfigReference<FireActionConfig> reference) {
            this.time=time; this.order=order; this.request=request; this.fire=fire; this.reference=reference;
        }
    }
    private static final class SpawnedActor {
        final Actor actor; final LevelItem weapon; final int expiresAt;
        final ActionConfig detonation; final List<Timer> timers=new ArrayList<>();
        int lastTick,destroyedAt=Integer.MAX_VALUE;
        int burstRemaining,burstInterval,nextBurst;
        ActionConfig burstAction;
        SpawnedActor(Actor actor,LevelItem weapon,int timestamp,int expiresAt,ActionConfig detonation) {
            this.actor=actor; this.weapon=weapon; this.lastTick=timestamp;
            this.expiresAt=expiresAt; this.detonation=detonation;
            for (HandlerConfig handler : actor.adZ().handlers) {
                if (handler instanceof HandlerConfig.Timer) timers.add(new Timer((HandlerConfig.Timer)handler,timestamp));
            }
        }
        void destroy(int timestamp) {
            if (destroyedAt!=Integer.MAX_VALUE) return;
            destroyedAt=timestamp; actor.gI(timestamp); actor.k(Mobile.MOVING,false);
        }
    }
    private static final class Timer {
        final HandlerConfig.Timer config; final int interval;
        int next,count;
        Timer(HandlerConfig.Timer config,int timestamp) {
            this.config=config; interval=Math.max(1,Math.round(config.interval*1000));
            next=timestamp+Math.round(config.offset*1000)+interval;
        }
    }
}
