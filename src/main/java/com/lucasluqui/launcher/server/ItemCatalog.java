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

    public static void populateKnightInventory(PlayerObject po, SceneManager scene) {
        InstalledConfigManager manager = scene.getConfigManager();
        List<Item> allItems = new ArrayList<>();
        long currentId = 1000L;

        // 1. Equipped Items:
        // Slot 1: Armor
        LevelItem armor = createItem(currentId++, "Gear/Armor/Chaos Cloak", 10, manager);
        if (armor != null) {
            allItems.add(armor);
            po.equipment[1] = armor.VD();
        }

        // Slot 0: Helm
        LevelItem helm = createItem(currentId++, "Gear/Helm/Chaos Cowl", 10, manager);
        if (helm != null) {
            allItems.add(helm);
            po.equipment[0] = helm.VD();
        }

        // Slot 2: Shield
        LevelItem shield = createItem(currentId++, "Gear/Shield/Grey Owlite Shield", 10, manager);
        if (shield != null) {
            allItems.add(shield);
            po.equipment[2] = shield.VD();
        }

        // Slot 6: Weapon 1 (Leviathan Blade)
        LevelItem w1 = createItem(currentId++, "Weapon/Sword/Leviathan Blade", 10, manager);
        if (w1 != null) {
            allItems.add(w1);
            po.equipment[6] = w1.VD();
        }

        // Slot 7: Weapon 2 (Combuster)
        LevelItem w2 = createItem(currentId++, "Weapon/Sword/Combuster", 10, manager);
        if (w2 != null) {
            allItems.add(w2);
            po.equipment[7] = w2.VD();
        }

        // Slot 8: Weapon 3 (Polaris)
        LevelItem w3 = createItem(currentId++, "Weapon/Handgun/Polaris", 10, manager);
        if (w3 != null) {
            allItems.add(w3);
            po.equipment[8] = w3.VD();
        }

        // Slot 9: Weapon 4 (Ash of Agni)
        LevelItem w4 = createItem(currentId++, "Weapon/Bomb/Ash of Agni", 10, manager);
        if (w4 != null) {
            allItems.add(w4);
            po.equipment[9] = w4.VD();
        }

        // 2. Extra Arsenal in Bag (Swords, Handguns, Bombs, Armor):
        String[] extraArsenal = new String[]{
            "Weapon/Sword/Glacius",
            "Weapon/Sword/Gran Faust",
            "Weapon/Sword/Divine Avenger",
            "Weapon/Sword/Fang of Vog",
            "Weapon/Handgun/Supernova",
            "Weapon/Handgun/Blitz Needle",
            "Weapon/Handgun/Argent Peacemaker",
            "Weapon/Handgun/Sentenza",
            "Weapon/Bomb/Nitronome",
            "Weapon/Bomb/Shivermist Buster",
            "Weapon/Bomb/Dark Briar Barrage",
            "Gear/Armor/Dragon Scale Mail",
            "Gear/Shield/Dragon Scale Shield"
        };

        TreeSet<String> arsenal = new TreeSet<>(scene.getWeaponCatalog());
        java.util.Collections.addAll(arsenal, extraArsenal);
        for (Item existing : allItems) {
            if (existing != null && existing.Mx() != null) {
                arsenal.remove(existing.Mx().getName());
            }
        }
        for (String path : arsenal) {
            LevelItem extra = createItem(currentId++, path, 10, manager);
            if (extra != null) {
                allItems.add(extra);
            }
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
