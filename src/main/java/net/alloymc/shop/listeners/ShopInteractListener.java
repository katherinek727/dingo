package net.alloymc.shop.listeners;

import net.alloymc.api.block.Block;
import net.alloymc.api.entity.Player;
import net.alloymc.api.event.EventHandler;
import net.alloymc.api.event.EventPriority;
import net.alloymc.api.event.Listener;
import net.alloymc.api.event.player.PlayerInteractEvent;
import net.alloymc.shop.data.Shop;
import net.alloymc.shop.data.ShopLocation;
import net.alloymc.shop.data.ShopManager;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles right-click interactions with shop signs and chests.
 *
 * <p>For player shops: owner clicks → owner menu, everyone else → customer menu.
 * <p>For reserve/server shops: normal click → customer menu (for everyone),
 *    sneak+click → owner menu (OPs only).
 */
public final class ShopInteractListener implements Listener {

    private final ShopManager shopManager;
    private final ShopInventoryListener inventoryListener;

    /** Tracks last shop each player viewed */
    private final ConcurrentHashMap<UUID, String> lastViewedShop = new ConcurrentHashMap<>();

    public ShopInteractListener(ShopManager shopManager, ShopInventoryListener inventoryListener) {
        this.shopManager = shopManager;
        this.inventoryListener = inventoryListener;
    }

    public ConcurrentHashMap<UUID, String> lastViewedShop() {
        return lastViewedShop;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.action() != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) return;
        if (!event.hasBlock()) return;

        Block block = event.clickedBlock();
        ShopLocation loc = ShopLocation.fromBlock(block);

        // Check if this is a shop sign
        Shop shopBySign = shopManager.getBySign(loc);
        if (shopBySign != null) {
            event.setCancelled(true);
            Player player = event.player();

            lastViewedShop.put(player.uniqueId(), shopBySign.id());

            if (shopBySign.isReserveShop()) {
                // Reserve shops: sneak+click = owner menu (OPs), normal click = customer
                if (player.isSneaking() && player.hasPermission("alloyshop.admin")) {
                    inventoryListener.openOwnerMenu(player, shopBySign);
                } else {
                    inventoryListener.openCustomerMenu(player, shopBySign);
                }
            } else if (shopBySign.isOwner(player.uniqueId())
                    || player.hasPermission("alloyshop.admin")) {
                inventoryListener.openOwnerMenu(player, shopBySign);
            } else {
                inventoryListener.openCustomerMenu(player, shopBySign);
            }
            return;
        }

        // Check if this is a shop chest (prevent non-owners from opening)
        Shop shopByChest = shopManager.getByChest(loc);
        if (shopByChest != null) {
            Player player = event.player();
            if (!shopByChest.isOwner(player.uniqueId()) && !player.hasPermission("alloyshop.admin")) {
                event.setCancelled(true);
                player.sendMessage("This chest belongs to " + shopByChest.displayTitle() + ".",
                        Player.MessageType.WARNING);
            }
        }
    }
}
