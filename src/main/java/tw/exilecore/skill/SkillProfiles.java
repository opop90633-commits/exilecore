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
        if (definition.isAttack()) {
            tags.addAll(weaponTags);
            if (definition.tags().contains(Tags.PROJECTILE) && !definition.tags().contains(Tags.MELEE)) {
                tags.remove(Tags.MELEE); // 擲出去的矛（擲雷槍）是投射物，不吃近戰的加成（跟輔助寶石的判斷一樣）
            }
        } else {
            final double[] range = spellDamage(config, definition, level);
            min = new DamagePacket();
            max = new DamagePacket();
            for (final Map.Entry<DamageType, Double> entry : definition.damageTypes().entrySet()) {
                min.set(entry.getKey(), range[0] * entry.getValue());
                max.set(entry.getKey(), range[1] * entry.getValue());
            }
        }
        return new SkillProfile(definition.id(), tags, definition.effectiveness().at(level), min, max, definition.conversion(), local);
    }

    /** 法術在這個等級的基礎傷害 [最小, 最大]（拆類型之前）；攻擊回傳 [0, 0]。 */
    public static double[] spellDamage(final SkillConfig config, final SkillDefinition definition, final int level) {
        if (definition.isAttack() || definition.damage() == null) {
            return new double[] {0, 0};
        }
        final double growth = config.spellGrowthAt(level);
        return new double[] {definition.damage()[0] * growth, definition.damage()[1] * growth};
    }

    /** 沒有輔助寶石時的魔力消耗（有輔助的看 {@link SkillBuild#manaCost()}）。 */
    public static int manaCost(final SkillDefinition definition, final int level) {
        return (int) Math.round(definition.mana().at(level));
    }

    /** 這個技能兩次之間要幾 tick：攻擊看武器攻速，法術看施法時間與施法速度（輔助寶石疊在屬性表上一起算）。 */
    public static double intervalTicks(final StatSheet sheet, final SkillDefinition definition, final SkillProfile profile) {
        final StatSheet effective = tw.exilecore.stats.DamageCalculator.chargeScaling(profile.effective(sheet), profile.tags());
        if (definition.isAttack()) {
            return SkillMath.attackInterval(DamageCalculator.attacksPerSecond(effective, profile.tags()), definition.attackSpeed());
        }
        return SkillMath.castInterval(definition.castTime(), DamageCalculator.castSpeedMultiplier(effective, profile.tags()));
    }
}
