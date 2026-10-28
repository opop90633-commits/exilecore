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

    /**
     * 2026-10-28 照 PoE2 Contagion（瘟疫）：死的時候身上掛著瘟疫的屍體帶著的瘟疫（復活這具屍體的召喚物、引爆它的爆炸擊中時傳出去）。
     *
     * @param source  施放的玩家
     * @param base    第一手的每秒傷害
     * @param spreads 傳出去算第幾手（已經加過這一手；每手更多 100%，最多更多 300%）
     * @param length  持續時間（tick，傳出去照這個重新算）
     * @param radius  死掉時傳染的半徑（格）
     */
    public record Contagion(UUID source, double base, int spreads, long length, double radius) {
    }

    /** 2026-10-28：屍體 → 它帶著的瘟疫（同一個物件；用掉、過期就忘掉）。這次之前屍體不記瘟疫。 */
    private final java.util.Map<Corpse, Contagion> contagions = new java.util.IdentityHashMap<>();

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
            contagions.remove(corpses.removeFirst());
        }
    }

    /**
     * 2026-10-28 照 PoE2 Contagion：剛死在 (x, z) 的那具屍體（最新加的、還在的）帶著瘟疫。找不到屍體回傳 false。
     */
    public boolean markContagion(final UUID world, final double x, final double z, final Contagion contagion, final long now) {
        if (contagion == null) {
            return false;
        }
        final Iterator<Corpse> newest = corpses.descendingIterator();
        while (newest.hasNext()) {
            final Corpse corpse = newest.next();
            if (corpse.until() > now && corpse.world().equals(world) && corpse.distanceSquared(x, z) <= 0.01 * 0.01) {
                contagions.put(corpse, contagion);
                return true;
            }
        }
        return false;
    }

    /** 2026-10-28 照 PoE2 Contagion：這具屍體帶著的瘟疫；沒有回傳 null（用掉之前先拿）。 */
    public Contagion contagion(final Corpse corpse) {
        return corpse == null ? null : contagions.get(corpse);
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
        contagions.remove(corpse);
    }

    /** 清掉過期的。 */
    public void expire(final long now) {
        final Iterator<Corpse> iterator = corpses.iterator();
        while (iterator.hasNext()) {
            final Corpse corpse = iterator.next();
            if (corpse.until() <= now) {
                iterator.remove();
                contagions.remove(corpse);
            }
        }
    }

    public int size() {
        return corpses.size();
    }
}
