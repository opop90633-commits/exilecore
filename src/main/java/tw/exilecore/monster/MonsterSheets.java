package tw.exilecore.monster;

import tw.exilecore.item.AffixMod;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.Map;
import java.util.Set;

/**
 * 用等級、稀有度、生物種類、詞綴做出怪物的屬性表。純函式，可測試。
 */
public final class MonsterSheets {

    /**
     * 原版 MAX_HEALTH 屬性的上限。生命超過的怪物，原版血量只當比例顯示：
     * 原版血 = 我們的生命 × {@link #healthScale}，玩家造成的傷害也乘同一個比例。
     */
    public static final double VANILLA_HEALTH_CAP = 1024.0;

    private MonsterSheets() {
    }

    /**
     * 2026-10-26（PoE2）：怪物效能的倍率（1 = 沒有）：(1 + 增加) × 更多。受到的傷害除以這個（韌性）；
     * 經驗、物品數量多 (倍率 − 1) ÷ 2。
     */
    public static double effectiveness(final StatSheet sheet) {
        return sheet == null ? 1.0 : Math.max(1.0, sheet.apply(StatKeys.EFFECTIVENESS, 1.0, Set.of()));
    }

    /** 我們的生命值對原版血量的比例（生命 ≤ 1024 時為 1）。 */
    public static double healthScale(final double life) {
        return life <= VANILLA_HEALTH_CAP ? 1.0 : VANILLA_HEALTH_CAP / life;
    }

    public static StatSheet build(final MonsterConfig config, final MonsterData data, final String entityType) {
        final StatSheet sheet = new StatSheet();
        final MonsterConfig.RaritySettings rarity = config.rarity(data.rarity());
        final MonsterConfig.TypeSettings type = config.type(entityType);
        final int level = data.level();

        sheet.setBase(StatKeys.LIFE_MAX, config.lifeAt(level) * type.lifeMultiplier() * rarity.lifeMultiplier() * data.lifeMultiplier());
        sheet.setBase(StatKeys.ACCURACY, config.accuracyAt(level));
        sheet.setBase(StatKeys.EVASION, config.evasionAt(level));
        sheet.setBase(StatKeys.ARMOUR, config.armourAt(level));
        sheet.setBase(StatKeys.RESIST_MAX, 75);
        final double resist = config.rarityResist(data.rarity()); // 2026-10-26 照 PoE2：Boss 元素抗性 30%
        if (resist != 0) {
            for (final DamageType element : new DamageType[] {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING}) {
                sheet.setBase(element.mitigationStat(), resist);
            }
        }
        final double curse = config.rarityCurseEffect(data.rarity()); // 2026-10-26 照 PoE2：魔法、稀有、傳奇身上的詛咒效果更少
        if (curse != 0) {
            sheet.add(Modifier.more(StatKeys.CURSE_EFFECT_ON_SELF, curse / 100.0, "稀有度"));
        }
        // 2026-10-26 照 PoE2 0.5：魔法、稀有、傳奇身上的緩速（冰緩、阻礙、癱瘓…）與曝露效果更少（這次之前不分稀有度）
        final double slow = config.raritySlowEffect(data.rarity());
        if (slow != 0) {
            sheet.add(Modifier.more(StatKeys.SLOW_TAKEN, slow / 100.0, "稀有度"));
        }
        final double exposure = config.rarityExposureEffect(data.rarity());
        if (exposure != 0) {
            sheet.add(Modifier.more(tw.exilecore.ailment.AilmentKeys.EXPOSURE_EFFECT_ON_SELF, exposure / 100.0, "稀有度"));
        }
        sheet.setBase(StatKeys.CRIT_MULTI, 130); // PoE2：怪物的暴擊傷害加成只有 +30%
        sheet.setBase(StatKeys.WEAPON_APS, 1.0);
        sheet.setBase(StatKeys.WEAPON_CRIT, config.critChance());

        final double[] damage = config.damageAt(level);
        final double multiplier = type.damageMultiplier() * rarity.damageMultiplier() * data.damageMultiplier();
        final double physMin = damage[0] * multiplier;
        final double physMax = damage[1] * multiplier;
        sheet.add(new Modifier(StatKeys.WEAPON_MIN, ModType.FLAT, physMin, Set.of(Tags.PHYSICAL), "怪物基礎"));
        sheet.add(new Modifier(StatKeys.WEAPON_MAX, ModType.FLAT, physMax, Set.of(Tags.PHYSICAL), "怪物基礎"));

        for (final String modId : data.mods()) {
            final MonsterConfig.MonsterMod mod = config.mod(modId);
            if (mod == null) {
                continue;
            }
            for (final AffixMod affixMod : mod.mods()) {
                sheet.add(affixMod.toModifier(Map.of(), "怪物詞綴 " + mod.name()));
            }
            for (final Map.Entry<DamageType, Double> entry : mod.addedFromPhysical().entrySet()) {
                final Set<String> tags = Set.of(Tags.ATTACK, entry.getKey().tag());
                sheet.add(new Modifier(StatKeys.DAMAGE_ADDED_MIN, ModType.FLAT, physMin * entry.getValue(), tags, "怪物詞綴 " + mod.name()));
                sheet.add(new Modifier(StatKeys.DAMAGE_ADDED_MAX, ModType.FLAT, physMax * entry.getValue(), tags, "怪物詞綴 " + mod.name()));
            }
        }
        return sheet;
    }
}
