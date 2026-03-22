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
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.UUID;

public class ShopManager {
    private static final double DEFAULT_SELL_MULTIPLIER = 0.20D;
    private static final String SELL_MENU_TITLE = "&8Sell Items";
    private static final int SELL_MENU_SIZE = 45;
    private static final int SELL_CANCEL_SLOT = 39;
    private static final int SELL_SUMMARY_SLOT = 40;
    private static final int SELL_CONFIRM_SLOT = 41;

    private final TitanEconomy plugin;
    private File shopFile;
    private FileConfiguration shopConfig;
    
    // Caches
    public final HashMap<Integer, String> categorySlots = new HashMap<>();
    public final HashMap<String, HashMap<Integer, String>> menuItemsCache = new HashMap<>();
    
    // Tracks which item a player selected before the quantity menu opens.
    public final HashMap<UUID, String> pendingPurchase = new HashMap<>();
    private final HashMap<UUID, HashSet<Integer>> sellSelections = new HashMap<>();

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

    public void openSellMenu(Player player) {
        clearSellSelection(player.getUniqueId());
        Inventory inv = Bukkit.createInventory(null, SELL_MENU_SIZE, color(SELL_MENU_TITLE));
        populateSellMenu(player, inv);
        player.openInventory(inv);
    }

    public boolean isSellMenu(String title) {
        return SELL_MENU_TITLE.equals(title);
    }

    public boolean isSellMenuSlot(int slot) {
        return slot >= 0 && slot < 36;
    }

    public int getPlayerInventorySlotFromSellMenuSlot(int slot) {
        if (slot < 0 || slot >= 36) {
            return -1;
        }
        if (slot < 27) {
            return slot + 9;
        }
        return slot - 27;
    }

    public int getSellCancelSlot() {
        return SELL_CANCEL_SLOT;
    }

    public int getSellConfirmSlot() {
        return SELL_CONFIRM_SLOT;
    }

    public void toggleSellSelection(Player player, int menuSlot) {
        int playerSlot = getPlayerInventorySlotFromSellMenuSlot(menuSlot);
        if (playerSlot < 0) {
            return;
        }

        ItemStack item = player.getInventory().getItem(playerSlot);
        if (!isSellable(item)) {
            return;
        }

        HashSet<Integer> selectedSlots = sellSelections.computeIfAbsent(player.getUniqueId(), key -> new HashSet<>());
        if (!selectedSlots.add(playerSlot)) {
            selectedSlots.remove(playerSlot);
        }
    }

    public void refreshSellMenu(Player player, Inventory inventory) {
        populateSellMenu(player, inventory);
    }

    public void clearSellSelection(UUID playerId) {
        sellSelections.remove(playerId);
    }

    public Set<Integer> getSelectedSellSlots(UUID playerId) {
        Set<Integer> selected = sellSelections.get(playerId);
        if (selected == null) {
            return Collections.emptySet();
        }
        return new HashSet<>(selected);
    }

