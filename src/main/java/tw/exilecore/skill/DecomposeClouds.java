package tw.exilecore.skill;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.combat.SkillDamage;
import tw.exilecore.stats.GemQualityKeys;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.TreeMechanicKeys;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 2026-10-28 照 PoE2 0.5 Decompose（腐屍爆；poe2db）：消耗一具屍體，在那裡留下一團易燃的毒雲——
 * 半徑一開始 1.6 格（吃範圍），每秒更多 20%、最多更多 80%；持續 4 秒（吃持續時間，品質每 1% +0.05 秒）；同時最多 6 團（吃 +x 上限，超過時最舊的消失）。
 * 雲裡的敵人中毒，像受到屍體最大生命 10% 的混沌傷害一樣（走一般的中毒，吃中毒效果、持續時間的加成；每秒補一次）。
 * 碰到燃燒的東西（雲裡有點燃中的敵人、碰到點燃地面）或被你的引爆類技能（detonator 標籤，例如骸爆）打到，毒雲炸開：
 * 照目前的半徑造成這個技能的火焰傷害（8% 暴擊率，寫在 skills.yml），毒雲消失。
 * 這次之前腐屍爆是直接引爆屍體造成火焰傷害（跟骸爆一樣，只是沒有屍體生命的部分）。
 */
final class DecomposeClouds implements SkillTargets.Strikeable {

    /** 技能代號。 */
    static final String SKILL = "rotting_corpse_blast";
    /** 基礎持續 4 秒（另外加品質的 +秒，再乘持續時間倍率）。 */
    static final double BASE_SECONDS = 4;
    /** 每秒半徑更多 20%。 */
    static final double GROWTH_PER_SECOND = 0.20;
    /** 半徑最多更多 80%。 */
    static final double GROWTH_MAX = 0.80;
    /** 中毒：像受到屍體最大生命 10% 的混沌傷害。 */
    static final double POISON_PERCENT = 10;
    /** 同時最多幾團（再加 +x 上限）。 */
    static final int BASE_LIMIT = 6;
    /** 每 20 tick 補一次中毒。 */
    static final long POISON_EVERY = 20;
    /** 每 4 tick 看一次有沒有燒起來（點燃中的敵人、點燃地面）。 */
    static final long IGNITE_CHECK_EVERY = 4;

    /** 一團毒雲：corpseLife 是那具屍體的最大生命；sheet 是放的當下這個技能的屬性表（中毒照它算）。 */
    static final class Cloud {
        final Location at;
        final long born;
        final long until;
        final double baseRadius;
        final double poisonBase;
        final SkillBuild build;
        final StatSheet sheet;
        final tw.exilecore.combat.Corpses.Contagion carried;

        Cloud(final Location at, final long born, final long until, final double baseRadius, final double poisonBase, final SkillBuild build,
              final StatSheet sheet, final tw.exilecore.combat.Corpses.Contagion carried) {
            this.at = at;
            this.born = born;
            this.until = until;
            this.baseRadius = baseRadius;
            this.poisonBase = poisonBase;
            this.build = build;
            this.sheet = sheet;
            this.carried = carried;
        }

        double radius(final long now) {
            return DecomposeClouds.radius(baseRadius, now - born);
        }
    }

    private final ExileCore plugin;
    private final SkillTargets targets;
    private final SkillDamage damage;
    private final SkillVisuals visuals;
    private final Map<UUID, Deque<Cloud>> clouds = new HashMap<>();

    DecomposeClouds(final ExileCore plugin, final SkillTargets targets, final SkillDamage damage, final SkillVisuals visuals) {
        this.plugin = plugin;
        this.targets = targets;
        this.damage = damage;
        this.visuals = visuals;
        targets.addStrikeable(this); // 引爆類技能打到毒雲就炸開
    }

    // ---- 純函式（測試用）----

    /** 出現後第 age tick 的半徑：基礎 × (1 + min(80%, 20% × 秒))。 */
    static double radius(final double base, final long age) {
        final double seconds = Math.max(0, age) / 20.0;
        return base * (1 + Math.min(GROWTH_MAX, GROWTH_PER_SECOND * seconds));
    }

    /** 中毒「像受到」的混沌傷害：屍體最大生命的 10%。 */
    static double poisonBase(final double corpseLife) {
        return Math.max(0, corpseLife) * POISON_PERCENT / 100.0;
    }

    /** 持續幾秒（乘持續時間倍率之前）：4 秒 + 品質的 +秒。 */
    static double seconds(final double qualitySeconds) {
        return BASE_SECONDS + Math.max(0, qualitySeconds);
    }

