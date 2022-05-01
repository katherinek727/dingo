package net.alloymc.shop.transaction;

import net.alloymc.api.AlloyAPI;
import net.alloymc.api.block.Block;
import net.alloymc.api.economy.EconomyProvider;
import net.alloymc.api.economy.EconomyRegistry;
import net.alloymc.api.entity.Player;
import net.alloymc.api.inventory.Inventory;
import net.alloymc.api.inventory.ItemStack;
import net.alloymc.api.inventory.Material;
import net.alloymc.api.world.World;
import net.alloymc.shop.data.Shop;
import net.alloymc.shop.data.ShopItem;
import net.alloymc.shop.data.ShopLocation;
import net.alloymc.shop.data.ShopManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles buy/sell transactions between customers and shops.
 *
 * <p>Uses a physical chest model:
 * <ul>
 *   <li>Buy: debit customer, remove items from chest, give to customer, credit earnings</li>
 *   <li>Sell: remove items from customer, add to chest, debit earnings, credit customer</li>
 * </ul>
 *
 * <p>Admin shops bypass the physical chest and use infinite virtual stock.
 */
public final class ShopTransaction {

    private final ShopManager shopManager;

    public ShopTransaction(ShopManager shopManager) {
        this.shopManager = shopManager;
    }

    private static String fmt(double amount) {
        EconomyRegistry reg = AlloyAPI.economy();
        return reg != null ? reg.formatAmount(amount) : "$" + String.format("%.2f", amount);
    }

    public record Result(boolean success, String message) {
        public static Result ok(String msg) { return new Result(true, msg); }
        public static Result fail(String msg) { return new Result(false, msg); }
    }

    /**
     * A unique material found in a chest with its aggregate count.
     */
    public record ChestStock(Material material, String materialName, int count) {}

