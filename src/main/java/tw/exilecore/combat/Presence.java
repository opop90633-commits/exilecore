package tw.exilecore.combat;

import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 臨在（2026-10，照 PoE2 的 Presence）：你身邊 {@value #BASE_RADIUS} 格內（吃 presence.area）的敵人受到你身上「範圍內的敵人…」的效果：
 * <ul>
 *   <li>presence.resist.火焰／冰冷／閃電／混沌（FLAT）：抗性降低 x%</li>
 *   <li>presence.taken（FLAT）：受到的傷害增加 x%</li>
 *   <li>presence.weaken（FLAT）：造成的傷害更少 x%</li>
 *   <li>presence.slow（FLAT）：移動速度變慢 x%</li>
 *   <li>presence.burn（FLAT）：每秒受到你最大生命 x% 的火焰傷害</li>
 *   <li>presence.gruelling_madness（FLAT）：每秒獲得 x 層殘忍瘋狂（2026-11-12，PoE2 Gruelling Madness，見 {@link #gruellingSlow}）</li>
 * </ul>
 * 每 {@value #PERIOD} tick 檢查一次，效果是一條很短的減益（離開範圍一下子就沒了）。
 */
public final class Presence {

    public static final long PERIOD = 10;
    /**
     * 臨在的基礎半徑（格）。2026-11-12 照站長「改回 4」：照 PoE2（PoB2 base_presence_radius 40 = 4.0 公尺；1 公尺 = 1 格）；這次之前 6。
     */
    public static final double BASE_RADIUS = 4;
    /** 減益留多久（比檢查間隔長一點，走出範圍很快就消失）。 */
    private static final long LINGER = PERIOD + 6;
    public static final String BURN_ID = "presence.burn";

    private final ExileCore plugin;
    private final Debuffs debuffs;
    private final SkillDamage damage;
    private long ticks;
    /** 2026-11-12（殘忍瘋狂）：敵人 → {層數, 上一次獲得的 tick}（誰給的都算同一份，照原作每個目標最多 10 層）。 */
    private final java.util.Map<java.util.UUID, long[]> gruelling = new java.util.HashMap<>();
    static final int GRUELLING_MAX = 10;
    static final long GRUELLING_DECAY_TICKS = 40;
    static final String GRUELLING_ID = "presence.gruelling_madness";
    /** 殘忍瘋狂的減益算誰給的（誰給的都算同一份；不能是 null，別的減益會拿來比）。 */
    private static final java.util.UUID GRUELLING_SOURCE = new java.util.UUID(0L, 0x6772756C6CL);

    /**
     * 2026-11-12 照 PoE2 Gruelling Madness：第一層讓目標移動變慢 15%，之後每多一層減速效力增加 10%（10 層 = 15% × 1.9 = 28.5%）。
     * 2026-11-12 照站長「原作是放大其他減速，能跟原作一樣嗎？」：多的層數也加強目標身上其他的減速（{@link Debuffs#slow}，
     * 倍率 {@link #gruellingFactor}）；這次之前只算殘忍瘋狂自己這一條。純函式。
     */
    static double gruellingSlow(final int stacks) {
        if (stacks <= 0) {
            return 0;
        }
        return GRUELLING_BASE * gruellingFactor(stacks);
    }

    /** 第一層的減速（15%）。 */
    static final double GRUELLING_BASE = 0.15;

    /** 減速效力倍率：1 層 ×1、之後每層 +10%（10 層 ×1.9）。純函式。 */
    static double gruellingFactor(final int stacks) {
        return stacks <= 0 ? 1 : 1 + 0.10 * (Math.min(GRUELLING_MAX, stacks) - 1);
    }

    public Presence(final ExileCore plugin, final Debuffs debuffs, final SkillDamage damage) {
        this.plugin = plugin;
        this.debuffs = debuffs;
        this.damage = damage;
    }

    /** 存在範圍的半徑（格，吃 presence.area）；2026-10-26 抽出來給「存在範圍內的友軍」用。 */
    public static double radius(final StatSheet sheet) {
        return BASE_RADIUS * Math.sqrt(Math.max(0.1, 1 + sheet.increased(StatKeys.PRESENCE_AREA, Set.of())));
    }

    /**
     * 2026-10-28 照 PoE2（神聖烈焰：Enemies in your Presence Resist Elemental Damage based on their Lowest Resistance）：
     * 把火焰、冰冷、閃電抗性都降到三種裡最低的那個（純函式；回傳要疊上去的修飾器，三種一樣就沒有）。
     */
    public static List<Modifier> lowestResist(final StatSheet monster) {
        final DamageType[] types = {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING};
        double min = Double.MAX_VALUE;
        for (final DamageType type : types) {
            min = Math.min(min, monster.get(type.mitigationStat()));
        }
        final List<Modifier> list = new ArrayList<>();
        for (final DamageType type : types) {
            final double diff = min - monster.get(type.mitigationStat());
            if (diff < 0) {
                list.add(Modifier.flat(type.mitigationStat(), diff, "最低抗性"));
            }
        }
        return list;
    }

    /**
     * 2026-11-07 照站長「瓦爾傳奇照原作」（PoE2 Enemies in your Presence have Lightning Resistance equal to yours）：
     * 把怪物的這種抗性改成 value（回傳要疊上去的 FLAT 修飾器；本來就一樣回傳 null）。純函式（測試用）。
     */
    public static Modifier equalResist(final StatSheet monster, final DamageType type, final double value) {
        final double diff = value - monster.get(type.mitigationStat());
        return Math.abs(diff) < 1e-9 ? null : Modifier.flat(type.mitigationStat(), diff, "抗性等於你的");
    }

    public void tick() {
        ticks += PERIOD;
        final boolean burnTick = ticks % 20 == 0;
        final long now = plugin.getServer().getCurrentTick();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final PlayerState state = plugin.players().find(player.getUniqueId()).orElse(null);
            if (state == null || !state.hasCharacter() || state.isDead() || player.isDead()) {
                continue;
            }
            final StatSheet sheet = plugin.players().sheet(player);
            final List<Modifier> mods = new ArrayList<>();
            for (final DamageType type : new DamageType[] {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING, DamageType.CHAOS}) {
                final double resist = sheet.flat(StatKeys.PRESENCE_RESIST + type.tag(), Set.of());
                if (resist != 0) {
                    mods.add(Modifier.flat(type.mitigationStat(), -resist, "存在範圍"));
                }
            }
            if (StatKeys.flag(sheet, tw.exilecore.item.unique.UniqueStats.PRESENCE_EXPOSURE)) {
                // 2026-10-26（焚王獸符）：你身邊的敵人曝露（元素抗性 -20%，吃曝露效果）
                final double exposure = HitEffects.EXPOSURE * Math.max(0, 1 + sheet.increased(StatKeys.EXPOSURE_EFFECT, Set.of()));
                for (final DamageType type : new DamageType[] {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING}) {
                    mods.add(Modifier.flat(type.mitigationStat(), -exposure * tw.exilecore.passive.ConquestEffects.exposureScale(sheet, type), "曝露"));
                }
            }
            // 2026-11-03 照 PoE2 Evergrasping Ring（攫命之環）：存在範圍內的敵人獲得 x% 傷害為額外混沌傷害（代價）
            final double enemyChaos = sheet.flat(tw.exilecore.item.unique.UniqueStats.PRESENCE_ENEMY_EXTRA_CHAOS, Set.of());
            if (enemyChaos > 0) {
                mods.add(Modifier.flat(StatKeys.damageExtra(DamageType.CHAOS), enemyChaos, "攫命之環"));
            }
            final double weaken = sheet.flat(StatKeys.PRESENCE_WEAKEN, Set.of());
            if (weaken > 0) {
                mods.add(Modifier.more(StatKeys.DAMAGE, -Math.min(90, weaken) / 100.0, "存在範圍"));
            }
            final double taken = Math.max(0, sheet.flat(StatKeys.PRESENCE_TAKEN, Set.of())) / 100.0;
            final double slow = Math.max(0, Math.min(90, sheet.flat(StatKeys.PRESENCE_SLOW, Set.of())
                    * Math.max(0, 1 + sheet.increased(StatKeys.SLOW_MAGNITUDE, Set.of())))) / 100.0; // 2026-10-21：吃減速幅度
            final double burn = sheet.flat(StatKeys.PRESENCE_BURN, Set.of());
            final double gruel = burnTick ? sheet.flat(StatKeys.PRESENCE_GRUELLING, Set.of()) : 0; // 2026-11-12：每秒加層
            // 2026-10-28 照 PoE2（神聖烈焰）：存在範圍內的敵人照牠最低的元素抗性抵抗元素傷害
            final boolean lowest = StatKeys.flag(sheet, tw.exilecore.item.unique.UniqueStats.PRESENCE_LOWEST_RESIST);
            // 2026-11-07 照站長「瓦爾傳奇照原作」（多里亞尼的原型）：存在範圍內的敵人閃電抗性等於你的（你的最終閃電抗性，照你的上限）
            final boolean equalLightning = StatKeys.flag(sheet, tw.exilecore.item.unique.VaalKeys.PRESENCE_LIGHTNING_RESIST_EQUAL);
            final double ownLightning = equalLightning ? Math.min(sheet.get(DamageType.LIGHTNING.mitigationStat()),
                    tw.exilecore.stats.DamageCalculator.resistCap(sheet, DamageType.LIGHTNING)) : 0;
            final boolean hasGruelling = sheet.flat(StatKeys.PRESENCE_GRUELLING, Set.of()) > 0;
            if (mods.isEmpty() && taken <= 0 && slow <= 0 && burn <= 0 && !lowest && !equalLightning && !hasGruelling) {
                continue;
            }
            final double radius = radius(sheet);
            final double radiusSquared = radius * radius;
            final World world = player.getWorld();
            final String id = "presence." + player.getUniqueId();
            final List<LivingEntity> burning = new ArrayList<>();
            for (final LivingEntity target : world.getNearbyLivingEntities(player.getLocation(), radius)) {
                if (target instanceof Player || target.isDead() || !target.isValid() || plugin.monsters().data(target).isEmpty()
                        || target.getLocation().distanceSquared(player.getLocation()) > radiusSquared
                        || (plugin.training() != null && !plugin.training().canTarget(target, player))) {
                    continue;
                }
                List<Modifier> targetMods = mods;
                if (lowest) {
                    final var data = plugin.monsters().data(target).orElse(null);
                    final List<Modifier> toLowest = data == null ? List.of() : lowestResist(plugin.monsters().sheet(target, data));
                    if (!toLowest.isEmpty()) {
                        targetMods = new ArrayList<>(mods);
                        targetMods.addAll(toLowest);
                    }
                }
                if (equalLightning) {
                    final var data = plugin.monsters().data(target).orElse(null);
                    final Modifier equal = data == null ? null : equalResist(plugin.monsters().sheet(target, data), DamageType.LIGHTNING, ownLightning);
                    if (equal != null) {
                        targetMods = new ArrayList<>(targetMods);
                        targetMods.add(equal);
                    }
                }
                if (!targetMods.isEmpty() || taken > 0 || slow > 0) {
                    debuffs.apply(target.getUniqueId(), new Debuffs.Debuff(id, player.getUniqueId(), now + LINGER, taken, false, null, targetMods, slow));
                }
                if (burn > 0) {
                    burning.add(target);
                }
                if (gruel > 0) { // 2026-11-12：殘忍瘋狂每秒加層
                    final long[] stack = gruelling.computeIfAbsent(target.getUniqueId(), k -> new long[] {0, now});
                    stack[0] = Math.min(GRUELLING_MAX, stack[0] + Math.round(gruel));
                    stack[1] = now;
                }
            }
            if (burnTick && !burning.isEmpty()) {
                final double amount = state.maxLife() * Math.min(100, burn) / 100.0;
                final SkillProfile profile = new SkillProfile(BURN_ID, Set.of(Tags.AREA, Tags.FIRE), 0, new DamagePacket().set(DamageType.FIRE, amount));
                for (final LivingEntity target : burning) {
                    if (target.isValid() && !target.isDead()) {
                        damage.hit(player, target, profile, null);
                        tw.exilecore.skill.Fx.as(player.getUniqueId(), () -> // 2026-11-02：記得是誰的（特效等級）
                                tw.exilecore.skill.Fx.spawn(world, Particle.FLAME, target.getLocation().add(0, 1, 0), 4, 0.3, 0.4, 0.3, 0.01));
                    }
                }
            }
        }
        tickGruelling(now);
    }

    /** 2026-11-12（殘忍瘋狂）：2 秒沒有新的就少 1 層；還有層數的敵人掛上減速。 */
    private void tickGruelling(final long now) {
        if (gruelling.isEmpty()) {
            return;
        }
        final java.util.Iterator<java.util.Map.Entry<java.util.UUID, long[]>> it = gruelling.entrySet().iterator();
        while (it.hasNext()) {
            final java.util.Map.Entry<java.util.UUID, long[]> entry = it.next();
            final long[] stack = entry.getValue();
            if (now - stack[1] >= GRUELLING_DECAY_TICKS) {
                stack[0]--;
                stack[1] = now;
            }
            final org.bukkit.entity.Entity entity = plugin.getServer().getEntity(entry.getKey());
            if (stack[0] <= 0 || !(entity instanceof LivingEntity target) || !target.isValid() || target.isDead()) {
                it.remove();
                continue;
            }
            debuffs.apply(target.getUniqueId(), new Debuffs.Debuff(GRUELLING_ID, GRUELLING_SOURCE, now + LINGER, 0, false, null, List.of(),
                    gruellingSlow((int) stack[0])));
        }
    }
}
