package com.lucasluqui.launcher.server;

import com.threerings.config.ConfigReference;
import com.threerings.crowd.data.PlaceConfig;
import com.threerings.projectx.data.ProjectXSceneObject;
import com.threerings.tudey.data.TudeySceneModel;

/** One scene the server hosts: its map, the object the game subscribes to, and how it plays (combat or not). */
final class SceneInstance {
    final int sceneId;
    final int placeOid;
    final ProjectXSceneObject object;
    final TudeySceneModel model;
    final PlaceConfig config;
    final SceneCollision collision;
    /** The fighting in this scene; null in a scene without combat (a town). */
    final CombatWorld combat;
    /** Where players appear when they enter; NaN for the scenes whose spawn depends on where the player came from. */
    final float spawnX, spawnY;

    SceneInstance(int sceneId, int placeOid, ProjectXSceneObject object, TudeySceneModel model, PlaceConfig config,
                  SceneCollision collision, CombatWorld combat) {
        this(sceneId, placeOid, object, model, config, collision, combat, Float.NaN, Float.NaN);
    }

    SceneInstance(int sceneId, int placeOid, ProjectXSceneObject object, TudeySceneModel model, PlaceConfig config,
                  SceneCollision collision, CombatWorld combat, float spawnX, float spawnY) {
        this.spawnX = spawnX;
        this.spawnY = spawnY;
        this.sceneId = sceneId;
        this.placeOid = placeOid;
        this.object = object;
        this.model = model;
        this.config = config;
        this.collision = collision;
        this.combat = combat;
    }
}
