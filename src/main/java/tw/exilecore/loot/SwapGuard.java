package tw.exilecore.loot;

import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.stats.StatKeys;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 防「最後一下換裝」（2026-10-19）：自己的物品稀有度、物品數量變高之後，要<b>連續維持</b> {@link #holdMillis} 才算數；
 * 變低馬上算。所以打 Boss 時穿輸出裝、快死時換上一身稀有度裝，那一下照換之前的低數字掉。
 * <p>
 * 每秒替線上的人記一次（{@link #sample}，從 LootModule 排程），擲掉落時也記一次（{@link LootBonus#personalRarity}）。
 * 剛上線第一次記到的直接算（登入時穿什麼都沒差）。秒數在 drops.yml 的 swap_hold_seconds（0 = 關掉）。
 */
public final class SwapGuard {

    private static volatile long holdMillis = 10_000;
    private static final Map<UUID, Track[]> TRACKS = new ConcurrentHashMap<>();

    private SwapGuard() {
    }

    /** 一種數值（稀有度或數量）的紀錄。 */
    static final class Track {
        /** 已經算數的值。 */
        private double stable = Double.NaN;
        /** 比 stable 高、還在等的值（從 since 起一直至少這麼高）。 */
        private double pending;
        private long since = -1;

        /** 記一次現在的值，回傳現在算數的值（不會比 current 高）。 */
        synchronized double update(final double current, final long now, final long hold) {
            if (Double.isNaN(stable) || hold <= 0) {
                stable = current;
                since = -1;
                return current;
            }
            if (current <= stable) {
                stable = current; // 變低：馬上算
                since = -1;
                return stable;
            }
            if (since < 0) {
                pending = current;
                since = now;
            } else if (current < pending) {
                pending = current; // 降了一點但還比 stable 高：從 since 起一直至少這麼高
            } else if (current > pending) {
                if (now - since >= hold) {
                    stable = pending; // 舊的那段已經撐夠久
                }
                pending = current;
                since = now;
            }
            if (now - since >= hold) {
                stable = pending;
                since = -1;
            }
            return stable;
        }
    }

    public static void setHoldSeconds(final double seconds) {
        holdMillis = Math.max(0, Math.round(seconds * 1000));
    }

    public static long holdMillis() {
        return holdMillis;
    }

    private static Track track(final UUID player, final int index) {
        return TRACKS.computeIfAbsent(player, id -> new Track[] {new Track(), new Track()})[index];
    }

    /** 自己的物品稀有度（屬性表上的，小數）：現在算數的值。 */
    public static double rarity(final UUID player, final double current) {
        return track(player, 0).update(current, System.currentTimeMillis(), holdMillis);
    }

    /** 自己的物品數量（屬性表上的，小數）：現在算數的值。 */
    public static double quantity(final UUID player, final double current) {
        return track(player, 1).update(current, System.currentTimeMillis(), holdMillis);
    }

    /** 每秒：替線上有角色的人各記一次；下線的人的紀錄丟掉（再上線從頭算）。 */
    public static void sample(final ExileCore plugin) {
        if (plugin.players() == null) {
            return;
        }
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            if (!plugin.players().get(player).hasCharacter()) {
                continue;
            }
            final var sheet = plugin.players().sheet(player);
            rarity(player.getUniqueId(), sheet.increased(StatKeys.DROP_RARITY, Set.of()));
            quantity(player.getUniqueId(), sheet.increased(StatKeys.DROP_QUANTITY, Set.of()));
        }
        TRACKS.keySet().removeIf(id -> plugin.getServer().getPlayer(id) == null);
    }
}
