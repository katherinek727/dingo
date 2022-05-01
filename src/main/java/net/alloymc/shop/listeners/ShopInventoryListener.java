package net.alloymc.shop.listeners;

import net.alloymc.api.AlloyAPI;
import net.alloymc.api.economy.EconomyProvider;
import net.alloymc.api.entity.Player;
import net.alloymc.api.event.EventHandler;
import net.alloymc.api.event.EventPriority;
import net.alloymc.api.event.Listener;
import net.alloymc.api.event.inventory.ClickAction;
import net.alloymc.api.event.inventory.MenuClickEvent;
import net.alloymc.api.gui.MenuInstance;
import net.alloymc.api.gui.MenuLayout;
import net.alloymc.api.gui.SlotDefinition;
import net.alloymc.api.gui.SlotType;
import net.alloymc.api.inventory.Inventory;
import net.alloymc.api.inventory.ItemStack;
import net.alloymc.api.inventory.Material;
import net.alloymc.shop.data.Shop;
import net.alloymc.shop.data.ShopItem;
import net.alloymc.shop.data.ShopManager;
import net.alloymc.shop.transaction.ShopTransaction;
import net.alloymc.shop.transaction.ShopTransaction.ChestStock;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles all GUI interactions for shop menus.
 *
 * <p>Items displayed in shop GUIs are read directly from the physical chest
 * behind the sign — one icon per unique material with an aggregate count.
 * Buying removes items from the chest; selling adds items to the chest.
 *
 * <p>Six GUI modes:
 * <ul>
 *   <li><b>Customer Browse</b> (3 rows): Shows buyable items from the chest.
 *       Click an item to open the Purchase screen. "Sell Items" button switches
 *       to the Sell Browse.</li>
 *   <li><b>Sell Browse</b> (3 rows): Shows items the shop will buy that the
 *       player has in inventory. Click to open Sell screen.</li>
 *   <li><b>Purchase</b> (3 rows): Selected item with quantity selector and Buy button.</li>
 *   <li><b>Sell</b> (3 rows): Selected item with quantity selector and Sell button.</li>
 *   <li><b>Owner</b> (4 rows): Shows all unique materials in the chest.
 *       Click an item to open the Price Editor. Collect Earnings in bottom row.</li>
 *   <li><b>Price Editor</b> (4 rows): +/- buttons for buy and sell prices
 *       with $0.01, $0.05, $0.10, $1.00 increments.</li>
 * </ul>
 */
public final class ShopInventoryListener implements Listener {

    private final ShopManager shopManager;
    private final ShopTransaction transactions;

    private final ConcurrentHashMap<UUID, String> openShopMenus = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ViewMode> viewModes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> editingMaterial = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, MenuInstance> menuInstances = new ConcurrentHashMap<>();

    /** Cached stock snapshot for the currently open menu */
    private final ConcurrentHashMap<UUID, List<ChestStock>> cachedStock = new ConcurrentHashMap<>();

    /** Purchase screen state: selected material and quantity */
    private final ConcurrentHashMap<UUID, String> purchaseMaterial = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> purchaseQuantity = new ConcurrentHashMap<>();

    /** Sell screen state: selected material and quantity */
    private final ConcurrentHashMap<UUID, String> sellMaterial = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> sellQuantity = new ConcurrentHashMap<>();

    private enum ViewMode { CUSTOMER, PURCHASE, SELL_BROWSE, SELL, OWNER, PRICE_EDITOR }

    public ShopInventoryListener(ShopManager shopManager, ShopTransaction transactions) {
        this.shopManager = shopManager;
        this.transactions = transactions;
    }

    // ========================================================================
    // Currency helpers — use the active economy's symbol
    // ========================================================================

    private static String sym() {
        var reg = AlloyAPI.economy();
        return reg != null ? reg.currencySymbol() : "$";
    }

    private static String fmt(double amount) {
        var reg = AlloyAPI.economy();
        return reg != null ? reg.formatAmount(amount) : "$" + String.format("%.2f", amount);
    }

    // ========================================================================
    // Public API — called by ShopInteractListener
    // ========================================================================

    public void openCustomerMenu(Player player, Shop shop) {
        Inventory chestInv = ShopTransaction.getChestInventory(shop, player.world());
        if (chestInv == null) {
            player.sendMessage("Shop chest is unavailable.", Player.MessageType.ERROR);
            return;
        }

        List<ChestStock> stock = ShopTransaction.readChestContents(chestInv);
        // Filter to materials that have a buy price set
        List<ChestStock> priced = stock.stream()
                .filter(s -> {
                    ShopItem item = shop.items().get(s.materialName());
                    return item != null && item.buyPrice() > 0;
                })
                .toList();

        // Check if shop has any sellable items (for showing sell button)
        boolean hasSellItems = shop.items().values().stream().anyMatch(ShopItem::canSell);

        if (priced.isEmpty() && !hasSellItems) {
            player.sendMessage("This shop has no items for sale.", Player.MessageType.WARNING);
            return;
        }

        int itemCount = Math.min(priced.size(), 18);
        List<Integer> extraSlots = new ArrayList<>();
        if (hasSellItems) extraSlots.add(18);
        extraSlots.add(26);
        MenuLayout layout = buildCustomerLayout(shop.displayTitle(), itemCount, extraSlots);
        MenuInstance menu = player.openMenu(layout);
        if (menu == null) {
            player.sendMessage("Failed to open shop.", Player.MessageType.ERROR);
            return;
        }

        populateCustomerItems(menu, priced, shop);
        if (hasSellItems) {
            setButton(menu, 18, Material.GOLD_NUGGET, "Sell Items",
                    List.of("Click to sell items to this shop"));
        }
        setButton(menu, 26, Material.BARRIER, "Close", List.of("Click to close"));

        cachedStock.put(player.uniqueId(), priced);
        track(player.uniqueId(), shop.id(), ViewMode.CUSTOMER, menu);
    }

