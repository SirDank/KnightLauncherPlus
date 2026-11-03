package com.lucasluqui.launcher.server;

import com.threerings.math.Transform2D;
import com.threerings.math.Vector2f;
import com.threerings.projectx.dungeon.data.actor.Monster;
import com.threerings.tudey.data.TudeySceneModel;
import com.threerings.tudey.data.actor.Actor;
import com.threerings.tudey.shape.Shape;

/**
 * Handles collision queries against client tile/placeable geometry and live mob shapes.
 */
public final class SceneCollision {
    private final TudeySceneModel model;
    private final CombatWorld world;

    public SceneCollision(TudeySceneModel model, CombatWorld world) {
        this.model = model;
        this.world = world;
    }

    public boolean penetration(Actor actor, Shape shape, Vector2f result) {
        synchronized (model) {
            model.a(actor, shape, result);
        }
        if (world != null) {
            for (Actor obstacle : world.snapshot(0).values()) {
                if (!solid(actor, obstacle)) continue;
                Shape victim = shape(obstacle);
                if (!victim.b(shape)) continue;
                Vector2f push = victim.a(shape, new Vector2f());
                if (push.tW() > result.tW()) result.q(push);
            }
        }
        return result.tW() > 0;
    }

    public boolean intersects(Actor actor, Shape shape) {
        synchronized (model) {
            if (model.a(actor, shape)) return true;
        }
        if (world != null) {
            for (Actor obstacle : world.snapshot(0).values()) {
                if (solid(actor, obstacle) && shape(obstacle).b(shape)) return true;
            }
        }
        return false;
    }

    public boolean intersects(int mask, Shape shape) {
        synchronized (model) {
            if (model.a(mask, shape)) return true;
        }
        if (world != null) {
            for (Actor obstacle : world.snapshot(0).values()) {
                if (obstacle instanceof Monster && ((Monster)obstacle).RD() > 0
                        && (mask & obstacle.NC()) != 0 && shape(obstacle).b(shape)) {
                    return true;
                }
            }
        }
        return false;
    }

    public int directions(Actor actor, Shape shape) {
        synchronized (model) {
            return model.b(actor, shape);
        }
    }

    private boolean solid(Actor actor, Actor obstacle) {
        return obstacle instanceof Monster && ((Monster)obstacle).RD() > 0 && actor.e(obstacle);
    }

    private Shape shape(Actor actor) {
        return actor.adZ().p(model.getConfigManager()).acS()
            .d(new Transform2D(actor.tz(), actor.tA()));
    }

    /**
     * Resolves town movement using native three-pass penetration rules.
     */
    public void move(Actor actor, float x, float y) {
        float dx = x - actor.tz().x;
        float dy = y - actor.tz().y;
        int steps = Math.max(1, (int)Math.ceil(Math.hypot(dx, dy) / 0.08));
        for (int step = 0; step < steps; step++) {
            Vector2f previous = new Vector2f(actor.tz());
            actor.tz().s(previous.x + dx / steps, previous.y + dy / steps);
            Vector2f push = new Vector2f();
            boolean clear = false;
            for (int pass = 0; pass < 3; pass++) {
                if (!penetration(actor, shape(actor), push)) {
                    clear = true;
                    break;
                }
                actor.tz().s(actor.tz().x + push.x * 1.001f, actor.tz().y + push.y * 1.001f);
            }
            if (!clear) actor.tz().q(previous);
        }
        actor.setDirty(true);
    }
}
