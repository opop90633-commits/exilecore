package tw.exilecore.clientmod;

import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 2026-11-02 照站長（模組 1.4 B 組）：怪物頭上的血條（{@value WorldHudProtocol#MOBS}）。每 {@link #DEFAULT_INTERVAL} tick 看一次
 * 附近 {@link #DEFAULT_RANGE} 格內的怪（最多 {@link #DEFAULT_MAX} 隻；Boss 一定列、其他照距離），只送有變化的（{@link Tracker}）。
 * 這次之前怪物只有原版名牌（名稱、詞綴、詛咒），看不到血量。
 * 選怪、比對是純函式（測試用）；{@link #collect} 才碰 Bukkit。
 */
public final class MobBarFeed {

    public static final int DEFAULT_INTERVAL = 6;
    public static final double DEFAULT_RANGE = 28;
    public static final int DEFAULT_MAX = 40;
    /** 生命、護盾比例差多少（/255）才重送：持續傷害一點一點掉時不要每次都送。 */
    static final int MIN_STEP = 2;

    private MobBarFeed() {
    }

    /** 一隻候選的怪（距離是平方）。 */
    public record Candidate(WorldHudProtocol.MobBar bar, double distanceSq) {
    }

    /** 挑要送的怪：Boss 先、再照距離近到遠，最多 max 隻、range 格內。 */
    public static List<WorldHudProtocol.MobBar> select(final List<Candidate> candidates, final double range, final int max) {
        final List<Candidate> sorted = new ArrayList<>();
        for (final Candidate candidate : candidates) {
            if (candidate.distanceSq() <= range * range) {
                sorted.add(candidate);
            }
        }
        sorted.sort(Comparator.comparing((Candidate c) -> !c.bar().boss()).thenComparingDouble(Candidate::distanceSq));
        final List<WorldHudProtocol.MobBar> out = new ArrayList<>();
        for (final Candidate candidate : sorted) {
            if (out.size() >= max) {
                break;
            }
            out.add(candidate.bar());
        }
        return out;
    }

    /** 跟上次送的比，值不值得重送（稀有度、Boss 不一樣；生命、護盾差 {@link #MIN_STEP} 以上；到 0 或滿；護盾出現或沒了）。 */
    static boolean changed(final WorldHudProtocol.MobBar before, final WorldHudProtocol.MobBar now) {
        if (before == null) {
            return true;
        }
        if (before.rarity() != now.rarity() || before.boss() != now.boss() || (before.shield() < 0) != (now.shield() < 0)
                || before.invulnerable() != now.invulnerable() || before.marks() != now.marks()) {
            return true;
        }
        return step(before.life(), now.life()) || now.shield() >= 0 && step(before.shield(), now.shield());
    }

    private static boolean step(final int before, final int now) {
        return before != now && (Math.abs(before - now) >= MIN_STEP || now == 0 || now == 255);
    }

    /** 一個玩家上次送了什麼（只在主執行緒用）。 */
    public static final class Tracker {
        private final Map<Integer, WorldHudProtocol.MobBar> sent = new HashMap<>();
        private boolean needFull = true;

        /** 換世界、重打招呼：下一包送完整的（模組清掉舊的）。 */
        public void reset() {
            sent.clear();
            needFull = true;
        }

        /** 這次挑到的怪 → 要送的封包；沒有變化回傳 null。 */
        public byte[] next(final List<WorldHudProtocol.MobBar> selected) {
            final List<WorldHudProtocol.MobBar> changed = new ArrayList<>();
            final Set<Integer> seen = new HashSet<>();
            for (final WorldHudProtocol.MobBar bar : selected) {
                if (!seen.add(bar.entityId())) {
                    continue;
                }
                final WorldHudProtocol.MobBar before = sent.get(bar.entityId());
                if (needFull || changed(before, bar)) {
                    changed.add(bar);
                    sent.put(bar.entityId(), bar);
                }
            }
            final List<Integer> removed = new ArrayList<>();
            sent.keySet().removeIf(id -> {
                if (seen.contains(id)) {
                    return false;
                }
                removed.add(id);
                return true;
            });
            final boolean full = needFull;
            needFull = false;
            if (!full && changed.isEmpty() && removed.isEmpty()) {
                return null;
            }
            return WorldHudProtocol.encodeMobs(full, changed, full ? List.of() : removed);
        }

        /** 測試用：記著的怪有幾隻。 */
        int size() {
            return sent.size();
        }
    }

    // ---- Bukkit ----

    static int rarity(final MonsterRarity rarity) {
        return switch (rarity) {
            case MAGIC -> WorldHudProtocol.RARITY_MAGIC;
            case RARE -> WorldHudProtocol.RARITY_RARE;
            case UNIQUE -> WorldHudProtocol.RARITY_UNIQUE;
            default -> WorldHudProtocol.RARITY_NORMAL;
        };
    }

    /** 詞綴帶來的小圖示（照詞綴 id 算一次記著：光環、替身、回血、一直狂暴的「暴怒的」）。 */
    private static final Map<String, Integer> MOD_MARKS = new java.util.concurrent.ConcurrentHashMap<>();

    /** 重載設定：詞綴的小圖示重算。 */
    public static void clearCache() {
        MOD_MARKS.clear();
    }

    static int modMarks(final tw.exilecore.monster.MonsterConfig.MonsterMod mod) {
        if (mod == null) {
            return 0;
        }
        int marks = "enraged".equals(mod.id()) ? WorldHudProtocol.MARK_ENRAGED : 0;
        if (mod.aura() != null) {
            marks |= WorldHudProtocol.MARK_AURA;
        }
        for (final tw.exilecore.item.AffixMod affix : mod.mods()) {
            switch (affix.stat()) {
                case "monster.invulnerability_aura" -> marks |= WorldHudProtocol.MARK_AURA;
                case "monster.minion_shield" -> marks |= WorldHudProtocol.MARK_PROXY;
                case "monster.regen_percent" -> marks |= WorldHudProtocol.MARK_REGEN;
                default -> {
                }
            }
        }
        return marks;
    }

    /** 這隻現在要標的小圖示：詞綴的（回血只在沒滿血時）、狂躁的狂暴中、詛咒、點燃／冰緩／凍結／感電。 */
    static int marks(final ExileCore plugin, final LivingEntity living, final MonsterData data, final long now, final double maxHealth,
                     final tw.exilecore.monster.MonsterMechanics mechanics) {
        int marks = 0;
        for (final String id : data.mods()) {
            marks |= MOD_MARKS.computeIfAbsent(id, key -> modMarks(plugin.monsters().config().mod(key)));
        }
        if ((marks & WorldHudProtocol.MARK_REGEN) != 0 && living.getHealth() >= maxHealth - 1e-6) {
            marks &= ~WorldHudProtocol.MARK_REGEN; // 滿血沒在回
        }
        if (mechanics != null && mechanics.enragedNow(living.getUniqueId())) {
            marks |= WorldHudProtocol.MARK_ENRAGED;
        }
        final tw.exilecore.ailment.AilmentService ailments = plugin.ailments();
        if (ailments != null) {
            if (!ailments.hexText(living.getUniqueId(), now, false).isEmpty()) {
                marks |= WorldHudProtocol.MARK_CURSED;
            }
            if (ailments.has(living, tw.exilecore.ailment.AilmentType.IGNITE)) {
                marks |= WorldHudProtocol.MARK_IGNITE;
            }
            if (ailments.has(living, tw.exilecore.ailment.AilmentType.CHILL)) {
                marks |= WorldHudProtocol.MARK_CHILL;
            }
            if (ailments.isFrozen(living)) {
                marks |= WorldHudProtocol.MARK_FREEZE;
            }
            if (ailments.has(living, tw.exilecore.ailment.AilmentType.SHOCK)) {
                marks |= WorldHudProtocol.MARK_SHOCK;
            }
        }
        return marks;
    }

    /** 這個人附近的怪（有怪物資料的、活著的、不是玩家）。 */
    public static List<WorldHudProtocol.MobBar> collect(final ExileCore plugin, final Player player, final double range, final int max) {
        if (plugin.monsters() == null) {
            return List.of();
        }
        final Location eye = player.getLocation();
        final List<Candidate> candidates = new ArrayList<>();
        final tw.exilecore.monster.MonsterMechanics mechanics = plugin.monsterModule() == null ? null : plugin.monsterModule().mechanics();
        final long now = plugin.getServer().getCurrentTick();
        for (final LivingEntity living : player.getWorld().getNearbyLivingEntities(eye, range)) {
            if (living instanceof Player || !living.isValid() || living.isDead()) {
                continue;
            }
            final Optional<MonsterData> found = plugin.monsters().data(living);
            if (found.isEmpty()) {
                continue;
            }
            final MonsterData data = found.get();
            final var maxAttribute = living.getAttribute(Attribute.MAX_HEALTH);
            final double maxHealth = maxAttribute == null ? living.getHealth() : maxAttribute.getValue();
            final int life = WorldHudProtocol.fraction255(maxHealth <= 0 ? 0 : living.getHealth() / maxHealth);
            int shield = -1;
            if (mechanics != null) {
                final double[] left = mechanics.shieldLeft(living.getUniqueId());
                if (left != null && left[1] > 0) {
                    shield = WorldHudProtocol.fraction255(left[0] / left[1]);
                }
            }
            final boolean invulnerable = living.isInvulnerable() || mechanics != null && mechanics.sanctuaryProtected(living.getUniqueId())
                    || plugin.maps() != null && plugin.maps().openingInvulnerable(living);
            final int marks = marks(plugin, living, data, now, maxHealth, mechanics);
            candidates.add(new Candidate(new WorldHudProtocol.MobBar(living.getEntityId(), life, shield, rarity(data.rarity()), data.boss(),
                    invulnerable, marks), living.getLocation().distanceSquared(eye)));
        }
        return select(candidates, range, max);
    }
}
