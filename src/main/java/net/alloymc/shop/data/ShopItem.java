package net.alloymc.shop.data;

/**
 * An item listing in a shop with buy/sell prices and virtual stock.
 *
 * Stock is tracked virtually (not from the physical chest) because the Alloy API
 * does not expose block inventories. Owners stock shops via /shop stock commands,
 * which remove items from their personal inventory and add to virtual stock.
 *
 * @param material  the Alloy Material enum name (e.g., "DIAMOND")
 * @param buyPrice  price customers pay to buy one (0 = not for sale)
 * @param sellPrice price shop pays customers who sell one (0 = not buying)
 * @param stock     current virtual stock count (-1 = infinite, used by admin shops)
 */
public record ShopItem(String material, double buyPrice, double sellPrice, int stock) {

    public boolean canBuy() {
        return buyPrice > 0 && (stock > 0 || stock == -1);
    }

    public boolean canSell() {
        return sellPrice > 0;
    }

    public boolean hasInfiniteStock() {
        return stock == -1;
    }

    public String formatBuyPrice() {
        return buyPrice > 0 ? String.format("%.2f", buyPrice) : "---";
    }

    public String formatSellPrice() {
        return sellPrice > 0 ? String.format("%.2f", sellPrice) : "---";
    }

    public String formatStock() {
        return stock == -1 ? "INF" : String.valueOf(stock);
    }

    /**
     * Returns a copy with adjusted stock.
     */
    public ShopItem withStock(int newStock) {
        return new ShopItem(material, buyPrice, sellPrice, newStock);
    }

    /**
     * Returns a copy with updated prices.
     */
    public ShopItem withPrices(double newBuyPrice, double newSellPrice) {
        return new ShopItem(material, newBuyPrice, newSellPrice, stock);
    }

    /**
     * Returns a display-friendly material name (DIAMOND_SWORD → Diamond Sword).
     */
    public String displayName() {
        String[] parts = material.split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(parts[i].charAt(0));
            sb.append(parts[i].substring(1).toLowerCase());
        }
        return sb.toString();
    }
}