    /**
     * Reads the physical chest contents and returns one entry per unique material.
     */
    public static List<ChestStock> readChestContents(Inventory chestInv) {
        Map<Material, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < chestInv.size(); i++) {
            ItemStack item = chestInv.item(i);
            if (item != null && !item.isEmpty() && item.type() != Material.AIR
                    && item.type() != Material.UNKNOWN) {
                counts.merge(item.type(), item.amount(), Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .map(e -> new ChestStock(e.getKey(), e.getKey().name(), e.getValue()))
                .toList();
    }

    /**
     * Resolves a shop's physical chest to an Inventory.
     *
     * @return the chest inventory, or null if the chest is missing or not a container
     */
    public static Inventory getChestInventory(Shop shop) {
        ShopLocation loc = shop.chestLocation();
        var worldOpt = AlloyAPI.server().world(loc.world());
        if (worldOpt.isEmpty()) return null;
        World world = worldOpt.get();
        Block block = world.blockAt(loc.x(), loc.y(), loc.z());
        return block.inventory();
    }

    /**
     * Resolves a shop's physical chest using the given world (avoids server world lookup).
     * Use this when you have the player's world available — more reliable.
     */
    public static Inventory getChestInventory(Shop shop, World playerWorld) {
        ShopLocation loc = shop.chestLocation();
        World world;
        if (playerWorld != null && playerWorld.name().equals(loc.world())) {
            world = playerWorld;
        } else {
            var worldOpt = AlloyAPI.server().world(loc.world());
            if (worldOpt.isEmpty()) return null;
            world = worldOpt.get();
        }
        Block block = world.blockAt(loc.x(), loc.y(), loc.z());
        return block.inventory();
    }

    /**
     * Customer buys items from a shop.
     * Items are physically removed from the shop's chest.
     */
    public Result buy(Player customer, Shop shop, String materialName, int amount) {
        ShopItem listing = shop.items().get(materialName);
        if (listing == null || listing.buyPrice() <= 0) {
            return Result.fail("This shop doesn't sell " + formatMaterial(materialName) + ".");
        }

        Material material;
        try {
            material = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            return Result.fail("Unknown item: " + materialName);
        }

        double totalCost = listing.buyPrice() * amount;
        UUID customerId = customer.uniqueId();

        EconomyProvider economy = AlloyAPI.economy().provider();
        if (economy == null) return Result.fail("Economy is not available.");

        if (!economy.has(customerId, totalCost)) {
            return Result.fail("Insufficient funds. You need " + fmt(totalCost)
                    + " but have " + fmt(economy.getBalance(customerId)) + ".");
        }

        // Admin shops have infinite stock — no chest interaction
        if (shop.isAdminShop()) {
            economy.withdraw(customerId, totalCost);
            shop.addEarnings(totalCost);
            giveItems(customer, material, amount);
            shopManager.save();
            return Result.ok("Bought " + amount + "x " + formatMaterial(materialName)
                    + " for " + fmt(totalCost) + ".");
        }

        // Chest-based shop (player shop or reserve shop) — remove from physical chest
        Inventory chestInv = getChestInventory(shop);
        if (chestInv == null) return Result.fail("Shop chest is unavailable.");

        int available = countMaterial(chestInv, material);
        if (available < amount) {
            return Result.fail("Out of stock. Only " + available + "x "
                    + formatMaterial(materialName) + " available.");
        }

        // All checks passed — execute
        economy.withdraw(customerId, totalCost);
        // Reserve shops: payment goes directly to reserve via economy provider.
        // Player shops: track earnings for owner to collect.
        if (!shop.isReserveShop()) {
            shop.addEarnings(totalCost);
        } else {
            shop.addEarnings(totalCost); // track revenue for display only
        }
        removeItems(chestInv, material, amount);
        giveItems(customer, material, amount);
        shopManager.save();

        return Result.ok("Bought " + amount + "x " + formatMaterial(materialName)
                + " for " + fmt(totalCost) + ".");
    }

    /**
     * Customer sells items to a shop.
     * Items are physically added to the shop's chest.
     */
    public Result sell(Player customer, Shop shop, String materialName, int amount) {
        ShopItem listing = shop.items().get(materialName);
        if (listing == null || !listing.canSell()) {
            return Result.fail("This shop doesn't buy " + formatMaterial(materialName) + ".");
        }

        Material material;
        try {
            material = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            return Result.fail("Unknown item: " + materialName);
        }

        double totalPayment = listing.sellPrice() * amount;

        // Check customer has the items
        Inventory playerInv = customer.inventory();
        int playerStock = countMaterial(playerInv, material);
        if (playerStock < amount) {
            return Result.fail("You only have " + playerStock + "x " + formatMaterial(materialName) + ".");
        }

        // Check shop can afford to pay (admin and reserve shops always can)
        if (!shop.isAdminShop() && !shop.isReserveShop() && shop.earnings() < totalPayment) {
            return Result.fail("This shop doesn't have enough funds to buy your items.");
        }

        EconomyProvider economy = AlloyAPI.economy().provider();
        if (economy == null) return Result.fail("Economy is not available.");

        // Admin shops don't need chest space
        if (shop.isAdminShop()) {
            removeItems(playerInv, material, amount);
            shop.deductEarnings(totalPayment);
            economy.deposit(customer.uniqueId(), totalPayment);
            shopManager.save();
            return Result.ok("Sold " + amount + "x " + formatMaterial(materialName)
                    + " for " + fmt(totalPayment) + ".");
        }

        // Player shop — add items to the physical chest
        Inventory chestInv = getChestInventory(shop);
        if (chestInv == null) return Result.fail("Shop chest is unavailable.");

        // Execute transaction
        removeItems(playerInv, material, amount);
        ItemStack toAdd = ItemStack.create(material, amount);
        if (toAdd != null) chestInv.addItem(toAdd);
        shop.deductEarnings(totalPayment);
        economy.deposit(customer.uniqueId(), totalPayment);
        shopManager.save();

        return Result.ok("Sold " + amount + "x " + formatMaterial(materialName)
                + " for " + fmt(totalPayment) + ".");
    }

    // ========================================================================
    // Inventory helpers
    // ========================================================================

    private void giveItems(Player player, Material material, int amount) {
        Inventory inv = player.inventory();
        ItemStack toGive = ItemStack.create(material, amount);
        if (toGive != null) inv.addItem(toGive);
    }

    /**
     * Counts how many of a material exist in an inventory.
     */
    public static int countMaterial(Inventory inv, Material material) {
        int count = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack item = inv.item(i);
            if (item != null && !item.isEmpty() && item.type() == material) {
                count += item.amount();
            }
        }
        return count;
    }

    /**
     * Removes a specific amount of a material from an inventory.
     */
    public static void removeItems(Inventory inv, Material material, int amount) {
        int remaining = amount;
        for (int i = 0; i < inv.size() && remaining > 0; i++) {
            ItemStack item = inv.item(i);
            if (item != null && !item.isEmpty() && item.type() == material) {
                if (item.amount() <= remaining) {
                    remaining -= item.amount();
                    inv.setItem(i, null);
                } else {
                    item.setAmount(item.amount() - remaining);
                    inv.setItem(i, item); // write back — inv.item() may return a copy
                    remaining = 0;
                }
            }
        }
    }

    /**
     * Formats a material enum name for display (DIAMOND_SWORD → Diamond Sword).
     */
    public static String formatMaterial(String materialName) {
        String[] parts = materialName.split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(parts[i].charAt(0));
            sb.append(parts[i].substring(1).toLowerCase());
        }
        return sb.toString();
    }
}