    public void openOwnerMenu(Player player, Shop shop) {
        Inventory chestInv = ShopTransaction.getChestInventory(shop, player.world());
        if (chestInv == null) {
            player.sendMessage("Shop chest is unavailable.", Player.MessageType.ERROR);
            return;
        }

        List<ChestStock> stock = ShopTransaction.readChestContents(chestInv);

        MenuLayout layout = buildOwnerLayout(shop.displayTitle() + " [Owner]", stock.size());
        MenuInstance menu = player.openMenu(layout);
        if (menu == null) {
            player.sendMessage("Failed to open shop management.", Player.MessageType.ERROR);
            return;
        }

        populateOwnerItems(menu, stock, shop);
        if (shop.isReserveShop()) {
            setButton(menu, 31, Material.GOLD_INGOT, "Total Revenue",
                    List.of("Revenue: " + fmt(shop.earnings()),
                            "Payments go to server reserve"));
        } else {
            setButton(menu, 31, Material.GOLD_INGOT, "Collect Earnings",
                    List.of("Pending: " + fmt(shop.earnings()),
                            "Click to collect all earnings"));
        }
        setButton(menu, 35, Material.BARRIER, "Close", List.of("Click to close"));

        cachedStock.put(player.uniqueId(), stock);
        track(player.uniqueId(), shop.id(), ViewMode.OWNER, menu);
    }

    public boolean hasShopOpen(UUID playerId) {
        return openShopMenus.containsKey(playerId);
    }

    public void trackClose(UUID playerId) {
        openShopMenus.remove(playerId);
        viewModes.remove(playerId);
        editingMaterial.remove(playerId);
        menuInstances.remove(playerId);
        cachedStock.remove(playerId);
        purchaseMaterial.remove(playerId);
        purchaseQuantity.remove(playerId);
        sellMaterial.remove(playerId);
        sellQuantity.remove(playerId);
    }

    // ========================================================================
    // Internal tracking
    // ========================================================================

    private void track(UUID playerId, String shopId, ViewMode mode, MenuInstance menu) {
        openShopMenus.put(playerId, shopId);
        viewModes.put(playerId, mode);
        menuInstances.put(playerId, menu);
        if (mode != ViewMode.PRICE_EDITOR) editingMaterial.remove(playerId);
        if (mode != ViewMode.PURCHASE) {
            purchaseMaterial.remove(playerId);
            purchaseQuantity.remove(playerId);
        }
        if (mode != ViewMode.SELL) {
            sellMaterial.remove(playerId);
            sellQuantity.remove(playerId);
        }
    }

    // ========================================================================
    // Event Handler
    // ========================================================================

    @EventHandler(priority = EventPriority.HIGH)
    public void onMenuClick(MenuClickEvent event) {
        Player player = event.player();
        UUID playerId = player.uniqueId();

        String shopId = openShopMenus.get(playerId);
        if (shopId == null) return;

        Shop shop = shopManager.getById(shopId);
        if (shop == null) {
            player.sendMessage("This shop no longer exists.", Player.MessageType.ERROR);
            player.closeInventory();
            return;
        }

        ViewMode mode = viewModes.getOrDefault(playerId, ViewMode.CUSTOMER);
        MenuInstance menu = event.menu();
        int rawSlot = event.rawSlot();
        SlotDefinition slotDef = event.slot();

        switch (mode) {
            case CUSTOMER -> handleCustomerClick(player, shop, menu, rawSlot, slotDef, event.action());
            case PURCHASE -> handlePurchaseClick(player, shop, menu, rawSlot, slotDef, event.action());
            case SELL_BROWSE -> handleSellBrowseClick(player, shop, menu, rawSlot, slotDef, event.action());
            case SELL -> handleSellClick(player, shop, menu, rawSlot, slotDef, event.action());
            case OWNER -> handleOwnerClick(player, shop, menu, rawSlot, slotDef, event.action());
            case PRICE_EDITOR -> {
                String mat = editingMaterial.get(playerId);
                if (mat != null) handlePriceEditorClick(player, shop, menu, rawSlot, slotDef, mat);
            }
        }
    }

    // ========================================================================
    // CUSTOMER BROWSE MENU (3 rows = 27 slots)
    // ========================================================================
    // Slots 0-17: Buyable items from the chest — click to open purchase screen
    // Slot 18: Sell Items (if shop has any sell prices)
    // Slot 26: Close

    private void handleCustomerClick(Player player, Shop shop, MenuInstance menu,
                                     int rawSlot, SlotDefinition slotDef, ClickAction action) {
        if (slotDef == null) return;
        int slot = slotDef.index();

        if (slot == 26) { player.closeInventory(); return; }

        if (slot == 18) {
            // Switch to sell browse
            openSellBrowseMenu(player, shop);
            return;
        }

        if (slot >= 0 && slot < 18) {
            List<ChestStock> stock = cachedStock.get(player.uniqueId());
            if (stock == null || slot >= stock.size()) return;

            ChestStock entry = stock.get(slot);
            ShopItem listing = shop.items().get(entry.materialName());
            if (listing == null || listing.buyPrice() <= 0) return;

            // Open purchase screen for this item
            openPurchaseMenu(player, shop, entry.materialName(), entry.count());
        }
    }

    // ========================================================================
    // SELL BROWSE MENU (3 rows = 27 slots)
    // ========================================================================
    // Slots 0-17: Items the shop buys that the player has in inventory
    // Slot 18: Buy Items (switch back to customer browse)
    // Slot 26: Close

    private void openSellBrowseMenu(Player player, Shop shop) {
        // Find items the shop will buy that the player has in inventory
        Inventory playerInv = player.inventory();
        List<ChestStock> sellable = new ArrayList<>();

        for (var entry : shop.items().entrySet()) {
            ShopItem listing = entry.getValue();
            if (!listing.canSell()) continue;

            String matName = entry.getKey();
            try {
                Material mat = Material.valueOf(matName);
                int playerCount = ShopTransaction.countMaterial(playerInv, mat);
                if (playerCount > 0) {
                    sellable.add(new ChestStock(mat, matName, playerCount));
                }
            } catch (Exception ignored) {}
        }

        int itemCount = Math.min(sellable.size(), 18);
        MenuLayout layout = buildCustomerLayout(shop.displayTitle() + " [Sell]", itemCount, List.of(18, 26));
        MenuInstance menu = player.openMenu(layout);
        if (menu == null) return;

        populateSellBrowseItems(menu, sellable, shop);
        setButton(menu, 18, Material.EMERALD, "Buy Items",
                List.of("Click to browse items for sale"));
        setButton(menu, 26, Material.BARRIER, "Close", List.of("Click to close"));

        cachedStock.put(player.uniqueId(), sellable);
        track(player.uniqueId(), shop.id(), ViewMode.SELL_BROWSE, menu);
    }

