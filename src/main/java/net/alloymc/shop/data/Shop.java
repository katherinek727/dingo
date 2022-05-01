package net.alloymc.shop.data;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A shop instance tied to a sign + chest pair.
 *
 * <p>Three shop types:
 * <ul>
 *   <li><b>Player shop</b>: Owned by a player. Stock from chest. Earnings collected by owner.</li>
 *   <li><b>Admin shop</b>: Infinite virtual stock. Infinite funds. Tagged [AdminShop].</li>
 *   <li><b>Reserve shop</b>: Server-owned. Stock from chest. Payments go directly to
 *       the server reserve wallet. Created with [Shop] + "reserve" on line 2.
 *       Any OP can manage it.</li>
 * </ul>
 */
public final class Shop {

    private final String id;
    private final UUID ownerUuid;   // null for reserve shops
    private String ownerName;       // "Server" for reserve shops
    private final ShopLocation signLocation;
    private final ShopLocation chestLocation;
    private final Map<String, ShopItem> items; // material name → ShopItem
    private double earnings;
    private boolean adminShop;
    private boolean reserveShop;
    private final long createdAt;

    public Shop(String id, UUID ownerUuid, String ownerName,
                ShopLocation signLocation, ShopLocation chestLocation,
                boolean adminShop, boolean reserveShop) {
        this.id = id;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.signLocation = signLocation;
        this.chestLocation = chestLocation;
        this.items = new LinkedHashMap<>();
        this.earnings = 0;
        this.adminShop = adminShop;
        this.reserveShop = reserveShop;
        this.createdAt = System.currentTimeMillis();
    }

    // Full constructor for deserialization
    public Shop(String id, UUID ownerUuid, String ownerName,
                ShopLocation signLocation, ShopLocation chestLocation,
                Map<String, ShopItem> items, double earnings,
                boolean adminShop, boolean reserveShop, long createdAt) {
        this.id = id;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.signLocation = signLocation;
        this.chestLocation = chestLocation;
        this.items = new LinkedHashMap<>(items);
        this.earnings = earnings;
        this.adminShop = adminShop;
        this.reserveShop = reserveShop;
        this.createdAt = createdAt;
    }

    public String id() { return id; }
    public UUID ownerUuid() { return ownerUuid; }
    public String ownerName() { return ownerName; }
    public void setOwnerName(String name) { this.ownerName = name; }
    public ShopLocation signLocation() { return signLocation; }
    public ShopLocation chestLocation() { return chestLocation; }
    public Map<String, ShopItem> items() { return items; }
    public double earnings() { return earnings; }
    public boolean isAdminShop() { return adminShop; }
    public boolean isReserveShop() { return reserveShop; }
    public long createdAt() { return createdAt; }

    /**
     * Sets or updates the price for an item in this shop.
     * Preserves existing stock if the item already exists.
     */
    public void setPrice(String material, double buyPrice, double sellPrice) {
        ShopItem existing = items.get(material);
        int stock = existing != null ? existing.stock() : 0;
        if (adminShop) stock = -1; // Admin shops have infinite stock
        items.put(material, new ShopItem(material, buyPrice, sellPrice, stock));
    }

    /**
     * Adds stock for a material. Creates a listing with zero prices if it doesn't exist.
     * @return new stock count
     */
    public int addStock(String material, int amount) {
        ShopItem existing = items.get(material);
        if (existing != null) {
            if (existing.hasInfiniteStock()) return -1;
            int newStock = existing.stock() + amount;
            items.put(material, existing.withStock(newStock));
            return newStock;
        } else {
            items.put(material, new ShopItem(material, 0, 0, amount));
            return amount;
        }
    }

    /**
     * Removes stock for a material.
     * @return true if enough stock was available
     */
    public boolean removeStock(String material, int amount) {
        ShopItem existing = items.get(material);
        if (existing == null) return false;
        if (existing.hasInfiniteStock()) return true;
        if (existing.stock() < amount) return false;
        items.put(material, existing.withStock(existing.stock() - amount));
        return true;
    }

    /**
     * Removes a priced item from the shop listing.
     */
    public void removeItem(String material) {
        items.remove(material);
    }

    /**
     * Adds earnings from a sale. For reserve shops this is tracking only —
     * the actual payment went directly through the economy provider.
     */
    public void addEarnings(double amount) {
        this.earnings += amount;
    }

    /**
     * Deducts from earnings (for sell-to-shop transactions).
     * Admin and reserve shops always return true (infinite/reserve funds).
     * @return true if enough earnings were available
     */
    public boolean deductEarnings(double amount) {
        if (adminShop || reserveShop) return true;
        if (earnings < amount) return false;
        earnings -= amount;
        return true;
    }

    /**
     * Collects all earnings, resetting to zero.
     * @return the amount collected
     */
    public double collectEarnings() {
        double collected = earnings;
        earnings = 0;
        return collected;
    }

    /**
     * Checks if a player is the owner of this shop.
     * Reserve shops have no player owner — returns false for everyone.
     */
    public boolean isOwner(UUID playerId) {
        return ownerUuid != null && ownerUuid.equals(playerId);
    }

    /**
     * Returns a display-friendly shop description.
     */
    public String displayTitle() {
        if (reserveShop) return "Server Shop";
        if (adminShop) return "Admin Shop";
        return ownerName + "'s Shop";
    }
}
