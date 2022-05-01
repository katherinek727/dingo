package net.alloymc.shop.listeners;

import net.alloymc.api.entity.Player;
import net.alloymc.api.event.EventHandler;
import net.alloymc.api.event.Listener;
import net.alloymc.api.event.inventory.InventoryCloseEvent;

/**
 * Handles inventory close events for shop GUIs.
 * Cleans up tracking state when a player closes a shop inventory.
 */
public final class ShopCloseListener implements Listener {

    private final ShopInventoryListener inventoryListener;

    public ShopCloseListener(ShopInventoryListener inventoryListener) {
        this.inventoryListener = inventoryListener;
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Player player = event.player();
        if (inventoryListener.hasShopOpen(player.uniqueId())) {
            inventoryListener.trackClose(player.uniqueId());
        }
    }
}