    private static void populateSellBrowseItems(MenuInstance menu, List<ChestStock> sellable, Shop shop) {
        int count = Math.min(sellable.size(), 18);
        for (int i = 0; i < count; i++) {
            ChestStock entry = sellable.get(i);
            ShopItem listing = shop.items().get(entry.materialName());
            if (listing == null) continue;

            try {
                int displayAmt = Math.max(1, Math.min(entry.count(), 64));
                ItemStack stack = ItemStack.create(entry.material(), displayAmt);
                if (stack != null) {
                    stack.setDisplayName(ShopTransaction.formatMaterial(entry.materialName()));
                    List<String> lore = new ArrayList<>();
                    lore.add("Sell price: " + fmt(listing.sellPrice()) + " each");
                    lore.add("You have: " + entry.count());
                    lore.add("");
                    lore.add("Click to sell");
                    stack.setLore(lore);
                    menu.setItem(i, stack);
                }
            } catch (Exception ignored) {}
        }
    }

    private void handleSellBrowseClick(Player player, Shop shop, MenuInstance menu,
                                       int rawSlot, SlotDefinition slotDef, ClickAction action) {
        if (slotDef == null) return;
        int slot = slotDef.index();

        if (slot == 26) { player.closeInventory(); return; }

        if (slot == 18) {
            // Switch back to buy browse
            openCustomerMenu(player, shop);
            return;
        }

        if (slot >= 0 && slot < 18) {
            List<ChestStock> stock = cachedStock.get(player.uniqueId());
            if (stock == null || slot >= stock.size()) return;

            ChestStock entry = stock.get(slot);
            ShopItem listing = shop.items().get(entry.materialName());
            if (listing == null || !listing.canSell()) return;

            openSellMenu(player, shop, entry.materialName(), entry.count());
        }
    }

    // ========================================================================
    // PURCHASE MENU (3 rows = 27 slots)
    // ========================================================================
    // Row 1: [...] [...] [...] [...] [ITEM=4] [...] [...] [...] [...]
    // Row 2: [-16=9] [-1=10] [...] [Qty=12] [...] [+1=14] [+16=15] [...] [Total=17]
    // Row 3: [Back=18] [...] [...] [...] [BUY=22] [...] [...] [...] [Close=26]

    private static final int PU_ITEM = 4;
    private static final int PU_DOWN_16 = 9, PU_DOWN_1 = 10, PU_QTY = 12;
    private static final int PU_UP_1 = 14, PU_UP_16 = 15, PU_TOTAL = 17;
    private static final int PU_BACK = 18, PU_BUY = 22, PU_CLOSE = 26;

    private void openPurchaseMenu(Player player, Shop shop, String materialName, int available) {
        ShopItem listing = shop.items().get(materialName);
        if (listing == null) return;

        String displayName = ShopTransaction.formatMaterial(materialName);
        int startQty = 1;

        MenuLayout layout = buildPurchaseLayout("Buy: " + displayName);
        MenuInstance menu = player.openMenu(layout);
        if (menu == null) return;

        // Set purchase state before tracking (track clears it for non-PURCHASE modes)
        purchaseMaterial.put(player.uniqueId(), materialName);
        purchaseQuantity.put(player.uniqueId(), startQty);
        track(player.uniqueId(), shop.id(), ViewMode.PURCHASE, menu);

        populatePurchaseMenu(menu, listing, materialName, startQty, available, player);
    }

    private void populatePurchaseMenu(MenuInstance menu, ShopItem listing, String materialName,
                                      int quantity, int available, Player player) {
        String displayName = ShopTransaction.formatMaterial(materialName);
        double unitPrice = listing.buyPrice();
        double totalCost = unitPrice * quantity;

        // Selected item display (center of row 1)
        try {
            Material mat = Material.valueOf(materialName);
            int displayAmt = Math.max(1, Math.min(quantity, 64));
            ItemStack display = ItemStack.create(mat, displayAmt);
            if (display != null) {
                display.setDisplayName(displayName);
                display.setLore(List.of(
                        "Price: " + fmt(unitPrice) + " each",
                        "In stock: " + available));
                menu.setItem(PU_ITEM, display);
            }
        } catch (Exception ignored) {}

        // Quantity controls
        setButton(menu, PU_DOWN_16, Material.REDSTONE, "-16",
                List.of("Decrease quantity by 16"));
        setButton(menu, PU_DOWN_1, Material.REDSTONE, "-1",
                List.of("Decrease quantity by 1"));

        // Quantity display
        try {
            int displayQty = Math.max(1, Math.min(quantity, 64));
            ItemStack qtyItem = ItemStack.create(Material.PAPER, displayQty);
            if (qtyItem != null) {
                qtyItem.setDisplayName("Quantity: " + quantity);
                qtyItem.setLore(List.of("Max available: " + available));
                menu.setItem(PU_QTY, qtyItem);
            }
        } catch (Exception ignored) {}

        setButton(menu, PU_UP_1, Material.EMERALD, "+1",
                List.of("Increase quantity by 1"));
        setButton(menu, PU_UP_16, Material.EMERALD, "+16",
                List.of("Increase quantity by 16"));

        // Total cost display with player balance
        EconomyProvider economy = AlloyAPI.economy() != null ? AlloyAPI.economy().provider() : null;
        double balance = economy != null ? economy.getBalance(player.uniqueId()) : 0;
        setButton(menu, PU_TOTAL, Material.GOLD_INGOT, "Total: " + fmt(totalCost),
                List.of(fmt(unitPrice) + " x " + quantity,
                        "",
                        "Your balance: " + fmt(balance)));

        // Back button
        setButton(menu, PU_BACK, Material.ARROW, "Back",
                List.of("Return to item list"));

        // Buy button — green if affordable, red if not
        boolean canAfford = balance >= totalCost;
        boolean hasStock = available >= quantity;
        Material buyMat = (canAfford && hasStock) ? Material.EMERALD_BLOCK : Material.BARRIER;
        List<String> buyLore = new ArrayList<>();
        buyLore.add("Buy " + quantity + "x " + displayName);
        buyLore.add("for " + fmt(totalCost));
        if (!canAfford) buyLore.add("Insufficient funds!");
        if (!hasStock) buyLore.add("Not enough stock!");
        setButton(menu, PU_BUY, buyMat, canAfford && hasStock ? "Buy" : "Cannot Buy", buyLore);

        // Close button
        setButton(menu, PU_CLOSE, Material.BARRIER, "Close", List.of("Click to close"));
    }

