package net.fetal.titaneconomy.managers;

import net.fetal.titaneconomy.TitanEconomy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class ShopManager {
    private static final double DEFAULT_SELL_MULTIPLIER = 0.20D;

    private final TitanEconomy plugin;
    private File shopFile;
    private FileConfiguration shopConfig;
    
    // Caches
    public final HashMap<Integer, String> categorySlots = new HashMap<>();
    public final HashMap<String, HashMap<Integer, String>> menuItemsCache = new HashMap<>();
    
    // Tracks which item a player selected before the quantity menu opens.
    public final HashMap<UUID, String> pendingPurchase = new HashMap<>();

    public ShopManager(TitanEconomy plugin) {
        this.plugin = plugin;
        loadShopConfig();
    }

    private void loadShopConfig() {
        shopFile = new File(plugin.getDataFolder(), "shops.yml");
        if (!shopFile.exists()) {
            plugin.saveResource("shops.yml", false);
        }
        shopConfig = YamlConfiguration.loadConfiguration(shopFile);
        migrateShopPrices();
    }

    public void openMainMenu(Player player) {
        String title = shopConfig.getString("main-menu.title", "Titan Marketplace");
        int rows = shopConfig.getInt("main-menu.rows", 3);
        
        Inventory inv = Bukkit.createInventory(null, rows * 9, color(title));
        categorySlots.clear();

        ConfigurationSection cats = shopConfig.getConfigurationSection("main-menu.categories");
        if (cats != null) {
            for (String key : cats.getKeys(false)) {
                int slot = cats.getInt(key + ".slot");
                String matName = cats.getString(key + ".material");
                String name = cats.getString(key + ".name");
                List<String> lore = cats.getStringList(key + ".lore");

                inv.setItem(slot, createGuiItem(Material.valueOf(matName), name, lore));
                categorySlots.put(slot, key);
            }
        }
        player.openInventory(inv);
    }

    public void openCategory(Player player, String categoryId) {
        String title = shopConfig.getString("menus." + categoryId + ".title", "Shop");
        Inventory inv = Bukkit.createInventory(null, 54, color(title)); // 54 slots for larger item pages
        
        menuItemsCache.putIfAbsent(title, new HashMap<>());
        menuItemsCache.get(title).clear();

        ConfigurationSection items = shopConfig.getConfigurationSection("menus." + categoryId + ".items");
        
        if (items != null) {
            for (String key : items.getKeys(false)) {
                try {
                    String itemPath = "menus." + categoryId + ".items." + key;
                    int slot = items.getInt(key + ".slot");
                    String matName = items.getString(key + ".material");
                    String name = items.getString(key + ".name");
                    double buyPrice = getBuyPrice(itemPath);
                    double sellPrice = getSellPrice(itemPath);
                    
                    List<String> lore = new ArrayList<>();
                    lore.add("&7Buy: &a$" + formatPrice(buyPrice));
                    lore.add("&7Sell: &c$" + formatPrice(sellPrice));
                    lore.add("");
                    lore.add("&eClick to Select Quantity");

                    ItemStack item = createGuiItem(Material.valueOf(matName), name, lore);
                    inv.setItem(slot, item);
                    
                    menuItemsCache.get(title).put(slot, itemPath);
                    
                } catch (Exception e) {
                    plugin.getLogger().warning("Error loading item: " + key);
                }
            }
        }
        
        ItemStack back = createGuiItem(Material.ARROW, "&cGo Back", List.of("&7Return to Main Menu"));
        inv.setItem(49, back); // Bottom center

        player.openInventory(inv);
    }

    // --- QUANTITY SELECTION MENU ---
    public void openQuantityMenu(Player player, String itemPath) {
        // Save the selected item so the click handler can complete the purchase.
        pendingPurchase.put(player.getUniqueId(), itemPath);

        String itemName = shopConfig.getString(itemPath + ".name", "Item");
        double buyPrice = getBuyPrice(itemPath);
        double sellPrice = getSellPrice(itemPath);
        String matName = shopConfig.getString(itemPath + ".material");
        Material mat = Material.valueOf(matName);

        Inventory inv = Bukkit.createInventory(null, 27, color("&8Select Amount: " + itemName));

        // Option 1: Buy x1
        inv.setItem(11, createGuiItem(mat, "&aBuy x1", List.of(
            "&7Buy Total: &e$" + formatPrice(buyPrice),
            "&7Sell Each: &c$" + formatPrice(sellPrice),
            "",
            "&bClick to Buy!"
        )));

        // Option 2: Buy x32
        ItemStack stack32 = createGuiItem(mat, "&aBuy x32", List.of(
            "&7Buy Total: &e$" + formatPrice(buyPrice * 32),
            "&7Sell Each: &c$" + formatPrice(sellPrice),
            "",
            "&bClick to Buy!"
        ));
        stack32.setAmount(32);
        inv.setItem(13, stack32);

        // Option 3: Buy x64
        ItemStack stack64 = createGuiItem(mat, "&aBuy x64", List.of(
            "&7Buy Total: &e$" + formatPrice(buyPrice * 64),
            "&7Sell Each: &c$" + formatPrice(sellPrice),
            "",
            "&bClick to Buy!"
        ));
        stack64.setAmount(64);
        inv.setItem(15, stack64);

        // Cancel Button
        inv.setItem(22, createGuiItem(Material.BARRIER, "&cCancel", List.of("&7Go back")));

        player.openInventory(inv);
    }

    private ItemStack createGuiItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(color(name));
            List<Component> loreComp = new ArrayList<>();
            if (lore != null) {
                for (String l : lore) loreComp.add(color(l));
            }
            meta.lore(loreComp);
            item.setItemMeta(meta);
        }
        return item;
    }

    public double getBuyPrice(String itemPath) {
        if (shopConfig.isSet(itemPath + ".buy")) {
            return shopConfig.getDouble(itemPath + ".buy");
        }
        return shopConfig.getDouble(itemPath + ".price");
    }

    public double getSellPrice(String itemPath) {
        if (shopConfig.isSet(itemPath + ".sell")) {
            return shopConfig.getDouble(itemPath + ".sell");
        }
        return calculateDefaultSellPrice(getBuyPrice(itemPath));
    }

    public String formatPrice(double price) {
        if (Math.abs(price - Math.rint(price)) < 0.0001D) {
            return String.format(Locale.US, "%.0f", price);
        }
        return String.format(Locale.US, "%.2f", price);
    }

    public String findItemPathByMaterial(Material material) {
        ConfigurationSection menus = shopConfig.getConfigurationSection("menus");
        if (menus == null) {
            return null;
        }

        for (String menuId : menus.getKeys(false)) {
            ConfigurationSection items = shopConfig.getConfigurationSection("menus." + menuId + ".items");
            if (items == null) {
                continue;
            }

            for (String itemId : items.getKeys(false)) {
                String itemPath = "menus." + menuId + ".items." + itemId;
                if (material.name().equalsIgnoreCase(shopConfig.getString(itemPath + ".material"))) {
                    return itemPath;
                }
            }
        }

        return null;
    }

    public FileConfiguration getConfig() { return shopConfig; }
    private Component color(String s) { return LegacyComponentSerializer.legacyAmpersand().deserialize(s); }

    private void migrateShopPrices() {
        ConfigurationSection menus = shopConfig.getConfigurationSection("menus");
        if (menus == null) {
            return;
        }

        boolean updated = false;
        for (String menuId : menus.getKeys(false)) {
            ConfigurationSection items = shopConfig.getConfigurationSection("menus." + menuId + ".items");
            if (items == null) {
                continue;
            }

            for (String itemId : items.getKeys(false)) {
                String itemPath = "menus." + menuId + ".items." + itemId;
                if (!shopConfig.isSet(itemPath + ".buy") && shopConfig.isSet(itemPath + ".price")) {
                    shopConfig.set(itemPath + ".buy", shopConfig.getDouble(itemPath + ".price"));
                    shopConfig.set(itemPath + ".price", null);
                    updated = true;
                }

                if (!shopConfig.isSet(itemPath + ".sell") && shopConfig.isSet(itemPath + ".buy")) {
                    shopConfig.set(itemPath + ".sell", calculateDefaultSellPrice(shopConfig.getDouble(itemPath + ".buy")));
                    updated = true;
                }
            }
        }

        if (!updated) {
            return;
        }

        try {
            shopConfig.save(shopFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save migrated shop prices: " + e.getMessage());
        }
    }

    private double calculateDefaultSellPrice(double buyPrice) {
        return Math.round(buyPrice * DEFAULT_SELL_MULTIPLIER);
    }
}
