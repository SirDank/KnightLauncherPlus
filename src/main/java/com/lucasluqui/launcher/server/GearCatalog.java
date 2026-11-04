package com.lucasluqui.launcher.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.threerings.config.ManagedConfig;
import com.threerings.projectx.item.config.ItemConfig;

/**
 * Every piece of gear the game can create, sorted by kind. Loot and companions draw from here, so only items the game itself has exist, and nothing is invented.
 */
final class GearCatalog {
    static final String HELM = "helm";
    static final String ARMOR = "armor";
    static final String SHIELD = "shield";
    static final String WEAPON = "weapon";
    /** Swords only: the one kind of weapon the server's combat carries out fully (see {@link Companion}). */
    static final String SWORD = "sword";
    static final String[] WEARABLE = {HELM, ARMOR, SHIELD, WEAPON};

    private static volatile Map<String, List<String>> catalog;

    private GearCatalog() {}

    /** The name of an item as shown to players: its last path part ("Weapon/Sword/Glacius" is "Glacius"). */
    static String shortName(String config) {
        return config.substring(config.lastIndexOf('/') + 1);
    }

    /** What a knight of this depth can be given: items whose rarity fits the level. */
    static boolean fitsDepth(int rarity, int depth) {
        int cap = Math.min(5, 1 + depth / 3);
        return rarity <= cap && rarity >= Math.max(0, cap - 2);
    }

    static int rarity(InstalledConfigManager configs, String name) {
        ItemConfig config = configs.d(ItemConfig.class, name);
        ItemConfig.Original original = config == null ? null : config.w(configs);
        return original == null ? 0 : original.rarity;
    }

    /** All items of a kind, in name order. */
    static List<String> all(InstalledConfigManager configs, String kind) {
        return catalog(configs).getOrDefault(kind, Collections.emptyList());
    }

    /** One item of the kind whose rarity fits the depth, or null if the game has none. */
    static String pick(InstalledConfigManager configs, String kind, int depth, Random random) {
        List<String> all = all(configs, kind);
        List<String> fitting = new ArrayList<>();
        for (String name : all) if (fitsDepth(rarity(configs, name), depth)) fitting.add(name);
        List<String> from = fitting.isEmpty() ? all : fitting;
        return from.isEmpty() ? null : from.get(random.nextInt(from.size()));
    }

    private static Map<String, List<String>> catalog(InstalledConfigManager configs) {
        Map<String, List<String>> known = catalog;
        if (known != null) return known;
        Map<String, List<String>> found = new HashMap<>();
        for (String kind : new String[]{HELM, ARMOR, SHIELD, WEAPON, SWORD}) found.put(kind, new ArrayList<>());
        for (ManagedConfig entry : configs.bd(ItemConfig.class).kL()) {
            ItemConfig config = configs.d(ItemConfig.class, entry.getName());
            if (config == null || !config.creatable) continue;
            ItemConfig.Original original = config.w(configs);
            String name = entry.getName();
            if (original instanceof ItemConfig.Helm && name.startsWith("Gear/Helm/")) found.get(HELM).add(name);
            else if (original instanceof ItemConfig.Armor && name.startsWith("Gear/Armor/")) found.get(ARMOR).add(name);
            else if (original instanceof ItemConfig.Shield && name.startsWith("Gear/Shield/")) found.get(SHIELD).add(name);
            else if (name.startsWith("Weapon/") && (original instanceof ItemConfig.Sword
                    || original instanceof ItemConfig.Handgun || original instanceof ItemConfig.Bomb)) {
                found.get(WEAPON).add(name);
                if (original instanceof ItemConfig.Sword) found.get(SWORD).add(name);
            }
        }
        for (List<String> names : found.values()) Collections.sort(names);
        catalog = found;
        return found;
    }
}