    private void handlePurchaseClick(Player player, Shop shop, MenuInstance menu,
                                     int rawSlot, SlotDefinition slotDef, ClickAction action) {
        if (slotDef == null) return;
        int slot = slotDef.index();
        UUID playerId = player.uniqueId();

        String materialName = purchaseMaterial.get(playerId);
        if (materialName == null) { player.closeInventory(); return; }

        int currentQty = purchaseQuantity.getOrDefault(playerId, 1);

        // Get available stock from chest
        Inventory chestInv = ShopTransaction.getChestInventory(shop, player.world());
        int available = 0;
        if (chestInv != null) {
            try {
                Material mat = Material.valueOf(materialName);
                available = ShopTransaction.countMaterial(chestInv, mat);
            } catch (Exception ignored) {}
        }

        switch (slot) {
            case PU_CLOSE -> { player.closeInventory(); return; }
            case PU_BACK -> { openCustomerMenu(player, shop); return; }
            case PU_DOWN_16 -> currentQty = Math.max(1, currentQty - 16);
            case PU_DOWN_1 -> currentQty = Math.max(1, currentQty - 1);
            case PU_UP_1 -> currentQty = Math.min(Math.max(1, available), currentQty + 1);
            case PU_UP_16 -> currentQty = Math.min(Math.max(1, available), currentQty + 16);
            case PU_BUY -> {
                // Execute the purchase
                ShopTransaction.Result result = transactions.buy(player, shop, materialName, currentQty);
                player.sendMessage(result.message(),
                        result.success() ? Player.MessageType.SUCCESS : Player.MessageType.ERROR);

                if (result.success()) {
                    // Go back to browse menu with refreshed stock
                    openCustomerMenu(player, shop);
                } else {
                    // Refresh purchase screen (stock/balance may have changed)
                    ShopItem listing = shop.items().get(materialName);
                    if (listing != null) {
                        int newAvailable = 0;
                        Inventory freshChest = ShopTransaction.getChestInventory(shop, player.world());
                        if (freshChest != null) {
                            try {
                                Material mat = Material.valueOf(materialName);
                                newAvailable = ShopTransaction.countMaterial(freshChest, mat);
                            } catch (Exception ignored) {}
                        }
                        currentQty = Math.min(currentQty, Math.max(1, newAvailable));
                        purchaseQuantity.put(playerId, currentQty);
                        clearSlots(menu, 27);
                        populatePurchaseMenu(menu, listing, materialName, currentQty, newAvailable, player);
                    }
                }
                return;
            }
            default -> { return; }
        }

        // Update quantity and refresh display
        if (currentQty < 1) currentQty = 1;
        purchaseQuantity.put(playerId, currentQty);
        ShopItem listing = shop.items().get(materialName);
        if (listing != null) {
            clearSlots(menu, 27);
            populatePurchaseMenu(menu, listing, materialName, currentQty, available, player);
        }
    }

    // ========================================================================
    // SELL MENU (3 rows = 27 slots) — mirrors PURCHASE layout
    // ========================================================================
    // Row 1: [...] [...] [...] [...] [ITEM=4] [...] [...] [...] [...]
    // Row 2: [-16=9] [-1=10] [...] [Qty=12] [...] [+1=14] [+16=15] [...] [Total=17]
    // Row 3: [Back=18] [...] [...] [...] [SELL=22] [...] [...] [...] [Close=26]

    // Reuses PU_* slot constants since the layout is identical

    private void openSellMenu(Player player, Shop shop, String materialName, int playerHas) {
        ShopItem listing = shop.items().get(materialName);
        if (listing == null || !listing.canSell()) return;

        String displayName = ShopTransaction.formatMaterial(materialName);
        int startQty = 1;

        MenuLayout layout = buildPurchaseLayout("Sell: " + displayName);
        MenuInstance menu = player.openMenu(layout);
        if (menu == null) return;

        sellMaterial.put(player.uniqueId(), materialName);
        sellQuantity.put(player.uniqueId(), startQty);
        track(player.uniqueId(), shop.id(), ViewMode.SELL, menu);

        populateSellMenu(menu, listing, materialName, startQty, playerHas, player, shop);
    }

