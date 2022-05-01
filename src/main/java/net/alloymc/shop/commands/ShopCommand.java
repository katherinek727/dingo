package net.alloymc.shop.commands;

import net.alloymc.api.AlloyAPI;
import net.alloymc.api.command.Command;
import net.alloymc.api.command.CommandSender;
import net.alloymc.api.economy.EconomyRegistry;
import net.alloymc.api.entity.Player;
import net.alloymc.api.inventory.Inventory;
import net.alloymc.api.inventory.ItemStack;
import net.alloymc.api.inventory.Material;
import net.alloymc.shop.ShopConfig;
import net.alloymc.shop.data.Shop;
import net.alloymc.shop.data.ShopItem;
import net.alloymc.shop.data.ShopLocation;
import net.alloymc.shop.data.ShopManager;
import net.alloymc.shop.transaction.ShopTransaction;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * /shop command hub with subcommands:
 *
 *   /shop help                            - Show all commands
 *   /shop info [id]                       - Info about a shop (by ID or nearest)
 *   /shop list [player]                   - List shops
 *   /shop price <item> <buy> [sell]       - Set buy/sell price for an item
 *   /shop stock <item> <amount>           - Add stock from your inventory
 *   /shop withdraw <item> <amount>        - Withdraw stock to your inventory
 *   /shop buy <item> [amount]             - Buy from the last viewed shop
 *   /shop sell <item> [amount]            - Sell to the last viewed shop
 *   /shop earnings [id]                   - View shop earnings
 *   /shop collect [id]                    - Collect earnings to your balance
 *   /shop remove [id]                     - Remove a shop
 *   /shop admin create                    - Create an admin shop (op)
 */
public final class ShopCommand extends Command {

    private final ShopManager shopManager;
    private final ShopTransaction transactions;
    private final ShopConfig config;

    /**
     * Shared map tracking the last shop each player interacted with.
     * Populated by ShopInteractListener, read by buy/sell commands.
     */
    private final java.util.concurrent.ConcurrentHashMap<UUID, String> lastViewedShop;

    public ShopCommand(ShopManager shopManager, ShopTransaction transactions, ShopConfig config,
                       java.util.concurrent.ConcurrentHashMap<UUID, String> lastViewedShop) {
        super("shop", "Shop management commands", "alloyshop.use");
        this.shopManager = shopManager;
        this.transactions = transactions;
        this.config = config;
        this.lastViewedShop = lastViewedShop;
    }

    private static String fmt(double amount) {
        EconomyRegistry reg = AlloyAPI.economy();
        return reg != null ? reg.formatAmount(amount) : "$" + String.format("%.2f", amount);
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!sender.isPlayer()) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            showHelp(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "help" -> showHelp(player);
            case "info" -> handleInfo(player, args);
            case "list" -> handleList(player, args);
            case "price" -> handlePrice(player, args);
            case "stock" -> handleStock(player, args);
            case "withdraw" -> handleWithdraw(player, args);
            case "buy" -> handleBuy(player, args);
            case "sell" -> handleSell(player, args);
            case "earnings" -> handleEarnings(player, args);
            case "collect" -> handleCollect(player, args);
            case "remove" -> handleRemove(player, args);
            case "admin" -> handleAdmin(player, args);
            default -> {
                player.sendMessage("Unknown subcommand: " + args[0], Player.MessageType.ERROR);
                player.sendMessage("Use /shop help for a list of commands.");
            }
        }