    public boolean isSellable(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return false;
        }
        return findItemPathByMaterial(item.getType()) != null;
    }

    public double getSellValue(ItemStack item) {
        if (!isSellable(item)) {
            return 0.0D;
        }
        String itemPath = findItemPathByMaterial(item.getType());
        return getSellPrice(itemPath) * item.getAmount();
    }

    public double sellInventorySlot(Player player, int playerSlot) {
        ItemStack item = player.getInventory().getItem(playerSlot);
        if (!isSellable(item)) {
            return 0.0D;
        }

        double total = getSellValue(item);
        if (total <= 0.0D) {
            return 0.0D;
        }

        player.getInventory().setItem(playerSlot, null);
        plugin.getEconomyManager().deposit(player, total);
        return total;
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

    private void populateSellMenu(Player player, Inventory inv) {
        inv.clear();
        Set<Integer> selectedSlots = getSelectedSellSlots(player.getUniqueId());

        for (int menuSlot = 0; menuSlot < 36; menuSlot++) {
            int playerSlot = getPlayerInventorySlotFromSellMenuSlot(menuSlot);
            ItemStack playerItem = player.getInventory().getItem(playerSlot);
            inv.setItem(menuSlot, createSellPreviewItem(playerItem, selectedSlots.contains(playerSlot)));
        }

        for (int slot = 36; slot < 45; slot++) {
            inv.setItem(slot, createGuiItem(Material.GRAY_STAINED_GLASS_PANE, "&8 ", List.of()));
        }

        inv.setItem(SELL_CANCEL_SLOT, createGuiItem(Material.BARRIER, "&cCancel", List.of("&7Close the sell menu")));
        inv.setItem(SELL_SUMMARY_SLOT, createSelectionSummary(player, selectedSlots));
        inv.setItem(SELL_CONFIRM_SLOT, createGuiItem(Material.EMERALD_BLOCK, "&aSell Selected", List.of(
            "&7Selected Slots: &f" + selectedSlots.size(),
            "&7Total Value: &a$" + formatPrice(getSelectedSellValue(player, selectedSlots)),
            "",
            "&eClick to confirm the sale"
        )));
    }

    private ItemStack createSellPreviewItem(ItemStack item, boolean selected) {
        if (item == null || item.getType() == Material.AIR) {
            return null;
        }

        if (!isSellable(item)) {
            return createStatusPane(Material.RED_STAINED_GLASS_PANE, item.getAmount(), "&cCannot Sell", List.of(
                "&7Item: &f" + formatMaterialName(item.getType()),
                "&7Amount: &f" + item.getAmount(),
                "",
                "&cThis item is not in the shop"
            ));
        }

        if (selected) {
            return createStatusPane(Material.LIME_STAINED_GLASS_PANE, item.getAmount(), "&aSelected", List.of(
                "&7Item: &f" + formatMaterialName(item.getType()),
                "&7Amount: &f" + item.getAmount(),
                "&7Total Sell Value: &a$" + formatPrice(getSellValue(item)),
                "",
                "&eClick again to deselect"
            ));
        }

        ItemStack preview = item.clone();
        ItemMeta meta = preview.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>();
            if (meta.lore() != null) {
                lore.addAll(meta.lore());
                lore.add(color(""));
            }
            lore.add(color("&7Sell Each: &c$" + formatPrice(getSellPrice(findItemPathByMaterial(item.getType())))));
            lore.add(color("&7Total Sell Value: &a$" + formatPrice(getSellValue(item))));
            lore.add(color(""));
            lore.add(color("&eClick to select this slot"));
            meta.lore(lore);
            preview.setItemMeta(meta);
        }
        return preview;
    }

    private ItemStack createStatusPane(Material paneMaterial, int amount, String name, List<String> lore) {
        ItemStack item = createGuiItem(paneMaterial, name, lore);
        item.setAmount(Math.max(1, Math.min(amount, paneMaterial.getMaxStackSize())));
        return item;
    }

    private ItemStack createSelectionSummary(Player player, Set<Integer> selectedSlots) {
        return createGuiItem(Material.PAPER, "&fSelection Summary", List.of(
            "&7Selected Slots: &f" + selectedSlots.size(),
            "&7Total Value: &a$" + formatPrice(getSelectedSellValue(player, selectedSlots))
        ));
    }

    private double getSelectedSellValue(Player player, Set<Integer> selectedSlots) {
        double total = 0.0D;
        for (int playerSlot : selectedSlots) {
            total += getSellValue(player.getInventory().getItem(playerSlot));
        }
        return total;
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
                if (!shopConfig.isSet(itemPath + ".price")) {
                    continue;
                }

                double legacyPrice = shopConfig.getDouble(itemPath + ".price");
                if (!shopConfig.isSet(itemPath + ".buy")) {
                    shopConfig.set(itemPath + ".buy", legacyPrice);
                }
                if (!shopConfig.isSet(itemPath + ".sell")) {
                    shopConfig.set(itemPath + ".sell", calculateDefaultSellPrice(legacyPrice));
                }
                if (shopConfig.isSet(itemPath + ".price")) {
                    shopConfig.set(itemPath + ".price", null);
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

    private String formatMaterialName(Material material) {
        String[] words = material.name().toLowerCase(Locale.US).split("_");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return builder.toString();
    }
}
