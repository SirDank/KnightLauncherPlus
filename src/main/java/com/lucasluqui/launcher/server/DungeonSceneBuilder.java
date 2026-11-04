package com.lucasluqui.launcher.server;

import java.lang.reflect.Field;

import com.threerings.config.ConfigReference;
import com.threerings.math.Transform3D;
import com.threerings.math.Vector3f;
import com.threerings.tudey.config.AreaConfig;
import com.threerings.tudey.config.TileConfig;
import com.threerings.tudey.data.TudeySceneModel;
import com.threerings.tudey.util.Coord;

/**
 * Makes the map the game loads for a dungeon level from a {@link DungeonLayout}: floor and wall tiles, and the scene
 * globals (lighting, camera, music) and the player start copied from a real scene, so the level looks and controls
 * like the Training Hall the game already knows. The client needs nothing else to show the map; where monsters stand
 * and what happens in the rooms is decided by the server.
 */
final class DungeonSceneBuilder {
    static final String FLOOR_TILE = "Clockworks/Floor, base/Base 01";
    static final String WALL_TILE = "Clockworks/Wall/Back x1 01";
    static final String EXIT_AREA = "Portals/Next Floor";

    private static final Field TILE_LOCATION;

    static {
        try {
            TILE_LOCATION = TudeySceneModel.TileEntry.class.getDeclaredField("_location");
            TILE_LOCATION.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private DungeonSceneBuilder() {}

    /**
     * @param template A real scene of the same kind (the Training Hall): its config, globals and player start are reused.
     * @param version Must grow with every build of the same scene id, or the game keeps showing its cached copy.
     */
    static TudeySceneModel build(TudeySceneModel template, InstalledConfigManager configs, DungeonLayout layout,
                                 int sceneId, int version) {
        try {
            TudeySceneModel model = new TudeySceneModel();
            model.sceneId = sceneId;
            model.name = template.name;
            model.version = version;
            model.a(template.adG());
            model.init(configs);

            for (long cell : layout.floor) model.h(tile(FLOOR_TILE, DungeonLayout.x(cell), DungeonLayout.y(cell)));
            for (long cell : layout.wallCells()) model.h(tile(WALL_TILE, DungeonLayout.x(cell), DungeonLayout.y(cell)));

            boolean startPlaced = false;
            for (TudeySceneModel.Entry entry : template.adK()) {
                if (entry instanceof TudeySceneModel.GlobalEntry) {
                    model.h((TudeySceneModel.Entry) entry.clone());
                } else if (!startPlaced && entry instanceof TudeySceneModel.PlaceableEntry
                        && "Dynamic/Portal/Level Start".equals(((TudeySceneModel.PlaceableEntry) entry).placeable.getName())) {
                    TudeySceneModel.PlaceableEntry start = (TudeySceneModel.PlaceableEntry) entry.clone();
                    start.transform = new Transform3D(new Vector3f(layout.startX, layout.startY, 0f), start.transform.tE());
                    model.h(start);
                    startPlaced = true;
                }
            }
            if (!startPlaced) throw new IllegalStateException("The template scene has no player start");

            float[] exit = layout.exitBounds();
            TudeySceneModel.AreaEntry area = new TudeySceneModel.AreaEntry();
            area.area = new ConfigReference<AreaConfig>(EXIT_AREA);
            area.vertices = new TudeySceneModel.Vertex[]{vertex(exit[0], exit[1]), vertex(exit[2], exit[1]),
                vertex(exit[2], exit[3]), vertex(exit[0], exit[3])};
            model.h(area);
            return model;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not build the dungeon map: " + e, e);
        }
    }

    private static TudeySceneModel.Vertex vertex(float x, float y) {
        TudeySceneModel.Vertex vertex = new TudeySceneModel.Vertex();
        vertex.r(x, y, 0f);
        return vertex;
    }

    private static TudeySceneModel.TileEntry tile(String name, int x, int y) throws ReflectiveOperationException {
        TudeySceneModel.TileEntry tile = new TudeySceneModel.TileEntry();
        tile.tile = new ConfigReference<TileConfig>(name);
        TILE_LOCATION.set(tile, new Coord(x, y));
        return tile;
    }
}
