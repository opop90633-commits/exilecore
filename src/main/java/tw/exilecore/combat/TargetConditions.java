package tw.exilecore.combat;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentService;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.TreeConditions;

import java.util.HashSet;
import java.util.Set;

/**
 * 2026-10-26：天賦樹「對某種敵人」的條件（{@link TreeConditions#ENEMY}）：擊中時照目標的狀態算出這一下的條件標籤，
 * 加進技能的情境，帶這些標籤的修飾器（傷害、暴擊率、暴擊加成、獲得額外傷害）才會算進去。
 */
public final class TargetConditions {

    private TargetConditions() {
    }

    /** 目標這一下的狀態（純資料，方便測試）。 */
    public record Target(double distance, boolean blinded, boolean pinned, boolean dazed, boolean hindered, boolean heavyStunned,
                         boolean marked, boolean rare, double lifeFraction, boolean fullyBroken, boolean cursed, boolean frozen,
                         boolean chilled, boolean shocked, boolean electrocuted, boolean ignited, boolean bleeding, boolean poisoned) {
    }

    /** 目標的狀態 → 條件標籤。 */
    public static Set<String> tags(final Target target) {
        final Set<String> tags = new HashSet<>();
        if (target.distance() > TreeConditions.FAR_RANGE) {
            tags.add(TreeConditions.VS_FAR);
        }
        if (target.distance() <= TreeConditions.NEAR_RANGE) {
            tags.add(TreeConditions.VS_NEAR);
        }
        if (target.distance() <= StatKeys.CLOSE_RANGE) {
            tags.add(TreeConditions.VS_CLOSE);
        }
        add(tags, target.blinded(), TreeConditions.VS_BLINDED);
        add(tags, target.pinned() || target.frozen(), TreeConditions.VS_IMMOBILISED); // PoE2：被禁錮＝釘住或凍結
        add(tags, target.dazed(), TreeConditions.VS_DAZED);
        add(tags, target.hindered(), TreeConditions.VS_HINDERED);
        add(tags, target.heavyStunned(), TreeConditions.VS_HEAVY_STUNNED);
        add(tags, target.marked(), TreeConditions.VS_MARKED);
        add(tags, target.rare(), TreeConditions.VS_RARE);
        add(tags, target.lifeFraction() >= 0.999, TreeConditions.VS_FULL_LIFE);
        add(tags, target.lifeFraction() <= TreeConditions.LOW_FRACTION, TreeConditions.VS_LOW_LIFE);
        add(tags, target.fullyBroken(), TreeConditions.VS_BROKEN_ARMOUR);
        add(tags, target.cursed(), TreeConditions.VS_CURSED);
        add(tags, target.frozen(), TreeConditions.VS_FROZEN);
        add(tags, target.chilled(), TreeConditions.VS_CHILLED);
        add(tags, target.shocked(), TreeConditions.VS_SHOCKED);
        add(tags, target.electrocuted(), TreeConditions.VS_ELECTROCUTED);
        add(tags, target.ignited(), TreeConditions.VS_IGNITED);
        add(tags, target.bleeding(), TreeConditions.VS_BLEEDING);
        add(tags, target.poisoned(), TreeConditions.VS_POISONED);
        // PoE2 的 Ailment：點燃、冰緩、凍結、感電、電擊、流血、中毒（暈眩不算）
        add(tags, target.ignited() || target.chilled() || target.frozen() || target.shocked() || target.electrocuted()
                || target.bleeding() || target.poisoned(), TreeConditions.VS_AILMENT);
        // 2026-10-26：元素異常（PoE2 Elemental Ailments：點燃、冰緩、凍結、感電、電擊）
        add(tags, target.ignited() || target.chilled() || target.frozen() || target.shocked() || target.electrocuted(),
                TreeConditions.VS_ELEMENTAL_AILMENT);
        return tags;
    }

    private static void add(final Set<String> tags, final boolean when, final String tag) {
        if (when) {
            tags.add(tag);
        }
    }

    /** 技能多帶這些條件標籤（沒有就原樣）。 */
    public static SkillProfile tag(final SkillProfile skill, final Set<String> tags) {
        return tags.isEmpty() ? skill : skill.derive(skill.id(), tags, Set.of());
    }

    /**
     * 2026-10-26：再多的目標狀態（釘住、曝露、致殘、緩速、元素異常幾種、怪物種類）。
     * family 是怪物的種類標籤（{@link #family}；不是怪物給 null）。
     */
    public record Extra(boolean pinned, boolean exposed, boolean maimed, boolean slowed, int elementalAilments, String family) {
    }

    /** 再多的目標狀態 → 條件標籤（poisoned 是 {@link Target#poisoned()}）。 */
    public static Set<String> extraTags(final Extra extra, final boolean poisoned) {
        final Set<String> tags = new HashSet<>();
        add(tags, extra.pinned(), TreeConditions.VS_PINNED);
        add(tags, extra.exposed(), TreeConditions.VS_EXPOSED);
        add(tags, extra.maimed(), TreeConditions.VS_MAIMED);
        add(tags, extra.slowed(), TreeConditions.VS_SLOWED);
        add(tags, !poisoned, TreeConditions.VS_NOT_POISONED);
        add(tags, extra.elementalAilments() == 0, TreeConditions.VS_NO_ELEMENTAL_AILMENT);
        for (int i = 1; i <= Math.min(5, extra.elementalAilments()); i++) {
            tags.add(TreeConditions.VS_ELEMENTAL_AILMENTS + i);
        }
        if (extra.family() != null) {
            tags.add(extra.family());
        }
        return tags;
    }

