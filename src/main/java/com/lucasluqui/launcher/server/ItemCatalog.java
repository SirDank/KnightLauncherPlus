package com.lucasluqui.launcher.server;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import com.threerings.config.ConfigReference;
import com.threerings.config.ManagedConfig;
import com.threerings.presents.dobj.DSet;
import com.threerings.projectx.data.PlayerObject;
import com.threerings.projectx.item.config.ItemConfig;
import com.threerings.projectx.item.data.Item;
import com.threerings.projectx.item.data.LevelItem;

/**
 * Manages item creation and inventory population for connected knights.
 */
public class ItemCatalog {
    private static Field itemIdField;
    private static Field levelField;

    static {
        try {
            itemIdField = Item.class.getDeclaredField("bvY");
            itemIdField.setAccessible(true);
        } catch (Exception ignored) {
            // Fallback: will call item.f(Long) directly
        }
        try {
            levelField = LevelItem.class.getDeclaredField("_level");
            levelField.setAccessible(true);
        } catch (Exception e) {
            System.err.println("[ItemCatalog] Could not access _level field: " + e.getMessage());
        }
    }

    public static LevelItem createItem(long id, String configPath, int level, InstalledConfigManager manager) {
        try {
            ItemConfig config = manager.d(ItemConfig.class, configPath);
            if (config == null) {
                throw new IllegalArgumentException("Missing client item: " + configPath);
            }
            LevelItem item = new LevelItem(config.getConfigId(), new ConfigReference<ItemConfig>(configPath));
            // Set item instance ID
            item.f(Long.valueOf(id));
            if (itemIdField != null) {
                try {
                    itemIdField.set(item, Long.valueOf(id));
                } catch (Exception ignored) {}
            }
            // Set level
            if (levelField != null) {
                levelField.set(item, Integer.valueOf(level));
            }
            return item;
        } catch (Exception e) {
            System.err.println("[ItemCatalog] Error creating item: " + configPath + " -> " + e.getMessage());
            return null;
        }
    }

    /** One item of the kit a new knight starts with, and the slot it is worn in (-1: in the bag). */
    record Starter(String config, int slot) {}

    private static final String[] EXTRA_ARSENAL = {
        "Weapon/Sword/Glacius", "Weapon/Sword/Gran Faust", "Weapon/Sword/Divine Avenger", "Weapon/Sword/Fang of Vog",
        "Weapon/Handgun/Supernova", "Weapon/Handgun/Blitz Needle", "Weapon/Handgun/Argent Peacemaker",
        "Weapon/Handgun/Sentenza", "Weapon/Bomb/Nitronome", "Weapon/Bomb/Shivermist Buster",
        "Weapon/Bomb/Dark Briar Barrage", "Gear/Armor/Dragon Scale Mail", "Gear/Shield/Dragon Scale Shield"
    };

    /** The level every starter item has. */
    static final int STARTER_LEVEL = 10;

    /** What a new knight starts with: a worn set (helm, armor, shield, four weapons) and every weapon in the bag. */
    static List<Starter> starterKit(SceneManager scene) {
        List<Starter> kit = new ArrayList<>();
        kit.add(new Starter("Gear/Armor/Chaos Cloak", 1));
        kit.add(new Starter("Gear/Helm/Chaos Cowl", 0));
        kit.add(new Starter("Gear/Shield/Grey Owlite Shield", 2));
        kit.add(new Starter("Weapon/Sword/Leviathan Blade", 6));
        kit.add(new Starter("Weapon/Sword/Combuster", 7));
        kit.add(new Starter("Weapon/Handgun/Polaris", 8));
        kit.add(new Starter("Weapon/Bomb/Ash of Agni", 9));
        TreeSet<String> arsenal = new TreeSet<>(scene.getWeaponCatalog());
        java.util.Collections.addAll(arsenal, EXTRA_ARSENAL);
        for (Starter worn : kit) arsenal.remove(worn.config());
        for (String path : arsenal) kit.add(new Starter(path, -1));
        return kit;
    }

    /** Gives a player object the starter kit with fixed ids (for sessions without accounts: tests and tools). */
    public static void populateKnightInventory(PlayerObject po, SceneManager scene) {
        InstalledConfigManager manager = scene.getConfigManager();
        List<Item> allItems = new ArrayList<>();
        long currentId = 1000L;
        for (Starter starter : starterKit(scene)) {
            LevelItem item = createItem(currentId++, starter.config(), STARTER_LEVEL, manager);
            if (item == null) continue;
            allItems.add(item);
            if (starter.slot() >= 0) po.equipment[starter.slot()] = item.VD();
        }
        po.items = new DSet<>(allItems);
        System.out.println("[ItemCatalog] Populated " + allItems.size() + " test weapons & equipment items.");
    }

    public static List<String> discoverWeapons(InstalledConfigManager manager) {
        TreeSet<String> names = new TreeSet<>();
        for (ManagedConfig entry : manager.bd(ItemConfig.class).kL()) {
            if (!entry.getName().startsWith("Weapon/")) continue;
            ItemConfig config = manager.d(ItemConfig.class, entry.getName());
            if (config == null || !config.creatable) continue;
            ItemConfig.Original item = config.w(manager);
            if (item instanceof ItemConfig.Sword || item instanceof ItemConfig.Handgun
                    || item instanceof ItemConfig.Bomb) {
                names.add(entry.getName());
            }
        }
        return java.util.Collections.unmodifiableList(new ArrayList<>(names));
    }
}