    /** 同時最多幾團：6 + 「+x 上限」。 */
    static int limit(final int bonus) {
        return BASE_LIMIT + Math.max(0, bonus);
    }

    /** 這一 tick（出現後第 age tick）要不要補中毒：出現的那一刻就上，之後每秒。 */
    static boolean poisonDue(final long age) {
        return age >= 0 && age % POISON_EVERY == 0;
    }

    /** 要放新的一團之前：超過上限的最舊的先拿掉（回傳拿掉的）。 */
    static <T> List<T> makeRoom(final Deque<T> mine, final int limit) {
        final List<T> gone = new ArrayList<>();
        while (!mine.isEmpty() && mine.size() >= Math.max(1, limit)) {
            gone.add(mine.pollFirst());
        }
        return gone;
    }

    /** 這個技能是不是腐屍爆（消耗屍體時留下毒雲、不直接爆）。 */
    static boolean is(final SkillBuild build) {
        return build != null && SKILL.equals(build.definition().id());
    }

    // ---- 放 ----

    /** 在屍體的位置留下一團毒雲（屍體已經消耗掉）。 */
    void spawn(final Player owner, final SkillBuild build, final Location at, final double corpseLife,
               final tw.exilecore.combat.Corpses.Contagion carried) {
        if (owner == null || at == null || at.getWorld() == null) {
            return;
        }
        final long now = plugin.getServer().getCurrentTick();
        final StatSheet ownerSheet = plugin.players().sheet(owner);
        final StatSheet sheet = build.profile().effective(ownerSheet);
        final double qualitySeconds = SkillZones.localFlat(build.profile(), GemQualityKeys.DURATION_FLAT);
        final Deque<Cloud> mine = clouds.computeIfAbsent(owner.getUniqueId(), uuid -> new ArrayDeque<>());
        for (final Cloud gone : makeRoom(mine, limit(SkillLimits.bonus(ownerSheet, build.profile())))) {
            Fx.as(owner.getUniqueId(), () -> Fx.spawn(gone.at.getWorld(), Particle.SMOKE, gone.at.clone().add(0, 0.4, 0), 8, 0.3, 0.2, 0.3, 0.01));
        }
        final Location center = at.clone().add(0, 0.1, 0);
        mine.addLast(new Cloud(center, now, now + build.durationTicks(seconds(qualitySeconds)), build.radius(), poisonBase(corpseLife), build,
                sheet, carried));
        center.getWorld().playSound(center, Sound.BLOCK_BREWING_STAND_BREW, SoundCategory.PLAYERS, 0.6f, 0.6f);
        Fx.spawn(center.getWorld(), Particle.SNEEZE, center.clone().add(0, 0.4, 0), 12, build.radius() * 0.4, 0.2, build.radius() * 0.4, 0.02);
    }

    // ---- 每 tick（SkillService，每個玩家）----

    void tick(final Player player, final long now) {
        final Deque<Cloud> mine = clouds.get(player.getUniqueId());
        if (mine == null) {
            return;
        }
        final List<Cloud> boom = new ArrayList<>();
        final List<Cloud> faded = new ArrayList<>();
        for (final Iterator<Cloud> it = mine.iterator(); it.hasNext(); ) {
            final Cloud cloud = it.next();
            if (now >= cloud.until || !cloud.at.getWorld().equals(player.getWorld())) {
                it.remove();
                if (now >= cloud.until && cloud.at.getWorld().equals(player.getWorld())) {
                    faded.add(cloud); // 2026-10-31 照站長（A＋B 統整成只要 A）：時間到自己炸開（原作要被點燃或引爆）
                }
                continue;
            }
            final long age = now - cloud.born;
            final double radius = cloud.radius(now);
            if (now % 3 == 0) {
                final double angle = (now * 0.4) % (Math.PI * 2);
                Fx.spawn(cloud.at.getWorld(), Particle.DUST_COLOR_TRANSITION,
                        cloud.at.clone().add(Math.cos(angle) * radius * 0.5, 0.5, Math.sin(angle) * radius * 0.5), 4, radius * 0.45, 0.3, radius * 0.45, 0,
                        new Particle.DustTransition(Color.fromRGB(0x7a, 0xa8, 0x3a), Color.fromRGB(0x4a, 0x5c, 0x22), 1.4f));
            }
            final boolean poison = poisonDue(age) && cloud.poisonBase > 0 && plugin.ailments() != null;
            final boolean check = age % IGNITE_CHECK_EVERY == 0 && plugin.ailments() != null;
            if (!poison && !check) {
                continue;
            }
            boolean burning = false;
            for (final LivingEntity near : targets.around(cloud.at, radius, player)) {
                if (check && plugin.ailments().has(near, AilmentType.IGNITE)) {
                    burning = true; // 雲裡有點燃中的敵人
                }
                if (poison) {
                    plugin.ailments().poisonFrom(player, near, cloud.poisonBase, cloud.sheet); // 不擊中：像受到屍體生命 10% 的混沌傷害一樣中毒
                }
            }
            if (!burning && check && ElementalGround.burningAt(plugin, cloud.at, radius)) {
                burning = true; // 碰到點燃地面
            }
            if (burning) {
                it.remove();
                boom.add(cloud);
            }
        }
        for (final Cloud cloud : boom) {
            explode(player, cloud, now, true); // 被點燃：更多 50%
        }
        for (final Cloud cloud : faded) {
            explode(player, cloud, now, false); // 時間到：基礎數值
        }
        if (mine.isEmpty()) {
            clouds.remove(player.getUniqueId());
        }
    }