    /**
     * 怪物的種類（PoE2 的 Undead／Beast／Demon／Construct／Humanoid）：這裡的怪物是原版生物，照原版的種類對應。
     * 對不上的回傳 null。
     */
    public static String family(final org.bukkit.entity.EntityType type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case ZOMBIE, ZOMBIE_VILLAGER, HUSK, DROWNED, SKELETON, STRAY, BOGGED, WITHER_SKELETON, WITHER, PHANTOM, ZOMBIFIED_PIGLIN, ZOGLIN,
                 SKELETON_HORSE, ZOMBIE_HORSE -> TreeConditions.VS_UNDEAD;
            case BLAZE, GHAST, MAGMA_CUBE, PIGLIN, PIGLIN_BRUTE, HOGLIN, ENDERMAN, ENDERMITE, ENDER_DRAGON, VEX, STRIDER -> TreeConditions.VS_DEMON;
            case IRON_GOLEM, SNOW_GOLEM, SHULKER, BREEZE, CREAKING, GUARDIAN, ELDER_GUARDIAN -> TreeConditions.VS_CONSTRUCT;
            case PILLAGER, VINDICATOR, EVOKER, ILLUSIONER, WITCH, VILLAGER, WANDERING_TRADER, RAVAGER -> TreeConditions.VS_HUMANOID;
            case SPIDER, CAVE_SPIDER, SILVERFISH, WOLF, POLAR_BEAR, PANDA, FOX, CAT, OCELOT, GOAT, BEE, HORSE, LLAMA, TRADER_LLAMA, PIG, COW,
                 MOOSHROOM, SHEEP, CHICKEN, RABBIT, SLIME, CREEPER, AXOLOTL, FROG, SNIFFER, CAMEL, ARMADILLO -> TreeConditions.VS_BEAST;
            default -> null;
        };
    }

    /** 從世界裡讀目標現在的狀態。 */
    public static Set<String> of(final ExileCore plugin, final LivingEntity attacker, final LivingEntity defender, final MonsterData monster,
                                 final Debuffs debuffs, final AilmentService ailments, final long now) {
        final Set<String> tags = basic(plugin, attacker, defender, monster, debuffs, ailments, now);
        final boolean hasAilments = ailments != null;
        final java.util.UUID id = defender.getUniqueId();
        int elemental = 0;
        if (hasAilments) {
            for (final AilmentType type : new AilmentType[] {AilmentType.IGNITE, AilmentType.CHILL, AilmentType.SHOCK}) {
                if (ailments.has(defender, type)) {
                    elemental++;
                }
            }
            if (ailments.isFrozen(defender)) {
                elemental++;
            }
            if (ailments.electrocuted(defender)) {
                elemental++;
            }
        }
        tags.addAll(extraTags(new Extra(debuffs.has(id, "pin", now),
                debuffs.has(id, "exposure.fire", now) || debuffs.has(id, "exposure.cold", now) || debuffs.has(id, "exposure.lightning", now),
                debuffs.has(id, "maim", now),
                hasAilments && ailments.slowed(defender) || debuffs.slow(id, now) > 0,
                elemental, monster == null ? null : family(defender.getType())),
                tags.contains(TreeConditions.VS_POISONED)));
        // 2026-10-27 照 PoE2 天賦樹：身上有切割的敵人、最近進入／離開你的存在範圍的敵人
        if (HitEffects.stacks(debuffs, id, "incision.", now) > 0) {
            tags.add(TreeConditions.VS_INCISION);
        }
        if (plugin.skills() != null && plugin.skills().treeLeftovers() != null) {
            tags.addAll(plugin.skills().treeLeftovers().presenceTags(attacker.getUniqueId(), id, now));
        }
        return tags;
    }

    private static Set<String> basic(final ExileCore plugin, final LivingEntity attacker, final LivingEntity defender, final MonsterData monster,
                                     final Debuffs debuffs, final AilmentService ailments, final long now) {
        final double distance = attacker.getWorld().equals(defender.getWorld())
                ? attacker.getLocation().distance(defender.getLocation()) : Double.MAX_VALUE;
        final AttributeInstance max = defender.getAttribute(Attribute.MAX_HEALTH);
        final double fraction = max == null || max.getValue() <= 0 ? 1 : defender.getHealth() / max.getValue();
        final boolean rare = monster != null && (monster.boss() || monster.rarity() == MonsterRarity.RARE || monster.rarity() == MonsterRarity.UNIQUE);
        final boolean hasAilments = ailments != null;
        return tags(new Target(distance,
                hasAilments && ailments.blinded(defender),
                debuffs.has(defender.getUniqueId(), "pin", now),
                debuffs.has(defender.getUniqueId(), "daze", now),
                hasAilments && ailments.hindered(defender),
                hasAilments && ailments.heavyStunned(defender),
                debuffs.marked(defender.getUniqueId(), now),
                rare, fraction,
                hasAilments && Double.isInfinite(ailments.armourBroken(defender)),
                debuffs.cursed(defender.getUniqueId(), now),
                hasAilments && ailments.isFrozen(defender),
                hasAilments && ailments.has(defender, AilmentType.CHILL),
                hasAilments && ailments.has(defender, AilmentType.SHOCK),
                hasAilments && ailments.electrocuted(defender),
                hasAilments && ailments.has(defender, AilmentType.IGNITE),
                hasAilments && ailments.has(defender, AilmentType.BLEED),
                hasAilments && ailments.has(defender, AilmentType.POISON)));
    }
}
