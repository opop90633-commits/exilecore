package tw.exilecore.skill;

import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.Tags;
import tw.exilecore.stats.StatSheet;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 技能定義 + 等級 + 手上的武器 → 數值引擎用的 {@link SkillProfile}，以及魔力消耗與動作間隔。
 * 純函式，不依賴 Bukkit。
 */
public final class SkillProfiles {

    private SkillProfiles() {
    }

    /**
     * 攻擊技能的標籤會加上武器的標籤（「增加 x% 弓的傷害」才吃得到）；法術不看武器。
     * 法術的基礎傷害 = 等級 1 的傷害 × 需求等級的成長倍率，再依類型比例拆開。
     */
    public static SkillProfile build(final SkillConfig config, final SkillDefinition definition, final int level,
                                     final Set<String> weaponTags) {
        return build(config, definition, level, weaponTags, List.of());
    }

    /** 同上，再帶只對這個技能有效的修飾器（輔助寶石）。 */
    public static SkillProfile build(final SkillConfig config, final SkillDefinition definition, final int level,
                                     final Set<String> weaponTags, final List<Modifier> local) {
        final Set<String> tags = new HashSet<>(definition.tags());
        DamagePacket min = null;
        DamagePacket max = null;
        if (definition.isAttack() && !definition.weaponless()) {
            tags.addAll(weaponTags);
            if (definition.tags().contains(Tags.PROJECTILE) && !definition.tags().contains(Tags.MELEE)) {
                tags.remove(Tags.MELEE); // 擲出去的矛（擲雷槍）是投射物，不吃近戰的加成（跟輔助寶石的判斷一樣）
            }
            if (definition.tags().contains(Tags.MELEE) && definition.tags().contains(Tags.PROJECTILE) && definition.mechanic() != Mechanic.PROJECTILE) {
                // 2026-10-28 照 PoE2（驚雷劈：近戰猛擊＋另外一段投射物）：近戰的那一下不是投射物，不吃投射物的加成；
                // 投射物那一段自己加回投射物標籤（SkillExecutor.thunderBolts）。寶石的標籤照舊有投射物（輔助、+等級照寶石標籤）
                tags.remove(Tags.PROJECTILE);
            }
        } else {
            // 法術；2026-10-27 照 PoE2：不用武器的攻擊（攻擊寫了 damage）也一樣——基礎傷害照技能的表、不帶武器的標籤
            //（加上 weaponless 標籤：DamageCalculator 看到它就用技能自己的基礎傷害、不用武器傷害）
            if (definition.isAttack()) {
                tags.add(DamageCalculator.WEAPONLESS_TAG);
            }
            final double[] range = spellDamage(config, definition, level);
            min = new DamagePacket();
            max = new DamagePacket();
            if (!definition.typedDamage().isEmpty()) {
                // 2026-10-28 照 PoE2（纏蝕箭）：每種傷害照自己的逐級表；這次之前一張加總的表照 damage_types 拆
                for (final Map.Entry<DamageType, int[]> entry : definition.typedDamage().entrySet()) {
                    final double[] typed = tableAt(entry.getValue(), level);
                    min.set(entry.getKey(), typed[0]);
                    max.set(entry.getKey(), typed[1]);
                }
            } else {
                for (final Map.Entry<DamageType, Double> entry : definition.damageTypes().entrySet()) {
                    min.set(entry.getKey(), range[0] * entry.getValue());
                    max.set(entry.getKey(), range[1] * entry.getValue());
                }
            }
        }
        return new SkillProfile(definition.id(), tags, definition.effectiveness().at(level), min, max, definition.conversion(), local);
    }

    /** 法術（與 2026-10-27 起不用武器的攻擊）在這個等級的基礎傷害 [最小, 最大]（拆類型之前）；用武器的攻擊回傳 [0, 0]。 */
    public static double[] spellDamage(final SkillConfig config, final SkillDefinition definition, final int level) {
        if (definition.isAttack() && !definition.weaponless() || definition.damage() == null) {
            return new double[] {0, 0};
        }
        final int[] damage = definition.damage();
        if (definition.damageByLevel()) {
            return tableAt(damage, level);
        }
        final double growth = config.spellGrowthAt(level);
        return new double[] {damage[0] * growth, damage[1] * growth};
    }