    private void populateSellMenu(MenuInstance menu, ShopItem listing, String materialName,
                                  int quantity, int playerHas, Player player, Shop shop) {
        String displayName = ShopTransaction.formatMaterial(materialName);
        double unitPrice = listing.sellPrice();
        double totalPayment = unitPrice * quantity;

        // Selected item display (center of row 1)
        try {
            Material mat = Material.valueOf(materialName);
            int displayAmt = Math.max(1, Math.min(quantity, 64));
            ItemStack display = ItemStack.create(mat, displayAmt);
            if (display != null) {
                display.setDisplayName(displayName);
                display.setLore(List.of(
                        "Sell price: " + fmt(unitPrice) + " each",
                        "You have: " + playerHas));
                menu.setItem(PU_ITEM, display);
            }
        } catch (Exception ignored) {}

        // Quantity controls
        setButton(menu, PU_DOWN_16, Material.REDSTONE, "-16",
                List.of("Decrease quantity by 16"));
        setButton(menu, PU_DOWN_1, Material.REDSTONE, "-1",
                List.of("Decrease quantity by 1"));

        // Quantity display
        try {
            int displayQty = Math.max(1, Math.min(quantity, 64));
            ItemStack qtyItem = ItemStack.create(Material.PAPER, displayQty);
            if (qtyItem != null) {
                qtyItem.setDisplayName("Quantity: " + quantity);
                qtyItem.setLore(List.of("You have: " + playerHas));
                menu.setItem(PU_QTY, qtyItem);
            }
        } catch (Exception ignored) {}

        setButton(menu, PU_UP_1, Material.EMERALD, "+1",
                List.of("Increase quantity by 1"));
        setButton(menu, PU_UP_16, Material.EMERALD, "+16",
                List.of("Increase quantity by 16"));

        // Total payment display
        boolean shopCanPay = shop.isAdminShop() || shop.isReserveShop()
                || shop.earnings() >= totalPayment;
        List<String> totalLore = new ArrayList<>();
        totalLore.add(fmt(unitPrice) + " x " + quantity);
        totalLore.add("");
        totalLore.add("You receive: " + fmt(totalPayment));
        setButton(menu, PU_TOTAL, Material.GOLD_INGOT, "Total: " + fmt(totalPayment), totalLore);

        // Back button
        setButton(menu, PU_BACK, Material.ARROW, "Back",
                List.of("Return to sell list"));

        // Sell button — green if possible, red if not
        boolean hasItems = playerHas >= quantity;
        boolean canSell = hasItems && shopCanPay;
        Material sellMat = canSell ? Material.EMERALD_BLOCK : Material.BARRIER;
        List<String> sellLore = new ArrayList<>();
        sellLore.add("Sell " + quantity + "x " + displayName);
        sellLore.add("for " + fmt(totalPayment));
        if (!hasItems) sellLore.add("You don't have enough!");
        if (!shopCanPay) sellLore.add("Shop can't afford this!");
        setButton(menu, PU_BUY, sellMat, canSell ? "Sell" : "Cannot Sell", sellLore);

        // Close button
        setButton(menu, PU_CLOSE, Material.BARRIER, "Close", List.of("Click to close"));
    }

    private void handleSellClick(Player player, Shop shop, MenuInstance menu,
                                 int rawSlot, SlotDefinition slotDef, ClickAction action) {
        if (slotDef == null) return;
        int slot = slotDef.index();
        UUID playerId = player.uniqueId();

        String materialName = sellMaterial.get(playerId);
        if (materialName == null) { player.closeInventory(); return; }

        int currentQty = sellQuantity.getOrDefault(playerId, 1);

        // Get player's current stock of this item
        int playerHas = 0;
        try {
            Material mat = Material.valueOf(materialName);
            playerHas = ShopTransaction.countMaterial(player.inventory(), mat);
        } catch (Exception ignored) {}

        switch (slot) {
            case PU_CLOSE -> { player.closeInventory(); return; }
            case PU_BACK -> { openSellBrowseMenu(player, shop); return; }
            case PU_DOWN_16 -> currentQty = Math.max(1, currentQty - 16);
            case PU_DOWN_1 -> currentQty = Math.max(1, currentQty - 1);
            case PU_UP_1 -> currentQty = Math.min(Math.max(1, playerHas), currentQty + 1);
            case PU_UP_16 -> currentQty = Math.min(Math.max(1, playerHas), currentQty + 16);
            case PU_BUY -> {
                // Execute the sell
                ShopTransaction.Result result = transactions.sell(player, shop, materialName, currentQty);
                player.sendMessage(result.message(),
                        result.success() ? Player.MessageType.SUCCESS : Player.MessageType.ERROR);

                if (result.success()) {
                    // Go back to sell browse with refreshed stock
                    openSellBrowseMenu(player, shop);
                } else {
                    // Refresh sell screen
                    ShopItem listing = shop.items().get(materialName);
                    if (listing != null) {
                        int newPlayerHas = 0;
                        try {
                            Material mat = Material.valueOf(materialName);
                            newPlayerHas = ShopTransaction.countMaterial(player.inventory(), mat);
                        } catch (Exception ignored) {}
                        currentQty = Math.min(currentQty, Math.max(1, newPlayerHas));
                        sellQuantity.put(playerId, currentQty);
                        clearSlots(menu, 27);
                        populateSellMenu(menu, listing, materialName, currentQty, newPlayerHas, player, shop);
                    }
                }
                return;
            }
            default -> { return; }
        }

        // Update quantity and refresh display
        if (currentQty < 1) currentQty = 1;
        sellQuantity.put(playerId, currentQty);
        ShopItem listing = shop.items().get(materialName);
        if (listing != null) {
            clearSlots(menu, 27);
            populateSellMenu(menu, listing, materialName, currentQty, playerHas, player, shop);
        }
    }

    // ========================================================================
    // OWNER MENU (4 rows = 36 slots)
    // ========================================================================
    // Slots 0-17: Unique materials from the chest — click to edit prices
    // Slot 31: Collect Earnings
    // Slot 35: Close

    private void handleOwnerClick(Player player, Shop shop, MenuInstance menu,
                                  int rawSlot, SlotDefinition slotDef, ClickAction action) {
        if (slotDef == null) return;
        int slot = slotDef.index();

        if (slot == 35) { player.closeInventory(); return; }

        // Collect Earnings (not applicable for reserve shops)
        if (slot == 31) {
            if (shop.isReserveShop()) {
                player.sendMessage("Revenue: " + fmt(shop.earnings())
                        + " — payments go directly to the server reserve wallet.",
                        Player.MessageType.INFO);
                return;
            }
            double collected = shop.collectEarnings();
            if (collected <= 0) {
                player.sendMessage("No earnings to collect.", Player.MessageType.WARNING);
                return;
            }
            EconomyProvider economy = AlloyAPI.economy() != null ? AlloyAPI.economy().provider() : null;
            if (economy != null) economy.deposit(player.uniqueId(), collected);
            shopManager.save();
            player.sendMessage("Collected " + fmt(collected) + "!",
                    Player.MessageType.SUCCESS);
            refreshOwnerMenu(player, menu, shop);
            return;
        }

        // Item slots: 0-17 → open Price Editor
        if (slot >= 0 && slot < 18) {
            List<ChestStock> stock = cachedStock.get(player.uniqueId());
            if (stock == null || slot >= stock.size()) return;
            openPriceEditor(player, shop, stock.get(slot).materialName());
        }
    }

