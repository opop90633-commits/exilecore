package tw.exilecore.skill;

import org.bukkit.util.Vector;
import tw.exilecore.stats.SkillProfile;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 2026-10-28 照 PoE2 0.5 Lightning Rod（雷樁矢）：落地炸開之後插在地上的箭。
 * <ul>
 *   <li>留 20 秒（吃技能持續時間），每個玩家同時最多 10 支（多的先拿掉最舊的）。2026-10-28：每個技能各算各的
 *       （雷樁矢、電鍊箭矢最後一次連鎖後插的各 10 支；PoE2 number_of_lightning_rods_allowed 寫在各自的技能上）；這次之前全部合起來算。</li>
 *   <li>閃電技能的連鎖雷束（鏈雷這類連鎖技能的每一跳、雷鳴箭的雷束）可以連鎖到自己的箭上：算一次連鎖，
 *       箭充能 1 秒之後再炸一次（用插下去那一發雷樁矢的傷害）；雷鳴箭的雷束只要 0.1 秒、同一支箭最快每 0.1 秒炸一次。</li>
 *   <li>2026-10-28 照 PoE2：雷雲（Orb of Storms）的連鎖雷也能連到箭上（{@link SkillZones}）；這次之前雷雲只連敵人。
 *       投射物本身的連鎖（連鎖輔助、電鍊箭矢連鎖的次級投射物）不連到箭上：原作說明只寫「連鎖的閃電雷束」，
 *       論壇回報（0.1）也是加了連鎖的閃電投射物連不到；0.4.0 起電鍊箭矢的連鎖是投射物不是雷束，查不到它能連到杖上的資料，照不能。
 *       電鍊箭矢插的杖跟雷樁矢的一樣能被連（PoE2 0.5.4b 修好雷鳴箭連不到電鍊箭矢的杖）。</li>
 *   <li>被連鎖 8 次之後消失（已經在充能的照炸）。</li>
 *   <li>品質：落地時 x% 機率多炸一次（{@link #EXTRA_BURST}）。</li>
 * </ul>
 * 這裡只管狀態（哪幾支、什麼時候炸、什麼時候消失）；傷害、畫面、展示實體由 {@link SkillZones} 做。這次之前雷樁矢只是落下炸一下。
 */
final class LightningRods {

    /** FLAT：雷樁矢落地時多炸一次的機率 %（品質，gem_quality.yml）。 */
    static final String EXTRA_BURST = "lightning_rod.extra_burst";
    /** FLAT：這個技能的雷束連鎖到雷樁矢的箭時，箭充能幾秒就炸（PoE2 雷鳴箭 skill_lightning_rod_burst_delay_ms_override 100）；0 = 照箭自己的。 */
    static final String BURST_DELAY = "lightning_rod.burst_delay";
    /** 雷鳴箭造成的爆發同一支箭最快每 0.1 秒一次（PoE2 0.4.0）。 */
    static final long FAST_GAP_TICKS = 2;
    /** 品質多的那一次爆發：落地後 0.2 秒。 */
    static final long EXTRA_BURST_TICKS = 4;

    /** 一支插在地上的箭。 */
    static final class Rod {
        final UUID owner;
        final UUID world;
        final Vector at;
        final long placed;
        final long until;
        final int maxChains;
        final long chargeTicks;
        final SkillProfile profile;
        final double radius;
        final String visual;
        /** 被連鎖過幾次。 */
        int chained;
        /** 排好要炸的 tick。 */
        final List<Long> pending = new ArrayList<>();
        /** 上一次雷鳴箭排的爆發 tick。 */
        long lastFast = Long.MIN_VALUE / 2;
        /** 連鎖次數用完：不能再被連鎖，排好的炸完就消失。 */
        boolean spent;
        /** 畫面用的展示實體（null = 沒有）。 */
        UUID display;
        /** 2026-10-29 照站長「更明顯」：頭上的字（剩幾秒、還能被連幾次；null = 沒有）。 */
        UUID label;
        /** 2026-10-28：插它的技能（上限各算各的；null = 不分）。 */
        final String skill;

        Rod(final UUID owner, final UUID world, final Vector at, final long placed, final long until, final int maxChains, final long chargeTicks,
            final SkillProfile profile, final double radius, final String visual) {
            this(owner, world, at, placed, until, maxChains, chargeTicks, profile, radius, visual, null);
        }

        Rod(final UUID owner, final UUID world, final Vector at, final long placed, final long until, final int maxChains, final long chargeTicks,
            final SkillProfile profile, final double radius, final String visual, final String skill) {
            this.skill = skill;
            this.owner = owner;
            this.world = world;
            this.at = at;
            this.placed = placed;
            this.until = until;
            this.maxChains = maxChains;
            this.chargeTicks = chargeTicks;
            this.profile = profile;
            this.radius = radius;
            this.visual = visual;
        }

        boolean charging() {
            return !pending.isEmpty();
        }
    }

    /** 2026-10-29：最後幾秒開始閃（tick）。 */
    static final long BLINK_TICKS = 60;

    /**
     * 純函式：2026-10-29 照站長「更明顯」：頭上那行字（MiniMessage）。ticksLeft 剩幾 tick、chainsLeft 還能被連幾次、charging 正在充能；
     * 最後 3 秒紅白交替閃（blinkOn 決定這一刻亮哪個顏色）。
     */
    static String labelText(final long ticksLeft, final int chainsLeft, final boolean charging, final boolean blinkOn) {
        final long seconds = Math.max(0, (ticksLeft + 19) / 20);
        final String colour = charging ? "<aqua>" : ticksLeft <= BLINK_TICKS ? (blinkOn ? "<red>" : "<white>") : "<yellow>";
        return colour + "⚡ 雷霆之杖 <white>" + seconds + " 秒 <gray>・ " + colour + "剩 " + Math.max(0, chainsLeft) + " 次"
                + (charging ? " <aqua>充能中" : "");
    }

    /** 純函式：最後幾秒每 0.25 秒換一次顏色。 */
    static boolean blinkOn(final long now) {
        return (now / 5) % 2 == 0;
    }

    private final List<Rod> rods = new ArrayList<>();

    /** 插一支（同一個玩家同一個技能超過 max 支就先拿掉最舊的，拿掉的交給 removed）。 */
    Rod place(final Rod rod, final int max, final Consumer<Rod> removed) {
        int mine = count(rod.owner, rod.skill);
        for (final Iterator<Rod> it = rods.iterator(); it.hasNext() && mine >= max; ) {
            final Rod old = it.next();
            if (old.owner.equals(rod.owner) && java.util.Objects.equals(old.skill, rod.skill)) {
                it.remove();
                mine--;
                removed.accept(old);
            }
        }
        rods.add(rod);
        return rod;
    }

    /** 品質：落地之後多炸一次（不算連鎖）。 */
    void extraBurst(final Rod rod, final long now) {
        rod.pending.add(now + EXTRA_BURST_TICKS);
    }

    /** owner 的箭裡，from 附近 radius 格內最近、還能被連鎖、不在 exclude、visible 過的那一支。 */
    Rod nearest(final UUID owner, final UUID world, final Vector from, final double radius, final Set<Rod> exclude, final Predicate<Rod> visible) {
        Rod best = null;
        double bestDistance = radius * radius;
        for (final Rod rod : rods) {
            if (rod.spent || !rod.owner.equals(owner) || (world != null && !world.equals(rod.world)) || exclude.contains(rod)) {
                continue;
            }
            final double distance = rod.at.distanceSquared(from);
            if (distance <= bestDistance && (visible == null || visible.test(rod))) {
                best = rod;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** owner 的箭裡，from 附近 radius 格內還能被連鎖的（近的在前）。 */
    List<Rod> around(final UUID owner, final UUID world, final Vector from, final double radius) {
        final List<Rod> list = new ArrayList<>();
        for (final Rod rod : rods) {
            if (!rod.spent && rod.owner.equals(owner) && (world == null || world.equals(rod.world)) && rod.at.distanceSquared(from) <= radius * radius) {
                list.add(rod);
            }
        }
        list.sort(java.util.Comparator.comparingDouble(rod -> rod.at.distanceSquared(from)));
        return list;
    }

    /**
     * 被連鎖到：算一次連鎖、排一次爆發。delaySeconds > 0（雷鳴箭）：充能這麼久、同一支箭最快每 0.1 秒一次；
     * 不然照箭自己的充能時間（1 秒）。連鎖次數用完就不能再被連鎖。回傳有沒有連上（已經用完的回傳 false）。
     */
    boolean chainTo(final Rod rod, final long now, final double delaySeconds) {
        if (rod.spent) {
            return false;
        }
        rod.chained++;
        if (delaySeconds > 0) {
            final long at = Math.max(now + Math.max(1, Math.round(delaySeconds * 20)), rod.lastFast + FAST_GAP_TICKS);
            rod.lastFast = at;
            rod.pending.add(at);
        } else {
            rod.pending.add(now + rod.chargeTicks);
        }
        if (rod.chained >= rod.maxChains) {
            rod.spent = true;
        }
        return true;
    }

    /**
     * 每 tick：keep 不過（主人離線、換世界）或時間到的拿掉；到時間的爆發交給 burst（先全部收好再呼叫，爆發裡再連鎖、再插箭都不會改到正在跑的清單）；
     * 連鎖次數用完、排好的都炸完的拿掉。
     */
    void tick(final long now, final Predicate<Rod> keep, final Consumer<Rod> burst, final Consumer<Rod> removed) {
        if (rods.isEmpty()) {
            return;
        }
        final List<Rod> due = new ArrayList<>();
        final List<Rod> gone = new ArrayList<>();
        for (final Iterator<Rod> it = rods.iterator(); it.hasNext(); ) {
            final Rod rod = it.next();
            if (!keep.test(rod) || now >= rod.until) {
                it.remove();
                gone.add(rod);
                continue;
            }
            for (final Iterator<Long> at = rod.pending.iterator(); at.hasNext(); ) {
                if (at.next() <= now) {
                    at.remove();
                    due.add(rod);
                }
            }
            if (rod.spent && rod.pending.isEmpty()) {
                it.remove();
                gone.add(rod);
            }
        }
        for (final Rod rod : due) {
            burst.accept(rod);
        }
        for (final Rod rod : gone) {
            removed.accept(rod);
        }
    }

    /** 2026-10-28：同一個技能插的幾支。 */
    int count(final UUID owner, final String skill) {
        int mine = 0;
        for (final Rod rod : rods) {
            if (rod.owner.equals(owner) && java.util.Objects.equals(rod.skill, skill)) {
                mine++;
            }
        }
        return mine;
    }

    int count(final UUID owner) {
        int mine = 0;
        for (final Rod rod : rods) {
            if (rod.owner.equals(owner)) {
                mine++;
            }
        }
        return mine;
    }

    List<Rod> all() {
        return rods;
    }

    void forget(final UUID owner, final Consumer<Rod> removed) {
        for (final Iterator<Rod> it = rods.iterator(); it.hasNext(); ) {
            final Rod rod = it.next();
            if (rod.owner.equals(owner)) {
                it.remove();
                removed.accept(rod);
            }
        }
    }

    void clear(final Consumer<Rod> removed) {
        final List<Rod> all = new ArrayList<>(rods);
        rods.clear();
        all.forEach(removed);
    }
}
