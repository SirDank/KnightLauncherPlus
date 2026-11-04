package com.lucasluqui.launcher.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.threerings.config.ConfigGroup;
import com.threerings.config.ConfigManager;
import com.threerings.config.DerivedConfig;
import com.threerings.config.ManagedConfig;
import com.threerings.config.Parameter;
import com.threerings.config.ParameterizedConfig;
import com.threerings.export.BinaryImporter;
import com.threerings.tudey.config.ActionConfig;
import com.threerings.tudey.config.ActorConfig;
import com.threerings.tudey.config.HandlerConfig;
import com.threerings.tudey.config.RegionConfig;
import com.threerings.tudey.config.TargetConfig;
import com.threerings.tudey.shape.config.ShapeConfig;

/**
 * In-memory view of the game's installed binary configuration catalog (.dat files).
 */
public final class InstalledConfigManager extends ConfigManager {
    public InstalledConfigManager() {
        this._type = "global";
        this._resources = new HashMap<>();
        this._classes = new HashMap<>();
        this._classes.put("global", new Class<?>[0]);
        this._classes.put("resource", new Class<?>[0]);
    }

    public <T extends ManagedConfig> int loadGroup(Class<T> type, File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            return loadGroup(type, input, file.getPath());
        }
    }

    public <T extends ManagedConfig> int loadGroup(Class<T> type, String path) throws Exception {
        return loadGroup(type, new File(path));
    }

    @SuppressWarnings("unchecked")
    public <T extends ManagedConfig> int loadGroup(Class<T> type, InputStream input, String sourceDesc) throws Exception {
        T[] configs;
        Object installed = new BinaryImporter(input).qe();
        // Some client catalogs (notably fire_action.dat) use the newer
        // ConfigGroup.Data envelope instead of a bare typed array.
        configs = (T[])(installed instanceof ConfigGroup.Data
            ? ((ConfigGroup.Data)installed).array : installed);

        ConfigGroup<T> group = new ConfigGroup<>(type);
        group.init(this);
        this._groups.put(type, group);
        for (T config : configs) {
            if (!type.isInstance(config) && !(config instanceof DerivedConfig)) {
                throw new IllegalArgumentException("Unexpected config type in " + sourceDesc
                    + ": " + (config == null ? "null" : config.getClass().getName()));
            }
            restoreRegionContainers(config);
            group.a(config, false);
        }
        return configs.length;
    }

    private static void restoreRegionContainers(ManagedConfig config) throws Exception {
        if (!(config instanceof ParameterizedConfig)) return;
        List<Parameter.Direct> directs = new ArrayList<>();
        for (Parameter parameter : ((ParameterizedConfig)config).parameters) {
            if (parameter instanceof Parameter.Direct) directs.add((Parameter.Direct)parameter);
            else if (parameter instanceof Parameter.Choice)
                java.util.Collections.addAll(directs, ((Parameter.Choice)parameter).directs);
        }
        restoreCloudSignal(config, directs);
        restoreBurstContainers(config, directs);
        for (Parameter.Direct parameter : directs) {
            for (String path : parameter.paths) {
                if (!path.contains(".region.shape")) continue;
                String[] parts = path.split("\\.");
                Object object = config, parent = null;
                Field parentField = null;
                for (int i = 0; i < parts.length && object != null; i++) {
                    String part = parts[i];
                    if (object instanceof RegionConfig.Default && part.equals("shape")
                            && parentField != null) {
                        ShapeConfig geometry;
                        if (i + 1 < parts.length && parts[i + 1].equals("radius")) {
                            geometry = new ShapeConfig.Circle();
                        } else if (i + 1 < parts.length && parts[i + 1].equals("shapes[0]")) {
                            ShapeConfig.Compound compound = new ShapeConfig.Compound();
                            compound.shapes = new ShapeConfig.TransformedShape[]{new ShapeConfig.TransformedShape()};
                            geometry = compound;
                        } else if (i + 1 == parts.length) {
                            geometry = new ShapeConfig.Point();
                        } else {
                            break;
                        }
                        RegionConfig.Transformed region = new RegionConfig.Transformed();
                        region.location = ((RegionConfig.Default)object).location;
                        region.shape = geometry;
                        parentField.set(parent, region);
                        object = region;
                    }
                    int bracket = part.indexOf('[');
                    String name = (bracket < 0 ? part : part.substring(0, bracket)).replace("_", "");
                    Field found = null;
                    for (Field field : object.getClass().getFields()) {
                        if (field.getName().replace("_", "").equalsIgnoreCase(name)) {
                            found = field;
                            break;
                        }
                    }
                    if (found == null) break;
                    parent = object;
                    parentField = found;
                    object = found.get(object);
                    if (bracket >= 0 && object != null) {
                        int close = part.indexOf(']', bracket);
                        if (close < 0) break;
                        String subscript = part.substring(bracket + 1, close);
                        if (!subscript.matches("[0-9]+")) break;
                        int index = Integer.parseInt(subscript);
                        if (!object.getClass().isArray() || index >= Array.getLength(object)) break;
                        object = Array.get(object, index);
                        parentField = null;
                    }
                }
            }
        }
    }

    private static void restoreBurstContainers(ManagedConfig config, List<Parameter.Direct> directs) {
        if (!(config instanceof ActorConfig)
                || !"Bullet/Sword/Burst Bullet".equals(config.getName())) return;
        ActorConfig.Original actor = ((ActorConfig)config).implementation instanceof ActorConfig.Original
            ? (ActorConfig.Original)((ActorConfig)config).implementation : null;
        if (actor == null || actor.handlers.length < 3 || !(actor.handlers[1] instanceof HandlerConfig.Timer)) return;
        boolean trailPath = false, countPath = false;
        for (Parameter.Direct direct : directs) {
            for (String path : direct.paths) {
                trailPath |= path.equals("implementation.handlers[1].action.action.actions[1].actor[\"Damage\"]");
                countPath |= path.equals("implementation.handlers[2].action.value.value");
            }
        }
        if (!trailPath || !countPath) return;
        ActionConfig action = ((HandlerConfig.Timer)actor.handlers[1]).action;
        if (!(action instanceof ActionConfig.Conditional)) return;
        ActionConfig branch = ((ActionConfig.Conditional)action).action;
        if (!(branch instanceof ActionConfig.Compound)) return;
        ActionConfig.Compound compound = (ActionConfig.Compound)branch;
        if (compound.actions.length == 1 && compound.actions[0] instanceof ActionConfig.SpawnActor) {
            compound.actions = new ActionConfig[]{new ActionConfig.None(), compound.actions[0]};
        }
        if (actor.handlers[2] instanceof HandlerConfig.Startup
                && ((HandlerConfig.Startup)actor.handlers[2]).action instanceof ActionConfig.SpawnActor
                && ((ActionConfig.SpawnActor)((HandlerConfig.Startup)actor.handlers[2]).action).actor == null) {
            ActionConfig.SetVariable count = new ActionConfig.SetVariable();
            count.name = "privateBurstCount";
            count.value = new com.threerings.tudey.config.ExpressionConfig.Constant("0");
            ((HandlerConfig.Startup)actor.handlers[2]).action = count;
        }
    }

    private static void restoreCloudSignal(ManagedConfig config, List<Parameter.Direct> directs) {
        if (!(config instanceof ActorConfig)
                || !"Bomb/Parts/Chemical (Cloud)".equals(config.getName())) return;
        boolean namePath = false, regionPath = false;
        for (Parameter.Direct parameter : directs) {
            for (String path : parameter.paths) {
                namePath |= path.equals("implementation.handlers[1].action.name");
                regionPath |= path.equals("implementation.handlers[1].action.target.region.shape.radius");
            }
        }
        if (!namePath || !regionPath) return;
        ActorConfig.Original actor = ((ActorConfig)config).implementation instanceof ActorConfig.Original
            ? (ActorConfig.Original)((ActorConfig)config).implementation : null;
        if (actor == null || actor.handlers.length < 2 || !(actor.handlers[1] instanceof HandlerConfig.Timer)) return;
        HandlerConfig.Timer handler = (HandlerConfig.Timer)actor.handlers[1];
        if (!(handler.action instanceof ActionConfig.SpawnActor)
                || ((ActionConfig.SpawnActor)handler.action).actor != null) return;
        ActionConfig.Signal signal = new ActionConfig.Signal();
        TargetConfig.Intersecting target = new TargetConfig.Intersecting();
        RegionConfig.Transformed region = new RegionConfig.Transformed();
        region.shape = new ShapeConfig.Circle();
        target.region = region;
        signal.target = target;
        handler.action = signal;
    }
}

