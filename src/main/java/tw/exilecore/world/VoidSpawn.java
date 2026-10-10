package tw.exilecore.world;

import org.bukkit.GameRules;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.slf4j.Logger;

/**
 * 虛空地圖的出生點：出生點底下整欄都是空的（第一次用虛空地圖啟動，或出生點底下的方塊被拆光）時，
 * 在 (0, 64, 0) 放一格草地、出生點移到它上面、出生範圍設成 0（每個人都剛好站在那一格上）。
 * <p>
 * 出生點底下有任何方塊就什麼都不做，所以一般地圖、已經蓋好營地的虛空地圖都不會被動到。
 */
public final class VoidSpawn {

    static final int X = 0;
    static final int Y = 64;
    static final int Z = 0;
    /** 面向東（autolayout 的戰鬥區域往東排）。 */
    static final float YAW = -90f;

    private VoidSpawn() {
    }

    /** @return 有沒有改動世界 */
    static boolean ensure(final World world, final Logger logger) {
        final Location spawn = world.getSpawnLocation();
        if (!emptyColumn(world, spawn.getBlockX(), spawn.getBlockZ())) {
            return false;
        }
        int top = world.getHighestBlockYAt(X, Z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        final boolean placed = top < world.getMinHeight();
        if (placed) {
            world.getBlockAt(X, Y, Z).setType(Material.GRASS_BLOCK, false);
            top = Y;
        }
        world.setSpawnLocation(X, top + 1, Z, YAW);
        world.setGameRule(GameRules.RESPAWN_RADIUS, 0);
        if (placed) {
            logger.info("虛空地圖：在 {} 的 ({}, {}, {}) 放了一格草地，出生點移到它上面", world.getName(), X, Y, Z);
        } else {
            logger.info("虛空地圖：出生點底下是空的，移到 {} 的 ({}, {}, {})", world.getName(), X, top + 1, Z);
        }
        return true;
    }

    /** 這一欄從最底到最頂都沒有任何方塊（虛空）。 */
    public static boolean emptyColumn(final World world, final int x, final int z) {
        return world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE) < world.getMinHeight();
    }
}
