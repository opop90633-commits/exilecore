package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentService;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.combat.SkillDamage;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 2026-10-27 照 PoE2 0.5 Snap（噬異爆；這次之前是「準心處炸一團三元素各 1/3 的爆炸、留下隨機元素殘骸」）：
 * <ul>
 *   <li>要對準一個被凍結、感電或點燃的敵人（沒有就不能放，不花魔力、不進冷卻）；用掉那個異常，以牠為中心炸出那種元素的爆炸（半徑 1.6 公尺），
 *       留下那種元素的灌注殘骸（ChargeEffects#dropInfusionRemnant）。</li>
 *   <li>爆炸擴散到範圍內身上有同樣異常的其他敵人（也用掉牠們的異常，延遲 50～250 毫秒）；擴散出去的不再擴散（原作 proliferation depth 2）。</li>
 *   <li>三種元素各有自己的傷害表與暴擊率（冰冷 13%、閃電 11%、火焰 8%）：冰冷用 skills.yml 的 damage，閃電、火焰用 level_modifiers 的
 *       snap.lightning_min／max、snap.fire_min／max。</li>
 *   <li>對自己還在飛的霜鑽施放：用掉那顆霜鑽，炸出更大的冰冷爆炸（3.2 公尺，留下冰冷殘骸）。</li>
 *   <li>從傳奇敵人引爆的爆炸擊中傷害更多 50%。品質：snap.extra_remnant（多一個殘骸的機率）、冷卻回復速度（gem_quality.yml）。</li>
 *   <li>同時有好幾種異常時：照凍結 → 感電 → 點燃（原作資料沒寫順序；這也是每一級傷害最高到最低的順序，等於選最強的）。</li>
 * </ul>
 * 由 SkillExecutor 的 impact 機制接手（玩家、圖騰、陷阱放的都走這裡）；花魔力前的檢查在 {@link #problem}。
 */
final class Snap {

    static final String ID = "snap";
    /** 可以被噬異爆引爆的投射物（PoE2 Frostbolt）。 */
    static final String FROSTBOLT = "frostbolt";
    static final String LIGHTNING_MIN = "snap.lightning_min";
    static final String LIGHTNING_MAX = "snap.lightning_max";
    static final String FIRE_MIN = "snap.fire_min";
    static final String FIRE_MAX = "snap.fire_max";
    /** FLAT 百分比：多留下一個灌注殘骸的機率（品質 0～10%）。 */
    static final String EXTRA_REMNANT = "snap.extra_remnant";
    /** 各元素的基礎暴擊率（PoE2）；skills.yml 的 spell.base_crit 寫的是冰冷的 13。 */
    static final double CRIT_COLD = 13;
    static final double CRIT_LIGHTNING = 11;
    static final double CRIT_FIRE = 8;
    /** 傳奇敵人引爆：擊中傷害更多 50%。 */
    static final double UNIQUE_MORE = 0.5;
    /** 對霜鑽施放：半徑是一般的兩倍（3.2 ÷ 1.6）。 */
    static final double FROSTBOLT_RADIUS = 2.0;
    /** 擴散的延遲（tick）：原作 50 毫秒基礎 + 0～200 毫秒。 */
    static final int SPREAD_BASE_TICKS = 1;
    static final int SPREAD_RANGE_TICKS = 4;
    /** 異常的優先順序（凍結、感電、點燃）。 */
    static final List<AilmentType> PRIORITY = List.of(AilmentType.FREEZE, AilmentType.SHOCK, AilmentType.IGNITE);

    private final ExileCore plugin;
    private final SkillTargets targets;
    private final ProjectileEngine projectiles;
    private final SkillDamage damage;

    Snap(final ExileCore plugin, final SkillTargets targets, final ProjectileEngine projectiles, final SkillDamage damage) {
        this.plugin = plugin;
        this.targets = targets;
        this.projectiles = projectiles;
        this.damage = damage;
    }

    static boolean is(final SkillBuild build) {
        return build != null && ID.equals(build.definition().id());
    }

    /** 異常對應的元素。 */
    static DamageType element(final AilmentType type) {
        return switch (type) {
            case FREEZE -> DamageType.COLD;
            case SHOCK -> DamageType.LIGHTNING;
            case IGNITE -> DamageType.FIRE;
            default -> null;
        };
    }

    /** 灌注殘骸的元素編號（0 火、1 冰、2 電，SkillResources#infusionIndex）。 */
    static int remnantIndex(final DamageType element) {
        return element == DamageType.FIRE ? 0 : element == DamageType.COLD ? 1 : 2;
    }

    static double crit(final DamageType element) {
        return element == DamageType.FIRE ? CRIT_FIRE : element == DamageType.LIGHTNING ? CRIT_LIGHTNING : CRIT_COLD;
    }

    /** 照優先順序挑要用掉的異常；has：身上有沒有。都沒有回傳 null。 */
    static AilmentType pick(final java.util.function.Predicate<AilmentType> has) {
        for (final AilmentType type : PRIORITY) {
            if (has.test(type)) {
                return type;
            }
        }
        return null;
    }

    /**
     * 某種元素的爆炸：基礎傷害換成那種元素自己的 [min, max]、拿掉另外兩種元素的標籤、暴擊率換成那種元素的（base 帶的是冰冷 13%），
     * 傳奇敵人引爆的再更多 50%。輔助、品質、技能自己的修飾器照留。
     */
    static SkillProfile elementProfile(final SkillProfile base, final DamageType element, final double min, final double max, final boolean unique) {
        final DamagePacket low = new DamagePacket().set(element, min);
        final DamagePacket high = new DamagePacket().set(element, max);
        final Set<String> tags = new HashSet<>(base.tags());
        for (final DamageType other : List.of(DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING)) {
            if (other != element) {
                tags.remove(other.tag());
            }
        }
        tags.add(element.tag());
        final List<Modifier> local = new ArrayList<>(base.local());
        final double critOffset = crit(element) - CRIT_COLD;
        if (Math.abs(critOffset) > 1e-9) {
            local.add(Modifier.flat(StatKeys.SPELL_BASE_CRIT, critOffset, "噬異爆"));
        }
        if (unique) {
            local.add(Modifier.more(StatKeys.DAMAGE, UNIQUE_MORE, "噬異爆（傳奇敵人）"));
        }
        return new SkillProfile(base.id(), tags, base.effectiveness(), low, high, base.conversion(), local);
    }

    /** 這一級某種元素的基礎傷害 [min, max]。 */
    double[] range(final SkillBuild build, final DamageType element) {
        if (element == DamageType.COLD) {
            final SkillProfile profile = build.profile();
            final double min = profile.spellDamage() == null ? 0 : profile.spellDamage().get(DamageType.COLD);
            return new double[] {min, profile.spellMax() == null ? min : profile.spellMax().get(DamageType.COLD)};
        }
        final SkillService skills = plugin.skills();
        if (skills == null) {
            return new double[] {0, 0};
        }
        return element == DamageType.LIGHTNING
                ? new double[] {skills.levelValue(ID, LIGHTNING_MIN, build.level()), skills.levelValue(ID, LIGHTNING_MAX, build.level())}
                : new double[] {skills.levelValue(ID, FIRE_MIN, build.level()), skills.levelValue(ID, FIRE_MAX, build.level())};
    }

    // ---- 目標 ----

    private AilmentType ailment(final LivingEntity entity) {
        final AilmentService ailments = plugin.ailments();
        return ailments == null || entity == null ? null : pick(type -> ailments.has(entity, type));
    }

    /** 準心指著（或已經選好的）身上有凍結、感電、點燃的敵人；沒有回傳 null。 */
    private LivingEntity target(final Origin origin, final SkillBuild build, final LivingEntity preferred) {
        if (preferred != null && preferred.isValid() && targets.valid(preferred, origin.owner()) && ailment(preferred) != null) {
            return preferred;
        }
        final LivingEntity aimed = targets.aimed(origin, Math.max(4, build.range()), 1.2);
        return ailment(aimed) != null ? aimed : null;
    }

    private Location frostbolt(final Origin origin, final SkillBuild build, final boolean consume) {
        if (origin.owner() == null || !origin.self()) {
            return null;
        }
        return projectiles.aimedShot(origin.owner().getUniqueId(), FROSTBOLT, origin.eye(), origin.direction(), Math.max(4, build.range()), 1.5, consume);
    }

    /** 花魔力之前的檢查：準心要指著有凍結／感電／點燃的敵人，或自己的霜鑽。 */
    String problem(final Player player, final SkillBuild build, final LivingEntity preferred) {
        final Origin origin = Origin.of(player);
        if (target(origin, build, preferred) != null || frostbolt(origin, build, false) != null) {
            return null;
        }
        return "噬異爆要對準被凍結、感電或點燃的敵人（或自己的霜鑽）";
    }

    // ---- 放出 ----

    void cast(final Origin origin, final SkillBuild build, final LivingEntity preferred) {
        final Player owner = origin.owner();
        if (owner == null || damage == null) {
            return;
        }
        final LivingEntity target = target(origin, build, preferred);
        final Location bolt = frostbolt(origin, build, false);
        if (bolt != null && (target == null || bolt.distanceSquared(origin.eye()) < target.getLocation().distanceSquared(origin.eye()))) {
            frostbolt(origin, build, true); // 用掉那顆霜鑽
            final double[] cold = range(build, DamageType.COLD);
            blast(owner, bolt, build.radius() * FROSTBOLT_RADIUS, elementProfile(build.profile(), DamageType.COLD, cold[0], cold[1], false), DamageType.COLD);
            remnants(owner, build, bolt, DamageType.COLD);
            return;
        }
        if (target == null) {
            return; // 圖騰、陷阱放的：沒有異常中的敵人就落空
        }
        final AilmentType type = ailment(target);
        final Location at = target.getLocation();
        final List<LivingEntity> spread = detonate(owner, build, target, type);
        remnants(owner, build, at, element(type));
        // 擴散：範圍內同樣異常的其他敵人也炸開（不再往外擴散）
        for (final LivingEntity other : spread) {
            final long delay = SPREAD_BASE_TICKS + plugin.random().nextInt(SPREAD_RANGE_TICKS + 1);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (owner.isOnline() && other.isValid() && !other.isDead() && plugin.ailments() != null && plugin.ailments().has(other, type)) {
                    detonate(owner, build, other, type);
                }
            }, delay);
        }
    }

    /** 用掉 source 身上的異常，以牠為中心炸開；回傳範圍內身上也有同樣異常的其他敵人（擴散用）。 */
    private List<LivingEntity> detonate(final Player owner, final SkillBuild build, final LivingEntity source, final AilmentType type) {
        final DamageType element = element(type);
        final boolean unique = plugin.monsters() != null && plugin.monsters().data(source).map(d -> d.rarity() == MonsterRarity.UNIQUE).orElse(false);
        final Location at = source.getLocation();
        final double radius = build.radius();
        final List<LivingEntity> same = new ArrayList<>();
        for (final LivingEntity near : targets.around(at, radius, owner)) {
            if (!near.equals(source) && plugin.ailments().has(near, type)) {
                same.add(near);
            }
        }
        plugin.ailments().cure(source, type); // 用掉異常
        final double[] range = range(build, element);
        blast(owner, at, radius, elementProfile(build.profile(), element, range[0], range[1], unique), element);
        return same;
    }

    private void blast(final Player owner, final Location at, final double radius, final SkillProfile profile, final DamageType element) {
        if (at.getWorld() == null) {
            return;
        }
        for (final LivingEntity victim : targets.around(at, radius, owner)) {
            damage.hit(owner, victim, profile, null);
        }
        final Location mid = at.clone().add(0, 0.8, 0);
        final Particle particle = element == DamageType.FIRE ? Particle.FLAME : element == DamageType.COLD ? Particle.SNOWFLAKE : Particle.ELECTRIC_SPARK;
        final double spread = Math.max(0.3, radius * 0.6);
        Fx.spawn(at.getWorld(), particle, mid, (int) Math.min(80, 20 + radius * 12), spread, 0.5, spread, 0.05);
        Fx.spawn(at.getWorld(), Particle.EXPLOSION, mid, 1, 0, 0, 0, 0);
        final Sound sound = element == DamageType.FIRE ? Sound.ENTITY_BLAZE_SHOOT : element == DamageType.COLD ? Sound.BLOCK_GLASS_BREAK
                : Sound.ENTITY_LIGHTNING_BOLT_IMPACT;
        at.getWorld().playSound(at, sound, SoundCategory.PLAYERS, 0.8f, 1.3f);
    }

    /** 留下那種元素的灌注殘骸（品質：機率多一個）。 */
    private void remnants(final Player owner, final SkillBuild build, final Location at, final DamageType element) {
        final SkillService skills = plugin.skills();
        if (skills == null || skills.chargeEffects() == null) {
            return;
        }
        final tw.exilecore.stats.StatSheet effective = build.profile().effective(plugin.players().sheet(owner));
        final double extra = effective.flat(EXTRA_REMNANT, build.profile().tags());
        final ChargeEffects.RemnantMods mods = ChargeEffects.RemnantMods.of(effective, build.profile().tags()); // 2026-10-27：殘骸輔助
        final int count = 1 + (plugin.random().nextDouble() * 100 < extra ? 1 : 0);
        for (int i = 0; i < count; i++) {
            final double angle = plugin.random().nextDouble() * Math.PI * 2;
            final Location spot = at.clone().add(count > 1 ? Math.cos(angle) * 0.8 : 0, 0, count > 1 ? Math.sin(angle) * 0.8 : 0);
            skills.chargeEffects().dropInfusionRemnant(owner, spot, remnantIndex(element), mods);
        }
    }
}
