package net.alloymc.shop.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central shop registry. Handles CRUD, JSON persistence, and spatial lookups.
 *
 * Shops are indexed by:
 * - Shop ID (UUID string)
 * - Sign location key ("world:x,y,z")
 * - Chest location key ("world:x,y,z")
 * - Owner UUID (null for reserve shops)
 */
public final class ShopManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** All shops indexed by shop ID */
    private final ConcurrentHashMap<String, Shop> shopsById = new ConcurrentHashMap<>();

    /** Sign location → shop ID (for right-click lookup) */
    private final ConcurrentHashMap<String, String> signIndex = new ConcurrentHashMap<>();

    /** Chest location → shop ID (for protection) */
    private final ConcurrentHashMap<String, String> chestIndex = new ConcurrentHashMap<>();

    /** Owner UUID → set of shop IDs */
    private final ConcurrentHashMap<UUID, Set<String>> ownerIndex = new ConcurrentHashMap<>();

    private final Path dataFile;

    public ShopManager(Path dataDir) {
        this.dataFile = dataDir.resolve("shops.json");
        load();
    }

    // ========================================================================
    // CRUD
    // ========================================================================

    /**
     * Creates a new shop and persists it.
     * @return the created shop
     */
    public Shop createShop(UUID ownerUuid, String ownerName,
                           ShopLocation signLocation, ShopLocation chestLocation,
                           boolean adminShop) {
        return createShop(ownerUuid, ownerName, signLocation, chestLocation, adminShop, false);
    }

    /**
     * Creates a new shop with full options.
     * @param ownerUuid null for reserve shops
     */
    public Shop createShop(UUID ownerUuid, String ownerName,
                           ShopLocation signLocation, ShopLocation chestLocation,
                           boolean adminShop, boolean reserveShop) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Shop shop = new Shop(id, ownerUuid, ownerName, signLocation, chestLocation, adminShop, reserveShop);

        shopsById.put(id, shop);
        signIndex.put(signLocation.key(), id);
        chestIndex.put(chestLocation.key(), id);
        if (ownerUuid != null) {
            ownerIndex.computeIfAbsent(ownerUuid, k -> ConcurrentHashMap.newKeySet()).add(id);
        }

        save();
        return shop;
    }

    /**
     * Removes a shop by ID.
     * @return the removed shop, or null if not found
     */
    public Shop removeShop(String shopId) {
        Shop shop = shopsById.remove(shopId);
        if (shop != null) {
            signIndex.remove(shop.signLocation().key());
            chestIndex.remove(shop.chestLocation().key());
            if (shop.ownerUuid() != null) {
                Set<String> owned = ownerIndex.get(shop.ownerUuid());
                if (owned != null) {
                    owned.remove(shopId);
                    if (owned.isEmpty()) ownerIndex.remove(shop.ownerUuid());
                }
            }
            save();
        }
        return shop;
    }

    // ========================================================================
    // Lookups
    // ========================================================================

    public Shop getBySign(ShopLocation signLoc) {
        String id = signIndex.get(signLoc.key());
        return id != null ? shopsById.get(id) : null;
    }

    public Shop getByChest(ShopLocation chestLoc) {
        String id = chestIndex.get(chestLoc.key());
        return id != null ? shopsById.get(id) : null;
    }

    public Shop getByLocation(ShopLocation loc) {
        Shop shop = getBySign(loc);
        return shop != null ? shop : getByChest(loc);
    }

    public Shop getById(String id) {
        return shopsById.get(id);
    }

    public List<Shop> getByOwner(UUID ownerUuid) {
        Set<String> ids = ownerIndex.get(ownerUuid);
        if (ids == null || ids.isEmpty()) return List.of();
        return ids.stream()
                .map(shopsById::get)
                .filter(Objects::nonNull)
                .toList();
    }

    public Collection<Shop> allShops() {
        return shopsById.values();
    }

    public boolean isShopSign(ShopLocation loc) {
        return signIndex.containsKey(loc.key());
    }

    public boolean isShopChest(ShopLocation loc) {
        return chestIndex.containsKey(loc.key());
    }

    public boolean isShopBlock(ShopLocation loc) {
        return isShopSign(loc) || isShopChest(loc);
    }

    public int shopCount() {
        return shopsById.size();
    }

    public void save() {
        try {
            Files.createDirectories(dataFile.getParent());
            List<ShopData> dataList = shopsById.values().stream()
                    .map(ShopData::fromShop)
                    .toList();
            Files.writeString(dataFile, GSON.toJson(dataList), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[AlloyShop] Failed to save shops: " + e.getMessage());
        }
    }

    // ========================================================================
    // Persistence
    // ========================================================================

    private record ShopData(
            String id, String ownerUuid, String ownerName,
            String signWorld, int signX, int signY, int signZ,
            String chestWorld, int chestX, int chestY, int chestZ,
            Map<String, ShopItemData> items, double earnings,
            boolean adminShop, boolean reserveShop, long createdAt
    ) {
        static ShopData fromShop(Shop s) {
            Map<String, ShopItemData> itemMap = new LinkedHashMap<>();
            for (var entry : s.items().entrySet()) {
                ShopItem si = entry.getValue();
                itemMap.put(entry.getKey(), new ShopItemData(si.material(), si.buyPrice(), si.sellPrice(), si.stock()));
            }
            return new ShopData(
                    s.id(),
                    s.ownerUuid() != null ? s.ownerUuid().toString() : null,
                    s.ownerName(),
                    s.signLocation().world(), s.signLocation().x(), s.signLocation().y(), s.signLocation().z(),
                    s.chestLocation().world(), s.chestLocation().x(), s.chestLocation().y(), s.chestLocation().z(),
                    itemMap, s.earnings(), s.isAdminShop(), s.isReserveShop(), s.createdAt()
            );
        }

        Shop toShop() {
            ShopLocation sign = new ShopLocation(signWorld, signX, signY, signZ);
            ShopLocation chest = new ShopLocation(chestWorld, chestX, chestY, chestZ);
            Map<String, ShopItem> shopItems = new LinkedHashMap<>();
            if (items != null) {
                for (var entry : items.entrySet()) {
                    ShopItemData d = entry.getValue();
                    shopItems.put(entry.getKey(), new ShopItem(d.material, d.buyPrice, d.sellPrice, d.stock));
                }
            }
            UUID owner = ownerUuid != null ? UUID.fromString(ownerUuid) : null;
            return new Shop(id, owner, ownerName,
                    sign, chest, shopItems, earnings, adminShop,
                    reserveShop, createdAt);
        }
    }

    private record ShopItemData(String material, double buyPrice, double sellPrice, int stock) {}

    private void load() {
        if (!Files.exists(dataFile)) return;
        try {
            String json = Files.readString(dataFile, StandardCharsets.UTF_8);
            Type listType = new TypeToken<List<ShopData>>(){}.getType();
            List<ShopData> dataList = GSON.fromJson(json, listType);
            if (dataList == null) return;

            for (ShopData data : dataList) {
                Shop shop = data.toShop();
                shopsById.put(shop.id(), shop);
                signIndex.put(shop.signLocation().key(), shop.id());
                chestIndex.put(shop.chestLocation().key(), shop.id());
                if (shop.ownerUuid() != null) {
                    ownerIndex.computeIfAbsent(shop.ownerUuid(), k -> ConcurrentHashMap.newKeySet()).add(shop.id());
                }
            }
            System.out.println("[AlloyShop] Loaded " + shopsById.size() + " shops");
        } catch (Exception e) {
            System.err.println("[AlloyShop] Failed to load shops: " + e.getMessage());
        }
    }
}