    /**
     * 逐級表（攤平的 [等級 1 最小, 等級 1 最大, 等級 2 最小, …]）在這個等級的 [最小, 最大]。
     * 2026-10-26：逐級表（照 PoE2）是每一級的實際傷害，不乘 spell_damage_growth；比表長的等級照最後兩級的比例往外推。
     * 2026-10-28 抽出來給每種傷害各一張表（纏蝕箭）用。
     */
    static double[] tableAt(final int[] damage, final int level) {
        final int levels = damage.length / 2;
        final int clamped = Math.max(1, Math.min(SkillBuild.MAX_EFFECTIVE_LEVEL, level));
        if (levels < 2 || clamped <= levels) {
            final int at = Math.min(clamped, levels) - 1;
            return new double[] {damage[at * 2], damage[at * 2 + 1]};
        }
        final double lastAvg = (damage[levels * 2 - 2] + damage[levels * 2 - 1]) / 2.0;
        final double prevAvg = (damage[levels * 2 - 4] + damage[levels * 2 - 3]) / 2.0;
        final double scale = prevAvg > 0 ? Math.pow(lastAvg / prevAvg, clamped - levels) : 1;
        return new double[] {damage[levels * 2 - 2] * scale, damage[levels * 2 - 1] * scale};
    }

    /** 沒有輔助寶石時的魔力消耗（有輔助的看 {@link SkillBuild#manaCost()}）。 */
    public static int manaCost(final SkillDefinition definition, final int level) {
        return (int) Math.round(definition.mana().at(level) * SkillConfig.manaCostScale()); // 2026-10-27 照站長：魔力消耗砍半
    }

    /** 這個技能兩次之間要幾 tick：攻擊看武器攻速，法術看施法時間與施法速度（輔助寶石疊在屬性表上一起算）。 */
    public static double intervalTicks(final StatSheet sheet, final SkillDefinition definition, final SkillProfile profile) {
        final StatSheet effective = tw.exilecore.stats.DamageCalculator.chargeScaling(profile.effective(sheet), profile.tags());
        // 2026-10-26（疾樁，PoE2 Urgent Totems）：放圖騰的速度只加在「放圖騰」這個動作上
        final double placement = profile.tags().contains(Tags.TOTEM)
                ? Math.max(0.1, 1 + effective.increased(tw.exilecore.stats.SupportKeys.TOTEM_PLACEMENT_SPEED, profile.tags())) : 1.0;
        // 2026-10-26 照 PoE2：「總攻擊／施法時間 +x 秒」（騰躍砸擊 +0.65、隕星 +1…），這段不吃攻擊、施法速度
        final double added = Math.max(0, effective.flat(ADDED_TIME, profile.tags())) * 20;
        final DeploySpec deploy = definition.deploy();
        if (deploy != null && deploy.kind() == DeploySpec.Kind.TOTEM && deploy.place() > 0) {
            // 2026-10-26 照 PoE2（Path of Building 的 TotemPlacementTime）：放圖騰的時間 = 寶石的施放時間 ÷ 放圖騰的速度（增加 × 更多），
            // 不吃攻擊速度、施法速度；這次之前攻擊圖騰照武器攻速 × 技能攻速（崩震圖騰 50% → 拿 1.2 攻速的錘要 1.67 秒）、法術圖騰照施法速度
            final double placementSpeed = Math.max(0.1, effective.apply(tw.exilecore.stats.SupportKeys.TOTEM_PLACEMENT_SPEED, 1.0, profile.tags()));
            return SkillMath.castInterval(deploy.place(), placementSpeed) + added;
        }
        if (definition.isAttack()) {
            return SkillMath.attackInterval(DamageCalculator.attacksPerSecond(effective, profile.tags()), definition.attackSpeed()) / placement + added;
        }
        if (definition.warcry() != null && definition.warcry().cry()) {
            // 2026-10-26 照 PoE2：戰吼的使用時間只吃戰吼速度（不吃施法速度）
            return SkillMath.castInterval(definition.castTime(), warcrySpeed(effective, profile.tags())) + added;
        }
        // 2026-10-28 照 PoE2（base_spell_cast_time_ms：游雷冰灌注「施法時間 +0.5 秒」）：加在基礎施法時間上，照樣吃施法速度
        final double castTime = definition.castTime() + Math.max(0, effective.flat(BASE_CAST_TIME, profile.tags()));
        return SkillMath.castInterval(castTime, DamageCalculator.castSpeedMultiplier(effective, profile.tags())) / placement + added;
    }

    /** 2026-10-26：戰吼速度倍率：（1 + 增加戰吼速度）× 更多。 */
    public static double warcrySpeed(final StatSheet sheet, final Set<String> context) {
        return Math.max(0.1, sheet.apply(tw.exilecore.stats.SupportKeys.WARCRY_SPEED, 1.0, context));
    }

    /** 2026-10-26：技能自己帶的「總攻擊／施法時間 +x 秒」（FLAT，秒；不吃攻擊、施法速度）。 */
    public static final String ADDED_TIME = "skill.added_time";
    /** 2026-10-28 照 PoE2（base_spell_cast_time_ms）：法術的基礎施法時間 +x 秒（FLAT，秒；吃施法速度，跟 {@link #ADDED_TIME} 不一樣）。 */
    public static final String BASE_CAST_TIME = "skill.base_cast_time";
}