    // ========================================================================
    // PRICE EDITOR (4 rows = 36 slots)
    // ========================================================================
    // Row 1 (0-8):   [...] [...] [...] [...] [ITEM=4] [...] [Stock=6] [Remove=7] [Back=8]
    // Row 2 (9-17):  [-$1=9] [-$0.10=10] [-$0.05=11] [-$0.01=12] [Buy=13] [+$0.01=14] [+$0.05=15] [+$0.10=16] [+$1=17]
    // Row 3 (18-26): [-$1=18] [-$0.10=19] [-$0.05=20] [-$0.01=21] [Sell=22] [+$0.01=23] [+$0.05=24] [+$0.10=25] [+$1=26]

    private static final int PE_ITEM = 4;
    private static final int PE_STOCK = 6, PE_REMOVE = 7, PE_BACK = 8;
    private static final int PE_BUY_DOWN_1 = 9, PE_BUY_DOWN_01 = 10;
    private static final int PE_BUY_DOWN_005 = 11, PE_BUY_DOWN_001 = 12;
    private static final int PE_BUY_DISPLAY = 13;
    private static final int PE_BUY_UP_001 = 14, PE_BUY_UP_005 = 15;
    private static final int PE_BUY_UP_01 = 16, PE_BUY_UP_1 = 17;
    private static final int PE_SELL_DOWN_1 = 18, PE_SELL_DOWN_01 = 19;
    private static final int PE_SELL_DOWN_005 = 20, PE_SELL_DOWN_001 = 21;
    private static final int PE_SELL_DISPLAY = 22;
    private static final int PE_SELL_UP_001 = 23, PE_SELL_UP_005 = 24;
    private static final int PE_SELL_UP_01 = 25, PE_SELL_UP_1 = 26;

    private void openPriceEditor(Player player, Shop shop, String materialName) {
        String displayName = ShopTransaction.formatMaterial(materialName);

        // Ensure a price entry exists (default prices for new items)
        if (!shop.items().containsKey(materialName)) {
            shop.setPrice(materialName, 1.00, 0.50);
            shopManager.save();
        }
        ShopItem listing = shop.items().get(materialName);

        MenuLayout layout = buildPriceEditorLayout("Edit: " + displayName);
        MenuInstance menu = player.openMenu(layout);
        if (menu == null) return;

        int stockCount = getChestStockCount(shop, materialName, player.world());
        populatePriceEditor(menu, listing, materialName, stockCount);

        editingMaterial.put(player.uniqueId(), materialName);
        track(player.uniqueId(), shop.id(), ViewMode.PRICE_EDITOR, menu);
    }

    private void populatePriceEditor(MenuInstance menu, ShopItem listing, String materialName, int stockCount) {
        String displayName = ShopTransaction.formatMaterial(materialName);
        String s = sym();

        // The item being edited (center of top row)
        try {
            Material mat = Material.valueOf(materialName);
            int amt = Math.max(1, Math.min(stockCount, 64));
            ItemStack display = ItemStack.create(mat, amt);
            if (display != null) {
                display.setDisplayName(displayName);
                display.setLore(List.of(
                        "Buy: " + s + listing.formatBuyPrice(),
                        "Sell: " + s + listing.formatSellPrice(),
                        "In chest: " + stockCount));
                menu.setItem(PE_ITEM, display);
            }
        } catch (Exception ignored) {}

        // Row 1 right side: Stock, Remove, Back
        setButton(menu, PE_STOCK, Material.CHEST, "In Chest: " + stockCount,
                List.of("Items in the physical chest", "Add more by placing items in the chest"));
        setButton(menu, PE_REMOVE, Material.BARRIER, "Remove Listing",
                List.of("Click to remove this item's prices", "Items stay in the chest"));
        setButton(menu, PE_BACK, Material.ARROW, "Back", List.of("Return to shop management"));

        // Buy price controls (row 2)
        setButton(menu, PE_BUY_DOWN_1, Material.REDSTONE, "-" + s + "1.00",
                List.of("Decrease buy price by " + s + "1.00"));
        setButton(menu, PE_BUY_DOWN_01, Material.REDSTONE, "-" + s + "0.10",
                List.of("Decrease buy price by " + s + "0.10"));
        setButton(menu, PE_BUY_DOWN_005, Material.REDSTONE, "-" + s + "0.05",
                List.of("Decrease buy price by " + s + "0.05"));
        setButton(menu, PE_BUY_DOWN_001, Material.REDSTONE, "-" + s + "0.01",
                List.of("Decrease buy price by " + s + "0.01"));
        setButton(menu, PE_BUY_DISPLAY, Material.GOLD_INGOT, "Buy: " + s + listing.formatBuyPrice(),
                List.of("Current buy price", "Customers pay this to buy"));
        setButton(menu, PE_BUY_UP_001, Material.EMERALD, "+" + s + "0.01",
                List.of("Increase buy price by " + s + "0.01"));
        setButton(menu, PE_BUY_UP_005, Material.EMERALD, "+" + s + "0.05",
                List.of("Increase buy price by " + s + "0.05"));
        setButton(menu, PE_BUY_UP_01, Material.EMERALD, "+" + s + "0.10",
                List.of("Increase buy price by " + s + "0.10"));
        setButton(menu, PE_BUY_UP_1, Material.EMERALD, "+" + s + "1.00",
                List.of("Increase buy price by " + s + "1.00"));

        // Sell price controls (row 3)
        setButton(menu, PE_SELL_DOWN_1, Material.REDSTONE, "-" + s + "1.00",
                List.of("Decrease sell price by " + s + "1.00"));
        setButton(menu, PE_SELL_DOWN_01, Material.REDSTONE, "-" + s + "0.10",
                List.of("Decrease sell price by " + s + "0.10"));
        setButton(menu, PE_SELL_DOWN_005, Material.REDSTONE, "-" + s + "0.05",
                List.of("Decrease sell price by " + s + "0.05"));
        setButton(menu, PE_SELL_DOWN_001, Material.REDSTONE, "-" + s + "0.01",
                List.of("Decrease sell price by " + s + "0.01"));
        setButton(menu, PE_SELL_DISPLAY, Material.GOLD_NUGGET, "Sell: " + s + listing.formatSellPrice(),
                List.of("Current sell price", "Shop pays this when buying from players"));
        setButton(menu, PE_SELL_UP_001, Material.EMERALD, "+" + s + "0.01",
                List.of("Increase sell price by " + s + "0.01"));
        setButton(menu, PE_SELL_UP_005, Material.EMERALD, "+" + s + "0.05",
                List.of("Increase sell price by " + s + "0.05"));
        setButton(menu, PE_SELL_UP_01, Material.EMERALD, "+" + s + "0.10",
                List.of("Increase sell price by " + s + "0.10"));
        setButton(menu, PE_SELL_UP_1, Material.EMERALD, "+" + s + "1.00",
                List.of("Increase sell price by " + s + "1.00"));
    }