        return true;
    }

    // ========================================================================
    // Subcommand handlers
    // ========================================================================

    private void showHelp(Player player) {
        player.sendMessage("=== AlloyShop Commands ===");
        player.sendMessage("");
        player.sendMessage("  Shopping (GUI):");
        player.sendMessage("    Right-click a shop sign to open the shop GUI");
        player.sendMessage("    Left-click item = buy 1, Right-click = sell 1");
        player.sendMessage("    Shift+click for bulk (x16)");
        player.sendMessage("    Owners: right-click sign to manage");
        player.sendMessage("");
        player.sendMessage("  Creating & Managing:");
        player.sendMessage("    Place a sign with [Shop] next to a chest");
        player.sendMessage("    Stock the chest directly with items");
        player.sendMessage("    /shop price <item> <buy> [sell] - Set prices");
        player.sendMessage("    /shop remove [id]               - Remove shop");
        player.sendMessage("");
        player.sendMessage("  Command-line shopping:");
        player.sendMessage("    /shop buy <item> [amount]  - Buy items");
        player.sendMessage("    /shop sell <item> [amount]  - Sell items");
        player.sendMessage("");
        player.sendMessage("  Information:");
        player.sendMessage("    /shop info [id]      - Shop details");
        player.sendMessage("    /shop list [player]  - List shops");
        player.sendMessage("    /shop earnings [id]  - View earnings");
        player.sendMessage("    /shop collect [id]   - Collect earnings");
        if (player.hasPermission("alloyshop.admin")) {
            player.sendMessage("");
            player.sendMessage("  Admin:");
            player.sendMessage("    Place sign with [AdminShop] - Infinite stock shop");
        }
    }

    private void handleInfo(Player player, String[] args) {
        Shop shop = resolveShop(player, args, 1);
        if (shop == null) return;

        player.sendMessage("=== Shop Info: " + shop.id() + " ===");
        player.sendMessage("  Owner: " + shop.displayTitle());
        player.sendMessage("  Location: " + shop.signLocation());
        player.sendMessage("  Type: " + (shop.isAdminShop() ? "Admin (infinite)" : "Player"));
        player.sendMessage("  Priced items: " + shop.items().size());
        player.sendMessage("  Earnings: " + fmt(shop.earnings()));

        // Show chest contents with prices
        Inventory chestInv = ShopTransaction.getChestInventory(shop);
        if (chestInv != null) {
            var stock = ShopTransaction.readChestContents(chestInv);
            if (!stock.isEmpty()) {
                player.sendMessage("");
                player.sendMessage("  Chest contents:");
                for (var entry : stock) {
                    ShopItem listing = shop.items().get(entry.materialName());
                    String priceInfo = listing != null
                            ? "Buy: " + fmt(listing.buyPrice()) + " | Sell: " + fmt(listing.sellPrice())
                            : "No prices set";
                    player.sendMessage("  " + ShopTransaction.formatMaterial(entry.materialName())
                            + " x" + entry.count() + " - " + priceInfo);
                }
            } else {
                player.sendMessage("  Chest is empty.");
            }
        } else {
            player.sendMessage("  Chest unavailable.");
        }
    }

    private void handleList(Player player, String[] args) {
        List<Shop> shops;
        String header;

        if (args.length >= 2) {
            // List shops by player name
            String targetName = args[1];
            var targetOpt = AlloyAPI.server().player(targetName);
            if (targetOpt.isPresent()) {
                shops = shopManager.getByOwner(targetOpt.get().uniqueId());
                header = targetName + "'s shops";
            } else {
                // Try searching all shops by owner name
                shops = shopManager.allShops().stream()
                        .filter(s -> s.ownerName().equalsIgnoreCase(targetName))
                        .toList();
                header = targetName + "'s shops";
            }
        } else {
            shops = shopManager.getByOwner(player.uniqueId());
            header = "Your shops";
        }

        player.sendMessage("=== " + header + " (" + shops.size() + ") ===");
        if (shops.isEmpty()) {
            player.sendMessage("  No shops found.");
            return;
        }

        for (Shop shop : shops) {
            String type = shop.isAdminShop() ? "[Admin]" : "";
            player.sendMessage("  " + shop.id() + " " + type + " - " + shop.items().size()
                    + " items, " + fmt(shop.earnings()) + " earnings @ " + shop.signLocation());
        }
    }

    private void handlePrice(Player player, String[] args) {
        // /shop price <item> <buyPrice> [sellPrice] [shopId]
        if (args.length < 3) {
            player.sendMessage("Usage: /shop price <item> <buyPrice> [sellPrice]", Player.MessageType.ERROR);
            return;
        }

        String materialName = args[1].toUpperCase();
        try {
            Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            player.sendMessage("Unknown item: " + args[1] + ". Use the material name (e.g., DIAMOND, OAK_LOG).", Player.MessageType.ERROR);
            return;
        }

        double buyPrice;
        try {
            buyPrice = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage("Invalid buy price: " + args[2], Player.MessageType.ERROR);
            return;
        }

        double sellPrice = 0;
        if (args.length >= 4) {
            try {
                sellPrice = Double.parseDouble(args[3]);
            } catch (NumberFormatException e) {
                player.sendMessage("Invalid sell price: " + args[3], Player.MessageType.ERROR);
                return;
            }
        }

        // Find the shop — try last viewed or by ID
        Shop shop = resolveOwnedShop(player, args, 4);
        if (shop == null) return;

        shop.setPrice(materialName, buyPrice, sellPrice);
        shopManager.save();

        String itemName = ShopTransaction.formatMaterial(materialName);
        player.sendMessage("Price set for " + itemName + ": Buy " + fmt(buyPrice)
                + (sellPrice > 0 ? " | Sell " + fmt(sellPrice) : ""), Player.MessageType.SUCCESS);
    }

    private void handleStock(Player player, String[] args) {
        // /shop stock <item> <amount> [shopId]
        if (args.length < 3) {
            player.sendMessage("Usage: /shop stock <item> <amount>", Player.MessageType.ERROR);
            return;
        }

        String materialName = args[1].toUpperCase();
        Material material;
        try {
            material = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            player.sendMessage("Unknown item: " + args[1], Player.MessageType.ERROR);
            return;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[2]);
            if (amount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            player.sendMessage("Invalid amount: " + args[2], Player.MessageType.ERROR);
            return;
        }

        Shop shop = resolveOwnedShop(player, args, 3);
        if (shop == null) return;

        // Check player has the items
        Inventory inv = player.inventory();
        int playerStock = ShopTransaction.countMaterial(inv, material);
        if (playerStock < amount) {
            player.sendMessage("You only have " + playerStock + "x " + ShopTransaction.formatMaterial(materialName) + ".", Player.MessageType.ERROR);
            return;
        }

        // Get the physical chest
        Inventory chestInv = ShopTransaction.getChestInventory(shop);
        if (chestInv == null) {
            player.sendMessage("Shop chest is unavailable.", Player.MessageType.ERROR);
            return;
        }

        // Remove from player, add to chest
        ShopTransaction.removeItems(inv, material, amount);
        ItemStack toAdd = ItemStack.create(material, amount);
        if (toAdd != null) chestInv.addItem(toAdd);
        shopManager.save();

        int chestStock = ShopTransaction.countMaterial(chestInv, material);
        player.sendMessage("Added " + amount + "x " + ShopTransaction.formatMaterial(materialName)
                + " to shop chest. In chest: " + chestStock, Player.MessageType.SUCCESS);
    }

    private void handleWithdraw(Player player, String[] args) {
        // /shop withdraw <item> <amount> [shopId]
        if (args.length < 3) {
            player.sendMessage("Usage: /shop withdraw <item> <amount>", Player.MessageType.ERROR);
            return;
        }

        String materialName = args[1].toUpperCase();
        Material material;
        try {
            material = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            player.sendMessage("Unknown item: " + args[1], Player.MessageType.ERROR);
            return;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[2]);
            if (amount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            player.sendMessage("Invalid amount: " + args[2], Player.MessageType.ERROR);
            return;
        }

        Shop shop = resolveOwnedShop(player, args, 3);
        if (shop == null) return;

        // Get the physical chest
        Inventory chestInv = ShopTransaction.getChestInventory(shop);
        if (chestInv == null) {
            player.sendMessage("Shop chest is unavailable.", Player.MessageType.ERROR);
            return;
        }

        int available = ShopTransaction.countMaterial(chestInv, material);
        if (available == 0) {
            player.sendMessage("No " + ShopTransaction.formatMaterial(materialName) + " in the shop chest.", Player.MessageType.ERROR);
            return;
        }

        if (available < amount) {
            player.sendMessage("Only " + available + " in the chest.", Player.MessageType.ERROR);
            return;
        }

        // Remove from chest, give to player
        ShopTransaction.removeItems(chestInv, material, amount);
        Inventory inv = player.inventory();
        ItemStack toGive = ItemStack.create(material, amount);
        if (toGive != null) inv.addItem(toGive);

        shopManager.save();
        player.sendMessage("Withdrew " + amount + "x " + ShopTransaction.formatMaterial(materialName) + " from shop chest.", Player.MessageType.SUCCESS);
    }

    private void handleBuy(Player player, String[] args) {
        // /shop buy <item> [amount] [shopId]
        if (args.length < 2) {
            player.sendMessage("Usage: /shop buy <item> [amount]", Player.MessageType.ERROR);
            return;
        }

        String materialName = args[1].toUpperCase();
        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Integer.parseInt(args[2]);
                if (amount <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                player.sendMessage("Invalid amount: " + args[2], Player.MessageType.ERROR);
                return;
            }
        }

        Shop shop = resolveShop(player, args, 3);
        if (shop == null) return;

        ShopTransaction.Result result = transactions.buy(player, shop, materialName, amount);
        player.sendMessage(result.message(), result.success() ? Player.MessageType.SUCCESS : Player.MessageType.ERROR);
    }

    private void handleSell(Player player, String[] args) {
        // /shop sell <item> [amount] [shopId]
        if (args.length < 2) {
            player.sendMessage("Usage: /shop sell <item> [amount]", Player.MessageType.ERROR);
            return;
        }

        String materialName = args[1].toUpperCase();
        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Integer.parseInt(args[2]);
                if (amount <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                player.sendMessage("Invalid amount: " + args[2], Player.MessageType.ERROR);
                return;
            }
        }

        Shop shop = resolveShop(player, args, 3);
        if (shop == null) return;

        ShopTransaction.Result result = transactions.sell(player, shop, materialName, amount);
        player.sendMessage(result.message(), result.success() ? Player.MessageType.SUCCESS : Player.MessageType.ERROR);
    }

    private void handleEarnings(Player player, String[] args) {
        Shop shop = resolveOwnedShop(player, args, 1);
        if (shop == null) return;

        player.sendMessage("Shop " + shop.id() + " earnings: " + fmt(shop.earnings()));
    }

    private void handleCollect(Player player, String[] args) {
        Shop shop = resolveOwnedShop(player, args, 1);
        if (shop == null) return;

        double collected = shop.collectEarnings();
        if (collected <= 0) {
            player.sendMessage("No earnings to collect.", Player.MessageType.WARNING);
            return;
        }

        var economy = AlloyAPI.economy().provider();
        if (economy != null) {
            economy.deposit(player.uniqueId(), collected);
        }

        shopManager.save();
        player.sendMessage("Collected " + fmt(collected) + " from shop " + shop.id() + ".", Player.MessageType.SUCCESS);
    }

    private void handleRemove(Player player, String[] args) {
        Shop shop = resolveOwnedShop(player, args, 1);
        if (shop == null) return;

        String id = shop.id();
        shopManager.removeShop(id);
        player.sendMessage("Shop " + id + " removed.", Player.MessageType.SUCCESS);
    }

    private void handleAdmin(Player player, String[] args) {
        if (!player.hasPermission("alloyshop.admin")) {
            player.sendMessage("You don't have permission for admin commands.", Player.MessageType.ERROR);
            return;
        }

        if (args.length < 2) {
            player.sendMessage("Usage: /shop admin <create>", Player.MessageType.ERROR);
            return;
        }

        if (args[1].equalsIgnoreCase("create")) {
            player.sendMessage("Place a sign with [AdminShop] next to a chest to create an admin shop.", Player.MessageType.INFO);
        }
    }

    // ========================================================================
    // Shop resolution helpers
    // ========================================================================

    /**
     * Resolves a shop from: explicit ID arg, or last viewed shop.
     */
    private Shop resolveShop(Player player, String[] args, int idArgIndex) {
        if (args.length > idArgIndex) {
            String shopId = args[idArgIndex];
            Shop shop = shopManager.getById(shopId);
            if (shop == null) {
                player.sendMessage("Shop not found: " + shopId, Player.MessageType.ERROR);
            } else {
                lastViewedShop.put(player.uniqueId(), shop.id());
            }
            return shop;
        }

        // Fall back to last viewed shop
        String lastId = lastViewedShop.get(player.uniqueId());
        if (lastId != null) {
            Shop shop = shopManager.getById(lastId);
            if (shop != null) return shop;
        }

        // Fall back to player's first shop
        var owned = shopManager.getByOwner(player.uniqueId());
        if (!owned.isEmpty()) return owned.getFirst();

        player.sendMessage("No shop selected. Right-click a shop sign first, or specify a shop ID.", Player.MessageType.ERROR);
        return null;
    }

    /**
     * Resolves a shop that the player owns (or has admin permission for).
     */
    private Shop resolveOwnedShop(Player player, String[] args, int idArgIndex) {
        Shop shop = resolveShop(player, args, idArgIndex);
        if (shop == null) return null;

        if (!shop.isOwner(player.uniqueId()) && !player.hasPermission("alloyshop.admin")) {
            player.sendMessage("You don't own this shop.", Player.MessageType.ERROR);
            return null;
        }

        return shop;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            return List.of("help", "info", "list", "price", "stock", "withdraw",
                           "buy", "sell", "earnings", "collect", "remove", "admin")
                    .stream().filter(s -> s.startsWith(partial)).toList();
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            String partial = args[1].toUpperCase();
            if (sub.equals("price") || sub.equals("stock") || sub.equals("withdraw")
                    || sub.equals("buy") || sub.equals("sell")) {
                // Tab-complete material names
                return Arrays.stream(Material.values())
                        .map(Enum::name)
                        .filter(n -> n.startsWith(partial))
                        .limit(20)
                        .toList();
            }
            if (sub.equals("list")) {
                // Tab-complete player names
                return AlloyAPI.server().onlinePlayers().stream()
                        .map(Player::name)
                        .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                        .toList();
            }
        }

        return List.of();
    }
}
