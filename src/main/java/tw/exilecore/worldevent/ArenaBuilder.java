package tw.exilecore.worldevent;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import tw.exilecore.ExileCore;

import java.util.ArrayList;
import java.util.List;

/**
 * 開服活動的圓形場地（2026-10，/ec worldevent arena [半徑]）：以腳下為中心蓋一個大圓形廣場，事件都在這裡面發生。
 * <ul>
 *   <li>地板：深板岩磨製磚，三圈石英環、八條放射線、環上的海燈籠；正中央是石英的圓。</li>
 *   <li>上面清空 {@value #CLEAR} 格（原本的地形、樹砍掉）；下面墊 {@value #BASE} 格深板岩（懸空的地方也站得住）。</li>
 *   <li>外圍一圈兩格高的黑石牆，東南西北各留一個門；牆外每 30° 一根石柱，頂上一盞燈籠。</li>
 * </ul>
 * 每 tick 放一點方塊，不會卡伺服器；蓋好之後把中心與半徑記在 world_event_arena.txt。
 */
final class ArenaBuilder {

    private static final int CLEAR = 16;
    private static final int BASE = 4;
    private static final int PER_TICK = 6000;

    private record Placement(int x, int y, int z, Material material) {
    }

    private final ExileCore plugin;
    private final CommandSender sender;
    private final World world;
    private final int cx;
    private final int cy;
    private final int cz;
    private final int radius;
    private final Runnable done;
    /** 蓋在虛空的活動世界（2026-10）：牆不留門，牆上面一路到 {@value #CLEAR} 格是看不見的屏障（掉不出去、怪也飛不出去）。 */
    private final boolean enclosed;

    ArenaBuilder(final ExileCore plugin, final CommandSender sender, final Location center, final int radius, final boolean enclosed,
                 final Runnable done) {
        this.enclosed = enclosed;
        this.plugin = plugin;
        this.sender = sender;
        this.world = center.getWorld();
        this.cx = center.getBlockX();
        this.cy = center.getBlockY() - 1; // 地板在腳下那一格
        this.cz = center.getBlockZ();
        this.radius = radius;
        this.done = done;
    }

    void start() {
        final List<Placement> list = plan();
        sender.sendMessage(plugin.text().prefixed(String.format(java.util.Locale.ROOT,
                "<gray>開始蓋場地：半徑 %d 格，%,d 個方塊（每 tick 放 %,d 個）", radius, list.size(), PER_TICK)));
        place(list, 0);
    }

    private List<Placement> plan() {
        final List<Placement> list = new ArrayList<>();
        final int outer = radius + 3;
        for (int dx = -outer; dx <= outer; dx++) {
            for (int dz = -outer; dz <= outer; dz++) {
                final double d = Math.sqrt(dx * dx + dz * dz);
                final int x = cx + dx;
                final int z = cz + dz;
                if (d <= radius + 0.5) {
                    list.add(new Placement(x, cy, z, floor(dx, dz, d)));
                    for (int y = 1; y <= BASE; y++) {
                        list.add(new Placement(x, cy - y, z, null)); // null = 只有空氣、水、岩漿才墊
                    }
                    for (int y = 1; y <= CLEAR; y++) {
                        list.add(new Placement(x, cy + y, z, Material.AIR));
                    }
                } else if (d <= radius + 2.0) {
                    // 外圍的牆（兩格高），四個門
                    final boolean gate = !enclosed && (Math.abs(dx) <= 2 || Math.abs(dz) <= 2);
                    list.add(new Placement(x, cy, z, Material.POLISHED_BLACKSTONE_BRICKS));
                    for (int y = 1; y <= BASE; y++) {
                        list.add(new Placement(x, cy - y, z, null));
                    }
                    for (int y = 1; y <= 2; y++) {
                        list.add(new Placement(x, cy + y, z, gate ? Material.AIR : Material.POLISHED_BLACKSTONE_BRICKS));
                    }
                    for (int y = 3; y <= CLEAR; y++) {
                        list.add(new Placement(x, cy + y, z, enclosed ? Material.BARRIER : Material.AIR));
                    }
                }
            }
        }
        // 牆外的石柱
        for (int i = 0; i < 12; i++) {
            final double angle = Math.toRadians(i * 30 + 15);
            final int x = cx + (int) Math.round(Math.cos(angle) * (radius + 4));
            final int z = cz + (int) Math.round(Math.sin(angle) * (radius + 4));
            for (int y = -BASE; y <= 6; y++) {
                list.add(new Placement(x, cy + y, z, y == 6 ? Material.SEA_LANTERN : Material.POLISHED_DEEPSLATE));
            }
            list.add(new Placement(x, cy + 7, z, Material.LANTERN));
        }
        return list;
    }

    /** 地板的花紋。 */
    private Material floor(final int dx, final int dz, final double d) {
        if (d <= 3.5) {
            return d <= 1.5 ? Material.SEA_LANTERN : Material.CHISELED_QUARTZ_BLOCK;
        }
        final int ringSpacing = Math.max(6, radius / 4);
        final boolean ring = Math.abs(d % ringSpacing) < 1.0 && d < radius - 1;
        final double angle = Math.toDegrees(Math.atan2(dz, dx)) + 360;
        final boolean spoke = Math.abs(((angle + 22.5) % 45) - 22.5) * Math.PI / 180 * d < 0.7;
        if (ring && spoke) {
            return Material.SEA_LANTERN;
        }
        if (ring) {
            return Material.SMOOTH_QUARTZ;
        }
        if (spoke) {
            return Material.DEEPSLATE_TILES;
        }
        return d > radius - 1.5 ? Material.POLISHED_BLACKSTONE : Material.POLISHED_DEEPSLATE;
    }

    private void place(final List<Placement> list, final int from) {
        final int to = Math.min(list.size(), from + PER_TICK);
        for (int i = from; i < to; i++) {
            final Placement p = list.get(i);
            final Block block = world.getBlockAt(p.x(), p.y(), p.z());
            if (p.material() == null) {
                if (block.isPassable() || block.isLiquid()) {
                    block.setType(Material.DEEPSLATE, false);
                }
            } else if (block.getType() != p.material()) {
                block.setType(p.material(), false);
            }
        }
        if (to < list.size()) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> place(list, to), 1);
            return;
        }
        sender.sendMessage(plugin.text().prefixed("<green>場地蓋好了：中心（" + cx + ", " + (cy + 1) + ", " + cz + "），半徑 " + radius
                + " 格。之後的開服活動都會在這裡面發生"));
        done.run();
    }
}