    private void handlePriceEditorClick(Player player, Shop shop, MenuInstance menu,
                                        int rawSlot, SlotDefinition slotDef, String materialName) {
        if (slotDef == null) return;
        ShopItem listing = shop.items().get(materialName);
        if (listing == null) {
            shop.setPrice(materialName, 1.00, 0.50);
            shopManager.save();
            listing = shop.items().get(materialName);
        }

        switch (slotDef.index()) {
            // Buy price adjustments
            case PE_BUY_DOWN_1 -> adjustPrice(player, shop, materialName, menu, -1.00, true);
            case PE_BUY_DOWN_01 -> adjustPrice(player, shop, materialName, menu, -0.10, true);
            case PE_BUY_DOWN_005 -> adjustPrice(player, shop, materialName, menu, -0.05, true);
            case PE_BUY_DOWN_001 -> adjustPrice(player, shop, materialName, menu, -0.01, true);
            case PE_BUY_UP_001 -> adjustPrice(player, shop, materialName, menu, 0.01, true);
            case PE_BUY_UP_005 -> adjustPrice(player, shop, materialName, menu, 0.05, true);
            case PE_BUY_UP_01 -> adjustPrice(player, shop, materialName, menu, 0.10, true);
            case PE_BUY_UP_1 -> adjustPrice(player, shop, materialName, menu, 1.00, true);
            // Sell price adjustments
            case PE_SELL_DOWN_1 -> adjustPrice(player, shop, materialName, menu, -1.00, false);
            case PE_SELL_DOWN_01 -> adjustPrice(player, shop, materialName, menu, -0.10, false);
            case PE_SELL_DOWN_005 -> adjustPrice(player, shop, materialName, menu, -0.05, false);
            case PE_SELL_DOWN_001 -> adjustPrice(player, shop, materialName, menu, -0.01, false);
            case PE_SELL_UP_001 -> adjustPrice(player, shop, materialName, menu, 0.01, false);
            case PE_SELL_UP_005 -> adjustPrice(player, shop, materialName, menu, 0.05, false);
            case PE_SELL_UP_01 -> adjustPrice(player, shop, materialName, menu, 0.10, false);
            case PE_SELL_UP_1 -> adjustPrice(player, shop, materialName, menu, 1.00, false);
            // Remove listing
            case PE_REMOVE -> {
                shop.removeItem(materialName);
                shopManager.save();
                player.sendMessage("Removed " + ShopTransaction.formatMaterial(materialName)
                        + " listing. Items remain in the chest.", Player.MessageType.SUCCESS);
                openOwnerMenu(player, shop);
            }
            // Back
            case PE_BACK -> openOwnerMenu(player, shop);
        }
    }

    private void adjustPrice(Player player, Shop shop, String materialName, MenuInstance menu,
                             double delta, boolean isBuyPrice) {
        ShopItem listing = shop.items().get(materialName);
        if (listing == null) return;

        double newBuy = listing.buyPrice(), newSell = listing.sellPrice();
        if (isBuyPrice) {
            newBuy = Math.max(0, Math.round((newBuy + delta) * 100.0) / 100.0);
        } else {
            newSell = Math.max(0, Math.round((newSell + delta) * 100.0) / 100.0);
        }

        shop.setPrice(materialName, newBuy, newSell);
        shopManager.save();

        // Refresh editor in place
        ShopItem updated = shop.items().get(materialName);
        int stockCount = getChestStockCount(shop, materialName, player.world());
        clearSlots(menu, 27);
        populatePriceEditor(menu, updated, materialName, stockCount);
    }

    // ========================================================================
    // Layout Builders
    // ========================================================================

    private static MenuLayout buildCustomerLayout(String title, int itemCount, List<Integer> buttonSlots) {
        MenuLayout.Builder builder = AlloyAPI.menuFactory().builder(title, 3);
        for (int i = 0; i < Math.min(itemCount, 18); i++) {
            builder.slot(i, 0, 0, SlotType.DISPLAY);
        }
        for (int s : buttonSlots) {
            builder.slot(s, 0, 0, SlotType.DISPLAY);
        }
        return builder.build();
    }

    private static MenuLayout buildOwnerLayout(String title, int itemCount) {
        MenuLayout.Builder builder = AlloyAPI.menuFactory().builder(title, 4);
        for (int i = 0; i < Math.min(itemCount, 18); i++) {
            builder.slot(i, 0, 0, SlotType.DISPLAY);
        }
        builder.slot(31, 0, 0, SlotType.DISPLAY); // collect earnings
        builder.slot(35, 0, 0, SlotType.DISPLAY); // close
        return builder.build();
    }

