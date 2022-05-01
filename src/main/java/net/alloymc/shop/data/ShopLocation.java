package net.alloymc.shop.data;

import net.alloymc.api.block.Block;

/**
 * Immutable location key for shop lookups.
 * Stores world name + block coordinates.
 */
public record ShopLocation(String world, int x, int y, int z) {

    public static ShopLocation fromBlock(Block block) {
        return new ShopLocation(block.world().name(), block.x(), block.y(), block.z());
    }

    /**
     * Returns a string key suitable for HashMap lookups.
     */
    public String key() {
        return world + ":" + x + "," + y + "," + z;
    }

    /**
     * Returns the location of the block adjacent in each direction (for finding chests near signs).
     */
    public ShopLocation offset(int dx, int dy, int dz) {
        return new ShopLocation(world, x + dx, y + dy, z + dz);
    }

    @Override
    public String toString() {
        return world + " @ " + x + ", " + y + ", " + z;
    }
}
