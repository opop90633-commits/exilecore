package tw.exilecore.combat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.UUID;

/**
 * 剛死的怪物留下的屍體（骸爆用）：記位置與牠的最大生命，過一陣子就沒了。純邏輯，不依賴 Bukkit。
 */
public final class Corpses {

    /** 屍體留幾 tick。 */
    public static final long LIFETIME = 200;
    /** 最多記幾具（太多就忘掉最舊的）。 */
    public static final int MAX = 300;

    /**
     * @param world   世界的 UUID
     * @param maxLife 牠的最大生命（我們的數值）
     * @param until   到哪個 tick 消失
     */
    public record Corpse(UUID world, double x, double y, double z, double maxLife, long until, String type) {
        double distanceSquared(final double px, final double pz) {
            final double dx = x - px;
            final double dz = z - pz;
            return dx * dx + dz * dz;
        }
    }

    private final Deque<Corpse> corpses = new ArrayDeque<>();

    public void add(final UUID world, final double x, final double y, final double z, final double maxLife, final long now) {
        add(world, x, y, z, maxLife, now, null);
    }

    /** type：原版生物種類（2026-10：附身召喚用；Boss 寫 null）。 */
    public void add(final UUID world, final double x, final double y, final double z, final double maxLife, final long now, final String type) {
        if (maxLife <= 0) {
            return;
        }
        corpses.addLast(new Corpse(world, x, y, z, maxLife, now + LIFETIME, type));
        while (corpses.size() > MAX) {
            corpses.removeFirst();
        }
    }

    /** 某個點附近 radius 內（水平距離）最近、還在的屍體；沒有回傳 null。 */
    public Corpse nearest(final UUID world, final double x, final double z, final double radius, final long now) {
        Corpse best = null;
        double bestDistance = radius * radius;
        for (final Corpse corpse : corpses) {
            if (corpse.until() <= now || !corpse.world().equals(world)) {
                continue;
            }
            final double distance = corpse.distanceSquared(x, z);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = corpse;
            }
        }
        return best;
    }

    /** 某個點附近 radius 內（水平距離）還在的屍體（畫記號用）。 */
    public java.util.List<Corpse> near(final UUID world, final double x, final double z, final double radius, final long now) {
        final java.util.List<Corpse> list = new java.util.ArrayList<>();
        for (final Corpse corpse : corpses) {
            if (corpse.until() > now && corpse.world().equals(world) && corpse.distanceSquared(x, z) <= radius * radius) {
                list.add(corpse);
            }
        }
        return list;
    }

    /** 用掉一具。 */
    public void remove(final Corpse corpse) {
        corpses.remove(corpse);
    }

    /** 清掉過期的。 */
    public void expire(final long now) {
        final Iterator<Corpse> iterator = corpses.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().until() <= now) {
                iterator.remove();
            }
        }
    }

    public int size() {
        return corpses.size();
    }
}
