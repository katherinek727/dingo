package net.alloymc.shop;

import net.alloymc.api.AlloyAPI;
import net.alloymc.api.permission.PermissionRegistry;
import net.alloymc.loader.api.ModInitializer;
import net.alloymc.shop.commands.ShopCommand;
import net.alloymc.shop.data.ShopManager;
import net.alloymc.shop.listeners.ShopCloseListener;
import net.alloymc.shop.listeners.ShopCreateListener;
import net.alloymc.shop.listeners.ShopInteractListener;
import net.alloymc.shop.listeners.ShopInventoryListener;
import net.alloymc.shop.listeners.ShopProtectListener;
import net.alloymc.shop.transaction.ShopTransaction;

import java.nio.file.Path;

/**
 * AlloyShop — GUI-based chest shop system for Alloy.
 *
 * Features:
 * - Sign + chest based shop creation
 * - GUI-based shopping (CustomInventory chest GUIs)
 * - Left-click to buy, right-click to sell, shift for bulk
 * - Owner management GUI (sneak + right-click)
 * - Virtual stock with buy/sell prices
 * - Economy integration (works with any EconomyProvider)
 * - Shop protection (signs + chests)
 * - Admin shops with infinite stock
 * - Comprehensive /shop command with tab completion
 */
public final class AlloyShopMod implements ModInitializer {

    @Override
    public void onInitialize() {
        System.out.println("[AlloyShop] Initializing AlloyShop...");

        // Load config
        Path dataDir = AlloyAPI.server().dataDirectory().resolve("alloy-shop");
        ShopConfig config = ShopConfig.load(dataDir);

        // Initialize shop manager (loads persisted shops)
        ShopManager shopManager = new ShopManager(dataDir);

        // Initialize transaction handler
        ShopTransaction transactions = new ShopTransaction(shopManager);

        // Initialize listeners
        ShopCreateListener createListener = new ShopCreateListener(shopManager);
        ShopInventoryListener inventoryListener = new ShopInventoryListener(shopManager, transactions);
        ShopInteractListener interactListener = new ShopInteractListener(shopManager, inventoryListener);
        ShopCloseListener closeListener = new ShopCloseListener(inventoryListener);
        ShopProtectListener protectListener = new ShopProtectListener(shopManager);

        // Register event listeners
        var eventBus = AlloyAPI.eventBus();
        eventBus.register(createListener);
        eventBus.register(inventoryListener);
        eventBus.register(interactListener);
        eventBus.register(closeListener);
        eventBus.register(protectListener);

        // Register permissions
        PermissionRegistry perms = AlloyAPI.permissionRegistry();
        perms.register("alloyshop.use", "Use shop commands", PermissionRegistry.PermissionDefault.TRUE);
        perms.register("alloyshop.create", "Create shops", PermissionRegistry.PermissionDefault.TRUE);
        perms.register("alloyshop.admin", "Admin shop management", PermissionRegistry.PermissionDefault.OP);

        // Register commands (share lastViewedShop map between listener and command)
        ShopCommand shopCommand = new ShopCommand(shopManager, transactions, config,
                interactListener.lastViewedShop());
        AlloyAPI.commandRegistry().register(shopCommand);

        System.out.println("[AlloyShop] Loaded " + shopManager.shopCount() + " shops");
        System.out.println("[AlloyShop] AlloyShop initialized with GUI support. Use [Shop] signs to create shops.");
    }
}
