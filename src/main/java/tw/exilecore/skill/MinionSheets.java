package tw.exilecore.skill;

import tw.exilecore.character.SkillLoadout;
import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 召喚物的屬性表（PoE 的規矩：召喚物有自己的數值，主人只有「召喚物…」的修飾器會給牠們）：
 * <ul>
 *   <li>底是「同等級普通怪物」：等級 = 技能等級對應的需求角色等級（20 級以上每級再加 4），
 *       生命、命中、閃避、護甲、傷害照怪物的曲線，乘 {@link SummonSpec} 的倍率；</li>
 *   <li>主人屬性表裡帶 minion 標籤的修飾器（天賦「增加 x% 召喚物傷害」、裝備、昇華）全部複製過來；</li>
 *   <li>掛在技能上的輔助寶石（兇猛召喚、蠻力…）是技能描述的 local，結算時疊上去。</li>
 * </ul>
 * 查詢召喚物的數值一律帶 {@link #CONTEXT}（含 minion），主人自己的「增加 x% 傷害」沒有 minion 標籤就不會算進來。
 * 純函式，不依賴 Bukkit。
 */
public final class MinionSheets {

    /** 怪物曲線在某個等級的底（遊戲裡由 monsters.yml 給；測試可以直接造）。 */
    public record Base(double life, double accuracy, double evasion, double armour, double damageMin, double damageMax,
                       double critChance) {
    }

    /** 召喚物查屬性時的情境。 */
    public static final Set<String> CONTEXT = Set.of(Tags.MINION);
    /** 召喚物的命中比同等級怪物高一些（怪物的命中是對玩家調的；召喚物打不中會很煩）。 */
    public static final double ACCURACY_BONUS = 1.25;
    /** 20 級以上（裝備的 +技能等級）每多一級，召喚物多幾個怪物等級。 */
    public static final int EXTRA_LEVELS_PER_GEM_LEVEL = 4;

    private MinionSheets() {
    }

    /** 技能等級 → 召喚物當幾級的怪物算：寶石的需求角色等級；超過 20 級每級再加 4。 */
    public static int monsterLevel(final SkillConfig config, final int skillLevel) {
        return monsterLevel(config, skillLevel, 0);
    }

    /**
     * 同上，再照角色等級往上補（2026-10）：寶石的需求等級是一階一階的（18 級要 50、19 級要 64），
     * 以前角色從 50 練到 63 級，召喚物一直停在 50 級怪的強度。現在召喚物至少跟角色同級，
     * 但不超過「下一級寶石的需求等級 − 1」（20 級的寶石沒有上限）——換寶石、升寶石還是有意義。
     * characterLevel ≤ 0 = 不補（舊的算法）。
     */
    public static int monsterLevel(final SkillConfig config, final int skillLevel, final int characterLevel) {
        final int capped = Math.max(1, Math.min(SkillLoadout.MAX_LEVEL, skillLevel));
        final int base = config.requiredLevel(capped);
        if (characterLevel <= 0) {
            return base + Math.max(0, skillLevel - SkillLoadout.MAX_LEVEL) * EXTRA_LEVELS_PER_GEM_LEVEL;
        }
        // 2026-10：超過 20 級的部分不再加怪物等級（以前加在 80 上再跟角色等級取大的，角色 94 級時 +1～+3 完全沒效果；
        // 怪物表到 100 級就封頂），改成 {@link #extraScale} 直接乘生命與傷害
        final int ceiling = capped < SkillLoadout.MAX_LEVEL ? config.requiredLevel(capped + 1) - 1 : Integer.MAX_VALUE;
        return Math.max(base, Math.min(characterLevel, ceiling));
    }

    /**
     * 超過 20 級（裝備的「+x 技能等級」、Lv.21 寶石）每多一級，召喚物的生命與傷害再乘 1 + minion_extra_level_damage
     * （2026-10：預設 5%，比法術的 10% 低——召喚流的「+召喚物技能等級」很好疊，+8 級時 ×1.48，以前 ×2.14）。
     */
    public static double extraScale(final SkillConfig config, final int skillLevel) {
        return skillLevel > SkillLoadout.MAX_LEVEL ? Math.pow(config.minionExtraLevelGrowth(), skillLevel - SkillLoadout.MAX_LEVEL) : 1.0;
    }

    /** 主人屬性表裡會給召喚物的修飾器：帶 minion 標籤的。 */
    public static List<Modifier> ownerModifiers(final StatSheet owner) {
        final List<Modifier> list = new ArrayList<>();
        for (final Modifier modifier : owner.allModifiers()) {
            if (modifier.tags().contains(Tags.MINION)) {
                list.add(modifier);
            }
        }
        return list;
    }

    /** 一隻召喚物的屬性表（還沒疊輔助寶石；疊法見 {@link SkillProfile#effective}）。 */
    public static StatSheet build(final SummonSpec spec, final Base base, final List<Modifier> ownerModifiers) {
        final StatSheet sheet = new StatSheet();
        sheet.setBase(StatKeys.LIFE_MAX, Math.max(1, base.life() * spec.life()));
        sheet.setBase(StatKeys.ACCURACY, base.accuracy() * ACCURACY_BONUS);
        sheet.setBase(StatKeys.EVASION, base.evasion());
        sheet.setBase(StatKeys.ARMOUR, base.armour());
        sheet.setBase(StatKeys.RESIST_MAX, 75);
        sheet.setBase(StatKeys.CRIT_MULTI, 200); // 2026-10 PoE2
        sheet.setBase(StatKeys.WEAPON_APS, spec.attackSpeed());
        sheet.setBase(StatKeys.WEAPON_CRIT, base.critChance());
        for (final Map.Entry<DamageType, Double> entry : spec.resists().entrySet()) {
            sheet.setBase(entry.getKey().mitigationStat(), entry.getValue());
        }
        sheet.add(new Modifier(StatKeys.WEAPON_MIN, ModType.FLAT, base.damageMin() * spec.damage(), Set.of(Tags.PHYSICAL), "召喚物基礎"));
        sheet.add(new Modifier(StatKeys.WEAPON_MAX, ModType.FLAT, base.damageMax() * spec.damage(), Set.of(Tags.PHYSICAL), "召喚物基礎"));
        // 2026-10：給召喚物的修飾器拿掉 minion 標籤再放進召喚物的屬性表：
        // 召喚物被打時抗性、護甲、閃避是用空的標籤讀的，留著 minion 標籤的話「召喚物抗性」「召喚物護甲」這類不會生效
        sheet.addAll(stripMinion(ownerModifiers));
        return sheet;
    }

    /** 拿掉 minion 標籤（放進召喚物自己的屬性表時用：在那裡 minion 標籤已經沒有意義，留著反而讓防禦類讀不到）。 */
    public static List<Modifier> stripMinion(final List<Modifier> modifiers) {
        final List<Modifier> list = new ArrayList<>(modifiers.size());
        for (final Modifier modifier : modifiers) {
            if (modifier.tags().contains(Tags.MINION)) {
                final Set<String> rest = new HashSet<>(modifier.tags());
                rest.remove(Tags.MINION);
                list.add(modifier.withTags(rest));
            } else {
                list.add(modifier);
            }
        }
        return list;
    }

    /**
     * 召喚物打人用的技能描述：代號加 .minion，是近戰攻擊（擲命中、用上面的武器傷害），帶 minion 與技能自己的傷害類型標籤，
     * 物理轉換照技能寫的（焚魂全轉火焰），輔助寶石照技能的。
     */
    public static SkillProfile profile(final SkillBuild build) {
        final SkillProfile base = build.profile();
        final Set<String> tags = new HashSet<>(base.tags());
        tags.remove(Tags.SPELL);
        tags.add(Tags.ATTACK);
        final SummonSpec spec = build.definition().summon();
        tags.add(spec != null && spec.isRanged() ? Tags.PROJECTILE : Tags.MELEE); // 2026-10：遠程召喚物的攻擊算投射物
        tags.add(Tags.MINION);
        return new SkillProfile(base.id() + ".minion", tags, 1.0, null, null, base.conversion(), base.local());
    }

    /** 這隻每秒攻擊幾次（屬性表已經疊好輔助寶石）。 */
    public static double attacksPerSecond(final StatSheet effective, final SkillProfile profile) {
        return Math.max(0.2, Math.min(5, DamageCalculator.attacksPerSecond(effective, profile.tags())));
    }

    /** 兩次攻擊之間幾 tick。 */
    public static int attackTicks(final double attacksPerSecond) {
        return (int) Math.max(4, Math.round(20 / Math.max(0.2, attacksPerSecond)));
    }

    /** 召喚物的最大生命（疊好輔助寶石之後）。 */
    public static double life(final StatSheet effective) {
        return Math.max(1, effective.get(StatKeys.LIFE_MAX, CONTEXT));
    }

    /** 移動速度倍率（1 = 原版該生物的速度 × summon.speed）。 */
    public static double movementScale(final StatSheet effective) {
        return Math.max(0.3, 1 + effective.increased(StatKeys.MOVEMENT_SPEED, CONTEXT)) * effective.more(StatKeys.MOVEMENT_SPEED, CONTEXT);
    }

    /** 每秒回復最大生命的幾 %（堅韌召喚輔助）。 */
    public static double regenPercent(final StatSheet effective) {
        return Math.max(0, effective.flat(StatKeys.LIFE_REGEN_PERCENT, CONTEXT));
    }

    /** 給 /stats：主人身上給召喚物的加成，沒有就是空的。 */
    public static List<String> summary(final StatSheet owner) {
        final List<Modifier> mods = ownerModifiers(owner);
        if (mods.isEmpty()) {
            return List.of();
        }
        final StatSheet filtered = new StatSheet().addAll(mods);
        final Set<String> context = Set.of(Tags.MINION, Tags.ATTACK, Tags.MELEE);
        final List<String> lines = new ArrayList<>();
        final String damage = scaledText(filtered.increased(StatKeys.DAMAGE, context), filtered.more(StatKeys.DAMAGE, context));
        if (damage != null) {
            lines.add("召喚物傷害：" + damage);
        }
        final String life = scaledText(filtered.increased(StatKeys.LIFE_MAX, context), filtered.more(StatKeys.LIFE_MAX, context));
        if (life != null) {
            lines.add("召喚物最大生命：" + life);
        }
        final String speed = scaledText(filtered.increased(StatKeys.ATTACK_SPEED, context), filtered.more(StatKeys.ATTACK_SPEED, context));
        if (speed != null) {
            lines.add("召喚物攻擊速度：" + speed);
        }
        final String move = scaledText(filtered.increased(StatKeys.MOVEMENT_SPEED, context), filtered.more(StatKeys.MOVEMENT_SPEED, context));
        if (move != null) {
            lines.add("召喚物移動速度：" + move);
        }
        final double extra = filtered.flat(StatKeys.SKILL_MINIONS, context);
        if (Math.abs(extra) > 1e-9) {
            lines.add("召喚物數量上限 " + (extra > 0 ? "+" : "") + ModifierText.number(extra));
        }
        final double regen = filtered.flat(StatKeys.LIFE_REGEN_PERCENT, context);
        if (regen > 1e-9) {
            lines.add("召喚物每秒回復 " + ModifierText.number(regen) + "% 最大生命");
        }
        return lines;
    }

    /** 「增加 30%、更多 10%」；都沒有回傳 null。 */
    private static String scaledText(final double increased, final double more) {
        final List<String> parts = new ArrayList<>();
        if (Math.abs(increased) > 1e-9) {
            parts.add((increased > 0 ? "增加 " : "減少 ") + ModifierText.number(Math.abs(increased) * 100) + "%");
        }
        if (Math.abs(more - 1) > 1e-9) {
            parts.add(String.format(Locale.ROOT, "%s %s%%", more > 1 ? "更多" : "更少", ModifierText.number(Math.abs(more - 1) * 100)));
        }
        return parts.isEmpty() ? null : String.join("、", parts);
    }
}
