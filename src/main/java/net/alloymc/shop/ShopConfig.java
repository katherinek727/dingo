package net.alloymc.shop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JSON configuration for AlloyShop.
 */
public final class ShopConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private int max_shops_per_player = 10;
    private double tax_rate = 0.0;
    private boolean allow_admin_shops = true;

    public int maxShopsPerPlayer() { return max_shops_per_player; }
    public double taxRate() { return tax_rate; }
    public boolean allowAdminShops() { return allow_admin_shops; }

    public static ShopConfig load(Path dataDir) {
        Path configFile = dataDir.resolve("config.json");
        if (Files.exists(configFile)) {
            try {
                String json = Files.readString(configFile);
                ShopConfig config = GSON.fromJson(json, ShopConfig.class);
                if (config != null) return config;
            } catch (IOException e) {
                System.err.println("[AlloyShop] Failed to read config: " + e.getMessage());
            }
        }
        ShopConfig config = new ShopConfig();
        config.save(dataDir);
        return config;
    }

    public void save(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
            Files.writeString(dataDir.resolve("config.json"), GSON.toJson(this));
        } catch (IOException e) {
            System.err.println("[AlloyShop] Failed to save config: " + e.getMessage());
        }
    }
}
