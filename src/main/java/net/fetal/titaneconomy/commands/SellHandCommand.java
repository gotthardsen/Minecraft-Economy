package net.fetal.titaneconomy.commands;

import net.fetal.titaneconomy.TitanEconomy;
import net.fetal.titaneconomy.managers.EconomyManager;
import net.fetal.titaneconomy.managers.ShopManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class SellHandCommand implements CommandExecutor {

    private final EconomyManager economyManager;
    private final ShopManager shopManager;
    private final TitanEconomy plugin;

    public SellHandCommand(TitanEconomy plugin) {
        this.plugin = plugin;
        this.economyManager = plugin.getEconomyManager();
        this.shopManager = plugin.getShopManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can sell items.", NamedTextColor.RED));
            return true;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR) {
            player.sendMessage(Component.text("Hold an item in your main hand first.", NamedTextColor.RED));
            return true;
        }

        String itemPath = shopManager.findItemPathByMaterial(item.getType());
        if (itemPath == null) {
            player.sendMessage(Component.text("This item cannot be sold to the shop.", NamedTextColor.RED));
            return true;
        }

        double sellPrice = shopManager.getSellPrice(itemPath);
        if (sellPrice <= 0) {
            player.sendMessage(Component.text("This item has no sell value.", NamedTextColor.RED));
            return true;
        }

        int amount = item.getAmount();
        double total = sellPrice * amount;
        economyManager.deposit(player, total);
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));

        player.sendMessage(Component.text("Sold " + amount + "x ", NamedTextColor.GREEN)
                .append(Component.text(item.getType().name(), NamedTextColor.YELLOW))
                .append(Component.text(" for $" + shopManager.formatPrice(total), NamedTextColor.GREEN)));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
        plugin.getScoreboardManager().updateScoreboard(player);
        return true;
    }
}
