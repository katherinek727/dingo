package net.alloymc.shop.listeners;

import net.alloymc.api.block.Block;
import net.alloymc.api.entity.Player;
import net.alloymc.api.event.EventHandler;
import net.alloymc.api.event.EventPriority;
import net.alloymc.api.event.Listener;
import net.alloymc.api.event.block.BlockBreakEvent;
import net.alloymc.api.event.block.BlockExplodeEvent;
import net.alloymc.shop.data.Shop;
import net.alloymc.shop.data.ShopLocation;
import net.alloymc.shop.data.ShopManager;

/**
 * Protects shop blocks (signs and chests) from being broken by non-owners.
 * Also protects against explosions.
 */
public final class ShopProtectListener implements Listener {

    private final ShopManager shopManager;

    public ShopProtectListener(ShopManager shopManager) {
        this.shopManager = shopManager;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.block();
        ShopLocation loc = ShopLocation.fromBlock(block);

        Shop shop = shopManager.getByLocation(loc);
        if (shop == null) return;

        Player player = event.player();

        // Owner or admin can break
        if (shop.isOwner(player.uniqueId()) || player.hasPermission("alloyshop.admin")) {
            // If owner breaks the sign, remove the shop
            if (shopManager.isShopSign(loc)) {
                shopManager.removeShop(shop.id());
                player.sendMessage("Shop removed.", Player.MessageType.WARNING);
                System.out.println("[AlloyShop] " + player.displayName() + " removed shop " + shop.id());
            }
            // Allow the break
            return;
        }

        // Non-owners cannot break shop blocks
        event.setCancelled(true);
        player.sendMessage("You can't break " + shop.displayTitle() + "'s shop blocks.", Player.MessageType.ERROR);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockExplode(BlockExplodeEvent event) {
        // Protect shop blocks from explosions
        Block block = event.block();
        ShopLocation loc = ShopLocation.fromBlock(block);
        if (shopManager.isShopBlock(loc)) {
            event.setCancelled(true);
        }
    }
}