    /** 你的引爆類技能打到的範圍蓋到毒雲（中心或邊緣）：炸開。 */
    @Override
    public void struck(final Player owner, final SkillProfile skill, final Predicate<Location> inside, final Object source) {
        if (owner == null || skill == null || !skill.tags().contains(TreeMechanicKeys.DETONATOR)) {
            return;
        }
        final Deque<Cloud> mine = clouds.get(owner.getUniqueId());
        if (mine == null || mine.isEmpty()) {
            return;
        }
        final long now = plugin.getServer().getCurrentTick();
        final List<Cloud> boom = new ArrayList<>();
        for (final Iterator<Cloud> it = mine.iterator(); it.hasNext(); ) {
            final Cloud cloud = it.next();
            if (overlaps(inside, cloud.at, cloud.radius(now))) {
                it.remove();
                boom.add(cloud);
            }
        }
        for (final Cloud cloud : boom) {
            explode(owner, cloud, now, true); // 被引爆類技能引爆：更多 50%
        }
    }

    /** 範圍有沒有蓋到這團雲：中心或邊緣上的 8 個點有一個在範圍裡。 */
    private static boolean overlaps(final Predicate<Location> inside, final Location center, final double radius) {
        if (inside.test(center)) {
            return true;
        }
        for (int i = 0; i < 8; i++) {
            final double angle = Math.PI / 4 * i;
            if (inside.test(center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius))) {
                return true;
            }
        }
        return false;
    }

    /** 炸開：照目前的半徑造成這個技能的火焰傷害，毒雲消失。 */
    /** enhanced：滿足原作的條件（被點燃、被引爆類技能打到）——更多 {@link Primed#MORE}；時間到自己炸是基礎數值。 */
    private void explode(final Player owner, final Cloud cloud, final long now, final boolean enhanced) {
        final double radius = cloud.radius(now);
        final SkillProfile profile = enhanced ? cloud.build.profile().scaled(Primed.MORE, "被點燃或引爆") : cloud.build.profile();
        if (damage != null && owner.isOnline()) {
            for (final LivingEntity target : targets.around(cloud.at, radius, owner)) {
                if (cloud.carried != null && plugin.ailments() != null) {
                    plugin.ailments().applyContagion(target, cloud.carried); // 2026-10-28 照 PoE2 Contagion：帶著瘟疫的屍體，爆炸擊中時傳出瘟疫
                }
                damage.hit(owner, target, profile, null);
            }
        }
        targets.strike(owner, profile, SkillTargets.sphere(cloud.at, radius), new Object());
        cloud.at.getWorld().playSound(cloud.at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 0.7f, 1.2f);
        tw.exilecore.skill.Fx.disk(cloud.at.getWorld(), Particle.FLAME, cloud.at.clone().add(0, 0.5, 0), 30, radius, 0.3, 0.05);
        visuals.detonate(cloud.build.definition().visual(), cloud.at, radius);
    }

    /** 這個玩家現在有幾團毒雲（測試、除錯用）。 */
    int active(final UUID player) {
        final Deque<Cloud> mine = clouds.get(player);
        return mine == null ? 0 : mine.size();
    }

    /** 玩家離線：清掉他的毒雲。 */
    void clear(final UUID player) {
        clouds.remove(player);
    }
}
