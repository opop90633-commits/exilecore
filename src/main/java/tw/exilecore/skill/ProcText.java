package tw.exilecore.skill;

import tw.exilecore.character.SkillLoadout;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 輔助寶石觸發的專屬技能（磁裂、帶電地、餘震…）的詳細說明（2026-10）：寶石說明在「觸發「X」」那一行下面列出
 * 它實際做什麼——範圍、傷害（1 級與 20 級）、地面持續多久、印記與戰吼的效果。全部照 skills.yml 算，改數值說明會跟著變。
 */
public final class ProcText {

    /** 每個觸發技能都一樣的規則（寶石說明最後列一次）。 */
    public static final String RULES = "觸發的技能用被輔助技能的寶石等級、吃你身上的加成（不吃這個技能掛的其他輔助），"
            + "不花魔力；同一種每 " + ModifierText.number(SupportProcs.COOLDOWN / 20.0) + " 秒最多觸發一次";

    private ProcText() {
    }

    /** 這個修飾器是不是觸發專屬技能的（proc.事件）。 */
    public static boolean isProc(final Modifier modifier) {
        return modifier.stat().startsWith(SupportProcs.PREFIX) && !modifier.tags().isEmpty();
    }

    /** 觸發的那個技能做什麼（一句一句；找不到技能回傳空的）。 */
    public static List<String> details(final String skillId) {
        final SkillConfig config = SkillConfig.current();
        final SkillDefinition d = config == null ? null : config.skill(skillId);
        return d == null ? List.of() : details(config, d);
    }

    static List<String> details(final SkillConfig config, final SkillDefinition d) {
        final List<String> lines = new ArrayList<>();
        if (d.description() != null && !d.description().isBlank()) {
            lines.add(d.description().trim());
        }
        final SkillParams p = d.params();
        final List<String> parts = new ArrayList<>();
        switch (d.mechanic()) {
            case NOVA -> parts.add("在觸發的位置炸開一圈，半徑 " + range(p.radius()) + " 格");
            case ZONE -> {
                final SkillParams.Zone z = p.zone();
                parts.add("在觸發的位置留下一片地面：半徑 " + range(p.radius()) + " 格、持續 " + ModifierText.number(z.duration()) + " 秒"
                        + (z.pull() > 0 ? "，會把敵人往中心拉" : ""));
                parts.add("地面每 " + ModifierText.number(z.interval()) + " 秒打一次，每次是下面傷害的 " + ModifierText.number(z.damage()) + "%"
                        + (z.max() > 0 ? "；同時最多 " + z.max() + " 片" : ""));
            }
            case PROJECTILE -> parts.add("從觸發的位置往四周射出 " + range(p.projectiles()) + " 發投射物"
                    + (p.explode().last() > 0 ? "（打中炸開半徑 " + range(p.explode()) + " 格）" : ""));
            case CHAIN -> parts.add("電向附近的敵人，連鎖 " + range(p.chains()) + " 次");
            case MARK -> {
                final SkillParams.Mark m = p.mark();
                parts.add((m.radius() >= 1 ? "半徑 " + ModifierText.number(m.radius()) + " 格內的敵人" : "那個敵人")
                        + " " + ModifierText.number(m.duration()) + " 秒內：" + String.join("、", markEffects(m)));
            }
            case WARCRY -> parts.add(String.join("；", warcryEffects(d.warcry())));
            default -> {
            }
        }
        final double parent = config.parentDamage(d.id());
        if (parent > 0) {
            parts.add("傷害是觸發它的那一下的 " + ModifierText.number(parent * 100) + "%（那一下的輔助也算）");
        } else if (d.isAttack() && d.dealsDamage()) {
            parts.add("用你的武器攻擊，傷害效益 " + percentRange(d.effectiveness()));
        } else if (!d.isAttack() && d.damage() != null && d.damageTypes() != null && !d.damageTypes().isEmpty()) {
            final double[] low = SkillProfiles.spellDamage(config, d, 1);
            final double[] high = SkillProfiles.spellDamage(config, d, SkillLoadout.MAX_LEVEL);
            parts.add("基礎" + types(d.damageTypes()) + "傷害 " + Math.round(low[0]) + "～" + Math.round(low[1]) + "（1 級）、"
                    + Math.round(high[0]) + "～" + Math.round(high[1]) + "（" + SkillLoadout.MAX_LEVEL + " 級）");
        }
        for (final Modifier modifier : d.modifiers()) {
            try {
                parts.add(ModifierText.describe(modifier));
            } catch (final RuntimeException ignored) {
                // 寫不出說明的略過
            }
        }
        lines.addAll(parts); // 一件事一行（遊戲裡的說明不會自動換行，擠在一行太長）
        return lines;
    }

    private static List<String> markEffects(final SkillParams.Mark m) {
        final List<String> list = new ArrayList<>();
        if (m.taken() > 0) {
            final String kind = m.tags() == null || m.tags().isEmpty() ? "" : String.join("、", m.tags().stream().map(ModifierText::tagName).toList());
            list.add("受到的" + kind + "傷害增加 " + ModifierText.number(m.taken()) + "%");
        }
        if (m.slow() != null && m.slow().last() > 0) {
            list.add("移動速度減少 " + range(m.slow()) + "%");
        }
        for (final ScaledMod mod : m.effects()) {
            list.add(scaled(mod));
        }
        return list;
    }

    private static List<String> warcryEffects(final WarcrySpec w) {
        final List<String> list = new ArrayList<>();
        for (final Map.Entry<String, Integer> charge : w.charges().entrySet()) {
            list.add("獲得 " + charge.getValue() + " 顆" + tw.exilecore.charge.ChargeText.name(charge.getKey()));
        }
        if (!w.buff().isEmpty()) {
            final List<String> buffs = new ArrayList<>();
            for (final ScaledMod mod : w.buff()) {
                buffs.add(scaled(mod));
            }
            list.add("你 " + ModifierText.number(w.duration()) + " 秒內：" + String.join("、", buffs));
        }
        return list;
    }

    /** 隨等級變的修飾器：寫 1 級的說明，20 級不一樣就在後面補「（20 級 X）」。 */
    private static String scaled(final ScaledMod mod) {
        final String low = mod.describe(1, 1.0).replace("+-", "-");
        final String high = mod.describe(SkillLoadout.MAX_LEVEL, 1.0).replace("+-", "-");
        return low.equals(high) ? low : low + "（" + SkillLoadout.MAX_LEVEL + " 級：" + high + "）";
    }

    private static String types(final Map<DamageType, Double> types) {
        final List<String> names = new ArrayList<>();
        for (final DamageType type : types.keySet()) {
            names.add(type.displayName());
        }
        return String.join("、", names);
    }

    /** 1 級～20 級的值（一樣就寫一個）。 */
    static String range(final Scaled value) {
        if (value == null) {
            return "?";
        }
        final String first = ModifierText.number(value.first());
        final String last = ModifierText.number(value.last());
        return first.equals(last) ? first : first + "～" + last;
    }

    private static String percentRange(final Scaled value) {
        final String first = ModifierText.number(value.first() * 100);
        final String last = ModifierText.number(value.last() * 100);
        return (first.equals(last) ? first : first + "～" + last) + "%";
    }
}
