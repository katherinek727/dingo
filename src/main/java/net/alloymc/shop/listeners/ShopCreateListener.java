package net.alloymc.shop.listeners;

import net.alloymc.api.block.Block;
import net.alloymc.api.block.BlockFace;
import net.alloymc.api.entity.Player;
import net.alloymc.api.event.EventHandler;
import net.alloymc.api.event.Listener;
import net.alloymc.api.event.block.SignChangeEvent;
import net.alloymc.api.inventory.Material;
import net.alloymc.shop.data.Shop;
import net.alloymc.shop.data.ShopLocation;
import net.alloymc.shop.data.ShopManager;

/**
 * Listens for sign creation to auto-create shops.
 *
 * <p>Sign formats:
 * <ul>
 *   <li><b>Player shop</b>: Line 1 = [Shop]</li>
 *   <li><b>Admin shop</b>: Line 1 = [AdminShop]</li>
 *   <li><b>Server/Reserve shop</b>: Line 1 = [Shop], Line 2 = reserve</li>
 * </ul>
 *
 * <p>Reserve shops are server-owned: no player owner, stock from physical chest,
 * payments go to the server reserve wallet. Any OP can manage them.
 */
public final class ShopCreateListener implements Listener {

    private final ShopManager shopManager;

    public ShopCreateListener(ShopManager shopManager) {
        this.shopManager = shopManager;
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        String firstLine = event.line(0);
        if (firstLine == null) return;

        String trimmed = firstLine.trim();
        if (!trimmed.equalsIgnoreCase("[Shop]") && !trimmed.equalsIgnoreCase("[AdminShop]")) {
            return;
        }

        boolean adminShop = trimmed.equalsIgnoreCase("[AdminShop]");
        Player player = event.player();

        // Check if line 2 says "reserve" — server/reserve shop
        String secondLine = event.line(1);
        boolean reserveShop = !adminShop
                && secondLine != null
                && secondLine.trim().equalsIgnoreCase("reserve");

        // Permission checks
        if (!player.hasPermission("alloyshop.create")) {
            player.sendMessage("You don't have permission to create shops.", Player.MessageType.ERROR);
            event.setCancelled(true);
            return;
        }

        if ((adminShop || reserveShop) && !player.hasPermission("alloyshop.admin")) {
            String type = reserveShop ? "server" : "admin";
            player.sendMessage("You don't have permission to create " + type + " shops.", Player.MessageType.ERROR);
            event.setCancelled(true);
            return;
        }

        Block signBlock = event.block();
        ShopLocation signLoc = ShopLocation.fromBlock(signBlock);

        if (shopManager.isShopSign(signLoc)) {
            player.sendMessage("A shop already exists at this sign.", Player.MessageType.ERROR);
            event.setCancelled(true);
            return;
        }

        // Find adjacent chest/barrel
        ShopLocation chestLoc = findAdjacentContainer(signBlock);
        if (chestLoc == null) {
            player.sendMessage("Place this sign on or next to a chest or barrel.", Player.MessageType.ERROR);
            event.setCancelled(true);
            return;
        }

        if (shopManager.isShopChest(chestLoc)) {
            player.sendMessage("That chest is already used by another shop.", Player.MessageType.ERROR);
            event.setCancelled(true);
            return;
        }

        // Create the shop
        Shop shop;
        if (reserveShop) {
            shop = shopManager.createShop(null, "Server", signLoc, chestLoc, false, true);
            event.setLine(0, "[Shop]");
            event.setLine(1, "Server Shop");
            event.setLine(2, "Right-click to browse");
            event.setLine(3, "Reserve-backed");
        } else {
            shop = shopManager.createShop(
                    player.uniqueId(), player.displayName(),
                    signLoc, chestLoc, adminShop, false);
            event.setLine(0, adminShop ? "[AdminShop]" : "[Shop]");
            event.setLine(1, player.displayName());
            event.setLine(2, "Right-click to browse");
            event.setLine(3, "/shop price to configure");
        }

        String shopType = reserveShop ? "Server shop" : (adminShop ? "Admin shop" : "Shop");
        player.sendMessage(shopType + " created! ID: " + shop.id(), Player.MessageType.SUCCESS);
        player.sendMessage("Use /shop price <item> <buyPrice> [sellPrice] to add items.", Player.MessageType.INFO);
        System.out.println("[AlloyShop] " + player.displayName() + " created "
                + shopType.toLowerCase() + " " + shop.id() + " at " + signLoc);
    }

    private ShopLocation findAdjacentContainer(Block signBlock) {
        BlockFace[] faces = { BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
                              BlockFace.UP, BlockFace.DOWN };
        for (BlockFace face : faces) {
            Block adjacent = signBlock.getRelative(face);
            if (isContainer(adjacent.type())) {
                return ShopLocation.fromBlock(adjacent);
            }
        }
        return null;
    }

    private boolean isContainer(Material material) {
        return material == Material.CHEST
                || material == Material.TRAPPED_CHEST
                || material == Material.BARREL;
    }
}