    private static MenuLayout buildPurchaseLayout(String title) {
        MenuLayout.Builder builder = AlloyAPI.menuFactory().builder(title, 3);
        int[] slots = { PU_ITEM,
                PU_DOWN_16, PU_DOWN_1, PU_QTY, PU_UP_1, PU_UP_16, PU_TOTAL,
                PU_BACK, PU_BUY, PU_CLOSE };
        for (int s : slots) {
            builder.slot(s, 0, 0, SlotType.DISPLAY);
        }
        return builder.build();
    }

    private static MenuLayout buildPriceEditorLayout(String title) {
        MenuLayout.Builder builder = AlloyAPI.menuFactory().builder(title, 3);
        int[] slots = { PE_ITEM, PE_STOCK, PE_REMOVE, PE_BACK,
                PE_BUY_DOWN_1, PE_BUY_DOWN_01, PE_BUY_DOWN_005, PE_BUY_DOWN_001,
                PE_BUY_DISPLAY, PE_BUY_UP_001, PE_BUY_UP_005, PE_BUY_UP_01, PE_BUY_UP_1,
                PE_SELL_DOWN_1, PE_SELL_DOWN_01, PE_SELL_DOWN_005, PE_SELL_DOWN_001,
                PE_SELL_DISPLAY, PE_SELL_UP_001, PE_SELL_UP_005, PE_SELL_UP_01, PE_SELL_UP_1 };
        for (int s : slots) {
            builder.slot(s, 0, 0, SlotType.DISPLAY);
        }
        return builder.build();
    }

    // ========================================================================
    // Menu Population
    // ========================================================================

    private static void populateCustomerItems(MenuInstance menu, List<ChestStock> stock, Shop shop) {
        int count = Math.min(stock.size(), 18);
        for (int i = 0; i < count; i++) {
            ChestStock entry = stock.get(i);
            ShopItem listing = shop.items().get(entry.materialName());
            if (listing == null) continue;

            try {
                int displayAmt = Math.max(1, Math.min(entry.count(), 64));
                ItemStack stack = ItemStack.create(entry.material(), displayAmt);
                if (stack != null) {
                    stack.setDisplayName(ShopTransaction.formatMaterial(entry.materialName()));
                    List<String> lore = new ArrayList<>();
                    lore.add("Price: " + fmt(listing.buyPrice()) + " each");
                    lore.add("In stock: " + entry.count());
                    lore.add("");
                    lore.add("Click to purchase");
                    stack.setLore(lore);
                    menu.setItem(i, stack);
                }
            } catch (Exception ignored) {}
        }
    }

    private static void populateOwnerItems(MenuInstance menu, List<ChestStock> stock, Shop shop) {
        String s = sym();
        int count = Math.min(stock.size(), 18);
        for (int i = 0; i < count; i++) {
            ChestStock entry = stock.get(i);
            ShopItem listing = shop.items().get(entry.materialName());

            try {
                int displayAmt = Math.max(1, Math.min(entry.count(), 64));
                ItemStack stack = ItemStack.create(entry.material(), displayAmt);
                if (stack != null) {
                    stack.setDisplayName(ShopTransaction.formatMaterial(entry.materialName()));
                    List<String> lore = new ArrayList<>();
                    if (listing != null) {
                        lore.add("Buy: " + s + listing.formatBuyPrice());
                        lore.add("Sell: " + s + listing.formatSellPrice());
                    } else {
                        lore.add("No prices set");
                    }
                    lore.add("In chest: " + entry.count());
                    lore.add("");
                    lore.add("Click to edit prices");
                    stack.setLore(lore);
                    menu.setItem(i, stack);
                }
            } catch (Exception ignored) {}
        }
    }

    private void refreshCustomerMenu(Player player, MenuInstance menu, Shop shop) {
        Inventory chestInv = ShopTransaction.getChestInventory(shop, player.world());
        if (chestInv == null) return;

        List<ChestStock> stock = ShopTransaction.readChestContents(chestInv);
        List<ChestStock> priced = stock.stream()
                .filter(s -> {
                    ShopItem item = shop.items().get(s.materialName());
                    return item != null && item.buyPrice() > 0;
                })
                .toList();

        clearSlots(menu, 18);
        populateCustomerItems(menu, priced, shop);
        cachedStock.put(player.uniqueId(), priced);
    }

    private void refreshOwnerMenu(Player player, MenuInstance menu, Shop shop) {
        Inventory chestInv = ShopTransaction.getChestInventory(shop, player.world());
        if (chestInv == null) return;

        List<ChestStock> stock = ShopTransaction.readChestContents(chestInv);
        clearSlots(menu, 18);
        populateOwnerItems(menu, stock, shop);
        if (shop.isReserveShop()) {
            setButton(menu, 31, Material.GOLD_INGOT, "Total Revenue",
                    List.of("Revenue: " + fmt(shop.earnings()),
                            "Payments go to server reserve"));
        } else {
            setButton(menu, 31, Material.GOLD_INGOT, "Collect Earnings",
                    List.of("Pending: " + fmt(shop.earnings()),
                            "Click to collect all earnings"));
        }
        cachedStock.put(player.uniqueId(), stock);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static int getChestStockCount(Shop shop, String materialName, net.alloymc.api.world.World world) {
        Inventory chestInv = ShopTransaction.getChestInventory(shop, world);
        if (chestInv == null) return 0;
        try {
            Material mat = Material.valueOf(materialName);
            return ShopTransaction.countMaterial(chestInv, mat);
        } catch (Exception e) {
            return 0;
        }
    }

    private static void setButton(MenuInstance menu, int slot, Material material,
                                  String name, List<String> lore) {
        try {
            ItemStack stack = ItemStack.create(material, 1);
            if (stack != null) {
                stack.setDisplayName(name);
                if (lore != null && !lore.isEmpty()) stack.setLore(lore);
                menu.setItem(slot, stack);
            }
        } catch (Exception ignored) {}
    }

    private static void clearSlots(MenuInstance menu, int count) {
        for (int i = 0; i < count; i++) {
            menu.setItem(i, null);
        }
    }
}
